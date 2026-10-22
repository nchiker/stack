# Actual phone-to-desktop sync and simpler controls — 2026-10-21

The user reported **Couldn't finish: Row too big to fit into CursorWindow
requiredPos=0, totalRows=1**, said the setup still had too many choices, and
explicitly asked the agent to use both interfaces to copy the actual phone files
to the computer. This supersedes the earlier headless/synthetic-only restriction
for operating Stack and this transfer. Other applications/windows, primary phone,
private archives, access protection and existing pairing remain untouched.

## Reproduced defects and changes

Opening the installed phone app through **Library options → Backup & restore →
Sync with a computer** reproduced the exact dialog. `SyncStore` was selecting its
complete serialized history/projection/baseline into a CursorWindow. These are
single JSON records whose size grows with a populated library. The fix reads those
scalar columns with `DatabaseUtils.stringForQuery` and reads the journal cursor
separately. The values stay complete; no rows are erased, JSON truncated, cursor
limit raised or schema/key/group reset performed. Android's
[implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/database/DatabaseUtils.java)
uses a compiled scalar statement for that method, avoiding the cursor row path.

An isolated authored regression creates64 documents with valid Unicode notes and
checks that **state, projection and baseline each exceed2MiB**. Setup, status,
reload, pending-state save, later change capture and apply all pass with exact notes
and all64 documents retained. This covers the aggregate-record boundary that the
earlier two-document transport fixtures missed.

Desktop **Sync with phone** now shows **Ready to sync** directly for a verified
phone. Phone **Sync with a computer** shows **Sync now** directly. File exchange,
IDs, certificates and connection settings are behind **More options**. New setup
starts directly with desktop **Connect a phone**, then phone **Find my computer**
or QR; the phone's name defaults to its device model. Matching-code approvals and
separate library adoption consent remain intact. Active phone sync shows progress
and Cancel only. Saved Back/state and all advanced routes remain available.

The first actual UI transfer also exposed a real lifecycle failure: after the
phone's60-second display timeout, `SyncActivity.onStop` canceled its socket. The
phone showed **Socket is closed** and the desktop reported peer EOF. The retained
PowerManager trace records timeout followed by the socket failure; this was not a
successful transfer. No documents were published on desktop on that attempt.
Stack now sets `FLAG_KEEP_SCREEN_ON` only during active foreground sync/pairing,
clears it when finished/leaving, and retains cancellation when the user leaves the
screen. No Android timeout, lock policy or persistent system setting is changed.

The subsequent transfer delivered all175 documents to the computer, but the phone's
45-second response read timed out while the authenticated peer completed populated
library work. Desktop then saw a broken pipe sending its reply. The fix allows a
bounded180-second read wait **after** certificate/pin and group checks; connect,
handshake, initial hello and listener limits stay unchanged. Writes remain bounded
as before. Both platforms use the same processing window. Cancellation still
closes the socket immediately; a blocked authenticated-read regression verifies
completion within two seconds. No performance acceptance budget is relaxed, and
no heartbeat/protocol-format change or weaker authentication is introduced.

## Interface execution and evidence

The real desktop is the existing normal XDG library; it initially held0 documents
and7 stacks. The phone is only Pixel4a/sunfish/API33 serial0A121JEC203530 on ADB
server5693. The current user-established phone baseline is **175 documents,
278 pages,25 stacks,248 memberships**. This differs from the earlier18-stack
checkpoint because the user completed a new reviewed setup before reporting this
failure. Updates/checks preserve the current baseline and all five aggregate hashes.
No reset, re-enrollment, uninstall or data-clearing operation is used.

Main operates actual native desktop buttons through their exported AT-SPI click
actions and actual phone controls with UI taps selected from the current Android
view hierarchy. No CLI/transport API substitutes for those sync actions. AT-SPI
activation briefly enables the accessibility bus property; its prior values are
recorded for restoration. Native exported hierarchy consumption and button routing
are observed; this does not imply a human screen-reader or every compositor case
was tested. Screenshots/tree evidence are limited to sync controls; document
contents and private original files are not bundled into regression artifacts.

Build/test roles requested as gpt-6-luna/medium return exact outcomes; runtime model
identity is not independently exposed. Main owns fixes, scope and actual UI actions.
The changed desktop UI passes fmt/check/strict all-target Clippy/build and its two
focused native controller regressions. Locked/offline metadata/build and package
checks pass. The earlier105-test/ten-limit/six-portal acceptance remains qualified
to its source; this UI-only increment does not relabel it a new full-suite run.

Android offline main/test/lint builds pass. The new large-state regression passes.
Seven final native UI groups pass at320dp and1x/2x, covering simple main controls,
advanced actions, cancellation, Back/save-state and real populated SyncActivity.
The initial seventh check used a fixed500ms readiness delay and failed while the
populated library was loading; the corrected bounded readiness wait passes. That
failed raw report and stack trace remain retained, never counted as seven passes.
The subsequent active-window keep-awake change is checked in the real transfer.

Current guide: [two-button sync and recovery](../STACK_DESKTOP_PHONE_TRANSFER.md).
Current delivery, final APK/source hashes and real transfer outcome are recorded in
`REMAINING_RESULTS.json` and the source-matched artifact receipts. Raw selected
check and UI evidence is retained under `.artifacts/stack-simple-sync-20261021-evidence`.
