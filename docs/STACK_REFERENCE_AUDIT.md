# Google Stack Android reference audit

Audit date: 2026-10-15. Scope: read-only static inspection of the two supplied APKs in **`Reference/`** (capital R). No application development, APK modification, installation, account login, backend requests, or runtime experiments were performed. This document describes behavior and independently implementable requirements; it contains no decompiled implementation or copied assets.

The companion [feature matrix](STACK_FEATURE_MATRIX.md) turns these findings into an unimplemented parity checklist.

## 1. Main findings and confidence

Stack combined a small document-oriented interface with a local library and substantial Google backend integration. Camera/image processing, an OCR pipeline, SQLite metadata, page images, and manual organization are present locally. The inspected search controller sends nonempty queries to Google's private Paperwork service. Annotation responses supply names, category/organization suggestions, and extracted details. Optional PDF copying to Google Drive is separate from the application's own upload and metadata synchronization paths. Disabling the Drive option therefore does **not** establish that document processing stays on the phone. [E03–E13]

The successor can reproduce the interaction model without reproducing Google's infrastructure. The largest replacement work is local OCR text persistence and indexing, reliable document naming/classification, and portable backup with metadata. Bundled OCR models alone do not establish that original Stack offered fully offline search or classification.

Evidence labels used throughout:

| Label | Meaning |
| --- | --- |
| **V — verified static** | Directly observed in an APK manifest, resource, decoded executable path, schema, or archive comparison. Establishes what is present in that build, not that it executed successfully. |
| **P — published** | Historical product behavior described by Google's own public material; identified separately from APK evidence. |
| **I — inference** | Interpretation supported by the inspected paths, with a remaining gap stated. |
| **U — unknown** | Not established by this investigation. |
| **R — recommendation** | Proposed independent successor behavior or implementation approach, not a discovery about Google Stack. |

Resource presence alone can reflect a bundled library, unused UI, or a feature flag. In particular, shared scanner settings and generic account-library strings are not proof of exposed product features.

## 2. Inputs, preservation, and method

Both filenames begin `com.area120.paperwork_` and end `_minAPI24(arm64-v8a,armeabi-v7a)(nodpi)_apkmirror.com.apk`.

| Property | A: earlier supplied build | B: later supplied build |
| --- | --- | --- |
| Filename middle | `1.5.365000750.release-276` | `1.231.431385252.release-71588` |
| Manifest version name | `1.5.365000750.release` | `1.231.431385252.release` |
| Manifest version code | 276 | 71588 |
| APK bytes | 69,947,371 | 81,211,548 |
| Minimum Android API | 24 | 24 |
| Target Android API | 29 | 30 |
| Manifest compile SDK | 30 | 32 |
| Native architectures | ARM64 and ARMv7 | ARM64 and ARMv7 |
| DEX files | 2 | 2 |
| ZIP entries | 1,546 | 1,614 |

Package identity in both manifests is `com.area120.paperwork`; application label is Stack. Version identity and API levels were decoded from the manifests, rather than accepted from filenames. These are relative build comparisons, not verified release dates. APK signatures and provenance against an independent Google signing-certificate source were not authenticated. [E01–E02]

SHA-256 of each original APK:

- **A:** `1076c4cdaef53bf90baef3815bee567581391697d968821aa91aad3a5c7d2cda`
- **B:** `d35225e2d3ded6852b7da93f87d0fc5078fb493e3634dac1143e3c25acce74ce`

Tools: Python 3.12 standard-library ZIP/XML/hash inspection; JADX **1.5.3** for both APKs; Apktool **2.11.1** independently decoded B's manifest/resources with source disassembly disabled; `rg` for targeted searches. Tools came from their upstream GitHub releases and were stored under `/tmp`, without project dependencies or system package installation. Java 25 was available. JADX initially could not create its normal configuration directory; `JADX_CONFIG_DIR` and `JADX_CACHE_DIR` redirected that state to `/tmp`.

