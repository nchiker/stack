# P0 implementation playbook

## Current delivery — 0.7.1-dev / code 12 / schema 6

The remaining P0 implementation sweep and all feasible final verification are
complete. Portable original/metadata backup and staged, journaled additive restore
are delivered. The backup screen now uses Stack's shared toolbar, circular icons,
action rows and blue controls; long help is expandable. No P1 work was started.
See [final evidence and the one remaining checklist](STACK_P0_FINAL_EVIDENCE.md).
Broad P0 acceptance remains open for the listed hardware, human-quality and system
boundary gates; do not replay completed implementation just because a box is open.

Stack was updated in place and reopened. The last preservation check kept current
6 documents / 12 pages / 9 stacks / 8 memberships, decision history, originals,
draft and consolidation recovery fingerprints identical. Historical counts below
are never reseeding targets. No personal backup/restore/clear/uninstall occurred.
Scanner approval, five user OCR passes and four naming/filing passes carry forward.


## Active execution order: all remaining P0, verification last

The latest user instruction is to finish **all remaining phase 0 items**, save
**all verification steps for the end of the run**, and continue without milestone
approval stops. Follow the [remaining P0 runbook](STACK_P0_REMAINING_RUNBOOK.md):
implementation sweep I1–I5, including **portable backup/restore**, then consolidated
verification/repair V1–V9. Its coverage map accounts for all 30 P0 rows. This
supersedes earlier stop-before-backup and interleaved-test instructions in the
historical implementation records below. P1 and structured-field editors stay out
of scope. No implementation, build, test or device acceptance was performed by this
planning update; baseline remains 0.6.1-dev / code 10 / schema 5.

Use the [saved next-session prompt](STACK_NEXT_SESSION_PROMPT.md). Continue through
independent work when user-only/device/resource checks are unavailable, and collect
those gates for one final checklist. Preserve the approved scanner, five OCR passes
and four user-reported naming/filing workflow passes. Do not invent acceptance.

## Delivered baseline and remaining acceptance

Updated 2026-10-16 for **0.6.1-dev / code 10 / schema 5**, implementation
**4afbefa**. Approved scanner, OCR/search and local reviewable naming/filing remain
implemented. The user's scanner approval and five earlier OCR workflow passes
carry forward. New feedback: filing suggestions were good on one veterinary bill,
but its proposed name was a street address. After the title correction, the user
reported **all four hands-on steps passed**: fresh name review, independent name edit/
filing acceptance, saved-choice/search after relaunch, and explicit empty filing
persisting after relaunch while remaining in All documents. Broad quality acceptance
and a hands-on dismissal check remain open.

**Delivered baseline: local naming/filing and partial milestone 5 workflow coverage.**
Address/contact/metadata title guards, retained invalid/failed-save name input,
recoverable deleted-stack selection and narrow/large-text actions are delivered.
Isolated rollback/provider/grant/editor/recreation and six-page real-worker/search/
export checks pass. [Current workflow evidence](STACK_WORKFLOW_EVIDENCE.md) records
84-document synthetic metrics, limitations and matching live-library fingerprints;
[original suggestion evidence](STACK_SUGGESTIONS_EVIDENCE.md) remains the baseline.

All **30 P0 rows** remain tracked in the [feature matrix](STACK_FEATURE_MATRIX.md).
Keep broad quality/platform/storage/hands-on gates explicit. The user authorized
continued independent P0 work, and now expanded the run to every remaining P0 item
including portable backup. Feedback-dependent acceptance stays open until evidenced.
Follow the active runbook above; stop only before structured-field editors or P1,
not before backup. The next-session prompt carries the continuous execution order.

## Product contract and current architecture

Deliver **capture/import → review → file → find → export**, entirely offline from
first use without an account, Play Services or model download. Keep originals and
manual corrections authoritative. Finish P0 before P1 conveniences or desktop work.

- Keep the single canonical **Stack `org.stackdocs.app`**, native Java 17/platform
  UI, minimum API 24 and compile/target API 36. Update in place only.
- SQLite/FTS4 uses additive migrations, stable IDs and explicit transactions.
  Originals live in private files, while previews/exports are disposable cache.
  Document names never determine storage paths. Untouched imported PDFs export
  byte for byte; edited/image documents render to PDF. A PDF export is not backup.
- Repository IO serializes metadata/file work; a separate single OCR worker does
  rendering/recognition. UI and repository work must remain responsive.
- Bundled OCR: Tesseract4Android 4.9.0 standard / Tesseract 5.5.1, pinned English
  tessdata_fast and Sauvola preprocessing. Runtime also includes ExifInterface
  1.4.2 and JSpecify 1.0.0; annotations are compile-only. Provenance, native audits
  and measurements are in [OCR evidence](STACK_OCR_EVIDENCE.md).
- No network permission, mandatory analytics, Google account SDK or downloaded
  product artwork. Platform cloud/device backup is explicitly excluded; intentional
  portable backup/restore is still to build. Build-time dependency acquisition is
  separate from runtime offline behavior.
- Preserve the approved crop/rotation/color/flatten pipeline, drafts, current
  title/stack choices and `files/consolidation_recovery/` archives. No scanner
  redesign or OCR-engine replacement is needed to start suggestions.

## Milestones and remaining delivery groups

