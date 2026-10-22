# Desktop execution ledger

## Current remaining-work run — 2026-10-18

Execution of R1–R7 is active from `fe07fad`. See [current remaining-work evidence](REMAINING_EVIDENCE.md). All engineering runs in this session are headless and synthetic. R1 draft/color/platform changes, R2 real offline text jobs and preliminary R3/R4 causal/file exchange are being integrated; none of the remaining stage gates or user acceptance is declared complete. Preserve the unrelated Android/UI/docs dirty files recorded at kickoff.

## Earlier current state — 2026-10-17

Current request: execute M2 using the handoff and rulebook. M2.1 persistent browsing
is delivered with qualified native verification; M2.2 saved appearance is delivered with host/native qualifications; M2.3 search/catalog is delivered with native evidence; M2.4 editor implementation is in progress (uncommitted); saved-data/native scenario passes, but reliable visible notes capture is under investigation before delivery. M0/M1 remain delivered through `91fdffb`, with their recorded compatibility
qualifications. The window now has an explicit-root saved-library mode and retains
the explicit demo. See [M2 evidence](M2_EVIDENCE.md) for current observations.
No personal library/archive or Android operation is part of this work. Preserve all
preexisting Android/unrelated changes. Retain the native shutdown stability watch:
one new browser smoke exit fault is recorded before its explicit worker-shutdown fix.
Read the [project plan](../STACK_DESKTOP_PLAN.md) and [draft contract 0.1](INTEROP_SYNC_CONTRACT_V0_1.md).
Start implementation with the [M2 handoff](M2_HANDOFF.md) and
[M2 rulebook](M2_RULEBOOK.md). The [earlier work order](../STACK_DESKTOP_NEXT_SESSION_PROMPT.md)
remains the broader requirements source. Current source and commands are documented in
[`desktop/README.md`](../../desktop/README.md).

| Stage | Status | Evidence or next action |
| --- | --- | --- |
| Planning | Complete | Work order/archive/current source reviewed; local tools/cache/session inspected; official COSMIC and Android sources consulted; plan and contract drafted. |
| M0 foundation | Delivered, qualified | Native build/rendering, four tests, 16 final GUI runs/48 captures pass; earlier shutdown fault remains a stability watch with unconfirmed cause. |
| M1 durable library/archive | Delivered, qualified | Durable backend/CLI, 19 tests, host Java format-1/2/3 chains and 13 SIGKILL cases pass; documented safe string/runtime restrictions; device restore is an M5 gate. |
| M2 desktop loop | In progress: M2.1–M2.3 delivered, qualified | Saved browser/appearance/search/catalog; 30 tests, native catalog saves/reopen and M1 regressions pass. Continue M2.4 editors; full M2 is incomplete. See M2_EVIDENCE.md. |
| M3 merge/conflicts | Not started | Freeze executable wire/golden contract and implement causal merge/review. |
| M4 integrated Rust exchange | Not started | Real folder bundles between isolated Rust libraries; run/install instructions and evidence. |
| M5 Android companion | Not started | Additive Java journal/merge/bundle integration; disposable Checks exchange. |
| M6 paired LAN | Not started | Reviewed transport spec, pairing, TLS, permissions, foreground transfer and runtime proof. |

### Planning baseline (historical)

- Git root `/home/charlie.day/Projects`; HEAD `3a23d5e`. Existing tracked/untracked
  Android follow-up and README/matrix/guide changes must remain. Do not touch
  `cosmic-takeout`. No applicable workspace/ancestor AGENTS.md found; recheck later.
- Current source is Android `0.8.9-dev`/code 22/schema 8/archive 3, ahead of the
  older 0.8.7 desktop handoff. Primary-phone install/restore is reported complete;
  icon/color evidence documents subsequent updates. No fresh device observation.
- rustc/cargo 1.99.0; cached libcosmic revision
  `dbc62f2d899cdbfbe43d170689ddc0290db663f4` (candidate, unbuilt).
- COSMIC/Wayland environment and session executables present. No window launched.
- `pkg-config`: Wayland 1.23.1 and xkbcommon 1.6.0 present; fontconfig, freetype2,
  sqlite3 and poppler-glib metadata unavailable. Renderer/toolkit build blockers
  require actual M0 verification. No dependencies fetched or installed.
- `/tmp/stack-dev` SDK/Gradle/signing directories exist; usability untested.

### Non-negotiable preservation

Use synthetic inputs and disposable XDG roots / `org.stackdocs.app.checks` only.
Keep canonical Android originals, drafts, recovery, policy and signing identity.
Never use private phone/Google migration sources as fixtures, repeat personal
restore, or recreate historical row counts. Keep unrelated work intact. Do not
repeat the completed Android H1–H8 session.

### M0 implementation and investigation

- Rust workspace: core, renderer, CLI and desktop shell. Toolkit pinned to the
  cached official revision; Cargo.lock generated. Rust toolchain file pins 1.99.0.
  Session uses the existing stable compiler reporting exactly that version and an
  isolated Cargo cache under `/tmp/stack-desktop-dev/cargo-home`.
- PDFium binary `chromium/8100`, SHA-256-pinned setup, all native notices retained;
  pdfium-render 0.9.4 with explicit 7881 binding surface. No OS packages installed.
  A debugger was downloaded/extracted only under `/tmp` for the synthetic failure.
- Default toolkit font stack builds without missing fontconfig/freetype headers.
  Native renderer uses one persistent engine worker and a queue of one; a test
  caught the wrapper's once-per-process bindings requirement and the implementation
  now reuses it. Rotation tests caught unwanted thumbnail upscaling, also repaired.
- Core/image/native tests: four meaningful tests pass, including the normally
  ignored PDF test, distinct colored PDF markers, concurrent callers, exact JPEG
  orientation and source-hash preservation. Headless smoke renders five previews.
  Workspace fmt/check/clippy (warnings denied)/build pass in the recorded environment.
- Native Wayland access requires the execution tool's desktop-session escalation;
  the restricted launch produced NoCompositor, while the approved launch succeeds.
  All runtime data/config/cache/state and captures use disposable paths and exact
  independently authored fixtures. No phone access or personal library operations.
- Light, dark and narrow captures inspected. Toolkit general scale changed
  responsive breakpoints but did not enlarge text in this pinned runner; replaced
  that misleading option with explicit `--text-scale` for content text/controls and
  wrapping rows. Sidebar/window typography remains native.
- One final light smoke run captured all three pages then exited with SIGSEGV.
  Debugger/fault-trace runs subsequently exited cleanly and did not produce a crash
  backtrace. The worker now drains/joins before teardown, and the final four layout
  cases plus twelve further shutdown cycles all exit 0 (48 own-window captures).
  Causal link and exact native root cause remain unconfirmed; retain that watch.
  Logs/captures/debug tools stay under `/tmp/stack-desktop-dev/` and out of Git.
- Final check/fmt/clippy/build and workspace tests pass (four unique core/renderer
  tests, native ignored test explicitly included). A small headless five-preview
  run measured 0.54s / 16,264KiB peak RSS on i7-10610U; broader budgets unmeasured.
- Final visual inspection: light portrait PDF, dark landscape PDF, narrow upright
  JPEG and doubled-content-text landscape PDF. Enlarged controls wrap; the narrow
  doubled-text layout leaves limited page area. Accessibility/user acceptance is M2.
- M0 handoff: source build/run instructions are in `desktop/README.md`. Resume M1
  storage/recovery/archive work without repeating this foundation, preserving the
  shutdown watch and all Android/unrelated edits. No transfer/sync claim yet.

### Resume record format

For each increment append: milestone, exact source revision/dependency pins,
implemented behavior, commands/results, fixture identity, runtime/evidence limits,
remaining blocker and next unfinished task. Failed/injected/actual process-stop
results remain distinguishable. Do not mark a gate complete from code inspection.
Maintain separate portable-transfer, Rust-sync, Android-sync and LAN-sync statuses.
Group user-dependent checks in the plan's single closeout checklist.


### M1 implementation and closeout

- Base `28b3225`; Stack-only increment preserves preexisting Android and unrelated
  changes. No device connected; no canonical/Checks/Google library operation.
- New `stack-archive` crate and transactional `stack-core` repository, bundled SQLite
  schema 1→2, originals/snapshot leases, journal/aliases/tombstones and CLI commands.
  Every supported mutation is journaled; no sync wire/merge claim.
- Formats 1/2/3 read, format 3 write. Full synthetic five-document/seven-original
  graph covers PDF, scan, JPEG, PNG, WebP, removed originals, exact typed values,
  organization links, explicit empty filing, hidden ordering and historical evidence.
- Final fmt/check/locked offline workspace build/tests/clippy pass. Nineteen unique
  tests include the native engine. Actual SIGKILL recovery passes at 13 boundaries
  with a preexisting library; injected storage errors and incomplete journal recovery
  are separate tests. No power-loss or full-disk claim.
- Real production Java validator/writer/helpers compiled with hash-pinned AOSP JSON
  and host OpenJDK 25.0.4.1. All three format chains preserve graph/hash/evidence;
  21 differential cases and 233 host currency codes agree. Android native decoding,
  SQLite restore and runtime remain M5; lone-surrogate rejection and release-specific
  currency/Unicode differences are explicit safe compatibility qualifications.
