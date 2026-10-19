# Stack for COSMIC — desktop foundation and durable core

The Rust/libcosmic app includes a persistent native library, document previews,
search, editors and portable archive import/export. Read the
[project plan](../docs/STACK_DESKTOP_PLAN.md), [storage contract](STORAGE.md) and
[M1 evidence](../docs/desktop/M1_EVIDENCE.md). For phase 2, use the
[M2 handoff](../docs/desktop/M2_HANDOFF.md) and
[M2 rulebook](../docs/desktop/M2_RULEBOOK.md). For a first phone transfer and current
search limitations, use the [phone transfer guide](../docs/STACK_DESKTOP_PHONE_TRANSFER.md).
Merge and sync follow in M3–M6; backup import is additive, not synchronization.
Remaining implementation is organized in the
[playbook](../docs/STACK_DESKTOP_REMAINING_PLAYBOOK.md), with a
[kickoff prompt](../docs/STACK_DESKTOP_REMAINING_START_PROMPT.md) for autonomous
headless execution and one final user review.

## Build

The first supported target is Linux x86-64 on COSMIC/Wayland. Use Rust 1.99.0,
a C/C++ build toolchain, `pkg-config`, and Wayland/xkbcommon runtime/development
libraries. Toolkit revision and all crate dependencies are pinned in `Cargo.toml`
and `Cargo.lock`. `rust-toolchain.toml` requests the exact compiler and clippy/rustfmt.
The optional PDF engine is installed separately below.

```sh
cd desktop
cargo fetch --locked
cargo fmt --all --check
cargo check --workspace --locked --offline
cargo build --workspace --locked --offline
```

If your already installed `stable` toolchain reports exactly rustc 1.99.0, exporting
`RUSTUP_TOOLCHAIN=stable` uses that existing compiler without downloading a duplicate
named toolchain. The implementation session uses an isolated Cargo cache through
`CARGO_HOME=/tmp/stack-desktop-dev/cargo-home`; that path is not a project dependency.
The tested dependency configuration needs no fontconfig/freetype development
headers and does not use system SQLite or Poppler. This is verified by compilation,
not a promise about arbitrary toolkit feature changes or other Linux environments.

## Renderer setup

```sh
# From the repository's Stack/ directory; downloads only at explicit setup time.
python3 desktop/tools/setup_pdfium.py
```

This installs the checksum-pinned Linux x86-64 PDFium build `chromium/8100` under
ignored `desktop/runtime/pdfium/`, including its package license and component
notices. It refuses to overwrite an existing destination. To install offline, pass
`--archive /path/to/pdfium-linux-x64.tgz`; the same checksum is required. Other
architectures need their own reviewed engine pin and tests.

`pdfium-render` 0.9.4 uses its explicit `pdfium_7881` binding surface with this engine;
the actual supported pair must pass native tests. The app never downloads a renderer
and never searches the working directory for shared libraries. Set
`STACK_PDFIUM_LIBRARY` to an absolute engine path when running a binary directly.
A missing engine produces a preview error and Retry; image decoding remains usable.

## Run the synthetic demo

```sh
bash desktop/tools/run-demo.sh
```

The script creates a new private directory under `/tmp`, isolates XDG data/config/
cache/state, generates authored samples and opens the native app. It leaves the
printed disposable directory available for inspection. No phone or personal library
is opened. Select Sample report or Orientation sample; use Previous/Next, Rotate
and mouse-wheel zoom/pan in the viewer. These previews do not save document edits.

The two-page PDF contains portrait and landscape pages. The JPEG contains four
colored quadrants and EXIF orientation 6; its expected upright top-left quadrant
is green. PNG/WebP companions use the same pixels without orientation metadata.
Fixture content is independently authored and dedicated to CC0-1.0; generator code
is Apache-2.0. The demo verifies exact fixture bytes before opening them.

