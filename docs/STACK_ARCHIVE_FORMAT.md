# Stack portable library archive — format 4 (formats 1 to 3 readable; format 3 still written when there are no link records and no encryption)

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

## Format 4 and the encrypted envelope (implemented on the phone 2026-11-21; reviewed by a higher-tier agent before coding; desktop reading is follow-up 3b)

Two independent layers. Older apps and the desktop refuse both by magic.

### Inner stream: archive format 4
Format 3 layout unchanged (`STACKBK\x01`, manifest length, SHA-256, manifest, entries) with manifest `version: 4`
(`schema` stays 8 unless the link tables need a bump) and two additions: a `links` array and `previews/<canonical UUID>.pdf`
entries in `files`. Link row fields (all required, strict, unknown fields rejected): `id, owner, link_id, kind, title, mime,
modified, hash, shared, protected, locators, state, title_user_edited, added_at, text`. `text` up to 1 MiB (as OCR text).
`state` is one of `live`, `unreachable`, `missing`, `trashed`; any other value is refused. A writer leaves out links in Recently deleted (`trashed`), so they are never exported. A reader accepts all four but, whatever the value, stores every restored link as `unreachable` (its `saf`/`owner` locators belong to another device or time; the next check settles it). Lifecycle columns (`state_since`, `checked_at`, `revision`, `removed_reason`) are local and not exported; no format bump.
Limits: 1,000 links; preview at most 32 MiB; previews count toward the 8 GiB total. A library with no link records may
still be written as format 3. Restore: a link whose `(owner, link_id)` exists locally is kept as is (never overwritten,
same or different hash) and counted as skipped in the report; others are inserted with new row ids inside the existing
single SQLite transaction, journal and owner-tagged staging used for documents.

### Outer layer: encrypted envelope
All integers big-endian. Primitives: `javax.crypto` only (AES-256-GCM, PBKDF2WithHmacSHA256, HmacSHA256).

Header (the "header bytes" H, everything up to and including the last slot):
1. Magic, 8 bytes: ASCII `STACKEN` followed by the single byte `0x01` (envelope version).
2. `kdf_id` u8 (1 = PBKDF2-HMAC-SHA256), `iterations` u32 (reader requires 600,000 to 2,000,000; anything else is rejected before any key work), `salt` 16 random bytes.
3. `slot_count` u8 (2 now; reader accepts 1 to 4, so a partner slot can be added later), then each slot:
   `slot_type` u8 (1 passphrase, 2 recovery key, 3 reserved for the partner), `nonce` 12 random bytes,
   `wrapped` 48 bytes (AES-256-GCM of the 32-byte data key, with its 16-byte tag). GCM uses `GCMParameterSpec(128, nonce)`;
   ciphertext is always ct||tag. A duplicate `slot_type` is rejected, unknown types are skipped, and a file with no usable
   slot reports "no key for this backup".
   Slot AAD = magic || kdf_id || iterations || salt || slot_type.
4. `header_hash` = SHA-256(H), 32 bytes, stored after H; the reader recomputes it and rejects a mismatch.

Keys: `data_key` = 32 random bytes, new for every backup file. Passphrase slot key =
PBKDF2-HMAC-SHA256 over the exact NFC UTF-8 bytes of the passphrase (no trimming, empty rejected), implemented directly over
`Mac("HmacSHA256")`, never `SecretKeyFactory`/`PBEKeySpec` (providers differ in how they turn chars into bytes); salt, iterations, 32 bytes. Recovery slot key =
HMAC-SHA256(key = recovery key bytes (32), message = "stack-backup-recovery-v1" (24 ASCII bytes, no terminator) || salt). Recovery key input: hex, case-insensitive, spaces and dashes
removed, exactly 32 bytes. A reader tries the slot types it has
a secret for; a failed GCM tag means wrong secret.