All decoded resources, copied native libraries/models, logs, and decompiled files remained under **`/tmp/stack-reference-audit/`**, outside the Git working tree. The original APKs were only read. The project's `.gitignore` excludes reference directories, APK archives, DEX/smali files, and conventional decompiler output directories. Only the two authored Markdown files and ignore rules belong in the audit commit.

JADX completed with partial failures: **18 reported errors for A; 22 for B**. Obfuscation renamed most implementation classes. Some methods have incomplete reconstruction or instruction dumps; native implementations were not decompiled. Findings below use readable paths, resources, and independent manifest/resource decoding where available. This is not a complete reconstruction of either application.

## 3. Interface, navigation, and document workflows

### Main navigation and organization

**V:** Both builds have a home screen, a document collection, stack screens, and document detail/edit screens. In B, `home_content_fragment.xml` and `bottom_navigation_menu.xml` explicitly define **Home** and **All documents** tabs. `home_screen_fragment.xml` defines a search bar, an overlaid search view, and an add-document action. The add menu controller offers PDF import, gallery import, and camera capture. [E03]

**V:** Stacks are category memberships rather than exclusive physical folders: document/tag join records permit a document in multiple stacks. Home visibility and color are stack metadata. Stack editing distinguishes visible and suggested stacks; deleting a stack is described as retaining its documents in the all-documents collection. Stack detail screens contain suggested-document and filing actions. [E04, E10]

**V:** B contains a built-in category catalog: Bills, Banking, IDs, House, Medical, Receipts, Starred, Immigration, Insurance, Pet, Tax, Travel, Vehicles, Legal, and Work. Its initial catalog groups Bills, Banking, IDs, House, Medical, Receipts, and Starred separately from the remaining categories. Associated illustrations are referenced through `www.gstatic.com/paperwork/categories/…`; no illustrations were fetched. Catalog presence and ordering do not establish every account's default visible stacks, since persisted settings and server tags also participate. [E04]

**R:** Reproduce the two main destinations, prominent search/add actions, category memberships, and short save flow with original artwork and independent layouts. Avoid a folder tree, projects, collaboration UI, or a desktop framework during Android parity work.

### Capture and import

The following is a **static reconstruction** of the workflow, not a device walkthrough:

1. **V:** Choose camera, gallery, or PDF. Incoming Android `ACTION_SEND` intents also accept `image/*` and `application/pdf` in both builds. [E02–E03]
2. **V:** Capture/import builds page records and document previews. The camera has scanning guidance; B includes flash controls. Crop geometry, rotation, and enhancement state are retained separately from image files. Multiple pages and page removal are represented by editor screens and document/page collections. [E05, E10]
3. **V:** Overview/edit views permit crop/rotate and color adjustment. Stack-specific enhancement values are Original, Medium, and Bright. Automatic rectification, color selection, shadow removal, and OCR-assisted enhancement paths exist. [E05–E06]
4. **V:** A post-scan/filing flow presents a document name and stack selection. A batch filing flow includes progress, next, and completion actions. Names and memberships can be corrected by the user. [E04, E08]
5. **V:** The saved document opens in a preview with an information bottom sheet. The sheet contains extracted details, memberships/organizations, and scan date; B additionally has note and detail editing paths. [E09]

**V:** PDF import uses Android `PdfRenderer`, iterates pages, and generates local page images and a thumbnail. A separate asynchronous path copies the incoming PDF; the input display name is read and its `.pdf` suffix removed for naming. **U:** Whether every edit/export preserves an imported PDF's text layer, annotations, forms, signatures, or other structural information was not established. [E07]

**V:** Opt-in camera-roll document import has settings, a local document detector, and document-added notifications. **U:** Background timing, deduplication behavior, battery impact, and behavior under current Android media restrictions were not exercised. [E06, E14]

**U:** Automatic shutter capture, arbitrary page reordering, merging existing documents, splitting documents, handwritten OCR quality, and supported document/page limits are not established. Their absence from this report must not become a claim that Stack lacked them.

### Viewing, editing, sharing, and deletion

**V:** B's document menu contains share, rename, add to stack, delete, and save-copy-to-device actions. It has PDF preview/zoom layouts, clipboard-copy actions for details, and a local note field. A multiselect menu supports share and delete. Sharing code handles a single PDF, multiple PDFs through `ACTION_SEND_MULTIPLE`, and a ZIP result. `FileProvider` grants temporary access to document/archive cache locations. [E09, E12]

