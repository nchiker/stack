//! The desktop export writes what the phone writes: format 4 with linked documents (plain or inside the
//! encrypted envelope), format 3 when there is nothing to carry. Synthetic data and synthetic keys only.
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_archive::{Control, Manifest, SourceVerifier, synthetic, write};
use stack_core::keystore::{MemoryStore, SecretStore, saved_backup_key};
use stack_core::{BackupKey, library::Library};
use std::{fs, io::Cursor, path::Path};

struct Accept;
impl SourceVerifier for Accept {
    fn verify(&self, _: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
        Ok(())
    }
}

const PDF: &[u8] = b"%PDF-1.4 synthetic preview";
const KEY_TEXT: &str = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
const OTHER_KEY: &str = "ff02030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
const FIELDS: &str = "id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text";

fn link(n: u128, hash: char, text: &str) -> Value {
    json!({"id":synthetic::id(5000 + n),"owner":"org.example.amipro","link_id":synthetic::id(7000 + n),"kind":"doc",
        "title":format!("Linked {n}"),"mime":"application/vnd.example+json","modified":1700000000000_i64,
        "hash":hash.to_string().repeat(64),"shared":1,"protected":1,
        "locators":"[{\"type\":\"filen\",\"uuid\":\"00000000-0000-0000-0000-000000000009\",\"path\":\"/a/b\"},{\"type\":\"saf\",\"uri\":\"content://x\"}]",
        "state":"live","title_user_edited":1,"added_at":5 + n as i64,"text":text})
}

