//! The backup recovery key saved on this computer. The key lives only in the operating system's secret store
//! (Secret Service / libsecret; inside a Flatpak, the Secret portal), reached through the `SecretStore` trait so
//! tests use an in-memory fake. Only the 64 hex characters are stored. Copies are zeroizing, and nothing here
//! prints, logs or returns the key in an error or status text.
use crate::BackupKey;
use sha2::{Digest, Sha256};
use std::sync::Mutex;
use zeroize::Zeroizing;

/// Prefix of the QR payload the phone and Key Vault show for the key.
const QR_PREFIX: &str = "stackkey1:";

/// Why the secret store failed. Fixed generic texts only: never key material or system error text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StoreError {
    /// No secret store can be reached at all (no Secret Service / portal / D-Bus service).
    Unavailable,
    /// A store was reached but the request failed (locked and not unlocked, denied, bad item, ...).
    Failed(String),
}
impl StoreError {
    pub const UNAVAILABLE_TEXT: &'static str = "The computer's secret store is not available.";
}
impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StoreError::Unavailable => f.write_str(Self::UNAVAILABLE_TEXT),
            StoreError::Failed(text) => f.write_str(text),
        }
    }
}
impl std::error::Error for StoreError {}
impl From<StoreError> for String {
    fn from(error: StoreError) -> String {
        error.to_string()
    }
}

/// Where the saved key lives. Implementations hold at most one key. Errors are short generic texts.
pub trait SecretStore: Send + Sync {
    /// The saved key as 64 lowercase hex characters, if any.
    fn get(&self) -> Result<Option<Zeroizing<String>>, StoreError>;
    /// Saves (replaces) the key; `hex` is 64 lowercase hex characters.
    fn set(&self, hex: &str) -> Result<(), StoreError>;
    /// Removes the saved key; removing nothing is fine.
    fn remove(&self) -> Result<(), StoreError>;
    /// True when the store is locked. Never asks the user to unlock it.
    fn is_locked(&self) -> Result<bool, StoreError>;
}

/// Reads a key from pasted or typed text: 64 hex characters, optionally with the `stackkey1:` prefix, and with
/// white space and dashes (grouped keys) ignored. Returns it normalised to lowercase hex, or `None` for anything else.
pub fn parse_key_text(text: &str) -> Option<Zeroizing<String>> {
    let trimmed = text.trim();
    let body = trimmed.strip_prefix(QR_PREFIX).unwrap_or(trimmed);
    let hex: Zeroizing<String> = Zeroizing::new(
        body.chars()
            .filter(|c| !c.is_whitespace() && *c != '-')
            .collect(),
    );
    (hex.len() == 64 && hex.bytes().all(|b| b.is_ascii_hexdigit()))
        .then(|| Zeroizing::new(hex.to_ascii_lowercase()))
}

/// The phone's fingerprint of a key: the first 6 bytes of SHA-256 over the lowercase hex text, as 12 hex characters.
pub fn fingerprint(hex: &str) -> String {
    let digest = Sha256::digest(hex.to_ascii_lowercase().as_bytes());
    digest[..6].iter().map(|b| format!("{b:02x}")).collect()
}

/// What the Backup key section shows.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum KeyStatus {
    NotSet,
    /// The store is locked; reading it would ask the user to unlock it, so it was not read.
    Locked,
    Saved {
        fingerprint: String,
    },
}

/// Validates pasted text and saves it. Returns the fingerprint to show.
pub fn save_key(store: &dyn SecretStore, text: &str) -> Result<String, String> {
    let hex = parse_key_text(text)
        .ok_or("That is not a Stack backup key (64 characters, 0-9 and a-f).".to_string())?;
    store.set(&hex)?;
    Ok(fingerprint(&hex))
}

/// The saved key ready for opening a backup, if one is saved.
pub fn saved_backup_key(store: &dyn SecretStore) -> Result<Option<BackupKey>, StoreError> {
    Ok(store.get()?.map(|hex| BackupKey::recovery(hex.to_string())))
}

/// The status without prompting: a locked store is reported as `Locked`, not unlocked.
pub fn key_status(store: &dyn SecretStore) -> Result<KeyStatus, String> {
    if store.is_locked()? {
        return Ok(KeyStatus::Locked);
    }
    Ok(match store.get()? {
        Some(hex) => KeyStatus::Saved {
            fingerprint: fingerprint(&hex),
        },
        None => KeyStatus::NotSet,
    })
}

