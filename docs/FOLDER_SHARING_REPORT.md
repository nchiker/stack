# Folder sharing with a partner: release report (2026-11-27)

Status: **RELEASED 2026-11-27** on the owner's go-ahead: Filen 0.5.0 (v0.5.0, nchiker/Filen-Filemanager), Stack 0.21.0 (v0.21.0, nchiker/stack, source snapshot 8ba1a85), Ami Pro 0.7.0 (v0.7.0, nchiker/AmiPro-APK). Filen upstream reports sent: filen-rs #23, #24. Last fixes before release: Stack 47077e2 (no scan writes while the partner is unknown; partner identity from the same call), Filen cb6d6a3; Opus quick check done.

## What you get
- **One search for everything.** Stack searches its own documents, your Filen folders (added once, files stay where they
  are) and everything your partner shares, in one list, also offline.
- **Share a folder or a file** with one switch in Stack, Filen or Ami Pro. Filen always asks you to confirm.
- **Your partner allows it once.** After that, everything you share shows up in their Stack by itself (new, changed,
  renamed, moved, deleted, unshared). Their own stacks and tags on your files are kept, also when you edit a file.
- **Shared scans:** long-press a stack → Share with [name]. Stack asks first; copies go to "Shared scans" in Filen;
  originals stay in Stack; stacks you don't share stay private.
- File types: PDF (text inside the PDF, and scanned pages by OCR, read a few pages at a time), JPEG, PNG, WebP, plain
  text, Ami Pro .SAM and .amipro. Protected and unsupported files are found by name with the reason. New types are
  added in one place (the type registry) without touching sharing or search.
- The old QR-code sharing is gone; on update, its trial records and keys are deleted.

## Versions ready (local branches `folder-sharing`)
| App | Version | Commit | Ahead of main |
|---|---|---|---|
| Filen Files | 0.5.0 (code 17) | cd2b3e7 | 25 commits |
| Stack (phone) | 0.21.0 (code 38) | 9fae985 | 56 commits |
| Ami Pro | 0.7.0 (code 8) | 1d49de6 | 6 commits |
| AmiPro-Linux | no release needed (Stack carries a copy of its reader) | 24605e1 | 3 commits |
Stack desktop stays at 0.7.1 and keeps working. Release notes: Filen `docs/RELEASE_NOTES_0.5.0.md`, Stack
`docs/RELEASE_NOTES_0.21.0.md`, Ami Pro `dist/RELEASE_NOTES_0.7.0.md`. Release order: **Filen first**, then Stack, then
Ami Pro. Older Filen is detected: Stack and Ami Pro say "Update Filen Files" instead of failing.

## How it was tested
On the test phone (Pixel 4a) with two test Filen accounts in two Android profiles (you / partner), synthetic files only:
- All automated suites pass in both profiles (folder reading 26 groups, screens 17, file types 28, plus the existing
  library, backup, link and sync suites).
- Full runs through the real screens several times: partner setup, every file type found and opened, add/edit/rename/
  move/delete/unshare, save-over keeping stacks and tags, partner's stacks showing your files, Ami Pro share button,
  scan sharing on/off, offline (nothing removed).
- Times on this phone: a 70-file folder is first read in about 3 minutes (mostly download and OCR); changes reach your
  partner in about 1–3 minutes (sooner when Stack is opened); a dense scanned page takes ~5.6 s of OCR.
- Release builds of all three apps: checked for no test settings and the same signing keys as the last releases
  (re-check once more at release time, on the final commits).

## Security review
Three rounds: two on Sonnet, then the higher-tier review on **Opus** (three reviewers), plus two Opus checks of the
fixes. Main things found and fixed:
- A single hostile partner file could crash Stack on every read, use gigabytes of memory, or hang it. Fixed with limits
  in the PDF and Ami Pro readers, and by reading partner files in **isolated processes** with no access to Stack's data,
  restarted for every file, with hard time limits. Re-tested with the attack files and millions of fuzzed files.
- Stack could have deleted your own files in the shared scans folder that matched its naming pattern → now deletes only
  files it recorded writing.
- Scans could stay in your partner's folder after switching off, or go to a new partner without asking → fixed
  (journal of uploads; partner checked by a stable id; sharing pauses or turns off when the partner changes or is unknown).
- Filen: locked access was per app instead of per folder; access wasn't revoked for all apps on account change;
  "Shared by me" exposed shares with other contacts; a test build could be released → all fixed.
- Erase library, backup and folder access no longer interfere with each other.

## Known limits (accepted)
- **OCR is not fully isolated.** The OCR library can only load from a folder, which an isolated process can't have. OCR
  runs in its own locked-down process that only accepts page images drawn by the isolated reader, never your partner's
  file itself. Fully isolating it needs new native code: a separate job if you want it.
- Change notices from Filen on your partner's side are best-effort; Stack also re-checks when opened, every 5 minutes
  while open, and every 6 hours.
- OCR sometimes misreads a digit, so that exact word isn't found on that page.
- After the Filen update, Filen asks again once about letting Stack read while locked, and about Stack's backup adding
  files while locked (the old setting didn't record which folder).

## Not tested (needs you)
- **Filen's "keep adding files while locked" prompt on the phone:** turning on Filen's app lock in a test profile needs a
  screen-lock PIN there, which agents don't set. Tested in code and unit tests only.
- **The real run with your partner** on your own phones and real accounts.
- Search of your partner's files on the computer: **not in this release** (your choice: phones first).

## Decisions for you
1. **Release go-ahead**, one app at a time: merge `folder-sharing` into main, then publish Filen 0.5.0 → Stack 0.21.0 →
   Ami Pro 0.7.0 (each through its normal release steps; nothing is pushed until you say so).
2. **Upstream reports to Filen** (two drafts in Filen `docs/UPSTREAM_REPORTS.md`): send or not.
3. Optional: a last quick Opus check of the final small fixes (partner state, process check, first-launch message,
   refresh rate), which were reviewed only by tests.
4. Optional, later: fully isolated OCR; computer search of shared files.
5. Test phone clean-up: keep or remove the two test profiles and junk accounts; the test builds of Filen and Ami Pro are
   also in your main profile on the test phone (Android shares apps across profiles).