**V:** B's extracted-detail editor can add/edit/remove fields; the schema records deletion state and optional location/timestamp data. The UI warns that inferred values may be wrong. **I:** These controls are essential correction mechanisms for imperfect automation; preserve that simplicity in the successor. [E09–E10]

**V:** Both builds include a biometric access setting. **U:** This establishes an application access gate, not database encryption or biometric-bound encryption keys. Google account/security support is present, including unsupported-account and required-update screens. A generic bundled string offering account-free use does not prove that Stack itself exposed such a mode. [E02, E14]

## 4. Scanning, OCR, naming, classification, and search

### Image processing and OCR

**V:** Both APKs bundle `doc_detector.tflite`, `color_correction.tflite`, `shadow_mobile_f16.tflite`, and a substantial `assets/photo-ocr/` model/configuration set. Native libraries include OpenCV, rectification, document image processing, TensorFlow Lite, and `libpaperworkpipeline.so`. [E01, E06]

**V:** B's `OcrEngineImpl` constructs a text-layout pipeline, processes bitmaps locally, and routes through a native pipeline that loads `paperworkpipeline`. Local color enhancement consumes OCR bounding boxes. This is stronger evidence of local OCR execution capability than model filenames alone. [E06]

**V:** `DocumentDetectorModel` returns a binary document/non-document result using a two-value model output. It is **not evidence of a semantic classifier for receipts versus bills versus medical records**. Separate server annotation paths produce category/tag suggestions. [E06, E08]

**U:** This audit does not establish that all document text recognition happened on-device, that local OCR results formed a persistent full-text library, or that the native pipeline needed no downloaded/configured state on first use. Embedded script models do not establish the application's promised OCR language coverage or quality. There are both local OCR and remote document-analysis paths.

### Automatic names and categories

**V:** B's upload path opens an authenticated `StreamAnnotateDocument` RPC. Its response is consumed by capture/storage conversion logic, which populates title suggestions, tags, organizations, and key/value details. The storage path applies suggestions when appropriate and has a date-based fallback title when annotation supplies no name. User-provided/imported names have separate handling. [E08, E11]

**V:** B also has local name-completion logic that combines stored suggestions with a built-in document-type vocabulary. That is a local aid to name editing, not proof of a complete local semantic classifier. The API descriptor includes `GetBaseDocumentNameSuggestions`, but its presence alone does not prove that particular RPC was used by the inspected UI. [E08, E11]

**P:** Google's launch announcement describes automatic names, suggested stacks, extracted due dates/amounts, and full-document text search. It explicitly attributes the document-understanding work to collaboration with Google Cloud's DocAI team. This corroborates the product intent, without exposing its backend algorithms. [S1]

**I:** Original-quality naming and classification cannot be recreated by merely connecting an OCR engine to the original UI. Independent text interpretation, confidence/fallback rules, and user correction are required. Exact model behavior, thresholds, training data, entity resolution, and server ranking remain unknown.

### Search

**V:** In both builds, the reconstructed search implementation contains a call to `paperwork.v1.PaperworkService/SearchDocuments`. B's controller sends a nonempty text query or organization/tag query to that service, then combines results with local saved-document metadata. The result view supports title, snippet, loading, no-results, error, and retry states. [E13]

**V:** The inspected document schema has no full-text virtual table or persisted OCR-text column; targeted inspection found no FTS/MATCH query in that document-store implementation. This is a bounded negative finding, not proof that no native component or uninspected path contains any index. [E10, E13]

**I:** Full-text search in the primary path depends on the backend. Local browsing by stack/date and metadata is a separate operation. **U:** Offline cached results, secondary search paths, matching/tokenization rules, typo tolerance, and relevance scoring were not determined. Do not label original full-text search offline-capable from these APKs.

## 5. Data storage, formats, metadata, and backup

### Local library

