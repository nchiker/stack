# Stack portable library archive — format 3 (formats 1 and 2 readable)

Current implementation: **0.8.7-dev / code 20 / database schema 8**. Format 3
adds saved stack order. Format 2/schema 7 introduced authoritative notes/details/
organization identities and relations, including corrections, rejected proposals
and removal tombstones. Format 1/schema 6 remains readable with empty P1 defaults.
Formats 1 and 2 retain their stack array order when upgraded. This independently
authored protocol is not a Google format, raw SQLite backup, PDF bundle or ZIP.

## Scope and protection

Library menu → **Backup & restore** opens local storage-access
controls. Backup includes every saved document and all retained original files
(including originals of removed pages), stable IDs, original page indexes and
saved page order, added timestamps, names/provenance/decision revisions, crop,
rotation, enhancement, flattening, stack catalog/order/visibility/memberships, OCR
text/configuration/revisions/status and suggestion payload/provenance/choice history.
Saved optional notes, typed date/amount/custom details, organization tags and
relations, provenance, decision revisions and removed/rejected detail records are
included. Transient worker claims/attempt counts and derived proposals/FTS/cache data
are excluded. Schemas 1–7 upgrade additively to 8. Archive, app and database versions
are separate. Unsaved metadata editor drafts remain local and are not portable.

The unfinished scan and `files/consolidation_recovery/` stay untouched locally and
are excluded. Save a draft before backup to protect it in the archive. Archive
operations never discard a draft. Automatic Android backup/transfer remains disabled.
Backups contain private original documents and metadata, **without encryption or a
password**. Only the selected destination is used; no permanent URI grant is kept.
A user may choose a cloud-backed provider, but Stack supplies no cloud service or
cloud durability guarantee. No network/account/model download is required by Stack.

## Byte layout and limits

All integers are big-endian. Files end exactly after the last listed original.

1. Eight bytes: ASCII `STACKBK` followed by byte `01`.
2. Four-byte signed positive manifest UTF-8 byte length, at most 32 MiB.
3. Thirty-two raw SHA-256 bytes of the manifest, followed by that manifest.
4. For each manifest `files` entry, in array order: unsigned 16-bit UTF-8 path byte
   length (1–160), path bytes, signed 64-bit positive original length, then exactly
   that many original bytes. Each manifest entry contains `path`, `length` and
   lowercase hexadecimal `sha256`. No compression, link, directory, arbitrary entry
   type or executable restoration semantics exist. ZIP inputs are rejected by magic.

Strict JSON rejects duplicate keys, nonstandard syntax, depth over 16 and overlong
strings. The envelope magic retains byte `01`; the manifest versions its metadata separately.
Format 3 top-level fields are `format: org.stackdocs.library`, `version: 3`,
`schema: 8`, `created_at`, `documents`, `pages`, `stacks`, `memberships`,
`suggestions`, `choices`, `decision_evidence`, `files`, `details`, `tags`,
`document_tags`. Documents add `notes`, `notes_revision`, `metadata_revision`.
Details use a per-document stable `field_id`, `kind`, `label`, exact string `value`,
`qualifier`, `origin`, `state`, evidence JSON and `revision`. Currency is an explicit
ISO code or `unknown`; dates retain day/month/year precision. Money is never binary
floating point. Saved organization fields require matching tag relations. Detail
states `saved`, `removed`, `rejected` carry authoritative decisions independently of
P0 title/filing history. Source evidence may remain historical after page removal.

Format 1 requires version 1/schema 6 and its original row allowlists. Only after
strict validation are notes/revisions defaulted to empty/zero and the three new
arrays defaulted empty. Unsupported version/schema pairs and unknown fields are
rejected. Format 2/schema 7 preserves P1 arrays. Both old formats have exactly
`id,name,color,visible` per stack; after validation their array positions become
saved order. Format 3 requires `id,name,color,visible,sort_order` per stack, with
integer positions exactly 0 through N−1 in array order. Missing, duplicate, negative,
noninteger, out-of-range or out-of-order positions reject the entire archive.
Older apps reject format 3 rather than silently dropping ordering or P1 values.
Keep future-version archives for a compatible release. Digests detect corruption,
not malicious authorship.

