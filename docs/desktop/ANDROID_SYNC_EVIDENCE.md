# Spare-phone sync engineering — 2026-10-20

Latest2026-10-21: friendly nearby/QR pairing and guarded local reset are described
in [current integration evidence](FRIENDLY_SYNC_RESET_20261020.md). The current
spare delivery is `.artifacts/stack-friendly-sync-phone-20261020`, with the same
permanent signer and preserved canonical library. Reports below remain historical
for their named source/artifacts; earlier statements that discovery is absent or
manual fingerprints are required do not describe the new named setup.


Current continuation: the disposable spare now runs the single original-package
`org.stackdocs.app`, signed with the permanent development key. Recovery and the
old Checks installations are retired. Current signed APKs and exact source inputs
are in `.artifacts/stack-single-app-20261020`; same-key update and retained-library
checks passed. See [permanent signing](../STACK_ANDROID_SIGNING.md) and the
[device record](../p0/TEST_DEVICE_CONTINUATION_20261020.md). The primary remains
untouched. Android H1–H8 acceptance is completed; only new-sync physical
observations belong to the [focused review](M2_REVIEW.md).

The following APKs, namespaces, update restrictions and test reports describe
their historical checkpoints, not the current spare installation.

Actual Android/Rust file exchange, reviewed nonempty adoption and Android Keystore
mTLS LAN exchange now have disposable-device evidence. Foreground network controls,
bounded cancellation, reconnect and five actual process-stop boundaries are
implemented and checked. Normal-app update and final human release acceptance
remain open; the installed app's original signing key is unavailable.

The user supplied a spare Pixel 4a (API33, arm64). Tests explicitly target its
serial through ADB server5693. Canonical `org.stackdocs.app`, the older
`org.stackdocs.app.checks`, existing apps and data were preserved. New namespaces
`org.stackdocs.app.checks.r5` and `.r6` contain only authored synthetic libraries.
There was no uninstall, data reset, private archive import, device unlock/security
change, desktop window or activity-driven UI test.

## Observed checks

| Check | Result | Saved evidence |
| --- | --- | --- |
| Production Java/Rust causal corpus | 10 golden cases +1 rejection pass | `causal.json` |
| Production Java graph projection | Cells and round trip equal Rust; valid causal gap retained;10 malformed partials rejected by both without durable state change | `graph-parity.json` |
| Android → Rust baseline and deltas | Seed PDF, repeated-startup idempotence, concurrent titles, explicit title/choice conflict resolution, exact notes/emoji/newline, receipts, replay pass | `file-exchange-progress.json`, `file-exchange-instrumentation.json` |
| Hostile Android imports | Unsupported version, malformed pending cell, truncated blob and changed blob rejected with sync state preserved | Same file-exchange reports |
| Deletion/replay | Old baseline does not resurrect a deleted document; original PDF hash retained across reopen | Same file-exchange reports |
| Rust → fresh Android baseline | Five documents including per-page scan JPEGs; all7 file descriptors/digests and document IDs/titles equal | `reverse-adoption.json`, `reverse-adoption.log` |
| Real populated migrations | All8 versions1–8 upgrade to9, preserving text, values, removals, draft, memberships, flatten and stack order; sync disabled initially | `migrations.log` |
| Actual LAN mTLS | Android Keystore identity, both fingerprint mismatch refusals, bidirectional offline title/notes edits, unchanged originals and revocation pass | `lan.json`, `lan-instrumentation.json`, `lan-server.log` |
| Android build/lint | Main/instrumentation APKs assemble;0 lint errors/16 warnings; local unit task `NO-SOURCE` | `gradle-tls-r6.log` and preceding build logs |

All named evidence is retained under Git-ignored
`.artifacts/stack-android-20261020/evidence/`. APKs, SHA256 inventory and an exact
Android source snapshot are beside it. This snapshot includes the preexisting
uncommitted Android UI/icon work used by the build; that work is preserved rather
than included in the sync commit. APK qualification is tied to the snapshot, not
inferred from a later Git HEAD. No signing private key is included.

The r5 main APK SHA256 is
`7d2733667211d4798339e8b946a83485fb5a37d1e00423d35e9f3356edb9fd19`;
final r5 instrumentation is
`d67766abfc6befe19fd8fd035915745bc7bf6b422c9e904862e154c4cbd08970`.
The fixed r6 main APK is
`2c7343012939299b89451833e11277e23584e49d870786bae1dbfe2e0a2e7ab1`;
r6 instrumentation is
`0882b1e53e3359cd45f7bcab3fe7f9c64ab7deeee844dd444341f52203dd210a`.
All use the isolated debug signer
`e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311`.

