# One-time original Google Stack migration

**Completed real transfer.** After explicit user approval and reported detail
verification, production batch deletion removed the seven current test documents,
then production restore added145 documents/232 pages once. All767 prepared detail
rows,218 memberships and377 originals match read-back. See [actual evidence](STACK_AUTOMATIC_DETAILS.md)
and [structured transfer](p1/GOOGLE_STACK_TRANSFER.json). The preparation chronology
below records the earlier backup-only authorization; the later explicit request
superseded it. Do not repeat the import.

The user requested migration from the original Google Stack app on their Pixel 4a,
then explicitly authorized a private local Android backup for this purpose after
being told it could contain app/account state. This is real migration preparation,
not a personal-library backup/restore test. During the original preparation neither
library was changed; the later expressly authorized real transfer is recorded above. No original app code/protocols,
accounts, backend or proprietary reference implementation were reused.

## Actual preparation result — 2026-10-16

Original app: `com.area120.paperwork`, release 71588, target 30, ALLOW_BACKUP.
Direct Android debug access correctly refused the nondebuggable app. Android's
user-confirmed local backup succeeded. No root, bootloader change, enrollment,
credential collection, private screenshot or personal content logging occurred.
The host's per-serial USB rule was installed by the user after one-time ACLs were
lost when USB re-enumerated. Pixel serial `0A121JEC203530` is connected again.

The prepared archive contains:

| Content | Count |
|---|---:|
| Documents / valid referenced PDF snapshots | 145 |
| Pages, matching every source PDF's page count | 232 |
| Configured category stacks | 17 |
| Active category memberships | 218 |
| Locally referenced page JPEG originals retained alongside PDFs | 232 |
| Saved source detail records, including one empty value | 318 |
| Explicitly removed source detail | 1 |
| Excluded category/tag relationships retained as tombstones | 53 |
| Additional active source labels retained as text details | 105 |
| Auxiliary relationships with unmapped state retained in source provenance | 43 |
| Nonempty source notes | 0 |
| Total original files / archive bytes | 377 / 198,485,169 |

The user hand-counted **19** documents in the original Bills stack. This agrees
with relationship code 1 and excludes nine code-3 records, establishing the
active/excluded mapping used here. This is a source-data interpretation report,
not a formal P0/P1 acceptance pass. Unknown category relationship codes reject;
auxiliary code-2 labels remain historical provenance and are not silently filed.

Every referenced PDF is present, valid, and has the expected page count. Every
locally referenced page JPEG is retained. PDF bytes retain the original app's
current referenced snapshot, including its page order/appearance; original crop/
rotation/enhancement fields and opaque version records are historical provenance.
They are not translated into supposedly identical processing algorithms. Unlinked
cache/draft media and account/sync credentials are not imported. Keep the original
backup until the real migration has been reviewed.

Names and filing become protected manual choices. Exact detail strings/labels,
original type codes, location/timestamp evidence, deletion records and notes are
preserved. The converter does **not** guess native currency/date semantics from an
undocumented type code: legacy values enter as text with original type provenance.
Two names had outer whitespace normalized for Stack's name contract; exact source
names remain in provenance. One empty source field displays `(empty in Google Stack)`
and retains its exact empty source value in evidence. Legacy naive scan dates use
the phone-confirmed `America/New_York` timezone; exact local strings remain retained.
Opaque category palette codes use Stack's default styling and remain in provenance.
No source photo/notification/lock consent, account state or credentials are imported.

The private input and prepared archive remain outside Git in the mode-0700 directory
`/tmp/stack-google-migration/`. Final prepared file:
`Google-Stack-Migration-final.stackbackup` (mode 0600). The original input is
`original-stack.ab`; inspection databases/media and preparation reports also stay
private there. No private binary or metadata values are checked in. Do not delete
this workspace or repeat the backup unnecessarily before the user decides.

**Real import completed once after later explicit approval.** The user identified all
then-current main-Stack documents as test data, reported details look good and
repeated delete/import. Production deletion removed those seven saved documents
without resetting app/DB or touching draft/recovery/catalog. Production restore
reviewed145 new docs/0 conflicts/7 matching stacks and committed once. Exact source
metadata/page/filing/original read-back passes, including unchanged draft/recovery
and previous stack definitions at commit. Later category-count observations are
recorded separately without reseeding; all source categories remain present.
Original Google Stack and private host source/archive remain; temporary phone copy
removed. This completed authority does not authorize repeating cleanup/import.

## Reproduction and verification

`tools/migrate_google_stack.py` independently reads an unencrypted Android app
backup, privately extracts only SQLite/document media under hashed filenames and
writes the existing strict portable archive 2. It has no network or app mutation.
Requires Python 3.9+ and `pdfinfo`; synthetic host tests additionally use Pillow.
Use a fresh work directory and output name outside the entire Git root:

```sh
python3 tools/migrate_google_stack.py \
  --input /private/original-stack.ab \
  --output /private/Google-Stack-Migration.stackbackup \
  --workdir /private/new-work-directory \
  --timezone America/New_York --active-tag-type 1 --excluded-tag-type 3
python3 tools/test_google_stack_migration.py
```

Eight synthetic host regressions pass: exact notes/values/source types, blank values,
removals/exclusions, archive framing/hashes, missing sources, unknown filing state,
page order/count, selective backup extraction, unsupported/encrypted input and limits.
An independently authored tiny fixture also passes actual Android production
stage/review/additive restore: matching Bills merges, a custom stack is created,
excluded Bills stays excluded, originals/notes/details/tombstones/search survive,
and the preexisting isolated document remains unchanged. The fixture identity and
size are checked before mutation; this check refuses canonical packages/private data.
The new instrumentation flag is `googleMigration`; fixture generation uses
`python3 tools/test_google_stack_migration.py --fixture /tmp/synthetic.stackbackup`.

Separately, the **actual prepared migration** received a read-only host audit of
all archive hashes, source titles/notes/detail values/type codes and the entire
active membership set. No private-data restore test occurred. Source properties,
generated regressions, actual preparation and the user's count remain distinct.

Google's current [export guidance](https://support.google.com/drive/answer/15128833?hl=en)
describes individual document sharing and the end of bulk export in September 2024.
Android documents [private app storage](https://developer.android.com/training/data-storage/app-specific)
and the [deprecated adb backup command](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/client/commandline.cpp).
The local backup route worked on this actual target-30/API-33 installation; it is
not a promise for every Android/app version or a direct account-import integration.
