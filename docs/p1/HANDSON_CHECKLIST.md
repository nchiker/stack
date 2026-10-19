# H1–H8 session — completed as reported by the user

The user reported “these have all been completed” and all eight entries below are
checked. This records **user-reported completion**, including carried-forward P0
checks. It supersedes the former pending-session status; do not ask the user to
repeat this checklist without a concrete regression or new requested change.

[Completion record](USER_COMPLETION.json) separates this report from automated
runs and agent observations. No new device/API/storage results, raw measurements,
or numeric quality thresholds were supplied with the report; do not invent those
or turn a checked entry into an agent-observed test. Existing automated coverage
and its documented exclusions remain unchanged. Broad OCR/classification parity
and universal platform/storage claims require specific supporting evidence;
recorded generated targets remain provisional until documented agreement.

The original steps below are retained as the scope of the completed report.

**Preparation:** use **Stack Checks** (`org.stackdocs.app.checks`) and synthetic
content for destructive, batch, archive, photo and authentication work. Canonical
**Stack** (`org.stackdocs.app`) must never be cleared, uninstalled, reseeded, restored
over, or used for destructive tests. Existing personal drafts/recovery stay intact.
Generate printable CC0 inputs with
`python3 tools/make_p1_handson_fixtures.py /tmp/stack-p1-handson`:
Invoice (two pages, later-page sentinel), Receipt, Ambiguous, Labeled Details and
an unsupported text input. Use a disposable media collection/profile/device for H4; installing a separate
package alone does not isolate Android's shared photo collection. The user performs
physical capture, secure unlocking and permission/enrollment choices.

- [x] **H1 — document, details, notes, names and search.** In Stack Checks import
  Invoice, Receipt and Ambiguous; also capture a varied non-sensitive multipage printout.
  Zoom/pan/Fit, use Read/Details, navigate pages, rotate/reopen and retain page/zoom
  context. Confirm detected date/amount/issuer values save without Apply and inspect
  their source: edit one amount, remove one detected value, add/edit/copy/remove a
  custom detail and an organization. The ambiguous date should abstain; a generic
  Date role stays unknown. Bare $ uses USD; explicit CAD/AUD overrides it, while a
  bare pound or unmarked amount retains unknown currency.
  Confirm Detected values are visible beside manual details with Add manually. Use
  a synthetic bill containing client/patient/account/invoice identifiers (including
  leading zeros), Staff name and label/next-line amounts; review/correct them. Keep
  a dismissed value dismissed, explicitly reconsider another, and confirm removal
  cannot reappear through reconsideration. Unsupported/conflicting fields abstain.
  Choose a name completion, cancel once (name unchanged), then explicitly save.
  Write a distinctive note, search it (Notes/details context), search the page-2-only
  sentinel, combine organization and stack filters, then clear each. Reprocess an
  edited synthetic page and relaunch: names/filing/notes/corrections/removals remain.
  Judge real-photo usefulness and record abstentions/correction needs without text.
  In Edit stacks, hold a tile and drag it across rows and the scrolling edge;
  confirm Home and filing pickers retain the order after relaunch. Toggle Home
  inline, rename/create a synthetic category and delete that category (documents
  stay in All documents). Use Options → Move earlier/later without dragging.
- [x] **H2 — physical camera and remaining P0 lifecycle edges.** Explicitly deny
  camera access and recover through Retry/Allow. On supported hardware observe actual
  continuous light on/off and labels; check unsupported/unavailable hardware where
  available. Background/turn screen off/rotate/interruption while capturing several
  synthetic pages, resume the draft, remove a mistaken page, save and check order.
  Light must close with the camera. Reuse the approved crop/cleanup/flatten pass;
  repeat it only for a concrete regression, plus previously uncovered real lighting.