/// An in-memory store for tests.
#[derive(Default)]
pub struct MemoryStore {
    key: Mutex<Option<Zeroizing<String>>>,
    /// When set, every call fails with this text (to test an unavailable secret store).
    pub fail: Mutex<Option<String>>,
    /// When set, every call reports that no secret store can be reached (`StoreError::Unavailable`).
    pub unavailable: std::sync::atomic::AtomicBool,
    /// Pretends the store is locked: `is_locked` says so, and any read or write counts as an unlock.
    pub locked: std::sync::atomic::AtomicBool,
    /// How many times a read or write would have had to unlock the store.
    pub unlocks: std::sync::atomic::AtomicUsize,
}
impl MemoryStore {
    fn check(&self) -> Result<(), StoreError> {
        use std::sync::atomic::Ordering::SeqCst;
        if self.unavailable.load(SeqCst) {
            return Err(StoreError::Unavailable);
        }
        if self.locked.load(SeqCst) {
            self.unlocks.fetch_add(1, SeqCst);
        }
        match self.fail.lock().unwrap_or_else(|e| e.into_inner()).clone() {
            Some(message) => Err(StoreError::Failed(message)),
            None => Ok(()),
        }
    }
}
impl SecretStore for MemoryStore {
    fn get(&self) -> Result<Option<Zeroizing<String>>, StoreError> {
        self.check()?;
        Ok(self
            .key
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref()
            .map(|k| Zeroizing::new(k.to_string())))
    }
    fn set(&self, hex: &str) -> Result<(), StoreError> {
        self.check()?;
        *self.key.lock().unwrap_or_else(|e| e.into_inner()) = Some(Zeroizing::new(hex.to_string()));
        Ok(())
    }
    fn remove(&self) -> Result<(), StoreError> {
        self.check()?;
        *self.key.lock().unwrap_or_else(|e| e.into_inner()) = None;
        Ok(())
    }
    fn is_locked(&self) -> Result<bool, StoreError> {
        match self.fail.lock().unwrap_or_else(|e| e.into_inner()).clone() {
            Some(message) => Err(StoreError::Failed(message)),
            None => Ok(self.locked.load(std::sync::atomic::Ordering::SeqCst)),
        }
    }
}

/// The operating system's secret store, through `oo7`. Each call runs on its own short-lived thread with its own
/// runtime, so it is safe to call from anywhere (including inside another runtime); it blocks, so keep it off the UI thread.
pub struct SystemStore;
const LABEL: &str = "Stack backup recovery key";
const ATTRIBUTES: [(&str, &str); 2] = [
    ("application", "org.stackdocs.desktop"),
    ("purpose", "backup-recovery-key"),
];

fn run<T: Send>(
    unlock: bool,
    work: impl AsyncFnOnce(oo7::Keyring) -> oo7::Result<T> + Send,
) -> Result<T, StoreError> {
    std::thread::scope(|scope| {
        scope
            .spawn(|| {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .map_err(|_| {
                        StoreError::Failed(
                            "The computer's secret store could not be started.".into(),
                        )
                    })?;
                runtime.block_on(async {
                    let keyring = oo7::Keyring::new()
                        .await
                        .map_err(|_| StoreError::Unavailable)?;
                    // A locked store asks the user to unlock it (only when the caller needs the key).
                    if unlock {
                        keyring.unlock().await.map_err(|_| {
                            StoreError::Failed(
                                "The computer's secret store could not be unlocked.".into(),
                            )
                        })?;
                    }
                    work(keyring).await.map_err(|_| {
                        StoreError::Failed(
                            "The computer's secret store refused the request.".into(),
                        )
                    })
                })
            })
            .join()
            .unwrap_or_else(|_| {
                Err(StoreError::Failed(
                    "The computer's secret store failed.".into(),
                ))
            })
    })
}

/// The one saved key among the secrets found: none is `None`; exactly one valid key is `Some`; several items or an
/// item that is not a valid key is an error (a generic text, never key bytes).
fn select_key(secrets: &[Zeroizing<Vec<u8>>]) -> Result<Option<Zeroizing<String>>, StoreError> {
    match secrets {
        [] => Ok(None),
        [one] => std::str::from_utf8(one)
            .ok()
            .and_then(parse_key_text)
            .map(Some)
            .ok_or_else(|| {
                StoreError::Failed("The saved item in the secret store is not a Stack key.".into())
            }),
        _ => Err(StoreError::Failed(
            "The secret store holds more than one Stack key entry.".into(),
        )),
    }
}

