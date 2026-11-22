//! Archive format 4 and encrypted backups restored into a desktop library. Synthetic data and synthetic keys only.
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_archive::crypto::{
    MIN_ITERATIONS, RecoveryKey, SLOT_PASSPHRASE, SLOT_RECOVERY, Slot, encrypt_to,
    passphrase_slot_key, recovery_slot_key,
};
use stack_archive::{Control, Manifest, SourceVerifier, synthetic, write};
use stack_core::{BackupKey, Sort, library::Library};
use std::{
    fs,
    io::{Cursor, Write},
    path::Path,
};

struct Accept;
impl SourceVerifier for Accept {
    fn verify(&self, _: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
        Ok(())
    }
}

const PDF: &[u8] = b"%PDF-1.4 synthetic preview";
const KEY_TEXT: &str = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

fn key_bytes() -> [u8; 32] {
    let mut b = [0u8; 32];
    b.iter_mut().enumerate().for_each(|(i, x)| *x = i as u8 + 1);
    b
}

fn link(n: u128, state: &str, hash: char, text: &str) -> Value {
    json!({"id":synthetic::id(5000 + n),"owner":"org.example.amipro","link_id":synthetic::id(7000 + n),"kind":"doc",
        "title":format!("Linked {n}"),"mime":"application/vnd.example+json","modified":1700000000000_i64,
        "hash":hash.to_string().repeat(64),"shared":0,"protected":1,
        "locators":"[{\"type\":\"filen\",\"uuid\":\"00000000-0000-0000-0000-000000000009\",\"path\":\"/a/b\"},{\"type\":\"saf\",\"uri\":\"content://x\"}]",
        "state":state,"title_user_edited":0,"added_at":5 + n as i64,"text":text})
}