| Original milestone | Scope | Current status and remaining gate |
| --- | --- | --- |
| 1. Local library | UX-01/02/03/05/06, SC-02/03/04, ORG-01/02/03, DT-01/02, EX-01/02, SEC-03 | Implemented local library, manual filing and import/export. Complete remaining share/provider, error/accessibility, platform and storage acceptance. Suggested filing belongs to milestone 4. |
| 2. Scanner | SC-01/05/06/07, UX-04 | Delivered and user-approved; 34 scanner/migration groups pass with the suggestions release. Broader real-photo, camera permission/interruption and platform acceptance remain open. |
| 3. OCR and durable search | ML-01, DT-03, SR-01/02/03 | Implemented and installed. Eight OCR groups, native-work force-stop recovery, 30-document synthetic corpus and five user-reported workflow steps pass. Real-photo quality, failure/retry UI, fresh-install offline and broader runtime coverage remain open. |
| 4. Local suggestions and filing | ML-02/03, UX-03; related UX-05/06, ORG-01/02/03 and rename portion of DT-02 | Implemented in 0.6.0-dev: additive provenance, local rules, independent accept/edit/dismiss and stale/recovery controls. Synthetic/runtime evidence linked above; broader quality and user acceptance remain open. |
| 5. Complete document workflow | Remaining UX-01/02/04/05/06, SC-01–07, EX-01/02, DT-01/02 | In progress: shared recoverable editor, deleted-stack selection, isolated rollback/storage refusal, actual provider/grant boundaries, large text/recreation and six-page OCR → file → search → export checks pass in 0.6.1. Full system picker/chooser, physical camera/lifecycle, actual storage exhaustion, broader accessibility/platform and hands-on acceptance remain open. |
| 6. Portable backup/restore | EX-03, DT-01/02/03, SEC-03 | Not implemented. Versioned archive and safe restore of originals, edits, memberships, OCR and suggestion/correction state; interrupted/corrupt restore must leave the existing library intact. |
| 7. Final offline quality/compatibility acceptance | SEC-02 and all P0 rows, including remaining ML-01/SR/DT gates | Shared real-document corpus, agreed quality targets, disposable fresh-install offline walkthrough, supported-device/native checks, storage failures and backup round trip. Run remaining verification in the final V1–V9 stage after implementation, per the latest user instruction. |

The current sequence is **complete remaining workflow/processing implementation →
portable backup/restore → consolidated verification and repair**. Author fixtures
as implementation proceeds; execute all remaining builds, tests, measurements,
walkthroughs and acceptance review at the end. Reuse existing evidence for its
actual scope; final verification covers new and affected behavior. Mark a matrix checkbox only when its entire acceptance
condition is demonstrated. Preserve the user's five-step OCR workflow result;
broad quality is not established by synthetic accuracy or build success.

## Reference APKs and installed Google Stack

The user explicitly reminds us that **both reference APKs are available locally
and Google Stack is installed on the test phone**. Use both as behavior references
for the next group, rather than guessing the original interaction.

- Ignored directory: **`Reference/`**, capital R. Supplied builds A
  `1.5.365000750.release` / code 276 and B `1.231.431385252.release` / code 71588;
  both are `com.area120.paperwork`. Both local APK hashes were rechecked against
  [the audit](STACK_REFERENCE_AUDIT.md) during this planning update and match.
- Installed reference: **Google Stack `com.area120.paperwork`** on Pixel 4a/API 33,
  previously verified as build B. Canonical successor: **`org.stackdocs.app`**.
  Recheck USB availability, foreground package and installed reference version
  before observing. Prior authorized USB serial: `0A121JEC203530`. Device access
  was rechecked during implementation: build B / 71588 is installed and accessible.
- Start with audit **E04** (post-scan/filing, stacks), **E08** (name/category
  suggestions, fallback and corrections), **E10** (provenance/relations), **E11**
  (annotation dependency). Use targeted read-only APK inspection to resolve gaps;
  keep decoded/extracted material in ignored tooling or `/tmp/stack-reference-audit/`.
- Observe the installed reference's name suggestions, stack selection, manual edits,
  dismissal and return/reopen behavior where accessible without changing its
  documents/settings/accounts. Record observed, static and inferred behavior
  separately. If a comparison needs a new document or account/network changes,
  obtain the user's specific direction; unavailable behavior stays unresolved and
  does not block independent local implementation. Have the user perform physical
  captures and secure unlocking. Do not capture unrelated/private screens or log
  personal contents.
- Record a short behavior/evidence table before implementation: interaction,
  APK locator/build, handset observation, confidence and successor decision. Use
  authored notes or independent diagrams, not copied Google artwork/source.
- The audit found **server annotation** supplying names/categories and separate
  local name-completion aids. The binary document detector is not a semantic
  receipt/bill classifier. Installed Google Stack and bundled models do not prove
  its automatic names/categories work offline or that its backend is available.
  Reproduce the user benefit with independently authored local logic. Do not call
  Google's private services, extract/reuse its models/code/assets/fonts, or add a
  network dependency. Full local-vs-cloud reference behavior remains unresolved.

## Delivered group: naming, classification and filing

**Implementation record:** delivered in 0.6.0-dev / schema 5; 0.6.1 corrects the reported address-name failure and advances milestone 5. The work order below
is retained as the acceptance contract and history; descriptions of schema 4 and
unconditional scan renaming describe the pre-implementation baseline. See
[actual decisions/results](STACK_SUGGESTIONS_EVIDENCE.md). No automatic application
was enabled. Remaining work is diverse corpus/hands-on/platform/workflow acceptance,
not reimplementation of the saved plan. The naming-group order below is historical;
the active remaining-P0 runbook now includes backup and defers verification to the
end. P1 remains excluded.

### Outcome and boundaries

After OCR completes on a new imported/scanned document, the user receives a short,
useful proposed name and zero or more suggested stacks, can edit/accept/dismiss
those proposals, and can still save/find/export when text is missing or ambiguous.
Explicit names and filing choices persist through recognition retries, page edits,
process death and suggestion-rule updates. Existing saved documents can expose
advisory suggestions without silently rewriting their names or memberships.