**V:** A and B both create a SQLite document store named **`documents_db`**. It is an application document database, distinct from the bundled Room/WorkManager and analytics databases. B's migration and query code exposes the following data concepts; this is a behavioral/schema summary, not copied DDL. [E10]

| Data concept | Observed persisted information |
| --- | --- |
| Documents | Identifier, name, scan date, file path/type/identifier, version state, uploaded state, needs-filing state; B adds note, naming status/provenance, selected suggestion, and entry-point information. |
| Pages | Page identifier, image path, rotation, enhancement, four crop corners; B records whether crop/enhancement was edited and page source. |
| Document/page and document/tag relations | Separate join tables; documents can contain multiple pages and belong to multiple stacks/tags. Full order semantics were not reconstructed from SQL alone. |
| Categories and organizations | Tag identifier/name/group and thumbnail URL; stack color, home visibility, and suggestion-dismissal state. |
| Extracted details | Key/value/type; B migrations support repeated keys, location/timestamp blobs, and deleted flags. |
| Name suggestions | In B, ordered suggestion records, strategy, typed suggestion components, entity identifiers, and business-name candidates. |
| Background/cloud bookkeeping | Pending uploads, annotation and metadata updates, Drive-copy flag, per-page upload state, file versions/UUIDs, and synchronization tokens. |

**V:** The page-image manager writes UUID-named JPEGs under `page_images/`, retains full-resolution page variants, and uses temporary-original and enhanced-image locations. File resolution distinguishes credential-protected application files from cache. The database and files are account-aware through the application's storage/account infrastructure. **U:** The complete physical directory layout for a populated account, retention schedule, and cache eviction behavior require an actual data directory/runtime inspection. [E10–E12]

**V:** Edit screens explicitly accommodate downloading an original before editing. **I:** Having a locally visible document does not guarantee that all originals needed for every edit remain available locally. An offline successor must retain its source images/PDFs as authoritative local data. [E09, E12]

### PDFs and exports

**V:** PDF is the external document format in import/share/provider paths. The APK contains a custom PDF 1.4 writer whose page content draws JPEG image objects. **I:** That inspected writer creates image-based pages; it does not by itself demonstrate an OCR text layer. **U:** Call-path reconstruction does not establish that every exported or server-produced PDF uses that writer, or whether every export is searchable in another reader. [E07, E12]

**V:** The file provider exposes cache `documents/` and `archives/` paths for controlled sharing. B supports saving a PDF copy through Android's document-creation flow and handling multiple-document/ZIP share results. **U:** A full library backup containing stack memberships, notes, edit state, and OCR text is not demonstrated by PDF or ZIP export alone. [E12]

### Backend backup versus Drive copies

**V:** The application has its own authenticated document/image upload and metadata synchronization paths to the Paperwork backend. Page variants distinguish full, high-quality crop, and low-quality crop. A separate `save_copy_to_drive` flag controls optional PDF copies in Drive. User-facing resources describe a **Stack** Drive folder for new-document copies and a separate **Stack Export** folder for bulk export. Both builds contain the bulk-export (`DriveTakeout`) service path. [E10–E11, E14]

**V:** B adds backup-review UI, waiting-for-Wi-Fi information, explicit retry, mobile-data preferences, and an upload-on-upgrade receiver. These verify that pending uploads and failures are part of the product design, not merely incidental library strings. Exact default settings and retry scheduling were not established. [E14]

**V:** The data-deletion UI distinguishes deleting Stack data from deleting copies already saved to Drive. The backend descriptor includes `Wipeout`. **U:** Server retention/deletion guarantees, encryption details, account restore completeness, simultaneous-device behavior, and Android system backup eligibility were not established. Neither manifest explicitly sets `allowBackup` or declares custom backup rules; that alone does not establish what data the OS actually backed up. [E02, E11, E14]

## 6. Local capability versus Google dependencies

