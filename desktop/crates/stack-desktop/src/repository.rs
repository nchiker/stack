use crate::worker::Worker;
use stack_core::library::DocumentToken;
use stack_core::{DocumentView, SearchHit, Sort, StackEdit, StackSummary, library::Library};
use std::path::PathBuf;

pub enum Request {
    ResetPreview,
    ResetRecovery,
    ScheduleReset {
        mode: stack_core::reset::Mode,
        library: String,
        revision: i64,
    },
    Sync(SyncRequest),
    TextClaim(Option<String>),
    TextFinish(stack_core::TextJob, Result<stack_core::TextOutput, String>),
    TextRetry(String),
    TextCancel(String),
    Tiles(Vec<String>),
    Layout(bool),
    #[cfg(test)]
    Probe(stack_core::library::Probe),
    Browse {
        query: String,
        stack: Option<String>,
        sort: Option<Sort>,
    },
    Select {
        id: String,
        page: usize,
        matching_page: Option<String>,
        detail_offset: u32,
    },
    Catalog {
        revision: i64,
        edit: StackEdit,
    },
    Edit {
        token: DocumentToken,
        edit: stack_core::DocumentEdit,
        detail_offset: u32,
    },
    Delete {
        token: DocumentToken,
    },
    Operation(crate::operations::RepoRequest),
}

pub enum SyncRequest {
    Network(crate::network_ui::Request),
    Status,
    #[cfg(test)]
    Start(Vec<String>),
    #[cfg(test)]
    Review {
        path: PathBuf,
        control: stack_archive::Control,
    },
    #[cfg(test)]
    Export {
        peer: String,
        path: PathBuf,
        control: stack_archive::Control,
    },
    Resolve {
        key: String,
        expected: serde_json::Value,
        value: serde_json::Value,
    },
}

#[derive(Clone, Debug)]
pub enum Response {
    ResetPreview(serde_json::Value),
    ResetRecovery(Option<stack_core::reset::Mode>),
    ResetScheduled,
    Sync(serde_json::Value),
    TextClaim {
        job: Option<stack_core::TextJob>,
        statuses: Vec<stack_core::TextStatus>,
        document: Option<String>,
    },
    TextFinished {
        document: String,
        statuses: Vec<stack_core::TextStatus>,
        view: Option<DocumentView>,
        based_on: Option<DocumentToken>,
    },
    Tiles(Vec<stack_core::DocumentTile>),
    SavedLayout,
    Browser {
        stacks: Vec<StackSummary>,
        hits: Vec<SearchHit>,
        revision: i64,
        sort: Sort,
        grid: Option<bool>,
    },
    SavedCatalog,
    SavedDocument(DocumentView),
    DeletedDocument(String),
    Operation(crate::operations::RepoOutcome),
    Selected {
        document: DocumentView,
        page: usize,
        source: PathBuf,
    },
}

