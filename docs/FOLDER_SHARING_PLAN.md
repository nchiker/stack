# Folder sharing with a partner: short plan (2026-11-26)

Status: plan only. No code written.

## Goal
I keep my files where they already are in Filen. I share a folder with my partner once. My partner searches its
files in their Stack and opens them. It works the same in the other direction.

## Agreed user model (2026-11-26, after phase 0)
- **One place to search.** Stack search covers, in one list: Stack's own documents, my Filen folders added to Stack,
  and everything my partner shares with me. It works offline: text is kept on the phone; only opening a Filen file
  that isn't downloaded yet needs the network.
- **Files stay where they are in Filen.** Add a Filen folder to Stack where it is; nothing moves.
- **Share a folder or a file** with my partner, from **Stack, Filen or Ami Pro**: all three do the same Filen share.
  Anything I share also shows in my own Stack.
- **Scans:** a "Share with [partner]" switch on a stack. Its scans (now and later) are copied as PDFs to a Filen
  folder shared with my partner; the originals stay in Stack. Unshared stacks stay private.
- **Partner:** allows it once ("Show what [name] shares with me"). After that, new shares appear in their Stack with no steps.
- **One-time setup:** both signed in to Filen and Filen contacts (one contact request, ever).
- Who reads: option A (each phone reads through Filen). The Ami Pro reading core is embedded in Stack. See
  `FOLDER_SHARING_PHASE0.md`.

## Must have
- No moving or copying files into a special folder. No per-file "share" step.
- Setup is entirely in the apps, once, with as few steps as possible (see `CLAUDE.md`).
- Subfolders are included. New, changed, renamed, moved, deleted and unshared files update by themselves.
- Every file type Stack or Ami Pro supports. Adding a type later uses one standard method.
- A file of an unsupported type, or one that cannot be read, is still findable by name and shows why.
- Search works on the partner's phone and computer. Opening on the phone is required.
- Partner files are read-only in Stack. The partner's own stacks and tags on them are kept.

## Where things stand (check again before building)
- Stack shares only linked records, one switch each. Only Ami Pro is allowed to create links.
- A publish/import path exists (encrypted `Stack shared.stackshare`, QR key). See `STACK_LINK_SLICE7_DESIGN.md`.
  A full two-phone run is not recorded. `RUNBOOK.md` is stale about the invite and join screens.
- Folder-based sharing is mentioned in that design; no implementation was found.
- Filen has a partner-folder scope (`Filen/docs/STACK_PARTNER_FOLDERS_SCOPE.md`). Its pairing was reported built but
  off by default, awaiting a two-account test. Filen has moved on to 0.4.9, so check the current state.
- Filen has a per-item "Share with a Filen contact" action, and received shares are a documents root.

## First decision: who reads the files?
| | A. Partner's Stack reads the shared folder | B. Owner's Stack reads and publishes |
|---|---|---|
| Setup | Share in Filen; partner allows Stack to read it. May need no QR or sharing key | Pairing, key, archive folder, plus access to the files |
| Existing code | New folder indexer | Reuses publish/import; still needs a new folder indexer |
| Cost | Partner downloads and reads every file | Owner does it; partner gets text and preview |
| Unshare and privacy | Filen controls access; Stack removes its cached copy | A second copy of the text goes through the archive |

**Leaning: A**, because it is much simpler for the two people. It is not proven. Check first: whether Filen gives
Stack a lasting grant on a received folder (any folder, several folders), stable file IDs and change information,
and what downloading and OCR cost on a real-sized folder. Also decide what happens to the current per-document
sharing and to records already shared (no duplicates, no lost stacks or tags).

## Standard method for file types
One registry in Stack. Each type has one handler that declares:
- id, handler version, extensions and MIME types, plus a content check (do not trust the name alone)
- text extraction for search (with OCR where needed)
- preview, or "none"
- which app opens it
- size, page, memory and time limits, and plain-language failure reasons

Indexing, search, sharing and the list screens use only the registry; they have no per-type branches. A new handler
version re-reads affected files, including ones that were "unsupported" before. Handlers ship inside the app (no
downloaded plugins). Each comes with synthetic test files and passes a shared test suite. Android is authoritative
for extraction; the desktop receives the results through sync.

Starting types: take the real list from the code. Seen so far: PDF, JPEG, PNG, WebP, Stack scans, and Ami Pro
`.SAM` and `.amipro`. Check plain text and anything else Ami Pro or Stack opens. Ami Pro text is currently produced
by Ami Pro itself, so decide how Stack gets it for a file that was never shared from Ami Pro (shared reading core
inside Stack is preferred; requiring someone to open each file is not acceptable).

## Phases (stop for approval after 0 and 1)
0. **Check facts and feasibility:** all three repos, supported types, decision A or B with measurements.
1. **Design:** user steps screen by screen, Filen/Stack interface, registry, data changes and migration, security.
2. **Filen:** folder grant, listing with stable IDs, change notice, open a shared file.
3. **Stack:** registry and handlers, background indexer (resumable, only changed files, progress shown), search,
   removal, Share/Receive screens, desktop sync.
4. **Ami Pro:** only what phase 1 shows is needed.
5. **Test:** two Filen accounts on two phones or profiles with synthetic files, then the real partner run.

## Security and safety
- Shared files are untrusted input: enforce limits, and one bad file must not stop or crash indexing.
- Password-protected files: name only. No text or preview without an explicit decision by the owner.
- A failed or partial listing (offline, signed out, lost grant) must never be treated as deletion.
- Unshare or lost access removes cached text and previews once confirmed.
- Never print, log or store keys. App lock covers partner content, as it does today.
- Higher-tier security review before any release.

## Done when
On two fresh installs, using only the apps: one person shares an existing folder where it is; the other finds a word
inside each supported type and opens the file; add, edit, rename, move, delete and unshare all show up correctly.
