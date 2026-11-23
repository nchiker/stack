use crate::{
    Message, Stack,
    operations::{Kind, RepoOutcome, RepoRequest, TransferOutcome, TransferRequest},
    repository::{Request, Response},
};
use cosmic::{Element, app, dialog::file_chooser, iced, widget};
use stack_archive::Control;
use std::{collections::VecDeque, path::PathBuf, sync::Arc, time::Duration};
use zeroize::Zeroizing;

/// Text typed into a key field. It never prints: messages are Debug-formatted in places.
#[derive(Clone)]
pub struct Typed(pub String);
impl Drop for Typed {
    fn drop(&mut self) {
        zeroize::Zeroize::zeroize(&mut self.0);
    }
}
impl std::fmt::Debug for Typed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Typed(<redacted>)")
    }
}

/// An encrypted backup waiting for its key. Held in memory only (zeroed when dropped), never saved or logged.
pub struct KeyEntry {
    pub(crate) path: PathBuf,
    pub(crate) key: Zeroizing<String>,
    pub(crate) passphrase: Zeroizing<String>,
    pub(crate) error: Option<String>,
    /// "Save this key on this computer" (only used with a recovery key).
    pub(crate) save: bool,
}
impl KeyEntry {
    fn new(path: PathBuf, error: Option<String>) -> Self {
        Self {
            path,
            key: Zeroizing::default(),
            passphrase: Zeroizing::default(),
            error,
            save: false,
        }
    }
}

const NO_SAVED_KEY: &str = "This backup is locked with a key.";
const CLIPBOARD_NOT_A_KEY: &str =
    "The clipboard does not hold a Stack key. In Key Vault, click Copy Stack key.";

/// True when the file starts with the encrypted-backup magic (`STACKEN` and version 1).
fn is_encrypted(path: &std::path::Path) -> bool {
    use std::io::Read;
    let mut magic = [0u8; 8];
    stack_archive::read_regular(path)
        .and_then(|mut file| file.read_exact(&mut magic).map_err(Into::into))
        .is_ok()
        && &magic == stack_archive::crypto::ENVELOPE_MAGIC
}

#[derive(Clone, Debug)]
pub enum Pick {
    Documents,
    Archive,
    ArchiveExport,
    Pdf {
        token: stack_core::library::DocumentToken,
        title: String,
    },
}
#[derive(Clone, Debug)]
pub enum Action {
    Pick(Pick),
    Cancel,
    Commit,
    ReviewAgain,
    Retry,
    RetryCleanup,
    Dismiss,
    KeyTyped(Typed),
    PassphraseTyped(Typed),
    KeySubmit,
    KeyCancel,
    SaveKeyToggled(bool),
    /// The saved key was looked up for an encrypted backup (the number says which request; an old answer is ignored
    /// and its key dropped). `None` means no key is saved.
    SavedKey(u64, Result<Option<Arc<stack_core::BackupKey>>, String>),
    /// "Paste key from Key Vault" on the key panel.
    PasteKey,
    /// The clipboard text for that request number (`None` when it could not be read); never printed.
    ClipboardKey(u64, Option<Typed>),
    /// A paste, or a key entered for a backup, was saved (the fingerprint) or could not be.
    KeySaved(Result<String, String>),
    RemoveKey,
    KeyRemoved(Result<(), String>),
    /// The saved-key status was read (for the Backup key section).
    KeyStatus(Result<stack_core::keystore::KeyStatus, String>),
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Preparing,
    Reading,
    Review,
    Committing,
    Writing,
    Cleaning,
    CleanupNeeded,
}
pub struct Active {
    pub id: String,
    pub kind: Kind,
    pub control: Control,
    pub phase: Phase,
    pub review: Option<stack_core::RestoreReceipt>,
    pub committed: Option<stack_core::RestoreReceipt>,
    pub published: bool,
    /// The backup was opened with a key (which only the worker holds; it is not kept here).
    pub keyed: bool,
    /// A recovery key to save once this backup has opened with it (zeroed when dropped with the transfer).
    pub(crate) pending_save: Option<Zeroizing<String>>,
}
#[derive(Default)]
pub struct Ui {
    pub active: Option<Active>,
    pub picking: bool,
    pub status: Option<String>,
    pub outcomes: Vec<(PathBuf, String)>,
    queue: VecDeque<PathBuf>,
    failed: Vec<PathBuf>,
    retry: Option<Kind>,
    control: Control,
    pub bytes: u64,
    pub key_entry: Option<KeyEntry>,
    /// An encrypted backup waiting for the saved key to be looked up: (request number, file).
    pub lookup: Option<(u64, PathBuf)>,
    lookups: u64,
    /// The current backup was opened with the saved key (so a failure says the saved key was wrong).
    used_saved: bool,
    /// The clipboard read the key panel is waiting for: its request number. Answers for any other number are dropped.
    pub(crate) clip: Option<u64>,
    clips: u64,
    /// A saved-key status read is in flight.
    key_status_reading: bool,
    /// What the Backup key section shows; `None` until read.
    pub key_status: Option<Result<stack_core::keystore::KeyStatus, String>>,
    /// Where the key is saved; `None` means the computer's secret store. Tests set an in-memory store.
    pub store: Option<Arc<dyn stack_core::keystore::SecretStore>>,
}
impl Ui {
    /// `private_store`: smoke runs and demos use an in-memory key store and never touch the computer's real one.
    pub fn new(private_store: bool) -> Self {
        Self {
            store: private_store.then(|| {
                Arc::new(stack_core::keystore::MemoryStore::default())
                    as Arc<dyn stack_core::keystore::SecretStore>
            }),
            ..Default::default()
        }
    }
    pub fn secret_store(&self) -> Arc<dyn stack_core::keystore::SecretStore> {
        self.store
            .clone()
            .unwrap_or_else(|| Arc::new(stack_core::keystore::SystemStore))
    }
    pub fn busy(&self) -> bool {
        self.active.is_some() || self.picking || self.key_entry.is_some() || self.lookup.is_some()
    }
}

