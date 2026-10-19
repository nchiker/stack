# M2 persistent COSMIC workflow — final user review pending

M2 implements the persistent native workflow. The latest UI refinement has been
compared directly with the supplied HTML in Chromium and with the real COSMIC app
on readable synthetic PDFs at matching1080×860 dimensions. Native wide/narrow
captures are inspected;47 tests, locked offline checks, private portal protocol
and all ten fresh-process debug targets pass. The actual interaction/keyboard/
chooser and platform qualifications remain in [M2_REVIEW.md](M2_REVIEW.md); full
user M2 acceptance stays separate. Historical evidence follows chronologically.
Base `1e309fe` (M0/M1 implementation `91fdffb`). Rust 1.99.0,
libcosmic `dbc62f2d899cdbfbe43d170689ddc0290db663f4`, PDFium `chromium/8100` with
SHA-256 `b8784cbd652ad2993332b7695e2c80f3a797f877fc47d9be97b66bca9639539e`.
No dependency version or database/archive schema changed. Tokio's already-resolved
sync support and existing workspace archive/JSON dependencies are now used directly.
All preexisting Android/unrelated edits remain. No phone/archive access occurred.

## M2.1 implementation

- `--library ABSOLUTE_ROOT` opens an explicit saved library. Ordinary launches use
  `default_root()`; `--demo` remains separate. Verification always overrides library
  and all four XDG roots under fresh `/tmp` directories.
- A dedicated repository thread opens/recoveries/owns `Library` and its writer lock.
  A separate preview thread decodes pages. Each has one active/one replaceable pending
  request; a newer pending request resolves the replaced reply as superseded.
