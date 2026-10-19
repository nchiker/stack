use crate::library::{
    self, Error, Library, Result, bindings, journal, meta, private_dir, put_meta, sync_id,
    write_graph,
};
use rusqlite::{OptionalExtension, params};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use stack_archive::{Control, Manifest, SourceVerifier, StagedArchive, validation};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::os::unix::ffi::{OsStrExt, OsStringExt};
use std::os::unix::fs::{MetadataExt, OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use uuid::Uuid;

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct IdMappings {
    pub documents: BTreeMap<String, String>,
    pub pages: BTreeMap<String, String>,
    pub stacks: BTreeMap<String, String>,
    pub tags: BTreeMap<String, String>,
    pub scans: BTreeMap<String, String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct RestoreReceipt {
    pub operation: String,
    pub documents_added: usize,
    pub document_collisions: usize,
    pub merged_stacks: usize,
    pub source_version: u8,
    pub source_hash: String,
    pub mappings: IdMappings,
}

pub struct StagedImport {
    operation: String,
    directory: PathBuf,
    library_root: PathBuf,
    archive: StagedArchive,
    lease: Arc<StageLease>,
}
impl StagedImport {
    pub fn manifest(&self) -> &Manifest {
        &self.archive.manifest
    }
}
/// Owned staging work contains no database connection and is safe to move to one
/// bounded transfer worker. Original publication remains a repository transaction.
pub struct ImportWork {
    operation: String,
    directory: PathBuf,
    library_root: PathBuf,
    lease: Arc<StageLease>,
}
impl ImportWork {
    pub fn archive(
        self,
        input: &mut impl Read,
        verifier: &dyn SourceVerifier,
        control: &Control,
    ) -> Result<StagedImport> {
        let archive =
            stack_archive::read_into(input, &self.directory.join("input"), verifier, control)?;
        Ok(StagedImport {
            operation: self.operation.clone(),
            directory: self.directory.clone(),
            library_root: self.library_root.clone(),
            archive,
            lease: self.lease.clone(),
        })
    }
    pub fn original(
        self,
        source: &Path,
        title: &str,
        verifier: &dyn SourceVerifier,
        mime: &str,
        pages: u32,
        control: &Control,
    ) -> Result<StagedImport> {
        if !(1..=1000).contains(&pages)
            || !matches!(
                mime,
                "application/pdf" | "image/jpeg" | "image/png" | "image/webp"
            )
            || (mime != "application/pdf" && pages != 1)
        {
            return Err(Error::Invalid("Unsupported original type or page count"));
        }
        let title = validation::clean_name(title)?;
        let operation = self.operation.clone();
        let directory = self.directory.clone();
        let mut guard = CleanupDirectory(directory.clone(), true);
        let id = Uuid::new_v4().to_string();
        let source_dir = directory.join("input");
        private_dir(&source_dir)?;
        private_dir(&source_dir.join("originals"))?;
        let document_dir = source_dir.join("originals").join(&id);
        private_dir(&document_dir)?;
        let path = format!("originals/{id}/source");
        let target = source_dir.join(&path);
        let mut input = stack_archive::read_regular(source)?;
        let length = input.metadata()?.len();
        if length == 0 || length > stack_archive::FILE_LIMIT {
            return Err(Error::Invalid("Original must be 1 byte–256 MiB"));
        }
        stack_archive::reserve_space(&document_dir, length)?;
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&target)?;
        let hash = stack_archive::transfer(&mut input, &mut output, length, control)?;
        output.sync_all()?;
        let mut end = [0];
        if input.read(&mut end)? != 0 {
            return Err(Error::Invalid("Import source changed while reading"));
        }
        fs::set_permissions(&target, fs::Permissions::from_mode(0o400))?;
        verifier.verify(
            &target,
            mime,
            if mime == "application/pdf" {
                Some(pages)
            } else {
                None
            },
        )?;
        let graph = single_document(&id, &title, mime, pages, &path, length, &hash)?;
        let bytes = graph.bytes()?;
        let archive = StagedArchive {
            manifest: graph,
            source_manifest: bytes.clone(),
            source_hash: stack_archive::digest(&bytes),
            directory: source_dir,
        };
        guard.1 = false;
        let lease = self.lease.clone();
        let staged = StagedImport {
            operation,
            directory,
            library_root: self.library_root.clone(),
            archive,
            lease,
        };
        Ok(staged)
    }
}

struct StageLease {
    directory: PathBuf,
    _lock: Arc<std::fs::File>,
}
impl Drop for StageLease {
    fn drop(&mut self) {
        if !self.directory.join("journal.json").exists() {
            let _ = fs::remove_dir_all(&self.directory);
        }
    }
}

pub struct RestorePlan {
    receipt: RestoreReceipt,
    directory: PathBuf,
    library_root: PathBuf,
    base_revision: i64,
    graph: Manifest,
    source: Manifest,
    source_manifest: Vec<u8>,
    pristine: bool,
    _lease: Arc<StageLease>,
}
impl RestorePlan {
    pub fn review(&self) -> &RestoreReceipt {
        &self.receipt
    }
}

/// Metadata is captured on the repository; immutable originals remain retained
/// while the transfer worker pins/verifies them without keeping a SQLite borrow.
pub struct SnapshotWork {
    manifest: Manifest,
    directory: PathBuf,
    root: PathBuf,
    lease: Arc<StageLease>,
}
impl SnapshotWork {
    pub fn pin(self, control: &Control) -> Result<FrozenSnapshot> {
        let directory = self.directory.clone();
        private_dir(&directory.join("originals"))?;
        for entry in self.manifest.rows("files") {
            control.check()?;
            let path = entry["path"].as_str().expect("path");
            let (doc, _) = stack_archive::original_path(path)?;
            stack_archive::read_directory(&self.root.join("originals").join(doc))?;
            let source = self.root.join(path);
            let target = directory.join(path);
            stack_archive::read_regular(&source)?;
            private_dir(target.parent().expect("original parent"))?;
            if fs::hard_link(&source, &target).is_err() {
                stack_archive::reserve_space(
                    target.parent().expect("parent"),
                    entry["length"].as_u64().expect("length"),
                )?;
                let mut input = stack_archive::read_regular(&source)?;
                let mut output = OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .mode(0o600)
                    .open(&target)?;
                stack_archive::transfer(
                    &mut input,
                    &mut output,
                    entry["length"].as_u64().expect("length"),
                    control,
                )?;
                output.sync_all()?;
                fs::set_permissions(&target, fs::Permissions::from_mode(0o400))?;
            }
            if stack_archive::file_hash(
                &target,
                entry["length"].as_u64().expect("length"),
                control,
            )? != entry["sha256"].as_str().expect("hash")
            {
                return Err(Error::Invalid("A saved original is missing or changed"));
            }
        }
        Ok(FrozenSnapshot {
            manifest: self.manifest,
            directory,
            _lease: self.lease.clone(),
        })
    }
}

/// Crash-recoverable, no-overwrite publication shared by archive and PDF output.
pub struct OutputWork {
    operation: String,
    directory: PathBuf,
    root: PathBuf,
    _lock: Arc<std::fs::File>,
}
impl OutputWork {
    pub fn publish(
        self,
        destination: &Path,
        control: &Control,
        write: impl FnOnce(&mut std::fs::File, &Control) -> Result<()>,
    ) -> Result<()> {
        let mut probe: library::Probe = Box::new(|_| Ok(()));
        self.publish_with_probe(destination, control, &mut probe, write)
    }
    /// Validate the flushed private output before its name becomes public.
    /// The callback runs on the transfer owner and cannot replace the owned part.
    pub fn publish_checked(
        self,
        destination: &Path,
        control: &Control,
        write: impl FnOnce(&mut std::fs::File, &Control) -> Result<()>,
        validate: impl FnOnce(&Path, &Control) -> Result<()>,
    ) -> Result<()> {
        let mut probe: library::Probe = Box::new(|_| Ok(()));
        self.publish_inner(destination, control, &mut probe, write, validate)
    }
    fn publish_with_probe(
        self,
        destination: &Path,
        control: &Control,
        probe: &mut library::Probe,
        write: impl FnOnce(&mut std::fs::File, &Control) -> Result<()>,
    ) -> Result<()> {
        self.publish_inner(destination, control, probe, write, |_, _| Ok(()))
    }
    fn publish_inner(
        self,
        destination: &Path,
        control: &Control,
        probe: &mut library::Probe,
        write: impl FnOnce(&mut std::fs::File, &Control) -> Result<()>,
        validate: impl FnOnce(&Path, &Control) -> Result<()>,
    ) -> Result<()> {
        control.check()?;
        let parent = destination
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."))
            .canonicalize()?;
        let name = destination
            .file_name()
            .ok_or(Error::Invalid("Choose an output filename"))?;
        let destination = parent.join(name);
        // A root inside library storage could otherwise make recovery export/delete its own data.
        if destination.starts_with(&self.root) {
            return Err(Error::Invalid(
                "Choose an export destination outside library storage",
            ));
        }
        let operation = &self.operation;
        let dir = &self.directory;
        let part = parent.join(format!(".stack-export-{operation}.part"));
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&part)?;
        let metadata = output.metadata()?;
        let target = ExportTarget {
            path: part.as_os_str().as_bytes().to_vec(),
            device: metadata.dev(),
            inode: metadata.ino(),
        };
        let _part_guard = OwnedPart {
            path: part.clone(),
            device: target.device,
            inode: target.inode,
        };
        write_new_json(&dir.join("target.json"), &serde_json::to_value(&target)?)?;
        stack_archive::sync_directory(dir)?;
        let mut published = false;
        let result = (|| -> Result<()> {
            (probe)("export_target")?;
            write(&mut output, control)?;
            output.flush()?;
            output.sync_all()?;
            (probe)("export_written")?;
            control.check()?;
            validate(&part, control)?;
            control.check()?;
            rustix::fs::renameat_with(
                rustix::fs::CWD,
                &part,
                rustix::fs::CWD,
                &destination,
                rustix::fs::RenameFlags::NOREPLACE,
            )
            .map_err(std::io::Error::from)?;
            published = true;
            stack_archive::sync_directory(&parent)?;
            (probe)("export_published")?;
            Ok(())
        })();
        drop(output);
        let cleanup = cleanup_export(dir);
        if published && (result.is_err() || cleanup.is_err()) {
            return Err(Error::PublishedExport(destination));
        }
        cleanup?;
        result
    }
}
impl Drop for OutputWork {
    fn drop(&mut self) {
        let _ = cleanup_export(&self.directory);
    }
}