- Maximum 1,000 documents/10,000 pages imports and finds all later-page hits. Debug
  observations: restore 28.32s / 309,904KiB, persisted-search/reopen 2.22s / 25,788KiB;
  before final atomic journal refinement. Retained FTS removed redundant reopening
  rebuild; M2 must run repository work off the UI thread.
- Native M0 regression on final linked binary: four layouts / 12 own-window captures
  exit 0. Earlier undiagnosed shutdown fault remains a watch. No new M2 UI acceptance.
- Next unfinished stage: M2 persistent COSMIC workflow, saved geometry/enhancement/
  flatten rendering, export PDF and accessible editors/progress/cancel. Keep M3–M6
  merge, folder exchange, Android companion and LAN as separate unimplemented gates.


### M2 handoff preparation

- Prepared `M2_HANDOFF.md` and `M2_RULEBOOK.md` against implementation `91fdffb`.
  This is documentation only; no M2 implementation, new runtime claim or changed
  acceptance result. The user viewed the synthetic M0 demo after M1 closeout.
- First task is explicit-root, background repository-backed browsing. The rulebook
  orders seven slices covering saved appearance, search/catalog, editors, imports/
  archives, PDF output and persistent GUI verification. Existing commands are
  separated from proposed `--library`/M2 harness interfaces.
- User identified `/home/charlie.day/Downloads/Stack-0.8.7/Google-Stack-Migration-final.stackbackup`
  as the phone reference. This is an archive path; no file was opened, staged or
  imported, and its contents/freshness remain unverified. Keep the personal Google
  migration archive outside regression fixtures/screenshots/Git. Use production
  Java for schema and authored synthetic archives for M2. A later real transfer is
  a separate user-chosen operation. No raw phone database copy was opened.
- Rechecked ancestor/workspace instructions, Git state, current code/API entry
  points, pinned runtime/toolchain files and temporary cache availability. Preserve
  all preexisting Android edits, unrelated work and the user's open demo window.
- Documentation verification: 53 local Markdown links/anchors and shell syntax for
  13 command blocks pass; diff whitespace is clean. Commands were syntax-checked,
  not executed. No code changed and no application/phone tests were rerun.
- Next implementation action remains M2.1. Run the handoff prompt to begin that
  work; no additional phone, pairing or canonical update action is part of M2.


### M2.1 persistent browser

- Base `1e309fe`; pinned Rust/COSMIC/PDFium unchanged. Explicit `--library`, XDG
  default mode, dedicated repository/preview threads with bounded latest-pending
  slots, saved lists/visible ordered stacks and stable saved-page/source selection.
  No new authoritative write API/schema or archive change. Demo remains explicit.
- New bounded read projections omit full graph/OCR allocation in normal browsing.
  Subset regression proves one saved later PDF page stays one page across restart.
  Busy-open retry, empty catalog, foreign-root preservation and worker queue tested.
- All fmt/check/clippy/build/locked offline tests pass: 23 unique tests including
  native PDF. Final persistent GUI harness passes first/reopen/empty/busy/error
  cases with 11 own-window captures. Graph/IDs/order/journal/hashes unchanged;
  capture JSON traces verify saved document/page/source selection. Message injection
  is distinct from actual pointer/keyboard input and GUI import/save/export proof.
- Restricted Wayland launch returns NoCompositor; session escalation succeeds.
  One initial four-capture run exits SIGSEGV before the explicit PDF-worker shutdown
  fix. Subsequent first/reopen and five-state runs exit 0. Normal window closing now
  joins repository/preview threads off the UI thread before PDFium shutdown. Preserve
  logs/captures and the undiagnosed native stability watch; no root-cause claim.
- Evidence/run guide/next-session pointer updated. Private archive, phone, existing
  windows and unrelated dirty files untouched. Next unfinished gate is M2.2 saved
  crop/rotation/flatten/enhancement; current transformed pages explicitly unavailable.


### M2.2 saved appearance

- Base `b96aea6`; saved EXIF/upright crop, quarter-turn, conservative flattening
  and enhancement now share `Renderer::render_saved`. No library/schema mutation.
- Ported Stack's production Java algorithms with provenance. Independent geometry,
  contrast/gray-edge, curved-baseline/no-model, border and full saved-source tests
  pass. Workspace fmt/check/clippy/build and 26 tests including native PDF pass.
- Host complete production Java enhancement pixels match exactly on 76,800 pixels;
  bowed model agrees within 1e-6 and straight/slanted no-model agrees. Limited host
  adapter excludes Canvas/scaling/Android runtime. CPU perspective/mesh rasterization
  has corner/baseline tolerances, not Android device bitmap parity.
- Native five-case/11-capture saved browser regression passes; transformed scan now
  renders in light/narrow-dark and after reopen. Saved rows/IDs/order/journal/hashes
  unchanged; no new shutdown fault, prior watch retained. Next M2.3 search/catalog.


### M2.3 search/catalog

- Base `2808131`; debounced composed search/filter/sort, stable matching-page
  selection, XDG Name/Newest preference and full catalog manager including hidden
  stacks. Journaled color API; ordinary catalog operations reuse M1 transactions.
  Accepted decisions cannot be superseded by later queued queries/selections.
- Workspace fmt/check/clippy/build and 30 tests pass. Native message-handler
  scenario saves eight journaled catalog edits, opens the real later PDF hit, then
  reopens with exact catalog/sort retained; six captures, document bytes/rows intact.
  The initial inactive-row assertion was a harness error, corrected using its
  preexisting starter-row baseline. No new native shutdown fault.
- M1 13 process-stop cases and three Java/Rust chains/21 validation comparisons/233
  host currency codes pass again. Device/Unicode/native-parser limits unchanged.
- Next unfinished task M2.4: title/notes/filing/details/provenance/page editors,
  exact-value/draft/stale/failure handling and journaled UI-save/restart evidence.


### M2.4 work in progress (2026-10-18; not yet delivered)

- Current committed base `20b613d`; title/multiline notes/multiple or empty filing,
  typed details/provenance/removal, saved page rotation/reorder/removal and confirmed
  document deletion controls are implemented in the working tree. Bounded selected
  details now paginate 200 slots at a time. Drafts retain exact strings across failed,
  stale and unrelated saves; loaded-version retry is explicit.
- Workspace 32 tests and clippy/build pass before capture refinements. Native editor
  scenario executes ten journaled edits and invalid/stale draft checks; exact notes,
  amount/evidence, subset page identity, original retention and reopen checks pass.
  The stale token is deliberately injected and labeled separately from UI saves.
- Native visual inspection found blank notes in screenshot output despite correct
  content/SQLite data. Further captures also showed stale frames. Do not mark M2.4
  delivered from these data checks alone. The pinned Winit Wayland `focus_window`
  implementation is a no-op; fresh-draw waiting can time out when occluded. The
  Iced screenshot consumes recorded render commands and editor buffers are weak.
- Working-tree harness refinements: smoke-only content-draw counter, task-stream
  capture after a fresh draw, random nonce test-window title, and standalone helper
  using already-pinned COSMIC toolkit rlib to activate only that exact synthetic
  title/app ID. It never logs foreign titles, closes windows or changes product
  focus behavior. Check latest `/tmp/stack-m2-editor-focused` result before resuming.
- Existing native traces/captures stay under `/tmp/stack-m2-editor-{native,contrast,
  widget,fresh-frame,draw-sync,focused}`. Original native/data passes and the failed
  fresh-frame wait are distinct observations. A style adjustment alone did not
  solve the capture issue. No private archive or Android/unrelated change.
- Checkpoint `4cc6168` preserves M2.4 implementation and its unpassed visual gate.
  Next: prove visible notes with reliable native frames; M2.5 native import/archive/
  progress/cancel, M2.6 PDF and M2.7 full
  workflow/performance/layout/input acceptance. Continue authorized work.


### M2.5 transfer implementation checkpoint (2026-10-18; native gate pending)

- Base `4cc6168`; native portal pickers, per-file image/PDF import, archive staging/
  additive review/commit, full archive export and direct progress/cancel/retry controls
  are wired to product handlers. One bounded transfer worker owns heavy staging/
  snapshot/output work; the serialized repository keeps SQLite and short decisions.
- Owned work leases retain the library lock across worker handoff. Snapshot metadata
  freezes before off-thread original pinning, so later deletion retains the exported
  saved graph. No schema, archive-version, source-byte or dependency-pin change.
- Locked offline fmt/check/clippy/build and 36 workspace tests including native PDF
  pass. New application-message test imports JPEG/PNG/WebP plus a failed item, retries
  only that item, exports, reviews/cancels/restores additively, rejects duplicate commit
  and existing-destination replacement, then reopens exact saved data. This is headless
  controller evidence, not native dialog, visual or physical-input acceptance.
- Core tests block active staging while saving notes, cancel directly, preserve a
  snapshot across later deletion, and retry committed cleanup without a new event or
  duplicate import. All 13 M1 process-stop cases pass at `/tmp/stack-m2-transfer-stops`;
  three Java/Rust chains, 21 differential cases and 233 currency codes pass at
  `/tmp/stack-m2-transfer-java`. Native visual condition above remains unresolved.