impl Stack {
    pub fn transfer_action(&mut self, action: Action) -> app::Task<Message> {
        match action {
            Action::Pick(pick) if !self.transfers.busy() => {
                self.transfers.picking = true;
                let directory = self
                    .flags
                    .browser_fixtures
                    .as_ref()
                    .map(|input| match &pick {
                        Pick::Documents => input.clone(),
                        Pick::Archive => self
                            .flags
                            .smoke
                            .as_ref()
                            .expect("verified smoke")
                            .join("reference"),
                        _ => self.flags.smoke.as_ref().expect("verified smoke").clone(),
                    });
                let caption = self.flags.smoke_title.clone();
                let open = move |owner| {
                    let pick = pick.clone();
                    let directory = directory.clone();
                    let caption = caption.clone();
                    cosmic::task::future(async move {
                        let result = pick_files(pick.clone(), directory, caption, owner).await;
                        Message::Picked(pick, result)
                    })
                };
                if std::env::var_os("STACK_M2_PORTAL_PROTOCOL").is_some() {
                    return open(None);
                }
                return cosmic::iced::runtime::window::owned_handle(iced::window::Id::RESERVED)
                    .then(open);
            }
            Action::Cancel => {
                if let Some(active) = &self.transfers.active {
                    if active.committed.is_some() || active.published {
                        return app::Task::none();
                    }
                    active.control.cancel();
                    self.transfers.status = Some("Cancel requested…".into());
                    if active.phase == Phase::Review {
                        return self.discard_transfer();
                    }
                }
            }
            Action::Commit => {
                let Some(active) = self
                    .transfers
                    .active
                    .as_mut()
                    .filter(|a| a.phase == Phase::Review)
                else {
                    return app::Task::none();
                };
                active.phase = Phase::Committing;
                let request = RepoRequest::Commit {
                    id: active.id.clone(),
                    control: active.control.clone(),
                };
                self.transfers.status = Some(format!(
                    "Importing {} reviewed documents…",
                    active.review.as_ref().map_or(0, |r| r.documents_added)
                ));
                return self.transfer_repository(request);
            }
            Action::ReviewAgain => {
                let Some(active) = self
                    .transfers
                    .active
                    .as_mut()
                    .filter(|a| a.phase == Phase::Review)
                else {
                    return app::Task::none();
                };
                active.phase = Phase::Preparing;
                let id = active.id.clone();
                return self.transfer_repository(RepoRequest::Review(id));
            }
            Action::RetryCleanup => {
                let Some(active) = self
                    .transfers
                    .active
                    .as_mut()
                    .filter(|a| a.phase == Phase::CleanupNeeded)
                else {
                    return app::Task::none();
                };
                let Some(receipt) = active.committed.clone() else {
                    return self.discard_transfer();
                };
                active.phase = Phase::Committing;
                return self.transfer_repository(RepoRequest::RetryCleanup {
                    operation: receipt.operation,
                });
            }
            Action::Retry if !self.transfers.busy() => {
                if !self.transfers.failed.is_empty() {
                    let files = std::mem::take(&mut self.transfers.failed);
                    return self.picked(Pick::Documents, Ok(Some(files)));
                }
                if let Some(kind) = self.transfers.retry.clone() {
                    self.transfers.control = Control::default();
                    return self.begin_transfer(kind);
                }
            }
            Action::Dismiss if !self.transfers.busy() => {
                self.transfers.status = None;
                self.transfers.outcomes.clear();
            }
            Action::KeyTyped(mut text) => {
                if let Some(entry) = self.transfers.key_entry.as_mut() {
                    entry.key = Zeroizing::new(std::mem::take(&mut text.0));
                    entry.error = None;
                }
            }
            Action::PassphraseTyped(mut text) => {
                if let Some(entry) = self.transfers.key_entry.as_mut() {
                    entry.passphrase = Zeroizing::new(std::mem::take(&mut text.0));
                    entry.error = None;
                }
            }
            Action::SaveKeyToggled(on) => {
                if let Some(entry) = self.transfers.key_entry.as_mut() {
                    entry.save = on;
                }
            }
            Action::SavedKey(id, result) => {
                // An answer for an old request is ignored; its key is dropped (and zeroed) here.
                if self.transfers.lookup.as_ref().is_none_or(|(c, _)| *c != id) {
                    return app::Task::none();
                }
                let (_, path) = self.transfers.lookup.take().expect("checked above");
                match result {
                    Ok(Some(key)) => {
                        self.transfers.control = Control::default();
                        self.transfers.used_saved = true;
                        return self.begin_transfer(Kind::Archive(path, Some(key)));
                    }
                    Ok(None) => {
                        self.transfers.key_entry = Some(KeyEntry::new(path, None));
                        self.transfers.status = Some(NO_SAVED_KEY.into());
                    }
                    Err(message) => {
                        self.transfers.key_entry = Some(KeyEntry::new(path, None));
                        self.transfers.status = Some(format!(
                            "{message} Paste or type the key to open this backup."
                        ));
                    }
                }
            }
            Action::KeyCancel => {
                let waiting = self.transfers.lookup.take().is_some();
                self.transfers.clip = None; // a late clipboard answer is dropped, never saved
                if self.transfers.key_entry.take().is_some() || waiting {
                    self.transfers.status = Some("Backup not opened.".into());
                }
            }
            Action::PasteKey
                if self.transfers.key_entry.is_some()
                    && self.transfers.active.is_none()
                    && !self.transfers.picking
                    && self.transfers.lookup.is_none()
                    && self.transfers.clip.is_none() =>
            {
                self.transfers.clips += 1;
                let id = self.transfers.clips;
                self.transfers.clip = Some(id);
                return iced::clipboard::read()
                    .map(move |text| Message::Transfer(Action::ClipboardKey(id, text.map(Typed))))
                    .map(cosmic::Action::App);
            }
            Action::ClipboardKey(id, text) => {
                // Only the answer to the read the panel is waiting for counts; the text is dropped (zeroed) otherwise.
                if self.transfers.clip != Some(id) {
                    return app::Task::none();
                }
                self.transfers.clip = None;
                if self.transfers.active.is_some() || self.transfers.key_entry.is_none() {
                    return app::Task::none();
                }
                let Some(hex) = text
                    .as_ref()
                    .and_then(|t| stack_core::keystore::parse_key_text(&t.0))
                else {
                    // Stay on the key panel with one short line.
                    if let Some(entry) = self.transfers.key_entry.as_mut() {
                        entry.error = Some(CLIPBOARD_NOT_A_KEY.into());
                    }
                    return app::Task::none();
                };
                let entry = self.transfers.key_entry.take().expect("checked above");
                // Open this same backup with the pasted key. The key is saved on this computer (replacing an older
                // one) only once it has opened the backup; a wrong key is never saved. The clipboard is cleared now.
                let path = entry.path.clone();
                drop(entry);
                let open = self.begin_keyed(path, hex.clone(), Some(hex));
                return app::Task::batch([
                    open,
                    iced::clipboard::write::<cosmic::Action<Message>>(String::new()),
                ]);
            }
            Action::KeySaved(result) => match result {
                Ok(fingerprint) => {
                    self.transfers.status = Some(format!(
                        "Backup key saved. Check fingerprint {fingerprint} matches Key Vault."
                    ));
                    self.transfers.key_status =
                        Some(Ok(stack_core::keystore::KeyStatus::Saved { fingerprint }));
                }
                Err(message) => {
                    self.transfers.status =
                        Some(format!("The backup key was not saved. {message}"));
                }
            },
            Action::RemoveKey if !self.transfers.busy() => {
                let store = self.transfers.secret_store();
                return cosmic::task::future(async move {
                    let result =
                        tokio::task::spawn_blocking(move || store.remove().map_err(String::from))
                            .await
                            .unwrap_or_else(|_| Err("Removing the key failed.".into()));
                    Message::Transfer(Action::KeyRemoved(result))
                });
            }
            Action::KeyRemoved(result) => match result {
                Ok(()) => {
                    self.transfers.status =
                        Some("Saved backup key removed from this computer.".into());
                    self.transfers.key_status = Some(Ok(stack_core::keystore::KeyStatus::NotSet));
                }
                Err(message) => {
                    self.transfers.status =
                        Some(format!("The saved key was not removed. {message}"));
                }
            },
            Action::KeyStatus(result) => {
                self.transfers.key_status_reading = false;
                self.transfers.key_status = Some(result);
            }
            Action::KeySubmit => {
                let Some(mut entry) = self.transfers.key_entry.take() else {
                    return app::Task::none();
                };
                let mut pending = None;
                let key = if !entry.key.trim().is_empty() {
                    let Some(hex) = stack_core::keystore::parse_key_text(&entry.key) else {
                        entry.error = Some(
                            "The recovery key is 64 characters (0-9, a-f); spaces and dashes are fine."
                                .into(),
                        );
                        self.transfers.key_entry = Some(entry);
                        return app::Task::none();
                    };
                    // Saved only once the backup has opened with it (a wrong key is never saved).
                    pending = entry.save.then(|| hex.clone());
                    stack_core::BackupKey::recovery(hex.to_string())
                } else if !entry.passphrase.is_empty() {
                    stack_core::BackupKey::passphrase(entry.passphrase.to_string())
                } else {
                    entry.error = Some("Enter the recovery key, or the passphrase.".into());
                    self.transfers.key_entry = Some(entry);
                    return app::Task::none();
                };
                let path = entry.path.clone();
                drop(entry); // zeroes the typed text
                self.transfers.used_saved = false;
                self.transfers.control = Control::default();
                self.transfers.clip = None;
                let task = self.begin_transfer(Kind::Archive(path, Some(Arc::new(key))));
                if let Some(active) = self.transfers.active.as_mut() {
                    active.pending_save = pending;
                }
                return task;
            }
            _ => {}
        }
        app::Task::none()
    }

