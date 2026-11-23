//! Link records through the sync (slice 6, D5). The phone is played by a plain `stack_sync::State` that writes
//! link cells with the real writer builder; the desktop is a real `Library` with real SQLite and exchange files.
//! Synthetic data only. No key store is used anywhere (the sync never touches one).
use rusqlite::{Connection, OpenFlags};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_archive::{Control, Manifest, SourceVerifier, synthetic, write};
use stack_core::{Sort, library::Library, reset::Mode};
use stack_sync::{
    Frontier, State,
    bundle::{self, Blob, Header},
    links::{self as wire, LinkBlob, LinkFilen, LinkInput, LinkPresence, LinkState},
};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::Cursor,
    path::{Path, PathBuf},
};

struct Accept;
impl SourceVerifier for Accept {
    fn verify(&self, _: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
        Ok(())
    }
}

const OWNER: &str = "org.example.amipro";
const PDF: &[u8] = b"%PDF-1.4 synthetic preview one";
const PDF2: &[u8] = b"%PDF-1.4 synthetic preview two, longer";

fn sha(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

// ---------------------------------------------------------------------------
// Desktop helpers
// ---------------------------------------------------------------------------

fn archive_link(n: u128, text: &str, protected: i64) -> Value {
    json!({"id":synthetic::id(5000 + n),"owner":OWNER,"link_id":synthetic::id(7000 + n),"kind":"doc",
        "title":format!("Linked {n}"),"mime":"application/pdf","modified":1700000000000_i64,
        "hash":"c".repeat(64),"shared":0,"protected":protected,
        "locators":"[{\"type\":\"filen\",\"uuid\":\"00000000-0000-0000-0000-000000000009\",\"path\":\"/a/b\"},{\"type\":\"saf\",\"uri\":\"content://x\"}]",
        "state":"live","title_user_edited":0,"added_at":5 + n as i64,"text":text})
}

/// A plain archive of the synthetic library, with `links`; link 1 (if present) has a preview.
fn archive(dir: &Path, links: Vec<Value>) -> Vec<u8> {
    fs::create_dir_all(dir).unwrap();
    let root = dir.join("source");
    let mut value = synthetic::create(&root, [b"pdf", b"jpeg", b"png", b"webp"])
        .unwrap()
        .into_json();
    let with_preview = links.iter().any(|l| l["id"] == json!(synthetic::id(5001)));
    if !links.is_empty() {
        value["version"] = json!(4);
        value["links"] = json!(links);
    }
    if with_preview {
        value["files"].as_array_mut().unwrap().push(
            json!({"path":format!("previews/{}.pdf",synthetic::id(5001)),"length":PDF.len(),"sha256":sha(PDF)}),
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

fn restore(library: &mut Library, bytes: &[u8]) {
    let control = Control::default();
    let staged = library
        .stage_archive_with_key(&mut Cursor::new(bytes), None, &Accept, &control)
        .unwrap();
    let plan = library.review_archive(&staged).unwrap();
    library.commit_archive(&plan, &control).unwrap();
}

fn library_with(dir: &Path, name: &str, links: Vec<Value>) -> Library {
    let bytes = archive(&dir.join(format!("src-{name}")), links);
    let mut library = Library::open(&dir.join(name)).unwrap();
    restore(&mut library, &bytes);
    library
}

fn db(library: &Library) -> Connection {
    Connection::open_with_flags(
        library.root().join("library.sqlite3"),
        OpenFlags::SQLITE_OPEN_READ_ONLY,
    )
    .unwrap()
}

#[derive(Debug, Clone, PartialEq)]
struct Row {
    row_id: String,
    link_id: String,
    title: String,
    state: String,
    text: String,
    preview: i64,
    preview_sha: String,
    pending: i64,
    protected: i64,
    locators: String,
}

fn rows(library: &Library) -> Vec<Row> {
    let conn = db(library);
    let mut statement = conn
        .prepare("SELECT row_id,link_id,title,state,text,preview,preview_sha,preview_pending,protected,locators FROM link_records ORDER BY added_at,row_id")
        .unwrap();
    statement
        .query_map([], |r| {
            Ok(Row {
                row_id: r.get(0)?,
                link_id: r.get(1)?,
                title: r.get(2)?,
                state: r.get(3)?,
                text: r.get(4)?,
                preview: r.get(5)?,
                preview_sha: r.get(6)?,
                pending: r.get(7)?,
                protected: r.get(8)?,
                locators: r.get(9)?,
            })
        })
        .unwrap()
        .map(Result::unwrap)
        .collect()
}

fn row_of(library: &Library, n: u128) -> Row {
    rows(library)
        .into_iter()
        .find(|r| r.link_id == synthetic::id(7000 + n))
        .unwrap_or_else(|| panic!("no row for link {n}"))
}

fn projection(library: &Library) -> BTreeMap<String, String> {
    let conn = db(library);
    let mut statement = conn
        .prepare("SELECT key,value FROM link_projection")
        .unwrap();
    statement
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
        .unwrap()
        .map(Result::unwrap)
        .collect()
}

fn preview_file(library: &Library, row: &Row) -> PathBuf {
    library
        .root()
        .join("links")
        .join(format!("{}.pdf", row.row_id))
}

fn count_files(dir: &Path) -> usize {
    fs::read_dir(dir).map(|d| d.count()).unwrap_or(0)
}

fn fail_at(library: &mut Library, point: &'static str) {
    library.set_probe(Box::new(move |p| {
        if p == point {
            Err(std::io::Error::other("injected stop"))
        } else {
            Ok(())
        }
    }));
}

fn no_probe(library: &mut Library) {
    library.set_probe(Box::new(|_| Ok(())));
}

// ---------------------------------------------------------------------------
// The fake phone
// ---------------------------------------------------------------------------

struct Phone {
    state: State,
    replica: String,
    peer: String,
    blobs: PathBuf,
    dir: PathBuf,
    n: usize,
}

fn state_from(exporter: &mut Library, peer: &str, dir: &Path) -> State {
    let path = dir.join("genesis.stackexchange");
    exporter.sync_export(peer, &path).unwrap();
    let scratch = dir.join("genesis-blobs");
    fs::create_dir_all(&scratch).unwrap();
    let header = bundle::read(&mut fs::File::open(path).unwrap(), &scratch).unwrap();
    let members = BTreeSet::from([exporter.status().unwrap().replica_id, peer.to_owned()]);
    let mut state = State::new(header.library.clone(), members).unwrap();
    state.receive(&header.operations).unwrap();
    state
}

/// The phone joins a desktop that holds the baseline (the usual shape for these tests).
fn join(desktop: &mut Library, dir: &Path) -> Phone {
    let replica = uuid::Uuid::new_v4().to_string();
    desktop.sync_start(std::slice::from_ref(&replica)).unwrap();
    let state = state_from(desktop, &replica, dir);
    phone_with(state, replica, desktop.status().unwrap().replica_id, dir)
}

fn phone_with(state: State, replica: String, peer: String, dir: &Path) -> Phone {
    let blobs = dir.join("phone-blobs");
    fs::create_dir_all(&blobs).unwrap();
    Phone {
        state,
        replica,
        peer,
        blobs,
        dir: dir.to_owned(),
        n: 0,
    }
}

fn input(n: u128, title: &str, text: &str, preview: Option<LinkBlob>) -> LinkInput {
    LinkInput {
        owner: OWNER.into(),
        link_id: synthetic::id(7000 + n),
        kind: "doc".into(),
        added_at: 10 + n as i64,
        hash: "a".repeat(64),
        mime: "application/pdf".into(),
        modified: 1_700_000_000_000,
        shared: false,
        protected: false,
        title: title.into(),
        title_user_edited: false,
        filen: vec![LinkFilen {
            uuid: synthetic::id(9000 + n),
            path: format!("/docs/{n}.pdf"),
        }],
        text: Some(text.into()),
        preview,
        presence: LinkPresence {
            state: LinkState::Live,
            reason: String::new(),
            since: 0,
        },
        omit_text: false,
        omit_preview: false,
        foreign: None,
    }
}

impl Phone {
    fn put(&self, bytes: &[u8]) -> LinkBlob {
        fs::write(self.blobs.join(sha(bytes)), bytes).unwrap();
        LinkBlob {
            sha256: sha(bytes),
            length: bytes.len() as u64,
        }
    }

    /// One phone operation carrying the full cell set of `rows` (and the marker when `enable`).
    fn publish(&mut self, rows: &[LinkInput], enable: bool) {
        let mut changes = BTreeMap::new();
        for row in rows {
            if let Some(text) = row
                .text
                .as_deref()
                .filter(|t| !t.is_empty() && !row.protected)
            {
                self.put(text.as_bytes());
            }
        }
        let built = wire::link_cells(rows);
        assert!(built.refused.is_empty(), "{:?}", built.refused);
        changes.extend(built.cells);
        if enable {
            changes.insert(
                wire::marker_key(&self.replica),
                wire::marker_value(1_700_000_000_000).unwrap(),
            );
        }
        self.write(
            changes,
            if enable {
                "links.enable"
            } else {
                "link.capture"
            },
        );
    }

    fn write(&mut self, changes: BTreeMap<String, Value>, kind: &str) {
        stack_sync::graph::validate_changes(&changes).unwrap();
        self.state
            .local(&self.replica, changes, json!({"kind":kind}))
            .unwrap();
    }

    fn header_for(&self, desktop: &Library, all: bool) -> Header {
        let ack: Frontier = if all {
            Frontier::new()
        } else {
            // What the desktop has, limited to what this phone has seen of it.
            let theirs: Frontier =
                serde_json::from_value(desktop.sync_summary().unwrap()["frontier"].clone())
                    .unwrap();
            theirs
                .into_iter()
                .map(|(origin, n)| {
                    let known = self.state.frontier.get(&origin).copied().unwrap_or(0);
                    (origin, n.min(known))
                })
                .collect()
        };
        let operations = self.state.operations_after(&ack).unwrap();
        let refs = wire::link_blob_refs(operations.iter().flat_map(|op| op.changes.iter()));
        let blobs = refs
            .keys()
            .filter_map(|sha| {
                let meta = fs::metadata(self.blobs.join(sha)).ok()?;
                Some(Blob {
                    sha256: sha.clone(),
                    length: meta.len(),
                })
            })
            .collect();
        Header {
            format: "org.stackdocs.exchange".into(),
            version: 1,
            library: self.state.library.clone(),
            sender: self.replica.clone(),
            acknowledged: self.state.frontier.clone(),
            operations,
            blobs,
        }
    }

    fn file(&mut self, header: &Header) -> PathBuf {
        self.n += 1;
        let path = self.dir.join(format!("phone-{}.stackexchange", self.n));
        bundle::publish(header, &self.blobs, &path).unwrap();
        path
    }

    /// The bundle the desktop would receive: everything the desktop does not yet have.
    fn bundle(&mut self, desktop: &Library) -> PathBuf {
        let header = self.header_for(desktop, false);
        self.file(&header)
    }

    fn send(&mut self, desktop: &mut Library) -> Value {
        let path = self.bundle(desktop);
        desktop.sync_import(&path, None).unwrap()
    }

    /// Takes what the desktop exports for the phone; returns the header it carried.
    fn receive(&mut self, desktop: &Library) -> Header {
        self.n += 1;
        let path = self.dir.join(format!("desktop-{}.stackexchange", self.n));
        desktop.sync_export(&self.replica, &path).unwrap();
        let scratch = self.dir.join(format!("desktop-blobs-{}", self.n));
        fs::create_dir_all(&scratch).unwrap();
        let header = bundle::read(&mut fs::File::open(&path).unwrap(), &scratch).unwrap();
        for blob in &header.blobs {
            fs::copy(scratch.join(&blob.sha256), self.blobs.join(&blob.sha256)).unwrap();
        }
        self.state.receive(&header.operations).unwrap();
        assert_eq!(self.peer, header.sender);
        header
    }
}

fn link_keys(header: &Header) -> Vec<String> {
    header
        .operations
        .iter()
        .flat_map(|op| op.changes.keys().cloned())
        .filter(|key| key.starts_with("link_records/"))
        .collect()
}

fn trashed(reason: &str, since: i64) -> LinkPresence {
    LinkPresence {
        state: LinkState::Trashed,
        reason: reason.into(),
        since,
    }
}

// ---------------------------------------------------------------------------
// Apply
// ---------------------------------------------------------------------------

#[test]
fn baseline_update_trash_restore_gone_and_nothing_is_deleted_by_absence() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    assert!(
        !a.sync_summary().unwrap()["links_enabled"]
            .as_bool()
            .unwrap()
    );

    let mut l1 = input(1, "Budget", "quarterly zebra budget", Some(phone.put(PDF)));
    let mut l2 = input(2, "Secret", "never copied anywhere", Some(phone.put(PDF2)));
    l2.protected = true;
    let l3 = input(3, "Plain", "", None);
    phone.publish(&[l1.clone(), l2.clone(), l3.clone()], true);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["applied"], 3, "{out}");
    // The incoming enable made the desktop enable too (derived, cached).
    assert!(
        a.sync_summary().unwrap()["links_enabled"]
            .as_bool()
            .unwrap()
    );
    assert!(a.sync_status().unwrap()["links_enabled"].as_bool().unwrap());
    assert_eq!(a.link_count().unwrap(), 3);

    let hit = &a.search("zebra", None, Sort::Newest, 10).unwrap()[0];
    assert!(hit.linked);
    let view = a.linked_document(&hit.document_id).unwrap();
    assert_eq!(view.state, "unreachable");
    assert_eq!(view.title, "Budget");
    assert_eq!(fs::read(view.preview.clone().unwrap()).unwrap(), PDF);
    let r1 = row_of(&a, 1);
    assert_eq!(
        (r1.pending, r1.preview, r1.preview_sha.as_str()),
        (0, 1, sha(PDF).as_str())
    );
    assert!(r1.locators.contains("/docs/1.pdf"));
    // Protected: metadata only. No text, no preview, no blob of its text anywhere.
    let r2 = row_of(&a, 2);
    assert_eq!((r2.protected, r2.text.as_str(), r2.preview), (1, "", 0));
    assert!(
        !a.root()
            .join("sync_blobs")
            .join(sha(b"never copied anywhere"))
            .exists()
    );
    assert!(!a.root().join("sync_blobs").join(sha(PDF2)).exists());
    assert!(
        a.root()
            .join("sync_blobs")
            .join(sha(b"quarterly zebra budget"))
            .exists()
    );
    assert!(!projection(&a).is_empty());

    // The desktop's own enable op is marker only (it had no links) and the phone takes it without trouble.
    let header = phone.receive(&a);
    let own = header
        .operations
        .iter()
        .find(|op| op.evidence["kind"] == "links.enable")
        .expect("desktop enable op");
    assert_eq!(own.changes.len(), 1);
    assert!(own.changes.contains_key(&wire::marker_key(&phone.peer)));
    assert!(phone.state.conflicts().is_empty());

    // Update in place: same local row id, new title, text and preview.
    l1.title = "Budget 2".into();
    l1.text = Some("annual giraffe plan".into());
    l1.preview = Some(phone.put(PDF2));
    phone.publish(std::slice::from_ref(&l1), false);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["applied"], 1, "{out}");
    let after = row_of(&a, 1);
    assert_eq!(after.row_id, r1.row_id);
    assert_eq!(after.title, "Budget 2");
    assert!(
        a.search("zebra", None, Sort::Newest, 10)
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        a.search("giraffe", None, Sort::Newest, 10).unwrap().len(),
        1
    );
    assert_eq!(fs::read(preview_file(&a, &after)).unwrap(), PDF2);

    // Trashed hides the row (list, search, count); live brings it back as unreachable (trashed -> live).
    l1.presence = trashed("trashed", 5);
    phone.publish(std::slice::from_ref(&l1), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).state, "trashed");
    assert_eq!(a.link_count().unwrap(), 2);
    assert!(
        a.search("giraffe", None, Sort::Newest, 10)
            .unwrap()
            .is_empty()
    );
    assert!(
        a.search("", None, Sort::Newest, 100)
            .unwrap()
            .iter()
            .all(|h| h.title != "Budget 2")
    );
    l1.presence.state = LinkState::Live;
    l1.presence.reason = String::new();
    l1.presence.since = 0;
    phone.publish(std::slice::from_ref(&l1), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).state, "unreachable");
    assert_eq!(row_of(&a, 1).row_id, r1.row_id);
    assert_eq!(a.link_count().unwrap(), 3);

    // Absence deletes nothing: an operation that mentions only link 3 leaves links 1 and 2 alone, and so does a
    // null content cell without a `gone` presence.
    let mut l3b = l3.clone();
    l3b.title = "Plain renamed".into();
    phone.publish(&[l3b], false);
    let key2 = wire::link_key(OWNER, &synthetic::id(7002));
    phone.write(
        BTreeMap::from([(format!("{key2}/content"), Value::Null)]),
        "link.capture",
    );
    phone.send(&mut a);
    assert_eq!(a.link_count().unwrap(), 3);
    assert_eq!(row_of(&a, 3).title, "Plain renamed");
    assert_eq!(row_of(&a, 2).title, "Secret");

    // Only presence `gone` deletes: row, search entry and preview file.
    let gone_file = preview_file(&a, &row_of(&a, 1));
    assert!(gone_file.exists());
    l1.presence = LinkPresence {
        state: LinkState::Gone,
        reason: "deleted".into(),
        since: 9,
    };
    phone.publish(std::slice::from_ref(&l1), false);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["deleted"], 1, "{out}");
    assert_eq!(a.link_count().unwrap(), 2);
    assert!(!gone_file.exists());
    assert!(
        a.search("giraffe", None, Sort::Newest, 10)
            .unwrap()
            .is_empty()
    );
    // An identity that arrives already `gone` is never inserted.
    let mut l4 = input(4, "Never here", "never inserted", None);
    l4.presence = LinkPresence {
        state: LinkState::Gone,
        reason: "deleted".into(),
        since: 3,
    };
    phone.publish(&[l4], false);
    phone.send(&mut a);
    assert_eq!(a.link_count().unwrap(), 2);
    assert!(rows(&a).iter().all(|r| r.link_id != synthetic::id(7004)));
}