pub struct FrozenSnapshot {
    pub manifest: Manifest,
    directory: PathBuf,
    _lease: Arc<StageLease>,
}
impl FrozenSnapshot {
    pub fn original(&self, document: &str, filename: &str) -> Result<PathBuf> {
        let path = format!("originals/{document}/{filename}");
        stack_archive::original_path(&path)?;
        if !self
            .manifest
            .rows("files")
            .iter()
            .any(|f| f["path"] == path)
        {
            return Err(Error::Invalid("Original is outside this snapshot"));
        }
        stack_archive::read_directory(&self.directory.join("originals").join(document))?;
        let file = self.directory.join(path);
        stack_archive::read_regular(&file)?;
        Ok(file)
    }
    pub fn write(&self, output: &mut impl Write, control: &Control) -> Result<()> {
        Ok(stack_archive::write(
            &self.manifest,
            &self.directory,
            output,
            control,
        )?)
    }
}
impl Drop for FrozenSnapshot {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.directory);
    }
}

impl Library {
    /// Allocate operation ownership on the serialized repository, then perform
    /// buffered staging/validation on a bounded transfer worker.
    pub fn prepare_import(&self) -> Result<ImportWork> {
        let (operation, directory) = self.operation("import")?;
        Ok(ImportWork {
            operation,
            directory: directory.clone(),
            library_root: self.root.clone(),
            lease: Arc::new(StageLease {
                directory,
                _lock: self._lock.clone(),
            }),
        })
    }
    pub fn stage_archive(
        &mut self,
        input: &mut impl Read,
        verifier: &dyn SourceVerifier,
        control: &Control,
    ) -> Result<StagedImport> {
        let staged = self.prepare_import()?.archive(input, verifier, control)?;
        (self.probe)("staged")?;
        Ok(staged)
    }
    pub fn review_original(&self, staged: &StagedImport) -> Result<RestorePlan> {
        self.plan(staged, false)
    }

