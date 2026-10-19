use serde_json::{Value, json};
use stack_archive::{Control, Manifest, SourceVerifier, synthetic};
use stack_core::{
    DocumentEdit, Sort, StackEdit,
    library::{Error, Library},
};
use std::{
    fs,
    io::Cursor,
    path::{Path, PathBuf},
};

struct SyntheticBytes;
impl SourceVerifier for SyntheticBytes {
    fn verify(&self, path: &Path, mime: &str, pages: Option<u32>) -> stack_archive::Result<()> {
        let expected = match mime {
            "application/pdf" => b"pdf".as_slice(),
            "image/jpeg" => b"jpeg",
            "image/png" => b"png",
            "image/webp" => b"webp",
            _ => panic!("unexpected fixture MIME"),
        };
        if fs::read(path)? != expected || (mime == "application/pdf" && pages != Some(2)) {
            return Err(stack_archive::Error::Source("synthetic mismatch".into()));
        }
        Ok(())
    }
}

#[test]
fn unfinished_private_journal_is_discarded_before_publication() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("library");
    let library = Library::open(&root).unwrap();
    let identity = library.status().unwrap().library_id;
    drop(library);
    let operation = synthetic::id(9876);
    let folder = root.join("operations").join(&operation);
    fs::create_dir(&folder).unwrap();
    fs::write(
        folder.join("operation.json"),
        json!({"operation":operation,"kind":"import"}).to_string(),
    )
    .unwrap();
    fs::write(folder.join("journal.json.tmp"), b"{incomplete").unwrap();
    let library = Library::open(&root).unwrap();
    assert_eq!(library.status().unwrap().library_id, identity);
    assert_eq!(library.status().unwrap().documents, 0);
    assert_eq!(library.journal().unwrap().len(), 1);
    assert!(!folder.exists());
}
fn fixture(root: &Path) -> (Manifest, Vec<u8>) {
    let graph = synthetic::create(root, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap();
    (graph, fs::read(root.join("format-3.stackbackup")).unwrap())
}

#[test]
fn invalid_archives_and_new_destination_collisions_leave_existing_library_unchanged() {
    let dir = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&dir.path().join("fixture"));
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let before = canonical(library.manifest().unwrap().into_json());
    let events = library.journal().unwrap();
    let mut bad_hash = bytes.clone();
    bad_hash[12] ^= 1;
    let mut bad_source = bytes.clone();
    *bad_source.last_mut().unwrap() ^= 1;
    let mut trailing = bytes.clone();
    trailing.push(0);
    for invalid in [&bytes[..bytes.len() - 1], &bad_hash, &bad_source, &trailing] {
        assert!(
            library
                .stage_archive(
                    &mut Cursor::new(invalid),
                    &SyntheticBytes,
                    &Control::default()
                )
                .is_err()
        );
        assert_eq!(canonical(library.manifest().unwrap().into_json()), before);
        assert_eq!(library.journal().unwrap(), events);
    }
    let staged = library
        .stage_archive(
            &mut Cursor::new(&bytes),
            &SyntheticBytes,
            &Control::default(),
        )
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    let collision = root
        .join("originals")
        .join(plan.review().mappings.documents.values().next().unwrap());
    fs::create_dir(&collision).unwrap();
    fs::write(collision.join("keep"), b"external collision").unwrap();
    assert!(matches!(
        library.commit_archive(&plan, &Control::default()),
        Err(Error::Stale)
    ));
    assert_eq!(
        fs::read(collision.join("keep")).unwrap(),
        b"external collision"
    );
    assert_eq!(library.journal().unwrap(), events);
    drop(plan);
    drop(staged);
    assert_eq!(fs::read_dir(root.join("operations")).unwrap().count(), 0);
}
fn restore(library: &mut Library, bytes: &[u8]) -> stack_core::RestoreReceipt {
    let staged = library
        .stage_archive(
            &mut Cursor::new(bytes),
            &SyntheticBytes,
            &Control::default(),
        )
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    library.commit_archive(&plan, &Control::default()).unwrap()
}
fn canonical(mut graph: Value) -> Value {
    graph["created_at"] = json!(0);
    for table in stack_archive::validation::TABLES {
        if table != "stacks" {
            graph[table]
                .as_array_mut()
                .unwrap()
                .sort_by_key(Value::to_string);
        }
    }
    graph
}

