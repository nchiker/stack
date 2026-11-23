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
1. Terminal commands in phone screens: **done** (BackupActivity text now names Key Vault's own screens). Not published (phone release needs the owner's go-ahead).
2. One-step install: **done in 0.6.3** (not published). Ami Pro Linux in the same apt repo: **open** (Ami Pro Linux session).
3. One file chooser per document on the computer: **open**; needs Ami Pro's `filen` locator (slice 5, part B) and a folder-once design in Stack desktop.
4. False hash warning: **done in 0.6.3** (same file name opens quietly).
5. Edits made on the computer never reach Stack's search: **open** (Ami Pro Linux publishing, or Stack re-reads the remembered file's hash); needs a design decision.
6. Ami Pro slice 4 release and slice 5: **open** (A, B, C).
7. Second launch error: message made plain in 0.6.3 (Stack says it is already open); bringing the window forward needs libcosmic single-instance activation, **open**.
8. Phone prompts explained before they appear; Ami Pro daily update check; Filen versionCode compare: **open** (phone app sessions).
9. Partner journey without a Refresh button and without Key Vault: **designed** (slice 7 design).
10. "Set up again" state for restored links: **open** (Stack phone).
11. Retire the Flatpak repo: **open**, after the partner's install.
12. Stale contract text: **done**.
Also open: rebuild the real-LAN test (T3) into the repo (`test_android_link_lan.py`, `LinkLanChecks`) so tie review over the network, 1000 links, release-build timing and phone kill tests can be run; the old copy existed only in a scratch patch.

## 6. Waiting for the owner (nothing below happens without a go-ahead)
- Publish desktop 0.7.0 (apt repo + source release; includes 0.6.3's one-step install). Install on every computer, because the library format moves to schema 8.
- Release a new Stack phone version (0.19.0 suggested: slice 5 events, partner sharing, no terminal text). Needs the signed release.
- Ami Pro release (slice 4) and Filen release (slice 5 provider): each session prepares, the owner says go.
- Approve a higher-tier security review of key handling and import (PartnerKeys, PartnerImport, ShareArchive, Filen caller pin).
- Partner: install desktop and phone updates, scan the pairing code once in person, choose the shared folder once (until Filen's one-tap grant exists).

## 7. Corrections from the Filen session (2026-11-22)
Filen Files has only `GRANT_BACKUP_FOLDER`. `GRANT_SHARE_FOLDER`, `GRANT_RECEIVED_FOLDER`, `OPEN_SHARED` (and its extras) and a received-folder change notice do NOT exist; they are slice 7 work for Filen that the owner has to scope. Stack treats them as optional: the system folder picker is used for the partner folders and Open shows a plain "needs Filen Files" message. Stack's checks build can call Filen's provider as a pinned caller (`-e filenLocate <uri>`, `-e filenLookup <uuid>`), which unblocks Ami Pro's slice 5 part B.