    pub fn review_archive(&self, staged: &StagedImport) -> Result<RestorePlan> {
        self.plan(staged, true)
    }

    fn plan(&self, staged: &StagedImport, replace_pristine: bool) -> Result<RestorePlan> {
        if staged.library_root != self.root {
            return Err(Error::Invalid(
                "Archive staging belongs to another desktop library",
            ));
        }
        let existing = self.manifest()?;
        let pristine = replace_pristine
            && existing.rows("documents").is_empty()
            && meta(&self.db, "catalog_pristine")? == "1";
        let source = &staged.archive.manifest;
        let mut maps = IdMappings::default();
        for (kind, table, map) in [
            ("document", "documents", &mut maps.documents),
            ("page", "pages", &mut maps.pages),
        ] {
            let mut used = self.reserved(kind)?;
            if kind == "document" {
                for entry in fs::read_dir(self.root.join("originals"))? {
                    let entry = entry?;
                    if let Some(name) = entry.file_name().to_str() {
                        used.insert(name.to_owned());
                    }
                }
            }
            for row in source.rows(table) {
                let old = row["id"].as_str().expect("validated ID");
                map.insert(
                    old.to_owned(),
                    mapped(&staged.operation, kind, old, &mut used)?,
                );
            }
        }
        let mut scans = self.reserved("scan")?;
        for row in source.rows("documents") {
            if let Some(scan) = row["source_scan_id"].as_str() {
                maps.scans.insert(
                    scan.to_owned(),
                    mapped(&staged.operation, "scan", scan, &mut scans)?,
                );
            }
        }
        let mut stacks = if pristine {
            Vec::new()
        } else {
            existing.rows("stacks").to_vec()
        };
        let mut stack_ids = if pristine {
            BTreeSet::new()
        } else {
            self.reserved("stack")?
        };
        let mut merged_stacks = 0;
        for row in source.rows("stacks") {
            let old = row["id"].as_str().expect("stack ID");
            let name = row["name"].as_str().expect("stack name");
            if let Some(other) = stacks.iter().find(|s| {
                validation::java_case_key(s["name"].as_str().expect("name"))
                    == validation::java_case_key(name)
            }) {
                maps.stacks.insert(
                    old.to_owned(),
                    other["id"].as_str().expect("stack ID").to_owned(),
                );
                merged_stacks += 1;
            } else {
                let id = mapped(&staged.operation, "stack", old, &mut stack_ids)?;
                let mut new = row.clone();
                new["id"] = json!(id);
                new["sort_order"] = json!(stacks.len());
                stacks.push(new);
                maps.stacks.insert(old.to_owned(), id);
            }
        }
        let mut tags = existing.rows("tags").to_vec();
        let mut tag_ids = self.reserved("tag")?;
        for row in source.rows("tags") {
            let old = row["id"].as_str().expect("tag ID");
            let name = row["name"].as_str().expect("tag name");
            if let Some(other) = tags.iter().find(|t| {
                validation::java_case_key(t["name"].as_str().expect("name"))
                    == validation::java_case_key(name)
            }) {
                maps.tags.insert(
                    old.to_owned(),
                    other["id"].as_str().expect("tag ID").to_owned(),
                );
            } else {
                let id = mapped(&staged.operation, "tag", old, &mut tag_ids)?;
                let mut new = row.clone();
                new["id"] = json!(id);
                tags.push(new);
                maps.tags.insert(old.to_owned(), id);
            }
        }
        let mut incoming = remap(source, &maps)?;
        let mut graph = existing.into_json();
        graph["stacks"] = json!(stacks);
        graph["tags"] = json!(tags);
        for table in validation::TABLES
            .into_iter()
            .filter(|t| !matches!(*t, "stacks" | "tags"))
        {
            graph[table]
                .as_array_mut()
                .expect("table")
                .append(incoming[table].as_array_mut().expect("remapped table"));
        }
        for (key, value) in incoming["decision_evidence"].as_object().expect("evidence") {
            graph["decision_evidence"][key] = value.clone();
        }
        graph["created_at"] = json!(library::now());
        let graph = Manifest::from_value(graph)?;
        let receipt = RestoreReceipt {
            operation: staged.operation.clone(),
            documents_added: source.rows("documents").len(),
            document_collisions: maps.documents.iter().filter(|(a, b)| *a != *b).count(),
            merged_stacks,
            source_version: source.source_version(),
            source_hash: staged.archive.source_hash.clone(),
            mappings: maps,
        };
        Ok(RestorePlan {
            receipt,
            directory: staged.directory.clone(),
            library_root: self.root.clone(),
            base_revision: self.revision()?,
            graph,
            source: source.clone(),
            source_manifest: staged.archive.source_manifest.clone(),
            pristine,
            _lease: staged.lease.clone(),
        })
    }

