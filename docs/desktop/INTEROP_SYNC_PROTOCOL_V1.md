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