Body: the inner stream in chunks of 65,536 plaintext bytes. Every chunk except the last is exactly 65,536 bytes; the last is
1 to 65,536 bytes. Each chunk is stored as ciphertext || 16-byte tag with
nonce = 4 zero bytes || chunk_index u64 (from 0), AAD = header_hash || chunk_index u64 || last_flag u8 (1 on the final chunk).
The reader reads up to 65,552 bytes (chunk plus tag). If it gets fewer, or exactly 65,552 followed by end of file, the chunk is
final (`last_flag` 1) and must be at least 17 bytes; otherwise `last_flag` is 0. The writer holds back one chunk to set the flag.
Any tag failure is an error: truncation, reordering, replacement, a flipped flag and an empty body all fail. The reader rejects
a `chunk_index` above 131,200 (about 8 GiB plus manifest allowance). Order of work: bounds, `header_hash`, one PBKDF2,
unwrap, chunks. `header_hash` is an unkeyed checksum; the header counts as authentic only once chunk 0 verifies.
Decrypted bytes may be parsed and staged, but nothing commits until the final chunk's tag has verified; a failure discards
the staging. After decryption the inner
stream must start with `STACKBK` and is validated in full exactly as today before anything touches the library.
The manifest and file table are inside the encryption.

### Keys and policy
- Recovery key: 256 random bits, shown once as 64 hex characters, with a confirm-back step. Stored by the user via the Key
  Vault. Stack never stores it.
- Manual backup: asks for the passphrase (and the recovery key, only on the first setup, to produce the recovery slot).
- Automatic backup runs unattended. At setup Stack derives the two slot keys once (passphrase key, recovery slot key) and
  keeps them sealed by a non-exportable Android Keystore AES-GCM key, together with the salt and iterations. Each backup
  generates a fresh data key and wraps it under those stored slot keys with fresh nonces. The passphrase and the recovery key
  themselves are never stored. A restore on a new phone needs the passphrase or the recovery key, never the Keystore.
- Salt and iterations belong to the setup; a passphrase change makes a new salt, new slot keys and a new Keystore entry. The
  Keystore key is non-exportable, `setUserAuthenticationRequired(false)` (needed for unattended runs), excluded from app
  backup, and the Keystore chooses its own IV. If it is lost or invalidated (`KeyPermanentlyInvalidatedException`), automatic
  backup stops and notifies; it never falls back to plaintext.
- Downgrade: restoring an unencrypted archive shows "not encrypted, not authenticated" and needs explicit confirmation;
  automatic and partner paths accept only envelopes. Inside an envelope the inner manifest must be `version: 4`. The restore
  review shows the manifest creation date (no rollback detection beyond that).
- Errors: header or chunk failures say "damaged"; all slots failing says "wrong passphrase or recovery key, or damaged".
- Test vectors: non-ASCII passphrase whose NFC form differs from its input; bodies of 1, 65,536 and 65,537 bytes; three
  slots; truncation at a chunk boundary; flipped `last_flag`; tampered header; reordered chunks.
- Deferred (partner slot, slice 7): add `commit = HMAC(data_key, "stack-commit-v1" || header_hash)` to the header so a slot
  holder cannot craft a slot that opens to a different data key.
- Backups that contain link records are encrypted by default; Stack asks before writing an unencrypted one.

### Implementation notes (phone)
- Code: `BackupEnvelope` (pure Java), `BackupKeys` (Keystore-sealed slot keys), `LinkArchive` (rows, strict validation),
  `LibraryArchive.snapshot(progress, syncInternal, linkMode)` with `LINKS_NONE` (format 3), `LINKS_INCLUDED` (format 4 only if
  links exist) and `LINKS_ENCRYPTED` (always format 4, empty `links` array if none). Sync snapshots never carry links.
- Link text lives in the manifest, so the existing 32 MiB manifest limit bounds the total link text; a larger library fails the
  backup with the existing "metadata limit" message rather than truncating.
- Restore: link rows get new local ids (deterministic per operation); previews are moved into `files/links/` before the single
  SQLite transaction and listed in the restore journal (`links`), so an unfinished restore removes them. A link whose
  `(owner, link_id)` exists is kept and counted as skipped. The restore review names both counts.
- Policy as built: with encryption set up, every backup (manual and automatic) is encrypted; a configured but unusable Keystore
  key fails the backup (never plaintext). Without it, backups leave links out; a manual backup of a library with links asks:
  set up encryption, leave them out, or include them unencrypted. Automatic backup never includes links unencrypted.
