# Stack

An independent, open-source document library for Android. The goal is a simple, completely offline successor to Google Stack: scan, recognize, organize, search, and export documents. This project is not affiliated with Google and does not include the original application's code, assets, or models.

Development follows the [P0 playbook](docs/STACK_P0_PLAYBOOK.md) and [P1 runbook](docs/STACK_P1_RUNBOOK.md), with acceptance tracked in the [feature matrix](docs/STACK_FEATURE_MATRIX.md). The delivered OCR phase is tracked in the [offline OCR playbook](docs/STACK_OCR_PLAYBOOK.md). [Local naming, classification and filing](docs/STACK_SUGGESTIONS_EVIDENCE.md) are implemented; [0.6.1-dev follow-up evidence](docs/STACK_WORKFLOW_EVIDENCE.md) records address-safe names and additional P0 workflow checks. Broad acceptance remains open. The [final P0 evidence](docs/STACK_P0_FINAL_EVIDENCE.md) records implemented backup/restore, completed final verification and remaining acceptance gates; the [runbook](docs/STACK_P0_REMAINING_RUNBOOK.md) preserves the execution ledger; the [next-session prompt](docs/STACK_NEXT_SESSION_PROMPT.md) carries that continuous work order. The [reference audit](docs/STACK_REFERENCE_AUDIT.md) records verified observations and unresolved behavior from the supplied APKs.

**Current build: 0.8.9-dev / code 22 / schema 8.** The
[P1 evidence](docs/STACK_P1_EVIDENCE.md) records all 14 rows, consolidated automated
verification and preservation. The user reports the [H1–H8 session completed](docs/p1/HANDSON_CHECKLIST.md);
[completion evidence](docs/p1/USER_COMPLETION.json) stays separate from automated runs.
The [runbook and ledger](docs/STACK_P1_RUNBOOK.md) preserve the completed sequence;
[next-session instructions](docs/STACK_NEXT_SESSION_PROMPT.md) carry forward completed
work and evidence limits. No known P1 implementation or repair remains; broader
quality/platform parity claims still require their specific documented evidence.

The [informal UI follow-up](docs/STACK_UI_FOLLOWUP.md) records compact controls,
consistent Settings and preservation. A [one-time Google Stack migration](docs/STACK_GOOGLE_STACK_MIGRATION.md)
is completed with metadata. [Automatic details and actual transfer](docs/STACK_AUTOMATIC_DETAILS.md)
record detected defaults, USD for bare dollars, verified preservation and testing files.
The [stack tile editor](docs/STACK_STACK_EDITOR.md) adds inline Home controls,
drag ordering and accessible move options, preserved across restarts and format-3
backups (formats 1 and 2 remain readable).

The [0.8.7 follow-up](docs/STACK_UNLOCK_AND_LIBRARY_TOOLS.md) opens Android unlock
directly, remembers Newest/Name document sorting and shows the last successful
local backup date.37 affected automated groups pass; all five current canonical
preservation fingerprints match. The [primary-phone install/restore guide](docs/STACK_PRIMARY_PHONE_INSTALL.md)
and private APK/converted Google backup are ready in `~/Downloads/Stack-0.8.7/`.
The user reports the primary-phone restore completed with documents present.
The [0.8.8/0.8.9 icon and color repair](docs/STACK_RESTORED_ICONS.md) fixes standard
category glyphs and colored rings for imported stack IDs, with stable pastel
defaults for custom stacks. Install it as an update; the existing library does not
need another restore. Native authentication of the entry flow remains a separate
hands-on check; the earlier H1–H8 report remains completed.

The [next-session desktop work order](docs/STACK_DESKTOP_NEXT_SESSION_PROMPT.md)
defines a native Rust COSMIC app with phone-compatible transfer and a path to
opt-in bidirectional sync. The [desktop source and CLI](desktop/README.md) now include
the native preview foundation and M1 durable library/archive backend. [M1 evidence](docs/desktop/M1_EVIDENCE.md)
records 19 tests, Java/Rust format-1/2/3 checks and 13 process-stop recovery cases,
with explicit compatibility limits. The COSMIC window still shows the M0 demo;
M2 will connect the persistent workflow. Merge, Android sync and LAN follow later.
The [project plan](docs/STACK_DESKTOP_PLAN.md), [draft sync contract](docs/desktop/INTEROP_SYNC_CONTRACT_V0_1.md)
and [execution ledger](docs/desktop/EXECUTION_LEDGER.md) track the remaining gates.

