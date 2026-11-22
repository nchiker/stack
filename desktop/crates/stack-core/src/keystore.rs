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
    /// The store is locked and the caller did not want to be asked to unlock it.
    Locked,
    /// A store was reached but the request failed (denied, bad item, ...).
    Failed(String),
}
impl StoreError {
    pub const UNAVAILABLE_TEXT: &'static str = "The computer's secret store is not available.";
    pub const LOCKED_TEXT: &'static str = "The computer's secret store is locked.";
}
impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StoreError::Unavailable => f.write_str(Self::UNAVAILABLE_TEXT),
            StoreError::Locked => f.write_str(Self::LOCKED_TEXT),
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
    key_status_typed(store).map_err(String::from)
}

/// Like [`key_status`], with the failure kept as a [`StoreError`] so callers can tell the causes apart.
pub fn key_status_typed(store: &dyn SecretStore) -> Result<KeyStatus, StoreError> {
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

/// How long to wait for the secret store to answer (connecting, asking whether it is locked).
const ANSWER_LIMIT: std::time::Duration = std::time::Duration::from_secs(15);
/// How long to wait when the user may have to unlock the store (a prompt is on screen).
const UNLOCK_LIMIT: std::time::Duration = std::time::Duration::from_secs(60);

/// Waits for `work` at most `limit`; a store that never answers becomes `Unavailable`, not a hang.
async fn within<T>(
    limit: std::time::Duration,
    work: impl std::future::Future<Output = Result<T, StoreError>>,
) -> Result<T, StoreError> {
    tokio::time::timeout(limit, work)
        .await
        .unwrap_or(Err(StoreError::Unavailable))
}

/// Where a sandboxed (Flatpak) app keeps its keyring file: the app's own data folder, which is always writable and
/// kept. The launcher points `XDG_DATA_HOME` at the shared library folder, so this must not follow it.
fn sandbox_keyring_path() -> Option<std::path::PathBuf> {
    if !oo7::ashpd::is_sandboxed() {
        return None;
    }
    let id = std::env::var("FLATPAK_ID").ok().filter(|i| !i.is_empty())?;
    let home = std::env::var_os("HOME")
        .map(std::path::PathBuf::from)
        .filter(|p| p.is_absolute())?;
    Some(
        home.join(".var/app")
            .join(id)
            .join("data/keyrings/default.keyring"),
    )
}

/// Where Stack 0.4.0 and earlier kept the same file inside the Flatpak: the launcher then pointed `XDG_DATA_HOME`
/// at the shared library folder (the host's data folder), and the keyring library used `keyrings/v1/` under it.
fn old_sandbox_keyring_path() -> Option<std::path::PathBuf> {
    let base = std::env::var_os("HOST_XDG_DATA_HOME")
        .map(std::path::PathBuf::from)
        .filter(|p| p.is_absolute())
        .or_else(|| {
            std::env::var_os("HOME")
                .map(std::path::PathBuf::from)
                .filter(|p| p.is_absolute())
                .map(|h| h.join(".local/share"))
        })?;
    Some(base.join("keyrings/v1/default.keyring"))
}

/// A saved key from the old place stays usable after an update: when the new file does not exist yet and the old
/// one does, the old file is copied (and left where it is). The file is encrypted and is never read here.
fn copy_old_keyring(old: &std::path::Path, new: &std::path::Path) {
    if new.exists() || !old.is_file() {
        return;
    }
    let Some(parent) = new.parent() else { return };
    let Ok(mut tmp) = tempfile::NamedTempFile::new_in(parent) else {
        return;
    };
    let Ok(mut src) = std::fs::File::open(old) else {
        return;
    };
    if std::io::copy(&mut src, &mut tmp).is_ok() {
        // Never replaces a file that appeared meanwhile.
        let _ = tmp.persist_noclobber(new);
    }
}

/// Opens the keyring. In a Flatpak, the file keyring is opened at an explicit place inside the app's own data
/// folder (the key comes from the Secret portal), so the app and `stack-cli` always use the same file. Elsewhere
/// this is the Secret Service.
async fn open_keyring() -> Result<oo7::Keyring, StoreError> {
    let Some(path) = sandbox_keyring_path() else {
        return oo7::Keyring::new()
            .await
            .map_err(|_| StoreError::Unavailable);
    };
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).map_err(|_| {
            StoreError::Failed("The computer's secret store could not be started.".into())
        })?;
    }
    if let Some(old) = old_sandbox_keyring_path() {
        copy_old_keyring(&old, &path);
    }
    let secret = oo7::ashpd::desktop::secret::retrieve()
        .await
        .map_err(|_| StoreError::Unavailable)?;
    let file = oo7::file::UnlockedKeyring::load(&path, oo7::Secret::from(secret))
        .await
        .map_err(|_| {
            StoreError::Failed("The computer's secret store could not be opened.".into())
        })?;
    Ok(oo7::Keyring::File(std::sync::Arc::new(
        tokio::sync::RwLock::new(Some(oo7::file::Keyring::Unlocked(file))),
    )))
}

