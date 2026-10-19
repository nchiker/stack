use crate::library::{Error, Result};
use rusqlite::Connection;

pub const VERSION: i64 = 4;
const APPLICATION_ID: i64 = 0x53544b44;

pub const V1: &str = "
CREATE TABLE meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE documents(id TEXT PRIMARY KEY,payload TEXT NOT NULL CHECK(json_valid(payload)),active INTEGER NOT NULL CHECK(active IN(0,1)),revision INTEGER NOT NULL,incarnation TEXT NOT NULL UNIQUE);
CREATE TABLE objects(kind TEXT NOT NULL,key TEXT NOT NULL,document_id TEXT REFERENCES documents(id),payload TEXT NOT NULL CHECK(json_valid(payload)),active INTEGER NOT NULL CHECK(active IN(0,1)),PRIMARY KEY(kind,key));
CREATE INDEX objects_document ON objects(document_id,kind,active);
CREATE TABLE restore_commits(operation TEXT PRIMARY KEY,receipt TEXT NOT NULL CHECK(json_valid(receipt)),source_hash TEXT NOT NULL,source_manifest BLOB NOT NULL);
CREATE VIRTUAL TABLE document_search USING fts5(document_id UNINDEXED,title,body,tokenize='unicode61');
CREATE VIRTUAL TABLE page_search USING fts5(page_id UNINDEXED,document_id UNINDEXED,body,position UNINDEXED,tokenize='unicode61');
INSERT INTO meta VALUES('revision','0'),('catalog_pristine','1');
";

const V2: &str = "
CREATE TABLE entities(kind TEXT NOT NULL,local_id TEXT NOT NULL,sync_id TEXT NOT NULL UNIQUE,active INTEGER NOT NULL CHECK(active IN(0,1)),PRIMARY KEY(kind,local_id));
CREATE TABLE journal(replica_id TEXT NOT NULL,sequence INTEGER NOT NULL CHECK(sequence>0),payload TEXT NOT NULL CHECK(json_valid(payload)),sha256 TEXT NOT NULL,PRIMARY KEY(replica_id,sequence));
CREATE TABLE source_aliases(operation TEXT NOT NULL REFERENCES restore_commits(operation),kind TEXT NOT NULL,source_id TEXT NOT NULL,local_id TEXT NOT NULL,sync_id TEXT NOT NULL,PRIMARY KEY(operation,kind,source_id));
CREATE TABLE tombstones(kind TEXT NOT NULL,sync_id TEXT NOT NULL,decision TEXT NOT NULL CHECK(json_valid(decision)),PRIMARY KEY(kind,sync_id));
CREATE TABLE peer_progress(peer_id TEXT PRIMARY KEY,acknowledged TEXT NOT NULL CHECK(json_valid(acknowledged)),received TEXT NOT NULL CHECK(json_valid(received)),status TEXT NOT NULL);
INSERT INTO meta VALUES('next_sequence','1');
";

const V3: &str = "
CREATE TABLE text_jobs(page_id TEXT PRIMARY KEY,document_id TEXT NOT NULL,incarnation TEXT NOT NULL,content_revision INTEGER NOT NULL,config TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN('queued','reading','ready','no_text','failed','cancelled')),attempt INTEGER NOT NULL DEFAULT 0,nonce TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '',method TEXT NOT NULL DEFAULT '');
CREATE INDEX text_jobs_queue ON text_jobs(state,document_id,page_id);
";