The executable also accepts `--theme light|dark`, `--size 680x680` and `--text-scale 2`.
The content text-scale option doubles document-view text and its controls, with
wrapping rows. Native sidebar/window typography stays under COSMIC control. This
fixture does not prove system text settings or screen-reader accessibility.
Without `--demo`, the executable opens the standard XDG library through a background
repository worker. `--library ABSOLUTE_ROOT` selects a different saved library.
Library and demo options are mutually exclusive. Development and verification must
use explicit disposable roots and isolated XDG directories.

## Verify

```sh
cd desktop
export STACK_PDFIUM_LIBRARY="$PWD/runtime/pdfium/lib/libpdfium.so"
cargo test --workspace --locked --offline -- --include-ignored
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
```

The ignored native PDF test is deliberately included above and requires the engine.
Default tests can run without it; do not count an ignored native test as a pass.
The native test checks both pages, aspect ratio, marker colors, rotation, concurrent
callers sharing one worker, explicit worker shutdown/join, invalid page rejection
and source hash preservation.
Image tests check EXIF orientation/explicit rotation, PNG/WebP, invalid page numbers,
malformed input and oversized files. Core tests check render/rotation bounds.

For a repeatable headless rendering report and native-window captures, use fresh
directories and isolated XDG roots (or use the demo script for GUI isolation):

```sh
cargo run --locked --offline -p stack-cli -- fixtures /tmp/stack-m0-fixtures
cargo run --locked --offline -p stack-cli -- render-smoke /tmp/stack-m0-fixtures /tmp/stack-m0-rendered
bash tools/run-demo.sh --smoke-output /tmp/stack-m0-window --theme light
```

`render-smoke` writes five PNG previews and `report.json`. The GUI smoke renders
PDF page 1, PDF page 2 and the oriented JPEG, captures only its own native window,
then exits successfully only after saving all three. It verifies synthetic input
before enabling capture. Its automated page transitions exercise the application
render path; they are not physical clicks or keyboard accessibility tests. Window
managers may override the requested window size; record captured dimensions.

For the complete four-layout native sequence, with optional extra shutdown cycles:

```sh
python3 tools/smoke_native.py --fixtures /tmp/stack-m0-fixtures --output /tmp/stack-m0-native --repeat 12
```

This isolates XDG roots automatically and records exit statuses, captured dimensions,
binary/engine digests and capture hashes. It needs access to the native Wayland
session. This execution environment requires desktop-session tool approval for that
access; the restricted execution context cannot connect to the compositor.

Preview targets are capped at 4,096 pixels per edge / 16 Mi pixels. Sources are
limited to 256 MiB, PDFs to 1,000 pages, and image decode to 24 megapixels with a
128 MiB decoder allocation limit. PDF jobs use one persistent worker and a queue
of one; the GUI permits one outstanding render. These bounds do not establish
a total native-parser memory cap or hostile-PDF sandbox. The archive backend adds
strict metadata/envelope and source validation; see its documented compatibility limits.
Library previews apply saved perspective crop, quarter-turn, conservative flattening
and original/medium/bright/black-white enhancement after EXIF orientation. The CPU
port has authored geometry/host algorithm evidence; Android bitmap parity is unverified.
The PDF worker drains and joins before synthetic smoke exit and at normal program
cleanup. The [evidence](../docs/STACK_DESKTOP_EVIDENCE.md) records one earlier native
shutdown fault and the final repeated-exit results; its exact native cause is
unconfirmed, so retain this stability watch during subsequent development.

## Dependency and license records

See [M0 dependency decisions](../docs/desktop/M0_DEPENDENCIES.md) and the generated
[current 494-package Linux Cargo inventory](../docs/desktop/M1_DEPENDENCIES.json). Engine notices stay
beside the installed engine. A future distributable must include the applicable
Rust and native notices and source obligations; this foundation is a source build,
not a public binary release. No upstream dependency source was modified.

To regenerate the target-filtered inventory:

```sh
cargo metadata --locked --offline --format-version 1 --filter-platform x86_64-unknown-linux-gnu > /tmp/stack-m1-metadata.json
python3 tools/dependency_inventory.py /tmp/stack-m1-metadata.json ../docs/desktop/M1_DEPENDENCIES.json
```

