# P1 execution ledger

## Current checkpoint — final delivery

Implementation `7d029b5`, **0.8.1-dev/code 14/schema 7/archive 2**. All I1–I8
and feasible A1–A7/F1 work complete. 91 suite groups and 11 actual process-stop
cases pass; affected core/batch/repository/archive checks reran after final repairs.
Canonical debug/test/unsigned-release builds and lint (0 errors/15 warnings) pass.
Original key verified; final install-r fresh aggregate comparison matches exactly,
actual installed APK SHA-256 `f2231433cd5d5b92f5547953fb3534ae84911e0f42b28244dfa11ad367afbcd5` verified.
Canonical test APK removed and Stack reopened; all new opt-ins remain off. No private
content exported or personal backup/restore/delete/reset/screenshots performed.
Only the single [H1–H8 human/resource session](HANDSON_CHECKLIST.md) remains pending;
full P0/P1 acceptance stays open. Earlier entries are chronological, not restart orders.

Active work order: `../STACK_P1_RUNBOOK.md`; user invocation authorizes all 14 P1 rows.
Baseline confirmed: HEAD 8846703 (planning), implementation 1c4d795,
P0 evidence 0f4aa3f, 0.7.1-dev/code 12/schema 6. Installed Pixel 4a reports
0.7.1-dev/code 12. Cached SDK/Gradle/user paths exist. Cached artifact and
Android user signing certificate SHA-256:
`db7352c8df2d3643ae9dc119ffd73ee26a700808d53efad3cf60a753e620eeab`.
Before delivery compare the installed certificate directly and capture fresh hashes.
No library content read/export, canonical mutation, or hands-on request during discovery.
Preserve existing matrix formatting edit and sibling cosmic-takeout.

| Stage | Status | Next/evidence |
|---|---|---|
| I1 | Complete | Schema 7 and archive 2; direct 1–6 migration fixtures extended; P1 round-trip fixtures finish in I8 |
| I2 | Complete | Independent typed proposals, recoverable editors, copy, notes and completions; frozen 20-case CC0 agent-labeled corpus |
| I3 | Complete | Bounded 3,200-edge zoom/pan/reset, stable page context, composed filters, actual Camera2 torch callbacks |
| I4 | Complete | Durable ≤50-item staging/journal, selection/review/filing/delete, pinned PDF and streamed ≤2 GiB ZIP sharing; application wiring in I8 |
| I5 | Complete | Default-off future local Camera photos, partial-permission-aware scope, source/digest history, commit consent gate, OS jobs/observer, separate private notification opt-in |
| I6 | Complete | GET_CONTENT MIME gate, explicit selection, process-owned preparation, read-only exact-output grants and cancel/recreation |
| I7 | Complete | Platform biometric + device credential on API 30+, biometric/credential fallback 28–29, credential fallback 24–27; one nonportable session gate, explicit enable/disable |
| I8 | Complete | Production wiring; durable single import; expanded read-only P1 preservation hash; authored repository/archive, corpus, production UI, separate-UID chooser, batch and real-process fixtures |
| A1–A7 | Feasible independent work complete | 91 suite groups + 11 actual process-stop cases; final builds/lint/package/key/preservation pass; H resources explicit |
| H1–H8 | Not run; pending | One HANDSON_CHECKLIST.md session; includes P0 unresolved gates |
| F1 | Complete | Record actual results, never imply acceptance |

## Ownership design (before editors)

Saved details have stable per-document field IDs, typed exact string values, role,
precision/currency, origin, evidence JSON and revisions; removed/rejected rows are
persistent tombstones. Organization tags have separate identities/relations, never
stack memberships. Notes and document metadata decisions have independent revisions.
Derived candidates use bounded OCR snapshots and separate worker claims; Apply
revalidates evidence, rule/catalog/input and metadata decision revision. Manual
values survive invalidation. Editor drafts are local and only explicit Save commits.
Batch operations and source history are device-local recovery records; consent,
permission, authentication and pending external requests are never portable.
Archive 2/schema 7 includes saved notes/details/tags/tombstones, reads format 1/schema 6
with empty defaults, rejects unsupported versions. Existing original/journal protocol
and P0 field ownership remain intact.

I1 commit `0b70017`. I2 metadata worker application wiring and lock integration complete in I7/I8. All verification remains pending.

## Implementation decisions / stage boundary

I1 `0b70017`; I2 `f5acd25`; I3 `c1067c9`; I4 `f08ef49`; I5 `cf0c665`;
I6 `edab368`; I7 `1b4da59`. I8 adds fixtures and integration before any build/test.
No human capture, permission request, enrollment, secure unlock or personal mutation
was requested/executed. No device screenshot or private backup was taken.

Authentication uses platform APIs only (no new dependency): API 30+ strong biometric
or credential; 28–29 fingerprint biometric prompt with explicit credential fallback;
24–27 system credential. Session ends when Stack leaves foreground or screen turns
off; internal navigation/configuration keeps it. Process death starts locked. Native
OCR/analysis already running may finish a guarded commit; no new claims while locked.
Already deliberately granted exports may remain readable until grant/cache expiry.

Photo import is off by default, future completed DCIM/Camera JPEG/PNG/WebP only,
15-minute OS periodic scheduling + content-triggered/foreground observation, ≤100
rows/sweep and bounded decode. It never queries cloud photo providers. Partial access
stays limited. Re-enable establishes a new future-only baseline. Source identities
are immutable decisions; changed/ignored/deleted assets are not overwritten/reimported.
Actual device photo permission/scheduling/notification behavior remains H4.

