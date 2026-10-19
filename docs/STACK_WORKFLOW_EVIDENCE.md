# P0 workflow and address-safe naming follow-up

2026-10-16 · **0.6.1-dev / code 10 / schema 5** · implementation **4afbefa**.
Canonical `org.stackdocs.app` was updated in place. This continues naming/filing
acceptance and milestone 5 without starting portable backup, extracted-field editors
or P1. Prior scanner approval and five user-reported OCR workflow passes remain valid.

## User feedback and resulting behavior

The user reported that stack suggestions were good for a veterinary examination
bill, while the proposed name was the practice's street address. Record this as
positive filing feedback for one document and a **title-quality failure**, not a
pass of the complete suggestions workflow. No actual practice/address, document,
OCR text, date or amount was obtained/exported. The user then stepped away and
authorized continued independent P0 work. After the 0.6.1 update, the user reported
“all worked” for the four requested hands-on steps below.

### User-reported follow-up: passed

On 2026-10-16, the user confirmed all four requested steps worked:

1. Fresh scan/import of the veterinary bill: review the proposed name for a useful
   practice/bill heading rather than address/contact information.
2. Edit/save the proposed name and accept suggested stacks independently.
3. Close/reopen Stack, confirm the saved name and stacks, and search the saved name.
4. Explicitly deselect every stack and save; reopen, confirm the document stays
   unfiled and remains in All documents.

This establishes user-reported corrected-name and saved-choice workflow acceptance
on that check, not an independently observed or broad quality benchmark. The actual
proposed/saved name and private contents were not requested or recorded. Dismissal
was not part of these four steps and retains automated evidence only.

The old title rule accepted the first short letter-containing line and could treat
a short address as a heading. `local-phrases-4` rejects common numeric/street/unit,
city/state/postal and contact/metadata lines, examines the first 12 first-page lines,
and ranks recognizable issuer/document headings ahead of arbitrary prose. Veterinary
practice/clinic headings can outrank a generic Invoice heading. Bill evidence yields
**practice · Bill** rather than a broad Medical document suffix; unchanged category
rules still permit multiple stacks. An address alone yields no name; a supported
generic **Invoice** heading remains a safe suggestion. First-page identity and
bounded text budgets are unchanged; later-page headings are not invented.

Rule 4 is versioned separately from the intermediate device-tested rule 3, ensuring
unreviewed intermediate proposals are recomputed after the last adjustment. Existing
names/memberships remain untouched until explicit acceptance. Accepted/manual names
and dismissed proposals for unchanged evidence remain user decisions across rule
updates. If an earlier bad name was accepted, ordinary **Rename** remains available;
the update does not silently undo that choice.

Additional P0 fixes:

- Shared `NameEditor` for document rename, suggested-name edit and stack creation
  validates before closing. Blank/overlong input, duplicate stacks and failed saves
  retain the typed value and show an inline error. Save/cancel controls prevent
  duplicate in-flight submissions; success closes only after the durable operation.
- The stack picker discards invisible deleted IDs only when the user explicitly
  saves its visible selection. Repository validation gives an actionable deleted-
  stack error before replacing filing or recording a decision. Hidden stacks remain
  selectable; Starred remains manual. Existing memberships are preserved on failure.
- Suggestion actions include their field in accessibility labels and stack vertically
  when their measured widths cannot fit. Stack tiles expose one checkable target.
  Search's accessible description now includes document text as well as names.

No schema migration, runtime dependency, model, scanner/render algorithm or OCR
configuration changed in this follow-up.

## Isolated workflow evidence

[Machine-readable checks](workflow/checks-summary.json) retain successful observations.
All library writes use new `files/checks/` contexts; the application's real repository
is never swapped or seeded. Two platform-only test components live solely in the
instrumentation APK: a fixed fictional content provider and a separate-UID export
recipient. They are removed with that test package and are absent from the app APK.

