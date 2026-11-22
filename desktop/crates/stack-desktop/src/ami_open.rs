//! "Open in Ami Pro" for a linked document: edit the ORIGINAL file, chosen once through the system file chooser.
//! Installed as a normal program it starts `amipro-linux <file>` directly (it opens in the running window or starts one);
//! inside a Flatpak it is handed to the desktop's own default handler through `org.freedesktop.portal.OpenURI.OpenFile`.
//! The remembered path lives in the local-only `link_local_files` table (never synced, never exported).
use crate::{
    Message, Stack,
    repository::{Request, Response},
};
use cosmic::{app, iced};
use sha2::{Digest, Sha256};
use std::{
    io::Read,
    os::fd::OwnedFd,
    path::{Path, PathBuf},
};

#[derive(Default)]
pub struct Ui {
    pub busy: bool,
    /// Plain message under the button.
    pub status: Option<String>,
    /// A chosen file whose content differs from the saved record, waiting for Cancel / Open anyway.
    pub differs: Option<PathBuf>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Same,
    Differs,
    /// The record carries no usable hash, so nothing can be compared.
    Unknown,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum OpenError {
    /// The remembered file is gone or its document-portal grant was lost.
    Gone,
    Failed(String),
}

#[derive(Clone, Debug)]
pub enum Event {
    Press,
    Chosen(Result<Option<PathBuf>, String>),
    Checked(PathBuf, Result<Verdict, String>),
    OpenAnyway,
    CancelDiffers,
    Opened(Result<(), OpenError>),
    Saved(Result<(), String>),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Button {
    /// Shown disabled, with this hint.
    Disabled(&'static str),
    /// No remembered file: pressing starts the choose-once step.
    Choose,
    /// A remembered file: pressing opens it.
    Open,
}

/// The button for a link, from its state and whether a file is remembered.
pub fn button_state(state: &str, remembered: bool) -> Button {
    if state == "trashed" {
        Button::Disabled("This document was removed, so it cannot be opened.")
    } else if remembered {
        Button::Open
    } else {
        Button::Choose
    }
}

/// Compare the record's content hash (hex SHA-256, any case) with the hash of the chosen file.
pub fn compare(known: &str, actual: &str) -> Verdict {
    let known = known.trim();
    if known.len() != 64 || !known.bytes().all(|b| b.is_ascii_hexdigit()) {
        Verdict::Unknown
    } else if known.eq_ignore_ascii_case(actual) {
        Verdict::Same
    } else {
        Verdict::Differs
    }
}

pub fn sha256_file(path: &Path) -> std::io::Result<String> {
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hasher
        .finalize()
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect())
}

/// Open the remembered file read-only to get the fd the portal needs. A missing file or a vanished
/// document-portal entry (ENOENT, EACCES, ENOTCONN, ESTALE) means "choose it again"; anything else is a real failure.
pub fn open_fd(path: &Path) -> Result<OwnedFd, OpenError> {
    match std::fs::File::open(path) {
        Ok(file) => match file.metadata() {
            Ok(meta) if meta.is_file() => Ok(file.into()),
            _ => Err(OpenError::Gone),
        },
        Err(e) => Err(classify(&e)),
    }
}

fn classify(error: &std::io::Error) -> OpenError {
    use std::io::ErrorKind::*;
    match error.kind() {
        NotFound | PermissionDenied | NotConnected => OpenError::Gone,
        _ => match error.raw_os_error() {
            Some(116 | 107 | 20) => OpenError::Gone, // ESTALE, ENOTCONN, ENOTDIR
            _ => OpenError::Failed(error.to_string()),
        },
    }
}

/// The Ami Pro program (documented in Ami Pro's INSTALL notes); found on PATH.
const AMI_PRO_PROGRAM: &str = "amipro-linux";

/// True when running inside a Flatpak sandbox: the app id is set, or `/.flatpak-info` exists.
pub fn sandboxed(flatpak_id: Option<&std::ffi::OsStr>, info_file_exists: bool) -> bool {
    flatpak_id.is_some_and(|id| !id.is_empty()) || info_file_exists
}

fn in_flatpak() -> bool {
    sandboxed(
        std::env::var_os("FLATPAK_ID").as_deref(),
        Path::new("/.flatpak-info").exists(),
    )
}

async fn open_document(path: PathBuf) -> Result<(), OpenError> {
    if in_flatpak() {
        portal_open(path).await
    } else {
        tokio::task::spawn_blocking(move || launch(std::ffi::OsStr::new(AMI_PRO_PROGRAM), &path))
            .await
            .map_err(|e| OpenError::Failed(e.to_string()))?
    }
}

/// Start `program <path>` without a shell and without its output. The file must be an existing regular file given by
/// an absolute path (so it can never be read as an option). The child is reaped on a small thread.
pub fn launch(program: &std::ffi::OsStr, path: &Path) -> Result<(), OpenError> {
    if !path.is_absolute() || !std::fs::metadata(path).is_ok_and(|m| m.is_file()) {
        return Err(OpenError::Gone);
    }
    let child = std::process::Command::new(program)
        .arg(path)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn();
    match child {
        Ok(mut child) => {
            std::thread::spawn(move || {
                let _ = child.wait();
            });
            Ok(())
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            Err(OpenError::Failed("Ami Pro is not installed.".into()))
        }
        Err(e) => Err(OpenError::Failed(e.to_string())),
    }
}

async fn portal_open(path: PathBuf) -> Result<(), OpenError> {
    use cosmic::dialog::ashpd::desktop::open_uri::OpenFileRequest;
    let fd = tokio::task::spawn_blocking(move || open_fd(&path))
        .await
        .map_err(|e| OpenError::Failed(e.to_string()))??;
    let request = OpenFileRequest::default()
        .send_file(&fd)
        .await
        .map_err(|e| OpenError::Failed(e.to_string()))?;
    // The reply comes once the handler is started; waiting forever for it is not needed.
    match tokio::time::timeout(std::time::Duration::from_secs(10), async {
        request.response()
    })
    .await
    {
        Ok(Err(e)) => Err(OpenError::Failed(e.to_string())),
        _ => Ok(()),
    }
}

async fn choose_file(
    owner: Option<std::sync::Arc<dyn iced::runtime::window::Window + Send + Sync>>,
) -> Result<Option<PathBuf>, String> {
    let parent = match owner {
        Some(owner) => Some(crate::transfers_ui::window_parent(owner).await?),
        None => None,
    };
    let filter = cosmic::dialog::file_chooser::FileFilter::new("Ami Pro documents")
        .glob("*.sam")
        .glob("*.SAM")
        .glob("*.amipro");
    let files = crate::transfers_ui::open_files(
        "Choose this document once".into(),
        None,
        filter,
        false,
        parent,
    )
    .await?;
    Ok(files.and_then(|f| f.into_iter().next()))
}

impl Stack {
    pub fn ami_button_state(&self) -> Option<Button> {
        self.linked
            .as_ref()
            .map(|l| button_state(&l.state, l.remembered_file.is_some()))
    }

