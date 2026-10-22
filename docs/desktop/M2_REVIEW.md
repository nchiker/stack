# Desktop and new-sync final interaction review

Engineering and automated checks run headlessly. This is the final user review;
the agent does not open these windows. The authoritative closeout checkbox is in
the [project plan](../STACK_DESKTOP_PLAN.md#one-consolidated-user-dependent-checklist-for-implementation-closeout).

The prepared review directory is
`/home/charlie.day/Projects/Stack/.artifacts/stack-friendly-sync-review-20261020`.
Its examples are authored, with disposable libraries, isolated XDG data/config/
cache/state and an `exports` folder. Launchers use the frozen verified package
`.artifacts/stack-friendly-sync-20261020`, including its PDF/OCR runtime, and the user's
COSMIC theme by default. They open neither the normal library nor a private archive.
Preparation opens no windows and starts no network connections. To recreate
the fixtures, from the Stack project root use a fresh output name:

```sh
python3 tools/prepare_desktop_sync_review.py --output /tmp/stack-desktop-review-NEW
```

Start the small review library when ready:

```sh
.artifacts/stack-friendly-sync-review-20261020/run
```

Run commands from the Stack project root. Close normally before reopening
that same library. It retains saved changes. Unsaved editor drafts last only for
the current process. This debug engineering delivery uses installed COSMIC/system fonts.

Begin with the implemented navigation described in the [interface review](M2_INTERFACE_REVIEW.md).
Earlier findings there are source-specific history; observe the current behavior.
The supplied HTML mockup is the layout reference. The agent has also performed a
native screenshot comparison with readable sample PDFs at 1080×860; see the
current interface review for that evidence and remaining platform limits. The sidebar contains library
destinations, with Sync with phone above Backup & restore at its bottom. Back stays at the upper left;
Search and Add documents appear in the library; the document workspace uses that
space for its page/export toolbar. Back is hidden at the library root. At narrow sizes use the
COSMIC navigation toggle to reopen the sidebar.

Walk through the workflow in this order:

1. From All documents, choose Manage stacks in the left sidebar. Scroll down,
   then use Back in the window header. Confirm you return to the same list/search.
   Open Manage stacks again and select a stack on the left; the document list should
   appear. Check that Back, Backup & restore and Add documents retain their positions.
   Try Grid/List and reopen to check the saved layout choice. Cards use real first-page thumbnails with file icons while loading or on error.
   Check names, dates and stack chips beneath the thumbnails.
   Browse the five initial documents and stacks. Search `laterpagesentinel` and
   confirm the hit opens the later PDF page. Check preview zoom and page navigation.
   Use Manage stacks to create, rename, recolor, hide/show and reorder a category.
2. Import `sources/two-pages.pdf`, `oriented.jpg`, `corners.png` and `corners.webp`
   from the review directory using Add documents and the actual file chooser. Check each saved outcome.
   Open another chooser and Cancel; the document count should stay the same.
3. Open a document. Check the preview beside Document information; at narrow sizes
   use Document information/Show preview; information and editing retain a smaller
   page above the controls. Choose Edit document and try Information,
   Details and Pages. Confirm an unfinished title survives section/preview changes
   and an unsaved-change notice appears. Done editing leaves the draft available;
   it does not save it. Save a title, multiline notes, filing and a typed detail.
   Page rotation/reordering/removal are under Pages; document deletion is at the
   end of Information.
   Try an invalid value,
   correct it, save, close the window and reopen using the same launcher. Check that
   exact values and page order persist. Try empty filing as an intentional choice.
   Switch between two individual typed details with unfinished values. Try closing
   with a dirty draft: Keep editing must preserve it, Discard must require the
   deliberate quit choice. Use only this disposable library for that exercise.
4. Export an unchanged imported PDF to `exports/original.pdf`; its original text,
   page count and appearance should remain. Reorder, rotate or remove a page, then
   export to `exports/edited.pdf`. Inspect the saved order/appearance. Rendered PDFs
   have no selectable text layer. Choose a new filename for every output; existing
   destinations are preserved.
5. Use Backup & restore → Export backup to export the portable library to `exports/review.stackbackup`. Use Backup & restore → Import backup for that archive,
   inspect the additive review, and Cancel first. Then select it again and commit:
   the archive adds document copies. Check progress, outcome messages and that the
   interface remains usable while work runs. Remove a test document through its
   confirmation control, and check that it disappears from search.
6. Exercise Tab, Shift-Tab, Enter/Space, Ctrl+F and Escape. Ctrl+F returns to and
   focuses search; Escape first closes an open Backup & restore menu, then cancels an editor confirmation
   before leaving the editor.
   Check focus visibility, labels, text entry, wrapping, scrolling and window controls.
7. Reopen in dark/narrow/enlarged states with the commands below. The same `--text-scale`
   option now scales the entire native shell. Look for clipped controls, tiny preview
   space, blank notes or frame stalls. Report the action and window settings if any
   appear. Review the second library's Previous/Next results controls as well.

```sh
.artifacts/stack-friendly-sync-review-20261020/run --theme dark --size 680x680 --text-scale 1
.artifacts/stack-friendly-sync-review-20261020/run --theme light --size 1080x780 --text-scale 2
.artifacts/stack-friendly-sync-review-20261020/run --theme dark --size 680x680 --text-scale 2
.artifacts/stack-friendly-sync-review-20261020/run-many --theme light --size 1080x780 --text-scale 1
```

Known platform qualifications: scoped native patches now publish the actual
widget hierarchy and route targeted activation; wrapped rows also publish their
children. Headless tests exercise the same tree builder and action path. Actual
AT-SPI/screen-reader consumption is still unobserved. The chooser now exports a
real owned native parent; actual placement and focus return remain user checks. Earlier live compositor frame stalls and the historical M0/M2.1
shutdown watch remain recorded; passing offscreen draws does not prove those
platform issues resolved. No live compositor or chooser was exercised during this
headless closeout. Any observed product failure is follow-up engineering work,
not a passed review item.

## Sync exchange and paired local connection

These are physical observations of implemented controls; existing automated
adoption/conflict/file/LAN/recovery passes are retained. `review.json` records
replica IDs, exact expected notes and the package receipt hash. Preparation leaves
adoption unapplied and pairing identities uninitialized. No credentials are bundled.

1. Open `run-adoption` in the prepared directory, choose **Sync with phone → Set up a phone**, then
   **Use setup files instead → Open phone setup file…** and select `adoption-baseline.stackexchange`.
   Inspect every source/local identity section and catalog choice. The local PDF
   and the source's two-page PDF were deliberately made from the same authored
   fixture; this is the one known-ancestry example for explicit document/page
   matching. Defaults keep them separate. First leave the review and edit the local
   PDF; attempting the old review must require a fresh review. Then review again
   and deliberately choose separate or explicit matches before adoption. Recreate
   a fresh fixture output to review the other choice.
   Use `run-adoption-source` for the corresponding sending replica.
2. Open `run-peer-a` and `run-peer-b`, each once. Both have the same group and
   21 documents with concurrent titles plus one long multiline notes conflict:
   86 alternatives across 43 cells, including retained title decisions, requiring
   three conflict pages. Inspect the complete values,
   Unicode/newlines and paging before choosing a resolution. Keep an unfinished
   document title, visit Sync with phone → Exchange files, receive a peer file and return to the draft;
   it must remain exact and require review against changed saved data. Export to
   a fresh filename under `exports`, import on the other replica, then return an
   exchange for receipts. File publication alone must not imply peer acknowledgment.
3. For the advanced desktop-to-desktop path open **Sync details** and the public
   identity controls. Create each identity. Compare the displayed full
   fingerprints and enroll the other fixture replica's public DER file, using its
   UUID from `review.json`. First try a wrong fingerprint and check refusal, then
   use the compared fingerprint. For these two desktop windows use loopback
   `127.0.0.1:45873` (or another unused port). Choose **Allow one connection** on A,
   cancel while waiting, retry and on B open **Sync details**, enter A's address in the advanced
   other-desktop control and choose **Sync now**. Review saved receipts
   and conflicts after completion/reopen. Finally use **Sync details → Revoke paired peer**, confirm and verify another
   connection is refused. Revocation is the last step because membership is fixed.

For the spare-phone portion use only the current original-package Stack on the
disposable Pixel4a, serial `0A121JEC203530`. The current same-key update retains its
synthetic library. Open **run-phone** for a fresh empty desktop library and follow
[the named/QR setup guide](../STACK_DESKTOP_PHONE_TRANSFER.md#connect-once-using-names-and-a-matching-code).
Use **Set up a phone → Connect a phone** on desktop, then **Find my computer** or
**Scan computer QR code** on the phone. Compare the digits on both screens, cancel
first, then retry and approve only matching codes. Observe camera acquisition and
permission/cancel fallback with the QR shown on the computer. Review the libraries
separately before adopting. Then choose **Allow one connection** here and **Sync
now** on the phone. Observe next-step instructions, saved receipts, foreground
cancel/retry, lock/relock and rotation during Sync. The phone uses the computer's
reachable LAN address. Do not import the prepared desktop-to-desktop baseline on
the phone; it names the two fixture desktop replicas.

Check the phone's current enrollment before approving a new connection. Preserve
an existing verified group and use its corresponding desktop; a different phone
or computer cannot replace a frozen member. A changed address can be refreshed by
a new desktop invitation and phone **Computer settings → Find this computer
again**, with another matching-code check.

## Local reset and recovery

Use **run-reset** for the destructive desktop review. This is a separate authored
five-document library. First export a checked backup to the review `exports` folder.
Choose **Backup & restore → Reset or rebuild library…**. Confirm the counts and
local-only wording. Walk through all three erase warnings, check that incomplete
or different phrases cannot enable the final action, then **Keep my library**.
Reopen and confirm the data remains. If deliberately testing erase, type
`ERASE THIS COMPUTER`, confirm, then reopen **run-reset**. The recovery screen should
offer **Import backup…** and **Set up a phone**. Import the checked backup and
observe the recovered documents. Connection-only reset has its own warning and
`RESET CONNECTION` phrase and should retain saved documents.

On the retained spare phone, inspect the corresponding warnings and cancel before
the final action. **Do not erase or reset its retained library for this review.**
The Android destructive engine is already exercised inside isolated authored
fixture contexts. Each device requires local confirmation; no remote wipe exists.
Never erase both copies without an independent checked backup. To rebuild from
a populated peer after an erase, reset only that peer's connection while keeping
its documents, then pair and review a fresh two-device group. Those steps must be
an explicit user choice on each device.

Android H1–H8 acceptance remains completed. Primary-phone operations, a personal
library transfer and broader Android platform/performance measurements are outside
this focused review. Report a failure with its launcher, action, window settings
and observed result; an unobserved physical interaction is not an implementation
defect or a passed check.
