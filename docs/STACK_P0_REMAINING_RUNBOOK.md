# Remaining P0: implement continuously, verify at the end

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


Planning checkpoint: 2026-10-16, after **a81dd24**. Starting app:
**0.6.1-dev / code 10 / schema 5**, implementation **4afbefa**.
This is an execution plan, not evidence that the remaining work has passed.

## Authorization and execution contract

The user requested **all remaining phase 0 items**, with **all verification steps
saved for the end of the run**, and continuous work without milestone approval
stops. This replaces the earlier boundary that stopped before portable backup.
**EX-03 portable backup/restore is now in scope.** P1, structured-field extraction/
editors, notes UI, learned profiles, batch actions, desktop and cloud integrations
remain out of scope. Existing stored fields must survive backup without adding P1 UI.

Read the current handoff, feature matrix and relevant evidence; then implement
remaining P0 gaps in dependency order. Treat the 30 P0 matrix rows as the scope
contract. Audit the source to distinguish existing behavior from missing behavior;
do not rebuild approved functionality just because its full acceptance box is open.
Use reasonable implementation choices and record them rather than requesting
routine design approval. Continue to the next independent item when a resource or
user-only action is unavailable. Do not end the run at a phase boundary, after a
plan, after writing tests, or while useful authorized work remains.

**Implementation first:** source reading, targeted read-only reference research,
implementation, test/fixture authoring, documentation and scoped commits may proceed.
Keep an execution ledger here with completed changes, pending work and final-check
commands. Do not run builds, lint, test suites, instrumentation, device walkthroughs,
corpus measurements, APK audits or acceptance reviews between implementation groups.
All such verification belongs to **V1–V9 below**. Do not install unfinished builds
on the personal handset. Read-only repository/tool/device discovery is preparation,
not acceptance. No application code is changed by this planning checkpoint.

**Final verification:** after the implementation sweep, perform V1–V9, fix failures
and rerun affected checks within that final stage. A build needed to run final tests
belongs there. Commit implementation increments with an explicit “verification
pending final pass” record; report only actual results. Do not label an untested
increment verified. Keep progress updates concise and continue working after them.

Preserve scanner approval, five user-reported OCR checks and all four 0.6.1 naming/
filing checks recorded in [workflow evidence](STACK_WORKFLOW_EVIDENCE.md). The user
reported “all worked” for fresh bill name review, independent name edit/filing
acceptance, reopen/name search and persistent explicit empty filing in All documents.
Dismissal was outside those four checks. These passes do not establish broad
corpus quality, every platform, or all 30 matrix conditions.

## Implementation sweep

| Order | Work | Required result before entering final verification |
| --- | --- | --- |
| I1 | Inventory and workflow/error completion | Inventory all rows below; fix missing navigation/import/share/save/camera lifecycle and actionable error states, keeping approved behavior. Author targeted fixtures and checks without running them. |
| I2 | OCR, naming, filing and search completion | Close uncovered failure/retry, recovery, stale-result and choice-persistence paths; improve demonstrated quality gaps without automatic overwrites. Prepare a fixed evaluation corpus/rubric before final measurement. |
| I3 | Portable archive protocol and consistent snapshot | Specify a versioned, independently authored archive and durable export path; include complete saved-library state and original bytes, never just rendered PDFs or a live database copy. |
| I4 | Restore protocol and user controls | Validate/stage/commit safely, restore into an empty library, preserve a nonempty library through additive import, recover interruptions and expose local Backup/Restore actions. |
| I5 | Integration, documentation and final-check preparation | Finish offline/error/accessibility behavior across the new flows; author remaining migration/archive/worker/UI checks, update provisional docs and prepare V1–V9. No verification yet. |
| V1–V9 | One final verification and repair stage | All feasible checks complete; one final user/resource checklist in STACK_P0_FINAL_EVIDENCE.md. |

### I1. Local library, capture/import, editing and export

