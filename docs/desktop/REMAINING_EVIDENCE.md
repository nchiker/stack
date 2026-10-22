# Remaining-work evidence checkpoint

## Latest friendly sync and guarded local recovery — 2026-10-21

The user's named-device/QR and careful local reset requests are implemented in the
existing native interfaces. Desktop **Set up a phone → Connect a phone** shows a
friendly invitation and QR; phone **Find my computer** / **Scan computer QR code**
share matching-code approval on both devices. No UUID/fingerprint typing is needed.
Library adoption remains a separate review. Everyday sync uses desktop **Allow one
connection**, then phone **Sync now**, with the saved verified address. A new
invitation refreshes the same active device after an address change. See the
[shared guide](../STACK_DESKTOP_PHONE_TRANSFER.md) and
[protocol/reset evidence](FRIENDLY_SYNC_RESET_20261020.md).

Both apps offer **Backup & restore → Reset or rebuild library…**. Full erase has
three warning stages and an exact local phrase, with explicit checked-backup and
never-erase-both-copies warnings. The separate connection reset keeps documents.
Reset completes at cold restart with a durable quarantine/recovery journal; it
never erases a remote device. Recovery offers backup restore or fresh reviewed
setup. Destructive Android checks use isolated authored child libraries only.

- Source-matched desktop acceptance: **105 passed/0 failed/0 ignored**, all **ten
  unchanged timing/memory limits** and **six private portal requests** pass.
  Fmt/check/strict Clippy/build/software checks pass; all248 report input hashes
  match the current tree. The synthetic portal does not establish native chooser
  placement/focus. Retained report:
  `.artifacts/stack-friendly-sync-20261020-evidence/acceptance-passed/report.json`.
- Android/Rust authored runtime: desktop-first verification, declined library
  review without mutation, same-device resume/adoption, phone-first group with
  desktop review, and ordinary two-way sync pass. Both pairs converge to2
  documents/4 pages/2 originals. Unilateral approval/cancel and wrong QR pin refuse
  enrollment. Initial operator/test-harness failures remain in separate raw logs.
- Reset: four Rust core groups and eight isolated Android groups pass, covering
  actual cold SQLite reset, crash/publication/interrupted rollback, preserved
  graph/originals for connection reset, independent backups and symlink refusal.
  Native desktop exact-phrase/cancel and phone three-warning/cancel checks pass;
  the real phone final destructive callback is never invoked. Actual Rust QR image
  decoding on Android passes; physical camera acquisition remains unobserved.
- Desktop delivery `.artifacts/stack-friendly-sync-20261020`: **952 payload hashes**,
  **227 exact receipt source hashes**, **510 dependency notices** verified. Separate
  exact source evidence archive is verified too. Isolated install/CLI/uninstall
  preserves unrelated prefix/XDG sentinels.
- Fresh `.artifacts/stack-friendly-sync-review-20261020` fixtures: **102 CLI
  commands**, **eight launchers**, **410 inventory hashes**,5/60 browser documents,
  dedicated5-document reset/empty phone desktop libraries, unapplied adoption and
  43 conflict cells/86 alternatives. Repeat preparation refuses and preserves every
  existing file. No desktop windows/listeners are opened during preparation.

Final phone delivery `.artifacts/stack-friendly-sync-phone-20261020` passes offline
main/test/lint build (**0 errors/18 warnings**), public-signer preflight and both
same-key `install -r` updates. Its **seven native UI groups** include the final
friendly-name/next-step message and rescan routes. Canonical **175/278/18/248**
counts and all five aggregate hashes remain unchanged. Final main APK SHA256 is
`44db6462a9e51aa225ffe3771a13352e79818759e147eca7d9aa29d8656fa93b`;
instrumentation is
`437622dc006ce69348d278937d0a9fd6a46f62341bac5f45e33494833d6060fc`.
Independent audit verifies **45 payloads/199 source files**, including all168
Android source/build inputs. Exact snapshot retains preexisting UI/icon/version
edits outside this commit. Preflight/install receipts explicitly distinguish
reconstructed tool observations from retained raw check logs. Unchanged protocol/
reset evidence is reused across final UI-only changes. Authored screenshots precede
the final ready-message correction; final text is asserted by the final native UI
run. Final audit receipts are the sibling `stack-friendly-sync-20261020-verification.json`
and `stack-friendly-sync-phone-20261020-verification.json` under `.artifacts`.

Main owns implementation/diagnosis; lower-model routine roles return observed
results. Focused read-only contract review approved after identity/deadline/reset
journal fixes. Historical pre-navigation/manual setup reports below retain their
source-specific meaning. Android H1–H8 remains completed and the primary untouched.


