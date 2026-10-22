# Current release — 2026-10-22 (Android 0.10.0, Flatpak 0.2.0)

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
  `platform-tools`). Signing keys stay in `~/.local/share/stack-signing/`.
- Publish source: `python3 tools/backup_github.py --mirror ~/.cache/stack-source-backup --push --public`.
