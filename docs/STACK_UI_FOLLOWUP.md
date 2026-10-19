# Informal feedback follow-up — 2026-10-16

User reports: Home navigation labels need centering, selection/filter controls take
too much space, add/share/more icons are too small, rename dialog spacing is uneven,
document information needs less scrolling, and Settings needs consistent styling
and shorter wording. These are informal observations, not new H1–H8 passes.

UI refinement `d6102e9`, badge correction `3272309`, inline/reconsider fix `3fc5907`, labeled fields `6d788a6`, **0.8.3-dev/code 16**, schema 7/archive 2 unchanged:

- Explicitly scale shared icon buttons inside 48 dp touch targets; enlarge the add glyph.
- Match the filter and library-settings circles at 32 dp, inside 48 dp touch targets.
- Center Home/All documents labels. Move Filters into the search bar, move selection
  entry into Library options, and show selection actions only for a nonempty selection.
- Show actual saved and suggested detail values together at the top of Details,
  clearly mark Suggested, and keep Add manually visible. Review opens evidence and
  Apply/Edit/Dismiss; saved fields expose Edit/Copy/Remove. Notes and stacks follow.
- Previously dismissed suggestions remain dismissed until explicit reconsideration;
  ordinary/stale Apply cannot reverse them. Removed fields stay removed.
- Pad rename input consistently and put optional name completions behind one action.
- Reuse the backup screen's circular action rows for Settings, with short on/off
  summaries and explanations only when opening the setting/help. Consent stays explicit.

## Original Google Stack migration

The user authorized a private original-app backup for real migration preparation.
It succeeded; all 145 PDFs/232 pages and 232 referenced page originals are present.
[Migration evidence and next action](STACK_GOOGLE_STACK_MIGRATION.md) documents
17 category stacks, 218 active memberships, source types/notes/exclusions and exact
limitations. The prepared private archive is **not imported** into either library.

## Details coverage follow-up

The user-selected record in original Google Stack has six visible saved details;
all six fields/values match the authorized migration backup. The accessibility hierarchy was read privately; only generic field names and
aggregate comparison results were emitted. No personal values or screenshots
were logged. PDF
import does not carry this metadata. The prepared migration preserves it as exact
text/source provenance, independently of new local proposals.

Read-only diagnosis in Stack found complete OCR, a previously dismissed total and
a valid labeled date inside a longer header that rule 2 missed. Rule 3 adds inline
labeled dates without relaxing ambiguous-calendar/range/conflicting-value guards.
Rule 4 adds strongly labeled client/patient/invoice/account/customer/reference
identifiers and Staff name as text proposals, retaining leading zeros and evidence;
it also handles Total Amount and bounded label/next-line amount layouts. It does
not promise arbitrary key/value, complicated table, person-role inference or Google
quality parity. Saved/corrected/removed decisions stay authoritative.

The user offered installed Google Stack files as a resource. Read-only public APK
structure inspection found two splits and model assets, but cannot establish which
model extracts details or service dependence. The earlier [reference audit](STACK_REFERENCE_AUDIT.md)
separately traces extracted details to annotation responses from the service. No new
decompilation, proprietary code or
model reuse, network/backend or learned profiles were introduced. New fixtures are
independently authored; no private record was turned into a parser fixture.

## Verification and delivery

[Structured evidence](p1/UI_FOLLOWUP.json) records **22 affected device groups**
and **8 host migration regressions**, all passed. Core includes 8 exact source and
actual rendered-OCR field values, six abstention counterexamples, correction/stale
action/search/archive preservation. Initial real-OCR coverage found only 6/8 due
to blank separators; bounded separator handling repaired it. Production UI checks
exercise manual and explicit reconsider editors and assert values/manual action
are inline. 41 synthetic captures cover narrow/2x text/landscape; changed views
received agent review, without implying human acceptance.

[Post-feedback regression](p1/DETAILS_FOLLOWUP_REGRESSION.json) replays the original
20 authored cases with expectations unchanged. All original date/amount/organization
expectations match on source and rendered OCR. The newly supported Invoice #
adds one output outside the old frozen rubric: evaluation all-field score is
12 true/1 unmatched/0 missed (92.3077% precision), not a measurement of the expanded
field contract. New labeled-field fixtures are authored regression evidence, not
blind evaluation. Original frozen rule-2 evidence is unchanged.

Offline canonical debug/test/unsigned release/lint pass (0 errors/15 warnings);
[final static packaging](p1/UI_FOLLOWUP_PACKAGING.json) passes. Normal build uses no
stackChecks. Original installed/update certificate matches; install-r succeeded,
actual installed APK hash matches build, temporary canonical test APK removed and
Stack reopened. Fresh before/after **7 documents / 13 pages / 9 stacks /
11 memberships** and all four aggregate fingerprints match exactly. Counts reflect
user activity, never targets. All opt-ins stay off; schema 7/archive 2 unchanged.

Actual read-only diagnosis after delivery finds four offered values (Client #,
Invoice #, generic Date, Patient #), plus the user's still-dismissed total. Balance
due abstains because two OCR values conflict; Staff name is not proposed on this
record. No Apply/save occurred. This observation does not establish field quality
or full Google parity; the actual migration still preserves all six source fields.

Host-only errors were repaired: preservation wrapper needed -r for raw output
validation and stopped before install; diagnostic compilation needed --release 17.
A device command was not executed during an approval-service usage limit; the same
approval path succeeded after user resume. Neither was counted as a product pass.

Historical 91-group P1/11-process-stop evidence remains separate; not all historical
suites are replayed for this UI follow-up. All remaining acceptance stays in the
single [H1–H8 checklist](p1/HANDSON_CHECKLIST.md). Full P0/P1 acceptance is open.

Actual migration is prepared, not committed. Obtain the final concrete migration
decision before adding 145 documents. No personal-library restore/delete/reset or
private screenshots are used in verification.
