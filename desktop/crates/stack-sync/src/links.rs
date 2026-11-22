//! Link records in the sync graph (slice 6 design, `docs/STACK_LINK_SLICE6_DESIGN.md`).
//!
//! Three layers, kept apart on purpose (design section 2, "writers refuse,
//! receivers skip"):
//!
//! * [`validate_cell`] is the RECEIVE-side check run by `graph::validate_changes`.
//!   It only checks key shape, arity, exact field set, JSON types, the safe
//!   integer range and string bounds needed for memory safety. It never
//!   rejects for a semantic reason, because one rejected cell would kill the
//!   whole bundle and every later session. It deliberately does NOT use
//!   `validate_primitives` (Opus finding M4).
//! * [`writer_self_check`] / [`link_cells`] are the WRITER side: every semantic
//!   limit. A writer never publishes what the receiver would reject.
//! * [`project_links`] applies the semantic limits again on receive and only
//!   skips (and counts); it never fails and never deletes by absence.
use crate::{Error, Result, graph::Cells};
use serde_json::{Value, json};
use stack_archive::validation::{self, utf16_len};
use std::collections::{BTreeMap, BTreeSet};

/// Largest integer every peer reads exactly (`MAX_SEQUENCE`, 2^53 - 1).
pub const MAX_SAFE: i64 = 9_007_199_254_740_991;
pub const MAX_LINKS: usize = 1000;
pub const MAX_TITLE_UNITS: usize = 200;
pub const MAX_OWNER_UNITS: usize = 200;
pub const MAX_FILEN_UNITS: usize = 1024;
pub const MAX_TEXT_BYTES: u64 = 1_048_576;
pub const MAX_PREVIEW_BYTES: u64 = 33_554_432;
/// Distinct link blob bytes referenced by current link cells, per device.
pub const DEVICE_BLOB_BUDGET: u64 = 2 * 1024 * 1024 * 1024;
/// Link blob bytes of unacknowledged operations plus the new operation.
pub const UNACKED_BLOB_BUDGET: u64 = 6 * 1024 * 1024 * 1024;

// Memory-safety bounds of the receive side (not semantic limits).
const RECV_OWNER_UNITS: usize = 1024;
const RECV_KIND_UNITS: usize = 64;
const RECV_SHORT_UNITS: usize = 64;
const RECV_MIME_UNITS: usize = 100;
const RECV_PRESENCE_UNITS: usize = 32;
const RECV_REPLICA_UNITS: usize = 128;

pub const PREFIX: &str = "link_records/";
const MARKER_PREFIX: &str = "enabled/";
/// Cell suffixes in the order they are written. `""` is the identity cell.
pub const SUFFIXES: [&str; 7] = [
    "",
    "/content",
    "/text",
    "/preview",
    "/title",
    "/filen",
    "/presence",
];
const REASONS: [&str; 5] = ["", "deleted", "trashed", "user", "forgotten"];

// ---------------------------------------------------------------------------
// Keys
// ---------------------------------------------------------------------------

/// `link_records/["<owner>","<link_id>"]` (canonical JSON array, no suffix).
pub fn link_key(owner: &str, link_id: &str) -> String {
    format!(
        "{PREFIX}{}",
        serde_json::to_string(&[owner, link_id]).expect("strings serialize")
    )
}

/// `link_records/enabled/<replica>`.
pub fn marker_key(replica: &str) -> String {
    format!("{PREFIX}{MARKER_PREFIX}{replica}")
}

/// The marker cell value, `{"since": ms}`. `since` above 2^53-1 is refused.
pub fn marker_value(since: i64) -> Result<Value> {
    if !(0..=MAX_SAFE).contains(&since) {
        return Err(Error::Invalid("link marker since is out of range"));
    }
    Ok(json!({ "since": since }))
}

/// Replica ids whose enable marker exists (non-null) in the current values.
pub fn enabled_replicas(cells: &Cells) -> BTreeSet<String> {
    cells
        .iter()
        .filter(|(_, value)| !value.is_null())
        .filter_map(|(key, _)| key.strip_prefix(PREFIX)?.strip_prefix(MARKER_PREFIX))
        .map(str::to_owned)
        .collect()
}

/// Splits what follows `link_records/` into `([owner, link_id], suffix)`.
/// The suffix is `""` for the identity cell. Lenient: no semantic checks.
fn split_key(rest: &str) -> Option<([String; 2], &str)> {
    if !rest.starts_with('[') {
        return None;
    }
    let (raw, suffix) = if rest.ends_with(']') {
        (rest, "")
    } else {
        let at = rest.rfind("]/")?;
        (&rest[..=at], &rest[at + 1..])
    };
    let ids: Vec<Value> = serde_json::from_str(raw).ok()?;
    if serde_json::to_string(&ids).ok()? != raw || ids.len() != 2 {
        return None;
    }
    Some((
        [ids[0].as_str()?.to_owned(), ids[1].as_str()?.to_owned()],
        suffix,
    ))
}

// ---------------------------------------------------------------------------
// Receive-side validator
// ---------------------------------------------------------------------------

fn bad<T>(message: &'static str) -> Result<T> {
    Err(Error::Invalid(message))
}