Limits: 1,000 documents; 10,000 current pages; 1,000 stacks; 100,000 memberships;
20,000 original entries; 20,000 choice records; 20,000 each details/tags/tag relations;
20,000 characters per note; 2,000 per detail value; 200 per label/tag; 8 KiB evidence; 256 MiB per original; 8 GiB total
original bytes; 1 MiB per page OCR/choice/payload string; 32 MiB manifest. Saved
image/PDF types remain JPEG/PNG/WebP/PDF and internal JPEG scans. PDF original page
counts and page references, image types, contiguous unique positions, settings,
IDs, references, lengths and digests are validated. Only
`originals/<canonical UUID>/source` and
`originals/<canonical UUID>/<canonical UUID>.jpg` are permitted. Paths cannot create
links or access local files outside the operation. There is no compressed expansion
and no ZIP bomb processing. Restore reserves 16 MiB beyond each staged original;
provider read/close and real disk failure behavior still require runtime evidence.

## Snapshot, commit and interruption protocol

All repository mutations/worker commits use the existing single IO executor.
Snapshot captures authoritative metadata in a transaction and pins immutable
originals by local hard links (copy fallback). This explicit lifetime ends when the
private snapshot is closed. Native work may finish concurrently but its repository
commit queues behind snapshot capture. Once pinned, edits/deletion/worker commits
can proceed while a separate archive executor hashes/streams originals. Memory is
bounded by metadata limits and 64 KiB transfer buffers, not original size. Startup
reclaims abandoned operation directories. No live DB/WAL files are exported.

Restore reads into a fresh private operation directory, validates every byte and
source, then computes a review plan on repository IO. Choosing a file or reading
its summary changes no library data. Review shows document conflicts and matching
stacks. Cancel removes staging; backgrounding/recreation retains the process-owned
operation. Process termination before commit cancels review; select the archive
again after launch. No permanent URI permission is needed.

Commit rechecks the catalog/IDs, writes and syncs an AtomicFile journal with the
operation and planned new document IDs, tags each staged directory with its owner,
and renames/syncs original directories before a single SQLite transaction. That
transaction inserts the complete restored graph, normalizes workers, rebuilds FTS
and writes a `restore_commits` marker. A new process recovers journals before
ordinary repository recovery: marked operations keep committed files; unmarked
operations remove only owner-tagged files without database rows. Existing files
are never overwritten. Uncommitted files never have visible document rows.
Synthetic boundary probes model exceptions/process termination separately; no
physical power-loss guarantee is inferred. SQLite/platform filesystem durability
applies. Committed operation markers remain as small durable retry tombstones.

On an untouched empty installation, starter stacks are reconciled to the archived
catalog, including deleted/hidden starters, without duplicates. On an existing
library, matching stack names merge into existing stacks and preserve current
visibility and order. Missing stacks append in archived order. An empty catalog
whose starter order/visibility/name was manually changed is not untouched; its
choices remain authoritative. Stack order changes invalidate a reviewed restore
plan, requiring review again. Other stack/document/page/scan-link ID conflicts map deterministically
within the operation using its UUID. Existing names, IDs, memberships, custom and
hidden stacks remain authoritative. A second commit of the same operation is
idempotent. Selecting the same archive again creates an explicit **new import**,
including new IDs where necessary; it never overwrites an edited existing document.

OCR claims/attempts are reset; missing/stale work queues after durable commit.
Compatible current text indexes immediately, including later pages. Archived
suggestion payload/history remains recorded, but proposals are recomputed before
being offered. Current evidence choices are rebased to mapped IDs; older historical
evidence remains historical. Accept/edit/dismiss and explicit empty filing persist.
Each restored suggestion receives a fresh random positive generation incarnation;
stale pre-restore worker claim/action tokens cannot match restored generations.

