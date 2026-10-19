# Next session — Stack for COSMIC in Rust, with phone interoperability and sync

For the remaining project, use the
[remaining-work playbook](STACK_DESKTOP_REMAINING_PLAYBOOK.md) and
[autonomous kickoff prompt](STACK_DESKTOP_REMAINING_START_PROMPT.md). The user
requests headless agent tests and prompting only at the end. Those documents
supersede the earlier UI-only work order below when the kickoff is invoked; they
cover R1 M2 closeout through R7 release, including desktop text search and M3–M6.
Latest import-menu follow-up:50 functional tests/checks pass; two stress timing
targets remain unmet in `/tmp/stack-import-feedback/final-checks/report.json`.
Do not substitute an earlier all-green report or reopen GUI tests.

Current phase: **M2 native visual refinement delivered; remaining user interaction
gates are separate**. The user reports the interface is “much, much better.” Latest
feedback concerns rendering speed, OCR search and phone transfer/synchronization.
M0/M1 are delivered through `91fdffb`. M2 implements persistent browsing, saved
appearance, search/catalog, exact-value editors, bounded imports/archives and
snapshot PDF output. Follow current [M2 evidence](desktop/M2_EVIDENCE.md),
[results](desktop/M2_RESULTS.json), [ledger](desktop/EXECUTION_LEDGER.md) and
[rulebook](desktop/M2_RULEBOOK.md); discover HEAD before editing.

The user's latest instruction explicitly authorizes running the app and comparing
native screenshots with the supplied HTML for this UI-only refinement. This
supersedes the preceding headless-only window restriction within that scope. Native
captures must still show only the app's own window with independently authored
PDFs and isolated XDG/library roots. Preserve user windows, theme settings, canonical
libraries, the supplied HTML and unrelated dirt. No M3 work is authorized here.

The current refinement starts from `574bbec`. Stack destinations remain exclusively
in a flat native sidebar. Library tiles use real saved-first-page thumbnails with
names, actual added dates and a stack, decoded only around the viewport and bounded
to the 50-result page. File icons are loading/error/unsupported fallbacks. Grid/list
rows use bounded layout work while retaining complete paging and scroll range.
Search/Add are hidden in document workspaces; Back appears only with a destination.
Native header/window titles include the current context. Preview preserves aspect
ratio, zoom and pan. Information/Details/Pages stay beside it; narrow information
layouts retain the page above controls. Chips expand into membership choices;
explicit save/recovery/draft semantics remain. No schema/original/journal/sync
semantics were changed; the new tile API is read-only.

Read [M2_INTERFACE_REVIEW.md](desktop/M2_INTERFACE_REVIEW.md) for the primary mockup
mapping and actual browser/native screenshot comparison. Readable PDF fixtures are
authored by `desktop/tools/prepare_ui_visual_review.py`; the restricted
`--visual-review ROOT --smoke-output NEW_DIR` route verifies all six source hashes
and saved graph. Current captures: `/tmp/stack-ui-refinement/native-confirmed`, with
browser references at `/tmp/stack-ui-refinement/mockup-*-1080x860.png`. Both browser
and native windows are1080×860. The native run uses the installed COSMIC theme
copied into the disposable config and the normal wgpu renderer.

The follow-up adds session caches for100 recent thumbnails and three viewer pages,
with saved-appearance/source identity invalidation. Development/test builds optimize
only rendering and image processing. Initial overscan avoids laying out an unnecessary
extra row. Document information now reports current searchable page text. Readable
visual PDFs have no saved OCR; ordinary desktop import does not run OCR or extract
embedded text. Existing compatible phone OCR is searchable after archive transfer.
See the [phone transfer guide](STACK_DESKTOP_PHONE_TRANSFER.md) for actual menus,
persistent-library launch and additive-copy semantics. No sync has been implemented.

Latest automated report: `/tmp/stack-ui-followup/report.json`, with raw final checks
in `/tmp/stack-ui-followup/final-checks/report.json`. Latest1080×860 native captures:
`/tmp/stack-ui-followup/final-native`; preservation/scope in `visual-report.json`.
The previous `/tmp/stack-ui-refinement/acceptance-checks/report.json` and browser
comparison remain evidence for that earlier revision. Intermediate reports are
retained, not current source proof.
The native appearance comparison is agent-performed; full M2 user acceptance,
physical chooser/input and the existing AT-SPI/native platform qualifications are
separate. Keep the one consolidated user checklist in the project plan. No private
phone archive, Android build/device or unrelated project may be used for these tests.

Read the [ledger](desktop/EXECUTION_LEDGER.md), [M1 evidence](desktop/M1_EVIDENCE.md)
and [storage contract](../desktop/STORAGE.md). Preserve Android 0.8.9/code22/schema8/
archive3 follow-up edits, the M0 shutdown watch and M1 compatibility qualifications.
The user identified a private phone `.stackbackup` reference (see the handoff);
it remains unopened and outside synthetic tests. No M3–M6 sync capability is delivered.

## Historical full product work order