#[test]
fn a_link_that_becomes_protected_keeps_no_text_or_preview_on_the_desktop() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let mut l = input(1, "Open", "openly searchable", Some(phone.put(PDF)));
    phone.publish(std::slice::from_ref(&l), true);
    phone.send(&mut a);
    let row = row_of(&a, 1);
    assert_eq!(row.text, "openly searchable");
    l.protected = true;
    phone.publish(std::slice::from_ref(&l), false);
    phone.send(&mut a);
    let row = row_of(&a, 1);
    assert_eq!((row.protected, row.text.as_str(), row.preview), (1, "", 0));
    assert!(
        !a.root()
            .join("links")
            .join(format!("{}.pdf", row.row_id))
            .exists()
    );
    assert!(
        a.search("openly", None, Sort::Newest, 10)
            .unwrap()
            .is_empty()
    );
}

#[test]
fn a_missing_or_wrong_link_blob_applies_the_other_parts_and_never_fails_the_import() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let l1 = input(1, "Has blob", "text one", Some(phone.put(PDF)));
    let l2 = input(2, "No blob", "text two", Some(phone.put(PDF2)));
    phone.publish(&[l1, l2], true);
    let mut header = phone.header_for(&a, false);
    // The text blob of link 2 does not travel.
    header.blobs.retain(|b| b.sha256 != sha(b"text two"));
    let path = phone.file(&header);
    let out = a.sync_import(&path, None).unwrap();
    // The link with the missing text blob is applied for its other parts (title, presence, filen); only the
    // text part waits (counted once as missing_blob).
    assert_eq!(out["links"]["applied"], 2, "{out}");
    assert_eq!(out["links"]["missing_blob"], 1, "{out}");
    assert_eq!(a.link_count().unwrap(), 2);
    assert_eq!(row_of(&a, 1).title, "Has blob");
    assert_eq!(
        (row_of(&a, 2).title.as_str(), row_of(&a, 2).text.as_str()),
        ("No blob", "")
    );
    // The import still acknowledged: the chain continues.
    phone.receive(&a);
    assert_eq!(a.sync_status().unwrap()["pending_gaps"], 0);

    // A header blob whose length differs from the cell's declared length is not a declared blob at all.
    let l3 = input(3, "Lying", "abcdef", None);
    phone.publish(std::slice::from_ref(&l3), false);
    let mut header = phone.header_for(&a, false);
    let scratch = dir.path().join("lie");
    fs::create_dir_all(&scratch).unwrap();
    fs::write(scratch.join(sha(b"abcdefg")), b"abcdefg").unwrap();
    header.blobs = vec![Blob {
        sha256: sha(b"abcdefg"),
        length: 7,
    }];
    let path = dir.path().join("lie.stackexchange");
    bundle::publish(&header, &scratch, &path).unwrap();
    assert!(a.sync_import(&path, None).is_err());
    assert!(!a.root().join("sync_blobs").join(sha(b"abcdefg")).exists());
}

#[test]
fn an_invalid_received_link_is_skipped_and_the_chain_continues() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let good = input(1, "Good", "fine", None);
    phone.publish(std::slice::from_ref(&good), true);
    // A title with a control character is valid on the wire but fails a semantic limit: skipped and counted.
    let key = wire::link_key(OWNER, &synthetic::id(7002));
    let changes = BTreeMap::from([
        (
            key.clone(),
            json!({"owner":OWNER,"link_id":synthetic::id(7002),"kind":"doc","added_at":1}),
        ),
        (
            format!("{key}/content"),
            json!({"hash":"a".repeat(64),"mime":"application/pdf","modified":1,"shared":0,"protected":0}),
        ),
        (
            format!("{key}/title"),
            json!({"title":"bad\u{7}title","title_user_edited":0}),
        ),
    ]);
    phone.write(changes, "link.capture");
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["applied"], 1, "{out}");
    assert_eq!(out["links"]["skipped"], 1, "{out}");
    // Later operations still arrive.
    let mut again = good.clone();
    again.title = "Good 2".into();
    phone.publish(&[again], false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).title, "Good 2");
}

