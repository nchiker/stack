# Stack 3b: desktop reads archive format 4 and encrypted backups (handoff, 2026-11-21)

Start with: "Read docs/STACK_3B_HANDOFF.md and start 3b." The user wants this done mostly with cheaper agents (see Process).

## Why
Since Stack 0.17.3 the phone writes encrypted backups (an outer envelope around the archive) and, when linked documents exist, format 4 (adds a `links`
array and `previews/<id>.pdf`). **The desktop (Rust, `desktop/`) refuses both**, so a backup made on the phone cannot be opened on the computer. Goal:
the desktop can open, review and restore/import format 4 and encrypted backups made by the phone, and refuses anything wrong cleanly.

## Read first
- `docs/STACK_ARCHIVE_FORMAT.md`: "Format 4 and the encrypted envelope" (inner stream, outer envelope, keys and policy, implementation notes, **shared test vector**,
  "Update (0.17.3): key-only setup"). The phone code is the reference: `LibraryArchive.java`, `LinkArchive.java`, the envelope classes beside them, `EnvelopeChecks` in `androidTest`.
- `docs/STACK_LINK_CONTRACT.md` ("Encryption of exports", "What this changes inside Stack").
- `docs/desktop/CURRENT_RELEASE_HANDOFF.md` ("Toolchains": Cargo cache, Flatpak SDK, how the desktop is built and checked).
- Rust crates: `desktop/crates/stack-archive` (format reader/writer), `stack-core/src/interchange.rs` (export/import flow), `stack-sync`, `stack-cli`, `stack-desktop`.

## Facts that matter
- **Key:** the recovery key is made and shown by the Key Vault app (button "Stack backup key (QR)…", repo `~/Projects/APK-Keys`; the `keyvault key-qr` command). Both
  phones use the same key. A key-only envelope has `slot_count` 1 (recovery slot only); the reader must accept 1 to 4 slots; a passphrase slot may exist.
  The desktop needs a way to enter the key (the 64-character typed key, and/or a file or paste; **never print, log or store the key**; do not run `keyvault`
  commands that open password windows for the user).
- **Test vector** (envelope v1) is in the archive doc: the Rust reader and writer must reproduce SHA-256 `d5ca2409…0c8f` for the 70,216-byte output; also the other listed vectors
  (non-ASCII NFC passphrase, bodies of 1, 65,536 and 65,537 bytes, tamper cases).
- Format 4 rows (`LinkArchive.FIELDS`): `id, owner, link_id, kind, title, mime, modified, hash, shared, protected, locators, state, title_user_edited, added_at, text`.
  `state` is `live|unreachable|missing|trashed` (the phone never exports `trashed`; a reader must accept all four, and a restored link is stored as `unreachable`).
  The lifecycle columns (`state_since`, `checked_at`, `revision`, `removed_reason`) are local to the phone and not exported.
- Strict validation is the house style (unknown fields refused; limits: text 1 MiB, title 200, preview 32 MiB, 1000 links).
- **Decision needed from the user before coding the UI:** what the desktop does with linked documents (suggestion: read, validate and keep them in the imported library as
  read-only "linked document" records with their text and preview, searchable, no Open; or ignore them with a clear message). Ask once, briefly.
- Not in scope: the phone app, AmiPro, sync of link records (slice 6), publishing a desktop release unless the user says so.

## Process (the user's rules)
- **Routine work (reads, edits, tests, builds) by cheaper agents (haiku/sonnet).** If something needs a higher-cost agent (e.g. a read-only Opus review of the decryption code
  before coding it, as was done on the phone), **suggest it to the user and wait for approval**; do not spawn it on your own.
- Test phone is `0A121JEC203530` (TEST build `org.stackdocs.app.checks.linkcontract`); the production phone `5A101JEA313018` is hands off. To get a real phone-made encrypted
  backup for testing, ask the user; do not read or print any key.
- The user is easily lost by similar names: say plainly which app, which computer, which key.
- Commit with the Co-Authored-By trailer. Publishing (GitHub, release) only on the user's explicit go-ahead: `python3 tools/backup_github.py --mirror ~/.cache/stack-source-backup --push --public`.
- Update this file's status at the end and add the desktop side to the archive doc ("Implementation notes").

## State at handoff
- Stack 0.18.0 (code 35) is published (GitHub v0.18.0). The slice 4 handoff is `docs/STACK_LINK_SLICE4_HANDOFF.md`.
- Phone checks: `-e linkContract true`, `-e archiveOnly true`, `-e features true` all pass on the TEST build.

## Status (2026-11-21, end of phase 2; nothing committed or published)
Decision taken by the user: linked documents are kept read-only in the imported library as "Linked document" records (text and preview, searchable,
no Open); the data model stays open to a desktop editor later (all archive columns are stored; only the UI is read-only).

Done
- Phase 1 (`stack-archive`): envelope reader/writer (`crypto.rs`), format 4 validation (`links.rs`), `read_encrypted_into`, shared vector.
- Phase 2 (`stack-core`): plain-vs-envelope detection by magic, `BackupKey`, format 4 and encrypted import/review/restore, schema 5 (`link_records`,
  `link_search`, `links/` folder, reset list updated), receipt fields and review text (documents, links imported, links skipped, "not encrypted"),
  search merges links into unfiltered results (`SearchHit.linked`), `Library::linked_document`.
