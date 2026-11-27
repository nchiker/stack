# Folder sharing, phase 0: facts checked against the code (2026-11-26)

Companion to `FOLDER_SHARING_PLAN.md`. Read-only check of Stack (phone 0.20.0 / desktop 0.7.1), Filen 0.4.9,
Ami Pro 0.6.1 and AmiPro-Linux 0.1.3. **Nothing was run on a device**; "tested" below means code tests already in the repos.

## 1. Supported file types (from the code)
| Type | How recognised | Text today | Preview | Opens in |
|---|---|---|---|---|
| PDF | content `%PDF-` (Stack `LibraryRepository.java` ~243) | Phone: OCR only (Tesseract 5.5.1, English) after rendering at 2400 px; no embedded-text read. Desktop: embedded text via PDFium, OCR fallback | PdfRenderer / PDFium | Stack |
| JPEG, PNG, WebP | BitmapFactory decoded MIME | OCR | yes | Stack |
| Stack scan | internal MIME `application/x-stack-scan` | OCR | yes | Stack |
| Ami Pro `.SAM` | Ami Pro: anything not native is tried as SAM (lenient, `[ver]` only warned) | Phone Stack: **only from Ami Pro** via link record. Desktop Stack: reads it itself (vendored `amipro-formats`, `local_text.rs`) | PDF made by Ami Pro (link records only) | Ami Pro |
| Ami Pro `.amipro` | magic first line `AMIPRO-LINUX/3` (plain UTF-8 text, not a zip) | same as `.SAM` | same | Ami Pro |
| Protected `.SAM` | first line `[encrypt]` (no password needed to detect) | none | none | Ami Pro |

Not supported anywhere: plain text (`.txt`), Word, anything else. The plan's "check plain text": it is not a type today.
Type branches that a registry would replace: about 10 places on the phone (import sniffing, renderer, pickers, archive
and sync MIME whitelists, link preview naming) and about 10 on the desktop (render, text, interchange, archive, file chooser,
`ami_open.rs`, `local_text.rs`). Full list in the phase 0 notes below.

## 2. Plan statements that are out of date
- "Ami Pro text is produced by Ami Pro itself": true on the phone only. **Stack desktop already contains the Ami Pro
  reading core** (`desktop/third_party/amipro`, MPL-2.0) and reads `.sam`/`.amipro` itself.
- "Partner's own stacks and tags on partner records are kept": **not implemented**. Stacks and tags attach only to
  `documents` rows; link records have none (`STACK_LINK_CONTRACT.md:186` says so too).
- "Option B reuses publish/import": the publish/import moves one archive file. There is no recursive folder walker,
  stable-ID tracking or change tokens in Stack today; both options need a new indexer.
- `PROJECT_ROLLUP.md` says slice 7 not started: it shipped in 0.19.0/0.20.0. `RUNBOOK.md` §0, §1, §3, §7, §10 stale
  (desktop version, manual Refresh, "PartnerJoinActivity not built" — it exists).
- Filen: commit 7a6d294 says "unreleased" but is in 0.4.9. `STATUS.md` says existing files are read-only in the provider
  (0.4.9 added save-over for own files). Pairing (`GRANT_RECEIVED_FOLDER`/`GRANT_SHARE_FOLDER`) is still behind
  `-PpairingEnabled`, off in 0.4.9, never device-tested.
- Ami Pro: `STACK_LINK_HANDOFF.md` says nothing exists on the Ami Pro side (link is released in 0.6.1); `STATUS.md`
  sizes and "unreleased" lines are stale. The parser lives in a fourth repo, `~/AmiPro-Linux`, reached from
  AmiPro-APK through a git-ignored symlink.

## 3. Who reads the files: A (partner's Stack reads the shared folder)
Evidence from the code (Filen `FilenDocumentsProvider.kt`, `FilenStorageProvider.kt`, `SharedWatcher.kt`):
- Filen exposes a "Filen – Shared with me" root with `FLAG_SUPPORTS_IS_CHILD`, and `isChildDocument` resolves
  received items, so a folder inside it can be picked with the standard folder picker and kept as a persistable grant.
  The pairing build flag is not needed for that.
- Document IDs are Filen UUIDs (`filen-in:<uuid>`), stable across rename and move. Columns: name, MIME, size,
  last modified. No hash or version column.
- Read-only is enforced by the provider for received items (write modes throw).
- Change notice already exists: `SharedWatcher` fingerprints each granted received tree (socket event, app foreground,
  6-hourly) and sends `SHARED_CHANGED` only to Stack; Stack already listens for it. Not device-tested.
