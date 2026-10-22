//! Serialized desktop repository: complete validated rows plus transactional indexes/journal.
use crate::schema;
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use stack_archive::{Control, Manifest, validation};
use std::collections::BTreeSet;
use std::fs::{self, File, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use uuid::Uuid;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("{0}")]
    Invalid(&'static str),
    #[error("The library changed; review this action again")]
    Stale,
    #[error("Stack desktop is already using this library")]
    Busy,
    #[error("Archive was committed; reopen to finish local cleanup (operation {0})")]
    CommittedCleanup(String),
    #[error("Output was published at {0:?}; directory durability or cleanup failed")]
    PublishedExport(PathBuf),
    #[error(transparent)]
    Archive(#[from] stack_archive::Error),
    #[error("Library storage failed: {0}")]
    Sql(#[from] rusqlite::Error),
    #[error("Library I/O failed: {0}")]
    Io(#[from] std::io::Error),
    #[error("Library JSON failed: {0}")]
    Json(#[from] serde_json::Error),
    #[error(transparent)]
    Sync(#[from] stack_sync::Error),
}
pub type Result<T> = std::result::Result<T, Error>;
pub type Probe = Box<dyn FnMut(&str) -> std::io::Result<()> + Send>;

pub struct Library {
    pub(crate) root: PathBuf,
    pub(crate) db: Connection,
    pub(crate) probe: Probe,
    pub(crate) _lock: Arc<File>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct DocumentToken {
    pub id: String,
    pub incarnation: String,
    pub revision: i64,
}

#[derive(Debug, Serialize)]
pub struct Status {
    pub schema: i64,
    pub library_id: String,
    pub replica_id: String,
    pub revision: i64,
    pub documents: usize,
    pub pages: usize,
    pub stacks: usize,
    pub originals: usize,
    pub journal_events: i64,
    pub tombstones: i64,
}

pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

pub fn default_root() -> Result<PathBuf> {
    if let Some(path) = std::env::var_os("XDG_DATA_HOME") {
        let path = PathBuf::from(path);
        if !path.is_absolute() {
            return Err(Error::Invalid("XDG_DATA_HOME must be absolute"));
        }
        return Ok(path.join(crate::APP_ID));
    }
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .ok_or(Error::Invalid(
            "Set an absolute XDG_DATA_HOME or choose a library root",
        ))?;
    Ok(home.join(".local/share").join(crate::APP_ID))
}

impl Library {
    pub fn open(root: &Path) -> Result<Self> {
        prepare_root(root)?;
        let root = root.canonicalize()?;
        let lock = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(false)
            .mode(0o600)
            .custom_flags(rustix::fs::OFlags::NOFOLLOW.bits() as i32)
            .open(root.join("writer.lock"))?;
        lock.try_lock().map_err(|_| Error::Busy)?;
        let lock = Arc::new(lock);
        let mut reset = crate::reset::ColdReset::begin(&root, lock.clone())?;
        for dir in ["originals", "operations"] {
            private_dir(&root.join(dir))?;
        }
        let database = root.join("library.sqlite3");
        if database
            .symlink_metadata()
            .is_ok_and(|m| !m.is_file() || m.file_type().is_symlink())
        {
            return Err(Error::Invalid(
                "Desktop database must be an owned regular file",
            ));
        }
        let flags = rusqlite::OpenFlags::SQLITE_OPEN_READ_WRITE
            | rusqlite::OpenFlags::SQLITE_OPEN_CREATE
            | rusqlite::OpenFlags::SQLITE_OPEN_NOFOLLOW;
        let mut db = Connection::open_with_flags(database, flags)?;
        db.busy_timeout(std::time::Duration::from_secs(2))?;
        db.pragma_update(None, "foreign_keys", "ON")?;
        schema::migrate(&mut db)?;
        db.pragma_update(None, "journal_mode", "WAL")?;
        db.pragma_update(None, "synchronous", "FULL")?;
        db.pragma_update(None, "trusted_schema", "OFF")?;
        let check: String = db.query_row("PRAGMA quick_check(1)", [], |r| r.get(0))?;
        if check != "ok" {
            return Err(Error::Invalid("Desktop database integrity check failed"));
        }
        let bad: Option<String> = db
            .query_row("PRAGMA foreign_key_check", [], |r| r.get(0))
            .optional()?;
        if bad.is_some() {
            return Err(Error::Invalid(
                "Desktop database references are inconsistent",
            ));
        }
        let mut library = Self {
            root,
            db,
            probe: Box::new(|_| Ok(())),
            _lock: lock,
        };
        library.recover_operations()?;
        library.recover_text()?;
        let first = meta_opt(&library.db, "library_id")?.is_none();
        if first {
            let mut graph = library.manifest()?.into_json();
            if graph["documents"]
                .as_array()
                .expect("validated docs")
                .is_empty()
                && graph["stacks"]
                    .as_array()
                    .expect("validated stacks")
                    .is_empty()
            {
                graph["stacks"] = starter_stacks();
            }
            let manifest = Manifest::from_value(graph)?;
            let tx = library.db.transaction()?;
            put_meta(&tx, "library_id", &Uuid::new_v4().to_string())?;
            put_meta(&tx, "replica_id", &Uuid::new_v4().to_string())?;
            write_graph(&tx, &manifest)?;
            journal(
                &tx,
                "library.bootstrap",
                json!({"graph":manifest.json(),"bindings":bindings(&tx)?}),
            )?;
            tx.commit()?;
        }
        if !first
            && library
                .db
                .query_row("SELECT count(*) FROM journal", [], |r| r.get::<_, i64>(0))?
                == 0
        {
            let manifest = library.manifest()?;
            let tx = library.db.transaction()?;
            write_graph(&tx, &manifest)?;
            journal(
                &tx,
                "library.migrate",
                json!({"graph":manifest.json(),"bindings":bindings(&tx)?}),
            )?;
            tx.commit()?;
        }
        crate::reset::connection(&mut library)?;
        if let Some(reset) = reset.as_mut() {
            reset.finish()?;
        }
        // Bootstrap/migration and every authoritative mutation maintain FTS in the
        // same transaction. Reopening keeps those persisted indexes.
        stack_archive::sync_directory(&library.root)?;
        Ok(library)
    }
    /// Explicit derived-data repair; authoritative rows, decisions and journal do not change.
    pub fn rebuild_search(&mut self) -> Result<()> {
        let manifest = self.manifest()?;
        let tx = self.db.transaction()?;
        rebuild_index(&tx, &manifest)?;
        tx.commit()?;
        Ok(())
    }
    pub fn root(&self) -> &Path {
        &self.root
    }
    pub fn set_probe(&mut self, probe: Probe) {
        self.probe = probe;
    }
    pub fn revision(&self) -> Result<i64> {
        meta(&self.db, "revision")?
            .parse()
            .map_err(|_| Error::Invalid("Invalid library revision"))
    }
    pub fn manifest(&self) -> Result<Manifest> {
        read_graph(&self.db)
    }
    pub fn token(&self, id: &str) -> Result<DocumentToken> {
        self.db
            .query_row(
                "SELECT incarnation,revision FROM documents WHERE id=?1 AND active=1",
                [id],
                |r| {
                    Ok(DocumentToken {
                        id: id.to_owned(),
                        incarnation: r.get(0)?,
                        revision: r.get(1)?,
                    })
                },
            )
            .optional()?
            .ok_or(Error::Stale)
    }
    pub fn status(&self) -> Result<Status> {
        let manifest = self.manifest()?;
        Ok(Status {
            schema: schema::VERSION,
            library_id: meta(&self.db, "library_id")?,
            replica_id: meta(&self.db, "replica_id")?,
            revision: self.revision()?,
            documents: manifest.rows("documents").len(),
            pages: manifest.rows("pages").len(),
            stacks: manifest.rows("stacks").len(),
            originals: manifest.rows("files").len(),
            journal_events: self
                .db
                .query_row("SELECT count(*) FROM journal", [], |r| r.get(0))?,
            tombstones: self
                .db
                .query_row("SELECT count(*) FROM tombstones", [], |r| r.get(0))?,
        })
    }
    pub fn journal(&self) -> Result<Vec<Value>> {
        let mut query = self
            .db
            .prepare("SELECT payload FROM journal ORDER BY sequence")?;
        let rows = query.query_map([], |r| r.get::<_, String>(0))?;
        rows.map(|r| Ok(serde_json::from_str(&r?)?)).collect()
    }
    pub fn original(&self, document: &str, filename: &str) -> Result<PathBuf> {
        let path = format!("originals/{document}/{filename}");
        stack_archive::original_path(&path)?;
        self.token(document)?;
        let graph = self.manifest()?;
        if !graph.rows("files").iter().any(|f| f["path"] == path) {
            return Err(Error::Invalid("Original does not belong to this document"));
        }
        stack_archive::read_directory(&self.root.join("originals").join(document))?;
        let file = self.root.join(path);
        stack_archive::read_regular(&file)?;
        Ok(file)
    }
    pub(crate) fn checked_token(&self, token: &DocumentToken) -> Result<()> {
        if self.token(&token.id)? != *token {
            return Err(Error::Stale);
        }
        Ok(())
    }
    pub(crate) fn reserved(&self, kind: &str) -> Result<BTreeSet<String>> {
        let mut query = self
            .db
            .prepare("SELECT local_id FROM entities WHERE kind=?1")?;
        Ok(query
            .query_map([kind], |r| r.get(0))?
            .collect::<std::result::Result<_, _>>()?)
    }
    pub(crate) fn commit_graph(
        &mut self,
        manifest: &Manifest,
        kind: &str,
        payload: Value,
        affected: &BTreeSet<String>,
        pristine: bool,
        control: &Control,
    ) -> Result<()> {
        control.check()?;
        let tx = self
            .db
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let previous: Vec<(String, String)> = {
            let mut query = tx.prepare("SELECT kind,sync_id FROM entities WHERE active=1")?;
            query
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect::<std::result::Result<_, _>>()?
        };
        write_graph(&tx, manifest)?;
        for (entity_kind, sync) in previous {
            let active: bool = tx.query_row(
                "SELECT active FROM entities WHERE sync_id=?1",
                [&sync],
                |r| r.get(0),
            )?;
            if !active {
                tx.execute("INSERT INTO tombstones VALUES(?1,?2,?3) ON CONFLICT(kind,sync_id) DO UPDATE SET decision=excluded.decision",params![entity_kind,sync,payload.to_string()])?;
            }
        }
        for id in affected {
            let changed = tx.execute(
                "UPDATE documents SET revision=revision+1 WHERE id=?1 AND revision<?2",
                params![id, i64::MAX - 1],
            )?;
            if changed != 1 {
                return Err(Error::Invalid("Document revision exhausted"));
            }
        }
        if !pristine {
            put_meta(&tx, "catalog_pristine", "0")?;
        }
        journal(
            &tx,
            kind,
            json!({"change":payload,"bindings":bindings(&tx)?}),
        )?;
        (self.probe)("metadata_transaction")?;
        control.check()?;
        tx.commit()?;
        Ok(())
    }
}

fn prepare_root(root: &Path) -> Result<()> {
    if root
        .symlink_metadata()
        .is_ok_and(|m| m.file_type().is_symlink() || !m.is_dir())
    {
        return Err(Error::Invalid("Library root must be a real directory"));
    }
    if root.exists()
        && !root.join("stack-desktop-library").exists()
        && fs::read_dir(root)?.next().is_some()
    {
        return Err(Error::Invalid(
            "Choose an empty directory or an existing Stack desktop library",
        ));
    }
    if !root.exists() {
        fs::create_dir_all(root)?;
    }
    let marker = root.join("stack-desktop-library");
    if marker.exists() {
        let mut text = String::new();
        stack_archive::read_regular(&marker)?
            .take(64)
            .read_to_string(&mut text)?;
        if text != "org.stackdocs.desktop/library/1\n" {
            return Err(Error::Invalid("Unrecognized desktop library marker"));
        }
    } else {
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(marker)?;
        output.write_all(b"org.stackdocs.desktop/library/1\n")?;
        output.sync_all()?;
        stack_archive::sync_directory(root)?;
    }
    fs::set_permissions(root, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

pub(crate) fn private_dir(path: &Path) -> Result<()> {
    if !path.exists() {
        fs::create_dir(path)?;
    }
    stack_archive::read_directory(path)?;
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))?;
    Ok(())
}

pub(crate) fn meta_opt(db: &Connection, key: &str) -> Result<Option<String>> {
    Ok(db
        .query_row("SELECT value FROM meta WHERE key=?1", [key], |r| r.get(0))
        .optional()?)
}
pub(crate) fn meta(db: &Connection, key: &str) -> Result<String> {
    meta_opt(db, key)?.ok_or(Error::Invalid("Missing library identity/state"))
}
pub(crate) fn put_meta(db: &Connection, key: &str, value: &str) -> Result<()> {
    db.execute(
        "INSERT INTO meta VALUES(?1,?2) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        params![key, value],
    )?;
    Ok(())
}

pub(crate) fn read_graph(db: &Connection) -> Result<Manifest> {
    let mut graph = Manifest::empty().into_json();
    let mut docs = db.prepare("SELECT payload FROM documents WHERE active=1 ORDER BY id")?;
    for row in docs.query_map([], |r| r.get::<_, String>(0))? {
        graph["documents"]
            .as_array_mut()
            .expect("static docs")
            .push(serde_json::from_str(&row?)?);
    }
    let mut objects =
        db.prepare("SELECT kind,key,payload FROM objects WHERE active=1 ORDER BY kind,key")?;
    for row in objects.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
        ))
    })? {
        let (kind, key, payload) = row?;
        let value = serde_json::from_str(&payload)?;
        if kind == "decision_evidence" {
            graph["decision_evidence"][key] = value;
        } else {
            graph
                .get_mut(&kind)
                .and_then(Value::as_array_mut)
                .ok_or(Error::Invalid("Unknown local row type"))?
                .push(value);
        }
    }
    graph["stacks"]
        .as_array_mut()
        .expect("stacks")
        .sort_by_key(|s| s["sort_order"].as_i64().unwrap_or(-1));
    graph["pages"]
        .as_array_mut()
        .expect("pages")
        .sort_by(|a, b| {
            (a["document_id"].as_str(), a["position"].as_i64())
                .cmp(&(b["document_id"].as_str(), b["position"].as_i64()))
        });
    graph["created_at"] = json!(now());
    Ok(Manifest::from_value(graph)?)
}

pub(crate) fn row_key(table: &str, row: &Value) -> Result<String> {
    let fields: &[&str] = match table {
        "pages" | "stacks" | "tags" => &["id"],
        "suggestions" => &["document_id"],
        "files" => &["path"],
        "memberships" => &["document_id", "stack_id"],
        "choices" => &["document_id", "evidence", "field"],
        "details" | "document_tags" => &["document_id", "field_id"],
        _ => return Err(Error::Invalid("Unknown local table")),
    };
    let parts: Vec<&Value> = fields.iter().map(|f| &row[*f]).collect();
    Ok(serde_json::to_string(&parts)?)
}

pub(crate) fn owner<'a>(table: &str, row: &'a Value) -> Option<&'a str> {
    if table == "files" {
        row["path"]
            .as_str()
            .and_then(|p| stack_archive::original_path(p).ok().map(|(doc, _)| doc))
    } else {
        row["document_id"].as_str()
    }
}