- Continue M2.6 PDF output independently; do not mark M2.4/M2.5 or full M2 delivered
  until the native integrated workflow/capture gates pass. Private archive, phone,
  existing windows and unrelated dirty files remain untouched.


### M2.6 PDF implementation checkpoint (2026-10-18; integrated native gate pending)

- Base `6d663c1`; selected-document PDF output uses a coherent selected snapshot
  and recoverable no-overwrite publication. Only a complete untouched PDF in original
  physical order copies bytes; all other documents use the same saved appearance
  renderer as preview, one page/scanline at a time. Lossless Flate image streams,
  3200-pixel longest edge, 8 GiB output bound and native page-count validation before
  publication. No OCR layer is invented; the product explains selectable-text status.
- Existing locked flate2 1.1.10 becomes a direct dependency; no version/checksum/pin
  change. Original files stay untouched. Export preserves the open selected document.
- Full locked offline check/clippy/build and 38 tests pass, including both explicitly
  included native tests. Native renderer scenario proves byte-identical untouched
  PDF, captured reorder despite later removal, edited subset/crop/rotation/enhancement,
  multi-original scan/flatten and all three image types; inspected page counts and
  aspect/corner tolerances pass. Private validation rejection/cancel publishes nothing.
- Headless application handlers import PDF, copy, reorder, remove/rotate, export and
  reopen saved subset. Native engine tests share its one lifecycle; no per-page init.
  All 13 M1 stops and Java/Rust 3 chains/21 cases/233 codes pass again under
  `/tmp/stack-m2-pdf-{stops,java}`. No new native shutdown failure in these runs.
- Native editor retries `/tmp/stack-m2-editor-{resumed,unminimize,configure}` still
  fail fresh draw (2→2). Waiting for asynchronous own-window activation now observes
  acknowledgement=true; that alone does not restore drawing. Temporary resize
  diagnostic failed and was removed. Preserve this distinction from earlier false
  acknowledgement observations. M2.4/M2.5/M2.6 integrated native acceptance remains
  pending; continue M2.7 independent harness/performance/failure work.


### M2.7 closeout checkpoint (2026-10-18; M2 remains in progress)

- Base `207c66b`; added synthetic native integrated workflow/reopen harness and
  matching headless data-path proof. Product handlers import four types, review/cancel,
  edit title/notes/filing/details, later-page search/preview, original/rendered PDF and
  full archive output. All 9 documents/11 pages survive restart; host Java and a fresh
  Rust restore read the application archive with equal portable graph. Headless driver
  explicitly omits captures. Artifacts `/tmp/.tmpinAcY4`, host report
  `/tmp/stack-m2-workflow-host/report.json`; no native dialog/input claim.
- Final isolated-XDG fmt/check/clippy/build and 39 tests pass in
  `/tmp/stack-m2-final-checks`, including all three ignored native/benchmark tests.
  Added malformed/stale-review/review-again/close-at-review checks. Maximum-count
  application handler scenario: stage/review 10037ms, commit/browse 9987ms, debounced
  search 1595ms, 1000 correct later-page hits, maximum 5ms ticker interval 7973µs;
  428764KiB process high-water RSS includes fixture/test allocations. CLI counts
  restore 22.24s/310040KiB and reopen-search 1.47s/25672KiB. Not frame/release budgets.
- `/tmp/stack-m2-workflow-native` imports four files (9 active docs, 6 journal events),
  then fails at first fresh capture, draw 2→2 despite acknowledged own activation.
  Zero operations remain after exit. No native integrated/reopen pass. The isolated
  nested compositor also stalled, was stopped by exact own-launch identity, and its
  test client reported connection reset. No existing user window/compositor changed.
- Remaining gate: reliable native redraw/visible editor, portal interactions, complete
  GUI round-trip/reopen, layouts/enlarged text/keyboard and native job-race/failure
  observations. Physical display-awake clarification pending; no root-cause claim.
  Continue from this gate after checking desktop state; do not restart implemented
  slices or use private data/Android/M3–M6 as a workaround. All unrelated dirt preserved.


### Native follow-up; terminal-only preference (2026-10-18)

- Base `a63c1c4`. User confirms desktop awake/active; withdraw the pending-display
  clarification. User then requests terminal/headless testing: open no further test
  windows. Keep native acceptance qualified while proceeding with headless work.
- Visible notes and ten editor saves/restart pass in `/tmp/stack-m2-editor-protocol`.
  Full injected native workflow/reopen passes `/tmp/stack-m2-workflow-visible` and
  `/tmp/stack-m2-wgpu-workflow`; portable graph, original bytes and journal verified.
  Actual portal selection/physical input are not part of those passes.
- Dedicated selected-document preview and collapsed narrow navigation improve space.
  GPU backend is default with software fallback using unchanged pins. Same binary
  also passes forced tiny-skia, while a separate awake software run stalls: no
  renderer-specific root cause proven. Four GPU layout cases pass actual dimensions
  and reopen at `/tmp/stack-m2-layouts-gpu`; scale-2 native font enlargement did not
  pass inspection. Restore content-only text scaling; full typography remains open.
- Native chooser harness is retained as unfinished. Real Cancel passes; import/save
  sequence fails a selected path (`/tmp/stack-m2-portals-traced`, five docs intact).
  Latest guards only compile-checked. Private AT-SPI probes expose pinned adapter's
  placeholder tree, not completed app accessibility. No host settings changed.
- Final current-source headless checks: `/tmp/stack-m2-terminal-checks/report.json`.
  Display variables removed; isolated XDG; fmt/check/clippy/build and all workspace
  tests including ignored native-engine/maximum-count cases: 39 passed, zero failed/
  ignored. Software-only compile and Python harness syntax pass too. Previous core recovery
  and Java/Rust results still apply; no core/archive/render changes in this slice.
- M2 incomplete: chooser round-trip, native typography/input/accessibility, frame
  reliability and native job/failure acceptance. Headless development continues;
  don't require desktop awake/unlock actions or repeat intrusive GUI diagnostics.


### M2 engineering closeout — final user review pending (2026-10-18)

- User directs completion of all remaining engineering headlessly, leaving visual
  checks/interface review to them at the end. Completed that scope without opening
  windows. Full M2 acceptance is pending the single project-plan review checkbox.
- Base `8e8856e`; connected pinned COSMIC's missing Iced scale callback with a public
  native launcher; full-shell scaling replaces content-only multiplication. Added
  Ctrl+F/Escape, 50-row result pages, committed-cleanup browser refresh and rejection
  of late messages during Close. No schema/archive/dependency/native version change.
- Headless `run_m2_checks.py` final report `/tmp/stack-m2-closeout-verified/report.json`:
  43 tests, zero failed/ignored; fmt/check/clippy/build, software-only check and Python
  syntax pass. Source hashes verified current; compiler/engine/binary hashes retained.
  Private-bus actual ashpd protocol requests verify cancel/four imports/Unicode URI/
  archive review-cancel-commit and exact original PDF output without a chooser/window.
- Added real storage-failure/draft/retry and committed-receipt retry proof; deterministic
  delayed renders span selection, deletion, PDF reorder and Close. Missing-engine
  preview recovery passes without resetting process-global bindings. 52 actual widget
  draws check scale/layout, text entry/focus, accessibility nodes and nonblank notes.
- Fresh maximum-count process: cancel signal 7µs/cleanup19ms after70917 processed bytes;
  stage11647ms, commit/browse13584ms, first50-row layout87ms, search1671ms, maximum5ms
  ticker interval90503µs;429196KiB process high-water including fixture allocations.
  Every documented debug target passes. The preceding combined-suite512MiB target
  failure `/tmp/stack-m2-closeout-final` is retained; final report separates its
  unrelated prior test allocations from fresh-process count evidence.
- Prepared and verified five/sixty-document review libraries at
  `/tmp/stack-m2-user-review`; run/run-many launchers are for the user to invoke.
  Guide `M2_REVIEW.md`; updated rulebook amendment, plan checkbox, README and handoff.
  Native chooser parent/focus, visual scaling/layout, keyboard/OS input and live
  compositor/shutdown review remain the user's final step. Pinned AT-SPI placeholder
  limitation is explicit. Historical failures and M1 qualifications remain retained.
- No private archive, canonical Android/device, existing user window, host setting or
  unrelated dirty file changed. Do not start M3 as part of this M2 closeout.


### Interface feedback and navigation consistency correction (2026-10-18)

- User requests careful interface/logic/consistency review, suggests BookStack, and
  reports wordy first screen, no discoverable return from Manage stacks and moving
  top/left menus. Source audit confirms hidden return, catalog state surviving
  sidebar navigation and background imports taking over the editor's location.
- Base `fd9dde7`; fixed header Back/Library actions, persistent search/Add, sidebar
  destinations, source-scoped labels, Back context/scroll preservation and loading
  route. Saved-list refresh no longer closes unrelated editors/documents/drafts.
  Recovery instructions and source history no longer crowd the routine edit view.