## Latest guided-sync interface repair — 2026-10-20

The user reported a concrete usability failure in the phone Sync screen and then
in the desktop process and placement. The new interfaces now agree: desktop
**Sync with phone** is a persistent sidebar footer tool above Backup & restore;
Android **Sync with a computer** uses Stack's usual action rows. Setup, file
exchange, Wi-Fi and details are separate pages, with real parent Back navigation
and instructions naming the other device's next control. Native desktop identity
selection uses a chooser; known two-device membership fills the receiving peer.
Explicit matching uses named dropdowns and preserves the original validation.
Phone identity export remains available after verifying the computer, so setup
can be completed in either verification order. Full fingerprints remain explicit.

- Phone: offline debug/instrumentation build and lint pass (**0 errors/14 warnings**).
  Both same-key `install -r` updates succeeded on the explicitly authorized spare.
  **7/7 sync UI groups pass** at320dp and1x/2x text, including the actual Activity's
  parent Back route. All **175 documents/278 pages/18 stacks/248 memberships** and
  five metadata/draft/order/OCR/original aggregate hashes remain identical to the
  immutable pre-update baseline. Tests neither changed enrollment nor imported
  user content. Public final APK hashes are in REMAINING_RESULTS.json.
- Desktop: **95 passed/0 failed/0 ignored**, all **ten unchanged timing/memory
  limits met** and **six private portal requests passed**. Full harness:
  `.artifacts/stack-desktop-sync-navigation-20261020-evidence/acceptance-passed/report.json`.
  Afterwards, only sync-page link styling changed to native button surfaces,
  bold labels and chevrons; current-source fmt/check/clippy/build and guided native
  pointer/screenshot checks pass. The report's one-file delta is recorded explicitly,
  not treated as byte-identical source. No extra full-suite repeat was needed.
- Initial compiler/clippy, sandbox socket and navigation-test failures were fixed
  or rerun under the required local IPC access. The first Android raw lint-failure
  log was accidentally overwritten; its observed one-error/fourteen-warning
  diagnostic is recorded as such, never represented as a retained raw log.
- Main owns design, implementation and diagnosis; lower-model roles ran routine
  checks and package work and returned exact outcomes. No visible desktop app,
  primary phone, private archive, signing credential or unrelated dirty work was
  manipulated. The phone UI update includes the existing UI/icon/version inputs.

Current delivery paths: `.artifacts/stack-sync-navigation-20261020` and
`.artifacts/stack-desktop-sync-navigation-20261020`. The
[shared guide](../STACK_DESKTOP_PHONE_TRANSFER.md) explains the actual process.
Final desktop package audit verifies **950/950 payload hashes**, **222/222 current
source hashes** and **509 dependency notices**. Isolated install/CLI/uninstall
passes and preserves both disposable sentinels. Fresh review fixtures at
`.artifacts/stack-desktop-sync-review-20261020` pass **391/391 inventory hashes**
and **100/100 packaged CLI commands**, with six launchers, 5/60-document libraries,
unapplied adoption and 43 conflict cells/86 alternatives. Consolidated receipt:
`.artifacts/stack-desktop-sync-review-20261020-verification.json`.
Final phone audit verifies **48/48 payload hashes** and **185/185 source files**
against both the current tree and frozen archive. The corrected APK path in the
shared guide is included in that snapshot; no rebuild or device repeat was needed.
Receipt: `.artifacts/stack-sync-navigation-20261020-verification.json`, **20 checks
passed/0 errors**. Source snapshots retain the preexisting Android build inputs.
Android H1–H8 stays completed. Native desktop/compositor/chooser/screen-reader
observations and user confirmation of the revised flow remain qualified.

## Earlier package-backed review checkpoint

Current desktop/sync continuation: Android is completed and in production use;
H1–H8 stays closed. The [active handoff](../STACK_DESKTOP_REMAINING_START_PROMPT.md)
supersedes historical general Android acceptance language below. No new desktop
application defect has been established in this continuation. The existing
93-test/ten-limit/six-portal desktop evidence is retained for unchanged source.
The [focused review](M2_REVIEW.md) uses the frozen desktop package and fresh
authored libraries; native physical observations remain unverified.

Fresh lower-model delivery/review audit:

- Desktop payload **950/950**, archived sources **216/216**; current tree
  **215/216**. The sole difference is `desktop/tools/java_archive_host.py`:
  `74957b5` changed the extracted Java `Progress` signature from `static final
  class` to `static class`. This Android host-probe helper is not a desktop
  application/runtime input. Reuse the verified binary and Rust acceptance;
  the whole current tool tree is not byte-identical to the package.
