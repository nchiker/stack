# Stack Link contract — draft 2 (2026-11-21)

Status: **slices 1-4 and 6 built and tested, slice 5 built on the Stack side (2026-11-22), slice 7 in progress.** See `RUNBOOK.md` for what is left. Kept deliberately small: two people sharing and finding documents. Agreement needed from Stack, AmiPro, Filen Files and any later
notes or spreadsheet app before code is written.

## Purpose

Stack is the one place to find every document. Scanned PDFs live inside Stack. Editable documents (Ami Pro
now; notes and spreadsheets later) stay in the app that understands them. Stack holds a **link record** for each
one: enough to search it, preview it, show where it is and open it in its owner app. Stack never parses another
app's format and never edits, moves or deletes another app's file.

## Roles and authority

| Party | Owns | Must never |
|---|---|---|
| **Owner app** (AmiPro, notes, sheets) | the document content, its text extraction, its preview | rely on Stack to keep the file |
| **Filen Files** | where the file lives and who it is shared with | edit content |
| **Stack** | the record: title, tags, stacks, notes, cached text and preview, state | write to, move or delete the file |

Rule 1: **the file is the truth; the record is derived.** A stale record must never win over the file.
Rule 2: **events are hints, `stat` is truth.** Any event can be lost, so Stack can always ask the owner.
Rule 3: **only the owner app or Filen changes a file.** Stack asks them to; it does not do it itself.

## Identity

- `link_id`: lowercase UUID, minted by the owner app the first time it shares a document with Stack. The owner
  keeps a map `link_id` to file in its own private storage, so it survives rename and move. (Ami Pro should not
  need to write it into the `.SAM`, which is a format it does not fully control.)
- Stack stores it as `owner` + `link_id`. The pair is unique. Stack's own `documents.id` is separate and local.
- Re-sharing the same file again updates the record. It never makes a second one.

## The record

Sent by the owner to Stack; every field is validated by Stack. Unknown fields are rejected (same strictness as
the archive format). Limits follow the archive format: text 1 MiB, title 200 chars, preview 32 MiB. **Transfer:** Android's Binder
limit is about 1 MB, so the small fields (everything except `text` and `preview`) go in the provider call, and
`text` and `preview` are streamed through a file descriptor the call returns.

| Field | Notes |
|---|---|
| `contract` | `1` |
| `owner` | package name, e.g. `app.amipro.editor` |
| `link_id` | see Identity |
| `kind` | `doc`, `note`, `sheet` (open set; unknown kinds show a generic icon) |
| `title` | display title |
| `mime` | e.g. `application/x-amipro-document` |
| `locators` | ordered list, see Locators |
| `modified` | epoch ms of the file's last change, as the owner sees it |
| `text` | plain text for search (what a person would type to find it). **Must include text inside tables and text frames**, not only body paragraphs (see audit below) |
| `preview` | optional PDF bytes, rendered by the owner |
| `shared` | `true` if the file is shared with the partner (display only, not authority) |
| `hash` | SHA-256 of the file bytes. Stack accepts a `publish` whose hash differs from the stored one and ignores an exact repeat (so a reinstalled owner app cannot be locked out). Also warns when two records have the same hash |
| `protected` | `true` if the file is password-protected (see Protected documents) |

## Locators

Ordered by durability; Stack tries them in order.

1. `filen`: `{uuid, path}`. The UUID survives rename and move inside Filen; the path is only a hint.
2. `saf`: `{uri}`. A persisted storage-access grant. Fragile: lost on reinstall, may not survive moves.
3. `owner`: `{token}`. Opaque; only the owner app can resolve it (its own private files).

A shared document should have a `filen` locator. Without one, Stack warns that the partner cannot open it.

**Only the `filen` locator syncs** between a person's own devices. `saf` and `owner` locators and the
`unreachable` state are per device, because a phone's storage URI means nothing on the desktop.

## Operations

