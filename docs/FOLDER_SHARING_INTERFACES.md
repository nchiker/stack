# Folder sharing: interfaces between the apps (agreed 2026-11-26)

Frozen for parallel work. Design and reasons: `FOLDER_SHARING_DESIGN.md`. Measurements: `FOLDER_SHARING_PHASE0.md`.
A change to anything here goes through the coordinator (the Stack session that owns this file) before code changes.
Names are exact. "Pinned caller" = Filen's existing package + release-certificate pinning in `StackLink.kt`
(Stack `org.stackdocs.app`, Ami Pro `app.amipro.editor`, plus the build-time test package).

## I1. `amipro-text` (AmiPro-Linux, new crate `crates/text`, package `amipro-text`, MPL-2.0)
```rust
pub const VERSION: u32 = 1;                 // bump when extracted text changes for the same input
pub struct Limits { pub max_bytes: usize, pub max_chars: usize }
impl Default for Limits { /* max_bytes 8 MiB, max_chars 1_000_000 */ }
pub enum Kind { Native, Sam, Protected, NotAmiPro }
pub fn sniff(head: &[u8]) -> Kind;          // head = first ≤ 4096 bytes; content only, never the file name
pub enum Reason { TooLarge, NotAmiPro, Damaged }
pub enum Extracted {
    Text { title: Option<String>, text: String, truncated: bool },
    Protected,
    Unreadable(Reason),
}
pub fn extract_text(bytes: &[u8], limits: &Limits) -> Extracted;
```
- Text order: main flow, then the other flows (frames, tables, notes), one line per paragraph: same as Stack desktop's
  `local_text.rs`, which is replaced by this function.
- `Protected` from the `[encrypt]` first line, without a password. Never returns text for protected files.
- Must not panic, hang or exceed ~128 MB peak memory for an 8 MiB input (revised after the security review: small
  line-heavy files can reach 30–50× their size, bounded by the caps; note nesting ≤ 4; at most 4,096 styles,
  100,000 paragraphs, 20,000 flows, 10,000 frames; over a cap → `Unreadable(Damaged)`; `VERSION` 2 since AmiPro-Linux
  6d3623b). Earlier wording: no allocation far beyond `max_bytes × 16` on any input; tests with synthetic bad inputs (empty,
  random, truncated, huge declared sizes, deep nesting). Depends only on `amipro-document` and `amipro-formats`.

## I2. Stack native library (Stack repo, Android, `libstacknative.so`, arm64-v8a)
Rust crate in Stack (`app/src/main/rust/stacknative`), built by the Gradle build with the NDK; vendors `amipro-text`
and its two dependencies at a pinned AmiPro-Linux commit (like `desktop/third_party/amipro`). JNI, Java side:
```java
package org.stackdocs.app.types;
final class NativeText {
  /** [status, title, text]; status is "text", "protected", "too_large", "not_amipro" or "damaged". */
  static native String[] amiPro(byte[] bytes, int maxChars);
  static native int version();               // = amipro-text VERSION
}
```
Native panics are caught (`catch_unwind`) and returned as "damaged". The PDF text-layer reader is added to the same
library later, behind its own interface (I3 `PdfText`); its implementation is decided in the Stack registry work.