Inspect `MainActivity`, `LibraryActivity`, `DocumentActivity`, `ScanActivity`,
`CameraController`, `ScanDraftStore`, page editing/rendering, `LibraryRepository`
and `ExportProvider`. Retain working flows; implement missing behavior found during
source review rather than speculative redesign.

- Keep Home/All documents, search/add, current query/navigation and multi-stack
  identity coherent after saves, edits, deletion, restore and activity recreation.
  Empty/loading/no-results/failure states need distinct useful actions.
- Preserve private original bytes before releasing incoming grants. Handle image
  orientation and multipage PDFs, absent/lying MIME/name/size, revoked grants,
  interrupted reads, invalid/unsupported documents and repeat/canceled intents.
  Do not broaden import formats or add batch import solely for this run.
- Preserve unfinished camera drafts and explicit naming/filing through permission
  denial, backgrounding, recreation, process interruption and save retries. Avoid
  duplicate save/capture work; retain approved crop/rotation/cleanup/flatten behavior.
- Keep page removal and confirmed document/stack deletion scoped to their targets;
  invalidate derived search/proposals and retain unrelated documents and files.
- Keep Save PDF and sharing usable offline. Untouched PDFs must remain byte-identical;
  edited/image output must preserve page order and saved edits. Handle provider
  cancellation, denied/short/failed writes, recipient grants, export cleanup and
  larger documents without unbounded bitmap retention or blocking the UI.
- Retain recoverable name editors, explicit empty filing and hidden/custom stack
  access. Complete accessibility labels, focus/checkbox state, error announcements,
  large-text layouts and actionable crop controls where source review finds gaps.

### I2. Local processing and suggestion quality

Inspect actual queue/index/decision code and existing evidence first. Preserve
bundled printed-English OCR and separate bounded workers; no engine replacement
or scanner redesign is implied.

- Finish missing OCR failure/retry UI and retry/relaunch paths; durable ingestion
  precedes processing, current successful text survives failed retries, edits remove
  stale text, and native late results cannot overwrite newer revisions/deletions.
- Retain durable suggestion provenance, independently reviewed name/filing controls,
  untouched-default versus explicit-choice tracking and dismissal/empty-filing
  history. Rule changes, retries, refiling and deletion cannot override choices.
- Address known contextual Medical false positives, receipt/mortgage cue misses
  and unsupported title specificity only with independent evidence and clear rules.
  Keep address/contact guards, category independence, safe abstention and manual
  filing. Preserve the date-based default/fallback required by ML-02; distinguish
  a generic fallback from extracted document facts and never silently rename an
  existing document to satisfy that requirement.
- Prepare independently sourced non-sensitive documents with reusable licensing
  or authored fixtures, keeping real scans, rendered templates and rule-text cases
  separate. Include negatives, ambiguous/multi-stack cases, blank/failed/poor OCR,
  later-page evidence and short/address-like headings. Record licenses/provenance.
  Do not access personal bills or fabricate external human labels.
- Freeze a labeled evaluation split/rubric before V5. Separate tuning from evaluation;
  if a measured failure is fixed during final verification, disclose that exposure
  and use fresh cases when available. Existing 84 documents are regression evidence,
  not an independent blind sample. Numerical quality targets remain provisional
  until user agreement; that does not block implementation or final measurement.

### I3. Portable backup format and export

Implement EX-03 as a user-initiated local archive through Android storage access.
Use platform/compatible existing dependencies where practical. Define the format
before wiring the UI and document schema/version support and explicit limits.

- Use a versioned manifest plus relative, allowlisted original-file entries, lengths
  and SHA-256 digests. Preserve stable document/page IDs and order, source linkage,
  metadata/added dates, saved crop/rotation/color/flatten settings, stack catalog and
  visibility/memberships, title/filing origins and revisions, current OCR text and
  its configuration/revisions, and suggestion proposals/choice history as applicable.
  Preserve any existing supported stored notes/details without inventing P1 editors.
