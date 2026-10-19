# Stack remaining-work playbook

Updated 2026-10-18. This is the execution order for finishing the desktop companion,
document-text search and safe phone/desktop synchronization. Start with the
[kickoff prompt](STACK_DESKTOP_REMAINING_START_PROMPT.md).

## Run autonomously; involve the user at the end

The user requests one continuous run, with questions and review at the end. Read
the repository instructions, make conservative implementation choices, record them,
and continue through the unfinished work below. Milestones, commits and passing
tests are progress markers, not permission checkpoints. Do not stop to ask about
routine design choices, optional preferences or whether to continue.

Run agent-driven tests headlessly. Do not open app, browser, chooser or emulator
windows on the user's desktop. Headless widget rendering, offscreen captures,
private protocol services, CLI processes and headless disposable emulators are
appropriate. Compilation or widget tests do not establish native visual acceptance.
Leave actual interface review and physical device/pairing interactions to the user
at the end. Keep the app's own save/cancel/destructive confirmations; this rule is
about agent interruptions, not removing product safeguards.

If a human action or unavailable device prevents one observation, finish the
implementation and all independent verification, record the exact missing evidence,
and continue with other work. Prepare concrete artifacts and instructions before
the final handoff. Do not claim an unperformed device or visual test passed.
Platform-required tool approvals still apply; this playbook does not authorize
destructive actions or bypass the execution environment's approval controls.

## Starting state: retain what already works

M0/M1 and the main M2 implementation exist. Do not rebuild the app from a mockup.
Use native libcosmic controls and the user's COSMIC theme. The reference is
`Mockup/stack-cosmic-desktop-mockup.html`; preserve the supplied file.

Implemented: persistent library, additive portable archive transfer, originals and
recovery, saved page rendering/export, stack navigation, real first-page thumbnails,
contextual viewer/editor, saved metadata, current imported OCR search and bounded
session thumbnail/page caches. Phone backups are transfers, not synchronization.

Latest import follow-up fixes the backup menu trapping outside clicks. Escape
restored responsiveness in the user's existing window. Outside clicks now dismiss
the menu; imports have an animated activity indicator and a document-count status.
Review does not poll an idle transfer. A pointer regression covers menu dismissal,
progress ticks, an intentionally paused commit and duplicate-click prevention.

Current evidence before beginning the next increment:

| Item | Actual state |
| --- | --- |
| Source baseline | Discover HEAD; `6dd5ee5` precedes the import-menu/progress fix and this playbook. |
| Latest headless run | `/tmp/stack-import-feedback/final-checks/report.json`:50 tests passed,0 failed/ignored; fmt/check/clippy/build/software/private-portal checks pass. |
| Stress limits still open | At1000 documents/10000 pages, first result-page draw171ms versus150ms target; maximum executor tick gap178312µs versus150000µs target. Eight other targets pass. Overall acceptance report correctly fails its performance gate. |
| Earlier evidence | `/tmp/stack-ui-followup/report.json` passed all ten targets for that earlier revision. Retain both observations; do not describe the latest run as all green. |
| Text-search gap | Ordinary desktop PDF/image import neither extracts embedded PDF text nor runs OCR. Compatible current text in phone backups is searchable. |
| Human/device evidence | Full M2 interaction acceptance, native AT-SPI tree publishing, chooser parenting and actual Android exchange/restore remain qualified or pending. |
| Private operation | The user's attempted import was staged in a disposable sample library when inspected. Only process metadata, aggregate counts and staging-marker existence were read. No private archive was opened or import repeated by the agent. Do not assume that old observation is current device/library state. |

Artifacts under `/tmp` may disappear. Missing artifacts require fresh synthetic
verification, not reconstruction from personal data. Read the durable
[M2 evidence](desktop/M2_EVIDENCE.md), [results](desktop/M2_RESULTS.json),
[execution ledger](desktop/EXECUTION_LEDGER.md), [storage contract](../desktop/STORAGE.md),
[archive contract](STACK_ARCHIVE_FORMAT.md) and
[sync draft](desktop/INTEROP_SYNC_CONTRACT_V0_1.md).