| Mode | Groups | What passed |
| --- | ---: | --- |
| `namingOnly` | 1 | Address-first/split-address/contact/city/postal fixtures prefer the veterinary issuer and Bill; address-only name abstention retains filing; no later-page heading invention. |
| `suggestionsOnly` | 7 | Existing ownership, field decisions, empty filing, stale commit/Apply races, rule identity, bounded text, legacy drafts, retry/recovery, real OCR/search/reopen. |
| `workflowOnly` | 3 | SQL failure at document/page/membership insertion rolls back metadata/copied files; original/draft bytes survive; successful scan retry is idempotent; restart reclaims only abandoned copies; broken staging storage refuses ingestion and reopens after repair. |
| `providerWorkflow` | 2 | Real external provider imports valid/unnamed multipage PDFs; denied/interrupted/unsupported reads leave no partial document/staging. Separate UID cannot read without a grant, reads matching PDF bytes with a read-only grant, cannot write, and loses access after revocation. Invalid UUID/extra-segment/traversal URIs and expired exports are rejected. |
| `workflowUi` | 3 | Production name editor retains blank/201-character/failed-save input, supports retry and duplicate-stack correction; picker saves deliberate no-stacks without invisible deleted IDs, selects hidden Banking with checkbox semantics; 320 dp / 1x and 2x text controls fit, retain 48 dp touch height and field labels through recreation. |
| `suggestionUi` | 1 | Production review name edit/save, filing accept, independent dismiss and full-filing-editor Cancel still work after the editor refactor. |
| `longWorkflow` | 1 | Both actual workers, accepted name/filing, retained manual Starred, stable page-6 search, reopen and byte-identical PDF export on a longer authored document. |

The provider grant test creates **one fresh UUID-named authored PDF** in the normal
export cache because Android must exercise the actual provider boundary. Its source
library is isolated; only that exact synthetic cache file is removed in `finally`.
No private document is exported or granted to the recipient. Import fixtures in the
test APK contain only fixed invented content and reject caller-selected file paths.

Storage tests deliberately abort transactions or make the isolated staging path
unavailable. They do **not** fill the handset or establish actual ENOSPC/power-loss
acceptance. Name-editor failure uses an injected IOException. User documents and
recovery archives are unaffected. The debug host initially failed because the font
override was applied after resources had been accessed; its isolated context is now
configured in `attachBaseContext`. The failed run is not counted as a pass, and no
handset font/settings change was used.

The [six-page result](workflow/long-document-results.json) covers **6,647 authored
characters / 189,285 original PDF bytes** on Pixel 4a/API 33: both workers finished in
**9,172 ms**, IO/UI pings were **1/1 ms**, and post-workflow `Debug.getPss()` was
**65,166 KiB**. Timing starts after worker launch and excludes fixture creation/import;
this is one run, not a percentile distribution. Post-workflow PSS misses peaks.
A page-6-only `violetquartz` search resolves the stable page after reopen. OCR,
accepted title/filing and export leave the untouched six-page PDF byte-identical.
These authored pages are not a camera/complex-layout or maximum-size benchmark.

Unchanged scanner/native and prior force-stop evidence is reused from
[scanner quality](STACK_SCANNER_QUALITY.md), [OCR](STACK_OCR_EVIDENCE.md) and
[suggestions](STACK_SUGGESTIONS_EVIDENCE.md). This pass does not repeat the full
scanner corpus or claim its image algorithms changed.

## Follow-up corpus, title review and limitations

The [84-document / 87-page fixture](../app/src/androidTest/assets/naming-followup-corpus.json)
has **5,415 characters** and preserves the original 52 as prior regression cases,
adds 12 address regressions plus 12 naming cases reviewed during authoring, and
separates eight evaluation cases. These are fictional source text and generated
PDFs. Labels/rules still share an author; the new split is **not an external blind
sample**. Near-duplicate veterinary address cases weight the pooled score heavily;
it is not representative population accuracy. Cases were inspected while developing
the rules; no further tuning was
made after this measured run. No actual user veterinary document was benchmarked.

[Measured results](suggestions/naming-followup-results.json) separate source text
from actual Tesseract OCR. Category sets and titles agree between those two paths
on this corpus; this does not establish zero OCR character/word error generally.
All 52 prior category sets are unchanged from rule 2, including its known errors.
No new categories or learned classification were introduced.

| Classification split | Precision | Recall | Coverage / abstention |
| --- | ---: | ---: | ---: |
| All 84 | 96.552% (84 TP, 3 FP) | 97.674% (2 FN) | 80.952% / 19.048% |
| Prior 52 | 97.674% | 95.455% | 73.077% / 26.923% |
| Address regressions, 12 | 100% | 100% | 100% / 0% |
| Separated follow-up, 8 | 90% | 100% | 100% / 0% |

False categories remain Medical on reminder/marketing text mentioning prescription/
dosage. Missing receipt wording and mortgage/Banking cues remain the two misses.
Three of 18 negatives receive a category; one is in the separated follow-up split.
These results are insufficient for broad acceptance or automatic application.

All **22 explicit structural title expectations** match on both source and OCR
paths. This includes the ten veterinary issuer-after-address/contact cases, safe
address-only abstention and supported generic headings. Exact expectation matching
is a regression check, not the title-usefulness metric.

