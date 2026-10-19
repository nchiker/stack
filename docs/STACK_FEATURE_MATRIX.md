# Stack successor feature-parity checklist

Reference: [Stack APK audit](STACK_REFERENCE_AUDIT.md), 2026-10-15. Inputs: build **A** = `1.5.365000750.release` (276), build **B** = `1.231.431385252.release` (71588). Evidence IDs below resolve to the audit's evidence register.

**No successor features were implemented or tested by the reference audit.** Development follows the [P0 playbook](STACK_P0_PLAYBOOK.md) and [P1 runbook](STACK_P1_RUNBOOK.md). Checkboxes remain unchecked until their complete acceptance conditions are demonstrated, including the stated offline condition. Reference presence and partial implementation do not imply acceptance.

Evidence: **V** = verified static capability/path; **P** = Google's published behavior; **I** = inference; **U** = unresolved. A build entry of `U` means unestablished, not absent. Static evidence does not establish runtime activation or quality.

Priorities:

- **P0:** First Android parity baseline: the shortest complete scan → organize → find → export loop, operating offline from first use.
- **P1:** Remaining supplied-build parity and reliability work, before unrelated new features.
- **Later:** Desktop or optional integration outside the initial offline parity release.
- **Exclude:** Original infrastructure that conflicts with the independent offline product. Replace the user benefit locally where appropriate.

Acceptance conditions below are the acceptance contract. Completed successor checks and remaining gates are recorded in the [final P0 evidence](STACK_P0_FINAL_EVIDENCE.md); reference-app runtime claims remain limited to the audit. The project must agree on a sample corpus and measurable OCR/classification performance targets before calling automation parity complete.

## Current P0 delivery — 0.7.1-dev

All remaining P0 implementation, including portable backup/restore, is delivered.
[Final evidence](STACK_P0_FINAL_EVIDENCE.md) records all 30 rows, final verification,
quality limits and the one remaining user/resource checklist. Checked rows have
focused offline evidence on API 33; unchecked rows retain specific broader gates.
Full P0/automation acceptance is not claimed. P1 implementation and open gates are recorded below.

## Current P1 implementation — 0.8.7-dev / code 20 / schema 8

The [0.8.7 follow-up](STACK_UNLOCK_AND_LIBRARY_TOOLS.md) adds direct native unlock,
device-local backup history and persistent Newest/Name document sorting.37 affected
groups pass, with all five fresh canonical fingerprints/previous protected rows exact.
Existing lock remains enabled. Native authentication of the new entry flow and actual
primary-phone transfer remain focused user actions, separate from the completed H report.
No schema/archive change or new quality/platform acceptance claim.

A separate [Rust COSMIC desktop work order](STACK_DESKTOP_NEXT_SESSION_PROMPT.md)
is prepared for the next session. Desktop transfer/sync is not implemented or tested
by this Android closeout; Android parity evidence keeps its existing scope.

The [stack tile editor](STACK_STACK_EDITOR.md) adds durable drag ordering and inline
Home controls, additive schema8 and archive3 with format1/2 readers.46 affected
device groups plus one actual isolated process-stop case pass; all five canonical
preservation hashes match the fresh144/231/17/216 baseline. Formal subjective and
resource checks are now [reported completed by the user](p1/USER_COMPLETION.json).
No known P1 implementation or unresolved repair remains.

All 14 P1 implementation rows are covered by the completed I1–I8 sweep and
consolidated A-stage verification/repair. [P1 evidence](STACK_P1_EVIDENCE.md) maps
each row to commits, automated results and exact evidence files. Format 2 preserves
notes/details/organization tags and removed/rejected decisions while reading
format 1 safely. Canonical preservation and final artifacts are recorded there.

