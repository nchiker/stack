//! Link records in the sync (slice 6, D5; `docs/STACK_LINK_SLICE6_DESIGN.md`).
//!
//! The desktop is a read-only mirror of the phone's links. This module holds everything that is not the pure graph
//! rules (those are `stack_sync::links`): the separate `link_projection` store, the apply of received link cells
//! inside the install transaction, blob keeping by copy, the one enable operation, the derived and cached
//! `enabled`, and the preview install and repair.
//!
//! Rules that are easy to break and are enforced here:
//! * nothing in the document capture/install code reads or writes `link_projection` (Opus finding C1);
//! * a row is deleted ONLY when the presence cell says `gone`, never because a link is absent;
//! * a blob is durable in `sync_blobs/<sha>` (copied, never hard-linked, fsynced, verified) before the operation or
//!   the transaction that references it commits;
//! * a bad or missing link is skipped and counted; the import never fails because of a link.
use crate::library::{Error, Library, Result, meta, private_dir};
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde_json::{Value, json};
use stack_sync::{
    Dot, Frontier, Operation, State,
    graph::{self, Cells},
    links::{self as wire, LinkBlob, LinkFilen, LinkInput, LinkPresence, LinkRow, LinkState},
};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;

const MARKER: &str = "enabled/";
/// Same bound as the archive and the phone: more non-trashed links than this cannot be exported.
const VISIBLE_LIMIT: i64 = stack_archive::links::LINK_LIMIT as i64;
const PLACEHOLDER_LOCATORS: &str = r#"[{"type":"owner","token":"stack-sync"}]"#;

/// What an install did with link cells (all counts are links, not cells).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LinkReport {
    /// Rows inserted or updated (a row deleted for `gone` is counted in `deleted`, not here). A link with a
    /// missing blob part is applied for its other parts and also counted in `missing_blob`.
    pub applied: usize,
    pub deleted: usize,
    /// Valid on the wire but failed a semantic limit, or a blob that does not verify as text.
    pub skipped: usize,
    /// Skipped because one of their cells is conflicted (phone resolves).
    pub conflicted: usize,
    /// Skipped because the desktop already holds 1000 visible links.
    pub not_shown: usize,
    /// Links whose text or preview blob is missing or does not match its cell: the other parts are applied and
    /// the blob part is retried when a blob with that sha arrives.
    pub missing_blob: usize,
    /// Preview files that could not be installed after the commit (the row stays pending; no key or text here).
    pub preview_failed: usize,
    /// Link cells left out of this device's enable operation (source changed, unreadable, or failed validation).
    pub enable_dropped: usize,
}

impl LinkReport {
    pub fn to_json(&self) -> Value {
        json!({"applied":self.applied,"deleted":self.deleted,"skipped":self.skipped,"conflicted":self.conflicted,"not_shown":self.not_shown,"missing_blob":self.missing_blob,"preview_failed":self.preview_failed,"enable_dropped":self.enable_dropped})
    }
}

pub(crate) fn is_link_key(key: &str) -> bool {
    key.starts_with(wire::PREFIX)
}

/// The archive requires 1 to 8 locators; a row mirrored from a phone link that has no Filen locator stores `[]`
/// and is exported with an inert owner placeholder (it is `unreachable` either way).
pub(crate) fn exportable_locators(stored: String) -> String {
    match serde_json::from_str::<Value>(&stored) {
        Ok(Value::Array(list)) if !list.is_empty() => stored,
        _ => PLACEHOLDER_LOCATORS.to_owned(),
    }
}

// ---------------------------------------------------------------------------
// link_projection (separate from sync_state.projection)
// ---------------------------------------------------------------------------

fn read_projection(db: &Connection) -> Result<Cells> {
    let mut statement = db.prepare("SELECT key,value FROM link_projection")?;
    let rows = statement.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
    let mut cells = Cells::new();
    for row in rows {
        let (key, value) = row?;
        // An undecodable stored cell is skipped: its link then reads as changed and is applied again, instead of
        // failing every install.
        if let Ok(value) = serde_json::from_str(&value) {
            cells.insert(key, value);
        }
    }
    Ok(cells)
}

fn write_projection(tx: &Transaction<'_>, cells: &Cells) -> Result<()> {
    tx.execute("DELETE FROM link_projection", [])?;
    let mut insert = tx.prepare("INSERT INTO link_projection VALUES(?1,?2)")?;
    for (key, value) in cells {
        insert.execute(params![key, serde_json::to_string(value)?])?;
    }
    Ok(())
}

/// `link_records/["o","id"]/suffix` -> (identity key, suffix). Markers and unknown suffixes are not link cells.
fn split(key: &str) -> Option<(&str, &str)> {
    let rest = key.strip_prefix(wire::PREFIX)?;
    if rest.starts_with(MARKER) || !rest.starts_with('[') {
        return None;
    }
    let (identity, suffix) = if rest.ends_with(']') {
        (key, "")
    } else {
        let at = rest.rfind("]/")?;
        (&key[..wire::PREFIX.len() + at + 1], &rest[at + 1..])
    };
    wire::SUFFIXES
        .contains(&suffix)
        .then_some((identity, suffix))
}

fn groups(cells: &Cells) -> BTreeMap<String, Cells> {
    let mut out: BTreeMap<String, Cells> = BTreeMap::new();
    for (key, value) in cells {
        if let Some((identity, _)) = split(key) {
            out.entry(identity.to_owned())
                .or_default()
                .insert(key.clone(), value.clone());
        }
    }
    out
}

fn identity_of(row_owner: &str, row_link: &str) -> String {
    wire::link_key(row_owner, row_link)
}

