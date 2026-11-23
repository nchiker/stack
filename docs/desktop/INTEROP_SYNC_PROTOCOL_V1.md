# Executable exchange protocol 1 — graph model 2

Updated 2026-10-20. Experimental implementation in `stack-sync`, with the same causal
kernel in production Java `SyncProtocol.java` and ten shared golden cases in
`desktop/protocol/golden-v1.json`. The phone and desktop apps both use it in
production; two-way sync between real devices was verified in Stack 0.9.0.

Archive version 3/schema 8, desktop schema 4, Android schema 9 and wire version 1
are independent. SQLite/WAL and device policy are never exchanged. The existing
`org.stackdocs.local-change` journal is still local history, not this wire format.

An operation has exactly `version`, `library`, `epoch`, `id`, `context`, `changes`
and `evidence`. Version/epoch are 1. Library and replica identifiers are lowercase
canonical UUIDs. `id` has exactly `replica` and `sequence`; sequences start at 1
and cannot exceed 9,007,199,254,740,991. Context is a replica-to-contiguous-counter
map of at most 64 entries and contains exactly the origin's previous sequence
(omission means zero). Times never establish order. Wire numbers are safe integers;
typed decimal/currency/identifier values remain exact strings. Ordinary non-BMP
Unicode is retained; unpaired UTF-16 surrogates are rejected.

`changes` is a nonempty map with at most 65,536 cells. Keys are at most 1,024 UTF-8
bytes and have no control characters. Each operation/metadata envelope is limited
to 32 MiB. Unknown structural fields, duplicate JSON keys, unsupported versions,
unsafe numbers, unreviewed origins and operation-ID payload changes fail. The
receiving batch is validated against a copy before any causal state is changed.

Each cell is a multi-value register. A causal successor supersedes only alternatives
in its context; concurrent unequal alternatives and evidence remain visible to
review. Equal values retain both histories. A deterministic origin/counter order
selects a provisional value. An explicit resolution checks the reviewed alternative
IDs and emits a new causal operation. History and document deletion tombstones are
retained. Deletion hides a document even when a later ordinary edit exists; recovery
must use a new identity. Invalid concurrent graph projections block full archive
export, and a deterministic provisional baseline still respects all tombstones.

At most 4,096 pending operations and an origin gap of 4,096 are accepted. A future
operation waits until every causal predecessor is durable. Only the contiguous
applied frontier can be acknowledged; identical forwarding/replay is a no-op.
The state/projection/index commit is one SQLite transaction. Peer receipts are
written after that commit; interruption before a receipt causes safe replay.

Graph model 2 cells use portable identities: table plus canonical JSON row-key
array. Document title/provenance, notes/revision, filing origin and metadata counters
are separate cells. Stack name, color and visibility merge independently. Derived
metadata/filing counters take their maximum as compatibility state, never causal
order. Whole saved page order and whole catalog order have explicit array cells;
page records and originals remain in history when a page is removed from an order.
Concurrent removal versus an unseen appearance edit reviews the editor's observed
whole order. A page's text carries its exact source and saved appearance input;
equal numeric revisions alone never make text current after a different crop.

Membership relations merge as independent deltas. Full filing replacements record
their observed complete set, including an explicit empty decision. An unseen
concurrent delta requires review. Edited legacy CSV filing-choice rows are derived
from merged relations when concurrent; their original exact values remain retained
in history. Deletion versus concurrent document/page/filing or stack-reference
changes remains reviewable; automatic text commits cannot resurrect a deletion.

Every incoming partial cell must have a supported canonical identity key, exact
row/suffix allowlist, matching row identity and bounded order array before entering
the durable inbox. Cross-row constraints are checked by the production portable
graph validator. A conflicting combination can have a provisional fallback;
`projection_valid`/`projection_error` make this visible and block full portable
export until corrected. Unsupported graph shapes are rejected rather than used as
a provisional conflict representation.

Desktop existing-library adoption proposes distinct, stable UUIDv5 mappings scoped
to group/replica, never matches by title, bytes or legacy ID. A review contains the
incoming file digest, local graph fingerprint, source/local counts, complete member
list, all identity maps and explicit catalog-name collision renames. Changing the
local graph or incoming file invalidates review. Explicit matches preserve local
and source decisions as concurrent alternatives; the accepted mapping and original
names remain in retained operation evidence. Originals publish without replacement
before the single state/projection transaction. The phone's ability to initiate a
group from its existing library remains separate from symmetric nonempty adoption;
see current runtime evidence for that implementation's acceptance state.

File bundles begin with 8 bytes `STACKEX\x01`, then an unsigned big-endian 32-bit
metadata length, strict UTF-8 JSON, then blob bodies in declared order. Header keys
are exactly `format`, `version`, `library`, `sender`, `acknowledged`, `operations`
and `blobs`; format is `org.stackdocs.exchange`. At most 4,096 operations and 10,000
blobs are allowed. Each blob has exactly lowercase hex SHA-256 and byte length;
hashes are unique, each body is at most 256 MiB, total bodies at most 8 GiB. Missing,
truncated, corrupt or trailing bytes fail before metadata apply. Blob names are
digest keys, never supplied filesystem paths. Originals install durably before
metadata publication; a stop may retain verified unreferenced bytes, never a partial
visible document. Owned staging directories recover separately from archives.

