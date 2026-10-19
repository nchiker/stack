# Automatic details and completed real migration — 0.8.5/code 18

Current app delivery is [0.8.6/code19/schema8/archive3](STACK_STACK_EDITOR.md);
this document records the earlier detail-rule/transfer work. Detail rules and
previous limited user feedback retain scope; the current personal library must
not be cleaned/imported again.

Implementation `0b17c4a` + `1bd1bf1`, schema 7/archive 2. The user explicitly
changed the review requirement: confident detected details should save immediately,
with Edit/Remove afterward. They selected USD for a bare dollar sign, identified
all current main-Stack documents as test data, then reported “detail information
looks good” and twice requested deletion followed by the actual Google transfer.
This is a limited informal report, not a full H1/P0/P1 acceptance pass.

## Saved defaults and authoritative corrections

Complete, untruncated OCR fills unowned detail slots once. Values appear in Details
as **Detected**, with Edit/Copy/Remove/Source in the ordinary per-value menu and
Add manually visible. Existing saved, dismissed and removed records are preserved;
known equivalent legacy/manual labels also block reinsertion under a different ID.
Manual changes stay authoritative across reanalysis/restart. Conflicting values
still abstain. Partial text retains explicit review, and an unfinished editor draft
holds the entire document revision stable until it is saved/canceled.

Saving defaults and publishing fresh worker input/claim happen in one transaction.
Injected failures roll back all values/revisions and remain retryable. Content,
choice and claim checks reject stale workers/editors/actions. Organization relations
and derived search update through the existing repository. No scanner/OCR/P0
naming/filing algorithm was changed; no network, model or learned profile was added.

A bare `$` now uses **USD**, with `currency_basis: bare-dollar-default` and
`dollar_currency_default: USD` in source evidence. Explicit supported currency codes
(e.g. CAD/AUD) take precedence. Bare pounds and amounts without a recognized symbol
or code retain unknown currency. Existing stored values are not silently retyped.
The default is the user's requested convention, not a claim that `$` uniquely means USD.

Automatic values use the existing accepted/saved state plus
`acceptance_actor: automatic` in evidence. They do not claim manual confirmation.
Manual edits keep historical source evidence but lose the Detected treatment.
Format 2 already preserves arbitrary supported evidence JSON, so schema/archive
versions remain unchanged; old readers preserve this actor marker. Format 1 and
strict unsupported-version rejection retain their existing behavior.

## Actual verification and in-place delivery

[Structured results](p1/AUTOMATIC_DETAILS.json) record **25 affected device groups**:
10 core, 5 production UI, 1 authored corpus replay, 1 synthetic Google conversion,
8 archive/export. All pass. New coverage includes automatic fill/edit/remove,
manual/dismissal/legacy-label authority, late drafts, partial OCR, atomic injected
failure/retry, stale actions, source/real-OCR leading zeros and dollar defaults,
search and format-2 actor/choice recovery. The row-digest comparator accepts the
actual synthetic addition and rejects six tampered existing-row/revision/ownership
cases. Six changed synthetic Details captures at narrow/2x text/landscape received
agent review; other shared views retain the 41-view follow-up checks.

[Original-rubric replay](p1/AUTOMATIC_DETAILS_REGRESSION.json) is post-feedback
regression evidence, not new blind or real-photo accuracy. Frozen labels remain
unchanged. Old unknown-dollar expectations intentionally differ from the user's
new USD requirement; the rule-4 Invoice # addition remains outside that old rubric.
No broad precision/Google-parity claim follows from these authored fixtures.

Normal offline debug/test/unsigned release and lint pass (0 errors/15 warnings).
Actual installed APK matches the final static audit and original certificate.
Canonical used no stackChecks and install-r only. Interim 0.8.4 added six authorized
automatic values: every preexisting row and each metadata revision delta was checked
on-device without exporting values. Final 0.8.5 then matched all four aggregate
fingerprints and all nine existing detail rows exactly. Fresh observations were
7 docs/13 pages/9 stacks/11 memberships, not reseeding targets. Temporary canonical
test APK removed; all new opt-ins stayed off. See the report for exact hashes.

## Actual cleanup and Google Stack transfer

[Transfer evidence](p1/GOOGLE_STACK_TRANSFER.json) separates this real, explicitly
authorized operation from synthetic tests. Production batch selection/confirmation
deleted exactly the seven current saved test documents, their 13 pages and owned
data. Main Stack was never cleared, reset, uninstalled or given a replacement DB.
The unfinished scan, consolidation recovery and all nine stack definitions remained
unchanged at cleanup. Original Google Stack and the private host backup were kept.

Production Backup & restore reviewed **145 documents / 0 ID conflicts / 7 matching
stacks** and received one Add restored documents action. All **145 documents,
232 pages, 218 source memberships, 767 prepared detail/provenance/removal rows and
377 original files (197,564,722 bytes)** match the source manifest read-back. Names,
notes, source types, page edits/order and source evidence were checked on-device;
only aggregate results enter Git. Existing stack rows and draft/recovery matched
at commit. Categories merged by name and missing categories were added. A later
catalog observation changed 19→18→17 during subsequent use; a second source mapping
and file audit still matches all source categories/data. No agent category deletion,
restoration or reseeding was performed. Counts are observations, never targets.

Legacy values retain exact source text/type provenance, not guessed native types.
The previously documented two trimmed outer-space names, one empty-value placeholder,
opaque source settings and unmapped auxiliary-state provenance retain their exact
source evidence. The real operation did not import account credentials, monitoring
consent or lock authorization. No private archive was restored as a test, no personal
backup was created for testing, and no private screenshots/content logs were taken.

The temporary phone migration archive was removed after verified commit. The
mode-0700 host workspace `/tmp/stack-google-migration` retains the original backup
and final archive until the user decides otherwise. Do not repeat the import.
Read-only verification source is [ReadOnlyMigration.java](../tools/android/ReadOnlyMigration.java);
it consumes the prepared manifest over stdin, compares metadata/files on-device and
emits counts/digests only. Raw row/catalog digests stay private outside Git.

## User testing remains one session

Five authored CC0 inputs and `Start-here.txt` are on the phone under
**Download/Stack-Test-Files**. No fixture was automatically imported into the main
145-document library. Stack Checks alone was reset for fresh testing. If the
Downloads provider omits copied files, choose phone storage → Download →
Stack-Test-Files in the picker. The labeled-details PDF tests bare-dollar USD;
the Receipt tests explicit CAD, Ambiguous tests date abstention, Invoice tests a
later-page search, and the text input tests a safe unsupported import.

Use the single [H1–H8 checklist](p1/HANDSON_CHECKLIST.md), including P0/resource gates.
Background OCR progress in the transfer report is an observation, not completion
or accuracy acceptance. Full P0/P1 acceptance remains open. Use disposable Checks
for destructive/bulk/archive/photo/authentication testing; the migrated real library
is protected again. Do not repeat cleanup or migration without a new request.