The single [H1–H8 checklist](p1/HANDSON_CHECKLIST.md) includes physical light/camera,
photo/notification permission and real-photo utility, actual system authentication,
provider variety, human accessibility/quality/offline first use and unavailable
runtimes/storage resources. The user reports all eight entries completed; this
supersedes pending-session wording. It is separate from automated/agent-observed
evidence. New detailed platform/storage measurements and numeric quality agreement
details were not supplied; no blanket parity or universal coverage claim is made.
Feature acceptance boxes retain their specific evidence requirements.
The [UI follow-up](STACK_UI_FOLLOWUP.md) implements informal feedback;
[original Google Stack migration](STACK_GOOGLE_STACK_MIGRATION.md) is completed
after explicit user-authorized cleanup. [Automatic details](STACK_AUTOMATIC_DETAILS.md)
records editable detected defaults, requested bare-dollar USD, 25 affected groups,
actual transfer read-back and limited informal detail approval. Original frozen
expectations and expanded/default behavior stay distinct. The current completion
report supersedes the earlier limited hands-on status; evidence scopes stay distinct.
Earlier scanner approval and the five OCR/four naming-filing user reports carry
forward for their documented scope. No new human pass is inferred from generated
metrics, deterministic authentication boundaries or agent visual review.

## Historical successor progress — 2026-10-16

This records partial implementation separately from acceptance. Detailed evidence and limitations are in the [P0 playbook](STACK_P0_PLAYBOOK.md).

| Area                                       | Implementation/evidence                                                                                                                                                                                                                                                                                                                                                                                                                                               | Acceptance still open                                                                                                                                                                                                                           |
| ------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Single installation (0.4.3-dev)            | User-requested consolidation complete: Stack retains both libraries and Preview functionality. All 8 documents / 14 pages / 8 stacks preserved; originals/metadata verified by phone read-back before Preview removal. [Evidence](STACK_CONSOLIDATION.md).                                                                                                                                                                                                            | General portable backup/restore remains a separate milestone.                                                                                                                                                                                   |
| Local library, stacks and title search     | Implemented; initial Android 13 PDF, membership, persistence and prefix-search checks passed. User reports photo import and Save PDF work.                                                                                                                                                                                                                                                                                                                            | Full library actions, share paths, offline first-use and platform/error cases.                                                                                                                                                                  |
| Interface alignment (0.3.0-dev)            | Search card, circular category tiles, thumbnail grids, floating add menu, document details sheet and scan filing picker implemented; both APK variants build. [Design notes](STACK_UI_REFERENCE.md).                                                                                                                                                                                                                                                                  | Visual/device acceptance of the revised UI; historical 0.2 scanner checks do not establish the new interface.                                                                                                                                   |
| SC-01, UX-04 capture and review            | Camera2 rear-camera path and recoverable multipage drafts implemented; historical synthetic review/edit/save checks. User reports successful camera capture and saving on the Pixel 4a.                                                                                                                                                                                                                                                                               | Permission/error handling, camera interruption/orientation/focus and complete real multipage workflow.                                                                                                                                          |
| SC-05/06/07 page processing (0.4.2-dev)    | Validated multi-threshold/line autocrop with fitted boundaries; perspective/rotation; noise-resistant paper normalization and retained faint/gray strokes; opt-in reversible flattening of gently bowed text. Pixel 4a passed 32 synthetic runner groups across processing/storage/export. Fixed-source crop accuracy, cleanup and curve results are [documented](STACK_SCANNER_QUALITY.md). The user subsequently tested and approved the delivered scanner changes. | Broader real-photo quality across a corpus, severe shadows/curls/folds, photos/faint detail, accessibility and lower-device memory. A similar-brightness touching object correctly falls back; no Google parity or general 3D flattening claim. |
| DT-01/02, EX-01/02 storage/export portions | Direct 1–4 → 5 migrations preserved prior data. Current in-place upgrade preserves 7 documents / 13 pages / 8 stacks / 10 memberships with matching metadata/original/draft/recovery hashes. Injected document/page/membership rollback and unavailable-staging repair pass; successful scan retry remains idempotent. [Evidence](STACK_WORKFLOW_EVIDENCE.md). | Actual disk-full/power-loss, large edited exports, provider-write/cancellation and broad lifecycle/platform acceptance. |
| ML-01, DT-03, SR-01/02/03 (0.5.0-dev)      | Bundled English OCR; revision/claim/configuration-safe queue; launch recovery and bounded attempts; import/save/backfill; page snippets/navigation; status/retry. Pixel real-engine, race/failure, worker and synthetic corpus checks; user reports five-step scan/import, later-page search/navigation, relaunch and success-status workflow passed; [evidence](STACK_OCR_EVIDENCE.md).                                                                              | Broad real-photo quality and agreed acceptance targets; genuine disposable fresh-install offline walkthrough; API 24/current Android and 16 KiB runtime; hands-on failure/retry UI acceptance. Checkboxes stay open.                            |
| ML-02/03, UX-03 naming/classification (0.6.1-dev) | Durable provenance/field decisions and stale-result safeguards remain. User reported good filing but a street-address title on one veterinary bill; rule 4 corrects the failure structure. 84/87 authored corpus, 22 structural name expectations, seven state-machine groups and current review UI pass. User reports all four corrected-name/edit/filing/relaunch/search/empty-filing steps passed. [Follow-up evidence](STACK_WORKFLOW_EVIDENCE.md). | Hands-on dismissal; independent human-rated/real-photo corpus; contextual Medical false positives and broader title specificity. Eight separated cases: 90% precision; agent title correction need 14.286%. All proposals advisory. |
| P0 document workflow follow-up (0.6.1-dev) | Name editors retain invalid/failed-save input; deleted invisible stack choices recover on explicit Save. Production picker/review, hidden/empty filing, 320 dp/2x text/recreation, external provider and separate-UID read-only grant checks pass. Six-page real OCR → accept → page-6 search/reopen → byte-identical export passes. [Evidence](STACK_WORKFLOW_EVIDENCE.md). | Full system picker/chooser and physical capture/lifecycle; OCR failure/retry hands-on UI, broad accessibility/storage/runtime and offline first-use gates. |
| EX-03 portable backup | Format-1 original/metadata archive, validated staged additive restore, reviewed collisions, durable journal/marker recovery and Stack-style local controls. Empty/additive/repeated round trips, hostile inputs and five real process-stop boundaries pass. [Evidence](STACK_P0_FINAL_EVIDENCE.md). | Full provider/storage/runtime variety remains in the final checklist; no personal archive test or full P0 acceptance claim. |

