//! Explicit reset requests are applied only during a cold library open, under
//! the unchanged writer.lock inode. No active worker ever loses its database.
use crate::library::{Error, Library, Result, meta, put_meta};
use serde::{Deserialize, Serialize};
use std::os::unix::fs::PermissionsExt;
use std::{
    fs::{self, File},
    io::Write,
    path::{Path, PathBuf},
    sync::Arc,
};
use uuid::Uuid;

const REQUEST: &str = "reset-request.json";
const QUARANTINE: &str = "reset-quarantine";
const OWNED: &[&str] = &[
    "library.sqlite3",
    "library.sqlite3-wal",
    "library.sqlite3-shm",
    "library.sqlite3-journal",
    "originals",
    "operations",
    "sync_blobs",
    "pairing/policy.json",
    "pairing/peer.der",
    "pairing/names.json",
];
#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    Erase,
    Connection,
}
#[derive(Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
struct Intent {
    version: u32,
    mode: Mode,
    phase: String,
    source_library: String,
    new_library: String,
    new_replica: String,
    present: Vec<usize>,
}
fn safe_tree(path: &Path) -> Result<()> {
    for parent in path.ancestors().skip(1) {
        if parent
            .symlink_metadata()
            .is_ok_and(|m| m.file_type().is_symlink())
        {
            return Err(Error::Invalid("Reset refuses linked ancestor directories"));
        }
    }
    let meta = match path.symlink_metadata() {
        Ok(m) => m,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(e.into()),
    };
    if meta.file_type().is_symlink() || (!meta.is_dir() && !meta.is_file()) {
        return Err(Error::Invalid(
            "Reset refuses symbolic links or special files",
        ));
    }
    if meta.is_dir() {
        for child in fs::read_dir(path)? {
            safe_tree(&child?.path())?;
        }
    }
    Ok(())
}
fn write(root: &Path, intent: &Intent) -> Result<()> {
    let mut file = tempfile::Builder::new()
        .prefix("reset-intent-")
        .tempfile_in(root)?;
    file.write_all(&serde_json::to_vec(intent)?)?;
    file.as_file().sync_all()?;
    file.persist(root.join(REQUEST)).map_err(|e| e.error)?;
    stack_archive::sync_directory(root)?;
    Ok(())
}