## Current development slice

The current development build implements:

- Home and All documents views, with seven starter stacks.
- JPEG, PNG, WebP and multipage PDF import from Android's document picker; image/PDF incoming share handling.
- Durable private originals, a SQLite library, local title/full-document text search and manual membership in several stacks.
- Stack tile editor with creation/rename, inline Home visibility, confirmed deletion and durable drag ordering; Move earlier/later alternatives and portable backup order.
- Document preview with fit/zoom/pan, keyboard/accessibility alternatives and a Read/Details toggle; stable page navigation and matching-page search snippets.
- Local date/amount/issuer and clearly labeled identifier/staff details save automatically after complete OCR, with Detected/source provenance and Edit/Remove. Manual choices/removals stay authoritative; bare dollars default to USD and explicit currency codes take precedence.
- Recoverable notes/detail editors, sensitive detail-copy construction, bounded explicit name completions, notes/detail search and composed organization/stack/query filters.
- Durable imports and ordered batch selection/review/filing/delete with per-item outcomes; temporary read-only PDF/ZIP sharing and Android GET_CONTENT PDF selection.
- Optional future local Camera-photo detection/import and private import notifications, both off until explicitly enabled. Photo permission is requested only from that setting; OS scheduling and selected-photo scope apply.
- Optional Android biometric/device-credential app lock, off by default, with one gate across all entry points and foreground/screen-off relock. It adds no original/archive encryption.
- Format-2 saved-library archive includes notes/details/tags/tombstones, reads format 1 with safe defaults and excludes local consent/lock/batch authorization.
- Bundled printed-English OCR, durable per-page processing, startup recovery and page retry.
- Independent local name/category proposals with separate accept/edit/dismiss, durable user-choice provenance and stale-result rejection.
- In-app rear-camera capture and recoverable multipage scan drafts.
- Automatic page-corner proposals and perspective correction for new camera captures, with manual four-corner correction, rotation and page removal.
- New camera pages default to black-and-white cleanup. Color choices are Black & white, Original color, Color · Medium and Color · Bright; changing color preserves crop/rotation and the original JPEG.
- Save/share multipage PDF: untouched imported PDFs are copied intact; edited documents and scans are rendered to PDF.
- Explicit exclusion from automatic cloud backup/device transfer and no network permission.

The interface now follows Stack's white/blue visual style: circular stack tiles, document thumbnail grids, a floating add menu and a document details sheet. See the [interface reference notes](docs/STACK_UI_REFERENCE.md) for the patterns reproduced, independent artwork and remaining differences. Scan review also offers stack selection before saving.

**Reviewable local naming/classification and portable library backup/restore are implemented.** Printed-English OCR and full-document search are implemented in 0.5.0-dev; broad real-photo accuracy and platform acceptance remain open. The user reports camera capture and saving succeeded on the Pixel 4a. Cleanup/crop quality, difficult lighting and the remaining camera scenarios still need validation; see the playbook. Perspective correction straightens flat pages; an opt-in Flatten preview also corrects gently bowed horizontal text. Severe folds, curls and arbitrary 3D page shapes remain unsupported.

The 0.4.2-dev scanner pass adds validated autocrop proposals, smoother paper estimation, retained faint strokes, and reversible gentle-curve flattening. **Crop & rotate → Auto crop → Apply** saves revised corners. **Flatten → Use flattening → Apply** enables a supported text-curve correction; it is off by default. A failed autocrop preserves your manual corners. [Implementation research](docs/STACK_SCANNER_RESEARCH.md) and [fixed-source measurements](docs/STACK_SCANNER_QUALITY.md) document the decisions and limits. The Stack APK builds offline; lint reports 0 errors and 7 warnings. The Pixel 4a / Android 13 passed 32 synthetic runner groups, including additive migrations, legacy drafts and original preservation. The user tested and approved these scanner changes; broader corpus/platform checks and full P0 acceptance remain open. That scanner pass added no runtime dependency, network permission or OCR.