/// A plain format 4 stream: three synthetic documents' worth of library plus the given links (link 1 has a preview).
fn inner(dir: &Path, links: Vec<Value>) -> Vec<u8> {
    fs::create_dir_all(dir).unwrap();
    let root = dir.join("source");
    let base = synthetic::create(&root, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap();
    let mut value = base.into_json();
    value["version"] = json!(4);
    value["links"] = json!(links);
    let with_preview = value["links"]
        .as_array()
        .unwrap()
        .iter()
        .any(|l| l["id"] == json!(synthetic::id(5001)));
    if with_preview {
        value["files"].as_array_mut().unwrap().push(
            json!({"path":format!("previews/{}.pdf",synthetic::id(5001)),
            "length":PDF.len(),"sha256":format!("{:x}",Sha256::digest(PDF))}),
        );
    }
    let manifest = Manifest::from_value(value).unwrap();
    if with_preview {
        fs::create_dir(root.join("previews")).unwrap();
        fs::write(
            root.join(format!("previews/{}.pdf", synthetic::id(5001))),
            PDF,
        )
        .unwrap();
    }
    let mut out = Vec::new();
    write(&manifest, &root, &mut out, &Control::default()).unwrap();
    out
}

fn envelope(plain: &[u8], with_passphrase: bool) -> Vec<u8> {
    let salt = [3u8; 16];
    let recovery = recovery_slot_key(&RecoveryKey::from_bytes(key_bytes()), &salt);
    let pass = passphrase_slot_key("correct horse battery", &salt, MIN_ITERATIONS).unwrap();
    let mut slots = vec![Slot {
        slot_type: SLOT_RECOVERY,
        key: &recovery,
    }];
    if with_passphrase {
        slots.push(Slot {
            slot_type: SLOT_PASSPHRASE,
            key: &pass,
        });
    }
    let mut seed = 0u8;
    let mut rand = |b: &mut [u8]| {
        b.iter_mut().for_each(|x| {
            seed = seed.wrapping_add(1);
            *x = seed
        })
    };
    let mut file = Vec::new();
    let mut writer = encrypt_to(&mut file, &salt, MIN_ITERATIONS, &slots, &mut rand).unwrap();
    writer.write_all(plain).unwrap();
    writer.finish().unwrap();
    file
}

fn restore(
    library: &mut Library,
    bytes: &[u8],
    key: Option<&BackupKey>,
) -> stack_core::RestoreReceipt {
    let control = Control::default();
    let staged = library
        .stage_archive_with_key(&mut Cursor::new(bytes), key, &Accept, &control)
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    library.commit_archive(&plan, &control).unwrap()
}

fn files_in(dir: &Path) -> usize {
    fs::read_dir(dir).unwrap().count()
}

fn two_links() -> Vec<Value> {
    vec![
        link(1, "live", 'a', "quarterly zebra budget"),
        link(2, "trashed", 'b', "second note"),
    ]
}

#[test]
fn encrypted_format4_restores_links_read_only_and_unreachable() {
    let dir = tempfile::tempdir().unwrap();
    let file = envelope(&inner(dir.path(), two_links()), false);
    let mut library = Library::open(&dir.path().join("library")).unwrap();
    let key =
        BackupKey::recovery(format!("{} {}", &KEY_TEXT[..32], &KEY_TEXT[32..]).to_uppercase());
    let receipt = restore(&mut library, &file, Some(&key));
    assert!(receipt.encrypted);
    assert_eq!((receipt.links_added, receipt.links_skipped), (2, 0));
    assert_eq!(receipt.source_version, 4);
    assert!(receipt.summary().contains("2 linked documents imported"));
    assert!(!receipt.summary().contains("not encrypted"));
    assert_eq!(library.link_count().unwrap(), 2);

    let hits = library.search("zebra", None, Sort::Newest, 100).unwrap();
    assert_eq!(hits.len(), 1);
    assert!(hits[0].linked);
    assert_eq!(hits[0].title, "Linked 1");
    assert!(hits[0].snippet.contains("zebra"));
    // By title too, and unfiltered browse lists documents and links together.
    assert_eq!(
        library
            .search("Linked", None, Sort::Name, 100)
            .unwrap()
            .len(),
        2
    );
    let all = library.search("", None, Sort::Newest, 100).unwrap();
    assert_eq!(all.len(), 5 + 2);
    assert_eq!(all.iter().filter(|h| h.linked).count(), 2);
    // A stack filter never includes links.
    let stack = library.stacks().unwrap()[0].id.clone();
    assert!(
        library
            .search("", Some(&stack), Sort::Newest, 100)
            .unwrap()
            .iter()
            .all(|h| !h.linked)
    );

    let id = &hits[0].document_id;
    let view = library.linked_document(id).unwrap();
    assert_eq!(view.state, "unreachable"); // archive said "live"
    assert_eq!(view.text, "quarterly zebra budget");
    assert_eq!(view.owner, "org.example.amipro");
    assert_eq!(fs::read(view.preview.as_ref().unwrap()).unwrap(), PDF);
    let second = library.search("second", None, Sort::Newest, 100).unwrap();
    let view2 = library.linked_document(&second[0].document_id).unwrap();
    assert_eq!(view2.state, "unreachable"); // archive said "trashed"
    assert!(view2.preview.is_none());

    // Staging is gone; reopening keeps the records and the preview.
    assert_eq!(files_in(&library.root().join("operations")), 0);
    let root = library.root().to_path_buf();
    drop(library);
    let library = Library::open(&root).unwrap();
    assert_eq!(library.link_count().unwrap(), 2);
    assert_eq!(
        library
            .search("zebra", None, Sort::Newest, 10)
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        fs::read(library.linked_document(id).unwrap().preview.unwrap()).unwrap(),
        PDF
    );
}

#[test]
fn passphrase_opens_the_same_backup() {
    let dir = tempfile::tempdir().unwrap();
    let file = envelope(&inner(dir.path(), two_links()), true);
    let mut library = Library::open(&dir.path().join("library")).unwrap();
    let receipt = restore(
        &mut library,
        &file,
        Some(&BackupKey::passphrase("correct horse battery".into())),
    );
    assert_eq!(receipt.links_added, 2);
}

#[test]
fn existing_links_are_kept_as_is_and_counted_as_skipped() {
    let dir = tempfile::tempdir().unwrap();
    let key = BackupKey::recovery(KEY_TEXT.into());
    let first = envelope(&inner(&dir.path().join("a"), two_links()), false);
    let mut library = Library::open(&dir.path().join("library")).unwrap();
    restore(&mut library, &first, Some(&key));
    // Same (owner, link_id), different hash and text, plus one new link.
    let mut changed = link(1, "missing", 'c', "replacement text");
    changed["id"] = json!(synthetic::id(5101));
    let mut fresh = link(3, "live", 'd', "third note");
    fresh["id"] = json!(synthetic::id(5103));
    let second = envelope(&inner(&dir.path().join("b"), vec![changed, fresh]), false);
    let receipt = restore(&mut library, &second, Some(&key));
    assert_eq!((receipt.links_added, receipt.links_skipped), (1, 1));
    assert_eq!(library.link_count().unwrap(), 3);
    let kept = library.search("zebra", None, Sort::Newest, 10).unwrap();
    assert_eq!(
        library.linked_document(&kept[0].document_id).unwrap().hash,
        "a".repeat(64)
    );
    assert!(
        library
            .search("replacement", None, Sort::Newest, 10)
            .unwrap()
            .is_empty()
    );
    // The very same archive again adds nothing.
    let again = restore(&mut library, &first, Some(&key));
    assert_eq!((again.links_added, again.links_skipped), (0, 2));
    assert_eq!(library.link_count().unwrap(), 3);
}

#[test]
fn row_ids_that_collide_get_new_ids() {
    let dir = tempfile::tempdir().unwrap();
    let plain = inner(dir.path(), vec![link(1, "live", 'a', "one")]);
    let mut library = Library::open(&dir.path().join("library")).unwrap();
    restore(&mut library, &plain, None);
    // Same row id, different (owner, link_id).
    let mut other = link(1, "live", 'a', "two");
    other["link_id"] = json!(synthetic::id(7999));
    let plain2 = inner(&dir.path().join("x"), vec![other]);
    let receipt = restore(&mut library, &plain2, None);
    assert_eq!(receipt.links_added, 1);
    assert_ne!(
        receipt.mappings.links[&synthetic::id(5001)],
        synthetic::id(5001)
    );
    assert_eq!(library.link_count().unwrap(), 2);
    assert_eq!(files_in(&library.root().join("links")), 2);
}

#[test]
fn missing_or_wrong_key_changes_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let file = envelope(&inner(dir.path(), two_links()), false);
    let mut library = Library::open(&dir.path().join("library")).unwrap();
    let before = library.journal().unwrap().len();
    let message = |result: Result<_, stack_core::library::Error>| match result {
        Err(error) => error.to_string(),
        Ok(_) => panic!("must fail"),
    };
    let control = Control::default();
    let none =
        message(library.stage_archive_with_key(&mut Cursor::new(&file), None, &Accept, &control));
    assert!(
        none.to_lowercase().contains("no key for this backup"),
        "{none}"
    );
    let wrong = BackupKey::recovery("ff".repeat(32));
    let bad = message(library.stage_archive_with_key(
        &mut Cursor::new(&file),
        Some(&wrong),
        &Accept,
        &control,
    ));
    assert!(
        bad.to_lowercase()
            .contains("wrong passphrase or recovery key, or damaged"),
        "{bad}"
    );
    let wrong_form = BackupKey::recovery("not a key".into());
    assert!(
        library
            .stage_archive_with_key(
                &mut Cursor::new(&file),
                Some(&wrong_form),
                &Accept,
                &control
            )
            .is_err()
    );
    // A passphrase against a key-only backup: no such slot.
    let pass = BackupKey::passphrase("anything at all".into());
    let missing = message(library.stage_archive_with_key(
        &mut Cursor::new(&file),
        Some(&pass),
        &Accept,
        &control,
    ));
    assert!(missing.to_lowercase().contains("no key"), "{missing}");
    // Truncated and extended files are refused after a correct key too.
    let key = BackupKey::recovery(KEY_TEXT.into());
    assert!(
        library
            .stage_archive_with_key(
                &mut Cursor::new(&file[..file.len() - 1]),
                Some(&key),
                &Accept,
                &control
            )
            .is_err()
    );
    let mut longer = file.clone();
    longer.push(0);
    assert!(
        library
            .stage_archive_with_key(&mut Cursor::new(&longer), Some(&key), &Accept, &control)
            .is_err()
    );
    assert_eq!(library.link_count().unwrap(), 0);
    assert_eq!(
        library.search("", None, Sort::Newest, 100).unwrap().len(),
        0
    );
    assert_eq!(library.journal().unwrap().len(), before);
    assert_eq!(files_in(&library.root().join("links")), 0);
    assert_eq!(files_in(&library.root().join("operations")), 0);
    // The debug output of a key never shows it.
    assert_eq!(format!("{key:?}"), "BackupKey(<redacted>)");
}