fn read(root: &Path) -> Result<Option<Intent>> {
    let path = root.join(REQUEST);
    if !path.exists() {
        return Ok(None);
    }
    safe_tree(&path)?;
    let file = stack_archive::read_regular(&path)?;
    if file.metadata()?.len() > 4096 {
        return Err(Error::Invalid("Reset request is invalid"));
    }
    let intent: Intent = serde_json::from_reader(file)?;
    if intent.version != 1
        || ![
            "requested",
            "moving",
            "preparing",
            "committed",
            "rolling_back",
        ]
        .contains(&intent.phase.as_str())
    {
        return Err(Error::Invalid("Reset request version or phase is invalid"));
    }
    if intent.present.iter().any(|i| *i >= OWNED.len())
        || intent
            .present
            .iter()
            .copied()
            .collect::<std::collections::BTreeSet<_>>()
            .len()
            != intent.present.len()
    {
        return Err(Error::Invalid("Reset original-path inventory is invalid"));
    }
    stack_archive::uuid(&intent.source_library)?;
    stack_archive::uuid(&intent.new_library)?;
    stack_archive::uuid(&intent.new_replica)?;
    Ok(Some(intent))
}
fn remove(path: &Path) -> Result<()> {
    safe_tree(path)?;
    if let Ok(meta) = path.symlink_metadata() {
        if meta.is_dir() {
            fs::remove_dir_all(path)?;
        } else {
            fs::remove_file(path)?;
        }
    }
    Ok(())
}
fn completion(root: &Path, mode: Mode) -> Result<()> {
    let mut file = tempfile::Builder::new()
        .prefix("reset-notice-")
        .tempfile_in(root)?;
    file.write_all(&serde_json::to_vec(
        &serde_json::json!({"version":1,"mode":mode}),
    )?)?;
    file.as_file().sync_all()?;
    file.persist(root.join("reset-completed.json"))
        .map_err(|e| e.error)?;
    stack_archive::sync_directory(root)?;
    Ok(())
}
impl Library {
    pub fn request_reset(
        &self,
        mode: Mode,
        expected_library: &str,
        expected_revision: i64,
    ) -> Result<()> {
        let status = self.status()?;
        if status.library_id != expected_library || status.revision != expected_revision {
            return Err(Error::Stale);
        }
        if self.root.join(REQUEST).exists() || self.root.join(QUARANTINE).exists() {
            return Err(Error::Invalid(
                "A reset is already pending; close and reopen Stack",
            ));
        }
        for item in OWNED {
            safe_tree(&self.root.join(item))?;
        }
        write(
            &self.root,
            &Intent {
                version: 1,
                mode,
                phase: "requested".into(),
                source_library: status.library_id,
                new_library: Uuid::new_v4().to_string(),
                new_replica: Uuid::new_v4().to_string(),
                present: OWNED
                    .iter()
                    .enumerate()
                    .filter_map(|(i, p)| self.root.join(p).exists().then_some(i))
                    .collect(),
            },
        )
    }
    pub fn take_reset_completion(&self) -> Result<Option<Mode>> {
        let path = self.root.join("reset-completed.json");
        if !path.exists() {
            return Ok(None);
        }
        let file = stack_archive::read_regular(&path)?;
        if file.metadata()?.len() > 4096 {
            return Err(Error::Invalid("Reset completion record is invalid"));
        }
        let value: serde_json::Value = serde_json::from_reader(file)?;
        if value["version"] != 1 {
            return Err(Error::Invalid("Reset completion version is invalid"));
        }
        let mode: Mode = serde_json::from_value(value["mode"].clone())?;
        fs::remove_file(path)?;
        stack_archive::sync_directory(&self.root)?;
        Ok(Some(mode))
    }
    pub fn reset_pending(&self) -> bool {
        self.root.join(REQUEST).exists()
    }
}