Progress and cancellation are checked between buffered reads and before commit.
Cancel closes a provider stream on a background thread; providers may delay it.
A cancellation that arrives after durable SQLite commit cannot undo that commit.
Export reports success only after flush and stream close. Stack attempts to delete
a failed created destination; a provider can refuse, leaving an incomplete output
that must be deleted. No provider-independent atomic replacement is promised.

## P1 restore and local policy

Organization names merge case-insensitively; conflicting tag IDs map within the
operation, independently of stack identities. Document/field decisions and notes
are inserted with restored documents, original page IDs in detail evidence rebase
when mapped, and the derived document search index rebuilds from notes, saved
details and current OCR. Current local documents are never overwritten. Repeating
an explicit import carries tombstones to the new document copies. Fresh metadata
claim tokens prevent pre-restore Apply/native work from acting on reused IDs.

Photo opt-in, permissions, enabled-at watermark, source/ignore/deduplication history,
notification preference/permission, pending batches/exports/external requests, app
lock choice and authentication/enrollment state are device-local and excluded.
Restore never enables monitoring, requests permission/authentication, enrolls or
weakens a local lock. The local editor drafts, unfinished scan and consolidation
recovery remain intact. New PDF ZIP sharing is an ordered bundle of copies, **not**
this archive and contains no portable metadata graph.

Detail evidence records explicit semantic `role` or `unknown` in current rule-6
provenance. Stable field IDs identify decision slots: the primary `date:document`
slot can hold a generic Date with role unknown, retaining prior removal identity.
Consumers must use evidence/label/origin for semantic role, not infer certainty
from that ID. Dollar and pound symbols alone retain unknown currency.

## Original Google Stack migration

The one-time local converter writes compatible format 2/schema 7; it does not change
the archive contract or import original app policy/account data. Legacy
exact strings and opaque type codes stay text plus source provenance; source
PDF snapshots and referenced JPEGs are retained. No inferred enum/currency/date
conversion, extra native processing or automatic personal-library restore occurs.

A rejected suggestion stays out of ordinary offers. Explicitly opening Dismissed
suggestions and choosing Apply/Edit can replace that rejection using a fresh
worker/input/decision token; stale actions fail. Removed values are excluded from
this reconsideration path. This changes a deliberate local decision, with the same
portable saved/rejected/removed states; no archive or schema version change occurs.

Rule-4 identifier/staff proposals use the existing `text` kind and stable field
slots (`text:client-number`, `text:patient-number`, `text:invoice-number`,
`text:account-number`, `text:customer-number`, `text:reference-number`,
`text:staff-name`). Values keep text spelling/leading zeros, empty qualifiers and
reviewed page evidence; they never become numeric amounts or organization tags.
Existing format-2 readers preserve this supported kind without a format migration.

## Automatic details (0.8.5)

User-authorized detected defaults use existing `origin: accepted` / `state: saved`
with `acceptance_actor: automatic` in evidence; this distinguishes them from manual
confirmation without changing the format-2 contract. Explicit editing stores its
own manual/edited origin and retains historical source evidence. Known equivalent
legacy/manual labels and all existing field decisions block automatic reinsertion.
Completed OCR fills unowned slots transactionally; incomplete/truncated text and
editor drafts retain explicit review. No stored value is silently retyped.

For a bare dollar, current rules record `currency_basis: bare-dollar-default` and
`dollar_currency_default: USD`; explicit currency codes override it. This is a user
convention, not inferred country certainty. Old unknown-currency records and exact
legacy text remain as stored. Actor/basis data survive archive round trips.

The real original Google Stack transfer was explicitly authorized and completed
after deletion of current test documents through production batch UI. This was not a test restore or
a DB replacement. The original source app and private host backup remain.

## Local presentation and backup history (0.8.7)

Newest/Name document sorting and last successful backup timestamp are local view/history
preferences, excluded from this archive. A restore never marks a local backup as saved
or changes these preferences. Timestamp updates only after successful export close;
failed output and canceled review keep the prior value. Format3/schema8 and both
older readers are unchanged.