// ---------------------------------------------------------------------------
// Derived, cached `enabled`
// ---------------------------------------------------------------------------

pub(crate) fn derive_enabled(state: &State, replica: &str) -> bool {
    // As the phone's `LinkSync.enabled`: this replica's own `links.enable` operation that also carries its marker.
    let marker = wire::marker_key(replica);
    state.history.values().any(|op| {
        op.id.replica == replica
            && op.evidence["kind"] == "links.enable"
            && op.changes.contains_key(&marker)
    })
}

/// True when the incoming operations carry link data: the peer has enabled links, so this side must too.
pub(crate) fn bundle_has_links(operations: &[Operation]) -> bool {
    operations.iter().any(|op| {
        op.evidence["kind"] == "links.enable" || op.changes.keys().any(|k| is_link_key(k))
    })
}

// ---------------------------------------------------------------------------
// Blob keeping (always by COPY)
// ---------------------------------------------------------------------------

/// What keeping a blob came to: kept, not kept because the source or content is bad (that cell is left out), or
/// not kept because the new file could not be written (the enable falls back to the marker alone).
enum Kept {
    Yes,
    No,
    Failed,
}

enum Source<'a> {
    Bytes(&'a [u8]),
    File(&'a Path),
}

impl Library {
    fn blobs_dir(&self) -> Result<PathBuf> {
        let blobs = self.root.join("sync_blobs");
        private_dir(&blobs)?;
        Ok(blobs)
    }

    /// Makes `sync_blobs/<sha>` durable and verified: temp file, fsync, rename, directory fsync, hash re-check, and
    /// only then may a database commit reference it. A source or content problem (changed, shrunk, unreadable,
    /// an existing target that does not verify, which is removed and copied again) is `Kept::No`; a failure to
    /// write the new file is `Kept::Failed`. Only an injected probe stop is an error.
    fn keep_link_blob(&mut self, sha: &str, length: u64, source: Source<'_>) -> Result<Kept> {
        let Ok(blobs) = self.blobs_dir() else {
            return Ok(Kept::Failed);
        };
        let target = blobs.join(sha);
        let control = stack_archive::Control::default();
        if fs::symlink_metadata(&target).is_ok() {
            if matches!(stack_archive::file_hash(&target, length, &control), Ok(hash) if hash == sha)
            {
                return Ok(Kept::Yes);
            }
            // Wrong length, wrong bytes or unreadable: it is not the blob it is named for.
            if fs::remove_file(&target).is_err() {
                return Ok(Kept::Failed);
            }
        }
        let temporary = blobs.join(format!(".link-{}.part", uuid::Uuid::new_v4()));
        let Ok(mut out) = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)
        else {
            return Ok(Kept::Failed);
        };
        let outcome = (|| -> Result<Kept> {
            match source {
                Source::Bytes(bytes) => {
                    if bytes.len() as u64 != length || stack_archive::digest(bytes) != sha {
                        return Ok(Kept::No);
                    }
                    if out.write_all(bytes).is_err() {
                        return Ok(Kept::Failed);
                    }
                }
                Source::File(path) => {
                    let Ok(mut input) = stack_archive::read_regular(path) else {
                        return Ok(Kept::No);
                    };
                    if !input.metadata().is_ok_and(|m| m.len() == length) {
                        return Ok(Kept::No);
                    }
                    let mut hash = <sha2::Sha256 as sha2::Digest>::new();
                    let mut remaining = length;
                    let mut buffer = [0u8; 64 * 1024];
                    while remaining > 0 {
                        let wanted = remaining.min(buffer.len() as u64) as usize;
                        match std::io::Read::read(&mut input, &mut buffer[..wanted]) {
                            Ok(0) | Err(_) => return Ok(Kept::No),
                            Ok(count) => {
                                if out.write_all(&buffer[..count]).is_err() {
                                    return Ok(Kept::Failed);
                                }
                                sha2::Digest::update(&mut hash, &buffer[..count]);
                                remaining -= count as u64;
                            }
                        }
                    }
                    let mut end = [0u8];
                    if !matches!(std::io::Read::read(&mut input, &mut end), Ok(0)) {
                        return Ok(Kept::No);
                    }
                    if format!("{:x}", sha2::Digest::finalize(hash)) != sha {
                        return Ok(Kept::No);
                    }
                }
            }
            (self.probe)("link_blob_temp")?;
            if out.sync_all().is_err() {
                return Ok(Kept::Failed);
            }
            Ok(Kept::Yes)
        })();
        drop(out);
        match outcome {
            Ok(Kept::Yes) => {}
            other => {
                let _ = fs::remove_file(&temporary);
                return other;
            }
        }
        let placed = fs::set_permissions(&temporary, fs::Permissions::from_mode(0o400))
            .and_then(|()| fs::rename(&temporary, &target))
            .is_ok()
            && stack_archive::sync_directory(&blobs).is_ok();
        if !placed {
            let _ = fs::remove_file(&temporary);
            return Ok(Kept::Failed);
        }
        (self.probe)("link_blob_kept")?;
        if !matches!(stack_archive::file_hash(&target, length, &control), Ok(hash) if hash == sha) {
            let _ = fs::remove_file(&target);
            return Ok(Kept::Failed);
        }
        Ok(Kept::Yes)
    }

    // -----------------------------------------------------------------------
    // The enable operation
    // -----------------------------------------------------------------------

    /// Whether this device has written its own link enable operation (derived from history, cached).
    pub fn sync_links_enabled(&self) -> Result<bool> {
        match self.links_enabled.load(Ordering::Relaxed) {
            1 => return Ok(false),
            2 => return Ok(true),
            _ => {}
        }
        let Some(state) = crate::sync::load(&self.db)? else {
            return Ok(false);
        };
        let enabled = derive_enabled(&state, &meta(&self.db, "replica_id")?);
        self.links_enabled
            .store(if enabled { 2 } else { 1 }, Ordering::Relaxed);
        Ok(enabled)
    }

    /// The hello hook (after the hello reply, before the first bundle): a peer that announced links makes this
    /// side enable once. Returns whether an operation was written. The hello itself is built in `stack-transport`.
    pub fn sync_links_on_hello(&mut self, peer_announces_links: bool) -> Result<bool> {
        if peer_announces_links {
            self.sync_enable_links()
        } else {
            Ok(false)
        }
    }

    /// Writes this device's one enable operation (marker plus every readable link row) if it has not yet. State and
    /// applied-cells store are saved in one transaction after the blobs are durable.
    pub fn sync_enable_links(&mut self) -> Result<bool> {
        let Some(mut state) = crate::sync::load(&self.db)? else {
            return Ok(false);
        };
        let replica = meta(&self.db, "replica_id")?;
        if derive_enabled(&state, &replica) {
            self.links_enabled.store(2, Ordering::Relaxed);
            return Ok(false);
        }
        let (operation, projection, _dropped) = self.link_enable_operation(&state, &replica)?;
        state.receive(std::slice::from_ref(&operation))?;
        let tx = self
            .db
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        // Merge: the stored projection holds nothing for a device that has never enabled, but keep it exact.
        let mut cells = read_projection(&tx)?;
        cells.extend(projection);
        write_projection(&tx, &cells)?;
        tx.execute(
            "UPDATE sync_state SET state=?1 WHERE singleton=1",
            [serde_json::to_string(&state)?],
        )?;
        (self.probe)("link_enable_transaction")?;
        tx.commit()?;
        (self.probe)("link_enable_committed")?;
        self.links_enabled.store(2, Ordering::Relaxed);
        Ok(true)
    }

    /// Builds (does not save) the enable operation: marker + link cells from the SAME builder and writer self-check
    /// the phone uses. Own-only context (concurrent with everything the phone wrote), so it never overrides the
    /// phone's baseline. Returns the operation, the link cells it carries (for `link_projection`) and how many cells
    /// were left out. A source or content problem only leaves a cell out; a write failure leaves the marker alone;
    /// only an injected probe stop is an error.
    pub(crate) fn link_enable_operation(
        &mut self,
        state: &State,
        replica: &str,
    ) -> Result<(Operation, Cells, usize)> {
        let (mut inputs, row_ids) = self.desktop_link_inputs();
        wire::trim_blobs_to_budget(&mut inputs, &BTreeSet::new());
        let built = wire::link_cells(&inputs);
        let mut cells = built.cells;
        // A desktop row is live: presence is omitted (absent = live), so it never conflicts with the phone's presence.
        cells.retain(|key, _| !key.ends_with("/presence"));
        let by_key: BTreeMap<String, &LinkInput> =
            inputs.iter().map(|row| (row.key(), row)).collect();
        let mut dropped = Vec::new();
        let mut write_failed = false;
        for (key, value) in &cells {
            let Some((identity, suffix)) = split(key) else {
                continue;
            };
            if value.is_null() || !matches!(suffix, "/text" | "/preview") {
                continue;
            }
            let (Some(sha), Some(length)) = (value["sha256"].as_str(), value["length"].as_u64())
            else {
                continue;
            };
            let Some(row) = by_key.get(identity) else {
                continue;
            };
            let kept = if suffix == "/text" {
                match self.keep_link_blob(
                    sha,
                    length,
                    Source::Bytes(row.text.as_deref().unwrap_or("").as_bytes()),
                )? {
                    Kept::Yes => true,
                    Kept::No => false,
                    Kept::Failed => {
                        write_failed = true;
                        break;
                    }
                }
            } else {
                let Some(row_id) = row_ids.get(identity) else {
                    continue;
                };
                let path = self.root.join(crate::links::preview_file(row_id));
                match self.keep_link_blob(sha, length, Source::File(&path))? {
                    Kept::Yes => true,
                    Kept::No => false,
                    Kept::Failed => {
                        write_failed = true;
                        break;
                    }
                }
            };
            if !kept {
                // The row changed since it was read: that cell is simply not synced (the link still is).
                dropped.push(key.clone());
            }
        }
        let mut left_out = dropped.len();
        for key in dropped {
            cells.remove(&key);
        }
        if write_failed {
            // The new blob file could not be written: publish the marker alone (the next sync can retry nothing
            // here, but the session and the import keep working).
            left_out += cells.len();
            cells.clear();
        }
        let mut changes: BTreeMap<String, Value> = cells.clone();
        changes.insert(
            wire::marker_key(replica),
            wire::marker_value(crate::clock::now_millis().clamp(0, wire::MAX_SAFE))?,
        );
        if graph::validate_changes(&changes).is_err() {
            // The writer never publishes what the receiver would reject. Fall back to the marker alone, which
            // keeps the operation valid and the session working.
            left_out += cells.len();
            cells.clear();
            changes.retain(|key, _| key.starts_with(&format!("{}{MARKER}", wire::PREFIX)));
        }
        let sequence = state
            .frontier
            .get(replica)
            .copied()
            .unwrap_or(0)
            .checked_add(1)
            .ok_or(Error::Invalid("Sync counter exhausted"))?;
        let mut context = Frontier::new();
        if sequence > 1 {
            context.insert(replica.to_owned(), sequence - 1);
        }
        let operation = Operation {
            version: 1,
            library: state.library.clone(),
            epoch: state.epoch,
            id: Dot {
                replica: replica.to_owned(),
                sequence,
            },
            context,
            changes,
            evidence: json!({"kind":"links.enable","graph_model":graph::GRAPH_MODEL}),
        };
        Ok((operation, cells, left_out))
    }

    /// Every readable non-trashed OWN row as a writer input (a record the partner shares is never one of them). An unreadable or invalid row is left out (the writer
    /// self-check refuses it later); this never fails the session.
    fn desktop_link_inputs(&self) -> (Vec<LinkInput>, BTreeMap<String, String>) {
        let none = (Vec::new(), BTreeMap::new());
        let Ok(mut statement) = self.db.prepare(
            "SELECT row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,title_user_edited,added_at,text,preview,preview_pending FROM link_records WHERE state<>'trashed' AND origin='own' ORDER BY added_at,row_id",
        ) else {
            return none;
        };
        let Ok(rows) = statement.query_map([], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, String>(2)?,
                r.get::<_, String>(3)?,
                r.get::<_, String>(4)?,
                r.get::<_, String>(5)?,
                r.get::<_, i64>(6)?,
                r.get::<_, String>(7)?,
                r.get::<_, i64>(8)?,
                r.get::<_, i64>(9)?,
                r.get::<_, String>(10)?,
                r.get::<_, i64>(11)?,
                r.get::<_, i64>(12)?,
                r.get::<_, String>(13)?,
                r.get::<_, i64>(14)?,
                r.get::<_, i64>(15)?,
            ))
        }) else {
            return none;
        };
        let mut out = Vec::new();
        let mut ids = BTreeMap::new();
        for row in rows.flatten() {
            let (row_id, owner, link_id, kind, title, mime, modified, hash) =
                (row.0, row.1, row.2, row.3, row.4, row.5, row.6, row.7);
            let protected = row.9 != 0;
            let filen = serde_json::from_str::<Value>(&row.10)
                .ok()
                .and_then(|list| {
                    list.as_array()?.iter().find_map(|entry| {
                        (entry["type"] == "filen").then(|| LinkFilen {
                            uuid: entry["uuid"].as_str().unwrap_or("").to_owned(),
                            path: entry["path"].as_str().unwrap_or("").to_owned(),
                        })
                    })
                })
                .into_iter()
                .collect();
            let preview = if row.14 != 0 && row.15 == 0 && !protected {
                self.read_preview(&row_id)
            } else {
                None
            };
            ids.insert(wire::link_key(&owner, &link_id), row_id);
            out.push(LinkInput {
                owner,
                link_id,
                kind,
                added_at: row.12,
                hash,
                mime,
                modified,
                shared: row.8 != 0,
                protected,
                title,
                title_user_edited: row.11 != 0,
                filen,
                text: Some(row.13),
                preview,
                presence: LinkPresence {
                    state: LinkState::Live,
                    reason: String::new(),
                    since: 0,
                },
                omit_text: false,
                omit_preview: false,
                // The partner's records are never sent from here: the phone is their only author.
                foreign: None,
            });
        }
        (out, ids)
    }

    fn read_preview(&self, row_id: &str) -> Option<LinkBlob> {
        let path = self.root.join(crate::links::preview_file(row_id));
        let mut file = stack_archive::read_regular(&path).ok()?;
        let length = file.metadata().ok()?.len();
        if length == 0 || length > wire::MAX_PREVIEW_BYTES {
            return None;
        }
        let mut head = [0u8; 5];
        std::io::Read::read_exact(&mut file, &mut head).ok()?;
        if &head != b"%PDF-" {
            return None;
        }
        let sha =
            stack_archive::file_hash(&path, length, &stack_archive::Control::default()).ok()?;
        Some(LinkBlob {
            sha256: sha,
            length,
        })
    }
}