| Capability | APK evidence | Offline conclusion |
| --- | --- | --- |
| Capture, crop, rotate, enhance | Local bitmap/native processing and bundled models. | **V:** local processing exists. **U:** cold-start accessibility without account/network. |
| OCR | Local engine, native pipeline, bundled recognition assets. | **V:** local engine exists. **U:** complete offline OCR workflow and first-run readiness. |
| Manual library organization | SQLite document/page/tag data and local edit paths. | **V:** local persistence exists. **U:** every operation's account/sync gating. |
| Smart name/category/detail suggestions | Authenticated annotation requests and response conversion; local fallback/completion. | **V:** cloud-assisted path. **U:** full offline equivalent in the original. |
| Full-text search | Controller invokes private `SearchDocuments` RPC. | **I:** primary full-text path needs network/backend. |
| Edit a stored document | Local crop/enhancement state plus download-original UI. | **I:** availability can depend on retained originals. |
| Share/save PDF | Local URI grants and export/share handlers. | **I:** local data can be exported; **U:** every path works without missing downloads. |
| Drive copy/bulk export | Drive setting, upload flag, bulk export RPC. | **V:** cloud operation. |
| Account/status | Google sign-in, Play Services, personal-account restriction/update UI, `ClientStatusCheck`. | **V:** Google service integration. **U:** first use without Google account/Play Services. |
| Category illustrations | Built-in category records point to gstatic URLs. | **V:** some presentation assets are remote references. |
| Analytics/configuration | Firebase measurement, Primes, Phenotype/remote configuration, Cronet libraries. | **V:** integrations are packaged. **U:** actual transmitted payloads/frequency. |

The service host is **`paperwork-pa.googleapis.com`**, with `paperwork.v1.PaperworkService` RPC descriptors. Inspected operations include annotation, upload/download, metadata save/sync, tag listing, search, client status, wipeout, and Drive export. These are evidence locators, not an integration specification; none was contacted. No API keys, account identifiers, proprietary protocols, or credentials need to be carried into the successor. [E11]

## 7. Significant differences between supplied versions

These are endpoint comparisons. They do not date when each feature first shipped or prove all feature flags were enabled.

| Area | A → B evidence | Interpretation |
| --- | --- | --- |
| Overall interaction | Core scan/import, stacks, search, detail/edit, Drive copy/export, and biometric resources are present in A as well as B. | Preserve this common core as the initial parity target. |
| Details and notes | B adds note UI/schema, key/value edit layouts, field addition/removal, and supporting migrations. | B has a materially richer correction workflow. |
| Save/export | B adds a save-copy-to-device menu/handler, multiselect menu, multiple PDF/ZIP share handling, and related errors. | B broadens local export and batch actions. |
| Android document selection | B adds `GetContentGateway`/`GetContentActivity` and a PDF `GET_CONTENT` intent filter. | B can participate as a PDF chooser for another application; this is distinct from incoming share import. |
| Backup diagnostics | B adds review/retry screens, outstanding-upload/Wi-Fi notifications, mobile-data controls, and upload-on-upgrade receiver. | Better visibility of incomplete backup. A already has upload/Drive-copy infrastructure. |
| Naming metadata | B adds structured name-suggestion tables and strategy/provenance, business/entity candidates; service descriptor adds suggested-detail/name operations. | More sophisticated suggestions and correction state; server algorithm remains unavailable. |
| Camera/presentation | B adds flash labels, a gallery screen, speed-dial layout, zoom/loading/highlight layouts, and rating/biometric prompts. | UI additions are verified; actual capture quality gains are not measured. |
| OCR assets | `photo-ocr/`: 138 entries / 10,850,496 uncompressed bytes → 147 / 14,505,274. B adds Georgian, Gujarati, and Hebrew recognizer triplets and changes multiscript configuration. | Broader packaged recognizers; no verified supported-language claim. |
| Main image models | Document detector 730,128 bytes; color selector 731,472; shadow model 14,584,496, all byte-identical across A/B by SHA-256. | No change to these three model files; surrounding processing can still differ. |
| Native/network libraries | Cronet 90 → 100; B adds `libtensorflowlite_jni_stable.so`; ARM64 `libimageutilsjni.so` grows from 856,624 to 11,104,968 bytes. | Significant dependency/binary changes, not proof of specific new behavior. |
| Platform/size | Target API 29 → 30, minimum unchanged at 24; APK grows 11,264,177 bytes, approximately 16.1%. B adds `GET_PACKAGE_SIZE` permission. | Compatibility/dependency changes; size is not attributable solely to OCR or features. |