/// A plain archive of the synthetic library, with links (link 1 has a preview) when `links` is given.
fn source_archive(dir: &Path, links: Option<Vec<Value>>) -> Vec<u8> {
    fs::create_dir_all(dir).unwrap();
    let root = dir.join("source");
    let mut value = synthetic::create(&root, [b"pdf", b"jpeg", b"png", b"webp"])
        .unwrap()
        .into_json();
    let preview = links.is_some();
    if let Some(links) = links {
        value["version"] = json!(4);
        value["links"] = json!(links);
        value["files"].as_array_mut().unwrap().push(
            json!({"path":format!("previews/{}.pdf",synthetic::id(5001)),
            "length":PDF.len(),"sha256":format!("{:x}",Sha256::digest(PDF))}),
        );
    }
    let manifest = Manifest::from_value(value).unwrap();
    if preview {
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

fn restore(library: &mut Library, bytes: &[u8], key: Option<&BackupKey>) {
    let control = Control::default();
    let staged = library
        .stage_archive_with_key(&mut Cursor::new(bytes), key, &Accept, &control)
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    library.commit_archive(&plan, &control).unwrap();
}

fn library_with(dir: &Path, name: &str, links: Option<Vec<Value>>) -> Library {
    let archive = source_archive(&dir.join(format!("src-{name}")), links);
    let mut library = Library::open(&dir.join(name)).unwrap();
    restore(&mut library, &archive, None);
    library
}

fn saved_key(text: &str) -> (MemoryStore, BackupKey) {
    let store = MemoryStore::default();
    stack_core::keystore::save_key(&store, text).unwrap();
    let key = saved_backup_key(&store).unwrap().unwrap();
    (store, key)
}

fn stage(library: &mut Library, file: &Path, key: Option<&BackupKey>) -> stack_core::StagedImport {
    let mut input = fs::File::open(file).unwrap();
    library
        .stage_archive_with_key(&mut input, key, &Accept, &Control::default())
        .unwrap()
}

/// Every stored link (all archive columns that survive a restore) in a stable order.
fn links_of(library: &Library) -> Vec<Value> {
    let manifest = library.manifest().unwrap();
    assert!(
        manifest.json().get("links").is_none(),
        "links are not in the document graph"
    );
    let mut rows = Vec::new();
    for found in library
        .search("", None, stack_core::Sort::Newest, 1000)
        .unwrap()
        .iter()
        .filter(|h| h.linked)
    {
        let hit = library.linked_document(&found.document_id).unwrap();
        let mut l = serde_json::to_value(&hit).unwrap();
        l.as_object_mut().unwrap().remove("id");
        l.as_object_mut().unwrap().remove("preview");
        l["has_preview"] = json!(hit.preview.is_some());
        if let Some(path) = &hit.preview {
            assert_eq!(fs::read(path).unwrap(), PDF);
        }
        rows.push(l);
    }
    rows.sort_by_key(|r| r["title"].as_str().unwrap().to_owned());
    rows
}

fn two_links() -> Vec<Value> {
    vec![
        link(1, 'a', "quarterly zebra budget"),
        link(2, 'b', "second note"),
    ]
}

fn titles(library: &Library) -> Vec<String> {
    let mut v: Vec<String> = library
        .manifest()
        .unwrap()
        .rows("documents")
        .iter()
        .map(|d| d["title"].as_str().unwrap().to_owned())
        .collect();
    v.sort();
    v
}

#[test]
fn plain_export_with_links_is_format_4_and_round_trips() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", Some(two_links()));
    let file = dir.path().join("plain.stackbackup");
    a.export_archive(&file, &Control::default()).unwrap();
    let bytes = fs::read(&file).unwrap();
    assert_eq!(&bytes[..8], stack_archive::MAGIC);
    let mut b = Library::open(&dir.path().join("b")).unwrap();
    let staged = stage(&mut b, &file, None);
    let manifest = staged.manifest();
    assert_eq!(manifest.source_version(), 4);
    let rows = manifest.json()["links"].as_array().unwrap();
    assert_eq!(rows.len(), 2);
    let columns: Vec<&str> = FIELDS.split(',').collect();
    for row in rows {
        let mut names: Vec<&str> = row
            .as_object()
            .unwrap()
            .keys()
            .map(String::as_str)
            .collect();
        names.sort_unstable();
        let mut want = columns.clone();
        want.sort_unstable();
        assert_eq!(
            names, want,
            "exactly the archive columns, no lifecycle columns"
        );
        assert_eq!(row["state"], "unreachable");
    }
    // Every exported column carries the stored value (ids and the restored state aside).
    let strip = |row: &Value| {
        let mut row = row.clone();
        row.as_object_mut().unwrap().remove("id");
        row.as_object_mut().unwrap().remove("state");
        row
    };
    let mut got: Vec<Value> = rows.iter().map(strip).collect();
    let mut want: Vec<Value> = two_links().iter().map(strip).collect();
    got.sort_by_key(|r| r["title"].as_str().unwrap().to_owned());
    want.sort_by_key(|r| r["title"].as_str().unwrap().to_owned());
    assert_eq!(got, want);
    let previews: Vec<&Value> = manifest
        .rows("files")
        .iter()
        .filter(|f| f["path"].as_str().unwrap().starts_with("previews/"))
        .collect();
    assert_eq!(previews.len(), 1);
    drop(staged);

    restore(&mut b, &bytes, None);
    assert_eq!(titles(&a), titles(&b));
    assert_eq!(b.link_count().unwrap(), 2);
    let (la, lb) = (links_of(&a), links_of(&b));
    assert_eq!(la, lb);
    assert!(la.iter().any(|l| l["has_preview"] == true));
    assert!(la.iter().any(|l| l["text"] == "quarterly zebra budget"));
    // The fresh library exports the same thing again (stable round trip).
    let again = dir.path().join("again.stackbackup");
    b.export_archive(&again, &Control::default()).unwrap();
    let mut c = Library::open(&dir.path().join("c")).unwrap();
    assert_eq!(
        stage(&mut c, &again, None).manifest().json()["links"]
            .as_array()
            .unwrap()
            .len(),
        2
    );
}

#[test]
fn encrypted_export_round_trips_and_a_wrong_key_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", Some(two_links()));
    let (_store, key) = saved_key(KEY_TEXT);
    let file = dir.path().join("secret.stackbackup");
    a.export_archive_with_key(&file, Some(&key), &Control::default())
        .unwrap();
    let bytes = fs::read(&file).unwrap();
    assert_eq!(&bytes[..8], stack_archive::crypto::ENVELOPE_MAGIC);
    assert!(
        !bytes.windows(5).any(|w| w == b"zebra"),
        "text must not be readable"
    );
    assert!(!bytes.windows(PDF.len()).any(|w| w == PDF));
    // key-only envelope: exactly one slot (slot_count byte follows magic, kdf, iterations, salt)
    assert_eq!(bytes[8 + 1 + 4 + 16], 1);

    let mut b = Library::open(&dir.path().join("b")).unwrap();
    restore(&mut b, &bytes, Some(&key));
    assert_eq!(titles(&a), titles(&b));
    assert_eq!(links_of(&a), links_of(&b));

    let mut c = Library::open(&dir.path().join("c")).unwrap();
    let wrong = BackupKey::recovery(OTHER_KEY.to_owned());
    for key in [Some(&wrong), None] {
        let mut input = fs::File::open(&file).unwrap();
        assert!(
            c.stage_archive_with_key(&mut input, key, &Accept, &Control::default())
                .is_err()
        );
    }
    assert_eq!(c.link_count().unwrap(), 0);
    // A passphrase is not a recovery key: exports refuse it.
    let pass = BackupKey::passphrase("correct horse battery".into());
    let refused = dir.path().join("refused.stackbackup");
    assert!(
        a.export_archive_with_key(&refused, Some(&pass), &Control::default())
            .is_err()
    );
    assert!(!refused.exists());
}

