# Phase 3 playbook: offline OCR and document search

Prepared 2026-10-16; updated after **0.5.0-dev / schema 4** implementation. **Bundled OCR, durable processing and full-document search are implemented and synthetically verified on Pixel 4a. Broad quality/platform acceptance remains open.** See [delivery evidence](STACK_OCR_EVIDENCE.md). Historical planning baseline: **d32ca84**, **0.4.3-dev / code 7 / schema 3**. This expands milestone 3 of the [P0 playbook](STACK_P0_PLAYBOOK.md): **ML-01, DT-03 and SR-01/02/03**. Scanner changes were tested and approved by the user. [Consolidation](STACK_CONSOLIDATION.md) left one app, `org.stackdocs.app`, with both libraries preserved.

## Outcome and boundaries

A user imports or saves a document, sees local recognition progress, and can find a word printed only on a later page. Recognition and its search results survive restarting the app. An interrupted page resumes on the next launch. Editing or deleting a page cannot let an older recognition result overwrite current state. Original files, filing choices, titles, page order and reversible edits remain intact.

Initial recognition scope is **printed English**, including camera/image documents and rendered PDF pages. Include existing saved pages through an idempotent local backfill. New drafts remain fast and recoverable; queue their pages after the document is durably saved. No account, Play Services, network permission or first-use engine/language download. Initial build-time dependency acquisition is separate from runtime offline operation.

Keep the current scanner/UI, SQLite/FTS4 and native Java application. Automatic names, categories, extracted dates/amounts, handwriting/multilingual guarantees, searchable exported PDF layers, portable backup and desktop work remain later milestones. OCR must not make an untouched imported PDF count as edited: its exported bytes must still match its original.

## Current integration points

Paths below are under `app/src/main/java/org/stackdocs/app/`.

| Surface | What exists | Required extension |
| --- | --- | --- |
| `LibraryDatabase.java` | Schema 3; page `ocr_text`, `ocr_state`, `ocr_engine`, `language`; FTS4 `search_index(document_id,title,body)` | Additive migration for revisions, durable claims/attempts, provenance and page-aware indexing. Existing OCR fields alone do not implement a queue. |
| `LibraryRepository.java` | Serialized DB/file operations; `index()` aggregates page text; safe FTS query tokenization; `editPage()` clears text/state; delete/removal rebuild index | Queue on import/save/backfill; transactionally invalidate edits; claim and commit by page ID/revision/token; index only current text; remove page-index rows on deletion. |
| `StackApplication.java` | One `io` executor initializes the repository before UI work | Recover interrupted jobs, then start a separate single OCR worker without blocking `io`. |
| `PageRenderer.java`, `PageState.java` | Bounded source sampling, EXIF, crop/rotation, color, flattening, PDF rendering | Produce bounded OCR inputs from immutable page snapshots, without changing saved geometry/presets or export behavior. |
| `MainActivity.java`, `Document.java`, `DocumentActivity.java` | Title search, snippet field, document viewer with page navigation | Search body text, show useful snippets/status/retry, and open a later-page hit using stable page identity. |
| `ScannerChecks.java`, `ScannerQualityChecks.java` | 32 synthetic runner groups in isolated child contexts of the canonical app | Add real-engine OCR fixtures plus deterministic queue-race/failure checks; never seed the actual library or draft. |

The six consolidation-tool checks and 32 scanner groups are baseline evidence, **not OCR evidence**. Reuse only checks whose relevant inputs remain unchanged.

## Step 1 — pin and prove the bundled engine

Start by evaluating **standard Tesseract4Android 4.9.0** with **English `tessdata_fast`**. Upstream was rechecked for this plan; this is a candidate selection, not an integrated artifact or measured quality result. Record any justified alternative before adopting it.

