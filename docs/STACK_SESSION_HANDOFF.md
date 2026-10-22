# Current handoff — 2026-10-21 automatic sync follow-up

Stopped at explicit user quota limit (3%). Commits `caa3cec` (simplified sync),
`b6f6020` (Flatpak packaging), `54f1677` (responsive browsing and saved-peer TLS).
Canonical spare debug browsing repair is installed and populated; signed release
APK is built, verified, NOT installed. Real transfer did NOT complete: fresh phone
worker passed TLS/group authentication and was replaying journal for first export;
desktop remained zero documents/seven stacks. Coordinator stopped its offscreen
receiver. No reset/unpair/restore. User data must remain intact.

Delivery `.artifacts/stack-delivery-20261021/`: Android `android/Stack-release.apk`
SHA256 `a9b8bc85d3c4381a5eb56eece8b263eb082526eac49c4bf4c666d3f5fc7733f5`;
Flatpak `flatpak-final/Stack-0.1.0-rc1-x86_64.flatpak`
SHA256 `0cb82e059471984135ec60002f9515b56ea11b9be5428e9e6f1df19005c656fe`.
Do not label final sync stable/passed. Exact source snapshots include preserved
unrelated icon/version edits excluded from sync commits. Detailed current test
qualifications: [automatic sync evidence](desktop/AUTOMATIC_SYNC_20261021.md).
Final large-state2/2 pass; latest UI fixture incomplete after2passes; auto4passes
then occupied-writer assertion failed. Build/lint/Clippy pass. No more tests were
requested at the quota stop. Next work: inspect pending raw-journal export cost
on retained164-document/257-page spare; complete one real exchange and preservation
before installing the exact release APK. Spare only ADB port5693 serial0A121JEC203530.
No primary phone access. Unrelated dirty user files remain uncommitted.

---

The following handoff is historical:

# Current handoff — 0.8.7 direct unlock and primary-phone delivery

**0.8.7-dev/code20/schema8/archive3**, implementation `b5cc48e`. Read
[follow-up evidence](STACK_UNLOCK_AND_LIBRARY_TOOLS.md), [actual results](p1/UNLOCK_LIBRARY_TOOLS.json),
[primary-phone install guide](STACK_PRIMARY_PHONE_INSTALL.md) and [ledger](p1/EXECUTION_LEDGER.md).
The user’s next requested development phase is a native Rust COSMIC desktop app
with phone transfer and eventual opt-in bidirectional sync. The [desktop work order](STACK_DESKTOP_NEXT_SESSION_PROMPT.md)
and [short next-session prompt](STACK_NEXT_SESSION_PROMPT.md) are prepared; desktop
implementation has NOT begun in this closeout. Preserve the Android delivery below.

Opening a locked Stack launches native Android unlock directly; secure blank host,
minimal explicit retry after cancellation, no prompt loop. Existing access/session/
credential branches retained. New local backup date follows completed export only;
Newest/Name ordering persists and composes with search/filters; Home Recent newest.
No schema/archive/consent/scanner/OCR/naming/detail-rule changes.

37 affected groups pass on disposable API33/arm64/4KiB; normal offline debug/test/
unsigned-release/lint and static APK audits pass (lint0/14). Eight final synthetic
captures reviewed by agent. One sorting test setup race repaired; rerun passes.
Synthetic unlock substitutes only the OS callback in test APK, never real auth.
Do not claim new native biometric/credential hardware or unavailable runtime proof.

Original key verified, normal package without stackChecks installed-r only, installed
APK matches, temporary canonical test package removed, Stack reopened. Fresh current
143docs/230pages/17stacks/216memberships and all five aggregates exact; all809 details/
21tags/23relations/230 OCR rows and prior projected rows exact, zero additions. Counts
are observations, never reseeding targets. Existing lock TRUE preserved; photos and
notifications FALSE. No personal restore/delete/reset/backup test, screenshot or auth.
Private row projections `/tmp/stack-unlock-delivery/`; synthetic images outside Git.

Private durable primary-phone delivery is `~/Downloads/Stack-0.8.7/`: original-key
normal development APK, existing converted Google `.stackbackup`, install guide and
SHA256SUMS, directory0700/files0600. Copies verified. Google snapshot does not include
later Stack edits; user may make a current Stack backup for the actual migration.
Restore is additive; do not import both overlapping snapshots or repeat on this phone.
Original Google source/app and migration source remain; no proprietary code reused.

Only focused user actions remain: try new native direct-prompt/cancel/retry/background
flow with real fingerprint/screen lock; install/restore on primary phone using guide.
Earlier H1–H8 stays completed, user-reported; never reopen the whole session. No known
implementation or automated repair remains. Preserve provisional quality/evidence limits,
canonical library/drafts/recovery and existing lock, unrelated OCR-row formatting and
cosmic-takeout. For future authorized updates use fresh baselines and original key,
not historical counts/hashes. No further independent task remains for this delivery.

---

# Previous closeout — P1 implemented, automated checks passed, hands-on completion reported

