# M2 execution rulebook

Applies to the native COSMIC desktop workflow in the [M2 handoff](M2_HANDOFF.md).
The [project plan](../STACK_DESKTOP_PLAN.md), [archive contract](../STACK_ARCHIVE_FORMAT.md)
and [storage contract](../../desktop/STORAGE.md) remain the technical requirements.
User instructions take precedence; this document adds no routine approval gates.

Execution amendment (2026-10-18): the user directs completion of all remaining
engineering and automated checks headlessly, with visual checks and interface
review left until the end for the user. Track engineering readiness separately
from that final review; open no agent-driven GUI test windows. Prior native evidence
and platform qualifications remain explicit.

Visual-refinement amendment (2026-10-18): the user's later explicit instruction
requires running the app, comparing native screenshots with the supplied HTML,
and using readable sample PDFs. This supersedes the earlier headless-only window
restriction for this scoped UI review. Capture only the app's own test window,
using independently authored PDFs and isolated XDG/library roots; preserve existing
user windows, theme settings, stored libraries and all other data boundaries.

Latest testing amendment (2026-10-18): during the import-menu follow-up the user
again requests headless testing. No further agent-driven GUI test windows may be
opened. The preceding native workflow had already finished and closed; retain that
historical evidence without rerunning it. The remaining-work playbook reserves user
questions and actual visual/device review for one handoff at the end. Its kickoff
prompt supplies the work order for later stages when invoked; routine engineering
and milestones do not require interim user approval.

## 1. Outcome and scope

Deliver a persistent native desktop library through which a user can import, browse,
search, preview, edit, restart and export supported saved documents. Keep libcosmic
and the existing Rust crates. Use real native controls, dialogs and repository calls.
A runnable demo, static screenshot or headless engine alone does not finish M2.

Existing archived OCR text is useful immediately. New desktop OCR/camera capture,
causal merge, exchange bundles, Android companion changes, LAN and release packaging
are separate stages. Preserve their future requirements without displaying them as
working capabilities. Adding an OCR engine or implementing new crop-handle tools is
not required to display and export existing saved page appearance correctly.

This preparation task creates instructions only. The next-session implementation
prompt authorizes M2 execution; no M2 acceptance row is passed by this document.

## 2. Preservation and data boundaries

- Recheck Git and applicable instructions; preserve every unrelated dirty file.
  Stage only the M2 increment, including selective hunks in shared documentation.
  Do not modify or borrow `cosmic-takeout`.
- All development imports, edits, deletes, exports, screenshots, failures and
  interruption tests use independently authored synthetic inputs and disposable
  explicit library/XDG roots. Keep private content, binaries, caches and captures
  out of Git. Do not terminate a user's existing window to free a test resource.
- Never clear, uninstall, reseed, restore, replace, migrate or export the canonical
  Android library as a test. Preserve originals, drafts, recovery, lock policy,
  signing identity and completed H1–H8 work. M2 needs no phone access or Android build.
- The supplied phone reference is the private archive
  `/home/charlie.day/Downloads/Stack-0.8.7/Google-Stack-Migration-final.stackbackup`.
  Keep it unopened for normal M2 development: production Java and synthetic fixtures
  already supply the required contracts. Its contents/freshness are unverified.
  If an independent need for read-only archive metadata inspection arises within
  the user's instructions, bound the read and expose only structural findings;
  never log titles, OCR, notes, typed values, evidence excerpts or original bytes.
  Do not call a staging/import command merely to inspect it, since those copy content.
- Never turn that archive's rows/originals into fixtures, capture its contents, or
  commit dumps/values. Derive public examples independently. Real document browsing/
  transfer is a separate user-chosen operation, with a concrete additive review.
  This personal Google migration archive remains excluded from regression inputs.