#[test]
fn failure_after_previews_are_moved_rolls_everything_back() {
    let dir = tempfile::tempdir().unwrap();
    let file = envelope(&inner(dir.path(), two_links()), false);
    let key = BackupKey::recovery(KEY_TEXT.into());
    for checkpoint in ["link-1", "db_transaction"] {
        let mut library = Library::open(&dir.path().join(format!("library-{checkpoint}"))).unwrap();
        let events = library.journal().unwrap().len();
        let revision = library.revision().unwrap();
        library.set_probe(Box::new(move |point| {
            if point == checkpoint {
                Err(std::io::Error::other("injected failure"))
            } else {
                Ok(())
            }
        }));
        let control = Control::default();
        let staged = library
            .stage_archive_with_key(&mut Cursor::new(&file), Some(&key), &Accept, &control)
            .unwrap();
        let plan = library.review_archive(&staged).unwrap();
        assert!(
            library.commit_archive(&plan, &control).is_err(),
            "{checkpoint}"
        );
        drop(plan);
        drop(staged);
        assert_eq!(library.link_count().unwrap(), 0, "{checkpoint}");
        assert!(
            library
                .search("zebra", None, Sort::Newest, 10)
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            library.search("", None, Sort::Newest, 100).unwrap().len(),
            0
        );
        assert_eq!(files_in(&library.root().join("links")), 0, "{checkpoint}");
        assert_eq!(
            files_in(&library.root().join("originals")),
            0,
            "{checkpoint}"
        );
        assert_eq!(
            files_in(&library.root().join("operations")),
            0,
            "{checkpoint}"
        );
        assert_eq!(library.journal().unwrap().len(), events);
        assert_eq!(library.revision().unwrap(), revision);
        // The same backup restores cleanly afterwards.
        library.set_probe(Box::new(|_| Ok(())));
        assert_eq!(restore(&mut library, &file, Some(&key)).links_added, 2);
    }
}