The user reports all H1–H8 checks completed; all eight checklist entries are checked.
[Completion record](p1/USER_COMPLETION.json) and [completed scope](p1/HANDSON_CHECKLIST.md)
supersede older pending-session wording. No known P1 implementation or unresolved
repair remains in the execution ledger. Do not restart I1–I8, rerun the completed
hands-on session, or request captures/unlocking/permissions without new work that
needs them. Continue only concrete feedback, newly requested work, or documentation
of a specifically requested broader quality/compatibility claim.

Current delivery remains0.8.6-dev/code19/schema8/archive3, implementations030c7f2
andcfa2fea; no app/device/library changes were made for this status closeout.
The original91 automated groups/11 interruption cases and later affected reruns
(including46 editor-delivery groups/one interruption case) retain their scopes;
these overlap and are not a unique-test total. User completion is a separate
report, not an agent-observed or automated runtime result. Detailed new platform/
storage measurements and numeric quality/corpus agreement details were not supplied.
Do not invent them; broad parity/universal compatibility claims remain limited by
the recorded evidence and provisional quality targets.

Preserve canonical Stack and its current data/drafts/recovery, original signing
identity, source Google app/private migration source, unrelated matrix formatting
and cosmic-takeout. The real Google migration and authorized test cleanup are DONE;
never repeat them or recreate historical counts. Original preservation/delivery
records below remain historical. No new build/install or personal testing is needed.

---

# Previous handoff — delivery before the user completion report

**0.8.6-dev/code19/schema8/archive3**, `030c7f2` + `cfa2fea`. Read
[editor evidence](STACK_STACK_EDITOR.md), [actual results](p1/STACK_EDITOR.json),
[ledger](p1/EXECUTION_LEDGER.md), [archive contract](STACK_ARCHIVE_FORMAT.md) and the
[single H1–H8 checklist](p1/HANDSON_CHECKLIST.md). All independent authorized work
is complete; the user said they are going to bed. No further requests tonight.

Current editor replaces the text dialog with shared-style tiles. Hold/drop saves
order, edges scroll, Home checkbox is inline, Options supplies rename/move/delete,
New stack reuses validation. Hidden positions persist; all lists use saved order.
Each completed action is durable; cancellation/interruption keeps the last save.
Catalog CAS rejects stale drag/rename/delete, rename invalidates stale workers and
Apply, and unchanged background notifications no longer replace held tiles.
Schema8 additively preserves every1–7 path. Format3 retains order and all P1 values;
formats1/2 adopt array order safely. Existing destination order stays authoritative,
missing categories append; changed order rejects an old restore review. No consent
or lock is changed through restore/migration. Original scanner/OCR/naming rules and
previous user reports retain scope.

46 affected device groups pass, plus one actual isolated process-stop/relaunch
case, seven host historical-SQL migrations and eight converter tests. Normal offline
debug/test/unsigned release/lint and static audits pass (lint0/15). Six synthetic
narrow/2×/landscape captures reviewed by agent; formal subjective/TalkBack/API/resource
acceptance remains H, not passed. A logcat diagnostic was rejected by automatic
approval review; no logcat was captured, work completed using own synthetic test
status/Views instead. Temporary private reference hierarchy removed.

Canonical original signer verified; install-r only, no stackChecks. Fresh current
144 documents/231 pages/17 stacks/216 memberships remained exactly unchanged. All
five fingerprints match, including normalized order; all811 prior details,20 tags/
23 relations and231 OCR row digests exact. Zero fields added during this delivery.
All231 current pages reported succeeded, a state observation without quality claim.
Counts differ from historical migration after intervening use; NEVER restore/reseed
to145/232 or any historical count. Original/draft/recovery hash also exact. New
photo/notification/lock opt-ins remain off. Temporary canonical test package removed,
installed APK hash matches, Stack resumed; screen left asleep without waking/unlocking.
Only Checks cleared for fresh testing; five synthetic inputs/guide stay in Downloads.

The earlier real Google migration is DONE; do not delete/import again, seek its
approval again, or use the personal backup/library as a test. Preserve the original
Google app/private host source, all current Stack data/drafts/recovery, the unrelated
matrix OCR-row formatting and cosmic-takeout. Remaining work is ONE H1–H8 session
when the user is ready; no other pending implementation/automated repair. Full
P0/P1 acceptance remains open. Resume using the updated next-session prompt.

---

# Previous handoff — 0.8.5 automatic details and real Google migration (historical)

**0.8.5-dev/code 18/schema 7/archive 2**, implementation `0b17c4a` + `1bd1bf1`.
Read [current evidence](STACK_AUTOMATIC_DETAILS.md), [automatic results](p1/AUTOMATIC_DETAILS.json),
[actual transfer](p1/GOOGLE_STACK_TRANSFER.json) and [ledger](p1/EXECUTION_LEDGER.md).
All independent authorized work is complete. Do not repeat cleanup/import or ask
for already-granted transfer approval. The older handoffs below are historical.

User explicitly requested automatic detail saving with Edit/Remove, selected bare
$→USD, identified ALL current main-Stack documents as test data, then reported
“detail information looks good” and repeated delete/import. This superseded prior
review-before-save and deletion/import restrictions only for the completed real
operation. Existing saved/dismissed/removed and equivalent legacy/manual label
choices stay authoritative. Detected source/automatic actor and currency-default
basis survive format 2; complete text fills once, draft/partial-text cases defer.
No scanner/OCR/P0 naming change, cloud, downloaded model or proprietary code reuse.