    pub fn commit_archive(
        &mut self,
        plan: &RestorePlan,
        control: &Control,
    ) -> Result<RestoreReceipt> {
        if plan.library_root != self.root {
            return Err(Error::Invalid(
                "Restore review belongs to a different library",
            ));
        }
        if let Some(existing) = self.committed(&plan.receipt.operation)? {
            if existing.source_hash != plan.receipt.source_hash {
                return Err(Error::Invalid(
                    "Restore retry identity has different source data",
                ));
            }
            return Ok(existing);
        }
        if self.revision()? != plan.base_revision {
            return Err(Error::Stale);
        }
        control.check()?;
        for id in plan.receipt.mappings.documents.values() {
            if self
                .root
                .join("originals")
                .join(id)
                .symlink_metadata()
                .is_ok()
            {
                return Err(Error::Stale);
            }
        }
        // Bind the reviewed graph to verified immutable staging bytes again before publication.
        for file in plan.source.rows("files") {
            let path = plan
                .directory
                .join("input")
                .join(file["path"].as_str().expect("path"));
            if stack_archive::file_hash(&path, file["length"].as_u64().expect("length"), control)?
                != file["sha256"].as_str().expect("hash")
            {
                return Err(Error::Invalid("A staged original changed after review"));
            }
        }
        let result = self.install_archive(plan, control);
        if result.is_err() {
            if self.committed(&plan.receipt.operation)?.is_some() {
                return Err(Error::CommittedCleanup(plan.receipt.operation.clone()));
            }
            self.recover_one(&plan.directory)?;
        }
        result
    }