- `M2_INTERFACE_REVIEW.md` compares Android source and official BookStack, Paperless
  and native guidance. It separates implemented repairs from proposed Home/document
  editor/list/color/draft improvements. The single user checklist and review guide
  now begin with the actual reported Manage stacks/Back/sidebar journey.
- New real-handler/offscreen regression proves route/back/draft preservation and
  constant Back/Library/Add bounds. Offscreen views `/tmp/stack-ui-navigation-review`;
  no GUI window opened. Full locked offline checks,44 tests (none failed/ignored),
  software-only compile,Python syntax,private portal protocol and all fresh-process
  performance targets pass in `/tmp/stack-ui-navigation-final/report.json`. Source
  hashes verified current; compiler/engine/binary hashes retained. No core/archive/
  render schema or dependency change, so previous M1 stop/interop evidence applies.
- Keep full interface acceptance pending. Broader design recommendations are not
  silently marked implemented. Private data, canonical Android, existing windows
  and all unrelated working-tree changes remain untouched.


### User mockup layout adaptation (2026-10-18)

- User supplies `Mockup/stack-cosmic-desktop-mockup.html` and asks to resume. Read
  its local embedded layout/styles/handlers; preserve it unchanged. Base `18df1f8`.
  This becomes the primary design reference, superseding speculative Home/editor
  recommendations from the preceding audit.
- Native COSMIC sidebar keeps library destinations and bottom Backup & restore;
  fixed header Back and Search/Add retain their navigation roles. Grid/List choice
  persists independently of sort through the repository worker, tolerating the
  old sort-only config. Cards use document icons and bounded actual search snippets;
  no sample thumbnail or new star/autosave capability is represented as working.
- Wide documents pair preview with name/filing/exact saved details/notes. Narrow
  sizes switch preview/information. Editing uses Information/Details/Pages and
  explicit saves with dirty indication. Document deletion is under Information;
  page edits remain under Pages. Draft/recovery/original-retention semantics persist.
- New headless pointer test traverses actual List/Edit/section/Rotate/switch controls
  before executing their handlers, proving saved rotation and unsaved-title retention.
  Initial wrapped toolbar controls were absent from the pinned widget accessibility
  tree; explicit rows/columns expose them. Card styling was corrected after offscreen
  inspection. Earlier intermediate check output remains separately labelled.
- Final report `/tmp/stack-mockup-workspace-final-checks/report.json`:45 tests,
  zero failed/ignored; locked offline fmt/check/clippy/build, software compile,
  Python syntax, all six private-bus portal requests and ten fresh-process performance
  targets pass. All61 source hashes match; binary and14 offscreen PNG hashes retained.
  Fresh1000doc/10000page process: stage12779ms, commit14863ms, first50-result grid
  layout112ms, search1913ms, max5ms ticker interval119014µs, RSS428804KiB.
- Updated the existing review guide, audit mapping, plan checkbox, evidence/results,
  README and handoff. Full user acceptance remains pending; real thumbnails, visual
  color selection and stronger individual-detail/quit draft policy remain design work.
  No GUI window, private archive, phone/canonical library, unrelated edit or native
  dependency/schema/journal/original was changed; M1 interop qualifications remain.


### Closer native visual alignment and real thumbnails (2026-10-18)

- Latest user explicitly requests a UI-only browser/native visual comparison,
  overriding the previous headless-only window restriction within this scope.
  Base `574bbec`; source HTML preserved unchanged. Read/rendered it in Chromium
  at1080×860. Ran the actual native app with copied user COSMIC theme in isolated
  XDG roots on six authored readable PDFs; captured/inspected five wide and five
  narrow states. Only own nonce-identified test windows were focused/captured/closed.
- Flat180px native list-button sidebar, restrained single-border thumbnail area,
  real first-saved-page thumbnails and name/date/stack metadata, visible-row layout/
  decode bounds, contextual titles/Back, hidden library Search/Add in document views,
  larger aspect-preserving page, compact toolbar,280px right information/edit panel,
  membership chips/expandable choices and notes field. Wide editing preserves the
  page beside every tab; narrow information/editing keeps it above controls.
- Added a read-only tile projection and one bounded thumbnail worker; cache is at
  most the current50-result page and obsolete generations are ignored. Saved-page
  appearance/source order is reused; worker drains before terminal PDF shutdown.
  All existing decision/save/cancel/retry/provenance semantics are retained. No
  original, data/archive schema, journal or dependency version changed.
- Actual native comparison exposed/corrected black transparency, transparent tile
  foregrounds and missing sidebar capture content. Retained native content paint,
  native list-item foregrounds, flat native list navigation and a clipped sidebar
  drawing region. Early eager50-decode/whole-page-layout prototypes exceeded old
  debug targets; viewport work corrected them without raising budgets. New PDF
  regression needed a fresh process under the existing global engine lifetime.
- Final `/tmp/stack-ui-refinement/acceptance-checks/report.json`:47 tests,0 failed/
  ignored; fmt/check/clippy/build/software/Python/private-portal checks pass; source
  hashes match; all ten fresh-process targets pass. First-page layout143ms,
  search3319ms, ticker gap148170µs, RSS429368KiB at1000docs/10000pages. Intermediate
  failures and captures remain recorded, not relabeled as final proof.
- `/tmp/stack-ui-refinement/visual-report.json` retains native/browser dimensions,
  binary/image hashes, comparison scope and preservation evidence. Saved graph and
  every original hash equal pre-capture state;16 seeded journal entries unchanged.
  Wide captures `/tmp/stack-ui-refinement/native-confirmed`, narrow `native-narrow`;
  all images and browser references bundled as `screenshots.zip` in that root.
- Updated rulebook amendment, interface mapping, evidence/results, existing review
  guide/plan checkbox, README and handoff. Requested appearance comparison is
  complete; broader user M2 interaction acceptance and existing OS/AT-SPI limits
  remain separate. Private archive, phone/canonical library, theme settings, existing
  user windows and all unrelated working-tree edits remain untouched.

### Rendering/search feedback and phone-transfer guide (2026-10-18)

- User reports the refined interface is “much, much better”; rendering speed and
  sample PDF body search remain concerns. Added bounded worker caches for100 small
  tiles and three viewer pages, invalidated by saved page state and original file
  identity/stamp. Existing visible-result bounds and stale-generation guards remain.
- Optimized only rendering/image crates in development/test builds. Existing PDF
  regression observes cold671418→103153µs and cached65µs; same cached Arc and actual
  thumbnail handle are reused. Changed saved appearance renders anew. Memory caches
  last only for the session; cold starts still decode the originals.
- Sample PDFs have no saved OCR. Added read-only current-text page count and native
  information-panel status. Existing imported OCR search remains implemented; new
  desktop OCR/embedded-PDF text extraction remains unimplemented. New regression
  assertions cover imported text counts alongside existing matching-page search.
- Fresh isolated readable-PDF native library/viewer/editing captures inspected at
 1080×860; saved graph, original hashes and16 journal entries unchanged. Evidence:
  `/tmp/stack-ui-followup/visual-report.json`; final check consolidation in
  `/tmp/stack-ui-followup/report.json`. Intermediate sandbox socket failures retained;
  private synthetic D-Bus protocol is checked separately with socket authorization.
- Added `docs/STACK_DESKTOP_PHONE_TRANSFER.md`: exact phone/desktop menus, normal
  persistent-library launch, fresh snapshot/additive-copy semantics and explicit
  M3–M6 sync limits. No phone/private data or unrelated working-tree edit touched.
- Final source-matched verification:49 tests pass,0 failed/ignored; locked offline
  fmt/check/clippy/build/test, software-only and private portal checks pass. All ten
  fresh1000doc/10000page targets pass; first layout140ms, search2115ms, ticker142943µs,
  RSS427624KiB. Final PDF cold75188µs/cached91µs. Earlier timing misses retained;
  initial viewport row calculation now avoids an unnecessary offscreen row.

### Import-menu recovery and autonomous remaining-work playbook (2026-10-18)

- User reports no import feedback/apparent freeze; aggregate read-only checks show
  a staged review in the sample library, with no new saved documents/publication
  journal. User confirms Escape restores input. Fixed backup menu outside-click
  dismissal, added native busy/progress feedback, accessible review actions/stable
  commit ID and no idle review polling. No private archive/import was repeated.
- New full-shell pointer/tick/gated-commit regression passes; reverting only the
  modal flag reproduces its failure. Final source-matched headless run has50 passing
  tests and successful locked checks/software/private portal protocol. Performance
  gate remains open:171ms initial draw/178312µs tick gap exceed150ms targets; other
  eight limits pass. Raw report `/tmp/stack-import-feedback/final-checks/report.json`.
- Existing synthetic native workflow finished/closed before the user renewed the
  headless-only request. Its binary was current, fixtures older; no further GUI
  tests run. User windows and canonical phone/data remain untouched.
- Created `docs/STACK_DESKTOP_REMAINING_PLAYBOOK.md` and its start prompt, covering
  M2 readiness, new desktop text search, M3/M4/M5/M6 and integrated release. The
  user's requested execution mode is autonomous, headless, with one final prompt/
  consolidated user review; no routine intermediate permission milestones. Later
  implementation begins when that kickoff prompt is invoked.