25 affected groups passed (10 core,5 UI,1 original-corpus replay,1 synthetic Google
migration,8 archive) plus actual synthetic row-projection/tampering checks. Normal
offline debug/test/unsigned release/lint and static packaging pass. Canonical
original-key install-r only, no stackChecks. Interim six automatic additions were
explained row by row; final update matched all four aggregates and all nine prior
detail rows exactly, test APK removed. User report is limited, not a full H pass.

Production batch Delete7 removed exactly the seven current saved test documents,
then Backup & restore committed the prepared archive ONCE: 145 documents/232 pages,
218 source memberships,767 source detail/provenance rows and377 originals totaling
197,564,722 bytes all match read-back. Original Google app/private host backup kept.
No app clear/uninstall/reseed/DB replacement/personal backup-as-test or screenshot.
Unfinished scan/recovery and all nine existing stack rows matched at commit. Later
catalog observations19→18→17 occurred during subsequent use; all17 source categories
and source data still match the second audit. Never recreate to historical counts.
Background OCR is processing; the report records actual progress, not completion
or quality acceptance. Source saved details are already present before new OCR.

Five authored CC0 test inputs and Start-here.txt are on the phone at
Download/Stack-Test-Files. They were not auto-imported into main Stack. Only
org.stackdocs.app.checks was reset for fresh testing. If Downloads index looks
empty, use phone storage → Download. Keep the main migrated library protected;
use Checks/disposable collections for destructive/archive/batch/photo/auth checks.

Remaining work is the ONE [H1–H8 session](p1/HANDSON_CHECKLIST.md), including P0 and
resource gates. Full P0/P1 acceptance remains open. Preserve scanner/five OCR/four
naming reports and the new limited detail report in their scopes. Preserve unrelated
matrix OCR formatting and cosmic-takeout. Original signing cache/USB rule remain.
Private source/archives/raw per-row readbacks stay mode0700 outside Git in
/tmp/stack-google-migration. Temporary phone migration archive removed. No repeat
backup/import, data deletion, opt-in enablement or secure unlock is implied.

---

Historical follow-up handoffs (their pending-migration statements are superseded):

# Current handoff — UI and local details follow-up delivered; migration prepared

**0.8.3-dev/code 16/schema 7/archive 2**, final implementation `6d788a6`, inline
Details/reconsider `3fc5907`, badges `3272309`, UI `d6102e9`, migration converter
`f37c464`. Read [follow-up](STACK_UI_FOLLOWUP.md), [structured actual results](p1/UI_FOLLOWUP.json),
[migration preparation](STACK_GOOGLE_STACK_MIGRATION.md) and [execution ledger](p1/EXECUTION_LEDGER.md).
All independent authorized work is complete; do not restart P1 or infer acceptance.

Suggested values now appear beside saved values in Details, with Suggested, Review
and Add manually. Fresh explicit reconsideration can change a dismissed proposal;
ordinary/stale Apply cannot, and removals stay removed. Rule local-details-4 adds
inline labeled dates, exact-text labeled identifiers/Staff name, Total Amount and
bounded label/next-line amounts. No scanner/OCR/P0 naming change, new model,
network/backend, learned profiles or proprietary code/model reuse. Controls and
Settings retain compact shared styling and equal filter/settings badges.

Twenty-two affected device groups and eight host migration tests pass, including
8-value source/real-OCR coverage and six abstention counterexamples; 41 synthetic
captures cover narrow/2x text/landscape. Original 20-case replay keeps frozen
expectations unchanged: original types match; a new Invoice # output is outside
its original rubric, explicitly reported. Historical 91-group/11-process-stop P1
results stay separate. No new formal human pass is implied.

Canonical original-key install-r succeeded; actual APK hash matches the build.
All four fresh preservation hashes/counts match, temporary canonical test package
removed and Stack reopened. Last counts **7 documents / 13 pages / 9 stacks /
11 memberships**, observations only; opt-ins remain off. No personal-library
backup/restore/reset/delete, data replacement or screenshots were used as tests.

User selected the same bill in original Google Stack. Six visible fields/values
match the authorized source backup; values/title remain private. After update,
Stack offers Client #, Invoice #, generic Date and Patient #; total stays dismissed.
Balance due has two conflicting OCR values and Staff name remains unproposed on
that record. No automatic save/Apply or hidden override. This is read-only runtime
evidence, not broad field accuracy or Google parity acceptance.

Original backup/migration preparation was explicitly approved. Prepared private
archive: /tmp/stack-google-migration/Google-Stack-Migration-final.stackbackup,
145 documents/232 pages, 17 stacks/218 active memberships and 232 JPEG originals,
exact source details/provenance/removals. **Real import NOT committed.** Obtain the
user's final concrete transfer decision before adding these documents. Existing
category names merge, missing categories are added; use production additive import
once deliberately. Do not test with private data, repeat backup unnecessarily or
import account/monitoring/lock policy. Keep private workspace outside Git/mode 0700.