#[test]
fn browser_projects_saved_subset_order_and_rejects_stale_page_identity() {
    let dir = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&dir.path().join("fixture"));
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let stacks = library.stacks().unwrap();
    assert_eq!(
        stacks.iter().map(|s| s.id.as_str()).collect::<Vec<_>>(),
        ["custom-first", "receipts", "custom-last"]
    );
    assert!(!stacks[0].visible);
    let before = library.document_view(&synthetic::id(1)).unwrap();
    assert_eq!(
        before
            .pages
            .iter()
            .map(|p| p.source_page)
            .collect::<Vec<_>>(),
        [0, 1]
    );
    assert!(!before.pages[0].has_appearance());
    assert!(library.document_view(&synthetic::id(2)).unwrap().pages[0].has_appearance());
    let original = library
        .page_original(&before.token, &before.pages[1])
        .unwrap();
    assert_eq!(fs::read(&original).unwrap(), b"pdf");
    library
        .edit_document(
            &before.token,
            DocumentEdit::PageOrder(vec![before.pages[1].id.clone()]),
            &Control::default(),
        )
        .unwrap();
    assert!(matches!(
        library.page_original(&before.token, &before.pages[1]),
        Err(Error::Stale)
    ));
    let after = library.document_view(&before.token.id).unwrap();
    assert_eq!(after.pages.len(), 1);
    assert_eq!(after.pages[0].position, 0);
    assert_eq!(after.pages[0].source_page, 1);
    assert_eq!(after.pages[0].id, before.pages[1].id);
    drop(library);
    let library = Library::open(&root).unwrap();
    assert_eq!(library.stacks().unwrap(), stacks);
    let reopened = library.document_view(&before.token.id).unwrap();
    assert_eq!(reopened.token, after.token);
    assert_eq!(reopened.pages, after.pages);
    assert_eq!(
        library
            .page_original(&reopened.token, &reopened.pages[0])
            .unwrap(),
        original
    );
}

#[test]
fn stack_color_is_journaled_and_failed_or_stale_saves_do_not_change_it() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    let revision = library.revision().unwrap();
    library
        .edit_stacks(
            revision,
            StackEdit::Color {
                id: "bills".into(),
                color: 0xff123456_u32 as i32,
            },
            &Control::default(),
        )
        .unwrap();
    assert_eq!(
        library.journal().unwrap().last().unwrap()["kind"],
        "stack.color"
    );
    let saved = library.stacks().unwrap();
    assert_eq!(saved[0].color, 0xff123456_u32 as i32);
    assert!(matches!(
        library.edit_stacks(
            revision,
            StackEdit::Color {
                id: "bills".into(),
                color: 0
            },
            &Control::default()
        ),
        Err(Error::Stale)
    ));
    let before = library.journal().unwrap();
    library.set_probe(Box::new(|point| {
        if point == "metadata_transaction" {
            Err(std::io::Error::other("synthetic save failure"))
        } else {
            Ok(())
        }
    }));
    assert!(
        library
            .edit_stacks(
                library.revision().unwrap(),
                StackEdit::Color {
                    id: "bills".into(),
                    color: 0
                },
                &Control::default()
            )
            .is_err()
    );
    assert_eq!(library.stacks().unwrap(), saved);
    assert_eq!(library.journal().unwrap(), before);
    drop(library);
    let library = Library::open(&root).unwrap();
    assert_eq!(library.stacks().unwrap(), saved);
    assert_eq!(library.journal().unwrap(), before);
}

