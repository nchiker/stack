# M2 handoff — persistent COSMIC workflow

Prepared 2026-10-17 after implementation commit `91fdffb`. This handoff prepares
phase 2; M2 implementation has not started. The user has seen the running synthetic
demo. That observation is not acceptance of persistent library features.

## Start here

Work in `/home/charlie.day/Projects/Stack`; Git root is
`/home/charlie.day/Projects`. Read applicable `AGENTS.md` files and inspect current
Git state before editing. None was found in the workspace or ancestors during
handoff preparation. Preserve all preexisting Android/unrelated changes.

Read, in this order:

1. [M2 rulebook](M2_RULEBOOK.md) — task order, implementation rules and acceptance.
2. [Execution ledger](EXECUTION_LEDGER.md) — actual stage/status and preservation.
3. [M1 evidence](M1_EVIDENCE.md) and [results](M1_RESULTS.json).
4. [Storage contract](../../desktop/STORAGE.md) and [build/run guide](../../desktop/README.md).
5. [Project plan](../STACK_DESKTOP_PLAN.md), [archive format](../STACK_ARCHIVE_FORMAT.md)
   and [draft sync contract](INTEROP_SYNC_CONTRACT_V0_1.md).

The [broader original work order](../STACK_DESKTOP_NEXT_SESSION_PROMPT.md) supplies
historical requirements. Its old begin-from-scratch/Android 0.8.7 wording does not
describe current source. Resume M2 instead of rebuilding M0/M1.

## What exists

| Component | Actual state at `91fdffb` |
| --- | --- |
| `stack-desktop` | Native libcosmic window, static sidebar, two synthetic sample selections, preview/navigation/zoom, temporary rotation and render retry. No repository-backed list, import or editors. |
| `stack-core` | Owned SQLite schema 2, serialized writer lock, immutable originals, migrations/recovery, persisted FTS, document tokens, journaled edits and tombstones. |
| `stack-archive` | Strict formats 1/2/3 reader and format-3 writer; complete graph, staging, additive collision mapping and source evidence. |
| `stack-render` | JPEG/PNG/WebP and PDF inspection/preview, EXIF orientation and explicit quarter turns. Saved crop/enhancement/flattening and PDF generation are unfinished. |
| `stack-cli` | Explicit-root import/search/edit/catalog/archive operations and synthetic harnesses. |
| Sync | Local events/source aliases exist. No causal merge, exchange bundles, Android sync, pairing or LAN transport. |

M1 evidence: 19 tests including the native PDF test; three Java/Rust version chains,
21 differential validation cases, 233 host currency codes, 13 actual process-stop
cases, four native layouts/12 captures and a 1,000-document/10,000-page fixture.
These are recorded observations, not a fresh test run during this documentation task.

## First implementation task: M2.1

Add an explicit desktop `--library ABSOLUTE_ROOT` option and a bounded background
repository worker. These are **proposed M2 interfaces**, not current executable
options. Keep development/test launches on disposable roots and preserve the
existing explicit `--demo` route.

Normal library mode should use the standard XDG root from `default_root()` when no
explicit root is supplied. All implementation verification overrides that root and
isolates XDG; never launch tests against a personal default library.

Open the M1 repository off the UI thread, populate All documents and ordered visible
stacks from saved data, select a saved document, and display its saved page sequence.
Use the stable page ID and its source filename/source-page index. The PDF renderer's
physical original page count must not replace the document's saved page count.
Handle loading, empty library, busy library and recoverable errors in the window.

First acceptance: create a synthetic library through the existing CLI; open it in
the new UI; browse a PDF/image and its later page; close/reopen the same root; verify
IDs/order/original hashes survive. Trace selection through the worker to real rows.
Do not count the M0 demo as that test. Until M2.2 supports saved appearance, a
transformed page needs an explicit unavailable state instead of an incorrect preview.
Continue through the rulebook's remaining M2 tasks after this slice passes.

## Code entry points

- UI: [`stack-desktop/src/main.rs`](../../desktop/crates/stack-desktop/src/main.rs).
  Replace hard-coded `Sample` navigation with repository-backed state in library mode.
- Storage: [`library.rs`](../../desktop/crates/stack-core/src/library.rs),
  [`search.rs`](../../desktop/crates/stack-core/src/search.rs),
  [`editing.rs`](../../desktop/crates/stack-core/src/editing.rs),
  [`interchange.rs`](../../desktop/crates/stack-core/src/interchange.rs).
  Reuse `Library`, `DocumentToken`, `DocumentEdit`, `StackEdit`, `Control`, staged
  restore/review/commit and frozen snapshots. Add bounded read projections as needed.
- Rendering: [`stack-render/src/lib.rs`](../../desktop/crates/stack-render/src/lib.rs).
  Keep the existing single PDFium worker and explicit shutdown/join.
- Android appearance reference: `PageRenderer.java`, `PageGeometry.java`,
  `PageEnhancement.java`, `PageFlattening.java`, `PageState.java`; PDF copy/export
  decision is `LibraryRepository.exportPdf`. These live under
  `app/src/main/java/org/stackdocs/app/` and are Stack's own source.
- Fixtures and regressions: `stack-archive/src/synthetic.rs`, archive/core tests,
  `desktop/tools/test_m1_*.py`, `measure_m1_counts.py`, `smoke_native.py`.

## Build and prepare a disposable library today

