# Automatic desktop and Android synchronization

## Follow-up: responsive browsing and normal connection recovery

A real spare-library launch exposed two startup bottlenecks after the initial
verification below. Status queries replayed a large pending journal before Home
could load, and desktop setup retry could occupy the writer while the phone
waited for ordinary TLS. Browsing now uses a separate bounded read executor after
initialization. Connection status avoids journal capture; capture checks
cancellation and preserves every causal event while updating independent queued
suggestion rows without rebuilding the full graph for each row. Desktop allows
ordinary connections first and yields an incomplete invitation to an arriving
connection, retaining the normal TLS and pin checks.

The actual canonical spare app displayed its populated library with no loading
bar after the update. Offline Android debug, release and lint checks pass; the
release APK uses the existing pinned development signer. The final desktop change
passes formatting, strict all-target Clippy and its build. The extended large-state
checks pass both groups; 256 distinct causal queue events replayed in 3452ms.
The latest separate fixture UI run returned two passes but no group completion;
the automatic-sync run passed four cases then failed the occupied-writer fixture
assertion in `queuedLock`. Those runs are incomplete/failed, not full passes.
The earlier five/three-group passes below describe their earlier source only.

Installable deliveries are in `.artifacts/stack-delivery-20261021/`:
`android/Stack-release.apk` and
`flatpak-final/Stack-0.1.0-rc1-x86_64.flatpak`. See `START-HERE.txt` and
`RELEASE.json` for exact hashes, installation and current real-transfer status.


The interaction is **Settings → Sync → Connect phone**, then **Approve connection**
on the phone. Documents, saved edits and organization subsequently synchronize in
both directions. The page contains the device, connection status, last sync,
**Sync now** and **Disconnect**. Obsolete adoption, file-exchange, certificate,
direction and session setup routes have been removed from both application UIs.
The existing causal engine, merge, exchange framing and pinned TLS are reused.

## Behavior

Initial connection matches shared backup identities, combines equal stack/tag
names and retains independent documents. Automatic exchange uses the existing
single repository writer. Idle desktop listening does not occupy that writer.
Local phone changes request a debounced exchange; the foreground app retries
about every 30 seconds. Persisted Android network jobs provide background attempts
subject to Android's battery policy. Keeping the desktop app running is required.
A changed network address is rediscovered and checked against the saved peer pin.
No repeated setup is needed for an ordinary interruption or restart.
An interrupted first transfer retains its approved identity and address. Pending
setup retries on desktop after a grace period and resumes on the phone using the
saved certificate pin, without another approval. Leaving the phone's setup screen
cancels foreground setup; reopening Stack resumes it. App lock cancels the active
connection and prevents queued work from starting while locked.

Genuine edit conflicts show readable phone and desktop versions. A title decision
also resolves its corresponding internal naming record, so one conflicting title
does not produce two prompts. Nonconflicting changes continue automatically.
Incoming background changes retain the open desktop document and exact unsaved
drafts. Reads requested during exchange wait for the owned writer automatically.
Disconnect cancels active work and revokes future connections while retaining
saved documents; a fresh explicit connection requires phone approval again.

Old generated starter stack duplicates are repaired on application startup.
Cleanup recognizes the old generated UUIDv5 starter category and exact reserved label,
including restored backups without sync history,
keeps the populated generated category and its memberships, removes its unused
starter duplicate, and restores the ordinary category name. It does not infer
identity from a title or rename arbitrary user-created stacks. Ambiguous or used
starter categories are retained rather than discarding organization.

## Verification

The earlier full Rust workspace run passed106 tests, with five pre-existing
optional tests skipped. Subsequent focused runs cover the delivered changes:
15 real core synchronization integrations pass, including shared-backup matching,
independent populated-library union, automatic nonconflicting edits, one-choice
conflict resolution, and preservation during old duplicate-category cleanup.
The31 enabled native controller/unit tests pass, including the two simple Sync
screens, actual widget actions and exact draft retention across automatic refresh.
Rust formatting, strict workspace Clippy and the workspace build pass for the
delivered source. The latest desktop test run passed29 cases; two cases failed
with display variables set/concurrent execution and passed their targeted,
serialized headless reruns. Three optional desktop cases were skipped. Toolkit
dependency warnings are separate from application diagnostics.

