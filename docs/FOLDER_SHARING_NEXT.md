# Folder sharing: what to look at next (ranked, 2026-11-27)

Released: Filen 0.5.0, Stack 0.21.0, Ami Pro 0.7.0 (see `FOLDER_SHARING_REPORT.md`). In order of importance:

1. **Real use on both real phones (first).** Everything so far used synthetic files on the test phone. Watch: first read
   of a real library (time, battery, storage), and **mobile data**: Stack downloads every file in a shared folder to
   read it, on Wi-Fi or mobile data. Likely needed: a "read new files on Wi-Fi only" setting (small).
2. **Backup and sync of the new state.** Stack's backup and desktop sync cover Stack's own documents only, not the added
   folders or stacks/tags on Filen files (including the partner's files). Lose or reset a phone and that organising is
   gone. Biggest design gap for "one place".
3. **Search on the computer** (deferred by the owner: phones first). Same sync work as item 2: do them together.
4. **Signing keys.** Checked 2026-11-27: Ami Pro, Filen and Stack keys are all in the Key Vault backups (newest
   2026-11-22, verified, offsite on Filen). The owner runs a fresh "Back up now" + Verify (the live folder changed after
   the last backup, probably only README.txt). No USB copy configured (Key Vault's rule is three copies, two places).
5. **Upkeep.** Three apps, a copy of the Ami Pro reader in Stack (re-vendor from AmiPro-Linux when it changes; pin in
   `app/src/main/rust/third_party/amipro/README`), and our own PDF text reader. Add the fuzzers to the regular checks so
   a change can't reopen a crash. Run `~/Projects/Filen/tools/sdk_upstream.sh` before Filen work.
   Filen opportunity: list received trees with the SDK's `listDirRecursive` (it takes shared folders) instead of one
   call per folder.

Smaller, when convenient:
- The decided-but-unbuilt one "Update available" bar, same in all three apps (matters more now that Filen must update first).
- OCR is English only.
- What happens to stacks/tags when partners stop sharing entirely or change accounts.
- Fully isolated OCR (needs new native code: Tesseract init from memory).
- Device check of Filen's "keep adding files while locked" prompt (needs a screen-lock PIN in a test profile).
- Upstream: filen-rs #23 is unverified; #24 was wrong (see Filen `docs/UPSTREAM_REPORTS.md`). No new reports without
  a running reproduction.
