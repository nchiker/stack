# Desktop evidence — M2 refinement and user feedback

Latest import follow-up: backup menu outside clicks now dismiss it, and active
imports show native busy/progress feedback. User confirmed Escape recovered the old
window.50 tests and locked build/lint/protocol checks pass, but the latest fresh
stress run misses two150ms timing targets; see `/tmp/stack-import-feedback/final-checks/report.json`
and the latest [M2 evidence](desktop/M2_EVIDENCE.md). All further agent testing is
headless. The [remaining playbook](STACK_DESKTOP_REMAINING_PLAYBOOK.md) and
[start prompt](STACK_DESKTOP_REMAINING_START_PROMPT.md) organize autonomous execution
through text search, M3–M6 and final release, with user prompting only at the end.

M2 engineering closeout is ready for final user visual/interface review. See
[M2 evidence](desktop/M2_EVIDENCE.md), [results](desktop/M2_RESULTS.json) and the
[review guide](desktop/M2_REVIEW.md). The earlier refinement's47 headless tests, locked offline checks,
native portal protocol, offscreen widget/focus/race/failure checks and fresh-process
maximum-count targets passed for that revision. Full M2 interaction acceptance remains separate;
platform qualifications remain explicit. The user reported navigation problems;
[interface review](desktop/M2_INTERFACE_REVIEW.md) now records both the initial
navigation corrections and the later adaptation to the user's supplied HTML
mockup: grid/list, preview beside information and grouped editing sections.
The later explicit visual-refinement request includes actual own-window native
runs on readable synthetic PDFs and direct comparison with Chromium mockup
screenshots at 1080×860; see the current interface review. Earlier no-window
statements refer to their respective headless closeouts.
The user subsequently reports the interface is “much, much better.” Rendering/text
feedback adds bounded session caches, optimized rendering/image development builds,
less offscreen tile layout and an honest searchable-page-text status. Latest check
evidence is `/tmp/stack-ui-followup/report.json`, native captures `final-native` under
that root. The [phone transfer guide](STACK_DESKTOP_PHONE_TRANSFER.md) explains
current additive backup transfer and unimplemented synchronization. Neither readable
PDF samples without stored OCR nor ordinary desktop import imply a new OCR engine.
M1 storage/archive evidence and its
compatibility qualifications remain in [M1 evidence](desktop/M1_EVIDENCE.md).
The M0 record below is historical. The shutdown stability watch remains active;
Android runtime restore and M3–M6 sync are separate pending gates.

# Desktop M0 foundation — 2026-10-17

M0 implementation is delivered: a pinned Rust workspace, actual native COSMIC
window/sidebar, bounded image/PDF preview, independently authored fixture generator,
headless smoke harness and native-window smoke runner. The acceptance evidence
below covers this foundation. One earlier native shutdown fault remains a stability
watch; its exact cause is unconfirmed. Persistence, archive interchange and sync
are not implemented. Resume the [plan](STACK_DESKTOP_PLAN.md) at M1, carrying this
watch forward in the [ledger](desktop/EXECUTION_LEDGER.md).

Source: [`desktop/`](../desktop/README.md), package version `0.1.0-dev`, app ID
`org.stackdocs.desktop`. Git base at task start was `3a23d5e`; existing Android
0.8.9 edits and unrelated work were preserved. No phone access, Android build/test,
canonical update, personal document operation or personal screenshot was performed.

## Delivered behavior

Four workspace crates separate domain bounds, rendering, a headless CLI and the
COSMIC application. The default window is an empty foundation. Explicit demo mode
opens verified synthetic samples with page navigation, mouse-wheel zoom/pan,
temporary quarter-turn rotation and error/retry presentation. Native navigation
shows All documents/Bills/Receipts; it does not imply a saved stack catalog yet.

The renderer uses image 0.25.10 for JPEG/PNG/WebP and pdfium-render 0.9.4 with the
pinned PDFium `chromium/8100` engine. Source original hashes remain unchanged in
tests and smoke rendering. JPEG EXIF orientation is applied before explicit rotation.
Portrait and landscape PDF pages retain aspect/orientation. A persistent native
worker serializes PDF operations with a queue of one; each request owns its document
and page until copying the bitmap. Shutdown drains and joins that worker.

Output is bounded to 4,096 pixels per edge / 16 Mi pixels. Input size is capped at
256 MiB, PDF page count at 1,000, and image decode at 24 megapixels with a 128 MiB
decoder allocation limit. These are implemented preview bounds; full hostile archive
validation, native-parser isolation and total-process resource budgets are later work.
Saved crop, enhancement and flattening rendering is still an M2 requirement.

Dependency pins, licenses and native setup are recorded in
[dependency decisions](desktop/M0_DEPENDENCIES.md) and the
[487-package Linux Cargo inventory](desktop/M0_DEPENDENCIES.json). The setup tool
verifies the engine archive/shared-library digests and retains all native notices.
No OS development packages, global linker shims or runtime downloads were needed.
The exact compiler file pins 1.99.0; this session used the existing stable compiler
reporting that exact version with an isolated temporary Cargo cache.

## Actual checks

All final commands ran from `Stack/desktop/` with:

```sh
export RUSTUP_TOOLCHAIN=stable
export CARGO_HOME=/tmp/stack-desktop-dev/cargo-home
export CARGO_BUILD_JOBS=4
export STACK_PDFIUM_LIBRARY="$PWD/runtime/pdfium/lib/libpdfium.so"
cargo fmt --all --check
cargo check --workspace --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
cargo build --workspace --locked --offline
cargo test --workspace --locked --offline -- --include-ignored
```