| Work package | Starting status | Result |
| --- | --- | --- |
| R1 / M2 closeout | Engineering delivered, qualified; native user acceptance pending | Dependable local desktop workflow ready for final user review |
| R2 text search | Implemented offline English PDF/OCR; real repository/worker/UI/search path verified | Offline PDF text extraction and OCR |
| R3 / M3 | Preliminary causal kernel and three-replica file test pass; full adoption/order/review gates pending | Tested causal merge, adoption and conflicts |
| R4 / M4 | Preliminary real-file exchange passes; installer and native exchange workflow under implementation | Desktop file exchange and normal installation |
| R5 / M5 | Shared Java causal golden cases pass on host; Android transactional integration/runtime exchange pending | Demonstrated disposable Android/desktop file sync |
| R6 / M6 | Not implemented | Paired authenticated local-network sync |
| R7 release | Pending the stages above | Integrated verification, reproducible artifacts and one final user handoff |

Current in-progress evidence: [remaining-work checkpoint](desktop/REMAINING_EVIDENCE.md). Stage gates remain open until their complete acceptance criteria are demonstrated.

## R1 — Finish M2 engineering and interaction readiness

1. Preserve the menu/progress correction and its regression. Demonstrate outside
   dismissal, reachable review/cancel controls, immediate busy state, truthful saved/
   canceled outcomes and one commit per accepted operation. Do not re-import a
   private backup to test the fix.
2. Profile and reduce the two remaining maximum-count timing misses. Bound visible
   layout, decoding and UI-thread work. Keep the existing150ms targets; do not raise
   budgets, suppress failures or keep rerunning unchanged code until a pass appears.
   Separate fresh-process measurements from cumulative workspace-process RSS and
   from unmeasured native compositor performance.
3. Protect unfinished individual-detail edits when switching details. Add a clear
   quit policy for dirty drafts: keep editing or deliberately discard; persist
   recovery drafts only if implemented and verified. Current process-local retention
   must not be advertised as restart recovery. Preserve explicit save behavior and
   stale/failed-save recovery.
4. Replace the stack editor's hex-only color workflow with a compact native palette
   and optional custom input. Make category accents and saved overrides consistent;
   a successful color save must have a visible, explainable result.
5. Address the pinned accessibility adapter's placeholder-only AT-SPI publication and
   missing native chooser parent identifiers. Use the actual toolkit/window APIs,
   with scoped dependency/pin/license changes only if necessary. Test what can be
   tested headlessly and retain actual screen-reader/parenting interaction as an
   explicit final user gate when no headless observation can establish it.
6. Extend headless shell/widget tests for keyboard focus, menu escape/outside-click,
   narrow/light/dark/enlarged layouts and background work preserving drafts and
   navigation. Keep the native shutdown stability watch and any new traces.

Finish gate: engineering checks pass, performance gaps are resolved or explicitly
remain open, and one runnable persistent-library build is ready for final user
interaction review. Do not mark full M2 user acceptance on the user's behalf.
Use the [phone transfer guide](STACK_DESKTOP_PHONE_TRANSFER.md) for a deliberate
first transfer into a normal persistent library; the sample launcher is disposable.

## R2 — Complete desktop document-text search

This is the requested search follow-up, separate from the original M2 requirement
to read existing phone OCR. Complete it before presenting newly imported desktop
documents as fully searchable.

1. Extract embedded PDF text using the pinned engine where valid. Process each
   saved physical source page in saved order. Text excluded by a saved crop or a
   removed page must not become searchable; use saved-page OCR when extraction
   cannot represent the visible saved page faithfully.
2. Add a bounded, offline OCR path for scanned/image pages without usable text.
   Evaluate the existing Android engine/model provenance and compatible desktop
   tooling. Pin and package licensed dependencies/models; no first-launch downloads
   or cloud OCR. Keep initial language coverage explicit.
3. Use a durable page queue with revision/configuration/document-incarnation checks,
   restart recovery, cancel/retry and one bounded native-engine lifecycle. Do not
   overwrite manual metadata or publish stale text after edits/deletion.
4. Keep ordinary PDF import and archive restore distinct. Preserve already valid
   imported text; do not unnecessarily reprocess a phone library. Preserve the
   portable format's exact allowlists. Keep engine-specific local state outside
   the archive unless the existing contract already represents it correctly.
5. Show queued/reading/ready/no-text/failed states honestly, with retry and stable
   matching-page navigation. Preserve original bytes and untouched-PDF export.