Owner to Stack (Stack exposes a content provider `org.stackdocs.app.link`; calls are `publish`, `moved`,
`removed`, `unshared`):

- `publish(record)`: create or update. Sent on first share and when a document is saved or closed (not on every
  keystroke).
- `moved(owner, link_id, locators)`: the file now lives elsewhere. Replaces the locators; nothing else changes.
- `removed(owner, link_id, reason)`: the owner (or Filen) deleted the file. Reason: `deleted`, `trashed`.
- `unshared(owner, link_id)`: access for others ended; the file still exists.

Stack to owner (each owner exposes `<package>.stacklink`; Stack calls it):

- `stat(link_id)` returns `{exists, locators, modified, hash}` and optionally `revision` (an owner with no revision counter, such as AmiPro,
  omits it; `hash` is the SHA-256 of the file bytes, the same hash it publishes). This is the truth. A failed call is `{error: "unreachable"}`.
- `open(link_id, mode)`: `edit` or `view`. The owner answers with a `Bundle` holding an explicit `intent` for one of
  its own activities (or `error: not_found`). Stack starts it from its own foreground screen (a provider cannot
  reliably start an activity from the background) and only if the intent names a component of the owner's package,
  grants no URI access and carries no clip data or selector. The owner resolves the file with its own grant, so no
  file bytes pass through Stack.
- `refresh(link_id)`: ask for a fresh `publish` (text and preview).
- `delete(link_id)`: *only when the user chose "Also delete the file"*; the owner (or Filen) does the deletion.

**Partner copies (foreign records).** The partner's AmiPro has never seen the owner's `link_id`, so Stack never
calls `stat` or `open` on the owner app for these. It checks the `filen` locator through Filen Files and opens the
file through Filen Files' shared-file view, in the matching app, for viewing. Filen Files only reports moves and
deletes made inside its own app; changes made through the Filen website or desktop client are caught by the next
check.

**While Stack's app lock is on,** the provider still accepts `publish`, `moved` and `removed`, and shows nothing.

**Caller check.** Apps here use different signing keys, so a signature permission does not work. Each side
verifies the caller by `Binder.getCallingUid` to package to certificate SHA-256, against a pinned list shipped
in the app. Unlisted callers are refused.

## Lifecycle: move, rename, delete

A record is in one state:

```
live ── moved ────────────▶ live (new locators)
live ── cannot reach ─────▶ unreachable ── reachable again ─▶ live
live ── "no such file" ───▶ missing ── 30 days, or user says "forget" ─▶ trashed
live ── removed ──────────▶ trashed ── 30 days / delete for good ─▶ gone (tombstone)
live ── unshared ─────────▶ live (not shared)
restored, locators dead ──▶ unreachable ("Set up again")
```

**Move or rename (the file is the same file).**
1. The party that moves it sends `moved` with the new locators. Filen Files does this for Filen moves, using the
   UUID, so a move inside Filen needs no owner involvement.
2. Rename changes the file name only. The owner re-publishes with a new `title` if it follows the filename;
   Stack keeps a user-edited title unless the owner's title changed *and* the user never edited it
   (`title_user_edited` already exists).
3. If the event is lost, Stack's next `stat` returns the new locators. If the owner cannot find it either, the
   record goes to `missing`.
4. Nothing the user added in Stack (stacks, tags, notes) is touched by a move.

**Delete in the owner app or Filen.**
1. Sender calls `removed`. Stack moves the record to **Recently deleted** and keeps its text and preview for 30 days (restoring it makes it readable again; a read-only view inside Recently deleted is not built yet) and restore the *file* from Filen's own trash if they want it back.
2. Filen trash is not a delete: while the file is in Filen's trash, reason is `trashed`. If it is restored,
   `moved` or `publish` returns the record to `live` automatically.
3. After 30 days, or "Delete for good" in Stack, the record is removed and a tombstone is kept (see Sync).