    pub fn picked(
        &mut self,
        pick: Pick,
        result: Result<Option<Vec<PathBuf>>, String>,
    ) -> app::Task<Message> {
        self.transfers.picking = false;
        let files = match result {
            Ok(Some(files)) if !files.is_empty() && files.len() <= 100 => files,
            Ok(None) => {
                self.transfers.status = Some("File selection canceled.".into());
                return app::Task::none();
            }
            Ok(_) => {
                self.transfers.status = Some("Choose between 1 and 100 files.".into());
                return app::Task::none();
            }
            Err(error) => {
                self.transfers.status = Some(format!("Could not choose files: {error}"));
                return app::Task::none();
            }
        };
        if self.transfers.active.is_some() {
            return app::Task::none();
        }
        self.transfers.control = Control::default();
        self.transfers.failed.clear();
        self.transfers.outcomes.clear();
        self.transfers.retry = None;
        match pick {
            Pick::Documents => {
                self.transfers.queue = files.into();
                self.next_import()
            }
            Pick::Archive => {
                if is_encrypted(&files[0]) {
                    // The saved key opens it with no prompt. The lookup is off the UI thread (the secret store
                    // may ask the user to unlock it); typing the key is the fallback.
                    self.transfers.lookups += 1;
                    let id = self.transfers.lookups;
                    let store = self.transfers.secret_store();
                    self.transfers.lookup = Some((id, files[0].clone()));
                    self.transfers.status = Some("Looking for the saved backup key…".into());
                    return cosmic::task::future(async move {
                        let result = tokio::task::spawn_blocking(move || {
                            stack_core::keystore::saved_backup_key(store.as_ref())
                                .map(|key| key.map(Arc::new))
                                .map_err(String::from)
                        })
                        .await
                        .unwrap_or_else(|_| Err("The saved key could not be read.".into()));
                        Message::Transfer(Action::SavedKey(id, result))
                    });
                }
                self.begin_transfer(Kind::Archive(files[0].clone(), None))
            }
            Pick::ArchiveExport => self.begin_transfer(Kind::ExportArchive(
                files[0].clone(),
                crate::operations::KeySource(self.transfers.secret_store()),
            )),
            Pick::Pdf { token, .. } => self.begin_transfer(Kind::ExportPdf {
                token,
                destination: files[0].clone(),
            }),
        }
    }