- Offline / signed out: signed out throws; offline returns the last listing Filen saw, or throws if it has none. It
  never invents an empty listing, but a stale listing is not marked stale.
- Opening downloads and decrypts the whole file to Filen's cache (1 GiB LRU) and returns a read-only descriptor.

Why B does not save work: the owner's files are in Filen, not in the owner's Stack. Under B the owner's Stack still has to
walk, download and read the whole folder (the same indexer), then also needs pairing, a key and the archive. B only moves
the cost to the other phone, and adds a second copy of the text plus key handling. A needs no QR, key or archive:
Filen's own sharing does access, encryption and unshare.

Risks of A that must be fixed or tested (phase 2, Filen):
1. Cold start: after Filen's process dies, nested received folders may fail ("reopen it from the Shared tab") until
   resolved; `resolveShared` partly addresses it. Must work without the user opening Filen.
2. Stale offline listings are unmarked. Needed: a freshness signal (e.g. cursor extra) so Stack removes nothing unless
   the listing is known fresh, plus confirm-before-remove.
3. "Save over" of a received file by the owner may change its UUID (unverified): treat as delete+add without losing
   the partner's stacks/tags, or match by path+name.
4. Cost: the partner downloads every file once (then only changed ones). Phone PDF text is OCR-only today, slow for
   born-digital PDFs. **Not measured.**

## 4. Ami Pro text for a file never shared from Ami Pro
Embed the same reading core in Stack's phone app that the desktop already uses: `amipro-formats` + `amipro-document`
(pure Rust, no third-party dependencies, no `unsafe`, MPL-2.0). One function, owned in AmiPro-Linux so phone, desktop and
Ami Pro share it: `extract_text(bytes) -> Text | Protected | Unreadable` (logic as `local_text.rs`), run with a size cap,
time limit and panic catch. Protected files: name only. No one has to open each file in Ami Pro. Cost: Stack's phone
build gains a small Rust/NDK library (packaging change, invisible to the user).

Measured on this computer (release build of the desktop's exact extract logic, synthetic files only):
10 KB file 3 ms / 1 MB 33 ms / 10 MB 344 ms; peak memory about 13x the file size (130 MB for 10 MB).
Bad input (protected, random bytes, truncated, empty, broken `.amipro`): returned "none" every time; no panic, no
hang. Added size about 1.3 MB (x86-64 proxy; Android not built). Consequence: the phone handler needs its own size
cap well below the desktop's 32 MB (memory), e.g. 8 MB, with larger files indexed by name only. Not tested:
layout-heavy real-world files, and the phone itself. Not chosen: asking the
partner's Ami Pro app for text (requires Ami Pro installed and running on the partner's phone).

## 4b. Device results so far (test phone, profiles 10/11, 2026-11-26)
Blocked: both new junk Filen accounts refuse every upload and folder creation with `max_storage_reached` (empty
drives). So no files, shares or contacts yet; A1, A3, S1, B2, B3, B4, B6 not run.
- **B1 PASS (empty):** in the partner profile, the folder picker lets Stack choose the whole "Filen – Shared with me"
  root ("Use this folder" enabled); the grant persists (`tree/filen-in:root`) and lists (216 ms). The drive root works
  the same way. Note: the picker gave read+write; Stack must request read only.
- **B5 PARTIAL:** after a phone restart both grants were still held and listings worked from a background job without
  opening Filen (535 ms cold). Only empty roots tested; nested shared folders not yet.
- **A4 PARTIAL:** offline, an already-seen listing came back from cache after a 7.3 s wait (not marked stale); a
  never-seen listing after Filen was stopped gave the client a null cursor (Filen logged `IOException`). Both must count
  as "unknown", never as empty.
- **OCR on the phone (Tesseract, Pixel 4a), sparse synthetic pages:** about 0.25–0.36 s per page or image plus
  0.12–0.16 s render. Dense real pages will cost more. Download speed not measured.
- Filen facts found: a contact request can only be sent from a file's Share dialog, and there is no "accept" screen
  (the receiver adds the sender back). `SHARED_CHANGED` goes only to Stack's package (plus a build-time test package).

### Second run (new junk accounts, 60 synthetic files, 137 MB, 3 levels)
- **A1 walk PASS:** 4 folders / 59 files: 3.2 s cold, 1.2–1.4 s warm (~0.3 s per folder warm, up to 1.9 s cold).
- **A2 download PASS:** all 59 files in 54 s; ~0.4–0.9 s fixed per file plus ~0.15–0.25 s per MB.
  **OCR:** a dense image-only page (572 words) took **5.6 s**; 100 such pages ≈ 9.5 min. Sparse pages ~0.3 s.
  The phone reads no PDF text layer today, so born-digital PDFs would be OCRed needlessly.