impl SecretStore for SystemStore {
    fn get(&self) -> Result<Option<Zeroizing<String>>, StoreError> {
        let secrets = run(true, async |keyring| {
            let mut secrets = Vec::new();
            for item in keyring.search_items(&ATTRIBUTES).await? {
                secrets.push(Zeroizing::new(item.secret().await?.as_bytes().to_vec()));
            }
            Ok(secrets)
        })?;
        select_key(&secrets)
    }
    fn set(&self, hex: &str) -> Result<(), StoreError> {
        let hex = Zeroizing::new(hex.to_string());
        run(true, async move |keyring| {
            // Replace every earlier entry, so exactly one is left.
            keyring.delete(&ATTRIBUTES).await?;
            keyring
                .create_item(LABEL, &ATTRIBUTES, oo7::Secret::text(hex.as_str()), true)
                .await
                .map(|_| ())
        })
    }
    fn remove(&self) -> Result<(), StoreError> {
        run(true, async |keyring| keyring.delete(&ATTRIBUTES).await)
    }
    fn is_locked(&self) -> Result<bool, StoreError> {
        run(false, async |keyring| keyring.is_locked().await)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const KEY: &str = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    #[test]
    fn parses_plain_and_qr_forms_and_refuses_the_rest() {
        assert_eq!(parse_key_text(&format!(" {KEY}\n")).unwrap().as_str(), KEY);
        assert_eq!(
            parse_key_text(&format!("stackkey1:{}", KEY.to_uppercase()))
                .unwrap()
                .as_str(),
            KEY
        );
        for bad in [
            "",
            "abc",
            &KEY[1..],
            &format!("{KEY}0"),
            &format!("x{}", &KEY[1..]),
            &format!("stackkey2:{KEY}"),
            &format!("stackkey1:stackkey1:{KEY}"),
            "ab cd",
        ] {
            assert!(parse_key_text(bad).is_none(), "{bad}");
        }
    }

    #[test]
    fn grouped_and_prefixed_forms_parse() {
        let spaced = KEY
            .as_bytes()
            .chunks(4)
            .map(|c| std::str::from_utf8(c).unwrap())
            .collect::<Vec<_>>();
        for text in [
            spaced.join(" "),
            spaced.join("-"),
            format!("stackkey1:{}", spaced.join(" - ")),
            format!("  {}\n", spaced.join("\n")),
        ] {
            assert_eq!(parse_key_text(&text).unwrap().as_str(), KEY, "{text}");
        }
    }

    #[test]
    fn several_items_or_a_non_hex_item_is_an_error_without_key_bytes() {
        let item = |t: &str| Zeroizing::new(t.as_bytes().to_vec());
        assert_eq!(select_key(&[]), Ok(None));
        assert_eq!(select_key(&[item(KEY)]).unwrap().unwrap().as_str(), KEY);
        for bad in [
            select_key(&[item(KEY), item(KEY)]),
            select_key(&[item("not a key")]),
            select_key(&[Zeroizing::new(vec![0xff, 0xfe])]),
        ] {
            let message = bad.unwrap_err();
            assert!(
                !message.to_string().contains(KEY) && !message.to_string().contains("not a key")
            );
        }
    }

    #[test]
    fn status_of_a_locked_store_does_not_unlock_it() {
        use std::sync::atomic::Ordering::SeqCst;
        let store = MemoryStore::default();
        store.locked.store(true, SeqCst);
        assert_eq!(key_status(&store).unwrap(), KeyStatus::Locked);
        assert_eq!(store.unlocks.load(SeqCst), 0);
    }

    #[test]
    fn fingerprint_is_twelve_hex_characters_of_the_sha256_of_the_lowercase_key() {
        let f = fingerprint(KEY);
        assert_eq!(f.len(), 12);
        assert_eq!(f, fingerprint(&KEY.to_uppercase()));
        let digest = Sha256::digest(KEY.as_bytes());
        assert_eq!(
            f,
            format!(
                "{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
                digest[0], digest[1], digest[2], digest[3], digest[4], digest[5]
            )
        );
    }

    #[test]
    fn saving_reading_and_removing_a_key_through_a_store() {
        let store = MemoryStore::default();
        assert_eq!(key_status(&store).unwrap(), KeyStatus::NotSet);
        assert!(saved_backup_key(&store).unwrap().is_none());
        assert!(save_key(&store, "nonsense").is_err());
        assert_eq!(key_status(&store).unwrap(), KeyStatus::NotSet);
        let shown = save_key(&store, &format!("stackkey1:{KEY}")).unwrap();
        assert_eq!(
            key_status(&store).unwrap(),
            KeyStatus::Saved { fingerprint: shown }
        );
        assert_eq!(store.get().unwrap().unwrap().as_str(), KEY);
        assert!(saved_backup_key(&store).unwrap().is_some());
        store.remove().unwrap();
        store.remove().unwrap();
        assert_eq!(key_status(&store).unwrap(), KeyStatus::NotSet);
        *store.fail.lock().unwrap() = Some("down".into());
        assert_eq!(key_status(&store), Err("down".into()));
    }

    #[test]
    fn an_unreachable_store_is_told_apart_from_a_failing_one() {
        let store = MemoryStore::default();
        store
            .unavailable
            .store(true, std::sync::atomic::Ordering::SeqCst);
        assert_eq!(
            saved_backup_key(&store).err(),
            Some(StoreError::Unavailable)
        );
        store
            .unavailable
            .store(false, std::sync::atomic::Ordering::SeqCst);
        *store.fail.lock().unwrap() = Some("denied".into());
        assert_eq!(
            saved_backup_key(&store).err(),
            Some(StoreError::Failed("denied".into()))
        );
    }
}