fn run<T: Send, E>(
    unlock: bool,
    work: impl AsyncFnOnce(oo7::Keyring) -> Result<T, E> + Send,
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
                    let keyring = within(ANSWER_LIMIT, open_keyring()).await?;
                    // A locked store asks the user to unlock it (only when the caller needs the key); that
                    // prompt, and the work that may trigger one, gets the longer limit.
                    let limit = if unlock { UNLOCK_LIMIT } else { ANSWER_LIMIT };
                    within(limit, async {
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
                    .await
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

/// What `SystemStore::set` needs from a keyring (a trait so the order of steps can be tested with a fake).
trait Ring {
    /// Creates the key item, replacing the one with the same attributes.
    async fn create(&self, hex: &str) -> Result<(), ()>;
    /// Removes every other entry with the same attributes (older duplicates), keeping the one holding `hex`.
    async fn prune_others(&self, hex: &str) -> Result<(), ()>;
}

/// Saves the new key first and only then tidies up, so a failed save leaves the old key in place.
async fn replace_key(ring: &impl Ring, hex: &str) -> Result<(), ()> {
    ring.create(hex).await?;
    ring.prune_others(hex).await
}

/// Which of the found secrets to remove: all but the first one that holds `hex`.
fn others_to_remove(secrets: &[Zeroizing<Vec<u8>>], hex: &str) -> Vec<usize> {
    let keep = secrets.iter().position(|s| s.as_slice() == hex.as_bytes());
    (0..secrets.len()).filter(|i| Some(*i) != keep).collect()
}

struct KeyringRing<'a>(&'a oo7::Keyring);
impl Ring for KeyringRing<'_> {
    async fn create(&self, hex: &str) -> Result<(), ()> {
        self.0
            .create_item(LABEL, &ATTRIBUTES, oo7::Secret::text(hex), true)
            .await
            .map_err(|_| ())
    }
    async fn prune_others(&self, hex: &str) -> Result<(), ()> {
        let items = self.0.search_items(&ATTRIBUTES).await.map_err(|_| ())?;
        let mut secrets = Vec::new();
        for item in &items {
            let secret = item.secret().await.map_err(|_| ())?;
            secrets.push(Zeroizing::new(secret.as_bytes().to_vec()));
        }
        for index in others_to_remove(&secrets, hex) {
            items[index].delete().await.map_err(|_| ())?;
        }
        Ok(())
    }
}

impl SecretStore for SystemStore {
    fn get(&self) -> Result<Option<Zeroizing<String>>, StoreError> {
        let secrets = run(true, async |keyring| {
            let mut secrets = Vec::new();
            for item in keyring.search_items(&ATTRIBUTES).await? {
                secrets.push(Zeroizing::new(item.secret().await?.as_bytes().to_vec()));
            }
            Ok::<_, oo7::Error>(secrets)
        })?;
        select_key(&secrets)
    }
    fn set(&self, hex: &str) -> Result<(), StoreError> {
        let hex = Zeroizing::new(hex.to_string());
        run(true, async move |keyring| {
            replace_key(&KeyringRing(&keyring), &hex).await
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
    fn old_keyring_is_copied_once_and_kept() {
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("old/default.keyring");
        let new = dir.path().join("new/data/default.keyring");
        std::fs::create_dir_all(old.parent().unwrap()).unwrap();
        std::fs::create_dir_all(new.parent().unwrap()).unwrap();
        std::fs::write(&old, b"encrypted-old").unwrap();
        copy_old_keyring(&old, &new);
        assert_eq!(std::fs::read(&new).unwrap(), b"encrypted-old");
        assert!(old.exists());
        // An existing new file is never overwritten.
        std::fs::write(&old, b"changed").unwrap();
        copy_old_keyring(&old, &new);
        assert_eq!(std::fs::read(&new).unwrap(), b"encrypted-old");
        // Missing old file: nothing happens.
        let none = dir.path().join("none.keyring");
        let other = dir.path().join("new/data/other.keyring");
        copy_old_keyring(&none, &other);
        assert!(!other.exists());
    }

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

    #[test]
    fn a_store_that_never_answers_gives_up_with_unavailable() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let started = std::time::Instant::now();
        let result: Result<(), StoreError> = runtime.block_on(within(
            std::time::Duration::from_millis(50),
            std::future::pending(),
        ));
        assert_eq!(result, Err(StoreError::Unavailable));
        assert!(started.elapsed() < std::time::Duration::from_secs(5));
        // An answer in time passes through, including its own error.
        let fast = runtime.block_on(within(std::time::Duration::from_secs(5), async {
            Err::<(), _>(StoreError::Locked)
        }));
        assert_eq!(fast, Err(StoreError::Locked));
        assert!(ANSWER_LIMIT < UNLOCK_LIMIT);
    }

    /// A fake keyring holding one old key; creating can be made to fail.
    struct FakeRing {
        items: Mutex<Vec<String>>,
        fail_create: bool,
        calls: Mutex<Vec<&'static str>>,
    }
    impl Ring for FakeRing {
        async fn create(&self, hex: &str) -> Result<(), ()> {
            self.calls.lock().unwrap().push("create");
            if self.fail_create {
                return Err(());
            }
            *self.items.lock().unwrap() = vec![hex.to_string()];
            Ok(())
        }
        async fn prune_others(&self, _hex: &str) -> Result<(), ()> {
            self.calls.lock().unwrap().push("prune");
            Ok(())
        }
    }

    #[test]
    fn a_failed_create_leaves_the_old_key_and_removes_nothing() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap();
        let old = "ab".repeat(32);
        let ring = FakeRing {
            items: Mutex::new(vec![old.clone()]),
            fail_create: true,
            calls: Mutex::new(Vec::new()),
        };
        assert!(runtime.block_on(replace_key(&ring, KEY)).is_err());
        assert_eq!(*ring.items.lock().unwrap(), vec![old]);
        assert_eq!(*ring.calls.lock().unwrap(), vec!["create"]);
        let ring = FakeRing {
            items: Mutex::new(Vec::new()),
            fail_create: false,
            calls: Mutex::new(Vec::new()),
        };
        runtime.block_on(replace_key(&ring, KEY)).unwrap();
        assert_eq!(*ring.calls.lock().unwrap(), vec!["create", "prune"]);
    }

    #[test]
    fn only_other_entries_are_pruned_and_the_new_key_is_kept() {
        let item = |t: &str| Zeroizing::new(t.as_bytes().to_vec());
        let old = "ab".repeat(32);
        assert_eq!(others_to_remove(&[item(&old), item(KEY)], KEY), vec![0]);
        assert_eq!(others_to_remove(&[item(KEY), item(KEY)], KEY), vec![1]);
        assert_eq!(others_to_remove(&[item(KEY)], KEY), Vec::<usize>::new());
        assert_eq!(others_to_remove(&[item(&old)], KEY), vec![0]);
    }

    #[test]
    fn a_locked_store_reports_locked_text_without_key_material() {
        assert_eq!(StoreError::Locked.to_string(), StoreError::LOCKED_TEXT);
        let store = MemoryStore::default();
        store
            .locked
            .store(true, std::sync::atomic::Ordering::SeqCst);
        assert_eq!(key_status_typed(&store), Ok(KeyStatus::Locked));
        *store.fail.lock().unwrap() = Some("down".into());
        assert_eq!(
            key_status_typed(&store),
            Err(StoreError::Failed("down".into()))
        );
    }
}