#[test]
fn more_than_a_thousand_visible_links_after_the_union_skips_phone_links_and_keeps_desktop_rows() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let root = a.root().to_owned();
    {
        let conn = Connection::open(root.join("library.sqlite3")).unwrap();
        let tx = conn.unchecked_transaction().unwrap();
        for n in 0..1000u128 {
            tx.execute(
                "INSERT INTO link_records(row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text,preview) VALUES(?1,?2,?3,'doc',?4,'application/pdf',1,?5,0,0,'[]','unreachable',0,?6,'',0)",
                rusqlite::params![
                    uuid::Uuid::new_v4().to_string(),
                    OWNER,
                    synthetic::id(100_000 + n),
                    format!("Desktop {n}"),
                    "d".repeat(64),
                    n as i64
                ],
            )
            .unwrap();
        }
        tx.commit().unwrap();
    }
    let mut phone = join(&mut a, dir.path());
    phone.publish(&[input(1, "One too many", "", None)], true);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["not_shown"], 1, "{out}");
    assert_eq!(a.link_count().unwrap(), 1000);
    assert!(rows(&a).iter().all(|r| r.title != "One too many"));
}

// ---------------------------------------------------------------------------
// The enable operation
// ---------------------------------------------------------------------------

#[test]
fn nothing_enables_on_its_own_and_a_zero_link_enable_is_a_valid_marker_only_operation() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    // No baseline yet: nothing to enable.
    assert!(!a.sync_links_on_hello(true).unwrap());
    let mut phone = join(&mut a, dir.path());
    assert!(!a.sync_links_enabled().unwrap());
    // A peer that does not announce links changes nothing.
    assert!(!a.sync_links_on_hello(false).unwrap());
    assert!(!a.sync_links_enabled().unwrap());
    let before = phone.receive(&a);
    assert!(link_keys(&before).is_empty());
    // A peer that announces: one operation, marker only, written once.
    assert!(a.sync_links_on_hello(true).unwrap());
    assert!(a.sync_links_enabled().unwrap());
    assert!(!a.sync_links_on_hello(true).unwrap());
    let header = phone.receive(&a);
    let keys = link_keys(&header);
    assert_eq!(keys, vec![wire::marker_key(&phone.peer)]);
    // The session continues: the phone's own zero-link enable is accepted, the desktop does not enable again.
    phone.publish(&[], true);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["applied"], 0, "{out}");
    assert_eq!(a.link_count().unwrap(), 0);
    assert_eq!(
        a.sync_status().unwrap()["pending_gaps"],
        0,
        "{}",
        a.sync_status().unwrap()
    );
}

#[test]
fn enable_carries_rows_with_the_phone_rules_protected_metadata_only_big_text_skipped_bad_rows_left_out()
 {
    let dir = tempfile::tempdir().unwrap();
    let big = "\u{20ac}".repeat(400_000); // 400,000 UTF-16 units but 1,200,000 UTF-8 bytes
    let links = vec![
        archive_link(1, "plain searchable words", 0),
        archive_link(2, "protected text must not travel", 1),
        archive_link(3, &big, 0),
        archive_link(4, "poisoned number", 0),
        archive_link(5, "", 0),
    ];
    let mut a = library_with(dir.path(), "a", links);
    let root = a.root().to_owned();
    // Row 4 holds a number that no peer can read exactly (archive restore accepts it).
    Connection::open(root.join("library.sqlite3"))
        .unwrap()
        .execute(
            "UPDATE link_records SET modified=9007199254740992 WHERE link_id=?1",
            [synthetic::id(7004)],
        )
        .unwrap();
    let mut phone = join(&mut a, dir.path());
    assert!(a.sync_links_on_hello(true).unwrap());
    let header = phone.receive(&a);
    let enable = header
        .operations
        .iter()
        .find(|op| op.evidence["kind"] == "links.enable")
        .unwrap();
    let cell = |n: u128, suffix: &str| {
        enable
            .changes
            .get(&format!(
                "{}{suffix}",
                wire::link_key(OWNER, &synthetic::id(7000 + n))
            ))
            .cloned()
    };
    // Plain row: text and preview cells (it has a preview file), no presence cell (absent = live).
    assert_eq!(
        cell(1, "/text").unwrap()["sha256"],
        sha(b"plain searchable words")
    );
    assert_eq!(cell(1, "/preview").unwrap()["sha256"], sha(PDF));
    assert!(cell(1, "/presence").is_none());
    assert!(cell(1, "/filen").unwrap()["path"] == "/a/b");
    // Protected: metadata only, null text and preview, and no blob for it.
    assert!(cell(2, "").is_some() && cell(2, "/content").unwrap()["protected"] == 1);
    assert!(cell(2, "/text").unwrap().is_null() && cell(2, "/preview").unwrap().is_null());
    assert!(
        !header
            .blobs
            .iter()
            .any(|b| b.sha256 == sha(b"protected text must not travel"))
    );
    // Text over 1 MiB in bytes: the cell is not written, the link still is.
    assert!(cell(3, "/content").is_some());
    assert!(cell(3, "/text").is_none());
    assert!(!header.blobs.iter().any(|b| b.sha256 == sha(big.as_bytes())));
    // Unreadable number: the row is left out; the session still works.
    assert!(cell(4, "").is_none() && cell(4, "/content").is_none());
    // Empty text maps to a null cell.
    assert!(cell(5, "/text").unwrap().is_null());
    // The desktop's own cells were not applied back onto its rows.
    assert_eq!(row_of(&a, 3).text, big);
    // Every blob the operation references is in the bundle, byte-exact.
    for blob in &header.blobs {
        assert!(phone.blobs.join(&blob.sha256).exists());
    }
    assert!(phone.state.conflicts().is_empty());
}

#[test]
fn export_works_after_a_preview_changes_before_the_peer_acknowledges() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![archive_link(1, "words", 0)]);
    let mut phone = join(&mut a, dir.path());
    a.sync_links_on_hello(true).unwrap();
    let row = row_of(&a, 1);
    // The live preview file is replaced before the first export (a rewrite in place, not a new inode).
    let live = preview_file(&a, &row);
    let mut writable = fs::metadata(&live).unwrap().permissions();
    std::os::unix::fs::PermissionsExt::set_mode(&mut writable, 0o600);
    fs::set_permissions(&live, writable).unwrap();
    fs::write(&live, b"%PDF-1.4 changed after the enable operation").unwrap();
    let header = phone.receive(&a);
    // The bundle carries the kept copy that the operation names, not the changed file.
    let want = sha(PDF);
    assert!(header.blobs.iter().any(|b| b.sha256 == want));
    assert_eq!(
        fs::read(phone.blobs.join(&want)).unwrap(),
        PDF,
        "the exported blob is the one the cell digests"
    );
}

#[test]
fn a_restore_after_enabling_stays_desktop_only_until_a_fresh_pairing() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    a.sync_links_on_hello(true).unwrap();
    let first = phone.receive(&a);
    assert_eq!(link_keys(&first).len(), 1); // the marker
    let more = archive(
        &dir.path().join("src-more"),
        vec![archive_link(7, "later row", 0)],
    );
    restore(&mut a, &more);
    assert_eq!(a.link_count().unwrap(), 1);
    let second = phone.receive(&a);
    assert_eq!(
        link_keys(&second),
        link_keys(&first),
        "no new link operation after enabling"
    );
    // Connection reset: the applied-cells store goes, rows stay, links enable again with the new replica.
    let status = a.status().unwrap();
    a.request_reset(Mode::Connection, &status.library_id, status.revision)
        .unwrap();
    let root = a.root().to_owned();
    drop(a);
    let mut a = Library::open(&root).unwrap();
    assert!(projection(&a).is_empty());
    assert_eq!(a.link_count().unwrap(), 1, "a reset deletes no link row");
    assert!(!a.sync_summary().unwrap()["adopted"].as_bool().unwrap());
    let dir2 = tempfile::tempdir().unwrap();
    let mut phone2 = join(&mut a, dir2.path());
    assert!(!a.sync_links_enabled().unwrap());
    assert!(a.sync_links_on_hello(true).unwrap());
    let header = phone2.receive(&a);
    let key = wire::link_key(OWNER, &synthetic::id(7007));
    assert!(
        link_keys(&header).contains(&key),
        "{:?}",
        link_keys(&header)
    );
    // Nothing was deleted by the new pairing either.
    assert_eq!(a.link_count().unwrap(), 1);
}

// ---------------------------------------------------------------------------
// Conflicts, adoption, gates
// ---------------------------------------------------------------------------

fn conflicting_pair(dir: &Path) -> (Library, Phone) {
    let mut a = library_with(dir, "a", vec![archive_link(1, "desktop words", 0)]);
    let mut phone = join(&mut a, dir);
    let mut l = input(1, "Phone title", "phone words", None);
    l.hash = "b".repeat(64);
    phone.publish(std::slice::from_ref(&l), true);
    let out = phone.send(&mut a);
    assert!(out["conflicts"].as_u64().unwrap() > 0, "{out}");
    (a, phone)
}

#[test]
fn a_concurrent_desktop_link_is_not_overwritten_by_the_phone_and_conflicts_are_phone_business() {
    let dir = tempfile::tempdir().unwrap();
    let (mut a, _phone) = conflicting_pair(dir.path());
    // The desktop enabled by the incoming bundle with its own cells concurrent with the phone's: both values
    // stay, the conflicted link is not applied, the desktop row is unchanged.
    assert_eq!(row_of(&a, 1).title, "Linked 1");
    assert_eq!(row_of(&a, 1).text, "desktop words");
    let status = a.sync_status().unwrap();
    let conflicts = status["conflicts"].as_object().unwrap();
    let link_key = conflicts
        .keys()
        .find(|k| k.starts_with("link_records/"))
        .expect("a link conflict")
        .clone();
    // sync_resolve refuses every link key.
    let ids: Vec<Value> = conflicts[&link_key]
        .as_array()
        .unwrap()
        .iter()
        .map(|alt| alt["id"].clone())
        .collect();
    let err = a
        .sync_resolve_json(&link_key, &Value::Array(ids), json!(null))
        .unwrap_err();
    assert!(err.to_string().contains("phone"), "{err}");
    // The backup gate ignores link conflicts: the export works while one is present.
    let file = dir.path().join("with-link-conflict.stackbackup");
    a.export_archive(&file, &Control::default()).unwrap();
    assert!(file.exists());
    // ... and a link conflict is the only kind present.
    assert!(conflicts.keys().all(|k| k.starts_with("link_records/")));
}