Actual build/runtime evidence and remaining scope live in
[desktop evidence](../docs/STACK_DESKTOP_EVIDENCE.md) and the
[execution ledger](../docs/desktop/EXECUTION_LEDGER.md).


## Durable library and portable archives (M1 CLI)

Every command requires an explicit library directory. These examples use fresh
synthetic paths under `/tmp`; choose new paths if they already exist. The CLI does
not implicitly open personal data. Install the pinned engine above for PDF imports
and archive validation containing PDFs. Image imports work without that engine.

```sh
cd desktop
export STACK_PDFIUM_LIBRARY="$PWD/runtime/pdfium/lib/libpdfium.so"
./target/debug/stack-cli fixtures /tmp/stack-m1-originals
./target/debug/stack-cli m1-fixtures /tmp/stack-m1-originals /tmp/stack-m1-fixtures
./target/debug/stack-cli library /tmp/stack-m1-library status
./target/debug/stack-cli archive /tmp/stack-m1-library review /tmp/stack-m1-fixtures/format-3.stackbackup
./target/debug/stack-cli archive /tmp/stack-m1-library restore /tmp/stack-m1-fixtures/format-3.stackbackup
./target/debug/stack-cli library /tmp/stack-m1-library search laterpagesentinel
./target/debug/stack-cli library /tmp/stack-m1-library rename 00000000-0000-0000-0000-000000000001 'Saved synthetic title'
./target/debug/stack-cli library /tmp/stack-m1-library notes 00000000-0000-0000-0000-000000000001 'A saved note'
./target/debug/stack-cli archive /tmp/stack-m1-library export /tmp/stack-m1-export.stackbackup
```

`validate` and `review` stage/check an archive and report mappings without adding
its documents. `restore` explicitly commits additive copies; selecting the same
archive again adds more copies. Existing originals and export destinations never
get overwritten. `manifest` and `journal` explicitly print saved content/history;
keep their output private when using a real library. Status/review report counts
and IDs. `reindex` repairs derived FTS without changing decisions.

Other commands: `import FILE TITLE`, `file ID [STACK...]` (empty means unfiled),
`detail ID JSON_FILE`, `remove-detail ID FIELD`, `page-order ID PAGE...`, `delete ID`,
`stack-create NAME SIGNED_ARGB show|hide`, `stack-rename ID NAME`,
`stack-visibility ID show|hide`, `stack-delete ID`, `stack-order ID...`.
Detail input is the exact portable detail row described in the archive spec; source
evidence on existing fields is preserved. Invalid or stale input fails atomically.
Deletion retains originals/tombstones locally and excludes the document from backups.

Synthetic recovery and maximum-count measurements:

```sh
python3 tools/test_m1_process_stops.py target/debug/stack-cli /tmp/stack-m1-fixtures /tmp/stack-m1-stops
python3 tools/measure_m1_counts.py target/debug/stack-cli /tmp/stack-m1-fixtures /tmp/stack-m1-counts
```

The process-stop tool uses actual SIGKILL at 13 boundaries; its hook is debug-only
and requires a marked disposable `/tmp` root. The count tool constructs 1,000 image
documents with 10,000 saved page references and verifies later-page search. It does
not fill storage to the archive's 8 GiB limit.

For host Java compatibility, fetch the six Apache-2.0 AOSP sources identified by
`tools/android-json-sources.json` into a chosen directory (Gitiles `?format=TEXT`
responses are base64-encoded), keeping their license headers. The builder verifies
every source digest, extracts the current production Java members and records all
source hashes. No Android app source is modified.

```sh
python3 tools/java_archive_host.py /tmp/aosp-json /tmp/stack-m1-java-host
python3 tools/test_m1_java_interop.py target/debug/stack-cli /tmp/stack-m1-java-host/classes /tmp/stack-m1-fixtures /tmp/stack-m1-java-golden
```

These are host validator/writer/digest checks. Android device restore remains an
explicit separate gate; this backend and the current window do not offer sync.


## Persistent native browser (M2.1)