// ---------------------------------------------------------------------------
// Apply of received link cells (inside the install transaction)
// ---------------------------------------------------------------------------

enum Change<T> {
    Keep,
    Clear,
    Set(T),
}

struct Item {
    identity: String,
    row: LinkRow,
    /// A verified-to-exist blob; its bytes are read (once) when the item is applied.
    text: Change<LinkBlob>,
    preview: Change<LinkBlob>,
    filen: Change<LinkFilen>,
    /// Suffixes whose blob is not here yet: the part is not applied and keeps its OLD stored value, so the link
    /// is planned again when a blob with that sha arrives.
    retry: Vec<&'static str>,
}

pub(crate) struct LinkPlan {
    blobs: PathBuf,
    items: Vec<Item>,
    gone: Vec<(String, String)>,
    unchanged: BTreeSet<String>,
    new_groups: BTreeMap<String, Cells>,
    old_groups: BTreeMap<String, Cells>,
    report: LinkReport,
}

pub(crate) struct LinkApplied {
    pub removals: Vec<String>,
    pub report: LinkReport,
}

struct OldRow {
    row_id: String,
    origin: String,
    shared_by: String,
    foreign_generation: i64,
    state: String,
    state_since: i64,
    removed_reason: String,
    title: String,
    mime: String,
    modified: i64,
    hash: String,
    shared: i64,
    protected: i64,
    locators: String,
    title_user_edited: i64,
    text: String,
    preview: i64,
    preview_sha: String,
    preview_pending: i64,
}

