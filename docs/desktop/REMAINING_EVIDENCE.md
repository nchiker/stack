# Remaining-work evidence checkpoint

2026-10-18; ongoing implementation from `fe07fad`, not a final release report.
R1 engineering and R2 offline text are verified with the qualifications below.
Machine-readable outcomes and source deltas are in [REMAINING_RESULTS.json](REMAINING_RESULTS.json).
All inputs authored independently, all libraries disposable, no windows opened.
Existing unrelated dirty Android/UI/docs/Mockup files remain preserved.

Observed checks:

| Check | Exact observation | Artifact |
| --- | --- | --- |
| Core queue/storage tests | 6 unit + 15 prior integration tests passed | `/tmp/stack-resume-core-tests.log` |
| Real offline PDF/OCR | 1 native integration scenario passed embedded pages, page-two hit, image scan, image-only PDF, blank page, crop exclusion, original preservation and restart | `/tmp/stack-r2-real-text.log` |
| Three Rust replicas | 1 real SQLite/file scenario passed adoption into empty peers, forwarding, independent/concurrent edits, resolution, blocked backup, deletion/replay/restart/original retention | `/tmp/stack-r3-exchange.log` |
| Java causal golden | 5 cases passed on host JVM with AOSP JSON; Android runtime false | `/tmp/stack-java-sync-golden2/report.json` |
| First diagnostic stress | First draw145ms, executor gap157145us; executor target fails | `/tmp/stack-r1-profile2.log` |
| Text-shaping-only optimization | First draw173ms, executor gap181731us; both targets fail | `/tmp/stack-r1-optimized-counts.log` |
| Subsequent workspace run with optimized iced_graphics | 56ms/63798us, cumulative-process RSS626476KiB; suite failed palette-accessibility assertion, so no acceptance pass | `/tmp/stack-remaining-workspace1.log` |

Never substitute the cumulative workspace RSS for the fresh-process count run.
The historical `/tmp/stack-import-feedback/final-checks/report.json` failures remain
valid observations for their recorded source. New source requires fresh acceptance.

Implementation currently includes detail draft retention, dirty quit choices,
native palette/custom color, an actual AccessKit child-tree update path, owned native
window handles for asynchronous chooser-parent export, schema-3 durable text jobs,
pinned offline English Tesseract assets and the existing PDFium lifecycle. Physical
screen-reader, chooser parenting, keyboard and visual acceptance remain unobserved.

The causal/exchange implementation is a preliminary versioned subset. Empty-peer
baseline adoption is explicit; existing nonempty libraries are refused until reviewed
source-ID mapping exists. A written exchange file is not acknowledged sync. Java
conformance is host evidence only: Android authoritative journaling, conflict/apply
UI, actual device exchange and authenticated LAN are still unfinished.

Runtime preparation, without opening an application:

```sh
python3 desktop/tools/setup_ocr.py
export STACK_TESSERACT_LIBRARY="$PWD/desktop/runtime/ocr/lib/libtesseract.so.5"
export STACK_TESSDATA_DIR="$PWD/desktop/runtime/ocr/tessdata"
export OMP_THREAD_LIMIT=1
```

The setup command refuses an existing destination; reuse the verified existing
runtime rather than rerunning it. Engine/model SHA-256 pins live in the setup and
native loader. Supported OCR configuration is English with the recorded Ubuntu
24.04 x86-64 Tesseract/Leptonica ABI. Embedded extraction is measured separately
from the small authored OCR scenario, which is not a general accuracy estimate.

Run the remaining acceptance harness in a fresh output directory after integration
fixes. Preserve failures and refresh this checkpoint with source hashes and the
final exact outcomes. The execution ledger and playbook track unfinished gates.

## Verified foundation checkpoint

- `/tmp/stack-remaining-foundation-20261018-final`: fmt, locked/offline check, clippy (workspace warnings denied), build, **64 workspace tests / 0 failed / 0 ignored**, and software-only check passed. Vendored upstream dependency warnings remain logged separately.
- Fresh 1000-document/10000-page process: first layout/draw **122ms**, maximum executor tick gap **124739us**, peak RSS **430832KiB**; all ten unchanged targets met. Native compositor/release timing remains unmeasured. Earlier failing runs remain above.
- The combined harness stopped only because the sandbox denied the private D-Bus socket. A separately approved headless rerun at `/tmp/stack-remaining-foundation-portal/report.json` passed all six actual portal requests, with application exit0 and no failures. No chooser UI/native parent exists in that fixture.
- `/tmp/stack-remaining-m1-regressions/stops/report.json`: all **13 actual SIGKILL/restart boundaries** passed. `/tmp/stack-remaining-m1-regressions/java/report.json`: format1/2/3 host Java/Rust round trips and differential cases passed (233 currency codes). Device limitations remain explicit.
- After that suite, added a sync-only provisional-view tombstone guard. `/tmp/stack-sync-tombstone-recheck.log`: **23 core tests passed**, including the new no-resurrection regression; final workspace clippy/build passed. The source delta is recorded, not represented as identical to the earlier full-suite source. This does not change the normal non-adopted M2 timing path.
- The scoped toolkit patch fixes both native tree publication and the wrapping-row traversal omission. A headless test calls the actual adapter tree builder and verifies target-only activation. Physical screen-reader and native chooser parenting remain final user observations.
- Ordinary desktop import now passes the real repository/native text worker/index/UI/search path while preserving and safely rebasing an exact typed draft. English OCR and embedded extraction remain separately labeled.
- `/tmp/stack-desktop-foundation-package-20261018`: prepared debug package. Isolated install/CLI status/uninstall and shell syntax checks passed; library remained. Dependency-notice gaps are explicitly still open, so this is not a qualified public release package. Its compiled binary precedes the later sync-only guard; final delivery must rebuild/repackage.
- Official emulator and Android36 Google APIs image were provisioned into `/tmp/stack-dev/android-sdk`; a separate headless Checks AVD is being prepared. No canonical phone access or mutation occurred.