    fn next_import(&mut self) -> app::Task<Message> {
        if self.transfers.control.check().is_err() {
            for path in self.transfers.queue.drain(..) {
                self.transfers
                    .outcomes
                    .push((path, "Canceled before import".into()));
            }
            self.transfers.status =
                Some("Remaining imports canceled; completed imports are saved.".into());
            return self.refresh_browser();
        }
        if let Some(source) = self.transfers.queue.pop_front() {
            return self.begin_transfer(Kind::Source(source));
        }
        if !self.transfers.outcomes.is_empty() {
            self.transfers.status =
                Some("Import batch finished. Review each file’s outcome below.".into());
        }
        self.refresh_browser()
    }

    /// Opens an encrypted backup with a recovery key from the panel; `pending` is saved once it has opened.
    fn begin_keyed(
        &mut self,
        path: PathBuf,
        hex: Zeroizing<String>,
        pending: Option<Zeroizing<String>>,
    ) -> app::Task<Message> {
        self.transfers.used_saved = false;
        self.transfers.control = Control::default();
        self.transfers.clip = None;
        let task = self.begin_transfer(Kind::Archive(
            path,
            Some(Arc::new(stack_core::BackupKey::recovery(hex.to_string()))),
        ));
        if let Some(active) = self.transfers.active.as_mut() {
            active.pending_save = pending;
        }
        task
    }

    fn begin_transfer(&mut self, kind: Kind) -> app::Task<Message> {
        let id = uuid::Uuid::new_v4().to_string();
        let control = self.transfers.control.clone();
        // Only the worker keeps an archive's key (dropped when it is done with it); `Active` never holds it.
        let (shown, keyed) = match &kind {
            Kind::Archive(path, key) => (Kind::Archive(path.clone(), None), key.is_some()),
            other => (other.clone(), false),
        };
        self.transfers.active = Some(Active {
            id: id.clone(),
            kind: shown,
            keyed,
            control: control.clone(),
            phase: Phase::Preparing,
            review: None,
            committed: None,
            published: false,
            pending_save: None,
        });
        self.transfers.status = Some("Preparing saved-library operation…".into());
        self.transfer_repository(RepoRequest::Prepare { id, kind, control })
    }

    fn transfer_repository(&self, request: RepoRequest) -> app::Task<Message> {
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        let receive = worker.submit_required(Request::Operation(request));
        cosmic::task::future(async move {
            Message::TransferRepository(
                receive
                    .await
                    .unwrap_or_else(|_| Err("Library worker stopped".into())),
            )
        })
    }

    fn transfer_request(&self, request: TransferRequest) -> app::Task<Message> {
        let Some(worker) = &self.transfer_worker else {
            return app::Task::none();
        };
        let receive = worker.submit_required(request);
        cosmic::task::future(async move {
            Message::Transferred(
                receive
                    .await
                    .unwrap_or_else(|_| Err("Transfer worker stopped".into())),
            )
        })
    }

    fn discard_transfer(&mut self) -> app::Task<Message> {
        let Some(active) = &mut self.transfers.active else {
            return app::Task::none();
        };
        active.phase = Phase::Cleaning;
        active.pending_save = None; // an unsaved key goes with its transfer
        let id = active.id.clone();
        self.transfer_request(TransferRequest::Discard(id))
    }

