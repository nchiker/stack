# Offline OCR and document search evidence

2026-10-16, **0.5.0-dev / version code 8 / schema 4**. OCR and full-document
search are implemented. Functional checks below pass on Pixel 4a / Android 13
(API 33). Broad real-photo accuracy, platform coverage and user acceptance remain
open; naming/classification is a separate later phase. First bundled-engine
checkpoint: **d9335a6**; this delivery builds on it.

## Independently sourced dependencies

- Standard `cz.adaptech.tesseract4android:tesseract4android:4.9.0`, obtained from
  [upstream's JitPack distribution](https://jitpack.io/cz/adaptech/tesseract4android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar).
  AAR SHA-256 `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a`;
  12,848,454 bytes. Published Gradle metadata matches this hash. Every app build
  verifies both the resolved AAR and bundled model/manifest hashes before packaging.
- [Wrapper tag 4.9.0](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0),
  release revision `15c5347`, identifies Tesseract 5.5.1, Leptonica 1.85.0,
  libjpeg 9f and libpng 1.6.48. Source archive inspected independently of the
  proprietary reference APK; no reference engine/model/artwork was reused.
- English `tessdata_fast`, immutable commit
  `87416418657359cb625c412a48b6e1d6d41c29bd`,
  [model source](https://github.com/tesseract-ocr/tessdata_fast/blob/87416418657359cb625c412a48b6e1d6d41c29bd/eng.traineddata).
  4,113,088 bytes; SHA-256
  `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`.
  Bundled manifest records these identities; engine uses LSTM-only mode.
- Resolved debug runtime graph: Tesseract4Android 4.9.0; ExifInterface 1.4.2 →
  JSpecify 1.0.0. AndroidX annotations excluded from runtime, existing annotation-jvm
  1.8.1 remains compile-only. No Kotlin, Play Services, OpenMP or runtime download.
- Upstream notices ship in `assets/notices/`: wrapper/Tesseract/model Apache-2.0,
  Leptonica BSD-style, IJG README/acknowledgement, libpng, LLVM C++/ABI/unwind/compiler
  runtime licenses with exceptions. No separate C++ shared object is bundled;
  native dependencies are Android system libraries plus the four OCR libraries.
  zlib is supplied by Android. Upstream builds with NDK 27.2.12479018;
  the app consumes the AAR and does not require a native build toolchain.

## Durable processing and search

Page rows form the durable queue. Additive migrations 1/2/3 → 4 preserve originals,
metadata, legacy text, source-file/source-page identity, crop, rotation, color,
flatten and memberships. Existing pages are queued once. New imports/scans publish
originals and pending rows in the same durable ingest operation; unsaved drafts
are not queued. New rows explicitly record the current engine/input configuration
rather than relying on an older installation's column defaults.

Claims include stable page/document IDs, monotonic content revision, opaque token,
and engine/model/input signature. Only a matching running claim can commit text,
provenance and both FTS4 indexes in one transaction. Input edits immediately clear
stale text, advance revision and queue a new attempt. Rename/filing changes survive
recognition. A retry failure retains still-current successful text. Page/document
removal deletes its index contribution; late success/failure cannot resurrect it.

Startup requeues interrupted running claims, invalidating their tokens. After three
interrupted attempts the page fails until explicit retry resets its budget. Normal
failures require manual retry. Configuration changes queue one new attempt while
retaining still-current previous text. Work resumes on the next app launch;
uninterrupted execution while closed is not promised. No platform job scheduler
or foreground service was added.

The single worker renders immutable snapshots up to a 2400-pixel long edge using
the approved renderer. It initializes one standard native engine and releases
bitmap/page allocations between pages and the engine when the queue becomes idle.
Rendering/native OCR runs separately from short repository IO claims/commits;
neither executor waits on the other. The model installer uses a same-directory
staging file, flush/fsync, size/hash validation and atomic rename, repairing only
OCR storage from the bundled asset.

FTS4 searches title plus current page text. Results show a plain snippet/current
page number and pass a stable page ID to the viewer. Title-only hits open normally;
queries spanning pages show document context without inventing one matching page.
Details shows processing progress, successful/blank state or page failure/retry.
Library snapshots refresh after recognition commits.

## Device checks

All fixtures use fresh isolated `files/checks/` children inside the canonical app;
no personal library/draft is seeded or edited. Commands are in the README.

- **8 OCR check groups pass**: fresh asset installation and corrupt/partial model
  repair; real engine `5.5.1/standard` recognizes an authored page; blank succeeds;
  real two-page PDF search/snippet/navigation; title/mixed/cross-page queries,
  punctuation, filters/no-results; repeated startup without duplicate jobs;
  retry text retention; edit/retry/configuration stale-result rejection; page and
  document deletion; current page renumbering versus original PDF source page;
  three-interruption budget/manual retry; production worker import/save/retry,
  deferred drafts and corrupt-source isolation. IO/UI pings in the final run were
  2 ms / under 1 ms while recognition ran; this is a targeted scheduling check, not a
  broad UI smoothness benchmark.
- **33 scanner check groups pass**, including actual direct schema 1, 2 and 3
  migrations, non-default source identities/edits, legacy drafts, idempotent save,
  original preservation and untouched/edited PDF export. Approved image algorithms
  were not changed.
- **Process termination**: `tools/check_ocr_restart.py` prepares a real recognized
  two-page document in an isolated context, persists a running retry claim, enters
  dense native recognition and is externally force-stopped. The expected first
  instrumentation process ends as crashed. New instrumentation recovers the claim,
  commits once and still finds the page-2 term with its stable page/snippet.
  Repeated initialization adds no jobs/documents. This tests force-stop recovery,
  not a deliberately triggered native segmentation fault.
- Untouched synthetic PDF export remains byte-identical after OCR; removed-page
  ordering and original PDF page identity remain independent.
- First smoke initialization plus recognition of one 1200×1600 authored page was
  628 ms; post-recognition PSS sample 58,184 KiB. That was the initial default
  thresholding increment, not the final corpus distribution or peak.

## Corpus and measurements

Committed [raw synthetic results](ocr/corpus-results.json) contain ground truth,
predictions, source/fixture-code hashes, model/configuration IDs, dimensions,
per-page CER/WER/timings and total-process PSS samples. Reproduce with
`tools/measure_ocr_corpus.py`. `OcrCorpusChecks.java` authors 30 documents:
10 clean general pages, 8 receipt-like pages, 6 small/faint forms, 6 two-page PDFs
with unique later-page terms. Total 36 pages and 72 paired 1600/2400 samples.
Receipt/form variants include simulated lighting/skew and saved color/flatten
choices. All are generated documents; none is a real camera acquisition, real
receipt/ID or personal source. Repeated templates limit the corpus's diversity.

An initial incomplete default-Otsu run missed 4 shaded receipts and almost all text
on 3 faint medium-color forms. Its [partial baseline](ocr/baseline-partial.json)
is retained honestly: 52 samples, aborted by a fixture sentinel/query mismatch.
It is not the completed corpus. The fixture was corrected and Tesseract's documented
Sauvola option (`thresholding_method=2`) selected for uneven backgrounds;
see [upstream quality guidance](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html)
and [parameter definition](https://github.com/tesseract-ocr/tesseract/blob/5.5.1/src/ccmain/tesseractclass.cpp).
Only engine preprocessing changed; saved scanner edits/output were preserved.
The input signature is now `page-renderer-2400-sauvola-v2`.

Final Pixel 4a/API 33 results:

| Input long edge | Weighted CER / WER | Clean CER / WER | Difficult CER / WER | P50 / P95 per page |
| --- | --- | --- | --- | --- |
| 1600 | 0% / 0% | 0% / 0% | 0% / 0% | 438 / 888 ms |
| 2400 | 0% / 0% | 0% / 0% | 0% / 0% | 505 / 1750 ms |

Ground truth per size: 7,134 normalized characters / 1,092 words. Normalization:
Unicode NFC, normalized line endings, collapsed whitespace; preserve case, digits
and punctuation. Levenshtein character/word edits are weighted by ground-truth
length. Empty nonblank outputs count fully as errors. All **6/6** designated later-page
search terms were found; one separate white blank fixture produced no false text.
No broader blank-background false-positive rate is established.

Times include repository snapshot/rendering and recognition with an initialized,
reused engine; fixture creation/import is excluded. Both sizes scored identically
on these templates; no accuracy advantage of 2400 was measured here. Production
retains its bounded 2400 input; 3200 was not needed for these fixtures. During the
corpus, 113 external total-process PSS samples at about 250 ms plus command overhead
observed a maximum **240,624 KiB / 235.0 MiB**. This includes instrumentation/fixture
generation, is a sampled maximum rather than an instantaneous guarantee, and does
not measure arbitrary camera inputs plus simultaneous preview/export.

The proposed clean/full CER/WER, sentinel, latency, sampled-memory and APK-size
engineering targets are met on this synthetic corpus. They are not user-agreed
acceptance thresholds; broad quality/performance acceptance remains open.

## Packaging, offline and platform evidence

[Actual APK/native audit](ocr/apk-audit.json), generated by
`tools/verify_ocr_apk.py`, records hashes/dependencies, model identity, ABI inventory,
ELF LOAD alignments and ZIP data offsets. All 16 libraries pass: `libjpeg.so`,
`libpngx.so`, `libleptonica.so`, `libtesseract.so` for each of `armeabi-v7a`,
`arm64-v8a`, `x86`, `x86_64`. AAR manifest minimum API 21; app remains API 24–36.
No ABI filtering. Both 64-bit ABIs have 16,384-byte LOAD alignment; 32-bit ABIs
have 4,096-byte alignment. All native APK entries are uncompressed and 16 KiB ZIP
aligned. Build tools 36 `zipalign -c -P 16 -v 4` passes.

After initial dependency acquisition, `assembleDebug`, `assembleDebugAndroidTest`
and `lintDebug` pass with `--offline`, using JDK 25 / Gradle 9.3.1 / AGP 9.1.1 /
SDK 36 / build tools 36.0.0. Lint: 0 errors, 6 warnings. APK is about 29.9 MiB
(previous about 182 KiB), within the proposed 40 MiB growth budget. Private installed
model adds 4,113,088 bytes plus filesystem overhead; OCR text/index storage varies
with content and has not been isolated as an installed-data growth benchmark.
`aapt dump permissions` reports only CAMERA, no network permission. Tests initialize
fresh private model storage from APK assets, with no network API or model path.

This establishes bundled first-use model initialization and offline builds, not a
genuine clean-install walkthrough on a disposable device. No spare/emulator was
available. Static 16 KiB checks do not establish execution on a 16 KiB device.
Only arm64 Pixel 4a/API 33/4 KiB runtime was exercised. API 24, current Android,
32-bit/x86 and 16 KiB runtimes remain unverified.

## Personal-library preservation

Before schema/pipeline upgrade, read-only metadata fingerprint:
`b9553a3ad813fb1a0ab450360c5d0b3bbf313ed03f78ffdedef0cfd91428c097`.
It covers all document titles/metadata, source identities, page order, saved edits,
stacks and memberships without exporting any values. Counts: 8 documents,
14 pages, 8 stacks, 8 memberships. Aggregate originals/draft/recovery file hash:
`7dddc1aa7706439ebb9ebb78185aaff3b0602cd035b4c8662f0470ef77cda4ba`.
Final installed-app read-back matches **both** fingerprints exactly: all 8 documents,
14 pages, 8 stacks and 8 memberships remain, with all 14 page jobs reporting
`succeeded`. Counts/states are operational evidence, not a personal-content quality
benchmark; recognized private text was not exported or logged. The aggregate file
hash also remains unchanged after removal of synthetic test storage. Stack was
updated in place; no Preview package, app reset or canonical uninstall was used.
The instrumentation package and dedicated synthetic checks tree were removed after
verification. The private production OCR directory occupies 4,035 KiB by device
`du -sk`; runtime page size is 4,096 bytes.

## User-reported hands-on workflow check — 2026-10-16

After installing 0.5.0-dev, the user reported steps 1–4 completed with no issues:
scan/import two printed-English pages, search a distinctive page-2-only word,
confirm its snippet and opening page, then close/reopen Stack and repeat the search.
After clarification of the Details step, the user confirmed: “yes, it says text ready
to search”. Record the five-step workflow as user-reported passed. The exact test
documents and measured corpus were not specified; broad accuracy/platform acceptance
remains open. Failure/retry UI remains a separate hands-on check.

## Remaining acceptance

Real-photo/varied-layout and multilingual/handwriting accuracy are unestablished;
initial scope remains printed English. The basic search/navigation/relaunch and
success-status workflow now has user-reported confirmation. Complete hands-on
failure/retry UI checks, broader memory/storage failure testing, disposable
fresh-install offline validation and platform/native runtime coverage. No semantic
naming/classification, extracted fields, OCR PDF text layer or portable backup was
added. Existing P0 acceptance checkboxes remain open.