- Build a coherent snapshot through repository serialization and an explicit snapshot
  lifetime so edits/deletion/worker commits cannot mix revisions or remove source
  files mid-export. Stream source bytes on background IO. Do not export a raw live
  SQLite/WAL set or include machine-specific absolute paths, caches or worker claims.
- Declare the draft policy: saved-library backup does not consume, reset or replace
  the local unfinished scan. If drafts are excluded, state that plainly in the
  backup UI/docs; do not imply that “library backup” protects unsaved work. Preserve
  `files/consolidation_recovery/` locally and exclude those private recovery archives
  from normal portable exports unless separately and explicitly requested.
- Provide progress, cancellation and retry. Release temporary resources on completion/
  failure; a failed destination write must never change the live library. Report
  completion only after stream flush/close succeeds; provider-dependent atomicity
  must be documented rather than promised. Identify incomplete outputs clearly.
- Document that the archive contains private original documents and metadata. Use
  only user-selected destinations, retain no permanent URI grants without need,
  and make no password/encryption or cloud-backup guarantee that is not implemented.
  Do not add archive encryption as an unrelated feature.

### I4. Restore, recovery and local controls

Implement restore as a separate validate → review → commit flow. Choosing a file
or viewing its summary must not mutate the library.

- Fully validate supported archive/manifest versions, paths, file types, IDs,
  uniqueness, referential integrity, page order, saved settings, byte lengths and
  digests. Reject traversal/absolute paths, duplicate entries, links, missing data,
  malformed metadata and unsupported future versions. Bound entry count, manifest
  size, expanded bytes and work; stream with cancellation, avoid ZIP bombs and
  document limits. Insufficient storage must fail without losing existing data.
- Stage beneath a fresh operation directory; never extract directly into live
  originals. Validate before commit. Use a durable operation journal/commit protocol
  across file installation and the SQLite transaction; restart must recover an
  interrupted operation without deleting preexisting files, leaking visible partial
  documents or duplicating already committed records. Make retry semantics explicit.
- For an empty library, reconstruct document/page relationships and all supported
  state. Built-in starter stacks must not duplicate. For a nonempty library, use
  additive restore with deterministic collision mapping and a reviewed summary;
  preserve existing document names/memberships/IDs and custom/hidden stacks. Never
  replace the live database, wipe the library or expose a destructive reset option.
  Repeated restore must have documented idempotent/import-again semantics and must
  not silently overwrite existing edited documents to resolve an ID collision.
- Rebuild derived FTS from authoritative names and compatible OCR text. Normalize
  transient worker states/claims; queue only missing/stale work after durable commit.
  Preserve accepted/dismissed/explicit-empty choices. Reconcile engine/rule versions
  without presenting archived stale proposals as current or allowing a pre-restore
  worker/action to modify restored rows. Keep active jobs coordinated with commit.
- Add discoverable Backup library and Restore library actions in the existing
  interface. Show archive scope, summary/conflicts, commit/cancel, progress and
  actionable failure/retry. Cancellation leaves existing library and draft intact.
  Keep automatic OS cloud/device backup excluded and explain intentional portable
  backups. No account, backend, model download or Play Services requirement.
- Author isolated round-trip, malformed/archive-limit, collision, interrupted-write/
  restore, retry and stale-worker checks now; run them only in V1–V9.

### I5. Integration and continuity ledger

Use additive migrations only when persistent changes require them; support every
existing schema directly to the new version without data loss. Version application,
archive and schema separately. Keep legacy drafts readable and preserve all recovery
archives. Draft/cache formats are not the portable backup protocol.

Update README and user-facing descriptions to describe implemented behavior precisely;
mark results pending until the final run. Record archive compatibility, limitations,
source licenses, operation recovery decisions and reproducible commands. Commit
coherent Stack-only increments, preserving unrelated edits. If context compacts,
resume from the ledger; do not restart completed implementation or end at a checkpoint.

