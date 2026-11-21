# Stack Link slice 3: link records and encryption in backups (handoff, 2026-11-21)

**Status (2026-11-21): phone side built and checked on the test phone (archiveOnly 21 groups, linkContract 4, features 15 pass).** Envelope design reviewed by a higher-tier agent before coding; spec and shared vector in `STACK_ARCHIVE_FORMAT.md`. Key handling reviewed by Opus after coding; fixed: per-setup Keystore alias (old key deleted last), secure dialogs, pasted recovery key with line breaks, dialogs after rotation, setup refused while a backup runs. Left open: an explicit confirm dialog before restoring an unencrypted archive, and showing the manifest date in the review. Not done: desktop reading (3b), a manual UI pass of the encryption screens on the phone, and the key-vault recovery drill.

Read first: `docs/STACK_LINK_CONTRACT.md` (sections "What this changes inside Stack", "Encryption of exports", "Recovery key and
the key vault", "Slice 2, Stack side: built"), then `docs/STACK_ARCHIVE_FORMAT.md`. Slice 2 (Stack side) is committed
(`d5f11c2`, `04d46ae`, `57360db`). The AmiPro side of slice 2 is being built in a separate session in `~/Projects/AmiPro-APK`.

## Goal
A backup (`.stackbackup`, manual and automatic) includes link records with their text and previews, and can be encrypted.
Restore brings them back. Until this is done, backups leave link records out and say so; slice 2 did not touch the archive.
Keep it simple: two people. Phone only in this slice; the desktop reading format 4 is a follow-up (3b).

## Current state (checked in the code, 2026-11-21)
- Archive code: `LibraryArchive.java` (`snapshot` around :80, documents enumerated at :95 with no filter, originals read per
  document, `validate` around :312-342), `ArchiveController.java` (`export` :26), `ArchiveJson.java` (strict JSON).
- Archive format 3, schema 8. Layout: `STACKBK\x01`, 4-byte manifest length, 32-byte SHA-256, manifest JSON, then entries
  (16-bit path length, path, 64-bit length, bytes). Path allowlist is only `originals/<uuid>/source` and
  `originals/<uuid>/<uuid>.jpg` (extend it for previews). Older apps reject unknown versions by design.
- Link data lives in `link_records` and `link_search` (database version 11), previews in `files/links/<id>.pdf`, text in
  `link_search.body`. `LinkStore` is the access point. They are deliberately not in `documents`, so nothing exports them yet.
- Restore rules to keep: never overwrite local data; a second import of the same file makes new records; a restore must
  leave a partly failed restore invisible (journal, owner-tagged staging, single SQLite transaction), as for documents.

## Recommended design (simple; review the crypto before coding)
1. **Two independent layers.**
   - *Inner:* archive **format 4** = format 3 plus a `links` array in the manifest and `previews/<uuid>.pdf` entries. Each
     link row carries `id, owner, link_id, kind, title, mime, modified, hash, shared, protected, locators, state,
     title_user_edited, added_at, text` (text can be large: use the same string limits as OCR text, 1 MiB). Limits: say
     1,000 links, preview 32 MiB each, count toward the 8 GiB total. Strict validation like documents.
   - *Outer:* an **encrypted envelope** around the whole inner stream with its own magic (not `STACKBK`), so every older app
     and the desktop refuses it by magic and nothing is half-read. Unencrypted backups stay plain format 4.
2. **Envelope.** Header: magic, version, KDF id and parameters, salt, then two key slots, each the 256-bit data key wrapped
   (AES-256-GCM) under (a) a key from the passphrase by PBKDF2-HMAC-SHA256 with at least 600,000 iterations and (b) the
   256-bit recovery key used directly. Body: the inner stream in chunks (64 KiB) of AES-256-GCM with a counter nonce and
   the chunk index plus a "last chunk" flag in the associated data, so truncation and reordering are detected. Nothing
   custom: platform `javax.crypto` only. The manifest and file table are inside the encryption.
3. **Keys.** Recovery key: 256 random bits shown once as 64 hex characters with a confirm-back step; stored by the user via
   the Key Vault (`keyvault protect --no-live-copy --expect-hex 64 stack/stack-backup-recovery-key.txt`), never by Stack.
   Automatic backup runs unattended: after the passphrase is entered once, keep the *wrapped data key* in the Android
   Keystore, never the passphrase. A restore on a new phone needs the passphrase or the recovery key.
4. **Policy.** Backups that contain link records are encrypted by default; Stack asks before writing an unencrypted one.
   The backup screen says link records are included and that a lost passphrase plus recovery key means a lost backup.
   (Partner key slot and the publish come in slice 7; leave room for a third slot.)
5. **Not in this slice:** sync of links, desktop 3b, Recently deleted, stacks/tags/notes for links.

## Decisions you still need to make (ask the user only if a choice is not obvious)
- Exact envelope layout and constants, written down in `docs/STACK_ARCHIVE_FORMAT.md` before code.
- Whether a restore of a link whose `(owner, link_id)` already exists updates it (same hash: keep local; different hash:
  keep local and report) or makes a copy. Recommend: keep local, never overwrite, report the skipped count.
- Where the recovery-key confirm-back screen lives in `BackupActivity`/`BackupScreen`.

## Process rules
- **Escalate the envelope design and the key handling to a higher-tier agent for a read-only review before writing code**
  (the user approved this for design or build decisions). Use cheaper agents for routine reads and edits.
- Test with `ArchiveChecks`/`archiveOnly` (8 groups pass today) and add `LinkArchiveChecks` (round trip with links,
  encrypted round trip, wrong passphrase, wrong recovery key, both slots work, truncated and reordered chunks rejected,
  tampered header and manifest rejected, a plain backup of a library without links is byte-compatible with format 3 readers
  where the spec says so, restore never overwrites). Add shared test vectors so the desktop can verify the same bytes later.
- Build and run checks as in slice 2: `./gradlew -PstackChecks -PstackChecksNamespace=linkcontract :app:assembleDebug
  :app:assembleDebugAndroidTest`, install on the test phone only (Pixel 4a, `adb -s 0A121JEC203530`), run
  `-e linkContract true`, `-e archiveOnly true`, `-e features true`. JDK/SDK paths are in
  `docs/desktop/CURRENT_RELEASE_HANDOFF.md` ("Toolchains"). The production phone is hands off.
- The AmiPro session uses the same test phone; use `adb -s` and your own package namespace, and avoid running instrumentation
  at the same moment.
- Bump the archive docs and the backup screen text; commit with the Co-Authored-By trailer; update this file's status.