    pub fn transfer_repository_result(
        &mut self,
        result: Result<Response, String>,
    ) -> app::Task<Message> {
        let result = match result {
            Ok(Response::Operation(result)) => result,
            Err(error) => return self.transfer_failed(error),
            _ => return app::Task::none(),
        };
        match result {
            RepoOutcome::Prepared(id) => {
                let Some(active) = self.transfers.active.as_mut().filter(|a| a.id == id) else {
                    return app::Task::none();
                };
                if active.control.check().is_err() {
                    return self.discard_transfer();
                }
                active.phase = if matches!(
                    active.kind,
                    Kind::ExportArchive(..) | Kind::ExportPdf { .. }
                ) {
                    Phase::Writing
                } else {
                    Phase::Reading
                };
                let request = TransferRequest::Run {
                    id,
                    control: active.control.clone(),
                };
                self.transfers.status = Some(
                    if active.phase == Phase::Writing {
                        "Writing and validating saved output…"
                    } else {
                        "Copying and validating input…"
                    }
                    .into(),
                );
                self.transfer_request(request)
            }
            RepoOutcome::Review { id, receipt } => {
                let Some(active) = self.transfers.active.as_mut().filter(|a| a.id == id) else {
                    return app::Task::none();
                };
                if active.control.check().is_err() {
                    return self.discard_transfer();
                }
                self.transfers.status = None;
                // Reaching review proves the key only for an encrypted backup (a plain one never used it).
                let pending = active.pending_save.take().filter(|_| receipt.encrypted);
                active.review = Some(receipt);
                active.phase = Phase::Review;
                // The key opened this backup, so it is now safe to save it.
                match pending {
                    Some(hex) => {
                        let store = self.transfers.secret_store();
                        cosmic::task::future(async move {
                            let result = tokio::task::spawn_blocking(move || {
                                stack_core::keystore::save_key(store.as_ref(), &hex)
                            })
                            .await
                            .unwrap_or_else(|_| Err("Saving the key failed.".into()));
                            Message::Transfer(Action::KeySaved(result))
                        })
                    }
                    None => app::Task::none(),
                }
            }
            RepoOutcome::Committed {
                id,
                receipt,
                cleanup_needed,
            } => {
                let Some(active) = self
                    .transfers
                    .active
                    .as_mut()
                    .filter(|a| id.is_empty() || a.id == id)
                else {
                    return app::Task::none();
                };
                self.transfers.status = Some(format!(
                    "Saved {} imported documents{}{}",
                    receipt.documents_added,
                    if receipt.links_added > 0 {
                        format!(" and {} linked documents (read-only)", receipt.links_added)
                    } else {
                        String::new()
                    },
                    if cleanup_needed {
                        "; local cleanup needs retry."
                    } else {
                        "."
                    }
                ));
                active.committed = Some(receipt);
                if cleanup_needed {
                    active.phase = Phase::CleanupNeeded;
                    return self.show_all_documents();
                }
                if let Kind::Source(source) = &active.kind {
                    self.transfers
                        .outcomes
                        .push((source.clone(), "Imported and saved".into()));
                }
                self.discard_transfer()
            }
        }
    }

    pub fn transferred(&mut self, result: Result<TransferOutcome, String>) -> app::Task<Message> {
        match result {
            Ok(TransferOutcome::Staged { id, archive }) => {
                let Some(active) = self.transfers.active.as_mut().filter(|a| a.id == id) else {
                    return app::Task::none();
                };
                if active.control.check().is_err() {
                    return self.discard_transfer();
                }
                active.phase = if archive {
                    Phase::Review
                } else {
                    Phase::Committing
                };
                let request = if archive {
                    RepoRequest::Review(id)
                } else {
                    RepoRequest::CommitOriginal {
                        id,
                        control: active.control.clone(),
                    }
                };
                self.transfer_repository(request)
            }
            Ok(TransferOutcome::Exported {
                id,
                destination,
                cleanup_needed,
                pdf,
                encrypted,
                no_key_store,
                links,
            }) => {
                let Some(active) = self.transfers.active.as_mut().filter(|a| a.id == id) else {
                    return app::Task::none();
                };
                active.published = true;
                let archive_note = match (pdf.is_some(), encrypted, links) {
                    (true, ..) => String::new(),
                    (false, encrypted, links) => format!(
                        " {} {}.",
                        if encrypted {
                            "Encrypted with the saved backup key;"
                        } else if no_key_store {
                            "Not encrypted (no key store on this computer);"
                        } else {
                            "Not encrypted (no backup key is saved);"
                        },
                        match links {
                            0 => "no linked documents".to_string(),
                            1 => "1 linked document included".to_string(),
                            n => format!("{n} linked documents included"),
                        }
                    ),
                };
                self.transfers.status = Some(format!(
                    "{} published to {}{}{}{}",
                    if pdf.is_some() { "PDF" } else { "Archive" },
                    destination.display(),
                    if cleanup_needed {
                        "; directory durability or cleanup needs review."
                    } else {
                        "."
                    },
                    archive_note,
                    pdf.map(|mode| format!(" {}", mode.text_status()))
                        .unwrap_or_default()
                ));
                self.transfers.retry = None;
                self.discard_transfer()
            }
            Ok(TransferOutcome::Discarded(id)) => {
                let Some(active) = self.transfers.active.take() else {
                    return app::Task::none();
                };
                if active.id != id {
                    self.transfers.active = Some(active);
                    return app::Task::none();
                }
                if active.control.check().is_err()
                    && active.committed.is_none()
                    && !active.published
                {
                    self.transfers.status = Some(
                        match &active.kind {
                            Kind::Archive(..) => {
                                "Archive restore canceled; saved library unchanged."
                            }
                            Kind::Source(_) => "Import canceled; completed imports remain saved.",
                            _ => "Export canceled before publication.",
                        }
                        .into(),
                    );
                }
                if matches!(active.kind, Kind::Source(_)) {
                    return self.next_import();
                }
                if matches!(
                    active.kind,
                    Kind::ExportArchive(..) | Kind::ExportPdf { .. }
                ) {
                    return app::Task::none();
                }
                if active.committed.is_some() && matches!(active.kind, Kind::Archive(..)) {
                    // Show the imported documents: the library, every stack, no search.
                    return self.show_all_documents();
                }
                self.refresh_browser()
            }
            Err(error) => self.transfer_failed(error),
        }
    }