Remaining acceptance is ONE [H1–H8 session](p1/HANDSON_CHECKLIST.md), including
outstanding P0/resource gates and new labeled-detail checks. Full P0/P1 acceptance
is open. Preserve scanner/five OCR/four naming reports in original scope. Preserve
unrelated matrix OCR formatting and cosmic-takeout. Original signing cache and
canonical privacy constraints persist; no stackChecks for canonical builds. User's
Pixel-serial USB rule works. Approval service transient limit resolved through same
review path after resume; no approval bypass occurred.

---

Previous delivery and historical approvals follow; earlier version/counts are historical.

# Session handoff — P1 delivered, final grouped acceptance pending

**0.8.1-dev / code 14 / schema 7 / archive 2**, final implementation `7d029b5`.
Read [P1 evidence](STACK_P1_EVIDENCE.md), [execution ledger](p1/EXECUTION_LEDGER.md),
[archive specification](STACK_ARCHIVE_FORMAT.md) and the
[single H1–H8 checklist](p1/HANDSON_CHECKLIST.md). The invoked P1 work order superseded
all historical stop-before-P1/fields/notes/batch directions below. Do not restart
I1–I8 or treat the original planning handoff as current work.

All 14 implementations are integrated; consolidated verification/repair completes
91 synthetic runner groups, 11 actual external-stop recovery cases, populated
1–6→7 migrations and six consolidation host regressions. Final offline debug/test/
unsigned release and lint pass (0 errors/15 warnings). Human/resource acceptance
remains pending; full P0/P1 is not accepted. No new human report was received.
Scanner approval, five OCR and four naming/filing reports retain their original scope.

Canonical `org.stackdocs.app` is updated with the original key using install -r only
and reopened. Fresh before/after **6 documents / 12 pages / 9 stacks / 8 memberships**
and all four metadata/P1/local-ledger/OCR/original-draft-recovery aggregates match.
Actual installed schema 7 and APK hash are verified. All new opt-ins remain off;
restore never authorizes them or weakens a lock. No personal backup/restore/delete/
reset or screenshot occurred. Counts are observations, never reseeding targets.
Temporary canonical test APK is removed; Stack Checks contains synthetic test data.
Never recreate Preview, clear/uninstall canonical Stack or replace its database.

Final APK `f2231433cd5d5b92f5547953fb3534ae84911e0f42b28244dfa11ad367afbcd5`; certificate `db7352c8df2d3643ae9dc119ffd73ee26a700808d53efad3cf60a753e620eeab`.
Build without -PstackChecks for canonical delivery; use it only for disposable
Stack Checks. Original signing environment remains ANDROID_USER_HOME=/tmp/stack-dev/android-user,
GRADLE_USER_HOME=/tmp/stack-dev/gradle-cache, cached Gradle 9.3.1 and SDK in /tmp/stack-dev.
Do not fix a signing mismatch by uninstalling canonical Stack.

Next: one H1–H8 session, including P0 outstanding gates; use synthetic/disposable
libraries and a disposable media collection. Physical capture/secure unlocking,
photo/notification permissions, system enrollment and subjective review require the
user. API 24/current/other ABIs/16 KiB and actual disk-full/power-loss need appropriate
disposable resources; none were installed. No task remains that requires replaying
component implementation. Repair actual feedback, rerun affected A checks and
preserve-check another install-r update only if needed. Keep private contents/APKs/
keys/caches/screenshots out of Git. Preserve cosmic-takeout and the matrix formatting edit.

---

The following is historical context, retained for approvals and prior evidence.
Its old exclusions/planning status do not override the delivered P1 scope above.

# Session handoff: P1 runbook ready; implementation not started

## P1 runbook prepared — execution not started

The user requested a complete P1 runbook with all in-person testing grouped at the
end, plus a starting prompt. [STACK_P1_RUNBOOK.md](STACK_P1_RUNBOOK.md) covers all
14 P1 rows in dependency order: implementation, consolidated automated verification,
one final hands-on session and evidence. [STACK_NEXT_SESSION_PROMPT.md](STACK_NEXT_SESSION_PROMPT.md)
starts that run when invoked. This planning update changes no app code and runs no
app/device tests. Invoking the new prompt supersedes the historical stop-before-P1
boundary for its defined scope; existing open P0 acceptance gates remain explicit
and join the final checklist. Do not interpret this plan as P1 delivery or acceptance.


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


## Latest execution instruction

After acceptance commit **a81dd24**, the user requested a continuous run covering
**all remaining phase 0 items**, with **all verification saved for the end**.
Follow [STACK_P0_REMAINING_RUNBOOK.md](STACK_P0_REMAINING_RUNBOOK.md), its I1–I5
implementation sweep and final V1–V9 verification/repair stage. **Portable backup/
restore is now authorized P0 work**; prior stop-before-backup instructions are
superseded. P1 and structured-field editors remain excluded. Implement and author
fixtures first; run builds/lint/tests/device walkthroughs/quality measurements at
the end, before updating the personal installation. Continue independent work
without phase approval stops, collecting unavailable/user-only gates for one final
checklist. This checkpoint only saves the plan; no app changes or checks ran.