**Delete in Stack.** The default removes only Stack's record. The file is not touched, and the dialog says so.
"Also delete the file" calls `delete(link_id)` and the owner or Filen does it, with its own confirmation. If
that app is missing or refuses, the record is still removed and the user is told the file remains.

**File vanished without notice** (owner uninstalled, grant lost, Filen offline). `stat` fails. A failed call is
never treated as a deletion: offline or "owner not installed" gives `unreachable`, shown as a grey badge, and
nothing is removed. Only an explicit "no such `link_id`" answer or an owner `removed` event can make it `missing`.

**Reconciliation.** Stack runs `stat` for a record when it is opened, and for all records in a manual
"Check linked documents". It is a pull, so the system heals after any lost event.

## Sharing with a partner

- Stack publishes selected stacks to a Filen folder as a read-only archive (the record, text and preview, not
  the file; the file is shared separately by Filen Files).
- The partner's Stack imports it as `origin: foreign`, `access: shared-read`. Foreign records can be opened,
  searched and tagged locally; they can never be edited, moved or deleted at the source.
- `Open` for a foreign record resolves the `filen` locator through the partner's Filen Files (`filen-in`).
  Without the owner app the partner still sees the cached text and preview.
- When the user removes or unshares a document, the next publish carries a tombstone. The partner's copy goes to
  Recently deleted with the note "no longer shared". The partner's own tags are kept in the trash entry.
- The partner deleting a foreign record removes only their local copy. It never reaches the owner.

## What this changes inside Stack

Findings from reading the code (not yet verified at runtime):

- **Data model.** Slice 2 keeps link records in their own tables, `link_records` and a `link_search` FTS4 table,
  **outside** `documents`. Reason (checked in the code, 2026-11-21): a `documents` row with no pages makes the
  whole backup fail (`An original is missing`), is refused on restore (`page_count` 1 to 1000, mime allowlist) and
  is captured by the sync triggers, whose receiving side rejects a zero-page document. The link tables are not
  captured and not exported, so nothing existing can break. Slice 3 (archive 4) adds them to backups, slice 6 adds
  them to sync, and stacks, tags and notes for links arrive with those slices (a membership table keyed on the
  link record, deleted with its stack).
- **Risk.** Code paths assume a document has pages (thumbnails, export, page count, OCR workers). Each needs a
  `kind` guard. The preview PDF is stored as a file, not as a page, so export and OCR do not treat it as a scan.
- **Trash.** Slice 2 deletes a link record outright (the file is untouched); Recently deleted for links arrives with
  the lifecycle slice.
- **Archive.** Format 3 rejects unknown fields, so link records need **format 4** (new `links` array,
  `previews/<uuid>.pdf` entries, older apps refuse it as before). Existing archives restore unchanged.
- **Sync.** The wire format rejects unsupported graph shapes, so link records need new `link_records/` cells (graph model
  stays 2, wire version 1; see "Slice 6, sync of link records" below) keyed by `(owner, link_id)`. The title, text hash, preview and `filen` locator sync; device-local locators and states stay per
  device. User fields (stacks, tags, notes) merge as they do now. This is the largest piece of work and needs the shared
  golden cases extended in both Java and Rust.
- **Desktop.** Needs the same schema and a Linux "open with" path (a launcher per owner kind). The first slice
  can be phone-only.

## Slice 2, Stack side: built (2026-11-21)

Provider `<stack package>.link` (`contract`, `begin`, `commit`, `abort`, write-only `stage/<token>/text|preview`), the
caller check (own certificate or a pinned package and certificate pair; AmiPro release pinned), `LinkStore` and tables,
search on Home and All documents with a "Linked" badge, the detail screen (saved preview, saved text, Open, Rename,
Remove) and the owner launcher. Preview drawing is capped and crash-marked. Reviewed for security by a higher-tier agent;
see the commit messages for the fixes. Not yet done on the Stack side: stacks, tags and notes for links, Recently
deleted, backup and sync of links (slices 3, 4, 6). The owner side (AmiPro) is the rest of slice 2.