pub fn migrate(db: &mut Connection) -> Result<()> {
    let app: i64 = db.query_row("PRAGMA application_id", [], |r| r.get(0))?;
    let version: i64 = db.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    if version > VERSION {
        return Err(Error::Invalid(
            "This desktop database requires a newer Stack release",
        ));
    }
    if app != APPLICATION_ID {
        let tables: i64 = db.query_row(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            [],
            |r| r.get(0),
        )?;
        if app != 0 || version != 0 || tables != 0 {
            return Err(Error::Invalid(
                "Refusing a database that is not Stack desktop storage",
            ));
        }
    }
    if version > 0 {
        for sql in [
            "SELECT key,value FROM meta LIMIT 0",
            "SELECT id,payload,active,revision,incarnation FROM documents LIMIT 0",
            "SELECT kind,key,document_id,payload,active FROM objects LIMIT 0",
            "SELECT operation,receipt,source_hash,source_manifest FROM restore_commits LIMIT 0",
            "SELECT document_id,title,body FROM document_search LIMIT 0",
            "SELECT page_id,document_id,body,position FROM page_search LIMIT 0",
        ] {
            db.prepare(sql)?;
        }
    }
    if version >= 2 {
        for sql in [
            "SELECT kind,local_id,sync_id,active FROM entities LIMIT 0",
            "SELECT replica_id,sequence,payload,sha256 FROM journal LIMIT 0",
            "SELECT operation,kind,source_id,local_id,sync_id FROM source_aliases LIMIT 0",
            "SELECT kind,sync_id,decision FROM tombstones LIMIT 0",
            "SELECT peer_id,acknowledged,received,status FROM peer_progress LIMIT 0",
        ] {
            db.prepare(sql)?;
        }
    }
    if version >= 3 {
        db.prepare("SELECT page_id,document_id,incarnation,content_revision,config,state,attempt,nonce,error,method FROM text_jobs LIMIT 0")?;
    }
    if version >= 4 {
        db.prepare("SELECT singleton,state,projection,baseline FROM sync_state LIMIT 0")?;
        db.prepare("SELECT peer,frontier FROM sync_receipts LIMIT 0")?;
    }
    if version < VERSION {
        let tx = db.transaction()?;
        if version < 1 {
            tx.execute_batch(V1)?;
        }
        if version < 2 {
            tx.execute_batch(V2)?;
        }
        if version < 3 {
            tx.execute_batch(V3)?;
        }
        if version < 4 {
            tx.execute_batch("CREATE TABLE sync_state(singleton INTEGER PRIMARY KEY CHECK(singleton=1),state TEXT NOT NULL,projection TEXT NOT NULL,baseline TEXT NOT NULL);CREATE TABLE sync_receipts(peer TEXT PRIMARY KEY,frontier TEXT NOT NULL);")?;
        }
        tx.pragma_update(None, "application_id", APPLICATION_ID)?;
        tx.pragma_update(None, "user_version", VERSION)?;
        tx.commit()?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::library::Library;
    use rusqlite::params;
    use serde_json::json;

    #[test]
    fn additive_v1_upgrade_preserves_rows_and_establishes_stable_identity() {
        let directory = tempfile::tempdir().unwrap();
        let root = directory.path().join("library");
        drop(Library::open(&root).unwrap());
        // Populate the previous desktop schema directly, with existing identity and rows.
        std::fs::remove_file(root.join("library.sqlite3")).unwrap();
        let db = Connection::open(root.join("library.sqlite3")).unwrap();
        db.execute_batch(V1).unwrap();
        db.pragma_update(None, "application_id", APPLICATION_ID)
            .unwrap();
        db.pragma_update(None, "user_version", 1).unwrap();
        for (key, value) in [
            ("library_id", "existing-library"),
            ("replica_id", "existing-replica"),
        ] {
            db.execute("INSERT INTO meta VALUES(?1,?2)", params![key, value])
                .unwrap();
        }
        let stack =
            json!({"id":"preserved","name":"Preserved 🦀","color":-1,"visible":0,"sort_order":0});
        db.execute(
            "INSERT INTO objects VALUES('stacks','[\"preserved\"]',NULL,?1,1)",
            [stack.to_string()],
        )
        .unwrap();
        drop(db);
        let library = Library::open(&root).unwrap();
        assert_eq!(library.status().unwrap().schema, VERSION);
        assert_eq!(library.status().unwrap().library_id, "existing-library");
        assert_eq!(library.manifest().unwrap().rows("stacks"), &[stack]);
        assert_eq!(library.journal().unwrap()[0]["kind"], "library.migrate");
        let bindings = crate::library::bindings(&library.db).unwrap();
        drop(library);
        let library = Library::open(&root).unwrap();
        assert_eq!(crate::library::bindings(&library.db).unwrap(), bindings);
        assert_eq!(library.journal().unwrap().len(), 1);
    }

    #[test]
    fn rejects_future_foreign_and_partial_schema_without_overwriting_data() {
        let mut future = Connection::open_in_memory().unwrap();
        future
            .pragma_update(None, "application_id", APPLICATION_ID)
            .unwrap();
        future
            .pragma_update(None, "user_version", VERSION + 1)
            .unwrap();
        assert!(migrate(&mut future).is_err());
        let version: i64 = future
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, VERSION + 1);
        let mut foreign = Connection::open_in_memory().unwrap();
        foreign
            .execute_batch("CREATE TABLE other(value TEXT);INSERT INTO other VALUES('keep');")
            .unwrap();
        assert!(migrate(&mut foreign).is_err());
        assert_eq!(
            foreign
                .query_row("SELECT value FROM other", [], |r| r.get::<_, String>(0))
                .unwrap(),
            "keep"
        );
        let mut partial = Connection::open_in_memory().unwrap();
        partial
            .pragma_update(None, "application_id", APPLICATION_ID)
            .unwrap();
        partial.pragma_update(None, "user_version", 1).unwrap();
        assert!(migrate(&mut partial).is_err());
        let version: i64 = partial
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, 1);
    }
}
