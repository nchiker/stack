# Folder sharing, phase 1 design (DRAFT, 2026-11-26)

Status: draft for approval. No product code changes until approved. Items marked **[test]** depend on the device
results in `FOLDER_SHARING_PHASE0.md` and will be corrected when those arrive. Names below: "Charlie" = owner,
"Sam" = partner (placeholders; the app shows the partner's Filen contact name).

## 1. What each person does

### Once, ever (each person)
- Filen Files installed and signed in (already true). Stack and Ami Pro installed.
- Charlie, in Stack: **Settings → Partner → Add partner → type Sam's Filen email → Send**. Stack hands this to Filen,
  which sends the Filen contact request. (Same thing is possible in Filen: Contacts → Add.)
- Sam, in Stack: a card on the search screen: **"Charlie wants to share files with you. [Show them here]"** → Filen's
  screen: "Accept Charlie as a contact and let Stack read what Charlie shares with you? [Allow]" → done. One tap
  plus Allow. Sam never picks folders, and never does anything per share. (Measured: one grant on "Shared with me"
covers shared folders and single shared files.) Filen must add accepting the contact request to this screen; today
the Filen app can only send requests.

### Charlie's own files in search
- **Stack → Folders → Add a Filen folder** → Android's folder picker opens in Filen Drive → go to the folder →
  **Use this folder → Allow**. Back in Stack: "Bills: reading 12 of 57". Subfolders included. Nothing moves.
- Repeat for each folder Charlie wants searchable (or pick a top folder to include everything under it).

### Sharing (any of the three apps, same result)
- **Stack:** Folders → tap a folder → switch **Share with Sam** → Filen's confirm sheet "Share 'Bills' with Sam? Sam
  can view and download, not change. [Share]". For a single file: search result → ⋮ → **Share with Sam** → same sheet.
- **Filen:** long-press a folder or file → **Share with Sam** (the existing "Share with a Filen contact", with the
  partner first in the list) → Share.
- **Ami Pro:** ⋮ → **Share with Sam** → same Filen sheet for the open document's Filen file. If the document is not in
  Filen: "Save it to Filen first" with a Save button.
- Anything Charlie shares also appears in Charlie's own Stack search, even if its folder was never added
  (Stack reads Filen's "Shared by me" list).
- **Stop sharing:** the same switch/menu item reads **Stop sharing with Sam**.

### Scans (live in Stack)
- **Stack → a stack → ⋮ → Share with Sam** (switch). First time only, Stack asks Filen for a folder "Stack scans
  shared with Sam" (Filen creates it and shares it with Sam in the same confirm sheet). Every scan in a shared stack,
  now and later, is written there as a PDF. The originals stay in Stack and remain searchable offline. Removing a scan
  from the stack, or turning the switch off, removes its PDF from that folder. Stacks without the switch stay private.

### What Sam sees
- Sam's search shows Charlie's shared files mixed with Sam's own, each marked "From Charlie" with the folder path.
  Tap → opens (PDF/images in Stack, .SAM/.amipro in Ami Pro read-only), downloading through Filen if needed.
- Sam can add Charlie's files to Sam's own stacks and tags; Charlie never sees that.
- Unsupported or unreadable files are still found by name, with the reason ("Password-protected", "Not a supported
  type", "Could not be read: file is damaged").
- Offline: search works on everything already read; opening a not-yet-downloaded file says "Needs the internet".

## 2. Who owns which piece
| Piece | Repo / owner session | Depends on |
|---|---|---|
| `amipro-text`: `extract_text(bytes, limits)` | AmiPro-Linux (Ami Pro session) | none |
| Filen: freshness signal, cold-start for shared trees, change notice for any granted tree, share sheet intent, partner accept+grant screen, "Shared by me" root | Filen (Filen session) | interfaces §3 |
| Stack: type registry and handlers, folder indexer, search, screens, scan-stack sharing, retire QR sharing, desktop sync | Stack (Stack session; may split registry/indexer vs screens) | §3, `amipro-text` |
| Ami Pro app: "Share with Sam" menu item | AmiPro-APK (Ami Pro session) | Filen share intent |

The coordinator (this session) owns this document and `FOLDER_SHARING_INTERFACES.md` (to be written from §3 once
approved); interfaces are frozen before parallel work starts. One session holds the test phone at a time.

## 3. Interfaces (proposed)

### Filen → other apps (Storage Access Framework, existing provider `app.filenfiles.documents`)
- Own folders: standard `ACTION_OPEN_DOCUMENT_TREE` on the Filen Drive root; persistable read grant.
- Partner content: one persistable read grant on the "Shared with me" root (`filen-in:root`). **[B1 passed on an
  empty root; recheck with real shares]** Stack requests read only (the picker otherwise also offers write). Fallback:
  Filen's own `GRANT_RECEIVED_FOLDER` screen returns that grant (no folder picking).
- "Shared by me": a new root `filen-out` listing items this account shares (ids `filen-out:<uuid>` already exist),
  readable with one grant.
- Listing results carry **`app.filenfiles.extra.FRESH = true`** in the cursor extras only when the listing came from the
  server just now. Offline/stale answers carry `false`. Errors: `AuthenticationRequiredException` (signed out/locked),
  `FileNotFoundException` (confirmed gone: server says the item or share no longer exists), `IOException` (unknown).
- Document IDs: Filen UUIDs, stable across rename and move. Columns: id, name, mime, size, last_modified, flags;
  **new column `app.filenfiles.version`** (Filen's file version UUID or modified+size) so Stack re-reads only changes.
  **Measured B4:** a save-over gives the partner a new ID. Stack matches it: within one fresh scan, an ID that vanished
  and a new ID with the same parent and name are the same file (stacks, tags kept; text re-read).
- Nested access after Filen's process died fails today (B2: `SecurityException` at depth ≥ 2). **Filen fix
  (required):** resolve any received item's parent chain from the server when it is not in memory, and persist
  `sharedParents`. Opening a partner's file from search depends on this. Until fixed, the indexer always walks top-down.
- Errors never mean "gone": an unshared or replaced item's old ID gives the same exception as a cold-start miss.
  Removal comes only from fresh parent listings (B6: unshare visible within 10 s).
- Change notice: existing `app.filenfiles.action.SHARED_CHANGED` to Stack, extended to every tree Stack holds a grant
  on (own folders too), plus `notifyChange` on the tree's children URI. Stack also rescans on app start and daily.
- Cold start: listing any folder under a granted tree must work after Filen's process died, without opening
  Filen's UI. Measured: offline after process death Filen answers `SecurityException` ("not a
  descendant") for nested items, so Filen must persist its parent map and last listings on disk. Stack treats
  `SecurityException` like any other error (nothing removed) unless the grant is missing from
  `getPersistedUriPermissions`.
- Account identity: every listing answer is tied to the Filen account (existing pinned `account` call). If the
  account differs from the one a source was indexed under, Stack pauses the source ("Filen is signed in as someone
  else") and removes nothing. (Measured: root grants silently follow an account change.)
- Contacts: the released Filen app cannot accept a contact request (only pairing-flag screens can). Filen needs an
  "Accept" for incoming requests, used by the partner setup screen below.
- Share sheet: new intent `app.filenfiles.action.SHARE_WITH_PARTNER` with a Filen document URI (or a Filen UUID from an
  Ami Pro locator). Filen shows the confirm sheet, does the share, returns RESULT_OK. Filen never shares without that
  sheet (no silent sharing by other apps). Same intent with `EXTRA_STOP=true` to stop sharing.
- Partner setup: `app.filenfiles.action.PARTNER_SETUP` (send contact request by email) and the accept+grant screen.
  Partner name: from the Filen contact. The existing `GRANT_SHARE_FOLDER`/`GRANT_RECEIVED_FOLDER` pairing build flag
  code is reused where it fits, otherwise removed.

### AmiPro-Linux → Stack (Rust library)
```
pub struct Limits { pub max_bytes: usize, pub max_chars: usize }
pub enum Extracted { Text { title: Option<String>, text: String }, Protected, Unreadable(&'static str) }
pub fn extract_text(bytes: &[u8], limits: &Limits) -> Extracted
```
Content check: native magic `AMIPRO-LINUX/3`, `[encrypt]` = Protected, else SAM import; never panics on bad input
(fuzzed; Stack still wraps it). Measured: ~34 ms per MB, memory ~13x file size, no panic on bad input, ~1.3 MB
of code; phone limit 8 MB per file (larger: name only). Same function used by Stack desktop (replacing `local_text.rs` logic) and Ami Pro.
Stack's Android build compiles it into a small native library through a fixed JNI entry. Pinned by git tag.

### Ami Pro → Stack
- The existing link contract stays for documents that are not in Filen. For a file that Stack also sees through a
  folder, Stack shows one result (matched by the Filen UUID in the link's `filen` locator).

## 4. Type registry (Stack)
One list, `FileTypes`, of handlers. Each declares: `id`, `version`, extensions + MIME types, `matches(headBytes)`,
`extract(input, limits) → Text | Unsupported(reason) | Protected | Unreadable(reason)`, `preview(...)` or none,
`opener` (Stack viewer or an app package), limits (bytes, pages, time, memory). Indexer, search, list screens and
sharing call only the registry. Starting handlers: PDF (embedded text layer first, OCR only for pages without text:
needed on the phone, where a dense page costs ~5.6 s of OCR; the text-layer reader goes into the same small native
library as `amipro-text`), JPEG, PNG,
WebP, Stack scan, Ami Pro (.SAM/.amipro via `amipro-text`), plain text (new, trivial; confirm wanted). Each file
row stores `handler_id` + `handler_version`; when the app starts with a new handler or version, matching rows
(including previously "unsupported") are queued again. Adding a type = one handler class + its synthetic test files;
the shared handler test suite runs every handler against bad input (empty, truncated, random, huge). Android is
authoritative; the desktop receives text/previews through sync and uses the registry only to label and open.

## 5. Data changes (Stack phone DB 17 → 18, desktop schema 9 → 10)
- `sources` (id, kind own|partner|shared_by_me|scan_share, tree_uri, title, partner_name, state, last_fresh_scan).
- `source_files` (id, source_id, provider_doc_id, parent_doc_id, path, name, mime, size, modified, version,
  handler_id, handler_version, state pending|indexed|unsupported|unreadable|protected, reason, text_sha,
  preview_file, missing_since). Unique (source_id, provider_doc_id).
- `source_search` (FTS4, same tokenizer as `search_index`); search queries union it with existing indexes.
- Stacks and tags on files: `item_stacks` / `item_tags` keyed by (item_kind, item_id), so documents, link records and
  source files share one mechanism (link records currently have none).
- Removal rule: a file is removed only when (a) a FRESH listing of its parent omits it and (b) a direct lookup returns
  FileNotFound, or two FRESH scans at least 10 minutes apart omit it. Unshare: a FRESH "Shared with me" listing omits
  the share → its rows, text and previews are deleted. Any error, stale answer, signed-out or lost grant: nothing is
  removed; the source shows "Can't reach Filen" / "Access lost: [Fix]".
- Indexer: JobScheduler job, resumable from `source_files.state`, only new/changed (`version`), one file at a time
  with limits, progress on the Folders screen, pauses on low battery; never stops on one bad file.

## 6. Retiring the QR sharing (nothing real to migrate)
Only trials were done. Stack: remove the Partner pairing/QR, publish and import screens and jobs; on upgrade delete
foreign link rows and `Stack shared.stackshare` handling, delete partner keys from the Keystore. The two trial Filen
shares are left alone (or removed by the user in Filen). Keep the link contract for Ami Pro.

## 7. Security
- Shared files are untrusted: per-handler limits, `catch_unwind` around native code, OCR/extraction in the existing
  worker, one bad file never stops the run. **Decided after the security review (2026-11-26):** text extraction of
  folder files runs in a separate process (`:extract` service) with a hard per-file wall-clock limit; Stack kills and
  restarts that process on timeout, crash or out-of-memory, and counts the attempt *before* starting (so a file that
  kills the reader is marked unreadable after a few tries instead of crashing Stack on every run).
  **PDFs are read in page chunks (decided 2026-11-26):** OCR of one dense page takes about 6 s on the test phone, so one limit
  for a whole scanned PDF would make every PDF over about 10 pages unreadable. Stack asks the extract process first for the
  text layer of all pages (own limit), then for OCR of the pages without text, 4 at a time, each call with its own hard
  limit (20 s per page of the chunk plus 5 s, then the process is killed). Whole-file time is unlimited; each call is not.
  Every page is stored (table `source_pages`, DB 19 → 20) when its chunk answers, so a stopped or killed run resumes after the
  last finished chunk and loses at most one. Tries are counted per page before each hand-over; a page that has been in a failed
  chunk is tried alone, and after 3 tries only that page is left out: the file stays indexed with the other pages and says
  "Some pages could not be read." The Folders screen shows "Reading page 12 of 100".
- **Isolated readers (2026-11-26, after the pre-release security review).** The `:extract` and `:render` services are `android:isolatedProcess`
  (another uid: no files, database, Keystore or network); they get only open descriptors (the file, the answer file). The same service draws one
  page at a time for the viewer as raw pixels (at most 2400 x 3400 pixels), so a partner's PDF or picture is never decoded in Stack's own process.
  Tesseract is the one exception: it starts only from a directory holding `tessdata/eng.traineddata`, an isolated uid has no directory it can write
  and SELinux refuses to hand it a directory descriptor, so OCR runs in a separate, NOT isolated `:ocr` process that receives only drawn pixels
  (never the partner's file). A native reader that reports its own timeout makes the process end itself after its answer is written.
  Scan sharing keeps a journal (DB 23, `scan_share_journal`): a file name is written down before its upload and crossed out only once its row exists
  or its delete is confirmed; Stack only ever deletes names it recorded itself (rows and journal), never by looking at a name. A new partner turns every
  scan-share switch off (the old copies are still removed unless the Filen account changed too).
- Protected files: name only. Stack gets read-only grants on partner content; Filen enforces read-only.
- Sharing always goes through Filen's confirm sheet; Stack and Ami Pro cannot share silently.
- App lock covers sources and search as it covers documents today. No keys handled by this feature (Filen does the
  encryption; the QR keys are deleted).
- Higher-tier security review before release (to be approved separately).

## 8. Build order
1. Freeze interfaces (§3) in `FOLDER_SHARING_INTERFACES.md`.
2. In parallel: `amipro-text` (AmiPro-Linux) · Filen changes · Stack registry + handlers with test suite.
3. Stack indexer + sources + search (needs Filen freshness/cold-start), then screens, scan-stack sharing, retire QR.
4. Ami Pro "Share with Sam"; desktop sync of source files.
5. Two-profile test on the test phone (profiles 10/11), then security review, then the real partner run.