    pub fn ami_action(&mut self, event: Event) -> app::Task<Message> {
        let Some(linked) = &self.linked else {
            return app::Task::none();
        };
        let id = linked.id.clone();
        match event {
            Event::Press if !self.ami.busy => {
                self.ami.differs = None;
                match button_state(&linked.state, linked.remembered_file.is_some()) {
                    Button::Disabled(_) => app::Task::none(),
                    Button::Open => {
                        let path = PathBuf::from(linked.remembered_file.clone().unwrap());
                        self.ami.busy = true;
                        self.ami.status = Some("Opening in Ami Pro…".into());
                        cosmic::task::future(async move {
                            Message::Ami(Event::Opened(open_document(path).await))
                        })
                    }
                    Button::Choose => {
                        self.ami.busy = true;
                        self.ami.status =
                            Some("Choose this document once in the window that opens.".into());
                        let open = |owner| {
                            cosmic::task::future(async move {
                                Message::Ami(Event::Chosen(choose_file(owner).await))
                            })
                        };
                        cosmic::iced::runtime::window::owned_handle(iced::window::Id::RESERVED)
                            .then(open)
                    }
                }
            }
            Event::Chosen(result) => match result {
                Ok(None) => {
                    self.ami.busy = false;
                    self.ami.status = Some("No file chosen.".into());
                    app::Task::none()
                }
                Err(error) => {
                    self.ami.busy = false;
                    self.ami.status = Some(format!("Could not show the file chooser: {error}"));
                    app::Task::none()
                }
                Ok(Some(path)) => {
                    let known = linked.hash.clone();
                    cosmic::task::future(async move {
                        let checked = path.clone();
                        let verdict = tokio::task::spawn_blocking(move || {
                            sha256_file(&checked)
                                .map(|actual| compare(&known, &actual))
                                .map_err(|e| e.to_string())
                        })
                        .await
                        .unwrap_or_else(|e| Err(e.to_string()));
                        Message::Ami(Event::Checked(path, verdict))
                    })
                }
            },
            Event::Checked(path, Err(error)) => {
                let _ = path;
                self.ami.busy = false;
                self.ami.status = Some(format!("Could not read the chosen file: {error}"));
                app::Task::none()
            }
            Event::Checked(path, Ok(Verdict::Differs)) => {
                self.ami.busy = false;
                self.ami.status = Some(
                    "This file is not the same as the saved copy of the document. It may be a different document, or it was edited after the copy was saved.".into(),
                );
                self.ami.differs = Some(path);
                app::Task::none()
            }
            Event::Checked(path, Ok(_)) => self.ami_remember_and_open(id, path),
            Event::OpenAnyway => match self.ami.differs.take() {
                Some(path) => self.ami_remember_and_open(id, path),
                None => app::Task::none(),
            },
            Event::CancelDiffers => {
                self.ami.differs = None;
                self.ami.status = Some("Not opened.".into());
                app::Task::none()
            }
            Event::Opened(Ok(())) => {
                self.ami.busy = false;
                self.ami.status = Some("Sent to Ami Pro.".into());
                app::Task::none()
            }
            Event::Opened(Err(OpenError::Gone)) => {
                self.ami.busy = false;
                self.ami.status = Some(
                    "The file is gone, or this app can no longer reach it. Press Open in Ami Pro to choose it again.".into(),
                );
                if let Some(linked) = &mut self.linked {
                    linked.remembered_file = None;
                }
                self.ami_save(id, None)
            }
            Event::Opened(Err(OpenError::Failed(error))) => {
                self.ami.busy = false;
                self.ami.status = Some(format!("Ami Pro could not be started: {error}"));
                app::Task::none()
            }
            Event::Saved(Err(error)) => {
                self.ami.status = Some(format!(
                    "Could not remember the file, so you will be asked again next time: {error}"
                ));
                app::Task::none()
            }
            Event::Saved(Ok(())) | Event::Press => app::Task::none(),
        }
    }

