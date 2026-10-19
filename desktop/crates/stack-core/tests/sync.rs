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
    let root=tempfile::tempdir().unwrap();
    let mut a=Library::open(&root.path().join("a")).unwrap();
    let mut b=Library::open(&root.path().join("b")).unwrap();
    for library in [&mut a,&mut b] {
        let source=root.path().join("local.png");
        std::fs::write(&source,b"same authored bytes do not imply identity").unwrap();
        library.import_original(&source,"Same title",&FixtureVerifier,"image/png",1,&Control::default()).unwrap();
    }
    let peer=b.status().unwrap().replica_id;
    a.sync_start(&[peer.clone()]).unwrap();
    let baseline=root.path().join("baseline");a.sync_export(&peer,&baseline).unwrap();
    let review=b.sync_review_adoption(&baseline).unwrap();
    let old=b.manifest().unwrap().rows("documents")[0]["id"].as_str().unwrap().to_owned();
    let local_id=review["mappings"]["documents"][&old].as_str().unwrap().to_owned();
    let token=b.token(&old).unwrap();
    b.edit_document(&token,DocumentEdit::Notes("changed after review".into()),&Control::default()).unwrap();
    assert!(b.sync_adopt_reviewed(&baseline,&review).is_err());
    assert!(!b.sync_status().unwrap()["adopted"].as_bool().unwrap());
    let review=b.sync_review_adoption(&baseline).unwrap();
    b.sync_adopt_reviewed(&baseline,&review).unwrap();
    assert_eq!(b.status().unwrap().documents,2);
    assert_eq!(b.document_view(&local_id).unwrap().notes,"changed after review");
    assert!(b.sync_status().unwrap()["conflicts"].as_object().unwrap().is_empty(),"{}",b.sync_status().unwrap());
    exchange(&b,&mut a,&root.path().join("back"));
    assert_eq!(graph(&a),graph(&b));
    let broot=b.root().to_owned();drop(b);let b=Library::open(&broot).unwrap();
    assert_eq!(graph(&a),graph(&b));
    assert!(broot.join("originals").join(old).join("source").exists());
    assert!(broot.join("originals").join(local_id).join("source").exists());
}

#[test]
fn explicitly_matched_existing_decisions_remain_concurrent() {
    let root=tempfile::tempdir().unwrap();let mut a=Library::open(&root.path().join("a")).unwrap();let mut b=Library::open(&root.path().join("b")).unwrap();
    for (library,title) in [(&mut a,"Source decision"),(&mut b,"Local decision")] {
        let source=root.path().join("local.png");std::fs::write(&source,b"authored common original").unwrap();
        library.import_original(&source,title,&FixtureVerifier,"image/png",1,&Control::default()).unwrap();
    }
    let peer=b.status().unwrap().replica_id;a.sync_start(&[peer.clone()]).unwrap();let baseline=root.path().join("baseline");a.sync_export(&peer,&baseline).unwrap();
    let mut review=b.sync_review_adoption(&baseline).unwrap();
    let am=a.manifest().unwrap();let bm=b.manifest().unwrap();
    for table in ["documents","pages","stacks","tags"] {
        let mapping=review["mappings"][table].as_object_mut().unwrap();
        for (local,source) in bm.rows(table).iter().zip(am.rows(table)) {mapping.insert(local["id"].as_str().unwrap().into(),source["id"].clone());}
    }
    b.sync_adopt_reviewed(&baseline,&review).unwrap();
    assert_eq!(b.status().unwrap().documents,1);
    let conflicts=b.sync_status().unwrap()["conflicts"].clone();
    assert!(conflicts.as_object().unwrap().keys().any(|key| key.ends_with("/title")),"{conflicts}");
    exchange(&b,&mut a,&root.path().join("back"));
    assert_eq!(a.sync_status().unwrap()["conflicts"],b.sync_status().unwrap()["conflicts"]);
}
