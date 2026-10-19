# Stack desktop project plan

Current execution entry: [remaining-work playbook](STACK_DESKTOP_REMAINING_PLAYBOOK.md)
and [autonomous start prompt](STACK_DESKTOP_REMAINING_START_PROMPT.md). The user
requests headless agent testing and one final user handoff. Most M2 functionality
is delivered; current open items and source-matched evidence are in that playbook.
The planning baseline and starting-point table below are historical.

Planning baseline: 2026-10-17. The user has now authorized the first implementation
task; M0 is delivered with a documented shutdown stability watch. The native shell
and rendering spike exist, while persistence,
interchange and sync are later stages. The earlier
[work order](STACK_DESKTOP_NEXT_SESSION_PROMPT.md) supplies product requirements.
[Execution ledger](desktop/EXECUTION_LEDGER.md) records observed
state. [Contract draft 0.1](desktop/INTEROP_SYNC_CONTRACT_V0_1.md) defines the proposed
identity, interchange and merge rules.

## Product and delivery boundary

Build a native Rust/libcosmic document library for COSMIC on Pop!_OS. The first
release must be useful offline: import documents or a phone archive, browse stacks,
preview pages, search existing text, edit names/filing/notes/details, restart without
loss, and export a phone-readable archive. Preserve originals, saved page appearance
and order, exact metadata, provenance and explicit user decisions.

Design and test synchronization as part of the core from the beginning. The first
integrated desktop milestone ends at M4 below: a runnable persistent app, portable
archive interchange, and a real folder-carried exchange between two Rust replicas.
Android exchange follows at M5; convenient paired local-network exchange follows at
M6. All three are required project stages, with separate completion evidence.

Desktop OCR generation, camera capture, scanner editing tools, multilingual models,
cloud storage, mobile rewrites and other desktop platforms are later work. Existing
OCR and saved page edits must be readable in the first milestone. An unavailable
OCR engine must never prevent archive browsing, editing or export.

## Verified starting point

| Item | Observation and consequence |
| --- | --- |
| Repository | Git root `/home/charlie.day/Projects`, HEAD `3a23d5e`; Stack workspace `/home/charlie.day/Projects/Stack`. |
| Existing edits | README, Android build/UI/migration checks, feature matrix and phone guide are modified; icon/color follow-up files are untracked. Preserve them. The unrelated `cosmic-takeout` tree is outside scope. |
| Current Android source | `app/build.gradle` reports `0.8.9-dev`, code 22; database schema 8 and archive format 3. Older handoff references to 0.8.7 are historical. Icon/color evidence reports the later primary-phone updates. No device state was inspected in this planning session. |
| Desktop source | No `desktop/` application exists. No applicable AGENTS.md was found in the workspace or its ancestor directories. Recheck before implementation. |
| Rust | Installed rustc and cargo report 1.99.0. No compile was attempted. |
| COSMIC | `cosmic-session`/`cosmic-comp` exist; environment reports COSMIC, Wayland, `wayland-1` and display `:1`. An available display is not proof of a successful GUI run. |
| Cache | Independent libcosmic checkout cached at `dbc62f2d899cdbfbe43d170689ddc0290db663f4`, package 1.0.0, edition 2024, minimum Rust 1.93. Candidate only until compilation verifies compatibility. |
| Native dependencies | `pkg-config` finds Wayland client 1.23.1 and xkbcommon 1.6.0; it cannot locate fontconfig, freetype2, sqlite3 or poppler-glib. This does not establish that every runtime library is absent. |
| Android environment | `/tmp/stack-dev` contains the prior SDK/Gradle/signing environment. Its continued usability and disposable-device availability are unverified. |

Source requirements come from the archive contract, current Java archive/database/
metadata code, P1 evidence, unlock/library follow-up, phone delivery guide and icon/
color follow-up. The Android code is Java; cross-language fixtures should exercise
that implementation directly without introducing Kotlin for the fixture requirement.

## Architecture and decisions

Use a Cargo workspace under `desktop/`, initially with these boundaries:

