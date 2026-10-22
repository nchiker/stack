# Android computer-sync navigation repair — 2026-10-20

The user reported that **Sync with a computer** was confusing and unlike the rest
of Stack. The screen exposed group creation, file exchange, conflict review,
identity enrollment/replacement and revocation together, followed by raw device
IDs and causal status. It gave no clear first action or parent-page navigation.
This is a concrete new-sync usability issue; Android H1–H8 remains completed.

The repaired screen uses the existing Stack toolbar, circular icon action rows,
spacing, typography and primary buttons. The overview shows the next available
action. **Set up a computer** explains the initial computer-side setup and offers
**Copy phone ID** and **Choose setup file**. **Connect over Wi-Fi** reveals identity
steps in order. Once verified, **Sync now** comes before explanatory text so it
stays visible at enlarged text sizes. **Exchange files** has separate Send/Receive
rows. **Computer settings** holds verification and deliberate disconnection;
**Sync details** holds the full IDs, fingerprints, frontiers and receipts.

Toolbar, older-device system Back and API33+ native Back share the same parent-page
navigation. Page/history and the pending export destination survive activity
recreation. The backup screen's sync entry is now a standard action row inside
its scroll area. Existing unrelated UI/icon/version edits remain untouched.

Adoption presents library counts and explicit match/device review, retaining the
complete fixed member list from the reviewed source. It does not ask users to
retype that list. Conflict lists use document names and readable field labels;
the selected value remains complete, with Back paths to the options and change
list. No causal operation IDs or raw cell keys are needed to navigate the list.
Wi-Fi enrollment derives the known group/computer ID from the reviewed two-device
membership; the full independently compared fingerprint still requires explicit
entry. Invalid address/port/fingerprint fields remain open for correction.

The storage, archive, causal sync, transport, certificate validation and signing
implementations are unchanged. Foreground cancellation remains in force. Network
actions disable overlapping file/review operations, expose Cancel on subpages,
and retain confirmed receipt and interrupted-session status on return. Revocation
and identity replacement keep explicit confirmation and fixed-membership limits.

All device commands target only `adb -P 5693 -s 0A121JEC203530`. The user explicitly
authorized installing the fix and necessary tests on this disposable Pixel4a.
Updates use the permanent signer and `install -r`, never uninstall/reset. The
primary phone, private archives, signing keys and backups remain untouched.

Validation and exact delivery hashes are recorded in
[REMAINING_RESULTS.json](REMAINING_RESULTS.json). The focused test uses the actual
native renderer at320dp with1x/2x text and authored state, plus read-only production
Activity navigation. Synthetic screenshots contain only authored values. Existing
library preservation is compared separately; no broader Android acceptance or new
protocol/device coverage is inferred from a view test.