#[test]
fn persisted_graph_originals_search_identity_and_additive_restores() {
    let dir = tempfile::tempdir().unwrap();
    let (fixture, bytes) = fixture(&dir.path().join("fixture"));
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    let initial = library.status().unwrap();
    assert_eq!(initial.stacks, 7);
    assert!(matches!(Library::open(&root), Err(Error::Busy)));
    let receipt = restore(&mut library, &bytes);
    assert_eq!(receipt.document_collisions, 0);
    assert_eq!(receipt.documents_added, 5);
    assert_eq!(
        canonical(library.manifest().unwrap().into_json()),
        canonical(fixture.json().clone())
    );
    let found = library
        .search("laterpagesentinel", None, Sort::Name, 100)
        .unwrap();
    assert_eq!(found.len(), 2);
    for hit in &found {
        let expected = fixture
            .rows("pages")
            .iter()
            .filter(|page| {
                page["document_id"] == hit.document_id
                    && page["text_revision"] == page["content_revision"]
                    && page["text_config"] == page["ocr_config"]
                    && !page["ocr_text"].as_str().unwrap().trim().is_empty()
            })
            .count() as u32;
        assert!(expected > 0);
        assert_eq!(
            library
                .document_view(&hit.document_id)
                .unwrap()
                .searchable_pages,
            expected
        );
    }
    assert_eq!(
        found[0].page_id.as_deref(),
        Some(synthetic::id(101).as_str())
    );
    assert_eq!(
        library
            .search("noteonlysentinel", None, Sort::Newest, 100)
            .unwrap()
            .len(),
        5
    );
    assert_eq!(
        library
            .search("0000123", Some("custom-first"), Sort::Name, 100)
            .unwrap()
            .len(),
        1
    );
    assert!(
        library
            .search("\" OR *", None, Sort::Name, 100)
            .unwrap()
            .is_empty()
    );
    let identity = library.status().unwrap().library_id;
    let journal = library.journal().unwrap();
    for file in fixture.rows("files") {
        let path = file["path"].as_str().unwrap();
        assert_eq!(
            stack_archive::digest(&fs::read(root.join(path)).unwrap()),
            file["sha256"]
        );
    }
    drop(library);
    let mut library = Library::open(&root).unwrap();
    assert_eq!(library.status().unwrap().library_id, identity);
    assert_eq!(library.journal().unwrap(), journal);
    let second = restore(&mut library, &bytes);
    assert_eq!(second.document_collisions, 5);
    assert_eq!(second.merged_stacks, 3);
    assert_eq!(library.status().unwrap().documents, 10);
    assert_eq!(library.status().unwrap().stacks, 3);
    let second_doc = &second.mappings.documents[&synthetic::id(1)];
    assert_ne!(second_doc, &synthetic::id(1));
    let graph = library.manifest().unwrap();
    let amount = graph
        .rows("details")
        .iter()
        .find(|d| d["document_id"] == *second_doc && d["field_id"] == "total")
        .unwrap();
    assert_eq!(amount["value"], "00024.500000");
    let evidence: Value = serde_json::from_str(amount["evidence"].as_str().unwrap()).unwrap();
    assert_eq!(
        evidence["page_id"],
        second.mappings.pages[&synthetic::id(101)]
    );
    assert_eq!(evidence["currency_source"], "bare-dollar fallback USD");
    let historical = graph
        .rows("details")
        .iter()
        .find(|d| d["document_id"] == *second_doc && d["field_id"] == "old")
        .unwrap();
    assert!(
        historical["evidence"]
            .as_str()
            .unwrap()
            .contains(&synthetic::id(999))
    );
    let db = rusqlite::Connection::open(root.join("library.sqlite3")).unwrap();
    let aliases: i64 = db
        .query_row(
            "SELECT count(*) FROM source_aliases WHERE operation=?1",
            [&second.operation],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(aliases, 17);
    let syncs: i64 = db
        .query_row(
            "SELECT count(DISTINCT sync_id) FROM entities WHERE kind='document'",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert_eq!(syncs, 10);
}

#[test]
fn edits_stale_workers_tombstones_snapshots_and_exact_details() {
    let dir = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&dir.path().join("fixture"));
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let snapshot = library.snapshot(&Control::default()).unwrap();
    let old = snapshot.manifest.clone();
    let token = library.token(&synthetic::id(1)).unwrap();
    let later = library
        .edit_document(
            &token,
            DocumentEdit::Rename("New manual title".into()),
            &Control::default(),
        )
        .unwrap();
    assert!(matches!(
        library.edit_document(
            &token,
            DocumentEdit::Notes("stale".into()),
            &Control::default()
        ),
        Err(Error::Stale)
    ));
    assert!(matches!(
        library.commit_page_text(
            &token,
            &synthetic::id(101),
            2,
            "stale replacement",
            "config",
            &Control::default()
        ),
        Err(Error::Stale)
    ));
    let later = library
        .edit_document(
            &later,
            DocumentEdit::Notes("Exact note 🦀\nnewnotesentinel".into()),
            &Control::default(),
        )
        .unwrap();
    let old_amount = old
        .rows("details")
        .iter()
        .find(|d| d["field_id"] == "total")
        .unwrap();
    let mut amount = old_amount.clone();
    amount["evidence"] = json!("{}");
    amount["value"] = json!("000024.500000");
    let later = library
        .edit_document(&later, DocumentEdit::SetDetail(amount), &Control::default())
        .unwrap();
    let current = library.manifest().unwrap();
    let amount = current
        .rows("details")
        .iter()
        .find(|d| d["field_id"] == "total")
        .unwrap();
    assert_eq!(amount["value"], "000024.500000");
    assert_eq!(amount["evidence"], old_amount["evidence"]);
    let later = library
        .edit_document(
            &later,
            DocumentEdit::RemoveDetail("total".into()),
            &Control::default(),
        )
        .unwrap();
    let later = library
        .edit_document(&later, DocumentEdit::Filing(vec![]), &Control::default())
        .unwrap();
    assert!(
        library
            .search("newnotesentinel", Some("custom-first"), Sort::Name, 10)
            .unwrap()
            .is_empty()
    );
    let later = library
        .edit_document(
            &later,
            DocumentEdit::PageAppearance {
                page: synthetic::id(101),
                rotation: 180,
                crop: "".into(),
                enhancement: "original".into(),
                flatten: false,
            },
            &Control::default(),
        )
        .unwrap();
    assert_eq!(
        library
            .search("laterpagesentinel", None, Sort::Name, 10)
            .unwrap()
            .len(),
        1
    ); // Stale OCR is retained, excluded from index.
    let later = library
        .commit_page_text(
            &later,
            &synthetic::id(101),
            3,
            "replacementcurrenttext",
            "new-config",
            &Control::default(),
        )
        .unwrap();
    assert_eq!(
        library
            .search("replacementcurrenttext", None, Sort::Name, 10)
            .unwrap()
            .len(),
        1
    );
    let later = library
        .edit_document(
            &later,
            DocumentEdit::PageOrder(vec![synthetic::id(101)]),
            &Control::default(),
        )
        .unwrap();
    assert_eq!(library.status().unwrap().originals, 7);
    assert!(library.status().unwrap().tombstones >= 1);
    library
        .delete_document(&later, &Control::default())
        .unwrap();
    assert!(library.token(&later.id).is_err());
    assert!(
        library
            .commit_page_text(
                &later,
                &synthetic::id(101),
                3,
                "resurrect",
                "x",
                &Control::default()
            )
            .is_err()
    );
    assert!(
        root.join(format!("originals/{}/source", synthetic::id(1)))
            .is_file()
    );
    let mut frozen = Vec::new();
    snapshot.write(&mut frozen, &Control::default()).unwrap();
    let restored = stack_archive::read_into(
        &mut Cursor::new(frozen),
        &dir.path().join("snapshot-read"),
        &SyntheticBytes,
        &Control::default(),
    )
    .unwrap();
    assert_eq!(restored.manifest.json(), old.json());
    drop(snapshot);
    let receipt = restore(&mut library, &bytes);
    assert_ne!(
        receipt.mappings.documents[&synthetic::id(1)],
        synthetic::id(1)
    );
    let events = library.journal().unwrap();
    for (i, event) in events.iter().enumerate() {
        assert_eq!(event["sequence"], (i + 1) as i64);
        let replica = event["replica_id"].as_str().unwrap();
        assert_eq!(event["context"][replica], i as i64);
    }
    drop(library);
    let library = Library::open(&root).unwrap();
    assert_eq!(library.journal().unwrap(), events);
    assert!(library.token(&synthetic::id(1)).is_err());
}

#[test]
fn review_is_read_only_stale_plans_fail_and_retries_are_idempotent() {
    let dir = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&dir.path().join("fixture"));
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    let staged = library
        .stage_archive(
            &mut Cursor::new(&bytes),
            &SyntheticBytes,
            &Control::default(),
        )
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    assert_eq!(library.status().unwrap().documents, 0);
    assert_eq!(library.journal().unwrap().len(), 1);
    assert_eq!(fs::read_dir(root.join("originals")).unwrap().count(), 0);
    library
        .edit_stacks(
            library.revision().unwrap(),
            StackEdit::Visibility {
                id: "bills".into(),
                visible: false,
            },
            &Control::default(),
        )
        .unwrap();
    assert!(matches!(
        library.commit_archive(&plan, &Control::default()),
        Err(Error::Stale)
    ));
    drop(plan);
    drop(staged);
    let staged = library
        .stage_archive(
            &mut Cursor::new(&bytes),
            &SyntheticBytes,
            &Control::default(),
        )
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    drop(staged); // Plan owns its staging lease.
    let first = library.commit_archive(&plan, &Control::default()).unwrap();
    let second = library.commit_archive(&plan, &Control::default()).unwrap();
    assert_eq!(first.operation, second.operation);
    assert_eq!(library.status().unwrap().documents, 5);
    assert_eq!(library.manifest().unwrap().rows("stacks")[0]["visible"], 0); // Existing catalog was edited; remains.
    drop(plan);
    assert_eq!(fs::read_dir(root.join("operations")).unwrap().count(), 0);
}

