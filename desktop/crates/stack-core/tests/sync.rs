//! Real SQLite and actual exchange files; original bytes are independently
//! authored state fixtures, not native renderer/device compatibility evidence.
use serde_json::Value;
use stack_archive::{Control, SourceVerifier};
use stack_core::{DocumentEdit, Sort, library::Library};
struct FixtureVerifier;
impl SourceVerifier for FixtureVerifier {
    fn verify(&self, _: &std::path::Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
        Ok(())
    }
}
fn exchange(from: &Library, to: &mut Library, path: &std::path::Path) {
    from.sync_export(&to.status().unwrap().replica_id, path)
        .unwrap();
    to.sync_import(path, None).unwrap();
}
fn graph(l: &Library) -> Value {
    let mut v = l.manifest().unwrap().into_json();
    v["created_at"] = Value::Null;
    v
}

#[test]
fn three_real_replicas_adopt_exchange_conflicts_resolve_replay_and_delete() {
    let root = tempfile::tempdir().unwrap();
    let aroot = root.path().join("a");
    let broot = root.path().join("b");
    let croot = root.path().join("c");
    let mut a = Library::open(&aroot).unwrap();
    let mut b = Library::open(&broot).unwrap();
    let mut c = Library::open(&croot).unwrap();
    let original = root.path().join("authored.png");
    std::fs::write(
        &original,
        b"authored sync state original; not renderer evidence",
    )
    .unwrap();
    a.import_original(
        &original,
        "Manual initial",
        &FixtureVerifier,
        "image/png",
        1,
        &Control::default(),
    )
    .unwrap();
    let id = a.search("", None, Sort::Newest, 10).unwrap()[0]
        .document_id
        .clone();
    let members = vec![
        a.status().unwrap().replica_id,
        b.status().unwrap().replica_id,
        c.status().unwrap().replica_id,
    ];
    a.sync_start(&members[1..]).unwrap();
    let library = a.status().unwrap().library_id;
    let baseline = root.path().join("baseline.stackexchange");
    a.sync_export(&members[1], &baseline).unwrap();
    assert!(b.sync_import(&baseline, None).is_err());
    b.sync_import(&baseline, Some((&library, &members)))
        .unwrap();
    c.sync_import(&baseline, Some((&library, &members)))
        .unwrap();
    assert_eq!(graph(&a), graph(&b));
    assert_eq!(graph(&b), graph(&c));
    let token = a.token(&id).unwrap();
    a.edit_document(
        &token,
        DocumentEdit::Rename("From A 🦀".into()),
        &Control::default(),
    )
    .unwrap();
    let token = b.token(&id).unwrap();
    b.edit_document(
        &token,
        DocumentEdit::Notes("Exact 00024.500000 GBP\n0000123".into()),
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("a-b-1"));
    exchange(&b, &mut a, &root.path().join("b-a-1"));
    exchange(&b, &mut c, &root.path().join("b-c-1"));
    assert_eq!(graph(&a), graph(&b));
    assert_eq!(graph(&b), graph(&c));
    assert!(
        a.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty(),
        "{}",
        a.sync_status().unwrap()["conflicts"]
    );
    for (l, title) in [(&mut a, "Concurrent A"), (&mut b, "Concurrent B")] {
        let token = l.token(&id).unwrap();
        l.edit_document(
            &token,
            DocumentEdit::Rename(title.into()),
            &Control::default(),
        )
        .unwrap();
    }
    exchange(&a, &mut b, &root.path().join("a-b-2"));
    exchange(&b, &mut a, &root.path().join("b-a-2"));
    assert!(
        !a.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty(),
        "{}",
        a.sync_status().unwrap()["conflicts"]
    );
    assert!(
        a.export_archive(
            &root.path().join("blocked.stackbackup"),
            &Control::default()
        )
        .is_err()
    );
    let conflicts = a.sync_status().unwrap()["conflicts"]
        .as_object()
        .unwrap()
        .clone();
    for (key, alternatives) in conflicts {
        let alternatives = alternatives.as_array().unwrap();
        let expected = alternatives
            .iter()
            .map(|v| serde_json::from_value(v["id"].clone()).unwrap())
            .collect::<Vec<_>>();
        let value = alternatives
            .iter()
            .find(|v| v["id"]["replica"] == members[0])
            .unwrap_or(&alternatives[0])["value"]
            .clone();
        a.sync_resolve(&key, &expected, value).unwrap();
    }
    exchange(&a, &mut b, &root.path().join("a-b-resolve"));
    exchange(&a, &mut c, &root.path().join("a-c-resolve"));
    assert_eq!(graph(&a), graph(&b));
    assert_eq!(graph(&a), graph(&c));
    let stale = b.token(&id).unwrap();
    let token = a.token(&id).unwrap();
    a.delete_document(&token, &Control::default()).unwrap();
    b.edit_document(
        &stale,
        DocumentEdit::Notes("Offline edit survives in history".into()),
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("a-b-delete"));
    exchange(&b, &mut a, &root.path().join("b-a-delete"));
    assert!(a.token(&id).is_err());
    assert!(b.token(&id).is_err());
    assert!(
        !a.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty(),
        "{}",
        a.sync_status().unwrap()["conflicts"]
    );
    let replay = root.path().join("delete-replay");
    a.sync_export(&members[1], &replay).unwrap();
    b.sync_import(&replay, None).unwrap();
    let before = b.sync_status().unwrap();
    b.sync_import(&replay, None).unwrap();
    assert_eq!(before, b.sync_status().unwrap());
    drop(b);
    let b = Library::open(&broot).unwrap();
    assert!(b.token(&id).is_err());
    assert_eq!(
        std::fs::read(broot.join("originals").join(id).join("source")).unwrap(),
        std::fs::read(original).unwrap()
    );
}

