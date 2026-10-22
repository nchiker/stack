# Remaining P0 delivery and final evidence — 2026-10-16

Current original-package, physical test-device and offline P0 continuation:
[2026-10-20 record](p0/TEST_DEVICE_CONTINUATION_20261020.md). The dated evidence
below remains historical; it does not describe the replacement signing identity.

> Current acceptance work is consolidated in the [single P0/P1 H1–H8 session](p1/HANDSON_CHECKLIST.md).
> This P0 report and its original checklist are historical evidence; remaining gates
> are carried forward there, not counted passed by the P1 implementation.

**0.7.1-dev / code 12 / schema 6**, implementation through **1c4d795**. All identified
remaining P0 implementation gaps, including EX-03, were handled in place. P1 was
not started. This is **not full P0 acceptance**: the consolidated remaining gates
below still require hardware/resources or human evidence.

I1–I5 were implemented/committed before any builds, lint, tests, instrumentation,
measurements or device walkthroughs. V1–V9 then ran as one verification/repair stage.
No milestone approval stops occurred. Existing scanner approval, five user-reported
OCR passes and all four naming/filing passes carry forward for their documented
scope. Dismissal remains outside those four user passes.

## Delivered behavior

- Discoverable **Library menu → Backup & restore**. Format-1
  `.stackbackup` includes coherent original bytes and authoritative saved-library
  metadata/edits/order, stack visibility/memberships, OCR/configuration/revisions
  and suggestion/decision history. [Format, bounds and recovery](STACK_ARCHIVE_FORMAT.md).
- Private staging validates every manifest relationship/path/setting/type and
  original length/digest before review. Restore adds documents without replacing
  existing data, maps conflicts and merges matching stack names. Repeating a
  selected archive imports another copy; retrying one committed operation is
  idempotent. Owner-tagged journals and SQLite markers recover interrupted commits.
- Compatible text rebuilds FTS; transient claims normalize; stale worker results
  and old review actions cannot act on restored IDs. Current-evidence decisions
  rebase to mapped IDs. Existing explicit names and empty filing remain authoritative.
- Process-owned backup/import state survives activity recreation. Progress,
  cancellation, review/cancel/retry and actionable failure messages are exposed.
  Tab/query preferences survive relaunch; deleted stack filters fall back to the
  live library. Failed OCR pages can be retried from another page's status.
- Edited/image PDFs now stream lossless RGB/Flate pages with one bitmap/scanline
  rather than retaining a native PDF's pages. Untouched imported PDFs retain the
  byte-identical copy path. Partial share files are reclaimed on failure.
- Rule 5 conservatively improves Medical context, receipt purchase cues and
  mortgage Banking evidence. Address/contact heading guards and date defaults
  remain; proposals require explicit review. Crop has named accessible movement
  actions. No engine/scanner redesign, runtime dependency, model or permission change.

Drafts and `consolidation_recovery/` are preserved locally and excluded from portable
backup; backups are not encrypted. No personal archive was created or restored.
No reference-app documents/settings/accounts were changed or reference code/assets
reused. The user-selected external provider determines destination durability and
whether incomplete output can be removed; no atomic-provider-write promise is made.

## Consolidated verification

Pixel 4a, API 33, arm64, **4 KiB runtime pages**. Fresh `org.stackdocs.app.checks`
installation and isolated synthetic child contexts were used before canonical
update; no canonical clear/uninstall/reseed/database replacement/restore occurred.
[Machine-readable checks](p0/checks-summary.json) contains counts and measurements.

