# Friendly device setup and local recovery — 2026-10-20/21

The user requested understandable phone/computer sync, friendly device names,
matching-code verification with both nearby selection and QR scanning, and careful
local erase followed by backup restore or resync. This increment extends the
native task pages delivered in `59992cb`; Android H1–H8 remains completed.

## Product flow

Desktop **Sync with phone** remains a footer tool above **Backup & restore**.
**Set up a phone → Connect a phone** opens one named invitation and a QR image.
Phone **Set up a computer** offers **Find my computer** or **Scan computer QR
code**, then a phone name and matching-code comparison. Both screens require
approval. Device verification and library adoption remain separate decisions.
The receiving device reviews its own existing documents against the baseline;
separate identities are the default. Either an unadopted phone or an unadopted
desktop can receive an existing reviewed group containing exactly these devices.

After adoption, desktop **Allow one connection** followed by phone **Sync now**
sends saved changes in both directions. The verified phone's first-sync instruction names **Allow one connection** on
the computer and **Sync now** on the phone, without repeating manual certificate
enrollment. The phone saves the verified computer's address. A fresh invitation plus phone **Computer settings → Find this computer
again** or its QR option refreshes the same verified connection. Details retains
raw IDs, receipts and advanced public-certificate enrollment. The
[shared instructions](../STACK_DESKTOP_PHONE_TRANSFER.md) name the actual controls.

## Trust and lifetime

The new bootstrap is `stack-transport/src/pairing.rs` and Android
`NearbyPairing.java`. Explicit foreground UDP discovery/TCP bootstrap uses45874;
normal pinned exchange remains45873. Discovery echoes a random request nonce and
advertises only name, invitation and port. Invitations last120 seconds and admit
one attempt; no automatic background service is installed.

Canonical commitment bytes have domain `STACKPAIR1/device`, fixed D/P role,
and big-endian u32 length-prefixed invitation/name/replica/raw certificate/group/
peer/raw nonce. Both commitments precede either reveal. Transcript domain is
`STACKPAIR1/transcript`, with the canonical desktop then phone records length
prefixed. SHA-256 of this transcript gives the six-digit comparison. The shared
fixture vector produces transcript
`375ded7c79529df911ab96bb85943d7602d83940413f67cb495f099f70104532`
and code`902 524` on both implementations. Approval is bound to the full current
token; stale and canceled callbacks fail. The QR pins the decoded certificate.

After both user approvals the same socket upgrades to mutually authenticated TLS
with exact exchanged certificate pins and ordinary hostname/chain validation.
Both sides confirm the same transcript and acceptance inside TLS before policy
writes or baseline export. An absolute monotonic watchdog closes unauthenticated
sockets at the invitation deadline, including slow partial frames. Worker cleanup
is bounded and joined. An existing complete local key/certificate pair is reused;
a partial pair fails without overwriting either half. Self-pairing compares decoded
certificate bytes. Resumption permits only the identical active certificate,
replica and complete frozen group. Revoked/different peers cannot replace members.
Names are presentation metadata bound to the verified peer.

Pinned QR dependencies are bundled: Android ZXing core3.5.4 is checked by
`verifyQrDependency`; Rust qrcodegen1.8.0 is locked with its notice. Android camera
frames are transient and never imported into the document library. Actual Rust QR
image decoding by the Android decoder passed; physical camera acquisition of the
desktop screen remains a distinct observation.

## Local erase and connection recovery

Both apps expose **Backup & restore → Reset or rebuild library…**. Full erase
shows three warning/confirmation stages: local data loss, checked recovery copy
and final exact phrase (`ERASE THIS COMPUTER` / `ERASE THIS PHONE`). It warns
against erasing both copies without an independent backup. The separate connection
choice requires `RESET CONNECTION` and keeps documents. Neither operation affects
a remote device; access protection, own transport key and application signer are
retained. Backups outside owned library paths and unknown files are preserved.
This is local deletion, not a forensic secure-erasure claim.