The [next-session prompt](STACK_NEXT_SESSION_PROMPT.md) is the current work order.
Baseline delivery and user-reported passes below carry forward unchanged.

Updated 2026-10-16 for **0.6.1-dev / code 10 / schema 5**, implementation
**4afbefa**. Canonical Stack is updated in place. Current evidence is
[workflow and address-safe naming](STACK_WORKFLOW_EVIDENCE.md); the original
[suggestion report](STACK_SUGGESTIONS_EVIDENCE.md) records the 0.6.0 baseline.
Scanner approval and all five earlier user-reported OCR workflow checks carry
forward. No broad P0/automation acceptance is implied.

## Current follow-up and user feedback

The user reported good stack suggestions on one veterinary examination bill but
an unsatisfactory street-address name. No actual practice/address, document contents,
amount or date were obtained. After 0.6.1, the user reported **“all worked”** for
all four requested hands-on steps: fresh bill name review, edit/save name and accept
stacks independently, relaunch with saved choices and name search, then explicitly
empty filing and relaunch with the document still in All documents. Record this as
user-reported corrected-name and four-step workflow acceptance; no actual name or
private contents were recorded. Dismissal was not requested in this check. Broad
quality/platform acceptance remains open. The user authorized **other independent
P0 work**; do not pause useful work merely waiting for feedback. Physical captures/
unlocking still require the user. Backup is now in P0 scope; do not start field
editors or P1.

Rule **local-phrases-4** rejects common address/contact/metadata candidates, ranks
credible first-page issuer/type headings and prefers Bill over a broad Medical
suffix when billing evidence is present. Unsupported names abstain; existing
names/memberships and accepted/dismissed decisions stay authoritative. An earlier
accepted bad title is deliberately not undone; ordinary Rename remains available.

Shared name editors retain invalid/overlong/duplicate/failed-save input for retry.
Picker Save removes invisible deleted IDs, repository validation reports deleted
stacks before replacing filing, hidden stacks remain selectable, and measured
review actions fit narrow/large-text layouts with field-specific accessible labels.
Schema/scanner/OCR configuration and runtime dependencies are unchanged.

Current validations: **18 applicable runner groups** across address counterexamples,
7 suggestion/stale-choice groups, storage rollback/recovery, actual provider/import
and separate-UID read-only export grants, editors/filing/320 dp/2x text/recreation,
saved review and six-page actual OCR/suggestion/search/export. The six-page fixture
(6,647 characters) completes both workers in 9,172 ms; IO/UI pings 1/1 ms, page-6
search survives reopen and the original PDF exports byte-identically. See evidence
for limitations and commands; unchanged scanner/native/restart results are reused.

Expanded **84-document / 87-page authored corpus**: all **22 structural name
expectations** match on source and real OCR. Category precision/recall **96.552% /
97.674%** overall; the eight separated evaluation cases score **90% / 100%**. Near-
duplicate address cases are regression coverage, not an external blind corpus.
Agent review: 60/64 names useful unchanged, 2 small edits, 2 unhelpful; separated
name correction/dismissal need 14.286%. Medical reminder/marketing false positives,
receipt/mortgage cue misses, generic-name/specificity and broad layout/real-photo
limits remain. No further tuning after this measurement; proposals remain advisory.

Immediate pre/post upgrade: **7 documents / 13 pages / 8 stacks / 10 memberships**,
all 13 OCR jobs succeeded. Metadata fingerprint
`aea67eb08ae7510137b2877e6fa1ce3a225a5808d093b4dcefc996b680ac7cbc` and original/
draft/consolidation-recovery aggregate
`112fa9c76b32ff6f20fd8c15e48a0ff240a12fbab66f27bab26d60a6f00620d0` match exactly.
Synthetic checks storage and instrumentation were removed; Stack was reopened.
Counts changed through user activity before upgrade; the older 6/12 or 8/14 counts
below are historical, never reseeding targets. No private contents were exported/
logged, canonical clear/uninstall/reseed or database replacement occurred.

Remaining work is now sequenced by the full P0 runbook: finish workflow/processing
implementation, build versioned portable backup and safe restore, then run all final
checks. Broader non-sensitive quality, OCR retry UI, provider failures, large exports,
lifecycle/accessibility, offline first use, API 24/current/other ABI/16 KiB execution
and actual disk-full/power-loss remain acceptance gaps until evidenced. Unavailable
resources and user-only checks do not block other work. Read the
[continuous next-session prompt](STACK_NEXT_SESSION_PROMPT.md). Preserve the
preexisting formatting-only OCR-row edit in the feature matrix and unrelated siblings.

The sections below retain earlier delivery evidence and historical counts.

## Completed scanner pass

Read [implementation research](STACK_SCANNER_RESEARCH.md) and [quality evidence](STACK_SCANNER_QUALITY.md) first. The user additionally requested autocrop and both perspective/curved-paper flattening. The pass adds validated crop candidates with fitted boundaries, noise-resistant illumination estimation, retained faint/gray strokes, and opt-in gentle text-curve flattening. It does not reconstruct arbitrary folded/curled 3D surfaces.