ZIP comparison found **148 added, 80 removed, and 499 changed entries**, comparing decompressed entry content by path and SHA-256. Resource renaming and library churn make those counts unsuitable as feature counts. B has 498 default string entries versus A's 397, and 220 default layouts versus 182; bundled-library resources contribute to those counts. [E01, E15]

## 8. Practical independent offline reproduction

All items in this section are **R — recommendations**, not completed work or a claim of exact Google algorithm parity.

### Keep the first version small

Use one native Android application and a simple local pipeline. A pinned JDK, Android command-line SDK, and Gradle wrapper are a reasonable baseline. Choose Kotlin or Java and one UI approach; postpone desktop UI and cross-platform frameworks. Android provides SQLite, PDF rendering/creation, camera APIs, and the Storage Access Framework. Camera2 offers dependency-free access but requires substantial lifecycle/device handling; a maintained camera wrapper may reduce total complexity despite adding a dependency. Make that choice explicitly when development is authorized. [S2–S4]

Package OCR code **and the initially supported language data** for first-run operation without downloads. Tesseract is a candidate independent open-source engine with desktop portability, but an Android integration entails native build/wrapper work and model packaging. Assess licensing of both engine and trained data, memory, quality, and latency before choosing it. Do not assume Google's embedded models are reusable, or add a model runtime solely because the reference contains TensorFlow Lite. [S5]

### Replace the cloud-dependent behavior locally

| Challenge | Initial practical approach | Boundary to validate later |
| --- | --- | --- |
| Clean scans across devices | Capture source images, detect document corners, allow manual crop/rotate, perspective-correct, then apply modest contrast/illumination correction. Add an image-processing library only if it materially simplifies this work. | Low light, curved receipts, shadows, rotated images, memory limits, and camera lifecycle. Exact Google enhancement is unnecessary. |
| Reliable OCR | Process one page at a time off the UI thread; retain text, page identity, geometry, language, and engine/version information locally. | Receipts, mixed fonts, skew, low-resolution images, multilingual accuracy; explicit supported-language scope. |
| Automatic names | Prefer a clear heading/merchant plus document type/date, with a scan-date fallback. Offer a short editable suggestion. Preserve explicit user names across reprocessing. | Ambiguous dates, duplicate titles, poor OCR, imported filenames. |
| Classification | Start with independently authored keyword/phrase rules over OCR text and a small category set; allow multiple memberships and explicit user correction. Add a small text classifier only if measured failures justify it. | Low confidence should leave a document unfiled or ask for a quick choice; do not force a misleading category. |
| Extracted details | Begin with common date/amount patterns and contextual labels; retain source-page references and allow correction. | Currency/locale ambiguity and several plausible totals/dates. Treat these as suggestions. |
| Full-text search | Persist OCR text and index it with names, notes, and useful metadata in SQLite FTS. Rank title matches and show snippets/page context. | Check the selected Android SQLite build's supported FTS extension; upstream FTS5 documentation is not proof that every target Android system provides it. Use available FTS or deliberately bundle SQLite only if justified. [S6] |
| Durable local data | Keep originals authoritative; store page order, edits, categories, details, and OCR text in an independently designed schema. Make save/index updates recoverable and processing resumable. | Process termination, storage exhaustion, large PDFs, reindexing, deletion of derived files, and migration consistency. |
| PDFs | Preserve imported originals, render previews, and generate multipage image PDFs using platform support. Distinguish in-app text search from exported searchable PDFs. | A portable OCR text layer is a separate format requirement and may justify extra work/library later. [S3] |
| Backup and desktop later | Export documents plus a versioned manifest containing memberships, notes, page order/edits, and OCR text; define import/restore. Use user-selected local storage destinations. | PDF export alone loses organization. Keep the format platform-neutral so desktop can adopt it later. [S4] |

