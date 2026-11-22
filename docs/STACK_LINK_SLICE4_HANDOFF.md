# Stack Link slice 4: lifecycle (handoff, 2026-11-21)

Read first: `docs/STACK_LINK_CONTRACT.md` ("Operations", "Lifecycle: move, rename, delete", "Decisions of 2026-11-21", "Work by project"), then
`docs/STACK_LINK_SLICE3_HANDOFF.md` (status and process) and `docs/STACK_ARCHIVE_FORMAT.md` (format 4, envelope).

## Where things stand (end of the 2026-11-21 session)
- Slices 1 to 3 are done. **Stack 0.17.3 (code 34) is published** (GitHub release v0.17.3, source checkpoint f836879) and installed on **both**
  phones: the test Pixel 4a (`0A121JEC203530`) and the production Pixel 10a (`5A101JEA313018`, updated by the user; **hands off** for the agent).
- AmiPro 0.4.0 (commit 73ad5da in `~/Projects/AmiPro-APK`) passed the real-package end-to-end check against Stack 0.17.x: share, search,
  repeat share, protected flag, Open. Its provider today answers only `open`.
- Encryption: backups are encrypted with a recovery key made and shown by the **Key Vault app** (button "Stack backup key (QR)…", repo
  `~/Projects/APK-Keys`, pushed to the private repo nchiker/keyvault-tool). Stack setup is one "Scan key"; no passphrase. Both phones use the same key.
- Small spec gaps from the slice 3 review are closed (confirm dialog before restoring an unencrypted backup; backup date in the review).
- Test phone: the real Stack (`org.stackdocs.app`) and **"TEST Stack linkcontract"** (`org.stackdocs.app.checks.linkcontract`, the checks build).
- Not done from slice 3: **3b** (desktop reads format 4 and encrypted backups, Rust, with the shared vector in the archive doc).

## Goal of slice 4
Stack keeps linked records honest: it asks the owner app whether the file still exists, shows `unreachable` / `missing`, lets the owner say `removed`
(and later `moved`), and gives links a Recently deleted. Phone only. Never treat a failed call as a deletion.

## What was agreed with the AmiPro session (not yet implemented on either side; AmiPro is waiting for the user's go-ahead on slice 4)
Stack calls the owner (`content://<owner>.stacklink`, `ContentResolver.call`, same caller check both ways):
- `stat(arg=link_id)` -> Bundle `{exists:boolean, hash:String sha256 of the file bytes (same hash as published), modified:long ms, locators:String JSON array}`;
  `revision` is optional (AmiPro has none). Not in its map -> `{exists:false}`. Unreadable/offline -> `{error:"unreachable"}`.
- `refresh(arg=link_id)` -> owner re-publishes headless; `{ok:true}` or `{error:...}`; a protected file answers `{error:"protected"}` (never auto-published).
Owner calls Stack (`<stack package>.link`, new methods beside contract/begin/commit/abort; same JSON validation rules as `begin`):
- `removed(arg=JSON {contract:1, owner, link_id, reason:"deleted"|"trashed"})` (AmiPro sends only when it sees the file gone).
- `moved`: skipped for now (Stack learns the new locator from `stat`). `unshared` and `delete(link_id)`: not needed yet (slice 5 / later).
The exact method names, bundle keys and error codes must be **confirmed to AmiPro in one message by Stack** before AmiPro codes. Peer address:
`uds:/run/user/1000/cc-socks/307834.sock` (name `amipro-apk-85`); check `ListAgents` first, it may differ.

## Stack-side work (suggested order)
1. **State model.** `link_records.state` exists (default `live`); define `live | unreachable | missing | trashed` and transitions per the contract diagram. A failed
   call is `unreachable`; only `{exists:false}` or an owner `removed` can make `missing`/`trashed`. Restored locators that are dead later become `unreachable` ("Set up again").
2. **Checker.** A `LinkChecker` that calls the owner's `stat` (like `LinkOpener` calls `open`), on detail open and in a manual "Check linked documents"; compare `hash`
   with the stored hash; a changed hash offers `refresh`. Run off the main thread; time-box the call; never block the UI; one owner failing must not stop the rest.
3. **Provider methods.** `removed` (and ignore-safe `moved`) in `LinkProvider`, with the existing caller check, size limits and strict validation; refuse while locked per the contract.
4. **Recently deleted for links.** Documents have a `trash(document_id, deleted_at)` table and a 30-day expiry; links need their own (links are not in `documents`).
   Decide: a `link_trash` table or a `trashed_at` column; keep text and preview readable; restore on `publish`; expiry like documents; tombstones only when sync arrives (slice 6).