| Item | Starting state | Execution record |
| --- | --- | --- |
| I1 workflow/error completion | Existing implementation plus remaining coverage gaps | Completed; retained approved behavior, closed concrete retry/export/import/navigation gaps; final checks recorded in STACK_P0_FINAL_EVIDENCE.md. |
| I2 processing/quality completion | Implemented; broader quality/failure UI gaps remain | Completed; rule 5 and frozen corpus measured in final stage, human quality gates remain explicit. |
| I3 archive/export | Not implemented | Completed; format 1 coherent pinned snapshot and local streaming export. |
| I4 restore/controls | Not implemented | Completed; validated staging, additive review, journal/marker recovery and Stack-style controls. |
| I5 integration/docs | This plan saved | Completed; additive schema 6, fixtures, user descriptions and reproducible evidence. |
| V1–V9 | Deferred to end | All feasible checks complete; one final user/resource checklist in STACK_P0_FINAL_EVIDENCE.md. |

## Final verification stage — run only after I1–I5

This is the sole scheduled verification stage. Prepare fixtures during implementation;
execute them here. Fix final-stage failures and rerun relevant checks, keeping the
final evidence tied to the exact resulting commit/APK. Complete all independent
checks even if some resources or user actions are unavailable. Record unavailable
checks as **not run**, failures as **failed**, and user reports separately from
agent-observed checks. Never promote static inspection into runtime evidence.

### V1. Build and migration foundations

Use the cached SDK/Gradle toolchain where available; first build all production and
instrumentation artifacts offline, then run lint, migration probes and archive unit/
host checks. Resolve compilation/lint errors; explain residual warnings. Exercise
all supported old schema → new schema upgrades with nondefault edits, legacy titles,
text, provenance, stack relations and drafts. Compare originals and metadata in
isolated fixtures; confirm failure rolls back and triggers/indexes remain correct.

### V2. Isolated workflow, lifecycle and storage

Run applicable existing scanner, OCR, suggestion and workflow suites plus new checks.
Cover independent accept/edit/dismiss, explicit-empty filing, name validation/failed
save, hidden/custom/deleted stacks, idempotent save, navigation/search/error states,
OCR failure/retry UI and stale success/failure/Apply races after edits, retry, refiling
and deletion. Exercise recreation and process termination during ingest, draft save,
OCR, suggestions and export; verify recovery and no duplicates. Clear only disposable
synthetic test state. Exercise image orientation, all supported formats, multipage
order, private copies, retained edits and exact untouched-PDF export bytes.

Use real synthetic provider/recipient boundaries for incoming shares, system picker/
chooser/destination flows, permission revocation, denied/interrupted reads, short/
failed writes and cancellation. Verify read-only URI grants and expiry/revocation,
no path traversal and no accidental access to unrelated exports. Run transaction/
IO failures and bounded disk-full scenarios only in disposable storage/emulators;
never fill the personal handset. Injected failures are not actual power-loss evidence.

### V3. Backup/restore acceptance and hostile input

Round-trip an isolated library with multiple/hidden/custom stacks, nondefault page
edits, multipage imported originals, manual/accepted/edited/legacy names, explicit
empty filing, dismissals and choice history, compatible OCR and pending/failed work.
Compare source hashes and every supported authoritative field before/after; prove
later-page search, snippets, manual editing, export and processing recovery work.
Do not require transient claims/cache/index bytes to match. Confirm an active local
draft and recovery archives are untouched by backup/restore.

Exercise empty-library restore, nonempty additive restore, ID/catalog conflicts,
repeated archives, future/old formats, malformed manifest, duplicate/path/link entries,
missing/changed files, limits/ZIP bomb refusal, storage failures and cancellation.
Kill at each staged/file/transaction/journal boundary and relaunch; prove existing
data survives, no visible partial restore, no duplicate retry and stale workers/
actions cannot modify restored rows. Test export snapshot consistency under concurrent
edit/delete/OCR and importer consistency under incoming worker completion.