- Saved document list (bounded to the archive's 1,000-document limit), ordered visible
  stacks, selection, saved page sequence, previous/next and integrated zoom/pan.
  Bounded read projections omit OCR/full-library graph copies during normal browsing.
- Document tokens, stable page IDs, content revisions and source filename/index are
  retained in selection projections. Request generations reject stale repository and
  preview results. A saved PDF subset never inherits physical-original page totals.
- Loading, empty, busy/refresh and invalid-root error states remain inside the window.
  Transformed pages explicitly report unavailable until M2.2; no incorrect preview.
- Closing drains/joins repository/preview work on a background task before joining
  the once-per-process PDF worker and exiting. No user's existing window is closed.

## Checks and native observations

From `desktop/`, with `RUSTUP_TOOLCHAIN=stable`,
`CARGO_HOME=/tmp/stack-desktop-dev/cargo-home`, `CARGO_BUILD_JOBS=4`, and
`STACK_PDFIUM_LIBRARY="$PWD/runtime/pdfium/lib/libpdfium.so"`:

```sh
cargo fmt --all --check
cargo check --workspace --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo build --workspace --locked --offline
cargo test --workspace --locked --offline -- --include-ignored
python3 -m py_compile tools/test_m2_browser.py
python3 tools/test_m2_browser.py --output /tmp/stack-m2-browser-delivery
```

The Rust suite has **23 tests**, including the native PDF test, a new saved PDF
subset/order/reopen regression, bounded latest-pending worker behavior, busy-open
retry/exclusive ownership and foreign-root preservation/retry. Native process access
requires the documented Wayland-session escalation; restricted execution returns
`NoCompositor` and is not counted as a pass.

The persistent harness CLI-generates/restores authored M0/M1 version-1 fixtures into
a new synthetic root. It selects the real saved PDF page 1, its later page, the EXIF
JPEG and a saved transformed scan, then reopens the same root. It compares complete
saved rows, revisions/incarnations, catalog/page order, journal digests and original
hashes. JSON traces associate each capture with its saved token/page/source index.
The capture gate verifies the entire graph and all original hashes against generated
reference bytes before displaying content. Empty, busy and foreign-root cases use
separate authored roots/lock contention and capture their actual window states.

Earlier `/tmp/stack-m2-browser-states` runs passed all five cases (11 captures), with
visual inspection of later PDF, narrow dark transformed-unavailable, empty, busy and
foreign-root states. The final `/tmp/stack-m2-browser-delivery` rerun passes all five cases and 11
captures, including saved-page trace assertions and the normal close-task lifecycle.
All five listed Cargo checks and Python syntax compilation pass.
Automation injects application messages; it is not a pointer/keyboard accessibility
check or evidence of GUI import/edit/export. Hidden stacks are retained in storage;
the catalog editor, search, edits, imports, outputs and full layout review remain.

## Retained failures and limits

The first rebuilt browser run `/tmp/stack-m2-browser-native` saved all four captures
then exited with SIGSEGV. The browser smoke branch had omitted M0's explicit native
worker shutdown before window exit. After matching that sequence, both first/reopen
runs in `/tmp/stack-m2-browser-shutdown-fix` and all five state runs exit 0. Normal
closing now drains both new workers before native shutdown as well. These observations
do not establish the underlying native fault's root cause; the M0 shutdown watch
remains active. Failed outputs/logs are retained privately under `/tmp`.

No private reference archive was opened. No personal default-root launch, Android
build/install, new OCR, sync, pairing, LAN or release packaging claim is made.
Original hashes/IDs/ordering checks cover synthetic browsing/restart. Failure during
new mutations/transfers, transformed appearance parity, maximum-count UI performance,
large-text/sidebar behavior and human keyboard/accessibility review remain M2 work.
M1's safe UTF-16, host currency/Unicode, native-parser isolation and device-runtime
qualifications remain in force. Next task: M2.4 document editors.


## M2.2 saved appearance

Base `b96aea6`. `Renderer::render_saved` applies physical source-page decoding and
EXIF orientation, validated normalized perspective crop, saved quarter turn,
conservative flattening and enhancement in that order. The native library preview
uses this path; PDF output will reuse it in M2.6. The M2.1 unavailable state is now
superseded for supported saved settings. Invalid settings remain errors.

The port follows Stack's own Apache-2.0 `PageRenderer`, `PageGeometry`,
`PageEnhancement` and `PageFlattening`. The original Java files are unmodified.
The first two source SHA-256 values at port time:

- PageRenderer: `fe8a4469ba06795feba113e4a73fcf6c6bb99b9d989a5b2b4feeaca8d17a39b5`
- PageGeometry: `1b0b616ab88d094076397f0bfab1d975845843351c8a8691f8e4750d519549b9`

Enhancement/flattening hashes are in the structured host results. CPU inverse
homography/bilinear sampling replaces Canvas perspective rasterization; a 32×64
forward triangular mesh preserves the Android flattening model, taper and borders.
The model uses the original nine-strip correlation/acceptance conditions and small
800-pixel sample; no-model is not a placeholder. Black/white uses the original
median, closing, normalization and continuous contrast equations. Crop-aware decode
is capped at 4,096 per edge/16 Mi pixels; source image decode remains capped at
24 megapixels and decoder allocation at 128 MiB. Additional bounded working arrays
mean those individual caps are not a total-process memory budget.

```sh
# Same pinned compiler/cache/engine environment as above, from desktop/:
cargo fmt --all --check
cargo check --workspace --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo build --workspace --locked --offline
cargo test --workspace --locked --offline -- --include-ignored
python3 tools/test_m2_appearance_host.py --output /tmp/stack-m2-appearance-host
python3 tools/test_m2_browser.py --output /tmp/stack-m2-appearance-native
```

The workspace suite now has **26 tests**. New tests check perspective corner order
and rejection, exact contrast values and gray edge retention, bowed-baseline model
acceptance/straight/slanted/blank/tiny rejection, fixed borders and baseline error
at most two pixels at five authored x positions. Existing renderer tests now also
exercise the full EXIF→crop→quarter-turn→flatten→enhance path, later physical PDF
page rotation/enhancement, bounds and original-hash preservation.

The host harness compiles complete unchanged production Java enhancement/flattening
classes with an explicitly limited pixel adapter. All 76,800 black-white RGBA pixels
match exactly (307,200 bytes); bowed model coefficients agree within `1e-6` absolute,
straight/slanted produce no model in both. The adapter forbids scaling and Canvas:
this is production algorithm evidence for authored inputs, not Android decode,
Canvas rasterization, mesh antialiasing or device bitmap proof. Perspective and mesh
sampling have geometry/corner/baseline tests, not phone pixel goldens. Images with
resampling can differ near strokes/edges; no arbitrary phone-pixel parity claim.

The saved-browser harness passes all five native cases/11 captures on the new
renderer. Inspected light and narrow dark transformed scan captures show the saved
orientation/crop/medium preset. Inspected authored bowed/flattened pages show the
line correction; input quantization remains visible at subpixel edges. The graph,
IDs/order, journal and originals remain unchanged after close/reopen. No new native
shutdown fault occurred; retain the prior watch. No PDF-output/UI editor claim yet.


## M2.3 search and stack catalog

Base `2808131`. The native search input debounces by 220ms and invalidates old query/
selection/render generations immediately. Search, stack filter and Newest/Name sort
compose through the repository API. Opening a result resolves its stable matching
page ID against current saved pages; a removed matching page fails stale rather than
silently opening a different page. Title/notes/saved details/current OCR use M1 FTS.
Sort persists in a small private XDG config file through the repository worker.

Manage stacks includes hidden stacks, create/name/custom RGB color, Home visibility,
full order changes and confirmed deletion. Color now uses a journaled `StackEdit`
variant. Standard accents use known IDs first, then familiar names on opaque imported
IDs, preserving explicit custom colors and the Android neutral-color hash fallback.
All edits use catalog revision checks; failures retain the draft and expose Refresh.
A pending authoritative request cannot be displaced by a subsequent read/save.

Workspace fmt/check/clippy/build and **30 tests** including native PDF pass. New
coverage includes color rollback/stale/reopen journal behavior, protected decision
queue slots, composed search and stable-page selection, persisted sort and imported
category/custom-color presentation. Existing storage/archive mutation paths were
regressed with 13 actual process stops and all three host Java format chains, 21
validation comparisons and 233 currency codes; all pass under M1's existing limits.

```sh
# Same compiler/cache/engine environment as above:
python3 tools/test_m2_catalog.py --output /tmp/stack-m2-catalog-verified
python3 tools/test_m1_process_stops.py target/debug/stack-cli /tmp/stack-desktop-m1/fixtures-final /tmp/stack-m2-catalog-stops
python3 tools/test_m1_java_interop.py target/debug/stack-cli /tmp/stack-desktop-m1/java-host-final/classes /tmp/stack-desktop-m1/fixtures-final /tmp/stack-m2-catalog-java
```

The native catalog scenario passes later-page search and eight real journaled
catalog actions (create, rename, color, hide, order, delete, color and hide). Six
own-window captures include the matching PDF page, full hidden-stack manager and
narrow-dark reopen. JSON traces and saved rows confirm the stable later page,
catalog/Name-sort persistence, unchanged document payloads/revisions and original
hashes. The capture boundary verifies the exact authored graph before editing and
its precisely defined color/visibility changes before reopen. No CLI mutation is
substituted for these UI message-handler saves. Physical pointer/keyboard input is
not claimed; full integrated import/editor/export acceptance is still outstanding.

The first catalog harness completed its GUI actions then failed a test assertion
that assumed no previously retained inactive starter-stack rows. The assertion now
compares the before/after delta of one deleted stack. This was a harness error;
there was no application failure or lost saved data. Its output remains under
`/tmp/stack-m2-catalog-native`; the corrected full native/restart run is above.


## M2.4 editors — implemented, visual/runtime gate pending (2026-10-18)

Base `20b613d`. The working increment adds title, multiline notes, explicit multiple/
empty filing, typed detail save/removal and retained source information, plus saved
page rotation/order/removal and confirmed document deletion. Detail projections page
200 slots at a time; all slots remain reachable. Ordinary edit requests use document
incarnation/revision tokens and M1 journaled transactions. Separate field saves retain
other drafts; failed/stale input stays available, with explicit refresh/review/use-loaded
version retry. Per-document in-memory drafts survive navigation, not process exit.

The suite now has **32 tests**, including paged details beyond 200 and exact CRLF/
Unicode/trailing-newline/amount draft retention across failed and partial saves.
`tools/test_m2_editors.py` executes invalid title validation, deliberately injected
stale token/review retry, then ten real UI-handler mutations. The early native runs
under `/tmp/stack-m2-editor-{native,contrast,widget}` pass saved graph, journal,
source evidence, exact typed strings, physical later-PDF subset, deletion/original
retention and close/reopen checks. The test's manually authored expected final graph
also verifies every authoritative portable row before reopening captures. No CLI
mutation stands in for these UI saves. The stale-token injection is not a claim of
physical user concurrency or pointer/keyboard input.

Visual inspection did **not** pass notes visibility: early screenshot output omitted
notes; subsequent output could repeat an earlier frame or omit other content. A
style adjustment alone did not resolve it. These screenshots cannot establish a
usable native notes editor. The Iced screenshot compositor consumes recorded draw
commands and editor buffers use weak references that relayout replaces. A new
smoke-only draw counter/fresh-frame wait fails honestly when the window stops drawing.
The pinned Winit Wayland focus method is a no-op. A standalone helper builds against
the already-pinned COSMIC toolkit and requests activation only for this app's exact
random nonce title; it does not log foreign titles, alter other windows or change
normal product focus behavior. Activation was found but not acknowledged, and the
content count stayed 2→2 in `/tmp/stack-m2-editor-frame-diagnostic`. Logind separately
reported `LockedHint=no`; the reason for denied activation/stalled draws remains
unconfirmed. Do not call these frames or M2.4 visual acceptance passed.

Failed/diagnostic capture outputs remain under `/tmp/stack-m2-editor-*`; native data
passes and fresh-frame failures are distinct observations. Current scripts require
a fresh draw and therefore may fail while this runtime condition persists. A desktop-
state clarification is pending; independent import/archive worker preparation can
continue without declaring this gate complete. Final M2 still requires visible
native editors and the full import/edit/search/restart/export loop.


## M2.5 transfers — implementation and headless controller checkpoint

Base `4cc6168`; native portal pickers and product controls now dispatch staged
source/archive input, additive archive review/commit and full portable archive
output. A bounded transfer owner performs buffered IO, source verification and
snapshot pinning separately from SQLite. UI receives IDs, counts and receipts.
Cancellation reaches the active `Control` directly. Failed batch retry includes
only failed inputs; committed cleanup retry uses its durable receipt. Publication
is recoverable and refuses existing destinations. Imports never overwrite originals.

Locked offline fmt/check/clippy/build and all **36 tests**, including the explicitly
requested native PDF test, pass. `controller_tests` drives the real application
handlers/workers without a window: three image types, one invalid item, repair/retry,
archive output, cancel at review, additive commit, duplicate-commit suppression,
existing-output preservation and close/reopen. It injects picker results and does
not establish portal, visual, keyboard or pointer acceptance. Separate core tests
prove repository edits continue during blocked staging, direct cancellation, a
frozen graph across later deletion and committed receipt cleanup retry.

M1 regressions after the ownership refactor: `/tmp/stack-m2-transfer-stops` passes
all 13 actual process stops; `/tmp/stack-m2-transfer-java` passes three version
chains, 21 differential validation cases and 233 host currency codes. Android
runtime/Unicode/currency qualifications remain unchanged. Native M2.4 capture and
M2.5 dialog/integrated workflow gates remain pending; this is not M2 completion.


## M2.6 PDF output — native engine and controller checkpoint

Base `6d663c1`. Export PDF is separate from the full library archive. A selected
document token freezes its saved graph; the transfer owner pins original bytes and
writes privately, flushes/syncs, inspects native page count and publishes with
no replacement. Complete unchanged PDFs copy exactly. Subset/reordered/appearance/
image/scan documents use `render_saved` with a 3200-pixel longest edge and a lossless
image-only PDF writer derived from Stack's Apache-2.0 `StreamingPdf.java`. Memory
is bounded to rendered page buffers, one RGB row and at most 4003 object offsets;
output is capped at 8 GiB. The existing locked flate2 1.1.10 is now used directly,
with no package version or checksum change. Product status distinguishes original
text retention from rendered output without selectable text.

The complete locked offline workspace checks pass with **38 tests**. The native
renderer test now also constructs a synthetic M1 library and verifies exact original
PDF bytes; reordered snapshot order survives subsequent deletion; edited subset
with crop/rotation/black-white/flatten; multi-file scan; JPEG, PNG and WebP. Native
inspection confirms saved output counts, orientations and corner RGB tolerances
(8 levels; aspect tolerance .02) against saved previews. Independent appearance
fixtures remain the separate algorithm reference. A new ignored native controller
test, explicitly included in this run, drives PDF import and saved-page output
through actual application handlers/workers. It remains headless, with injected
picker results. A storage test verifies rejection and cancellation after private
write expose no destination or remaining part. All original hashes stay unchanged.

M1 regressions at `/tmp/stack-m2-pdf-stops` (13 process stops) and
`/tmp/stack-m2-pdf-java` (3 chains/21 cases/233 codes) pass. Toolchain 1.99.0 and
PDFium SHA-256 remain exactly the handoff pins. No new shutdown failure observed.
Native portal/complete integrated GUI acceptance remains pending.

Native capture retries under `/tmp/stack-m2-editor-{resumed,unminimize,configure}`
still time out at content draw count 2→2. The own-window helper now unminimizes
only the nonce test window and waits for asynchronous activation: acknowledgement
is true in the later two logs, but fresh drawing still fails. A temporary own-window
resize diagnostic did not fix it and was removed. Do not attribute this to denied
activation or call these frames usable editor evidence.


## M2.7 closeout checkpoint — native redraw gate remains open

Source base `207c66b`; the current increment adds `workflow_smoke.rs` and
`tools/test_m2_workflow.py`. It starts from the exact authored M1 graph, imports
all four supported source types through product handlers, stages/cancels archive
review, saves title/notes/multiple filing/exact amount, searches a later page,
exports the original PDF, edits a saved subset, exports rendered PDF and a full
archive, then verifies graph/hash/journal outcomes and prepares native reopen.
Picker responses and actions are injected; this does not exercise portal selection
or physical input. The reopen reference is bounded, validated and restricted to
nine authored documents with fixture original hashes, then matched to every saved
row/original by the repository before capture. Normal launches have no such path.

The same scenario passes in the headless application driver, which explicitly
omits its four capture requests. Its final output has **9 documents / 11 pages**.
The preserved synthetic artifacts are `/tmp/.tmpinAcY4`; host Java reads the
application-exported archive and a fresh Rust library restores it with exactly
equal portable graph, recorded at `/tmp/stack-m2-workflow-host/report.json`. This
is handler/archive/native-engine evidence, not a GUI pass. Existing controller
coverage now also proves malformed archive failure, a real title save invalidating
an open archive plan, explicit review-again recovery, duplicate-commit suppression
and clean close while another archive review is pending.

Final locked offline fmt/check/clippy/build and **39 tests** pass at
`/tmp/stack-m2-final-checks/report.json` and `test.log`. All three ignored tests
(native renderer, native application PDF scenario, maximum-count benchmark) were
explicitly included; none was skipped or counted twice. This report records source
file SHA-256 values and compiler 1.99.0 for the uncommitted increment above its base.
The native PDF worker initializes once across each test process and drains before
shutdown. No new unexplained native shutdown fault observed. The retained M0/M2.1
SIGSEGV watch and Android/host limitations remain open.

### Maximum-count observations

All data is independently authored; numbers are debug observations on this host,
not budgets, full-byte-limit measurements, release benchmarks or accessibility proof.

| Run | Observation | Scope |
| --- | --- | --- |
| Fresh CLI restore | 1,000 documents / 10,000 pages; 22.24 s; 310,040 KiB peak RSS | `/tmp/stack-m2-counts/restore.time` |
| CLI search with reopen | 1,000 correct later-page hits; 1.47 s; 25,672 KiB peak RSS | Same synthetic library; persisted FTS |
| Application archive stage/review | 10,037 ms | Headless real product handlers and bounded workers |
| Application commit/browse | 9,987 ms; 1,000 visible hits | Includes saved commit and bounded list projection |
| Application debounced search | 1,595 ms; all 1,000 stable later-page IDs correct | 100 superseded query messages then final query |
| Executor ticker | 7,973 µs largest observed interval for a 5 ms ticker | Entire application transfer/search scenario; no window/frame timing |
| Application test process | 428,764 KiB peak RSS | Includes fixture creation and retained test graph plus application; not an isolated product-only RSS |

Final application figures are emitted in `/tmp/stack-m2-final-checks/test.log`.
A focused pass at `/tmp/stack-m2-application-counts-fixed.log` observed 9,830 /
10,814 / 1,612 ms, 6,818 µs tick gap and 420,424 KiB peak. The first benchmark
fixture omitted required suggestion rows and failed validation; it was corrected
without weakening validation (`/tmp/stack-m2-application-counts.log`).

### Actual native gate failure and remaining work

`/tmp/stack-m2-workflow-native/report.json` records **failed**, not passed. The
native application imports all four authored sources (9 active documents, 6 total
journal events including the initial two), stages its archive review, activates
its exact own window, then cannot draw a fresh capture: count stays 2→2. The
process exits unsuccessful and staged cleanup leaves zero operations. No editor,
export or reopen native acceptance follows from this partial native run.

An isolated nested COSMIC attempt used a private runtime, XDG roots and D-Bus
session. It stalled before usable application initialization, and only that own
compositor was terminated. Its client then reported the expected connection reset
under `/tmp/stack-m2-editor-nested`; this is distinct from an unexplained PDFium
shutdown fault. No nested compositor remains. The cause of missing frame callbacks
is unconfirmed. The user subsequently confirmed that the physical desktop was awake
and in active use; display sleep is not an established explanation. No unlock or
monitor-power changes were performed.

Remaining acceptance: reliable visible notes/native frames; portal open/save and
cancel interaction; full native workflow and same-root reopen; inspected 1080×780
and 680×680 light/dark/enlarged-text layouts; recorded keyboard/focus behavior;
native rapid-selection/delete/reorder/close job races and missing-engine/storage
recovery observations. Existing worker/token/core tests are supporting evidence,
not substitutes for those UI observations. Sidebar/window typography was not
enlarged by content text scaling; the M0 limitation remains explicit. Do not mark
M2 delivered or begin M3 from these checkpoints.


## Native follow-up and terminal-only testing (2026-10-18)

Source base `a63c1c4`. The user confirmed the desktop was awake and later requested
headless terminal testing. Continue without opening further GUI test windows.
Earlier failure records above remain historical evidence, not current prerequisites
for all engineering work.

The native editor run `/tmp/stack-m2-editor-protocol` completed ten journaled edits
and restart with six captures. Inspection confirmed visible multiline Unicode notes.
Protocol diagnostic logs were sanitized to retain only frame/configure/buffer events
and ordinary diagnostics; do not enable unfiltered `WAYLAND_DEBUG`, since the toolkit
also receives foreign-window protocol events.

`/tmp/stack-m2-workflow-visible/report.json` passes the full injected native workflow:
four supported imports, review/cancel, saved edits, later-page search, original and
rendered PDF, portable archive, host Java graph comparison and exact same-root reopen.
Five captures include the reopen. The harness previously asserted transient batch
outcomes after opening archive review had correctly cleared them; it now verifies
the durable import journal events. This remains message/picker-result injection,
not actual chooser selection or physical keyboard/pointer evidence.

The selected document now has its own preview view with Back/Edit/Export actions;
the sidebar collapses at narrow widths. Synthetic capture windows enforce their
requested dimensions; ordinary product windows remain resizable. The default build
now includes the already-pinned libcosmic wgpu backend with tiny-skia fallback;
`--no-default-features` retains a software-only build. No dependency version, Git
revision or checksum changed. The capture wrapper forwards accessibility, IDs and
drag destinations, using the existing pinned `iced_accessibility` as a direct edge.
Archive cancellation now reports completion after discarded work is cleaned up.

The wgpu native workflow passes at `/tmp/stack-m2-wgpu-workflow`; forcing tiny-skia
in the same binary also passes at `/tmp/stack-m2-cpu-same-build`. A later software
layout attempt still stalled while the desktop was awake. Backend choice is a
comparative observation, not a proven root-cause fix. `/tmp/stack-m2-layouts-gpu`
passes all four workflow/reopen cases, five captures each, at actual 1080×780 and
680×680 dimensions in light/dark themes. Inspected narrow preview and notes are
readable. The two requested scale-2 cases did **not** demonstrate enlarged native
fonts: the attempted window-scale change was reverted to explicit content-only
`--text-scale`. Those earlier captures do not validate the reverted scale path or
full native typography. Enlarged-text and focus/input acceptance remain pending.

`tools/test_m2_portals.py` is an unfinished native chooser harness on a private bus,
private XDG/runtime roots and exact nonce-titled synthetic dialogs. It verified real
Cancel without a saved change and enumerated the four filtered authored sources.
It has **not** passed import/save/round-trip: the latest exercised attempt
`/tmp/stack-m2-portals-traced` failed a selected PDF path with “No such file or
directory”, leaving the original five documents. Subsequent path guards/logging
are compile-checked only. The open request uses the pinned ashpd re-export to carry
its directory, which the pinned libcosmic open wrapper omits. No new GUI run follows
the user's terminal-only request. The chooser still lacks a parent identifier.
Private AT-SPI probes also exposed an empty app tree; the pinned Winit adapter
initializes its placeholder but does not publish widget-tree updates. The smoke
wrapper forwarding fix alone does not establish screen-reader acceptance.

Current-source verification is `/tmp/stack-m2-terminal-checks/report.json`: it removes
DISPLAY/WAYLAND_DISPLAY, uses isolated XDG directories, and runs locked offline
fmt/check/clippy/build plus every workspace test, explicitly including ignored
native-engine and maximum-count tests. These tests run the native PDF engine and
real handlers/workers without creating windows. See the report for exit codes and
source hashes. All **39 tests pass, zero failed/ignored**; fmt/check/clippy/build,
software-only compile and Python harness syntax also pass. The terminal maximum-count
run observed 14,412 ms stage/review, 15,609 ms commit/browse, 2,345 ms search,
12,960 µs maximum ticker interval and 425,000 KiB test-process peak RSS; these remain
host/debug observations, not displayed-frame budgets. The older
`/tmp/stack-m2-final-checks` report describes the prior
checkpoint. Core/archive/render behavior is unchanged since that checkpoint, so its
13 process-stop and Java/Rust interoperability results remain applicable.

M2 remains in progress. Remaining checks are native chooser open/save round-trip,
full native typography, keyboard/focus/accessibility, repeatable frame behavior,
and native rapid-job/failure observations. Continue independent headless engineering
without requiring the user to change desktop power or session state. Preserve the
M0/M2.1 shutdown watch, existing host/device qualifications and private-data boundary.


## Engineering closeout; user interface review last (2026-10-18)

Source base `8e8856e` plus the source-hashed closeout increment. The user directed
completion of remaining implementation and automated checks, reserving all visual
checks and interface review for their final step. This supersedes the earlier
instruction to finish agent-driven native acceptance before stopping.

Implemented corrections:

- The pinned `src/app/mod.rs::run` builds Iced without its scale callback, explaining
  why `Settings::scale_factor` had no effect. `launcher.rs` now uses the same public
  COSMIC shell/update/view/subscriptions on the pinned Iced daemon builder and
  registers `Cosmic::scale_factor`. `--text-scale`/`COSMIC_SCALE` scale the entire
  shell once; per-content multiplication was removed. No toolkit fork or dependency
  version update. Fonts come from the installed COSMIC/system font configuration.
  Native window launch, theme/scale appearance and focus are left for user review.
- Ctrl+F returns to and focuses search. Escape first cancels an editor confirmation,
  then leaves the editor while retaining its in-process draft. Headless widget tests
  exercise the native search text-entry event and focus-next traversal, not physical
  OS keyboard input.
- Search retains all 1,000 supported hits but builds only 50 result rows at once.
  Previous/Next and a displayed range keep every result accessible. Query/filter/sort
  reset paging; returning from a document retains the current result page. A last-page
  result still opens its stable later matching page. The 1,000-widget layout initially
  took 241 ms and stretched the executor ticker to 249 ms; limiting widget rows removes
  that measured pause rather than merely weakening the target.
- A post-commit cleanup failure now refreshes the saved browser while retaining its
  committed receipt and cleanup retry. Close drains workers off the executor and
  rejects late results/actions so they cannot repopulate UI state or dispatch work.
- The failed GTK automation supplied multiple quoted names to a single-path Location
  field. The retained GUI harness now supplies individual absolute authored paths;
  that revised GUI interaction is intentionally unrun. Its former failure is not
  evidence of a production URI decoding defect. The new headless protocol test sends
  real pinned ashpd OpenFile/SaveFile requests to a private synthetic D-Bus service:
  native filters/multiple/modal options, Cancel, four source types, archive output,
  additive review/cancel/commit and byte-identical PDF output all pass. Filenames
  contain spaces, Unicode and `#`. These are real client/protocol and saved-state
  tests, not installed-chooser UI or pointer automation.

Automated acceptance is reproducible with `tools/run_m2_checks.py --output NEW_TMP`.
It removes display variables, uses isolated XDG roots, runs locked offline
fmt/check/clippy/build and **43 tests, zero failed or ignored**, checks software-only
compilation and Python syntax, then runs a separate maximum-count process and the
private-bus portal protocol scenario. The final report is
`/tmp/stack-m2-closeout-verified/report.json`, including compiler, native-engine hash,
source hashes, exact commands and outcomes. The separate protocol process repeats
one test; it is not added again to the 43-test count. Existing core/archive/render
implementations and pins are unchanged; prior M1 process-stop/Java checks remain
applicable alongside the freshly rerun workspace storage/render tests.

The new storage test causes real transaction failure, verifies exact notes/draft
retention, then performs refresh/review/retry and verifies durable saved values.
Unreadable input retries only after it becomes readable. Real post-commit failure
retains one receipt; repeated cleanup retry adds no duplicate or journal event.
Delayed real renders span rapid selection and deletion; the deleted original bytes
remain intact. Another delayed render spans a saved PDF page reorder and cannot
replace the new page's pixels. Closing while preview work is active stays off the
executor and rejects late query/selection/render messages. Missing-engine preview
leaves saved data available and succeeds through Retry once configured; no
process-wide PDF bindings are reinitialized. Older invalid/stale editor, archive,
snapshot/output preservation and transfer cancellation tests still pass.

Two offscreen tests use the actual COSMIC widget tree and pinned tiny-skia renderer,
without creating a window: 40 whole-shell draws across two sizes/themes/scales,
plus 12 editor draws. Real text entry/focus operations, populated widget accessibility
nodes and repeated nonblank notes pixels are asserted. They catch the historical
blank-buffer symptom in offscreen rebuilding, but do not establish native compositor
frame callbacks, physical input, full screen-reader functionality or visual comfort.

### Performance targets and final observations

Targets are bounded host/debug engineering checks derived from prior M2 observations,
not release promises or live compositor frame budgets. The fresh process contains
fixture generation, retained archive graphs and app workers; its RSS is not an
isolated product-only measurement. Combined workspace-process high-water memory is
recorded separately and is not used for this fresh-process target.

| Measurement | Fresh-process observation | Target maximum |
| --- | --- | --- |
| Empty initial browse | 26 ms | 500 ms |
| Cancel signal / cleanup after 70,917 processed bytes | 7 µs / 19 ms | 1,000 µs / 1,000 ms |
| Stage/review 1,000 documents / 10,000 pages | 11,647 ms | 30,000 ms |
| Commit and browse | 13,584 ms | 30,000 ms |
| First 50-result-page layout/draw | 87 ms | 150 ms |
| Native widget scroll/draw | below 1 ms at millisecond resolution | 50 ms |
| Debounced later-page search | 1,671 ms | 5,000 ms |
| Maximum interval of 5 ms executor ticker | 90,503 µs | 150,000 µs |
| Test-process peak RSS | 429,196 KiB | 524,288 KiB |

All fresh-process targets pass. A preceding combined suite reported 547,584 KiB,
which failed the original 512 MiB check because earlier PDF/test allocations share
that process. That retained failed aggregate report is `/tmp/stack-m2-closeout-final`;
the corrective measurement runs maximum counts independently rather than conflating
unrelated test allocations with this scenario. The final report retains both scopes.

The remaining user step is the single project-plan closeout checkbox with the
[M2 review guide](M2_REVIEW.md). Prepared `/tmp/stack-m2-user-review` contains five-
and sixty-document synthetic libraries, import/archive sources, an export directory
and user-run launchers. Preparation and row-count verification open no windows.
Known platform qualifications remain explicit: the pinned Winit AccessKit adapter
publishes only a placeholder to AT-SPI, portal requests have no parent identifier,
and live compositor frame stalls plus the historical shutdown watch are not claimed
resolved by offscreen tests. The user reviews those native boundaries last; any
observed failure returns to engineering. Full M2 acceptance is not recorded yet.


## Interface review and navigation correction (2026-10-18)

The user found the first screen wordy, could not find a return from Manage stacks,
and reported that menu locations changed. This is actual user interface feedback;
the prior engineering pass did not establish usability acceptance. The careful
[interface review](M2_INTERFACE_REVIEW.md) records source-traced navigation defects,
Android comparisons, BookStack/Paperless/native navigation references and which
broader editor/list/color/draft proposals remain unimplemented.

Base `fd9dde7`. Implemented the first navigation correction: fixed header Back and
Library menu; persistent search/Add; sidebar destinations including Manage stacks;
shorter first-screen controls; actual sidebar navigation out of management;
preservation of query/filter/result page and recorded scroll on Back; a loading
route that retains Back; and saved-list refresh after imports that keeps the
current editor/document/draft. Recovery instructions are conditional on a failed
save, source history is expandable, and save confirmations identify their field.
Native menu placement uses a COSMIC popover rather than an unconfigured Wayland
popup surface. No storage/archive schema, dependency, original-byte or Android edit.

`navigation_back_sidebar_and_background_refresh_keep_the_users_location` exercises
the reported route and compares Back/Library/Add bounds across the list, manager,
document and editor. It follows actual sidebar callbacks, opens/closes the Library
menu through app handlers, and verifies that a completed real import retains an
unrelated unsaved title and the active editor. Offscreen authored renders live in
`/tmp/stack-ui-navigation-review`; these are not live Wayland captures. The helper
retains paragraph caches through screenshot so weak text buffers are not dropped.

Current source-hashed report `/tmp/stack-ui-navigation-final/report.json` passes
fmt/check/clippy/build, software-only check, all **44 tests, zero failed/ignored**,
Python syntax and six private-bus native FileChooser protocol requests. All ten
fresh-process performance targets still pass: initial15ms, cancel5µs/cleanup21ms,
stage11860ms, commit/browse13287ms, first result page layout100ms, scroll below1ms,
search1989ms, maximum5ms ticker interval105727µs and429172KiB process peak. These
retain the prior debug/fixture/process/compositor scope distinctions. The earlier
`/tmp/stack-ui-navigation-checks` pass precedes the final menu assertions and the
manager scroll-restore correction; use the final report for current-source claims.

The user's final visual review now starts with their exact Manage stacks/Back
journey in `M2_REVIEW.md`. The review document identifies further design work rather
than claiming a complete redesign. Full M2 usability acceptance remains pending;
no new GUI test window was opened and existing user windows were left alone.


## User HTML mockup adaptation (2026-10-18)

Base `18df1f8`. The local HTML supplied in `Mockup/` is now the primary interface
reference; see the design mapping and remaining differences in
[M2_INTERFACE_REVIEW.md](M2_INTERFACE_REVIEW.md). The supplied file remains
unchanged and is not used as a data fixture. Native COSMIC remains the UI toolkit.

The revision moves backup actions to the sidebar bottom, adds grid/list with a
saved preference, places document information beside preview, and divides editing
into Information/Details/Pages. Narrow sizes switch preview and information while
keeping the same draft. Explicit saves, exact values and recovery semantics remain;
a dirty indicator distinguishes unsaved work. Cards use real titles/search snippets
and document icons; a real thumbnail cache, separate starring and autosave are not
claimed. Document deletion is under Information and page actions under Pages.

The new regression sends mouse press/release events through actual widgets before
running their messages through the application handlers/workers. It clicks List,
Edit document, the three sections, Rotate page and the narrow preview/information
switch. It verifies saved rotation and retention of an unsaved exact title. This
is headless native-widget interaction, not OS pointer/chooser/compositor evidence.
A repository reopen regression verifies sort and layout persistence. The existing
navigation test still follows Manage stacks → Back and verifies fixed control
bounds and draft preservation during a real background import. Explicit toolbar
rows/columns expose accessibility children that the pinned wrapped widget omitted
in the initial pointer test; all prior platform qualifications remain.

Final reproducible command (with the compiler/cache/engine environment above):

```sh
STACK_M2_UI_REVIEW_OUT=/tmp/stack-mockup-workspace-final-review \
  python3 tools/run_m2_checks.py --output /tmp/stack-mockup-workspace-final-checks
```

The final source-hashed report is
`/tmp/stack-mockup-workspace-final-checks/report.json`; offscreen captures cover the
full native shell and the new wide/narrow editor controls. The earlier
`/tmp/stack-mockup-workspace-checks` run preceded the final card/placement changes;
its captured sources do not represent the final revision. Core/archive/render
APIs, schema, journal, dependency/native pins and originals are unchanged, so the
previous M1 process-stop/interoperability evidence remains applicable. Config
layout reads/writes use the existing background repository owner and tolerate old
sort-only preferences. No new document storage API was added.

No personal archive, Android/device, canonical library, existing user window or
unrelated working-tree edit was changed. Full M2 acceptance remains the single
final user review in the project plan, using the updated M2_REVIEW guide.

All locked offline fmt/check/clippy/build/test checks, software-only compilation,
Python tool syntax, and private D-Bus portal protocol checks pass. **45 tests pass,
zero failed/ignored**; all 61 recorded source hashes match. Binary and 14 authored
offscreen image hashes are retained in the report. No native window was opened.
Fresh maximum-count process: initial browse 27ms; cancel signal 3µs/cleanup18ms;
stage12,779ms, commit/browse14,863ms, first50-result grid layout112ms,
scroll<1ms, search1,913ms; maximum5ms ticker interval119,014µs;
428,804KiB peak RSS including fixture allocation. All ten documented host debug
targets pass. Combined-suite process observations remain separate from this fresh
maximum-count process. These figures do not establish native compositor frame times.


## Closer visual alignment with the supplied HTML (2026-10-18)

Base `574bbec`. The user's latest scoped instruction explicitly asks to run the
app, open the HTML in a browser and compare native screenshots. This supersedes
the earlier headless-only restriction for this review; the rulebook records it.
The HTML source was read and rendered in installed Chromium at1080×860. The native
app was run on COSMIC with its normal wgpu renderer at the same dimensions and
with the user's installed theme copied into isolated XDG config. All captures
show only the app's own authored synthetic review window, which closed normally.

Readable inputs: six independently authored text PDFs, including a two-page water
bill, receipt, health summary, insurance renewal, bank statement and reservation.
`prepare_ui_visual_review.py` authors them without new dependencies. The native
`--visual-review` route validates their six fixed hashes and exact saved graph
before rendering. Existing color/Unicode fixtures remain unchanged for their tests.
The post-capture graph and every original hash equal the prepared state; journal
count16 equals its seeded count. No canonical/private archive or phone was opened.

Implemented and compared directly with the browser reference: a flat180px sidebar
with native list controls, restrained single-border thumbnail surfaces, real
saved-first-page thumbnails with name/date/stack, contextual titles/Back, hidden
library search/Add in document workspaces, compact viewer controls, an aspect-
preserving large page, and a280px information/editor panel. Membership chips expand
into choices; labels/details/notes remain readable and explicit saves/recovery
remain. Pages edits never replace the page. Narrow information/editing layouts
keep a page above the scrolling controls.

Initial native images exposed black transparent areas, a button style with fully
transparent foregrounds and missing sidebar content in a later GPU capture. The
native content background was retained; tile foregrounds now use native list-item
styling, and the flat sidebar uses native list buttons with an isolated clipped
drawing region. Final wide captures were inspected after these corrections.
An eager50-thumbnail prototype also exceeded prior debug responsiveness targets;
visible rows and decoding are now bounded around the viewport, while the full50-
result scroll range remains. Actual scroll messages load newly visible thumbnails.
The first new PDF-thumbnail regression exposed the existing process-wide terminal
PDF engine lifecycle; that scenario now runs in its own fresh process. Intermediate
failures and captures remain under `/tmp/stack-ui-refinement` and are not final proof.

Final native comparison:

- Browser library: `/tmp/stack-ui-refinement/mockup-library-1080x860.png`.
- Browser viewer: `/tmp/stack-ui-refinement/mockup-viewer-1080x860.png`.
- Native library/viewer/information/details/pages:
  `/tmp/stack-ui-refinement/native-confirmed/01-library.png` through
  `05-edit-pages.png` (all1080×860, normal native exit0).
- Preservation, binary/image hashes and scope:
  `/tmp/stack-ui-refinement/visual-report.json`.
- Final engineering report:
  `/tmp/stack-ui-refinement/acceptance-checks/report.json`.

The capture scenario changes routes through real application handlers and reads
back real native frames. This is agent-performed appearance verification, not
physical keyboard/picker input, a whole-desktop screenshot, or AT-SPI acceptance.
Existing platform qualifications and the separate final user M2 acceptance remain.
No new document-write API, database schema, archive, journal or dependency version
changed; the tile projection is read-only and uses the repository worker. The
thumbnail worker is bounded and joined before terminal PDF shutdown. No originals
or saved metadata are rewritten to create thumbnails.

Final checks:47 passed,0 failed/ignored; fmt/check/clippy/build, software-only compile,
Python syntax and private portal protocol pass. All source hashes match the final
revision. Fresh1000doc/10000page process: browse27ms; cancel14µs/cleanup28ms;
stage12925ms, commit/browse16047ms; first50-result page layout143ms, scroll<1ms,
search3319ms; maximum5ms ticker interval148170µs; RSS429368KiB. All ten existing
host-debug targets pass; these are not native GPU frame budgets. The earlier
intermediate target misses remain recorded. A fresh PDF subprocess respects the
engine's existing terminal shutdown lifetime without changing production lifecycle.

The additional680×680 native run also exits0 and is visually inspected: page stays
above Information, Details and Pages controls. Final images and Chromium references
are bundled in `/tmp/stack-ui-refinement/screenshots.zip`. Screenshot comparison
is complete for this requested appearance scope; physical input, chooser parenting
and the pinned AT-SPI adapter remain their explicitly qualified separate gates.

## User feedback: rendering speed, searchable text and phone transfer (2026-10-18)

The user reports the interface is “much, much better” and identifies PDF rendering
speed and missing content-search results in the readable sample library. This is
specific interface feedback, not blanket completion of every M2 interaction gate.

The library already renders 320×400-bounded first-page thumbnails, rather than full
viewer pages. Its UI handles remain bounded to the current50-result page. Previously,
returning from the viewer cleared those handles and decoded every tile again. The
thumbnail worker now retains100 recent small handles across navigation/search; the
viewer worker retains three1200×1600-bounded pages. Both use least-recent eviction,
saved-page identity/appearance and original path/device/inode/length/modification
stamp keys. A missing/replaced source cannot reuse old pixels. Failures are retried,
and the existing generation checks continue to reject obsolete UI results. These
are bounded session-memory caches, not persisted thumbnails or archive metadata.

Development and test profiles now optimize just `stack-render` and `image` at level2;
the native UI remains a development build. On the existing authored PDF regression,
the cold worker render was671418µs before this profile adjustment and103153µs after;
repeat requests were76µs and65µs, respectively. The cached request returns the same
`Arc<Preview>`; a saved rotation returns a different render. These individual host
test observations are not end-to-end UI latency or a broad PDF benchmark.

The visual samples have no stored OCR rows. Ordinary desktop PDF import currently
neither performs OCR nor extracts embedded PDF text; visibility/selectability in a
PDF does not populate Stack's search index. Existing imported, revision-compatible
phone OCR participates in search. A new read-only count, using the current-text
revision/configuration predicate, supplies **Document text** in the information
panel. This reports missing page text honestly, without introducing an OCR engine
or rewriting saved text. Tests cover the count against imported current text,
matching-page search, stale text, actual thumbnail-handle reuse and cache eviction/
retry/source replacement/appearance invalidation.

Fresh native1080×860 COSMIC/wgpu captures use the same authored readable PDFs and
copied user theme in a new disposable library: `/tmp/stack-ui-followup/native`.
Library, viewer and editing captures were inspected; the text-status field fits,
and the page stays visible. `/tmp/stack-ui-followup/visual-report.json` records image/
binary hashes and scope. Saved graph, original hashes and16 seeded journal entries
are unchanged. The user's active review library and all other windows were preserved.

The [phone transfer guide](../STACK_DESKTOP_PHONE_TRANSFER.md) documents a fresh
Android **Backup library** → copied `.stackbackup` → desktop **Import backup…** →
**Import reviewed copies** workflow, using a normal persistent desktop library.
It explicitly distinguishes additive snapshots from unimplemented M3–M6 merge,
Android exchange and paired transport. No private archive/phone was opened, imported,
updated or used as a test. No stored schema, write API, journal, archive envelope,
native/dependency version, scanner or Android implementation changed.

Engineering reports and retained intermediate observations are under
`/tmp/stack-ui-followup`; the consolidated `report.json` records final source hashes,
checks, fresh maximum-count targets and the separately authorized private D-Bus
protocol run. The filesystem sandbox blocks that test's socket, so its failed
sandboxed attempts remain separate from the successful synthetic protocol check.

Final source-matched checks:49 passed,0 failed/ignored; fmt, locked offline workspace
check/clippy/build/test, software-only compile, Python syntax and six actual private
D-Bus portal requests pass. All ten fresh-process targets pass at1000documents/
10000pages: initial26ms; cancel7µs/cleanup26ms; stage13104ms; commit/browse15595ms;
first50-result page layout140ms; scroll<1ms; search2115ms; ticker gap142943µs;
RSS427624KiB. The final PDF worker observation is75188µs cold and91µs cached.
These remain host debug/headless measurements, not release or compositor guarantees.
An intermediate optimized run exceeded two timing targets; removing unused initial
overscan reduces offscreen work without changing page size or raising any budget.

## Import menu click trap and remaining-work handoff (2026-10-18)

The user clicked **Import reviewed copies**, saw no progress and reported a locked
interface. Read-only inspection was limited to process/thread metadata, aggregate
desktop document/import counts and staging-marker existence: the sample library
still had six saved documents and one staged operation, with no publication journal.
No private archive, document body, title or original was inspected or imported.
The user confirmed Escape restored responsiveness, identifying the modal backup
menu's interception of clicks outside it.

The menu now dismisses on outside clicks. Review actions use native `flex_row`
instead of the pinned wrapping row that omitted their accessibility nodes. The
commit button has a stable ID. Active transfers show an indeterminate native
indicator, reviewed document count, phase-specific guidance and processed MB;
review/cleanup-needed states do not poll idle work. Existing cancellation, receipt
idempotency and saved/error outcomes are unchanged.

The new full-shell widget regression presses the import button with the menu open,
applies menu dismissal and an intervening progress tick, then releases it. It pauses
the real commit before publication, draws/checks reachable controls at1080×780 and
680×680, rejects a repeated commit, releases the worker and verifies one imported
copy. Restoring the original modal setting deliberately reproduces the failure;
`/tmp/stack-import-feedback/original-modal-regression.log` retains that observation.
Offscreen busy captures are in `widget-captures` under the same root and were
inspected. These are headless widget evidence, not physical input or native frames.

`/tmp/stack-import-feedback/final-checks/report.json` matches current sources:
50 tests pass,0 failed/ignored; fmt/check/clippy/build, software-only and private
portal checks pass. Eight of ten fresh maximum-count targets pass. First-page draw
171ms exceeds150ms and maximum ticker gap178312µs exceeds150000µs; RSS429700KiB and
search2457ms pass. Overall performance acceptance remains failed. The earlier
revision's passing run is retained separately, not substituted for this result.

Before the latest headless instruction, the existing synthetic native workflow
completed normally, with five captures, unchanged originals, exact restart and host
Java export checks. Report: `/tmp/stack-import-feedback/native-workflow/report.json`.
Its binary hash matches the updated build; its older color/Unicode workflow fixtures
and explicit light theme explain the different appearance the user noticed. This
is not new readable-PDF appearance acceptance. It had already exited when checked;
no user window was closed. All subsequent testing remains headless.

The user requests a playbook and start prompt for all remaining work, with prompting
only at the end. See [remaining playbook](../STACK_DESKTOP_REMAINING_PLAYBOOK.md)
and [kickoff prompt](../STACK_DESKTOP_REMAINING_START_PROMPT.md). They distinguish
M2 closeout, desktop text extraction/OCR, M3–M6 sync and integrated release, preserve
the single final user checklist and require autonomous independent execution when
the prompt is invoked. This documentation increment does not implement those later
stages or repeat the user's private import.


## R1 engineering and R2 text closeout — 2026-10-18

[Remaining-work evidence](REMAINING_EVIDENCE.md) and [results](REMAINING_RESULTS.json)
retain all source hashes, failed attempts and follow-up deltas. Drafts survive
individual-detail switches within this process; quit requires keep editing or
deliberate discard. Native palette/custom colors now apply visibly, including
standard categories. The native adapter publishes current children and targeted
actions; wrapping rows now expose their controls. Choosers export actual native
parents. Physical AT-SPI/chooser/keyboard/visual acceptance remains unperformed.

64 workspace tests and all10 fresh-process targets passed:122ms first layout,
124739us maximum executor gap,430832KiB peak RSS. The private socket needed approved
access; its separate6-request protocol test passed without a chooser/window. M1's
13 SIGKILL cases and host Java/Rust archive chains passed. A subsequent sync-only
no-resurrection guard passed23 core tests; final clippy/build passed. That source
delta is explicitly recorded; no unchanged performance rerun was used to find a pass.

Ordinary imports now become searchable offline through embedded PDF extraction or
pinned English OCR of the saved raster, with durable guarded jobs, retry/cancel,
truthful per-page states and stable matching-page navigation. Real engine and UI
worker tests preserve originals, cropped/removed page exclusions and exact typed
drafts. This does not establish general OCR accuracy or full M2 native acceptance.
