# P1 runbook — implement continuously, group hands-on testing at the end

Prepared 2026-10-16. Baseline: **0.7.1-dev / code 12 / schema 6**, implementation
**1c4d795**, P0 evidence/documentation **0f4aa3f**. Re-read the actual repository
and device state when executing; these are starting references, not fixed targets.

## Work order and execution contract

This document plans a complete run through **all 14 P1 feature-matrix rows**.
Creating this runbook does not start implementation. Invoking the companion
[next-session prompt](STACK_NEXT_SESSION_PROMPT.md) authorizes the P1 run, superseding
older stop-before-P1/fields/notes/batch instructions for the scope below. It does
not mark the remaining P0 acceptance gates passed.

Read the [feature matrix](STACK_FEATURE_MATRIX.md), [P0 final evidence](STACK_P0_FINAL_EVIDENCE.md),
[handoff](STACK_SESSION_HANDOFF.md), [archive protocol](STACK_ARCHIVE_FORMAT.md) and
relevant source/reference evidence. Inspect existing behavior before implementing;
flash and category filtering already have partial production paths. Preserve them.
Use the matrix's acceptance conditions as the minimum scope, with the integration
and data safeguards in this runbook. Do not substitute a prototype for persistence,
recovery, usable UI or portable backup support.

Execute **I1–I8 → A1–A7 → H1–H8 → F1** below. Implement and author fixtures first;
run builds, lint, automated tests, corpus/performance measurements and automated
visual checks in the consolidated A stage. Fix failures and rerun affected checks.
**All user-dependent/in-person testing is grouped in the single H stage at the end.**
Do not request physical captures, unlocking, enrollment, photo access, notification
permission or subjective acceptance during implementation groups. Read-only device
availability discovery is preparation, not a hands-on acceptance session.

Continue without routine milestone approval stops. Make reasonable implementation
choices and document them. If a user/resource check is unavailable, finish all
independent implementation and verification, mark that check not run and carry it
into the one final checklist. Do not claim a feature accepted from source inspection
or invent human labels. Do not stop after a plan, a component or a commit while
useful authorized work remains. Update the ledger before context compaction and
resume the next unfinished item without restarting completed work.

When this runbook is invoked, concrete P0 regressions are in scope to repair.
Open P0 hardware/human acceptance gates alone do not require rebuilding approved
behavior or stopping independent P1 work. Combine their remaining steps with H below.

## Product and data contract

- Keep canonical **Stack `org.stackdocs.app`**, updating in place only. Never
  clear/uninstall/reseed it, replace its database, recreate Preview or reset its
  draft. Preserve originals, all explicit choices/names/filing, page order/edits,
  OCR, decision history and `files/consolidation_recovery/`.
- Retain the approved scanner, five user-reported OCR checks, four naming/filing
  passes and the 0.7.1 visual integration. Use shared white surfaces, blue actions,
  original circular/line icons, typography, spacing and accessible controls. Apply
  this design to new settings, editors, selection and authentication states; avoid
  separate text-heavy utility screens. Derive visual fixtures from current source,
  not obsolete Preview screenshots.
- Core behavior remains offline, with no account, backend, Play Services, analytics
  or model download. Prefer the current toolchain/dependencies; justify any addition
  by actual requirements, licenses, offline packaging, ABI/runtime and size cost.
  At implementation time consult current official Android documentation for photo,
  scheduling, notification, authentication and exported-component requirements;
  this plan does not prescribe a stale permission/API recipe.
- New automatic photo import and app lock are **off by default** when no existing
  explicit choice exists, including upgrade. Preserve existing local choices; restore
  does not create consent or weaken an existing lock. No photo-library permission,
  monitoring, import, biometric prompt or new notification permission request merely
  because Stack was updated.
- Ordinary migration must preserve existing authoritative values. New derived
  proposals may backfill locally without changing saved titles, details, notes,
  organization tags, memberships or explicit dismissals. P0 category/naming
  decisions remain independent of new P1 detail/organization decisions.
- Automated mutation, archive/restore, disk-failure and termination checks use
  synthetic isolated `files/checks/` contexts or disposable
  **`org.stackdocs.app.checks`** installations. Never test deletion, restore, camera
  roll monitoring, security enrollment or storage exhaustion on the personal library.
  Do not create/export a personal backup as a test or log private contents.