After the CLI's synthetic restore example, launch the same disposable library:

```sh
./target/debug/stack-desktop --library /tmp/stack-m1-library
python3 tools/test_m2_browser.py --output /tmp/stack-m2-browser-NEW
```

Use the isolated XDG environment in the M2 handoff. Never use the personal default
root for implementation verification. The browser has saved All documents and
visible stacks in catalog order, document selection, saved page navigation and
integrated preview/zoom. Page totals describe the saved sequence, including subsets,
rather than the original PDF's physical page count. Refresh retries a busy/unavailable
library. Empty and invalid-root states retain useful window controls.

One repository thread owns the writer lock; a separate preview thread has one active
and one replaceable pending job. Both join at teardown. Selection/filter generations
reject old results. M2.1–M2.2 expose browsing and saved appearance; search/filter/sort and the stack manager are available in M2.3. Document editors
and import/export controls follow in later slices.

The browser harness creates and CLI-restores authored synthetic fixtures, runs native
saved PDF/later-page/image/transformed-scan selection, reopens the same root,
and compares rows, IDs, ordering, journal and original hashes. It also exercises
empty/busy/foreign-root states. Captures require exact authored fixture rows and bytes;
automation injects application messages and does not prove pointer/keyboard access.
It needs the documented Wayland-session access. Use a fresh `/tmp` output directory.


For the authored saved-appearance/production-Java host comparison:

```sh
python3 tools/test_m2_appearance_host.py --output /tmp/stack-m2-appearance-NEW
```

This uses local `javac`/`java` and unchanged production Java sources, with a limited
pixel adapter. It compares enhancement pixels and flatten-model decisions; it does
not run Android Canvas, decoding, device lifecycle or mesh bitmap parity checks.


Search uses archived current text, notes and saved details; a page hit opens the
matching saved page. Stack filtering and Newest/Name sort compose. Sort is saved in
`$XDG_CONFIG_HOME/org.stackdocs.desktop/view.json` (or the standard config fallback).
Manage stacks includes hidden rows and confirmed stack deletion, which retains
all documents. Invalid/stale catalog saves keep the draft; Refresh reloads the
saved catalog before retrying. All library mutations use the repository worker.

```sh
python3 tools/test_m2_catalog.py --output /tmp/stack-m2-catalog-NEW
```

This synthetic native scenario invokes the same message handlers as controls,
verifies journal events and saved rows, and reopens the root. It does not simulate
physical pointer/keyboard input. Use disposable roots for all development checks.


M2.4 adds separate saved title/notes/filing/detail controls, retained source history,
paged details and saved page controls. Failed/stale drafts remain in memory for
review/retry; unsaved drafts do not survive process exit. Document/page removal
retains originals. Visible multiline notes and saved restart now pass native
inspection; keyboard/focus/accessibility acceptance remains pending.

```sh
python3 tools/test_m2_editors.py --output /tmp/stack-m2-editors-NEW
```

Updated native harnesses build `focus_synthetic.rs` against the already-built pinned
COSMIC toolkit, generate an unguessable test title and activate only that own window.
They wait for an actual content draw before capture, failing if it stalls. They never
close/modify existing user windows or log their titles. Avoid unfiltered
`WAYLAND_DEBUG`: toolkit protocol events can include foreign-window metadata. Normal product launches do
not use the helper/draw tracker. No new Rust dependency version was introduced;
desktop now uses the existing workspace UUID crate directly for stable detail IDs.


The transfer toolbar opens native portal pickers for document batches, Stack archive
restore review and full portable archive output. Batches accept up to 100 files and
report each outcome. Cancel preserves completed imports; Retry failed items skips
already saved files. Archive review is additive. Export refuses an existing output;
choose a new filename. Heavy work runs separately from the repository owner.
M2.5 headless controller and recovery checks and the injected native workflow pass;
actual native chooser selection/save acceptance remains pending.