Primary IDs: **ML-02/03 and UX-03**. Related integration/acceptance: **UX-05/06,
ORG-01/02/03 and DT-02 naming**. Keep existing multi-stack semantics, starter stacks,
custom-stack editing, home visibility and deletion behavior. Existing organization
features need integration/regression coverage, not wholesale rebuilding.

Initial scope is printed English and the current catalog: Bills, Banking, IDs,
House, Medical, Receipts, Starred. Starred remains user-selected. Custom stacks stay
available manually; arbitrary custom names do not imply a trained classifier.
Dates/amounts may inform a title only when supported by source text, but structured
field extraction/editors (ML-04/05), short-name completion (ML-06), learned user
profiles, extra categories, batch filing, cloud AI and desktop are later work.
Backup was outside this delivered naming slice and is now in the active P0 run. A compact local model is an alternative only if measured rule failures justify
its size/licensing/maintenance cost; assess independently sourced artifacts first.

### 1. Inspect reference and current integration, then record decisions

Finish the bounded reference pass above; do not repeat the entire APK audit. Inspect
these current source surfaces under `app/src/main/java/org/stackdocs/app/`:

| Surface | Current behavior | Required attention |
| --- | --- | --- |
| `LibraryDatabase.java` | Schema 4; `title_user_edited`; actual document/stack relations; no suggestion provenance | Add proposal/user-decision state through an additive migration from all supported prior schemas; preserve existing rows and decisions. |
| `LibraryRepository.java` | Import filename/default titles; `saveScan()` marks titles edited; `rename()` marks titles edited; `setMemberships()` replaces selected memberships | Separate generated defaults from explicit user actions for new documents; record title and filing decisions independently; apply suggestions transactionally and update FTS title rows without rerunning OCR. |
| `ScanActivity.java`, `ScanDraftStore.java` | Pre-save name/stack picker; save calls `saveScan()`, unconditional `rename()` and `setMemberships()`; OCR starts after durable save | Track genuine user interaction across recreation/recovery; do not count an untouched default name/empty selection as a deliberate correction. Do not move OCR into unsaved drafts or delay saving on suggestions. |
| `OcrWorker.java`, `LibraryRepository.completeOcr()`, `StackApplication.java` | Revision-safe per-page commits, notifications and launch recovery | Trigger bounded document-level recomputation from current text snapshots without blocking UI/native OCR or accepting stale proposals. |
| `DocumentActivity.java`, `StackPicker.java`, `MainActivity.java` | Details, Rename, manual multi-stack filing, current search snapshots | Present suggestions and correction/dismissal in the existing workflow; preserve normal manual filing and search. |

**Important migration limit:** the legacy title flag cannot reliably distinguish
an untouched scan default from a deliberate name, because current scan save sets
it unconditionally. Preserve all existing names/memberships conservatively; do not
infer consent from timestamps, title patterns or an empty stack list. Add explicit
touch/provenance tracking for new actions, including a deliberate choice of no
stacks. Applying an unchanged default through existing save plumbing is not proof
of a user correction. Do not reset existing flags to make automation appear to work.

Persist new draft interaction/provenance fields compatibly with existing manifests
and activity recreation. Older drafts must remain recoverable with their originals,
names and known choices intact; absence of a new field cannot authorize destructive
renaming/refiling. Applying an accepted proposal is itself a deliberate user choice.

### 2. Implement a small, independent, testable suggestion engine

Start with deterministic local text/phrase/layout-light rules over **current OCR
text**, not merchant APIs or proprietary vocabularies. Prefer a credible heading or
merchant plus document type when supported; produce a short editable title, strip
control/path characters, bound its length and avoid guessing entities or ambiguous
dates. Imported filenames are useful existing names; preserve them until the user
chooses a proposal. Keep the existing date-based scan fallback when no useful name
is supported. Duplicate titles are valid because identity/storage uses stable IDs.

Classification should use multiple useful signals, contradictory evidence and
abstention. Permit multiple appropriate stacks; avoid assigning a category for one
incidental keyword, a generic payment word, an address alone, or a blank/poor OCR
result. Starred is never inferred. Use a small number of ranked proposals with
plain explanations; a rule score is not a calibrated probability. Version the
independently authored rules and record inputs/proposal provenance.

Evaluate all eligible pages with explicit bounded text/memory budgets; prioritize
first-page headings for names without ignoring later-page classification evidence.
Record any truncation. Reuse durable OCR text; do not decode images or rerun native
recognition merely to generate a suggestion. Pending/partial/failed OCR must have
predictable behavior: show progress or clearly partial proposals, abstain when
unsupported, and keep the saved source/manual filing available.

### 3. Persist proposals and decisions before wiring automatic refresh

Use an additive schema change, normally version 5; design the exact columns/tables
from the protocol rather than copying Google's schema. Support direct upgrades from
schemas 1–4 and update old migration-test expectations. Track proposed names/stacks,
rule/input version, pending/ready/empty/failed/stale status, accepted/dismissed state
and independent title/filing user decisions. Decisions must survive restart,
including rejected categories and explicit empty filing.

Snapshot stable document/page IDs, current page order, content/text revisions,
OCR configuration and a digest/revision of the actual recognized text. A successful
retry can change text without changing the page's content revision. Include a
separate title/filing decision revision so a late proposal cannot overwrite a user
action. Heavy analysis runs off UI/repository IO; claim/snapshot/commit are short.
Validate input/rule/decision identity again both on proposal commit and on Apply.
Reject stale work after page/document deletion, edits, removals, new OCR results,
rule changes or newer proposals. Never recreate deleted documents or stacks.

