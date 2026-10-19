# M1 durable library and archive backend — 2026-10-17

The durable backend and CLI are delivered, with the compatibility qualifications
below. The COSMIC window remains the M0 demo; M2 is the next implementation stage.
Portable archive interchange has host evidence. Rust merge, Android sync and LAN
sync are not implemented. M0's earlier shutdown fault remains an undiagnosed
stability watch; successful regressions do not erase that observation.

Git base: `28b3225`. Source: [`desktop/`](../../desktop/README.md),
[`STORAGE.md`](../../desktop/STORAGE.md), and
[structured results](M1_RESULTS.json). Existing Android 0.8.9/code22/schema8/archive3
edits and unrelated projects remain untouched. `adb devices -l` listed no devices.
No phone backup, restore, install, personal document, Google migration fixture or
Android H1–H8 action was performed.

## Delivered behavior

- Desktop schema 1→2 additive migration, exclusive writer lock, private owned root,
  bundled SQLite with foreign keys/WAL/FULL, complete saved graph and persisted FTS.
- Immutable originals, pinned snapshots, staged validation/review, additive restore,
  collision mapping, raw source manifest/aliases, durable receipts and idempotent
  committed retries. New restore selection creates another copy, never a sync join.
- Format-1/2/3 reads and format-3 writes, strict envelope/metadata/reference/hash/type
  validation, old defaults after validation, cancellation and 64 KiB original streams.
  Actual JPEG/PNG/WebP dimensions/type and PDF original page count are checked.
- Journaled names, notes, filing (including empty), typed detail saves/removals,
  page appearance/order/text commits, stack catalog edits and deletion. Stable IDs,
  replica sequence/context, source aliases and tombstones remain local. Stale tokens
  cannot overwrite manual edits or recreate deletions. No OCR engine is included.
- Original bytes and inactive metadata survive document/page deletion. Backups omit
  deleted documents and all sync/policy/private-device state. Destination publication
  uses no-overwrite renames; exports report a published result after commit failures.
- Startup recovery distinguishes unpublished original directories from committed
  restores. Atomic private JSON publication avoids treating a partial journal as a
  committed recovery record. Uncertain ownership fails closed.

Dependencies added: rusqlite **0.40.2** with bundled libsqlite3-sys **0.38.2**,
uuid **1.28.0** (random IDs and operation-local deterministic collision IDs),
rustix **1.1.5**, and tempfile **3.27.0** for tests. The
[494-package Linux inventory](M1_DEPENDENCIES.json) records declared/reviewed licenses.
SQLite is bundled; no system SQLite headers or OS packages were installed. The
COSMIC/PDFium pins and native notices from M0 remain unchanged. These records are
source-build evidence, not a completed distributable notice bundle.

## Checks run

From `Stack/desktop/`, with the existing stable compiler reporting exactly rustc
1.99.0 and `CARGO_HOME=/tmp/stack-desktop-dev/cargo-home`, `CARGO_BUILD_JOBS=4`:

```sh
cargo fmt --all --check
cargo check --workspace --locked --offline
cargo build --workspace --locked --offline
cargo clippy --workspace --all-targets --locked --offline -- -D warnings
STACK_PDFIUM_LIBRARY="$PWD/runtime/pdfium/lib/libpdfium.so" \
  cargo test --workspace --locked --offline -- --include-ignored
```

All pass. **19 tests** run: five archive tests, three core unit/migration tests,
eight repository integration tests and three renderer tests. The normally ignored
native PDF test is explicitly included and passes. Zero-test executables/doctests
are not counted. Python harnesses also pass syntax compilation.

Coverage includes complete graph/original round-trip for every version, duplicate
keys, depth/token/UTF-16 limits, integer types, enums, future/unknown fields, decimal
precision, Julian/Gregorian dates, crops, mismatched organization relations, paths,
symlinks, hashes, truncation/trailing bytes, cancellation, disk-reserve rejection and
failing writers. Repository checks cover persistence, migrations, foreign/future
database rejection, locking, read-only review, collisions, idempotent retry, stale
plans/workers, exact source evidence, manual filing, hidden stack ordering, tombstones,
snapshot lifetime, invalid-input nonmutation and injected transaction/publication
failures. Kernel/full-filesystem exhaustion and power loss were not simulated.

## Actual process termination