For the 0.4.2 scanner pass, both APK variants, the instrumentation APK and lint built offline; **32 synthetic runner groups passed** on Pixel 4a/API 33. The 0.4.3 consolidation builds a single Stack variant, as described below. Schema 1/2 → 3 migrations preserve old edits and default flatten off; draft version 1 remains compatible. The runner's old destructive UI-seeding path was removed: checks use fresh isolated contexts and do not replace Preview's personal draft. Preview was updated in place; original/draft aggregate hashes remain unchanged. No runtime dependency or network permission was added. Full measurements, timing/memory caveats and remaining quality gates are linked above.

Use **Crop & rotate → Auto crop → Apply** to replace a saved crop deliberately. **Flatten** previews a supported gentle text-curve correction; **Use flattening → Apply** saves the choice. A failed crop retains manual corners; flattening stays off for existing pages unless explicitly enabled.

## User approval

After testing the changes in this session, the user stated: “i have tested these, I approve.” Record this as user-reported hands-on approval of the delivered scanner changes, including autocrop, cleanup and flattening. The user did not specify a measured corpus or additional device coverage; broader quality and P0 gates remain open.

The user then requested a single app with Preview's functionality. This is complete: At consolidation verification, **Stack** (`org.stackdocs.app`) held all **8 documents / 14 pages / 8 stacks** from both libraries. Subsequent user activity changed the current counts recorded above. Read [consolidation evidence](STACK_CONSOLIDATION.md) for metadata/hash verification and private recovery archives. Preview and temporary test packages were removed only after read-back verification. Do not recreate the Preview variant or uninstall/clear the canonical Stack package; update it in place.

## Delivered OCR and next session

Phase 3 implementation is complete: Tesseract4Android 4.9.0 standard / Tesseract 5.5.1 plus pinned English tessdata_fast, verified bundled installation/repair, schema 1/2/3 → 4 migrations, revision/token/configuration-safe page queue, launch recovery with a three-interruption budget, import/save/backfill, dedicated worker, FTS4 body search, stable matching-page snippets/navigation and status/retry. Originals, manual titles/filing and saved scanner edits are preserved; OCR-only processing leaves untouched PDF export byte-identical.

Pixel 4a/API 33: 8 OCR groups, 33 scanner groups and the force-stop/new-process recovery check pass. The 30-document/36-page authored synthetic corpus found all 6 later-page sentinels. At 2400 pixels, CER/WER were 0%/0%, P50/P95 505/1750 ms, sampled peak total PSS 235.0 MiB. These generated templates do not establish real-photo accuracy or broad acceptance. Static native/APK 16 KiB checks and offline builds/lint pass; broader runtimes remain unverified.

Final canonical read-back: 8 documents / 14 pages / 8 stacks / 8 memberships; all 14 OCR jobs succeeded. Original/draft/recovery aggregate hash and all non-OCR metadata fingerprints match their pre-upgrade values. Personal text was neither exported nor logged. Synthetic checks storage and instrumentation were removed after validation; Stack is updated in place and reopened.

User-reported hands-on check: steps 1–4 completed with no issues (two-page scan/import, page-2-only search, snippet/opening page and search after close/reopen). The user then confirmed Details displays “Text ready to search.” Record all five workflow steps as passed by user report. Failure/retry UI and broad corpus/platform acceptance remain open; the tested documents/corpus were not specified.

The requested local naming, classification and filing implementation is now delivered. OCR remains the baseline; broader real-photo/platform/failure-retry acceptance carries forward. Backup/restore is a later group and was not started.

## Delivered naming, classification and filing — 0.6.0 baseline