#[test]
fn exports_are_never_identical_twice() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", Some(two_links()));
    let (_store, key) = saved_key(KEY_TEXT);
    let one = dir.path().join("one");
    let two = dir.path().join("two");
    a.export_archive_with_key(&one, Some(&key), &Control::default())
        .unwrap();
    a.export_archive_with_key(&two, Some(&key), &Control::default())
        .unwrap();
    let (x, y) = (fs::read(one).unwrap(), fs::read(two).unwrap());
    assert_ne!(
        x[8 + 1 + 4..8 + 1 + 4 + 16],
        y[8 + 1 + 4..8 + 1 + 4 + 16],
        "random salt"
    );
}

#[test]
fn no_links_stays_format_3_and_encrypted_no_links_is_format_4_with_empty_links() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", None);
    assert_eq!(a.link_count().unwrap(), 0);
    let plain = dir.path().join("plain");
    a.export_archive(&plain, &Control::default()).unwrap();
    let mut b = Library::open(&dir.path().join("b")).unwrap();
    let staged = stage(&mut b, &plain, None);
    assert_eq!(staged.manifest().source_version(), 3);
    assert!(staged.manifest().json().get("links").is_none());
    drop(staged);

    let (_store, key) = saved_key(KEY_TEXT);
    let sealed = dir.path().join("sealed");
    a.export_archive_with_key(&sealed, Some(&key), &Control::default())
        .unwrap();
    let staged = stage(&mut b, &sealed, Some(&key));
    assert_eq!(staged.manifest().source_version(), 4);
    assert_eq!(staged.manifest().json()["links"], json!([]));
}

#[test]
fn no_saved_key_means_a_plain_file() {
    let store = MemoryStore::default();
    assert!(saved_backup_key(&store).unwrap().is_none());
    store.set(KEY_TEXT).unwrap();
    assert!(saved_backup_key(&store).unwrap().is_some());
}

fn exported_links(library: &mut Library, dir: &Path, name: &str) -> Vec<Value> {
    let file = dir.join(name);
    library.export_archive(&file, &Control::default()).unwrap();
    let mut probe = Library::open(&dir.join(format!("probe-{name}"))).unwrap();
    let staged = stage(&mut probe, &file, None);
    staged.manifest().json()["links"]
        .as_array()
        .cloned()
        .unwrap_or_default()
}

#[test]
fn a_trashed_link_is_not_exported() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", Some(two_links()));
    let db = rusqlite::Connection::open(dir.path().join("a/library.sqlite3")).unwrap();
    let changed = db
        .execute(
            "UPDATE link_records SET state='trashed' WHERE title='Linked 2'",
            [],
        )
        .unwrap();
    assert_eq!(changed, 1);
    drop(db);
    let rows = exported_links(&mut a, dir.path(), "trash.stackbackup");
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0]["title"], "Linked 1");
}

#[test]
fn a_missing_preview_file_does_not_block_the_export() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", Some(two_links()));
    let previews: Vec<_> = fs::read_dir(dir.path().join("a/links"))
        .unwrap()
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|x| x == "pdf"))
        .collect();
    assert_eq!(previews.len(), 1);
    fs::remove_file(&previews[0]).unwrap();
    let file = dir.path().join("nopreview.stackbackup");
    a.export_archive(&file, &Control::default()).unwrap();
    let mut b = Library::open(&dir.path().join("b")).unwrap();
    let staged = stage(&mut b, &file, None);
    let manifest = staged.manifest();
    assert_eq!(manifest.json()["links"].as_array().unwrap().len(), 2);
    assert!(
        !manifest
            .rows("files")
            .iter()
            .any(|f| f["path"].as_str().unwrap().starts_with("previews/"))
    );
}

#[test]
fn write_encrypted_refuses_a_format_3_snapshot() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", None);
    let snapshot = a.snapshot(&Control::default()).unwrap();
    let key = BackupKey::recovery(KEY_TEXT.to_owned());
    let mut out = Vec::new();
    assert!(
        snapshot
            .write_encrypted(&mut out, &key, &Control::default())
            .is_err()
    );
    assert!(out.is_empty(), "nothing is written before the refusal");
}