    fn transfer_failed(&mut self, error: String) -> app::Task<Message> {
        let Some(active) = self.transfers.active.as_ref() else {
            self.transfers.status = Some(error);
            return app::Task::none();
        };
        if active.committed.is_some() || active.published {
            self.transfers.status = Some(format!(
                "Saved or published already; cleanup retry needed: {error}"
            ));
            self.transfers.active.as_mut().unwrap().phase = Phase::CleanupNeeded;
            return app::Task::none();
        }
        let canceled = active.control.check().is_err();
        let keyed = active.keyed;
        if active.phase == Phase::Committing
            && active.review.is_some()
            && !canceled
            && error.contains("library changed")
        {
            self.transfers.status = Some(format!(
                "{error}. Review the staged archive again before importing."
            ));
            self.transfers.active.as_mut().unwrap().phase = Phase::Review;
            return app::Task::none();
        }
        let status = if canceled {
            "Canceled before commit".into()
        } else {
            format!("Failed before saving: {error}")
        };
        match &active.kind {
            Kind::Source(path) => {
                self.transfers.outcomes.push((path.clone(), status.clone()));
                if !canceled {
                    self.transfers.failed.push(path.clone());
                }
            }
            // An encrypted backup that failed (most often a wrong key) asks for the key again; the key itself
            // is not kept for a retry.
            Kind::Archive(path, _) if keyed => {
                if !canceled {
                    let message = if self.transfers.used_saved {
                        format!("The saved backup key did not open this backup. {status}")
                    } else {
                        status.clone()
                    };
                    self.transfers.key_entry = Some(KeyEntry::new(path.clone(), Some(message)));
                }
            }
            kind => {
                if !canceled {
                    self.transfers.retry = Some(kind.clone());
                }
            }
        }
        self.transfers.status = Some(status);
        self.discard_transfer()
    }

    /// Reads the saved-key status for the Backup key section (off the UI thread).
    pub fn refresh_key_status(&mut self) -> app::Task<Message> {
        if self.transfers.key_status_reading {
            return app::Task::none();
        }
        self.transfers.key_status_reading = true;
        let store = self.transfers.secret_store();
        // Never unlocks the store: a locked one is shown as unavailable.
        cosmic::task::future(async move {
            let result = tokio::task::spawn_blocking(move || {
                stack_core::keystore::key_status(store.as_ref())
            })
            .await
            .unwrap_or_else(|_| Err("The saved key could not be read.".into()));
            Message::Transfer(Action::KeyStatus(result))
        })
    }