pub(crate) fn write_graph(tx: &Transaction<'_>, manifest: &Manifest) -> Result<()> {
    tx.execute("UPDATE documents SET active=0 WHERE active=1", [])?;
    tx.execute("UPDATE objects SET active=0 WHERE active=1", [])?;
    tx.execute("UPDATE entities SET active=0 WHERE active=1", [])?;
    for row in manifest.rows("documents") {
        let id = row["id"].as_str().expect("validated doc ID");
        tx.execute("INSERT INTO documents VALUES(?1,?2,1,0,?3) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload,active=1",
            params![id,serde_json::to_string(row)?,Uuid::new_v4().to_string()])?;
        entity(tx, "document", id)?;
        if let Some(scan) = row["source_scan_id"].as_str() {
            entity(tx, "scan", scan)?;
        }
    }
    for table in validation::TABLES.into_iter().filter(|t| *t != "documents") {
        for row in manifest.rows(table) {
            tx.execute("INSERT INTO objects VALUES(?1,?2,?3,?4,1) ON CONFLICT(kind,key) DO UPDATE SET document_id=excluded.document_id,payload=excluded.payload,active=1",
                params![table,row_key(table,row)?,owner(table,row),serde_json::to_string(row)?])?;
            let kind = match table {
                "pages" => Some("page"),
                "stacks" => Some("stack"),
                "tags" => Some("tag"),
                _ => None,
            };
            if let Some(kind) = kind {
                entity(tx, kind, row["id"].as_str().expect("validated entity ID"))?;
            }
        }
    }
    for (id, evidence) in manifest.json()["decision_evidence"]
        .as_object()
        .expect("validated evidence")
    {
        tx.execute("INSERT INTO objects VALUES('decision_evidence',?1,?1,?2,1) ON CONFLICT(kind,key) DO UPDATE SET payload=excluded.payload,active=1",
            params![id,serde_json::to_string(evidence)?])?;
    }
    rebuild_index(tx, manifest)?;
    crate::text::reconcile(tx, crate::TEXT_CONFIG)?;
    Ok(())
}