Slice 3 handoff (archive format 4 and encrypted exports): `docs/STACK_LINK_SLICE3_HANDOFF.md`.

## Slice 4, Stack side: built (2026-11-21)

`stat`/`refresh` calls (`LinkChecker`: owner identity checked first, 5 s limit, own thread pool, one call per owner, compare-and-set write; only a well-formed `exists:false` makes `missing`), provider `removed` (`{ok:true, changed:bool}`; unknown id is a no-op; `moved`/`unshared`/`delete` answer `unknown_method`), states `live|unreachable|missing|trashed` with `state_since` (missing for 30 days becomes trashed; trashed for 30 days is deleted), Recently deleted for links, "Check linked documents", detail-screen messages and an "Update saved copy" button when the hash changed. Checks: `LinkLifecycleChecks`.

## Slice 5, Stack side: built (2026-11-22)

Provider methods `moved` and `unshared` now exist beside `removed`. Two senders:
- **The owner app** sends `{contract:1, owner, link_id, locators:[...]}` (`moved`), `{contract:1, owner, link_id}` (`unshared`) or `removed` as before. `owner` must be the caller. `moved` replaces the locator list and changes nothing else (stacks, tags, notes and the user's title stay); it also brings a record back to live if it was missing, unreachable, or in Recently deleted only because the file sat in Filen's trash (`removed` reason `trashed`). A record reported `deleted` or removed by the user is never revived by `moved`. `unshared` clears `shared`.
- **Filen Files** (pinned package `app.filenfiles`, release certificate `59ccca4c...80bf`) does not know the owner's `link_id`, so it names the file by its UUID: `moved {contract:1, filen_uuid, path}`, `removed {contract:1, filen_uuid, reason: deleted|trashed}`, `unshared {contract:1, filen_uuid}`. Stack finds every record whose `filen` locator has that UUID; a Filen move replaces only the `filen` locator (the owner's `saf`/`owner` entries stay). Only Filen's package may send the `filen_uuid` form. Answers: `{ok:true, changed:bool, count:n}`.
- The detail screen says "Shared", and adds that the partner cannot open it when the document has no `filen` locator.
- Checks: `LinkLifecycleChecks` group "moved and unshared" and "Filen-origin events" (13 groups pass on the test phone).

## Slice 6, sync of link records (built and announced)

Full design and implementation notes: `STACK_LINK_SLICE6_DESIGN.md`. Wire details: `desktop/INTEROP_SYNC_PROTOCOL_V1.md`.

- **What syncs.** Phone to desktop (and desktop-only links to the phone): title, locator `filen`, hash, mime, modified, shared,
  protected, text, preview, presence. The desktop is a read-only mirror of the records: it never edits or deletes them. It can open the original file in Ami Pro Linux ("Open in Ami Pro"), which edits the file, not the record.
  Not synced: `saf`/`owner` locators, `unreachable`/`missing`, `checked_at`, `state_since`, `revision`, stacks/tags/notes for links.
- **Protected links** sync metadata only (identity, content, title, filen, presence). No text, no preview, no blob.
- **Limits.** At most 1000 links, text 1 MiB (UTF-8 bytes), preview 32 MiB, title 200, 2 GiB of link blobs per device.
  A row over a limit is not synced (marked "not synced"); it never blocks Sync.
- **Disagreement.** The same link with a different hash on each side: the later `modified` wins, the other value stays in
  history. Only an equal `modified` goes to review, on the phone, one choice per link. Otherwise the phone's values win.
- **Delete.** Only presence `gone` deletes a desktop row (phone Delete for good, the 30-day sweep, Remove). Absence deletes
  nothing. A link only the desktop has arrives on the phone in Recently deleted (reason `user`); if it is not restored in
  30 days the phone sweep makes it `gone` and the desktop deletes it. Purge removes retained blob bytes after the desktop
  acknowledges; titles, Filen paths and possibly old text stay in sync history.