Results: formatting/check/build pass; clippy passes with warnings denied; four
meaningful tests pass, including the otherwise ignored native engine test. No
ignored test is counted as a pass. CLI/GUI test executables and doctests contain
zero additional tests, so these are not an inflated group total.

| Test | Actual assertions |
| --- | --- |
| Core render bounds | Zero/overflow/excessive dimensions reject; valid maximum accepts; invalid rotations reject. |
| Image orientation | Exact JPEG portrait dimensions and green top-left after EXIF; explicit reverse rotation restores landscape/red corner; PNG/WebP work; invalid image page rejects; original hash matches. |
| Invalid/oversized input | Malformed image and sparse file above 256 MiB fail before decoding. |
| Native PDF | Both pages have correct counts, aspect, bounded dimensions and distinct red/blue markers; invalid page rejects; concurrent callers reuse the same worker; rotated output fits; original hash matches; worker joins. |

Additional exercised paths: a corrupt engine archive is rejected before creating an
installation; generating fixtures into an existing directory fails without changing
the original PDF. Early test failures exposed unwanted image upscaling and repeated
PDFium initialization; both implementations were repaired and final assertions pass.

Headless `render-smoke` produces five previews: PDF page 1 at 600×776, PDF page 2
at 600×464, oriented JPEG at 360×640, PNG and WebP at 600×338. Exact hashes and
source fixtures are recorded in [structured results](desktop/M0_RESULTS.json).
One small-fixture run measured 0.54 s elapsed and 16,264 KiB peak RSS using
`/usr/bin/time -v` on an Intel i7-10610U / 8 logical CPUs / Linux x86-64. This
measurement describes that headless run; large-library and GUI budgets remain open.

## Native runtime and visual inspection

The restricted launch returned Wayland `NoCompositor`. Desktop-session execution
approval allowed actual native launches. Every run used fresh temporary XDG data,
config, cache and state; captures use the app's own window screenshot API after
verifying exact authored fixtures. No desktop-wide capture or other app content.

Final command (through the desktop-session execution context):

```sh
python3 desktop/tools/smoke_native.py \
  --fixtures /tmp/stack-desktop-dev/fixtures-v1 \
  --output /tmp/stack-desktop-dev/native-final --repeat 12
```

All **16** final runs exit 0 and save all three captures: **48** captures total.
The four layout cases are light/dark at 1080×780, narrow light at 680×680 and
doubled content text in dark at 680×680. Twelve additional light cases repeat
startup/render/capture/shutdown. Captured dimensions match requested dimensions.
Binary/engine/capture digests and per-case outcomes are in the structured results.

The agent visually inspected final light PDF page 1, dark PDF page 2, narrow
oriented JPEG and doubled-text PDF page 2. Markers, page identity and JPEG corner
orientation match expectations; labels/controls wrap without clipping. Enlarged
text leaves a small preview area in the narrow layout, an M2 usability follow-up.
The content-scale fixture does not change native sidebar/window typography.
The toolkit's generic scale setting changed responsive breakpoints without scaling
text in this pinned runner; that misleading test option was removed.

These are actual runtime captures and agent judgment. Automated transitions invoke
the render path directly; physical mouse/keyboard actions, screen-reader behavior,
system text preferences and subjective usability have not been tested or accepted.

## Shutdown stability watch

Before explicit worker teardown, one native light smoke run wrote all three
captures then exited with SIGSEGV. This is retained as a real failure. Diagnostic
debugger/fault-trace runs subsequently exited normally and did not yield a fault
backtrace. A debugger was downloaded/extracted only under `/tmp`; no system package
was installed and no existing user process was attached.

The implementation now drains/joins the PDF worker before smoke window/runtime
teardown and at ordinary process cleanup. All 16 final runs of that binary exit
normally. **The exact native cause and causal link to the lifecycle change are
unconfirmed.** This is a mitigation plus bounded successful evidence, not proof of
universal shutdown stability. If it recurs, preserve the own-process synthetic
trace and investigate before expanding the runtime claim. Do not silently erase
the original failure or claim a conclusively diagnosed repair.

## Preservation and remaining scope

Binaries, renderer package, build cache, generated fixtures, raw logs and screenshots
stay in ignored `desktop/target`, `desktop/runtime` or `/tmp/stack-desktop-dev/`.
Only authored source, lockfile, dependency records and nonprivate synthetic result
projections belong in Git. Existing Android source and feature-matrix edits are
outside this increment. No unrelated project was modified or borrowed.

M1 next: transactional storage/migrations, immutable originals/recovery, strict
format-1/2/3 interchange and journaled local decisions. Desktop OCR generation,
saved-transform parity, phone sync and LAN pairing remain separate gates. Retain
the plan's single user-dependent checklist for the later integrated milestone;
no new phone/unlock/permission or full Android H1–H8 session is needed for M0.


## Remaining-work foundation — 2026-10-18

[Current evidence](desktop/REMAINING_EVIDENCE.md) and
[machine-readable outcomes](desktop/REMAINING_RESULTS.json) record R1 engineering
and R2 offline English text verification: 64 full-workspace tests, fresh stress
122ms first draw/124739us max gap/430832KiB peak RSS (all ten targets), six approved
private portal requests, 13 M1 SIGKILL cases and host Java/Rust format1/2/3 chains.
A subsequent sync-only tombstone guard passed all23 core checks and final workspace
clippy/build; exact source deltas remain recorded. No native user or primary-phone
acceptance is claimed. Experimental R3/R4 real-file and shared host Java causal
evidence is explicitly separate from full merge/adoption, Android or LAN gates.