pub(crate) fn entity(db: &Connection, kind: &str, id: &str) -> Result<String> {
    db.execute(
        "INSERT INTO entities VALUES(?1,?2,?3,1) ON CONFLICT(kind,local_id) DO UPDATE SET active=1",
        params![kind, id, Uuid::new_v4().to_string()],
    )?;
    sync_id(db, kind, id)
}
pub(crate) fn sync_id(db: &Connection, kind: &str, id: &str) -> Result<String> {
    Ok(db.query_row(
        "SELECT sync_id FROM entities WHERE kind=?1 AND local_id=?2",
        params![kind, id],
        |r| r.get(0),
    )?)
}
pub(crate) fn bindings(db: &Connection) -> Result<Value> {
    let mut query = db.prepare(
        "SELECT kind,local_id,sync_id FROM entities WHERE active=1 ORDER BY kind,local_id",
    )?;
    let rows = query.query_map([], |r| {
        Ok((
            r.get::<_, String>(0)?,
            r.get::<_, String>(1)?,
            r.get::<_, String>(2)?,
        ))
    })?;
    let mut result = Vec::new();
    for row in rows {
        let (kind, id, sync) = row?;
        result.push(json!({"kind":kind,"local_id":id,"sync_id":sync}));
    }
    Ok(json!(result))
}
pub(crate) fn journal(tx: &Transaction<'_>, kind: &str, change: Value) -> Result<()> {
    crate::sync::capture(tx, kind, &change)?;
    let replica = meta(tx, "replica_id")?;
    let library = meta(tx, "library_id")?;
    let sequence: i64 = meta(tx, "next_sequence")?
        .parse()
        .map_err(|_| Error::Invalid("Invalid replica sequence"))?;
    let revision: i64 = meta(tx, "revision")?
        .parse()
        .map_err(|_| Error::Invalid("Invalid local revision"))?;
    if sequence <= 0 || sequence >= i64::MAX - 1 || revision >= i64::MAX - 1 {
        return Err(Error::Invalid("Local revision counter exhausted"));
    }
    let payload = json!({"format":"org.stackdocs.local-change","version":1,"library_id":library,
        "replica_id":replica,"sequence":sequence,"context":{replica.clone():sequence-1},"kind":kind,"change":change});
    let bytes = serde_json::to_vec(&payload)?;
    tx.execute(
        "INSERT INTO journal VALUES(?1,?2,?3,?4)",
        params![
            replica,
            sequence,
            std::str::from_utf8(&bytes).expect("JSON UTF-8"),
            stack_archive::digest(&bytes)
        ],
    )?;
    put_meta(tx, "next_sequence", &(sequence + 1).to_string())?;
    put_meta(tx, "revision", &(revision + 1).to_string())?;
    Ok(())
}

