# Local naming, classification and filing evidence

This records the **0.6.0-dev baseline**. [0.6.1 workflow/naming follow-up](STACK_WORKFLOW_EVIDENCE.md) records the user-reported address-name failure, corrective rules, expanded corpus and additional P0 checks.

2026-10-16 · **0.6.0-dev / code 9 / schema 5** · Pixel 4a, Android 13/API 33,
arm64, 4 KiB pages. Implementation checkpoint: **378b0ed**. This delivers reviewable local proposals, not broad automation
parity or completion of P0. Scanner approval and the five user-reported OCR workflow
checks in [OCR evidence](STACK_OCR_EVIDENCE.md) carry forward unchanged.

## Reference observations and independent decisions

Both ignored reference APK SHA-256 values were rechecked against audit E01:
build A `1076c4cdaef53bf90baef3815bee567581391697d968821aa91aad3a5c7d2cda`,
build B `d35225e2d3ded6852b7da93f87d0fc5078fb493e3634dac1143e3c25acce74ce`.
USB serial `0A121JEC203530` was accessible; installed `com.area120.paperwork`
reported **1.231.431385252.release / 71588**. Reference originals, settings and
accounts were not edited. No backend request was issued by the tooling. Observation
alone does not establish whether the reference app itself made background requests.

| Evidence | Observed or static finding | Independent successor decision |
| --- | --- | --- |
| Audit E04; B `document_stacks_edit_fragment.xml`, `post_scan_fragment.xml` | Static filing/editor surfaces and completion action. On handset, opened and backed out of the stack editor without selecting or saving; document heading, stack tiles and Done were accessible. | Reuse our independently drawn multi-stack picker; label proposal editing **Edit all document stacks**. Accept adds proposed stacks while retaining current memberships. |
| User-selected older document, B handset | Expanded Details exposed two existing stack memberships and separate Edit controls. Overflow exposed Rename, Add to Stack, Delete and Save copy to device. | Keep filing and rename in the saved-document workflow, with independent decisions for each field. |
| Older document, B handset | Rename opened `rename_editor` with `rename_cancel`; cancelled without typing/saving. No suggestion list was established in that dialog. | Offer an editable proposed title, ordinary Rename and explicit Cancel. Do not infer untouched defaults are edits. |
| Older document, B handset + user report | Details exposed populated extracted-value rows. The user confirmed the displayed total and date were extracted from the document. Values/content were not exported. | Record extracted fields as reference behavior; structured extraction/editors remain outside this group. |
| Audit E08/E10/E11; targeted B `gcj`, `gyh`, `gyi`, `gym` inspection | Static date fallback, annotation-derived suggestions and name strategy/status/used-suggestion provenance. This does not establish an offline semantic classifier. | Independently authored English phrase rules and additive local provenance; no Google code, assets, models, fonts, schema implementation or private protocols reused. |

Fresh reference auto-naming, confidence, dismiss/reopen semantics and backend behavior
remain unresolved: no new reference document or network/account/settings experiment
was performed. An early accessibility snapshot exposed keyguard controls; after the
user clarified/reopened the app, foreground and older-document controls were
rechecked. Only resource IDs/bounds and allowlisted generic labels left the phone;
no screenshots or personal document text were exported. Temporary on-device
hierarchies were removed.

## Storage, ownership and stale-work protocol

`LibraryDatabase` adds schema 5 directly from versions 1–4. Existing titles,
`title_user_edited`, memberships, page edits and OCR text/configuration remain intact.
All migrated ownership is conservatively **legacy**, including empty filing; no
title pattern or legacy flag is reinterpreted as permission to rename.

New documents distinguish **default**, **filename**, **manual**, **accepted** and
**edited** origins. Title and filing have independent decision revisions. Scan save
no longer calls unconditional Rename/replace-filing afterward: title ownership and
chosen memberships commit with the saved document. Explicit empty filing counts as
a decision; untouched empty selection does not. Repeating a committed scan save
returns its existing document without overwriting subsequent choices.

Draft version 1 remains readable. Optional name, title-choice, filing-choice, stack
IDs and tracking fields persist atomically; older absent tracking is legacy/unknown.
Activity recreation retains the same flags. Typing then reverting to the initial
name is still a deliberate edit. Picker Save is deliberate even with no stacks.
Append/page-edit/removal preserve these fields. A delayed choice write checks the
draft ID and cannot recreate a discarded/saved manifest.