| Component | Responsibility |
| --- | --- |
| `stack-core` | Domain values, SQLite migrations/repository, immutable originals, operation recovery, search and durable user mutations. No COSMIC types. |
| `stack-archive` | Strict streaming format-1/2/3 reader, format-3 writer, staged validation, additive restore planning, compatibility projection. |
| `stack-sync` | Identity, durable operations, causal merge, peer progress, conflicts and bundle validation. Independent of UI and network. |
| `stack-render` | Bounded image/PDF decoding, saved geometry/enhancement/flattening, thumbnails and PDF output. |
| `stack-desktop` | COSMIC navigation, editors, dialogs, task/status presentation and desktop integration. |
| `stack-cli` | Synthetic fixture creation and headless import/export/exchange/inspection for repeatable verification. No private-content logging by default. |

These can be modules before separate crates are justified, but core, merge and
rendering must remain testable without launching a window.

Use app ID `org.stackdocs.desktop`, standard XDG data/config/cache roots and an
explicit disposable-root override. Store SQLite, originals and recovery journals
in private application data; thumbnails and derived previews belong in cache.
Never share a live Android database or a SQLite/WAL file across devices. Prevent
two uncoordinated writers using an application lock and repository serialization.

Prefer SQLite through a maintained Rust binding with bundled SQLite to reduce
system-header dependence. Enable foreign keys and use a serialized writer; keep
each authoritative mutation, sync journal event and derived-index invalidation in
one transaction. Use an independent desktop schema, with fixtures for every upgrade.
Original files are immutable and never overwritten. Snapshot pins, staging ownership,
durable commit markers and startup recovery must cover the filesystem/DB boundary.

Use bounded background queues for decoding, hashing, transfers and indexing. Work
results carry document/page incarnation, input revision and decision tokens; stale
results cannot recreate deleted entities or replace later manual edits. Keep memory
bounded by page resolution, cache budgets and transfer buffers rather than file size.
Use cancellation before commit and truthful committed/canceled outcomes afterward.

