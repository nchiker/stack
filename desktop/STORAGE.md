# M1 repository and archive contract

`stack-core::library::Library` owns the serialized SQLite writer and a process lock.
Every call uses an explicit root; `default_root()` derives the application data path
from absolute `XDG_DATA_HOME`, or `$HOME/.local/share/org.stackdocs.desktop`.
The native window opens this repository through one background owner in library
mode. Bounded browser projections omit full-library OCR/graph copies; saved-page
selection uses document tokens, stable page IDs and original source indexes.

## Owned storage

An empty root receives a versioned `stack-desktop-library` marker, mode 0700,
`writer.lock`, `library.sqlite3`, `originals/` and `operations/`. Existing unrelated
directories, links and foreign/future databases are rejected. SQLite uses bundled
SQLite, application ID `0x53544b44`, desktop schema 4, foreign keys, WAL and FULL
synchronous writes. SQLite/WAL files never leave the local library.

Schema 1 stores complete portable document/object rows, immutable restore receipts,
the original source manifest, local document incarnations/revisions and derived FTS.
The additive schema-2 migration adds entity sync IDs, source aliases, local journal,
tombstones and a reserved peer-progress table. A populated schema-1 upgrade is tested.
No Android schema migration or database sharing is involved.

Authoritative rows preserve exact typed strings and historical decisions, including
removed/rejected detail slots and source evidence. FTS stores title, notes, saved
details and current OCR text. Page hits retain stable page IDs. Each mutation updates
rows and FTS together; reopen reuses persisted indexes. `rebuild_search()` repairs
derived data without creating a user decision. Schema3 adds a local durable text queue with document-incarnation, page-revision, configuration and per-attempt guards. The bounded offline worker extracts PDF text or recognizes the final saved page with pinned English Tesseract. Schema4 adds separate experimental causal state and peer receipts. Neither table extends portable archives.

Originals are private, immutable files, normally mode 0400. Renaming, editing,
reordering and deleting pages never rewrite their bytes. Document deletion hides
rows, records a tombstone and retains the last originals and metadata. Deleted IDs
remain reserved. There is no purge feature. Explicit empty filing remains a decision.
Document tokens contain ID, incarnation and revision; stale editors/workers fail.

## Staging, review, commit and recovery

Archive formats 1/2/3 are checked before library rows change: exact header/version/
schema/row keys/types, duplicate JSON keys, depth/token/string/count/byte limits,
canonical paths, relationships, source lengths/hashes and actual source MIME/PDF
page counts. Older defaults are applied after validation. Buffer size is 64 KiB for
original transfers; metadata is bounded to 32 MiB. Each file requires 16 MiB free
reserve in addition to its declared bytes. Native PDF inspection uses the pinned
single-worker PDFium engine; image inspection reads dimensions/type without full
pixel decoding. This is not native-parser process isolation.

Review computes additive document/page/scan collision mappings, matching stack/tag
names and preserved catalog visibility/order. Only an untouched starter catalog is
replaced. Source aliases and raw source manifest stay local and preserve provenance.
Current decision evidence and known page/stack references are remapped; historical
unknown evidence stays historical. Restore never adopts a sync lineage. Selecting
the archive again adds another copy. A retry of the same committed operation returns
its stored receipt. A changed library or newly occupied original destination makes
an old review stale.

Commit writes and syncs an ownership journal, adds owner markers to staged original
directories and publishes each directory with `RENAME_NOREPLACE`. One SQLite
transaction then installs the complete graph, source aliases, receipt and local
event. Startup deletes only provably owned uncommitted publications, or completes
cleanup after a committed marker. Uncertain ownership fails closed. These boundaries
have actual SIGKILL/restart tests with an existing synthetic library.

Snapshots pin immutable originals and metadata. A snapshot or staged review holds
the writer-lock lease even if its `Library` is dropped, so a new process cannot
mistake a live snapshot for abandoned work. Export emits format 3 through a private
partial file, syncs it and publishes without overwriting a destination. Recovery
removes a partial only when its path/device/inode still match its journal. A failure
after publication reports that the output was published. An immediate process stop
between external partial-file creation and its ownership journal can leave an empty
uniquely named `.part` file; it is never published or automatically deleted without
ownership proof. Power-loss/filesystem-controller behavior is not established by
process-stop tests.

## Local journal, not yet an exchange protocol

Bootstrap, restore/import, names, notes, filing, details, page appearance/order/text,
stack catalog (including color) and deletion all append `org.stackdocs.local-change` version 1 in the
same transaction as saved rows. Events have library/replica UUIDs, increasing replica
sequence, own-replica causal context, stable entity bindings and a SHA-256 digest.
Rollback consumes no sequence number. Source IDs, local IDs and stable sync IDs are
separate. Page/stack removals retain tombstones; detail removal retains its slot.

This local recording format is an M1 implementation detail. It is not the frozen M3
wire contract, a merge implementation, acknowledgment, pairing or successful sync.
Portable format 3 carries no extra sync, policy, lock, device or path fields.

## Compatibility evidence limits

The host harness extracts production Java validator/writer/helper bodies verbatim
(only two method visibility modifiers change). It compiles complete `ArchiveJson`,
`PageGeometry`, `PageState` and `DetailValue`, production suggestion payload decoding,
name cleanup and the production evidence digest. A pinned Apache-2.0 AOSP JSON
implementation supplies Android JSON number/string behavior. The host envelope
reader and two small platform shims are identified in the harness source. Full
graph/original comparisons cover both directions and all three versions.

This proves host Java metadata/writer compatibility for the tested cases. It does
not run Android BitmapFactory/PdfRenderer, SQLite restore, claim normalization or
lifecycle behavior. No device was connected in this session. On-device archive
restore and sync remain M5 verification gates.

Rust rejects lone escaped UTF-16 surrogates instead of silently replacing them;
Android JSON can retain those unusual strings. This is an explicit safe compatibility
restriction. Currency acceptance is pinned to the 233 codes observed in the recorded
host JDK; Android release-specific currency/Unicode tables still need device golden
checks. Ordinary non-BMP text and UTF-16 evidence lengths are tested across languages.
Case matching follows simple Java-style upper/lower comparison; rare Unicode-version
differences across runtimes remain a device check. Do not claim arbitrary archive
equivalence beyond these documented limits.

## Experimental causal exchange

`stack-sync` has a separate versioned operation/envelope contract and retained
alternatives/history. Baseline creation and reviewed nonempty adoption, durable
inbox state, immutable pending blobs and monotonic peer receipts remain outside
portable metadata. Source/local fingerprints reject stale or replaced adoption
inputs. Independent membership deltas merge; complete filing and whole page/catalog
orders retain concurrent alternatives. Deletion retains originals and prevents
resurrection by offline edits or stale text. Actual process-stop/replay checks cover
original installation, state commit, receipt recording and bundle publication.

Native conflict/adoption/file-exchange controls use the existing serialized writer;
review lists have bounded pages. Paired foreground TLS also borrows that same
repository owner, never opening a second writer. Networking is explicitly enabled
per session, with cancellation and durable-state refresh after socket failure.
Pairing credentials stay in private device-local files outside archives and bundles.
Current network evidence is Rust localhost peers plus native headless controls.
Android runtime, physical pairing and native interaction gates remain separate.
See the [executable protocol](../docs/desktop/INTEROP_SYNC_PROTOCOL_V1.md),
[paired transport](../docs/desktop/PAIRED_TRANSPORT.md) and
[current results](../docs/desktop/REMAINING_RESULTS.json).