- Temporary test/provider/recipient components must stay out of production releases.
  Keep temporary grant scopes narrow, release unneeded grants and clean only exact
  owned synthetic files/operations. Preserve reference-app accounts/data/settings;
  use recorded evidence or targeted read-only inspection, no backend calls or
  proprietary code/models/assets/fonts.
- P1 scope is the 14 rows below and their necessary integration. Desktop, cloud
  services, learned personal profiles, multilingual/handwriting expansion, an
  exported searchable OCR PDF layer, arbitrary page merge/split/reorder, custom
  cryptography and unrelated features remain excluded. Do not infer additional
  reference behavior from an unresolved audit entry.

## Complete P1 coverage and dependency map

| ID | Required result | Implementation | Automated verification | Final hands-on |
| --- | --- | --- | --- | --- |
| UX-07 | Readable fine-print zoom/pan/reset without losing document/page context | I3 | A2, A5 | H1 |
| UX-08 | Review/file several new imports, visible per-document progress and corrections | I4 | A2, A4 | H3 |
| SC-08 | Hardware-aware light/flash controls and safe state/lifecycle recovery | I3 | A2, A5 | H2 |
| SC-09 | Explicit opt-in local document-photo detection/import, deduplication and disable | I5 | A3, A4, A6 | H4 |
| SC-10 | Import notifications open the correct saved document and respect permissions | I5 | A3, A6 | H4 |
| ORG-04 | Useful local organization proposals/groupings with edit/reject controls | I2 | A2, A4 | H1 |
| ML-04 | Advisory date/amount extraction with provenance, ambiguity and corrections | I2 | A2, A4 | H1 |
| ML-05 | Add/edit/remove/copy details; corrections/removals survive reprocessing/restart | I1, I2 | A1, A2, A4 | H1 |
| ML-06 | Small, useful local name-completion list, explicit selection only | I2 | A2, A4 | H1 |
| SR-04 | Organization/category filters compose with query and clear reliably | I3 | A2, A4 | H1 |
| DT-04 | Optional durable editable notes in local search and portable backup | I1, I2 | A1, A2, A4 | H1 |
| EX-04 | Selected PDFs/ZIP sharing and confirmed batch delete with per-item outcomes | I4 | A2, A3, A5 | H3, H5 |
| EX-05 | Another app's PDF chooser can select a stored PDF or cancel safely | I6 | A3, A6 | H5, H6 |
| SEC-01 | Optional system biometric/application lock, relock and recovery without data loss | I7 | A2, A3, A6 | H6 |

## Implementation sweep — no builds/tests/measurements yet

### I1. Authoritative metadata, decisions and archive compatibility

Inspect `LibraryDatabase`, `LibraryRepository`, `SuggestionStore`, search/index code,
`LibraryArchive`, `ArchiveJson`, `ArchiveController`, `ScanDraftStore` and preservation
probes. Specify the data/ownership/revision model before wiring new editors.

Implement additive migrations from **every schema 1–6** to the chosen new schema.
Do not reuse a current schema number or replace the database. Separate authoritative
values from derived suggestions and machine-local settings. Cover:

- Typed date and amount details plus supported user-added label/value details;
  stable field IDs, document/page evidence, rule/config/text/content revisions,
  accepted/edited/manual origin, dismissal/removal tombstones and decision revisions.
  Preserve date precision/raw evidence; use exact decimal/currency representation,
  not binary floating-point money. Unknown currency/date roles stay unknown.
- Optional notes with durable draft/editor recovery, length limits and empty-state
  semantics. A canceled edit does not silently persist; failed saves retain input.
- Organization/tag identities, relations, provenance and explicit edits/rejections;
  keep these distinct from stack memberships. Custom/hidden stacks and existing
  multi-stack identity remain unchanged.
- Durable batch/import operation identities and per-item progress/retry outcomes,
  photo-source deduplication/ignore history and process-safe worker claims as needed.
  Do not make transient claims, provider grants or biometric enrollment portable.

Update backup/restore **with the new metadata**, not after delivery. Version database,
application and archive separately. Prefer a new archive/manifest version for the
expanded schema; continue reading existing **format-1/schema-6** archives with
explicit defaults for absent P1 data. Older apps must reject unsupported new archives
rather than silently lose fields. Extend strict row/path/type/reference/size limits,
collision mapping, decision-evidence rebasing, FTS rebuilding and journal recovery.
Carry explicit removed/rejected P1 values and edited notes through empty/additive/
repeated restore. Rebuild derived proposals safely without reviving removals.