Durably mark dirty work on relevant changes; recover it idempotently on launch.
Manual rename/filing must not trigger OCR. Reopening cannot duplicate proposal or
membership rows. Retrying OCR/suggestions cannot restore a dismissed proposal or
re-add a rejected stack for unchanged evidence. On genuinely changed input, retain
explicit choices; new advisory proposals may be refreshed without auto-applying.
Delete a stack without deleting documents; discard proposals referencing that stack
and never recreate it automatically. Hidden stacks retain existing visibility
semantics and can remain manually selectable.

### 4. Integrate one short review/correction flow

Default this phase to automatically generated **reviewable proposals**, applied by
an explicit user action. Keep manual names/memberships until acceptance; no silent
retroactive rename/refiling of the existing library. A proposal is not an actual
membership until applied. Offer compact editable name/stack choices within existing
Details/filing surfaces; accepting a name and filing may be independent actions.
Allow dismissal and ordinary manual edits, with plain loading/empty/failure states
and retry where useful. Keep rule IDs, model hashes and engine internals out of UI.

Because OCR intentionally begins after durable save, deliver this review in the
saved-document workflow after recognition; keep pre-save name/stack entry fast.
If reference observations support preselection or later product work calls for
automatic application on untouched new documents, document that behavioral change
explicitly with ownership/undo/late-OCR rules before extending this scope.

Apply a chosen title and corresponding document/page FTS title updates atomically;
apply chosen memberships as one transaction. Preserve unrelated manual memberships
when accepting an additional suggestion. If the UI replaces the whole filing set,
show that explicitly in the existing picker and preserve deliberate removals.
Refresh search/stack counts after commits, preserving the current query/filter.

### 5. Naming verification contract (execute remaining checks in final V1–V9)

Use fresh isolated synthetic contexts for migrations, deterministic ordering/race
checks and native/UI fixtures. Do not seed or benchmark the personal library.
Extend the existing authored corpus with independently obtained non-sensitive
receipts, bills, statements, IDs/forms, house/medical documents and general pages;
use at least 30 labeled documents, including multi-stack, ambiguous, blank/poor-OCR
and later-page evidence. Distinguish rule-text fixtures, synthetic OCR fixtures and
actual camera documents; have the user perform physical captures.

| Gate | Required evidence |
| --- | --- |
| Reference behavior | APK/build/locator and handset observations recorded separately; gaps and independent successor decisions explicit. No copied implementation or cloud dependency. |
| Useful suggestions | Report multi-label precision/recall, abstention/coverage, per-category errors and correction rate. Score titles as useful unchanged, useful after small edit, or unhelpful against documented human labels; exact string equality alone is insufficient. Separate real/generated data and held-out examples from rule-tuning cases. |
| User control | Existing names/filing preserved; new untouched default versus genuinely edited name distinguished; accepted/dismissed proposals, deliberate no-stacks and per-field choices survive retries/restart. |
| Stale results | Hold analysis, then rename/refile/edit/delete/remove a page/delete a stack, retry OCR to changed text, or produce a newer proposal. Late success/failure and stale Apply cannot overwrite, resurrect or refile anything. |
| Storage/search | Actual migrations from 1/2/3/4 preserve originals, non-default edits, memberships, legacy text and recovery archives. Applying a title updates title/body search and page context. Actual memberships/counts remain correct without duplicate source copies. |
| Workflow/offline | Saved import and scan → OCR → suggestions → edit/accept/dismiss → search/reopen works locally with no network permission/download. Empty/failed OCR still permits manual name/filing/export. Test failure/retry UI in isolated data; preserve the prior five-step user report. |
| Recovery/performance | Kill during pending/running suggestion work and relaunch; no duplicate jobs/choices. Measure named-device latency, peak/sampled process memory with method/caveats, input size and APK growth; reuse unchanged OCR/native evidence appropriately. |

Functional preservation/no-stale-overwrite gates are mandatory. Numerical usefulness,
classification and latency thresholds remain proposals until agreed; measure a
baseline, document the rubric and recommend thresholds from evidence. Do not label
automation parity accepted based on rule examples, synthetic OCR scores or an APK
build. Missing real-photo/platform/corpus coverage stays explicit and does not justify
claiming completion of all P0.

### 6. Historical naming delivery order and completion record

1. Verify repository/package state and reference resources. Record the focused
   reference observations and concrete local behavior/ownership decisions.
2. Implement/test additive schema, durable proposal state and explicit user-decision
   tracking first. Verify preservation before applying anything to the live library.
3. Implement the independent rule engine and versioned, revision-safe recomputation;
   verify controlled races and failures before connecting automatic work.
4. Integrate saved-document review plus explicit accept/edit/dismiss and ordinary
   manual filing. Verify real OCR end to end on an isolated multipage fixture.
5. Run applicable offline builds/lint and affected scanner/OCR/migration/search tests;
   measure the labeled corpus and record actual quality/performance limitations.
6. Update README, feature matrix, this playbook, handoff and an authored
   `STACK_SUGGESTIONS_EVIDENCE.md` containing reference decisions, schema/protocol,
   corpus/rubric/metrics, operations/tests, preservation and remaining acceptance.
   Commit coherent Stack-only increments. Stop before backup or unrelated P1 work.

## Build, device and data safeguards

The commands/checks in this section are for the final V1–V9 stage. Do not install
unfinished implementation on the personal handset; final isolated checks precede
the canonical update. Preparing test code earlier does not authorize running it
before the scheduled final verification stage.

Project `/home/charlie.day/Projects/Stack`; Git root is its parent. Scope staging and
commits to Stack. Preserve unrelated current edits. Do not stage siblings, `Reference/`, APKs, Google
source/assets/models, SDKs/caches, signing keys, screenshots or personal contents.