pub(crate) fn rebuild_index(tx: &Transaction<'_>, manifest: &Manifest) -> Result<()> {
    tx.execute("DELETE FROM document_search", [])?;
    tx.execute("DELETE FROM page_search", [])?;
    for doc in manifest.rows("documents") {
        let id = doc["id"].as_str().expect("doc ID");
        let mut body = doc["notes"].as_str().expect("notes").to_owned();
        for detail in manifest
            .rows("details")
            .iter()
            .filter(|d| d["document_id"] == id && d["state"] == "saved")
        {
            body.push('\n');
            body.push_str(detail["label"].as_str().expect("label"));
            body.push(' ');
            body.push_str(detail["value"].as_str().expect("value"));
        }
        for page in manifest.rows("pages").iter().filter(|p| {
            p["document_id"] == id
                && p["text_revision"] == p["content_revision"]
                && p["text_config"] == p["ocr_config"]
        }) {
            let text = page["ocr_text"].as_str().expect("OCR text");
            body.push('\n');
            body.push_str(text);
            tx.execute(
                "INSERT INTO page_search VALUES(?1,?2,?3,?4)",
                params![page["id"].as_str(), id, text, page["position"].as_i64()],
            )?;
        }
        tx.execute(
            "INSERT INTO document_search VALUES(?1,?2,?3)",
            params![id, doc["title"].as_str(), body],
        )?;
    }
    Ok(())
}

pub(crate) fn starter_stacks() -> Value {
    let ids = [
        "bills", "banking", "ids", "house", "medical", "receipts", "starred",
    ];
    let names = [
        "Bills", "Banking", "IDs", "House", "Medical", "Receipts", "Starred",
    ];
    let colors = [
        0xffdceadb_u32,
        0xffe5e8fa,
        0xfff7e6d2,
        0xfff1dde8,
        0xffdcecf2,
        0xfff7edca,
        0xffe2e5e0,
    ];
    json!((0..7).map(|i|json!({"id":ids[i],"name":names[i],"color":colors[i] as i32,"visible":1,"sort_order":i})).collect::<Vec<_>>())
}