## Core interface and workflows

| Done | ID    | Capability                             | Original evidence                                       | Priority | Offline successor acceptance condition                                                                                       |
| ---- | ----- | -------------------------------------- | ------------------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------------- |
| [ ]  | UX-01 | Home and all-documents navigation      | A/B V; B explicit two-tab menu. E03                     | P0       | Reach stacks and complete library from two main destinations; persist state across app restart.                              |
| [ ]  | UX-02 | Prominent search and add action        | A/B V. E03                                              | P0       | Search/add available without settings or account setup; add offers camera, image, and PDF inputs.                            |
| [ ]  | UX-03 | Simple filing after capture/import     | A/B V. E04, E08                                         | P0       | Review/edit suggested title and memberships, then save with a short completion flow; retain document if automation fails.    |
| [ ]  | UX-04 | Multipage capture and preview          | A/B V. E05, E10                                         | P0       | Scan several pages into one document, review each, remove a mistaken page, save and reopen with stable order.                |
| [ ]  | UX-05 | Document preview and information sheet | A/B V; B notes/details additions. E09                   | P0       | Preview pages; access title, memberships, added date, and available extracted details without leaving the document workflow. |
| [ ]  | UX-06 | Empty/loading/error/retry states       | A/B V; B more backup/download states. E03, E09, E13–E14 | P0       | Empty library/search and failed processing show clear actions; failures do not discard a saved source.                       |
| [ ]  | UX-07 | Zoom document preview                  | B V layouts; A U. E09, E15                              | P1       | Inspect fine print at readable zoom and return to document context.                                                          |
| [ ]  | UX-08 | Batch filing                           | A/B V. E04                                              | P1       | File several newly imported documents with visible progress and editable suggestions.                                        |