#[cfg(test)]
pub fn start(
    root: PathBuf,
    expected: Option<stack_archive::Manifest>,
    preferences: Option<PathBuf>,
) -> Worker<Request, Response> {
    start_operations(root, expected, preferences, std::sync::Arc::default())
}
pub fn start_operations(
    root: PathBuf,
    expected: Option<stack_archive::Manifest>,
    preferences: Option<PathBuf>,
    operations: crate::operations::Store,
) -> Worker<Request, Response> {
    let mut library = None;
    Worker::new("stack-repository", move |request| {
        // Open/recovery/retry belongs to this thread, including lock contention.
        if library.is_none() {
            let mut opened = Library::open(&root).map_err(|e| e.to_string())?;
            opened
                .clean_legacy_sync_stacks()
                .map_err(|e| e.to_string())?;
            if let Some(expected) = &expected {
                verify_synthetic(&opened, expected)?;
            }
            library = Some(opened);
        }
        let library = library.as_mut().expect("opened repository");
        if library.reset_pending() {
            return Err("A library reset is pending. Close and reopen Stack.".into());
        }
        let run = || -> stack_core::library::Result<Response> {
            match request {
                Request::ResetRecovery => {
                    Ok(Response::ResetRecovery(library.take_reset_completion()?))
                }
                Request::ResetPreview => Ok(Response::ResetPreview(serde_json::to_value(
                    library.status()?,
                )?)),
                Request::ScheduleReset {
                    mode,
                    library: expected,
                    revision,
                } => {
                    library.request_reset(mode, &expected, revision)?;
                    Ok(Response::ResetScheduled)
                }
                Request::Sync(command) => {
                    use SyncRequest::*;
                    let value = match command {
                        Status => {
                            let mut value = library.sync_status()?;
                            value["pairing"] = crate::network_ui::pairing_status(library.root())
                                .unwrap_or_else(
                                    |error| serde_json::json!({"error":error.to_string()}),
                                );
                            value
                        }
                        Network(request) => {
                            crate::network_ui::execute(library, request).map_err(|error| {
                                stack_core::library::Error::Io(std::io::Error::other(error))
                            })?
                        }
                        #[cfg(test)]
                        Start(members) => library.sync_start(&members)?,
                        #[cfg(test)]
                        Review { path, control } => {
                            library.sync_review_adoption_control(&path, &control)?
                        }
                        #[cfg(test)]
                        Export {
                            peer,
                            path,
                            control,
                        } => library.sync_export_control(&peer, &path, &control)?,
                        Resolve {
                            key,
                            expected,
                            value,
                        } => {
                            library.sync_resolve_json(&key, &expected, value)?;
                            library.sync_status()?
                        }
                    };
                    Ok(Response::Sync(value))
                }
                Request::TextClaim(document) => Ok(Response::TextClaim {
                    job: library.claim_text()?,
                    statuses: document
                        .as_deref()
                        .map(|id| library.text_status(id))
                        .transpose()?
                        .unwrap_or_default(),
                    document,
                }),
                Request::TextFinish(job, result) => {
                    let accepted = library.finish_text(&job, result)?;
                    Ok(Response::TextFinished {
                        based_on: accepted.then_some(job.token.clone()),
                        document: job.token.id.clone(),
                        statuses: library.text_status(&job.token.id)?,
                        view: library.document_view(&job.token.id).ok(),
                    })
                }
                Request::TextRetry(document) => {
                    library.retry_text(&document)?;
                    Ok(Response::TextFinished {
                        statuses: library.text_status(&document)?,
                        view: None,
                        based_on: None,
                        document,
                    })
                }
                Request::TextCancel(document) => {
                    library.cancel_text(&document)?;
                    Ok(Response::TextFinished {
                        statuses: library.text_status(&document)?,
                        view: None,
                        based_on: None,
                        document,
                    })
                }
                Request::Tiles(ids) => {
                    if ids.len() > crate::RESULTS_PER_PAGE {
                        return Err(stack_core::library::Error::Invalid(
                            "Too many visible tiles",
                        ));
                    }
                    Ok(Response::Tiles(
                        ids.iter()
                            .map(|id| library.document_tile(id))
                            .collect::<stack_core::library::Result<_>>()?,
                    ))
                }
                Request::Layout(grid) => {
                    if let Some(path) = &preferences {
                        crate::preferences::save_grid(path, grid).map_err(|e| {
                            stack_core::library::Error::Io(std::io::Error::other(e))
                        })?;
                    }
                    Ok(Response::SavedLayout)
                }
                #[cfg(test)]
                Request::Probe(probe) => {
                    library.set_probe(probe);
                    Ok(Response::SavedCatalog)
                }
                Request::Operation(request) => Ok(Response::Operation(
                    crate::operations::repository(library, &operations, request)?,
                )),
                Request::Browse { query, stack, sort } => {
                    let chosen = sort.unwrap_or_else(|| {
                        preferences
                            .as_ref()
                            .and_then(|path| crate::preferences::load(path).ok())
                            .unwrap_or(Sort::Newest)
                    });
                    let hits = library.search(&query, stack.as_deref(), chosen, 1000)?;
                    if sort.is_some()
                        && let Some(path) = &preferences
                        && crate::preferences::load(path).ok() != Some(chosen)
                    {
                        crate::preferences::save(path, chosen).map_err(|e| {
                            stack_core::library::Error::Io(std::io::Error::other(e))
                        })?;
                    }
                    Ok(Response::Browser {
                        stacks: library.stacks()?,
                        hits,
                        revision: library.revision()?,
                        sort: chosen,
                        grid: preferences
                            .as_ref()
                            .and_then(|p| crate::preferences::load_grid(p).ok()),
                    })
                }
                Request::Catalog { revision, edit } => {
                    library.edit_stacks(revision, edit, &stack_archive::Control::default())?;
                    Ok(Response::SavedCatalog)
                }
                Request::Edit {
                    token,
                    edit,
                    detail_offset,
                } => {
                    let token =
                        library.edit_document(&token, edit, &stack_archive::Control::default())?;
                    Ok(Response::SavedDocument(
                        library.document_view_page(&token.id, detail_offset)?,
                    ))
                }
                Request::Delete { token } => {
                    library.delete_document(&token, &stack_archive::Control::default())?;
                    Ok(Response::DeletedDocument(token.id))
                }
                Request::Select {
                    id,
                    page,
                    matching_page,
                    detail_offset,
                } => {
                    let document = library.document_view_page(&id, detail_offset)?;
                    let page = match matching_page {
                        Some(id) => document
                            .pages
                            .iter()
                            .position(|p| p.id == id)
                            .ok_or(stack_core::library::Error::Stale)?,
                        None => page,
                    };
                    let saved = document
                        .pages
                        .get(page)
                        .ok_or(stack_core::library::Error::Stale)?;
                    let source = library.page_original(&document.token, saved)?;
                    Ok(Response::Selected {
                        document,
                        page,
                        source,
                    })
                }
            }
        };
        run().map_err(|e| e.to_string())
    })
}