- If the user later identifies a separate raw SQLite copy, keep inspection schema-only
  and read-only/query-only. Do not modify its sidecars or assume a main file without
  required WAL/SHM is a coherent snapshot. Use a private temporary coherent clone
  only if tooling requires one; do not use `immutable=1` to ignore needed WAL.
  SQLite layout is defined by production `LibraryDatabase.java` and its migrations.
  None of these optional references blocks independent M2 work.
- Keep archive formats/version pairs and limits unchanged. Any required desktop
  migration is additive and tested with populated prior-schema data. No SQLite/WAL
  sharing across devices and no sync keys in portable format 3.

## 3. Execution order

Finish vertical slices in this order. Small coherent commits are useful; they are
not permission checkpoints. Update the ledger after each slice and resume the next
unfinished item across context compactions.

| Task | Implement | Acceptance before marking delivered |
| --- | --- | --- |
| M2.1 persistent browser | Explicit library-root option, background repository worker, saved list, ordered stack sidebar, selection, stored page sequence and basic preview. | GUI reads a synthetic M1 library; close/reopen preserves IDs/catalog/page order; busy/empty/error states work. |
| M2.2 saved appearance | EXIF/upright coordinates, perspective crop, user rotation, flattening and enhancement through one saved-page rendering path. | Authored geometry/pixel fixtures pass; transformed scans and later PDF pages display without changing originals. |
| M2.3 search/catalog | Search/filter/Newest/Name composition, matching stable-page selection, stack create/rename/color/visibility/order/delete. | Later-page hit opens that page; notes/details/current OCR participate; hidden stacks remain manageable; catalog persists and mutations journal. |
| M2.4 editors | Title, notes, multiple/empty filing, typed details/removals, provenance view, saved page rotation/reorder/removal where exposed. | Exact values survive restart; failed/stale save retains draft; edits/removals journal; deletion retains last original. |
| M2.5 import and archive | Native file picking, JPEG/PNG/WebP/PDF batch outcomes, archive stage/review/commit, full portable export and progress/cancel/retry. | GUI import/review/export round-trip works; cancel/invalid input leaves saved state unchanged; committed retry does not duplicate. |
| M2.6 PDF output | Snapshot-based original-PDF copy or rendered saved-page PDF, safe destination publication and honest selectable-text status. | Untouched PDF is byte-identical; edited/subset/reordered/image/scan output has correct saved pages/appearance and passes inspection. |
| M2.7 integrated closeout | Persistent GUI scenario harness, failure/stale-work checks, native layouts/keyboard review, guide and evidence. | Complete UI import → edit → search → preview → restart → export passes; performance and remaining human/device gates are explicit. |

For a transformed page before M2.2 is ready, show a clear unsupported state and keep
its metadata intact. Such a state is a temporary slice limitation, not M2 completion.
M2.3 can add a journaled stack-color API if the current `StackEdit` lacks one; never
change color through ad hoc UI SQL. Either a list or a grid is sufficient.

## 4. Repository and worker rules

1. Keep one serialized authoritative writer, owned off the UI thread. UI handlers
   dispatch typed requests and consume results. No direct widget SQL/filesystem
   mutations, second mirror database or in-memory state presented as a saved result.
2. Reuse M1 transactions, tokens, `Control`, review/commit receipts and snapshots.
   Every authoritative mutation must journal in the same transaction. Derived view
   preferences/cache updates need not masquerade as document decisions.
3. Synchronous M1 APIs include file hashing/staging and manifest reads. Wrapping a
   blocking call in an async function does not make it nonblocking. Use bounded
   blocking/background workers; keep native decoding and long transfers off the UI
   executor and avoid holding repository transactions during them.
4. Cancellation must reach active work directly, not sit behind that work in a
   serialized request queue. Separate long staging/render work from short writer
   operations where needed while preserving lock/snapshot ownership and recovery.
5. Bound request queues, visible metadata projections, thumbnail/cache allocations,
   page pixels and pending preview work. Prefer latest-request behavior for search
   and selection; never accumulate a full-library decoded-image queue.