Provide account-free first use, bundle original category illustrations or simple independent icons, and omit mandatory Google APIs, remote model downloads, telemetry, and server status gates. For an entirely offline build, omit network permission and configure backup explicitly so Android's own backup behavior matches the product promise. A biometric lock can be included through platform APIs; make any at-rest encryption requirement a separate documented decision.

### Recommended next step

Review and adopt the matrix's **P0 behavioral baseline**, then authorize a separate, bounded OCR/scan feasibility evaluation using independently sourced sample documents and explicit accuracy/latency/memory criteria. The evaluation should settle OCR packaging, language scope, camera complexity, and SQLite FTS availability before application architecture is committed. This audit did not implement or benchmark any candidate.

## 9. Evidence register and reproduction

Paths below refer to **temporary decoded output**, not repository files. `res/…` is relative to JADX's resource directory; `defpackage/*.java` is relative to its source directory. Obfuscated names are specific to the identified build. Identifiers and brief data descriptions are provided as locators; proprietary source/assets are not included.

| ID | Evidence locator | What it establishes |
| --- | --- | --- |
| E01 | Original APK ZIPs; SHA-256 and decompressed-entry comparisons with Python `zipfile`/`hashlib`. | Input identity, sizes, ABI/model/library inventory, version differences. |
| E02 | A/B `AndroidManifest.xml`; B independently checked with Apktool and `apktool.yml`. | Version/API/package data, permissions, entry points, Google components, import/provider contracts. |
| E03 | A/B home/capture resources; B `res/layout/home_screen_fragment.xml`, `home_content_fragment.xml`, `res/menu/bottom_navigation_menu.xml`; B `gpb.java`. | Two main tabs, search/add controls, camera/gallery/PDF menu. |
| E04 | B `gph.java`, `gpr.java`; `res/layout/stack_*.xml`, `post_scan_fragment.xml`, `filing_*.xml`, `document_stacks_edit_fragment.xml`; stack-related strings. | Category catalog, remote thumbnail references, stack/filing interactions. |
| E05 | A/B edit/capture/overview resources; B `ghn.java`, `ghl.java`, `ghh.java`; named crop/rectifier classes under `com/google/bionics/scanner/rectifier/`. | Multipage/edit controls, enhancement enum, local image pipeline. |
| E06 | A `gwj.java`, `gxa.java`, `gwy.java`, `gxd.java`; B `gig.java`, `gir.java`, `ghh.java`; `com/google/android/libraries/vision/visionkit/pipeline/NativePipelineImpl.java`; bundled assets/libs. | Local OCR and image-model paths; binary document detector; native boundary. |
| E07 | B `gcp.java`, `geb.java`, `gcm.java`. | PDF rendering/page import, separate incoming-file path, filename handling. |
| E08 | B `fcx.java` annotation branch, `gcj.java`, `gww.java`, `gjk.java`. | Server annotation use, name/tag/detail conversion, date fallback, local completion vocabulary. |
| E09 | B `res/layout/detail_fragment.xml`, `detail_bottom_sheet.xml`, `note_fragment.xml`, `value_edit_fragment.xml`; `gkx.java`, `glv.java`; `KeyValueEditFragment.java`; download-original strings/layout. | Detail view, notes/corrections, edit-download state. |
| E10 | A `hme.java`; B `gyx.java`, `gyh.java`, `gyi.java`, `gyv.java`, `gym.java`. | Document SQLite store, migrations, metadata/relations, browsing queries. |
| E11 | A `hrp.java`, `goe.java`; B `hgp.java`, `hbc.java`, `fcx.java`, `gzi.java`, `ghd.java`, `fsa.java`, `gax.java`. | Private service operations, authenticated upload, annotations, tag/metadata sync, detail requests, export/status. |
| E12 | B `gwu.java`, `frh.java`, `fyb.java`; `gmw.java`, `gmz.java`, `gkv.java`, `gbw.java`; `hdl.java`, `hdx.java`, `hdr.java`, `hdn.java`, `hds.java`; `res/xml/file_paths.xml`; detail/multiselect menus. | File/cache/page variants, sharing and document creation, bundled PDF writer. Writer call coverage is incomplete. |
| E13 | A `hba.java`; B `gnp.java`, `geq.java` search branch, `ghe.java` result branch, `gno.java`; `res/layout/search_result.xml`, search-results layouts; E10 schema. | Main search RPC and display/local-metadata paths; no observed document FTS index. |
| E14 | A/B strings and settings resources; B `gav.java`, `gan.java`, `gog.java`, `hbu.java`; `BackupReviewFragment.java`, `UploadOnUpgradeReceiver_Receiver.java`. | Biometrics, auto import, Drive-copy/export, backup review/network policy, deletion wording. |
| E15 | A/B resource/XML comparisons and E01 ZIP comparison. | Version-difference table, presence/absence of resources and components. |