    fn install_archive(&mut self, plan: &RestorePlan, control: &Control) -> Result<RestoreReceipt> {
        let documents: Vec<_> = plan.receipt.mappings.documents.values().cloned().collect();
        write_new_json(
            &plan.directory.join("journal.json"),
            &json!({"operation":plan.receipt.operation,"documents":documents}),
        )?;
        stack_archive::sync_directory(&plan.directory)?;
        (self.probe)("journal")?;
        for (index, (old, new)) in plan.receipt.mappings.documents.iter().enumerate() {
            control.check()?;
            let from = plan.directory.join("input/originals").join(old);
            let to = self.root.join("originals").join(new);
            stack_archive::read_directory(&from)?;
            let mut marker = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(from.join(".stack-import-owner"))?;
            marker.write_all(plan.receipt.operation.as_bytes())?;
            marker.sync_all()?;
            stack_archive::sync_directory(&from)?;
            rustix::fs::renameat_with(
                rustix::fs::CWD,
                &from,
                rustix::fs::CWD,
                &to,
                rustix::fs::RenameFlags::NOREPLACE,
            )
            .map_err(std::io::Error::from)?;
            stack_archive::sync_directory(&self.root.join("originals"))?;
            stack_archive::sync_directory(&plan.directory.join("input/originals"))?;
            (self.probe)(&format!("original-{}", index + 1))?;
        }
        control.check()?;
        let tx = self
            .db
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        write_graph(&tx, &plan.graph)?;
        // One transaction contains the entire graph, source identity map, event and retry marker.
        tx.execute(
            "INSERT INTO restore_commits VALUES(?1,?2,?3,?4)",
            params![
                plan.receipt.operation,
                serde_json::to_string(&plan.receipt)?,
                plan.receipt.source_hash,
                plan.source_manifest
            ],
        )?;
        for (kind, map) in [
            ("document", &plan.receipt.mappings.documents),
            ("page", &plan.receipt.mappings.pages),
            ("stack", &plan.receipt.mappings.stacks),
            ("tag", &plan.receipt.mappings.tags),
            ("scan", &plan.receipt.mappings.scans),
        ] {
            for (source, local) in map {
                let sync = sync_id(&tx, kind, local)?;
                tx.execute(
                    "INSERT INTO source_aliases VALUES(?1,?2,?3,?4,?5)",
                    params![plan.receipt.operation, kind, source, local, sync],
                )?;
            }
        }
        put_meta(&tx, "catalog_pristine", "0")?;
        journal(
            &tx,
            "archive.restore",
            json!({"receipt":plan.receipt,"graph":plan.graph.json(),"bindings":bindings(&tx)?,"replaced_pristine_catalog":plan.pristine}),
        )?;
        (self.probe)("db_transaction")?;
        control.check()?;
        tx.commit()?;
        (self.probe)("db_committed")?;
        self.cleanup_committed(&plan.directory, &documents, &plan.receipt.operation)?;
        (self.probe)("cleanup")?;
        Ok(plan.receipt.clone())
    }

    pub fn prepare_snapshot(&self, control: &Control) -> Result<SnapshotWork> {
        self.ensure_sync_exportable()?;
        self.prepare_snapshot_for(None, control)
    }
    pub fn prepare_document_snapshot(
        &self,
        token: &library::DocumentToken,
        control: &Control,
    ) -> Result<SnapshotWork> {
        self.checked_token(token)?;
        self.prepare_snapshot_for(Some(&token.id), control)
    }
    fn prepare_snapshot_for(
        &self,
        document: Option<&str>,
        control: &Control,
    ) -> Result<SnapshotWork> {
        control.check()?;
        let (_, directory) = self.operation("snapshot")?;
        let lease = Arc::new(StageLease {
            directory: directory.clone(),
            _lock: self._lock.clone(),
        });
        let mut graph = self.manifest()?.into_json();
        if let Some(id) = document {
            graph["documents"]
                .as_array_mut()
                .expect("documents")
                .retain(|d| d["id"] == id);
            for table in validation::TABLES
                .into_iter()
                .filter(|t| !matches!(*t, "documents" | "stacks" | "tags"))
            {
                graph[table]
                    .as_array_mut()
                    .expect("rows")
                    .retain(|row| library::owner(table, row) == Some(id));
            }
            graph["decision_evidence"]
                .as_object_mut()
                .expect("evidence")
                .retain(|key, _| key == id);
        }
        for doc in graph["documents"].as_array().expect("documents").clone() {
            let id = doc["id"].as_str().expect("ID");
            graph["decision_evidence"][id] = json!(validation::current_evidence(&graph, id));
        }
        Ok(SnapshotWork {
            manifest: Manifest::from_value(graph)?,
            directory,
            root: self.root.clone(),
            lease,
        })
    }
    pub fn snapshot(&mut self, control: &Control) -> Result<FrozenSnapshot> {
        self.prepare_snapshot(control)?.pin(control)
    }

    pub fn prepare_output(&self) -> Result<OutputWork> {
        let (operation, directory) = self.operation("export")?;
        Ok(OutputWork {
            operation,
            directory,
            root: self.root.clone(),
            _lock: self._lock.clone(),
        })
    }
    pub fn export_archive(&mut self, destination: &Path, control: &Control) -> Result<()> {
        let snapshot = self.snapshot(control)?;
        let output = self.prepare_output()?;
        output.publish_with_probe(destination, control, &mut self.probe, |file, control| {
            snapshot.write(file, control)
        })
    }