#[test]
fn injected_restore_failures_recover_without_partial_graph_or_sequence_gaps() {
    let dir = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&dir.path().join("fixture"));
    for checkpoint in [
        "staged",
        "journal",
        "original-1",
        "original-2",
        "db_transaction",
        "db_committed",
        "cleanup",
    ] {
        let root = dir.path().join(checkpoint);
        let mut library = Library::open(&root).unwrap();
        let expected = checkpoint.to_owned();
        library.set_probe(Box::new(move |point| {
            if point == expected {
                Err(std::io::Error::from_raw_os_error(28))
            } else {
                Ok(())
            }
        }));
        if let Ok(staged) = library.stage_archive(
            &mut Cursor::new(&bytes),
            &SyntheticBytes,
            &Control::default(),
        ) {
            let plan = library.review_archive(&staged).unwrap();
            assert!(library.commit_archive(&plan, &Control::default()).is_err());
        }
        drop(library);
        let library = Library::open(&root).unwrap();
        let committed = matches!(checkpoint, "db_committed" | "cleanup");
        assert_eq!(
            library.status().unwrap().documents,
            if committed { 5 } else { 0 },
            "{checkpoint}"
        );
        assert_eq!(
            fs::read_dir(root.join("originals")).unwrap().count(),
            if committed { 5 } else { 0 },
            "{checkpoint}"
        );
        assert_eq!(
            library.journal().unwrap().len(),
            if committed { 2 } else { 1 }
        );
        assert_eq!(fs::read_dir(root.join("operations")).unwrap().count(), 0);
    }
}