Version **0.4.3-dev** consolidates the former Stack and Stack Preview into one app named **Stack** (`org.stackdocs.app`), preserving both libraries and the approved Preview functionality. Future updates use the debug APK below; no Preview variant is built. [Consolidation evidence](docs/STACK_CONSOLIDATION.md) records preservation and recovery details.

Version **0.5.0-dev / schema 4** bundles Tesseract4Android 4.9.0 / Tesseract 5.5.1 and pinned English tessdata_fast. Existing saved pages queue once on upgrade; new imports/scans queue after durable save. Recognition respects saved edits, runs on a separate worker, and resumes interrupted work on the next launch. Three interrupted attempts mark a page failed until manual retry. Successful current text remains searchable through a failed retry; editing a page removes its stale text immediately.

Search finds titles and printed text across all pages. Results include a matching-page snippet and open that stable page; queries spanning several pages show document context. Expand **Details** to see text-reading status; on a failed page, tap its retry message. OCR changes neither the original nor the saved edit settings; untouched imported PDFs still export byte for byte. Processing can stop when Android closes the app and resumes on the next launch.

[OCR evidence](docs/STACK_OCR_EVIDENCE.md) records real-engine search, race/recovery and preservation checks, dependency/native packaging, and a **30-document / 36-page synthetic corpus**. Synthetic results do not establish camera-document accuracy. API 24/current-Android execution, 16 KiB runtime testing and a disposable fresh-install offline walkthrough remain unverified. Naming/classification now has a separate [implementation and quality report](docs/STACK_SUGGESTIONS_EVIDENCE.md).

Version **0.6.0-dev / schema 5** generates local printed-English name and stack proposals after saved-document OCR finishes. Expand **Details → Suggestions** to **Accept**, **Edit** or **Dismiss** the name and filing independently. Accepting filing adds suggestions to current stacks; **Edit all document stacks** replaces the complete selection you review. Starred and custom stacks remain manual. Empty/failed text still permits ordinary rename, filing and export.

Names/memberships stay as saved until you choose a proposal. Untouched scan defaults, imported filenames, legacy values, explicit names and deliberate no-stacks choices are tracked separately. Draft names/choices survive recovery. Retries and late results cannot overwrite a later edit, restore a dismissed proposal for unchanged evidence, or recreate deleted documents/stacks. No extra runtime dependency, model or network permission was added.

[Suggestion evidence](docs/STACK_SUGGESTIONS_EVIDENCE.md) records 7 suggestion groups, 34 scanner/migration groups, 8 OCR groups, isolated production review UI and force-stop recovery. On **52 authored synthetic documents / 55 rendered pages**, category precision/recall were **97.7% / 95.5%**; the 12 fresh held-out examples scored **88.9% / 100%** and exposed contextual false positives. Agent title review judged 36 of 38 proposed names useful unchanged. These small generated examples do not establish real-photo or broad automation acceptance; proposals remain explicit choices.

Version **0.6.1-dev / code 10 / schema 5** addresses a user-reported veterinary bill being named after its street address. Proposed names now reject common address/contact lines, prefer a credible practice/document heading, and abstain when no useful heading is available. A bill can be named **practice · Bill**. Existing names, accepted/dismissed decisions and memberships remain authoritative; no automatic renaming/refiling is enabled.

Name editors now keep invalid/failed-save input visible for correction or retry. The picker can recover an explicit selection containing a deleted stack, and suggestion actions fit narrow/large-text layouts with field-specific accessibility labels. Isolated transaction failures, real external-provider imports/read-only export grants and a six-page real-worker → acceptance → page-6 search/reopen → byte-identical PDF export check pass. [Workflow evidence](docs/STACK_WORKFLOW_EVIDENCE.md) distinguishes these checks from the remaining full picker/chooser, storage, platform and hands-on gates.

