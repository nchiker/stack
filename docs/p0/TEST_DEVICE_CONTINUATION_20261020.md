# One Stack application and P0 test-device continuation — 2026-10-20

The user explicitly authorized uninstalling the old Stack successor and Stack
Recovery, losing their disposable data, on **Pixel 4a / sunfish / API33 / arm64**,
serial **0A121JEC203530**. Every ADB operation used that serial and server port5693.
The primary phone was never accessed or modified. This waiver applies only to the
spare; it is not authorization to replace the primary or its library.

## Installed application and permanent signing

- One launchable Stack application: **`org.stackdocs.app`**, version0.8.9-dev,
  code22, schema9. The non-launcher `org.stackdocs.app.test` instrumentation helper
  remains for P0 work; it has no separate user application/library to maintain.
- Old `org.stackdocs.app` and `org.stackdocs.app.recovery` were uninstalled, with
  Success responses, before fresh installation. The six old Checks/checks-test
  packages were subsequently removed. No unrelated application was removed.
- The installed public base APK matches the frozen debug APK byte for byte:
  `9542bb5f31008c7adfabe2b21b97c88b48bba5d4e216819b8c791d83a268e6de`.
- Permanent public signer SHA256:
  `e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311`.
  Debug, release and instrumentation candidates share it. Installed debug and
  release-candidate update preflights pass package/version/minSDK/certificate checks.
- `-PstackRecovery` now fails explicitly; no Recovery package/label/profile branch
  remains. Normal builds use the permanent development profile with the original
  package name. Optional isolated Checks namespaces remain test infrastructure.
- All **13 real Gradle signing cases pass**: managed key, independently restored
  backup, original-package selection, retired Recovery flag, missing key/backup,
  wrong password, temporary/repository-local paths, wrong certificate, unsafe file
  permissions, same-file backup and unsigned-debug refusal.
- Offline debug/instrumentation/release builds and debug/release lint pass. A fresh
  Android user directory did not create a replacement debug keystore.

The permanent PKCS12 and independent encrypted backup still have SHA256
`5b61cd09cc08a2076b0c7aedaca7bfc5babeb824c1215515c0036b714202a909`;
private600/700 permissions, distinct files and separate recovery credentials remain
intact. Prior independent backup reopening/private-key use remains valid. Neither
keys nor private credentials are in Git or delivery artifacts. Both backup copies
are on this computer; no off-machine recovery claim is made. The original historical
signer/private key was not recovered. [Signing and recovery procedures](../STACK_ANDROID_SIGNING.md).

## Actual checks and P0 continuation

The user requested lower-model agents for routine testing. The main agent made
the signing/test changes and diagnosed failures; the two existing verifier roles
executed standard builds, guards, APK inspection and device tests. No desktop
windows were opened. Physical spare-phone UI/camera checks were explicitly in scope.

| Check | Verified result |
| --- | --- |
| First saved-library backup/restore UI | 2 groups pass: production PDF import, bundled native OCR/suggestions, manual naming and explicit empty filing, later-page text search, backup, review/cancel/retry, additive restore; failed destination preserves prior backup/history |
| Original-package smoke | 5 groups pass: MainActivity launch, production two-page scan commit/native OCR, actual search input finds OCR text absent from title, physical rear-camera JPEG capture, review recreation preserves original, Save scan commits it, original digests verified |
| Real process restart | 1 group passes after host `am force-stop`: saved authored/camera originals retain their digests; OCR and the page-two search hit survive the new process |
| Future same-key update | Frozen debug `adb install -r` succeeds; 1 continuity group verifies saved originals, OCR and page-two search again; only the canonical app has a launcher, and it launches successfully |
| Scanner regression | 37 groups pass: geometry, migrations, draft/edit/original preservation, boundaries, EXIF, PDF/FTS and fixed-source crop/cleanup/flatten behavior |
| Focused native OCR | 8 groups pass: bundled model repair, real OCR/blank results, search/restart/export, retry/race cleanup, crash budget/manual retry and worker responsiveness |

Backup/restore and the final smoke/scanner/OCR runs executed with airplane mode
enabled and Wi-Fi disabled. Original airplane/Wi-Fi/mobile-data preferences0/1/1
were restored in `finally`. This supplies actual offline evidence despite the
legitimate INTERNET permission for optional LAN sync. Backup checks use authored
file-URI destinations, not the Android system document picker. Camera capture
proves the physical camera path; the photographed scene and its OCR accuracy were
not graded. No screenshot, camera original, database, private document, key or
credential was exported in the public test reports/artifact.

The offline checks total53 passing groups; the post-update continuity check adds
one more. The update check ran after restoring the radio settings.

P0 work resumed through this production offline-library flow and physical-camera
lifecycle/storage check. `BackupUiChecks` previously asserted that an offline app
must lack INTERNET permission; it now checks local bundled-model availability and
relies on the separately recorded offline device state. The new explicitly opted-in
smoke can exercise the canonical disposable library without maintaining another
installed application. Ordinary scanner fixtures remain in isolated child contexts.
The explicit disposable smoke discards an interrupted spare draft before retry;
never invoke it on a library whose draft must be preserved.

## Retained failures and their repairs

The first offline harness refused to run when `svc data disable` left the
mobile-data preference at1; it restored settings and did not run instrumentation.
The subsequent airplane-mode/Wi-Fi-disabled run passed the fresh-library checks.

The initial camera smoke crashed in its test-only name selector:
`ClassCastException: TextView cannot be cast to EditText`, at the visible
"Document name" label. Actual camera capture and review recreation had already
completed. Selecting the editable field by type and content description fixed the
harness. Only the instrumentation APK was rebuilt/reinstalled, and all affected
smoke/restart checks then passed. The failed log remains historical, never counted
as a pass. That behavior-fix test APK SHA256:
`b7f3d10e91493892491b09f21e5e7f814e7b2e76044213cc8be187a953f98437`.
The final comments/report footer also distinguish a physical camera scene from
authored fixtures. AndroidTest rebuild/lint and one retained-library resume check
pass after that text-only adjustment. Final installed/delivered test APK SHA256:
`cf5ce737cdbce7aaf71c125044abde68875e2cf37ddd7e159f31a6ac030378a0`.

## Delivery, retained data and limits

Current APKs, exact Android source snapshot, public evidence and SHA256 inventory:
[`.artifacts/stack-single-app-20261020`](../../.artifacts/stack-single-app-20261020/README.md).
The source snapshot includes the preexisting uncommitted UI/icon/version changes
used by these builds. The cleanup commit preserves those unrelated edits rather
than absorbing them; a clean checkout of the commit alone is not the exact code22
artifact input. Earlier artifacts, encrypted signing backups and both private
old-spare snapshots remain retained unchanged. No primary backup/restoration or
historical original-key recovery is claimed.

The user-completed H1–H8 session remains completed for its recorded scope. These
new tests do not establish broad camera-photo OCR quality, physical permission-
denial/unlock/TalkBack/system-picker acceptance, other APIs/ABIs/16KiB execution,
maximum-size Android library performance, physical disk-full or power-loss behavior.
Only this API33/arm64/4KiB handset is available, and `/dev/kvm` is unavailable.
Those resource/observation gates remain qualified in the existing project plan;
they do not block normal development updates on the newly signed spare. The primary
cannot receive this differently signed APK as an in-place update and stays protected.