## Repairs and retained failures

Java canonical quoting used optional slash escaping, changing JSON-array identity
keys embedded in cell names. The production canonical writer now matches Rust's
unescaped-slash spelling. Partial graph validation now checks types, ranges,
geometry, enums and exact details before a causal gap can enter the pending inbox.

Actual repeated app startup exposed generation increments for already-pending
suggestions with an old rule. Recovery now leaves that pending generation alone;
the device restart/export idempotence check passes. Distinct title and suggestion
choice conflicts are each explicitly reviewed by the harness.

The first reverse inspector incorrectly required one `source` file for a scan;
the corrected inspector verifies every production snapshot file. The completed
earlier file checks and migrations remain valid; only the missing reverse check
was rerun. This was a harness failure, not a missing scan original.

The first LAN handshake failed in Conscrypt, while Rust saw EOF. The EC key had
only SHA256 authorization. Android documents that TLS generally requires
`DIGEST_NONE` because the TLS stack computes the digest before signing; the
[API reference](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setDigests(java.lang.String...))
and [Android CTS TLS key test](https://android.googlesource.com/platform/cts/+/7e3bf0ef2a5d26c05ba798f48e781383c9393ee2/tests/tests/keystore/src/android/keystore/cts/KeyPairGeneratorTest.java)
support this diagnosis. New keys authorize NONE+SHA256, and identity initialization
rejects an incompatible existing key without replacing it. A new r6 namespace
tested the fix; the old r5 key and pairing were retained. TLS verification,
hostname checks and exact peer pins remain enforced. Failed phone/server logs are
retained beside the passing evidence.

The installed older Checks APK uses a different signer from available debug
keys. It was preserved; these new test namespaces avoid replacing it. A normal
primary-phone update still requires its original signing identity.

## Tooling and limits

Official SDK36/build-tools36.0.0/platform-tools37.0.1 and Gradle9.3.1 were restored
under `/tmp/stack-dev`. The system Java25 compiler module alone did not satisfy
Gradle's full JDK capability check. Checksum-verified Temurin25.0.4.1+1 supplied
`javac`; no system Java installation was replaced. Existing device/migration/file
evidence is reused across the subsequent TLS-only change.

Two read-only verifier roles were requested as `gpt-6-luna`/medium, following the
user's instruction. Runtime model identity is not exposed. The main agent owns
implementation, diagnosis, acceptance decisions and integration. No Rust source
changed in this phone continuation, so the prior93-test desktop acceptance remains
valid for its source. The initial checkpoint above is historical; final continuation evidence follows.
Other Android versions, normal-app installation and physical network/UI/security
interactions remain unobserved.


## Final adoption, foreground controls and recovery continuation

Following checkpoint `891f309`, Android now reviews every local identity before
joining an existing group. Review is bound to both the exact incoming source and
current local snapshot. Distinct documents remain distinct by default; explicit
matches include document/page ownership, stacks, organizations, tags and scans.
Catalog names are reviewed, complete mappings must be injective, and invalid
combined graphs are rejected before metadata commit. Existing decisions remain
causally concurrent for explicit conflict resolution. Originals are cached and
verified before adoption; no old original is replaced.

The Android **Sync** screen now includes public-certificate save/enrollment,
separate fingerprint confirmation, manual-address **Sync now**, cancel, revoke,
explicit replacement-key confirmation and persistent confirmed exchange status.
Leaving the foreground cancels an active connection. Connect is bounded to eight
seconds, socket I/O to45 seconds, with cancellation propagated through bundle work.
No discovery, listener or background synchronization starts on Android. File receipt
frontiers remain distinct from publication. A lost final acknowledgment can follow
an already saved import; reconnect confirms progress. Conflict review is paged at40
and shows the complete selected value before confirmation; adoption is paged at20.
These controls have source/build qualification; activities were not launched for
physical UI, keyboard, accessibility, lock or picker acceptance.

| Final check | Result | Evidence under final artifact `evidence/` |
| --- | --- | --- |
| Pure reviewed adoption |13 cases pass: separate/matched graphs agree with Rust; stale source/local review, incomplete/duplicate/ownership mappings and unresolved organization collisions rejected | `adoption-host/report.json` |
| Real separate adoption | Four stages pass: incomplete mapping, replaced valid source and local edit invalidate review;6 documents/8 originals retain the local PDF and converge with Rust | `adoption-separate/progress.json` |
| Real matched adoption | Three checks pass: safe restored queue tokens, equal unmatched organization names rejected before commit, explicit full mappings preserve identical Android/Rust conflicts | `adoption-device/report.json` |
| LAN recovery | All8 checks pass: bidirectional edits, pins, active-key replacement refusal, blocked-handshake cancel, same-group wrong key, authenticated corrupt bundle, reconnect without duplicate edits, revocation | `network-recovery/report.json` |
| Actual Android process stops | Five boundaries pass: originals_installed/transaction/rows_written roll back and retry; committed/receipt resume committed; every case retains originals, reaches6 documents/8 files, replays idempotently and converges with Rust | `apply-stops/report.json` |
| Current portable compatibility | All3 format chains and21 differential cases/233 currencies pass with exact graph, evidence and original digests | `archive-interop/report.json` |
| Checks build/lint | Main/instrumentation APKs assemble; lint0 errors/17 warnings | `gradle-checks.log` |
| Normal candidate build/lint | Release assembles without Checks namespace; lint0 errors/16 warnings | `gradle-release.log` |

Actual blocked-handshake cancellation completed in649ms after the server observed
257 bytes of ClientHello. Only the separate r6 package was force-stopped. The
initial stop invocation lacked PDFium configuration and failed before substantive
work; the configured run passed. No canonical app was stopped or modified.

Additional diagnosed failures are retained: restored private suggestion queue
incarnations used62-bit values outside the exact53-bit wire range. New tokens use52
bits and startup requeues oversized private work tokens, preserving saved values.
Status now captures committed journal changes before reporting conflicts/frontiers;
a no-change cursor fast path avoids rehashing the full library on each refresh.
Earlier matched runs exposed those issues and the pre-export status mismatch.
The first cancellation timer fired before TLS; the final harness signals cancel
only after the server has observed ClientHello. A final graph validation guard
rejects unresolved cross-row organization collisions before adoption publishes data.
Earlier reports are historical diagnostics, not substituted acceptance results.

The final persistent package is `.artifacts/stack-android-sync-20261020`, with
`SOURCE.json`, an exact167-file `SOURCE.tar.gz`, reports/logs and `SHA256.json`.
The snapshot includes preserved preexisting UI/icon/version edits used by the build;
it is the reproducibility reference rather than a claim that Git HEAD alone
produces the same APK. No private signing key or personal archive is included. The
lower-model package verifier confirms all96 payload hashes and all167 source/tar
matches in `.artifacts/stack-android-sync-20261020-verification.json`. The included
first-checkpoint inventory is a reference to the separate sibling checkpoint;
its23 other payloads are not duplicated in this final bundle. Final package
integrity passes with that explicit historical-reference qualification.

- r6 main APK SHA256: `9b1d890d62b7b5a4b52164f76c17227162dccc42628689ebf5b8adb78bf712fe`.
- r6 instrumentation SHA256: `d1165f3b7f1348d70961c3653a49ae9f3f1ec4d22e2180de9b5b6ba6f7136858`.
- Normal unsigned candidate SHA256: `b2222b2867d6c48e2daef7b11f8ee1ab58133ba1f2956552a8449ede150b3729`.

`Stack-0.8.9-sync-unsigned.apk` has ID `org.stackdocs.app`, code22/0.8.9-dev,
minSdk24/targetSdk36 and debuggable=false. All four existing ABIs are retained.
INTERNET is added for explicit local-network sync; cloud backup stays excluded.
The installed canonical code20/0.8.7-dev public signing certificate SHA256 is
`db7352c8df2d3643ae9dc119ffd73ee26a700808d53efad3cf60a753e620eeab`.
Neither available debug key matches. The unsigned candidate cannot update that app
until signed with its original key. No canonical install/update/uninstall, private
library access, unlock or data reset occurred. Do not uninstall to bypass this gate.

Desktop Rust production source is unchanged, so the93-test acceptance and prepared
desktop delivery remain valid. Android maximum-library timing, other API/device
versions and physical activities are unmeasured; no such acceptance is claimed.
Use the [transfer and sync guide](../STACK_DESKTOP_PHONE_TRANSFER.md) and the
[single final user checklist](../STACK_DESKTOP_PLAN.md#one-consolidated-user-dependent-checklist-for-implementation-closeout).