Read [suggestion evidence](STACK_SUGGESTIONS_EVIDENCE.md), the
[P0 active group](STACK_P0_PLAYBOOK.md#active-group-naming-classification-and-filing)
and [next-session prompt](STACK_NEXT_SESSION_PROMPT.md) first. Implementation is
complete; do not replay the old planning prompt or infer that all P0 is accepted.

Schema 5 upgrades directly from 1–4, conservatively marking existing ownership
legacy without changing names/memberships or OCR fields. New defaults/filenames,
explicit title edits, accepted/edited proposals and deliberate empty filing have
separate durable provenance and decision revisions. Draft version 1 optional name/
choice fields survive recovery. Scan save no longer unconditionally renames or
replaces filing after commit; repeated save cannot overwrite newer choices.

Independent `local-phrases-2` rules analyze bounded current OCR text on a separate
worker. Proposals remain advisory until an explicit action. Expand **Details →
Suggestions** for independent name/filing Accept/Edit/Dismiss. Filing Accept adds
to current memberships; **Edit all document stacks** replaces the reviewed set.
Starred/custom stacks stay manual. Blank/unsupported or failed text still permits
manual rename/filing/export. Claims validate text/config/page order, stack catalog,
rule version and both decision revisions at commit and Apply. Database invalidation,
new-process recovery and unchanged-evidence decision history reject late results
and preserve rejected categories without recreating deleted rows.

Pixel 4a/API 33: **7 suggestion groups**, **34 scanner/migration groups** (direct
1/2/3/4 → 5), **8 OCR groups**, isolated production review UI and real force-stop
recovery of a held suggestion claim pass. Both production OCR/suggestion workers
are exercised together on isolated imports/scans. Builds/lint pass offline; no new
runtime dependency/model/network permission. API 24/current Android/16 KiB runtime,
real-photo quality, broad storage/platform/accessibility and new hands-on workflow
acceptance remain open.

Final corpus: **52 authored synthetic documents / 55 rendered pages**. Source-text
and real OCR proposals both measure **97.674% precision / 95.455% recall**, but the
**12 fresh held-out examples score 88.889% / 100%**, including a task-list Medical
false positive. Missing receipt wording and mortgage/Banking cues remain errors.
Agent title review: 36/38 useful unchanged, 1 small edit, 1 unhelpful; this is not
user acceptance. Proposal snapshot/analysis/commit P50/P95 **5.749/16.805 ms**;
sampled process PSS max **120.2 MiB** with instrumentation/fixture/OCR overhead.
See evidence for method, limits, recommended unagreed targets and versioned reports.

0.6.0 library recheck: **6 documents / 12 pages / 8 stacks / 8 memberships**, all
12 OCR jobs succeeded. Pre/post non-OCR metadata fingerprint
`81a062f346909f68fbf3f4f871815e6d4de491b7c2a32bb965cb8e88abba5c36` and corrected
original/draft/consolidation-recovery aggregate
`7c7c1234d1363062e32dec3aa6885ed9f106b01d58d7afb289dda2e962a2443f` match.
The old 8/14 count is historical, not a reseeding target. No personal contents were
exported/logged or fixtures placed in the real library. Preserve all recovery data.

Reference: both ignored APK identities match the audit; installed Google Stack
**1.231.431385252.release / 71588** was accessible. Targeted E04/E08/E10/E11 static
inspection plus a user-selected older document exposed multiple stacks, Details,
Rename and filing controls. Rename/stack editors were opened/cancelled without
saving; accounts/settings/documents were preserved. The user confirmed the older
document's total/date were extracted from its contents. Structured field extraction
remains later scope. Fresh automatic suggestion/dismiss behavior remains unverified;
no proprietary code/models/assets or explicit backend calls were used.

Next: bounded suggestion/workflow acceptance and broader corpus/platform coverage.
Do not start portable backup, structured-field editors or P1 without separate scope.
Preserve the user's scanner approval and all five OCR workflow steps. The preexisting
formatting-only edit in the OCR row of `STACK_FEATURE_MATRIX.md` was kept separate
from intended commits; leave unrelated sibling changes untouched.

## Product decisions

- Android first; desktop later. Completely offline from first use, without sign-in, Play Services or engine/model downloads.
- Reproduce Google Stack's core document loop before expanding features. Keep dependencies and the build environment small, but assess quality and maintenance costs when choosing processing dependencies.
- Preserve original sources; store crop, rotation and enhancement separately. Allow a color override. All source/artwork must be independently authored or obtained with compatible redistribution terms.
- The original audit's initial prohibition on application development was superseded by explicit development authorization. P0 development is underway.

## What exists

- Native Java/platform Android UI, private SQLite library, picker/share import, title search, stacks, document viewer and PDF export.
- Camera2 capture, recoverable multipage drafts, reversible manual four-corner edits, rotation, page removal and stack selection before save.
- Google Stack-inspired Home, document grid, floating add menu and Details sheet. The clipped chip border was fixed in 9d4a545.
- New camera pages default to **Black & white**. Original color, Color · Medium and Color · Bright remain available per page. Imported/existing pages retain their settings.
- Bundled English OCR/search with page snippets/navigation, durable state/retry and local reviewable name/category proposals. Portable library backup is not implemented.
- Runtime dependencies: Tesseract4Android 4.9.0 standard, ExifInterface 1.4.2 and JSpecify 1.0.0; AndroidX annotations are compile-only. Bundled English model/third-party notices are independently sourced. No OpenCV/scanner library or network permission was added. Database schema is 5; additive suggestion provenance preserves prior scanner/OCR fields.

## Historical same-page evidence and remaining problem

The user reports camera capture and saving work. The original Google Stack is installed on the Pixel 4a: `com.area120.paperwork`, version `1.231.431385252.release`, code 71588, matching audited build B.

The user scanned the same printed page in both apps and authorized temporary on-screen comparison captures. Google's displayed result had cleaner paper and smoother, thinner letters. Our 0.4.0 binary filter thickened letters, amplified texture into speckles, and retained dark surrounding strips through an inaccurate crop. The scans were separate acquisitions, so this was not a controlled algorithm benchmark. Google's selected enhancement preset and processing internals were not established.

Version 0.4.1:

- Replaces binary output with local paper normalization and continuous grayscale tones, retaining stroke-edge pixels.
- Fixes loss of detail caused by shrinking the whole frame before cropping. Crop-aware decoding uses up to a 4,096-pixel input edge, with 1,600-pixel previews and up to 3,200-pixel edited PDF pages.
- Adds nearby edge refinement, bright-region candidate comparison and interior checks intended to reject surrounding objects/surfaces.

The revised renderer visibly improved letters and reduced black speckling on the saved page. Some gray texture and existing crop borders remained. An intermediate unsaved Auto crop proposal incorrectly included surrounding surface; it was cancelled. Additional candidate checks and a tone-curve adjustment were subsequently built and installed, but **their final visual acceptance remains open**. Do not claim automatic cropping is fixed or that output matches Google.

Both final APK variants assembled offline and Stack Preview was updated in place. No automated tests were added or run for the recent processing revisions. The nine historical scanner check groups apply to 0.2.0-dev, not the current algorithm. No saved crop/color settings were changed during the comparisons. All temporary comparison screenshots were deleted; no document contents or reference images were committed.

## Original bounded task (completed; remaining limits in quality evidence)

1. Review OSS Document Scanner's actual image-processing code and file-level provenance first. Its MIT repository is a full NativeScript/OpenCV/Tesseract app; our shortlist is not an adoption decision or a measured quality result.
2. Separate boundary detection, source sampling, perspective correction, lighting normalization and tone/sharpness. Investigate the causes of each visible defect instead of tuning a single threshold against one page.
3. Decide whether independent techniques or a pinned, bundled OpenCV component best meet quality and environment goals. Check selected artifacts, notices, ABI/alignment support, first-use offline behavior and build cost before integration. CVScanner's current build includes Google Play Services Vision; ML Kit's scanner downloads implementation through Play Services.
4. Preserve the original images, manual corner correction, per-page overrides, existing library/drafts and Google-like workflow. Perspective rectification does not establish curved-page flattening.
5. Seek evidence on the same source images at fixed output sizes, including shadows, small/faint text and varied backgrounds. Follow the active session's instructions about running tests; do not treat historical results as current verification. Update the playbook/matrix with actual observations and unresolved limits, then commit the focused result.

## Connected handset and data precautions

- Pixel 4a, Android 13/API 33, USB serial `0A121JEC203530`. USB debugging was authorized; availability/unlocked state must be checked again.
- Original Google Stack: `com.area120.paperwork`. Observe only; do not change its documents, settings, accounts or backup state.
- **Canonical Stack**: `org.stackdocs.app`, version 0.6.1-dev. Contains the merged personal libraries and approved scanner functionality; update in place only.
- Former Preview: `org.stackdocs.app.verification`. Retired after verified consolidation. Its original library is retained in the canonical app's private recovery archive; do not reinstall an empty Preview copy.
- The comparison scan was titled `Scan 2026-10-16 08.34`; the original Google Stack copy was displayed as `Test`. Use UI labels to locate them; coordinates and foreground state are not stable.
- User authorized temporary comparison captures, kept outside Git and deleted afterward. Check the foreground package before any capture; refuse captures of a lock screen, notification shade or unrelated app. Request the user's help if secure unlocking is needed. Do not dump unrelated personal data or copy private originals indiscriminately.
- Have the user perform physical camera captures; do not photograph their surroundings unattended.

## Build and repository context

Project: `/home/charlie.day/Projects/Stack`. The Git root is its parent, which contains unrelated projects. Scope Git operations to Stack; do not stage or commit siblings. Git writes and USB/build operations required the environment's escalation review in this session.

Cached tools, if still present:

```sh
# SDK: /tmp/stack-dev/android-sdk (ignored local.properties points here)
# adb: /tmp/stack-dev/android-sdk/platform-tools/adb
ANDROID_USER_HOME=/tmp/stack-dev/android-user ./gradlew \
  -g /tmp/stack-dev/gradle-cache \
  :app:assembleDebug :app:assembleDebugAndroidTest \
  --offline --no-daemon --console=plain
```

Java 17 source, installed JDK 25, Gradle 9.3.1, AGP 9.1.1, compile/target API 36, minimum API 24. Prefer the existing cached toolchain; discover alternatives if temporary paths have expired.

Install the single Stack APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, preserving its data. Instrumentation uses `org.stackdocs.app.test`; it must never seed/edit the real library. Build output and third-party tooling remain ignored.

Reference APKs remain untouched in ignored `Reference/`. Decompiled/extracted material was kept under `/tmp/stack-reference-audit/`. Do not commit APKs, Google assets/code/models/fonts, screenshots, personal documents, SDKs, caches or signing keys.

Relevant OCR files: `OcrModel.java`, `OcrEngine.java`, `OcrWorker.java`, `OcrJob.java`, `OcrStatus.java`, `LibraryDatabase.java`, `LibraryRepository.java` and search/status UI. Relevant scanner source files: `PageFlattening.java`, `PageState.java`, `LibraryDatabase.java`, `PageEnhancement.java`, `PageRenderer.java`, `BoundaryDetector.java`, `EdgePageDetector.java`, `CameraController.java`, `ScanDraftStore.java`, `PageEditorActivity.java` and `ScanActivity.java`, under `app/src/main/java/org/stackdocs/app/`.

Current suggestion sources: `SuggestionJob.java`, `SuggestionStore.java`,
`SuggestionRules.java`, `SuggestionWorker.java`, `SuggestionReview.java`; integration
in repository/database, application/OCR worker, draft/scan and document Details.
Checks/scripts/corpus and exact reproduction commands are linked in suggestion evidence.
