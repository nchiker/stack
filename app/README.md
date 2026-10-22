# Stack for Android

Java, no AndroidX UI framework or Kotlin runtime. Package `org.stackdocs.app`,
Android 7.0 (API 24) or later, target SDK 36.

## What it does

- Scan with the camera (automatic page corners, perspective correction, optional
  gentle-curve flattening, black-and-white or colour cleanup) or import JPEG, PNG,
  WebP and PDF files, including from Android's share sheet.
- Read printed English text offline (bundled Tesseract) and search titles and the
  text of every page.
- File documents into stacks; proposes a name and stacks after reading the text,
  which you accept, edit or dismiss. Dates, amounts and issuers are detected as
  details. Notes, tags and an optional app lock.
- Export a document as PDF (untouched imported PDFs are copied byte for byte), and
  back up or restore the whole library as a `.stackbackup` file.
- Sync with the desktop app over Wi-Fi: see
  [Sync your phone and computer](../docs/STACK_DESKTOP_PHONE_TRANSFER.md).

Originals stay in app-private storage. Uninstalling removes the library, so keep a
backup. Android's automatic cloud backup is turned off for Stack.

## Build

Use a JDK with `javac` (the handoff lists the one used for releases) and the
Android SDK named in `local.properties`:

```sh
./gradlew :app:assembleRelease     # signed with the development key, see docs/STACK_ANDROID_SIGNING.md
adb install -r app/build/outputs/apk/release/app-release.apk   # updates in place
```

Never uninstall to work around an install error: that deletes the library. Bump
`versionCode`/`versionName` in `app/build.gradle` for each release.

## Test

Instrumented checks run in a separate app, so they never touch a real library:

```sh
./gradlew -PstackChecks -PstackChecksNamespace=autosync :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e syncAuto true \
  org.stackdocs.app.checks.autosync.test/org.stackdocs.app.ScannerChecks
```

Each suite is selected with `-e NAME true`. Sync-related suites: `syncAuto`,
`syncLargeState`, `syncUi`, `archiveOnly`, and `-e nearbyPhase unit`; the full list
is in `ScannerChecks.onCreate`. Some suites (such as the queued-lock check in
`syncAuto`) require the `autosync` namespace. Android↔desktop sync over a real LAN
is exercised by `desktop/tools/test_android_sync.py` (see `--help`).