- Current signed Android payload **49/49**, archived sources **185/185**;
  current **183/185** at audit time, with only handoff/ledger documentation drift.
  Exact snapshots remain the artifact references, including preserved preexisting
  Android UI/icon/version build inputs. No phone action or rebuild.
- `python3 tools/prepare_desktop_sync_review.py --output
  /home/charlie.day/Projects/Stack/.artifacts/stack-desktop-review-20261020`
  completed **100 packaged CLI commands**. Browser libraries have **5/60 documents**;
  nonempty adoption is unapplied; paired replicas agree on **43 conflict cells /
  86 alternatives**, with no gaps and valid projections. Concurrent title edits
  retain both title and decision cells; the initial review-text estimate of44
  alternatives omitted decision cells and was corrected, not a product failure.
- All **six launchers** and the package wrapper pass `sh -n`. Repeating the same
  output is refused; all **391 preexisting fixture files** retain their hashes.
  Pairing identities remain uninitialized; no network or visible desktop action.

Reports, exact commands, logs and source-drift hashes are retained in
`.artifacts/stack-desktop-review-20261020/validation/`. `review.json` binds fixtures
to the package receipt SHA256. The detailed checklist is the existing
[focused native review](M2_REVIEW.md), with authoritative checkboxes in the plan.
Stale unsigned-candidate/Recovery instructions were corrected in the transfer and
sync guides. The primary signer is an operating boundary, not a desktop
engineering blocker or reopened Android checklist.

Final standalone `.artifacts/stack-desktop-review-20261020-verification.json`
passes **400/400 review inventory hashes**, **4/4 preparer/guide source hashes**,
all100 command exits and exact661-character notes on both peers. An output outside
the permitted disposable roots is refused with exit2 and no directory creation.

Latest Android continuation: the user explicitly authorized replacing the
disposable Pixel4a only with one original-package Stack using the permanent key.
Stack Recovery is retired. See the
[current device/P0 record](../p0/TEST_DEVICE_CONTINUATION_20261020.md) and signing
guide for current APKs and checks. Historical unsigned-candidate and no-canonical-
mutation statements below describe the earlier checkpoints. The primary remains
untouched; no original private key recovery is claimed.

## Current continuation — 2026-10-20

Desktop graph-model-2 adoption/conflicts/file exchange and paired TLS native
controls are implemented. The initial continuation report
`/tmp/stack-resume-20261020-acceptance/report.json` passed 90 tests and all ten
unchanged timing/memory limits. After ordering/native-network additions,
`/tmp/stack-resume-20261020-final/report.json` passes **93 tests / 0 failed /
0 ignored**, fmt/check/clippy/build/software and all six private D-Bus portal
requests. That report **fails** its performance gate: commit/browse **32158ms**
against 30000ms, executor gap **156522us** against 150000us. First draw147ms and the
other seven targets pass. Both source-specific observations are retained.

| Additional check | Observation | Artifact |
| --- | --- | --- |
| Shared causal Java/Rust corpus | Ten host Java cases plus one rejected case pass | `/tmp/stack-resume-java-sync/report.json` |
| Portable compatibility | Format1/2/3 host chains,21 differential cases,233 currency codes pass | `/tmp/stack-resume-regressions/java/report.json` |
| Actual sync process stops | Seven SIGKILL/replay boundaries pass | `/tmp/stack-resume-regressions/sync-stops/report.json` |
| Actual archive process stops | Thirteen SIGKILL/recovery boundaries pass | `/tmp/stack-resume-regressions/m1-stops/report.json` |
| Complete saved ordering | Concurrent page/catalog orders and removed-page/unseen-appearance review, resolution, restart and unchanged originals pass through SQLite/files | `/tmp/stack-resume-order-review.log` |
| Native LAN handlers/widgets | Fingerprint mismatch, enrollment, canceled listener, both endpoint roles, exact notes, retained dirty title, original hashes and revocation pass | `/tmp/stack-resume-native-network-real.log` |
| Standalone transport | Nine tests pass, including waiting/blocked-handshake cancellation and same-owner reuse | `/tmp/stack-resume-transport-cancel.log` |

The native **Sync exchange** screen includes a source-matched paged adoption review,
40-alternative conflict pages, file publication/import and paired connection controls.
The paired flow borrows the existing repository owner; credentials stay local.
Cancel stops accept and active TLS I/O; connect establishment is bounded to eight
seconds. A broken receipt does not undo a committed import. Status/drafts refresh
after uncertain apply. Other library operations can report busy during the session.
No daemon, listener at launch or automatic discovery is enabled.