Export PDF on the selected document opens a separate save picker. A complete,
unchanged original PDF copies exactly; saved subset/order/appearance and image/scan
documents render one page at a time into lossless image-based PDF. Rendered output
has no selectable text layer. A native page-count check runs before publication.
Choose a new output filename; existing files are preserved. Archive export remains
the route for portable notes, filing, decisions, source evidence and originals.
PDF native-engine/controller checks and the injected native workflow pass;
actual native save-dialog acceptance is pending.


The integrated synthetic harness is now available:

```sh
python3 tools/test_m2_workflow.py --output /tmp/stack-m2-workflow-NEW \
  --java-classes /tmp/stack-desktop-m1/java-host-final/classes
```

It uses injected application messages/picker results and native own-window captures;
portal and physical keyboard/pointer checks remain separate. The workflow and
same-root reopen now pass; intermittent fresh-frame stalls remain undiagnosed
even with the desktop awake. Do not count a failed/cached frame as a pass.
The user currently requests terminal/headless testing: do not launch this GUI
harness during that work.
It never unlocks or changes monitor power, or closes existing windows. The optional
expected graph argument is restricted to its validated synthetic same-root reopen.

Headless scenario and maximum-count worker checks can run independently:

```sh
cargo test -p stack-desktop application_pdf_import --locked --offline -- --include-ignored
cargo test -p stack-desktop application_maximum_counts --locked --offline -- --include-ignored --nocapture
```

The PDF test needs the pinned engine and omits capture requests explicitly. To retain
its authored temporary artifacts, set `STACK_M2_PRESERVE_WORKFLOW=1`; it prints the
private `/tmp` directory. The count test generates 1,000 documents/10,000 pages and
emits timings, ticker gaps and process peak RSS. Neither establishes displayed-frame
responsiveness. See M2 evidence for actual numeric observations and outstanding gates.


For fully terminal-only workspace verification (including the headless application
workflow), remove display variables and include the otherwise ignored tests:

```sh
env -u DISPLAY -u WAYLAND_DISPLAY cargo test --workspace --locked --offline -- --include-ignored
```

Set `STACK_PDFIUM_LIBRARY` to the pinned engine as above. These tests create no
windows; they exercise real handlers, workers, recovery and PDF inspection using
authored synthetic libraries. Current results are recorded in
`/tmp/stack-m2-closeout-verified/report.json`. All engineering automation now runs
headlessly, including actual portal protocol requests on a private test bus. The
chooser UI, physical input and live compositor remain the final user review. The
older `test_m2_portals.py` GUI harness is not counted as passing.

The default app build includes pinned wgpu with tiny-skia fallback. A software-only
build uses `--no-default-features`. Both renderers passed a native workflow in the
same binary; neither observation establishes the intermittent frame-stall cause.
Selected documents use a dedicated preview view and narrow navigation collapses.
`--text-scale` now scales the full shell through the Iced scale callback, including
native navigation and inputs. Ctrl+F focuses search; Escape leaves an editor or
cancels its pending confirmation first. Search results build 50 rows per page with
Previous/Next controls, keeping all supported results reachable.


To repeat the complete headless engineering closeout, use the compiler/cache
settings above and a fresh output directory:

```sh
python3 tools/run_m2_checks.py --output /tmp/stack-m2-checks-NEW
```

This runs all locked offline checks/tests (including ignored engine/benchmark
cases), checks a software-only build, measures maximum counts in a fresh process,
and tests the native FileChooser protocol with a private synthetic D-Bus service.
It removes display variables and opens no windows. The private socket must be
allowed by the execution environment. Performance targets are debug observations
for this host; the report distinguishes them from live compositor/release behavior
and from the combined suite's process high-water mark.

The user performs the final visual/interface review. Prepare only its synthetic
libraries and launchers, without opening a window:

```sh
python3 tools/prepare_m2_review.py --output /tmp/stack-m2-review-NEW
```

It creates a five-document workflow library, a sixty-document paging library,
authored import/archive inputs and an empty export folder under that root. Launch
`OUTPUT/run` or `OUTPUT/run-many` yourself when ready. Follow the
[review guide](../docs/desktop/M2_REVIEW.md); the project plan holds the single
closeout checkbox. Full M2 acceptance awaits that review. Known platform limits
remain physical AT-SPI/screen-reader and chooser-parent/focus observation. Scoped native patches now publish the actual widget hierarchy and export real native chooser parents; see `platform/README.md`.


