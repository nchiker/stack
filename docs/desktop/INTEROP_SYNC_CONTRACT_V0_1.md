# Stack interoperability and sync contract — draft 0.1

Status: design proposal, 2026-10-17. No deployed sync protocol. M1 records local
mutation events and source aliases; their storage format is not this frozen wire
contract. See [storage](../../desktop/STORAGE.md) and [evidence](M1_EVIDENCE.md).
This draft accompanies the [desktop plan](../STACK_DESKTOP_PLAN.md). Freeze exact
wire schemas and executable golden cases before claiming protocol version 1.
Archive version, desktop schema, Android schema and sync version are independent.

## 1. Existing backup interoperability

The normative implemented backup contract is [archive formats 1/2/3](../STACK_ARCHIVE_FORMAT.md)
plus `LibraryArchive.java`, `ArchiveJson.java` and their validators. A Rust reader
must accept exactly supported version/schema pairs 1/6, 2/7 and 3/8, with strict
validation before applying older-format defaults. Write format 3/schema 8. Do not
extend its allowlists with sync metadata or change its magic/envelope.

Preserve original bytes and complete supported document/page/stack/decision/detail/
tag graphs. Store amount values as exact decimal strings, dates with their original
precision, text identifiers with leading zeros, and source evidence as evidence.
Do not infer currency, role or manual acceptance from display labels or field IDs.
Retain historical decisions and originals for removed pages. FTS, thumbnails and
transient work claims are derived/local state.

Restore remains additive. One operation ID commits once; selecting the archive
again starts another import and may create new local IDs. Retain `(import instance,
source entity type, source ID) → local ID` mappings and source manifest digests in
desktop storage. Source digests are evidence for review, not global document IDs.
An archive export is not a backup of paired credentials or sync causal history.
Restoring one never reuses an old replica counter space or silently rejoins peers.

Version 3 cannot represent multiple unresolved values for a single field. Preserve
all conflicts locally and block portable archive export for a conflicted library
until reviewed, with a precise explanation. Do not silently export just one value
as though the complete decision graph was preserved. Ordinary document/PDF copies
may remain available with their narrower stated scope. Ordinary format-3 archives
do not carry document-deletion sync tombstones and cannot be used to revive or
advance a paired baseline automatically.

## 2. Identity and initial adoption

Proposed identifiers:

| Identifier | Meaning |
| --- | --- |
| `library_id` | UUID for the explicitly joined synchronization group. |
| `replica_id` | Random UUID for one writer installation/incarnation; never cloned/reused on restore. |
| `entity_id` | Stable sync UUID for document/page/stack/tag; separate from Android legacy IDs and backup collision IDs. |
| `field_key` | Stable decision slot scoped to its entity; source legacy `field_id` remains recorded. |
| `operation_id` | `(replica_id, sequence)`, with a monotonically increasing persisted counter. |
| `baseline_id` | Digest of an agreed immutable baseline and its explicit legacy-to-sync mappings. |
| `membership_epoch` | Protocol membership generation used to reject revoked/replaced replicas. |

Legacy starter stack IDs and names are aliases, not globally unique sync entities.
Reimported copies keep distinct import identities even if source IDs/bytes match.
Content hashes may deduplicate immutable storage only when references/lifetimes are
safe; they never merge document identity.

First adoption requires explicit consent and a preview. For a new empty peer,
transfer the first peer's chosen baseline and identity map; record acknowledgment
on both peers before sending later deltas. For two existing libraries, review
source-ID mappings and possible common archive ancestry. An identical archive
manifest is useful evidence, but archive format 3 has no signed lineage claim.
Never match solely on title or original hash, and never silently match duplicate
copies from repeated restores.

If an exact common ancestor is available, compare each side against it and retain
independent edits using the same conflict rules as ordinary sync. If ancestry is
missing or ambiguous, keep entities separate by default; a user-confirmed mapping
must preserve divergent current states as concurrent alternatives, without assigning
false causal order. Distinct stacks with colliding names need reviewed mapping or
rename before phone-compatible export. Persist adoption atomically with its baseline
mapping so restart/retry cannot create another set of documents.

## 3. Journal, causality and transactional apply

Each operation has protocol version, library/membership identity, origin operation
ID, causal context (a replica-to-contiguous-sequence frontier), entity/field target,
typed change, provenance and referenced blob hashes/lengths. User times are display
metadata only. Android revisions and local generation numbers are never causal clocks.

Persist the local counter increment, operation, authoritative values/conflicts and
search invalidation in one transaction. Import/edit/delete/stack/worker/restore paths
must all use this boundary. Workers submit input tokens and cannot create decisions
against changed/deleted inputs. Pure caches are not journaled. Publish only current,
compatible OCR text with its input/page revision; retain stale/historical evidence
without making it searchable as current text.

Before apply, validate protocol/schema, library/epoch/authorized origin, ranges,
payload limits, references and blobs. An operation with missing causal dependencies
or earlier origin sequences stays in a bounded pending inbox; it does not advance
the contiguous acknowledgment frontier. Re-request gaps. Apply causal successors
after dependencies. Accept forwarding without changing origin operation IDs.
For the initial version, third-peer forwarding is limited to explicitly reviewed
local bundles; network forwarding requires verifiable origin authorization defined
in the transport specification, rather than trusting an arbitrary sender's claim.

Replay of identical operation IDs and payloads is a no-op. The same ID with a
different payload is corruption/protocol failure. Acknowledgments follow durable
commit, never just receipt or completed hashing. Persist inbox/outbox state, per-peer
frontiers and commit markers; resume or safely retry after process termination.
Reject malformed batches before mutation and apply each bounded valid batch
atomically. Missing/invalid blobs cannot leave visible partial documents.

