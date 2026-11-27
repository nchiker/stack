# Stack runbook: what is left, in what order, who does it (2026-11-22)

Rule for everything below (from `CLAUDE.md`): nothing the end user has to run, set, or remember. Setup is done by the apps. Publishing only on the owner's explicit go-ahead. Never print or store keys.
Snapshot; the project files named in `PROJECT_ROLLUP.md` are the source of truth.

## 0. State today
- Stack desktop 0.6.2 is published (apt repo, source release `desktop-v0.6.2`) and installed on the owner's computer.
- Stack phone: slice 6 sync done. Slice 5 Stack side done 2026-11-22 (this runbook's change): provider `moved`, `unshared`, Filen-origin events by UUID, "partner cannot open it" warning. 13 check groups pass on the test phone.
- Desktop 0.6.3 is built and passes all 12 checks (not published, waiting for the owner's go-ahead): one-step install (the package ships its own signed update source), quiet open when the file name matches, friendlier second start.
- Slice 7 (partner sharing) built and passing on the test phone and in the one-button run (12 of 12): share archive, publish, import, pairing QR both ways, foreign records synced to the computer (phone schema 16, desktop schema 8, desktop 0.7.0 built, not published). Left: Filen's one-tap folder grants and `OPEN_SHARED` (Filen session), the two-phone end-to-end, the security review, and the partner's own run.
- Slice 5 (Filen events) verified with the real Filen test build on the test phone: rename, move, trash, restore, public-link removal and delete-for-good all reach Stack correctly. Ami Pro slice 4 verified end to end against this build.
- Not done: see the table.

## 1. Parts, owners, tests
| # | Part | Session | Needs test phone | Needs partner computer | State |
|---|---|---|---|---|---|
| A | Ami Pro slice 4 end-to-end check against current Stack, then release 0.5.x | Ami Pro (phone) | yes | no | code written, release waits on owner |
| B | Ami Pro slice 5: write the `filen` locator | Ami Pro (phone) | yes | no | needs Filen `locate` (C) |
| C | Filen slice 5: `locate`/`lookup` provider, send `moved`/`removed`/`unshared` | Filen | yes | no | parked until now; Stack side is ready |
| D | Stack slice 7: publish to a Filen folder, import as foreign, tombstones, `Open` through Filen (phone) | Stack | yes | no (two test profiles) | design in section 3 |
| E | Stack slice 7 on desktop (Linux): same import, no Filen app | Stack | no | final check only | after D |
| F | Filen slice 7: folder change notice, `filen-in` open | Filen | yes | no | after C and D design |
| G | Partner's first install of Stack desktop and phone pairing | owner | no | **yes** | waits |
| H | Retire the Flatpak repo; reconcile local `main` with the remote backup commits | Stack | no | after G | waits |
| I | Untested items: tie review over network, 1000 links, release timing, phone kill tests | Stack | yes | no | do now |
| J | Hand checks: Key Vault menus, tooltips, QR button | owner | no | no | owner |
| K | `.bak` naming from Open in Ami Pro on Linux | Ami Pro Linux | no | no | decision pending |

## 2. Order
1. Stack slice 5 (done). 2. In parallel: C (Filen) and A (Ami Pro). 3. B after C reports the `locate` call works. 4. D/E/F once C is in. 5. G, H last.
Only one session may use the test phone at a time (`0A121JEC203530`): ask by message, wait for "go", tell the others when done. Never the production phone.

## 3. Slice 7 design (partner publish), as built by Stack
Goals: the owner does nothing new; the partner does nothing but sign in to Filen and open Stack.
- **Publish:** a link marked shared (it has a `filen` locator) is exported by Stack into a folder of the owner's own Filen drive, `Stack shared/`, as one encrypted read-only archive (record, text, preview; not the file). Encryption key: the partner's key from pairing (the Stack sharing key), never the owner's backup key. Re-published after changes; tombstones on unshare/remove. Filen shares the folder with the partner once (Filen's own share dialog, asked for by Stack the first time a document is shared).
- **Import:** the partner's Stack reads the received `Stack shared/` folder through Filen Files (`filen-in`), imports records as `origin: foreign, access: shared-read`, and they appear in search with a "Shared by <name>" badge. Manual "Refresh" plus a refresh at app start (Filen folder notices come later with F).
- **Open:** the partner's Open resolves the `filen` locator through Filen Files' shared-file view; without the owner app the cached text and preview still show.
- **Removed or unshared:** next publish carries a tombstone; the partner's copy goes to Recently deleted with "no longer shared".
- **Desktop:** the same archive is read by the partner's desktop Stack from the Filen desktop client's folder, or via a one-time folder choice in the app (no terminal).
- Details are written into `STACK_LINK_CONTRACT.md` as the slice is built.

## 4. Session prompts (paste each into its own session)

### Ami Pro (phone) session, project `~/Projects/AmiPro-APK`
```
Read ~/Projects/Stack/docs/STACK_LINK_CONTRACT.md (sections Operations, Locators, Slice 5 Stack side, Work by project) and docs/STACK_LINK_HANDOFF.md in this repo. Goals, in order:
1. Slice 4 end-to-end: with the real current Stack phone build (versionCode of ~/Projects/Stack at HEAD, built with the test namespace if needed) on the TEST phone 0A121JEC203530 only, check stat, refresh and removed from the real Ami Pro, and note results in docs/STATUS.md. Ask the Stack session (name: stack-4e, via SendMessage) for the phone and wait for "go"; tell it when done. Never the production phone 5A101JEA313018.
2. Slice 5 (after the Filen session reports its `locate` call works, see ~/Projects/Filen/docs/STATUS.md): when a file lives in Filen, ask Filen Files' provider `app.filenfiles.stacklink` (method `locate`, argument = the document URI, answer {uuid, path} or {error}) and write a `filen` locator into the published record. Send `moved` to Stack when a file you changed moves; keep `removed` as is. Do not add permissions without telling the owner.
3. Prepare the next release (versionCode higher than the published one, notes that say the network is used only for update checks) but do NOT publish, tag or sign without the owner's explicit go-ahead. Never print or store keys.
Rules: setup must live in the product (no scripts, env vars or dev-machine paths for the user); cheaper agents for routine work; keep updates short.
```

### Filen Files session, project `~/Projects/Filen`
```
Read ~/Projects/Stack/docs/STACK_LINK_CONTRACT.md (sections Operations, Slice 5 Stack side, Sharing with a partner, Work by project) and your own docs/STATUS.md and ROADMAP.md. Stack's side of slice 5 is built; you may now do your part:
1. Add a content provider `app.filenfiles.stacklink` (pin callers by package and release-certificate SHA-256: Stack `org.stackdocs.app`, Ami Pro `app.amipro.editor`; compute the certificates from their APKs/installed packages, never print keys) with `locate(uri)` -> {uuid, path} or {error:"not_filen"} and `lookup(uuid)` -> {exists, path, trashed, shared}.
2. From your own move/rename/trash/delete/unshare operations, call Stack's provider `org.stackdocs.app.link` (methods `moved`, `removed`, `unshared`) with the Filen-origin messages in the contract: {contract:1, filen_uuid, path} / {contract:1, filen_uuid, reason: deleted|trashed} / {contract:1, filen_uuid}. Stack missing, locked or refusing must never break a file operation: ignore quietly and rely on Stack's next stat.
3. Keep `GRANT_BACKUP_FOLDER` and the locked-writer allowance unchanged (Stack depends on them).
4. Test on the TEST phone 0A121JEC203530 only, after asking the Stack session (stack-4e) for it and waiting for "go". No release, tag or signing without the owner's explicit go-ahead. Never print or store keys.
Rules: nothing the end user must run or configure; cheaper agents for routine work; keep updates short.
```

### Ami Pro Linux session, project `~/AmiPro-Linux` (only for part K, optional)
```
Read ~/Projects/Stack/docs/STACK_LINK_CONTRACT.md section "Desktop Open, Results". When a file is opened through the document portal (path under /run/user/*/doc/), the save leaves a hidden `.xdp-NAME.bak-<random>` file instead of NAME.bak next to the document. Make the backup name predictable and tidy (or do not leave a stray hidden file) without changing behaviour for normal paths, with tests. Do not publish without the owner's go-ahead.
```

## 5. Opus review (2026-11-22), ranked changes and where they stand
1. Terminal commands in phone screens: **released** in Stack 0.19.0.
2. One-step install: **released** in desktop 0.7.0. Ami Pro Linux in the same apt repo: **open** (Ami Pro Linux session).
3. One file chooser per document on the computer: **in progress** (the `filen` locator now exists: Ami Pro slice 5 verified): learn the Filen folder from the first chosen file, derive every other path from the `filen` locator.
4. False hash warning: **released** in desktop 0.7.0 (same file name opens quietly).
5. Edits made on the computer never reach Stack's search: **in progress**, decided: Stack desktop re-checks remembered files' hash/mtime at launch and on window focus and refreshes the saved text and preview; no Ami Pro Linux dependency.
6. Ami Pro slice 4 and 5 (and Filen 0.4.7 events): **verified; Filen 0.4.7 released; Ami Pro release is the Ami Pro session's.**
7. Second launch: plain message **released** in 0.7.0; bringing the window forward (single-instance activation) **in progress**.
8. Phone prompts explained before they appear; Ami Pro daily update check; Filen versionCode compare: **open** (phone app sessions).
9. Partner journey without a Refresh button and without Key Vault: **designed** (slice 7 design).
10. "Set up again" for restored links: **in progress** (restored links adopt the owner's next share of the same file instead of duplicating; automatic check after a restore).
11. Retire the Flatpak repo: **open**, after the partner's install.
12. Stale contract text: **done** (released with the docs).
Also open: rebuild the real-LAN test (T3) into the repo (`test_android_link_lan.py`, `LinkLanChecks`) so tie review over the network, 1000 links, release-build timing and phone kill tests can be run; the old copy existed only in a scratch patch.

## 6. Waiting for the owner (nothing below happens without a go-ahead)
- Publish desktop 0.7.0 (apt repo + source release; includes 0.6.3's one-step install). Install on every computer, because the library format moves to schema 8.
- Release a new Stack phone version (0.19.0 suggested: slice 5 events, partner sharing, no terminal text). Needs the signed release.
- Ami Pro release (slice 4) and Filen release (slice 5 provider): each session prepares, the owner says go.
- Approve a higher-tier security review of key handling and import (PartnerKeys, PartnerImport, ShareArchive, Filen caller pin).
- Partner: install desktop and phone updates, scan the pairing code once in person, choose the shared folder once (until Filen's one-tap grant exists).

## 7. Corrections from the Filen session (2026-11-22)
Filen Files has only `GRANT_BACKUP_FOLDER`. `GRANT_SHARE_FOLDER`, `GRANT_RECEIVED_FOLDER`, `OPEN_SHARED` (and its extras) and a received-folder change notice do NOT exist; they are slice 7 work for Filen that the owner has to scope. Stack treats them as optional: the system folder picker is used for the partner folders and Open shows a plain "needs Filen Files" message. Stack's checks build can call Filen's provider as a pinned caller (`-e filenLocate <uri>`, `-e filenLookup <uuid>`), which unblocks Ami Pro's slice 5 part B.

## 8. Released (2026-11-22)
- Desktop 0.7.0: signed apt repo https://nchiker.github.io/stack-flatpak/apt/ (also carries 0.6.3's one-step install and the security fixes); source release `desktop-v0.7.0`. Every computer needs the update (library schema 8).
- Stack phone 0.19.0 (versionCode 36): https://github.com/nchiker/stack/releases/tag/v0.19.0, signed with the development key (certificate e873f2fd...e311), APK digest 4f0c2ebf...fe3dd. Release-build smoke on the test phone: upgrade 0.17.3 to 0.19.0, Settings and Pair with partner screens, no crash, picker fallback with Filen 0.4.7.
- Security review (Opus, 2026-11-22): no critical findings; fixes for items 1-5, 8, 10, 11, 12 are in; deferred: isolated-process PDF rendering, QR is a long-lived secret (documented in the app), Release Valid-Until.
- Still open: the two-account Filen handshake test (Filen 0.4.8 re-enables pairing), the partner's own install and pairing run, one file chooser per document on the computer, edits made on the computer reaching search, the "Set up again" state after a restore, retiring the Flatpak repo.

## 8b. Released (2026-11-23, on the owner's go-ahead)
- Stack phone 0.20.0 (versionCode 37): https://github.com/nchiker/stack/releases/tag/v0.20.0 (marked Latest), `Stack-0.20.0.apk` SHA-256 820b6648...f84b9, signed with the development key (certificate e873f2fd...e311), built from commit 846fc53, target the source snapshot 77fefcc. The exact APK tested on the TEST phone (update over 0.19.0 kept the library: 164 documents, 2 links, 16 stacks).
- Desktop 0.7.1: signed apt repo https://nchiker.github.io/stack-flatpak/apt/ (`stack-docs_0.7.1_amd64.deb`, SHA-256 4aa4c338...b6067, newest three versions kept: 0.6.2, 0.7.0, 0.7.1); source release `desktop-v0.7.1` (not Latest). Library moves to schema 9; 0.6.2 can upgrade straight to 0.7.1.
- Start page https://nchiker.github.io/stack-flatpak/start/ (text only). Links checked: Install Stack goes to releases/latest (v0.20.0), the computer button resolves to the 0.7.1 .deb.
- Not published, by decision: `stack-suite`, Ami Pro for Linux in the apt repo, the Flatpak retirement.

## 8c. Decided for after the weekend (owner's decision, relayed by the orchestrator 2026-11-23; no code yet)
- **One update control in Stack, Filen Files and Ami Pro (phone apps).** A bar at the top of Home that says "Update available" only when there is an update; one tap downloads it and then Android asks to confirm; "Check for updates" sits at the same place in each app's Settings. Today Stack needs a check first and then a second tap to upgrade (Home notice or Settings, Update check). Each app keeps its own pinned download rules (see the Companion apps screen); only the look and the steps become the same. Owners: each app's own session; Stack's part is the Home bar and the Settings row.

## Refreshing the apt repo
One command: `python3 desktop/tools/refresh_apt_repo.py`. It fetches and verifies the latest Ami Pro Linux release
(`sync_amipro_linux.py`: SHA256SUMS, package name, architecture and version checked), builds `stack-suite_1.0_all.deb`
if absent (`package_suite_deb.py`, depends on stack-docs and amipro-linux), and publishes those plus the stack-docs and
apt-source debs found in `desktop/target/deb` (newest 3 versions per package kept). Default is a dry run into a temp site
checkout; only `--push` commits and pushes the real site. Build a new stack-docs deb first when Stack itself changed.

## 8d. Phone native library and file type registry (folder sharing, 2026-11-26)
- `org.stackdocs.app.types` is the one list of file types (`FileTypes.all()`: stack-scan, pdf, jpeg, png, webp, amipro, text). Library import, page previews, the photo picker/importer and the archive/sync MIME whitelists ask it. Add a type = one handler class + synthetic files in `app/src/androidTest/assets/types/<id>/` (never real documents). Check: `-e types true` (also in `run_all_checks.py` as `phone-types`).
- `libstacknative.so` (Rust, `app/src/main/rust/stacknative`, arm64-v8a, about 1.2 MB) is built by the normal Gradle build (`buildStackNative`, before `preBuild`); nothing to run by hand and nothing needed on a user's phone beyond the installed app. It vendors `amipro-text/-document/-formats` in `app/src/main/rust/third_party/amipro` (README names the AmiPro-Linux commit).
- Developers need: `cargo` on PATH (the crate's `rust-toolchain.toml` pins Rust 1.99.0 with the `aarch64-linux-android` target; rustup installs it), and the Android NDK found the Gradle way: `ndk.dir=` in `local.properties`, else `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`, else the newest `<sdk.dir>/ndk/<version>`. The build stops with a plain message when the NDK is missing. No crates are downloaded (`--offline --locked`, no dependencies). AGP prints a harmless NDK-version note when the NDK differs from its default.
- Devices that are not arm64-v8a install fine but read no Ami Pro text (`NativeText.available()` is false and the handler answers UNREADABLE).

## 9. Release rules
- **"Latest" is always the phone APK.** https://github.com/nchiker/stack/releases/latest is what Ami Pro's "Get Stack" button and the in-app updater follow, so it must land on a release with Stack-<version>.apk. Desktop source releases (`desktop-v<version>`) are created with `gh release create ... --latest=false`; phone releases (`v<version>`) are created normally (or with `--latest`). After any release, check with `gh release list -R nchiker/stack` that the row marked Latest is the phone APK.
- Phone release steps: bump versionCode/versionName in app/build.gradle, build `:app:assembleRelease` (signed with the development key), name the file `Stack-<version>.apk`, write `SHA256SUMS`, push the source snapshot with `tools/backup_github.py --mirror ~/.cache/stack-source-backup --push --public`, then `gh release create v<version> ... --target <that commit> --notes-file docs/RELEASE_NOTES_<version>.md`.
- Desktop release steps: `desktop/tools/package_deb.py --output <fresh dir>`, `gh release create desktop-v<version> <dir>/SOURCE.tar.gz --latest=false ...`, then `desktop/tools/publish_apt_repo.py --deb <deb> --push` (or `refresh_apt_repo.py --push`).
- Nothing is published without the owner's go-ahead typed in the Stack session.

## 10. RESUME NOTE (written 2026-11-22 at shutdown; read this first next session)
Everything is committed locally. NOTHING from the list below is released or pushed except desktop 0.7.0 and phone 0.19.0 (section 8). The local `main` is ahead of GitHub by many commits (the last source snapshot pushed was 1101068); the release tools push snapshots, do not `git push` by hand.

**Built, committed, tested on the phone (not released):** `list_owned` provider call (Ami Pro verified it answers), Companion apps screen (pinned certificate lists, SHA256SUMS/digest/none integrity order), Set up again for restored links (schema 17), the two UI-text passes (docs/UI_TEXT_REVIEW.md; the new strings still need a device re-run), the list_owned test fixes. Device groups last run green before the text passes: linkContract 13 (needs re-run after the test fix), companions 7, linkRestore 6, partnerImport 10, partnerPublish 4, shareArchive 9, linkSync 16, archive 22, features 15; `partnerFilen` needs re-run after its wording assertion fix.
**Built on the computer, committed (desktop 0.7.1, not released):** one chooser per Filen folder, "Changed on this computer" detection with local text refresh (vendored Ami Pro readers, desktop/third_party/amipro, MPL-2.0), single-instance activation, schema 9, "Add Ami Pro?" card via PackageKit (never run for real), apt tooling for Ami Pro Linux + stack-suite (never published; `refresh_apt_repo.py` dry run needs network).

**Partner journey, state of the four pieces:**
1. "I'm joining my partner" first run: PARTIAL. Only the pure logic exists (`PartnerJourney.java`: step state machine, grant route, first-run decision). NOT built: `PartnerJoinActivity` (4 screens + finish), Home first-run cards in `MainActivity`, manifest entries, `partnerJourney=true` test group, string review rows.
2. Static start page: DONE in site/start/ (index.html + start.css; no images, the placeholder picture was removed 2026-11-23) with `desktop/tools/publish_start_page.py` (dry run by default, selftest passes; script logic checked in node against the real package index). No screenshot is planned. Publishing only with the owner's go-ahead (`--push`).
3. "Invite partner": PARTIAL. `PartnerInvite.java` (START_URL, message, share intent) exists; the screen, the button in Partner sharing and the Home menu item are NOT built.
4. "Install Ami Pro" on desktop first launch: DONE in code and mock-tested; needs a real PackageKit check on COSMIC.
Recovery sheet: NOT started.

**Phone queue (TEST phone 0A121JEC203530):** free. Order next time: (a) re-run Stack's device groups (linkContract after the list_owned fix, partnerFilen, plus everything above), (b) (unknown-sources screenshot dropped), (c) device checks for the partner journey once built, (d) Filen's second-profile/junk-account tests, (e) LAN harness `desktop/tools/test_android_link_lan.py --scenario all` if desired. Ask Filen/AmiPro through the orchestrator before taking it. The installed Filen Files is a test-flag build; real Stack 0.19.0 is installed too.
**Next step:** finish `PartnerJoinActivity` + invite screen + Home cards (phone), run the full one-button run (`python3 -I desktop/tools/run_all_checks.py`, ~8 min, uses the phone), rehearse the joining flow on the test phone, then ask the owner for the go-ahead to release phone 0.20.0 + desktop 0.7.1 and publish the start page (apt repo and Ami Pro Linux refresh too). Then the recovery sheet.
**Waiting on others:** Filen 0.4.8 (re-enables one-tap pairing: GRANT_SHARE_FOLDER/GRANT_RECEIVED_FOLDER, needs a two-account test); Ami Pro release; the owner's decisions (retire the Flatpak repo: owner must type it in the Stack session; key rotation: not recommended).