| Check | Actual result |
| --- | --- |
| Cached offline debug, instrumentation and unsigned release builds | Passed; final normal artifacts use the original cached development key |
| Lint | 0 errors / 6 warnings: newer Gradle advisory, one unused resource, two DetailsSheet custom-view/accessibility advisories, two existing camera literal/localization warnings |
| Direct schema 1/2/3/4/5 → 6 | Actual Java SQL against host SQLite passes all five; corresponding device fixture migrations pass with nondefault prior state |
| Consolidation host regression | 6 tests pass |
| Existing scanner + fixed-source quality | 35 groups pass, including preservation/migrations and approved crop/cleanup/flatten behavior |
| OCR / suggestions / address naming | 8 / 7 / 1 groups pass |
| Repository failures / external provider and separate-UID grants | 3 / 2 groups pass; denied/interrupted imports and exact PDF bytes, read-only/revoked/expired export grants |
| Workflow UI / suggestion UI | 3 / 1 groups pass; recoverable editors, empty/hidden filing, independent decisions, failure/retry and 320 dp / 2x text / recreation |
| Six-page actual production workers | Pass; 6,647 authored characters, page-6 search/reopen and exact original PDF export; workers 9,242 ms, UI/IO pings 1/1 ms |
| Archive + streaming PDF | 8 groups pass; full authoritative field/original round trip, empty/additive/repeated restore, choices/FTS/export, pinned snapshot through edit/delete, 13 hostile archives, cancellation/write failure, stale native/review actions and changed-library review rejection |
| Real process termination/relaunch | All five archive boundaries pass: staged, journal, installed file, SQLite transaction, committed marker; OCR native interruption and held suggestion claim also pass |
| Production first saved-library flow | Pass on fresh disposable package: import → bundled OCR/suggestions → manual corrections → later-page search → backup → review/cancel/retry → additive restore; Backup screen scope/protection/commit controls. File URI boundary; no device-network-disabled/system-picker claim |
| Navigation | Production All documents/query relaunch and deleted-filter fallback pass |
| Backup visual integration (0.7.1) | Shared toolbar/typography, circular outlined original icons, two library action rows, blue review/commit controls, short scope/protection note and expandable help; 320 dp at 1x/2x text and synthetic visual review pass. Progress/review auto-scrolls into view. Production synthetic backup/cancel/retry/restore rerun passes |
| Larger export/archive | 12 edited pages at 3,200 edge: 10,676 ms / 1,169,575 PDF bytes; 193,731 original bytes; snapshot 30 ms, archive write 12 ms / 201,107 bytes, restore 165 ms; UI ping 1 ms / IO ping 6 ms |
| Memory | Larger-export/backup/restore run sampled 239,978 KiB whole-process peak PSS (~234.4 MiB); 100 ms plus sampling overhead, not instantaneous peak or a controlled baseline |
| Final packaging | [APK audit](p0/apk-audit.json): 31,325,834 bytes, 16 native libraries across four shipped ABIs; bundled model/license/hash and ZIP/ELF alignment pass. Only CAMERA permission, backup/transfer exclusions intact. Static alignment does not prove 16 KiB execution |

Initial final-stage failures were repaired and affected checks rerun: Android's
non-public directory-open constant, a hardcoded canonical accessibility selector,
a test race with Application startup and test-window sleep. Restore review additionally
received a pristine-library recheck and random suggestion incarnation to prevent
concurrent membership loss and old-action aliasing. The first canonical preservation
probe was denied for signing mismatch; rebuilding with the original cached key fixed
it before any canonical application update. SDK XML/deprecation notices and five
compile-only test annotation warnings remain nonfatal. No failed check is counted as
passed without its affected rerun.

The 24-page RGB/order check renders every output page and confirms preceding
bitmaps are recycled before the next page and after write failure. Actual
force-stop tests are process-interruption evidence, **not physical power-loss tests**.

## Quality and provenance

[Fresh frozen set](p0/frozen-quality.json): 18 independently authored CC0 text cases,
19 rendered pages; 4 tuning and **14 evaluation** documents frozen before measurement.
Source rules and real bundled OCR both give evaluation category precision/recall
**100% / 100%** (10 true labels, 0 false positives/misses; 6 negative documents).
Category coverage 57.143%, abstention 42.857%. This is a small agent-authored,
agent-labeled set aligned with known failure structures, not a blind external sample.
No post-measurement rule tuning occurred.

[Frozen title review](p0/title-review.json) uses the predeclared rubric, reviewed by
the implementation agent. Evaluation: 6 proposed names, 4 useful unchanged,
2 small edits for more specific prescription/mortgage wording, 0 unhelpful,
8 abstentions. Conditional correction need **33.333%**; abstention includes negative
and no-first-page-issuer cases. Human usefulness acceptance remains open.

[Unchanged 84-document / 87-page regression set](p0/regression-quality.json): source
and OCR category precision/recall **98.837% / 98.837%** (85 true / 1 false / 1 missed
label), versus rule-4 baseline 96.552% / 97.674%. All **22 structural naming
expectations match**. Residual cases are `held-v3-note` (Medical false positive) and
`h-house3` (Banking miss). The old eight-case follow-up split still scores 90% / 100%.
These previously exposed cases remain regression evidence, not new evaluation.