- **A3 PASS:** rename then move to another folder kept the same document ID.
- **A4 offline:** with Filen running, cached listings (1.5 s) and already-downloaded files open. **After Filen's process
  dies, offline: a nested folder or file under the grant gives `SecurityException: not a descendant`**, the root a
  null cursor. A client cannot tell this from a revoked grant. Filen must keep its parent map/listing cache on disk.
- **Account replaced:** grants on the roots kept working against the new accounts with no error (root IDs are constant
  strings). A Stack that saw a fresh, empty "Shared with me" after an account change would think everything was unshared.
- **S1 FAIL:** the released Filen app can send a contact request (Share dialog) but **cannot accept one**: accepting
  exists only in the pairing screens behind `-PpairingEnabled` (`FilenStorageProvider.kt:395-401`, `PairingActivities.kt`).
  Mutual requests do not auto-accept. B1 with content, B2–B6 not run.

### Third run (contacts accepted in Filen's web app: Contacts → Requests)
- **Shares PASS:** junk1 shared folder "Probe Shared" and one file from the Filen app's Share dialog.
- **B1 PASS:** in the partner profile, ONE grant on "Shared with me" lists both the shared folder and the single shared
  file. IDs `filen-in:<owner's uuid>`. Recursive walk (5 folders, 60 files, 137 MB): 1.8 s; downloading all 59
  files: 42.5 s (~0.2 s per file + ~0.21 s per MB), no failures. The picker grants write even when only read is asked;
  Filen refuses writes to received items anyway.
- **B2 FAIL (Filen fix needed):** after Filen's process is stopped, a folder or file **two or more levels down** in a
  share gives `SecurityException: not a descendant` (online and offline) until its parents are listed again in the
  same process (`FilenStorageProvider.resolveShared`/`parentOf` only know parents from earlier listings). A full
  top-down walk works; opening a nested file straight from search does not.
- **B3 PASS:** an add and a delete by the owner showed in the partner's listing after ~31–37 s with Filen running (a
  cache), and at the next listing (6–10 s) when Filen had been stopped. No `SharedWatcher` log line was seen; its
  broadcast cannot reach a test package, so it is unverified.
- **B4:** a save-over by the owner gives the file a **new ID** on the partner's side (old ID errors). Looks like delete +
  add; stacks/tags on it would be lost unless matched.
- **B5 PARTIAL:** after a reboot the partner's grant is still held and a full walk works (9.8 s cold); direct nested
  access fails as in B2.
- **B6 PASS:** an unshared file disappeared from the partner's fresh root listing within 10 s. Its old ID gives the
  same `SecurityException` as B2, so errors can never be used to detect removal.
- After a reboot the phone sits on the owner's lock screen and profile switching fails (`Error: 4`) until someone
  picks a test profile on the lock screen.

### Filen I4 device tests (build 9a44b93 + c66e4d9, profiles 10/11)
PASS: F1 cold start at depth 3 (owner and partner, the phase-0 B2 case), offline after process death (`FRESH=false`
listings, cached opens), gone vs offline (`FileNotFoundException` online through `open`; offline null), F2
`FRESH`/`ACCOUNT`, F3 version, F4 Shared by me, F7 share/stop/cancel, F8 setup with READ-only persisted grants,
pinned-caller refusal. PARTIAL: F5 change notice reaches Stack on the owner side (~9 s) and on the partner side only
when Filen's UI starts (not from the background provider process): **Stack must not rely on it**; F6/F9 states
`requested`/`incoming`/`signed_out` not run (unit-tested). NOT RUN: the F1 top-down fallback search (the parent map was
already on disk), app-locked Filen serving Stack's grants. A cold `stat` of a gone item gives a null cursor; only
`open` throws a typed `FileNotFoundException`.

## 5. Not tested / needed from the user
- No device run: test phone not connected. Needed for: folder-picker grant on a received folder, cold-start behaviour,
  recursive listing time, download + OCR + SAM extraction time per file on a realistic synthetic folder.
- A received share needs a **second Filen account** (or a second profile on the test phone). Proxy tests with the test
  account's own drive are possible without it.
- Unknown from the code: whether Filen folder shares include later-added subfolders/files automatically (Filen's model
  says yes; not proven here), and what happens to IDs on unshare/reshare.
