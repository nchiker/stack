# Permanent Android development signing

Current2026-10-20: one maintained application, **Stack** (`org.stackdocs.app`).
The user explicitly authorized replacing the old app and losing its data on the
attached **Pixel4a, serial0A121JEC203530**. That permission does not apply to the
primary phone. The temporary Stack Recovery package/build variant is retired.
Its old flag fails with an explanatory error instead of selecting another package.

## Permanent identity and backup

Normal debug/release builds and optional isolated Checks builds all use the pinned
permanent development identity:

- Keystore: `~/.local/share/stack-signing/development.p12`.
- Private credentials: `~/.config/stack-signing/development.properties`.
- Encrypted backup:
  `~/.local/share/stack-signing-backups/20261020T193004Z-8640dffc/development.p12`.
- Separate private recovery credentials:
  `~/.config/stack-signing/recovery-20261020T193004Z-8640dffc/development.properties`.
- Public certificate SHA256:
  `e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311`.

The key/backup are distinct files with equal hashes; an independent restored copy
successfully used its private key. PKCS12 uses PBES2/PBKDF2/AES256-CBC with a strong
locally generated password. Files are600, containing directories700. Credentials
are not printed, committed, or bundled with APK/source artifacts. The backup and
credential recovery copy remain on this computer; no off-machine/disaster backup
is claimed. Keep any future offline copy encrypted and its credentials separately
protected. Never overwrite an existing backup when making another copy.

The original `db7352c8…e620eeab` private key was not recovered. The `canonical`
section in `gradle/signing-policy.json` records that historical identity; it is
not the selected profile for current builds. The current development identity is
an explicit change authorized for the disposable test installation, not a way to
update an old-signed primary installation. No primary operation was performed.

## Normal builds

```sh
export JAVA_HOME=/tmp/stack-dev/jdk-25.0.4.1
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/tmp/stack-dev/android-sdk
export GRADLE_USER_HOME=/tmp/stack-dev/gradle-cache
./gradlew --offline :app:verifyStackSigning
./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew --offline :app:assembleRelease :app:lintRelease
```

The toolchain/cache may be temporary; signing credentials must not be. All normal
builds use `development.properties`, independent of ANDROID_USER_HOME or Gradle's
usual debug-keystore location. A custom signing configuration prevents implicit
key generation. Missing key/config/backup, a different certificate, wrong password,
unsafe permissions, repository-local or temporary credentials and identical-file
backups all fail before signing. The public policy pins both identity and allowed
package names. A new key cannot silently substitute for the established signer.

`-PstackChecks -PstackChecksNamespace=NAME` remains an optional isolated testing
namespace, not a second maintained product. No Checks installation is necessary
for ordinary development on the authorized disposable phone. Explicit
`-PstackUnsignedRelease :app:assembleRelease` only produces an unsigned candidate;
it cannot produce an installable debug build. `-PstackRecovery` is retired.

`tools/stack_signing.py` is an explicit provisioning tool, never a build dependency.
It only adopts a source key already matching the reviewed policy, stores encrypted
copies outside the repository and refuses existing key/config/backup paths. Do not
rerun provisioning to repair a missing file; restore the established identity:

1. Preserve the remaining files and identify the matching encrypted backup receipt.
2. Restore the encrypted PKCS12 to a new private path and restore the separately
   protected credential file. Correct absolute store/backup paths if necessary.
3. Set private600/700 permissions. Keep primary and backup as independent files.
4. Run `:app:verifyStackSigning`; it checks the private key and pinned certificate.
5. Build and run the installation preflight against the exact target device.

Never put private keystores or password files into source or APK distributions.
The source backup excludes key formats; Git ignores PKCS12/JKS/key-store files.

## Device targeting and installation

Use the exact explicitly authorized serial for every command. Before any proposed
in-place update:

```sh
python3 tools/check_android_update.py \
  --adb /tmp/stack-dev/android-sdk/platform-tools/adb --adb-port 5693 \
  --serial 0A121JEC203530 --apk app/build/outputs/apk/debug/app-debug.apk \
  --apksigner /tmp/stack-dev/android-sdk/build-tools/36.0.0/apksigner \
  --aapt2 /tmp/stack-dev/android-sdk/build-tools/36.0.0/aapt2
```

This read-only check verifies package/version, minimum SDK/device API and public
certificates. Mismatches or failed device queries do not become permission to
uninstall. ABI/runtime/migration correctness requires separate qualification.
Future updates on the newly established test installation use `adb install -r`
with the same permanent signer. Do not put automatic uninstall/data-clear in a
build or installation helper. The one-time old-signer removal was a user-authorized
exception for this specific disposable phone.

The old-signed primary app cannot receive these APKs as an ordinary update.
Android independently refuses its mismatched signer. Leave it unchanged unless
separate, explicit primary migration instructions are given. Old backup/archive
files, the earlier recovery artifact and private snapshots remain retained.

## Data export and historical recovery

The existing app's **Backup & restore → Backup library** writes a portable,
unencrypted `.stackbackup`. It includes saved documents, originals, pages/edits,
notes/details, stacks, organizations, OCR and review choices. Save unfinished scans
first. Device lock/grants, unfinished drafts, consolidation recovery, Keystore
keys and sync transport credentials/history are not portable library state.
Repeated restore adds copies; it is not a way to reconcile independently edited apps.

The optional `tools/backup_android_app.py` performs a read-only snapshot only when
ADB/run-as is authorized, the installed app is debuggable and not running, and
credential-encrypted storage is available. It refuses existing destinations;
copies files/databases/preferences/no_backup, including sidecars when present;
compares copied hashes against the device; requires library.db and validates a
separate SQLite copy; and saves the installed public APK. It never stops apps,
uninstalls, restores or claims to export Android Keystore private keys. Output is
private700/600 local plaintext, not a portable archive or proof of full restoration.

The previously captured spare snapshots remain at:
`~/.local/share/stack-recovery/spare-20261020-verifier` and
`~/.local/share/stack-recovery/spare-20261020-final-verifier-194129`.
They were verified381-file/schema8 copies of the old spare installation, not the
primary. They are retained unchanged despite the user's later data-disposal waiver.

The [historical key investigation](desktop/SIGNING_KEY_RECOVERY_20261020.md) and
[earlier recovery delivery](../.artifacts/stack-signing-recovery-20261020/README.md)
remain qualified historical records. The [Android signing guide](https://developer.android.com/studio/publish/app-signing)
explains why public APKs/certificates cannot replace the missing original private key.
Current cleanup/device and P0 continuation evidence is in
[p0/TEST_DEVICE_CONTINUATION_20261020.md](p0/TEST_DEVICE_CONTINUATION_20261020.md).