The separate `org.stackdocs.app.checks.autosync` app on the authorized spare phone
passes five production-engine regression groups: initial merge, bidirectional
edits/organization and real conflicting edits, safe legacy category cleanup,
recovery of approved setup with pin validation, and a lock while sync is queued.
Three UI groups cover320dp width at normal and doubled font size and actual
SyncActivity stop cancellation before destruction. The
large-state regression covers64 authored documents with aggregate sync JSON over
2MiB and exact Unicode notes, avoiding Android CursorWindow truncation.
The canonical app and instrumentation APKs are built with the existing pinned
signer; signing credentials are neither generated nor included in artifacts.

The actual native desktop app was exercised on an isolated X11 display with an
authored library. Settings, Sync and Connect phone were clicked; the actual spare
phone test app displayed **Approve connection**. Approving once combined the two
independent documents and seven starter stacks without an adoption screen. Both
interfaces reached **Up to date**. A title saved in the real desktop editor
appeared in the phone library automatically. A title saved with the phone's real
Rename dialog appeared in desktop automatically. Restarting desktop reconnected
without another approval. The desktop editor remained open during later exchange.
No screen reader or global accessibility setting was enabled for this exercise.
The primary phone, private archives and unrelated desktop windows were untouched.

## Deliveries and evidence

The final desktop delivery is
`.artifacts/stack-automatic-sync-resume-20261021/desktop/stack`. Run it from the Stack root:

```sh
./.artifacts/stack-automatic-sync-resume-20261021/desktop/stack
```

It uses the normal persistent library and packaged pinned PDF/OCR runtimes.
Android APKs are in `.artifacts/stack-automatic-sync-resume-20261021/apk/` and the separate
checks APKs are in its `checks/` directory. The desktop receipt verifies952
payload hashes and227 current and archived source hashes. Exact Android inputs
are recorded separately in `source/`. Exact source and payload hashes, final
logs, native captures and spare-library preservation results are retained in the
ignored artifact directories. These are development deliveries; the earlier
platform/release acceptance qualifications remain separate.

The resumed work delegated routine validation to two workers requested as
gpt-6-luna/medium and a focused static review to one worker requested as
gpt-6.1-sol/high. Actual runtime model identity is not independently exposed.
Review found and closed interrupted setup recovery, foreground cancellation and
queued-work lock races. Earlier failed fixture compile/lint and environment test
logs are retained alongside their successful followups.

The canonical app on the authorized spare was updated using the permanent signer
and `adb install -r`, with no reset, restore or re-enrollment. The fresh baseline
was164 documents,257 pages,23 stacks and240 memberships. Startup removed the seven
unused starter duplicates and renamed their retained counterparts, leaving16
stacks and zero legacy generated labels. Device-local read-only checks prove
exact equality of all eight protected tables (documents, pages, memberships,
details, tags, tag links, editor drafts and saved choices), including every row,
plus the original/draft file hash. Personal values, filenames, databases and WALs
were not copied off the phone. This current baseline supersedes historical counts
only for this update; no claim is made that the old175-document fixture still
describes the current library. Accessibility remains disabled; no primary phone
was accessed. Aggregate evidence is in the delivery's `preservation/` directory.

## Final follow-up status

Work stopped at the user's quota limit. Commit `54f1677` contains the browsing,
journal and desktop connection repairs. The actual phone reached authenticated
export preparation, but no completed transfer or receipt was observed; desktop
remained at zero documents. The signed release APK was built and verified but
was not installed. The canonical spare has the debug browsing repair. These final
artifacts are unverified for real synchronization and must not be called stable.
Resume with the exact source snapshots and retained peer, checking pending journal
replay/export before any new builds or broad tests. Preserve all phone data and
pins; do not reset, unpair or restore. See the current delivery receipt for hashes.