Publication writes an exclusive private partial, flushes/syncs, links without
replacement and syncs the selected directory. Written/transferred is distinct from
acknowledged. Local-file exchange provides integrity and explicit file consent;
it provides no sender authentication or confidentiality. Authenticated paired LAN is a separate, opt-in transport for these same files;
see [paired transport](PAIRED_TRANSPORT.md). File exchange itself enrolls no peer.

Observed conformance includes Rust causal permutations/gaps/replay/resolution,
ten shared host Java/Rust cases, envelope corruption/truncation/path validation,
and actual three-library SQLite/file exchange with originals/deletion retention.
Android's journal capture records a run of consecutive suggestion-row changes as
one operation; they are local operations with no remote operation between them,
so this matches recording each row separately.

The native desktop now exposes source-matched nonempty adoption, file exchange and
conflict resolution. Identity sections and conflict alternatives are paged; page
boundaries preserve the original alternative IDs/indices passed to stale-review
validation. Complete page/catalog order conflicts and page removal versus unseen
appearance edits have SQLite/file/restart coverage in addition to the causal
kernel. Seven actual SIGKILL cases cover sync originals, transaction, committed
state, receipt and bundle publication boundaries. File replay never regresses a
verified acknowledgment. Paired TLS/native controls reuse the same apply path and
remain distinct from unperformed Android runtime exchange.

## Link records (slice 6; implemented and announced)

Status: both sides can read, write, resolve and purge link cells, and both devices now announce `links`
(`ANNOUNCE_LINKS=true` in Rust `stack-transport` and Java `PairingTransport`; D4b), with `enabled` derived from history. Wire version stays 1, graph model stays 2, archive format stays 4. The kernel is unchanged. Design:
`../STACK_LINK_SLICE6_DESIGN.md`.

Hello: optional `"features":["links"]` and optional `"enabled":true|false`, after `frontier`. Absent means the peer does
not announce. Other unknown fields are still refused. Link cells are never created for a peer that has not announced
`links`. A client tries `frontier+features`, then `frontier`, then plain within one Sync (a refusal or timeout moves down;
the level is remembered 15 minutes). On equal frontiers a Sync may end early only if both announce `links` and both are
`enabled`, or if either does not announce.

Cells: key is `link_records/` + canonical JSON array `["<owner>","<link_id>"]` + suffix. Values are exact-field objects.

| Suffix | Value |
|---|---|
| (none) | `{owner, link_id, kind, added_at}`; not null |
| `/content` | `{hash, mime, modified, shared, protected}` or null |
| `/text` | `{sha256, length}` (bytes, 1..1,048,576) or null; empty text is null |
| `/preview` | `{sha256, length}` (1..33,554,432) or null |
| `/title` | `{title, title_user_edited}` or null; 1..200 UTF-16 units |
| `/filen` | `{uuid, path}` or null; path at most 1024 UTF-16 units; first filen locator only |
| `/presence` | `{state: live\|trashed\|gone, reason, since}`; not null; `live` uses since 0; absent means live |
| `enabled/<replica>` | `{since}` marker, written once by each device in its enable operation (`evidence.kind = "links.enable"`), so the operation is never empty. A device is enabled iff its own marker is in history |

Text and preview bodies are blobs named by digest, kept by copy (never hard link) in `sync_blobs/<sha>` before the
operation is committed. Link refs are returned in a separate map from document refs and never become paths.

Rules:
- Receivers check only key shape, exact fields, JSON types, safe-integer range (0..2^53-1) and memory bounds, so one odd
  cell cannot kill the bundle and the per-origin chain. Semantic limits (patterns, 1000 links, text bytes, title rules,
  content checks, byte budget) are enforced by the writer, which does not write what fails, and again on projection, which
  skips and counts an invalid link.
- Authors: the phone writes every cell on every local change. The desktop writes only one enable operation (empty context,
  after its hello reply and before it receives a bundle) and never resolves.
- Conflicts: for a link whose cells conflict the phone applies one rule and writes one `link.resolve` operation with the
  winner's full 7 cells. Either side `gone`: the phone's cells win. Hashes differ: the later `modified` wins (if the phone
  had trashed it, trashed is kept). Same hash: the phone's values win. A `modified` tie with different hashes goes to review
  on the phone only. Conflicted link keys are skipped by projection, hidden from the desktop review screen, refused by
  desktop `sync_resolve` and ignored by both backup gates.
- Deletion: the desktop deletes a row only on presence `gone`, never by absence. On `gone` the writer also sends content,
  text, preview, title and filen as null. A link that is `gone` is never inserted on the phone; a link only the desktop has
  arrives trashed (reason `user`).