6. Test readable authored PDFs, page-two-only phrases, image-only scans, blank pages,
   crops, non-BMP text, failed jobs, interruptions, stale results and search after
   restart. Label embedded-text extraction separately from measured OCR accuracy.

Finish gate: independently authored new desktop imports become searchable offline
through the real worker/repository/index/UI path, and earlier imported OCR remains
searchable without lost metadata, changed originals or stale hits.

## R3 / M3 — Identity, causal merge and conflict review

1. Turn the sync draft into a versioned executable protocol contract and shared
   golden cases. The current local journal and peer-progress table are not a wire
   protocol. Keep archive, desktop schema, Android schema and protocol versions
   independent.
2. Implement stable entity/replica identity, monotonic operation counters, causal
   contexts, durable inbox/outbox, contiguous acknowledgments and bounded pending
   gaps. Identical replay is a no-op; the same operation ID with another payload
   fails. Acknowledgment follows durable commit.
3. Preserve concurrent scalar alternatives and evidence. Merge independent fields
   and membership deltas; review conflicting replacements, page edits and ordering.
   Wall-clock time is not an ordering rule. A conflict resolution is a new causal
   decision, not deletion of the losing history.
4. Retain deletion tombstones and last originals; old offline peers and stale OCR
   cannot resurrect deleted documents or replace later manual decisions.
5. Implement explicit, restart-safe baseline adoption and reviewed source-ID maps.
   Existing phone/desktop backup copies must not be silently matched by title,
   filename, identical bytes or a content hash. Ambiguous ancestry remains distinct
   unless deliberately mapped, with divergent values preserved.
6. Add compact native conflict/adoption views without disrupting library navigation.
   Format3 cannot encode unresolved alternative values: block full portable export
   with an actionable explanation rather than silently exporting one alternative.

Finish gate: two and three isolated replicas converge across delivery permutations,
replay, gaps, forwarding, independent/conflicting edits, resolution, delete/edit,
adoption, interruptions and stale workers. No lost decision or duplicated operation.

## R4 / M4 — Desktop exchange and installation

1. Define a bounded exchange bundle separate from `.stackbackup`, containing durable
   operations, required immutable blobs and acknowledgments. Validate versions,
   references, hashes, lengths, paths and limits before mutation.
2. Implement user-selected file/folder inbox/outbox exchange, safe publication,
   restart/cancel/retry, replay protection and clear pending/transferred/acknowledged
   status. A queued or written bundle is not an acknowledged synchronization.
3. Run bidirectional exchanges between isolated Rust libraries through real on-disk
   bundles. Test missing blobs, corruption, partial files, storage failures and
   process interruption at publication/apply/acknowledgment boundaries.
4. Provide a normal desktop launcher, icons, reproducible install/update/uninstall
   instructions, pinned PDF/OCR runtime assets and complete notices. Default to
   persistent XDG data. Never delete a library on uninstall or point a normal launch
   at sample data under `/tmp`.

Finish gate: two desktop replicas exchange and converge through actual files,
including conflicts/deletions/replay/restart, and a reproducible installation is
ready. Final native visual/install interaction remains a user check at the end.
This stage does not claim Android synchronization or network authentication.

## R5 / M5 — Android companion exchange

1. Inventory every Android authoritative mutation: import/restore, capture/save,
   title/notes/details/filing, stacks, pages, OCR/automation, removal and deletion.
   Add compatible migrations and transactionally journal all relevant paths.
2. Implement the same protocol, adoption, causal merge, conflict retention and
   portable exchange UI in the existing Java app. Preserve scanner behavior, lock,
   drafts/recovery, original bytes, manual decisions and current archive formats.
3. Make Java and Rust execute the same golden vectors, including currency/Unicode
   and current host-only compatibility qualifications. Reuse production validators;
   host shims are not proof of Android renderer/database/lifecycle behavior.
4. Run actual Android ↔ Rust archive and delta exchanges on a disposable headless
   emulator or Checks installation. Cover both directions, offline edits, conflicts,
   deletions, replay, replacement/adoption, process stops and restart.
5. Prepare the signed normal app update and concrete installation/evidence notes.
   Preserve the existing signing identity; never solve a mismatch by uninstalling
   or clearing the canonical phone. A primary-phone update/transfer is a deliberate
   final user operation, not a regression test.