#[test]
fn adoption_by_file_writes_the_enable_with_an_empty_context_and_does_not_override_the_phone() {
    let dir = tempfile::tempdir().unwrap();
    // The phone owns the baseline; the desktop is brand new with one restored-free row inserted directly.
    let mut p = Library::open(&dir.path().join("p")).unwrap();
    let mut d = Library::open(&dir.path().join("d")).unwrap();
    let d_replica = d.status().unwrap().replica_id;
    let p_replica = p.status().unwrap().replica_id;
    p.sync_start(std::slice::from_ref(&d_replica)).unwrap();
    let state = state_from(&mut p, &d_replica, dir.path());
    let mut phone = phone_with(state, p_replica.clone(), d_replica.clone(), dir.path());
    {
        let conn = Connection::open(d.root().join("library.sqlite3")).unwrap();
        conn.execute(
            "INSERT INTO link_records(row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text,preview) VALUES(?1,?2,?3,'doc','Desktop title','application/pdf',1,?4,0,0,'[]','unreachable',0,5,'desktop text',0)",
            rusqlite::params![uuid::Uuid::new_v4().to_string(), OWNER, synthetic::id(7001), "c".repeat(64)],
        )
        .unwrap();
    }
    let mut l = input(1, "Phone title", "phone text", None);
    l.hash = "b".repeat(64);
    phone.publish(&[l], true);
    let header = phone.header_for(&d, true);
    let path = phone.file(&header);
    let group = phone.state.library.clone();
    let members = vec![p_replica.clone(), d_replica.clone()];
    let out = d.sync_import(&path, Some((&group, &members))).unwrap();
    assert_eq!(out["adopted"], true, "{out}");
    assert!(d.sync_links_enabled().unwrap());
    // The desktop's enable operation: sequence 1 of its replica with an EMPTY context.
    let sent = phone.receive(&d);
    let own = sent
        .operations
        .iter()
        .find(|op| op.id.replica == d_replica)
        .expect("desktop enable op");
    assert_eq!(own.id.sequence, 1);
    assert!(own.context.is_empty(), "{:?}", own.context);
    assert_eq!(own.evidence["kind"], "links.enable");
    // It is concurrent with the phone baseline, so the phone's values are kept next to it as a conflict and the
    // desktop row is left as it was.
    assert!(
        !d.sync_status().unwrap()["conflicts"]
            .as_object()
            .unwrap()
            .is_empty()
    );
    assert_eq!(row_of(&d, 1).title, "Desktop title");
}

#[test]
fn a_document_edit_after_enabling_emits_no_link_change_and_keeps_the_link_projection() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let mut l = input(1, "Kept", "kept words", Some(phone.put(PDF)));
    phone.publish(std::slice::from_ref(&l), true);
    phone.send(&mut a);
    let before = projection(&a);
    assert!(before.keys().any(|k| k.contains("/preview")));
    let history = a.sync_status().unwrap()["operations"].as_u64().unwrap();
    // A document edit: one new operation, with no link key in it; the link projection is byte-identical.
    let id = a.manifest().unwrap().rows("documents")[0]["id"]
        .as_str()
        .unwrap()
        .to_owned();
    let token = a.token(&id).unwrap();
    a.edit_document(
        &token,
        stack_core::DocumentEdit::Rename("Renamed after enabling".into()),
        &Control::default(),
    )
    .unwrap();
    assert_eq!(
        a.sync_status().unwrap()["operations"].as_u64().unwrap(),
        history + 1
    );
    let header = phone.receive(&a);
    let newest = header
        .operations
        .iter()
        .filter(|op| op.id.replica == phone.peer)
        .max_by_key(|op| op.id.sequence)
        .unwrap();
    assert!(
        newest
            .changes
            .keys()
            .all(|k| !k.starts_with("link_records/"))
    );
    assert_eq!(projection(&a), before);
    // A received bundle (here a link-only change) installs through the document projection and keeps the link
    // projection and rows consistent.
    l.title = "Kept 2".into();
    phone.publish(std::slice::from_ref(&l), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).title, "Kept 2");
    assert!(projection(&a).keys().any(|k| k.contains("/preview")));
    assert_eq!(
        a.manifest().unwrap().rows("documents")[0]["title"],
        "Renamed after enabling"
    );
}

// ---------------------------------------------------------------------------
// Process stops
// ---------------------------------------------------------------------------

fn state_text(library: &Library) -> String {
    db(library)
        .query_row("SELECT state FROM sync_state", [], |r| r.get(0))
        .unwrap()
}

#[test]
fn a_stop_while_keeping_blobs_leaves_only_harmless_files_and_the_next_enable_succeeds() {
    for point in [
        "link_blob_temp",
        "link_blob_kept",
        "link_enable_transaction",
    ] {
        let dir = tempfile::tempdir().unwrap();
        let mut a = library_with(dir.path(), "a", vec![archive_link(1, "stop words", 0)]);
        let mut phone = join(&mut a, dir.path());
        let before = state_text(&a);
        fail_at(&mut a, point);
        assert!(a.sync_links_on_hello(true).is_err(), "{point}");
        // Nothing durable changed: not enabled, same state, no projection.
        assert_eq!(state_text(&a), before, "{point}");
        assert!(projection(&a).is_empty(), "{point}");
        assert!(!a.sync_links_enabled().unwrap(), "{point}");
        let blobs = a.root().join("sync_blobs");
        if point == "link_blob_kept" || point == "link_enable_transaction" {
            assert!(blobs.join(sha(PDF)).exists(), "{point}: orphan blob");
        }
        // Reopen and retry: succeeds, the blobs verify, the phone takes the operation.
        let root = a.root().to_owned();
        drop(a);
        let mut a = Library::open(&root).unwrap();
        assert!(a.sync_links_on_hello(true).unwrap(), "{point}");
        let header = phone.receive(&a);
        assert!(
            header.blobs.iter().any(|b| b.sha256 == sha(b"stop words")),
            "{point}"
        );
        assert!(phone.state.conflicts().is_empty());
    }
}

#[test]
fn a_stop_after_the_enable_commit_cannot_lose_a_blob() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![archive_link(1, "durable words", 0)]);
    let mut phone = join(&mut a, dir.path());
    fail_at(&mut a, "link_enable_committed");
    assert!(a.sync_links_on_hello(true).is_err());
    let root = a.root().to_owned();
    drop(a);
    let a = Library::open(&root).unwrap();
    assert!(a.sync_links_enabled().unwrap(), "the operation committed");
    let header = phone.receive(&a);
    assert!(
        header
            .blobs
            .iter()
            .any(|b| b.sha256 == sha(b"durable words"))
    );
    assert!(!projection(&a).is_empty());
}

#[test]
fn a_stop_inside_the_install_transaction_rolls_back_rows_and_projection_and_a_retry_applies() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    phone.publish(
        &[input(1, "Retry", "retry words", Some(phone.put(PDF)))],
        true,
    );
    let path = phone.bundle(&a);
    fail_at(&mut a, "sync_transaction");
    assert!(a.sync_import(&path, None).is_err());
    assert_eq!(a.link_count().unwrap(), 0);
    assert!(projection(&a).is_empty());
    assert!(!a.sync_links_enabled().unwrap());
    no_probe(&mut a);
    let out = a.sync_import(&path, None).unwrap();
    assert_eq!(out["links"]["applied"], 1, "{out}");
    assert_eq!(row_of(&a, 1).pending, 0);
}

#[test]
fn a_stop_after_the_commit_leaves_a_pending_preview_that_the_startup_repair_finishes() {
    for point in [
        "sync_committed",
        "link_preview_temp",
        "link_preview_renamed",
    ] {
        let dir = tempfile::tempdir().unwrap();
        let mut a = library_with(dir.path(), "a", vec![]);
        let mut phone = join(&mut a, dir.path());
        phone.publish(
            &[input(1, "Pending", "pending words", Some(phone.put(PDF)))],
            true,
        );
        let path = phone.bundle(&a);
        fail_at(&mut a, point);
        assert!(a.sync_import(&path, None).is_err(), "{point}");
        // The transaction committed: the row, its text and the marker are there; export skips the preview.
        let row = row_of(&a, 1);
        assert_eq!(row.text, "pending words", "{point}");
        assert_eq!(row.preview_sha, sha(PDF), "{point}");
        if point != "link_preview_renamed" {
            assert_eq!(row.pending, 1, "{point}");
            assert!(!preview_file(&a, &row).exists(), "{point}");
        }
        let view = a.linked_document(&row.row_id).unwrap();
        assert!(
            view.preview.is_none(),
            "{point}: preview not ready while pending"
        );
        let root = a.root().to_owned();
        drop(a);
        let a = Library::open(&root).unwrap();
        let row = row_of(&a, 1);
        assert_eq!(row.pending, 0, "{point}");
        assert_eq!(fs::read(preview_file(&a, &row)).unwrap(), PDF, "{point}");
        // No temp file is left behind.
        assert!(
            fs::read_dir(root.join("links")).unwrap().all(|e| !e
                .unwrap()
                .file_name()
                .to_string_lossy()
                .ends_with(".part"))
        );
    }
}

#[test]
fn the_startup_repair_touches_only_pending_rows_and_removes_orphans() {
    let dir = tempfile::tempdir().unwrap();
    let a = library_with(dir.path(), "a", vec![archive_link(1, "restored", 0)]);
    let row = row_of(&a, 1);
    assert_eq!(
        (row.preview, row.preview_sha.as_str(), row.pending),
        (1, "", 0)
    );
    let file = preview_file(&a, &row);
    // A restored preview whose bytes no digest knows: repair must neither hash nor rewrite it.
    let mut writable = fs::metadata(&file).unwrap().permissions();
    std::os::unix::fs::PermissionsExt::set_mode(&mut writable, 0o600);
    fs::set_permissions(&file, writable).unwrap();
    fs::write(&file, b"%PDF-1.4 user bytes that nothing can verify").unwrap();
    let orphan = a
        .root()
        .join("links")
        .join(format!("{}.pdf", uuid::Uuid::new_v4()));
    fs::write(&orphan, b"%PDF-1.4 orphan").unwrap();
    let stale = a.root().join("links").join(".link-x.part");
    fs::write(&stale, b"partial").unwrap();
    let root = a.root().to_owned();
    drop(a);
    let a = Library::open(&root).unwrap();
    assert_eq!(
        fs::read(&file).unwrap(),
        b"%PDF-1.4 user bytes that nothing can verify"
    );
    assert!(!orphan.exists() && !stale.exists());
    assert_eq!(row_of(&a, 1).pending, 0);
    assert_eq!(count_files(&root.join("links")), 1);
}

#[test]
fn reset_clears_the_link_projection_and_a_new_pairing_enables_again() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    phone.publish(&[input(1, "Survivor", "survives reset", None)], true);
    phone.send(&mut a);
    assert!(a.sync_links_enabled().unwrap());
    assert!(!projection(&a).is_empty());
    let status = a.status().unwrap();
    a.request_reset(Mode::Connection, &status.library_id, status.revision)
        .unwrap();
    let root = a.root().to_owned();
    drop(a);
    let mut a = Library::open(&root).unwrap();
    assert!(projection(&a).is_empty());
    assert_eq!(a.link_count().unwrap(), 1);
    assert!(!a.sync_links_enabled().unwrap());
    let dir2 = tempfile::tempdir().unwrap();
    let mut phone2 = join(&mut a, dir2.path());
    // The phone of the new pairing announces links again: the desktop enables again, with its surviving row.
    assert!(a.sync_links_on_hello(true).unwrap());
    let header = phone2.receive(&a);
    assert!(link_keys(&header).contains(&wire::link_key(OWNER, &synthetic::id(7001))));
    assert_eq!(a.link_count().unwrap(), 1);
}

// ---------------------------------------------------------------------------
// Review fixes (D5 Opus review)
// ---------------------------------------------------------------------------

fn corrupt_projection(library: &Library) {
    let conn = Connection::open(library.root().join("library.sqlite3")).unwrap();
    conn.execute(
        "INSERT OR REPLACE INTO link_projection VALUES('link_records/[\"x\",\"y\"]/title','{not json')",
        [],
    )
    .unwrap();
}