## 4. Deterministic merge and review

For scalar decisions, retain causally maximal alternatives in a multi-value
register. A causally later operation supersedes values it observed; concurrent
different values remain conflicts with origin and evidence intact. Equal display
values may share a presentation while retaining both causal histories. All replicas
derive the same conflict set. A deterministic operation-ID ordering may choose a
provisional preview value, but is not user acceptance and never deletes alternatives.

| Change | Rule |
| --- | --- |
| Different document fields | Merge independently. |
| Concurrent title/note/same detail slot | Retain both; explicit resolution observes all alternatives and emits a new operation. Notes are not automatically concatenated. |
| Detail save/edit/remove/reject | Treat value, state and provenance as one decision; concurrent edited/removed/rejected states require review. A worker cannot override any authoritative decision. |
| Membership delta | Per-membership observed add/remove; independent stack changes merge. Concurrent opposing changes to one relation require review. |
| Replace complete filing, including empty | Include an observed membership context. Remove observed memberships; concurrent unseen additions remain explicit filing conflicts. Never turn explicit empty filing into an unspecified default. |
| Page appearance | Crop/rotation/enhancement/flatten form one page-appearance decision; concurrent edits to that page require review. Preserve source-page references. |
| Page order/removal | Whole ordered stable-page-ID list is a decision; concurrent list edits require review. Removed-page originals stay retained. Validate nonempty documents and references. |
| Stack order | Ordered stable-stack-ID list is a catalog decision; concurrent reorder/create/delete conflicts are retained and reviewed, never silently sorted by timestamp. |
| Stack visibility/name | Separate scalar decisions; concurrent edits to the same property require review. Hide is not delete. Name collisions across different stacks require resolution before archive projection. |
| Stack delete | Hide deleted stack and memberships from active views; retain relationship history. Concurrent rename/order/filing becomes a review item. Documents remain. |
| Document delete versus edit | Tombstone hides document; retain concurrent edits and originals in conflict/recovery state. No ordinary edit recreates it. Explicit recovery creates a new document identity with provenance. |

A resolution operation carries the full context being resolved. Two concurrent
resolutions can themselves conflict; retain them rather than choosing by clock.
Do not publish duplicate names, invalid references, contradictory saved organization
relations or malformed page/order lists into the authoritative archive projection.
Use one deterministic valid provisional view, expose unresolved alternatives, and
block archive export until the graph is fully representable.

## 5. Retention and disconnected devices

Retain deletion/removal tombstones and journal history indefinitely in the first
version. Do not physically delete the last original through sync. A future purge/
compaction feature must require all active peers' durable acknowledgment and no
conflict/snapshot references, with a separate explicit retention policy.

Forget/revoke advances membership policy. A returning revoked peer cannot upload
old history or resurrect deletions; require explicit re-pairing and a fresh baseline
with a new replica incarnation. Fresh replica enrollment requires approval by an
existing paired authority; replicas cannot self-enroll through operation payloads.
Lost-device or rollback recovery never reuses previously emitted sequence IDs.
Offline peers keep local usability and queued changes, with truthful pending status.

## 6. Folder exchange and later network transport

Draft folder bundles use a distinct format identifier `org.stackdocs.exchange`,
version 1, with bounded manifest, operations, causal frontiers and immutable blobs.
Freeze an exact byte layout, canonical operation-digest representation, integer/
string rules, count/byte limits and golden fixtures in M3 before implementing the
writer. Carry no executable entries or arbitrary filesystem paths; resolve blobs
through validated digest keys. Publish complete bundles by temporary file plus
durable rename in a selected local folder. Partial files are never imported.

M4 folder exchange has explicit local-file consent and integrity checks, without
claiming sender authentication or confidentiality. Treat external files as hostile
input. Network delivery must use an authenticated paired session and the same
validated merge path; an unauthenticated socket must never expose library data.

M6 should specify maintained TLS, certificate/public-key pinning through verified
pairing, mutual peer authorization, credential storage/revocation, timeouts, size
limits and downgrade rejection. QR/manual pairing exchanges public identity;
discovery only supplies candidate addresses. No bespoke crypto or trust-on-any-first
connection. Background operation requires separate Android lifecycle evidence.

## 7. Device-local policy and version negotiation

Never sync app lock/enrollment/authentication, monitoring consent, photo/notification
permissions, paths/URI grants, credentials, unfinished editor/scan drafts or local
backup/view history. Pairing cannot weaken an existing local lock. Retain device
authorization checks on every receiving apply path. Backups remain unencrypted;
hashes are integrity checks and an app gate is not at-rest encryption.

Peers negotiate implemented protocol/features before sending data. Reject unsupported
required operations, versions and field types before advancing acknowledgments.
Ordinary backup transfer remains available separately. Partial protocol support
must not silently drop details, provenance, conflicts or deletion decisions.

## 8. Required conformance evidence

Fixtures must prove: complete archive graph round-trip; old safe defaults and future
rejection; deterministic baseline mapping; no identity merge from title/hash alone;
repeat import versus exchange replay; causal gaps and out-of-order batches; two/three
replica forwarding; independent and concurrent decisions; explicit-empty filing;
delete/edit, field rejection and no resurrection; conflict resolution; membership
revocation/re-pair; stale worker rejection; crash before/after commit and acknowledgment;
and blocked export of unrepresentable conflicts. Test Java/Rust agreement against the
same expected outcomes before describing Android bidirectional sync as implemented.