### Remaining run checkpoint — 2026-10-18

- Authorized all R1–R7 engineering, headless tests and coherent Stack-only commits; one final user handoff. No GUI, private archive, canonical-phone library mutation or signing change.
- Workflow Heavy dispatched three disjoint workers for R1 editors/colors, R2 text backend and R1 native adapter. All stopped at a usage limit before completing their verification; user requested resume. Main agent is integrating/testing their saved changes directly. Requested worker settings were gpt-6.1-sol/high; no independent verifier has certified this work.
- Added schema 3 durable page jobs and schema 4 causal state/receipts; portable format 3/schema 8 unchanged. Populated schema-1 migration and prior library tests passed after migration.
- Real pinned PDFium plus pinned Tesseract 5.3.4/English model test passed authored embedded text, later-page hit, image scan, image-only PDF, blank, crop exclusion and restart. Core queue failure/retry/cancel/stale/edit/delete/restart tests passed. Desktop subscription/repository/UI path now wired and needs its own regression.
- Three isolated Rust SQLite libraries exchanged actual files and passed baseline-to-empty adoption, forwarding, independent edits, concurrent title alternatives, explicit resolution, deletion versus offline edit, replay and restart with retained original bytes. This is a preliminary supported subset, not the full M3/M4 gate; existing-library source maps, whole ordering semantics, all failure/kill boundaries and native review remain unfinished.
- Shared Java causal kernel passes five authored golden cases on host JVM/AOSP JSON; no Android runtime/journaling/UI claim. No emulator/system image is installed in the recorded SDK. Device availability has not been established by the host test.
- Last full workspace run failed the new palette accessibility assertion; investigation/fix ongoing. Earlier stress profile had 173ms first draw/181731us max gap (failure retained). Optimizing iced_graphics subsequently gave cumulative-process 56ms/63798us, but cumulative RSS626476KiB is not a fresh-process measurement. Need the final fresh-process acceptance harness after fixes; do not label performance accepted yet.
- Offline package/install/uninstall tooling written, unverified. Normal wrapper uses persistent XDG roots and packaged runtime paths. No installation or desktop launch performed.

### Verified R1/R2 foundation checkpoint

64 workspace tests pass; fresh maximum-count process meets all10 unchanged targets
(122ms first draw,124739us max executor gap,430832KiB RSS). The socket-restricted
portal run failed explicitly; approved separate private-bus retry passed6 requests.
13 actual M1 SIGKILL/restart boundaries and format1/2/3 Java/Rust host chains pass.
New sync provisional-view tombstone protection then passed23 core tests; workspace
clippy/build pass. See REMAINING_RESULTS.json for exact hashes/deltas. R1 engineering
and R2 English offline text are delivered with native review pending. Current next
work is complete R3 graph semantics/adoption/native review, R4 exchange/recovery/
notices, Android transactional integration and disposable runtime, paired TLS and
integrated release. Continue autonomously; no milestone permission checkpoint.

### Desktop sync continuation — 2026-10-20

- Resumed actual HEAD `2166c03`, preserving existing Android/UI/Mockup dirt and
  unrelated `cosmic-takeout`. No applicable AGENTS.md found. The pinned Rust 1.99
  toolchain remained under `.rustup`; restored locked Cargo dependencies and six
  checksum-pinned AOSP JSON sources into `/tmp`. Existing PDFium/OCR runtime and
  notice hashes all match. The prior temporary Android SDK/Gradle/emulator roots
  were absent and `/dev/kvm` unavailable. No canonical phone was accessed.
- Integrated retained R3 graph-model-2 changes: independent memberships/properties,
  whole ordering, removed-page/unseen-edit review, stable nonempty baseline maps,
  stale/replaced-file rejection, pending blobs and nonregressing receipts. Native
  adoption/conflict/file controls retain explicit drafts. Conflict alternatives
  are paged at 40, retaining their original review indices across page boundaries.
- Added a real SQLite/file ordering scenario covering concurrent page/catalog
  orders, resolution, page removal versus unseen appearance, restart and original
  preservation. Existing host Java vectors now pass ten cases plus one rejection.
  Format-1/2/3 host chains and 21 differential cases/233 currencies pass; seven sync
  and thirteen archive actual SIGKILL/recovery cases pass.
- Connected paired TLS to native identity/enroll/connect/listen/cancel/revoke
  controls using the existing repository owner, with no second writer. Session
  cancellation interrupts accept/TLS I/O and propagates into bundle operations;
  initial connect has an eight-second bound. Failed sessions refresh durable state,
  preserve unsaved drafts and retain the connection error. Repository work may
  report busy while the foreground session owns the worker. Networking is off at
  launch. Native headless controls passed real notes/original/draft preservation,
  both endpoint roles and revocation; nine transport tests include cancel/retry,
  wrong/unknown/revoked peer and corruption boundaries. No Android LAN claim.
- `/tmp/stack-resume-20261020-final`: all 93 workspace tests pass, no failures or
  ignored cases; fmt/check/clippy/build/software/6 private portal requests pass.
  Performance gate correctly fails: commit/browse 32158ms > 30000ms and maximum
  executor gap 156522us > 150000us. Other eight targets pass, including 147ms draw.
  Earlier `/tmp/stack-resume-20261020-acceptance` passed 90 tests and all ten limits
  before the final additions. Neither report substitutes for the other's source.
- Scoped dev/test optimization now also covers archive/core CPU loops and native
  widget layout; all checks/limits/durability paths remain unchanged. This changes
  the build configuration, requiring a fresh acceptance result. Final disposition
  is recorded below and in REMAINING_RESULTS.json when available.
- User requested lower-model agents for standard tests, results returned to the
  main agent for diagnosis and decisions. Workflow Heavy assigns two read-only
  verifier tasks, both requested `gpt-6-luna`/medium with fresh context. One owns
  standard acceptance reporting; the other owns prepared-package/install/source
  checks. Main owns code, scope, documentation and Git integration. Workers do not
  spawn children or repair tests. Runtime-confirmed model metadata is not exposed.
- Android graph/journal/apply qualification, nonempty adoption/network controls and
  disposable Android ↔ desktop runtime remain unfinished. Preserve their dirty
  source; do not equate the shared host causal kernel or Rust TLS/native-widget
  proof with phone sync. Full native/user acceptance stays in the single project
  checklist. No private archive, personal library or user window was tested.

### Verified optimized desktop continuation

- `/tmp/stack-resume-20261020-optimized/report.json` passes fmt, locked/offline
  check/clippy/build/test, software-only check and the private synthetic portal
  protocol: **93 tests passed, 0 failed, 0 ignored; all six portal requests pass**.
  The verifier confirmed all 214 recorded Rust/Cargo source hashes match current
  files. The main agent did not rerun the delegate's standard checks.
- All ten unchanged fresh-process performance limits pass. Commit/browse14327ms
  versus30000ms; maximum executor gap136501us versus150000us. Earlier measured
  failures remain in the previous entry and machine-readable results. Compiler
  optimization changed configuration; no validation, transaction or target changed.
- Prepared-package checkpoint `/tmp/stack-desktop-20261020-final` passes all950
  payload hashes, shell syntax, isolated install/update/uninstall with a prefix
  containing spaces and preservation of its synthetic library sentinel. Its
  509 dependency notices and complete Cargo source archive resolve673 packages
  locked/offline with an empty Cargo cache. It precedes the optimization delta
  and is qualified as intermediate. Final delivery is being prepared under the
  Git-ignored workspace `.artifacts/` so it survives loss of `/tmp`.

- Final persistent artifact `.artifacts/stack-desktop-20261020` is verified:
  all950 payload hashes and all216 source hashes match, shell syntax and fresh
  isolated install/packaged CLI status/uninstall preserve the sentinel library.
  Includes509 dependency notices and complete offline source. Unchanged prior
  update/uninstall and673-package offline source-resolution checks are reused;
  no second source build or Android runtime claim. Exact package/binary hashes,
  commands and both verifier reports are captured in REMAINING_RESULTS.json;
  standalone delivery verification is retained beside the ignored artifact.

### Spare Android file/LAN checkpoint — 2026-10-20

- User supplied an authorized spare Pixel4a/API33/arm64. Restored official SDK36,
  Gradle9.3.1 and checksum-verified complete Temurin25.0.4.1+1 under `/tmp/stack-dev`;
  the system Java compiler module alone lacks Gradle's compiler capability.
- Preserved canonical and older Checks apps/data. Their installed signer differs
  from available debug keys, so separate r5/r6 test namespaces were installed.
  Every mutation uses isolated authored child libraries, explicit serial and ADB
  server5693. No reset/uninstall/private archive or desktop/UI window test.
- Fixed Java canonical slash spelling and partial graph validation. Host graph
  projection/roundtrip agrees with Rust; one valid gap retained and10 malformed
  changes rejected before persistence. Causal10+1 corpus remains green.
- Real r5 file checks pass seed/adopt, idempotent reopen/export, concurrent titles,
  individually reviewed title/choice conflicts, exact notes/emoji/newline,
  receipt/replay, four hostile envelopes and deletion without resurrection or
  lost original. Reverse baseline contains5 documents/all7 original descriptors
  matching Android, including per-page scan JPEGs. Eight real populated1–8->9
  migrations preserve saved values, drafts, removals, text/order and start sync off.