- **Setup.** Nothing to install or run: both apps announce links in the sync handshake and the next Sync does the rest.
  An older desktop still syncs documents. An older desktop build cannot open a library the new desktop has upgraded (schema 6).
- **Status.** Both sides are built, tested and announce `links` (`ANNOUNCE_LINKS=true`, step D4b is live),
  so link cells now travel once a side enables. Real-LAN test (T3) passed 2026-11-22 (50 links, first sync 42-49 s). The Flatpak launch test (6b) passed 2026-11-22 for option 1 (see Desktop Open, Results); only the open decision about files outside Stack's own folders remains.

## Slice 7, desktop (S7-4)

The partner's computer gets the partner's shared documents through the normal phone-to-computer link sync, with nothing to set
up: the phone writes them as link records with `origin: foreign`, the partner's name (`shared_by`) and the share archive's
generation, but only for a computer that announced it can show them (hello feature `link_foreign`); an older computer keeps
syncing documents and the phone's own links exactly as before. The sharing key never goes to the computer and the computer
never reads the share archive. Shared records are read-only on the computer: they show in the Linked list and in search with
"Shared by <name>", the detail view shows the saved text and preview and says "Opening needs your phone: it opens through
Filen Files.", and there is no Open button, no "Open in Ami Pro" and no remembered file. The computer never edits, deletes or
sends them back; the phone is the only author. Unshared (Recently deleted) is hidden on the computer, restoring brings it
back, and removing the phone's copy for good deletes the computer's copy. Shared records are not in backups or archive
exports. Desktop schema 8 and phone schema 16; an older desktop build cannot open a library the new one has upgraded. Wire
details: `desktop/INTEROP_SYNC_PROTOCOL_V1.md` ("Link records, slice 7").

## Work by project

Changes to the owner apps are part of this plan, not an afterthought. Each slice names who does what.

| Slice | Stack | AmiPro (owner app) | Filen Files |
|---|---|---|---|
| 1. Contract | agree this document; a stub owner app in instrumented tests | same | same |
| 2. Read side (phone) | `publish` receiver, index text, show preview and badge, `Open` | **Share with Stack** action; `link_id` map; extract body, table and frame text; render the preview PDF; `<package>.stacklink` provider with `open`; re-publish after save | none |
| 3. Archive 4 and encryption | link records and encrypted export in backups, phone then desktop | none | none |
| 4. Lifecycle | state machine, Recently deleted, `stat` check | `stat`, `refresh`; emit `moved` and `removed` for its own files; rescan by hash when a file is gone | none |
| 5. Filen | `filen` locator, shared badge | write the `filen` locator when the file lives in Filen | `moved`/`removed`/`unshared` events from its own file operations; share setup on request; locator lookup by UUID |
| 6. Sync and desktop | `link_records/` sync cells (6); Flatpak launch test is 6b | **AmiPro-Linux: accept a file argument and hand off to a running instance**; extract and preview on desktop | none |
| 7. Partner (Android and Linux) | publish to a Filen folder, import as foreign, tombstones | none | folder change notice, `filen-in` open |
| Later: restore and setup | `unreachable` and relink states | setup manifest entry | setup manifest entry |

Encryption (slice 3) comes before the partner publish (slice 7), so nothing sensitive is written unencrypted by
the new features. Slice 3 (phone) is done: backups include link records when encrypted (or when the user agrees to an unencrypted one); see STACK_ARCHIVE_FORMAT.md.

