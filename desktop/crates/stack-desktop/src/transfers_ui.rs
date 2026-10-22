use crate::{
    Message, Stack,
    operations::{Kind, RepoOutcome, RepoRequest, TransferOutcome, TransferRequest},
    repository::{Request, Response},
};
use cosmic::{Element, app, dialog::file_chooser, iced, widget};
use stack_archive::Control;
use std::{collections::VecDeque, path::PathBuf, time::Duration};

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
}
impl Ui {
    pub fn busy(&self) -> bool {
        self.active.is_some() || self.picking
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
            Pick::Archive => self.begin_transfer(Kind::Archive(files[0].clone())),
            Pick::ArchiveExport => self.begin_transfer(Kind::ExportArchive(files[0].clone())),
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

    fn begin_transfer(&mut self, kind: Kind) -> app::Task<Message> {
        let id = uuid::Uuid::new_v4().to_string();
        let control = self.transfers.control.clone();
        self.transfers.active = Some(Active {
            id: id.clone(),
            kind: kind.clone(),
            control: control.clone(),
            phase: Phase::Preparing,
            review: None,
            committed: None,
            published: false,
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
                active.phase =
                    if matches!(active.kind, Kind::ExportArchive(_) | Kind::ExportPdf { .. }) {
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
                self.transfers.status = Some(format!(
                    "Archive review · {} documents to add · {} matching stacks · {} document ID collisions. This adds copies; selecting this archive again adds more copies.",
                    receipt.documents_added, receipt.merged_stacks, receipt.document_collisions
                ));
                active.review = Some(receipt);
                active.phase = Phase::Review;
                app::Task::none()
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
                    "Saved {} imported documents{}",
                    receipt.documents_added,
                    if cleanup_needed {
                        "; local cleanup needs retry."
                    } else {
                        "."
                    }
                ));
                active.committed = Some(receipt);
                if cleanup_needed {
                    active.phase = Phase::CleanupNeeded;
                    return self.refresh_browser();
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
            }) => {
                let Some(active) = self.transfers.active.as_mut().filter(|a| a.id == id) else {
                    return app::Task::none();
                };
                active.published = true;
                self.transfers.status = Some(format!(
                    "{} published to {}{} {}",
                    if pdf.is_some() { "PDF" } else { "Archive" },
                    destination.display(),
                    if cleanup_needed {
                        "; directory durability or cleanup needs review."
                    } else {
                        "."
                    },
                    pdf.map(|mode| mode.text_status()).unwrap_or("")
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
                            Kind::Archive(_) => {
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
                if matches!(active.kind, Kind::ExportArchive(_) | Kind::ExportPdf { .. }) {
                    return app::Task::none();
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
            kind => {
                if !canceled {
                    self.transfers.retry = Some(kind.clone());
                }
            }
        }
        self.transfers.status = Some(status);
        self.discard_transfer()
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
                actions.push(
                    self.button("Import reviewed copies")
                        .id(cosmic::iced::widget::Id::new("stack-import-reviewed"))
                        .on_press(Message::Transfer(Action::Commit))
                        .into(),
                );
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
                    self.button("Cancel operation")
                        .on_press(Message::Transfer(Action::Cancel))
                        .into(),
                );
            }
            content = content.push(widget::flex_row(actions).spacing(8));
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

async fn pick_files(
    pick: Pick,
    directory: Option<PathBuf>,
    caption: Option<String>,
    owner: Option<std::sync::Arc<dyn iced::runtime::window::Window + Send + Sync>>,
) -> Result<Option<Vec<PathBuf>>, String> {
    use cosmic::dialog::ashpd;
    let parent = if let Some(owner) = owner.as_ref() {
        let owner = owner.clone();
        Some(
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
            .map_err(|e| e.to_string())??,
        )
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
async fn open_files(
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