The expanded **84-document / 87-page synthetic corpus** passes all **22 defined structural naming expectations** on source text and real OCR. Category precision/recall are **96.6% / 97.7%** overall; the separated eight-case set scores **90% / 100%**. Contextual Medical false positives and broader title-quality limits remain. The user reports the four-step corrected-name/edit/filing/reopen/search/explicit-empty-filing check passed on a fresh bill. Broader quality acceptance remains open.

Originals live in app-private storage and remain available independently of the source provider. Uninstalling removes that library. Save PDF creates a portable document copy; it does not back up stack memberships or other library metadata.

## Build

No Android Studio, NDK, Node, Python environment, or server is required to build the current application.

| Tool | Pinned version / requirement |
| --- | --- |
| JDK | A Gradle-compatible JDK 17 or newer; this development build uses JDK 25. |
| Gradle | 9.3.1, supplied by the wrapper with upstream distribution SHA-256 verification. |
| Android Gradle plugin | 9.1.1. |
| SDK platform | `platforms;android-36`. |
| SDK build tools | `build-tools;36.0.0`. |
| Platform tools | `platform-tools` for optional device installation. |
| Android device | Android 7.0 / API 24 or newer. |

Install Android command-line tools from the [official download page](https://developer.android.com/studio#command-line-tools-only), then use the supplied SDK manager to install the packages:

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'
sdkmanager --licenses
```

Set `ANDROID_HOME` to your SDK, or create an ignored `local.properties` file containing `sdk.dir=/absolute/path/to/android-sdk`. Ensure the selected JDK's `java` is on your path.

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. With an authorized connected device:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The first build downloads Gradle and build dependencies. Once cached, `./gradlew --offline :app:assembleDebug` can build without network access. The app itself declares no network permission. Development signing is automatic; release signing and public distribution are separate tasks.

### Isolated scanner checks

Use `-PstackChecks` for **Stack Checks** (`org.stackdocs.app.checks`) before canonical delivery. The platform instrumentation runner uses synthetic data only in fresh isolated child directories. It never seeds/discards the actual library or draft. No extra test framework or camera capture is needed. Update Stack in place; never clear or uninstall it as test cleanup.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb uninstall org.stackdocs.app.test
```

Successful output ends with `PASS` and `INSTRUMENTATION_CODE: -1`. These checks cover synthetic processing, migrations, durable edits and exports; they do not drive the personal library UI or establish camera/overall P0 acceptance. `-e qualityOnly true` limits execution to quality fixtures. Details and remaining acceptance gates are in the [quality evidence](docs/STACK_SCANNER_QUALITY.md).

### Isolated OCR checks and measurements

After building and installing both APKs in place as above:

```sh
adb shell am instrument -w -r -e ocrOnly true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
python3 tools/measure_ocr_corpus.py --adb /path/to/adb --serial DEVICE_SERIAL --output /tmp/corpus-results.json
python3 tools/check_ocr_restart.py --adb /path/to/adb --serial DEVICE_SERIAL --output /tmp/restart-results.log
python3 tools/verify_ocr_apk.py app/build/outputs/apk/debug/app-debug.apk
```

The restart check deliberately force-stops Stack during synthetic native recognition, then relaunches instrumentation. All fixtures use isolated `files/checks/` children; they never seed the personal library. The corpus contains authored images/PDFs, not personal documents or camera acquisitions. Its script samples total process PSS and computes case-sensitive character/word error rates; source hashes, ground truth and predictions are retained in the report. Static APK verification needs host `readelf`.

### Isolated suggestion checks and measurements

After installing the app and instrumentation APKs in place:

```sh
python3 tools/verify_suggestion_migrations.py
adb shell am instrument -w -r -e suggestionsOnly true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e suggestionUi true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
python3 tools/check_suggestion_restart.py --adb /path/to/adb --serial DEVICE_SERIAL --output /tmp/suggestion-restart.log
python3 tools/measure_suggestions.py --adb /path/to/adb --serial DEVICE_SERIAL --output /tmp/suggestion-results.json
```

The host probe executes actual migration SQL against historical schema 1–4 fixtures. Device checks use fresh private child contexts. The UI host is debug-only/non-exported and exercises the production review view with isolated dependencies; it needs an awake accessible display. The restart check force-stops a held synthetic claim, then verifies recovery in a new process. The measured corpus separates source-text rules from real OCR over generated PDFs, and regression from fresh examples; it contains no personal/reference documents.

### P0 workflow follow-up checks

After the in-place app/test installation above:

```sh
adb shell am instrument -w -r -e namingOnly true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e workflowOnly true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e providerWorkflow true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e workflowUi true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e longWorkflow true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
python3 tools/measure_suggestions.py --adb /path/to/adb --serial DEVICE_SERIAL --corpus naming-followup-corpus.json --output /tmp/naming-results.json
```

The provider check uses an instrumentation-only external provider/recipient, a fresh isolated source library and one unique synthetic PDF in the actual export cache. It removes only that file/grant; it never exports a private document. Failure injection and large-text overrides remain isolated and do not fill the handset or change personal settings. See the evidence for what remains unverified.

## Implementation layout

| Location | Responsibility |
| --- | --- |
| `LibraryDatabase.java` | Versioned local schema, document/page/stack relations and FTS4 index. |
| `LibraryRepository.java` | Background imports, validation, file ownership/recovery, metadata edits and search. |
| `StackApplication.java` | Repository, serialized IO executor and processing notifications. |
| `SuggestionStore.java`, `SuggestionJob.java` | Durable input/decision provenance, claims, recovery and transactional accept/edit/dismiss. |
| `SuggestionRules.java`, `SuggestionWorker.java`, `SuggestionReview.java` | Bounded independent phrase rules, text worker and saved-document review controls. |
| `OcrModel.java`, `OcrEngine.java`, `OcrWorker.java` | Hash-verified bundled data, single native engine, revision-safe worker orchestration. |
| `MainActivity.java` | Library, title/body search, importing and stack management. |
| `DocumentActivity.java` | Document detail, manual filing, preview and export actions. |
| `PageRenderer.java` | Bounded local preview, JPEG orientation and PDF export. |
| `ScanActivity.java`, `CameraController.java` | Rear-camera capture and multipage review. |
| `ScanDraftStore.java` | Private JPEG originals and an atomically written draft manifest. |
| `PageEditorActivity.java`, `CropView.java`, `PageGeometry.java` | Reversible page edits and manual corners. |
| `BoundaryDetector.java`, `EdgePageDetector.java` | Validated region/edge proposals and fitted paper boundaries; no proprietary models. |
| `PageFlattening.java` | Conservative, reversible correction of gently bowed horizontal text. |
| `PageEnhancement.java`, `ColorOptions.java` | Local adaptive black-and-white cleanup and shared per-page color choices. |
| `Ui.java`, `Icons.java` | Shared visual styling and independently drawn interface symbols. |
| `DetailsSheet.java`, `StackPicker.java` | Document information panel and shared visual filing picker. |
| `NameEditor.java` | Shared validated name entry, retained failed-save input and retry. |
| `DocumentThumbnails.java` | Bounded first-page preview cache and cancellation for detached cells. |
| `ExportProvider.java` | Temporary read-only access to individual cached PDF exports. |

The source is under `app/src/main/java/org/stackdocs/app/`. The application identifier is currently `org.stackdocs.app`; public branding/package ownership will be settled before release. UI resources and the launcher icon are independently authored. Import limits are 256 MiB per source and 1,000 pages per PDF; scan saves allow up to 100 pages and 256 MiB of originals. Password-protected PDFs and unsupported image formats are rejected. Preview/export resolution is bounded; large-document export memory still needs measurement. Original bytes remain untouched. **Edited PDFs export as page images, losing existing selectable text, links and form fields in the exported copy.** Untouched PDFs export byte for byte. Removing a saved page excludes it from previews/exports and keeps its original until the document is deleted.

## Reference material and licensing

Original APKs stay in the ignored `Reference/` directory. Extracted resources, proprietary models, decompiled code, generated APKs, local SDKs/caches and signing keys must not be committed. Future OCR code and language data must be obtained independently with compatible redistribution licenses.

Project source and original resources are licensed under [Apache License 2.0](LICENSE). The generated Gradle wrapper is from [Gradle](https://github.com/gradle/gradle), also Apache 2.0. Build tools and Android platform APIs retain their own licenses; they are not bundled into this repository.

The image metadata runtime dependency is [AndroidX ExifInterface 1.4.2](https://developer.android.com/jetpack/androidx/releases/exifinterface), Apache 2.0, for maintained image metadata/orientation handling across supported Android versions. Its runtime dependency is JSpecify 1.0.0. AndroidX annotations 1.8.1 are compile-only. OCR additionally uses [Tesseract4Android 4.9.0 standard](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0) with independently sourced English tessdata_fast. Exact hashes, native dependencies and bundled license notices are recorded in the [OCR evidence](docs/STACK_OCR_EVIDENCE.md) and `app/src/main/assets/notices/`. No NDK is required for application builds. The application does not use an AndroidX UI framework, and the runtime classpath contains no Kotlin standard library. Build tools and dependencies retain their original license notices.

## Portable local backup — 0.7.1-dev

**Library menu → Backup & restore** writes a `.stackbackup` to your
chosen location or validates an archive before showing a restore review. Restore
adds documents; it keeps existing names/filing and maps conflicting IDs. Selecting
the same archive again imports another copy. Saved original bytes, page order/
edits, stacks, OCR and review choices are included. The unfinished scan and local
consolidation recovery archives are excluded and stay on this device. Backups are
not encrypted; protect the selected file yourself. Automatic OS backup stays off.

[Archive format and recovery](docs/STACK_ARCHIVE_FORMAT.md) documents format 1,
limits, compatible state and provider-dependent incomplete-output behavior. Schema
6 upgrades additively from 1–5; no runtime dependency/model/permission was added.
The final build/lint, migration, isolated archive/worker/UI and process-interruption
checks pass. [Final evidence](docs/STACK_P0_FINAL_EVIDENCE.md) records measurements,
canonical preservation and the remaining human/resource acceptance gates. The backup
screen uses Stack's toolbar, circular icons, action rows and blue controls; detailed
help is under **What's included**. Narrow/2x text layouts pass.

To build a **disposable checks installation** before touching canonical Stack:

```sh
./gradlew --offline -PstackChecks :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e archiveOnly true org.stackdocs.app.checks.test/org.stackdocs.app.ScannerChecks
```

`-PstackChecks` changes only the application/test IDs to `org.stackdocs.app.checks`
and `.checks.test`. Production builds without this flag remain canonical
`org.stackdocs.app`. Disposable synthetic checks never need to reset the personal
library. Measurement/restart tools accept `--package org.stackdocs.app.checks`.

### P1 verification and delivery

The final P1 pass builds offline with the pinned cached toolchain. Use `-PstackChecks`
only for disposable artifacts. Never install one over canonical Stack. Current test
flags include `p1Only`, `p1Batch`, `p1Ui`, `p1Corpus`, `p1Performance`, plus the existing
scanner/OCR/suggestion/workflow/archive flags. Each flag takes `-e FLAG true` with
`org.stackdocs.app.checks.test/org.stackdocs.app.ScannerChecks`. The P1 restart tool
enforces disposable package use; OCR/suggestion tools need
`--package org.stackdocs.app.checks`. Commands and scope are in the P1 evidence.

Canonical delivery uses the original cached signing environment and **no
`-PstackChecks`**, a read-only `-PpreservationProbe` before/after, and only `adb install -r`.
No clear, uninstall, database replacement, reseed or personal backup/restore is part
of verification. APKs, keys, screenshots, private archives and tool caches stay out
of Git. `tools/make_p1_handson_fixtures.py /tmp/stack-p1-handson` generates CC0 printable
fixtures for the consolidated final session. System authentication, camera/light,
photo/notification permissions and subjective utility remain human/resource gates.