The final callback schedules a private intent, drains/closes the app, and completes
only during cold initialization before ordinary database work. Desktop holds the
same persistent writer.lock inode. The desktop review checks the same library ID
and revision again before scheduling. Both implementations quarantine an explicit
allowlist with a durable journal (`requested`, `moving`, `preparing`, `committed`,
separate `rolling_back`). The owned-path inventory is captured at cold transition
and immutable during rollback. Symlinks/special files and linked ancestors are
refused. Atomic intent publication and directory synchronization preserve recovery
across interrupted moves, initialization, commit publication and rollback. An
ambiguous durable commit preserves quarantine for reconciliation on restart.

Full erase removes database/sidecars, originals, edits, OCR, sync state and local
recovery data; Android also removes unfinished scans and disables automatic photo
import durably. Unsaved desktop drafts are discarded on the explicit close.
Connection reset clears membership/history/enrollment and allocates fresh sync
identity while preserving the full portable graph and originals. Because model2
membership is frozen, rebuilding an erased device requires connection-only reset
on the populated peer too, followed by a fresh reviewed group. Recovery offers
backup restore or setup. Additive backup import retains its existing semantics.

## Verification and boundaries

Main owns design, fixes and acceptance. Routine builds/tests were assigned to
roles requested as `gpt-6-luna`/medium; a read-only focused reviewer was requested
as `gpt-6.1-sol`/xhigh. Runtime identities beyond requested settings are not
independently exposed. The reviewer found and helped close identity reuse,
certificate comparison, absolute deadline and reset crash-publication/rollback
issues; final focused review approved with no remaining blocking finding.

Focused engineering evidence under the current delivery's evidence directory:

- Rust: four reset groups, four pairing groups, native reset confirmation,
  guided task-page and actual QR encoder checks passed. The source-matched final full desktop
  suite passes105 tests/0 failed/0 ignored, all ten unchanged timing/memory limits
  and six private portal requests. Fmt/check/strict Clippy/build/software checks pass.
- Android: offline main/instrumentation/lint build passes,0 errors/18 warnings.
  Eight isolated reset groups cover full cold recovery, crash/rollback boundaries,
  exact graph/original retention for connection reset, independent backups and
  linked-ancestor refusal. One native warning/cancel group never invokes the
  real final destructive callback. Seven native sync groups cover320dp and1x/2x,
  named/QR actions, address refresh, Back/state, cancel and revocation.
- Authored real Android/Rust nearby cases: desktop-first setup, canceled library
  review without causal mutation, exact identity resume and adoption; phone-first
  group and desktop review; ordinary bidirectional sync on both pairs. Each pair
  converged to2 documents/4 pages/2 originals. Host-only approval with phone cancel
  refused enrollment on both sides. Wrong QR pin was rejected before the phone's
  comparison or policy write; no host approval was provided.
- The spare's retained library stays175 documents/278 pages/18 stacks/248
  memberships with all five aggregate preservation hashes equal to the immutable
  baseline. Only exact serial0A121JEC203530 via ADB server5693 is used. Main/test
  packages update in place with permanent signer
  `e873f2fd869005e59828db6d4f8f31d1b05a37988818a7dd94adc1ae1b07e311`.
  No real-library reset, uninstall, primary-phone operation or private archive test.

Failures are retained rather than relabeled: initial Rust integration/Clippy and
Android compile errors; Gradle/local socket sandbox refusals followed by authorized
retries; Android warning-button case mismatch in the test lookup; initial paired
interop coordinator missing the phone's45-second test approval window; C wrapper
JSON escaping and D host-wait timing mistakes; desktop final formatting and a test
still targeting the advanced identity control on its former page. The controller
failure poisoned its shared mutex, causing five dependent failures. Corrected
focused/full follow-ups are separate logs. The final UI copy review also removed
a legacy manual-enrollment instruction after named verification; the final phone
UI check asserts the actual next step. No failed attempt is counted as a pass.

Android snapshots include preserved unrelated UI/icon/version build inputs, which
remain outside this change's commit. Exact source manifests and archives qualify
APKs; HEAD alone is insufficient. Primary Android acceptance stays closed. Remaining
physical observations are the focused native interaction review, camera QR
acquisition, chooser/focus/screen-reader consumption and compositor behavior; see
[M2 review](M2_REVIEW.md). No visible desktop app is opened by agent checks.