`tools/test_m1_process_stops.py` uses debug-only marked disposable `/tmp` libraries.
Each starts with the full five-document fixture, then a child is actually SIGKILLed
after its requested checkpoint. Reopening performs recovery. All **13** pass:

| Checkpoints | Recovery result |
| --- | --- |
| staged, durable journal, original directories 1–5, SQLite transaction before commit | Existing five documents remain; no new published graph or originals, no sequence consumed. |
| SQLite committed, cleanup | All ten documents and both sets of originals remain; committed event/receipt retained. |
| export target, export written | No destination published; owned partial file removed. |
| export published | Complete destination retained and validated; no abandoned operation. |

Every case verifies preexisting original hashes and existing metadata. These are
actual process stops, separate from injected ENOSPC-shaped errors in unit tests.
They establish the exercised boundaries; they do not prove controller/filesystem
power-loss behavior. A stop in the tiny interval between external empty partial-file
creation and ownership-journal publication may leave an unpublished uniquely named
empty `.part`; recovery refuses to delete files without ownership proof.

## Java/Rust compatibility

`tools/java_archive_host.py` compiles extracted production Java members and full
production JSON/geometry/value classes. Only validator/upgrade visibility changes;
method bodies are unchanged. Source hashes and the six Apache-2.0 AOSP JSON source
digests are recorded. The host uses OpenJDK **25.0.4.1** and AOSP JSON tag
`android-7.0.0_r36`, not SDK `android.jar` runtime stubs.

For each source version 1/2/3, the real Java writer produces an archive; Rust restores
and exports format 3; Java validates/rewrites it; a new Rust library restores it
again. Independently, the Rust fixture envelope passes Java validation/rewrite.
Comparisons ignore only archive creation time and unordered row-array ordering;
they preserve stack ordering, all authoritative values and all seven original hashes.
The production Java evidence digest agrees with Rust for non-BMP OCR text.

All three version chains pass. **21** differential metadata cases agree, including
exact integer types, UTF-16 notes limits, decimals/currency, historical evidence,
calendar cutover, Turkish case matching, crop numeric strings and organization links.
All **233** host-JDK currency codes match the pinned Rust list.

This is host production metadata/writer/digest proof. The host envelope reader is
test code; Android PdfRenderer/BitmapFactory, SQLite restore, OCR claim normalization,
device currency/Unicode tables and Android lifecycle behavior are excluded. Actual
Android archive restore remains M5 evidence. Rust safely rejects lone escaped UTF-16
surrogates that Android JSON can retain. No replacement or silent value repair occurs.
These documented restrictions prevent claiming arbitrary Android-archive equivalence.

## Native regression and count measurement

The rebuilt native window runs the M0 light, dark, narrow and doubled-content-text
cases with fresh isolated XDG roots and exact authored fixture bytes. Four runs / 12
own-window captures exit 0. The renderer test also checks type/page-count inspection
and rejects a wrong expected PDF count. Saved-transform rendering and persistent
library UI still belong to M2. Human keyboard/accessibility acceptance is unchanged.

The maximum-count fixture contains **1,000 image documents and 10,000 saved page
references**, a 5,581,479-byte manifest and a 15,775,523-byte archive. All import and
all 1,000 later-page search results identify the expected stable page. On this
Intel i7-10610U / Linux x86-64 debug build:

| Operation | Wall time | Peak RSS |
| --- | ---: | ---: |
| Restore (validation, review, file copies/publication, complete transactional graph/index/journal) | 28.32 s | 309,904 KiB |
| New CLI process, reopen/integrity checks, persisted FTS query and 1,000 page snippets | 2.22 s | 25,788 KiB |

These measurements preceded the final atomic private-journal publication refinement;
they are bounded development observations, not release budgets. An earlier run exposed
an unnecessary index rebuild; reopen now reuses persisted indexes. M2 must keep these
operations off the UI thread and show progress/cancel. No GUI responsiveness,
256 MiB single-file/8 GiB total-byte throughput or native-parser isolation claim is made.

Raw generated archives, libraries, Java classes, logs, engine/cache and captures stay
under `/tmp/stack-desktop-m1/` or ignored desktop runtime/target paths. Git contains
authored generators, source, pins, license inventory and public synthetic reports.
Next: M2 persistent COSMIC workflow and saved appearance, retaining the compatibility
and M0 native stability qualifications above.
