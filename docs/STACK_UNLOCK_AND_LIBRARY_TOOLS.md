# Direct unlock, backup date and document sorting — 0.8.7

**Delivered 2026-10-17: 0.8.7-dev / code20 / schema8 / archive3**, implementation
`b5cc48e`. [Actual results](p1/UNLOCK_LIBRARY_TOOLS.json) distinguish automated
fixtures, preservation, agent visual judgment and remaining user actions.

Opening a locked Stack now launches Android's **Unlock Stack** prompt immediately.
The secure host stays blank during authentication. Cancel/error shows **Try again**,
Android security settings and Close Stack; it does not loop. Duplicate resume,
credential handoff, recreation and stale callbacks retain the existing access gate.
System prompt-launch exceptions finish denied and leave recovery available. No
lock policy, enrollment, authenticators or scanner/OCR/detail/naming rules change.
The existing API-specific native prompt/credential branches follow current official
[BiometricPrompt reference](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt)
and [Android authentication guidance](https://developer.android.com/identity/sign-in/biometric-auth).

**All documents → Newest ▾** offers Newest first or Name (A–Z). The choice persists
locally and composes with search/filters; Home Recent always stays newest first.
Name uses SQLite NOCASE with deterministic title/ID tie-breaking. It is not a new
locale-aware indexing algorithm. Sorting changes display order only, never saved
pages, selected export order or category order. The library menu also offers sorting.

**Backup & restore** displays the last successful local backup date, or “No backup
saved on this phone.” It records only after output write/flush/close and snapshot
cleanup succeed. Failed output, canceled restore review and restore do not advance
it. Failure to save this device-local preference does not delete a completed backup.
It records a successful export, not whether a destination still retains that file.
Both view sorting and backup history are excluded from portable library metadata.

The private [primary-phone delivery and instructions](STACK_PRIMARY_PHONE_INSTALL.md)
are ready in `/home/charlie.day/Downloads/Stack-0.8.7/`: normal signed APK, already
converted Google archive, guide and checksums, mode0700 directory/0600 files. This
is the current development build, not a store release. Copies match their sources.
No new personal backup/test restore was created. The Google file is the original
migration snapshot; use a newly saved Stack backup for later Stack edits. Original
`.ab` and PDF-only exports cannot directly supply that metadata through Restore.
No primary-phone install or restore has been performed by the agent.

## Actual automated verification

All **37 affected groups pass** on the disposable Pixel4a/API33/arm64/4KiB runtime.
These are affected reruns, not 37 new unique tests to add to previous totals.

| Runner flag | Groups | Scope |
| --- | ---: | --- |
| backupUi | 2 | Real synthetic import/OCR/choices/export/review/cancel/additive restore; last date visible, failed destination leaves date/archive intact |
| lockUi | 5 | Auto-entry, no duplicate/loop, retry, stale callback, credential handoff, background return/recreation; narrow/2x/landscape |
| convenience | 4 | Fixed SQL order/ties/fallback/filter composition, actual chooser/rows/recreation, Home Recent and backup-date process persistence |
| backupLayout | 2 | Shared Backup View and wrapped date at320dp/1x/2x; synthetic-only captures |
| p1Only | 10 | Protected automatic/manual details, notes, stale workers/actions, local-policy defaults, auth-session result boundary and portable metadata |
| p1Ui | 5 | Editors, Details/settings/library/chooser layouts and protected-owner gate routing |
| archiveOnly | 8 | Authoritative originals/metadata, old/future/invalid inputs, additive restore, failures and streaming PDFs |
| navigationOnly | 1 | Persisted query/tab and deleted-filter recovery |

Unlock fixtures replace only the Android callback boundary using a subclass in the
test APK, restricted to `org.stackdocs.app.checks`. They do not authenticate anyone
or prove native fingerprint/PIN hardware behavior. No fake class/provider/recipient
exists in either normal production APK. Release also excludes the preexisting debug
View host. Eight final synthetic captures were reviewed by the agent; no personal
screenshots. Whole-device Backup capture was replaced with exact View drawing.

Initial sorting fixture failed its second font-size setup: a finishing screen saved
Home after the test reset preferences. Waiting for destruction fixed the fixture;
the rerun passes. Final capture-only reruns pass. No unresolved failure remains.

Offline normal debug/test/unsigned-release/lint builds pass, **lint0errors/14warnings**.
Debug/release static bundled-model/native/ELF/ZIP audits pass:16 libraries/four ABIs,
16KiB alignment. Actual additional ABI/API/16KiB runtime coverage is not inferred.
No new frozen evaluation or human-quality metrics were generated.

## Actual canonical preservation

Original signer verified on installed and new APK:
`db7352c8df2d3643ae9dc119ffd73ee26a700808d53efad3cf60a753e620eeab`.
Canonical built **without `-PstackChecks`**, installed using **adb install -r** only.
Current **143docs/230pages/17stacks/216memberships** stayed identical. All five
aggregate fingerprints match, and all809 details/21 tags/23 relations,230 saved OCR
rows and every other projected prior row are exact. Zero automatic additions.
Originals/draft/recovery remain exact. Counts are observations, never targets.

The existing lock remains **enabled**; photos and import notifications remain off.
No permission/enrollment/authentication or restore/delete/reset was performed on the
personal library. Temporary canonical test APK removed; installed APK hash matches
the deliverable; Stack reopened. APK31,425,546bytes/SHA256:
`d5e18ac24de008542153ff98710e0c76fa82c91b4641abaad870debe1d22656d`.
Private row projections stay `/tmp/stack-unlock-delivery/`; aggregate results only
are checked in. Historical0.8.6 preservation and original Google transfer stay intact.

## One focused pending user checklist

- [ ] Try the new direct native prompt with fingerprint/screen lock, cancel/retry and
  background return on the locked phone. Physical secure authentication is the user's.
- [ ] Install on the primary phone and perform the intended one-time restore using
  the guide; check the migrated library there. This is actual migration, not an
  automated test or a repeated import into the existing phone.

The earlier H1–H8 completion remains [user-reported](p1/USER_COMPLETION.json); do not
restart that session. These new actions are separate. Retain prior automated scope
limits/provisional quality targets; no blanket P0/P1/Google/platform parity claim.