The current native layout follows the user's HTML mockup. Stack destinations stay
in a flat left sidebar; header Back appears only when it has a destination. Search
and Add documents are library actions and are hidden in the document workspace.
Window/header titles include the current library or document context. Native
COSMIC colors and controls remain; no mockup palette is forced.

Real thumbnails use the first saved page (including saved order/appearance), with
name, actual added date and a stack below. Only visible rows plus a margin are
laid out/decoded; caches stay bounded to the current 50-result page. A file icon
is the loading/error/unsupported fallback. Neither originals nor saved metadata
are changed to generate a thumbnail. Library sort/layout preferences persist.

Wide workspaces keep a fit-to-space, aspect-preserving zoomable page beside a
280-pixel information panel. Narrow information/editing layouts retain the page
above the scrollable controls. Information, Details and Pages share that panel;
page tools never replace the preview. Membership chips expand into stack choices.
Saves remain explicit; Done editing retains unsaved drafts within this process.

Readable PDF review fixtures and their isolated library can be prepared with:

```sh
python3 tools/prepare_ui_visual_review.py --output /tmp/stack-ui-review-NEW
# Set the prepared XDG paths and PDFium as for other review launchers, then:
./target/debug/stack-desktop --visual-review /tmp/stack-ui-review-NEW \
  --smoke-output /tmp/stack-ui-review-captures-NEW --size 1080x860
```

This restricted native capture route verifies the six authored PDF hashes and
saved graph before displaying it. See the [interface review](../docs/desktop/M2_INTERFACE_REVIEW.md)
for comparison evidence, screenshots, remaining differences and platform limits.


## Offline document text and prepared installation

New desktop imports enter a durable page queue. The UI shows queued/reading/ready/no-text/failed/cancelled states and retry/cancel actions. Valid imported text is preserved. Untransformed PDFs use embedded text where usable; scanned/image or transformed pages use the visible saved raster with pinned English Tesseract. Removed pages/cropped-out text never enter the current search index. Background publication checks the original token and preserves typed drafts. Recognition is offline and downloads nothing at launch.

Prepare the existing Ubuntu24.04 x86-64 runtime once:

```sh
python3 desktop/tools/setup_ocr.py
export STACK_PDFIUM_LIBRARY="$PWD/desktop/runtime/pdfium/lib/libpdfium.so"
export STACK_TESSERACT_LIBRARY="$PWD/desktop/runtime/ocr/lib/libtesseract.so.5"
export STACK_TESSDATA_DIR="$PWD/desktop/runtime/ocr/tessdata"
export OMP_THREAD_LIMIT=1
```

`stack-cli library EXPLICIT_ROOT read-text` drains the real queue headlessly;
`text-status ID`, `text-retry ID` and `text-cancel ID` expose durable state. All
development roots must be authored disposable libraries.

Prepare a normal persistent-XDG package without installing/launching it:

```sh
RUSTUP_TOOLCHAIN=stable CARGO_HOME=/tmp/stack-desktop-dev/cargo-home \
  python3 desktop/tools/package_desktop.py --output /tmp/Stack-package-UNIQUE
# Deliberate user installation, after reviewing current qualifications:
python3 /tmp/Stack-package-UNIQUE/install.py
~/.local/bin/stack
# Remove application files; never remove the library:
python3 ~/.local/lib/stack-desktop/install.py --uninstall
```

The wrapper sets packaged PDF/OCR paths and keeps the normal persistent XDG data
root; it never selects sample data. Updates retain one `.previous` installation.
The current prepared debug artifact passed an isolated install/uninstall test,
but dependency notices and later sync/release gates remain open. Read
[current results](../docs/desktop/REMAINING_RESULTS.json) before treating an artifact
as a release. Source and runtime ABI limitations are explicit.