#[test]
fn an_unfinished_restore_removes_its_previews_on_reopen() {
    let dir = tempfile::tempdir().unwrap();
    let file = envelope(&inner(dir.path(), two_links()), false);
    let key = BackupKey::recovery(KEY_TEXT.into());
    let root = dir.path().join("library");
    let mut library = Library::open(&root).unwrap();
    let staged = library
        .stage_archive_with_key(
            &mut Cursor::new(&file),
            Some(&key),
            &Accept,
            &Control::default(),
        )
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    // Simulate a crash: the probe aborts the process-like flow by panicking after the preview moved.
    library.set_probe(Box::new(|point| {
        if point == "link-1" {
            panic!("simulated crash");
        }
        Ok(())
    }));
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        library.commit_archive(&plan, &Control::default())
    }));
    assert!(result.is_err());
    assert_eq!(files_in(&root.join("links")), 1);
    drop(plan);
    drop(staged);
    drop(library);
    let library = Library::open(&root).unwrap();
    assert_eq!(files_in(&root.join("links")), 0);
    assert_eq!(library.link_count().unwrap(), 0);
    assert_eq!(files_in(&root.join("operations")), 0);
}

#[test]
fn plain_archives_keep_todays_behaviour() {
    let dir = tempfile::tempdir().unwrap();
    // Format 3 (no links): nothing new, and a key given for a plain file is refused (it proves nothing).
    let root = dir.path().join("f3");
    synthetic::create(&root, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap();
    let v3 = fs::read(root.join("format-3.stackbackup")).unwrap();
    let mut library = Library::open(&dir.path().join("library")).unwrap();
    let refused = library.stage_archive_with_key(
        &mut Cursor::new(&v3),
        Some(&BackupKey::recovery("00".repeat(32))),
        &Accept,
        &Control::default(),
    );
    assert!(refused.is_err());
    let receipt = restore(&mut library, &v3, None);
    assert!(!receipt.encrypted);
    assert_eq!((receipt.links_added, receipt.links_skipped), (0, 0));
    assert_eq!(receipt.documents_added, 5);
    assert!(receipt.summary().contains("not encrypted"));
    assert!(!receipt.summary().contains("linked"));
    assert_eq!(library.link_count().unwrap(), 0);
    // A plain format 4 stream (no envelope) restores links too and says it is not encrypted.
    let plain4 = inner(&dir.path().join("p4"), two_links());
    let receipt = restore(&mut library, &plain4, None);
    assert!(!receipt.encrypted);
    assert_eq!(receipt.links_added, 2);
    // Plain format 4 with links but no previews imports its links (none dropped, no preview files).
    let no_previews = inner(
        &dir.path().join("p4n"),
        vec![
            link(2, "live", 'c', "no preview one"),
            link(3, "live", 'd', "no preview two"),
        ],
    );
    let mut fresh = Library::open(&dir.path().join("library-n")).unwrap();
    let receipt = restore(&mut fresh, &no_previews, None);
    assert_eq!(receipt.links_added, 2);
    assert_eq!(fresh.link_count().unwrap(), 2);
    // A file that is neither archive nor envelope is refused.
    assert!(
        library
            .stage_archive(
                &mut Cursor::new(b"not an archive"),
                &Accept,
                &Control::default()
            )
            .is_err()
    );
    assert!(
        library
            .stage_archive(&mut Cursor::new(b"short"), &Accept, &Control::default())
            .is_err()
    );
}

#[test]
fn old_receipts_without_link_fields_still_read() {
    let old = json!({"operation":synthetic::id(1),"documents_added":1,"document_collisions":0,"merged_stacks":0,
        "source_version":3,"source_hash":"x","mappings":{"documents":{},"pages":{},"stacks":{},"tags":{},"scans":{}}});
    let receipt: stack_core::RestoreReceipt = serde_json::from_value(old).unwrap();
    assert_eq!(
        (
            receipt.links_added,
            receipt.links_skipped,
            receipt.encrypted
        ),
        (0, 0, false)
    );
}
