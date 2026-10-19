//! One bounded transfer owner; only IDs/counts/outcomes cross into widgets.
//! SQLite stays on the repository thread. Cleanup and large source/snapshot work
//! stay on workers, including when a response is superseded or a window closes.
use crate::worker::Worker;
use stack_archive::Control;
use stack_core::{
    ImportWork, OutputWork, RestorePlan, RestoreReceipt, SnapshotWork, StagedImport,
    library::{Error, Library, Result},
};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{Arc, Mutex},
};

#[derive(Clone, Debug)]
pub enum Kind {
    Source(PathBuf),
    Archive(PathBuf),
    ExportArchive(PathBuf),
    ExportPdf {
        token: stack_core::library::DocumentToken,
        destination: PathBuf,
    },
}
#[derive(Clone)]
pub enum RepoRequest {
    Prepare {
        id: String,
        kind: Kind,
        control: Control,
    },
    Review(String),
    Commit {
        id: String,
        control: Control,
    },
    CommitOriginal {
        id: String,
        control: Control,
    },
    RetryCleanup {
        operation: String,
    },
}
#[derive(Clone, Debug)]
pub enum RepoOutcome {
    Prepared(String),
    Review {
        id: String,
        receipt: RestoreReceipt,
    },
    Committed {
        id: String,
        receipt: RestoreReceipt,
        cleanup_needed: bool,
    },
}

pub enum TransferRequest {
    Run { id: String, control: Control },
    Discard(String),
}
#[derive(Clone, Debug)]
pub enum TransferOutcome {
    Staged {
        id: String,
        archive: bool,
    },
    Exported {
        id: String,
        destination: PathBuf,
        cleanup_needed: bool,
        pdf: Option<stack_render::pdf::Mode>,
    },
    Discarded(String),
}

pub enum Work {
    Input(ImportWork, Kind),
    Export(SnapshotWork, OutputWork, PathBuf),
    Pdf(SnapshotWork, OutputWork, PathBuf, String),
}
#[derive(Default)]
pub struct Slot {
    archive: bool,
    work: Option<Work>,
    staged: Option<Arc<StagedImport>>,
    plan: Option<Arc<RestorePlan>>,
}
#[derive(Default)]
pub struct Shared {
    slots: BTreeMap<String, Slot>,
}
pub type Store = Arc<Mutex<Shared>>;

pub fn repository(
    library: &mut Library,
    store: &Store,
    request: RepoRequest,
) -> Result<RepoOutcome> {
    match request {
        RepoRequest::Prepare { id, kind, control } => {
            control.check()?;
            let work = match kind.clone() {
                Kind::Source(_) | Kind::Archive(_) => Work::Input(library.prepare_import()?, kind),
                Kind::ExportArchive(destination) => Work::Export(
                    library.prepare_snapshot(&control)?,
                    library.prepare_output()?,
                    destination,
                ),
                Kind::ExportPdf { token, destination } => Work::Pdf(
                    library.prepare_document_snapshot(&token, &control)?,
                    library.prepare_output()?,
                    destination,
                    token.id,
                ),
            };
            let mut shared = store.lock().expect("transfer slots");
            if shared.slots.len() >= 2 || shared.slots.contains_key(&id) {
                return Err(Error::Invalid("Transfer owner is busy"));
            }
            shared.slots.insert(
                id.clone(),
                Slot {
                    work: Some(work),
                    ..Default::default()
                },
            );
            Ok(RepoOutcome::Prepared(id))
        }
        RepoRequest::Review(id) => {
            if !store
                .lock()
                .expect("slots")
                .slots
                .get(&id)
                .is_some_and(|s| s.archive)
            {
                return Err(Error::Stale);
            }
            let staged = store
                .lock()
                .expect("slots")
                .slots
                .get(&id)
                .and_then(|s| s.staged.clone())
                .ok_or(Error::Stale)?;
            let plan = Arc::new(library.review_archive(&staged)?);
            let receipt = plan.review().clone();
            store
                .lock()
                .expect("slots")
                .slots
                .get_mut(&id)
                .ok_or(Error::Stale)?
                .plan = Some(plan);
            Ok(RepoOutcome::Review { id, receipt })
        }
        RepoRequest::CommitOriginal { id, control } => {
            if store
                .lock()
                .expect("slots")
                .slots
                .get(&id)
                .is_some_and(|s| s.archive)
            {
                return Err(Error::Stale);
            }
            let staged = store
                .lock()
                .expect("slots")
                .slots
                .get(&id)
                .and_then(|s| s.staged.clone())
                .ok_or(Error::Stale)?;
            let plan = Arc::new(library.review_original(&staged)?);
            store
                .lock()
                .expect("slots")
                .slots
                .get_mut(&id)
                .ok_or(Error::Stale)?
                .plan = Some(plan);
            commit(library, store, id, &control)
        }
        RepoRequest::Commit { id, control } => commit(library, store, id, &control),
        RepoRequest::RetryCleanup { operation } => {
            let receipt = library.committed_receipt(&operation)?.ok_or(Error::Stale)?;
            let cleanup_needed = library.retry_committed(&operation).is_err();
            Ok(RepoOutcome::Committed {
                id: String::new(),
                receipt,
                cleanup_needed,
            })
        }
    }
}