    pub fn transfer_panel(&self) -> Element<'_, Message> {
        let mut content = widget::column::with_capacity(5).spacing(8);
        if let Some(status) = &self.transfers.status {
            content = content.push(self.body(status.as_str()));
        }
        if let Some(active) = &self.transfers.active {
            if !matches!(active.phase, Phase::Review | Phase::CleanupNeeded) {
                content =
                    content.push(widget::indeterminate_linear().width(cosmic::iced::Length::Fill));
                if active.phase == Phase::Committing {
                    content =
                        content.push(self.body(
                            "Checking originals and saving documents. Keep this window open.",
                        ));
                } else if active.phase == Phase::Cleaning {
                    content = content.push(self.body("Finishing local cleanup…"));
                }
            }
            if matches!(
                active.phase,
                Phase::Reading | Phase::Writing | Phase::Committing
            ) {
                content = content.push(widget::text::caption(format!(
                    "{:.1} MB processed",
                    active.control.transferred_bytes() as f64 / 1_000_000.
                )));
            }
            let mut actions: Vec<Element<'_, Message>> = Vec::with_capacity(3);
            if active.phase == Phase::Review {
                if let Some(receipt) = &active.review {
                    let documents =
                        |n: usize| format!("{n} document{}", if n == 1 { "" } else { "s" });
                    let mut ready =
                        format!("Ready to import: {}", documents(receipt.documents_added));
                    if receipt.links_added > 0 {
                        let n = receipt.links_added;
                        ready.push_str(&format!(
                            ", {n} linked document{} (read-only)",
                            if n == 1 { "" } else { "s" }
                        ));
                    }
                    content = content.push(self.body(ready));
                    if !receipt.encrypted {
                        content = content.push(widget::text::caption(
                            "This backup is not encrypted, so it cannot be checked for tampering.",
                        ));
                    }
                    let n = receipt.documents_added;
                    actions.push(
                        widget::button::suggested(format!("Import {}", documents(n)))
                            .font_size(14)
                            .line_height(20)
                            .height(32.0)
                            .id(cosmic::iced::widget::Id::new("stack-import-reviewed"))
                            .on_press(Message::Transfer(Action::Commit))
                            .into(),
                    );
                }
                actions.push(
                    self.button("Review again")
                        .on_press(Message::Transfer(Action::ReviewAgain))
                        .into(),
                );
            }
            if active.phase == Phase::CleanupNeeded {
                actions.push(
                    self.button("Retry saved import cleanup")
                        .on_press(Message::Transfer(Action::RetryCleanup))
                        .into(),
                );
            } else if active.phase != Phase::Cleaning
                && active.committed.is_none()
                && !active.published
            {
                actions.push(
                    self.button(if active.phase == Phase::Review {
                        "Cancel"
                    } else {
                        "Cancel operation"
                    })
                    .on_press(Message::Transfer(Action::Cancel))
                    .into(),
                );
            }
            content = content.push(widget::flex_row(actions).spacing(8));
        } else if let Some(entry) = &self.transfers.key_entry {
            content = content.push(self.key_entry_view(entry));
        } else if self.transfers.status.is_some() {
            content = content.push(
                widget::row::with_capacity(2)
                    .spacing(8)
                    .push(
                        self.button("Retry failed items").on_press_maybe(
                            (!self.transfers.failed.is_empty() || self.transfers.retry.is_some())
                                .then_some(Message::Transfer(Action::Retry)),
                        ),
                    )
                    .push(
                        self.button("Dismiss")
                            .on_press(Message::Transfer(Action::Dismiss)),
                    ),
            );
        }
        if !self.transfers.outcomes.is_empty() {
            let mut outcomes =
                widget::column::with_capacity(self.transfers.outcomes.len()).spacing(4);
            for (path, status) in &self.transfers.outcomes {
                outcomes = outcomes.push(self.body(format!(
                        "{} · {status}",
                        path.file_name()
                            .map(|p| p.to_string_lossy())
                            .unwrap_or_default()
                    )));
            }
            content = content.push(widget::scrollable(outcomes).height(100));
        }
        widget::container(content).padding(12).into()
    }
}

impl Stack {
    fn key_entry_view<'a>(&'a self, entry: &'a KeyEntry) -> Element<'a, Message> {
        let mut column = widget::column::with_capacity(8).spacing(8);
        column = column.push(self.body("In Key Vault, click Copy Stack key first."));
        column = column.push(widget::text::caption(
            "A clipboard manager may keep a copy of the key.",
        ));
        if let Some(error) = &entry.error {
            column = column.push(self.body(error.as_str()));
        }
        column = column
            .push(
                widget::button::suggested("Paste key from Key Vault")
                    .font_size(14)
                    .line_height(20)
                    .height(32.0)
                    .id(widget::Id::new("stack-paste-key"))
                    .on_press(Message::Transfer(Action::PasteKey)),
            )
            .push(widget::text::caption("Type the key or passphrase instead"))
            .push(
                widget::secure_input("Recovery key", entry.key.as_str(), None, true)
                    .on_input(|v| Message::Transfer(Action::KeyTyped(Typed(v))))
                    .on_submit(|_| Message::Transfer(Action::KeySubmit))
                    .id(widget::Id::new("stack-backup-key")),
            )
            .push(
                widget::secure_input("Passphrase", entry.passphrase.as_str(), None, true)
                    .on_input(|v| Message::Transfer(Action::PassphraseTyped(Typed(v))))
                    .on_submit(|_| Message::Transfer(Action::KeySubmit)),
            );
        column
            .push(
                widget::checkbox(entry.save)
                    .label("Save this key on this computer")
                    .on_toggle(|on| Message::Transfer(Action::SaveKeyToggled(on))),
            )
            .push(
                widget::row::with_capacity(2)
                    .spacing(8)
                    .push(
                        self.button("Open backup")
                            .on_press(Message::Transfer(Action::KeySubmit)),
                    )
                    .push(
                        self.button("Cancel")
                            .on_press(Message::Transfer(Action::KeyCancel)),
                    ),
            )
            .into()
    }
}