The [agent title review](suggestions/naming-followup-title-review.json) retains each
judgment against the previous rubric. Of **64 proposed names**, **60** were judged
useful unchanged, **2** useful after a small edit and **2** unhelpful. Estimated
correction/dismissal need is **4/64 = 6.25%**; separated follow-up **1/7 = 14.286%**.
Twenty documents get no name; positive name coverage is **62/66 = 93.939%**. This is
agent judgment, not observed user acceptance. Two remaining misleading names come
from marketing/reminder context. A numeric-leading practice name is conservatively
replaced by a generic invoice heading; a bank example loses an available issuer.
Street-ending business names can also abstain to a generic heading. International/
OCR-corrupted addresses and diverse real layouts remain unestablished. The update
fixes the demonstrated failure structure without claiming universal entity detection.

Pixel 4a/API 33 corpus timings:

| Operation | P50 | P95 | Maximum |
| --- | ---: | ---: | ---: |
| Rules on source text | 3.323 ms | 5.819 ms | 88.046 ms |
| Snapshot + analysis + commit after OCR | 8.143 ms | 57.840 ms | 104.692 ms |
| OCR per document | 301 ms | 409 ms | 524 ms |

Same method as the prior suggestion corpus: elapsed realtime; fixture creation,
import, model installation and production queue scheduling excluded; one initialized
OCR engine reused. More title filtering adds work; different fixtures/GC/runtime
conditions prevent a controlled latency regression conclusion from these two runs.
External total-process PSS maximum: **125,426 KiB / 122.5 MiB**, **79** samples at
nominal 250 ms plus adb/dumpsys overhead, including fixture rendering/native OCR/
instrumentation. It is a sampled maximum, not classifier-only or worst-case memory.

Proposed quality targets remain unagreed. The separated set still misses the earlier
95% precision / no-more-than-10% title-correction recommendations. Keep advisory-only
behavior and the broad quality gates open; use diverse independent real/public
samples and external/user title review next rather than treating these templates
as parity acceptance.

## Build, preservation and reproduction

Offline `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` pass: **0 errors /
6 warnings**, using the cached toolchain. [APK audit](workflow/apk-audit.json):
**31,444,067 bytes**, **19,635 bytes** above 0.6.0-dev. No new permission/dependency/
model; aapt reports CAMERA only. All original OCR native/model hashes, four ABIs
and static native/ZIP alignment checks remain valid. Only Pixel 4a/API 33/4 KiB
execution was exercised; API 24/current Android/other ABI/16 KiB runtime remain open.

The earlier 6-document/12-page handoff predates user activity. Immediately before
this update the library held **7 documents / 13 pages / 8 stacks / 10 memberships**;
all 13 OCR jobs succeeded. The membership count rose from 8 to 10 while the user was checking
suggestions before upgrade; the immediate pre-upgrade snapshot is the comparator.
Pre/post read-only metadata fingerprint:
`aea67eb08ae7510137b2877e6fa1ce3a225a5808d093b4dcefc996b680ac7cbc`.
Pre/post originals/draft/consolidation-recovery aggregate:
`112fa9c76b32ff6f20fd8c15e48a0ff240a12fbab66f27bab26d60a6f00620d0`.
They match exactly, including the file aggregate after removal of the dedicated
synthetic checks tree and instrumentation package. Canonical Stack was reopened.
Counts, names, memberships, saved scanner edits and original/
draft/recovery files remain intact. No private contents were logged/exported.
Canonical Stack was never cleared/uninstalled/reseeded or its database replaced.
Google Stack/reference data/settings/accounts were untouched in this follow-up.

```sh
ANDROID_USER_HOME=/tmp/stack-dev/android-user ./gradlew -g /tmp/stack-dev/gradle-cache \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --no-daemon --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e namingOnly true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e workflowOnly true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e providerWorkflow true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e workflowUi true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e longWorkflow true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
python3 tools/measure_suggestions.py --adb /path/to/adb --serial DEVICE \
  --corpus naming-followup-corpus.json --output /tmp/naming-results.json
```

UI modes need an accessible awake screen; never bypass secure unlocking or change
personal settings to run them. Automated fixtures and the test package can be
removed afterward without touching the canonical library. Full real document-
picker/share-chooser flow, canceled provider writes, actual disk-full/storage loss,
large edited exports, physical camera/lifecycle/permissions, disposable offline
first use and broad accessibility/platform acceptance remain open. The four-step
corrected-name/edit/filing/reopen/search/explicit-empty-filing check above passed
by user report; a hands-on dismissal check and broader quality acceptance remain open.
No backup, field editor, batch filing or P1 work was started.