fn commit(
    library: &mut Library,
    store: &Store,
    id: String,
    control: &Control,
) -> Result<RepoOutcome> {
    let plan = store
        .lock()
        .expect("slots")
        .slots
        .get(&id)
        .and_then(|s| s.plan.clone())
        .ok_or(Error::Stale)?;
    let (receipt, cleanup_needed) = match library.commit_archive(&plan, control) {
        Ok(receipt) => (receipt, false),
        Err(Error::CommittedCleanup(operation)) => (
            library.committed_receipt(&operation)?.ok_or(Error::Stale)?,
            true,
        ),
        Err(error) => return Err(error),
    };
    Ok(RepoOutcome::Committed {
        id,
        receipt,
        cleanup_needed,
    })
}

pub fn start(store: Store) -> Worker<TransferRequest, TransferOutcome> {
    Worker::new("stack-transfer", move |request| {
        let run = || -> Result<TransferOutcome> {
            match request {
                TransferRequest::Discard(id) => {
                    let slot = store.lock().expect("slots").slots.remove(&id);
                    drop(slot); // The potentially large staged tree is removed here.
                    Ok(TransferOutcome::Discarded(id))
                }
                TransferRequest::Run { id, control } => {
                    let work = store
                        .lock()
                        .expect("slots")
                        .slots
                        .get_mut(&id)
                        .and_then(|s| s.work.take())
                        .ok_or(Error::Stale)?;
                    control.check()?;
                    match work {
                        Work::Input(work, kind) => {
                            let renderer = stack_render::Renderer::from_environment();
                            let (staged, archive) = match kind {
                                Kind::Archive(source) => (
                                    work.archive(
                                        &mut stack_archive::read_regular(&source)?,
                                        &renderer,
                                        &control,
                                    )?,
                                    true,
                                ),
                                Kind::Source(source) => {
                                    let info = renderer
                                        .inspect_original(&source)
                                        .map_err(|e| Error::Io(std::io::Error::other(e)))?;
                                    let title = source
                                        .file_stem()
                                        .map(|s| s.to_string_lossy().into_owned())
                                        .unwrap_or_else(|| "Imported document".into());
                                    (
                                        work.original(
                                            &source,
                                            &title,
                                            &renderer,
                                            info.mime,
                                            info.page_count,
                                            &control,
                                        )?,
                                        false,
                                    )
                                }
                                Kind::ExportArchive(_) | Kind::ExportPdf { .. } => {
                                    return Err(Error::Stale);
                                }
                            };
                            let mut shared = store.lock().expect("slots");
                            let slot = shared.slots.get_mut(&id).ok_or(Error::Stale)?;
                            slot.archive = archive;
                            slot.staged = Some(Arc::new(staged));
                            Ok(TransferOutcome::Staged { id, archive })
                        }
                        Work::Export(work, output, destination) => {
                            let snapshot = work.pin(&control)?;
                            let cleanup_needed =
                                match output.publish(&destination, &control, |file, control| {
                                    snapshot.write(file, control)
                                }) {
                                    Ok(()) => false,
                                    Err(Error::PublishedExport(_)) => true,
                                    Err(error) => return Err(error),
                                };
                            Ok(TransferOutcome::Exported {
                                id,
                                destination,
                                cleanup_needed,
                                pdf: None,
                            })
                        }
                        Work::Pdf(work, output, destination, document) => {
                            let snapshot = work.pin(&control)?;
                            let renderer = stack_render::Renderer::from_environment();
                            let plan = stack_render::pdf::Plan::new(&snapshot, &document)?;
                            let cleanup_needed = match output.publish_checked(
                                &destination,
                                &control,
                                |file, control| plan.write(&snapshot, &renderer, file, control),
                                |path, control| {
                                    control.check()?;
                                    renderer
                                        .inspect_generated_pdf(path, plan.page_count())
                                        .map_err(|e| Error::Io(std::io::Error::other(e)))
                                },
                            ) {
                                Ok(()) => false,
                                Err(Error::PublishedExport(_)) => true,
                                Err(error) => return Err(error),
                            };
                            Ok(TransferOutcome::Exported {
                                id,
                                destination,
                                cleanup_needed,
                                pdf: Some(plan.mode),
                            })
                        }
                    }
                }
            }
        };
        run().map_err(|e| e.to_string())
    })
}