#[test]
fn a_preview_that_cannot_be_installed_never_fails_the_import() {
    // links/ is a plain file: the directory cannot be made or used.
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let blocker = a.root().join("links");
    let _ = fs::remove_dir_all(&blocker);
    fs::write(&blocker, b"not a directory").unwrap();
    phone.publish(
        &[input(1, "Pending", "pending words", Some(phone.put(PDF)))],
        true,
    );
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["applied"], 1, "{out}");
    assert_eq!(out["links"]["preview_failed"], 1, "{out}");
    assert_eq!(row_of(&a, 1).pending, 1);
    assert_eq!(row_of(&a, 1).text, "pending words");
    // The receipt was produced: the chain continues.
    phone.receive(&a);
    assert_eq!(a.sync_status().unwrap()["pending_gaps"], 0);
    // Once the obstacle is gone the startup repair finishes the preview.
    fs::remove_file(&blocker).unwrap();
    let root = a.root().to_owned();
    drop(a);
    let a = Library::open(&root).unwrap();
    let row = row_of(&a, 1);
    assert_eq!(row.pending, 0);
    assert_eq!(fs::read(preview_file(&a, &row)).unwrap(), PDF);

    // A directory where the preview file must go: same outcome.
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let links = a.root().join("links");
    a.set_probe(Box::new(move |point| {
        if point == "link_preview_temp" {
            for entry in fs::read_dir(&links)?.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if let Some(id) = name
                    .strip_prefix(".link-")
                    .and_then(|n| n.strip_suffix(".part"))
                {
                    fs::create_dir(links.join(format!("{id}.pdf")))?;
                }
            }
        }
        Ok(())
    }));
    phone.publish(
        &[input(1, "Pending", "pending words", Some(phone.put(PDF)))],
        true,
    );
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["preview_failed"], 1, "{out}");
    assert_eq!(row_of(&a, 1).pending, 1);
    assert!(
        fs::read_dir(a.root().join("links")).unwrap().all(|e| !e
            .unwrap()
            .file_name()
            .to_string_lossy()
            .ends_with(".part")),
        "no temp file is left"
    );
    phone.receive(&a);
    assert_eq!(a.sync_status().unwrap()["pending_gaps"], 0);
}

#[test]
fn a_kept_preview_blob_with_the_wrong_bytes_is_dropped_not_copied_again_every_install() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let blob = a.root().join("sync_blobs").join(sha(PDF));
    let spoil = blob.clone();
    a.set_probe(Box::new(move |point| {
        if point == "sync_committed" {
            let _ = fs::remove_file(&spoil);
            fs::write(&spoil, vec![b'x'; PDF.len()])?;
        }
        Ok(())
    }));
    phone.publish(
        &[input(1, "Pending", "pending words", Some(phone.put(PDF)))],
        true,
    );
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["preview_failed"], 0, "{out}");
    assert_eq!(row_of(&a, 1).pending, 1);
    assert!(!blob.exists(), "the spoiled blob is removed");
}

#[test]
fn the_startup_repair_removes_a_stale_preview_file_of_a_row_without_a_preview() {
    let dir = tempfile::tempdir().unwrap();
    let a = library_with(dir.path(), "a", vec![archive_link(2, "no preview", 0)]);
    let row = row_of(&a, 2);
    assert_eq!(row.preview, 0);
    let stale = preview_file(&a, &row);
    fs::create_dir_all(stale.parent().unwrap()).unwrap();
    fs::write(&stale, PDF).unwrap();
    let root = a.root().to_owned();
    drop(a);
    let _a = Library::open(&root).unwrap();
    assert!(!stale.exists());
}

/// Both ways the enable operation gets built: the hello hook, and the incoming bundle that carries links.
fn enable_both_ways(prepare: impl Fn(&Library), check: impl Fn(&Header, &str)) {
    // Hook.
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![archive_link(1, "stop words", 0)]);
    let mut phone = join(&mut a, dir.path());
    prepare(&a);
    assert!(a.sync_links_on_hello(true).unwrap(), "hook enables");
    let header = phone.receive(&a);
    check(&header, "hook");
    // Incoming trigger.
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![archive_link(1, "stop words", 0)]);
    let mut phone = join(&mut a, dir.path());
    prepare(&a);
    phone.publish(&[input(5, "Phone", "phone words", None)], true);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["applied"], 1, "{out}");
    assert!(a.sync_links_enabled().unwrap());
    let header = phone.receive(&a);
    check(&header, "incoming");
}

fn has_text_cell(header: &Header) -> bool {
    link_keys(header).iter().any(|k| k.ends_with("/text"))
}

#[test]
fn a_bad_existing_blob_is_replaced_and_enabling_never_fails_on_content() {
    // Wrong length, wrong bytes of the right length (and for the preview too): removed, copied again.
    for bad in [b"short".to_vec(), vec![b'z'; "stop words".len()]] {
        enable_both_ways(
            |a| {
                let blobs = a.root().join("sync_blobs");
                fs::create_dir_all(&blobs).unwrap();
                fs::write(blobs.join(sha(b"stop words")), &bad).unwrap();
            },
            |header, how| {
                assert!(has_text_cell(header), "{how}");
                assert!(
                    header.blobs.iter().any(|b| b.sha256 == sha(b"stop words")),
                    "{how}"
                );
            },
        );
    }
}

#[test]
fn a_write_failure_while_keeping_blobs_ends_in_a_marker_only_enable() {
    // A directory sits where the blob must go: it cannot be replaced.
    enable_both_ways(
        |a| {
            let blobs = a.root().join("sync_blobs");
            fs::create_dir_all(blobs.join(sha(b"stop words")).join("inner")).unwrap();
        },
        |header, how| {
            assert!(!has_text_cell(header), "{how}");
            assert_eq!(link_keys(header).len(), 1, "{how}: the marker alone");
        },
    );
}

#[test]
fn an_undecodable_link_projection_row_does_not_fail_installs() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let mut l1 = input(1, "One", "one words", None);
    phone.publish(std::slice::from_ref(&l1), true);
    phone.send(&mut a);
    corrupt_projection(&a);
    l1.title = "One again".into();
    phone.publish(std::slice::from_ref(&l1), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).title, "One again");
    // The hook still works on such a library too.
    let dir = tempfile::tempdir().unwrap();
    let mut b = library_with(dir.path(), "b", vec![archive_link(1, "stop words", 0)]);
    let _phone = join(&mut b, dir.path());
    corrupt_projection(&b);
    assert!(b.sync_links_on_hello(true).unwrap());
}

#[test]
fn a_gone_link_restored_from_an_archive_is_not_deleted_again_by_an_unrelated_install() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let mut l1 = input(1, "Doomed", "doomed words", None);
    let l3 = input(3, "Other", "other words", None);
    phone.publish(&[l1.clone(), l3.clone()], true);
    phone.send(&mut a);
    l1.presence = LinkPresence {
        state: LinkState::Gone,
        reason: "deleted".into(),
        since: 9,
    };
    phone.publish(std::slice::from_ref(&l1), false);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["deleted"], 1, "{out}");
    assert_eq!(a.link_count().unwrap(), 1);
    // The same identity comes back through an archive restore.
    let bytes = archive(
        &dir.path().join("src-again"),
        vec![archive_link(1, "restored words", 0)],
    );
    restore(&mut a, &bytes);
    assert_eq!(row_of(&a, 1).title, "Linked 1");
    // An unrelated install (another link's title): the restored row stays.
    let mut l3b = l3.clone();
    l3b.title = "Other renamed".into();
    phone.publish(&[l3b], false);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["deleted"], 0, "{out}");
    assert_eq!(row_of(&a, 1).title, "Linked 1");
    assert_eq!(row_of(&a, 3).title, "Other renamed");
}

#[test]
fn a_missing_blob_does_not_stall_the_link_and_is_retried_when_the_blob_arrives() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let mut l = input(2, "No blob", "late words", None);
    phone.publish(std::slice::from_ref(&l), true);
    let strip = |phone: &mut Phone, a: &Library| {
        let mut header = phone.header_for(a, false);
        header.blobs.retain(|b| b.sha256 != sha(b"late words"));
        phone.file(&header)
    };
    let path = strip(&mut phone, &a);
    let out = a.sync_import(&path, None).unwrap();
    assert_eq!(out["links"]["missing_blob"], 1, "{out}");
    assert_eq!(out["links"]["applied"], 1, "{out}");
    assert_eq!(row_of(&a, 2).text, "");
    // A title-only change is applied while the text is still missing.
    l.title = "Renamed".into();
    phone.publish(std::slice::from_ref(&l), false);
    let path = strip(&mut phone, &a);
    let out = a.sync_import(&path, None).unwrap();
    assert_eq!(out["links"]["applied"], 1, "{out}");
    assert_eq!(row_of(&a, 2).title, "Renamed");
    // So is a trash-only change.
    l.presence = trashed("trashed", 5);
    phone.publish(std::slice::from_ref(&l), false);
    let path = strip(&mut phone, &a);
    a.sync_import(&path, None).unwrap();
    assert_eq!(row_of(&a, 2).state, "trashed");
    assert_eq!(row_of(&a, 2).text, "");
    // The blob arrives later (no new operation): the text part is applied then.
    let mut header = phone.header_for(&a, false);
    assert!(header.operations.is_empty());
    header.blobs = vec![Blob {
        sha256: sha(b"late words"),
        length: b"late words".len() as u64,
    }];
    let path = phone.file(&header);
    let out = a.sync_import(&path, None).unwrap();
    assert_eq!(out["links"]["missing_blob"], 0, "{out}");
    assert_eq!(row_of(&a, 2).text, "late words");
    assert_eq!(row_of(&a, 2).title, "Renamed");
    assert_eq!(row_of(&a, 2).state, "trashed");
}

#[test]
fn sync_summary_after_an_install_does_not_parse_the_whole_state() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    phone.publish(&[input(1, "One", "one words", None)], true);
    phone.send(&mut a);
    // Make a full state load impossible: the history is garbage. The summary needs only identity and frontier,
    // and `links_enabled` comes from the cache the install set.
    let conn = Connection::open(a.root().join("library.sqlite3")).unwrap();
    let text: String = conn
        .query_row("SELECT state FROM sync_state", [], |r| r.get(0))
        .unwrap();
    let mut value: Value = serde_json::from_str(&text).unwrap();
    value["history"] = json!("garbage");
    conn.execute(
        "UPDATE sync_state SET state=?1",
        [serde_json::to_string(&value).unwrap()],
    )
    .unwrap();
    drop(conn);
    let summary = a.sync_summary().unwrap();
    assert_eq!(summary["links_enabled"], true, "{summary}");
}

// ---------------------------------------------------------------------------
// Cross-implementation scenarios (driven by desktop/tools/test_link_cross_java.py)
//
// A: the REAL desktop enable operations (the Library writes them) are dumped, with everything else the phone side
//    holds, as wire JSON for the production Java kernel and validators to replay; Java's answer must equal Rust's.
// B: operations built by the Java phone writer are replayed here through the Rust kernel and validators.
// Both sides print the same "expectation" shape (see desktop/tools/java/SyncCrossHost.java).
// ---------------------------------------------------------------------------