The [upstream wrapper README](https://github.com/adaptech-cz/Tesseract4Android/blob/4.9.0/README.md) documents a precompiled JitPack dependency, API 21 minimum, private `tessdata` storage, standard/OpenMP variants and a non-thread-safe engine API. Its [4.9.0 release](https://github.com/adaptech-cz/Tesseract4Android/releases/tag/4.9.0) identifies Tesseract 5.5.1. Start with one standard instance on one worker; compare OpenMP only if measurements justify the added choice. The model repository declares Apache 2.0 and LSTM-only compatibility; select a compatible engine mode. [Model source and license](https://github.com/tesseract-ocr/tessdata_fast).

Before production integration:

1. Record exact Maven coordinates/revision, artifact SHA-256 and resolved dependency graph. Select an immutable `tessdata_fast` commit and calculate the actual `eng.traineddata` SHA-256. Do not invent hashes or depend on a moving branch.
2. Inspect the selected AAR and final APK: actual ABIs, all native libraries, runtime dependencies, minimum API and packaged assets. Preserve API 24–36 support; do not silently narrow ABI coverage to the attached handset. Inventory wrapper, Tesseract, Leptonica, image-codec/STL and model licenses/notices actually shipped.
3. Check native ELF segment alignment and APK ZIP alignment, including dependencies. Upstream release claims are insufficient. Follow [Android's page-size checks](https://developer.android.com/guide/practices/page-sizes); use the installed build-tools `zipalign -c -P 16 -v 4 <apk>` and an appropriate ELF inspection tool. Record which runtime page sizes were actually tested.
4. Bundle the English data in APK assets, with a small source/version/hash manifest. Install to a versioned private directory such as `files/ocr/<model-hash>/tessdata/eng.traineddata`: temporary file → flush/sync → verify hash → atomic rename. Never initialize from an incomplete file. Retry interrupted installation from the bundled asset; do not repair it through a download. Never clean document/draft/recovery directories as part of model installation.
5. Run the real engine against one authored page from fresh isolated OCR storage without a network. Record recognizable text, elapsed time, memory, APK size and installed data size. Rebuild offline after dependencies are cached.

Write the dependency/model provenance and notices into the repository before bundling. If artifact acquisition fails, record the exact failure and evaluate an independently obtained upstream build in ignored tooling storage. Do not substitute a cloud/download-on-first-use API. Avoid adding a native toolchain to every application build unless the actual dependency decision requires it.

**First working increment:** one bundled, independently sourced engine/model recognizes one synthetic page offline, using isolated storage; dependency hashes/notices and binary checks are recorded. Commit that bounded result, then complete the durable pipeline below. Do not mistake this checkpoint for phase completion.

## Step 2 — make recognition durable before connecting automatic work

Prefer using page rows as the durable queue unless a demonstrated need warrants a separate job table. Extend schema 3 additively (normally schema 4) and handle direct upgrades from schemas 1, 2 and 3. Test non-default crop, rotation, color, flatten, source-file/source-page identity, title, memberships and existing text. Do not recreate tables destructively or reseed the live library.

The design must represent:

- Monotonic `content_revision` and the revision associated with recognized text. Increment for OCR-input changes: crop, rotation, color, flatten or source replacement. Title/stack changes do not require recognition.
- Durable `pending`, `running`, `succeeded`, `failed` states, mapping legacy `unprocessed` pages to pending once. Persist attempts, a bounded error code/message and an opaque claim token. Record a retry policy so a repeatedly crashing page cannot create an infinite restart loop; manual retry can reset the attempt budget.
- Engine version, language, model hash and OCR-input/preprocessing version. A result belongs to a specific page/revision/claim/configuration, never just a document position.
- A valid last successful text revision, distinct from an attempt's transient state. A retry failure must not erase still-current recognized text. An edit must immediately remove the now-stale text from search while retaining all other current pages.

State protocol:

1. Import/scan commit first durably publishes originals, document and page rows. Insert pending work in that same transaction. A backfill marks existing eligible pages once; reopening the app does not create duplicate work or duplicate documents.
2. Startup requeues interrupted `running` claims and clears their tokens, subject to the documented attempt limit. Failed pages require a visible retry or an explicitly bounded automatic retry policy.
3. On `app.io`, atomically claim one eligible page and capture an immutable snapshot: stable IDs, validated source path, original PDF page index, geometry/presets, content revision, engine/model/input signature and claim token. Release DB transactions before rendering/recognition.
4. The dedicated OCR worker renders and recognizes that snapshot. No native engine use on the UI or repository executor; no simultaneous calls into the same engine. Never have `io` wait for OCR while OCR waits for `io`.
5. Completion returns to `app.io`. In one transaction, accept only if the page still exists and revision, claim token and intended configuration still match. Update text/provenance/state and affected search rows together. Otherwise discard the stale result. A late failure must not mark a newer claim failed.
6. Edits atomically increment revision, invalidate old text/claims, rebuild the affected document index and mark the new revision pending. Page/document deletion removes its queue/index contribution; a late worker completion cannot recreate it. Resolve page order from current rows, not a captured position.

Persist work before starting it. For this slice, recovery means resume on the next app launch, including after a force-stop followed by explicit relaunch. Do not promise uninterrupted work while the app is closed. Add platform job scheduling only if needed for an agreed background-execution requirement; a UI-thread callback or static in-memory queue is not durable recovery.

## Step 3 — bound image processing and isolate failures

Use the existing renderer so OCR respects saved crop, upright orientation and optional flattening. Start by measuring long-edge 1,600 versus 2,400 inputs on the same source pages; consider 3,200 only when it improves small-text accuracy within the memory budget. The current 4,096 decode cap is a ceiling, not a requirement to allocate it for every page.

Run decoding, perspective/curve correction, cleanup and native OCR on the worker, with only short snapshot/commit operations on `io`. Keep one page and one engine instance active; release bitmaps/native page allocations before claiming the next page. Report total process memory, not only Java arrays or post-GC snapshots. Do not reread full-resolution images for every UI refresh.

Process each PDF page locally from its retained source; keep `source_page` separate from current displayed order. For initial correctness, recognize rasterized PDF pages without adding a PDF text-extraction dependency. Keep unchanged-PDF export byte-for-byte and edited-PDF export behavior intact. Test OCR-only processing does not alter `PageState.unchanged()` semantics.

A blank page may successfully produce empty text. Corrupt/unrenderable inputs and engine/model initialization failures need understandable states and retry; other pages remain usable. Native process crashes require durable recovery, not a claim that a Java exception handler catches them. Bound attempts for poison pages, and release/cancel work where supported while still rejecting any stale result.

## Step 4 — finish search and understandable processing status

Keep FTS4 and the current sanitized prefix-query behavior, including Android-specific quoting/implicit-conjunction handling. The [SQLite FTS4 reference](https://www.sqlite.org/fts3.html) documents matching/snippets; verify behavior on Android's actual SQLite. Do not replace the search engine for this slice.

Maintain the document-level title/body index from current recognized page revisions. Add page-level FTS/context if needed to identify a matching stable page ID. Update document and page search rows transactionally with OCR results and deletions. Test titles stay searchable during pending/failed OCR; one failed page must not hide successful pages.

For body matches, show a readable plain-text snippet and current page number, then open the matching page. Pass a stable page ID to the viewer and resolve its current position on opening. Title-only matches can open the document normally. For queries spanning multiple pages, avoid inventing a single page match: show document context or choose a supported page match explicitly. Keep punctuation-only/no-results/clear-query and stack filters working.

Use small status/retry additions within the current document workflow: “Reading text…”, partial page progress, finished/empty text and “Couldn't read this page” with retry. No engine paths, model hashes or implementation jargon in ordinary UI. Rename and filing choices survive recognition/retry. Refresh current snapshots after commits so a completed page becomes searchable without restarting.

## Step 5 — verify with the real engine and adversarial state tests

Use non-sensitive, independently authored or compatibly licensed sources with pinned hashes and ground truth. Do not use the personal library as a benchmark or print its recognized text into logs. A deterministic engine double is appropriate for race/failure ordering tests; it does not establish OCR accuracy.

| Gate | Required evidence |
| --- | --- |
| Bundled first use | Real engine recognizes from fresh isolated model storage; final APK contains engine/data; no network permission, remote model path or account dependency. Also perform a genuine fresh-install offline walkthrough on a disposable emulator/spare device when available. Never clear the user's canonical app to simulate it. Distinguish the two forms of evidence. |
| Durable work | Kill during running recognition; relaunch and finish once. Repeat launches do not duplicate documents, jobs or FTS contributions. Exercise incomplete model installation and missing/corrupt model repair from assets. |
| Stale results | Pause an engine double, edit then complete the old job; repeat with deletion, page removal and a newer retry. Old success/failure must not overwrite or resurrect anything. |
| All pages/search | A unique printed term exists only on page 2 of a multipage PDF/scan; real OCR finds it, snippet identifies page 2, opening goes there, and search still succeeds after process restart. Also cover title-only hits, mixed title/body queries, punctuation, stack filters and no results. |
| Failure/blank | Blank page succeeds without endless retry; one failed page leaves other text/title searchable; explicit retry is durable. Rendering/import/export still work when recognition fails. |
| Preservation | Original byte hashes, crop/rotation/color/flatten, page order, titles/memberships and recovery archives preserved. OCR-only processing leaves untouched PDF export byte-identical. No destructive test setup against the live library. |
| Performance | Named device, input dimensions, engine/model IDs; per-page P50/P95 time, peak process memory, APK/installed-data growth and UI responsiveness while indexing. |
| Platform/native | API 24 and current Android, actual bundled ABIs, ELF/APK 16 KiB checks; distinguish static inspection from device/emulator execution. Unavailable coverage stays explicitly unverified. |

Build a corpus of at least **30 documents**: 10 clean printed general pages; 8 receipts/bills/statements; 6 synthetic forms/IDs with small/faint text; 6 multipage PDFs/scans with later-page-only terms. Apply lighting, skew, color/flatten and rendering variants across groups. Distinguish generated distortions from actual camera acquisitions. Have the user perform any new physical captures.

Compute character/word error using a documented normalization: Unicode NFC, normalized line endings and collapsed whitespace; preserve case, digits and punctuation for the primary scores. Report weighted corpus and per-document results, clean versus difficult subsets, blank-page false positives and exact search-term recall. Empty nonblank outputs count as errors. Keep raw fixture identities/truth and scripts reproducible without personal contents.

**Proposed engineering targets, not yet user-agreed acceptance thresholds:** clean subset CER ≤2% / WER ≤5%; full corpus CER ≤5% / WER ≤12%; 100% of designated later-page search sentinels found; at 2,400 long edge on Pixel 4a, P50 ≤5 seconds/page and P95 ≤10 seconds/page, total peak PSS ≤256 MiB, APK growth ≤40 MiB. Measure before tuning and report exceptions. These provisional targets can guide implementation without pausing for approval; do not label broad accuracy/performance acceptance complete until thresholds are agreed and evidence supports it.

## Delivery order and completion record

1. Recheck the current handoff, working tree, package and engine sources; preserve current user changes. Do the engine/model smoke increment first.
2. Implement/test migration and durable claims with controlled races, then wire the real worker into import/save/backfill and resume.
3. Finish body search, snippets/page navigation and status/retry. Verify real-engine multipage search after restart before calling full-text search implemented.
4. Run applicable builds/lint and targeted regression checks; run the corpus and record measurements/remaining coverage. Reuse valid scanner/consolidation evidence; rerun checks affected by code/schema changes. Update old test migration expectations when the schema advances.
5. Update dependency notices/provenance, README, feature matrix, this playbook's progress and the session handoff. Commit coherent increments and the completed phase. Do not mark P0 checkboxes from build success or an engine double. Stop before naming/classification work.

Create an OCR evidence record with artifact/model identifiers and hashes, exact checks/outcomes, corpus composition/metrics, device/toolchain, offline proof, native/ABI checks, failure/restart behavior, preservation checks and unresolved limits. Keep a distinction between implemented behavior, verified behavior and user acceptance. If only an increment is complete, say which remaining gates are open; do not present the phase as finished.

## Repository and handset operations

There is exactly one app, **Stack `org.stackdocs.app`**. At consolidation it held 8 documents / 14 pages / 8 stacks; counts may change through user activity. Do not assume it is disposable, recreate Preview, run `pm clear`, uninstall Stack, reset a draft, replace its database, or use the consolidation tool as a normal schema-upgrade path. Preserve private `files/consolidation_recovery/` archives. Update in place only. Existing-page backfill runs locally and must not export/log personal text or originals.

Pixel 4a/API 33, previously authorized serial `0A121JEC203530`: recheck availability. No unattended photography or unrelated/lock-screen screenshots. Use fresh isolated contexts for synthetic tests. The Git root is the parent of this project; scope commits to **Stack** and exclude sibling projects, APKs, SDKs/caches, private data and proprietary `Reference/` material. Independently sourced, licensed OCR assets are a separate permitted dependency, not a source to extract from the reference APK.

After acquiring/pinning required dependencies and assets, prefer the cached offline toolchain:

```sh
ANDROID_USER_HOME=/tmp/stack-dev/android-user ./gradlew \
  -g /tmp/stack-dev/gradle-cache \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  --offline --no-daemon --console=plain
# Only with an authorized available device; preserve canonical app data.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb uninstall org.stackdocs.app.test
```

Paths may expire; discover replacements without replacing the app/framework. New OCR dependencies require initial acquisition before offline builds can succeed. Compare permissions/dependency/native inventories and record the additional build cost honestly.

## Original planning checkpoint (historical)

This session wrote the playbook and [next-session prompt](STACK_NEXT_SESSION_PROMPT.md), inspected the current code paths and rechecked cited upstream documentation. **No engine/model was downloaded, no dependency/schema/app code changed, no new runtime tests were run, and no OCR quality/compatibility result is claimed.** Candidate binary/model verification, implementation and every OCR acceptance gate above remain next-session work.

## Delivery checkpoint — 0.5.0-dev / schema 4

Steps 1–4 are implemented. Step 5's functional checks pass on Pixel 4a/API 33:
8 OCR runner groups, 33 scanner groups, real native force-stop/new-process recovery,
30 synthetic documents/36 pages/72 paired samples and 6 later-page search sentinels.
Default Otsu missed simulated lighting variants; Sauvola preprocessing resolved
those measured failures while preserving scanner output. Exact artifact/model and
input IDs, corpus metrics, native audit, preservation evidence and limitations are
in [the OCR evidence](STACK_OCR_EVIDENCE.md). Both sizes produced zero errors on
these templates; 2400-pixel P50/P95 were 505/1750 ms, sampled total PSS maximum
235.0 MiB. Builds/lint pass offline; no P0 acceptance checkbox is inferred from that.

Remaining gates: varied real camera documents and agreed quality acceptance,
hands-on failure/retry UI, disposable first-install offline walkthrough,
API 24/current Android/16 KiB and other ABI runtimes, broader memory/storage failures.
The 30 documents are all authored synthetic layouts, not evidence of real-photo
parity. Naming/classification has not started.

User-reported follow-up: the five-step scan/import, later-page search/snippet/opening,
close/reopen search and Details success-status workflow passed without issues.
See the [evidence record](STACK_OCR_EVIDENCE.md) for the precise scope; broad quality
and platform acceptance remain open.