pub(crate) struct ColdReset {
    root: PathBuf,
    intent: Intent,
    committed: bool,
    _lock: Arc<File>,
}
impl ColdReset {
    pub(crate) fn begin(root: &Path, lock: Arc<File>) -> Result<Option<Self>> {
        let Some(intent) = read(root)? else {
            return Ok(None);
        };
        if intent.mode == Mode::Connection {
            return Ok(None);
        }
        if intent.phase == "requested" {
            let database = root.join("library.sqlite3");
            safe_tree(&database)?;
            let db = rusqlite::Connection::open_with_flags(
                database,
                rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY
                    | rusqlite::OpenFlags::SQLITE_OPEN_NOFOLLOW,
            )?;
            if meta(&db, "library_id")? != intent.source_library {
                return Err(Error::Stale);
            }
        }
        let mut reset = Self {
            root: root.into(),
            intent,
            committed: false,
            _lock: lock,
        };
        if reset.intent.phase == "rolling_back" {
            reset.rollback()?;
            reset.committed = true;
            return Ok(None);
        }
        if reset.intent.phase == "committed" {
            reset.committed = true;
            reset.cleanup()?;
            return Ok(None);
        }
        let quarantine = root.join(QUARANTINE);
        if !quarantine.exists() {
            fs::create_dir(&quarantine)?;
            fs::set_permissions(&quarantine, fs::Permissions::from_mode(0o700))?;
            stack_archive::sync_directory(root)?;
        }
        safe_tree(&quarantine)?;
        if reset.intent.phase == "requested" || reset.intent.phase == "moving" {
            for item in OWNED {
                safe_tree(&root.join(item))?;
            }
            if reset.intent.phase == "requested" {
                reset.intent.present = OWNED
                    .iter()
                    .enumerate()
                    .filter_map(|(i, p)| root.join(p).exists().then_some(i))
                    .collect();
            }
            reset.intent.phase = "moving".into();
            write(root, &reset.intent)?;
            for (i, item) in OWNED.iter().enumerate() {
                let source = root.join(item);
                let target = quarantine.join(i.to_string());
                if source.exists() {
                    if target.exists() {
                        return Err(Error::Invalid(
                            "Reset recovery found conflicting owned files",
                        ));
                    }
                    fs::rename(&source, target)?;
                    stack_archive::sync_directory(source.parent().expect("owned parent"))?;
                    stack_archive::sync_directory(&quarantine)?;
                }
            }
            reset.intent.phase = "preparing".into();
            write(root, &reset.intent)?;
        } else {
            // A process stopped during fresh initialization. Preserve the old
            // library in quarantine and retry only the owned replacement paths.
            for item in OWNED {
                remove(&root.join(item))?;
            }
        }
        Ok(Some(reset))
    }
    pub(crate) fn finish(&mut self) -> Result<()> {
        self.intent.phase = "committed".into();
        // Publication can rename successfully but fail its directory fsync.
        // Preserve quarantine on either outcome; never roll back under a
        // possibly committed marker. Startup reconciles the durable phase.
        self.committed = true;
        write(&self.root, &self.intent)?;
        self.cleanup()
    }
    fn cleanup(&self) -> Result<()> {
        completion(&self.root, Mode::Erase)?;
        remove(&self.root.join(QUARANTINE))?;
        fs::remove_file(self.root.join(REQUEST))?;
        stack_archive::sync_directory(&self.root)?;
        Ok(())
    }
}
impl ColdReset {
    fn rollback(&mut self) -> Result<()> {
        let quarantine = self.root.join(QUARANTINE);
        if self.intent.phase != "rolling_back" {
            // Publish rollback before restoring even one original path. If this
            // publication is indeterminate, leave both generations untouched.
            self.intent.phase = "rolling_back".into();
            write(&self.root, &self.intent)?;
        }
        for (i, item) in OWNED.iter().enumerate() {
            if !self.intent.present.contains(&i) {
                remove(&self.root.join(item))?;
            }
            let saved = quarantine.join(i.to_string());
            if saved.exists() {
                let target = self.root.join(item);
                remove(&target)?;
                if let Some(parent) = target.parent() {
                    fs::create_dir_all(parent)?;
                }
                fs::rename(saved, &target)?;
                stack_archive::sync_directory(target.parent().expect("owned parent"))?;
                if quarantine.exists() {
                    stack_archive::sync_directory(&quarantine)?;
                }
            }
        }
        remove(&quarantine)?;
        if self.root.join(REQUEST).exists() {
            fs::remove_file(self.root.join(REQUEST))?;
        }
        stack_archive::sync_directory(&self.root)?;
        Ok(())
    }
}
impl Drop for ColdReset {
    fn drop(&mut self) {
        if !self.committed && self.rollback().is_err() {
            eprintln!(
                "Reset rollback needs recovery on next startup; original data remains in reset-quarantine"
            );
        }
    }
}
pub(crate) fn connection(library: &mut Library) -> Result<()> {
    let Some(intent) = read(&library.root)? else {
        return Ok(());
    };
    if intent.mode != Mode::Connection {
        return Ok(());
    }
    let current = meta(&library.db, "library_id")?;
    if current != intent.source_library && current != intent.new_library {
        return Err(Error::Stale);
    }
    let tx = library.db.transaction()?;
    tx.execute("DELETE FROM sync_state", [])?;
    tx.execute("DELETE FROM sync_receipts", [])?;
    put_meta(&tx, "library_id", &intent.new_library)?;
    put_meta(&tx, "replica_id", &intent.new_replica)?;
    tx.commit()?;
    for item in [
        "pairing/policy.json",
        "pairing/peer.der",
        "pairing/names.json",
    ] {
        remove(&library.root.join(item))?;
    }
    if library.root.join("pairing").exists() {
        stack_archive::sync_directory(&library.root.join("pairing"))?;
    }
    completion(&library.root, Mode::Connection)?;
    fs::remove_file(library.root.join(REQUEST))?;
    stack_archive::sync_directory(&library.root)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use stack_archive::{Control, SourceVerifier};
    struct Authored;
    impl SourceVerifier for Authored {
        fn verify(&self, path: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
            assert_eq!(fs::read(path)?, b"authored reset original");
            Ok(())
        }
    }
    fn populated(dir: &Path) -> Library {
        let source = dir.join("authored.pdf");
        fs::write(&source, b"authored reset original").unwrap();
        let mut library = Library::open(&dir.join("library")).unwrap();
        library
            .import_original(
                &source,
                "Saved reset fixture 🦀",
                &Authored,
                "application/pdf",
                1,
                &Control::default(),
            )
            .unwrap();
        library
    }
    fn schedule(library: &Library, mode: Mode) {
        let s = library.status().unwrap();
        library
            .request_reset(mode, &s.library_id, s.revision)
            .unwrap();
    }
    fn lock(root: &Path) -> Arc<File> {
        let file = File::open(root.join("writer.lock")).unwrap();
        file.try_lock().unwrap();
        Arc::new(file)
    }
    #[test]
    fn erase_waits_for_cold_open_preserves_lock_keys_backups_and_unknown_files() {
        let dir = tempfile::tempdir().unwrap();
        let library = populated(dir.path());
        let root = library.root().to_owned();
        let before = library.status().unwrap();
        fs::create_dir(root.join("pairing")).unwrap();
        fs::write(
            root.join("pairing/identity-key.der"),
            b"unchanged authored identity",
        )
        .unwrap();
        fs::write(root.join("pairing/policy.json"), b"authored policy").unwrap();
        fs::write(
            root.join("keep.stackbackup"),
            b"independent backup sentinel",
        )
        .unwrap();
        fs::write(root.join("unrelated"), b"keep").unwrap();
        schedule(&library, Mode::Erase);
        assert_eq!(library.status().unwrap().documents, 1);
        assert!(matches!(Library::open(&root), Err(Error::Busy)));
        drop(library);
        let library = Library::open(&root).unwrap();
        let after = library.status().unwrap();
        assert_eq!(after.documents, 0);
        assert_ne!(before.library_id, after.library_id);
        assert_ne!(before.replica_id, after.replica_id);
        assert!(!library.reset_pending());
        assert!(root.join("writer.lock").is_file());
        assert!(!root.join("pairing/policy.json").exists());
        assert_eq!(
            fs::read(root.join("pairing/identity-key.der")).unwrap(),
            b"unchanged authored identity"
        );
        assert_eq!(
            fs::read(root.join("keep.stackbackup")).unwrap(),
            b"independent backup sentinel"
        );
        assert_eq!(fs::read(root.join("unrelated")).unwrap(), b"keep");
        assert!(!root.join(QUARANTINE).exists());
    }
    #[test]
    fn connection_reset_keeps_complete_graph_and_originals_but_changes_membership_identity() {
        let dir = tempfile::tempdir().unwrap();
        let mut library = populated(dir.path());
        let root = library.root().to_owned();
        library.sync_start(&[Uuid::new_v4().to_string()]).unwrap();
        let before = library.status().unwrap();
        let mut graph = library.manifest().unwrap().into_json();
        graph["created_at"] = serde_json::json!(0);
        schedule(&library, Mode::Connection);
        drop(library);
        let library = Library::open(&root).unwrap();
        let after = library.status().unwrap();
        let mut actual = library.manifest().unwrap().into_json();
        actual["created_at"] = serde_json::json!(0);
        assert_eq!(graph, actual);
        assert_ne!(before.replica_id, after.replica_id);
        assert_ne!(before.library_id, after.library_id);
        assert_eq!(library.sync_status().unwrap()["adopted"], false);
    }
    #[test]
    fn reset_recovery_resumes_moving_preparing_and_interrupted_rollback_without_losing_originals() {
        for phase in [
            "moving",
            "preparing",
            "rolling_back",
            "failed_initialization",
        ] {
            let dir = tempfile::tempdir().unwrap();
            let library = populated(dir.path());
            let root = library.root().to_owned();
            let before = library.status().unwrap().library_id;
            schedule(&library, Mode::Erase);
            drop(library);
            // Appears after the live request: the cold-start inventory must own it.
            fs::create_dir(root.join("sync_blobs")).unwrap();
            fs::write(root.join("sync_blobs/late"), b"late authored bytes").unwrap();
            if phase == "moving" {
                let mut intent = read(&root).unwrap().unwrap();
                intent.present = OWNED
                    .iter()
                    .enumerate()
                    .filter_map(|(i, p)| root.join(p).exists().then_some(i))
                    .collect();
                intent.phase = "moving".into();
                write(&root, &intent).unwrap();
                fs::create_dir(root.join(QUARANTINE)).unwrap();
                fs::rename(
                    root.join("library.sqlite3"),
                    root.join(QUARANTINE).join("0"),
                )
                .unwrap();
            } else {
                let mut guard = ColdReset::begin(&root, lock(&root)).unwrap().unwrap();
                if phase == "rolling_back" {
                    assert!(guard.intent.present.contains(&6));
                    guard.intent.phase = "rolling_back".into();
                    write(&root, &guard.intent).unwrap();
                    fs::rename(
                        root.join(QUARANTINE).join("0"),
                        root.join("library.sqlite3"),
                    )
                    .unwrap();
                    fs::rename(root.join(QUARANTINE).join("6"), root.join("sync_blobs")).unwrap();
                    guard.committed = true;
                } else if phase == "failed_initialization" {
                    fs::write(root.join("library.sqlite3"), b"failed replacement").unwrap();
                } else {
                    guard.committed = true;
                } // Simulated abrupt process death.
                drop(guard);
            }
            let library = Library::open(&root).unwrap();
            let status = library.status().unwrap();
            if ["rolling_back", "failed_initialization"].contains(&phase) {
                assert_eq!(status.documents, 1, "{phase}");
                assert_eq!(status.library_id, before);
                assert_eq!(
                    fs::read(root.join("sync_blobs/late")).unwrap(),
                    b"late authored bytes"
                );
            } else {
                assert_eq!(status.documents, 0, "{phase}");
                assert_ne!(status.library_id, before);
            }
            assert!(!root.join(QUARANTINE).exists());
            assert!(!library.reset_pending());
        }
    }
    #[test]
    fn reset_refuses_stale_review_and_linked_ancestors_without_touching_external_files() {
        let dir = tempfile::tempdir().unwrap();
        let library = populated(dir.path());
        let s = library.status().unwrap();
        assert!(matches!(
            library.request_reset(Mode::Erase, &s.library_id, s.revision - 1),
            Err(Error::Stale)
        ));
        let outside = dir.path().join("outside");
        fs::create_dir(&outside).unwrap();
        fs::write(outside.join("policy.json"), b"external sentinel").unwrap();
        std::os::unix::fs::symlink(&outside, library.root().join("pairing")).unwrap();
        assert!(
            library
                .request_reset(Mode::Erase, &s.library_id, s.revision)
                .is_err()
        );
        assert_eq!(
            fs::read(outside.join("policy.json")).unwrap(),
            b"external sentinel"
        );
        assert_eq!(library.status().unwrap().documents, 1);
        assert!(!library.reset_pending());
    }
}