Device photo opt-in/grants/watermarks, notification permission, pending external
requests, lock enabled state and enrollment/authentication material are local policy,
not restored authorization. Restoring an archive must not enroll biometrics, disable
an existing lock, enable monitoring or resurrect pending notifications/batches.
Document backup protection and notes/details scope; archives remain unencrypted
unless encryption is separately requested in a later task.

Author migration/rollback, empty and nondefault state, old/new archive compatibility,
collision/recovery and authoritative-choice fixtures now; execute them in A.

### I2. Details, notes, organization and name completion

Extend the existing Details/editor/suggestion patterns; keep the simple document
loop usable when OCR is blank, failed, pending or ambiguous. Do not replace the
bundled English engine or retune approved scanner processing without a demonstrated
regression.

- Extract common document dates and amounts from bounded current per-page OCR.
  Distinguish roles such as document/due date and total/amount due where supported;
  preserve alternatives or abstain for ambiguous formats/currencies/roles. Never
  pick the largest number or infer a currency as if certain. Show source-page/text
  evidence and make suggestions editable and individually accept/dismissible.
- Add, edit, remove and copy supported details. Preserve explicit corrections and
  removal history across retries, rule upgrades, page edits/deletion, restart and
  restore. A late worker or previously open Apply action cannot overwrite a newer
  decision. Source invalidation does not erase a user's accepted/manual value.
  Clipboard operations follow the optional lock and avoid unintended sensitive previews.
- Add optional notes with recoverable inline validation/storage failures. Update
  local search after save/delete without inventing an OCR page hit; note matches
  should show their own context. OCR/suggestion reprocessing never rewrites notes.
- Derive conservative organization/issuer tags locally with reviewed apply/edit/
  reject. Reuse address/contact guards, bounded text and safe abstention. Offer useful
  groupings without automatically creating stacks or changing filing. Do not add
  learned profiles, remote enrichment or organization aliases without evidence.
- Offer a small bounded name-completion list from current document evidence and
  independently authored type/date defaults. Tapping a completion edits the name
  being reviewed; it does not save or change filing. Preserve typed input, ordinary
  Rename, date fallback and independent name/filing/detail decisions.
- Keep new analysis off the UI/repository serialization thread. Validate page order,
  content/text/config/rule/catalog and field-decision revisions at claim/commit/Apply.
  Bound queue attempts and offer useful retry/failure states without losing originals.

Freeze provenance-labeled tuning/evaluation fixtures and a rubric **before A4**:
positive/negative/ambiguous dates, amounts/currencies/roles, multiple organizations,
address-like headings, blank/failed OCR, later-page evidence and manual corrections.
Use authored non-sensitive or compatibly licensed sources; distinguish rendered
fixtures from real captures. Record reviewer identity, abstentions and provisional
quality targets. Do not fabricate independent human labels or request private bills.

### I3. Zoom, filters and camera light

- Add fit/zoom/pan/reset to the document viewer, with a bounded rendering/bitmap
  strategy for fine print. Coordinate touch gestures with page navigation and Details;
  preserve the stable page/document context through rotation/recreation, edits and
  search navigation. Supply accessible alternatives to gestures and error/retry.
- Extend existing stack/query filtering with reviewed organization/category filters.
  Chips/labels must distinguish organization tags from stacks; combine filters
  predictably, show current constraints, clear independently and recover empty or
  deleted filters after restart/restore. Keep snippets and matching-page identity
  correct; notes do not masquerade as page OCR.
- Complete the existing camera light/flash path rather than duplicating it. Reflect
  actual camera capability and actual enabled state; define torch versus still-flash
  labels accurately. Keep light off when the camera closes/backgrounds. Retain the
  user's session preference where safe, reacquire only while visible/authorized,
  and handle denial, disconnect, recreation and unsupported hardware honestly.

Author gesture/render-memory, filter composition/staleness, camera capability and
permission/lifecycle fixtures. Physical camera validation waits for H2.

### I4. Batch import, filing, PDF/ZIP share and deletion

Extend the existing add/import/library flows with stable-ID multi-selection and
per-document review; retain the normal one-document path.

- Copy each supported selected/shared image/PDF into owned staging while access is
  valid, then commit before OCR. Use a durable operation/item ledger to survive
  rotation, backgrounding and process death without duplicate imports. Do not hold
  every image/PDF/bitmap in memory. Support progress/cancel and actionable per-item
  read/permission/storage failures; retries do not import successful items twice.