#[test]
fn failed_edits_cancellation_exports_and_snapshot_lock_lifetime() {
    let dir = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&dir.path().join("fixture"));
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let token = library.token(&synthetic::id(1)).unwrap();
    let before = library.journal().unwrap();
    library.set_probe(Box::new(|_| Err(std::io::Error::from_raw_os_error(28))));
    assert!(
        library
            .edit_document(
                &token,
                DocumentEdit::Rename("uncommitted".into()),
                &Control::default()
            )
            .is_err()
    );
    assert_eq!(library.token(&token.id).unwrap(), token);
    assert_eq!(library.journal().unwrap(), before);
    library.set_probe(Box::new(|_| Ok(())));
    let control = Control::default();
    control.cancel();
    assert!(library.delete_document(&token, &control).is_err());
    let destination = dir.path().join("export.stackbackup");
    library
        .export_archive(&destination, &Control::default())
        .unwrap();
    let first = fs::read(&destination).unwrap();
    assert!(
        library
            .export_archive(&destination, &Control::default())
            .is_err()
    );
    assert_eq!(fs::read(&destination).unwrap(), first);
    for checkpoint in ["export_target", "export_written", "export_published"] {
        let expected = checkpoint.to_owned();
        library.set_probe(Box::new(move |point| {
            if point == expected {
                Err(std::io::Error::from_raw_os_error(28))
            } else {
                Ok(())
            }
        }));
        let target = dir.path().join(checkpoint);
        assert!(
            library
                .export_archive(&target, &Control::default())
                .is_err()
        );
        assert_eq!(target.exists(), checkpoint == "export_published");
        assert!(
            !fs::read_dir(dir.path()).unwrap().any(|e| e
                .unwrap()
                .file_name()
                .to_string_lossy()
                .ends_with(".part"))
        );
    }
    let snapshot = library.snapshot(&Control::default()).unwrap();
    drop(library);
    assert!(matches!(Library::open(&root), Err(Error::Busy)));
    drop(snapshot);
    Library::open(&root).unwrap();
}