AmiPro-specific items to track in `AmiPro-APK/docs/STATUS.md`:
- Text extraction of main text plus tables, text frames and (later) headers, footers and notes, in the facade.
- Preview PDF for a document, reusing the print layout without the print dialog.
- Share with Stack action, record builder, `link_id` map, caller check against Stack's pinned certificate.
- Publish after save, and on a `refresh` request.
- Preview: label drawings, charts, equations and metafiles (decided: no extraction or drawing). Columns, fixed-position frames and footnotes wait for a real document that needs them.
- Manifest changes: a provider and a query for Stack's package. The app currently asks for few permissions, so
  each is a decision for the user (see the `INTERNET` note already in STATUS).
- A version bump and a signed release for each of these.

## Decisions of 2026-11-21

### Protected documents
A password-protected `.SAM` is **never shared with Stack by default**, because extracted text and a preview in
Stack would make the password pointless. If the user insists, AmiPro warns that the text and preview will be
readable in Stack without the password; the record is then `protected: true`. Stack shows such records only after
its app lock is unlocked, labels them with a lock, and never includes them in a partner publish unless that
publish names them explicitly.

### Privacy of cached copies
Linked records hold personal data (medical lists are the first use). Rules:
- Text and previews live in Stack's app-private storage only, on phone and desktop. Android automatic backup stays
  off, as it is now.
- **Stack's app lock covers linked documents** (list, search results, snippets, preview) exactly as it covers scans.
- Stack archives and backups include link records, text and previews. Today they are not encrypted, as the archive
  format already says; **encryption of exports is decided below.** The backup screen must say that link records
  are in it.
- A partner publish is always encrypted (see Encryption of exports); Stack still warns when the destination is not
  a Filen folder, because Filen also encrypts end to end and other providers may not.

### Encryption of exports (decided 2026-11-21)
Scope: encrypt what leaves the device, not the live library.
- **Not encrypted:** the live SQLite library and its search index. Android already encrypts app-private storage
  behind the lock screen, Stack's app lock covers the UI, and a database cipher would put FTS4, OCR, sync and the
  desktop code at risk for little gain. On the desktop the library relies on the computer's disk encryption;
  Stack's docs say so.
- **Encrypted:** manual backups, the automatic backup folder, and every partner publish. LAN sync stays as it is
  (authenticated TLS).
- **Required or optional:** **required** for a partner publish. **On by default** for backups, and Stack asks
  before it writes an unencrypted backup that contains link records. A backup without link records may stay
  unencrypted if the owner turns it off.
- **Format:** part of **archive format 4**, the same bump that adds link records (slice 3), so there is one
  format change, not two. An envelope flag says "encrypted"; older apps refuse format 4 as they do now.
- **Cipher:** a standard authenticated cipher (AES-256-GCM or ChaCha20-Poly1305), applied in chunks so the 64 KiB
  streaming and the 8 GiB limit still hold. Nothing custom. The manifest and file table are inside the
  encryption, not beside it.
- **Keys:** a random data key encrypts the archive. The data key is wrapped twice ("two slots"): once under a key
  derived from the **passphrase** by a slow key-derivation function, and once under a **recovery key**. Either
  slot opens the archive. The passphrase uses **PBKDF2-HMAC-SHA256 with at least 600,000 iterations** on both
  platforms (Android has it built in, Rust has it as a crate). It is weaker against GPU guessing than Argon2, so
  the recovery key is the strong path and the screen asks for a long passphrase. Shared test vectors prove both
  sides agree. Argon2 can come in a later format if wanted.
- **Automatic backup:** runs unattended, so after the passphrase is entered once, the wrapped key is kept in the
  Android Keystore (hardware-backed where available). Restoring on a new phone needs the passphrase or the
  recovery key, never the Keystore.
- **Partner key:** the partner's Android and Linux Stack receive the publish key at **pairing** (the QR flow Stack
  already uses for sync), as a separate slot. Removing the partner means publishing under a new key.
- **Wording:** the backup screen says plainly that a lost passphrase and recovery key mean a lost backup.

**Recovery key and the key vault.**
- The recovery key is 256 bits of random data shown as 64 hex characters. The phone shows it once, with a
  "I have saved it" confirmation that asks for a few characters back.