/// The expectation of a kernel state, in the shape SyncCrossHost.expectation prints.
fn cross_expectation(state: &State) -> Value {
    let values = state.values();
    let conflicts = state.conflicts();
    let conflicted: BTreeSet<String> = conflicts.keys().cloned().collect();
    let projection = stack_sync::links::project_links(&values, &conflicted);
    let flag = |b: bool| i64::from(b);
    let mut links: Vec<Value> = projection
        .rows
        .iter()
        .map(|r| {
            json!({
                "key": wire::link_key(&r.owner, &r.link_id), "owner": r.owner, "link_id": r.link_id,
                "kind": r.kind, "added_at": r.added_at,
                "outcome": if r.metadata_only { "applied_metadata_only" } else { "applied" },
                "state": r.presence.state.as_str(), "reason": r.presence.reason, "since": r.presence.since,
                "content": r.content.as_ref().map(|c| {
                    let mut content = json!({"hash": c.hash, "mime": c.mime, "modified": c.modified,
                        "shared": flag(c.shared), "protected": flag(c.protected)});
                    if let Some(f) = &c.foreign {
                        content["origin"] = json!("foreign");
                        content["shared_by"] = json!(f.shared_by);
                        content["foreign_generation"] = json!(f.generation);
                    }
                    content
                }).unwrap_or(Value::Null),
                "title": r.title.as_ref().map(|t| json!({"title": t.title, "title_user_edited": flag(t.user_edited)})).unwrap_or(Value::Null),
                "filen": r.filen.as_ref().map(|f| json!({"uuid": f.uuid, "path": f.path})).unwrap_or(Value::Null),
                "text": r.text.as_ref().map(|b| json!({"sha256": b.sha256, "length": b.length})).unwrap_or(Value::Null),
                "preview": r.preview.as_ref().map(|b| json!({"sha256": b.sha256, "length": b.length})).unwrap_or(Value::Null),
            })
        })
        .collect();
    links.extend(projection.gone.iter().map(|(owner, id)| {
        json!({"key": wire::link_key(owner, id), "owner": owner, "link_id": id, "outcome": "gone"})
    }));
    links.sort_by(|a, b| a["key"].as_str().cmp(&b["key"].as_str()));
    let is_link = |k: &String| k.starts_with("link_records/");
    let link_values: serde_json::Map<String, Value> = values
        .iter()
        .filter(|(k, _)| is_link(k))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    let alternatives: serde_json::Map<String, Value> = state
        .registers
        .iter()
        .filter(|(k, _)| is_link(k))
        .map(|(k, alts)| (k.clone(), json!(alts.len())))
        .collect();
    let mut enables: Vec<Value> = state
        .history
        .values()
        .filter(|op| op.evidence["kind"] == "links.enable")
        .map(|op| {
            json!({"id": {"replica": op.id.replica, "sequence": op.id.sequence},
                "context": op.context, "keys": op.changes.keys().collect::<Vec<_>>()})
        })
        .collect();
    enables.sort_by_key(|e| {
        (
            e["id"]["replica"].as_str().unwrap().to_owned(),
            e["id"]["sequence"].as_u64().unwrap(),
        )
    });
    json!({"links": links, "skipped": projection.skipped, "conflicted": projection.conflicted,
        "conflict_keys": conflicted, "link_values": link_values, "alternatives": alternatives, "enable_ops": enables})
}

/// Every operation the phone side holds, as the wire JSON the kernel would send, plus the Rust expectation.
fn cross_scenario(name: &str, state: &State, desktop: &str) -> Value {
    let operations: Vec<Value> = state
        .history
        .values()
        .map(|op| {
            // Rust must accept what it dumps: the graph validator on every operation.
            stack_sync::graph::validate_changes(&op.changes).unwrap();
            serde_json::to_value(op).unwrap()
        })
        .collect();
    assert!(state.pending.is_empty());
    json!({"name": name, "desktop": desktop, "library": state.library, "members": state.members,
        "operations": operations, "expect": cross_expectation(state)})
}

fn cross_write(scenarios: &[(&str, Value)]) {
    if let Some(dir) = std::env::var_os("STACK_LINK_CROSS_DIR") {
        for (name, scenario) in scenarios {
            fs::write(
                Path::new(&dir).join(format!("A-{name}.json")),
                serde_json::to_string(scenario).unwrap(),
            )
            .unwrap();
        }
    }
}

/// The desktop's enable operation in `state`: (operation, ids of the other replica's operations).
fn desktop_enable<'a>(state: &'a State, desktop: &str) -> &'a stack_sync::Operation {
    state
        .history
        .values()
        .find(|op| op.id.replica == desktop && op.evidence["kind"] == "links.enable")
        .expect("the desktop enable operation")
}

/// The real desktop enable shape: own-only context, marker cell, never a presence cell, concurrent with the phone.
fn assert_enable_shape(state: &State, phone: &str, desktop: &str) -> Value {
    let enable = desktop_enable(state, desktop);
    let expected_context: Frontier = if enable.id.sequence > 1 {
        Frontier::from([(desktop.to_owned(), enable.id.sequence - 1)])
    } else {
        Frontier::new()
    };
    assert_eq!(enable.context, expected_context, "own-only context");
    assert!(enable.changes.contains_key(&wire::marker_key(desktop)));
    assert!(
        !enable.changes.keys().any(|k| k.ends_with("/presence")),
        "a desktop row is live: no presence cell"
    );
    // Concurrent with the phone's link operation, not after it.
    let phone_ops: Vec<_> = state
        .history
        .values()
        .filter(|op| {
            op.id.replica == phone && op.changes.keys().any(|k| k.starts_with("link_records/"))
        })
        .collect();
    assert!(!phone_ops.is_empty());
    for op in phone_ops {
        assert!(
            enable.context.get(phone).copied().unwrap_or(0) < op.id.sequence,
            "the desktop enable must not descend from the phone link operation"
        );
    }
    cross_expectation(state)
}

fn finish_a(a: &mut Library, phone: &mut Phone) {
    if !a.sync_links_enabled().unwrap() {
        assert!(a.sync_links_on_hello(true).unwrap());
    }
    phone.receive(a);
}

#[test]
fn cross_a_real_desktop_enable_shapes_for_the_java_kernel() {
    let big = "\u{20ac}".repeat(400_000);
    let mut scenarios: Vec<(&str, Value)> = Vec::new();

    // A1: desktop rows (plain with preview, protected, big text, empty text), the phone's rows (plain, protected,
    // empty, trashed, gone) with its marker; the desktop enables AFTER receiving them.
    {
        let dir = tempfile::tempdir().unwrap();
        let mut a = library_with(
            dir.path(),
            "a",
            vec![
                archive_link(1, "plain searchable words", 0),
                archive_link(2, "protected text must not travel", 1),
                archive_link(3, &big, 0),
                archive_link(5, "", 0),
            ],
        );
        let mut phone = join(&mut a, dir.path());
        let l11 = input(11, "Phone plain", "phone words", Some(phone.put(PDF2)));
        let mut l12 = input(12, "Phone secret", "phone secret", None);
        l12.protected = true;
        let l13 = input(13, "Phone empty", "", None);
        let mut l14 = input(14, "Phone trashed", "trashed words", None);
        l14.presence = trashed("user", 1_700_000_000_002);
        let mut l15 = input(15, "Phone gone", "x", None);
        l15.presence = LinkPresence {
            state: LinkState::Gone,
            reason: "forgotten".into(),
            since: 1_700_000_000_003,
        };
        // Slice 7: two of the partner's records ride in the same phone operation (one live with text and preview,
        // one unshared = trashed). The computer never puts them into its own enable operation.
        let l16 = foreign_input(16, "Shared live", "shared giraffe words", Some(phone.put(PDF)));
        let mut l17 = foreign_input(17, "Shared unshared", "unshared words", None);
        l17.presence = trashed("deleted", 1_700_000_000_004);
        phone.publish(&[l11, l12, l13, l14, l15, l16, l17], true);
        phone.send(&mut a);
        finish_a(&mut a, &mut phone);
        let expect = assert_enable_shape(&phone.state, &phone.replica, &phone.peer);
        let enable = desktop_enable(&phone.state, &phone.peer);
        let has = |n: u128, suffix: &str| {
            enable.changes.contains_key(&format!(
                "{}{suffix}",
                wire::link_key(OWNER, &synthetic::id(7000 + n))
            ))
        };
        // The enable carries the desktop's own rows (the phone's rows are applied to the table after the enable is
        // written during the import, so they are not in it); both sets are in the projection.
        assert!(has(1, "/text") && has(1, "/preview") && has(2, "") && !has(11, ""));
        assert!(
            enable.changes[&format!("{}/text", wire::link_key(OWNER, &synthetic::id(7002)))]
                .is_null()
        );
        assert!(!has(3, "/text"), "text over 1 MiB in bytes is skipped");
        let outcomes: BTreeMap<String, String> = expect["links"]
            .as_array()
            .unwrap()
            .iter()
            .map(|l| {
                (
                    l["link_id"].as_str().unwrap().to_owned(),
                    l["outcome"].as_str().unwrap().to_owned(),
                )
            })
            .collect();
        assert_eq!(outcomes[&synthetic::id(7002)], "applied_metadata_only");
        assert_eq!(outcomes[&synthetic::id(7012)], "applied_metadata_only");
        assert_eq!(outcomes[&synthetic::id(7015)], "gone");
        assert_eq!(outcomes[&synthetic::id(7011)], "applied");
        assert_eq!(outcomes[&synthetic::id(7016)], "applied");
        assert!(!has(16, "") && !has(17, ""), "the computer never writes the partner's records");
        let shared = expect["links"]
            .as_array()
            .unwrap()
            .iter()
            .find(|l| l["link_id"] == synthetic::id(7016))
            .unwrap();
        assert_eq!(shared["content"]["origin"], "foreign");
        assert_eq!(shared["content"]["shared_by"], "Sam");
        assert_eq!(expect["skipped"], 0, "{expect}");
        scenarios.push((
            "enable-after-receive",
            cross_scenario("enable-after-receive", &phone.state, &phone.peer),
        ));
    }

    // A2: zero links on both sides: the desktop enable is the marker alone.
    {
        let dir = tempfile::tempdir().unwrap();
        let mut a = library_with(dir.path(), "a", vec![]);
        let mut phone = join(&mut a, dir.path());
        phone.publish(&[], true);
        phone.send(&mut a);
        finish_a(&mut a, &mut phone);
        let expect = assert_enable_shape(&phone.state, &phone.replica, &phone.peer);
        let enable = desktop_enable(&phone.state, &phone.peer);
        assert_eq!(
            enable.changes.keys().collect::<Vec<_>>(),
            vec![&wire::marker_key(&phone.peer)]
        );
        assert!(expect["links"].as_array().unwrap().is_empty());
        scenarios.push((
            "zero-link-marker-only",
            cross_scenario("zero-link-marker-only", &phone.state, &phone.peer),
        ));
    }

    // A3: the same identity written by both with different content: concurrent, both kept, a conflict.
    {
        let dir = tempfile::tempdir().unwrap();
        let (mut a, mut phone) = conflicting_pair(dir.path());
        finish_a(&mut a, &mut phone);
        let expect = assert_enable_shape(&phone.state, &phone.replica, &phone.peer);
        assert_eq!(expect["conflicted"], 1, "{expect}");
        assert!(
            expect["alternatives"]
                .as_object()
                .unwrap()
                .values()
                .any(|n| n == 2),
            "the phone's cells survive next to the desktop's"
        );
        scenarios.push((
            "concurrent-conflict",
            cross_scenario("concurrent-conflict", &phone.state, &phone.peer),
        ));
    }

    // A4: the desktop enables at adoption: sequence 1, empty context.
    {
        let dir = tempfile::tempdir().unwrap();
        let mut p = Library::open(&dir.path().join("p")).unwrap();
        let mut d = Library::open(&dir.path().join("d")).unwrap();
        let d_replica = d.status().unwrap().replica_id;
        let p_replica = p.status().unwrap().replica_id;
        p.sync_start(std::slice::from_ref(&d_replica)).unwrap();
        let state = state_from(&mut p, &d_replica, dir.path());
        let mut phone = phone_with(state, p_replica.clone(), d_replica.clone(), dir.path());
        Connection::open(d.root().join("library.sqlite3"))
            .unwrap()
            .execute(
                "INSERT INTO link_records(row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text,preview) VALUES(?1,?2,?3,'doc','Desktop title','application/pdf',1,?4,0,0,'[]','unreachable',0,5,'desktop text',0)",
                rusqlite::params![uuid::Uuid::new_v4().to_string(), OWNER, synthetic::id(7001), "c".repeat(64)],
            )
            .unwrap();
        let mut l = input(2, "Phone only", "phone text", None);
        l.hash = "b".repeat(64);
        phone.publish(&[l], true);
        let header = phone.header_for(&d, true);
        let path = phone.file(&header);
        let members = vec![p_replica.clone(), d_replica.clone()];
        let group = phone.state.library.clone();
        let out = d.sync_import(&path, Some((&group, &members))).unwrap();
        assert_eq!(out["adopted"], true, "{out}");
        phone.receive(&d);
        assert_eq!(desktop_enable(&phone.state, &d_replica).id.sequence, 1);
        assert_enable_shape(&phone.state, &p_replica, &d_replica);
        scenarios.push((
            "adoption-empty-context",
            cross_scenario("adoption-empty-context", &phone.state, &d_replica),
        ));
    }

    cross_write(&scenarios);
}