## Scanning and import

| Done | ID    | Capability                                | Original evidence                                      | Priority | Offline successor acceptance condition                                                                               |
| ---- | ----- | ----------------------------------------- | ------------------------------------------------------ | -------- | -------------------------------------------------------------------------------------------------------------------- |
| [ ]  | SC-01 | Camera scanning                           | A/B V. E02, E05                                        | P0       | First-launch capture succeeds without a network or Google account; handle permission denial and camera interruption. |
| [x]  | SC-02 | Gallery image import                      | A/B V. E03, E05                                        | P0       | Import user-selected images locally, including rotated images; retain original image data.                           |
| [x]  | SC-03 | Multipage PDF import                      | A/B V; B path traced. E07                              | P0       | Retain the imported PDF, render all pages locally, and process text without rasterizing away the only original.      |
| [ ]  | SC-04 | Receive Android shared image/PDF          | A/B V manifest. E02                                    | P0       | Another app can send a supported image/PDF; ingest while URI access is valid and preserve the file locally.          |
| [ ]  | SC-05 | Detect boundaries and correct perspective | A/B V local paths. E05–E06                             | P0       | Suggest usable crop corners; manual adjustment remains available when detection fails.                               |
| [x]  | SC-06 | Crop, rotate, and page removal            | A/B V. E05, E10                                        | P0       | Reopen and revise crop/rotation without losing the original; removing one page leaves other pages intact.            |
| [ ]  | SC-07 | Automatic enhancement and simple presets  | A/B V; Original/Medium/Bright in B. E05–E06            | P0       | Provide useful automatic cleanup and a few understandable choices; preserve the original and edit settings.          |
| [ ]  | SC-08 | Flash control                             | B V controls; A U. E05, E15                            | P1       | Show hardware-appropriate flash control and persist/recover camera state correctly.                                  |
| [ ]  | SC-09 | Opt-in camera-roll document import        | A/B V setting/detector; runtime scheduling U. E06, E14 | P1       | Detect candidate document photos locally with explicit consent; avoid duplicate imports and support disabling it.    |
| [ ]  | SC-10 | Document-import notification              | A/B V. E14                                             | P1       | Open the imported document from a notification; honor permission/notification settings.                              |

## OCR, naming, organization, and search