`suggestions` stores one current proposal per document: generation/claim, status,
rule version, input/evidence digests, decision revisions, payload and independent
field states. `suggestion_choices` retains accepted/edited/dismissed decisions by
stable document, evidence and field. Proposal decisions also retain rule/input,
proposed payload and chosen value for provenance; a subset/empty filing edit
suppresses the rejected remainder for that unchanged evidence. Ordinary manual
edits record their explicit value. These are local private metadata, never logs.

Database triggers durably invalidate claims on page insert/remove/reorder,
content/text/configuration/queue changes, title/filing decisions and stack
creation/deletion. Snapshots include stable ordered page IDs, content/text
revisions, OCR configurations, actual full-text digests, OCR state/claim/attempts,
current stack IDs and both decision revisions. Retrying with different text can
invalidate a proposal without changing a content revision. Rule and decision
identity, current input and generation are checked at **both commit and Apply**.
Late successes and failures cannot overwrite a newer proposal or recreate a
deleted document/stack.

All metadata runs on serialized repository IO; analysis uses a separate single
text worker, independent of native OCR. Durable pending/running work recovers on
launch. Reopening and repeated recovery do not duplicate proposals or memberships.
Unchanged-evidence retries preserve dismissals and explicit corrections, including
across rule versions; genuinely changed evidence can offer fresh advice but never
auto-applies it. Failed analysis persists until retry; interrupted claims are
requeued with a new generation/token. A deleted default stack is never recreated
by suggestions; custom stacks and Starred remain manual.

Accepting a title updates document/page FTS titles in the same transaction. Accepting
filing unions suggestions with current memberships; Edit deliberately replaces the
whole set shown in the picker. Ordinary manual filing remains available. These
operations do not request OCR. Existing search/query/filter refresh behavior is
preserved through library notifications.

## Rules and bounded behavior

`local-phrases-2` uses independently authored document phrases plus supporting
fields for Bills, Banking, IDs, House, Medical and Receipts. It abstains on example/
invalid-document cues, ambiguous evidence, and explicit “not an invoice/bill” cues
for Bills. Every eligible page contributes classification evidence within **32,768
UTF-16 characters/page and 262,144/document**. Truncation is recorded and disclosed;
empty page slots preserve first-page identity. Input hashing covers the full actual
text, including omitted tails. No image decode or OCR is performed for suggestions.

Names use a short credible first-page heading and supported type; control/path
characters are removed and output is limited to 100 characters. Dates and entities
are not guessed. With no useful name, the existing filename/date-based scan title
stays in place. Duplicate titles are valid. Explanations describe matched evidence,
not calibrated probabilities. These rules do not learn profiles or infer arbitrary
custom stack names.

Proposals wait for active OCR to finish. Failed pages permit explicitly partial
proposals from still-current text; empty/unsupported text leaves manual naming,
filing and export available. Expand **Details → Suggestions** after saving to
Accept, Edit or Dismiss each field. Rules/configuration internals are kept out of
the product UI. Source documents and manual choices remain authoritative.

## Labeled quality corpus and title rubric

Sources are authored fictional English text, released with this repository; no
reference/personal documents. These are small generated templates, not photos or an
independent public-document corpus. The first [40-document v1 corpus](suggestions/baseline-corpus.json)
contains six rule-authoring examples and 34 examples authored after v1 froze. Its
[baseline report](suggestions/baseline-results.json) records 97.143% precision,
94.444% recall overall (34 TP, 1 FP, 2 FN), and 96.552%/93.333% on its 34 examples.
The negated-invoice false positive informed v2; those 40 are now regression data.

The [final fixture corpus](../app/src/androidTest/assets/suggestions-corpus.json)
has **52 documents / 55 pages / 2,969 characters**, 0–105 characters/document:
40 regressions plus 12 fresh examples authored after v2 froze. It includes
multi-stack, later-page evidence, ID/forms, housing/medical, ambiguous, blank,
poor-text and negative examples. Labels and rules share an author, so even the
held-out split is not a blind external evaluation. No positive ID example occurs
in the fresh split. No further tuning was made after its contextual Medical error.

[Final measured results](suggestions/final-results.json): multi-label micro metrics
count document/category pairs; coverage is documents with any category proposal,
including wrong proposals. Abstention is its complement. Eligible coverage uses
only documents with at least one labeled category. Source text and **actual
Tesseract OCR of rendered PDFs** are measured separately; both produced the same
category sets and proposed titles here, not proof of zero OCR errors generally.