Finish gate: actual disposable Android/Rust file exchanges converge with originals
and decisions intact. If hardware/emulation is unavailable, complete independent
engineering and identify the missing runtime evidence at the final handoff.
M5 provides phone/desktop sync through exchange files; M6 adds direct transport.

## R6 / M6 — Pairing and local-network synchronization

1. Specify and implement explicit enrollment, verified peer fingerprints, maintained
   TLS/client authentication, protected credentials and revocation. Use QR/manual
   verification; never trust discovery names or invent cryptography.
2. Add opt-in foreground **Sync now** with discovery/manual address fallback,
   bounded operation/blob transfer, durable resume and truthful offline/progress/
   conflict/last-acknowledged state. Reuse the tested merge/apply path.
3. Keep listeners/discovery off until enabled. Recheck official Android local-network
   and lifecycle requirements against the actual SDK/device target. Implement only
   required permissions; do not promise continuous background sync when suspended.
4. Test authenticated local endpoints/headless clients and disposable Android peers:
   unknown/revoked/wrong-key peers, reconnect, interruption, corruption, replay and
   conflict resolution. Never sync local lock/authentication, consent, monitoring,
   device paths or paired secrets through a library backup.

Finish gate: demonstrated disposable phone/desktop bidirectional LAN exchange,
including rejection and recovery cases. Prepare real pairing/permission interactions
for the user's single final review; preserve any unobserved platform qualification.

## R7 — Integrated release and final handoff

Run the complete offline import → text search → edit → restart → archive/PDF export
loop plus file exchange, merge/conflict and LAN scenarios. Verify populated older
schemas, originals, exact values/evidence, cancellation, stale jobs and process-stop
recovery. Complete dependency/model notices and backup/recovery/install guides.
Document currently supported configurations, limits and reproducible artifacts.

Update the [execution ledger](desktop/EXECUTION_LEDGER.md),
[evidence index](STACK_DESKTOP_EVIDENCE.md), this playbook's stage states and the
next-session prompt after coherent increments. Add protocol/storage/archive docs
only where implemented changes require them. Commit only owned Stack changes.

At the end provide one concise handoff: what is implemented, exact tests and
remaining failures, install/launch commands, artifacts and one consolidated list
of user-only interactions. Update the existing
[user-dependent checklist](STACK_DESKTOP_PLAN.md#one-consolidated-user-dependent-checklist-for-implementation-closeout)
rather than creating competing lists. No routine intermediate questions.

## Verification and operating boundaries

- Preserve all unrelated dirty files and `cosmic-takeout`; recheck instructions/HEAD.
  Do not replace the native UI with HTML, a browser shell or hardcoded theme colors.
- All development mutations use independently authored inputs and explicit private
  disposable library/XDG roots. Never use a private archive as a fixture, open its
  contents casually, log personal titles/text/values or capture user windows.
- Never clear, uninstall, reseed, restore into or replace the canonical Android
  library as a test. No live SQLite/WAL sharing and no sync keys in format3.
- Reuse one serialized repository writer, immutable originals, coherent snapshots,
  guarded worker tokens and bounded caches/queues. Derived text/caches do not grant
  permission to overwrite saved manual decisions.
- Run meaningful checks for each change; broaden only for changed risk or unresolved
  failure. Do not rerun unchanged code merely to obtain favorable timing. Rerun M1
  process-stop/Java interop checks when storage/archive/journal behavior changes.
- Use the existing acceptance harness with a fresh `/tmp` output per run:

  ```sh
  cd /home/charlie.day/Projects/Stack
  RUSTUP_TOOLCHAIN=stable CARGO_HOME=/tmp/stack-desktop-dev/cargo-home \
    CARGO_BUILD_JOBS=4 python3 desktop/tools/run_m2_checks.py \
    --output /tmp/stack-remaining-CHECK-UNIQUE
  ```

  It removes display variables and runs locked/offline fmt/check/clippy/build/tests,
  fresh maximum counts, software-only compile and a private synthetic portal service.
  Its D-Bus test needs socket access; use the environment's normal tool approval
  mechanism when necessary. Verify the recorded compiler/PDFium/cache are present;
  use the repository's pinned setup and document any replacement, rather than
  relying permanently on the historical `/tmp` cache.
- Add targeted headless sync/text/Android checks as their implementations arrive.
  Existing test counts and old pass reports are baselines, not completion evidence
  for new stages. Keep failures, source hashes, binary/runtime pins and limitations.