- Fixed repeated generation changes for pending suggestions during recovery.
  Corrected the inspector's single-source assumption for scans; reused the seven
  completed file stages and eight migration results, rerunning only reverse check.
- First LAN TLS attempt failed because the Keystore EC key lacked DIGEST_NONE,
  required by Conscrypt's already-digested signing. Official API/CTS evidence
  informed the fix. New keys permit NONE+SHA256; existing incompatible keys are
  preserved. A fresh r6 namespace passes actual LAN bidirectional edits, matching
  original hashes, fingerprint mismatch refusals and revocation/later rejection.
- Main/instrumentation build and lint pass,0 errors/16 warnings; local unit task
  has NO-SOURCE. Lower-model read-only verifier roles execute mechanical checks;
  main handles diagnosis, implementation and decisions. Details/failures/pins:
  ANDROID_SYNC_EVIDENCE.md. Persistent ignored checkpoint contains APKs, exact
  Android source snapshot, hashes and logs at `.artifacts/stack-android-20261020`.
- Nonempty reviewed Android adoption, foreground network controls, Android apply
  interruption/network recovery matrix and final normal-phone/human release gates
  remain unfinished. Continue those next without repeating valid desktop93-test
  acceptance or the unaffected phone checks.


### Final Android adoption, foreground connection and recovery — 2026-10-20

- Following891f309, implemented source/local-bound complete identity adoption,
  deterministic distinct defaults, explicit document/page/catalog/scan matches,
  combined-graph validation and retained concurrent decisions. Android paged review
  and manual-address public-cert/fingerprint/connect/cancel/revoke controls now exist.
  Status captures the journal before reporting; receipts persist independently of
  file publication. Leaving the foreground cancels active socket/bundle work.
- Fixed restored suggestion private queue tokens outside the exact53-bit wire range;
  use52-bit tokens and rebase oversized old work on startup, preserving manual values.
  Retain the earlier failures and the pre-export status mismatch as diagnostics.
- Lower-model verifier reports: pure adoption13 cases; real separate adoption4
  stages; final matched adoption3 checks; LAN recovery8 checks; actual Android
  force-stop boundaries5 cases all pass. Each stopped import retries/resumes to6
  documents/8 originals, replays and converges with Rust. Actual blocked TLS cancel
  completes649ms after observed ClientHello. No canonical app mutation occurred.
- Current portable format1/2/3 chains and21 differential cases/233 currencies pass.
  Checks build/lint0 errors/17 warnings; normal release build/lint0 errors/16 warnings.
  Read-only bounded source review found no material lifecycle/cancel/data-loss gap;
  physical activities/rotation and adversarial concurrent-process behavior unobserved.
- Final package `.artifacts/stack-android-sync-20261020` holds Checks APKs, unsigned
  normal candidate, exact167-source snapshot and hashed synthetic reports/logs.
  Normal packageorg.stackdocs.app code22/0.8.9-dev, nondebuggable, all4 ABIs preserved.
  No available key matches installed code20's signer; the original keystore is the
  concrete remaining update gate. Do not uninstall/reset to bypass it.
- Rust production source unchanged; reuse93-test desktop acceptance and verified
  desktop package. Final native/phone UI, lock/picker/fingerprint interactions,
  other Android versions and Android maximum-count timing remain qualified in the
  existing single project checklist. Artifacts include preexisting UI/icon/version
  dirt as exact build inputs; those unrelated edits remain uncommitted.

- Final independent package audit confirms96/96 payload hashes and167/167 source
  workspace/tar matches, exact APK IDs/signatures/hashes and report counts. No key,
  keystore, database or personal archive found by filename/PEM-marker scan. The nested
  first-checkpoint inventory references23 payloads retained only in its sibling
  artifact; the final inventory hashes its reference files, not absent old payloads.
  Report: `.artifacts/stack-android-sync-20261020-verification.json`.


### Permanent signing and recovery continuation — 2026-10-20

- User requested final targeted key search, installed-cert comparison, data export
  investigation, durable development key/secure backup, fail-closed builds and
  procedures. Later clarified spare is disposable and differs from primary;
  primary protection remains. No current app was uninstalled or cleared.
- Independent public APK comparison confirms spare code20 and downloaded primary
  delivery code22 both use originaldb7352c8…eeab. Neither of the two available Android
  keys matches; persistent/home/build/config/backup/artifact search found no original.
- Adopted existing working Checkse873f2fd…e311 into permanent encrypted PKCS12 under
  ~/.local/share/stack-signing, strong local password, private config under ~/.config.
  Independent encrypted backup reopened and privatekey used successfully.600/700
  permissions, no overwrite. Both copies local; off-machine backup unperformed.
- Normal canonical builds keep original pin and fail without its key. Checks and
  separate org.stackdocs.app.recovery use managed development pin; no build generates
  a key. Explicit unsigned release option remains noninstallable.11 Gradle guards
  pass, including missing key/backup, wrong key/password, temporary paths, permissions,
  same-file backup, canonical refusal and unsigned-debug refusal.
- Read-only raw snapshot tool on idle spare verified381 copied files against device,
  schema8 SQLite/WAL integrity and no source changes; repeated output refused without
  altering saved archive/report. Private snapshot outside project; no primary backup
  or complete portable restore claim. Built-in export and debug prerequisites documented.
- Checks and recovery release builds/lint pass. Fresh ANDROID_USER_HOME has no
  generated debug.keystore. Preflight now handles absent pm-path exit1 using successful
  independent package-list proof; wrong signer and disconnecteddevice remain refused.
  Affected checks rerun after this helper repair; no redundant full runtime suite.
- Installed r6 main/test and separate signed recovery release only on authorized spare,
  no UI launch. Post-install metadata/signers pass; r6 sandbox digest unchanged across
  update. Canonical spare app, primary and existing backups remain untouched.
- Final delivery .artifacts/stack-signing-recovery-20261020 includes APKs,174 exact
  source files, public reports and hashes; no credentials or raw app data. Remaining
  options/consequences and recovery steps: docs/STACK_ANDROID_SIGNING.md. Primary
  direct update still requires original key; side-by-side recovery needs its own
  newly exported/validated library and deliberate local security/grant enrollment.

- Bounded review found and main fixed three gaps: disallow repo-local signing
  credentials, require library.db/report actual SQLite sidecars, and check candidate
  minSDK/device API before claiming compatibility. New repo-path rejection and
  managed-key positive pass; combined12 distinct signing guards. Preflight minimumSDK
  parser adjusted to actual aapt2 minSdkVersion spelling (also accepts older spelling);
  affected positives/negatives and simulated API23 refusal pass, no APK rebuild/reinstall.
  Fresh spare snapshot verifies381 files/schema8 with actual WAL/SHM present; review
  finds no further material gap. OpenSSL confirms encrypted PKCS12/PBKDF2/AES256-CBC,
  private600/700 permissions and separate inodes; both backups still same filesystem.

- Independent final artifact audit passes30/30 payload hashes and174/174 workspace/
  source-tar matches. All APK IDs/certs/digests and installed-spare report match.
  Scan against actual private credential values found no leaked passwords, keys,
  private signing config or raw app data. Bootstrap repeat refuses existing key/
  config without changing hashes or adding backup directories. Audit saved beside
  artifact at .artifacts/stack-signing-recovery-20261020-verification.json.


### One original-package app and P0 continuation — 2026-10-20

- User explicitly authorized old Stack successor/Recovery removal and test-data
  loss on Pixel4a serial0A121JEC203530 only. Every ADB command targeted that
  serial/server5693; the primary was never accessed. Removed old canonical and
  Recovery, installed permanent-key org.stackdocs.app code22/0.8.9-dev, then
  removed old Checks/r5/r6 and their test helpers. One Stack launcher remains;
  org.stackdocs.app.test is non-launcher instrumentation infrastructure.
- Retired Recovery build flag/label/appID, selected permanent development signing
  for ordinary original-package builds and enforced allowed IDs.13 real Gradle
  guards pass; debug/instrumentation/release and lint pass. No generated fallback
  debug key. Keystore and independent encrypted backup hashes/permissions unchanged;
  no off-machine backup claim. Historical original private key still unrecovered.
- Lower-model agents ran standard tests; main diagnosed an initial test-harness
  TextView/EditText selector crash after successful physical camera/recreation.
  Typed selector fix and affected rerun pass. Initial offline harness refusal on
  unchanged mobile-data preference is retained; airplane+Wi-Fi-disabled replacement
  verified no default network and restored original preferences in finally.
- P0 resumed: offline production first-library import/OCR/manual choices/search/
  backup/review/cancel/retry/additive restore passes2 groups. Physical camera and
  production two-page OCR/search/saved-original smoke passes5; host force-stop/
  new-process originalhash+page2query continuity passes1. Scanner37 and focused
  native OCR8 pass. Actual same-key adb install -r and post-update continuity
  pass; launcher/package inventory and MainActivity launch pass. Photo OCR accuracy,
  system picker, other APIs/ABIs/16KiB, disk-full/power-loss and broad accessibility
  remain qualified; prior user H1-H8 completion is retained.