pub(crate) async fn window_parent(
    owner: std::sync::Arc<dyn iced::runtime::window::Window + Send + Sync>,
) -> Result<cosmic::dialog::ashpd::WindowIdentifier, String> {
    use cosmic::dialog::ashpd;
    tokio::task::spawn_blocking(move || {
        use iced::runtime::window::raw_window_handle::{RawDisplayHandle, RawWindowHandle};
        let window = owner.window_handle().map_err(|e| e.to_string())?;
        let display = owner.display_handle().map_err(|e| e.to_string())?;
        let identifier = match (window.as_raw(), display.as_raw()) {
            (RawWindowHandle::Xlib(handle), _) => {
                Some(ashpd::WindowIdentifier::from_xid(handle.window))
            }
            (RawWindowHandle::Xcb(handle), _) => Some(ashpd::WindowIdentifier::from_xid(
                handle.window.get().into(),
            )),
            (RawWindowHandle::Wayland(window), RawDisplayHandle::Wayland(display)) => {
                // SAFETY: native owner stays alive across export and the
                // complete chooser request; both handles came from it.
                unsafe {
                    tokio::runtime::Handle::current().block_on(
                        ashpd::WindowIdentifier::from_wayland_raw(
                            window.surface.as_ptr(),
                            display.display.as_ptr(),
                        ),
                    )
                }
            }
            _ => None,
        };
        identifier.ok_or_else(|| "Could not export the native chooser parent".to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

async fn pick_files(
    pick: Pick,
    directory: Option<PathBuf>,
    caption: Option<String>,
    owner: Option<std::sync::Arc<dyn iced::runtime::window::Window + Send + Sync>>,
) -> Result<Option<Vec<PathBuf>>, String> {
    let parent = if let Some(owner) = owner {
        Some(window_parent(owner).await?)
    } else {
        if std::env::var_os("STACK_M2_PORTAL_PROTOCOL").is_none() {
            return Err("The native window closed before opening its chooser".into());
        }
        None
    };
    let title = |label: &str| {
        caption
            .as_ref()
            .map(|nonce| format!("{nonce} · {label}"))
            .unwrap_or_else(|| label.into())
    };
    match pick {
        Pick::Documents => {
            let filter = file_chooser::FileFilter::new("PDF and images")
                .glob("*.pdf")
                .glob("*.jpg")
                .glob("*.jpeg")
                .glob("*.png")
                .glob("*.webp");
            open_files(title("Import documents"), directory, filter, true, parent).await
        }
        Pick::Archive => {
            open_files(
                title("Review a Stack archive"),
                directory,
                file_chooser::FileFilter::new("Stack archives").glob("*.stackbackup"),
                false,
                parent,
            )
            .await
        }

        Pick::ArchiveExport => {
            save_file(
                title("Export full portable library archive"),
                "Stack-library.stackbackup".into(),
                directory,
                file_chooser::FileFilter::new("Stack archives").glob("*.stackbackup"),
                parent,
            )
            .await
        }
        Pick::Pdf {
            title: document_title,
            ..
        } => {
            save_file(
                title("Export saved document as PDF"),
                format!(
                    "{}.pdf",
                    document_title
                        .chars()
                        .map(|c| if c == '/' || c == '\\' || c.is_control() {
                            '_'
                        } else {
                            c
                        })
                        .take(100)
                        .collect::<String>()
                ),
                directory,
                file_chooser::FileFilter::new("PDF documents").glob("*.pdf"),
                parent,
            )
            .await
        }
    }
}

// The pinned libcosmic open wrapper omits its directory option in the portal
// branch. Use its existing ashpd re-export so the native request honours it.
pub(crate) async fn open_files(
    title: String,
    directory: Option<PathBuf>,
    filter: file_chooser::FileFilter,
    multiple: bool,
    parent: Option<cosmic::dialog::ashpd::WindowIdentifier>,
) -> Result<Option<Vec<PathBuf>>, String> {
    use cosmic::dialog::ashpd;
    let result = async {
        ashpd::desktop::file_chooser::OpenFileRequest::default()
            .identifier(parent)
            .title(Some(title.as_str()))
            .multiple(multiple)
            .modal(true)
            .filters([filter])
            .current_folder::<&std::path::Path>(directory.as_deref())?
            .send()
            .await?
            .response()
    }
    .await;
    match result {
        Ok(response) => response
            .uris()
            .iter()
            .map(|url| {
                url.to_file_path()
                    .map_err(|_| "Choose local files".to_string())
            })
            .collect::<Result<Vec<_>, _>>()
            .map(Some),
        Err(ashpd::Error::Response(ashpd::desktop::ResponseError::Cancelled)) => Ok(None),
        Err(error) => Err(error.to_string()),
    }
}

pub fn progress_subscription(ui: &Ui) -> cosmic::iced::Subscription<Message> {
    let Some(active) = &ui.active else {
        return cosmic::iced::Subscription::none();
    };
    // A review awaits a user decision; there is no background work to poll.
    if matches!(active.phase, Phase::Review | Phase::CleanupNeeded) {
        return cosmic::iced::Subscription::none();
    }
    let id = active.id.clone();
    cosmic::iced::time::every(Duration::from_millis(120))
        .with(id)
        .map(|(id, _)| Message::TransferTick(id))
}

async fn save_file(
    title: String,
    name: String,
    directory: Option<PathBuf>,
    filter: file_chooser::FileFilter,
    parent: Option<cosmic::dialog::ashpd::WindowIdentifier>,
) -> Result<Option<Vec<PathBuf>>, String> {
    use cosmic::dialog::ashpd;
    let result = async {
        ashpd::desktop::file_chooser::SaveFileRequest::default()
            .identifier(parent)
            .title(Some(title.as_str()))
            .modal(true)
            .current_name(Some(name.as_str()))
            .filters([filter])
            .current_folder::<&std::path::Path>(directory.as_deref())?
            .send()
            .await?
            .response()
    }
    .await;
    match result {
        Ok(response) => response
            .uris()
            .iter()
            .map(|url| {
                url.to_file_path()
                    .map_err(|_| "Choose a local output".to_string())
            })
            .collect::<Result<Vec<_>, _>>()
            .map(Some),
        Err(ashpd::Error::Response(ashpd::desktop::ResponseError::Cancelled)) => Ok(None),
        Err(error) => Err(error.to_string()),
    }
}