- It is stored by the user's Key Vault (`~/Projects/APK-Keys`), not by Stack: the user types it into
  `keyvault protect --no-live-copy --expect-hex 64 stack/stack-backup-recovery-key.txt` in a terminal. That keeps it
  in RAM only, wraps it in the vault's passphrase-protected archive, copies that to the Filen drive and reads it
  back; exit 0 means it is protected. The key never passes through a chat or a log.
- Recovery drill: `keyvault unseal-check stack/stack-backup-recovery-key.txt <sha256>` proves the stored key is the
  right one without printing it; do it once after setup, before trusting backups.
- The vault is the user's own recovery path only. The partner's key arrives through pairing, not the vault.
- Stack asks for the recovery key by name when restoring; the restore/setup app (below) walks the user through
  fetching it from the vault, and the setup manifest lists the vault's location.
- The vault is a desktop tool, so the first recovery key is shown on the phone and entered by hand in a terminal.
  If Stack Desktop creates the key instead, it can pipe it straight into `keyvault protect`.

### Partner devices
The partner has **Android and Linux**, the same as the owner. So every slice that touches the partner must work
on both: the partner publish and import (slice 7) and `Open` on desktop (slice 6) are both required, not optional.

### Desktop Open (feasibility findings, not yet tested)
- Stack's Flatpak (`org.stackdocs.desktop`) has access only to its own data and config folders. It cannot read a
  file in a Filen sync folder, and it cannot start another program on the host.
- AmiPro-Linux installs natively (`packaging/install.sh`, MIME type `application/x-amipro-document`).
- **AmiPro-Linux takes no file argument today**; File > Open goes through the desktop's file portal. Stack cannot
  say "open this document" until it can. This is new work in AmiPro-Linux (accept a file path or portal handle on
  the command line, and an "already running" single-instance hand-off).
- Candidate ways for Stack to launch it, to test in this order:
  1. `org.freedesktop.portal.OpenURI.OpenFile` with a handle: no new Flatpak permission, the host opens its
     default handler for the MIME type. Open question: whether it edits the original (via the document portal) or
     a copy.
  2. A one-time folder grant (`--filesystem=` for the Filen sync folder, or the file-chooser portal) so a path
     works and edits reach the original.
  3. `--talk-name=org.freedesktop.Flatpak` and `flatpak-spawn --host`. Works, but it removes the sandbox; use only
     if 1 and 2 cannot work, and only with the user's agreement.
- How the partner's Linux machine gets files from Filen (the Filen desktop client's sync folder, or the web) is
  not known and decides option 2.

**Results (2026-11-22).** Tested with the test Flatpak `org.stackdocs.desktop.test` (its own permissions only) and a
synthetic .sam in its data folder, Ami Pro Linux 0.1.1+git90a5767 already running on the host.
- Option 1 works with no new permission. From inside the sandbox, `org.freedesktop.portal.OpenURI.OpenFile`
  (parent window `''`, an fd opened read-only on the file, options `{}`) returned OK; the host default handler for
  `application/x-amipro-document` (Ami Pro Linux) was launched, handed the file to the running window through
  `DbusActivation.ActivateAction("open", [path])`, and it opened as a tab there (no second window).
- Ami Pro saw the real host path (`/home/<user>/.var/app/org.stackdocs.desktop.test/data/NAME.sam`), not a
  `/run/user/1000/doc/...` copy, because the file is in the app's own data folder. Edit and Save changed the
  ORIGINAL (hash changed); `NAME.sam.bak` was created next to it holding the pre-edit bytes; no loss-check dialog blocked.
- Not tested, and the open point: a file outside Stack's own folders (for example a Filen sync folder) can only be
  passed if Stack already holds an fd for it (file-chooser portal, which then gives a document-portal path).
  Option 2 is therefore needed only for that, once, per folder; option 3 is not needed.