Primary API sources consulted 2026-10-16:
- https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- https://developer.android.com/training/data-storage/shared/media
- https://developer.android.com/reference/android/app/job/JobInfo.Builder
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
- https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder
- https://developer.android.com/identity/sign-in/biometric-auth
- https://developer.android.com/reference/android/app/KeyguardManager
- https://developer.android.com/reference/android/content/Intent#ACTION_GET_CONTENT
- https://developer.android.com/develop/ui/compose/sharing/send

Next: offline checks builds/lint, then all authored/reused automated checks and
repair; record actual results under docs/p1. Do not restart implementation or stop
at component boundaries. Preserve canonical until isolated verification passes.

## A-stage checkpoint (ongoing, not final acceptance)

Offline disposable debug/test/unsigned release builds compile; lint repaired from
4 errors (legacy fingerprint permission and predictive-back callbacks) to 0 errors /
15 warnings. Host direct 1–6→7 SQL and six consolidation tests pass.
Device regression passes: scanner/migrations 36 groups; OCR 8; suggestions 7;
naming 1; repository 3; provider/grants 2; workflow UI 3; suggestion UI 1;
six-page workers 1; archive/PDF 8; disposable first saved-library 1;
navigation 1; Backup layouts 2; archive performance 1.
P1 core rerun passes 5 groups (notes/details/tombstones/claims/archive 1+2/auth
boundary/photo ledger). Frozen source and real rendered OCR measurements completed,
`frozen-quality.json`: 16 evaluation documents, 12 expected fields, 0 false/missed;
photo scenes 9/10 positives, 0/10 false imports. No post-measurement rule tuning.
These remain agent-authored/labeled generated evidence, not agreed acceptance.

P1 batch mixed-source/retry/ordered-ZIP portion passes, but separate-UID PDF result
fixture has timed out; diagnostic rerun in progress. UI captures for narrow screens
exist; fixture repaired to flush pending layouts and avoid synchronous launch into
the common lock gate. All 3 size modes are still pending a complete rerun/review.
No user permission/enrollment/unlock/capture requested. Canonical remains untouched.

Source review repairs added strict ISO currencies (Android accepts unknown 3-letter
codes), fresh metadata proposal claim tokens after restore, immutable photo source
reservations, direct old-operation lookup/recovery, label edits, grant release after
all batch consumers, and screen-off policy that only cancels archives when lock is
actually enabled. Need affected reruns, real-process P1/P0 restarts, remaining visuals,
final artifacts/preservation and F1 docs. Resume these; do not restart I1–I8.

Latest A repair checkpoint: P1 batch suite passes all 3 groups including actual
separate-UID GET_CONTENT selection/read-only grant/cancel/MIME mismatch and selected
deletion/consent boundary. MIME mismatch uncovered a null-token onDestroy crash;
fixed and rerun passed. Core 5, archive 8, workflow UI 3, suggestion UI 1 and Backup
layout 2 affected groups pass. P1 UI now passes all three modes. Synthetic visual
review caught irrelevant currency/label headings in Notes and landscape reading
space; fixed hidden field headings and added a reversible Read/Details toggle that
preserves page/zoom. Latest three-mode visual rerun passes; process-restart scripts
are running. Test-only visible/keep-awake flags show synthetic windows over keyguard;
they do not dismiss/unlock it or authenticate. No production flags were broadened.
Off-IO pinned viewer rendering and a low-memory retry were added; dedicated latency/
memory fixtures authored to verify that concern. Current remaining work: complete
restart reports, run performance and affected editor checks, review final captures,
final build/package/signing/preservation, then F1 and the single H checklist.

Automated stage now has 89 passing suite groups across 19 flags, plus 4 P1 and 5
archive external process-stop boundaries. Production editor actions and P1 render
performance added to the affected checks and passed. Synthetic 3,200-edge zoom
bitmap 3,200×2,400 / 30,720,000 bytes; render 314 ms. Eight edited PDF pages
1,159,837 bytes / 6,937 ms, sampled whole-process PSS 241,100 KiB, UI/serializer
pings <1 ms at millisecond resolution (isolated serializer, not a universal bound).
Remaining A work: final frozen report pinning, legacy OCR/suggestion restart reruns,
final artifact/manifest/signing, final synthetic visual review and canonical
preservation-checked install. H checklist authored in HANDSON_CHECKLIST.md, all
pending; no human-gate claim. Final docs/handoff still to complete after delivery.

A7 prep: canonical normal debug/test/unsigned release builds and lint pass (0 errors /
15 warnings), but final zoom focal-point/restore-matrix and legacy auth recovery
source refinements require artifact rebuild before install. Actual installed APK
and cached/final signing certificates match SHA-256 db7352c8…620eeab. No canonical
application update yet. Last visual rerun passes 4 groups including editor Save and
all 3 configurations; accessible panning is exercised in Read view. Legacy native
OCR and suggestion force-stop/new-process reruns both pass. Final code commit and
fresh aggregate preservation probes are next; all H1–H8 remain pending.

Final focused gate regression passes: P1 UI 5 groups includes actual Notes/detail
Save/validation, all 3 synthetic sizes and an open-dialog→lock transition. Affected
batch 3 and core 5 also pass. Total 90 suite groups + 11 actual external-stop recovery
boundaries. Camera request toggling now follows actual selected state, with honest
unsupported light labels; physical camera/torch remains H2. No independent source
implementation remains. Commit final source, rebuild normal canonical artifacts,
capture fresh preservation aggregates and install in place; finish F1 docs after
comparison. All human/resource gates remain grouped at the end.