    /// Raw image/PDF ingestion uses the same staged graph/atomic publication path.
    pub fn import_original(
        &mut self,
        source: &Path,
        title: &str,
        verifier: &dyn SourceVerifier,
        mime: &str,
        pages: u32,
        control: &Control,
    ) -> Result<RestoreReceipt> {
        let staged = self
            .prepare_import()?
            .original(source, title, verifier, mime, pages, control)?;
        let plan = self.plan(&staged, false)?;
        self.commit_archive(&plan, control)
    }

    pub fn committed_receipt(&self, operation: &str) -> Result<Option<RestoreReceipt>> {
        stack_archive::uuid(operation)?;
        self.committed(operation)
    }
    /// Retry cleanup of this committed receipt; never starts another additive import.
    pub fn retry_committed(&self, operation: &str) -> Result<RestoreReceipt> {
        stack_archive::uuid(operation)?;
        let receipt = self.committed(operation)?.ok_or(Error::Stale)?;
        let directory = self.root.join("operations").join(operation);
        if directory.exists() && self.recover_one(&directory).is_err() {
            return Err(Error::CommittedCleanup(operation.into()));
        }
        Ok(receipt)
    }

    fn committed(&self, operation: &str) -> Result<Option<RestoreReceipt>> {
        let text: Option<String> = self
            .db
            .query_row(
                "SELECT receipt FROM restore_commits WHERE operation=?1",
                [operation],
                |r| r.get(0),
            )
            .optional()?;
        text.map(|s| Ok(serde_json::from_str(&s)?)).transpose()
    }
    pub(crate) fn operation(&self, kind: &str) -> Result<(String, PathBuf)> {
        let id = Uuid::new_v4().to_string();
        let directory = self.root.join("operations").join(&id);
        fs::create_dir(&directory)?;
        fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))?;
        write_new_json(
            &directory.join("operation.json"),
            &json!({"operation":id,"kind":kind}),
        )?;
        stack_archive::sync_directory(&directory)?;
        stack_archive::sync_directory(&self.root.join("operations"))?;
        Ok((id, directory))
    }
    pub(crate) fn recover_operations(&mut self) -> Result<()> {
        for entry in fs::read_dir(self.root.join("operations"))? {
            self.recover_one(&entry?.path())?;
        }
        Ok(())
    }
    fn recover_one(&self, directory: &Path) -> Result<()> {
        stack_archive::read_directory(directory)?;
        let operation = directory
            .file_name()
            .and_then(|s| s.to_str())
            .ok_or(Error::Invalid("Invalid operation directory"))?;
        stack_archive::uuid(operation)?;
        let owner = read_json(&directory.join("operation.json"), 16 * 1024)?;
        if owner["operation"] != operation {
            return Err(Error::Invalid("Operation ownership marker disagrees"));
        }
        match owner["kind"].as_str() {
            Some("import") => {
                let journal = directory.join("journal.json");
                if journal.exists() {
                    let value = read_json(&journal, 128 * 1024)?;
                    validation::keys(&value, "operation,documents")?;
                    if value["operation"] != operation {
                        return Err(Error::Invalid("Import journal ownership disagrees"));
                    }
                    let docs = value["documents"]
                        .as_array()
                        .filter(|a| a.len() <= 1000)
                        .ok_or(Error::Invalid("Invalid import recovery list"))?;
                    let mut ids = Vec::new();
                    for id in docs {
                        let id = id
                            .as_str()
                            .ok_or(Error::Invalid("Invalid recovery document ID"))?;
                        stack_archive::uuid(id)?;
                        ids.push(id.to_owned());
                    }
                    if self.committed(operation)?.is_some() {
                        return self.cleanup_committed(directory, &ids, operation);
                    }
                    for id in ids {
                        let owned = self.root.join("originals").join(&id);
                        if !owned.exists() {
                            continue;
                        }
                        stack_archive::read_directory(&owned)?;
                        let present: bool = self.db.query_row(
                            "SELECT EXISTS(SELECT 1 FROM documents WHERE id=?1)",
                            [&id],
                            |r| r.get(0),
                        )?;
                        let mut marker = String::new();
                        stack_archive::read_regular(&owned.join(".stack-import-owner"))?
                            .take(128)
                            .read_to_string(&mut marker)?;
                        if present || marker != operation {
                            return Err(Error::Invalid(
                                "Recovery refuses to remove originals with uncertain ownership",
                            ));
                        }
                        fs::remove_dir_all(owned)?;
                    }
                    stack_archive::sync_directory(&self.root.join("originals"))?;
                }
                fs::remove_dir_all(directory)?;
            }
            Some("snapshot" | "sync") => fs::remove_dir_all(directory)?,
            Some("export") => cleanup_export(directory)?,
            _ => return Err(Error::Invalid("Unknown operation recovery type")),
        }
        stack_archive::sync_directory(&self.root.join("operations"))?;
        Ok(())
    }
    fn cleanup_committed(
        &self,
        directory: &Path,
        documents: &[String],
        operation: &str,
    ) -> Result<()> {
        for id in documents {
            let folder = self.root.join("originals").join(id);
            stack_archive::read_directory(&folder)?;
            let marker = folder.join(".stack-import-owner");
            if marker.exists() {
                let mut owner = String::new();
                stack_archive::read_regular(&marker)?
                    .take(128)
                    .read_to_string(&mut owner)?;
                if owner != operation {
                    return Err(Error::Invalid(
                        "Committed original ownership marker disagrees",
                    ));
                }
                fs::remove_file(marker)?;
                stack_archive::sync_directory(&folder)?;
            }
        }
        fs::remove_dir_all(directory)?;
        stack_archive::sync_directory(&self.root.join("operations"))?;
        Ok(())
    }
}