Use only synthetic archives. Do not create/export/restore a personal-library backup
for this acceptance run, and never reset canonical Stack to obtain an empty library.

### V4. Offline first use and platform/native coverage

Use a disposable installation/device context with no prior library/model provisioning
and no network/account/Play Services dependency. Run capture/import → OCR → name/
classify → manual corrections → later-page search → export → archive → restore →
reopen, without downloads. Test camera denial/retry/background/rotation/recovery on
available test hardware; only the user performs real-world physical captures.

Run API 24, the current supported Android target/runtime, available shipped ABIs and
an actual 16 KiB runtime when available. Record missing devices/runtimes explicitly.
Audit final APK permissions, backup flags, bundled model/license/hash, dependency
changes, all supported native libraries/alignment, APK size and offline packaging.
Do not claim 16 KiB runtime or account-free cold-start success from manifest checks.

### V5. Recognition, scanner and suggestion quality

Measure the frozen corpus on the final code: OCR CER/WER and later-page retrieval;
multi-label category precision/recall, coverage/abstention and per-category confusion;
title usefulness/correction/dismissal need with the documented rubric. Report sample
counts, provenance, generated versus independently sourced data, tuning/evaluation
separation and title reviewer identity. Preserve the original regression sets.

For scanner quality, use approved fixed-source fixtures and available non-sensitive
real captures across crop/rotation, faint text/photos, colored paper and lighting.
Separate lack of supported severe-curve behavior from regressions. Do not claim
Google parity or broad human acceptance from synthetic exact matches. If external
human labels or agreed targets are absent, record metrics and provisional targets,
continue all other checks, and leave that acceptance gate open for the final review.

### V6. Performance and accessibility

Measure final worker latency, queue recovery, UI/IO responsiveness and sampled/peak
memory with named hardware, input sizes and sampling caveats. Include larger edited
PDF export, archive snapshot/export and restore; compare baseline where controlled.
Exercise bounded inputs and cancellation without freezing the UI or retaining every
rendered page. Record APK growth and limits. Check narrow screens, 2x text, focus,
labels, touch targets, checkboxes, error announcements and accessible crop controls;
use isolated configuration overrides rather than changing personal settings.

### V7. Canonical in-place update and preservation

Only after the disposable/isolation checks pass, recheck authorized handset access
and capture current aggregate metadata/original/draft/recovery fingerprints without
exporting private values. Historical 7/13/8/10 counts are not a target; user activity
can change them. Install only with `adb install -r`, never uninstall/clear/reseed,
replace the canonical database or recreate Preview. Read back preservation aggregates
and reopen Stack; investigate any unexpected difference before further writes.
Remove only exact synthetic operation directories/files and the test APK. Do not
run destructive migration/restore/power-loss checks on the personal library.

### V8. One final hands-on checklist

Collect only the user-dependent checks still lacking evidence at this point: physical
camera/permission/lifecycle scenarios, dismissal persistence, meaningful title/category
review on a varied non-sensitive sample, system chooser/provider behavior that could
not be automated, and accessibility review. Reuse the approved scanner, five OCR
passes and four naming/filing passes; do not ask the user to repeat them without a
regression reason. Provide one short consolidated checklist at the end. If the user
is absent, finish independent work and mark these gates awaiting user evidence.

### V9. Evidence, matrix and completion record

For each row below, record final implementation commit, relevant checks/results,
evidence path and any remaining gate. Update README, feature matrix, playbook,
handoff and next-session prompt; commit final coherent Stack-only increments.
Record actual counts/metrics/warnings and unavailable checks. Mark a P0 checkbox
only when its complete acceptance condition has evidence; otherwise keep it open
with the specific reason. Finish with implementation status, verification results,
remaining user/resource gates and commits. Do not drift into P1 to avoid an open gate.