Final contract follow-up: 0.8.1-dev/code 14, schema 7/archive 2 unchanged. Rule 2
keeps generic Date role and pound currency unknown and abstains on code/symbol
conflict. Stable field IDs/tombstones remain intact; source and actual OCR corpus
remeasured, focused core suite now 6 groups, total 91 groups. Original frozen input
labels are retained; post-exposure semantic-role auditing is separately disclosed
as regression evidence, not new blind quality. The 0.8.0 interim canonical update
matched all four aggregates; build and preserve-check the 0.8.1 follow-up next.

## Final checkpoint

Final production implementation 36cf2ec, 0.8.1-dev/code 14/schema 7/archive 2.
All independent implementation/feasible automated work complete; 91 suite groups
and 11 external-stop cases pass; offline final debug/test/unsigned release and lint
0 errors/15 warnings. Both canonical install-r passes match all four aggregates.
Actual final installed APK hash, schema 7 and original key are verified; opt-ins
remain off, temporary canonical test APK removed, Stack reopened. No personal
backup/restore/reset/delete/screenshot or secure unlock/enrollment/permission request.
F1 docs complete. Resume only HANDSON_CHECKLIST.md H1–H8 and concrete feedback;
all H/resource gates remain pending and full P0/P1 acceptance is not claimed.
Earlier chronological entries retain then-pending states, not new work orders.

Final delivery supersedes the interim 0.8.0 checkpoint above: 0.8.1-dev/code 14,
implementation 36cf2ec; all four fresh before/after hashes match again, actual schema
7 and installed APK SHA-256 verified. Canonical test APK removed and Stack reopened.
H1–H8 remain the only pending acceptance work; no new human reports.

Final recovery edge repair: document deletion now marks any reserved photo source
as deleted in the same metadata transaction. This covers death after document commit
before source acknowledgment, followed by user deletion before the next observation.
New regression and affected core/batch/repository/archive checks pass; stable source
identity cannot resurrect that explicit deletion. Final production artifact is rebuilt
and preservation-checked once more; app version/schema/archive remain 0.8.1/14/7/2.

Final transaction-order repair: nested batch imports/filing now defer OCR and
proposal signals until the outer durable item transaction has committed. A new
fixture asserts no worker callback inside the transaction and one after commit.
Deletion cleanup failures remain actionable failed items for retry rather than
reporting completion with leftover files. Affected core (6), batch (3), repository
(3) and archive (8) groups pass; offline checks build/lint pass. Final artifact
rebuild and fresh preservation comparison follow this source commit.

Final repaired artifact delivery completed: fresh schema-7 before/after probe
matches all four aggregates and opt-ins exactly. Actual installed APK matches
packaging.json; original certificate verified, test-only package removed, Stack
reopened. Final evidence and handoff point only to H1–H8; no new human passes.

## Informal feedback follow-up — active 0.8.2 / code 15

User requested compact Home controls, centered nav labels, correctly scaled icons,
rename spacing, information hierarchy and simpler consistent Settings. Implemented
shared row/icon fixes and compact saved-details/review separation; source build/lint
pass, disposable runtime/visual verification pending. See ../STACK_UI_FOLLOWUP.md.
Original Google Stack migration requested; user reports PDF-only export. Phone
replugged as USB node 001/026; Linux access grant not visible yet, independent code
work continues. Do not restart P1 or treat informal feedback as formal acceptance.

Original-app migration inspection: Google Stack release 71588, target 30,
ALLOW_BACKUP set, nondebuggable; direct run-as denied without reading files.
User explicitly approved a private local backup of only original Google Stack for
migration, after disclosure of app/account state scope. No canonical backup/restore
authorized. USB ACL worked, then reconnection changed node 026 to 027; per-serial
host rule requested to avoid repeated grants. Disposable UI test installation was
interrupted before suites; do not count old logs as new passes.

## Follow-up final independent work checkpoint

UI `d6102e9` delivered as 0.8.2-dev/code 15/schema 7/archive 2, original key,
normal canonical build/install-r. Fresh observed 7 docs/13 pages/9 stacks/10
memberships and all four hashes match exactly; all new opt-ins off; test package
removed and Stack reopened. 19 affected groups and 8 host migration tests pass;
39 synthetic captures agent reviewed. Initial external activity interruption and
new test-helper context cast were repaired and affected checks rerun. See
../STACK_UI_FOLLOWUP.md and UI_FOLLOWUP.json; historical P1 totals are separate.

Original-app local backup was explicitly authorized and succeeded. User confirmed
Bills has 19 documents, supporting active code 1 vs excluded code 3. Converter
prepares all 145 documents/232 pages, 17 configured stacks/218 memberships, 232
referenced JPEG originals, exact stored values/type provenance and removals.
Eight host synthetic tests + one actual isolated production restore pass. Actual
private preparation was audited read-only, never restored as a test. No original
code/backend/account integration. Private source/output stays under
/tmp/stack-google-migration outside Git. See ../STACK_GOOGLE_STACK_MIGRATION.md.
Final real import NOT committed; user approval was for backup/preparation only.
Finish follow-up docs/Stack-only commits, then present this concrete migration
review decision. H1–H8 remain the one formal pending acceptance checklist.

Latest steering: user requested equal filter/settings circles after seeing 0.8.2.
Now both use centered 32 dp badges inside 48 dp touch targets; 0.8.3/code 16.
Added synthetic active-filter/Home captures; affected UI + conversion check running.
Migration converter/tests/docs committed separately; private final archive remains
prepared, not imported. Finish current badge verification/delivery, refresh current
handoff/evidence, then ask for the actual 145-document import decision.

Latest user question: original Google Stack populated details automatically. Stack
already generates local reviewed date/amount/organization proposals, but the compact
UI put their entry below notes/stacks. Move the review count to the top of saved
details, retain explicit Apply/edit/dismiss, mark the manual-add dialog clearly.
No automatic acceptance/overwrite, rule retuning or new parser scope. Final current
0.8.3 build must include this discoverability correction before delivery.

