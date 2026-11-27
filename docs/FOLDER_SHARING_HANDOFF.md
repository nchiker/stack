# Folder sharing: handoff (2026-11-26, evening; updated 2026-11-27)

**Current state (2026-11-27): see `FOLDER_SHARING_REPORT.md`.** Everything below the table is history. Heads: Stack
9fae985 (0.21.0), Filen cd2b3e7 (0.5.0), Ami Pro 1d49de6 (0.7.0), AmiPro-Linux 24605e1. Versions are bumped and
release notes written; all tested on the test phone; Opus security review done and fixed. Waiting for the owner's
release go-ahead (order: Filen, Stack, Ami Pro).

For a new session picking this up. Read in order: `FOLDER_SHARING_PLAN.md` (goal and agreed user model),
`FOLDER_SHARING_DESIGN.md`, `FOLDER_SHARING_INTERFACES.md` (binding contract), `FOLDER_SHARING_PHASE0.md` (facts and
device results). Owner's standing rules are in Claude memory (`standing-autonomy-rules`, `test-profiles-junk-accounts`).

## Where the code is (all local, nothing pushed or released)
| Repo | Branch | Head at handoff | Contents |
|---|---|---|---|
| ~/Projects/Stack | folder-sharing | 58c9be3 (+ final-run commits) | type registry, native lib (Ami Pro + own PDF text reader), sources/indexer/search, `:extract` process, chunked OCR, screens, scan sharing, QR sharing retired, security fixes |
| ~/Projects/Filen | folder-sharing | 5953f5a | I4 F1–F11, security fixes (two rounds), findDocumentPath, partner screens |
| ~/Projects/AmiPro-APK | folder-sharing | eb53ef5 | "Share with [partner]" via Filen F7/F10, Filen cert pin, "Show in Stack search" rename |
| ~/AmiPro-Linux (public repo) | folder-sharing | 6d3623b | `amipro-text` crate, nesting bound and caps (vendored into Stack at this commit) |

Note: Filen's commits after 01d823d were made on a detached HEAD; the branch was moved to 5953f5a on 2026-11-26.
Check `git status` shows a branch before committing in any repo.

## Done
- Phase 0 (facts, option A chosen with device evidence), phase 1 design and interfaces (approved).
- Built and tested on the TEST phone (Pixel 4a, serial 0A121JEC203530) in profiles 10 (owner, junk1) and 11
  (partner, junk2): full "Done when" run passed (all types found and opened; add/edit/rename/move/delete/unshare;
  Ami Pro share; scan sharing; offline keeps everything).
- Security review (higher tier, approved by the owner): first review (Filen, Stack native, Stack app + Ami Pro), fixes,
  re-check, fixes of the re-check's remaining items.

## In progress at handoff
- Final run (Stack): save-over carry-over that survives listing lag (1 h records), S1–S3 from the re-check
  (part-file cleanup, ExtractClient call ids, 30-min per-file OCR budget), two small notes; then all suites in both
  profiles and an end-to-end run with the newest Filen (5953f5a). Check its commits/results in `git log` and the
  scratchpad `final/` screenshots if the session that started it is gone.

## Open, needs the owner
- Real run between the owner's and partner's phones (real accounts): not started.
- Push / release of Stack, Filen, Ami Pro, AmiPro-Linux: needs explicit go-ahead; versions not bumped, no release notes.
- Upstream reports to Filen (drafts in Filen `docs/UPSTREAM_REPORTS.md`): not sent.
- Desktop: partner/folder files are not synced to Stack desktop yet (sources are not in sync); desktop is unchanged.
- Test phone: Filen and Ami Pro test builds are installed (shared with the owner's main profile, user 0, by Android);
  profiles 10/11 and the junk accounts stay until the owner says to remove them.

## Known limits (accepted)
- `:extract` and `:render` are isolated processes (own uid: no files, DB, Keystore or network). The one exception is Tesseract: it runs in the
  separate, not isolated `:ocr` process on pixels drawn by the isolated process (Tesseract needs a directory an isolated uid cannot have).
- Partner-side change notices are best-effort; Stack also rescans on open and every 6 h.
- Android 13 phone OCR: ~5.6 s per dense page; a 100-page scan takes ~10 min, read in chunks.