// Capture safety boundary: every row and original must be the authored fixture.
fn verify_synthetic(library: &Library, expected: &stack_archive::Manifest) -> Result<(), String> {
    let canonical = |mut value: serde_json::Value| {
        value["created_at"] = serde_json::json!(0);
        for table in stack_archive::validation::TABLES {
            value[table]
                .as_array_mut()
                .expect("validated table")
                .sort_by_key(serde_json::Value::to_string);
        }
        value
    };
    let actual = library.manifest().map_err(|e| e.to_string())?;
    if canonical(actual.into_json()) != canonical(expected.json().clone()) {
        return Err("Capture requires the exact authored M1 synthetic graph".into());
    }
    for file in expected.rows("files") {
        let path = library
            .root()
            .join(file["path"].as_str().expect("validated path"));
        let hash = stack_archive::file_hash(
            &path,
            file["length"].as_u64().expect("length"),
            &stack_archive::Control::default(),
        )
        .map_err(|e| e.to_string())?;
        if hash != file["sha256"].as_str().expect("hash") {
            return Err("Capture requires authored synthetic original bytes".into());
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_sync_requests_serialize_group_creation_and_file_publication() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().join("library");
        let exchange = directory.path().join("out.stackexchange");
        let peer = uuid::Uuid::new_v4().to_string();
        let worker = start(root.clone(), None, None);
        let request = |request| {
            let Response::Sync(value) = worker
                .submit(Request::Sync(request))
                .blocking_recv()
                .unwrap()
                .unwrap()
            else {
                panic!("sync response");
            };
            value
        };
        let before = request(SyncRequest::Status);
        assert_eq!(before["adopted"], false);
        let own = before["replica"].as_str().unwrap().to_owned();
        let started = request(SyncRequest::Start(vec![peer.clone()]));
        assert_eq!(started["adopted"], true);
        assert_eq!(started["members"].as_array().unwrap().len(), 2);
        assert!(
            started["members"]
                .as_array()
                .unwrap()
                .iter()
                .any(|id| id == &own)
        );
        let canceled = stack_archive::Control::default();
        canceled.cancel();
        assert!(
            worker
                .submit_required(Request::Sync(SyncRequest::Export {
                    peer: peer.clone(),
                    path: exchange.clone(),
                    control: canceled,
                }))
                .blocking_recv()
                .unwrap()
                .is_err()
        );
        assert!(!exchange.exists(), "a canceled file must not be published");
        request(SyncRequest::Export {
            peer: peer.clone(),
            path: exchange.clone(),
            control: stack_archive::Control::default(),
        });
        assert!(exchange.is_file());
        let status = request(SyncRequest::Status);
        assert_eq!(status["acknowledged_by"], serde_json::json!({}));
        assert!(status["operations"].as_u64().unwrap() >= 1);
        drop(worker);
        let reopened = start(root, None, None);
        let Response::Sync(saved) = reopened
            .submit(Request::Sync(SyncRequest::Status))
            .blocking_recv()
            .unwrap()
            .unwrap()
        else {
            panic!("status after restart")
        };
        assert_eq!(saved["library"], status["library"]);
        assert_eq!(saved["members"], status["members"]);

        let review_worker = start(directory.path().join("reviewer"), None, None);
        let canceled = stack_archive::Control::default();
        canceled.cancel();
        assert!(
            review_worker
                .submit_required(Request::Sync(SyncRequest::Review {
                    path: exchange.clone(),
                    control: canceled,
                }))
                .blocking_recv()
                .unwrap()
                .is_err()
        );
        let Response::Sync(unchanged) = review_worker
            .submit_required(Request::Sync(SyncRequest::Status))
            .blocking_recv()
            .unwrap()
            .unwrap()
        else {
            panic!("reviewer status")
        };
        assert_eq!(unchanged["adopted"], false);
        let Response::Sync(review) = review_worker
            .submit_required(Request::Sync(SyncRequest::Review {
                path: exchange,
                control: stack_archive::Control::default(),
            }))
            .blocking_recv()
            .unwrap()
            .unwrap()
        else {
            panic!("review plan after retry")
        };
        assert_eq!(review["library"], saved["library"]);
    }

    #[test]
    fn composed_search_selects_saved_matching_page_and_sort_survives_worker_restart() {
        struct FixtureBytes;
        impl stack_archive::SourceVerifier for FixtureBytes {
            fn verify(
                &self,
                _path: &std::path::Path,
                _mime: &str,
                _pages: Option<u32>,
            ) -> stack_archive::Result<()> {
                Ok(())
            }
        }
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().join("library");
        let fixture = directory.path().join("fixture");
        stack_archive::synthetic::create(&fixture, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap();
        let mut library = Library::open(&root).unwrap();
        let staged = library
            .stage_archive(
                &mut std::fs::File::open(fixture.join("format-3.stackbackup")).unwrap(),
                &FixtureBytes,
                &stack_archive::Control::default(),
            )
            .unwrap();
        let plan = library.review_archive(&staged).unwrap();
        library
            .commit_archive(&plan, &stack_archive::Control::default())
            .unwrap();
        drop(plan);
        drop(staged);
        drop(library);
        let preferences = directory.path().join("config/view.json");
        let worker = start(root.clone(), None, Some(preferences.clone()));
        for (query, count) in [
            ("laterpagesentinel", 1),
            ("noteonlysentinel", 1),
            ("00024.500000", 1),
            ("doesnotexist", 0),
        ] {
            let result = worker
                .submit(Request::Browse {
                    query: query.into(),
                    stack: Some("custom-first".into()),
                    sort: Some(Sort::Name),
                })
                .blocking_recv()
                .unwrap()
                .unwrap();
            let Response::Browser { hits, .. } = result else {
                panic!("browser");
            };
            assert_eq!(hits.len(), count, "query={query}");
        }
        let result = worker
            .submit(Request::Select {
                id: stack_archive::synthetic::id(1),
                page: 0,
                matching_page: Some(stack_archive::synthetic::id(101)),
                detail_offset: 0,
            })
            .blocking_recv()
            .unwrap()
            .unwrap();
        let Response::Selected {
            document,
            page,
            source,
        } = result
        else {
            panic!("selection");
        };
        assert_eq!(page, 1);
        assert_eq!(document.pages[page].source_page, 1);
        assert_eq!(std::fs::read(source).unwrap(), b"pdf");
        assert!(
            worker
                .submit(Request::Select {
                    id: document.token.id,
                    page: 0,
                    matching_page: Some("removed-page".into()),
                    detail_offset: 0
                })
                .blocking_recv()
                .unwrap()
                .is_err()
        );
        worker
            .submit(Request::Layout(false))
            .blocking_recv()
            .unwrap()
            .unwrap();
        drop(worker);
        let worker = start(root, None, Some(preferences));
        let Response::Browser { sort, grid, .. } =
            worker.submit(browse()).blocking_recv().unwrap().unwrap()
        else {
            panic!("browser");
        };
        assert_eq!(sort, Sort::Name);
        assert_eq!(
            grid,
            Some(false),
            "layout survives restart independently of sort"
        );
    }

    fn browse() -> Request {
        Request::Browse {
            query: String::new(),
            stack: None,
            sort: None,
        }
    }

    #[test]
    fn busy_open_can_retry_and_empty_root_returns_saved_catalog() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().join("library");
        let held = Library::open(&root).unwrap();
        let worker = start(root.clone(), None, None);
        let error = worker
            .submit(browse())
            .blocking_recv()
            .unwrap()
            .unwrap_err();
        assert!(error.contains("already using"));
        drop(held);
        let Response::Browser { stacks, hits, .. } =
            worker.submit(browse()).blocking_recv().unwrap().unwrap()
        else {
            panic!("browser response");
        };
        assert_eq!(stacks.len(), 7);
        assert!(hits.is_empty());
        // The authoritative repository stays exclusively on its owning worker.
        assert!(matches!(
            Library::open(&root),
            Err(stack_core::library::Error::Busy)
        ));
        drop(worker);
        assert!(Library::open(&root).is_ok());
    }

    #[test]
    fn foreign_directory_errors_preserve_its_files_and_can_retry() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().join("foreign");
        std::fs::create_dir(&root).unwrap();
        std::fs::write(root.join("keep"), b"authored synthetic unrelated bytes").unwrap();
        let worker = start(root.clone(), None, None);
        assert!(worker.submit(browse()).blocking_recv().unwrap().is_err());
        assert!(worker.submit(browse()).blocking_recv().unwrap().is_err());
        assert_eq!(
            std::fs::read(root.join("keep")).unwrap(),
            b"authored synthetic unrelated bytes"
        );
    }
}