## Active details follow-up checkpoint

Latest user steering requires actual proposed values inline in the same Details
section as saved values, plus a visible Add manually option. Implemented, not yet
delivered: current canonical remains 0.8.2/code 15 until final isolated verification
and fresh original-key preservation install. New source is 0.8.3/code 16.

Read-only diagnosis of the user-selected bill found complete OCR, one previously
dismissed amount candidate and a valid labeled date embedded in a longer header.
Rule local-details-3 now handles labeled inline dates with existing strict calendar,
ambiguity and conflicting-value guards. Source headings/amount parsing/P0 naming
remain unchanged. Ordinary Apply cannot reverse a dismissal; a separate explicit
reconsider action validates fresh tokens. Removed fields remain removed. New
synthetic backend regressions and production reconsider-editor/inline-value checks
were authored; host migration regressions pass (8), current core passes (7).

User requested inspection of the same record currently open in original Google
Stack. Read its accessibility hierarchy privately, without screenshots or logged
values. Six visible details match all six saved fields/values in the authorized
source backup. A PDF-only import carries none of this metadata; prepared format-2
migration preserves these exact source fields. Current local proposals intentionally
cover dates/amounts/organizations, not arbitrary client/patient/staff fields. No
new personal values became fixtures or parser rules; no document was changed.

Next unfinished work: finish current affected UI/corpus/migration runtime checks,
review fresh synthetic layouts, repair failures, commit implementation, rebuild
normal canonical artifact without stackChecks, verify original signer and fresh
four-hash before/after install-r preservation, refresh actual evidence/handoff.
Then present the concrete real migration decision and retain the ONE H1–H8 list.

User asked how to close original-app field coverage using available install files
and the running source app. Extend independent bundled-OCR proposals to clearly
labeled client/patient/invoice/account/customer/reference identifiers and Staff
name, exact text with leading zeros and page evidence; no external service/model,
learned profiles or original code reuse. Rule 4 also handles Total Amount and
unambiguous amount-label/next-line layouts. New isolated source/real-OCR, conflict,
manual correction/stale action/archive/search fixture authored; verification pending.
Do not claim arbitrary extraction parity. Original-app behavior/data may establish
coverage and migration, not supply proprietary implementation.

Canonical delivery probe obtained a valid fresh baseline (7/13/9/11 after user
activity) but the host wrapper expected raw instrumentation output without -r.
Wrapper stopped BEFORE install-r; fix host command and rerun fresh after latest
feature checks. Canonical still 0.8.2/code 15, temporary read-only test package
installed. Temp diagnostic javac initially used host version 25; rebuild with
--release 17 for d8. These are host tooling failures, not product passes/failures.

## Final follow-up delivery — completed independent work

Implementation 3fc5907 (inline values/explicit reconsider/editor/inline dates) and
6d788a6 (labeled text fields/next-line amounts) delivered as 0.8.3-dev/code 16,
schema 7/archive 2. All 22 affected suite groups/eight host regressions pass;
source and actual rendered OCR yield eight new-field fixture values, six
counterexamples abstain. Blank separators initially missed two values; bounded
next-line handling repaired and affected core/corpus checks reran. One additional
Invoice # is outside the unchanged original frozen rubric; source/OCR original
field types match. No blind/real-photo or human-quality claim.

Normal offline debug/test/unsigned release/lint (0 errors/15 warnings) and static
APK audits pass. Actual installed original certificate/build hash verified, fresh
7/13/9/11 counts and all four aggregates match exactly; canonical install-r only,
temporary canonical test package removed, Stack reopened, opt-ins off. Previous
historical counts are never targets. No personal restore/clear/reset/screenshots.