The text below preserves the broader product requirements. For the next session,
paste the M2 prompt in the handoff above. This historical prompt's begin-from-scratch,
0.8.7, old phone-transfer status and Kotlin wording are superseded by current source
and evidence; the Android implementation is Java. Do not restart M0/M1 or H1–H8.

```text
Begin development of a native Rust desktop version of Stack for COSMIC on Pop!_OS.
Work in /home/charlie.day/Projects/Stack, with desktop code under Stack/desktop/.
The Git root is /home/charlie.day/Projects. Preserve unrelated edits, especially
cosmic-takeout and the feature matrix's formatting-only OCR-row change. Do not
modify or borrow cosmic-takeout. Read all applicable AGENTS.md instructions first.

Read README.md, docs/STACK_SESSION_HANDOFF.md, docs/STACK_ARCHIVE_FORMAT.md,
docs/STACK_FEATURE_MATRIX.md, docs/STACK_P1_EVIDENCE.md,
docs/STACK_UNLOCK_AND_LIBRARY_TOOLS.md, docs/p1/UNLOCK_LIBRARY_TOOLS.json,
docs/STACK_PRIMARY_PHONE_INSTALL.md and relevant Android repository/archive/
metadata/search/recovery source and synthetic fixtures. The old P1 exclusion of
desktop is superseded for this new desktop work order; it does not authorize an
Android rewrite or deletion/migration of my current personal library.

Confirm actual Git/app/toolchain state before changing anything. Android closeout
baseline is0.8.7-dev/code20/schema8/archive3, implementationb5cc48e; later commits
record evidence/docs, so discover HEAD rather than assuming a commit. Its37 affected
automated groups pass, five canonical preservation fingerprints match, and the
earlier H1–H8 session is completed,user-reported. Do not restart Android P1/H testing.
Only the new direct native-unlock behavior and actual primary-phone transfer were
left as focused user actions. Current143docs/230pages/17stacks/216memberships were
historical observations, never targets. Original app lock was enabled; preserve
actual current policy, not historical default-off assumptions.

Product goal: a usable native COSMIC document library in Rust. Documents, originals,
page appearance/order/edits, names, multiple stack memberships/order/visibility,
notes, exact typed details, source provenance, manual corrections/removals and saved
OCR/searchable text must move safely between phone and desktop. The intended end
state is opt-in bidirectional synchronization, with each device usable offline.
Manual transfer is a fallback, not a claim that automatic sync is complete.

Use native libcosmic, not a browser/Electron shell. Consult current official sources:
https://github.com/pop-os/libcosmic
https://pop-os.github.io/libcosmic/cosmic/
https://github.com/pop-os/cosmic-app-template
https://pop-os.github.io/libcosmic-book/
Inspect installed Rust/COSMIC and available caches/dependencies, then pin compatible
revisions and commit Cargo.lock. Use current official APIs and independent open-source
libraries/assets with recorded licenses. Preserve Stack's simple organization and
recognizable accents while following COSMIC navigation, theme, keyboard/focus and
window conventions. Use a distinct desktop app ID/data directory, such as
org.stackdocs.desktop with standard XDG locations. Never open the Android live DB
as the desktop DB or share a SQLite/WAL file between devices.

Begin implementation this session, not just planning. Create docs/STACK_DESKTOP_PLAN.md,
docs/desktop/EXECUTION_LEDGER.md and a versioned interoperability/sync contract, then
implement the first integrated desktop milestone continuously:

1. Persistent core and safe interchange. Separate Rust library/data/archive logic
   from the COSMIC UI. Own transactional storage, immutable originals, migrations,
   bounded background work and interrupted-operation recovery. Read current Stack
   archive formats1/2/3 using their exact contracts and safe older defaults; export
   a phone-readable format3 without losing supported data. Reject unsupported/
   invalid fields or versions rather than silently discarding them. Never overwrite
   existing originals. Distinguish additive backup restore from a future sync merge.

2. A usable desktop loop. Provide stacks/sidebar, document list/grid, preview with
   page navigation/zoom, text search/filter/sort, names/filing, saved details and notes
   editors, and visible import/export/progress/retry/error states. Import supported
   files and synthetic phone archives into a disposable desktop library. Persist
   edits and rebuild derived search through restart. Preserve exact decimals and
   currency/date precision, explicit choices/tombstones, provenance and original
   page edits/order. Keep manual decisions authoritative. Existing OCR text should
   work immediately; do not make OCR/model parity a blocker to archive browsing,
   search and safe editing, or falsely claim an unimplemented OCR/camera feature.

3. Sync designed into the data model now. Define library/document/field/stack IDs,
   replica identity, a durable change journal, acknowledgments, per-peer progress,
   deletion/removal tombstones, retry identity and deterministic conflict handling.
   Existing local revisions and timestamps are not globally comparable clocks.
   Preserve exact source identity separately from any backup collision remapping.
   Explain how initial pairing establishes a common baseline, including libraries
   descended from the same archive. Matching titles or file hashes alone must never
   silently merge distinct documents. Repeated exchange must be idempotent.

   Merge independent changes where safe. Surface concurrent edits to the same name,
   note, field, page or ordering for review; preserve both conflicting values and
   their provenance. Define delete-versus-edit, explicit empty membership, hidden/
   deleted/reordered stacks, edited-versus-rejected details and disconnected-device
   behavior. An old peer must not resurrect a deletion. Define tombstone retention/
   acknowledgment before garbage collection, and never silently delete the last
   original or let a stale worker replace a newer user choice.

4. Build and test the first real exchange path with synthetic replicas. Keep the
   merge engine separate from transport. A user-chosen folder/USB-carried exchange
   bundle is a reasonable initial transport; demonstrate two Rust replicas converging
   with replay, interruption, out-of-order delivery and explicit conflicts. Do not
   relabel ordinary backup re-import as sync: Android currently adds copies on repeat
   restore and has no sync journal/protocol. A Rust-only exchange is not proof of
   phone-to-desktop bidirectional sync. Document and implement the smallest justified
   Android companion integration when the core contract is ready, using additive
   migrations/version negotiation and disposable org.stackdocs.app.checks only.

   The intended convenient transport is paired local-device sync, preferably local
   network without a cloud account, central service or required always-on daemon.
   Choose the concrete path after inspecting current platform capabilities. Use
   explicit pairing/consent, bounded authenticated transfers, content hashes and
   maintained standard secure transport; never invent cryptography or expose an
   unauthenticated library endpoint. Networking/sync remains off until enabled.
   Android currently has no INTERNET permission: any required local-peer permission
   or service is new scoped integration, not an incidental planning change. Follow
   current official Android background/lifecycle/permission guidance and explain OS
   scheduling limits honestly. Define offline/status/retry/conflict UX; no promise
   of background continuous sync before it actually works on both platforms.

Safety and compatibility are part of the implementation, not later polish. Library
content and decisions may sync; device lock/authentication/enrollment, photo monitoring,
notification permissions, filesystem paths/grants and unfinished editor/scan drafts
must not become remote authorization. Restore/sync never weakens an existing lock or
enables monitoring. Do not claim at-rest encryption from an app gate or hash. Current
portable backups remain unencrypted; do not replace their format with custom encryption.

Never clear/uninstall/reseed canonical org.stackdocs.app, replace its DB, recreate
Preview, delete/restore the personal library or create a personal backup as a test.
Preserve originals/drafts/files/consolidation_recovery and original signing key.
No private document logs/screenshots, Google backup as regression input, proprietary
reference code, cloud accounts, remote backends, runtime model downloads or telemetry. Use independently
authored synthetic fixtures and temporary desktop XDG roots/disposable Android
package for all mutations, archives, failures, auth and sync. The private delivery
~/Downloads/Stack-0.8.7 and original /tmp/stack-google-migration sources are real
user material, not fixtures. Keep binaries/keys/caches/private archives/screenshots
out of Git. Physical unlocking, camera, pairing/permission choices belong to me.

Before any canonical Android update, first pass isolated checks, verify the original
installed/new signing identity and fresh aggregate/row preservation, build WITHOUT
-PstackChecks, use adb install-r only, compare afterward and reopen. Existing signing
environment is ANDROID_USER_HOME=/tmp/stack-dev/android-user and
GRADLE_USER_HOME=/tmp/stack-dev/gradle-cache; cached Gradle/SDK under/tmp/stack-dev.
Original cert SHA256 db7352c8df2d3643ae9dc119ffd73ee26a700808d53efad3cf60a753e620eeab.
Discover actual paths/device state; never fix a mismatch by removing the app.

Author cross-language golden archive fixtures from synthetic Kotlin/Rust libraries:
Rust reads Android output and Android reads Rust output, preserving the complete
metadata/original graph, with old-format/future-version/corrupt/oversized/path/reference
rejection checks. Exercise desktop restart, transaction/disk/provider failure and
sync replay/concurrency/deletion/no-resurrection/convergence in isolated contexts.
Run appropriate cargo fmt/check/test/clippy/build and Android affected checks when
changed. Run the COSMIC app and inspect synthetic narrow/large-text/light/dark states
where available; distinguish source inspection, model fixtures, actual runtime,
agent judgment and human reports. Do not claim unperformed GUI/phone-sync tests.

Commit coherent Stack-only increments and keep the ledger updated across compactions.
Resume the next unfinished item rather than restarting. Do not pause for routine
milestones or unavailable human/hardware feedback while independent work remains.
Finish with a runnable, persistent integrated desktop milestone, installation/run
instructions, actual evidence in docs/STACK_DESKTOP_EVIDENCE.md and docs/desktop/,
updated interoperability/archive/version docs where needed, and the next-session
prompt. Clearly separate completed portable transfer, tested synthetic sync core,
actual Android interoperability and still-pending automatic bidirectional sync.
Group remaining user-dependent checks into ONE checklist at the end. If a toolchain,
dependency or desktop runtime is unavailable, complete all independent authorized
work and record the exact blocker; do not pretend a static mockup is a working app.
```

The first milestone is intentionally bounded; full Android feature parity and
convenient automatic phone sync require their own demonstrated implementation.
The official toolkit links above were checked when this prompt was written;
recheck current APIs and pin actual dependencies when executing it.