#[derive(Serialize, Deserialize)]
struct ExportTarget {
    path: Vec<u8>,
    device: u64,
    inode: u64,
}
fn cleanup_export(directory: &Path) -> Result<()> {
    let journal = directory.join("target.json");
    if journal.exists() {
        let value = read_json(&journal, 32 * 1024)?;
        let target: ExportTarget = serde_json::from_value(value)?;
        if target.path.len() > 4096 {
            return Err(Error::Invalid("Invalid export recovery path"));
        }
        let path = PathBuf::from(std::ffi::OsString::from_vec(target.path));
        let operation = directory
            .file_name()
            .and_then(|s| s.to_str())
            .ok_or(Error::Invalid("Invalid export operation"))?;
        if !path.is_absolute()
            || path.file_name().and_then(|s| s.to_str())
                != Some(&format!(".stack-export-{operation}.part"))
        {
            return Err(Error::Invalid("Invalid owned export filename"));
        }
        if let Ok(metadata) = path.symlink_metadata() {
            if !metadata.is_file()
                || metadata.file_type().is_symlink()
                || metadata.dev() != target.device
                || metadata.ino() != target.inode
            {
                return Err(Error::Invalid(
                    "Export cleanup refuses an output whose ownership changed",
                ));
            }
            fs::remove_file(&path)?;
            stack_archive::sync_directory(path.parent().expect("absolute output parent"))?;
        }
    }
    fs::remove_dir_all(directory)?;
    Ok(())
}

struct OwnedPart {
    path: PathBuf,
    device: u64,
    inode: u64,
}
impl Drop for OwnedPart {
    fn drop(&mut self) {
        if let Ok(metadata) = self.path.symlink_metadata()
            && metadata.is_file()
            && !metadata.file_type().is_symlink()
            && metadata.dev() == self.device
            && metadata.ino() == self.inode
        {
            let _ = fs::remove_file(&self.path);
        }
    }
}