fn uuid_form(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, b)| match i {
            8 | 13 | 18 | 23 => b == b'-',
            _ => b.is_ascii_digit() || (b'a'..=b'f').contains(&b),
        })
}

enum TextBlob {
    Text(String),
    Missing,
    Invalid,
}

/// Reads a kept text blob ONCE: length and sha-256 must match its cell, then it must be text the archive accepts.
fn read_text_blob(blobs: &Path, blob: &LinkBlob) -> TextBlob {
    if blob.length > stack_archive::links::MAX_TEXT as u64 * 3 {
        return TextBlob::Invalid;
    }
    let Ok(file) = stack_archive::read_regular(&blobs.join(&blob.sha256)) else {
        return TextBlob::Missing;
    };
    let mut bytes = Vec::new();
    if std::io::Read::read_to_end(&mut std::io::Read::take(file, blob.length + 1), &mut bytes)
        .is_err()
        || bytes.len() as u64 != blob.length
        || stack_archive::digest(&bytes) != blob.sha256
    {
        return TextBlob::Missing;
    }
    match String::from_utf8(bytes) {
        Ok(text)
            if !text.contains('\0')
                && stack_archive::validation::utf16_len(&text)
                    <= stack_archive::links::MAX_TEXT =>
        {
            TextBlob::Text(text)
        }
        _ => TextBlob::Invalid,
    }
}