At OCR delivery Stack held 8 documents / 14 pages / 8 stacks. The suggestions
upgrade rechecked **6 documents / 12 pages / 8 stacks / 8 memberships**, with equal
pre/post metadata and original/draft/recovery hashes; counts can change with user
activity. Capture current aggregate metadata/original/draft/recovery fingerprints
without exporting values before an in-place upgrade, and verify afterward. Do not
clear/uninstall canonical Stack, recreate Preview, replace its database, reset a
draft, or use the consolidation tool as a schema-upgrade mechanism. Reference Google
Stack is observation-only. Keep test data in isolated `files/checks/` children.

Prefer the existing cached toolchain; paths can expire and should be rediscovered:

```sh
ANDROID_USER_HOME=/tmp/stack-dev/android-user ./gradlew \
  -g /tmp/stack-dev/gradle-cache \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  --offline --no-daemon --console=plain
```

Update Stack using `adb install -r app/build/outputs/apk/debug/app-debug.apk` only
with the available authorized device; instrumentation package is `org.stackdocs.app.test`.
Rebuild affected APKs before running targeted checks. Never clear/uninstall Stack as
test cleanup. Retain no INTERNET permission, bundled first-use data, all supported
ABIs and static native alignment checks. API 24/current Android/16 KiB runtime,
disposable fresh-install offline behavior, broader real-photo quality, storage and
backup acceptance remain open; carry those into later delivery groups.

## Historical progress records

The dated records below describe the versions named in their headings. Older
statements that OCR is pending or Preview is active are historical, superseded by
this current status and [OCR delivery evidence](STACK_OCR_EVIDENCE.md).

## Initial progress record

- Audit complete; APKs preserved and excluded from Git.
- P0 scope adopted through this playbook. Initial implementation includes a native Android module, Home/library views, seven starter stacks, picker/share import, original-file retention, transactional metadata/index updates, manual filing, stack management, local title search, page preview, rename/delete, and PDF save/share.
- Validation on 2026-10-15: `:app:assembleDebug` and `:app:lintDebug` passed with JDK 25, Gradle 9.3.1, AGP 9.1.1, SDK platform 36 and build tools 36.0.0. Lint reports 0 errors and 2 advisory version-update warnings (Gradle and compile-only annotations). No lint baseline or blanket suppression was added.
- Inspected `debugRuntimeClasspath`: ExifInterface 1.4.2 and JSpecify 1.0.0; Kotlin standard-library code is not on the runtime classpath. AndroidX annotations are compile-only. Platform `aapt dump permissions` on the built APK reports no requested permissions.
- SDK and build caches for this session are under `/tmp/stack-dev/`; ignored `local.properties` points at that SDK. A fresh environment must install/provide the SDK as documented in the README. Generated APKs and reports remain ignored.
- Initial and scanner device checks are recorded below. Camera capture/quality, OCR, semantic suggestions, resumable recognition and backup/restore remain pending.
- Next execution order: validate actual multipage capture and camera interruption/permission states, then milestone 3's bundled OCR and durable indexing. Remaining library/device edge cases continue alongside those slices. Full P0 acceptance stays open.

## Initial phone check — 2026-10-16

Device: Pixel 4a, Android 13 / API 33, connected through authorized USB debugging. The current debug APK was installed and updated without clearing application data. Synthetic files were placed under `Download/StackDeviceCheck/`; no personal documents were needed.

Verified through the device UI and the application's own test-data database:

- Successful cold launch and local library initialization; Home/All documents screens render.
- A two-page synthetic PDF imports through the Android picker; both page previews render and page navigation advances to page 2.
- The PDF can be assigned to Bills and Receipts; one document record has two membership records.
- The document, page count and memberships remain after stopping/restarting the process and updating the APK.
- Title search finds the PDF for a single term (`stack`) and multiple prefix terms (`stack-sam`). This does not establish OCR text search.

The check exposed and corrected two compatibility problems in `LibraryRepository`: Android rejects `Pattern.UNICODE_CHARACTER_CLASS`, so the tokenizer now uses explicit Unicode letter/number categories without that flag; FTS4 prefix terms require the star inside the quoted token, and implicit conjunction avoids Android FTS4 builds that interpret the word `AND` as text. Both corrections were rebuilt offline, passed lint, installed and rechecked on the phone. Final lint: 0 errors and 2 version-update notices. The final running app process reported no app errors in its filtered log.

Image-picker automation was attempted but not completed during that initial check. Subsequently, the user reported **Import a photo → Save PDF succeeded with no issues** on this handset. That is user-reported workflow evidence; the agent did not inspect the personal document or exported file. Share/incoming share, rename/delete, stack create/hide/delete, API 24 behavior, airplane-mode first use, interrupted processing, and error/storage limits remain to be exercised. The full P0 checkboxes stay open.

## Scanner development and overnight checks — 2026-10-16

Build: `0.2.0-dev`, database schema 2. All code/resources were independently authored. No reference code, images or models were reused. The only requested permission is `CAMERA`; hardware features are optional so import remains usable on devices without a rear camera.

Implemented path: **Add → Scan a document → Capture page → review → optional crop/rotate/color or removal → Add page → Save scan → choose stacks/export**. Reopen Scan to resume an unfinished draft. Closing the review screen keeps it; Discard explicitly deletes its captured photos. Pages and edits are saved after each successful operation. The name field is retained through activity recreation; reopening a draft in a new session currently uses a date-based name.

Storage and edit behavior:

- Draft JPEGs live in private `files/scan_draft/`; `AtomicFile` protects a versioned JSON manifest. Drafts survive repository startup cleanup. A process death before a new photo's manifest update can leave an unreferenced private JPEG; explicit Discard reclaims such files too. Recovery from arbitrary filesystem corruption is not established.
- Save copies originals into a staged document directory, flushes files, then commits document/pages/index. A unique source-draft ID makes a save retry return the existing document. The draft is discarded only after library commit. Failed or interrupted saves can retain a draft for retry; this does not establish full storage-failure acceptance.
- Page rows store relative source file, original PDF page number, order, crop in upright original coordinates, rotation and color choice. Preview and export use the same rendering pipeline. Editing invalidates that page's future OCR state/index contribution.
- Schema 1 → 2 adds source-file ownership, initial page count and source-draft ID without deleting rows or originals. Removed saved pages are omitted from view/export while original source files remain until document deletion.
- Untouched imported PDFs export byte for byte. Editing/removing pages regenerates an image-based PDF, so existing PDF text, links/forms are lost in that exported copy. The UI explains this before PDF editing; the original remains intact.
- Camera access closes when the activity pauses. Captures already received by the app continue to durable draft storage on the IO executor. Actual camera lifecycle behavior is still unverified.

The connected Pixel 4a was used through **separate `org.stackdocs.app.verification` and `.verification.test` installations**, with synthetic documents. The runner refuses the regular package and uses fresh private directories for data checks. No personal library files were read, cleared or exported; the real camera was not opened. The native UI check uses a pre-seeded synthetic draft, avoiding any camera permission prompt.

Checks cover coordinate round trips/invalid crops; actual `SQLiteOpenHelper` migration preserving title/page/stack/index data; bright-page proposal and whole-frame fallback; multipage draft reload/edit/removal; original-byte preservation; idempotent save; generated PDF page counts; memberships/recovery and FTS4 prefix queries; untouched versus edited PDF output; and the native review → editor Rotate/Apply → Save scan → document-detail flow. See the README for reproducible build/runner commands. These are targeted checks, not full P0 acceptance or a camera quality evaluation.

Final result: **9 check groups passed** on the Pixel 4a / Android 13, including a further pixel-content check of perspective/clockwise rotation, EXIF orientation and color enhancement. `:app:assembleDebug`, `:app:assembleVerification`, `:app:assembleVerificationAndroidTest` and `:app:lintDebug` passed offline with the existing cached toolchain. Lint: **0 errors, 2 advisory version-update warnings**. APK permission inspection reports only `CAMERA`. No runtime dependency was added. Both original reference APK hashes still match the audit, and both remain Git-ignored.

The regular handset app was not updated or launched during these scanner checks. The generated `0.2.0-dev` debug APK is ready for an update using `adb install -r` as documented in the README; no uninstall/data clearing is needed. Temporary verification installations were removed after checking. Install that build before the next hands-on workflow below.

Remaining limits: rear-camera orientation/autofocus/light, actual permission denial/retry, lock/background interruption, real-paper boundary quality, activity/process termination during capture/save, storage exhaustion, large export memory, TalkBack crop editing, API 24 and Android 16 runtime behavior. The crop view supports dragging and keyboard corner movement; complete accessibility is not established. No OCR/naming/classification or library backup is included yet.

### Next hands-on check

Use two non-sensitive pages. Open **+ Add → Scan a document**, allow Camera, capture both pages, correct one crop, rotate one page, try the color choices, save and reopen. Check order and save a PDF. Then leave/reopen an unfinished scan and deny/re-enable camera access. Report orientation, preview distortion, focus or crop problems. The automated evidence above should not be substituted for this camera check.

## Interface alignment — 0.3.0-dev

Following the user's request for an interface close to Google Stack, Home now uses the search card, round category tiles, thumbnail grids, two bottom destinations and floating add menu. Document details use a persistent information sheet; scan review combines icon-based tools, a filled name field and stack selection before save. All UI code/icons were independently authored, with no new runtime dependency or schema migration. See [interface reference notes](STACK_UI_REFERENCE.md).

The current entry path is **floating + → Camera** (or Photo/PDF). In the document viewer, Share is in the toolbar and **Save PDF to device** is in the overflow menu. Tap/swipe the Details handle to expand memberships and added date. The existing nine scanner check groups above were run on 0.2.0-dev; they are not a claim that the changed 0.3.0-dev interface/filing path was tested again. Full P0 checkboxes remain open.

## Automatic scan cleanup — 0.4.0-dev

On 2026-10-16 the user reported that camera capture and adding a document worked correctly on the Pixel 4a. This is user-reported evidence, not full camera acceptance. The user also described Google Stack's automatic page correction and default black-and-white output with a color override. This runtime recollection supplements the static audit; it does not establish the original algorithm or every preset's behavior.

New camera pages now use an independent edge/line quadrilateral proposal, falling back to the existing bright-region detector and then the whole frame. The renderer applies flat-page perspective correction followed by local adaptive thresholding to black and white. Color → Original color keeps crop/rotation while restoring the untouched source's color; Medium/Bright color choices remain available. Choices are per page and persist through draft/save. Existing pages and imported documents retain their current settings. No dependency, model or schema change was required; no Google implementation was copied.

Image decoding now retains enough source pixels to reach the requested preview size before scaling exactly to the bound, rather than prematurely rounding the decode sample upward. Edited PDF export uses up to 3,200 pixels on the long edge instead of 2,400. Originals remain unchanged. Export resolution is still bounded; larger export memory needs measurement.

The user supplied an imported Google Stack document and a new camera scan for an on-screen comparison. Temporary screenshots were explicitly authorized, kept outside the repository and deleted after inspection. The new scan showed a blue cast, uneven paper illumination and softer text; the reference had stronger contrast. They are different pages, so the observation is not a controlled resolution or quality benchmark. No medical contents are transcribed into project documentation.

Build evidence: `:app:assembleDebug` and `:app:assembleVerification` succeeded offline. Stack Preview was updated in place without clearing its library or drafts; the regular installation was left unchanged. The handset relocked before a visual review of the new processing, so improved output on that particular page is not established. The existing scan's edit settings were not changed. To try cleanup on an existing scan, open Edit this page → Color → Black & white → Apply; new camera captures use it automatically. No new camera capture was performed by the agent.