Start from the official COSMIC application patterns and verify features against the
actual pinned checkout. Commit Cargo.lock and exact Git revisions; do not depend on
a floating toolkit branch. Record licenses and all native runtime dependencies.
The cached revision is a starting candidate, not an already approved dependency set.
The [official toolkit](https://github.com/pop-os/libcosmic) is MPL-2.0 and publishes
build requirements; use its [template](https://github.com/pop-os/cosmic-app-template)
and [API reference](https://pop-os.github.io/libcosmic/cosmic/) for implementation.

PDFium is the first renderer candidate. M0 must establish a maintained Rust binding,
reproducible offline runtime packaging and complete third-party notices; its
[license](https://pdfium.googlesource.com/pdfium/+/refs/heads/main/LICENSE) includes
additional component terms. Do not treat a dynamic wrapper as supplying the native
engine, download a renderer on first launch, or substitute an external viewer for
the required integrated preview. Record a renderer decision before M2 proceeds.

## Milestones and acceptance gates

| Stage | Deliverables | Required evidence to finish |
| --- | --- | --- |
| M0 — executable foundation | Dependency/license decisions; pinned Cargo workspace; synthetic headless harness; a COSMIC window with sidebar; PDF/image rendering spike. | Core tests run; app compiles and launches against the recorded toolkit; a synthetic multipage PDF and rotated image render; missing native dependencies have a reproducible build/install solution. |
| M1 — durable library and archive interchange | Migrations, originals, recovery, archive reader/writer, additive review/commit and source-identity mapping; search data persists. Every user mutation starts producing a local sync event. | Formats 1/2/3 import correctly; Rust format 3 round-trips the complete synthetic graph; invalid input leaves the library unchanged; kill/restart across restore boundaries preserves committed originals and metadata. |
| M2 — usable COSMIC desktop loop | Stack sidebar/catalog editor; document grid/list; PDF/image/scan preview with page navigation/zoom; search/filter/sort; editors; imports, archive export and PDF output; progress/cancel/retry. | Synthetic end-to-end import → edit → search later page → preview → restart → export works through the UI. Saved page transforms are rendered and retained. Narrow/large-text/light/dark and keyboard/focus states are inspected where runtime permits. |
| M3 — causal merge and conflict review | Durable IDs/journal/acks; deterministic merge; explicit identity adoption; conflict records/UI; deletion retention. | Two isolated replicas converge after independent edits and conflict resolution; replay, delivery permutations, sequence gaps, delete/edit and stale-worker cases preserve invariants. |
| M4 — integrated desktop milestone | User-selected folder exchange, operation/blob transfer, inbox/outbox status, two running Rust libraries, desktop launcher/install/run guide and evidence. | Real on-disk bundles exchange in both directions; interruption/restart/replay converges without duplicates or lost decisions. M0–M3 checks pass together; document exact GUI and platform evidence limits. |
| M5 — Android companion interoperability | Additive Android migration, mutation journal hooks, the same merge rules, portable bundle import/export, identity adoption and conflict UI in Checks. | Java-produced archives read in Rust and Rust archives read in Android; Java/Rust sync golden cases agree; actual disposable Android ↔ Rust exchanges converge with deletions, replay and interruption. |
| M6 — paired local-device transport | Explicit pairing, authenticated encrypted transport, consent, discovery/manual connection, foreground sync, revocation and OS-aware status/retry. | Disposable Android ↔ desktop LAN pairing and bidirectional sync run; unknown/revoked peers are rejected; reconnect and transfer interruption recover; permission/lifecycle cases and offline behavior are recorded. |

Critical path: M0 → M1 → M2 → M3 → M4 → M5 → M6. Specify the M3 contract during M1
so M2 edits never bypass the journal. Start Android compatibility fixture design in
M1 and renderer appearance fixtures in M0; neither can be left to final packaging.
Use coherent Stack-only increments. A UI scaffold or headless engine alone does not
complete the first integrated milestone.

## User experience of the first milestone

The main window has All documents and ordered stacks in a sidebar, search and add
actions in the header, a list/grid content area and a document preview/details view.
Selecting a search result opens the matching stable page. Newest/Name sort and
filters compose. Hidden stacks remain manageable without appearing on Home.
Stack accents follow the Android standard-category/custom-color behavior while
using COSMIC theme, navigation and window conventions.

Import accepts JPEG, PNG, WebP and multipage PDFs plus `.stackbackup`. Copy durable
originals before publishing document rows; show per-item outcomes for a batch.
Archive review explains additive copies, matching stacks, collisions and the fact
that another import adds copies again. Export offers a full portable archive and
document PDFs; these are distinct products with distinct metadata coverage.
Untouched PDFs export byte-identically. Edited documents render their saved page
appearance; image-based PDF output must state its selectable-text limitations.

Editors keep invalid or failed-save input available, display typed amounts/dates
without reformatting away precision, and preserve removed/rejected field decisions.
Notes, saved details and compatible current OCR text are searchable. OCR queued or
unsupported on desktop has an honest status, with existing valid text still usable.
Desktop need not offer crop/flatten editing yet, but existing transforms must render
correctly; missing support blocks that capability instead of silently flattening or
discarding its settings. Conflicted values remain visible and reviewable.

Exchange shows unpaired/offline, pending changes, transferring, retry needed,
conflicts and last acknowledged exchange. A queued operation is not a successful
sync. "Resolve" creates a new decision that causally includes the conflicting
values. Pairing, device policy and filesystem paths are never document metadata.

## Interchange and synchronization strategy

The [existing archive specification](STACK_ARCHIVE_FORMAT.md) and current Java
validator define compatibility. Preserve its binary envelope, exact allowlists,
version/schema pairs, original paths, limits, typed strings and safe old defaults.
Port duplicate-key/depth/integer/path/reference checks deliberately; an ordinary
JSON deserializer is insufficient. Match Java string-length and case-comparison
behavior through Unicode fixtures, and test currency-code acceptance differences.

Format 3 remains phone-readable and carries no new sync keys. Desktop storage keeps
source IDs separately from local restore collision remapping. A backup has no
globally unique library lineage or causal history: a fresh backup restore creates
a new replica and is never an automatic sync join. The proposed separate exchange
contract handles explicit pairing, baseline adoption and conflicts; see the
[draft](desktop/INTEROP_SYNC_CONTRACT_V0_1.md).

Use replica counters plus causal context, not wall time or Android local revision
numbers, to order changes. Preserve concurrent scalar values. Merge independent
field edits and membership deltas; review concurrent edits to the same value, whole
membership replacement, page or ordering. Retain deletion tombstones indefinitely
for the initial version. Never let an offline old peer revive a deletion. A document
deletion hides it but retains the last original and conflicting data until a later
explicit purge feature has its own retention policy.

Use a folder/USB-carried exchange first to prove semantics independently of network
discovery. Bundles are a separate format, not renamed `.stackbackup` files. M4 uses
explicitly reviewed local files and must not claim network authentication. M5 should
implement the small protocol in the existing Java repository and use shared golden
tests, avoiding an initial Android Rust/JNI toolchain unless a measured constraint
justifies that change.

For M6, prefer the desktop as an on-demand HTTPS endpoint and Android as the
foreground client. No daemon or cloud account is required. Pair by transferring
a public-key fingerprint through a user-verified QR/manual path; verify the peer
before exchanging any library content. Use maintained TLS implementations, pinned
peer identity and client authentication; never trust discovery names or custom
cryptography. Pin down enrollment, credentials and revocation in a reviewed transport
specification before exposing a listener. Keep discovery and listeners off until
explicitly enabled, and provide a manual local-address fallback.

Android currently has no INTERNET permission. LAN transport is therefore a scoped
Android capability change. Its target is currently SDK 36; official guidance says
SDK 37 adds LAN protection paths, including a system discovery picker or a runtime
permission. Recheck the target/device combination at M6 rather than adding blanket
permissions now. Follow [local-network guidance](https://developer.android.com/privacy-and-security/local-network-permission),
[NSD guidance](https://developer.android.com/develop/connectivity/wifi/use-nsd) and
[background-task guidance](https://developer.android.com/develop/background-work/background-tasks).
Ship explicit foreground Sync now first; background scheduling is a separate
capability and cannot promise continuous sync while either app is suspended.

## Verification and evidence

Create authored synthetic fixtures only. Cover every archive row and retained
original, older formats, removed pages, unusual identifiers, exact decimals, partial
dates, rejected/removed details, explicit empty filing, notes, tag relations, hidden/
ordered stacks and historical provenance. Include non-BMP text and case-folding
examples. Compare authoritative graph projections and original hashes across
Java → Rust → Java and Rust → Java → Rust; distinguish permitted claim/index
normalization from source values. Use the real Android archive implementation.

Reject duplicate/unknown keys, invalid enums, unknown version/schema pairs,
truncation, trailing bytes, corruption, oversize/depth/length/count breaches,
traversal/symlink attempts, invalid image/PDF references and mismatched relations.
Exercise full/archive disk limits with injectable writers and real disposable
filesystem failures where possible. Include process termination before and after
file publication, DB commit and acknowledgment. Clearly distinguish injected errors,
actual process stops, runtime observations and untested power-loss claims.

Test merge invariants across delivery permutations: no duplicate operations, causal
gaps stay pending, all replicas retain the same conflicts, independent changes
survive, resolution propagates, deletions do not resurrect, and delayed workers
cannot replace manual decisions. Include three-replica forwarding and re-pairing
after replica replacement, not just two-replica happy paths.

Run `cargo fmt --check`, locked check/test/clippy/build for the delivered workspace
and configurations. Measure synthetic maximum-count libraries, slow imports, search
and bounded preview/transfer memory; set numeric budgets after M0 measurements and
record hardware, rather than inventing a performance pass. Keep UI tasks responsive
while work proceeds. Record renderer pixel/geometry comparisons separately from
human judgment about page appearance and accessibility.

Run affected Android tests only when Android integration/fixtures change, using
`org.stackdocs.app.checks`. Do not restart the completed H1–H8 session. Never clear,
uninstall, reseed, restore into or replace canonical Android data as a test. Preserve
the original signing environment and originals/drafts/consolidation recovery. Any
later authorized canonical update needs fresh signature and row/aggregate checks,
normal package build and `adb install -r`; historical counts are never targets.

Record results in `docs/STACK_DESKTOP_EVIDENCE.md` and `docs/desktop/`, with fixture
versions and exact commands. Keep binaries, personal content, screenshots, keys,
build caches and generated private archives out of Git. Public synthetic fixtures
and expected projections may be versioned with their authorship/license.

## Principal risks and bounded decisions

| Risk | Planned resolution |
| --- | --- |
| Toolkit revision/API drift and missing headers | M0 compiles an actual app using the cached candidate or a justified newer pin; record exact dependencies and any environment blocker. |
| Saved appearance differs across renderers | M0/M2 compare synthetic Android and desktop geometry/enhancement/flattening, including scan pages; port existing independent Stack algorithms with provenance. |
| A valid Rust archive fails Java validation | M1 golden cases exercise actual Java allowlists, string semantics, payloads and evidence; full Java runtime round-trip is required for the phone-interoperability claim. |
| Existing phone/desktop backups lack sync lineage | Explicit baseline adoption with reviewed source-ID mapping; divergent prior edits become conflicts, and uncertain identity stays distinct. |
| Late/deleted/reordered changes lose decisions | Causal context, retained alternatives/tombstones, deterministic provisional display and user-reviewed resolution; test arbitrary delivery order. |
| Android integration misses a mutation path | Inventory import/edit/worker/delete/restore/stack operations; journal within the same DB transaction and test every path. |
| TLS pairing or permissions delay LAN work | Complete portable exchange and Android bundle tests first; record transport blocker honestly and keep foreground/folder use available. |
| Large scope masks partial completion | Close each milestone only with its acceptance evidence; portable transfer, Rust sync, Android sync and LAN sync remain separate ledger states. |

Do not provide calendar commitments before M0 measures dependency and rendering
work. The highest uncertainty is saved appearance and cross-language sync adoption,
not basic library navigation. Reassess estimates after M0 and M1 using actual results.

## First implementation increment and handoff

M0 source/build/runtime evidence is recorded in [desktop evidence](STACK_DESKTOP_EVIDENCE.md).
M1 backend/CLI and its qualified compatibility evidence are recorded in
[M1 evidence](desktop/M1_EVIDENCE.md). Use the [M2 handoff](desktop/M2_HANDOFF.md) and
[M2 execution rulebook](desktop/M2_RULEBOOK.md). The next increment is M2: integrate the durable
repository into COSMIC, saved appearance, editors, output and progress/cancel. Carry
M0’s shutdown watch and M1’s device/runtime compatibility gates forward. Proceed through M4
without treating routine milestones as permission checkpoints. Re-read the ledger
after compaction and update evidence as each gate actually passes.

Installation guidance must give clean build/run commands, app identity/data paths,
renderer dependencies, offline behavior and disposable test-root examples. A future
user installation is separate from an isolated GUI run. Refresh the next-session
prompt around the actual unfinished gate, preserving the original work order's
requirements and all unrelated edits.

## One consolidated user-dependent checklist for implementation closeout

These checks are deferred until the corresponding capability exists; none blocks
the current planning deliverable or independent implementation work.

- [ ] Complete the final [M2 visual/interface review](desktop/M2_REVIEW.md) after
  headless engineering checks: actual chooser import/save/cancel and focus return,
  stable Manage stacks → Back/sidebar navigation and mockup-aligned grid/list,
  preview/information/editor sections (see [interface review](desktop/M2_INTERFACE_REVIEW.md)),
  keyboard/focus, individual-detail draft switches, keep/discard quit choices, palette/color
  saves, offline text states/matching-page navigation, notes/preview, paging, restart/export, light/dark/narrow/
  enlarged layouts and any frame/shutdown failure. The agent has completed the
  readable-PDF/native screenshot comparison recorded in that review. The user
  performs final interaction acceptance; report failures before full M2 acceptance.
- [ ] Review later M4 exchange/conflict presentation when implemented.
- [ ] Perform deliberate pairing/permission and real unlock interactions after M6;
  review offline, cancel/retry, revoked-peer and conflict presentation.
- [ ] Choose any real library transfer or installation only after a runnable build,
  complete compatibility evidence and concrete instructions are ready. Use personal
  content only for that chosen operation, never as regression fixtures.