## I3. Stack type registry (Stack repo, Android, package `org.stackdocs.app.types`)
```java
interface FileType {
  String id();                    // "pdf", "jpeg", "png", "webp", "stack-scan", "amipro", "text"
  int version();                  // bump → files of this type are read again
  java.util.Set<String> extensions();   java.util.Set<String> mimeTypes();
  boolean matches(byte[] head, String name, String mime);   // content first; name/MIME only as a hint
  Limits limits();                // maxBytes, maxPages, maxMillis
  Extracted extract(Source in, Limits l, android.os.CancellationSignal c) throws java.io.IOException;
  Preview preview(Source in, int maxPx) throws java.io.IOException;   // null = no preview
  Opener opener();                // STACK_VIEWER or an app package to open the original
}
final class Extracted { enum Status { TEXT, PROTECTED, UNSUPPORTED, UNREADABLE, TOO_LARGE }
  Status status; java.util.List<String> pages; String reason; }   // reason: plain words for the user
final class FileTypes { static java.util.List<FileType> all(); static FileType match(byte[] head, String name, String mime);
  static String fingerprint(); }   // ids+versions, stored with each indexed file
interface PdfText { String pageText(android.os.ParcelFileDescriptor pdf, int page); }  // null = no text layer
// Implemented by NativePdfText (libstacknative, Rust module `pdftext`, no third-party code). The handler asks once per file:
//   String[] pageTexts(pfd, maxPages, maxChars, budgetMillis)  // entry null = page needs OCR; null overall = whole file by OCR
// Hostile-input bounds: input size, decompressed bytes, nesting, page-tree nodes, operators, wall clock; panics caught.
// pdf handler version() = 2000 + reader version (so existing rows are re-read); other ABIs have no reader (OCR only).
```
Indexer, search, list screens and sharing use only `FileTypes`. A new handler is one class added to `all()` plus its
synthetic files under `app/src/androidTest/assets/types/<id>/`; `TypeChecks` (flag `-e types true`) runs every handler
against its files and against bad input. Existing per-type branches (list in `FOLDER_SHARING_PHASE0.md` §1) move behind it.

## I4. Filen → Stack (Filen repo; Storage Access Framework, authority `app.filenfiles.documents`)
- **F1 Cold start.** Any `filen-in:<uuid>` (and `filen:`) item under a granted tree resolves at any depth after
  Filen's process died, online, without Filen's UI: the parent chain comes from the server when not in memory, and
  `sharedParents` is persisted. Errors: `FileNotFoundException` only when the server confirms the item or its share
  is gone; `AuthenticationRequiredException` when signed out or locked; `IOException` for anything else (offline,
  timeout). `SecurityException` only for an item really outside the tree.
- **F2 Freshness.** Every `queryChildDocuments` cursor carries extras `app.filenfiles.extra.FRESH` (boolean: listed from
  the server in this call) and `app.filenfiles.extra.ACCOUNT` (string, I4 F6). Offline last-listing answers say false.
  The last listings and parent map persist on disk (so offline after a process death answers stale, not an error).
- **F3 Version column.** Extra column `app.filenfiles.version` (opaque string; changes whenever the content changes).
- **F4 Shared by me.** New root `filen-out` ("Filen – Shared by me", `FLAG_SUPPORTS_IS_CHILD`, read only) listing items
  this account shares, ids `filen-out:<uuid>`.
- **F5 Change notice.** `app.filenfiles.action.SHARED_CHANGED` (extra `app.filenfiles.extra.TREE_URI`) and
  `notifyChange` on the tree's children URI for every tree a pinned caller holds a persisted grant on (drive,
  shared-with-me, shared-by-me). One log line per run without names or ids (`SharedWatcher: n trees, m changed`).
- **F6 Account.** `call("account")` on `app.filenfiles.stacklink` for pinned callers, always on (not behind the
  pairing flag): returns `account_id` = an opaque stable id for the signed-in account (not the email), or error
  `signed_out`.
- **F7 Share sheet.** Activity action `app.filenfiles.action.SHARE_WITH_PARTNER`, pinned callers only. Input: data URI
  = a `content://app.filenfiles.documents/document/filen:<uuid>` (or tree-document URI), or extra
  `app.filenfiles.extra.FILEN_UUID`; optional boolean `app.filenfiles.extra.STOP`. Filen shows its own confirm sheet
  (no silent share) with the partner preselected; if there is no partner yet it offers "Add partner" (F8) first.
  Result: RESULT_OK with boolean `app.filenfiles.extra.SHARED`, or RESULT_CANCELED. Works for files and folders.
- **F8 Partner setup.** Activity action `app.filenfiles.action.PARTNER_SETUP`, pinned callers only. Shows one screen:
  enter the partner's Filen email → send the request; or an incoming request "Accept [name]" → accept. Then, for the
  calling app, asks to let it read "Shared with me" (and "Shared by me") and returns those persistable READ grants as
  result clip data (`FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION | FLAG_GRANT_PREFIX_URI_PERMISSION`).
  Result extras: `app.filenfiles.extra.PARTNER_NAME` (display name, not email), `app.filenfiles.extra.STATE`
  ("connected" | "requested").