fn joined_two(root: &std::path::Path) -> (Library, Library, String) {
    let mut a = Library::open(&root.join("a")).unwrap();
    let mut b = Library::open(&root.join("b")).unwrap();
    let source = root.join("initial.png");
    std::fs::write(&source, b"independent initial state original").unwrap();
    a.import_original(
        &source,
        "Initial",
        &FixtureVerifier,
        "image/png",
        1,
        &Control::default(),
    )
    .unwrap();
    let id = a.manifest().unwrap().rows("documents")[0]["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let members = vec![
        a.status().unwrap().replica_id,
        b.status().unwrap().replica_id,
    ];
    a.sync_start(&members[1..]).unwrap();
    let baseline = root.join("baseline");
    a.sync_export(&members[1], &baseline).unwrap();
    b.sync_import(&baseline, Some((&a.status().unwrap().library_id, &members)))
        .unwrap();
    (a, b, id)
}
#[test]
fn independent_membership_deltas_and_stack_properties_merge_and_replay_keeps_tokens() {
    use stack_core::StackEdit;
    let root = tempfile::tempdir().unwrap();
    let (mut a, mut b, id) = joined_two(root.path());
    for (library, stack) in [(&mut a, "bills"), (&mut b, "banking")] {
        let token = library.token(&id).unwrap();
        library
            .edit_document(
                &token,
                DocumentEdit::Membership {
                    stack: stack.into(),
                    selected: true,
                },
                &Control::default(),
            )
            .unwrap();
    }
    a.edit_stacks(
        a.revision().unwrap(),
        StackEdit::Rename {
            id: "bills".into(),
            name: "Authored name".into(),
        },
        &Control::default(),
    )
    .unwrap();
    b.edit_stacks(
        b.revision().unwrap(),
        StackEdit::Color {
            id: "bills".into(),
            color: 0xff123456_u32 as i32,
        },
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("a-b"));
    exchange(&b, &mut a, &root.path().join("b-a"));
    assert_eq!(graph(&a), graph(&b));
    assert!(
        a.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty(),
        "{}",
        a.sync_status().unwrap()["conflicts"]
    );
    assert_eq!(
        a.document_view(&id)
            .unwrap()
            .memberships
            .into_iter()
            .collect::<std::collections::BTreeSet<_>>(),
        ["bills".to_owned(), "banking".to_owned()]
            .into_iter()
            .collect()
    );
    let token = b.token(&id).unwrap();
    let revision = b.revision().unwrap();
    b.sync_import(&root.path().join("a-b"), None).unwrap();
    assert_eq!(token, b.token(&id).unwrap());
    assert_eq!(revision, b.revision().unwrap());
}
#[test]
fn explicit_empty_filing_reviews_an_unseen_addition_and_resolution_is_causal() {
    let root = tempfile::tempdir().unwrap();
    let (mut a, mut b, id) = joined_two(root.path());
    let token = a.token(&id).unwrap();
    a.edit_document(&token, DocumentEdit::Filing(vec![]), &Control::default())
        .unwrap();
    let token = b.token(&id).unwrap();
    b.edit_document(
        &token,
        DocumentEdit::Membership {
            stack: "house".into(),
            selected: true,
        },
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("a-b"));
    exchange(&b, &mut a, &root.path().join("b-a"));
    let key = format!("filing_review/{id}");
    let conflicts = a.sync_status().unwrap()["conflicts"].clone();
    let alternatives = conflicts[&key]
        .as_array()
        .expect("explicit empty versus unseen addition requires review");
    let expected = alternatives
        .iter()
        .map(|v| serde_json::from_value(v["id"].clone()).unwrap())
        .collect::<Vec<_>>();
    a.sync_resolve(&key, &expected, serde_json::json!([]))
        .unwrap();
    exchange(&a, &mut b, &root.path().join("resolved"));
    assert!(a.document_view(&id).unwrap().memberships.is_empty());
    assert!(b.document_view(&id).unwrap().memberships.is_empty());
    assert!(
        a.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty(),
        "{}",
        a.sync_status().unwrap()["conflicts"]
    );
    assert_eq!(graph(&a), graph(&b));
}
#[test]
fn pending_future_import_and_blob_survive_restart_then_gap_delivery() {
    use stack_sync::bundle;
    let root = tempfile::tempdir().unwrap();
    let (mut a, mut b, id) = joined_two(root.path());
    for n in 2..=3 {
        let source = root.path().join(format!("authored-{n}.png"));
        std::fs::write(&source, format!("independent immutable blob {n}")).unwrap();
        a.import_original(
            &source,
            &format!("Later {n}"),
            &FixtureVerifier,
            "image/png",
            1,
            &Control::default(),
        )
        .unwrap();
    }
    let all = root.path().join("all");
    a.sync_export(&b.status().unwrap().replica_id, &all)
        .unwrap();
    let blobs = root.path().join("blobs");
    std::fs::create_dir(&blobs).unwrap();
    let header = bundle::read(&mut std::fs::File::open(all).unwrap(), &blobs).unwrap();
    let write = |sequence: u64, path: &std::path::Path| {
        let mut part = header.clone();
        part.operations.retain(|op| op.id.sequence == sequence);
        let hashes = part.operations[0]
            .changes
            .iter()
            .filter(|(k, _)| k.starts_with("files/"))
            .map(|(_, v)| v["sha256"].as_str().unwrap().to_owned())
            .collect::<Vec<_>>();
        part.blobs.retain(|blob| hashes.contains(&blob.sha256));
        bundle::publish(&part, &blobs, path).unwrap();
    };
    let future = root.path().join("future");
    write(3, &future);
    let token = b.token(&id).unwrap();
    let outcome = b.sync_import(&future, None).unwrap();
    assert_eq!(outcome["applied"], 0);
    assert_eq!(outcome["pending_gaps"], 1);
    assert_eq!(token, b.token(&id).unwrap());
    let broot = b.root().to_owned();
    drop(b);
    let mut b = Library::open(&broot).unwrap();
    assert_eq!(b.sync_status().unwrap()["pending_gaps"], 1);
    let gap = root.path().join("gap");
    write(2, &gap);
    b.sync_import(&gap, None).unwrap();
    assert_eq!(b.status().unwrap().documents, 3);
    assert_eq!(b.sync_status().unwrap()["pending_gaps"], 0);
    assert_eq!(graph(&a), graph(&b));
}