#[test]
fn stack_edits_preserve_documents_and_hidden_order_and_raw_ingest_is_atomic() {
    let dir = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&dir.path().join("fixture"));
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let created = library
        .edit_stacks(
            library.revision().unwrap(),
            StackEdit::Create {
                name: "New stack".into(),
                color: -1,
                visible: false,
            },
            &Control::default(),
        )
        .unwrap()
        .unwrap();
    let order = vec![
        created.clone(),
        "custom-last".into(),
        "receipts".into(),
        "custom-first".into(),
    ];
    library
        .edit_stacks(
            library.revision().unwrap(),
            StackEdit::Order(order.clone()),
            &Control::default(),
        )
        .unwrap();
    library
        .edit_stacks(
            library.revision().unwrap(),
            StackEdit::Rename {
                id: created.clone(),
                name: "Renamed".into(),
            },
            &Control::default(),
        )
        .unwrap();
    assert_eq!(library.manifest().unwrap().rows("stacks")[0]["visible"], 0);
    let before = library.token(&synthetic::id(1)).unwrap();
    library
        .edit_stacks(
            library.revision().unwrap(),
            StackEdit::Delete("custom-first".into()),
            &Control::default(),
        )
        .unwrap();
    assert_eq!(library.status().unwrap().documents, 5);
    assert_ne!(before, library.token(&before.id).unwrap());
    assert_eq!(library.status().unwrap().tombstones, 1);
    let graph = library.manifest().unwrap();
    let choice = graph
        .rows("choices")
        .iter()
        .find(|c| c["document_id"] == synthetic::id(1) && c["field"] == "filing")
        .unwrap();
    assert_eq!(choice["value"], "");
    let path: PathBuf = dir.path().join("synthetic.pdf");
    fs::write(&path, b"pdf").unwrap();
    library
        .import_original(
            &path,
            "Synthetic raw PDF",
            &SyntheticBytes,
            "application/pdf",
            2,
            &Control::default(),
        )
        .unwrap();
    assert_eq!(library.status().unwrap().documents, 6);
    assert!(
        library
            .import_original(
                &path,
                "Wrong count",
                &SyntheticBytes,
                "application/pdf",
                3,
                &Control::default()
            )
            .is_err()
    );
    assert_eq!(library.status().unwrap().documents, 6);
    assert!(
        library
            .import_original(
                &path,
                "Too many",
                &SyntheticBytes,
                "application/pdf",
                u32::MAX,
                &Control::default()
            )
            .is_err()
    );
}