fn blob_present(blobs: &Path, blob: &LinkBlob) -> bool {
    stack_archive::read_regular(&blobs.join(&blob.sha256))
        .and_then(|f| Ok(f.metadata()?.len()))
        .is_ok_and(|len| len == blob.length)
}

impl Library {
    /// Before the transaction: project the link cells, skip what cannot apply, check that blobs are present. No
    /// database write and no blob text in memory. `values` is the state's register view the caller already
    /// computed. `own` are the cells of this device's enable operation that was just written (already the rows'
    /// own values).
    pub(crate) fn plan_link_apply(
        &self,
        state: &State,
        values: &BTreeMap<String, Value>,
        own: Option<&Cells>,
    ) -> Result<Option<LinkPlan>> {
        if !values.keys().any(|k| split(k).is_some()) {
            return Ok(None);
        }
        let mut old = read_projection(&self.db)?;
        if let Some(own) = own {
            old.retain(|key, _| split(key).is_none_or(|(identity, _)| !own.contains_key(identity)));
            old.extend(own.clone());
        }
        // Only link keys can matter here: a link key with differing alternatives (no full conflict analysis).
        let conflicted: BTreeSet<String> = state
            .registers
            .iter()
            .filter(|(key, alternatives)| {
                is_link_key(key)
                    && alternatives
                        .iter()
                        .skip(1)
                        .any(|a| a.value != alternatives[0].value)
            })
            .map(|(key, _)| key.clone())
            .collect();
        let projected = wire::project_links(values, &conflicted);
        let new_groups = groups(values);
        let old_groups = groups(&old);
        let mut plan = LinkPlan {
            blobs: self.root.join("sync_blobs"),
            items: Vec::new(),
            gone: Vec::new(),
            unchanged: BTreeSet::new(),
            new_groups,
            old_groups,
            report: LinkReport {
                skipped: projected.skipped,
                conflicted: projected.conflicted,
                ..LinkReport::default()
            },
        };
        // A `gone` identity is deleted only when its cells changed since the stored projection: a link restored
        // later (archive restore) is not deleted again by an unrelated install.
        for (owner, link_id) in projected.gone {
            let identity = identity_of(&owner, &link_id);
            if plan.old_groups.get(&identity) == plan.new_groups.get(&identity) {
                plan.unchanged.insert(identity);
            } else {
                plan.gone.push((owner, link_id));
            }
        }
        for row in projected.rows {
            let identity = identity_of(&row.owner, &row.link_id);
            let Some(group) = plan.new_groups.get(&identity) else {
                continue;
            };
            if plan.old_groups.get(&identity) == Some(group) {
                plan.unchanged.insert(identity);
                continue;
            }
            let cell = |suffix: &str| values.get(&format!("{identity}{suffix}"));
            let mut retry = Vec::new();
            let text = if row.metadata_only {
                Change::Keep
            } else {
                match (cell("/text"), &row.text) {
                    (Some(Value::Null), _) => Change::Clear,
                    (None, _) | (Some(_), None) => Change::Keep,
                    (Some(_), Some(blob)) if blob_present(&plan.blobs, blob) => {
                        Change::Set(blob.clone())
                    }
                    (Some(_), Some(_)) => {
                        retry.push("/text");
                        Change::Keep
                    }
                }
            };
            let preview = if row.metadata_only {
                Change::Keep
            } else {
                match (cell("/preview"), &row.preview) {
                    (Some(Value::Null), _) => Change::Clear,
                    (None, _) | (Some(_), None) => Change::Keep,
                    (Some(_), Some(blob)) if blob_present(&plan.blobs, blob) => {
                        Change::Set(blob.clone())
                    }
                    (Some(_), Some(_)) => {
                        retry.push("/preview");
                        Change::Keep
                    }
                }
            };
            let filen = match (cell("/filen"), &row.filen) {
                (Some(Value::Null), _) => Change::Clear,
                (Some(_), Some(f)) if uuid_form(&f.uuid) => Change::Set(f.clone()),
                _ => Change::Keep,
            };
            plan.items.push(Item {
                identity,
                row,
                text,
                preview,
                filen,
                retry,
            });
        }
        Ok(Some(plan))
    }