#[test]
fn reviewed_existing_library_adoption_defaults_distinct_survives_restart_and_stale_review() {
    let root = tempfile::tempdir().unwrap();
    let mut a = Library::open(&root.path().join("a")).unwrap();
    let mut b = Library::open(&root.path().join("b")).unwrap();
    for library in [&mut a, &mut b] {
        let source = root.path().join("local.png");
        std::fs::write(&source, b"same authored bytes do not imply identity").unwrap();
        library
            .import_original(
                &source,
                "Same title",
                &FixtureVerifier,
                "image/png",
                1,
                &Control::default(),
            )
            .unwrap();
    }
    let peer = b.status().unwrap().replica_id;
    a.sync_start(std::slice::from_ref(&peer)).unwrap();
    let baseline = root.path().join("baseline");
    a.sync_export(&peer, &baseline).unwrap();
    let review = b.sync_review_adoption(&baseline).unwrap();
    let old = b.manifest().unwrap().rows("documents")[0]["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let local_id = review["mappings"]["documents"][&old]
        .as_str()
        .unwrap()
        .to_owned();
    let token = b.token(&old).unwrap();
    b.edit_document(
        &token,
        DocumentEdit::Notes("changed after review".into()),
        &Control::default(),
    )
    .unwrap();
    assert!(b.sync_adopt_reviewed(&baseline, &review).is_err());
    assert!(!b.sync_status().unwrap()["adopted"].as_bool().unwrap());
    let review = b.sync_review_adoption(&baseline).unwrap();
    b.sync_adopt_reviewed(&baseline, &review).unwrap();
    assert_eq!(b.status().unwrap().documents, 2);
    assert_eq!(
        b.document_view(&local_id).unwrap().notes,
        "changed after review"
    );
    assert!(
        b.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty(),
        "{}",
        b.sync_status().unwrap()
    );
    exchange(&b, &mut a, &root.path().join("back"));
    assert_eq!(graph(&a), graph(&b));
    let broot = b.root().to_owned();
    drop(b);
    let b = Library::open(&broot).unwrap();
    assert_eq!(graph(&a), graph(&b));
    assert!(broot.join("originals").join(old).join("source").exists());
    assert!(
        broot
            .join("originals")
            .join(local_id)
            .join("source")
            .exists()
    );
}

#[test]
fn explicitly_matched_existing_decisions_remain_concurrent() {
    let root = tempfile::tempdir().unwrap();
    let mut a = Library::open(&root.path().join("a")).unwrap();
    let mut b = Library::open(&root.path().join("b")).unwrap();
    for (library, title) in [(&mut a, "Source decision"), (&mut b, "Local decision")] {
        let source = root.path().join("local.png");
        std::fs::write(&source, b"authored common original").unwrap();
        library
            .import_original(
                &source,
                title,
                &FixtureVerifier,
                "image/png",
                1,
                &Control::default(),
            )
            .unwrap();
    }
    let peer = b.status().unwrap().replica_id;
    a.sync_start(std::slice::from_ref(&peer)).unwrap();
    let baseline = root.path().join("baseline");
    a.sync_export(&peer, &baseline).unwrap();
    let mut review = b.sync_review_adoption(&baseline).unwrap();
    let am = a.manifest().unwrap();
    let bm = b.manifest().unwrap();
    for table in ["documents", "pages", "stacks", "tags"] {
        let mapping = review["mappings"][table].as_object_mut().unwrap();
        for (local, source) in bm.rows(table).iter().zip(am.rows(table)) {
            mapping.insert(local["id"].as_str().unwrap().into(), source["id"].clone());
        }
    }
    b.sync_adopt_reviewed(&baseline, &review).unwrap();
    assert_eq!(b.status().unwrap().documents, 1);
    let conflicts = b.sync_status().unwrap()["conflicts"].clone();
    assert!(
        conflicts
            .as_object()
            .unwrap()
            .keys()
            .any(|key| key.ends_with("/title")),
        "{conflicts}"
    );
    exchange(&b, &mut a, &root.path().join("back"));
    assert_eq!(
        a.sync_status().unwrap()["conflicts"],
        b.sync_status().unwrap()["conflicts"]
    );
}

#[test]
fn whole_page_and_catalog_orders_and_unseen_page_edits_require_durable_review() {
    use stack_core::StackEdit;
    let root = tempfile::tempdir().unwrap();
    let aroot = root.path().join("a");
    let broot = root.path().join("b");
    let mut a = Library::open(&aroot).unwrap();
    let mut b = Library::open(&broot).unwrap();
    let source = root.path().join("authored.pdf");
    let original = b"Authored three-page state fixture, not native PDF evidence";
    std::fs::write(&source, original).unwrap();
    a.import_original(
        &source,
        "Saved orders",
        &FixtureVerifier,
        "application/pdf",
        3,
        &Control::default(),
    )
    .unwrap();
    let id = a.manifest().unwrap().rows("documents")[0]["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let pages: Vec<_> = a
        .document_view(&id)
        .unwrap()
        .pages
        .iter()
        .map(|p| p.id.clone())
        .collect();
    let members = vec![
        a.status().unwrap().replica_id,
        b.status().unwrap().replica_id,
    ];
    let group = a.status().unwrap().library_id;
    a.sync_start(&members[1..]).unwrap();
    let baseline = root.path().join("orders-baseline");
    a.sync_export(&members[1], &baseline).unwrap();
    b.sync_import(&baseline, Some((&group, &members))).unwrap();

    let left_order = vec![pages[2].clone(), pages[1].clone(), pages[0].clone()];
    let right_order = vec![pages[0].clone(), pages[2].clone(), pages[1].clone()];
    a.edit_document(
        &a.token(&id).unwrap(),
        DocumentEdit::PageOrder(left_order.clone()),
        &Control::default(),
    )
    .unwrap();
    b.edit_document(
        &b.token(&id).unwrap(),
        DocumentEdit::PageOrder(right_order),
        &Control::default(),
    )
    .unwrap();
    let mut left_catalog: Vec<_> = a
        .manifest()
        .unwrap()
        .rows("stacks")
        .iter()
        .map(|s| s["id"].as_str().unwrap().to_owned())
        .collect();
    let mut right_catalog = left_catalog.clone();
    left_catalog.reverse();
    right_catalog.rotate_left(1);
    a.edit_stacks(
        a.revision().unwrap(),
        StackEdit::Order(left_catalog.clone()),
        &Control::default(),
    )
    .unwrap();
    b.edit_stacks(
        b.revision().unwrap(),
        StackEdit::Order(right_catalog),
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("orders-ab"));
    exchange(&b, &mut a, &root.path().join("orders-ba"));
    let page_key = format!("page_order/{id}");
    let conflicts = a.sync_status().unwrap()["conflicts"].clone();
    assert_eq!(conflicts, b.sync_status().unwrap()["conflicts"]);
    assert!(conflicts[&page_key].is_array(), "{conflicts}");
    assert!(conflicts["catalog_order/stacks"].is_array(), "{conflicts}");
    assert!(
        a.export_archive(
            &root.path().join("unreviewed.stackbackup"),
            &Control::default()
        )
        .is_err()
    );
    for (key, chosen) in [
        (&page_key[..], serde_json::json!(left_order)),
        ("catalog_order/stacks", serde_json::json!(left_catalog)),
    ] {
        let expected: Vec<_> = conflicts[key]
            .as_array()
            .unwrap()
            .iter()
            .map(|a| serde_json::from_value(a["id"].clone()).unwrap())
            .collect();
        a.sync_resolve(key, &expected, chosen).unwrap();
    }
    exchange(&a, &mut b, &root.path().join("orders-reviewed"));
    assert_eq!(graph(&a), graph(&b));
    assert!(
        a.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        a.document_view(&id)
            .unwrap()
            .pages
            .iter()
            .map(|p| p.id.clone())
            .collect::<Vec<_>>(),
        left_order
    );

    // Removing a page must review a concurrent edit of that unseen page, even
    // when the editor did not explicitly change the page order itself.
    let removed = pages[1].clone();
    let kept: Vec<_> = left_order.into_iter().filter(|p| p != &removed).collect();
    a.edit_document(
        &a.token(&id).unwrap(),
        DocumentEdit::PageOrder(kept.clone()),
        &Control::default(),
    )
    .unwrap();
    b.edit_document(
        &b.token(&id).unwrap(),
        DocumentEdit::PageAppearance {
            page: removed.clone(),
            rotation: 90,
            crop: String::new(),
            enhancement: "original".into(),
            flatten: false,
        },
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("remove-edit-ab"));
    exchange(&b, &mut a, &root.path().join("remove-edit-ba"));
    let conflicts = a.sync_status().unwrap()["conflicts"].clone();
    assert_eq!(conflicts, b.sync_status().unwrap()["conflicts"]);
    let alternatives = conflicts[&page_key]
        .as_array()
        .expect("unseen appearance edit must be reviewed");
    assert!(alternatives.iter().any(|a| {
        a["value"]
            .as_array()
            .unwrap()
            .contains(&serde_json::json!(removed))
    }));
    let expected: Vec<_> = alternatives
        .iter()
        .map(|a| serde_json::from_value(a["id"].clone()).unwrap())
        .collect();
    a.sync_resolve(&page_key, &expected, serde_json::json!(kept))
        .unwrap();
    exchange(&a, &mut b, &root.path().join("remove-edit-reviewed"));
    let durable = graph(&a);
    assert_eq!(durable, graph(&b));
    assert_eq!(a.document_view(&id).unwrap().pages.len(), 2);
    drop(a);
    drop(b);
    let a = Library::open(&aroot).unwrap();
    let b = Library::open(&broot).unwrap();
    assert_eq!(durable, graph(&a));
    assert_eq!(durable, graph(&b));
    assert!(
        a.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty()
    );
    for library_root in [&aroot, &broot] {
        assert_eq!(
            std::fs::read(library_root.join("originals").join(&id).join("source")).unwrap(),
            original
        );
    }
}

#[test]
fn malformed_partial_cell_rejects_without_state_or_token_changes_and_cancel_never_publishes() {
    use stack_sync::bundle;
    let root = tempfile::tempdir().unwrap();
    let (mut a, mut b, id) = joined_two(root.path());
    let token = a.token(&id).unwrap();
    a.edit_document(
        &token,
        DocumentEdit::Notes("new valid decision".into()),
        &Control::default(),
    )
    .unwrap();
    let path = root.path().join("valid");
    a.sync_export(&b.status().unwrap().replica_id, &path)
        .unwrap();
    let blobs = root.path().join("blobs");
    std::fs::create_dir(&blobs).unwrap();
    let mut header = bundle::read(&mut std::fs::File::open(&path).unwrap(), &blobs).unwrap();
    header.operations.last_mut().unwrap().changes.insert(
        format!("documents/[\"{id}\"]/unknown"),
        serde_json::json!({"secret_field":"unsupported"}),
    );
    let invalid = root.path().join("invalid");
    bundle::publish(&header, &blobs, &invalid).unwrap();
    let before = b.sync_status().unwrap();
    let token = b.token(&id).unwrap();
    assert!(b.sync_import(&invalid, None).is_err());
    assert_eq!(before, b.sync_status().unwrap());
    assert_eq!(token, b.token(&id).unwrap());
    let control = Control::default();
    control.cancel();
    let cancelled = root.path().join("cancelled");
    assert!(
        a.sync_export_control(&b.status().unwrap().replica_id, &cancelled, &control)
            .is_err()
    );
    assert!(!cancelled.exists());
    assert!(b.sync_import_control(&path, None, &control).is_err());
    assert_eq!(before, b.sync_status().unwrap());
    b.sync_import(&path, None).unwrap();
    assert_eq!(b.document_view(&id).unwrap().notes, "new valid decision");
}

#[test]
fn forged_origin_unreferenced_blob_and_malformed_gap_fail_before_receipt_or_retained_state() {
    use stack_sync::bundle;
    let root = tempfile::tempdir().unwrap();
    let (mut a, mut b, id) = joined_two(root.path());
    let token = a.token(&id).unwrap();
    a.edit_document(
        &token,
        DocumentEdit::Notes("legitimate incoming".into()),
        &Control::default(),
    )
    .unwrap();
    let path = root.path().join("valid");
    a.sync_export(&b.status().unwrap().replica_id, &path)
        .unwrap();
    let blobs = root.path().join("blobs");
    std::fs::create_dir(&blobs).unwrap();
    let header = bundle::read(&mut std::fs::File::open(path).unwrap(), &blobs).unwrap();
    let before = b.sync_status().unwrap();
    let initial = b.manifest().unwrap().into_json();
    let mut cases = Vec::new();
    let mut forged = header.clone();
    forged.operations[0].id.replica = b.status().unwrap().replica_id;
    forged.operations[0].id.sequence = 1;
    cases.push(("forged", forged));
    let mut extra = header.clone();
    let bytes = b"unreferenced content must never enter retained cache";
    let sha = stack_archive::digest(bytes);
    std::fs::write(blobs.join(&sha), bytes).unwrap();
    extra.blobs.push(bundle::Blob {
        sha256: sha.clone(),
        length: bytes.len() as u64,
    });
    cases.push(("extra", extra));
    let mut malformed = header.clone();
    malformed.operations[0].id.sequence = 3;
    malformed.operations[0]
        .context
        .insert(malformed.sender.clone(), 2);
    malformed.operations[0].changes.insert(
        "stacks/[\"bills\"]/visible".into(),
        serde_json::json!({"visible":"on"}),
    );
    cases.push(("gap", malformed));
    for (name, header) in cases {
        let path = root.path().join(name);
        bundle::publish(&header, &blobs, &path).unwrap();
        assert!(b.sync_import(&path, None).is_err(), "{name}");
        assert_eq!(before, b.sync_status().unwrap(), "{name}");
        let mut actual = b.manifest().unwrap().into_json();
        actual["created_at"] = initial["created_at"].clone();
        assert_eq!(initial, actual, "{name}");
        assert!(!b.root().join("sync_blobs").join(&sha).exists());
    }
}

#[test]
fn replacing_the_reviewed_file_between_revalidation_and_parse_is_stale() {
    let root = tempfile::tempdir().unwrap();
    let mut a = Library::open(&root.path().join("a")).unwrap();
    let mut b = Library::open(&root.path().join("b")).unwrap();
    let source = root.path().join("original.png");
    std::fs::write(&source, b"authored local original").unwrap();
    a.import_original(
        &source,
        "Source",
        &FixtureVerifier,
        "image/png",
        1,
        &Control::default(),
    )
    .unwrap();
    let peer = b.status().unwrap().replica_id;
    a.sync_start(std::slice::from_ref(&peer)).unwrap();
    let selected = root.path().join("selected");
    a.sync_export(&peer, &selected).unwrap();
    let review = b.sync_review_adoption(&selected).unwrap();
    let id = a.manifest().unwrap().rows("documents")[0]["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let token = a.token(&id).unwrap();
    a.edit_document(
        &token,
        DocumentEdit::Notes("unreviewed later content".into()),
        &Control::default(),
    )
    .unwrap();
    let replacement = root.path().join("replacement");
    a.sync_export(&peer, &replacement).unwrap();
    let changed = selected.clone();
    b.set_probe(Box::new(move |point| {
        if point == "sync_review_revalidated" {
            std::fs::rename(&replacement, &changed)?;
        }
        Ok(())
    }));
    assert!(matches!(
        b.sync_adopt_reviewed(&selected, &review),
        Err(stack_core::library::Error::Stale)
    ));
    assert_eq!(b.sync_status().unwrap()["adopted"], false);
    assert_eq!(b.status().unwrap().documents, 0);
}

#[test]
fn older_receipt_replay_never_regresses_verified_acknowledgment_after_restart() {
    let root = tempfile::tempdir().unwrap();
    let (mut a, mut b, id) = joined_two(root.path());
    let old = root.path().join("old");
    b.sync_export(&a.status().unwrap().replica_id, &old)
        .unwrap();
    let token = a.token(&id).unwrap();
    a.edit_document(
        &token,
        DocumentEdit::Notes("later".into()),
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("forward"));
    exchange(&b, &mut a, &root.path().join("receipt"));
    let before = a.sync_status().unwrap()["acknowledged_by"].clone();
    a.sync_import(&old, None).unwrap();
    assert_eq!(a.sync_status().unwrap()["acknowledged_by"], before);
    let path = a.root().to_owned();
    drop(a);
    let a = Library::open(&path).unwrap();
    assert_eq!(a.sync_status().unwrap()["acknowledged_by"], before);
}

#[test]
fn approved_connection_unions_libraries_without_duplicate_categories_or_document_loss() {
    let root = tempfile::tempdir().unwrap();
    let mut a = Library::open(&root.path().join("desktop")).unwrap();
    let mut b = Library::open(&root.path().join("phone")).unwrap();
    let original = root.path().join("authored.png");
    std::fs::write(&original, b"independent authored originals").unwrap();
    for (library, title) in [(&mut a, "Desktop document"), (&mut b, "Phone document")] {
        library
            .import_original(
                &original,
                title,
                &FixtureVerifier,
                "image/png",
                1,
                &Control::default(),
            )
            .unwrap();
    }
    let aid = a.status().unwrap().replica_id;
    let bid = b.status().unwrap().replica_id;
    a.sync_start(std::slice::from_ref(&bid)).unwrap();
    let file = root.path().join("connect");
    a.sync_export(&bid, &file).unwrap();
    b.sync_merge_connected(&file, &Control::default()).unwrap();
    exchange(&b, &mut a, &root.path().join("return"));
    assert_eq!(a.status().unwrap().documents, 2);
    assert_eq!(graph(&a), graph(&b));
    let catalog = a.manifest().unwrap();
    assert_eq!(catalog.rows("stacks").len(), 7);
    assert!(
        catalog
            .rows("stacks")
            .iter()
            .all(|s| !s["name"].as_str().unwrap().contains("adopt"))
    );
    assert_eq!(a.sync_status().unwrap()["conflicts"], serde_json::json!({}));
    let id = a
        .search("Desktop document", None, Sort::Newest, 10)
        .unwrap()[0]
        .document_id
        .clone();
    a.edit_document(
        &a.token(&id).unwrap(),
        DocumentEdit::Rename("Desktop rename".into()),
        &Control::default(),
    )
    .unwrap();
    b.edit_document(
        &b.token(&id).unwrap(),
        DocumentEdit::Notes("Phone notes 🦀\nExact 00024.500".into()),
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("edits-out"));
    exchange(&b, &mut a, &root.path().join("edits-back"));
    assert_eq!(graph(&a), graph(&b));
    assert_eq!(
        a.document_view(&id).unwrap().notes,
        "Phone notes 🦀\nExact 00024.500"
    );
    assert_eq!(a.document_view(&id).unwrap().title, "Desktop rename");
    a.edit_document(
        &a.token(&id).unwrap(),
        DocumentEdit::Rename("Desktop conflict".into()),
        &Control::default(),
    )
    .unwrap();
    b.edit_document(
        &b.token(&id).unwrap(),
        DocumentEdit::Rename("Phone conflict".into()),
        &Control::default(),
    )
    .unwrap();
    exchange(&a, &mut b, &root.path().join("conflict-out"));
    let status = b.sync_status().unwrap();
    let (key, alternatives) = status["conflicts"]
        .as_object()
        .unwrap()
        .iter()
        .find(|(key, _)| key.starts_with("documents/") && key.ends_with("/title"))
        .unwrap();
    let alternatives = alternatives.as_array().unwrap();
    let expected = Value::Array(alternatives.iter().map(|a| a["id"].clone()).collect());
    let selected = alternatives
        .iter()
        .find(|a| a["value"]["title"] == "Phone conflict")
        .unwrap()["value"]
        .clone();
    b.sync_resolve_json(key, &expected, selected).unwrap();
    assert_eq!(b.sync_status().unwrap()["conflicts"], serde_json::json!({}));
    exchange(&b, &mut a, &root.path().join("decision-back"));
    assert_eq!(a.document_view(&id).unwrap().title, "Phone conflict");
    assert_eq!(a.sync_status().unwrap()["conflicts"], serde_json::json!({}));

    assert!(
        a.sync_status().unwrap()["acknowledged_by"]
            .get(aid)
            .is_none()
    );
}

#[test]
fn independently_restored_same_backup_connects_without_duplicate_documents() {
    let root = tempfile::tempdir().unwrap();
    let mut a = Library::open(&root.path().join("a")).unwrap();
    let mut b = Library::open(&root.path().join("b")).unwrap();
    let original = root.path().join("authored.png");
    std::fs::write(&original, b"authored backup identity").unwrap();
    a.import_original(
        &original,
        "Restored document",
        &FixtureVerifier,
        "image/png",
        1,
        &Control::default(),
    )
    .unwrap();
    let backup = root.path().join("backup");
    a.export_archive(&backup, &Control::default()).unwrap();
    let staged = b
        .stage_archive(
            &mut std::fs::File::open(backup).unwrap(),
            &FixtureVerifier,
            &Control::default(),
        )
        .unwrap();
    let plan = b.review_archive(&staged).unwrap();
    b.commit_archive(&plan, &Control::default()).unwrap();
    let bid = b.status().unwrap().replica_id;
    a.sync_start(std::slice::from_ref(&bid)).unwrap();
    let file = root.path().join("connect");
    a.sync_export(&bid, &file).unwrap();
    b.sync_merge_connected(&file, &Control::default()).unwrap();
    exchange(&b, &mut a, &root.path().join("return"));
    assert_eq!(a.status().unwrap().documents, 1);
    assert_eq!(b.status().unwrap().documents, 1);
    assert_eq!(graph(&a), graph(&b));
    assert_eq!(a.manifest().unwrap().rows("stacks").len(), 7);
}

#[test]
fn legacy_duplicate_starter_cleanup_retains_documents_originals_and_memberships() {
    let root = tempfile::tempdir().unwrap();
    let mut a = Library::open(&root.path().join("a")).unwrap();
    let mut b = Library::open(&root.path().join("b")).unwrap();
    let original = root.path().join("authored.png");
    std::fs::write(&original, b"authored preserved legacy original").unwrap();
    for (library, title) in [(&mut a, "Desktop document"), (&mut b, "Phone document")] {
        library
            .import_original(
                &original,
                title,
                &FixtureVerifier,
                "image/png",
                1,
                &Control::default(),
            )
            .unwrap();
    }
    let id = b.search("", None, Sort::Newest, 10).unwrap()[0]
        .document_id
        .clone();
    b.edit_document(
        &b.token(&id).unwrap(),
        DocumentEdit::Filing(vec!["bills".into()]),
        &Control::default(),
    )
    .unwrap();
    let bid = b.status().unwrap().replica_id;
    a.sync_start(&[bid]).unwrap();
    let file = root.path().join("old-connect");
    a.sync_export(&b.status().unwrap().replica_id, &file)
        .unwrap();
    let plan = b.sync_review_adoption(&file).unwrap();
    b.sync_adopt_reviewed_control(&file, &plan, &Control::default())
        .unwrap();
    let before = b.manifest().unwrap();
    assert_eq!(before.rows("stacks").len(), 14);
    let backup = root.path().join("legacy-backup");
    b.export_archive(&backup, &Control::default()).unwrap();
    let mut restored = Library::open(&root.path().join("restored")).unwrap();
    let staged = restored
        .stage_archive(
            &mut std::fs::File::open(backup).unwrap(),
            &FixtureVerifier,
            &Control::default(),
        )
        .unwrap();
    let plan = restored.review_archive(&staged).unwrap();
    restored.commit_archive(&plan, &Control::default()).unwrap();
    assert_eq!(restored.sync_status().unwrap()["adopted"], false);
    assert_eq!(restored.clean_legacy_sync_stacks().unwrap(), 7);
    assert_eq!(
        restored.manifest().unwrap().rows("documents"),
        before.rows("documents")
    );
    assert_eq!(
        restored.manifest().unwrap().rows("memberships"),
        before.rows("memberships")
    );
    assert_eq!(b.clean_legacy_sync_stacks().unwrap(), 7);
    let after = b.manifest().unwrap();
    assert_eq!(after.rows("stacks").len(), 7);
    for table in ["documents", "pages", "memberships", "files"] {
        assert_eq!(
            before.rows(table),
            after.rows(table),
            "{table} changed during category cleanup"
        );
    }
    assert!(
        !after
            .rows("stacks")
            .iter()
            .any(|s| s["name"].as_str().unwrap().contains("adopt"))
    );
    assert_eq!(b.clean_legacy_sync_stacks().unwrap(), 0);
    exchange(&b, &mut a, &root.path().join("cleaned-return"));
    assert_eq!(graph(&a), graph(&b));
}

#[test]
fn legacy_cleanup_retains_custom_labels_and_used_starter_stacks() {
    let root = tempfile::tempdir().unwrap();
    let mut peer = Library::open(&root.path().join("peer")).unwrap();
    let mut library = Library::open(&root.path().join("library")).unwrap();
    let original = root.path().join("authored.png");
    std::fs::write(&original, b"preserve filing in the used starter stack").unwrap();
    library
        .import_original(
            &original,
            "Filed document",
            &FixtureVerifier,
            "image/png",
            1,
            &Control::default(),
        )
        .unwrap();
    let replica = library.status().unwrap().replica_id;
    peer.sync_start(std::slice::from_ref(&replica)).unwrap();
    let baseline = root.path().join("old-connect");
    peer.sync_export(&replica, &baseline).unwrap();
    let review = library.sync_review_adoption(&baseline).unwrap();
    library
        .sync_adopt_reviewed_control(&baseline, &review, &Control::default())
        .unwrap();
    library
        .edit_stacks(
            library.revision().unwrap(),
            stack_core::StackEdit::Create {
                name: "Banking (adopted 2)".into(),
                color: 0xff112233_u32 as i32,
                visible: true,
            },
            &Control::default(),
        )
        .unwrap();
    let document = library.search("", None, Sort::Newest, 10).unwrap()[0]
        .document_id
        .clone();
    library
        .edit_document(
            &library.token(&document).unwrap(),
            DocumentEdit::Filing(vec!["ids".into()]),
            &Control::default(),
        )
        .unwrap();
    let before = graph(&library);
    assert_eq!(library.clean_legacy_sync_stacks().unwrap(), 6);
    let after = graph(&library);
    for table in ["documents", "pages", "memberships", "files"] {
        assert_eq!(
            after[table], before[table],
            "{table} changed during cleanup"
        );
    }
    let stacks = after["stacks"].as_array().unwrap();
    for name in ["IDs", "IDs (adopted 1)", "Banking (adopted 2)"] {
        assert!(stacks.iter().any(|row| row["name"] == name));
    }
    assert_eq!(library.clean_legacy_sync_stacks().unwrap(), 0);
}
