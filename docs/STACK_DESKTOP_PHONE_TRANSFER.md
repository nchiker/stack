# Move a phone library to Stack desktop

The current desktop app can import and export portable `.stackbackup` libraries.
Automatic or bidirectional phone/desktop synchronization is not implemented yet.
These instructions refer to the independent Stack Android app in this repository.

## First transfer from your phone

1. On the phone, save any unfinished scans and let text recognition finish for
   documents whose text you want to search on desktop.
2. Open Stack's menu → **Backup & restore** → **Backup library**. Choose a location
   and wait for the backup to finish. Use a fresh backup to include recent edits.
3. Copy the completed `.stackbackup` file to your computer, for example using USB
   file transfer.
4. Open the desktop app with your normal persistent library. The UI review launcher
   under `/tmp/stack-ui-refinement/visual-final/` is a disposable sample library;
   use a separate normal library for your documents. From the Stack project root:

   ```sh
   STACK_PDFIUM_LIBRARY="$PWD/desktop/runtime/pdfium/lib/libpdfium.so" \
     desktop/target/debug/stack-desktop
   ```

   The default library is `$XDG_DATA_HOME/org.stackdocs.desktop`, or
   `~/.local/share/org.stackdocs.desktop` when `XDG_DATA_HOME` is unset. An absolute
   `--library /path/to/library` selects a different persistent library.
5. In the left sidebar choose **Backup & restore** → **Import backup…** and select
   the copied file. Read the document counts and collision information, then choose
   **Import reviewed copies** once.

The portable archive carries saved originals, page order/appearance, names, notes,
stack memberships, typed details, recognized text and retained review decisions.
The source phone library is unchanged. Unfinished scans and device security settings
are excluded. The archive itself is not encrypted.

Restore is additive: it keeps existing desktop documents and adds imported copies.
Selecting the same backup again starts another import and can duplicate documents.
Import one chosen current snapshot; importing an older overlapping snapshot as well
does not reconcile the copies.

## Searching the transferred documents

Desktop search includes names, notes, saved details and current recognized page
text. An OCR hit opens the matching saved page. Stale OCR from an earlier page
appearance is preserved but excluded from current search.

New desktop imports now enter an offline text queue. Untransformed readable PDFs
extract embedded text; images/scanned or transformed pages use the pinned English
OCR runtime. Saved crops/removals constrain searchable text. The **Document text**
section shows queued/reading/ready/no-text/failed states and retry/cancel controls.
Already valid text in phone backups remains searchable without reprocessing.
Prepare the packaged runtime described in [desktop instructions](../desktop/README.md).
English OCR coverage and the recorded Ubuntu24.04 x86-64 ABI are explicit; embedded
extraction and the small authored OCR accuracy scenario are separate observations.

## Keeping both copies up to date

There is currently no **Sync now** workflow. Backup export/import transfers a
snapshot and adds copies; it does not match subsequent edits, propagate deletions
or reconcile conflicts. Until exchange is implemented, keep one device as the main
editing library rather than repeatedly restoring full snapshots to both devices.
Avoid sharing the live SQLite/library directory between devices.

The remaining planned stages are:

- **M3:** stable shared identity, causal merge, retained deletions and conflict review.
- **M4:** bidirectional file/folder exchange between desktop libraries.
- **M5:** the same exchange and merge rules in the Android companion.
- **M6:** explicit pairing and authenticated, encrypted local-network transfer.

M5 is needed for phone/desktop synchronization through exchange files; M6 adds the
convenience of a direct local connection. Existing phone and desktop copies will
need an explicit reviewed identity-adoption step rather than automatic matching by
filename or original-file hash. See the [project plan](STACK_DESKTOP_PLAN.md) and
[draft sync contract](desktop/INTEROP_SYNC_CONTRACT_V0_1.md).

This guide was checked against the Android backup and desktop transfer handlers.
No private archive was opened or imported while preparing it. Synthetic Rust and
host-Java archive checks are recorded separately from the still-pending deliberate
transfer of the user's phone library.