- Show editable names/proposals and filing for new batch items. Default filing
  operations add chosen memberships; replacement/explicit empty filing requires
  a clearly reviewed action. Preserve independent per-document choices, hidden/
  custom stacks and user edits made while workers are active.
- Batch export produces ordered per-document PDFs or a bounded streamed ZIP of PDF
  copies. Sanitize names/paths, resolve duplicate display names deterministically,
  preserve page edits/order and untouched PDF bytes. Make document order explicit.
  This ZIP is **not** the metadata/original library backup format.
- Share only complete outputs via temporary read-only grants with appropriate
  multiple-item clip data. Handle canceled chooser/destination, short/denied/failed
  writes and stream close; clean exact owned partial cache outputs. Do not promise
  destination atomicity. Large selection limits must be explicit and bounded.
- Confirm batch deletion with selected count and scope. Coordinate metadata/index/
  original cleanup and active workers; remove only selected documents and unshared
  owned files. Record completed/failed items and permit safe retry. Never silently
  report complete success for a partial result or recreate a deleted document.

Author mixed success/failure/retry, stale selections/deleted stacks, duplicate names,
concurrent edits, canceled grants, large selections and interruption fixtures.

### I5. Opt-in camera-roll import and import notifications

Design one local opt-in setting and a clear permission/status/disable flow using
current Android APIs appropriate to min/target SDK and actual permitted scope.

- Default off. Enabling explains which **future eligible local photos** will be
  inspected/imported and requests only the access needed. Do not scan historical
  personal photos or import a backlog merely because the app is upgraded/enabled.
  If limited access is granted, respect that exact scope. Do not trigger remote
  photo-provider downloads or broaden supported import formats for this feature.
- Use a conservative independently authored local page/document detector, bounded
  decode and safe abstention; reuse approved boundary evidence when suitable.
  Document confidence is not a semantic category classifier. Commit accepted
  originals before OCR and suggestions. Exclude app-generated/test/export feedback
  loops and unfinished writes. Document scheduling latency/OS restrictions honestly;
  do not promise continuous background monitoring where it is unavailable.
- Persist source identity/generation/content evidence and operation decisions so
  duplicate observer events, renames, restarts or retries do not reimport the same
  asset. An intentional skip/deletion must not be undone by a later observation.
  Changed assets need an explicit, documented policy; never overwrite a saved original.
- Disabling stops observation/scheduling and prevents new queued imports from
  committing after the disable boundary; already saved documents stay saved. Handle
  permission revocation, locked-device source unavailability and process death
  without losing existing documents or prompting repeatedly.
- Import notifications are optional and permission-aware. Group bursts, display
  accurate completion/partial-error state and open the exact saved stable ID/batch.
  Denial does not block local import. Keep lock-screen content private by default,
  handle deleted documents/stale taps and route through app authentication when enabled.
  Do not request notification/photo access on first launch or from restore alone.

Use a synthetic source/provider and disposable media collection for fixtures. Never
turn on monitoring or seed photo fixtures in the user's real collection during A.
Actual permission and varied-photo behavior is grouped in H4.

### I6. PDF selection for another app

Implement a user-facing PDF selection entry point for the reference-backed
`GET_CONTENT` workflow, matching the caller's contract/MIME/request lifecycle.
Do not turn it into an unrelated broad DocumentsProvider integration.

Allow the user to choose a document, prepare the saved-edits PDF off the UI thread,
and return a narrow temporary read grant only after successful output. Support
cancellation, no documents, rendering failure, caller death and recreation. Neither
library metadata nor arbitrary private paths may be exposed. Do not let the caller
supply an unchecked document/storage path or bypass the optional lock. Coordinate
with cache expiry/active recipients and explain that already shared recipient copies
cannot be recalled. Author a separate-UID consumer fixture with actual result/grant
inspection, MIME mismatch, malicious path, cancellation and stale request cases.

### I7. Optional system authentication and relock

Implement one application access gate using supported Android system authentication
with an appropriate compatible API/library strategy. Handle the supported runtime
range and inspect any dependency's license, permissions, packaging and offline cost.
Do not invent an app PIN, capture biometric material or claim archive encryption.

