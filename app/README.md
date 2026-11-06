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
- Automatic backup: choose a folder once (Backup & restore > Automatic backup) and Stack saves a new
  `Stack-auto-<time>.stackbackup` about every week, keeping the newest five. The folder can be on any Android
  document provider, including Filen Files' Filen Drive, Google Drive or a USB drive; Stack stores no account.
  After each write it lists the folder to check the file arrived with the right size, because a cloud app may take
  the file first and upload it later. Backups are not encrypted by Stack.
- Duplicate warning: before a PDF or image is added, Stack compares it with the originals it already holds
  (by size first, then SHA-256 of the bytes) and asks whether to skip, open the existing document or add it
  anyway. The same file chosen twice in one selection is caught as well. Scanned documents are not matched.
- Update check (Settings, off by default): once a day while Stack is open it asks the public GitHub release list
  whether a newer Android version exists and shows a notice. It sends no documents or identifiers and never
  downloads or installs anything; the notice opens the release page.
- Recently deleted: deleting a document (one or a selection) moves it to Recently deleted for 30 days (menu >
  Recently deleted: restore, delete for good, or empty). It disappears from lists, search and stack counts but is
  not removed, so nothing syncs: with a computer paired, the document stays there until it is deleted for good here.
  The list is local only (not in backups); a restored backup shows such documents as normal ones. Library database
  version 10 adds the `trash` table.
- Find duplicates (menu): lists documents whose saved originals are identical, keeps the oldest of each set and moves
  ticked extra copies to Recently deleted. A set can never have every copy ticked.
- Backup reminder: a row at the bottom of Home when documents exist and there is no backup yet or the last one is over
  30 days old and automatic backup is off; "Remind me in a week" hides it.
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