    fn ami_remember_and_open(&mut self, id: String, path: PathBuf) -> app::Task<Message> {
        self.ami.busy = true;
        self.ami.status = Some("Opening in Ami Pro…".into());
        if let Some(linked) = &mut self.linked {
            linked.remembered_file = Some(path.to_string_lossy().into_owned());
        }
        let save = self.ami_save(id, Some(path.to_string_lossy().into_owned()));
        let open =
            cosmic::task::future(
                async move { Message::Ami(Event::Opened(open_document(path).await)) },
            );
        app::Task::batch([save, open])
    }

    fn ami_save(&self, id: String, path: Option<String>) -> app::Task<Message> {
        let Some(repository) = &self.repository else {
            return app::Task::none();
        };
        let receive = repository.submit_required(Request::LinkFile { id, path });
        cosmic::task::future(async move {
            Message::Ami(Event::Saved(
                receive
                    .await
                    .unwrap_or_else(|_| Err("Library worker stopped".into()))
                    .map(|_: Response| ()),
            ))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hash_compare_is_case_blind_and_unknown_when_not_a_sha256() {
        let h = "ab".repeat(32);
        assert_eq!(compare(&h, &h.to_uppercase()), Verdict::Same);
        assert_eq!(compare(&h, &"cd".repeat(32)), Verdict::Differs);
        assert_eq!(compare("", &h), Verdict::Unknown);
        assert_eq!(compare("not a hash", &h), Verdict::Unknown);
        assert_eq!(compare(&"ab".repeat(31), &h), Verdict::Unknown);
    }

    #[test]
    fn sha256_of_a_file_matches_a_known_digest() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("a.sam");
        std::fs::write(&file, b"abc").unwrap();
        assert_eq!(
            sha256_file(&file).unwrap(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
        assert!(sha256_file(&dir.path().join("none")).is_err());
    }

    #[test]
    fn button_state_per_link() {
        assert!(matches!(button_state("trashed", true), Button::Disabled(_)));
        assert_eq!(button_state("live", false), Button::Choose);
        assert_eq!(button_state("unreachable", false), Button::Choose);
        assert_eq!(button_state("missing", true), Button::Open);
    }

    #[test]
    fn flatpak_is_detected_by_id_or_info_file() {
        use std::ffi::OsStr;
        assert!(!sandboxed(None, false));
        assert!(!sandboxed(Some(OsStr::new("")), false));
        assert!(sandboxed(Some(OsStr::new("org.stackdocs.desktop")), false));
        assert!(sandboxed(None, true));
    }

    #[test]
    fn launch_passes_the_file_to_the_program_and_refuses_bad_paths() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("got.txt");
        let fake = dir.path().join("fake-amipro");
        std::fs::write(
            &fake,
            format!("#!/bin/sh\nprintf '%s' \"$1\" > '{}'\n", out.display()),
        )
        .unwrap();
        std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).unwrap();
        let doc = dir.path().join("a b.sam");
        std::fs::write(&doc, b"x").unwrap();
        launch(fake.as_os_str(), &doc).unwrap();
        for _ in 0..100 {
            if out.exists() && std::fs::metadata(&out).unwrap().len() > 0 {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        assert_eq!(
            std::fs::read_to_string(&out).unwrap(),
            doc.to_string_lossy()
        );
        assert_eq!(
            launch(fake.as_os_str(), &dir.path().join("none.sam")).unwrap_err(),
            OpenError::Gone
        );
        assert_eq!(
            launch(fake.as_os_str(), Path::new("relative.sam")).unwrap_err(),
            OpenError::Gone
        );
        assert_eq!(
            launch(std::ffi::OsStr::new("/nonexistent/amipro-linux"), &doc).unwrap_err(),
            OpenError::Failed("Ami Pro is not installed.".into())
        );
    }

    #[test]
    fn a_missing_remembered_file_means_choose_again_and_a_present_one_gives_an_fd() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("a.sam");
        assert_eq!(open_fd(&file).unwrap_err(), OpenError::Gone);
        assert_eq!(open_fd(dir.path()).unwrap_err(), OpenError::Gone);
        std::fs::write(&file, b"x").unwrap();
        assert!(open_fd(&file).is_ok());
        std::fs::remove_file(&file).unwrap();
        assert_eq!(open_fd(&file).unwrap_err(), OpenError::Gone);
    }
}