[OCR corpus](p0/ocr-quality.json): unchanged 30 authored documents / 36 pages at
1,600 and 2,400 edge; **CER/WER 0% / 0%** at both sizes, 7,134 characters / 1,092
words per size; all six later-page sentinels retrieved. At 2,400: P50/P95 510/1,507 ms.
Sampled whole-process max PSS 145,001 KiB. Clean/generated fixtures do not establish
real-photo performance or Google parity. Numerical acceptance targets remain provisional.

## Canonical preservation and delivery

Canonical Stack was updated to 0.7.0, then the 0.7.1 visual follow-up, with **`adb install -r`**, using the original signing
identity, then reopened. Both preservation passes match; [before/after evidence](p0/preservation.json) matches exactly:
**6 documents / 12 pages / 9 stacks / 8 memberships**, all 12 OCR pages succeeded.
These current counts differ from historical counts because of prior user activity;
no reseeding occurred.

The metadata fingerprint now includes title/filing origins and revisions plus choice
history, in addition to document/page/stack relations and saved edits:
`30fa5a6f9107782c458a9f5ceba60faa7fda351146fffbf27ec3fd3c60415d72`.
Original/draft/recovery path/length/byte aggregate:
`16d6621f0db69b7d4eae119b079d3dced8ac6074233e65408b4785e9becdfda3`.
All hashing occurred on-device; no private file, SQLite database, OCR text or personal
backup was exported/logged. Temporary canonical test APK and both disposable packages
were removed; canonical Stack and the reference application remain installed.

Final installed debug APK SHA-256:
`3ecfab40e5632e79b7d02260180a48dec31fdcfb419fdffe07fff39bb53adfbf`.
The measured code's processing/archive implementation is unchanged by the later
navigation/test-probe changes; source is recorded by implementation commits below.
Final APK is 118,233 bytes smaller than the previous recorded debug APK, including
packaging/alignment differences; this is not a controlled runtime performance claim.

## All 30 P0 rows

The feature matrix remains the acceptance contract. “Pass” here names the evidence's
scope; resource/human gates are retained rather than promoting inspection to execution.
All code below is present through **1c4d795**; earlier approvals remain applicable.

| ID | Implementation/evidence | Specific remaining gate, if any |
| --- | --- | --- |
| UX-01 | Home/All/query preferences; production relaunch/deleted-filter check | Broader runtime/process navigation walkthrough |
| UX-02 | Existing search/add/camera/image/PDF actions retained | Full system picker/add walkthrough |
| UX-03 | Independent accept/edit/dismiss and automation-failure preservation; UI/worker suites | Broad human title/category usefulness |
| UX-04 | Multipage draft/save/removal/order; scanner suite and prior approval | Physical capture/lifecycle edge cases |
| UX-05 | Existing preview/Details/title/stacks/date/text status retained; prior five-step user pass | Broad large-text/human review |
| UX-06 | Import/export/archive/processing progress/failure/retry/cancel | Full provider-write and OCR retry hands-on UI |
| SC-01 | Camera permission/recovery path retained | Physical first-use/permission/background/orientation cases |
| SC-02 | Private image/EXIF/rendering/original-retention fixtures | No specific implementation gap; broader platform gate |
| SC-03 | Multipage real OCR/render/search/exact original PDF export | No specific implementation gap; broader PDF/platform variety |
| SC-04 | Private provider imports/read-grant boundary; process-owned ingest | Full other-app ACTION_SEND/system chooser walkthrough |
| SC-05 | Approved corners/manual fallback; fixed-source regressions pass | Broader real-photo/lighting quality |
| SC-06 | Reversible saved crop/rotation/removal and preserved original; scanner/archive round trip | Broad human accessibility/platform review |
| SC-07 | Approved cleanup/color/flatten settings and originals preserved | Varied real photos/faint/colored-paper quality |
| ML-01 | Bundled engine/model; actual workers/native restart and fresh saved-library flow | Physical offline first-use + unavailable runtimes/real photos |
| ML-02 | Address-safe editable proposals/date fallback/explicit choices | Human corpus/targets; generic specificity limits disclosed |
| ML-03 | Rule 5, multi-label metrics, manual/independent controls | Residual Medical/Banking errors and human quality targets |
| ORG-01 | Multi-membership one-identity suites and archive round trip | No specific implementation gap |
| ORG-02 | Create/show/hide/delete preserving documents; hidden picker/restore merge | No specific implementation gap |
| ORG-03 | Seven original starter icons/catalog; mapping below | No specific implementation gap |
| SR-01 | Title/all-page search, real process restart and restore FTS | No specific implementation gap |
| SR-02 | Stable matching-page context/snippet/page-6 reopen | No specific implementation gap |
| SR-03 | Sanitized empty/punctuation queries and recoverable error paths | Human complete no-results/error flow review |
| DT-01 | Additive migrations/durable originals/edits; snapshot/journal recovery; canonical hashes | Actual disk-full/physical power-loss/resources |
| DT-02 | Existing confirmed scoped delete/rename and stale-result/index cleanup retained | Full human deletion/recovery/platform walkthrough |
| DT-03 | Commit-before-worker, real OCR/suggestion/restore process recovery | No specific implementation gap; power loss remains broader gate |
| EX-01 | Ordered lossless streaming PDF and actual separate-UID read-only grants | Full system chooser + broad recipient variety |
| EX-02 | Existing SAF destination/flush/close/error handling retained | Real destination cancellation/short/denied/close failures |
| EX-03 | Versioned originals/metadata, validated empty/additive restore and real process recovery | Full SAF-provider interaction variety, actual disk-full/power loss |
| SEC-02 | No account/INTERNET/Play Services/download; fresh production saved-library flow | Physical network-disabled first capture-to-restore walkthrough |
| SEC-03 | APK backup flags/exclusions and local portable scope/protection controls/docs | No specific implementation gap |