6. Results carry request generation, document incarnation/revision, stable page ID,
   page content revision and render settings as applicable. Reject a result after
   selection/query/root changes, page reorder/removal, later manual edits or deletion.
   Reading an old snapshot is not permission to publish stale current UI state.
7. Only show Saved after success. Before commit, cancel/failure preserves saved
   rows; after commit, report committed/published or cleanup-needed. Retrying a
   committed operation reuses its receipt. Repeated archive selection is additive.
8. Preserve the single native PDF worker's once-per-process initialization and
   shutdown/join lifecycle. Do not reinitialize bindings per preview or destroy the
   engine while jobs still hold native objects. Preserve any new failure trace.

## 5. Preview and export fidelity

Use each saved page's `source_file` and `source_page` in saved `position` order.
Display `page_count` from current saved pages, not `initial_page_count` or the
renderer. Cache by stable identity/revision/settings; editing must invalidate the
appropriate preview and prevent stale OCR/search or preview results from taking over.

Match Stack's existing Android appearance order from `PageRenderer.render`:

1. Decode the physical PDF page or EXIF-upright image.
2. Apply validated normalized four-corner perspective crop in upright coordinates,
   with bounded source resolution and white background.
3. Apply the saved quarter-turn rotation.
4. Estimate/apply conservative flattening when enabled; a no-model result is valid
   only under the actual documented algorithm's conditions.
5. Apply the saved enhancement (`original`, `medium`, `bright`, `black_white`).

Port Stack's own algorithms with source provenance. Write independent synthetic
corner/text-line/contrast fixtures and explicit tolerances for resampling or renderer
differences. Distinguish algorithm/host goldens from actual Android bitmap evidence.
Do not silently drop unsupported transforms or call an approximation phone parity.

Preview and rendered PDF output use the same saved-state semantics. Export one page
at a time with bounded memory, from a coherent pinned snapshot. Copy a PDF's original
bytes only if the whole original page set remains in original order, each physical
source index matches its position, every source filename is `source`, and every page
is unchanged (no saved crop/rotation/enhancement/flattening). A removed/reordered page
must not cause the full original PDF to be copied and reintroduce omitted pages.

For rendered/image-based PDF output, explain selectable-text limitations. Archive
export and PDF output are separate actions: only the archive carries the portable
library metadata/decisions/original graph. Never edit an original to produce either.
Use temporary output, validation/flush/sync and safe publication; preserve existing
destinations unless replacement is deliberately chosen and safely implemented.

## 6. Product behavior

- All documents plus ordered visible stacks in the sidebar; hidden stacks remain in
  a catalog editor. Preserve standard category accents for imported IDs and custom
  colors without relying on ID literals alone. Follow native theme/navigation.
- Search, stack filter and sort compose. Persist sort/view preferences in config.
  Opening a hit selects its stable matching page; document-only hits remain usable.
  Debounce/cancel stale queries without losing keyboard focus or entered text.
- Editors preserve exact decimal strings, leading zeros, currency/unknown qualifiers,
  date precision, notes, rejected/removed slots and historical evidence. Show source
  context without inventing provenance or converting bare-dollar evidence into a
  new currency decision. Ordinary edits use M1 manual-decision APIs.
- Invalid input, storage error or stale revision retains the user's draft with a
  clear action to retry/review. Explicit empty filing remains an intentional choice.
  Confirm destructive user actions in the product where appropriate; do not turn
  those controls into agent permission checkpoints during synthetic development.
- Show honest import/transfer/render progress, cancellation and per-item batch
  outcomes. Avoid technical schema/journal/version machinery in normal user flows.
  Missing PDFium or unavailable transforms offer actionable recovery and keep saved data.
- Support keyboard navigation, focus visibility, readable labels, scrolling/wrapping
  and sensible narrow layouts. Test 1080×780 and 680×680, light/dark and enlarged text.
  M0's doubled content text did not enlarge native sidebar/window typography; do not
  repeat that limitation as a full accessibility pass.

## 7. Verification and evidence

