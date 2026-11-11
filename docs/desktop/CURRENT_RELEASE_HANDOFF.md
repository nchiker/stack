# Current release — 2026-11-11 (Android 0.14.0 published; Flatpak 0.2.0)

## Android 0.14.0 (versionCode 28, published 2026-11-11): updates like Filen Files

Release APK `Stack-0.14.0.apk` (SHA-256 `439b1939…4e87`, signer `e873f2fd…e311`), staged in
`.artifacts/stack-release-20261111-v0.14.0/`; installed on the 4a over 0.13.0: Update check shows "On · Up to date", no crash.


- Update check is ON by default now (pref `updates/enabled`, default true; an explicit Off stays off). Same single
  anonymous daily request. The Home notice and Settings offer **Download**: the release APK's own
  `browser_download_url` (kept only if it is under `github.com/nchiker/stack/releases/download/` and names the same
  `Stack-x.y.z.apk`; otherwise the release page), plus **What's new** (release page) and **Not for this version**.
- Device-tested on the 4a with a temporary 0.12.9-labelled debug build: it found v0.13.0, showed the notice, and
  Download opened Chrome on the APK (Chrome then asks "File might be harmful / Download anyway"; that is the owner's
  tap). Phone put back on the 0.13.0 release. `features` group 1 covers the link rules.

## Android 0.13.0 (versionCode 27): one-tap Filen Drive folder, keep time

Release APK `Stack-0.13.0.apk` (SHA-256 `8519a91d…67d9`, signer `e873f2fd…e311`), staged in
`.artifacts/stack-release-20261111-v0.13.0/`; installed on the 4a as an in-place update from a 0.12.0-code debug build
(settings kept, Keep backups / Use Filen Drive shown, job still scheduled, no crash). Needs Filen Files 0.4.2+ for the
one-tap folder; older Filen Files or none = the system folder picker only.


- **Use Filen Drive** (Automatic backup, and Change folder): when Filen Files 0.4.2+ is installed, Stack calls
  `app.filenfiles.action.GRANT_BACKUP_FOLDER` (`FilenFolder`, `<queries>` entry) with `startActivityForResult`. Filen
  Files shows one Allow screen (unlock first if its App lock is locked), creates or reuses "Stack backups" at the top of
  Filen Drive, and returns a persistable READ|WRITE|PREFIX tree grant; the result goes through the normal
  `folderChosen` path. Cancel codes: `app.filenfiles.extra.ERROR` = not_signed_in | offline | failed (+ `MESSAGE`)
  are shown; user_cancelled / no_caller are silent. The Allow is the owner's consent: agents never tap it.
  The Allow also puts Stack on Filen Files' locked-writer list: list/read/create file/write/delete inside that tree
  work while Filen Files is locked (Settings > Security there turns it off).
- **Keep backups** row (shown while automatic backup is on): 1 month (default) / 3 / 6 months / 1 year / Forever,
  pref `keep_days` (-1 = forever, unknown values fall back to 30, survives Turn off). `AutoBackup.expired` removes
  Stack-auto files whose NAME date is older than that; never the new file, never the newest older one, never an id
  containing "pending" (deleting a Filen pending row cancels its upload), never an undated name. Replaces "newest 5".
- Device-tested 2026-11-11 on the 4a with the Filen session (Filen Files 0.4.2 debug, owner tapped Allow): first
  backup 217 MB confirmed; a second setup returned the same tree (filen:02280617-...); with Filen Files force-stopped
  (cold = locked) and the screen off, job 1094863696 wrote and uploaded a new backup (Android refused Filen's
  foreground service; its WorkManager fallback finished it) and pruned a planted 71-day-old file, keeping a 17-day one.
  Suite flag `features` group 13 covers the keep rule.
- Leftovers in the owner's real Filen Drive > Stack backups from that test: three 217 MB test-phone backups
  (2026-11-11 175516, 175801, 180238) and a 4-byte `Stack-auto-2026-10-25-120000.stackbackup`. Retention removes them
  in time; delete earlier only if the owner asks.

## Android 0.11.0 (versionCode 25): automatic backup