Read-only selected bill: four offers (Client #, Invoice #, generic Date, Patient #),
one still-dismissed Total, no saved details; balance due has two conflicting OCR
values and Staff name is not proposed on this record. Six source Google fields
remain preserved in prepared migration. No private text/name/value was logged or
used as a fixture. APK structure inspection only confirms bundled assets; earlier
reference audit records service annotation responses, not an offline parity proof.

Transient approval service usage limit prevented one command execution; same review
path succeeded after user resume. Host preservation wrapper and javac tooling
issues repaired before final delivery; no bypass or false product pass.

README/matrix/archive/evidence/handoff/next prompt and structured follow-up reports
now record actual results. Next unfinished decision is the real 145-document
migration (backup/preparation approved; final addition not approved), followed by
the ONE H1–H8 session including P0 and resource gaps. All formal H remain pending;
full P0/P1 acceptance is not claimed. Preserve private /tmp migration workspace,
original signing cache, matrix OCR formatting and cosmic-takeout.

## Active automatic-details work order — 0.8.4/code 17

User explicitly changed the prior review requirement: detected details should be
applied automatically with edit/delete afterward. This supersedes review-before-
save for details only. Names/filing, opt-ins, locks and real migration authorization
are unchanged. Fill unowned slots once, preserve all existing saved/removed/rejected
records, defer entire document while editor drafts exist or OCR is partial/truncated.
Automatic accepted state has acceptance_actor=automatic in preserved evidence;
manual edit removes the Detected treatment without losing historical source.
No schema/archive change: existing format-2 evidence JSON carries the actor.

Implementation authored; first checks pass core 10, UI 5, original migration 1;
41 synthetic captures regenerated, changed Detected rows reviewed at narrow/2x.
Read-only preservation projection and partial-OCR tests are now authored; rerun
core/corpus/archive after this build. Next: validate host row-digest comparator on
synthetic before/after projection (including rejection of tampered old rows), commit
implementation, build normal canonical without stackChecks, capture fresh baseline
including row digests and original key, install-r and verify every old row exactly
with only authorized automatic additions/revision increments. Keep all private row
digests outside Git; record aggregates only. Final actual evidence/handoff follow.
Canonical still 0.8.3/code16 until that delivery; no P1/H acceptance claim.

Latest user steering authorizes real Google Stack import AFTER new-detail
verification and deletion of current test data, then making authored fixtures
available for hands-on testing. This supersedes the prior no-final-import-approval
state; do not ask again for migration approval once those conditions are met.
Deletion scope is ambiguous against original main-library preservation instruction;
async clarification pending (Checks-only, identified main records, or all main
records). Do not delete any main-library document without resolved scope. The same
question asks whether bare-dollar currency should default to USD; explicit currency
codes/edits stay authoritative. Explain that $ alone is not unique to USD. Continue
independent final label-guard verification, preservation delivery and docs while
answers are pending. Current installed 0.8.4/code17 already added six authorized
automatic details with every previous row preserved; selected record saved4/
offered0/dismissed1. Final equivalent-label safeguard builds 0.8.5/code18 and
is being verified. Actual source migration remains prepared, not committed yet.

Deletion/currency scope resolved explicitly by user: ALL documents currently in
main Stack are test data to delete after verification; bare $ defaults to USD.
This overrides the earlier prohibition for those explicitly identified saved
documents only. Do not clear/uninstall/reset the app/database or touch unfinished
scan/recovery; preserve stack catalog and use production confirmed batch deletion.
Real 145-document Google transfer is now authorized after verification/deletion;
no repeat import approval needed. New $ rule records bare-dollar-default evidence
and respects explicit CAD/AUD/etc and all existing manual choices. Rule6/0.8.5
code18; 25 current affected groups pass (10 core,5 UI,1 corpus,1 migration,8 archive).
Equivalent legacy/manual labels and removals block alternate-ID reinsertion.

Next unfinished: finish final normal build/static audit, original-key install-r
with fresh protected-row preservation (interim 0.8.4 installed6 automatic defaults),
then user-authorized real cleanup/import using production UI. Validate private
prepared source before commit; use its actual manifest to read back all imported
source metadata/original hashes without a personal backup or test restore. Keep
source app/backup, unfinished draft/recovery intact. Provide five CC0 synthetic
files after actual import for the ONE H1-H8 session; all human gates still pending.
Current migration archive is /tmp/stack-google-migration/Google-Stack-Migration-final.stackbackup.
No personal screenshots/content logs, no re-backup, no direct DB replacement.

User has now directly reported the new detail information looks good and explicitly
repeated the instruction to delete current saved Stack data and import Google data.
Record as informal user report scoped to detail presentation/default workflow, not
a full H1/P0/P1 pass. No further deletion/import confirmation needed. Final 0.8.5/
code18 implementation1bd1bf1 is installed; exact all-four hashes and protected9
existing detail rows match this final update. Original key verified. All25 current
affected groups pass. Executing production UI selection/deletion of all7 current
documents (fresh observed count, not a target), then production real archive import.
No app reset/DB replacement/uninstall; unfinished scan/recovery/catalog protected.
Private UI automation only reads hierarchy in memory, no screenshots/values logged.

0.8.5/code18 installed with original key, all four exact aggregate matches and
all9 preexisting detail rows preserved, no new detail additions in final guard/USD
update. User explicitly reported details look good and repeated delete/import.
Phone had dozed; no UI controls were available, so initial selector requests made
no changes. Wakeup key only (no unlock/dismissal); keyguard showing=false and
input unrestricted. Production UI selected exactly7 current saved docs and opens
confirmed Delete7 scope; actual deletion request now executing. Read-only pre-
transfer baseline /tmp/stack-google-migration/real-transfer-before.json protects
draft/recovery fingerprint182bc10e86f75b8ec287f1d98675851f026932b9cedea87a573272d32ebc6ef9
and all9 stack rows. No screenshots/personal names/values logged. Temporary source
copy goes to /sdcard/Download/Stack-Migration/Stack-migration.stackbackup solely
for real production restore selection; remove after verified commit.

Authorized production deletion completed: read-only counts0 documents/0 pages/
0 memberships/0 details, catalog9 rows exactly unchanged, unfinished draft/recovery
fingerprint exactly unchanged. Used normal batch Delete7 confirmation; never
cleared or replaced DB/app, never touched original Google app. Initial selector
missed native uppercase ACTIONS; case-insensitive public-control match corrected
without wrong-screen/destructive actions. Real migration file now copied once to
Downloads/Stack-Migration and production picker/validation being opened. Import
commit has not occurred yet. Resume pending real restore review/commit, verify
all145/232/767metadata rows/377original digests against source manifest in memory
with private read-only /tmp/stack-readonly/ReadOnlyMigration.java. Never import twice.

REAL MIGRATION COMMITTED ONCE through production Backup & restore: expected
review145 docs/0 conflicts/7 matching stacks, clicked Add restored documents once.
Actual read-only source-manifest verification succeeds145 documents/232 pages/
767 source detail rows/218 memberships/377 originals totaling197,564,722 bytes.
All source names/notes/types/provenance/removals/page edits/order and original
digests match; preexisting9 stack rows and unfinished draft/recovery hash unchanged.
Final catalog19 stacks (7 matches,10 source additions); no reseeding targets.
No personal backup-as-test or private-data test restore occurred: this was the real
transfer explicitly requested by user. Source Google app and host backup retained.
Initial OCR state7 succeeded/1 running/224 pending is asynchronous processing, not
a pass/failure claim; poll final actual counts before report, without logging text.
Five generated test files copied to Download/Stack-Test-Files, not imported into
main library. Temporary phone migration copy being removed after verified transfer;
only disposable Checks data reset for user's fresh synthetic testing. Reopen main
Stack. Next unfinished: final actual evidence/docs/source-only commit, testing guide
copy and one final H/resource checklist. Do not delete/migrate/import again.
Private raw readback/probe files /tmp/stack-google-migration/real-transfer-*.json
and /tmp/stack-readonly. Do not put row digests/catalog hashes per ID in Git.

All source data readback passed at commit, then current catalog count changed19→18
during subsequent use: one old pre-transfer catalog row absent, no new source-
created category absent, memberships still218; no agent category delete operation.
Do not recreate/reseed to19. Re-running read-only source mapping audit once to
establish all17 source categories remain present, then record actual current
counts separately from preservation at commit. OCR33/232 succeeded at last poll.
Temporary phone archive removed, host input retained. Checks-only pm clear done
for fresh synthetic testing; main Stack untouched by resets. Five fixture PDFs/txt
plus Start-here.txt copied to Download/Stack-Test-Files; testing remains user's
single H1-H8 session. All independent implementation/import work is done; finish
actual reports/docs/commits, do not repeat real import or ask migration approval.

Final source read-back again succeeds after later category-count observations:
145 documents/232 pages/17 current stacks/218 memberships; all17 original source
category names map uniquely, all767 source metadata rows and377 original hashes
match. Existing two extra pre-transfer catalog rows are now absent during later
use; no agent deletion/recreation of categories occurred. Draft/recovery hash
unchanged. Do not restore/reseed to19. Latest audit48 OCR succeeded/1 running/
183 pending is retained as a dated-stage observation; processing remains background
work, not an unfulfilled import or broad OCR acceptance.

25 latest affected device groups pass with USD and equivalent-label safeguards;
source-only readback helper and regenerated fixtures/guide are retained in tools.
Phone test PDFs/txt/guide indexed successfully in Downloads (queried only synthetic
filename), no MediaStore photos or opt-ins touched. Temp phone archive removed,
original Google app and private host source retained. Current docs/results/handoff
now describe completed transfer, scoped informal detail approval and the ONE H1-H8
checklist. Finish Stack-only evidence commit; leave original matrix OCR formatting
and cosmic-takeout untouched. No further import/whole-library cleanup action.


## 2026-10-17 — Stack tile editor and saved order (active)

User requested the open Google Stack “Edit your stacks” layout and drag ordering,
then is going to bed. Inspected native accessibility structure only: category tile
grid (no private screenshot/source reuse). Implement an original shared-style tile
editor with inline Home visibility, rename/delete/create and native local drag to
reorder; provide Move earlier/later actions. Persist each completed action, include
order in portable archives, preserve existing row order during additive migration.
No human requests tonight. Current baseline 0.8.5-dev/code18/schema7/archive2, commits
1bd1bf1 + evidence0cf1581; real Google transfer already complete, NEVER repeat it.
Next: schema8/archive3 + integrated editor, isolated migration/order/archive/UI
checks, original-signature canonical preservation delivery if available, docs. All
formal H gates remain in the single final checklist; original OCR formatting diff
and cosmic-takeout are untouched.

Implementation now in working tree: native local-only tile drag/drop, inline Home
checkbox, standard Options (rename, Move earlier/later, confirmed category delete),
New stack, per-action transactional order save. Schema8 adds sort_order and retains
historical rowid order; all legacy1–7 paths compile and host SQL checks pass.
Archive3 includes contiguous order;1/2 read array order, additive merge keeps local
order/new categories append; stale restore review also detects reorder. Dedicated
checks authored for actual native motion, canceled drop, accessible option,
visibility, recreation, narrow/2x/landscape, transaction failure, stale catalog and
malformed/old/future archives. Initial test compilation repaired (lambda captures),
isolated debug/test/release/lint build passes. Consolidated device verification
starting; canonical not yet updated.

Consolidated repair notes: initial archive-append assertion ignored intentional ID
remapping when a renamed source starter collides with the destination starter ID;
compare semantic category order instead. Legacy converter fixture was regenerated
and copied only to Checks after its earlier reset. Navigation check now supplies
its own disposable document and waits for Application startup (previously relied
on historical data). Archive8 + P1core10 + Google1 + navigation1 pass after repairs.
All seven Android migrations and three order/archive groups pass. Native gesture
injection requires system input routing; targeted Instrumentation rejects the
system drag input channel. UiAutomation per-MOVE waits stalled; stopped only Checks
and now tracing generic fixture stages with Android's native input draganddrop.
An attempted logcat diagnostic was rejected by automatic approval review for
potential private content. No logcat was captured; use only own test-stage status
and synthetic View.draw artifacts. Canonical remains unchanged at0.8.5/schema7.

Native UI repair succeeded: Android input draganddrop with a duration exceeding
the long-press timeout completes actual gestures.14 groups pass (seven migrations,
three order/archive, one native interactions/recreation, three layout groups).
Synthetic editor captures top/bottom320dp,2×text and landscape are authored, no
personal captures. Centered document counts after agent narrow review. Added
rename generation invalidation (stale worker/Apply safety) and a separate persistent
isolated fixture for actual Checks process-stop/resume. Final affected sweep and
canonical delivery remain pending. Preparing coherent implementation commit now.

Implementation committed030c7f2. Final consolidated affected sweep now running
against that source: stackEditor, archiveOnly, p1Only, googleMigration,
suggestionsOnly, navigationOnly, p1Ui. Separate stop/resume fixture follows.
Canonical remains0.8.5/code18/schema7 until all isolated checks pass and original
signer/current protected aggregates are captured. Next unfinished item: inspect
suite results and synthetic captures, repair only failures, then delivery.

Final affected archive8/core10/Google1/suggestions7/navigation1/P1UI5 pass. Repeated
native drag failed once (after a prior full14-group pass), exposing timing and a
real interaction hazard: identical OCR/metadata notifications rebuilt held tiles.
Repair: compare catalog/order/visibility/name/color/count and retain identical
views; don't reject a drag merely because an identical read is pending, and defer
in-flight rendering while dragging. Added actual library notification during held
native DOWN, assert same tile survives and native drag started before MOVE/UP.
Input commands now hold with300ms margin beyond deadline; edge hold900ms. No system
logs/private captures used. New English status literals moved to resources to
retain prior lint warning baseline. Repaired stackEditor + actual restart checks
running next; canonical delivery still pending.

Affected final results: stackEditor14, archive8, core10, Google1, suggestions7,
navigation1, P1UI5 =46 passing device groups; actual isolated restart prepare/resume
both pass (one process-stop case). Host actual historical1–7 SQL migrations and
8 converter tests pass. Notification-during-hold now retains the same view and
starts the real native drag; held edge scroll asserts nonzero scroll position.
An equal-height-row visual refinement exposed wrapped-label clipping, detected by
layout assertion; retained natural wrapping heights instead. Final14-group repair
rerun passes all gestures/layouts. Removed empty status space and matched spare
cell margins. Checks lint remains0 errors/16 warnings (15 baseline + checks label).
Next: final synthetic visual review, coherent repair commit, normal canonical build
(no stackChecks), current original-key fingerprints and install-r preservation.

## 2026-10-17 — Stack tile editor completed, canonical preserved

Implementation030c7f2 + repaircfa2fea delivered as0.8.6-dev/code19/schema8/archive3.
Final native14-group rerun includes stable held tile under background notification,
actual DOWN/MOVE/UP/drop, canceled outside drop, nonzero native edge scroll, Options
move, inline visibility and recreation; all pass. Other affected archive8/core10/
Google1/suggestions7/navigation1/P1UI5 pass:46 total, plus one actual isolated
Checks process-stop case (prepare/resume). Seven host historical migrations and
8 converter tests pass. Six final synthetic top/bottom narrow/2×/landscape captures
reviewed: full label wrapping, reachable controls and no empty status gap. Natural
heights retained after an attempted uniform-row refinement caused clipping caught
by layout assertions. No new quality/frozen-rubric/human acceptance claimed.

Normal offline debug/test/unsigned release/lint and both static APK audits pass;
normal lint0 errors/15 warnings. SDK/Gradle/cache/key paths confirmed; keytool public
certificate, old installed APK, normal build and installed APK all match original
SHA256 db7352c8df2d3643ae9dc119ffd73ee26a700808d53efad3cf60a753e620eeab.
Canonical built WITHOUT stackChecks and installed with adb install-r only.
Fresh before/after144 documents/231 pages/17 stacks/216 memberships exactly match.
All five aggregate fingerprints match: metadata/choices, P1 notes/details/tags/
relations/drafts, saved OCR, originals/scan draft/recovery, normalized category order.
All811 prior detail rows,20 tags/23 relations and231 OCR row digests exact; zero
new details/tags/relations. Current231 OCR pages succeeded is processing state,
not quality acceptance. Fresh counts differ from historical transfer after ordinary
use; do NOT restore/reseed historical counts. No canonical test cleanup/import,
clear/uninstall/reset/DB replacement, personal backup-as-test or personal screenshot.

APK SHA2567813cb0ee2e265e8c9acb4692f457d244ae171361bf4134a10946b751c4605e8
matches readback. Temporary canonical test package removed. am start returned
Status:ok/currently-running-topmost and ResumedActivity MainActivity. Phone was
asleep; left screen off, no wake/unlock/authentication requested or performed. All
new opt-ins remain false. Only Checks reset after verification for fresh user tests;
external test inputs/guide remain. Private reference hierarchy removed, no system
logcat captured. Original Google app/private host source kept and import NOT repeated.

Actual results retained in docs/p1/STACK_EDITOR.json, native/process/host text
reports, plus updated README/matrix/archive/editor/handoff/next prompt. Current
private row digests stay/tmp/stack-editor-delivery; synthetic images outside Git.
Next unfinished item is ONE H1–H8 session when user ready (including original P0
and resource gates and new ordering/TalkBack/subjective review). No independent
implementation or automated repair remains. User going to bed: no more requests.
Preserve original matrix OCR-row formatting and cosmic-takeout in final evidence
commit; full P0/P1 acceptance remains open.


## User-reported H1–H8 completion and status closeout

User stated “these have all been completed”; checklist has all eight entries checked.
Record this as user-reported completion, distinct from agent observations and actual
automated runs. Corrected stale pending-session header/current README/evidence/
editor/matrix/handoff/next prompt. Historical delivery reports and machine results
remain unchanged. USER_COMPLETION.json records source, scope and evidence limits.
No new measurements, device/API/storage details or numeric corpus/quality agreement
were supplied; do not invent them or turn reported completion into automated proof.
Broad parity/compatibility claims retain specific evidence requirements/provisional
targets. No known P1 implementation task or unresolved failure remains. Do not
repeat completed H checks or implementation without new scope/concrete regression.
No code, build, test, phone, consent or library mutation needed for this closeout.
Preserve user's checked entries, original matrix OCR formatting and cosmic-takeout.


## Direct Android unlock prompt — active user follow-up

User reconnected phone and requested skipping the first “Stack is locked” page,
showing Android “Unlock Stack / Use your fingerprint or screen lock” directly.
Also requested easy improvement recommendations, not implementation of extras.
Baseline0.8.6-dev/code19/schema8/archive3, implementation030c7f2/cfa2fea, closeout
2a1a037. Phone accessible by original serial on USB001/029; no ACL request needed.
Preserve completed user-reported H1–H8; no repeat of whole session. New scope only:
automatic once-per-entry system prompt, secure blank host, minimal cancel/error
recovery, no automatic retry loop, original lock/session/consent unchanged. Platform
prompt-launch exception now ends denied instead of stranding request. No schema/
archive change; candidate0.8.7-dev/code20. Use synthetic test-APK subclass at the
platform callback boundary (never canonical fake authentication); verify routing,
callback/lifecycle safety and narrow/large text. Then original-key canonical build,
fresh five hashes/policy, install-r preservation and reopen. Physical auth only user.
Official Android BiometricPrompt/reference and biometric-auth guidance consulted;
existing supported API-specific authenticators/fallbacks retained. Independent work
continues without captures/permission/enrollment requests.

User additionally authorized both recommendations: device-local last successful
backup date and persistent Newest/Name document sorting. Also requested primary-phone
installation/Google backup restore instructions and deliverable. Implement in same
0.8.7 candidate; no schema/archive change. Date follows actual closed export only,
never restore/cancel/failure. Home Recent stays newest; other document views sort
without changing originals/page/stack order. Prepare signed normal-package APK and
instructions outside Git for private APK/archive; original Google .ab is not directly
readable, existing prepared .stackbackup is. It contains original Google data, not
later Stack edits. No repeat restore/delete on this phone. Verification pending.

Affected verification now passes37 groups: backupUi2, lockUi5, convenience4,
backupLayout2, p1Only10, p1Ui5, archiveOnly8, navigationOnly1. Initial convenience
fixture failed its second font-size setup because prior Activity.onPause saved Home
after preference reset; waiting for destruction fixes it, rerun passes. No product
repair required by that failure. Backup capture now draws only its exact synthetic
View on white (not whole-device screenshot); final capture-only rerun pending.
Synthetic retry narrow/2x/landscape, sorting list/chooser and Backup shared layouts
reviewed; font2 chooser scrolls normally, history wraps without clipping. Original
policy/no-overwrite/core/archive checks retain their existing scope. Canonical
normal debug/test/release/lint build succeeds, lint0errors/15warnings. Final normal
rebuild after fixture-only changes, original-key preservation/delivery and private
primary-phone bundle remain; no full H repeat or fake native authentication.

Final backupLayout2 rerun passes using exact View captures with opaque white
background; agent reviewed the final wrapped history at320dp/2x. All37 affected
groups pass with no unresolved failure. Implementation/fixtures ready to commit;
canonical preservation/delivery and primary-phone bundle are the next unfinished
work, not another implementation restart. USB serial remains accessible.

Delivered0.8.7-dev/code20/schema8/archive3, implementationb5cc48e. Final normal
offline debug/test/unsigned release/lint pass, final lint0/14; static model/native/
ZIP/ELF audits pass, new fake callback/provider/recipient absent from both normal
APKs (debug-only preexisting View host excluded from release). All37 affected
groups pass; capture-only reruns retain group scope, eight final synthetic views
reviewed by agent. No new frozen evaluation/hardware-auth/parity claim.

Original-key installed/new signer verified. Fresh actual143docs/230pages/17stacks/
216memberships identical after canonical adb install-r, WITHOUT stackChecks. All
five aggregate fingerprints exact; all809 details/21tags/23relations/230 OCR rows
and prior projected rows exact, zero automatic additions. Existing app lock TRUE
stays TRUE, photos/notifications FALSE. Original/draft/recovery exact. Canonical
test package removed, installed APK matches, Stack reopened. No personallibrary
test mutation/restore/backup/screenshot/authentication, no new consent or DB reset.

Private permanent primary-phone folder~/Downloads/Stack-0.8.7 prepared(mode0700,
files0600): normal original-key development APK31,425,546bytes; existing converted
Google snapshot198,485,169bytes; exact install guide and checksums. All copies match.
No actual primary install/restore performed. Snapshot lacks later Stack edits;
guide explains real current Stack backup alternative and additive repeat duplicates.

Actual results/docs/p1 reports, README/matrix/archive/handoff/next prompt updated.
Private full projections/tmp/stack-unlock-delivery; synthetic images outside Git.
Only ONE focused final user checklist remains: new real native unlock/cancel/retry/
background flow and actual primary-phone install/one-time chosen restore. Prior
H1–H8 remains completed,user-reported. No unfinished implementation/automated repair;
do not restart or repeat personal cleanup/migration. Preserve old source/app, current
library, original OCR-row formatting and cosmic-takeout. Evidence commit next.


## Next-session desktop work order — documentation only

User requested one final closeout item: prompt to begin a Rust COSMIC desktop Stack
with phone/desktop portability and ideally continuous synchronization. Prepared
STACK_DESKTOP_NEXT_SESSION_PROMPT.md and changed the main next-session entrypoint
to execute it. Official pop-os/libcosmic API/book/template references checked.
Prompt stages persistent native desktop UI and versioned archive1/2/3 interchange,
a sync-ready change/tombstone/conflict model, synthetic replica convergence and
necessary explicit Android companion integration. Backup additive restore is not
sync; no real phone-sync claim before both sides work. Local opt-in peer transport,
no remote service/account, device-local policy isolation and current canonical
preservation remain explicit. Desktop source goes Stack/desktop, unrelated sibling
cosmic-takeout untouched. No desktop code/scaffold/dependency install/build/runtime
or phone change was performed for writing this prompt. The desktop phase begins
next session; no unfinished Android implementation/automated repair remains.