| Split | Documents | Precision | Recall | Coverage / abstention | Eligible coverage |
| --- | ---: | ---: | ---: | ---: | ---: |
| v2 all | 52 | 97.674% (42 TP / 1 FP) | 95.455% (2 FN) | 73.077% / 26.923% | 97.368% |
| v2 regressions | 40 | 100% | 94.444% | 75% / 25% | 96.774% |
| v2 fresh held-out | 12 | 88.889% | 100% | 66.667% / 33.333% | 100% |

| Category, v2 all | TP | FP | FN |
| --- | ---: | ---: | ---: |
| Bills | 11 | 0 | 0 |
| Banking | 6 | 0 | 1 |
| IDs | 4 | 0 | 0 |
| House | 7 | 0 | 0 |
| Medical | 8 | 1 | 0 |
| Receipts | 6 | 0 | 1 |

Errors: a mortgage statement lacks Banking rule cues; a receipt lacking the word
“receipt” abstains; a Friday task list mentioning prescription/dosage incorrectly
suggests Medical. One of 14 negative documents receives a false category. Broad
context and layout understanding remain limited; the simple negation guard is
not general language understanding.

[Title review](suggestions/title-review.json) is a documented **agent judgment**,
not user/human-panel acceptance or exact-string equality scoring. A useful unchanged
title must be readable, short and supported by the source heading/entity/type. A
small edit keeps meaning but removes minor wording defects; an unhelpful title
misleads or needs substantial correction. Generic supported types can qualify when
no safe entity is available. Missing proposals are counted separately as coverage.

Of **38 offered names**, 36 were judged useful unchanged, 1 useful after a small
edit (redundant “Identity document”), and 1 unhelpful (task-list Medical title).
Estimated correction/dismissal need: **2/38 = 5.263%**; fresh split **1/8 = 12.5%**.
These are judgments, not observed user edits. Fourteen documents receive no title;
13 are appropriate negative abstentions and one is the missed receipt. Positive
name coverage is 37/38 = 97.368%. Corpus/rule-versioned individual judgments are
retained so another reviewer can disagree and rescore.

Measured usefulness is insufficient to accept automation parity. Recommended future
proposal-only targets for a diverse independently labeled corpus: at least 95%
micro precision, 90% recall and no more than 10% title correction/dismissal, reported
with per-category and negative-set results. The small fresh split misses precision
and title targets. Targets are recommendations, not user-agreed gates; do not enable
automatic application from these results.

## Timing, memory and packaging

Pixel 4a/API 33 final measurements across 52 documents:

| Operation | P50 | P95 | Maximum |
| --- | ---: | ---: | ---: |
| Rule analysis on source text | 1.192 ms | 3.456 ms | 3.750 ms |
| Proposal snapshot + analysis + commit after OCR | 5.749 ms | 16.805 ms | 33.179 ms |
| OCR render/claim/recognize/commit per document | 287 ms | 471 ms | 650 ms |

Times use elapsed realtime and exclude fixture generation/import, model installation
and production executor scheduling delay; OCR reuses one initialized engine.
Short documents do not establish 256K-budget/worst-library latency. Production-worker
IO/UI pings were 3/0 ms during recognition in the separate integration check.

External total-process PSS: **48 samples**, nominal 250 ms plus adb/dumpsys overhead,
maximum **123,041 KiB / 120.2 MiB**. Includes fixture creation, instrumentation and
real OCR; not an instantaneous maximum or isolated classifier allocation. The
post-document `Debug.getPss()` maximum was 56,412 KiB; it misses rendering peaks.
Reuse prior [OCR/native evidence](STACK_OCR_EVIDENCE.md) for unchanged components.

[Final APK audit](suggestions/apk-audit.json): **31,424,432 bytes**, growth **35,862
bytes** over 0.5.0-dev. No new runtime dependency/model/network permission; aapt
reports CAMERA only. All four supported ABIs and bundled OCR/model hashes remain
unchanged; native alignment/ZIP checks pass. API 24–36 contract is retained;
execution outside Pixel 4a/API 33 and 16 KiB runtime remain unverified. The isolated
view host is debug-only/non-exported and injects fresh test dependencies without
swapping or seeding the real application's repository.

## Verification and reproduction

- Offline `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` pass: 0 lint
  errors, 6 warnings. Cached JDK 25/Gradle 9.3.1/AGP 9.1.1/SDK 36 toolchain.