Run checks appropriate to each change; final M2 delivery requires the locked offline
workspace set below with the pinned native engine. Preserve the baseline's ignored
test distinction; a skipped native test is never a pass.

```sh
# From desktop/, with the handoff's verified compiler/cache/engine environment:
cargo fmt --all --check
cargo check --workspace --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo build --workspace --locked --offline
cargo test --workspace --locked --offline -- --include-ignored
```

Rerun M1 process-stop/Java interop checks when their storage/archive/journal paths
change, including for new API behavior. Use a new output directory for each harness.
Cached host examples, if still present:

```sh
python3 tools/test_m1_process_stops.py target/debug/stack-cli /tmp/stack-desktop-m1/fixtures-final /tmp/stack-m2-stops-NEW
python3 tools/test_m1_java_interop.py target/debug/stack-cli /tmp/stack-desktop-m1/java-host-final/classes /tmp/stack-desktop-m1/fixtures-final /tmp/stack-m2-java-NEW
```

Add persistent-workflow GUI scenarios; the current `smoke_native.py` exercises demo
previews only. Assert controls invoke the real application commands, saved graph and
journal outcomes. Clearly label automation that injects messages versus actual
keyboard/pointer interactions. No CLI-seeded library alone proves GUI import/save.

| Gate | Required M2 observation |
| --- | --- |
| Persistent workflow | Import through UI, browse, edit title/notes/filing/details, later-page search, preview, restart, then archive/PDF export through UI. |
| Fidelity | Original hashes unchanged; exact values/provenance retained; saved subset/order/crop/rotation/flatten/enhancement rendered and exported. |
| Archive | Additive review is explicit; canceled/rejected restore preserves saved data; exported graph passes existing Rust/host-Java checks within M1's qualified scope. |
| Concurrency | Rapid selection/search, delete/reorder during preview, stale editor/text result and closing during jobs cannot overwrite newer state or revive deletion. |
| Failure/retry | Missing engine, unreadable input, malformed archive, storage errors, cancel before commit and failure after commit have truthful outcomes and useful retry. |
| Layout/input | Own-window light/dark/narrow/large-text captures inspected; recorded keyboard/focus checks and outstanding human accessibility checks. |
| Performance | Maximum-count synthetic browse/search/import responsiveness and memory observed; numeric observations separated from budgets or unmeasured release behavior. |

Record source revision, dependency/native pins, fixture versions, actual commands,
outcomes and limits in new `docs/desktop/M2_EVIDENCE.md` and `M2_RESULTS.json` when
implementation provides evidence. Update the execution ledger, desktop run guide,
top-level evidence index and next-session prompt. Keep historical M0/M1 results intact.

M1's roughly 28s restore and 2.22s persisted-search observations are debug baselines,
not UI budgets. Demonstrate that the UI remains interactive while work runs, and
record cancellation latency, initial/scroll/search behavior and peak memory. Set
numeric targets from those measurements and document any unmet target honestly.

## 8. Completion and continuation

M2 is complete only when every task/gate above has actual evidence or an explicitly
qualified platform-dependent limit consistent with the original plan. Unsupported
saved appearance, missing PDF output or an untested persistent GUI loop remains
unfinished implementation. Human feedback and absent phone hardware do not block
independent implementation work; continue it and record the specific external gate.

Retain M0's undiagnosed native shutdown watch and M1's safe lone-surrogate rejection,
host currency/Unicode limits, native-parser isolation limit and pending device restore.
Do not label host Java checks Android runtime proof, backup re-import sync, or a
local journal a frozen exchange protocol. M3 causal merge is the next distinct stage.

Commit coherent Stack-only increments and report completed behavior, tests, material
limits and next unfinished task. Routine reversible work within the authorized M2
scope needs no repeated user permission. Consolidate remaining human checks in the
[project plan's single closeout checklist](../STACK_DESKTOP_PLAN.md#one-consolidated-user-dependent-checklist-for-implementation-closeout),
updating it instead of creating competing lists of phone/permission requests.