## Complete P0 coverage map

Every P0 row in the feature matrix is accounted for here; existing functionality
is retained unless source review or final failures establish a concrete defect.

| ID | Implementation obligation / remaining gap | Final verification |
| --- | --- | --- |
| UX-01 | Home/All documents, navigation and restored-library refresh | V2, V3, V7 |
| UX-02 | Prominent search/add with camera/image/PDF, no setup dependency | V2, V4 |
| UX-03 | Independent name/filing review; automation failure preserves saved document | V2, V5, V8 |
| UX-04 | Multipage capture/review/removal, stable page order/draft recovery | V2, V4, V8 |
| UX-05 | Preview/details title, stacks, added date, available text/status; no P1 field editor | V2, V3, V6 |
| UX-06 | Empty/loading/error/retry/cancel for processing/import/export/backup/restore | V2, V3, V6 |
| SC-01 | Offline camera and permission/interruption recovery | V2, V4, V8 |
| SC-02 | Oriented image import with private original retention | V2, V4 |
| SC-03 | All PDF pages/OCR; retain original PDF | V2, V3, V4 |
| SC-04 | Receive another app's image/PDF while grants are valid | V2, V4 |
| SC-05 | Retain approved corner proposal/manual perspective fallback | V2, V5, V6 |
| SC-06 | Reopen crop/rotation/removal without source loss | V2, V3, V5 |
| SC-07 | Retain cleanup/presets/original/settings | V2, V3, V5 |
| ML-01 | Bundled English per-page OCR, offline first use and recovery | V2, V4, V5 |
| ML-02 | Useful editable name, safe date fallback and preserved explicit names | V2, V3, V5, V8 |
| ML-03 | Independent category proposals, ambiguity/manual controls | V2, V3, V5, V8 |
| ORG-01 | Multiple memberships, one document identity | V2, V3 |
| ORG-02 | Create/show/hide/delete stacks without deleting documents | V2, V3, V6 |
| ORG-03 | Preserve independent starter catalog/icons and documented reference mapping | V2, V3, V9 |
| SR-01 | Offline title/full-document/later-page search after restart/restore | V2, V3, V4, V5 |
| SR-02 | Useful snippet and stable matching-page/document context | V2, V3, V5 |
| SR-03 | Empty/no-results/query failure/clear/retry during indexing | V2, V3, V6 |
| DT-01 | Durable originals/order/edits/metadata/memberships; safe staging/cache eviction | V1, V2, V3, V7 |
| DT-02 | Durable rename and scoped confirmed deletion/search/file cleanup | V2, V3, V7 |
| DT-03 | Commit before processing; interrupted indexing/work resumes without duplicates | V1, V2, V3, V4 |
| EX-01 | Ordered offline PDF share through temporary read grants | V2, V4, V6 |
| EX-02 | User-selected PDF destination; cancellation/failure and retry | V2, V4, V6 |
| EX-03 | Versioned original/metadata archive; empty restore and safe additive restore | V1, V3, V4, V6 |
| SEC-02 | Account/download-free offline first use through restore | V4 |
| SEC-03 | Explicit OS backup exclusion; intentional portable-backup scope/protection docs | V3, V4, V9 |

## Data, reference and repository safeguards

Canonical package is **org.stackdocs.app**. Preserve all originals, approved scanner/
OCR settings, drafts, deliberate names/memberships and `files/consolidation_recovery/`.
Automated mutation and archives use isolated synthetic `files/checks/` or disposable
installations. The existing export-provider check's one fresh UUID-named synthetic
cache file is a narrow exception; never inspect/delete unrelated exported documents.
Never log/export private contents to implement or benchmark these features.