- BackupUiChecks no longer confuses optional LAN INTERNET permission with a core
  network dependency; verifies bundled model with actual host-offline evidence.
  Canonical UI/camera smoke requires explicit disposable opt-in and known spare
  model; its retry may discard that disposable unfinished draft. Fixed generic
  report footer no longer labels physical-camera scene data synthetic.
- Current procedures/evidence: docs/STACK_ANDROID_SIGNING.md and
  docs/p0/TEST_DEVICE_CONTINUATION_20261020.md. Delivery under
  .artifacts/stack-single-app-20261020 includes public reports/APKs and exact source
  inputs including preexisting UI/icon/version dirt. Cleanup commit leaves those
  unrelated edits uncommitted. Previous artifacts/private snapshots/backups retained.
  Rust source unchanged; prior93-test desktop/portal/performance results reused.


### Corrected desktop/sync handoff — 2026-10-20

- User confirmed the Android APK is completed and in production use. Existing
  H1-H8 completion remains authoritative; historical agent-test qualifications are
  not a new acceptance backlog. Earlier proposed repetition of camera permission,
  recovery, export-failure, quality and accessibility work was incorrect.
- Rewrote STACK_DESKTOP_REMAINING_START_PROMPT.md for concrete desktop defects,
  unfinished desktop interaction work and newly added desktop-phone sync only.
  Updated the generic next-session entry and added playbook precedence guidance.
  Current93-test/ten-limit/six-portal desktop results and actual file/LAN adoption/
  recovery evidence are retained, not rerun or converted back into implementation
  tasks. No newly reproduced production defect is claimed by this documentation.
- Handoff preserves lower-model mechanical test execution, main-agent decisions,
  headless desktop boundaries, explicit disposable serial targeting, one original-
  package Stack app, permanent signing/backups and primary-phone protection. It
  carries dirty-source/artifact provenance and requests only focused remaining
  desktop/new-sync review. No app build, device action or acceptance test was run.

### Package-backed focused desktop/new-sync review — 2026-10-20

- Executed the active handoff from552c2f8. Inspected current source and existing
  acceptance for draft/quit/palette/navigation and Sync adoption/conflict/receipt/
  cancellation handlers. No concrete newly reproduced application defect or
  missing implementation established. Android H1-H8 remains completed and in
  production use; no general Android work, ADB command or visible desktop action.
- Corrected stale unsigned APK/Recovery/Checks guidance and top-level results
  implying a reopened Android backlog. Historical reports and unmeasured platform
  claims remain qualified. Primary, signing keys/backups, private archives and
  unrelated dirty work untouched.
- Added `tools/prepare_desktop_sync_review.py`, using the frozen package rather
  than mutable target/debug binaries. It verifies payload hashes, refuses existing
  output and isolates XDG/library roots. Prepared5/60-document browser libraries,
  unapplied nonempty adoption, and two adopted peers with43 conflict cells/86
  alternatives. Six user launchers include packaged PDF/OCR and default COSMIC
  theme. No automatic enrollment, key generation, listener or UI launch.
- Requested verifier as gpt-6-luna/medium under the user's lower-model test
  instruction; authorized only fresh fixture/report writes for mechanical checks.
  Runtime model identity was not independently exposed. Main owns diagnosis and
  all changes. Exact commands/logs/results are retained under
  `.artifacts/stack-desktop-review-20261020/validation/`.
- Audit: desktop950/950 payload and216/216 archived source hashes; current215/216,
  sole drift is the Java host extractor `Progress` signature change in74957b5.
  No Rust/Cargo/runtime input drift. Android49/49 payload and185/185 archived source
  hashes; current183/185 with only handoff/ledger doc differences at audit.
  Preserve frozen receipts; current HEAD alone does not include original dirty
  Android inputs or every current desktop helper.
- Preparation command:
  `python3 tools/prepare_desktop_sync_review.py --output /home/charlie.day/Projects/Stack/.artifacts/stack-desktop-review-20261020`.
  All100 packaged CLI operations succeed; peer projections valid, gaps0, conflicts
  equal. Six launcher syntax checks and package wrapper syntax pass. Same-output
  retry refuses with all391 fixture hashes unchanged. The initial doc estimate44
  alternatives omitted retained title decision cells; inspected actual status and
  corrected to86 across three pages.
- Reused source-compatible93 tests/ten unchanged limits/six portal checks and
  installer evidence. No app rebuild/full harness/phone retest warranted by these
  review tooling/docs changes. `git diff --check` passes. M2_REVIEW.md now covers
  desktop and only new-sync phone behavior; physical screen-reader/chooser focus/
  compositor/shutdown observations remain open.
- Final lower-model validation passes400/400 review payload and4/4 preparer/guide
  source hashes, all100 command exits and exact661-character notes on both peers.
  Invalid output boundary exits2 without creating the directory. Standalone report:
  `.artifacts/stack-desktop-review-20261020-verification.json`.


### Guided phone/desktop sync interface repair — 2026-10-20

- User reported phone Sync navigation unlike the rest of Stack, then asked to
  resume both interfaces and clarify the desktop location/process. User explicitly
  authorized installation and any necessary tests on the disposable Pixel4a only.
  Main redesigned the existing native views; no new shell/protocol or general
  Android acceptance work. Unrelated dirty files and primary remain protected.
- Phone uses Stack action rows, task pages, parent Back/save-state, clear next
  device steps, separate file actions/details and progressive Wi-Fi verification.
  Known group/peer IDs no longer need retyping during enrollment. Fingerprint
  consent and selected complete conflict values remain explicit. Identity export
  remains available after phone-side enrollment to finish desktop verification.
- Desktop Sync with phone moved from the document destination list to a permanent
  footer control above Backup & restore. Pages guide phone ID → setup file → phone
  review → files or Wi-Fi. Peer destination is known for two members. Native public
  identity chooser, own-interface address/port labels and confirmed revocation
  replace unclear control placement. IDs/raw frontiers live under details. Back
  retains document drafts and returns to the saved browse position.
- Lower-model roles requested as gpt-6-luna/medium performed builds, tests and
  delivery work; model identity beyond requested settings is not exposed. Main
  owns code/fixes. Phone command environment is the recorded /tmp/stack-dev JDK,
  SDK and Gradle cache; `./gradlew --offline :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintDebug` passes, lint0 errors/14 warnings.
  Every ADB command uses `-P 5693 -s 0A121JEC203530`; public-signer preflights and
  main/test `install -r` pass. No uninstall/reset, system preference change,
  secondary application namespace, private phone dump or primary operation.
- Final phone command: `adb -P 5693 -s 0A121JEC203530 shell am instrument -w -r
  -e disposableDevice 0A121JEC203530 -e syncUi true
  org.stackdocs.app.test/org.stackdocs.app.ScannerChecks`:7 groups pass. Separate
  preserveOnly before/after matches175/278/18/248 counts and all five aggregate
  hashes. Final logs use *-bidirectional.log; captures are authored state only.
- Desktop `python3 tools/run_m2_checks.py --output
  /tmp/stack-desktop-sync-navigation-20261020-acceptance-final`: fmt/check/clippy/
  build/test/counts/software/portal all0,95 passed/0 failed/0 ignored, ten unchanged
  limits met, six portal requests passed. The final sync-link affordance changed
  only sync_pages.rs afterwards; source delta is retained in current results.
  Current-source fmt/check/clippy/build plus guided pointer/screenshot regression
  pass at /tmp/stack-desktop-sync-navigation-20261020-current-source. Browser
  performance inputs are unaffected by that sync-local helper.
- Retained failures: Gradle sandbox wildcard-IP startup and Rust loopback denial
  resolved with approved local IPC access. Main fixed Android native-back lint
  annotation, Rust syntax/field visibility/type inference/dead-action warnings,
  selected-stack state and the test's newly advanced desktop initiator location.
  Original raw first Android lint log was accidentally overwritten; observed
  GestureBackNavigation error at SyncActivity:29 plus14 warnings is documented,
  not relabeled as raw retained evidence. Successful later reports are separate.
- Phone APK final SHA2569374f1af9cc1af647f660a0ce64b4a057628f9c31b24cd8815d72ad4ab1f47e5;
  test9a15e48731760d8bf8b245c988f24ad95b5c231ece48511d90d731fa81cdb271;
  permanent public signer unchanged. Exact dirty build-input snapshot is the
  delivery reference, not a claim that HEAD alone reproduces unrelated Android
  UI/icon/version edits. Updated shared transfer guide and focused review path.
- Final delivery audit: desktop950/950 payload hashes,222/222 exact current source
  hashes,509 notices; isolated prefix install/CLI/uninstall passes with prefix/XDG
  sentinels retained. Durable acceptance, current-source follow-up and six authored
  snapshots are under `.artifacts/stack-desktop-sync-navigation-20261020-evidence`.
  Consolidated receipt `.artifacts/stack-desktop-sync-review-20261020-verification.json`
  SHA256bbc16a8bc405f47f39f627dbdce8e8e87810b776c5f0deacefa2a816c17b23b0.