- Encryption setup (Backup & restore, "Backup encryption"): passphrase (at least 12 characters, twice), the recovery key shown once
  as 64 hex characters, and a confirm-back of its first 6 and last 6 characters before anything is stored.
- Restoring an unencrypted archive adds "not encrypted, so it cannot be checked for tampering" to the review text.

- Desktop (3b, 2026-11-21): `stack-archive` reads format 4 and the envelope (`crypto.rs`, `links.rs`, `read_encrypted_into`: the staging
  directory exists only if the final chunk's tag verified; inside an envelope the inner manifest must be version 4). `stack-core`
  decides by the first 8 bytes: `STACKEN\x01` needs a key (`BackupKey`: the 64-character recovery key, or a passphrase), `STACKBK\x01` is read as
  before and a key given for it is ignored; the review says "not encrypted, so it cannot be checked for tampering" for plain files. A missing
  key says "No key for this backup", a wrong one "Wrong passphrase or recovery key, or damaged.". Link rows go to their own tables
  (`link_records`, `link_search`, schema 5) and previews to `links/<row id>.pdf`, outside the document graph and the sync graph, so nothing that
  expects pages can meet one. Restore follows the phone: new row ids, state always `unreachable`, a link whose `(owner, link_id)` exists is kept and
  counted as skipped, previews are moved into `links/` after the restore journal (`links` list) is written and before the single transaction, and
  an unfinished or failed restore removes them. The receipt carries `links_added`, `links_skipped`, `encrypted`. Linked documents show in All
  documents and search (title and saved text, labelled "Linked document"), open to a read-only view (saved preview page 1, saved text), with no
  Open and no editing in the UI; the stored columns keep the locators, hash and flags so a desktop editor could use them later. The desktop does
  not sync link records (slice 6); its exports are described under "Desktop writer" below. The key is typed or pasted into a
  password field (desktop) or read from a file or stdin without echo (`stack-cli archive ROOT restore FILE --saved-key|--key-file PATH|--key-stdin|
  --passphrase-file PATH|--passphrase-stdin`; never a command-line value), held zeroed-on-drop, and never printed, logged or stored.
  Saved key (2026-11-21): the desktop keeps the recovery key itself, like the phone. Backup & restore shows one short row: "Backup key: saved" with a
  "Remove" button (the 12-character fingerprint, the phone's: first 6 bytes of SHA-256 over the lowercase hex text, is only a tooltip;
  `stack_core::keystore::fingerprint`), "Backup key: not set", or "Backup key: unavailable" (locked or no store; opening the menu never unlocks the
  store). Pasting lives only in the import panel. Key text (pasted or typed) is parsed by one function: 64 hex characters, optional `stackkey1:` prefix,
  spaces and dashes ignored. Storage is the OS secret store through `oo7` (`keystore::SystemStore`: Secret portal inside a Flatpak, otherwise Secret Service)
  behind the `keystore::SecretStore` trait (tests use `MemoryStore`); exactly one entry is kept (set replaces, several or a non-key entry is an error).
  Opening an encrypted backup uses the saved key with no prompt; with none saved, or a saved key that fails, the key panel opens ("Paste key from Key Vault"
  continues straight to the review; typing the key or passphrase is the fallback). A pasted or typed key is saved (replacing the old one) only after it
  opened an encrypted backup, never for a wrong key or a plain archive (core refuses a key for a plain archive). The clipboard answer is matched to its
  request. A clipboard manager may keep a copy of the pasted key. New computer: Key Vault "Copy Stack key", then Import backup, then "Paste key from Key Vault".
  CLI: `stack-cli archive ROOT ... --saved-key` uses the same store. A Flatpak build keeps the key in its own sandboxed store, so a host-side
  `stack-cli --saved-key` will not see it (use `--key-file`/`--key-stdin`, or paste in the app). The Flatpak has no keyring permission. The earlier Key Vault
  launcher was removed (it needed a dev-machine path and cannot run in the Flatpak sandbox).
  **Setting up a new computer:** in Key Vault click "Copy Stack key"; in Stack choose Import backup, pick the backup, and click "Paste key from Key Vault"
  (it opens the backup for review and saves the key on this computer). The fingerprint (menu tooltip) should match the one Key Vault or the phone shows. After that
  encrypted backups open without asking. The Backup & restore menu shows the key status and a Remove button.

- Desktop writer (2026-11-22): the desktop export writes what the phone writes. `Library::prepare_export_snapshot` reads the `link_records` rows in the same
  repository call as the document graph (so rows and graph are one view); `SnapshotWork::pin_for(encrypted, ...)` then hard-links (or copies) each
  `links/<row id>.pdf` into the private snapshot as `previews/<row id>.pdf`, hashes the pinned copy and adds its `files` row, sets `version` 4 and the
  `links` array, and validates the whole graph again (`Manifest::from_value`, the reader's own checks). Link rows carry exactly the `LinkArchive.FIELDS`
  columns, with the local row id as the archive id; lifecycle-local data is never exported (the desktop has none besides `preview`, and `state` is
  written as stored: `unreachable` after a restore). More than 1000 links fails the export with a plain message. Without linked documents and without
  encryption the file stays format 3 exactly as before. Sync, per-document and PDF snapshots never carry links.
  No key store (2026-11-22): when the secret store cannot be reached at all (`keystore::StoreError::Unavailable`: no Secret Service, portal or D-Bus service), the desktop export proceeds plain and the saved message says "Not encrypted (no key store on this computer)". A reachable store that fails (locked and not unlocked, denied, a bad entry) still stops the export and writes nothing; no key saved on a reachable store is plain with "no backup key is saved". `stack-cli archive ROOT export FILE --saved-key` never falls back to plain: an unreachable store is an error there.
  Encryption: `FrozenSnapshot::write_encrypted` wraps the same stream with `crypto::encrypt_with_recovery_key`: one recovery slot (`slot_count` 1),
  a fresh 16-byte salt and data key from the operating system (`getrandom`, through rustix's `rand` feature; no new crate), 600,000 iterations (unused by
  a recovery slot but part of the header), slot key derived from the recovery key and zeroed. An encrypted file always holds format 4 (an empty `links`
  array when there are none, as on the phone), because the reader refuses anything else inside an envelope. A random-source failure aborts the export.
  Policy: the desktop export encrypts with the saved backup key when one is saved and writes a plain file when none is; a saved key that cannot be read
  stops the export (nothing is written) rather than silently writing plain. Export never accepts a passphrase and never asks for a key. The saved message
  says "Encrypted with the saved backup key" or "Not encrypted (no backup key is saved)" and how many linked documents were included.
  CLI: `stack-cli archive ROOT export NEW_FILE [--saved-key]` (no key on the command line; without the flag the file is plain).
  Tests: `stack-core/tests/export_links.rs` (round trips plain and encrypted, exact columns, wrong or missing key refused, format 3 without links,
  random salt), `stack-desktop` controller test for the saved message.

### Shared test vector (envelope v1)
Inputs: salt = bytes 100..115; passphrase `correct horse`, 600,000 iterations; recovery key = bytes 200..231 (slot key =
HMAC-SHA256 as above); random source = a counter returning bytes 1, 2, 3, ... in the order data key (32), passphrase-slot nonce
(12), recovery-slot nonce (12); plaintext = 70,000 bytes with byte i = (i*31+7) mod 256. Output length 70,216 bytes, SHA-256
`d5ca2409e2a27e2ca27a8fbd0322c4bdc8afaff0d04fc18beb40ff6cc27d0c8f` (same on a host JDK and on the phone; `EnvelopeChecks`).

### Update (0.17.3): key-only setup
Setup no longer needs a passphrase. Scanning the recovery key from Key Vault (`keyvault key-qr`, or the Key Vault app's
"Stack backup key (QR)") creates an envelope with only the recovery slot (`slot_count` 1); the reader already accepts 1 to 4
slots. A passphrase slot remains available under "Other options". The Keystore-sealed record carries a flag byte for whether a
passphrase slot exists. Restore of a key-only backup uses Scan key (or the typed 64-character key).