| Done | ID     | Capability                                  | Original evidence                                              | Priority | Offline successor acceptance condition                                                                                                                      |
| ---- | ------ | ------------------------------------------- | -------------------------------------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [ ]  | ML-01  | Local OCR                                   | A/B V engine/assets; complete original offline workflow U. E06 | P0       | Bundle engine and initial language data; recognize every page on first use with network unavailable. Record language/version and recover from interruption. |
| [ ]  | ML-02  | Automatic editable title                    | A/B V cloud annotation/fallback; P product promise. E08, S1    | P0       | Produce useful local suggestions on the agreed corpus; offer date-based fallback; preserve explicit user edits.                                             |
| [ ]  | ML-03  | Automatic category suggestions              | A/B V annotation path; P product promise. E08, S1              | P0       | Suggest category memberships using independent local logic; ambiguous documents remain easy to file manually.                                               |
| [x]  | ORG-01 | Multiple stacks per document                | A/B V relations/UI. E04, E10                                   | P0       | A document appears in several chosen stacks with one underlying document identity.                                                                          |
| [x]  | ORG-02 | Create, show/hide, and delete stacks        | A/B V. E04, E10                                                | P0       | Create a category, choose whether it appears on Home, and remove the category while retaining its documents.                                                |
| [x]  | ORG-03 | Starter category set                        | B V catalog; account-specific visibility U. E04                | P0       | Supply a concise independent starter catalog and original icons; record how it maps to the reference catalog.                                               |
| [x]  | SR-01  | Full-document text search                   | A/B V main RPC; P full-text behavior. E13, S1                  | P0       | Find a term occurring only in a later page, with network unavailable and after restart; index title and OCR text locally.                                   |
| [x]  | SR-02  | Search snippets and document context        | B V result binding. E13                                        | P0       | Show a useful matching snippet and open the right document; preserve source-page context where available.                                                   |
| [ ]  | SR-03  | No-results, retry, and clear-query behavior | A/B V resources/controllers. E13                               | P0       | Empty/failed queries are distinguishable and recoverable; search remains usable during indexing.                                                            |
| [ ]  | ORG-04 | Suggested documents and organization tags   | A/B V annotation/tag/stack paths. E04, E08, E13                | P1       | Locally derive and expose useful organization/category groupings; users can reject or correct suggestions.                                                  |
| [ ]  | ML-04  | Extract common dates and amounts            | A/B V annotation/detail paths; P examples. E08–E09, S1         | P1       | Produce editable local suggestions with provenance on an agreed corpus; do not present ambiguous values as certain.                                         |
| [ ]  | ML-05  | Add/edit/remove/copy details                | B V; A V viewing/copy, editor U. E09–E10, E15                  | P1       | User corrections persist across reprocessing/restart; removed suggestions stay removed; values can be copied.                                               |
| [ ]  | ML-06  | Short name completion                       | B V local candidate logic; A U. E08                            | P1       | Offer a small useful suggestion list while naming; combine local document-specific candidates with independently authored choices.                          |
| [ ]  | SR-04  | Organization-filtered search                | A/B V service paths; B query traced. E13                       | P1       | Filter/search by organization/category locally, with consistent results and easy filter removal.                                                            |

## Storage, editing, export, and protection

| Done | ID     | Capability                                            | Original evidence                                                       | Priority | Offline successor acceptance condition                                                                                                                                  |
| ---- | ------ | ----------------------------------------------------- | ----------------------------------------------------------------------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [ ]  | DT-01  | Durable local document library                        | A/B V SQLite/images; full originals retention U. E10–E12                | P0       | Sources, page order, edits, memberships, and metadata survive restart without cloud downloads; cache eviction cannot destroy the only original.                         |
| [ ]  | DT-02  | Rename and delete documents                           | A/B V. E09–E10                                                          | P0       | Rename persists; confirmed deletion removes document/search membership and associated unshared files without affecting other documents.                                 |
| [x]  | DT-03  | Local processing/index state                          | Original V upload/process state; local index R. E09–E11                 | P0       | Ingest is durable before OCR; incomplete OCR/index work resumes after process termination without duplicate documents.                                                  |
| [ ]  | EX-01  | Multipage PDF sharing                                 | A/B V PDF/share paths. E02, E12                                         | P0       | Share a correctly ordered PDF via temporary URI grants; no account/network requirement.                                                                                 |
| [ ]  | EX-02  | Save PDF copy to user-selected storage                | B V menu/handler; A U dedicated action. E12, E15                        | P0       | Save through Android's document destination flow, with clear success/error feedback.                                                                                    |
| [x]  | EX-03  | Backup and restore documents plus metadata            | Original V cloud copies/export; complete metadata round trip U. E10–E14 | P0       | A local archive/manifest restores documents, page order, memberships, notes/details, and OCR text into an empty installation. This is a deliberate offline replacement. |
| [ ]  | DT-04  | Optional document notes                               | B V schema/UI; A U. E09–E10                                             | P1       | Notes persist, are editable, are included in local search/backup, and survive reprocessing.                                                                             |
| [ ]  | EX-04  | Batch PDF/ZIP sharing and batch deletion              | B V; A U equivalent workflow. E12, E15                                  | P1       | Select several documents, share PDFs/archive or confirm deletion; partial errors identify affected items.                                                               |
| [ ]  | EX-05  | Offer stored PDF to another app's chooser             | B V `GET_CONTENT` gateway; absent in A manifest. E02, E15               | P1       | Respond to PDF document selection with a valid temporary grant and clear cancellation.                                                                                  |
| [ ]  | SEC-01 | Optional biometric application lock                   | A/B V. E02, E14                                                         | P1       | Lock/relock through Android authentication with recoverable enrollment/cancellation handling; document whether encryption is separately provided.                       |
| [ ]  | SEC-02 | Account-free first use and no core network dependency | Original Google integration V; successor R. E02, E11, E14               | P0       | Capture, OCR, name, classify, search, edit, export, and restore from a fresh install with no account, Play Services dependency, or downloads.                           |
| [x]  | SEC-03 | Explicit platform backup policy                       | Original policy/coverage U. E02                                         | P0       | OS backup configuration matches the offline promise; explain local backup/restore to users.                                                                             |

