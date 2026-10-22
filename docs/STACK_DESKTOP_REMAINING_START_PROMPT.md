# Current synchronization handoff

Read `docs/desktop/CURRENT_RELEASE_HANDOFF.md` FIRST. The latest session stopped
further diagnosis at the user's reported3% weekly quota. The spare's loading bug
was repaired and signed APK/Flatpak candidates were produced, but the actual
phone-to-desktop transfer has NOT passed. Do not call these a stable sync release,
reuse old success claims, or restart broad tests. Preserve the exact current
artifacts/data and continue only the remaining concrete real-session failure.

Resumed closeout: automatic sync is implemented and verified. The current desktop
launcher is `.artifacts/stack-automatic-sync-resume-20261021/desktop/stack`;
signed Android APKs are in the sibling `apk/` directory. The authorized spare was
updated without resetting its library. All eight protected table hashes and the
original/draft file hash match before/after; all164 documents,257 pages and240
memberships remain, while the seven obsolete starter duplicates were removed
(23→16 stacks, zero generated labels). This is the fresh observed baseline,
not the historical175-document checkpoint below. No user sync test is pending.
Routine checks used two lower-cost workers; final static review found no remaining
blocking finding. See the automatic-sync evidence and current results object.

The latest user instruction supersedes all older setup descriptions below:
implement Settings → Sync → Connect phone → approval on phone, then automatic
bidirectional sync. See `docs/desktop/AUTOMATIC_SYNC_20261021.md` and
`docs/STACK_DESKTOP_PHONE_TRANSFER.md` first. Old adoption and foreground setup
screens have been removed. Internal merge, receipts and pinned TLS are reused.
Do not enable a screen reader or change accessibility settings for automation.

## Historical checkpoints

Latest resumed result: actual interface sync succeeded on2026-10-21 at17:04:39
local time. Both libraries retain175 documents/278 pages/25 stacks/248 memberships,
with exact preservation hashes. The repeated-review cache is repaired and compacted
from383,888,840 to2,154,890 bytes. Read
`docs/desktop/SYNC_RESUMED_20261021.md` first. Current runnable package is
`.artifacts/stack-sync-resumed-20261021/stack`. No user sync test is pending.
The user objected to enabling the screen reader for native UI automation; all
accessibility flags were restored to false. Do not enable them again.

Latest2026-10-21 user follow-up reports a real CursorWindow failure and asks the
agent to perform the actual phone-to-desktop sync through both interfaces. That
explicit request supersedes the earlier headless-only desktop restriction for
operating Stack itself and the synthetic-only sync restriction for this transfer.
Keep other desktop apps/windows, the primary phone and private archives untouched.
Current simple UI is desktop Ready to sync → phone Sync now, with advanced routes
behind More options. Read `docs/desktop/SIMPLE_SYNC_CURSORWINDOW_20261021.md` and
latest results before older checkpoints. The exact current spare pre-update
baseline is175 documents/278 pages/25 stacks/248 memberships; it changed during
the user's own setup since the previous18-stack baseline. Preserve the actual
current hashes, not an obsolete baseline. No reset or re-enrollment is authorized
or needed by the CursorWindow repair.


Current2026-10-21 continuation adds the user's requested friendly nearby/QR
pairing, matching-code approval on both devices, and guarded local erase/rebuild
on desktop and Android. Read the latest current results and
`docs/desktop/FRIENDLY_SYNC_RESET_20261020.md` before older baseline text below.
Current runnable desktop is `.artifacts/stack-friendly-sync-20261020`; phone is
`.artifacts/stack-friendly-sync-phone-20261020`. Both use source manifests rather
than assuming HEAD includes preserved Android UI/icon/version inputs. The retained
spare library must remain intact: reset tests use isolated authored child contexts;
the full erase feature request is not permission to erase that retained library.
The focused native review includes named/QR setup, everyday sync and reset warnings.


Updated2026-10-20 after `f41c679`. The user confirms the Android APK was completed
and is in production use on the primary phone. The completed Android H1–H8 session
stays closed. The next work concerns the desktop companion and newly added sync,
limited to concrete defects, unfinished desktop work and integration-specific gaps.
This prompt supersedes earlier instructions to resume general Android P0 testing,
reimplement delivered R1–R7 stages or investigate the missing historical signing key.

Paste the following into a new session in `/home/charlie.day/Projects/Stack`:

```text
Continue the native Rust COSMIC Stack desktop companion and its newly added
Android–desktop sync integration. Work autonomously: implement and fix concrete
unfinished work, verify affected behavior, document and commit coherent Stack-only
increments. Do not stop at another investigation or planning phase. Do not invent
features or defects to fill an empty backlog.

The Android app is completed and the user uses it in production. H1–H8, including
camera permissions/lifecycle, OCR, export/restore and accessibility, are recorded
completed. Do not repeat that checklist, relabel it pending, or use historical
agent-test exclusions as a new Android acceptance backlog. Preserve those reports
without inventing additional platform measurements. Android changes/testing must
address a concrete regression or the newly added desktop–phone sync behavior.

Read the current state, in this order:
1. Applicable AGENTS.md, git status and recent commits. Git root is
   /home/charlie.day/Projects; work only within the Stack project.
2. docs/desktop/REMAINING_RESULTS.json: current status/stages/unresolved entries,
   current desktop source/package evidence and latest Android continuation.
3. docs/desktop/REMAINING_EVIDENCE.md and latest entries in
   docs/desktop/EXECUTION_LEDGER.md. Older entries are source-specific history.
4. docs/STACK_DESKTOP_PLAN.md, particularly the consolidated desktop/sync review
   checklist; docs/desktop/M2_REVIEW.md for exact remaining desktop interactions.
5. docs/desktop/ANDROID_SYNC_EVIDENCE.md and docs/STACK_ANDROID_SIGNING.md.
   docs/p0/TEST_DEVICE_CONTINUATION_20261020.md records the final spare replacement.
6. docs/p1/USER_COMPLETION.json confirms Android acceptance is already completed.
   Consult desktop/README.md, desktop/STORAGE.md, archive/sync contracts and
   docs/STACK_DESKTOP_REMAINING_PLAYBOOK.md only as relevant to the actual change.
This prompt and current evidence take precedence over stale starting-state text.

Preserve the verified baseline:
- Persistent native libcosmic desktop library, previews/edits, metadata, explicit
  saves, portable archives, offline embedded-PDF extraction and English OCR exist.
- Reviewed nonempty adoption, causal conflicts/order/deletion, file exchange,
  native paired TLS controls and Android foreground sync are implemented.
- Current desktop engineering evidence:93 tests passed, all ten unchanged
  timing/memory limits met, six private portal requests passed, fmt/check/clippy/
  build/software checks passed. Earlier import-menu/progress, draft/palette,
  accessibility-tree/chooser-parent and timing fixes are implemented. Do not
  present them as open defects merely because older review tables still list them.
- Actual Android/Rust file exchange, eight populated schema migrations, reviewed
  adoption, eight LAN recovery scenarios and five Android apply-stop boundaries
  passed for their recorded source. They are not merely planned work.
- Desktop package .artifacts/stack-desktop-20261020 was verified against950 payload
  hashes and216 source hashes, with509 dependency notices. It is a Linux debug
  engineering delivery, not evidence of native compositor/release frame budgets.
- .artifacts/stack-single-app-20261020 contains current signed Android artifacts
  and185 exact source files. Its49 payload hashes were independently verified.
  The spare passed53 offline groups, actual same-key update and retained-library
  checks. Its source snapshot includes preexisting UI/icon/version edits.

Pick up only the actual unfinished desktop/integration work:
A. Resolve any concrete user-reported desktop regression or failing current
   source-matched check first. Existing live-compositor frame-stall and native
   shutdown watches remain qualifications, not newly reproduced failures. Use an
   existing trace/reproduction if relevant; do not call the old timing misses
   unresolved or alter budgets to obtain a pass.
B. Complete any demonstrably missing desktop interaction behavior: navigation,
   import/export progress and cancellation, exact edit/draft preservation, keep/
   discard quit flow, palette, matching-page text search, and portal/focus or
   accessibility behavior. Confirm whether a fix already exists before changing
   it. Scoped tree publication and native chooser-parent export are implemented;
   actual screen-reader consumption, dialog placement/focus and physical keyboard
   observations remain distinct from headless tests.
C. Close gaps specific to the new Sync exchange and Paired local connection
   workflows: reviewed separate versus explicitly matched identities, stale review
   rejection, paged conflicts/full selected values, exact metadata/originals,
   replay/deletion convergence, publication versus durable peer acknowledgment,
   fingerprint enrollment/pins, cancel/reconnect/revocation, retained dirty edits,
   foreground cancellation and receipt/status persistence. Use existing evidence;
   add or rerun cases only for changed or genuinely uncovered integration paths.
D. Refresh the runnable desktop delivery and prepare one focused desktop/new-sync
   review using the existing project-plan checklist. A pending human observation
   is not proof of missing implementation. If no concrete engineering defect or
   unfinished implementation is established, say so and prepare the review rather
   than manufacturing changes or repeating Android acceptance.

Execution and data boundaries:
- Use native libcosmic controls, the user's COSMIC theme and
  Mockup/stack-cosmic-desktop-mockup.html as the layout reference. Preserve the
  existing app and supplied mockup; do not rebuild it as a website or new shell.
- Do not open or manipulate visible desktop apps, choosers, browsers or emulators,
  or close the user's windows. Keep agent desktop checks headless/offscreen.
  Prepare concrete launchers/fixtures for remaining physical review at the end.
- The attached spare is disposable Pixel4a/sunfish, serial0A121JEC203530. Every ADB
  command MUST use -P 5693 -s 0A121JEC203530. Launch/UI tests
  on this spare are authorized when relevant. Never access, update, uninstall,
  clear, restore or test against the primary phone. Do not ask to attach it.
- Keep one maintained phone app: org.stackdocs.app. Stack Recovery is retired;
  legacy Checks installations were removed. The non-launcher .test package is
  instrumentation only. Use the original-package app on the spare or isolated
  fixture contexts, not a newly maintained recovery/product variant.
- Normal builds use permanent signer SHA256
  e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311.
  Keep ~/.local/share/stack-signing/development.p12, the private credential config,
  independent encrypted backup and all signing guards intact. Never generate a
  replacement key or put credentials in Git/artifacts. Use update preflight and
  same-key adb install -r for spare updates; do not add automatic uninstall/reset.
  The old-signed primary cannot receive these APKs as ordinary updates. Do not
  reopen key recovery or primary migration as a prerequisite for desktop work.
- Never open private archives or repeat the user's import as a regression test.
  Use independently authored fixtures, explicit disposable desktop library roots
  and isolated XDG data/config/cache/state. Preserve originals, exact typed values,
  decisions and security consent. Portable backup adds copies; it is not sync.
  Do not share live SQLite/WAL or put sync fields into portable archive format3.
- Sync model2 uses a fixed reviewed member list. Dynamic membership, history
  garbage collection, cloud sync and unattended background phone sync are not
  implied new requirements.
- Preserve unrelated dirty work: README and app version changes; Android
  GoogleMigrationChecks/DocumentActivity/Icons/Ui; untracked StackStyle, icon tests/
  docs and Mockup; feature matrix/primary install guide; sibling cosmic-takeout.
  Inspect the actual current diff rather than trusting this list as exhaustive.
  Do not stage, discard or absorb unrelated changes. Artifact provenance must
  identify exact source inputs rather than pretend HEAD alone includes that dirt.

Testing and delivery:
- Use lower-model agents for standard builds/tests/package audits, returning
  exact commands/results to the main agent. Main owns diagnosis, code changes,
  scope and decisions. Existing roles requested gpt-6-luna/medium; use available
  lower-model equivalents if necessary, and do not claim unexposed model metadata.
- Reuse valid source-compatible evidence. Run meaningful affected regressions;
  rerun the full acceptance harness only when the change warrants it. Keep
  locked/offline dependencies, include required native tests and preserve engine
  lifecycle/bounded workers. Do not count ignored tests as passed.
- Existing tool caches may be under /tmp/stack-dev (Android/JDK/Gradle) and
  /tmp/stack-desktop-dev/cargo-home; PDFium/OCR runtimes are under desktop/runtime.
  Check availability before reusing. Restore missing tools only as needed for the
  current work; missing temporary files do not justify touching production data.
- Record failures truthfully and rerun affected checks after fixes. Update current
  evidence/results/ledger and source-matched artifact hashes after code changes.
  Keep historical reports and backups intact. Documentation-only work needs no
  application rebuild or phone test.
- Continue through independent work without routine questions or milestone
  permission stops. Respect tool approval controls; explain genuine blockers
  precisely. If one observation needs human action, finish everything else and
  prepare the smallest concrete review before the final handoff.

Finish with committed changes, exact validation outcomes, runnable desktop and
spare-sync instructions/artifacts, any genuine remaining blocker, and one concise
review list confined to unfinished desktop/new-sync behavior. Do not reopen the
completed Android checklist or imply that production Android acceptance was lost.
```