Remaining quality gates: paper edges on varied backgrounds, faint/small text, severe shadows, focus/motion blur, processing latency and peak memory. Perspective correction does not flatten curved pages. Binary cleanup can lose low-contrast detail or photographs; Original color remains available. The historical nine scanner checks predate this algorithm; no automated tests were added or run for this update.

## Same-page runtime comparison — 0.4.1-dev

The original `com.area120.paperwork` application is installed on the test Pixel 4a: version `1.231.431385252.release`, code 71588, matching supplied build B. The user displayed a Google Stack scan of the same printed page captured in Stack Preview. Both saved results were inspected on screen with authorized temporary captures. Google Stack showed smoother, thinner letter edges and cleaner paper. Version 0.4.0's binary threshold produced thick strokes, speckles and black strips from surrounding background. These are observations of one physical page captured separately, not controlled acquisition, OCR or quality measurements. The original application's selected enhancement preset and processing internals were not established.

An unsaved inspection of our original-color page and corner overlay showed that the page occupied only part of the camera frame and some crop edges extended into the surrounding surface. That inspection was cancelled. The renderer previously reduced the entire frame to the output bound before cropping, leaving too few pixels for the page itself.

Version 0.4.1 replaces binary thresholding with local paper-brightness normalization and a continuous grayscale tone curve. The **Black & white** choice retains intermediate gray pixels around strokes. Crop-aware decoding retains more original detail, with a 4,096-pixel input bound for current preview/export sizes and the existing 1,600/3,200-pixel output bounds. Automatic corners refine coarse lines toward nearby paper/background transitions, consider the bright-region proposal alongside line candidates, and reject proposals with broad areas of surrounding surface inside the sheet. No dependency, schema change or proprietary implementation is involved. Existing originals and per-page settings are preserved; a saved crop is only replaced through an explicit editor action.

The revised renderer was inspected on the same saved scan without changing its edit settings: letters were visibly smoother and black speckles were reduced. Some gray paper texture and the existing crop's dark border remained. A subsequent unsaved automatic-crop proposal included surrounding surface; it was cancelled and prompted the additional candidate checks above. Both final APK variants built offline, and Stack Preview was updated in place. The final corner checks and tone-curve adjustment were not visually accepted before the handset moved to Settings. No saved crop/color settings were changed, and the original Google Stack application was not edited. Authorized temporary captures were deleted after inspection.

These observations are limited to this handset/page and do not establish Google-quality parity. Faint text, halftones/photos, colored/dark paper, severe shadows, difficult boundaries and memory on lower-end devices remain open. No automated tests were added or run for this update. The historical static audit remains unchanged. The user asked about open-source scanning libraries; the [scanner reference shortlist](STACK_SCANNER_OPTIONS.md) records verified upstream capabilities, dependency constraints and a recommendation to study OSS Document Scanner and OpenCV before further detector tuning.

## Verified scanner-quality pass — 0.4.2-dev

The focused pre-OCR pass reviewed actual OSS Document Scanner, ScanLibrary and CVScanner processing files and licenses at pinned revisions; [research notes](STACK_SCANNER_RESEARCH.md) explain the decision to keep independently authored Java processing. No scanner library, native binary, model or runtime dependency was added. The user additionally requested autocrop and both perspective/curved-paper correction.

Automatic corners now share boundary validation across multiple threshold/line candidates, use sustained edge/contrast and interior checks, and fit accepted sides to local transitions. A failed editor Auto crop preserves current manual corners. Paper cleanup denoises its illumination estimate, closes rather than only dilates it, and retains a wider continuous gray range. Opt-in **Flatten** previews/corrects gentle horizontal text bows after perspective correction; arbitrary folds, curled book pages and horizontal foreshortening remain unsupported. Originals and saved per-page choices remain authoritative.

Pixel 4a/API 33: **32 synthetic runner groups passed**, ending `INSTRUMENTATION_CODE: -1`. Fixed sources yield 7/8 accurate crops (all seven below 0.25% diagonal corner error) and one safe touching-object fallback, versus 6/8 usable baseline crops with two surrounding-surface failures. Blank/L/circle negatives are rejected. Blank-paper near-white coverage is 99.814% / 99.890% / 99.391% at 800/1,600/3,200, with 100% of sampled small-text stroke cores retained, continuous edge tones and faint strokes present. Gentle ±14-pixel sine bows reduce measured baseline spread from 10 pixels to 2/0 pixels; unsupported severe/inconsistent/blank/straight examples are left unchanged. See [quality evidence](STACK_SCANNER_QUALITY.md) for definitions, baseline values, reproducible fixtures and practical limits.

Storage checks include actual schema 1 → 3 and 2 → 3 upgrades preserving non-default corners/rotation/color, title, text and memberships. Schema 3 adds only `pages.flatten` with default 0. Old draft JSON without flatten loads unchanged; a selected choice survives draft reload and library save. The existing original-byte, multipage order, idempotency, PDF passthrough/regeneration, EXIF/rotation and FTS4 checks pass. Untouched PDFs still export byte for byte.

The old runner's UI path discarded Preview's draft. It has been removed; all synthetic writes use fresh isolated child contexts. Preview was updated in place with `adb install -r`, without clearing/uninstalling, camera capture or library UI seeding. Aggregate SHA-256 of existing document/draft files matched before/after; private originals were not copied off the device. The regular app and original Google Stack were not modified.