struct CleanupDirectory(PathBuf, bool);
impl Drop for CleanupDirectory {
    fn drop(&mut self) {
        if self.1 {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
}

pub(crate) fn write_new_json(path: &Path, value: &Value) -> Result<()> {
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path.with_extension("json.tmp"))?;
    file.write_all(&serde_json::to_vec(value)?)?;
    file.sync_all()?;
    rustix::fs::renameat_with(
        rustix::fs::CWD,
        path.with_extension("json.tmp"),
        rustix::fs::CWD,
        path,
        rustix::fs::RenameFlags::NOREPLACE,
    )
    .map_err(std::io::Error::from)?;
    stack_archive::sync_directory(
        path.parent()
            .ok_or(Error::Invalid("Missing journal parent"))?,
    )?;
    Ok(())
}
fn read_json(path: &Path, limit: usize) -> Result<Value> {
    let file = stack_archive::read_regular(path)?;
    if file.metadata()?.len() > limit as u64 {
        return Err(Error::Invalid("Operation recovery record exceeds limit"));
    }
    let mut bytes = Vec::new();
    file.take(limit as u64 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > limit {
        return Err(Error::Invalid("Operation recovery record grew"));
    }
    Ok(stack_archive::strict_json::object(&bytes)?)
}

fn mapped(operation: &str, kind: &str, old: &str, used: &mut BTreeSet<String>) -> Result<String> {
    let namespace =
        Uuid::parse_str(operation).map_err(|_| Error::Invalid("Invalid operation ID"))?;
    let mut output = old.to_owned();
    let mut attempt = 0_u32;
    while used.contains(&output) {
        attempt = attempt
            .checked_add(1)
            .ok_or(Error::Invalid("ID collision space exhausted"))?;
        output = Uuid::new_v5(&namespace, format!("{kind}:{old}:{attempt}").as_bytes()).to_string();
    }
    used.insert(output.clone());
    Ok(output)
}

pub(crate) fn remap(source: &Manifest, maps: &IdMappings) -> Result<Value> {
    let mut value = source.json().clone();
    for doc in value["documents"].as_array_mut().expect("docs") {
        doc["id"] = json!(maps.documents[doc["id"].as_str().expect("ID")]);
        if let Some(scan) = doc["source_scan_id"].as_str() {
            doc["source_scan_id"] = json!(maps.scans[scan]);
        }
    }
    for table in [
        "pages",
        "memberships",
        "suggestions",
        "choices",
        "details",
        "document_tags",
    ] {
        for row in value[table].as_array_mut().expect("rows") {
            row["document_id"] =
                json!(maps.documents[row["document_id"].as_str().expect("doc ID")]);
            if table == "pages" {
                row["id"] = json!(maps.pages[row["id"].as_str().expect("page ID")]);
            }
            if table == "memberships" {
                row["stack_id"] = json!(maps.stacks[row["stack_id"].as_str().expect("stack ID")]);
            }
            if table == "document_tags" {
                row["tag_id"] = json!(maps.tags[row["tag_id"].as_str().expect("tag ID")]);
            }
            if table == "details" {
                let raw = row["evidence"].as_str().expect("detail evidence");
                let mut evidence = stack_archive::strict_json::object(raw.as_bytes())?;
                if let Some(old) = evidence["page_id"].as_str()
                    && let Some(new) = maps.pages.get(old).filter(|new| new.as_str() != old)
                {
                    evidence["page_id"] = json!(new);
                    row["evidence"] = json!(evidence.to_string());
                }
            }
            if table == "choices" && row["field"] == "filing" {
                row["value"] = json!(remap_choice(
                    row["value"].as_str().expect("choice"),
                    &maps.stacks
                ));
            }
        }
    }
    for file in value["files"].as_array_mut().expect("files") {
        let (id, name) = stack_archive::original_path(file["path"].as_str().expect("path"))?;
        file["path"] = json!(format!("originals/{}/{name}", maps.documents[id]));
    }
    value["decision_evidence"] = json!({});
    for (old, new) in &maps.documents {
        let evidence = validation::current_evidence(&value, new);
        for choice in value["choices"].as_array_mut().expect("choices") {
            if choice["document_id"] == *new
                && choice["evidence"] == source.json()["decision_evidence"][old]
            {
                choice["evidence"] = json!(evidence);
            }
        }
        value["decision_evidence"][new] = json!(evidence);
    }
    Ok(value)
}

fn remap_choice(raw: &str, stacks: &BTreeMap<String, String>) -> String {
    if stacks.iter().all(|(old, new)| old == new) {
        return raw.to_owned();
    }
    let csv = |value: &str| {
        value
            .split(',')
            .filter(|s| !s.is_empty())
            .map(|s| stacks.get(s).map_or(s, String::as_str))
            .collect::<Vec<_>>()
            .join(",")
    };
    if raw.starts_with('{') {
        if let Ok(mut value) = stack_archive::strict_json::object(raw.as_bytes()) {
            let selected = value["chosen"].as_str().unwrap_or("");
            value["chosen"] = json!(csv(selected));
            if let Some(old) = value["proposed"]["stacks"].as_object() {
                let changed: serde_json::Map<String, Value> = old
                    .iter()
                    .map(|(id, reason)| (stacks.get(id).unwrap_or(id).clone(), reason.clone()))
                    .collect();
                value["proposed"]["stacks"] = json!(changed);
            }
            return value.to_string();
        }
        raw.to_owned()
    } else {
        csv(raw)
    }
}

fn single_document(
    id: &str,
    title: &str,
    mime: &str,
    pages: u32,
    path: &str,
    length: u64,
    hash: &str,
) -> Result<Manifest> {
    let mut graph = Manifest::empty().into_json();
    graph["documents"] = json!([{"id":id,"title":title,"mime":mime,"added_at":library::now(),"page_count":pages,
        "title_user_edited":1,"initial_page_count":pages,"source_scan_id":null,"title_origin":"manual","filing_origin":"default",
        "title_decision":1,"filing_decision":0,"notes":"","notes_revision":0,"metadata_revision":0}]);
    graph["pages"]=json!((0..pages).map(|index|json!({"id":Uuid::new_v4().to_string(),"document_id":id,"position":index,
        "source_page":index,"rotation":0,"crop":"","enhancement":"original","flatten":0,"ocr_text":"","ocr_state":"pending",
        "ocr_engine":"","language":"","source_file":"source","content_revision":0,"text_revision":-1,
        "ocr_error":"","ocr_config":"","text_config":""})).collect::<Vec<_>>());
    graph["suggestions"] = json!([{"document_id":id,"generation":0,"status":"pending","input":"","evidence":"","rule":"",
        "title_revision":1,"filing_revision":0,"payload":"{}","title_state":"edited","filing_state":"offered"}]);
    graph["files"] = json!([{"path":path,"length":length,"sha256":hash}]);
    graph["decision_evidence"][id] = json!(validation::current_evidence(&graph, id));
    Ok(Manifest::from_value(graph)?)
}