- Host migration probe compiles **actual current Java migration SQL** and historical
  schema 1/2/3/4 creation code, executes it with SQLite and compares legacy values.
  All four pass before phone upgrade; this supplements Android runtime checks.
- **34 scanner groups**, including actual SQLiteOpenHelper direct upgrades from
  1/2/3/4 to 5, pass. Existing scanner algorithms are unchanged.
- **7 suggestion groups** pass: advisory-only behavior, independent decisions,
  unchanged-evidence dismissal, explicit empty filing, draft provenance/idempotent
  save, legacy optional fields, FTS/search/reopen, current-rule checks, failed-OCR
  partial state, text budgets and manual-only Starred.
- Separate held-analysis **and ready-Apply** fixtures mutate rename, empty filing,
  page edit/removal, retry, changed text, stack/document deletion and newer generation;
  late success/failure and stale actions are rejected without resurrection.
- **8 OCR groups** pass, including actual native OCR, scanner save/import/retry,
  queue/search races and both production workers following OCR in isolated storage.
- Production review view in isolated debug host passes failure/retry, title Edit/Save,
  filing Accept, separate Dismiss and full-filing-editor Cancel. The driver waits
  for async refresh, handles platform uppercase buttons and reacquires accessibility
  nodes after scrolling. Early driver failures are not counted as passes.
- Real force-stop/new-process recovery of a **held durable suggestion claim** passes:
  old token rejected, dismissal retained, no duplicate proposals/memberships. This
  is not a sustained heavy-analysis/CPU kill benchmark.

```sh
python3 tools/verify_suggestion_migrations.py
ANDROID_USER_HOME=/tmp/stack-dev/android-user ./gradlew -g /tmp/stack-dev/gradle-cache \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline --no-daemon --console=plain
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e suggestionsOnly true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
adb shell am instrument -w -r -e suggestionUi true org.stackdocs.app.test/org.stackdocs.app.ScannerChecks
python3 tools/check_suggestion_restart.py --adb /path/to/adb --serial DEVICE --output /tmp/suggestion-restart.log
python3 tools/measure_suggestions.py --adb /path/to/adb --serial DEVICE --output /tmp/suggestion-results.json
python3 tools/verify_ocr_apk.py app/build/outputs/apk/debug/app-debug.apk
```

All automated fixtures use fresh `files/checks/` children; no private library is
seeded, benchmarked or exported. UI checks need an awake accessible display; have
the user unlock if needed. Never clear/uninstall canonical Stack. Instrumentation
is `org.stackdocs.app.test` and may be removed afterward. The temporary approval
review usage-limit error initially prevented installation; retry succeeded when the
user resumed. No rejected action was bypassed.

## Personal-library preservation and remaining acceptance

Current pre-upgrade read-back differed from the old handoff because of user activity:
**6 documents / 12 pages / 8 stacks / 8 memberships**, all 12 OCR jobs succeeded.
Read-only non-OCR metadata SHA-256 before and after upgrade:
`81a062f346909f68fbf3f4f871815e6d4de491b7c2a32bb965cb8e88abba5c36`.
Aggregate original/draft/private recovery hash:
`7c7c1234d1363062e32dec3aa6885ed9f106b01d58d7afb289dda2e962a2443f`.
These cover saved names, memberships and all scanner edit fields without exposing
values, and files under documents/scan_draft/consolidation_recovery. A first draft
of the file-hash command used the wrong recovery directory; only the corrected
pre-upgrade aggregate above is used for comparison. The dedicated synthetic checks tree and instrumentation package were removed after
validation; the file aggregate still matches and canonical Stack was reopened. No
personal content was logged/exported. Stack was updated with `install -r`; no canonical clear/uninstall,
Preview recreation, database replacement or recovery-archive rewrite occurred.

Keep broad acceptance open: diverse real photos/receipts/IDs/forms and longer
layouts, external/human title review, handwriting/multilingual scope, hands-on
new suggestion workflow and OCR failure/retry, accessibility/font-size/device
coverage, actual storage exhaustion, provider/share grants, lifecycle/offline
fresh-install and API/ABI/16 KiB execution. Prior five OCR workflow checks and
scanner approval remain user-reported passes. No structured field editor, learned
profile, batch filing, portable backup, P1 work or automatic renaming/refiling was
added. Finish bounded suggestion/workflow acceptance next; portable backup requires
a later group. All broad P0 acceptance boxes remain open.