Toolchain file requests Rust 1.99.0. The implementation machine's installed
`stable` reports exactly 1.99.0; verify before using the override. COSMIC is pinned
to `dbc62f2d899cdbfbe43d170689ddc0290db663f4`; keep Cargo.lock. PDFium is pinned to
`chromium/8100`; its shared-library SHA-256 is
`b8784cbd652ad2993332b7695e2c80f3a797f877fc47d9be97b66bca9639539e`.

```sh
cd /home/charlie.day/Projects/Stack/desktop
rustc +stable --version
# Only if stable is the pinned 1.99.0 compiler:
export RUSTUP_TOOLCHAIN=stable
# Optional reuse of this machine's verified cache, if still present:
export CARGO_HOME=/tmp/stack-desktop-dev/cargo-home
export CARGO_BUILD_JOBS=4
export STACK_PDFIUM_LIBRARY="$PWD/runtime/pdfium/lib/libpdfium.so"
cargo build --workspace --locked --offline

umask 077
STACK_M2_WORK=$(mktemp -d /tmp/stack-m2.XXXXXXXX)
export XDG_DATA_HOME="$STACK_M2_WORK/data"
export XDG_CONFIG_HOME="$STACK_M2_WORK/config"
export XDG_CACHE_HOME="$STACK_M2_WORK/cache"
export XDG_STATE_HOME="$STACK_M2_WORK/state"
mkdir -p "$XDG_DATA_HOME" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME" "$XDG_STATE_HOME"
./target/debug/stack-cli fixtures "$STACK_M2_WORK/render-fixtures"
./target/debug/stack-cli m1-fixtures "$STACK_M2_WORK/render-fixtures" "$STACK_M2_WORK/archive-fixtures"
./target/debug/stack-cli archive "$STACK_M2_WORK/library" restore "$STACK_M2_WORK/archive-fixtures/format-3.stackbackup"
./target/debug/stack-cli library "$STACK_M2_WORK/library" search laterpagesentinel
# Current executable: preview demo, not the saved library above.
./target/debug/stack-desktop --demo "$STACK_M2_WORK/render-fixtures"
```

After implementing the new option, launch `stack-desktop --library
"$STACK_M2_WORK/library"` in that isolated environment. Never label this command
available before it is implemented. Existing demo smoke remains a renderer
regression; add a separate persistent-workflow GUI harness for M2.

Temporary caches/fixtures may disappear. At preparation time the isolated Cargo
cache, M0 samples, M1 samples and AOSP JSON sources were present under
`/tmp/stack-desktop-dev/` and `/tmp/stack-desktop-m1/`. Their regeneration commands
and hash checks are in the build guide. Missing caches are not source dependencies;
use the pinned setup/fetch process when necessary. Native Wayland launches in this
execution environment need desktop-session tool escalation; request that specific
capability if restricted execution returns `NoCompositor`.

## Preservation and open qualifications

Android source is 0.8.9-dev/code22/schema8/archive3. Preexisting modifications at
handoff: root README, app/build.gradle, GoogleMigrationChecks, DocumentActivity,
Icons, Ui, feature matrix, primary-phone guide; untracked StackStyle, icon evidence
and test_stack_icons.py. `../cosmic-takeout/` is unrelated: do not modify or borrow it.
Recheck the actual diff; this list is a snapshot, never a reason to reset files.

The user supplied this private phone-library reference:

```text
/home/charlie.day/Downloads/Stack-0.8.7/Google-Stack-Migration-final.stackbackup
```

Its filename identifies a portable Stack backup archive. It has not been opened,
staged, imported or verified during this handoff. Its freshness and correspondence
to the current phone library are unverified; the directory/version name is not a
current-state guarantee. Keep it private and outside all tests/screenshots/Git.
M2 uses authored synthetic archives. A later user-chosen real transfer can use the
review/commit workflow once ready. Any optional read-only format-metadata inspection
must follow the rulebook's privacy boundary. For SQLite schema design, use the
production `LibraryDatabase.java` and migrations. No raw database copy was opened.

Retain the undiagnosed M0 native shutdown watch, Rust's safe lone-surrogate rejection,
host-pinned currency/Unicode limitations, and the pending Android runtime gate.
Maximum-count debug observations were about 28s/303 MiB for restore and 2.22s/25 MiB
for search/reopen; keep heavy work off the UI thread. The M0 large-text case left a
small preview area and did not scale native sidebar typography; M2 must revisit it.

## Copy into the next implementation session

```text
Begin M2 for Stack's native COSMIC desktop app. Work in
/home/charlie.day/Projects/Stack. Read docs/desktop/M2_HANDOFF.md and
docs/desktop/M2_RULEBOOK.md, then follow their linked evidence and preservation rules.
Recheck AGENTS.md, Git state, toolchain and dependency pins. M0/M1 are delivered at
91fdffb with recorded qualifications; do not restart them or Android H1–H8.

Start M2.1: connect the existing durable repository to a serialized background worker
and persistent COSMIC browsing using an explicit disposable library root. Then carry
the remaining M2 tasks through their UI, rendering/export and recovery acceptance.
Use real repository operations; journal every saved edit. Preserve originals,
historical evidence and exact typed values. Keep tests synthetic. A user-mentioned
phone reference is the private Google-Stack-Migration-final.stackbackup archive;
keep it unopened for development tests and use synthetic fixtures.

Complete independent authorized work without routine confirmation checkpoints.
Record real tests/runtime evidence and limits; do not substitute demo or CLI checks
for the persistent GUI workflow. Keep M3–M6 claims separate. Commit coherent Stack-only
increments, update the ledger and M2 evidence/run guide, and report the actual next
unfinished gate. Keep all unrelated edits and the user's currently open windows intact.
```
