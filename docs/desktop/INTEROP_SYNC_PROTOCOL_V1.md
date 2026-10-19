# Executable exchange protocol 1 — implementation subset

2026-10-18. Experimental implementation in `stack-sync`, with the same causal
kernel in production Java `SyncProtocol.java` and five shared golden cases in
`desktop/protocol/golden-v1.json`. This does not close the full M3/M4/M5 gates.
The broader requirements remain in [draft 0.1](INTEROP_SYNC_CONTRACT_V0_1.md).

Archive version 3/schema 8, desktop schema 4, Android schema 8 and wire version 1
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

Graph cells use an explicitly adopted baseline's portable identities: table plus
canonical JSON row-key array. Document title/provenance and notes/revision are
separate decision cells. Other portable rows retain their full supported values.
This subset does **not** yet implement the complete stable-entity mapping, atomic
whole-page/catalog ordering decisions, reviewed existing-library adoption or every
cross-row conflict rule required by M3. Such gaps must not be represented as full
protocol conformance. The current baseline API requires an empty receiving library
and the explicitly reviewed complete member list. It refuses nonempty adoption;
titles, matching bytes/hashes and source IDs do not silently join identities.

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
it provides no sender authentication or confidentiality. No network listener,
pairing, TLS or Android synchronization is implemented by this subset.

Observed conformance includes Rust causal permutations/gaps/replay/resolution,
five shared host Java/Rust cases, envelope corruption/truncation/path validation,
and actual three-library SQLite/file exchange with originals/deletion retention.
See [remaining evidence](REMAINING_EVIDENCE.md). Android SQLite, renderer, lifecycle,
authoritative journaling, file exchange and actual device convergence remain M5.