- [x] **H3 — batch import, filing, sharing and confirmed deletion.** Select several
  synthetic images/PDFs (limit 50), include an unsupported/denied source, then
  interrupt/cancel/retry through Imports & batch actions. Verify saved items are not
  duplicated and errors identify items. Review different names/details/stack choices,
  including hidden/custom stacks, add filing and explicitly reviewed replacement/
  empty filing. Select in a known order, use duplicate names, share complete PDFs
  and a ZIP and inspect output order/bytes/edits in a receiver. ZIP is a PDF-copy
  bundle, not a library backup. Confirm deletion of only a synthetic subset and
  verify unselected documents remain. Check cancel/partial errors and safe retry.
- [x] **H4 — photo consent, limited access, disable and notifications.** On a device
  or profile with a **disposable media collection**, verify both settings start off.
  Enable future-photo import deliberately; choose full/selected/denied scope, add
  new document and non-document Camera photos, and verify no historical backlog.
  Repeated events/renames/restart must not duplicate; deleted/skipped imports must
  not reappear. Revoke access/disable, add another photo, and verify no new commit.
  Deliberately deny/allow notifications; denial must not block local saving. Review
  burst grouping, generic lock-screen content, exact saved-document routing, stale
  deleted-document taps and lock-gated entry. Android controls scheduling latency.
- [x] **H5 — real system pickers, recipients, destinations and archive providers.**
  Another app requests a PDF: select/cancel and inspect temporary read-only access.
  Exercise incoming single/multiple share, picker cancellation, chooser cancellation,
  save destination cancellation, denied/short/close failures where supported. In a
  disposable library only, make format-3 backup with saved stack order/notes/details/tags/tombstones,
  restore empty and additively, repeat explicitly, and restore a format-1/schema-6
  fixture with empty defaults and a format-2/schema-7 fixture with legacy order. Unsupported versions must reject without mutation.
  Check originals/order/edits/choices and that restore preserves local lock/consent.
  Never create a personal backup or restore/delete the personal library for testing.
- [x] **H6 — actual Android authentication.** On appropriate disposable hardware/
  profile, the user sets up Android enrollment if needed, then explicitly enables
  app lock. Cancel/fail/succeed; background/screen-off/restart and check relock.
  Exercise launcher, incoming share, notification, PDF request, editor and backup/
  restore entry. Review unavailable enrollment, lockout and device-credential
  recovery/enrollment changes, then deliberately authenticate to disable. No data
  loss or accidental disable; private recents/overlays must stay covered. This is
  an access gate; it adds no archive/original encryption and cannot recall copies
  already deliberately delivered to recipients.
- [x] **H7 — accessibility, genuine offline first use and quality agreement.**
  TalkBack/keyboard/focus/checkboxes, accessible zoom/pan alternatives, large text/
  landscape and clear errors across all new flows. Include outstanding P0 OCR
  failure/retry UI and dismissal. On a fresh disposable library with networking
  actually unavailable, perform capture → bundled OCR → reviewed choices/notes/
  filters → export → archive → restore without account/download/Play Services.
  Review varied real captures and agree corpus/quality targets; current generated
  and agent-rated results remain provisional. Earlier scanner approval, five OCR
  reports and four naming/filing reports remain credited for their original scope.
- [x] **H8 — resource-only platform/storage gates.** Execute API 24 and current
  Android (compile/target 36), other shipped ABIs, a real 16 KiB runtime, and actual
  disk-full/physical power-loss cases only with suitable disposable resources.
  Available automated runtime is Pixel 4a/API 33/arm64/4 KiB. SDK caches contain no
  emulator/system images; no new runtime downloads or device-global setting changes
  were performed. Force-stop and injected failures are separate evidence, not
  physical power-loss/disk-full proof. Mark unavailable resources explicitly.

Historical informal follow-up before the completed-session report: user reported new detail information looks good, then explicitly
authorized real cleanup/Google migration. This is limited feedback, not a complete
H1 or other H pass. Actual transfer is verified in GOOGLE_STACK_TRANSFER.json;
the main migrated library is protected again. Five synthetic files plus guide are
on the phone in Download/Stack-Test-Files; Checks was reset for fresh testing.