    /// Inside the install transaction: insert, update by `(owner, link_id)` keeping the local row id, delete ONLY
    /// presence `gone`, rewrite `link_projection`. Preview files are not touched here (after commit).
    pub(crate) fn apply_link_plan(tx: &Transaction<'_>, plan: LinkPlan) -> Result<LinkApplied> {
        let LinkPlan {
            blobs,
            items,
            gone,
            unchanged,
            new_groups,
            old_groups,
            mut report,
        } = plan;
        // Only the person's own links count towards the 1000 an archive can hold: a record the partner shares is a
        // mirror of the phone and never goes into an archive.
        let mut visible: i64 = tx.query_row(
            "SELECT count(*) FROM link_records WHERE state<>'trashed' AND origin='own'",
            [],
            |r| r.get(0),
        )?;
        let mut done = unchanged;
        let mut retries: BTreeMap<String, Vec<&'static str>> = BTreeMap::new();
        let mut removals = Vec::new();
        for mut item in items {
            // The text is read here, once, for this item only (the plan holds no text).
            let text_change: Change<String> = match &item.text {
                Change::Keep => Change::Keep,
                Change::Clear => Change::Clear,
                Change::Set(blob) => match read_text_blob(&blobs, blob) {
                    TextBlob::Text(text) => Change::Set(text),
                    TextBlob::Missing => {
                        item.retry.push("/text");
                        Change::Keep
                    }
                    TextBlob::Invalid => {
                        report.skipped += 1;
                        continue;
                    }
                },
            };
            let row = &item.row;
            let old = tx
                .query_row(
                    "SELECT row_id,state,state_since,removed_reason,title,mime,modified,hash,shared,protected,locators,title_user_edited,text,preview,preview_sha,preview_pending,origin,shared_by,foreign_generation FROM link_records WHERE owner=?1 AND link_id=?2",
                    params![row.owner, row.link_id],
                    |r| {
                        Ok(OldRow {
                            row_id: r.get(0)?,
                            origin: r.get(16)?,
                            shared_by: r.get(17)?,
                            foreign_generation: r.get(18)?,
                            state: r.get(1)?,
                            state_since: r.get(2)?,
                            removed_reason: r.get(3)?,
                            title: r.get(4)?,
                            mime: r.get(5)?,
                            modified: r.get(6)?,
                            hash: r.get(7)?,
                            shared: r.get(8)?,
                            protected: r.get(9)?,
                            locators: r.get(10)?,
                            title_user_edited: r.get(11)?,
                            text: r.get(12)?,
                            preview: r.get(13)?,
                            preview_sha: r.get(14)?,
                            preview_pending: r.get(15)?,
                        })
                    },
                )
                .optional()?;
            let trashed = row.presence.state == LinkState::Trashed;
            let (state_since, removed_reason) = if trashed {
                (row.presence.since, row.presence.reason.clone())
            } else {
                (0, String::new())
            };
            let locators_with = |base: &str, change: &Change<LinkFilen>| -> String {
                let mut list: Vec<Value> = serde_json::from_str(base).unwrap_or_default();
                match change {
                    Change::Keep => {}
                    Change::Clear => list.retain(|e| e["type"] != "filen"),
                    Change::Set(f) => {
                        list.retain(|e| e["type"] != "filen");
                        list.insert(0, json!({"type":"filen","uuid":f.uuid,"path":f.path}));
                    }
                }
                serde_json::to_string(&list).unwrap_or_else(|_| "[]".into())
            };
            match old {
                None => {
                    let (Some(content), Some(title)) = (&row.content, &row.title) else {
                        report.skipped += 1;
                        continue;
                    };
                    let foreign = content.foreign.as_ref();
                    if foreign.is_none() && !trashed && visible >= VISIBLE_LIMIT {
                        report.not_shown += 1;
                        continue;
                    }
                    let row_id = uuid::Uuid::new_v4().to_string();
                    let text = match &text_change {
                        Change::Set(text) if !content.protected => text.clone(),
                        _ => String::new(),
                    };
                    let preview = match (&item.preview, content.protected) {
                        (Change::Set(blob), false) => Some(blob.sha256.clone()),
                        _ => None,
                    };
                    tx.execute(
                        "INSERT INTO link_records(row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text,preview,state_since,removed_reason,preview_sha,preview_pending,origin,shared_by,foreign_generation) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18,?19,?20,?21,?22,?23)",
                        params![
                            row_id,
                            row.owner,
                            row.link_id,
                            row.kind,
                            title.title,
                            content.mime,
                            content.modified,
                            content.hash,
                            i64::from(content.shared),
                            i64::from(content.protected),
                            locators_with("[]", &item.filen),
                            // A partner's record is not checked against any file here: it is simply live.
                            if trashed {
                                "trashed"
                            } else if foreign.is_some() {
                                "live"
                            } else {
                                "unreachable"
                            },
                            i64::from(title.user_edited),
                            row.added_at,
                            text,
                            i64::from(preview.is_some()),
                            state_since,
                            removed_reason,
                            preview.clone().unwrap_or_default(),
                            i64::from(preview.is_some()),
                            if foreign.is_some() { "foreign" } else { "own" },
                            foreign.map(|f| f.shared_by.as_str()).unwrap_or(""),
                            foreign.map_or(0, |f| f.generation),
                        ],
                    )?;
                    tx.execute(
                        "INSERT INTO link_search VALUES(?1,?2,?3)",
                        params![row_id, title.title, text],
                    )?;
                    if !trashed && foreign.is_none() {
                        visible += 1;
                    }
                }
                Some(old) => {
                    // A row's origin never flips through sync: a content cell without `origin` cannot turn a shared copy into an own row, and a
                    // cell with `origin` cannot turn an own row into a shared copy. Such a cell is skipped (counted), the row stays as it is.
                    if let Some(c) = row.content.as_ref() {
                        if c.foreign.is_some() != (old.origin == "foreign") {
                            report.skipped += 1;
                            continue;
                        }
                    }
                    let was_trashed = old.state == "trashed";
                    // Origin follows the phone's content cell (phone-authoritative); without one the row keeps its own.
                    let (origin, shared_by, generation) = match row.content.as_ref() {
                        Some(c) => match &c.foreign {
                            Some(f) => ("foreign", f.shared_by.clone(), f.generation),
                            None => ("own", String::new(), 0),
                        },
                        None => (
                            if old.origin == "foreign" { "foreign" } else { "own" },
                            old.shared_by.clone(),
                            old.foreign_generation,
                        ),
                    };
                    let was_counted = old.origin == "own" && !was_trashed;
                    let now_counted = origin == "own" && !trashed;
                    if now_counted && !was_counted && visible >= VISIBLE_LIMIT {
                        report.not_shown += 1;
                        continue;
                    }
                    let (mime, modified, hash, shared, protected) = match &row.content {
                        Some(c) => (
                            c.mime.clone(),
                            c.modified,
                            c.hash.clone(),
                            i64::from(c.shared),
                            i64::from(c.protected),
                        ),
                        None => (
                            old.mime.clone(),
                            old.modified,
                            old.hash.clone(),
                            old.shared,
                            old.protected,
                        ),
                    };
                    let (title, edited) = match &row.title {
                        Some(t) => (t.title.clone(), i64::from(t.user_edited)),
                        None => (old.title.clone(), old.title_user_edited),
                    };
                    // A link that BECOMES protected on the phone keeps no searchable text or preview here.
                    let now_protected = old.protected == 0 && protected == 1;
                    let text = if now_protected {
                        String::new()
                    } else {
                        match &text_change {
                            Change::Keep => old.text.clone(),
                            Change::Clear => String::new(),
                            Change::Set(text) => text.clone(),
                        }
                    };
                    let (preview, sha, pending) = if now_protected {
                        if old.preview != 0 {
                            removals.push(old.row_id.clone());
                        }
                        (0, String::new(), 0)
                    } else {
                        match &item.preview {
                            Change::Keep => {
                                (old.preview, old.preview_sha.clone(), old.preview_pending)
                            }
                            Change::Clear => {
                                if old.preview != 0 {
                                    removals.push(old.row_id.clone());
                                }
                                (0, String::new(), 0)
                            }
                            Change::Set(blob) => {
                                if old.preview != 0
                                    && old.preview_sha == blob.sha256
                                    && old.preview_pending == 0
                                {
                                    (1, blob.sha256.clone(), 0)
                                } else {
                                    (1, blob.sha256.clone(), 1)
                                }
                            }
                        }
                    };
                    let (state, since, reason) = if trashed {
                        ("trashed".to_owned(), state_since, removed_reason)
                    } else if was_trashed {
                        // trashed -> live: a locally trashed row is visible again.
                        (
                            if origin == "foreign" { "live" } else { "unreachable" }.to_owned(),
                            0,
                            String::new(),
                        )
                    } else {
                        (
                            old.state.clone(),
                            old.state_since,
                            old.removed_reason.clone(),
                        )
                    };
                    visible += i64::from(now_counted) - i64::from(was_counted);
                    tx.execute(
                        "UPDATE link_records SET title=?1,mime=?2,modified=?3,hash=?4,shared=?5,protected=?6,locators=?7,state=?8,title_user_edited=?9,text=?10,preview=?11,state_since=?12,removed_reason=?13,preview_sha=?14,preview_pending=?15,origin=?17,shared_by=?18,foreign_generation=?19 WHERE row_id=?16",
                        params![
                            title,
                            mime,
                            modified,
                            hash,
                            shared,
                            protected,
                            locators_with(&old.locators, &item.filen),
                            state,
                            edited,
                            text,
                            preview,
                            since,
                            reason,
                            sha,
                            pending,
                            old.row_id,
                            origin,
                            shared_by,
                            generation,
                        ],
                    )?;
                    if origin == "foreign" {
                        // A shared copy has no file on this computer to remember.
                        tx.execute("DELETE FROM link_local_files WHERE row_id=?1", [&old.row_id])?;
                    }
                    tx.execute("DELETE FROM link_search WHERE row_id=?1", [&old.row_id])?;
                    tx.execute(
                        "INSERT INTO link_search VALUES(?1,?2,?3)",
                        params![old.row_id, title, text],
                    )?;
                }
            }
            if !item.retry.is_empty() {
                report.missing_blob += 1;
                retries.insert(item.identity.clone(), item.retry.clone());
            }
            done.insert(item.identity);
            report.applied += 1;
        }
        // The ONLY deletion: the phone said `gone`. Absence never deletes.
        for (owner, link_id) in gone {
            let id: Option<String> = tx
                .query_row(
                    "SELECT row_id FROM link_records WHERE owner=?1 AND link_id=?2",
                    params![owner, link_id],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(id) = id {
                tx.execute("DELETE FROM link_search WHERE row_id=?1", [&id])?;
                tx.execute("DELETE FROM link_records WHERE row_id=?1", [&id])?;
                tx.execute("DELETE FROM link_local_files WHERE row_id=?1", [&id])?;
                removals.push(id);
                report.deleted += 1;
            }
            done.insert(identity_of(&owner, &link_id));
        }
        let mut next = Cells::new();
        for (identity, group) in &new_groups {
            if done.contains(identity) {
                let mut group = group.clone();
                // A part whose blob is missing keeps its OLD value so the link is planned again later.
                for suffix in retries.get(identity).into_iter().flatten() {
                    let key = format!("{identity}{suffix}");
                    match old_groups.get(identity).and_then(|g| g.get(&key)) {
                        Some(previous) => group.insert(key, previous.clone()),
                        None => group.remove(&key),
                    };
                }
                next.extend(group);
            } else if let Some(previous) = old_groups.get(identity) {
                next.extend(previous.clone());
            }
        }
        write_projection(tx, &next)?;
        Ok(LinkApplied { removals, report })
    }

    /// After the commit: remove the preview files of deleted or cleared rows, then install pending previews.
    /// Best effort: a link never fails the import. Returns how many previews could not be installed (the rows stay
    /// pending); only an injected probe stop is an error.
    pub(crate) fn finish_link_apply(&mut self, removals: &[String]) -> Result<usize> {
        for id in removals {
            let _ = fs::remove_file(self.root.join(crate::links::preview_file(id)));
            let _ = fs::remove_file(self.root.join("links").join(format!(".link-{id}.part")));
        }
        Ok(self.install_pending_link_previews()?.1)
    }

    /// Writes `links/<row_id>.pdf` from the kept blob for rows marked `preview_pending=1` only (never hashes every
    /// preview). A blob that is missing or does not match leaves the row pending ("preview not ready"); a kept blob
    /// whose bytes do not match its name is removed so it is not copied again on every install. A file-system
    /// problem leaves the row pending and is counted. Returns (installed, failed).
    pub(crate) fn install_pending_link_previews(&mut self) -> Result<(usize, usize)> {
        let pending: Vec<(String, String)> = {
            let Ok(mut statement) = self
                .db
                .prepare("SELECT row_id,preview_sha FROM link_records WHERE preview_pending=1")
            else {
                return Ok((0, 0));
            };
            let Ok(rows) = statement.query_map([], |r| Ok((r.get(0)?, r.get(1)?))) else {
                return Ok((0, 0));
            };
            rows.flatten().collect()
        };
        if pending.is_empty() {
            return Ok((0, 0));
        }
        let links = self.root.join("links");
        if private_dir(&links).is_err() {
            return Ok((0, pending.len()));
        }
        let (mut installed, mut failed) = (0, 0);
        for (row_id, sha) in pending {
            match self.install_preview_row(&links, &row_id, &sha)? {
                Preview::Installed => installed += 1,
                Preview::NotReady => {}
                Preview::Failed => failed += 1,
            }
        }
        if failed > 0 {
            // No key, path or text: only a fixed note that previews are waiting.
            let _ =
                crate::library::put_meta(&self.db, "link_preview_error", "preview install failed");
        } else {
            let _ = self
                .db
                .execute("DELETE FROM meta WHERE key='link_preview_error'", []);
        }
        Ok((installed, failed))
    }

    fn install_preview_row(&mut self, links: &Path, row_id: &str, sha: &str) -> Result<Preview> {
        let control = stack_archive::Control::default();
        let source = self.root.join("sync_blobs").join(sha);
        let Ok(mut input) = stack_archive::read_regular(&source) else {
            return Ok(Preview::NotReady);
        };
        let Ok(length) = input.metadata().map(|m| m.len()) else {
            return Ok(Preview::Failed);
        };
        if length == 0 || length > wire::MAX_PREVIEW_BYTES {
            return Ok(Preview::NotReady);
        }
        let temporary = links.join(format!(".link-{row_id}.part"));
        let _ = fs::remove_file(&temporary);
        let Ok(mut out) = OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&temporary)
        else {
            return Ok(Preview::Failed);
        };
        let hash = match stack_archive::transfer(&mut input, &mut out, length, &control) {
            Ok(hash) => hash,
            Err(_) => {
                drop(out);
                let _ = fs::remove_file(&temporary);
                return Ok(Preview::Failed);
            }
        };
        if hash != sha {
            drop(out);
            let _ = fs::remove_file(&temporary);
            // The kept blob is not what its name says: drop it (later a blob with that sha can be kept again).
            let _ = fs::remove_file(&source);
            return Ok(Preview::NotReady);
        }
        (self.probe)("link_preview_temp")?;
        let target = links.join(format!("{row_id}.pdf"));
        let placed = out.sync_all().is_ok() && {
            drop(out);
            fs::rename(&temporary, &target).is_ok()
        };
        if !placed {
            let _ = fs::remove_file(&temporary);
            return Ok(Preview::Failed);
        }
        if stack_archive::sync_directory(links).is_err() {
            return Ok(Preview::Failed);
        }
        (self.probe)("link_preview_renamed")?;
        match self.db.execute(
            "UPDATE link_records SET preview_pending=0 WHERE row_id=?1 AND preview_sha=?2 AND preview_pending=1",
            params![row_id, sha],
        ) {
            Ok(n) => Ok(if n > 0 { Preview::Installed } else { Preview::NotReady }),
            Err(_) => Ok(Preview::Failed),
        }
    }

    /// Startup: remove this module's temp files and preview files whose row is gone or has no preview, then finish
    /// pending previews. Touches only `preview_pending=1` rows; restored rows with an empty `preview_sha` are never
    /// read. Best effort: a repair problem must not stop the library from opening.
    pub(crate) fn recover_link_previews(&mut self) -> Result<()> {
        let rows: BTreeMap<String, i64> = {
            let mut statement = self.db.prepare("SELECT row_id,preview FROM link_records")?;
            statement
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?
                .collect::<std::result::Result<_, _>>()?
        };
        let links = self.root.join("links");
        if let Ok(entries) = fs::read_dir(&links) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                let orphan = if name.starts_with(".link-") && name.ends_with(".part") {
                    true
                } else {
                    // A crash can leave a file for a row that has no preview (cleared or protected).
                    name.strip_suffix(".pdf").is_some_and(|stem| {
                        uuid_form(stem) && rows.get(stem).is_none_or(|preview| *preview == 0)
                    })
                };
                if orphan {
                    let _ = fs::remove_file(entry.path());
                }
            }
        }
        let _ = self.install_pending_link_previews();
        Ok(())
    }
}

enum Preview {
    Installed,
    NotReady,
    Failed,
}