- Enable only through an explicit setting after successful system authentication.
  Preserve a clear off/unavailable state on devices with no usable enrollment.
  Use a supported device-credential recovery option where available; otherwise
  explain required OS enrollment/recovery without trapping the user or deleting data.
- Define a documented session/relock policy. Gate cold launch, return after the
  policy boundary, incoming shares, notifications, external PDF selection, backup/
  restore controls and deep links consistently. Prevent background overlays,
  thumbnails/recents or pending requests from exposing private content while locked.
- Handle cancel, repeated failure/lockout, enrollment change, credential changes,
  recreation and process death. Failed/canceled authentication leaves documents
  intact and does not accidentally disable the lock. Disabling requires an
  authenticated deliberate action, with recoverable errors.
- Explicitly decide background policy: pause new automatic imports/processing and
  private content delivery while locked where feasible; document any unavoidable
  already-running work. Cancel/defer ungranted external requests and suppress
  sensitive notifications. Do not promise revocation of bytes/grants already
  deliberately delivered to another app.
- State the protection precisely: this is an app access gate; originals and portable
  archives have no new app-level encryption guarantee. Grants/enrollment/lock
  authorization are not restored from another installation or weakened by archive data.

Abstract the authentication result boundary for isolated deterministic fixtures;
actual biometric/credential enrollment and lockout/relock review wait for H6.

### I8. Integration, visuals and verification preparation

Audit every row in the coverage map against the resulting source. Finish error,
empty/loading/cancel/retry, settings, accessibility/focus/checkbox semantics, narrow/
large-text/landscape layouts and process recreation across all new flows. Use shared
components and synthetic screenshots for visual review. Keep manual document access
usable when suggestions, monitoring or notifications are unavailable.

Extend preservation fingerprints to all new authoritative metadata/relations/notes/
decisions, keeping derived work and intentional device-local policy separate. Finish
archive limits, old-format defaults, nonempty collision mapping, stale workers and
operation recovery for P1 fields. Author all missing A-stage fixtures, including
old draft/format compatibility and locked entry-point cases. Document dependency,
permission, archive and scheduling choices and any declared limits. Commit coherent
Stack-only increments with verification explicitly pending. Enter A only after the
implementation sweep is complete; do not put unfinished builds on the personal phone.

## A. Consolidated automated verification and repair

Use cached tools first. Build disposable artifacts before touching canonical Stack.
Tests, injections and measurement never clear/restore the personal installation.
Verify current official API/library facts when implementing/testing platform-specific
paths; record primary sources and actual versions, not assumed reference behavior.

| Stage | Required checks and evidence |
| --- | --- |
| A1 build/migrate/archive | Offline debug/test/unsigned release builds, lint; direct schema 1–6 migrations on populated nondefault fixtures; transactional failures; old format-1 restore and new-format full original/P1 metadata round trips; empty/additive/repeated restore, collisions, limits, hostile paths/entries, current/removed decisions, claims/FTS recovery; all new authoritative state compared. |
| A2 repository/workers/lifecycle | Existing applicable scanner/OCR/suggestion/workflow suites plus P1 details/notes/removals/copy/completion/filter/zoom/batch/auth-result suites; separate field choices; stale native/proposal/Apply races; recreation and externally terminated process recovery at import/batch/delete/restore boundaries; no duplicates or unrelated deletion. |
| A3 Android boundaries | Real synthetic provider/recipient and PDF-requesting app; grants/read-only scope/revocation/expiry, save/share/multiple selection, canceled/interrupted/denied/short/close failures; photo access/disable races, notification taps/deleted targets and lock-gated exported entry points. Distinguish stubs/injection from actual system behavior. |
| A4 frozen quality/search | Separate source text from actual rendered OCR; date role/value/currency precision/recall and ambiguity/abstention, organization labels, name usefulness/correction and document-photo detector false-import/miss rates. Record sample counts/licenses/splits/reviewer, note/detail search and later-page retrieval. If a measured case is tuned, disclose exposure and use fresh cases where possible. No invented external labels or agreed thresholds. |
| A5 performance/accessibility/visuals | Named hardware/input sizes; zoom/render/batch PDF/ZIP/backup/restore latency, UI/IO pings and sampled memory; cancellation and bounded selection/decode. Synthetic visual review of every new screen/state using current Stack design, narrow/2x text/landscape/focus/touch targets. No personal screenshots or global handset setting changes. |
| A6 offline/platform/security/package | Fresh disposable saved-library flow without account/network/download/Play Services through notes/details/filter/batch/export/restore/lock-eligible paths; available min/current API, other ABIs and real 16 KiB runtime; final permissions/backup flags/new components/provider scope, model/license/hash/dependencies, native/ZIP alignment and APK size. Missing hardware is not run; static checks are not runtime proof. |
| A7 final artifact/preservation/handoff | Repair failures and rerun affected checks; pin final implementation/APK hashes. Recheck signing/device access; capture current aggregate authoritative/original/draft/recovery fingerprints, install canonical only with adb install -r, compare preservation and reopen. Keep new opt-ins off. Prepare one H checklist against this tested build; no tests that mutate/restore/delete the personal library. |