#[test]
fn detail_projection_pages_reach_all_slots_without_losing_exact_values_or_evidence() {
    let directory = tempfile::tempdir().unwrap();
    let fixture_root = directory.path().join("fixture");
    let (manifest, _) = fixture(&fixture_root);
    let mut graph = manifest.into_json();
    for index in 0..201 {
        graph["details"].as_array_mut().unwrap().push(json!({"document_id":synthetic::id(1),"field_id":format!("zextra:{index:03}"),"kind":"text","label":format!("Authored field {index}"),"value":"0000123","qualifier":"","origin":"manual","state":"saved","evidence":"{}","revision":1}));
    }
    let manifest = Manifest::from_value(graph).unwrap();
    let mut bytes = vec![];
    stack_archive::write(&manifest, &fixture_root, &mut bytes, &Control::default()).unwrap();
    let root = directory.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let first = library.document_view(&synthetic::id(1)).unwrap();
    let second = library.document_view_page(&synthetic::id(1), 200).unwrap();
    assert_eq!(first.details.len(), 200);
    assert_eq!(second.details.len(), 10);
    assert_eq!(first.detail_count, 210);
    let ids: std::collections::BTreeSet<_> = first
        .details
        .iter()
        .chain(second.details.iter())
        .map(|d| d["field_id"].as_str().unwrap())
        .collect();
    assert_eq!(ids.len(), 210);
    let mut detail = second.details[0].clone();
    let field = detail["field_id"].clone();
    detail["value"] = json!("0000001");
    let token = library
        .edit_document(
            &second.token,
            DocumentEdit::SetDetail(detail),
            &Control::default(),
        )
        .unwrap();
    drop(library);
    let library = Library::open(&root).unwrap();
    let reopened = library.document_view_page(&token.id, 200).unwrap();
    assert_eq!(reopened.token, token);
    assert!(
        reopened
            .details
            .iter()
            .any(|d| d["field_id"] == field && d["value"] == "0000001")
    );
    let original = library.document_view(&token.id).unwrap();
    assert_eq!(
        original
            .details
            .iter()
            .find(|d| d["field_id"] == "total")
            .unwrap()["evidence"],
        manifest
            .rows("details")
            .iter()
            .find(|d| d["field_id"] == "total")
            .unwrap()["evidence"]
    );
}

#[test]
fn transferred_staging_does_not_hold_sqlite_and_direct_cancel_keeps_saved_edits() {
    use std::{io::Read, sync::mpsc, thread};
    struct Gate {
        input: Cursor<Vec<u8>>,
        started: Option<mpsc::Sender<()>>,
        release: mpsc::Receiver<()>,
    }
    impl Read for Gate {
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            if let Some(started) = self.started.take() {
                started.send(()).unwrap();
                self.release.recv().unwrap();
            }
            self.input.read(buffer)
        }
    }
    let directory = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&directory.path().join("fixture"));
    let root = directory.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let work = library.prepare_import().unwrap();
    let control = Control::default();
    let job_control = control.clone();
    let (started, wait_started) = mpsc::channel();
    let (release, wait_release) = mpsc::channel();
    let job = thread::spawn(move || {
        work.archive(
            &mut Gate {
                input: Cursor::new(bytes),
                started: Some(started),
                release: wait_release,
            },
            &SyntheticBytes,
            &job_control,
        )
    });
    wait_started.recv().unwrap();
    assert_eq!(
        library.search("", None, Sort::Newest, 100).unwrap().len(),
        5
    );
    let token = library.token(&synthetic::id(1)).unwrap();
    library
        .edit_document(
            &token,
            DocumentEdit::Notes("Saved while transfer waits".into()),
            &Control::default(),
        )
        .unwrap();
    drop(library);
    assert!(matches!(Library::open(&root), Err(Error::Busy)));
    control.cancel();
    release.send(()).unwrap();
    assert!(matches!(
        job.join().unwrap(),
        Err(Error::Archive(stack_archive::Error::Canceled))
    ));
    let library = Library::open(&root).unwrap();
    assert_eq!(library.status().unwrap().documents, 5);
    assert_eq!(
        library.document_view(&token.id).unwrap().notes,
        "Saved while transfer waits"
    );
    assert_eq!(fs::read_dir(root.join("operations")).unwrap().count(), 0);
}