Final `assembleDebug`, `assembleVerification`, `assembleVerificationAndroidTest`, `lintDebug` and runtime dependency inspection succeeded offline. Lint: **0 errors, 7 warnings**, documented without suppression. APK permissions: only CAMERA; automatic backup disabled; zero native `.so` files. Verification APK grew 9,861 bytes to 297,402 bytes. Processing is local and asynchronous; 3,200-pixel cleanup costs about 1.6 seconds on this handset. Heap snapshots include test fixtures and do not establish peak production memory. The cached build logs and baseline/final runner logs are under `/tmp/stack-scanner-*`; no generated/private content was committed.

This completes the bounded research/implementation/evidence slice before OCR. P0 checkboxes remain open for real-photo/editor visual acceptance, broader curved-paper quality, faint/photos/lighting corpus, camera/lifecycle/storage failures, supported platform coverage, memory profiling and first-install airplane-mode walkthrough. OCR, recognition queues, semantic classification and backup remain subsequent work.

## Single-app consolidation — 0.4.3-dev

After approving Preview's functionality, the user requested one app. The canonical `org.stackdocs.app` installation now runs that code and retains all 8 documents, 14 pages and 8 stacks from both libraries. Every source hash and metadata row was verified by phone read-back, with both original private recovery archives retained before Preview/test packages were removed. See [consolidation evidence](STACK_CONSOLIDATION.md). No scanner algorithm, runtime dependency or schema changed; the older regular library's schema 1 was normalized using the existing migration.

The Preview build type/label override is removed. Build/update `assembleDebug`; the isolated runner uses `assembleDebugAndroidTest` and targets the canonical app without seeding its real library/draft. Six offline consolidation-tool checks and the 32 scanner groups pass. Offline APK/test/lint builds pass; lint retains 0 errors and 7 warnings. The single Stack main activity launches, only CAMERA is requested, and the existing camera grant is retained. OCR remains subsequent work.

## Technical references

- [AGP 9.1 compatibility](https://developer.android.com/build/releases/agp-9-1-0-release-notes) for the pinned AGP/Gradle/SDK requirements.
- [Android document access](https://developer.android.com/training/data-storage/shared/documents-files) for provider-based import/export.
- [SQLiteDatabase](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase) and [SQLite FTS3/FTS4](https://www.sqlite.org/fts3.html) for local transactions and search.
- [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer) and [PdfDocument](https://developer.android.com/reference/android/graphics/pdf/PdfDocument) for local PDF handling.

## Offline OCR and document search — 0.5.0-dev

Milestone 3 is implemented with bundled printed-English OCR, durable revision-safe
page processing, launch recovery, import/save/existing-page backfill, FTS4 body
search, stable matching-page snippets/navigation and failure retry. Schema upgrades
from 1, 2 and 3 to 4 are additive. The approved scanner/render/export pipeline and
manual titles/memberships are preserved. See [OCR evidence](STACK_OCR_EVIDENCE.md)
for exact device checks, synthetic corpus metrics, dependency/native audit and
remaining gates. This supersedes earlier statements that OCR is unimplemented.
Broad automation/P0 acceptance remains open; naming/classification follows later.

## Planning checkpoint — next P0 group

The P0 front matter/milestone statuses and detailed naming/classification/filing
work order were refreshed after OCR delivery and the user's five-step workflow
confirmation. Both local reference APKs were inventoried and their SHA-256 hashes
match the audit. The installed reference is explicitly carried into the
[next-session prompt](STACK_NEXT_SESSION_PROMPT.md); handset/UI availability must
be rechecked then. This update changes documentation only: no suggestion engine,
app/schema/draft code, package installation or runtime behavior changed, and no new
classification quality result is claimed.

## Local naming, classification and filing — 0.6.0-dev

Implementation **378b0ed**, schema 5, supersedes the planning-only checkpoint above.
Delivered durable proposal/user-choice provenance before automatic work, independent
local phrase rules, revision/generation-safe recomputation and saved-document
name/filing Accept/Edit/Dismiss. No title or membership changes occur until an
explicit choice. Starred/custom stacks remain manual; draft recovery and existing
values are preserved.

Seven suggestion groups, 34 scanner/migration groups, eight OCR groups (both real
workers), isolated production review UI and a force-stop/new-process held-claim
check pass. The 52-document/55-page synthetic corpus scores 97.674% precision /
95.455% recall overall, but fresh 12-document precision is 88.889% and broad
acceptance stays open. The current 6-document/12-page library retains matching
metadata and originals/draft/recovery hashes through the in-place update. See
[suggestion evidence](STACK_SUGGESTIONS_EVIDENCE.md) for source, rubric, timing,
reference observations, limits and reproduction. No portable backup or P1 work.

## P0 workflow/naming follow-up — 0.6.1-dev

Implementation **4afbefa**, schema unchanged at 5. The user reported appropriate
stack suggestions but a street-address name on a veterinary bill. Rule 4 rejects
common address/contact/metadata candidates, prefers issuer/type headings and
abstains safely. Existing choices remain authoritative. The user subsequently
reported all four requested corrected-name/edit/filing/reopen/search/empty-filing
steps passed; dismissal was outside that check. Shared name editors retain invalid/failed-save text, picker Save clears
invisible deleted IDs, and measured action widths support 320 dp/2x text.

Eighteen applicable runner groups pass across naming, existing stale-decision
protection, injected rollback/recovery, external provider/grant boundaries, editors/
large text/recreation, saved review and a six-page native-worker/search/export
fixture. The 84/87 authored corpus passes 22 structural name expectations; its
separated eight-case precision is 90% and agent title correction need 14.286%, so
broad automation acceptance remains open. Current **7/13/8/10** document/page/stack/
membership counts and preservation hashes match pre/post in-place upgrade. See
[workflow evidence](STACK_WORKFLOW_EVIDENCE.md). No backup or P1 work was started.