Starter mapping uses independently drawn `Icons` symbols and original colors, never
Google artwork. Stack's seven starter IDs/names correspond by concept to the first
seven catalog entries recorded in reference audit E04: Bills, Banking, IDs, House,
Medical, Receipts, Starred. Reference Immigration/Insurance/Pet/Tax/Travel/Vehicles/
Legal/Work categories are not automatically added; users can create custom stacks.
Reference account-specific initial visibility is unknown. No illustrations were fetched.

## One remaining user/resource checklist

- [ ] **Hands-on edge cases:** physical camera permission denial/retry,
  background/rotation/interruption and multipage review; OCR failed-page retry;
  dismissal persistence. Reuse the already approved scanner and five OCR/four
  naming-filing passes; no need to repeat them without a regression reason.
- [ ] **Human quality/accessibility:** varied non-sensitive real captures, title/
  category usefulness and acceptance targets; TalkBack/focus/checkbox/crop movement,
  narrow/large-text Backup/Restore and error announcements. Generated scores and
  agent title grades do not close this gate.
- [ ] **System boundaries/offline first use:** Android incoming share, picker/
  chooser/destination cancellation and denied/short/close failures, then a fresh
  disposable network-disabled capture-to-restore walkthrough. Use synthetic
  documents/disposable libraries for restore tests; never reset the personal library.
- [ ] **Unavailable resources:** API 24 and current Android runtime, other shipped
  ABI execution, an actual 16 KiB runtime, safe disposable actual disk-full and
  physical power-loss/storage testing. No emulator/system images were installed;
  the only available handset is the preserved personal Pixel 4a.

## Commits and reproduction

`0f59925` workflow retry/export; `dcb8ea7` rule/corpus/crop actions;
`a856608` archive/restore; `874db5f` integration/fixtures/streaming export;
`879b654` restore review safety/termination fixtures; `5895bbf` navigation and probes;
`1c4d795` verified Backup/Restore visual integration.
Final evidence/docs are a subsequent Stack-only commit. Unrelated sibling changes
and the feature matrix's preexisting OCR-row formatting edit are preserved.

Build with the existing signing environment when updating this handset:

```sh
ANDROID_USER_HOME=/tmp/stack-dev/android-user GRADLE_USER_HOME=/tmp/stack-dev/gradle-cache \
  /tmp/stack-dev/gradle-9.3.1/bin/gradle --offline \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
python3 tools/verify_suggestion_migrations.py
python3 tools/test_consolidate_libraries.py
python3 tools/verify_ocr_apk.py app/build/outputs/apk/debug/app-debug.apk
```

For isolated runtime verification first build/install with `-PstackChecks` and use
`org.stackdocs.app.checks.test/org.stackdocs.app.ScannerChecks`; runner flags are
`archiveOnly`, `archivePerformance`, `backupUi` (fresh disposable saved library),
`navigationOnly` (after backupUi), plus existing suite flags in README. Each flag takes
`-e FLAG true`. Restart tools accept `--package org.stackdocs.app.checks` except
`check_archive_restart.py`, which enforces that package. The suggestion measurement tool accepts
`--corpus p0-final-corpus.json` or the original regression corpus. Never install a
checks artifact over canonical Stack or reset canonical data. A separate
`-PpreservationProbe` test build uses the read-only `PreservationRunner`, which can
run before and after the canonical APK update without new production-class calls.