#[test]
fn transferred_snapshot_keeps_its_saved_graph_and_originals_after_later_deletion() {
    let directory = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&directory.path().join("fixture"));
    let root = directory.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let before = canonical(library.manifest().unwrap().into_json());
    let work = library.prepare_snapshot(&Control::default()).unwrap();
    let output = library.prepare_output().unwrap();
    let token = library.token(&synthetic::id(1)).unwrap();
    library
        .delete_document(&token, &Control::default())
        .unwrap();
    drop(library);
    assert!(matches!(Library::open(&root), Err(Error::Busy)));
    let snapshot = std::thread::spawn(move || work.pin(&Control::default()).unwrap())
        .join()
        .unwrap();
    assert_eq!(canonical(snapshot.manifest.json().clone()), before);
    assert_eq!(
        fs::read(snapshot.original(&token.id, "source").unwrap()).unwrap(),
        b"pdf"
    );
    let target = directory.path().join("frozen.stackbackup");
    output
        .publish(&target, &Control::default(), |file, control| {
            snapshot.write(file, control)
        })
        .unwrap();
    assert!(target.is_file());
    assert!(matches!(Library::open(&root), Err(Error::Busy)));
    drop(snapshot);
    let library = Library::open(&root).unwrap();
    assert_eq!(library.status().unwrap().documents, 4);
    assert_eq!(
        fs::read(root.join("originals").join(&token.id).join("source")).unwrap(),
        b"pdf"
    );
    assert_eq!(fs::read_dir(root.join("operations")).unwrap().count(), 0);
}

#[test]
fn committed_cleanup_retry_uses_receipt_without_reimporting_or_consuming_an_event() {
    let directory = tempfile::tempdir().unwrap();
    let (_, bytes) = fixture(&directory.path().join("fixture"));
    let root = directory.path().join("library");
    let mut library = Library::open(&root).unwrap();
    restore(&mut library, &bytes);
    let work = library.prepare_import().unwrap();
    let staged = work
        .archive(
            &mut Cursor::new(&bytes),
            &SyntheticBytes,
            &Control::default(),
        )
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    let operation = plan.review().operation.clone();
    library.set_probe(Box::new(|point| {
        if point == "db_committed" {
            Err(std::io::Error::other("synthetic post-commit failure"))
        } else {
            Ok(())
        }
    }));
    assert!(matches!(
        library.commit_archive(&plan, &Control::default()),
        Err(Error::CommittedCleanup(_))
    ));
    let receipt = library.committed_receipt(&operation).unwrap().unwrap();
    let events = library.journal().unwrap();
    drop(plan);
    drop(staged);
    assert!(
        root.join("operations")
            .join(&operation)
            .join("journal.json")
            .is_file()
    );
    assert_eq!(
        library.retry_committed(&operation).unwrap().operation,
        receipt.operation
    );
    assert_eq!(
        library.retry_committed(&operation).unwrap().operation,
        receipt.operation
    );
    assert_eq!(library.status().unwrap().documents, 10);
    assert_eq!(library.journal().unwrap(), events);
    drop(library);
    let library = Library::open(&root).unwrap();
    assert_eq!(library.status().unwrap().documents, 10);
    assert_eq!(fs::read_dir(root.join("operations")).unwrap().count(), 0);
}

#[test]
fn checked_output_is_private_until_validation_and_cancel_preserves_destinations() {
    use std::io::Write;
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("library");
    let library = Library::open(&root).unwrap();
    let target = dir.path().join("document.pdf");
    let control = Control::default();
    let result = library.prepare_output().unwrap().publish_checked(
        &target,
        &control,
        |file, _| {
            file.write_all(b"authored invalid PDF")?;
            Ok(())
        },
        |part, _| {
            assert!(!target.exists());
            assert_eq!(fs::read(part)?, b"authored invalid PDF");
            Err(Error::Invalid("test validator rejected output"))
        },
    );
    assert!(result.is_err());
    assert!(!target.exists());
    let cancel = Control::default();
    let result = library.prepare_output().unwrap().publish_checked(
        &target,
        &cancel,
        |file, _| {
            file.write_all(b"complete but canceled")?;
            Ok(())
        },
        |_, control| {
            control.cancel();
            Ok(())
        },
    );
    assert!(result.is_err());
    assert!(!target.exists());
    assert_eq!(fs::read_dir(root.join("operations")).unwrap().count(), 0);
    assert!(!fs::read_dir(dir.path()).unwrap().any(|entry| {
        entry
            .unwrap()
            .file_name()
            .to_string_lossy()
            .ends_with(".part")
    }));
    assert_eq!(library.status().unwrap().documents, 0);
}