- Purge: a retained link blob is deleted when it has no reference in document history, current cells, conflicted
  alternatives or unacknowledged/pending operations (after the peer acknowledges). Cells hold digests only; old text may
  remain in sync snapshots, and titles and Filen paths remain in history.
- Caps: 1000 links (non-`gone`), text 1 MiB UTF-8, preview 32 MiB, title 200, 2 GiB of referenced link blobs per device;
  an operation that would push unacknowledged link blobs over 6 GiB waits for the next acknowledgement. The enable
  operation on the phone is metadata only (blobs follow). Overall blob limits above (256 MiB each, 8 GiB total) still hold.
- Protected links (`protected=1`): metadata only; no text or preview cell and no blob.
- Not synced: `saf`/`owner` locators, `unreachable`/`missing`, `checked_at`, `state_since`, `revision`, stacks/tags/notes
  for links. Link cells live in a separate projection (`link_projection` on desktop, `sync_link_projection` on the phone;
  desktop schema 6, phone schema 13) and are cleared, with no link row deleted, by a connection reset.

A pre-slice-6 device that adopts or receives a history containing `link_records/` cells fails in `validate_changes` with
"unknown graph table". It cannot happen through the hello gate; it can happen through a copied backup or a re-pairing with
an old app after a connection reset on the new side. There is no silent fallback. An older desktop build refuses a
schema-6 library.

## Link records, slice 7 (S7-4): the partner's shared records on the computer

Status: built on both sides (phone schema 16, desktop schema 8). Wire version, graph model and archive format are unchanged
(1, 2, 4). A record the partner shares with this phone reaches the partner's computer through this same phone-to-computer
sync. The computer never reads the share archive, never receives the sharing key (no key material is in any cell, log or
history), and never needs a Filen client. Design: `../STACK_LINK_SLICE7_DESIGN.md` (item 6).

- **Hello.** The computer announces a second feature, `"features":["links","link_foreign"]`. The phone writes the partner's
  records into link cells only after the computer's hello said so (it remembers that in `sync_meta` until a connection
  reset). A computer that does not announce it (any build before this one) never receives them and keeps syncing documents
  and the phone's own links exactly as before. The phone does not announce `link_foreign` itself (the computer never writes
  these records). `link_foreign` counts only next to `links`; unknown names stay ignored.
- **Cells.** No new suffix. A shared record is an ordinary link (identity, `/title`, `/filen`, `/text`, `/preview`,
  `/presence`) whose `/content` cell has three more fields, all or none:
  `{hash, mime, modified, shared, protected, origin:"foreign", shared_by, foreign_generation}`. `origin` is the string
  `foreign`; `shared_by` is the partner's display name (1 to 100 UTF-16 units on the wire, `LinkRecord.free` rules; the phone
  cleans it to 40); `foreign_generation` is the share archive's generation (0..2^53-1). Receivers check only the shape (five
  fields or eight; types; range; `shared_by` at most 100 units); an unknown `origin`, an empty or control-character name make the
  link skipped and counted at projection (never a rejected bundle). Own records keep the five-field cell. A protected shared
  record is metadata only like any protected link. Shared records count towards the 1000 links of the phone; on the computer
  they do not count towards the 1000 an archive can hold (archives never contain them).
- **Authority.** The phone is the only author. The computer's one enable operation lists only its own rows (never an
  `origin='foreign'` row), it never edits, resolves, deletes or sends them back, and the phone never applies a computer cell
  to a shared record. If a computer-only link has the same identity as a record the phone says is shared, the phone's cells win
  (the existing resolution rule) and the computer's row becomes a shared copy (it forgets any remembered file).
- **Presence.** Unshared by the partner (Recently deleted, reason `unshared` on the phone) travels as `trashed` with reason
  `deleted` (no new wire reason); a record the person removed travels as `trashed`/`user`; both are hidden on the computer, and a
  restore on the phone brings it back `live`. Deleting the copy on the phone for good (or its 30 days) is `gone`, with
  content, text, preview, title and filen null; only `gone` deletes the computer's row, its search entry and preview.
- **Computer storage.** Schema 8 adds `link_records.origin` (`own` default), `shared_by` and `foreign_generation`
  (additive; every existing row stays own). As with every schema bump an older desktop build refuses a library the new one
  opened (`version > VERSION`); no row or file is removed by the migration. A shared record is read-only: no Open, no
  "Open in Ami Pro", no remembered file. The list and search show "Shared by <name>"; the detail view shows the saved text
  and preview and says "Opening needs your phone: it opens through Filen Files." Shared records are excluded from archive
  exports (as on the phone).
- **Tests.** `desktop/protocol/golden-v1.json` `graph_cases` (receiver_rejects, receiver_accepts_skips, writer_refuses) hold
  the foreign shapes for Rust and Java; `tools/test_link_cross_java.py` round-trips shared records Java writer -> Rust kernel
  and Rust kernel -> Java projection (live, unshared, gone, protected, rename); `stack-core/tests/sync_links.rs` covers
  apply, update, unshare, gone, the computer never writing them and archive exclusion; `HelloGateHost` the feature.

