# Stack tile editor and saved category order

**Status update:** [H1–H8 completion is now user-reported](p1/USER_COMPLETION.json).
The pending-session wording below records delivery-time status, superseded by this
report. No known editor implementation or unresolved automated repair remains.


User requested a tile-based Edit stacks screen and drag ordering after the real
Google Stack migration. The reference observation was its open accessibility
hierarchy (category tile grid); no private screen capture or proprietary code was
used. The implementation uses Stack's existing badges, icons, toolbar and name
editor. Delivered **0.8.6-dev/code19/schema8/archive3**, implementation `030c7f2` +
`cfa2fea`. [Actual results](p1/STACK_EDITOR.json), [native device checks](p1/stack-editor-device.txt)
and [process-stop evidence](p1/stack-editor-process-recovery.txt) record the final
consolidated verification and preserved canonical delivery.

Home → Your Stacks → Edit and the library menu open the same editor. Hold a tile
to drag it to a highlighted position; keep it near the scrolling edge to reach
other rows. Drop commits the complete category order in one SQLite transaction.
Dropping outside the editor, leaving the screen or a failed transaction keeps the
previous order. Done leaves the screen; completed actions are already saved.
Options → Move earlier/later supplies the keyboard/TalkBack alternative. Home's
visible subset and filing selectors use the same repository order. Hidden stacks
keep their place when shown again. New categories append; deletion compacts order.

Each tile has Home visibility and Options (rename/move/delete); New stack reuses
name validation. Names wrap instead of truncating. At 320dp there are two columns;
at 2× text there is one column; wider screens gain columns. Controls retain 48dp
minimum touch targets. Category deletion is confirmed and leaves documents in All
documents. This screen changes categories, not document page order.

A drag compares the entire catalog (IDs, order, names, colors and visibility) at
commit. Duplicate/missing IDs and stale drags reject; stale rename/delete actions
also reject. Rename invalidates outstanding naming/filing worker claims and Apply
actions. Reordering alone does not invalidate OCR/metadata/classification work or
change any document names, filing, details, decisions, notes, pages or originals.
No text/URI is put in ClipData, and no global drag flag is used. The editor uses the
common app-lock gate and makes no policy/consent changes.

Schema8 adds sort_order to schemas1–7 and fills the historical rowid order,
including hidden/custom categories. No tables or documents are replaced. Format3
stores contiguous integer category positions and preserves all format2 P1 values.
Formats1/schema6 and2/schema7 validate against their original strict allowlists,
then adopt their stack array order. An existing or manually rearranged destination
keeps its own order; missing categories append in source order. Reviewed restore
rejects a concurrent category reorder. Unsupported versions and malformed ordering
reject the whole input. See [archive contract](STACK_ARCHIVE_FORMAT.md).

Authored checks exercise real native long-press/drag/drop, cancellation, edge
scrolling, accessible menu action, visibility and recreation, transactional failure,
stale actions, old/future archives, every legacy migration, and synthetic small/
large-text/landscape captures. Generated checks and agent visual judgment remain
separate from the [single pending H1–H8 session](p1/HANDSON_CHECKLIST.md). No hands-on
request is needed tonight and no broad P0/P1 acceptance is implied.

Android guidance consulted: [native drag/drop](https://developer.android.com/develop/ui/views/touch-and-input/drag-drop/view),
[accessible controls](https://developer.android.com/guide/topics/ui/accessibility/apps),
and [AOSP input shell command](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/input/InputShellCommand.java)
for the synthetic native gesture harness. No new platform dependency or download.


46 affected device groups pass: editor14 (including seven legacy migrations),
archive8, P1core10, Google-format2 fixture1, naming/filing7, navigation1 and P1UI5.
One additional actual process-stop/relaunch case passes (prepare/resume phases),
plus seven host historical-SQL migrations and eight converter tests. Final offline
normal debug/test/unsigned release/lint and APK audits pass; lint0 errors/15 warnings.
These are scoped generated regressions and actual runtime results; no quality
rubric, frozen evaluation or previous human acceptance was relabeled.

Agent reviewed six final synthetic top/bottom captures at320dp1×/2× text and640×320
landscape. Unchanged OCR/metadata notifications now leave held tiles intact; the
native regression sends a notification during DOWN, checks drag initiation, then
moves/drops and verifies saved order. Native edge scrolling is observed through
nonzero scroll position. Long labels retain natural wrapping heights. No personal
capture was taken and the temporary reference hierarchy was removed.

The current library before/after update is **144 documents/231 pages/17 stacks/
216 memberships**. These fresh observations differ from the historical transfer;
do not recreate historical counts. All five aggregate hashes match (metadata/
decisions, P1 notes/details/relations/drafts, OCR, originals/scan draft/recovery,
and normalized category order). All811 prior details,20 tags/23 tag relations and
231 OCR row digests match, with zero additions during delivery. Current231 pages
reported succeeded; this is processing state, not quality acceptance. Original
signer verified, canonical built without stackChecks, installed using install-r,
installed APK hash matched, temporary canonical test APK removed. Stack's activity
was resumed, phone left asleep without wake/unlock. Only Checks was reset for fresh
synthetic testing; Download/Stack-Test-Files remains available. All new opt-ins
remain off. The only pending work is the linked H1–H8 human/resource session.