For this handset, the cached signing environment used by P0 is:

```sh
ANDROID_USER_HOME=/tmp/stack-dev/android-user \
GRADLE_USER_HOME=/tmp/stack-dev/gradle-cache \
/tmp/stack-dev/gradle-9.3.1/bin/gradle --offline -PstackChecks \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Treat those paths as discovery hints; confirm they exist. Use the same original
signing identity for canonical artifacts, **without `-PstackChecks`**. The optional
`-PpreservationProbe` test build currently provides a read-only before/after runner;
extend it for new metadata without requiring newly upgraded production classes.
The existing migration/archive/tool scripts may need extending for P1 rather than
being treated as sufficient unchanged tests. Never fix signing mismatch by removing
canonical Stack. Preserve unrelated edits; do not commit APKs/keys/SDKs/screenshots/
private or generated archives.

After A7, report the tested version, automated outcomes and the **single H checklist**.
Do not stop independent work earlier to ask the user to test one feature at a time.
If the user is absent, finish documentation/evidence/commits, mark H awaiting evidence
and stop with that one checklist; do not claim full P1 accepted. If feedback arrives,
repair, rerun affected A checks, update in place with preservation checks and request
only the affected H retest within the same grouped checklist.

## H. One final in-person session — all user-dependent checks together

Prepare one numbered sheet with test fixtures, prerequisites and exact steps for
the final build. Use non-sensitive synthetic documents/photos and a disposable
library for destructive, bulk, monitoring, restore and no-prior-setup checks.
Use a supported emulator/disposable hardware for disk-full/power-loss work when
available. The personal library is never a destructive-test substitute.
Physical captures, secure unlocking, permission/enrollment choices, recipient/provider
selection and subjective review belong here. Do not enable opt-ins on the user's
behalf. Carry existing approvals forward; repeat a P0 pass only for a concrete
regression or uncovered scenario. Record user report separately from agent observation.

| Step | Combined hands-on exercise | Pass evidence to collect, without private contents |
| --- | --- | --- |
| H1 document/metadata loop | Capture/import a varied non-sensitive multipage sample; zoom/pan/reset fine print; review/edit/dismiss date/amount/organization proposals independently; add/edit/remove/copy a detail; use a name completion; write/search notes and compose/clear organization/stack filters. Reopen/reprocess and check corrections/removals remain. | Useful reading/gestures; accurate/appropriately abstaining proposals; clear provenance and no automatic overwrite; persistent notes/choices/filter context and later-page search. Record aggregate judgments, not document text. |
| H2 physical camera/lifecycle | On supported hardware check light on/off and unsupported/disabled capability, permission denial/retry, background/rotation/interruption, resume draft and multipage order. Reuse approved crop/cleanup/flatten behavior and cover only missing edge cases/regressions. | Actual light state/lifecycle matches UI; camera closes safely; draft/originals/choices and saved order remain. |
| H3 batch actions | Pick several synthetic images/PDFs, interrupt/cancel/retry, correct different names/filing, verify one identity per item; export selected PDFs/ZIP to a receiver/destination, then confirm a selected synthetic subset deletion. | Accurate progress/partial failures/retries, no duplicate saves/unintended refiling, correct filenames/order/PDF bytes/edits, only confirmed targets deleted. |
| H4 photo opt-in/notification | On disposable media/library, enable the feature and grant/limit/deny access; add document and non-document photos, repeat events/restart, skip/delete an imported synthetic item, revoke access/disable, then add another photo. Test notification permission deny/allow, grouping, locked-screen privacy and correct/stale taps. | No pre-consent or historical collection; useful conservative detection, deduplication and no resurrection; disable prevents new commits, imports remain visible without notifications; permitted tap routes correctly. |
| H5 external/system boundaries | Another app requests a PDF; select/cancel, share a batch and save to available providers; verify temporary read-only access and failure/retry. Try new-format backup/empty and additive restore in a synthetic disposable library, and old-format restore; include notes/details/tags/choices. | No unselected data exposure; chooser lifecycle/grants correct; close failures not false success; full supported metadata/original preservation and no destructive replacement. |
| H6 system authentication | On appropriate hardware explicitly enable the lock after enrollment, cancel/fail/authenticate, background/reopen/restart, enter via notification/share/PDF picker/backup, test supported credential recovery/enrollment changes, and deliberately disable. | Every entry path gated, clear unavailable/recovery states, no data loss/accidental disable, private recents/notifications; existing authorized recipient copies acknowledged. |
| H7 accessibility/offline acceptance | TalkBack/keyboard, labels/focus/checkboxes/zoom alternatives, large text/landscape and clear errors across new flows; OCR failure retry and dismissal persistence still lacking P0 user evidence; full fresh disposable offline first-use loop. Review provisional quality targets and real-photo utility together. | Independent human observations, resource/runtime identity and explicit decisions. Do not infer network-disabled/real-photo acceptance from generated scores. |
| H8 resource-only gates | Execute still-missing API 24/current/other ABI/16 KiB and safe actual storage/power-loss cases only if proper disposable resources exist. | Named actual execution results; otherwise one clearly identified unavailable-resource list, retained from P0 where applicable. |

## F1. Evidence, feature matrix and final delivery record

Create `docs/STACK_P1_EVIDENCE.md` and non-sensitive structured reports under
`docs/p1/` during execution. For **each of the 14 rows**, record the implementation
commit, relevant automated/human results, sample/runtime scope, exact evidence path
and remaining gate. Update README, feature matrix, archive specification, handoff,
execution ledger and next-session prompt with actual results. Keep implemented,
automatically verified, user-reported and fully accepted statuses separate. A
checkbox needs its complete acceptance condition evidenced; deferred H/resource
items are not passes. Preserve the preexisting OCR-row formatting-only edit and
unrelated siblings when staging matrix changes.

Finish with completed work, tests/measurements/failures repaired, preservation and
installed version, coherent Stack-only commits, and one consolidated H/resource
checklist if anything remains. Do not start later/excluded work to avoid an open
P1 acceptance gate. This run is complete only when all scoped implementation and
feasible automated work are handled and remaining user/resource gates are explicit.

## Execution ledger — completed independent work, final session pending

Invoked and executed on 2026-10-16. Final implementation **7d029b5**, delivery
**0.8.1-dev/code 14/schema 7/archive 2**. [Detailed execution record](p1/EXECUTION_LEDGER.md)
and [actual evidence](STACK_P1_EVIDENCE.md) distinguish implementation, generated/
regression/frozen metrics, agent judgments, runtime evidence and pending human gates.

| Stage | Status | Commit / evidence |
|---|---|---|
| I1 | Complete | 0b70017; schema 7, strict archive 2/format-1 compatibility |
| I2 | Complete | f5acd25, 36cf2ec; typed proposals/editors/notes/completions |
| I3 | Complete | c1067c9, 02acc37, 846eb11; zoom/filter/light integration |
| I4 | Complete | f08ef49, dbe3f4b, f39ed9a, 7d029b5; durable batch workflows |
| I5 | Complete | cf0c665, f39ed9a, e4dbe26; explicit photo/notification opt-ins |
| I6 | Complete | edab368, f39ed9a; actual GET_CONTENT result/grant checks |
| I7 | Complete | 1b4da59, 846eb11; common optional system-auth gate |
| I8 | Complete | dbe3f4b and consolidated repairs; fixtures/preservation/integration |
| A1–A6 | Feasible work complete | 91 groups, 11 process-stop cases, host/build/lint/quality/visual/package evidence; unavailable resources explicit |
| A7 | Complete | Original key verified; install-r only; four exact aggregate matches; actual schema 7, final APK hash, reopen |
| H1–H8 | Not run; pending | One [consolidated session](p1/HANDSON_CHECKLIST.md), includes P0 open gates; no interim user-testing requests |
| F1 | Complete | Evidence/README/matrix/archive/handoff/prompt/ledger updated; Stack-only commits |

Do not replay completed implementation or call open H/resource conditions passed.