Both Google Stack APKs remain ignored in `Reference/`; `com.area120.paperwork`
was installed as build B / 71588 on the Pixel 4a. Reuse the audit and recorded older-
document observation; inspect read-only only to resolve a specific gap. Preserve
reference documents/settings/accounts, do not call its backend or reuse proprietary
code/models/assets/fonts. Physical capture/secure unlocking requires the user; an
unavailable reference never blocks independently authored local implementation.

The Git root is the parent of Stack. Commit Stack-only increments. Preserve the
feature matrix's existing formatting-only OCR-row edit, unrelated sibling changes
and ignored resources. Do not commit APKs, personal archives/screenshots, decoded
reference material, SDKs/caches or keys. If an action truly needs unavailable approval
or credentials, defer that action, record why and continue every unaffected item.

## Active execution ledger — 2026-10-16

Implementation sweep is in progress; **all verification remains pending the final
V1–V9 stage**. Source inventory: UX-01–06, SC-01–07, ML-01–03, ORG-01–03,
SR-01–03, DT-01–03, EX-01–02 and SEC-02–03 already have production paths.
Retain the approved scanner, OCR, explicit-choice and name/filing behavior.
EX-03 is the substantial missing implementation. Broad acceptance boxes represent
runtime/quality evidence gaps as well as code gaps; they do not justify rebuilding.

I1 source review retained durable private imports, MIME sniffing, EXIF orientation,
PDF-page limits, draft AtomicFile/idempotent saves, scoped deletion, FTS rebuilding,
separate bounded workers and grant-restricted exports. Changes: failed-page retry
is reachable from another successful page, export duplicate taps are guarded,
partial share cache files are removed on failure, provider output is flushed before
success, and scan save captures explicit-choice flags before background work.
Verification pending final pass. Provider atomicity and process interruption of a
provider destination remain acceptance scenarios, not guarantees.

I2: rule `local-phrases-5` adds conservative contextual Medical guards, a purchase
cue combination without literal “receipt”, and mortgage payment Banking evidence.
These respond to the recorded authored-corpus errors; no personal document was
read. Existing address/name safeguards and date defaults remain. New authored CC0
corpus `p0-final-corpus.json` freezes 4 tuning / 14 evaluation documents before any
measurement. It is agent-labeled, generated-fixture evidence, not blind independent
human evaluation. Crop exposes named accessibility corner/movement actions.
Verification pending final pass; existing regression sets remain unchanged.

I3/I4: implemented format-1 uncompressed `.stackbackup`, strict versioned metadata,
original digests, pinned immutable snapshot lifetime, buffered background transfer,
private validated staging, additive collision planning, reviewed commit and durable
owner-tagged journal/SQLite-marker recovery. Schema 6 upgrades 1–5 without replacing
data. Process-owned controls survive rotation and provide progress/cancel/retry.
See `STACK_ARCHIVE_FORMAT.md` for scope, bounds, conflict and interruption semantics.
No personal archive was created. Verification pending final pass.

I5: authored `ArchiveChecks` for complete metadata/original round trip, FTS/export,
empty/additive/repeated restore, pinned snapshot across delete, malformed/duplicate/
traversal/link/size/corruption cases, cancellation/write failure, staged journal/file/
transaction/commit interruption and stale OCR claims. Added disposable checks package
flag so the personal installation need not receive an unverified build. Extended
migration fixtures through v5 → v6 and measurement tools to select the disposable
package and frozen corpus. Documentation distinguishes archive/import-again scope
and excluded local draft/recovery data. All checks remain unrun at this checkpoint.

I5 lifecycle follow-up: import completion now belongs to the application process,
with one active import and a consumed completion token. Activity rotation cannot
queue a duplicate import or lose the saved-document navigation/error. Persisted
read grants are released only when acquired by that operation. Process death still
recovers uncommitted staging at launch; it does not replay a canceled incoming share.

I5 export follow-up: replaced the edited/image PDF accumulator with an independently
authored lossless RGB/Flate streaming writer. Rendered page appearance/settings and
order remain authoritative; memory holds one bitmap plus one scanline/deflater,
not all native PDF pages. Untouched imported PDFs retain the exact-copy path.
Authored a 24-page color/order/render/recycling/write-failure fixture; unrun.