fn int(value: &Value, minimum: i64, maximum: i64) -> Result<i64> {
    match value.as_i64() {
        Some(n) if (minimum..=maximum).contains(&n) => Ok(n),
        Some(_) => bad("link integer outside its range"),
        None => bad("link integer has another type"),
    }
}

fn bounded(value: &Value, units: usize) -> Result<&str> {
    let text = value
        .as_str()
        .ok_or(Error::Invalid("link text field has another type"))?;
    if utf16_len(text) > units {
        return bad("link text field exceeds its memory bound");
    }
    Ok(text)
}

/// Receive-side check of one `link_records/` cell. `rest` is the key after
/// `link_records/`. Null is accepted for every suffix except identity and
/// presence; the marker is never null.
pub fn validate_cell(rest: &str, value: &Value) -> Result<()> {
    if let Some(replica) = rest.strip_prefix(MARKER_PREFIX) {
        if replica.is_empty() || replica.encode_utf16().count() > RECV_REPLICA_UNITS {
            return bad("invalid link marker replica");
        }
        validation::keys(value, "since")?;
        int(&value["since"], 0, MAX_SAFE)?;
        return Ok(());
    }
    let (ids, suffix) = split_key(rest).ok_or(Error::Invalid("invalid link identity key"))?;
    if !SUFFIXES.contains(&suffix) {
        return bad("unsupported link cell");
    }
    if ids[0].is_empty() || utf16_len(&ids[0]) > RECV_OWNER_UNITS {
        return bad("link owner exceeds its memory bound");
    }
    stack_archive::uuid(&ids[1])?;
    if value.is_null() {
        if matches!(suffix, "" | "/presence") {
            return bad("link identity and presence cannot be null");
        }
        return Ok(());
    }
    match suffix {
        "" => {
            validation::keys(value, "owner,link_id,kind,added_at")?;
            bounded(&value["kind"], RECV_KIND_UNITS)?;
            int(&value["added_at"], 0, MAX_SAFE)?;
            if value["owner"] != ids[0] || value["link_id"] != ids[1] {
                return bad("link identity differs from its key");
            }
        }
        "/content" => {
            validation::keys(value, "hash,mime,modified,shared,protected")?;
            bounded(&value["hash"], RECV_SHORT_UNITS)?;
            bounded(&value["mime"], RECV_MIME_UNITS)?;
            int(&value["modified"], 0, MAX_SAFE)?;
            int(&value["shared"], 0, 1)?;
            int(&value["protected"], 0, 1)?;
        }
        "/text" | "/preview" => {
            validation::keys(value, "sha256,length")?;
            bounded(&value["sha256"], RECV_SHORT_UNITS)?;
            let limit = if suffix == "/text" {
                MAX_TEXT_BYTES
            } else {
                MAX_PREVIEW_BYTES
            };
            int(&value["length"], 1, limit as i64)?;
        }
        "/title" => {
            validation::keys(value, "title,title_user_edited")?;
            bounded(&value["title"], MAX_TITLE_UNITS)?;
            int(&value["title_user_edited"], 0, 1)?;
        }
        "/filen" => {
            validation::keys(value, "uuid,path")?;
            bounded(&value["uuid"], RECV_SHORT_UNITS)?;
            bounded(&value["path"], MAX_FILEN_UNITS)?;
        }
        _ => {
            validation::keys(value, "state,reason,since")?;
            bounded(&value["state"], RECV_PRESENCE_UNITS)?;
            bounded(&value["reason"], RECV_PRESENCE_UNITS)?;
            int(&value["since"], 0, MAX_SAFE)?;
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Semantic checks shared by the writer and the projection
// ---------------------------------------------------------------------------

/// `[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+`, at most 200 UTF-16 units.
fn valid_owner(value: &str) -> bool {
    if value.len() > MAX_OWNER_UNITS {
        return false;
    }
    let parts: Vec<_> = value.split('.').collect();
    parts.len() >= 2
        && parts.iter().all(|part| {
            let b = part.as_bytes();
            !b.is_empty()
                && b[0].is_ascii_lowercase()
                && b.iter()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'_')
        })
}

fn valid_link_id(value: &str) -> bool {
    stack_archive::uuid(value).is_ok()
}

/// `[a-z]{1,16}`
fn valid_kind(value: &str) -> bool {
    (1..=16).contains(&value.len()) && value.bytes().all(|b| b.is_ascii_lowercase())
}

/// `[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*`, at most 100 chars.
fn valid_mime(value: &str) -> bool {
    if value.len() > 100 {
        return false;
    }
    let mut parts = value.split('/');
    let (Some(a), Some(b), None) = (parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    [a, b].iter().all(|part| {
        let bytes = part.as_bytes();
        !bytes.is_empty()
            && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
            && bytes.iter().all(|c| {
                c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'.' | b'+' | b'-')
            })
    })
}

fn valid_sha(value: &str) -> bool {
    validation::sha(value).is_ok()
}

/// `LinkRecord.free` semantics (not `clean_name`): non-empty, at most `max`
/// UTF-16 units, no control or direction-changing character. Surrounding
/// spaces are allowed. (An unpaired surrogate cannot exist in a Rust string.)
fn valid_free(value: &str, max: usize) -> bool {
    if value.is_empty() || utf16_len(value) > max {
        return false;
    }
    !value.chars().any(|c| {
        let c = u32::from(c);
        c < 0x20
            || (0x7f..=0x9f).contains(&c)
            || (0x202a..=0x202e).contains(&c)
            || (0x2066..=0x2069).contains(&c)
            || matches!(c, 0x200e | 0x200f | 0x061c)
    })
}

fn valid_filen(uuid: &str, path: &str) -> bool {
    valid_link_id(uuid) && !path.is_empty() && utf16_len(path) <= MAX_FILEN_UNITS
}

/// The text rule of the writer: empty maps to a null cell (`Ok(None)`), over
/// 1,048,576 UTF-8 bytes or a NUL skips the cell (`Err(())`).
fn text_cell(text: &str) -> std::result::Result<Option<Value>, ()> {
    if text.is_empty() {
        return Ok(None);
    }
    if text.len() as u64 > MAX_TEXT_BYTES || text.contains('\0') {
        return Err(());
    }
    Ok(Some(
        json!({"sha256": stack_archive::digest(text.as_bytes()), "length": text.len()}),
    ))
}

fn preview_cell(blob: &LinkBlob) -> Option<Value> {
    (valid_sha(&blob.sha256) && (1..=MAX_PREVIEW_BYTES).contains(&blob.length))
        .then(|| json!({"sha256": blob.sha256, "length": blob.length}))
}

// ---------------------------------------------------------------------------
// Writer self-check
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinkState {
    Live,
    Trashed,
    Gone,
}

impl LinkState {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "live" => Some(Self::Live),
            "trashed" => Some(Self::Trashed),
            "gone" => Some(Self::Gone),
            _ => None,
        }
    }
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Live => "live",
            Self::Trashed => "trashed",
            Self::Gone => "gone",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkPresence {
    pub state: LinkState,
    pub reason: String,
    pub since: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkBlob {
    pub sha256: String,
    pub length: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkFilen {
    pub uuid: String,
    pub path: String,
}

/// One local link row as the writer sees it (phone `link_records` + `link_search`,
/// or a desktop row). `text` is the searchable text itself; `preview` is the
/// digest of the preview file (already hashed by the caller).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkInput {
    pub owner: String,
    pub link_id: String,
    pub kind: String,
    pub added_at: i64,
    pub hash: String,
    pub mime: String,
    pub modified: i64,
    pub shared: bool,
    pub protected: bool,
    pub title: String,
    pub title_user_edited: bool,
    /// Filen locators in locator order; the FIRST is used.
    pub filen: Vec<LinkFilen>,
    pub text: Option<String>,
    pub preview: Option<LinkBlob>,
    pub presence: LinkPresence,
    /// Set by [`trim_blobs_to_budget`]: the cell is not emitted at all (not even
    /// null) so a previously synced blob cell is left alone.
    pub omit_text: bool,
    pub omit_preview: bool,
}

impl LinkInput {
    pub fn key(&self) -> String {
        link_key(&self.owner, &self.link_id)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WriterOutcome {
    /// Every cell written.
    Emitted,
    /// The row is written but at least one text/preview/filen cell was skipped.
    CellSkipped,
    /// title/owner/modified/added_at/since out of range or another semantic
    /// failure: no cell of the row is written (marked "not synced").
    RowRefused,
}

#[derive(Clone, Debug, PartialEq)]
pub struct WriterResult {
    pub outcome: WriterOutcome,
    pub reason: Option<&'static str>,
    /// suffix (`""`, `"/content"`, ...) -> value (`Null` for a null cell).
    pub cells: BTreeMap<String, Value>,
    /// Suffixes whose cell was skipped (absent from `cells`).
    pub skipped: Vec<&'static str>,
}

impl WriterResult {
    fn refused(reason: &'static str) -> Self {
        Self {
            outcome: WriterOutcome::RowRefused,
            reason: Some(reason),
            cells: BTreeMap::new(),
            skipped: Vec::new(),
        }
    }
}

/// The writer self-check of one row. `captured_before` is the number of
/// live/trashed (not `gone`) identities already emitted before this row; the
/// 1001st is refused.
pub fn writer_self_check(row: &LinkInput, captured_before: usize) -> WriterResult {
    let range = 0..=MAX_SAFE;
    if !valid_owner(&row.owner) {
        return WriterResult::refused("owner");
    }
    if !valid_link_id(&row.link_id) {
        return WriterResult::refused("link_id");
    }
    if !valid_kind(&row.kind) {
        return WriterResult::refused("kind");
    }
    if !range.contains(&row.added_at) {
        return WriterResult::refused("added_at");
    }
    let gone = row.presence.state == LinkState::Gone;
    if !gone && captured_before >= MAX_LINKS {
        return WriterResult::refused("link count");
    }
    let presence = match row.presence.state {
        LinkState::Live => json!({"state": "live", "reason": "", "since": 0}),
        state => {
            if !range.contains(&row.presence.since) {
                return WriterResult::refused("presence since");
            }
            if !REASONS.contains(&row.presence.reason.as_str()) {
                return WriterResult::refused("presence reason");
            }
            json!({"state": state.as_str(), "reason": row.presence.reason, "since": row.presence.since})
        }
    };
    let mut cells = BTreeMap::new();
    let mut skipped = Vec::new();
    cells.insert(
        String::new(),
        json!({"owner": row.owner, "link_id": row.link_id, "kind": row.kind, "added_at": row.added_at}),
    );
    if gone {
        for suffix in ["/content", "/text", "/preview", "/title", "/filen"] {
            cells.insert(suffix.to_owned(), Value::Null);
        }
        cells.insert("/presence".to_owned(), presence);
        return WriterResult {
            outcome: WriterOutcome::Emitted,
            reason: None,
            cells,
            skipped,
        };
    }
    if !valid_sha(&row.hash) {
        return WriterResult::refused("hash");
    }
    if !valid_mime(&row.mime) {
        return WriterResult::refused("mime");
    }
    if !range.contains(&row.modified) {
        return WriterResult::refused("modified");
    }
    if !valid_free(&row.title, MAX_TITLE_UNITS) {
        return WriterResult::refused("title");
    }
    cells.insert(
        "/content".to_owned(),
        json!({"hash": row.hash, "mime": row.mime, "modified": row.modified,
               "shared": u8::from(row.shared), "protected": u8::from(row.protected)}),
    );
    cells.insert(
        "/title".to_owned(),
        json!({"title": row.title, "title_user_edited": u8::from(row.title_user_edited)}),
    );
    // Protected links sync metadata only: no text and no preview cell (decision 6).
    if row.protected {
        cells.insert("/text".to_owned(), Value::Null);
        cells.insert("/preview".to_owned(), Value::Null);
    } else {
        if row.omit_text {
            skipped.push("/text");
        } else {
            match row.text.as_deref().map_or(Ok(None), text_cell) {
                Ok(cell) => {
                    cells.insert("/text".to_owned(), cell.unwrap_or(Value::Null));
                }
                Err(()) => skipped.push("/text"),
            }
        }
        if row.omit_preview {
            skipped.push("/preview");
        } else {
            match row.preview.as_ref().map(preview_cell) {
                None => {
                    cells.insert("/preview".to_owned(), Value::Null);
                }
                Some(Some(cell)) => {
                    cells.insert("/preview".to_owned(), cell);
                }
                Some(None) => skipped.push("/preview"),
            }
        }
    }
    match row.filen.first() {
        None => {
            cells.insert("/filen".to_owned(), Value::Null);
        }
        Some(f) if valid_filen(&f.uuid, &f.path) => {
            cells.insert("/filen".to_owned(), json!({"uuid": f.uuid, "path": f.path}));
        }
        Some(_) => skipped.push("/filen"),
    }
    cells.insert("/presence".to_owned(), presence);
    WriterResult {
        outcome: if skipped.is_empty() {
            WriterOutcome::Emitted
        } else {
            WriterOutcome::CellSkipped
        },
        reason: None,
        cells,
        skipped,
    }
}

/// Result of [`link_cells`].
#[derive(Debug, Default)]
pub struct LinkCells {
    /// Full graph keys (`link_records/[...]` + suffix) -> values.
    pub cells: Cells,
    /// Identity keys of refused rows ("not synced") with the reason.
    pub refused: Vec<(String, &'static str)>,
    /// (identity key, suffix) of skipped cells.
    pub skipped: Vec<(String, &'static str)>,
}

/// Builds the cells of `rows` in the given order (the caller applies the
/// inclusion order of the budget). `gone` rows never count towards 1000.
pub fn link_cells(rows: &[LinkInput]) -> LinkCells {
    let mut out = LinkCells::default();
    let mut captured = 0usize;
    let mut seen = BTreeSet::new();
    for row in rows {
        let key = row.key();
        if !seen.insert(key.clone()) {
            out.refused.push((key, "duplicate identity"));
            continue;
        }
        let result = writer_self_check(row, captured);
        if result.outcome == WriterOutcome::RowRefused {
            out.refused.push((key, result.reason.unwrap_or("refused")));
            continue;
        }
        if row.presence.state != LinkState::Gone {
            captured += 1;
        }
        for (suffix, value) in result.cells {
            out.cells.insert(format!("{key}{suffix}"), value);
        }
        for suffix in result.skipped {
            out.skipped.push((key.clone(), suffix));
        }
    }
    out
}

// ---------------------------------------------------------------------------
// Byte budget (pure helpers; design section 2 "Total byte budget")
// ---------------------------------------------------------------------------

/// Sum of the lengths of DISTINCT digests (two links may share a blob).
pub fn distinct_blob_bytes<'a>(blobs: impl IntoIterator<Item = (&'a str, u64)>) -> u64 {
    let mut seen = BTreeSet::new();
    let mut total = 0u64;
    for (sha, length) in blobs {
        if seen.insert(sha) {
            total = total.saturating_add(length);
        }
    }
    total
}

/// True if `extra` more distinct bytes still fit the 2 GiB per-device budget.
pub fn fits_device_budget(current: u64, extra: u64) -> bool {
    current.saturating_add(extra) <= DEVICE_BLOB_BUDGET
}

/// True if an operation adding `new_bytes` must be DEFERRED: the bytes of all
/// unacknowledged operations plus the new blobs exceed 6 GiB. The enable op on
/// the phone is never deferred; the caller does not ask for it.
pub fn must_defer(unacked_bytes: u64, new_bytes: u64) -> bool {
    unacked_bytes.saturating_add(new_bytes) > UNACKED_BLOB_BUDGET
}

/// Applies the 2 GiB budget to `rows` in the deterministic inclusion order
/// (links whose blobs were already synced first, then `added_at` ascending,
/// then key) and sets `omit_text` / `omit_preview` on the cells that do not
/// fit. Metadata is never trimmed. Returns the number of omitted cells.
/// `already_synced` holds identity keys ([`link_key`]).
pub fn trim_blobs_to_budget(rows: &mut [LinkInput], already_synced: &BTreeSet<String>) -> usize {
    let mut order: Vec<usize> = (0..rows.len()).collect();
    order.sort_by_key(|&i| {
        let key = rows[i].key();
        (!already_synced.contains(&key), rows[i].added_at, key)
    });
    let mut used = BTreeSet::<String>::new();
    let mut total = 0u64;
    let mut omitted = 0;
    for i in order {
        let row = &mut rows[i];
        row.omit_text = false;
        row.omit_preview = false;
        if row.protected || row.presence.state == LinkState::Gone {
            continue;
        }
        let text = match row.text.as_deref().map(text_cell) {
            Some(Ok(Some(cell))) => Some((
                cell["sha256"].as_str().unwrap_or_default().to_owned(),
                cell["length"].as_u64().unwrap_or(0),
            )),
            _ => None,
        };
        let preview = row
            .preview
            .as_ref()
            .filter(|b| preview_cell(b).is_some())
            .map(|b| (b.sha256.clone(), b.length));
        for (slot, blob) in [(true, text), (false, preview)] {
            let Some((sha, length)) = blob else { continue };
            let cost = if used.contains(&sha) { 0 } else { length };
            if fits_device_budget(total, cost) {
                total += cost;
                used.insert(sha);
            } else if slot {
                row.omit_text = true;
                omitted += 1;
            } else {
                row.omit_preview = true;
                omitted += 1;
            }
        }
    }
    omitted
}

// ---------------------------------------------------------------------------
// Link blob references (separate from `files/` refs)
// ---------------------------------------------------------------------------

/// digest -> the lengths declared by link text/preview cells. Keyed by digest
/// only (never a path), so a link reference can never become a file path.
pub type LinkBlobRefs = BTreeMap<String, BTreeSet<u64>>;

/// Collects the non-null `/text` and `/preview` cells of operation changes.
/// Malformed cells are ignored (the validator already rejected them).
pub fn link_blob_refs<'a>(
    changes: impl IntoIterator<Item = (&'a String, &'a Value)>,
) -> LinkBlobRefs {
    let mut refs = LinkBlobRefs::new();
    for (key, value) in changes {
        let Some(rest) = key.strip_prefix(PREFIX) else {
            continue;
        };
        let Some((_, suffix)) = split_key(rest) else {
            continue;
        };
        if !matches!(suffix, "/text" | "/preview") {
            continue;
        }
        if let (Some(sha), Some(length)) = (value["sha256"].as_str(), value["length"].as_u64())
            && valid_sha(sha)
        {
            refs.entry(sha.to_owned()).or_default().insert(length);
        }
    }
    refs
}

/// True when a blob declared in the bundle header matches a link cell: same
/// digest and the cell's length equals the declared length. A 256 MiB blob
/// therefore cannot hide behind a small cell. A blob that is not referenced by
/// a link must be matched against the `files/` refs by the caller (union check).
pub fn link_blob_declared(refs: &LinkBlobRefs, sha256: &str, length: u64) -> bool {
    refs.get(sha256)
        .is_some_and(|lengths| lengths.contains(&length))
}

// ---------------------------------------------------------------------------
// Receive-side projection
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkContent {
    pub hash: String,
    pub mime: String,
    pub modified: i64,
    pub shared: bool,
    pub protected: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkTitle {
    pub title: String,
    pub user_edited: bool,
}

/// A link that passed every semantic check. Cells that are absent or null are
/// `None` (the caller keeps what it has). Presence absent = live (since 0).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LinkRow {
    pub owner: String,
    pub link_id: String,
    pub kind: String,
    pub added_at: i64,
    pub content: Option<LinkContent>,
    pub text: Option<LinkBlob>,
    pub preview: Option<LinkBlob>,
    pub title: Option<LinkTitle>,
    pub filen: Option<LinkFilen>,
    /// `Live` or `Trashed` only; `Gone` is reported in [`LinkProjection::gone`].
    pub presence: LinkPresence,
    /// Protected: text and preview cells were ignored (decision 6).
    pub metadata_only: bool,
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct LinkProjection {
    /// Links to insert or update, in key order.
    pub rows: Vec<LinkRow>,
    /// `(owner, link_id)` of links whose presence is `gone`: the only case in
    /// which the caller deletes a row. Never inferred from absence.
    pub gone: Vec<(String, String)>,
    /// Links that were valid on the wire but failed a semantic limit.
    pub skipped: usize,
    /// Links skipped because one of their cells is conflicted.
    pub conflicted: usize,
}

fn parse_blob(value: &Value, limit: u64) -> Option<LinkBlob> {
    let sha = value["sha256"].as_str()?;
    let length = value["length"].as_u64()?;
    (valid_sha(sha) && (1..=limit).contains(&length)).then(|| LinkBlob {
        sha256: sha.to_owned(),
        length,
    })
}

fn parse_link(
    owner: &str,
    link_id: &str,
    group: &BTreeMap<&str, &Value>,
) -> Option<std::result::Result<LinkRow, ()>> {
    // None = skip. Some(Err(())) = gone. Some(Ok(row)) = project.
    let identity = group.get("")?;
    if identity.is_null()
        || identity["owner"] != owner
        || identity["link_id"] != link_id
        || !valid_owner(owner)
        || !valid_link_id(link_id)
    {
        return None;
    }
    let kind = identity["kind"].as_str().filter(|k| valid_kind(k))?;
    let added_at = identity["added_at"]
        .as_i64()
        .filter(|n| (0..=MAX_SAFE).contains(n))?;
    let present = |suffix: &str| group.get(suffix).copied().filter(|v| !v.is_null());
    let presence = match present("/presence") {
        None => LinkPresence {
            state: LinkState::Live,
            reason: String::new(),
            since: 0,
        },
        Some(p) => {
            let state = LinkState::parse(p["state"].as_str()?)?;
            let reason = p["reason"].as_str()?;
            if !REASONS.contains(&reason) {
                return None;
            }
            let since = p["since"].as_i64().filter(|n| (0..=MAX_SAFE).contains(n))?;
            LinkPresence {
                state,
                reason: reason.to_owned(),
                since,
            }
        }
    };
    if presence.state == LinkState::Gone {
        return Some(Err(()));
    }
    let content = match present("/content") {
        None => None,
        Some(c) => {
            let hash = c["hash"].as_str().filter(|h| valid_sha(h))?;
            let mime = c["mime"].as_str().filter(|m| valid_mime(m))?;
            let modified = c["modified"]
                .as_i64()
                .filter(|n| (0..=MAX_SAFE).contains(n))?;
            Some(LinkContent {
                hash: hash.to_owned(),
                mime: mime.to_owned(),
                modified,
                shared: c["shared"].as_i64().filter(|n| (0..=1).contains(n))? == 1,
                protected: c["protected"].as_i64().filter(|n| (0..=1).contains(n))? == 1,
            })
        }
    };
    let metadata_only = content.as_ref().is_some_and(|c| c.protected);
    let title = match present("/title") {
        None => None,
        Some(t) => Some(LinkTitle {
            title: t["title"]
                .as_str()
                .filter(|s| valid_free(s, MAX_TITLE_UNITS))?
                .to_owned(),
            user_edited: t["title_user_edited"]
                .as_i64()
                .filter(|n| (0..=1).contains(n))?
                == 1,
        }),
    };
    let filen = match present("/filen") {
        None => None,
        Some(f) => {
            let (uuid, path) = (f["uuid"].as_str()?, f["path"].as_str()?);
            // No uuid-shape check on a received Filen locator (settled).
            if !valid_free(path, MAX_FILEN_UNITS) {
                return None;
            }
            Some(LinkFilen {
                uuid: uuid.to_owned(),
                path: path.to_owned(),
            })
        }
    };
    let text = match present("/text") {
        None => None,
        Some(t) => Some(parse_blob(t, MAX_TEXT_BYTES)?),
    };
    let preview = match present("/preview") {
        None => None,
        Some(p) => Some(parse_blob(p, MAX_PREVIEW_BYTES)?),
    };
    Some(Ok(LinkRow {
        owner: owner.to_owned(),
        link_id: link_id.to_owned(),
        kind: kind.to_owned(),
        added_at,
        content,
        text: if metadata_only { None } else { text },
        preview: if metadata_only { None } else { preview },
        title,
        filen,
        presence,
        metadata_only,
    }))
}

/// Projects the link cells of the current values. `conflicted` holds the
/// conflicted cell keys of the kernel (any key; only `link_records/` ones
/// matter): every link with a conflicted cell is skipped (provisional values,
/// M8). A link failing a semantic limit is skipped and counted; the library
/// projection never fails because of a link. Nothing is ever deleted by
/// absence: only a `gone` presence is reported in `gone`.
pub fn project_links(cells: &Cells, conflicted: &BTreeSet<String>) -> LinkProjection {
    let mut groups: BTreeMap<[String; 2], BTreeMap<&str, &Value>> = BTreeMap::new();
    for (key, value) in cells {
        let Some(rest) = key.strip_prefix(PREFIX) else {
            continue;
        };
        if rest.starts_with(MARKER_PREFIX) {
            continue;
        }
        if let Some((ids, suffix)) = split_key(rest)
            && SUFFIXES.contains(&suffix)
        {
            groups.entry(ids).or_default().insert(suffix, value);
        }
    }
    let conflicted: BTreeSet<[String; 2]> = conflicted
        .iter()
        .filter_map(|key| {
            let rest = key.strip_prefix(PREFIX)?;
            (!rest.starts_with(MARKER_PREFIX))
                .then(|| split_key(rest))
                .flatten()
                .map(|(ids, _)| ids)
        })
        .collect();
    let mut out = LinkProjection::default();
    for (ids, group) in groups {
        if group.values().all(|v| v.is_null()) {
            continue;
        }
        if conflicted.contains(&ids) {
            out.conflicted += 1;
            continue;
        }
        match parse_link(&ids[0], &ids[1], &group) {
            None => out.skipped += 1,
            Some(Err(())) => {
                let [owner, link_id] = ids;
                out.gone.push((owner, link_id));
            }
            Some(Ok(row)) => out.rows.push(row),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const SHA_A: &str = "abababababababababababababababababababababababababababababababab";
    const SHA_B: &str = "cdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcdcd";
    const ID: &str = "30000000-0000-0000-0000-000000000001";
    const OWNER: &str = "org.stackdocs.test";
    const GIB: u64 = 1024 * 1024 * 1024;

    fn row(n: u32) -> LinkInput {
        LinkInput {
            owner: OWNER.into(),
            link_id: format!("30000000-0000-0000-0000-{n:012x}"),
            kind: "pdf".into(),
            added_at: i64::from(n),
            hash: SHA_A.into(),
            mime: "application/pdf".into(),
            modified: 1,
            shared: false,
            protected: false,
            title: "t".into(),
            title_user_edited: false,
            filen: vec![],
            text: None,
            preview: None,
            presence: LinkPresence {
                state: LinkState::Live,
                reason: String::new(),
                since: 0,
            },
            omit_text: false,
            omit_preview: false,
        }
    }

    fn preview(sha: &str, length: u64) -> Option<LinkBlob> {
        Some(LinkBlob {
            sha256: sha.into(),
            length,
        })
    }

    #[test]
    fn device_budget_boundaries() {
        assert!(fits_device_budget(0, 2 * GIB));
        assert!(fits_device_budget(2 * GIB - 1, 1));
        assert!(!fits_device_budget(2 * GIB, 1));
        assert!(!fits_device_budget(u64::MAX, 1));
    }

    #[test]
    fn unacked_budget_defers_over_six_gib() {
        assert!(!must_defer(0, 6 * GIB));
        assert!(!must_defer(6 * GIB - 5, 5));
        assert!(must_defer(6 * GIB - 5, 6));
        assert!(must_defer(u64::MAX, 1));
    }

    #[test]
    fn distinct_bytes_count_a_shared_digest_once() {
        let total = distinct_blob_bytes([(SHA_A, 10), (SHA_B, 5), (SHA_A, 10)]);
        assert_eq!(total, 15);
    }

    #[test]
    fn thousand_32_mib_previews_trim_to_two_gib_and_synced_links_stay_in() {
        let mut rows = Vec::new();
        for n in 0..1000u32 {
            let mut r = row(n + 1);
            // A distinct digest per link.
            r.preview = preview(&format!("{n:064x}"), MAX_PREVIEW_BYTES);
            rows.push(r);
        }
        // The newest link was synced before: it stays in despite its late added_at.
        let synced = BTreeSet::from([rows[999].key()]);
        let omitted = trim_blobs_to_budget(&mut rows, &synced);
        let kept = rows.iter().filter(|r| !r.omit_preview).count();
        assert_eq!(kept as u64 * MAX_PREVIEW_BYTES, DEVICE_BLOB_BUDGET);
        assert_eq!(kept, 64);
        assert_eq!(omitted, 1000 - 64);
        assert!(!rows[999].omit_preview);
        // Then oldest added_at first.
        assert!(!rows[0].omit_preview && !rows[62].omit_preview);
        assert!(rows[63].omit_preview);
        // Metadata is untouched: every row still emits identity and content.
        let cells = link_cells(&rows);
        assert_eq!(cells.refused.len(), 0);
        assert_eq!(cells.cells.len(), 1000 * 6 + 64);
        assert_eq!(cells.skipped.len(), 1000 - 64);
    }

    #[test]
    fn shared_digest_costs_once_and_protected_costs_nothing() {
        let mut rows = vec![row(1), row(2), row(3)];
        rows[0].preview = preview(SHA_A, MAX_PREVIEW_BYTES);
        rows[1].preview = preview(SHA_A, MAX_PREVIEW_BYTES);
        rows[2].protected = true;
        rows[2].preview = preview(SHA_B, MAX_PREVIEW_BYTES);
        assert_eq!(trim_blobs_to_budget(&mut rows, &BTreeSet::new()), 0);
    }

    #[test]
    fn project_links_skips_conflicted_and_invalid_and_deletes_only_gone() {
        let one = |n: u32, title: &str| {
            let mut r = row(n);
            r.title = title.into();
            r
        };
        let mut rows = vec![one(1, "ok"), one(2, "bad\u{1}title"), one(3, "conflicted")];
        // Row 2 passes through a writer that refuses it; craft its cells by hand.
        rows[1].title = "fine".into();
        let mut cells = link_cells(&rows).cells;
        cells.insert(
            format!("{}/title", link_key(OWNER, &rows[1].link_id)),
            json!({"title": "bad\u{1}title", "title_user_edited": 0}),
        );
        let conflicted = BTreeSet::from([format!("{}/content", rows[2].key())]);
        let projection = project_links(&cells, &conflicted);
        assert_eq!(projection.rows.len(), 1);
        assert_eq!(projection.rows[0].link_id, rows[0].link_id);
        assert_eq!(projection.skipped, 1);
        assert_eq!(projection.conflicted, 1);
        assert!(projection.gone.is_empty());
        // Absence is not deletion: dropping every link cell reports nothing.
        let empty = project_links(&Cells::new(), &BTreeSet::new());
        assert_eq!(empty, LinkProjection::default());
        // A gone presence is the only deletion signal.
        let mut gone = row(4);
        gone.presence = LinkPresence {
            state: LinkState::Gone,
            reason: "deleted".into(),
            since: 5,
        };
        let projection = project_links(&link_cells(&[gone.clone()]).cells, &BTreeSet::new());
        assert_eq!(projection.gone, vec![(OWNER.to_owned(), gone.link_id)]);
        assert!(projection.rows.is_empty());
    }

    #[test]
    fn missing_presence_is_live_and_marker_cells_are_ignored() {
        let mut cells = link_cells(&[row(1)]).cells;
        cells.remove(&format!("{}/presence", row(1).key()));
        cells.insert(
            marker_key("20000000-0000-0000-0000-000000000001"),
            json!({"since": 7}),
        );
        let projection = project_links(&cells, &BTreeSet::new());
        assert_eq!(projection.rows.len(), 1);
        assert_eq!(projection.rows[0].presence.state, LinkState::Live);
        assert_eq!(projection.skipped, 0);
        assert_eq!(
            enabled_replicas(&cells),
            BTreeSet::from(["20000000-0000-0000-0000-000000000001".to_owned()])
        );
    }

    #[test]
    fn count_limit_ignores_gone_links() {
        let mut rows: Vec<LinkInput> = (1..=1001).map(row).collect();
        let result = link_cells(&rows);
        assert_eq!(result.refused.len(), 1);
        assert_eq!(result.refused[0].1, "link count");
        rows[0].presence.state = LinkState::Gone;
        assert!(link_cells(&rows).refused.is_empty());
    }

    #[test]
    fn link_blob_refs_are_keyed_by_digest_and_check_the_declared_length() {
        let cells = link_cells(&{
            let mut r = row(1);
            r.preview = preview(SHA_A, 20);
            r.text = Some("hello".into());
            vec![r]
        })
        .cells;
        let refs = link_blob_refs(cells.iter());
        let text_sha = stack_archive::digest(b"hello");
        assert!(link_blob_declared(&refs, &text_sha, 5));
        assert!(link_blob_declared(&refs, SHA_A, 20));
        // A 256 MiB blob cannot hide behind a small cell.
        assert!(!link_blob_declared(&refs, SHA_A, 256 * 1024 * 1024));
        assert!(!link_blob_declared(&refs, SHA_B, 20));
        assert!(refs.keys().all(|k| k.len() == 64));
    }

    #[test]
    fn owner_limit_is_200_and_refusal_only() {
        // 200 units with dots: "a." * 99 + "ab" = 200.
        let owner = format!("{}ab", "a.".repeat(99));
        assert_eq!(owner.len(), 200);
        let mut r = row(1);
        r.owner = owner.clone();
        assert_eq!(writer_self_check(&r, 0).outcome, WriterOutcome::Emitted);
        r.owner = format!("{owner}x");
        assert_eq!(writer_self_check(&r, 0).outcome, WriterOutcome::RowRefused);
        // The receiver accepts owner 201 on the wire (writer refusal only).
        let key = link_key(&r.owner, ID);
        let cell = json!({"owner": r.owner, "link_id": ID, "kind": "pdf", "added_at": 1});
        let cells = Cells::from([(key, cell)]);
        assert!(crate::graph::validate_changes(&cells).is_ok());
        assert_eq!(project_links(&cells, &BTreeSet::new()).skipped, 1);
    }

    #[test]
    fn link_id_must_be_lowercase_canonical_on_both_sides() {
        let upper = ID.replace("000000000001", "00000000000A").to_uppercase();
        let mut r = row(1);
        r.link_id = upper.clone();
        assert_eq!(writer_self_check(&r, 0).outcome, WriterOutcome::RowRefused);
        let key = link_key(OWNER, &upper);
        let cell = json!({"owner": OWNER, "link_id": upper, "kind": "pdf", "added_at": 1});
        assert!(crate::graph::validate_changes(&Cells::from([(key, cell)])).is_err());
    }

    #[test]
    fn marker_value_is_range_checked() {
        assert!(marker_value(0).is_ok());
        assert!(marker_value(MAX_SAFE).is_ok());
        assert!(marker_value(MAX_SAFE + 1).is_err());
        assert!(marker_value(-1).is_err());
    }
}