- **F9 Partner status.** `call("partner_status")` for pinned callers, answered while Filen is locked too (like `contract`:
  only opaque ids and a display name, no file access). Any `state` other than "connected" means not connected. Returns `state` ("none" | "requested" | "incoming" |
  "connected"), `name` (display name, when known), and `partner_id` (only when `state` is "connected"): an opaque, stable
  id of the saved partner contact (HMAC with the same per-install secret as `account_id`, over the contact's uuid; never
  the email). It changes only if the partner is removed and added again or Filen is reinstalled. Stack uses it for the
  "[name] shares files with you" card and to tell partners apart.
- **F10 Share state** (added 2026-11-26, additive). `call("share_state")` for pinned callers with extra
  `app.filenfiles.extra.FILEN_UUID` (file or folder) → `shared` (boolean: shared with the saved partner), or error
  `not_found` / `signed_out` / `unknown` (offline). Stack's Share switch and Ami Pro's "Stop sharing" read it instead of
  remembering shares themselves (until then Ami Pro keeps its own list of shares it made).
  Extended: also `shared_via` (string, the name of the nearest shared ancestor folder) when the item itself is not
  shared but a parent folder is; `shared` stays false in that case. Callers show "Shared with [name] in [folder]".
- **F11 Scan folder** (added 2026-11-26). Activity action `app.filenfiles.action.CREATE_SHARED_FOLDER`, pinned callers
  only, extra `app.filenfiles.extra.NAME` (suggested folder name). Filen shows one confirm sheet "Create '[name]' in
  your Filen and share it with [partner]?", creates the folder in the drive root (or reuses an existing folder of that
  name that is already shared with the partner), shares it, and returns a persistable READ+WRITE tree grant on it
  (clip data, PREFIX) with RESULT_OK; RESULT_CANCELED with `ERROR` otherwise. No partner → partner setup first (F8).
  Stack writes the shared scans there as PDFs.
- **F12 Contract level** (added 2026-11-26, before release). `call("contract")` on `app.filenfiles.stacklink`, open to
  pinned callers even when Filen is locked or signed out: returns `level` (int; 1 = F1–F11 as specified here). A Filen
  without the call (0.4.9 and older) counts as level 0. Stack and Ami Pro check it before showing any sharing UI; below
  level 1 they show "Update Filen Files to share with your partner" with an Update button that opens Filen (whose own
  in-app updater installs the new version) or, if Filen is missing, the Filen release page. Nothing fails silently.
  A call that gets no reply at all (Filen is starting, timeout) is not level 0: Stack asks again after 1, 3 and 8 seconds, never keeps a
  failure, and shows "Update Filen Files" only after Filen answered with a level below 1 (or answered with an error, as 0.4.9 and older do).
  While Filen is still silent the sharing screens say "Filen Files is not answering yet" with an Open button.
- The existing pairing code (`GRANT_SHARE_FOLDER`, `GRANT_RECEIVED_FOLDER`, `-PpairingEnabled`) is replaced by F7–F9 and
  removed; `GRANT_BACKUP_FOLDER` and `OPEN_SHARED` stay.

## I5. Ami Pro → Filen
Menu item **Share with [partner]** on an open document whose record has a `filen` locator: start F7 with
`app.filenfiles.extra.FILEN_UUID`. No `filen` locator → "Save it to Filen first". Nothing else changes in the link
contract; Stack shows one result when a link record and a folder file have the same Filen UUID.

## I6. Removal rules (all apps)
Nothing is removed because of an error, a stale (`FRESH=false`) listing, a different `account_id`, or a missing grant.
A file is removed only when a FRESH listing of its parent omits it and either a FRESH lookup says
`FileNotFoundException` or a second FRESH scan ≥ 10 minutes later still omits it. Same-parent same-name replacement
within one scan keeps the user's stacks and tags (save-over gives a new id).