Backup & restore has a new **Automatic backup** row. The user picks a folder once (Android folder picker, so any
document provider works: Filen Files' Filen Drive, Google Drive, USB) and Stack writes `Stack-auto-<time>.stackbackup`
about weekly, keeping the newest five (only that name pattern is ever pruned). Code: `AutoBackup` (policy, schedule,
upload check), `AutoBackupJob` (daily JobScheduler wake-up, id `0x41424b50` = 1094863696), `ArchiveController.exportToFolder`.
Details that matter later:

- A first backup runs when the folder is chosen. A run waits while app lock is locked and also fires from the foreground
  hook (`StackApplication.automaticSync`) when due. After a failure it retries after 6 hours.
- After each write Stack compares the size the provider reports with the bytes written (mismatch is reported, the file is
  never deleted) and lists the folder: a settled row with the right size = confirmed; a row whose document id contains
  `pending` = a cloud app still uploading; summary text starting `Upload failed` or a day without arrival = failed, which
  clears `last_ok` so the next run retries. This relies on standard document-provider queries only.
- Cloud side: the Filen Files documents provider (0.3.x) falls back to an expedited WorkManager job when Android refuses its
  foreground service for a background write, lists in-flight uploads with `COLUMN_SUMMARY`, and answers listings offline
  from a cache. Filen Files with its own App lock locked refuses every provider call, so unattended backups need that off.
- Device-tested on the Pixel 4a (Filen Files 0.3.0): manual backup and restore, 11 scheduled cold runs with the phone locked,
  offline run, retention, and a real 215 MB library (written in about 16 s, confirmed within 33 s).
- Test tips: `am force-stop` cancels the scheduled job; use `am crash <pkg>` to end the process, edit
  `shared_prefs/auto_backup.xml` with `run-as` (debug builds only), then `cmd jobscheduler run -f <pkg> 1094863696`.
- Added in 0.12.0 (versionCode 26, signed APK built and smoke-tested on the Pixel 4a as an in-place update from 0.11.0 incl. DB 9 to 10; published 2026-11-06): duplicate warning on import (`DuplicateCheck`, size first then SHA-256, no
  stored state), optional update check (`UpdateCheck`, off by default, one request a day to the GitHub release list, never
  installs), Recently deleted (`TrashActivity`, library DB v10 `trash` table + trigger, local only, 30 days, deleted
  documents stay in the synced tables and on the computer until purged), Find duplicates (`DuplicatesActivity`), and the
  Home backup reminder (`BackupNudge`, a header row at the end of Home; a banner above the list broke the `convenience`
  check because it pushed "Recent documents" off screen).
- Test notes: suite flag `features` covers all of the above (12 groups). `convenience` needs a backup date from an earlier
  process: run `backupUi` then `convenience` in the plain `org.stackdocs.app.checks` copy without clearing in between.
  `stackEditor` asserts DB version 10. The `P1BatchChecks` delete check now expects trashed, not removed, documents.
- Not built yet: backup encryption (a passphrase envelope around the archive stream, chunked AES-GCM, would need the
  passphrase kept safe for scheduled runs).


Release v0.10.0 on GitHub: `Stack-0.10.0.apk` (versionCode 24, signer
`e873f2fd…e311`, SHA-256 `854ae450…`; both phones run exactly this file) and `Stack-0.2.0-x86_64.flatpak` (branch `stable`, built from
source by `desktop/tools/build_flatpak.py`). Local copies are in
`.artifacts/stack-release-20261022-v0.10.0/`.

## Devices

- **Production phone**: Pixel 10a, serial `5A101JEA313018`. Moved to Stack 0.10.0
  on 2026-10-22. Its old 0.8.9 install was signed with the lost original key
  `db7352c8…`, so it was backed up, uninstalled by the user, the new APK installed
  and the backup restored by the user. The verified backup (164 documents, 257
  pages) is kept at `~/.local/share/stack-production-backup-20261022/`.
  **It is now the phone paired with the desktop** (since 2026-10-22, 13:40).
  The desktop started from an empty library and received the phone's 158
  documents / 250 pages / 16 stacks; a stack created on the desktop reached the
  phone and its deletion on the phone reached the desktop.
- **Test phone**: Pixel 4a, serial `0A121JEC203530`, signer `e873f2fd…`. No longer
  connected to the desktop; it keeps its own copy of the old library and is free
  for testing.
- The desktop's previous library (paired with the test phone) and an old
  development install were deleted at the user's request after the switch.
- The desktop app is the Flatpak only. An old standalone development install
  (`~/.local/lib/stack-desktop`, its `~/.local/bin/stack` and a launcher with the
  same desktop-file ID as the Flatpak) had been hijacking the Stack icon; it showed
  "Sync exchange" pages and could not read current sync state. It is removed.
- Two adb servers may run: the default one (port 5037) often owns the phones.
  Use `adb -P 5037` if `-P 5693` lists nothing. A udev rule for Google devices is
  in `/etc/udev/rules.d/51-android.rules`.
- The desktop syncs with exactly one phone (a two-member group). Switching phones
  means Disconnect, then a new connection. Two libraries created separately (for
  example from separate imports or restores) have different document identities
  and would be merged as duplicates; start one side empty instead.

## Verified this release

On the real devices (test phone ↔ desktop, the user's real 164-document libraries):

- Phone CPU with Stack open and nothing to sync: **13.3 s → 0.41 s per minute**
  (`/proc/<pid>/stat`, 60 s windows). Per idle exchange ~5.3 s → ~0.1 s.
- Equal frontiers end the session after the hello; real changes still flow:
  a desktop-created stack reached the phone; phone renames, a visibility toggle
  and the deletion reached the desktop. The test stack is deleted on both sides.
- Renaming a stack on the phone: capture **121–149 s CPU and ~440 operations →
  under 2 s and 2 operations**. Any local edit's capture ~4.7 s → ~1.8 s.
- The new phone build against the old 0.1.0 desktop falls back to the plain hello
  and keeps syncing.
- Background job: with Stack killed and the phone unplugged, the periodic job ran
  by itself (13:11) and finished normally; before the fix, app start-up
  rescheduling cancelled the running job.

Tests: desktop workspace 108 passed / 0 failed / 5 ignored with `DISPLAY` set,
fmt, strict Clippy. Android instrumented suites `syncAuto` (twice, including
`queuedLock`), `syncLargeState`, `syncUi`, `nearbyPhase unit`, `archiveOnly` pass
in `org.stackdocs.app.checks.autosync`. `desktop/tools/test_android_sync.py
--transport --recovery` passes all 16 Android↔Rust checks over real LAN.

## What changed

- Phone: parsed sync state cached per process; no-op imports no longer serialize
  the state twice or re-hash every original; hello carries the frontier;
  suggestion-row changes from one capture become one operation; sync manifests
  hash originals in place (no hard-link snapshot) with a hash cache.
- Desktop: optional `frontier` in the hello (`deny_unknown_fields` kept; it is
  echoed only to peers that sent one); cheap `sync_summary` for each session;
  receipts recorded on skipped sessions; the "Sync now" button is removed (the
  phone always starts a sync).
- SyncJob: not rescheduled when a current job exists; needs Wi-Fi/Ethernet, not
  internet; no back-off retries when the computer is unavailable.
- Phone Sync page: spacing between the computer name, status, last sync and
  buttons (it was jammed together).
- Packaging: from-source Flatpak manifest; homepage and releases in metainfo;
  `package_desktop.py` refuses stale binaries; `rust-version` 1.98.
- `tools/backup_github.py` requires `--public` to push to the public repository.
- Docs: superseded plans and evidence removed (still in git history).

## Known issues

Next-version fixes the user hit on 2026-10-22:

1. Disconnecting on the phone while it cannot reach the computer leaves the
   computer paired; the phone then says to use the computer's "Connect phone",
   which the computer does not show until it is also disconnected.
2. The desktop's Sync page is reached only through a small "Settings" entry at
   the bottom of the sidebar; users looked for it elsewhere.
3. Nothing detects a stale standalone install whose launcher shadows the Flatpak's.

Network: on the GL-BE9300 (Flint 3) router, the phone and laptop could not reach
each other at all (no ARP) while both used the 6 GHz radio, the phone on the MLO
network "Asus7"; traffic between bands worked. It cleared after the phone
restarted and joined "Asus". If "Computer unavailable" returns while the Flatpak
is running, check 6 GHz/MLO client isolation in LuCI before suspecting Stack.

Other:

- Causal history keeps growing (~2,570 operations; two test renames before the
  fix added ~1,000). The first state parse in each process costs ~1–1.5 s, then it
  is cached. History compaction is not implemented.
- The desktop spends ~1.5 s per bundle step on sessions that do transfer.
- Android may defer the 15-minute job (Doze, standby buckets). Changes from the
  desktop arrive within ~30 s only while Stack is open.
- The Flatpak bundles prebuilt PDFium/OCR engines and the host's libjpeg8.

## Toolchains (persistent; `/tmp` is wiped on reboot)

- Android SDK `~/.local/share/stack-android-sdk`; JDK
  `~/.local/share/stack-jdk/jdk-25.0.5+8` (the system Java has no `javac`).
  `JAVA_HOME=… ANDROID_HOME=… GRADLE_USER_HOME=~/.cache/stack-gradle ./gradlew -Dorg.gradle.java.installations.paths=$JAVA_HOME :app:assembleRelease`
- Cargo cache `CARGO_HOME=~/.cache/stack-cargo-home`; Flatpak SDK 25.08 with
  `rust-stable` (Rust 1.98.1) installed for the user.
- adb: `/tmp/stack-platform-tools/platform-tools/adb` (also in the SDK's
  `platform-tools`). Signing keys stay in `~/.local/share/stack-signing/`
  (properties in `~/.config/stack-signing/`). A backup copy of both folders is in
  `~/APK-Keys/stack/` (`share/` and `config/`), which Key Vault encrypts to Filen.
  If the key changes, refresh that copy and ask the owner to click Back up now.
- Repository: Stack has its own git repo in `~/Projects/Stack` (split out of the
  old shared `~/Projects` repo on 2026-11-11, history kept). Its `origin` is
  nchiker/stack, but that public history is separate snapshot commits: never
  `git push` directly; publish only with the command below.
- Publish source: `python3 tools/backup_github.py --mirror ~/.cache/stack-source-backup --push --public`.