To repeat the static audit with the same upstream tools, use an output directory outside the checkout. For example, with `audit_tmp` pointing at a new temporary directory and `apk_path` at an original APK:

```sh
sha256sum -- "$apk_path"
JADX_CONFIG_DIR="$audit_tmp/config" JADX_CACHE_DIR="$audit_tmp/cache" \
  /path/to/jadx-1.5.3/bin/jadx -j 3 --log-level warn \
  -d "$audit_tmp/jadx-output" "$apk_path"
java -jar /path/to/apktool_2.11.1.jar d -s \
  -p "$audit_tmp/framework" -o "$audit_tmp/apktool-output" "$apk_path"
```

JADX's nonzero exit can accompany useful but incomplete output; preserve/read its error summary before making claims. Compare decoded manifests, default resources, document-store migrations, caller paths, and decompressed model hashes. Do not rebuild/re-sign the inputs, run private RPCs, or commit output directories.

## 10. Limitations

- No device, emulator, `adb`, or populated application data directory was used. Navigation is reconstructed from resources/controllers; pixel appearance, animations, actual first-run flow, scan quality, timing, accessibility, and feature-flag activation remain unverified.
- No packet capture or cloud operation was performed. Network conclusions come from callers and contracts, not observed traffic; service availability and current shutdown behavior were not checked.
- Native binaries and backend implementations remain opaque. Decompiler failures and obfuscation limit completeness; a missing string/schema entry is not universal proof of absence.
- Archive hashes identify these exact supplied files; they do not establish Google authenticity. No externally authenticated signing-certificate comparison was performed.
- Export fidelity, restore completeness, searchable-PDF behavior, security/encryption guarantees, OCR accuracy/language coverage, page limits, and cold-start offline behavior require separate evidence.
- No Google code, UI asset, font, model, native library, schema implementation, or extracted resource file is proposed for reuse. The deliverables contain authored observations, behavioral requirements, and proposed independent approaches only.

## 11. Public sources

Public sources supplement the APK audit; they are not substitutes for its evidence. Accessed 2026-10-15. No screenshots or downloadable product assets were copied.

- **S1:** Google, [Organize your documents effortlessly with Stack](https://blog.google/innovation-and-ai/technology/area-120/stack/), 2021-03-30: historical product behavior and DocAI collaboration.
- **S2:** Android, [Camera2 package reference](https://developer.android.com/reference/android/hardware/camera2/package-summary): platform camera access.
- **S3:** Android, [PdfDocument](https://developer.android.com/reference/android/graphics/pdf/PdfDocument): platform PDF creation; not a guarantee of automatic OCR text-layer generation.
- **S4:** Android, [Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files): user-selected document access/creation through the Storage Access Framework; [SQLite package](https://developer.android.com/reference/android/database/sqlite/package-summary): platform database support.
- **S5:** Tesseract, [user manual](https://tesseract-ocr.github.io/tessdoc/) and [compilation guide](https://tesseract-ocr.github.io/tessdoc/Compiling.html): independent OCR candidate and integration/build considerations.
- **S6:** SQLite, [FTS5 documentation](https://www.sqlite.org/fts5.html): full-text indexing, matching, and result facilities; availability on Android must be checked separately.
- **Tool provenance:** [JADX 1.5.3 release](https://github.com/skylot/jadx/releases/tag/v1.5.3), [Apktool 2.11.1 release](https://github.com/iBotPeaches/Apktool/releases/tag/v2.11.1).
