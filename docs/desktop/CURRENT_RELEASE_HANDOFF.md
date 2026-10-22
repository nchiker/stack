# Current release — 2026-10-22

Real two-way sync between the test phone (Pixel 4a, serial 0A121JEC203530) and
the desktop Flatpak **passed** on the user's actual libraries. Deliverables are in
`.artifacts/stack-release-20261022/` (see `START-HERE.txt` and `SHA256SUMS`):

- `android/Stack-0.9.0.apk` — release, versionCode 23, signer `e873f2fd…e311`.
  Installed on the test phone; the pulled `base.apk` matches the file exactly.
- `flatpak/Stack-0.1.0-x86_64.flatpak` — branch `stable`, Freedesktop 25.08.
  Installed for the user; the old `candidate` branch was uninstalled.

## Verified on real devices (final builds)

Baseline was 164 documents / 257 pages on both devices; the existing pairing
(group `5ccad7b6…`, phone replica `4a385e85…`) was kept, with no reset or re-pair.

- Desktop → phone: imported PDF (pages render), imported PNG, title renames,
  deletions. Phone → desktop: notes, new photo documents (original bytes match
  by SHA-256). Saved phone edits reach the desktop within seconds.
- Restarting the desktop app: the phone reconnects within one poll, no approval.
- All test documents were deleted afterwards (deletion synced); the one renamed
  real document was restored. Both devices are back to 164 active documents.

## Fixes made in this session

1. **Desktop never opened its sync listener (intermittent).** The listener waited
   for one library-status request through the repository worker, issued once
   and never retried. The tick now reads the small pairing files directly and
   retries every 2s until enrollment is known (`network_ui.rs`).
2. **Interrupted transfers leaked `lan-receive-*` staging dirs** in the library
   root (bundles up to 8 GB). Stale ones are removed before each receive.
3. **Phone thumbnails never appeared for synced documents.** They rendered on the
   same single IO executor as the foreground sync loop and were cancelled by
   each post-sync refresh. They now use the `reads` executor.
4. **Phone CPU while open dropped from ~50–70% to ~22% of a core**: idle polling
   is 30s (local saves still sync immediately), the library list refreshes only
   when the computer sent changes, and `hello()` no longer builds a full status.
5. Desktop wording: "Waiting for phone · Syncs when Stack is open on your phone".

Checks: desktop workspace tests 108 passed / 0 failed (5 optional ignored; run
with `DISPLAY`/`WAYLAND_DISPLAY` unset, one controller test requires headless),
`cargo fmt --check`, strict workspace Clippy. Android release build passes; the
instrumented Android check suites were not re-run this session.

## Known follow-up

Each idle phone sync still parses the full causal state (~1,500 operations and
growing) several times, ~6s of CPU per exchange. Caching the parsed state or
skipping no-op exchanges would cut this further.

## Toolchains (persistent; `/tmp` is wiped on reboot)

- Android SDK: `~/.local/share/stack-android-sdk` (`local.properties` points there).
- JDK 25 with compiler: `~/.local/share/stack-jdk/jdk-25.0.5+8` — the system Java
  has no `javac`. Build with
  `JAVA_HOME=… ANDROID_HOME=… GRADLE_USER_HOME=~/.cache/stack-gradle ./gradlew -Dorg.gradle.java.installations.paths=$JAVA_HOME :app:assembleRelease`.
- Cargo cache: `CARGO_HOME=~/.cache/stack-cargo-home`.
- adb: `/tmp/stack-platform-tools/platform-tools/adb -P 5693 -s 0A121JEC203530`
  (also in the SDK's `platform-tools`). Never touch the primary phone; its signing
  key differs.

Signing keys stay in `~/.local/share/stack-signing/` (see `STACK_ANDROID_SIGNING.md`).