Scoped dev/test optimization was extended to archive/core CPU loops and native
widget layout after the measured misses. The new report at
`/tmp/stack-resume-20261020-optimized/report.json` **passes all93 tests and all ten
unchanged timing/memory limits**, with successful fmt/check/clippy/build/software
and all six portal requests. Commit/browse14327ms; maximum executor gap136501us.
The verifier confirmed all214 Rust/Cargo source hashes match current files. The
failing report remains historical evidence, never relabeled. Standard checks use two read-only verifier agents
requested as `gpt-6-luna`/medium, following the user's latest preference. Main-agent
implementation/diagnosis decisions remain separate from mechanical execution.

Prepared-package checkpoint `/tmp/stack-desktop-20261020-final` verifies950 file
hashes,509 dependency notices, shell syntax and isolated install/update/uninstall
preserving its synthetic library. Its complete source archive resolves673 packages
locked/offline with an empty Cargo cache. It precedes the compiler-optimization
delta; final source-matched delivery is prepared separately under the workspace's
Git-ignored `.artifacts/stack-desktop-20261020`. Current delivery disposition is in
REMAINING_RESULTS.json, never inferred from an older package.

Final delivery **`.artifacts/stack-desktop-20261020`** now passes all950 payload
hashes and all216 recorded source hashes, wrapper syntax, fresh isolated install,
packaged CLI status, uninstall and sentinel preservation. It contains509 dependency
notices and complete offline Cargo source. Earlier unchanged full update/uninstall
and673-package offline resolution proofs are reused; no separate full source rebuild
is claimed. Exact package/binary hashes and commands are retained in
REMAINING_RESULTS.json and `.artifacts/stack-desktop-20261020-verification.json`.
This is a source-matched debug engineering delivery, not full Android/native-human
release acceptance. Installation/run instructions are in
[`desktop/README.md`](../../desktop/README.md#offline-document-text-and-prepared-installation).

The user subsequently attached a spare phone. Its isolated r5/r6 Checks apps now
pass actual bidirectional Android/Rust file and Keystore mTLS LAN scenarios,
all8 populated schema migrations and retained-original/replay/conflict checks.
Exact evidence, failures, source snapshots and qualifications are in
[Android sync evidence](ANDROID_SYNC_EVIDENCE.md). Canonical apps/data were
preserved. Final continuation additionally passes13 host adoption cases, real
separate/matched adoption,8 LAN recovery checks and5 actual Android force-stop
boundaries. Foreground network controls and persistent receipt status are implemented.
Current archive format1/2/3 chains and21 differential cases/233 currencies pass.
The final package `.artifacts/stack-android-sync-20261020` contains Checks APKs,
exact167-file source snapshot, evidence and the normal unsigned code22 candidate.
Release build/lint passes (0 errors/16 warnings); Checks lint has0 errors/17 warnings.
No available key matches the installed canonical signer, so the normal update is
prepared through unsigned-candidate stage only. No app/data was replaced to bypass it.
Android maximum-library timing and other device/API versions remain unmeasured;
normal install and physical/native acceptance remain user-dependent.
The [single user checklist](../STACK_DESKTOP_PLAN.md#one-consolidated-user-dependent-checklist-for-implementation-closeout)
retains physical/native observations. All development inputs are independently
authored, all test libraries disposable, no windows opened.

## Historical foundation — 2026-10-18

2026-10-18; ongoing implementation from `fe07fad`, not a final release report.
R1 engineering and R2 offline text are verified with the qualifications below.
Machine-readable outcomes and source deltas are in [REMAINING_RESULTS.json](REMAINING_RESULTS.json).
All inputs authored independently, all libraries disposable, no windows opened.
Existing unrelated dirty Android/UI/docs/Mockup files remain preserved.

Observed checks:

| Check | Exact observation | Artifact |
| --- | --- | --- |
| Core queue/storage tests | 6 unit + 15 prior integration tests passed | `/tmp/stack-resume-core-tests.log` |
| Real offline PDF/OCR | 1 native integration scenario passed embedded pages, page-two hit, image scan, image-only PDF, blank page, crop exclusion, original preservation and restart | `/tmp/stack-r2-real-text.log` |
| Three Rust replicas | 1 real SQLite/file scenario passed adoption into empty peers, forwarding, independent/concurrent edits, resolution, blocked backup, deletion/replay/restart/original retention | `/tmp/stack-r3-exchange.log` |
| Java causal golden | 5 cases passed on host JVM with AOSP JSON; Android runtime false | `/tmp/stack-java-sync-golden2/report.json` |
| First diagnostic stress | First draw145ms, executor gap157145us; executor target fails | `/tmp/stack-r1-profile2.log` |
| Text-shaping-only optimization | First draw173ms, executor gap181731us; both targets fail | `/tmp/stack-r1-optimized-counts.log` |
| Subsequent workspace run with optimized iced_graphics | 56ms/63798us, cumulative-process RSS626476KiB; suite failed palette-accessibility assertion, so no acceptance pass | `/tmp/stack-remaining-workspace1.log` |

Never substitute the cumulative workspace RSS for the fresh-process count run.
The historical `/tmp/stack-import-feedback/final-checks/report.json` failures remain
valid observations for their recorded source. New source requires fresh acceptance.

Implementation currently includes detail draft retention, dirty quit choices,
native palette/custom color, an actual AccessKit child-tree update path, owned native
window handles for asynchronous chooser-parent export, schema-3 durable text jobs,
pinned offline English Tesseract assets and the existing PDFium lifecycle. Physical
screen-reader, chooser parenting, keyboard and visual acceptance remain unobserved.

The causal/exchange implementation is a preliminary versioned subset. Empty-peer
baseline adoption is explicit; existing nonempty libraries are refused until reviewed
source-ID mapping exists. A written exchange file is not acknowledged sync. Java
conformance is host evidence only: Android authoritative journaling, conflict/apply
UI, actual device exchange and authenticated LAN are still unfinished.

Runtime preparation, without opening an application:

```sh
python3 desktop/tools/setup_ocr.py
export STACK_TESSERACT_LIBRARY="$PWD/desktop/runtime/ocr/lib/libtesseract.so.5"
export STACK_TESSDATA_DIR="$PWD/desktop/runtime/ocr/tessdata"
export OMP_THREAD_LIMIT=1
```

The setup command refuses an existing destination; reuse the verified existing
runtime rather than rerunning it. Engine/model SHA-256 pins live in the setup and
native loader. Supported OCR configuration is English with the recorded Ubuntu
24.04 x86-64 Tesseract/Leptonica ABI. Embedded extraction is measured separately
from the small authored OCR scenario, which is not a general accuracy estimate.

Run the remaining acceptance harness in a fresh output directory after integration
fixes. Preserve failures and refresh this checkpoint with source hashes and the
final exact outcomes. The execution ledger and playbook track unfinished gates.

## Verified foundation checkpoint

- `/tmp/stack-remaining-foundation-20261018-final`: fmt, locked/offline check, clippy (workspace warnings denied), build, **64 workspace tests / 0 failed / 0 ignored**, and software-only check passed. Vendored upstream dependency warnings remain logged separately.
- Fresh 1000-document/10000-page process: first layout/draw **122ms**, maximum executor tick gap **124739us**, peak RSS **430832KiB**; all ten unchanged targets met. Native compositor/release timing remains unmeasured. Earlier failing runs remain above.
- The combined harness stopped only because the sandbox denied the private D-Bus socket. A separately approved headless rerun at `/tmp/stack-remaining-foundation-portal/report.json` passed all six actual portal requests, with application exit0 and no failures. No chooser UI/native parent exists in that fixture.
- `/tmp/stack-remaining-m1-regressions/stops/report.json`: all **13 actual SIGKILL/restart boundaries** passed. `/tmp/stack-remaining-m1-regressions/java/report.json`: format1/2/3 host Java/Rust round trips and differential cases passed (233 currency codes). Device limitations remain explicit.
- After that suite, added a sync-only provisional-view tombstone guard. `/tmp/stack-sync-tombstone-recheck.log`: **23 core tests passed**, including the new no-resurrection regression; final workspace clippy/build passed. The source delta is recorded, not represented as identical to the earlier full-suite source. This does not change the normal non-adopted M2 timing path.
- The scoped toolkit patch fixes both native tree publication and the wrapping-row traversal omission. A headless test calls the actual adapter tree builder and verifies target-only activation. Physical screen-reader and native chooser parenting remain final user observations.
- Ordinary desktop import now passes the real repository/native text worker/index/UI/search path while preserving and safely rebasing an exact typed draft. English OCR and embedded extraction remain separately labeled.
- `/tmp/stack-desktop-foundation-package-20261018`: prepared debug package. Isolated install/CLI status/uninstall and shell syntax checks passed; library remained. Dependency-notice gaps are explicitly still open, so this is not a qualified public release package. Its compiled binary precedes the later sync-only guard; final delivery must rebuild/repackage.
- Official emulator and Android36 Google APIs image were provisioned into `/tmp/stack-dev/android-sdk`; a separate headless Checks AVD is being prepared. No canonical phone access or mutation occurred.