5. **UI.** Badges (grey unreachable, "missing"), the "Check linked documents" action, a Recently deleted section that includes links, detail-screen messages. Reuse the
   existing screens (`LinkActivity`, the library list).
6. **Archive.** `state` is already a row field in format 4; decide whether trashed links are exported (probably yes, with their state) and add checks. No format bump if the schema is unchanged.
7. **Tests.** `LinkLifecycleChecks` in `androidTest` (stub owner answering stat/refresh in the instrumentation process, as `LinkProviderChecks` does for `open`), added to the `linkContract` group.

## Process rules (same as slice 3)
- The user approved: **a higher-tier (Opus) read-only review of the design before coding**, and again of the finished security-relevant code. Use cheaper agents for routine reads, edits and
  test runs. Ask before using anything larger.
- Test phone only (`adb -s 0A121JEC203530`), own namespace: `./gradlew -PstackChecks -PstackChecksNamespace=linkcontract :app:assembleDebug :app:assembleDebugAndroidTest`,
  then `-e linkContract true`, `-e archiveOnly true`, `-e features true` (`-e backupUi true` for the backup screens). JDK/SDK: `docs/desktop/CURRENT_RELEASE_HANDOFF.md` ("Toolchains").
  `ScannerChecks` is the instrumentation component: `org.stackdocs.app.checks.linkcontract.test/org.stackdocs.app.ScannerChecks`.
- The AmiPro session shares the test phone: avoid running instrumentation at the same moment; never touch its repo.
- Release builds: bump `versionName`/`versionCode` in `app/build.gradle`, `./gradlew :app:verifyStackSigning :app:assembleRelease` (signer must be `e873f2fd…`), stage in
  `.artifacts/stack-release-<date>-v<version>/` with `notes.md` and `SHA256SUMS`. **Publishing (GitHub, production phone) only on the user's explicit go-ahead**:
  `python3 tools/backup_github.py --mirror ~/.cache/stack-source-backup --push --public`, then `gh release create`.
- Commit with the Co-Authored-By trailer; update this file's status at the end.

## Lessons from slice 3 worth keeping
- The user is easily lost by similar names. Say plainly which app, which screen, which password. Never ask the user to retype or copy a long key; the Key Vault app does that.
- The real app is `org.stackdocs.app`; test builds are labelled "TEST Stack <namespace>". Check which one is in front (`adb shell dumpsys window | grep mCurrentFocus`) before asking the user to act on the phone.
- Do not run `keyvault` commands that open password windows for the user; they must type their own passphrase. Never read or print the recovery key.

## Status (2026-11-21, end of Stack-side coding)
- Stack side is coded and passes on the test phone: `-e linkContract` (11 groups, incl. 7 new `LinkLifecycleChecks`), `-e archiveOnly` (22), `-e features` (15). Library schema is now 12.
- AmiPro side is coded and committed locally in its repo; not yet run on a device. AmiPro waits for a message from Stack before touching the test phone.
- Stack hardening from the Opus design review: provider identity check (`LinkCallers.trustedOwnerProvider`, also used by `LinkOpener`), calls off the IO executor, compare-and-set writes, strict `stat` parsing, restore forces `unreachable`, trashed links not exported.
- Opus review of the finished code done (2 medium, 5 low findings fixed; read-only view of a trashed link inside Recently deleted is not built). Real-package end-to-end against AmiPro on the TEST app: stat/refresh verified; removed-after-delete pending. Not done: release build/publish (needs the user's go-ahead), desktop 3b.

## Decision (2026-11-21, user via the AmiPro session): deleted files stay "unreachable"
AmiPro cannot tell a deleted Downloads file from one it cannot read (the read grant goes with the file). So AmiPro sends `removed` / answers
`exists:false` only on a clear file-not-found with no row left; otherwise `unreachable`. The user removes such a record by hand ("Remove from Stack",
which goes to Recently deleted). No Stack change. Verified on the TEST app: stat/refresh work against real AmiPro; `removed` is covered by
`LinkLifecycleChecks`, not yet seen from the real AmiPro (it cannot be triggered on this phone). Remaining: release build and publish, on the user's go-ahead.

## Published (2026-11-21)
**Stack 0.18.0 (code 35)** is on GitHub (release v0.18.0, source checkpoint 1f0d260, signer e873f2fd…, SHA-256 of the APK cf597e37…3ed4). Staged in
`.artifacts/stack-release-20261121-v0.18.0/`. The test phone still runs the TEST build; the production phone (`5A101JEA313018`) is NOT updated: the user does that
from Stack's Update notice. Next slices: 5 (unshare/delete in Stack), 6 (sync tombstones), 3b (desktop reads format 4 and encrypted backups), a read-only view of a trashed link.