/// B: replay what the Java phone writer produced (SyncCrossHost capture) through the Rust kernel and validators.
#[test]
#[ignore = "driven by desktop/tools/test_link_cross_java.py (needs STACK_LINK_CROSS_DIR with B-*.json)"]
fn cross_b_java_capture_is_accepted_and_projects_identically() {
    let dir =
        PathBuf::from(std::env::var_os("STACK_LINK_CROSS_DIR").expect("STACK_LINK_CROSS_DIR"));
    let mut seen = 0;
    for entry in fs::read_dir(&dir).unwrap() {
        let path = entry.unwrap().path();
        let name = path.file_name().unwrap().to_string_lossy().into_owned();
        if !name.starts_with("B-") {
            continue;
        }
        seen += 1;
        let scenario: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        let operations: Vec<stack_sync::Operation> =
            serde_json::from_value(scenario["operations"].clone()).unwrap();
        let members: BTreeSet<String> =
            serde_json::from_value(scenario["members"].clone()).unwrap();
        let mut state =
            State::new(scenario["library"].as_str().unwrap().to_owned(), members).unwrap();
        for op in &operations {
            op.validate()
                .unwrap_or_else(|e| panic!("{name}: kernel rejects: {e}"));
            stack_sync::graph::validate_changes(&op.changes)
                .unwrap_or_else(|e| panic!("{name}: validate_changes rejects: {e}"));
        }
        state
            .receive(&operations)
            .unwrap_or_else(|e| panic!("{name}: receive: {e}"));
        assert!(state.pending.is_empty(), "{name}");
        let actual = cross_expectation(&state);
        assert_eq!(actual, scenario["expect"], "{name}: Rust and Java disagree");
    }
    assert!(seen >= 2, "no B scenarios found in {}", dir.display());
}

// ---------------------------------------------------------------------------
// Real SIGKILL of a child process at the link durability boundaries (slice 6, T1)
// ---------------------------------------------------------------------------

/// Child half. Runs only when the parent re-executes this test binary with the variable set (test code only;
/// nothing in the shipped app reads it). It parks at the named probe, writes the ready marker and waits to be killed.
#[test]
fn kill_child() {
    let Ok(spec) = std::env::var("STACK_LINK_KILL_CHILD") else {
        return;
    };
    let parts: Vec<&str> = spec.split('|').collect();
    let [kind, point, root, bundle, ready] = parts[..] else {
        panic!("bad child spec");
    };
    let mut library = Library::open(Path::new(root)).unwrap();
    let (point, ready) = (point.to_owned(), PathBuf::from(ready));
    library.set_probe(Box::new(move |p| {
        if p == point {
            fs::write(&ready, p).unwrap();
            loop {
                std::thread::park();
            }
        }
        Ok(())
    }));
    match kind {
        "enable" => {
            library.sync_links_on_hello(true).unwrap();
        }
        _ => {
            library.sync_import(Path::new(bundle), None).unwrap();
        }
    }
    panic!("checkpoint was not reached");
}

/// Runs the child to `point`, SIGKILLs it, and returns once it is gone.
fn kill_at(kind: &str, point: &str, root: &Path, bundle: &Path, dir: &Path) {
    let ready = dir.join(format!("{point}.ready"));
    let mut child = std::process::Command::new(std::env::current_exe().unwrap())
        .args(["--exact", "kill_child", "--test-threads=1"])
        .env(
            "STACK_LINK_KILL_CHILD",
            format!(
                "{kind}|{point}|{}|{}|{}",
                root.display(),
                bundle.display(),
                ready.display()
            ),
        )
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .unwrap();
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
    while !ready.exists()
        && child.try_wait().unwrap().is_none()
        && std::time::Instant::now() < deadline
    {
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    assert!(ready.exists(), "{point}: not reached");
    child.kill().unwrap(); // SIGKILL
    assert!(!child.wait().unwrap().success(), "{point}");
}

#[test]
fn sigkill_during_the_enable_never_wedges_and_the_next_enable_succeeds() {
    for point in [
        "link_blob_temp",          // blob copied, before fsync
        "link_blob_kept",          // fsync + rename done, before the DB commit
        "link_enable_transaction", // inside the transaction
        "link_enable_committed",   // after the DB commit
    ] {
        let dir = tempfile::tempdir().unwrap();
        let mut a = library_with(dir.path(), "a", vec![archive_link(1, "kill words", 0)]);
        let mut phone = join(&mut a, dir.path());
        let root = a.root().to_owned();
        drop(a);
        kill_at("enable", point, &root, Path::new(""), dir.path());
        let mut a = Library::open(&root).unwrap();
        let committed = point == "link_enable_committed";
        assert_eq!(a.sync_links_enabled().unwrap(), committed, "{point}");
        assert_eq!(projection(&a).is_empty(), !committed, "{point}");
        if point == "link_blob_kept" || point == "link_enable_transaction" || committed {
            // A kept blob is present: an orphan before the commit, referenced after it.
            assert!(root.join("sync_blobs").join(sha(PDF)).exists(), "{point}");
        }
        // The next enable succeeds (or, after the commit, is already done) and the phone gets every blob.
        assert_eq!(a.sync_links_on_hello(true).unwrap(), !committed, "{point}");
        assert!(a.sync_links_enabled().unwrap(), "{point}");
        let header = phone.receive(&a);
        for blob in [PDF, b"kill words"] {
            assert!(
                header.blobs.iter().any(|b| b.sha256 == sha(blob)),
                "{point}"
            );
        }
        assert_eq!(row_of(&a, 1).text, "kill words", "{point}");
    }
}

#[test]
fn sigkill_during_an_import_leaves_the_same_final_rows_after_the_next_import() {
    for point in [
        "sync_transaction",     // inside the install transaction
        "sync_committed",       // after the transaction, before the preview temp file
        "link_preview_temp",    // preview temp written, before the rename
        "link_preview_renamed", // renamed, before preview_pending is cleared
    ] {
        let dir = tempfile::tempdir().unwrap();
        let mut a = library_with(dir.path(), "a", vec![]);
        let mut phone = join(&mut a, dir.path());
        phone.publish(
            &[input(1, "Killed", "killed words", Some(phone.put(PDF)))],
            true,
        );
        let path = phone.bundle(&a);
        let root = a.root().to_owned();
        drop(a);
        kill_at("import", point, &root, &path, dir.path());
        let a = Library::open(&root).unwrap(); // startup repair runs here
        if point == "sync_transaction" {
            assert_eq!(a.link_count().unwrap(), 0, "{point}");
            assert!(projection(&a).is_empty(), "{point}");
        } else {
            let row = row_of(&a, 1);
            assert_eq!(
                (row.text.as_str(), row.pending),
                ("killed words", 0),
                "{point}"
            );
            assert_eq!(fs::read(preview_file(&a, &row)).unwrap(), PDF, "{point}");
        }
        assert!(
            fs::read_dir(root.join("links")).map_or(true, |d| d
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .all(|n| !n.ends_with(".part"))),
            "{point}: no temp file"
        );
        let mut a = a;
        a.sync_import(&path, None).unwrap();
        let row = row_of(&a, 1);
        assert_eq!(
            (
                row.title.as_str(),
                row.text.as_str(),
                row.state.as_str(),
                row.preview_sha.as_str(),
                row.pending
            ),
            (
                "Killed",
                "killed words",
                "unreachable",
                sha(PDF).as_str(),
                0
            ),
            "{point}"
        );
        assert_eq!(fs::read(preview_file(&a, &row)).unwrap(), PDF, "{point}");
        assert_eq!(a.link_count().unwrap(), 1, "{point}");
        a.sync_import(&path, None).unwrap(); // a replay changes nothing
        assert_eq!(rows(&a).len(), 1, "{point}");
    }
}

#[test]
fn an_unwritable_blob_folder_falls_back_to_the_marker_alone_and_the_session_goes_on() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![archive_link(1, "no room", 0)]);
    let mut phone = join(&mut a, dir.path());
    // The blob folder cannot be made (a plain file sits where it should be).
    let blobs = a.root().join("sync_blobs");
    let _ = fs::remove_dir_all(&blobs);
    fs::write(&blobs, b"in the way").unwrap();
    assert!(a.sync_links_on_hello(true).unwrap());
    assert!(a.sync_links_enabled().unwrap());
    let header = phone.receive(&a);
    assert_eq!(link_keys(&header), vec![wire::marker_key(&phone.peer)]);
    assert!(projection(&a).is_empty());
    assert!(!a.sync_links_on_hello(true).unwrap(), "enabled once");
}

// ---------------------------------------------------------------------------
// Slice 7 (S7-4): the partner's shared records
// ---------------------------------------------------------------------------

fn foreign_input(n: u128, title: &str, text: &str, preview: Option<LinkBlob>) -> LinkInput {
    let mut row = input(n, title, text, preview);
    row.shared = true;
    row.foreign = Some(wire::LinkForeign {
        shared_by: "Sam".into(),
        generation: 1,
    });
    row
}