- Fresh authored review at `.artifacts/stack-desktop-sync-review-20261020` passes
  391/391 inventory hashes and100/100 packaged CLI commands; six launchers,5/60
  documents, unapplied adoption,43 conflict cells/86 alternatives, zero gaps.
  No visible desktop app or listener launched. Human native observations remain
  confined to the existing focused review, including the revised sync process.
- Corrected the guide's APK path to include `apk/`; refreshed only phone source
  snapshot/inventory and desktop guide-hash receipt. No app rebuild/test needed.
  Phone final48/48 payload hashes,185/185 current and archived source files,
  20 audit checks/0 errors. Standalone receipt
  `.artifacts/stack-sync-navigation-20261020-verification.json` SHA256
  5c0a2bf992ffa59483152c8016f8fff841793e00f4b65a5552295d974a18f35b.
  Mutable global results/evidence/ledger are explicitly excluded from the phone
  source whitelist to avoid circular provenance. Earlier candidate retained.


### Friendly nearby/QR pairing and guarded local recovery — 2026-10-20/21

- Continued from59992cb for explicit user requests: friendly device names plus
  matching-code verification, both nearby and QR selection, and careful complete
  local erase followed by backup restore/resync. No general Android acceptance
  reopened. Native task pages retained; advanced IDs/certificates remain in details.
- Main implemented bootstrap and cold-reset engines/UI; routine lower-model roles
  requested gpt-6-luna/medium ran builds, device checks, full desktop acceptance and
  package audits. Read-only focused review requested gpt-6.1-sol/xhigh approved
  after fixes. Runtime identities beyond requested settings are not exposed.
- Pairing: explicit120second one-attempt UDP/TCP invitation on45874, role-bound
  commit/reveal transcript, six-digit comparison and both user approvals, same-socket
  pinned mTLS plus authenticated transcript consent before enrollment/baseline.
  Decoded certificate comparison, complete-identity reuse, absolute monotonic
  deadline and exact active peer/group resume preserve trust/frozen membership.
  Nearby names alone never authorize trust; private keys remain local.
- Both full-reset interfaces use three warnings plus exact local phrase. Connection
  reset keeps documents and requires its own phrase. Durable allowlisted quarantine,
  immutable cold inventory, rolling_back phase and indeterminate-publication handling
  protect crashes/retries. Desktop keeps the same writer lock; Android resets before
  startup workers and disables automatic photo import after erase. Unknown files,
  external backups, access controls, transport own key and signer stay protected.
- Actual isolated Android/Rust cases A/B pass both baseline directions, canceled
  library review without causal change, same-device resume and normal bidirectional
  mTLS sync; each pair reaches2docs/4pages/2originals. C host-only consent plus phone
  cancel refuses enrollment; D wrong QR pin rejects before phone preview/policy.
  Initial A coordinator45second test timeout, C escaped wrapper assertion and D host
  wait timing errors remain retained and distinguished from product outcomes.
- Focused reset/QR checks pass: four Rust core groups, four Rust pairing groups,
  native confirmation/cancel and encoder checks; Android8isolatedreset,1nativecancel,
 1canonical/actualRustQRdecode. Real spare library is never erased or re-enrolled.
- Full desktop harness `python3 desktop/tools/run_m2_checks.py --output
  /tmp/stack-friendly-sync-20261021-acceptance-passed` passes105/0/0, fmt/check/strict
  Clippy/build/software, all10unchanged limits and6privateportalrequests. All248
  report source hashes match current tree. Initial formatting and stale advanced
  identity test route failure (plus five poisoned-mutex followers) are retained;
  corrected native-route focused test/full follow-up pass. Sandbox loopback and
  Gradle wildcard-IP refusals required approved local IPC retries, no network bypass.
- Desktop package952payload/227receiptsourcehashes and510notices verified;
  isolated install/CLI/uninstall preserves unrelated sentinels. Review8launchers,
  102CLIcommands,410inventoryhashes,5/60/5/0documents,43cells/86alternatives;
  repeat preparation refuses with all existing bytes preserved. No visible app,
  chooser, normal library, private archive or primary-device operation.
- User instructions and technical detail are in STACK_DESKTOP_PHONE_TRANSFER.md,
  FRIENDLY_SYNC_RESET_20261020.md and current focused M2_REVIEW.md. Current artifacts
  are stack-friendly-sync-20261020 and stack-friendly-sync-phone-20261020 under
  .artifacts. Exact phone source includes preserved unrelated UI/icon/version inputs;
  the coherent commit deliberately excludes that unrelated work.
- Final phone copy review fixed one real usability inconsistency: named mutual-code
  setup no longer prompts manual certificate enrollment before first sync. Final
  offline main/test/lint pass0errors/18warnings; same-key spare preflight/install-r
  main+test pass and7nativeUIgroups pass with correct nextstep and paired rescan.
  Before/after175/278/18/248 counts plus all5hashes remain identical. Destructive
  finalcallback never invoked on retained library. Phone physical camera QR remains
  unobserved, actual Rust QR decoder evidence reused for unchanged engine.
- Final main APK SHA25644db6462a9e51aa225ffe3771a13352e79818759e147eca7d9aa29d8656fa93b;
  instrumentation 437622dc006ce69348d278937d0a9fd6a46f62341bac5f45e33494833d6060fc. Independent phone audit
  passes45payload/199currentandarchivedsourcefiles/all168Androidbuildinputs;
  no credential/key/database included. Prior copy-fix candidate retained separately.
  Source manifests exclude these three mutable global status records.
- Durable desktop evidence inventory200files; desktop verification SHA256
  1b59badf6ec9e9e4919e4927995f75aad4c227212b6cd4b351ef4546a7e65982;
  phone verification SHA256a04c4ed2bac74dce3c326b8ec79fbcf8a6fcc591a24fa0e391c6523f671758ac.
  Both source-matched current deliveries pass. Remaining user observations are
  confined to the focused native/new-sync review, without reopening AndroidH1-H8.


### Simplified automatic sync — resumed closeout, 2026-10-21

- Delivered Settings → Sync → Connect phone → phone approval, then automatic
  two-way documents, saved edits and organization. Removed obsolete adoption,
  direction, receipt, session and manual file-exchange pages from application UI.
- Initial merge matches shared archive identities and equal category names,
  preserves independent documents, and presents readable genuine edit choices.
  Existing pinned TLS and causal exchange remain. Ordinary retries preserve pins;
  incomplete approved setup now resumes, including after process restart.
- Final review found and closed incomplete-enrollment recovery, foreground setup
  cancellation and queued-work lock races. Android regressions exercise recovered
  addresses/pins, stale/revoked-peer refusal, an occupied IO queue followed by lock,
  and actual SyncActivity stop before destruction.
- Current checks:15 core sync integrations;31 enabled desktop cases (29 in broad
  run, two passing headless serialized followups),3 desktop optional skips; fmt,
  strict workspace Clippy and build pass. Earlier106/5-skipped workspace run stays
  qualified to its source. Android canonical/checks build+lint pass;5 sync+3 UI
  groups pass; unchanged large-state1-group evidence reused. Initial test-fixture
  compile/lint and environment failures are retained, not relabeled passes.
- Desktop package audit verifies952 payloads and227 current/archive source files;
  Android source snapshot has169 build inputs. Deliveries and sanitized logs are
  under .artifacts/stack-automatic-sync-resume-20261021. Existing artifacts retained.
- Updated only authorized spare0A121JEC203530/server5693 with matching permanent
  signer and install-r. Fresh current library164 docs/257 pages/240 memberships
  preserved exactly across eight protected table hashes plus originals/drafts;
  startup repairs23→16 stacks and7→0 obsolete generated labels. No reset, restore,
  re-enrollment, database/WAL transfer, primary phone or accessibility change.
- Two routine verifier workers requested gpt-6-luna/medium returned exact results;
  one static reviewer requested gpt-6.1-sol/high returned no remaining blocker.
  Runtime identities are not independently exposed. Main owns fixes/integration.
- Working-tree icon, color, version, mockup and sibling-project changes remain
  separate. Exact artifact inputs include their preserved bytes; the focused
  sync commit deliberately excludes them. No user sync validation is pending.

### 2026-10-21 follow-up — startup and TLS starvation

- Actual spare launch exposed journal replay blocking Home; read callbacks now
  use a bounded executor after initialization, status is cheap, and causal replay
  checks cancellation and handles independent queue invalidations without full
  graph reconstruction per event. Actual canonical Home loaded populated.
- Actual normal connection exposed desktop bootstrap starving TLS. Startup gives
  ordinary peers time; an incoming socket cancels the incomplete invitation and
  is served with unchanged TLS/pin validation when the writer is free.
- Final targeted desktop fmt/strict Clippy/build and Android debug/release/lint
  pass. Large-state2/2 pass (256 distinct events,3452ms). Latest fixture UI2 passes
  but incomplete; auto4 passes then queuedLock occupied-writer assertion failed.
  Earlier all-group successes remain scoped to earlier source.
- Installable APK and Flatpak, exact source snapshots and hashes are under
  `.artifacts/stack-delivery-20261021`; real transfer status is in RELEASE.json.