**Implementation sweep I1–I5 complete. Enter V1–V9 next.** No build/lint/test,
instrumentation, device walkthrough or quality/performance measurement has run
between implementation groups. Final verification must repair any failures before
canonical in-place delivery. All remaining hardware/user-only gates will be collected
once, with no P1 work or milestone approval stops.

### Final verification/repair stage (active)

V1: offline disposable debug/test artifacts and lint pass (0 errors / 6 warnings);
actual Java schema SQL v1–5 → v6 passes host SQLite; six consolidation host tests
pass. Initial compile failure used a non-public Android directory-open constant;
fixed by opening the known private directory read-only before fsync.
V2: 35 scanner/migration, 8 OCR, 7 suggestion, 1 naming, 3 repository workflow,
2 real provider/grant groups and six-page real-worker workflow pass on Pixel 4a /
API 33 / 4 KiB. First UI rerun failed because its accessibility selector hardcoded
the canonical package; fixed for disposable testing, rerun pending.
V3: initial seven archive/streaming groups pass, including 13 hostile archives and
injected journal/file/transaction/commit failures. Review additionally found/fixed
pristine-catalog deletion after concurrent import and stale suggestion-generation
aliasing on reused IDs. Fresh random suggestion incarnations and pristine recheck
now have regression fixtures; affected rerun pending. Actual external force-stop,
production-controller/UI and 12-page edited-export/performance checks were added.
Canonical installation remains untouched. No personal backup/restore was attempted.

V2/V3/V6 update: hardened archive rerun passes eight groups; real external
force-stop/new-process recovery passes staged/journal/file/transaction/commit
boundaries, plus OCR and suggestion restarts. Corrected UI suites pass (3 workflow,
1 suggestion) with an awake debug-only window; fresh disposable production import/
OCR/manual choices/later-page search/backup/review/cancel/retry/additive restore
passes. No system-picker or physical network-disabled walkthrough claim. A final
navigation review added durable tab/query preferences and deleted-filter fallback;
its production disposable relaunch fixture is pending. Twelve-page 3200-edge edited
PDF: 10,676 ms / 1,169,575 bytes; snapshot 30 ms, archive write 12 ms, restore 165 ms;
sampled whole-process peak PSS 239,978 KiB, UI ping 1 ms / IO ping 6 ms. No controlled
baseline or instantaneous memory-peak claim.

### Final completion record

I1–I5 and feasible V1–V9 are complete; no pending implementation or known failing
executed check remains. Remaining acceptance gates are consolidated once in the
[final report](STACK_P0_FINAL_EVIDENCE.md#one-remaining-userresource-checklist).
0.7.0 delivered processing/archive behavior; 0.7.1 aligns Backup/Restore with Stack
and adds isolated 320 dp / 1x / 2x layout and synthetic visual review. Affected
production backup/review/cancel/retry/additive restore checks were rerun. Final
packaging and preservation results are recorded in that report and `docs/p0/`.
Do not start P1 or repeat completed checks absent changes/new resources.

0.7.1 visual follow-up (**1c4d795**): Backup/Restore now uses shared white toolbar,
original circular outlined icons, library action rows and blue review controls.
Short scope/protection copy stays visible; detailed help is expandable. New production
layout checks pass at 320 dp / 1x and 2x text with synthetic screenshots reviewed;
progress/review scrolls into view. Affected fresh disposable backup/review/cancel/
retry/additive restore rerun passes. Final signed build/lint/native audit pass;
canonical 0.7.1 update used install -r, preserved the same current 6/12/9/8 metadata/
decision and original/draft/recovery hashes, and reopened Stack. Both disposable
packages and the canonical test APK were removed. See final evidence for the one
remaining user/resource checklist. No further P0 implementation gap is currently known.