fn origin_of(library: &Library, n: u128) -> (String, String, i64) {
    db(library)
        .query_row(
            "SELECT origin,shared_by,foreign_generation FROM link_records WHERE link_id=?1",
            [synthetic::id(7000 + n)],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .unwrap()
}

#[test]
fn a_shared_record_arrives_read_only_with_its_badge_text_and_preview() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let f1 = foreign_input(1, "Shared budget", "quarterly zebra budget", Some(phone.put(PDF)));
    let own = input(2, "My own", "an own giraffe", None);
    phone.publish(&[f1.clone(), own], true);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["applied"], 2, "{out}");
    assert_eq!(origin_of(&a, 1), ("foreign".into(), "Sam".into(), 1));
    assert_eq!(origin_of(&a, 2), ("own".into(), String::new(), 0));
    // A partner's record is live (no file here to check), has text and preview, and is found by search.
    let r1 = row_of(&a, 1);
    assert_eq!(r1.state, "live");
    assert_eq!((r1.pending, r1.preview), (0, 1));
    assert_eq!(fs::read(preview_file(&a, &r1)).unwrap(), PDF);
    let hits = a.search("zebra", None, Sort::Newest, 10).unwrap();
    assert_eq!((hits.len(), hits[0].shared_by.as_str()), (1, "Sam"));
    let own_hit = &a.search("giraffe", None, Sort::Newest, 10).unwrap()[0];
    assert_eq!(own_hit.shared_by, "");
    let view = a.linked_document(&hits[0].document_id).unwrap();
    assert!(view.foreign);
    assert_eq!((view.shared_by.as_str(), view.text.as_str()), ("Sam", "quarterly zebra budget"));
    assert!(view.preview.is_some());
    // No remembered file for a shared copy, ever.
    assert!(a.set_link_local_file(&view.id, Some("/home/x/a.sam")).is_err());
    assert_eq!(view.remembered_file, None);
    // Backups hold only the person's own links: the partner's record never goes into an archive.
    assert_eq!(a.link_count().unwrap(), 2);
    let file = dir.path().join("own-only.stackbackup");
    a.export_archive(&file, &Control::default()).unwrap();
    let mut b = Library::open(&dir.path().join("restored")).unwrap();
    restore(&mut b, &fs::read(&file).unwrap());
    let restored = rows(&b);
    assert_eq!(restored.len(), 1);
    assert_eq!(restored[0].title, "My own");
}

#[test]
fn a_shared_record_updates_unshares_comes_back_and_is_deleted_only_by_gone() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let mut f = foreign_input(1, "Shared", "first words", Some(phone.put(PDF)));
    phone.publish(std::slice::from_ref(&f), true);
    phone.send(&mut a);
    let id = row_of(&a, 1).row_id;
    // A newer generation updates in place: same row, new badge name, new text.
    f.title = "Shared v2".into();
    f.text = Some("second giraffe words".into());
    f.foreign = Some(wire::LinkForeign {
        shared_by: "Samantha".into(),
        generation: 2,
    });
    phone.publish(std::slice::from_ref(&f), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).row_id, id);
    assert_eq!(origin_of(&a, 1), ("foreign".into(), "Samantha".into(), 2));
    assert_eq!(a.search("giraffe", None, Sort::Newest, 10).unwrap().len(), 1);
    // Unshare on the phone (Recently deleted, 'unshared'): a trashed presence; the computer hides it everywhere.
    f.presence = trashed("deleted", 7);
    phone.publish(std::slice::from_ref(&f), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).state, "trashed");
    assert!(a.search("giraffe", None, Sort::Newest, 10).unwrap().is_empty());
    assert_eq!(a.link_count().unwrap(), 0);
    // Shared again: live again, and still a shared copy.
    f.presence = LinkPresence {
        state: LinkState::Live,
        reason: String::new(),
        since: 0,
    };
    phone.publish(std::slice::from_ref(&f), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).state, "live");
    assert_eq!(origin_of(&a, 1).0, "foreign");
    // The phone removes its copy for good (or its 30 days pass): `gone`, and only that deletes.
    let file = preview_file(&a, &row_of(&a, 1));
    assert!(file.exists());
    f.presence = LinkPresence {
        state: LinkState::Gone,
        reason: "deleted".into(),
        since: 9,
    };
    phone.publish(std::slice::from_ref(&f), false);
    let out = phone.send(&mut a);
    assert_eq!(out["links"]["deleted"], 1, "{out}");
    assert!(rows(&a).is_empty());
    assert!(!file.exists());
}

#[test]
fn the_computer_never_writes_or_sends_back_a_shared_record() {
    let dir = tempfile::tempdir().unwrap();
    // The computer has one own link; the phone's first operation carries a shared record and its enable marker.
    let mut a = library_with(dir.path(), "a", vec![archive_link(1, "own words", 0)]);
    let mut phone = join(&mut a, dir.path());
    let shared = foreign_input(5, "Partner doc", "partner words", None);
    phone.publish(std::slice::from_ref(&shared), true);
    phone.send(&mut a);
    assert!(a.sync_links_enabled().unwrap());
    assert_eq!(origin_of(&a, 5).0, "foreign");
    // Everything the computer sends to the phone: no operation of the computer mentions the shared identity.
    let header = phone.receive(&a);
    let shared_key = shared.key();
    for op in header.operations.iter().filter(|op| op.id.replica == a.status().unwrap().replica_id) {
        assert!(
            op.changes.keys().all(|k| !k.starts_with(&shared_key)),
            "the computer must not write the partner's record: {:?}",
            op.changes.keys().collect::<Vec<_>>()
        );
    }
    assert!(phone.state.conflicts().is_empty());
    // Even a shared row that exists locally before the computer enables is left out of its enable operation.
    let dir2 = tempfile::tempdir().unwrap();
    let mut b = library_with(dir2.path(), "b", vec![archive_link(1, "own words", 0)]);
    {
        let conn = Connection::open(b.root().join("library.sqlite3")).unwrap();
        conn.execute(
            "INSERT INTO link_records(row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text,preview,origin,shared_by,foreign_generation) VALUES(?1,?2,?3,'doc','Shared copy','application/pdf',1,?4,1,0,'[]','live',0,6,'shared text',0,'foreign','Sam',1)",
            rusqlite::params![uuid::Uuid::new_v4().to_string(), OWNER, synthetic::id(7009), "d".repeat(64)],
        )
        .unwrap();
    }
    let mut phone_b = join(&mut b, dir2.path());
    assert!(b.sync_links_on_hello(true).unwrap());
    let seen = phone_b.receive(&b);
    let keys = link_keys(&seen);
    assert!(keys.iter().any(|k| k.contains(&synthetic::id(7001))));
    assert!(keys.iter().all(|k| !k.contains(&synthetic::id(7009))), "{keys:?}");
}

#[test]
fn a_cell_cannot_flip_a_rows_origin_in_either_direction() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![archive_link(1, "own words", 0)]);
    let mut phone = join(&mut a, dir.path());
    let row = row_of(&a, 1);
    a.set_link_local_file(&row.row_id, Some("/home/x/a.sam")).unwrap();
    assert!(a.sync_links_on_hello(true).unwrap());
    phone.receive(&a);
    // An own row stays own and keeps its remembered file when a cell says "shared by".
    let mut f = foreign_input(1, "Now shared", "now shared words", None);
    f.hash = "c".repeat(64);
    phone.publish(std::slice::from_ref(&f), true);
    phone.send(&mut a);
    assert_eq!(origin_of(&a, 1).0, "own");
    assert_eq!(a.link_local_file(&row.row_id).unwrap(), Some("/home/x/a.sam".into()));
    // A shared copy stays shared when a later cell has no origin.
    let dir_b = tempfile::tempdir().unwrap();
    let mut b = library_with(dir_b.path(), "b", vec![]);
    let mut phone_b = join(&mut b, dir_b.path());
    let shared = foreign_input(5, "Partner doc", "partner words", None);
    phone_b.publish(std::slice::from_ref(&shared), true);
    phone_b.send(&mut b);
    assert_eq!(origin_of(&b, 5).0, "foreign");
    let mut plain = input(5, "Partner doc", "partner words changed", None);
    plain.hash = "d".repeat(64);
    phone_b.publish(std::slice::from_ref(&plain), true);
    phone_b.send(&mut b);
    assert_eq!(origin_of(&b, 5).0, "foreign", "a cell without origin never makes a shared copy an own row");
}

#[test]
fn text_read_from_the_computers_own_copy_is_local_only_and_the_phones_newer_record_outranks_it() {
    let dir = tempfile::tempdir().unwrap();
    let mut a = library_with(dir.path(), "a", vec![]);
    let mut phone = join(&mut a, dir.path());
    let mut l1 = input(1, "Budget", "quarterly zebra budget", None);
    phone.publish(std::slice::from_ref(&l1), true);
    phone.send(&mut a);
    let id = row_of(&a, 1).row_id;
    let found = |a: &Library, q: &str| a.search(q, None, Sort::Newest, 10).unwrap().len();

    // The file on this computer differs from the record's hash and is newer than its `modified`.
    let file = dir.path().join("Budget.sam");
    fs::write(&file, b"edited on this computer").unwrap();
    a.set_link_local_file(&id, Some(file.to_str().unwrap())).unwrap();
    let report = a
        .refresh_local_files(&|_: &Path| Some("migrating flamingo plan".to_owned()))
        .unwrap();
    assert_eq!((report.changed, report.with_text), (1, 1));
    assert_eq!((found(&a, "flamingo"), found(&a, "zebra")), (1, 0));
    let local = a.linked_document(&id).unwrap();
    assert!(local.changed_here && local.text_from_computer);
    // Nothing the sync reads was changed: the record text is still the phone's.
    assert_eq!(row_of(&a, 1).text, "quarterly zebra budget");

    // A phone update that is not newer (same `modified`, new title) rewrites the row but keeps the local text.
    l1.title = "Budget renamed".into();
    phone.publish(std::slice::from_ref(&l1), false);
    phone.send(&mut a);
    assert_eq!(row_of(&a, 1).title, "Budget renamed");
    assert_eq!((found(&a, "flamingo"), found(&a, "zebra")), (1, 0));
    assert!(a.linked_document(&id).unwrap().changed_here);

    // The phone saves a NEWER version (later `modified`, new hash and text): the local text is gone from search.
    l1.modified = 4_000_000_000_000;
    l1.hash = "d".repeat(64);
    l1.text = Some("phone hippo version".into());
    phone.publish(std::slice::from_ref(&l1), false);
    phone.send(&mut a);
    assert_eq!((found(&a, "flamingo"), found(&a, "hippo")), (0, 1));
    let view = a.linked_document(&id).unwrap();
    assert!(!view.changed_here && !view.text_from_computer);
    assert_eq!(view.text, "phone hippo version");
    // Refreshing again does not bring the old local text back: the file is now older than the phone's record.
    assert_eq!(a.refresh_local_files(&|_: &Path| Some("migrating flamingo plan".to_owned())).unwrap().changed, 0);
    assert_eq!((found(&a, "flamingo"), found(&a, "hippo")), (0, 1));
}