- `stack-cli`: `archive ROOT validate|review|restore FILE [--saved-key|--key-file|--key-stdin|--passphrase-file|--passphrase-stdin]`.
- `stack-desktop`: picking an encrypted file opens a key panel (password fields, paste works, optional passphrase; a wrong key asks again),
  linked cards ("Linked document") and a read-only linked view (page 1 of the saved preview, saved text, no Open, no Edit).
- Tests: `stack-core/tests/links.rs` (encrypted end to end, passphrase, skip-existing, unreachable-on-restore, id collision, wrong/missing key,
  rollback at two failure points, unfinished restore cleaned on reopen, plain archives unchanged) and a desktop controller test of the whole key flow.

- Saved backup key (2026-11-21, uncommitted; replaces the Key Vault launcher, which was removed): the desktop stores the recovery key itself in the OS secret
  store (`oo7` 0.6.0, tokio, `native_crypto`; Secret portal in the Flatpak, Secret Service otherwise; the manifest has no keyring permission, the Flatpak keeps its own store).
  Code: `stack-core/src/keystore.rs` (`SecretStore`, `SystemStore`, `MemoryStore`, `parse_key_text`, `fingerprint`, `save_key`, `saved_backup_key`,
  `key_status`); `transfers_ui.rs` (`Action::PasteKey`, `ClipboardKey`, `KeySaved`, `RemoveKey`, `SavedKey`, `SaveKeyToggled`), the menu key row (saved + Remove, fingerprint in a tooltip, width-limited) is in
  `navigation.rs` `library_menu`; pasting is only on the key panel. Encrypted backups open with the saved key, else the key panel (with "Save this key on this computer"). CLI: `--saved-key`.
  Tests use the in-memory store. Security review fixes applied (2026-11-21): clipboard answers carry a request number and are dropped if stale; the pending key lives in the transfer and is saved only for an encrypted review; core refuses a key for a plain archive; menu open never unlocks; one-entry store; shared key parser; Typed zeroizes. NOT yet run against a real Secret Service or inside the Flatpak (needs a manual check: paste, restart, open a backup).
  New computer: Key Vault "Copy Stack key", then in Stack choose Import backup and click "Paste key from Key Vault". To replace a saved key, import a backup the saved key cannot open; the panel's paste replaces it once the backup opens.
- Guided import (2026-11-21, uncommitted; from a real user test where the flow was not understood): choosing an encrypted backup with no saved key opens the key
  panel ("In Key Vault, click Copy Stack key first." and one primary button "Paste key from Key Vault"; typing a key or passphrase is a small option below).
  A valid clipboard key opens that same backup straight to review (the key is saved once it has opened the backup, the clipboard is cleared); a bad clipboard
  or wrong key stays on the panel with one short line. The review shows "Ready to import: N documents[, M linked documents (read-only)]" with a primary
  "Import N documents" button (id `stack-import-reviewed`), "Review again" and "Cancel". After the import the saved message is shown and the library
  opens on All documents with search cleared (`Stack::show_all_documents`); before this change it only refreshed whatever view was open. Tests:
  `pasting_the_key_on_the_panel_opens_the_same_backup` and the PDFium-only `..._imports_and_shows_the_linked_document` (run alone; PDFium initialises once per process).
  After Prepare `Active` holds no key (`Active.keyed` only); the worker drops it when its work ends.

Left / not done
- Real phone-made backup checked (2026-11-21, `Stack-2026-11-21-182252.stackbackup`, 218 MB from test phone): `archive ... review` with the key piped from Key Vault (`keyvault unseal stack/stack-backup-recovery-key.txt --stdout | stack-cli ... --key-stdin`) gave format 4, encrypted, 164 documents, 2 links, 0 skipped. Still to try: restore of that file and opening it in the desktop app.
- The CLI needs `STACK_PDFIUM_LIBRARY` for any backup with link previews (the packaged app ships PDFium). `review` prints the full id mapping JSON (very long); consider a short form.
- The desktop export (and any export) still writes format 3 without link records, so a desktop round trip drops linked documents. Sync of link records is slice 6.
- Linked view shows page 1 of the preview only, and the first 20,000 characters of text. No stacks, tags, notes or delete for linked documents yet.
- Link rows are not removed or aged by any lifecycle on the desktop (no checks; they stay `unreachable`).
- Typed key text passes through iced widget strings that cannot be zeroed; the copy kept by the app is zeroed after use.
- Decryption code reviewed by a read-only Opus agent (no critical/high findings); its medium and low findings are fixed: previews go through the verifier, plain v4 import covered by a test, `aes` zeroize, `DecryptReader` fails for good after an error, writer rejects duplicate slots, wrong-key message matches the spec. fmt and clippy are clean.
- One desktop test (`encrypted_backup_asks_for_a_key_and_imports_linked_documents_read_only`) is `#[ignore]`d because it needs PDFium; run it with `STACK_PDFIUM_LIBRARY=<path to libpdfium.so>` (e.g. the one under `.artifacts/stack-desktop-20261111-v0.3.0/stage/runtime/pdfium/lib/`).
- New crates in the desktop Cargo.lock (copied from the other Cargo cache with checksums verified): aes-gcm, aes, hmac, pbkdf2, unicode-normalization and their helpers.
- Committed on main (2026-11-21); not published.

Next
1. Restore the real backup into a throwaway root and open it in the desktop app (key from Key Vault, never printed).
2. Decide with the user: desktop export of linked documents (format 4, encrypted; needs an OS random source) vs leaving it to slice 6; link count in CLI Status JSON.
3. Publishing only on the user's explicit go-ahead.
