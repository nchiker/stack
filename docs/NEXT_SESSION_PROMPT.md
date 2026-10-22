# Next session prompt — remaining issues and improvements

Paste everything below the line into a new Claude Code session started in
`~/Projects/Stack`.

---

You're taking over Stack, a document library app with an Android phone app
(Java, `app/`) and a Linux desktop app (Rust/libcosmic, `desktop/`, shipped as a
Flatpak). Phone ↔ desktop sync works and was verified both ways on real devices
in release v0.9.0 (APK 0.9.0 / Flatpak 0.1.0, https://github.com/nchiker/stack).
Read `docs/desktop/CURRENT_RELEASE_HANDOFF.md` first: it has the verified state,
toolchain paths and device rules.

## Goal

Work through the list below in priority order. Each item is done when it's fixed
**and checked on the real devices or by a real test**, not when it's documented.
Ship a new APK + Flatpak at the end if anything user-visible changed.

1. **Phone sync CPU cost (most important).** Each phone ↔ desktop exchange,
   even with nothing to send, costs ~6 s of CPU on the Pixel 4a, and that grows with
   history (~1,500 causal operations now). `SyncStore.load()` parses the full
   state JSON several times per session (`hello`, two `SyncExchange.export`
   calls, `importFile`, plus two `state.saved().toString()` comparisons).
   Options: cache the parsed `SyncProtocol.State` per session, skip no-op
   exchanges when neither side has new operations (compare frontiers in the
   hello), and/or compact history. Measure CPU per sync before and after
   (`/proc/<pid>/stat` utime+stime over 60 s with Stack open). Keep the
   protocol compatible with the desktop or update both sides together.
2. **Desktop "Sync now" doesn't sync.** The phone always initiates; the
   desktop button only runs a tick. Either make the desktop able to prompt
   the phone, or rename/remove the button so it doesn't promise something it
   can't do. Keep wording plain.
3. **Background sync on Android.** Desktop changes only reach the phone while
   Stack is open (plus JobScheduler's ~15 min periodic job, which Android may
   defer). Check the job actually runs (`adb shell dumpsys jobscheduler`), and
   decide whether anything better is reasonable without a foreground service.
4. **Test suite hygiene.** `controller_tests::detail_drafts_quit_choices…`
   asserts `DISPLAY` is unset, so `cargo test` fails on a desktop session;
   make it skip or isolate itself. Re-run the Android instrumented checks
   (they were not re-run for v0.9.0; an earlier run reported a `queuedLock`
   fixture assertion failure in the automatic-sync checks) and fix what fails.
5. **Packaging polish.** Add the GitHub URL as the homepage in
   `desktop/packaging/org.stackdocs.desktop.metainfo.xml` (appstreamcli
   currently warns it's missing). Consider a proper Flatpak manifest that
   builds from source instead of repackaging a prebuilt binary.
6. **Docs cleanup.** `docs/` has many superseded evidence/handoff files from
   earlier sessions. Consolidate into a short current README per app plus the
   handoff; delete or archive stale ones. Don't write new evidence logs.

## How to work (lessons from earlier sessions)

- **Test on the real devices.** Test phone: `adb -P 5693 -s 0A121JEC203530`.
  Never touch any other phone; the primary phone has a different signing key.
  Drive the phone UI with `adb shell input` and `uiautomator dump`; activities
  are not exported, so launch via `monkey -p org.stackdocs.app -c
  android.intent.category.LAUNCHER 1`.
- **The real libraries hold the user's documents** (164 docs on each side).
  Never reset, restore, re-pair, uninstall or clear data. Back up
  `~/.local/share/org.stackdocs.desktop` before experiments. Delete any test
  documents you create and confirm the deletion synced.
- To change the desktop library from a script: stop the app
  (`flatpak kill org.stackdocs.desktop`), use
  `desktop/target/release/stack-cli library ~/.local/share/org.stackdocs.desktop …`,
  then relaunch with `flatpak run --user org.stackdocs.desktop`. Read the
  library read-only with Python sqlite3 (`?mode=ro`) while the app runs.
- Toolchains live in persistent paths (see the handoff). `/tmp` is wiped on
  reboot. The system Java has no `javac`; use the JDK listed in the handoff.
- Run desktop tests with `env -u DISPLAY -u WAYLAND_DISPLAY` until item 4 is
  fixed, plus `cargo fmt --check` and strict Clippy.
- Release builds: bump `versionCode`/`versionName` in `app/build.gradle`,
  `./gradlew :app:assembleRelease`, `adb install -r` (updates in place),
  confirm the pulled `base.apk` hash matches. Desktop:
  `desktop/tools/package_desktop.py` then `package_flatpak.py` (see
  `desktop/packaging/README.md`); keep only the `stable` Flatpak branch.
- Publishing: commit locally, then push the source with
  `python3 tools/backup_github.py --mirror ~/.cache/stack-source-backup --push`
  (the repo on GitHub is a Stack-only checkpoint, not the parent repo).
  Attach binaries to a GitHub Release, never commit them. The repo is
  **public**: no personal document content, keys or credentials in commits.
- Report plainly what was verified on devices versus only built or unit-tested.