## Boundaries, later work, and unresolved reference behavior

These are decisions and research items, not claims of missing original functionality.

| Done | ID    | Item                                               | Original evidence                                             | Disposition                          | Decision/acceptance needed                                                                                                          |
| ---- | ----- | -------------------------------------------------- | ------------------------------------------------------------- | ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------- |
| [ ]  | BD-01 | Google sign-in and private Paperwork backend       | A/B V. E02, E11                                               | Exclude                              | Replace account/status/upload/search/annotation dependencies with independent local behavior.                                       |
| [ ]  | BD-02 | Automatic Drive copies and bulk Drive export       | A/B V. E11, E14                                               | Exclude from initial release         | Preserve the backup/export benefit through EX-03. A later user-selected cloud destination must remain optional.                     |
| [ ]  | BD-03 | Backend Wi-Fi/mobile-data/retry diagnostics        | B V UI; A has upload infrastructure. E11, E14                 | Exclude cloud-specific controls      | Local processing/export must still expose useful progress/failure states.                                                           |
| [ ]  | BD-04 | Firebase/Primes/Phenotype and server feature gates | A/B V integrations. E02, E11                                  | Exclude                              | No mandatory analytics, remote configuration, or backend status dependency in the core product.                                     |
| [ ]  | BD-05 | Google assets, fonts, models, and native code      | A/B V inventory. E01                                          | Exclude                              | Independently author UI assets and behavior; use separately obtained compatible open-source dependencies/data.                      |
| [ ]  | BD-06 | Desktop application                                | Outside supplied Android evidence.                            | Later                                | Adopt the portable document/backup format after Android parity; no desktop framework commitment now.                                |
| [ ]  | UN-01 | Searchable OCR layer in exported PDFs              | U globally; bundled image-PDF writer V. E12                   | Clarify later                        | Distinguish in-app full-text parity from an exported text layer; investigate actual sample exports before claiming original parity. |
| [ ]  | UN-02 | Multilingual and handwritten OCR quality           | V bundled script assets; U product coverage/quality. E06, E15 | Set scope before automation sign-off | Declare initial languages and benchmark metrics; do not equate model presence with quality.                                         |
| [ ]  | UN-03 | Auto shutter, page reorder/merge/split, limits     | U. E05, E10                                                   | Reference research only              | Require direct runtime or stronger caller evidence before expanding the parity baseline.                                            |
| [ ]  | UN-04 | Original account-free/cold-start offline behavior  | U; Google integration V. E02, E11                             | Reference research only              | If runtime evidence becomes available, record first-run and network-disabled behavior separately from static findings.              |
| [ ]  | UN-05 | Complete backup/restore and encryption guarantees  | U. E02, E10–E14                                               | Define successor guarantees          | Establish and document local restore and protection semantics; do not inherit unverified guarantees.                                |

Finish P0 first, then P1 parity. Defer unrelated additions until the short capture, filing, search, and export loop works reliably offline. Proposed future validation must include first use without connectivity, restart/interrupted processing, multipage content search, editable automation, and local restore. No such validation was run during this documentation-only audit.