### Restore and setup (later, a separate project)
After a new phone or computer, the records return but their locators are dead. Two parts:
1. **In the contract:** a restored record with an unreachable locator is `unreachable` (never `missing`), shows
   "Set up again", and relinks by Filen UUID or by `hash` once the owner app and Filen are back.
2. **A restore/setup app (proposed, not designed):** reinstalls Stack, AmiPro and Filen Files from their release
   pages (each already has an update checker), restores the latest Stack backup, signs in to Filen, re-grants
   folders and runs `Check linked documents`. It needs a setup manifest listing the apps, package names,
   certificate hashes and backup locations; the contract's pinned-certificate list can double as that manifest.
   Decide whether this is its own repo before building any of it.

### Existing documents
The reference set is for development only. The user's documents move from Google Docs to Ami Pro one at a time, with a
review, so there is **no bulk import**. Each is shared by hand. A "Save a copy" is a new file and gets its own
record only when shared; the `hash` check warns when an identical file is shared twice.

### Version check
Every call carries `contract`, a whole number. If the two apps' numbers differ, the newer app uses the older shape
when it can; otherwise the user sees "Update Stack" or "Update AmiPro". Unknown fields are never silently dropped.

## Open questions

1. **Does AmiPro keep a durable map from `link_id` to file?** If the user moves a `.SAM` in a file manager
   outside Filen, nothing reports it; only a content hash search can find it again (propose: owner rescans its
   known folders for the same hash and name when `stat` fails).
2. **Preview fidelity (audited 2026-11-21, corrected).** The phone app already prints tables, pictures and text
   frames (AmiPro STATUS, "better print"), so its PDF is a usable preview for most documents. What it cannot draw
   is drawings, charts, equations and metafiles (15% of the reference set), columns, frames fixed on the page and
   footnotes. **Decided 2026-11-21:** drawings, charts, equations and metafiles hold nothing Stack needs to search,
   so they are not extracted or drawn. The preview labels them ("contains a drawing") and Open gives fidelity.
   Columns, fixed-position frames and footnotes stay open until a real document shows they matter.
3. **Spreadsheets and notes:** is `text` (flattened cells) enough, or does search need to know sheet names?
4. **Partner transport:** a published folder of records is assumed. Confirm Filen Files can notify Stack on the
   partner's phone when the folder changes, or accept a manual "Refresh". On the partner's Linux machine the
   same question applies to the Filen desktop client.
5. **One owner, many apps?** If two apps can edit the same MIME type, `owner` picks which opens it.

## Audit result: what real Ami Pro documents contain (2026-11-21)

Read-only count over the 424-file reference set (417 opened, 1 password-protected, the rest unreadable); no
names or text were read out. Test: `reference_object_audit` in AmiPro-APK `crates/amipro-mobile`.

| Measure | Documents |
|---|---|
| Plain text only (no table, frame, picture or drawing) | 225 (54%) |
| With a table | 141 (34%) |
| With a text frame | 41 (10%) |
| With a picture | 11 (3%) |
| With a drawing, chart or equation | 61 (15%) |
| Text inside tables or frames | 142 (34%) |
| Table text is more than the body text | 58 (14%) |
| Body text empty | 45 (11%) |
| Tables the extractor could not read | 0 |

By characters (non-space): body 1,597,091; tables 164,551 (about 9%); text frames 24,901 (about 1.4%).

What it means:
- **Search must read tables and frames.** A body-only index would miss searchable text in about a third of
  documents and most of the text in 14%. Extraction works on every table found, so this is solved on the owner side.
- **The preview is mostly there.** AmiPro's print already lays out tables, pictures and text frames. The gap is
  the 15% with a drawing, chart or equation (drawn as a labelled box), plus columns, fixed-position frames and
  footnotes. Stack should label such a preview "contains a drawing" and rely on Open for fidelity.
- Caveat: this is the existing reference set, not the user's new medicine and doctor lists. Check one of those too.
