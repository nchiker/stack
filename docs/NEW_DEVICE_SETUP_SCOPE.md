# Scope: one setup and recovery path for all apps (phone + computer)

Scoped 2026-11-22 by the orchestrating session on the owner's delegation ("best end-user experience").
Owner request: a single install/recovery system that gets every app installed and set up on a new phone and computer.
The test is the same as Stack's working rule: on a brand-new device, "the app does it".

## The bar
- **New phone, computer still there:** install Stack (one download) → "Set up from my computer" → scan one QR on the computer
  → tap Install for Filen Files and Ami Pro (Android requires one tap per app) → sign in to Filen once → done.
  Library, keys, links and settings come back. No Key Vault, no folder picking, no typed key.
- **New computer, phone still there:** one install (`stack-suite`) → open Stack → scan the QR on the phone → done.
- **Both lost:** install Stack on a phone → "Restore from recovery sheet" → scan the printed recovery QR → sign in to
  Filen (one Allow for the backup folder) → library restored from the Filen backup. The computer is then set up from the phone as above.
- Updates for every app come from one place, with one "Update all" button.

## Pieces and owners
1. **Stack phone = the hub (Stack session).**
   - First run: "Set up from my computer" / "Restore from recovery sheet" / "Start fresh".
   - *Companion apps* screen: generalise AppUpdater/UpdateCheck to a **pinned list** of {GitHub repo, package,
     signing-cert SHA-256}: Stack (org.stackdocs.app), Filen Files (app.filenfiles, nchiker/Filen-Filemanager, 59ccca4c…80bf),
     Ami Pro (app.amipro.editor, nchiker/AmiPro-APK). Keep the same rules: only that repo's release assets, SHA256SUMS
     checked, Android's own signature check, a user tap per install. After download and before install, verify the APK's
     signing cert matches the pin.
   - Shows "installed / update available / not installed" for each app and offers "Update all". The other apps' own
     update checks can stay as a fallback, but Stack is the one place to look.
   - After installing, Stack walks through the hand-offs in order: Filen sign-in → GRANT_BACKUP_FOLDER (one Allow) →
     restore → Ami Pro first open.
2. **Recovery sheet (Stack phone + desktop).** At first setup, and from Settings, Stack offers a printable page (PDF):
   recovery QR + short words, what it is for, and where the backup lives. This replaces Key Vault's "Show QR for phone"
   in the user's path. Stack nags gently until the sheet is confirmed printed or saved.
3. **Device-to-device setup (Stack phone + desktop).** Reuse the existing LAN pairing/sync. The "Set up a new
   phone/computer" screen on the old device shows a QR; the new device scans it and receives the key, library,
   links and settings over the existing TLS transport, then normal sync takes over. No cloud round-trip is needed.
4. **Computer: one package (Stack session, with the Ami Pro Linux owner).** A `stack-suite` metapackage in the signed
   apt repo (https://nchiker.github.io/stack-flatpak/apt/) that depends on `stack-docs` and Ami Pro for Linux. The
   one-step .deb on the release page installs the repo, and the repo pulls the rest. Ami Pro Linux in the apt repo is
   review item #2. Developer tools (Key Vault, signing keys) are **not** part of the suite.
5. **Filen Files and Ami Pro (their sessions).** Add nothing to the hub except: support being installed by Stack
   (nothing needed beyond a normal signed APK), accept an `ACTION_SETUP` hand-off later if a first-run step needs it,
   and keep versionCode-based update checks (review item #8).

## Release gate: the new-device drill
Before any release that touches setup, run the three journeys above on a wiped profile or emulator: new phone,
new computer, both lost. Count the taps and screens. Anything beyond the bar is a defect.

## Retire the Flatpak repo (owner approved 2026-11-22)
Remove the Flatpak build and publishing. Keep the apt repo URL (it lives under stack-flatpak; rename later only with a
redirect). Tell existing Flatpak users in the 0.7.x notes to move to the .deb.

## Watch: phone signing key
Stack phone releases are signed with the *development* key (cert e873f2fd…e311). Moving to a dedicated release key
later forces every user to uninstall, which loses local data. Decide before the partner installs. Recommendation: switch
now, while there is only one user, using Android key rotation (APK Signature Scheme v3 lineage) so the upgrade stays in
place. This needs the owner's go-ahead.

**Update 2026-11-22 (Stack session):** an off-machine encrypted copy of the development key already exists (Key Vault archive on the Filen drive; see docs/STACK_ANDROID_SIGNING.md), which removes the main reason to rotate. Recommendation now: do NOT rotate unless the owner asks; if ever done, first ship Ami Pro and Filen builds that accept both certificates (their pins are lists), then Stack with a v3 lineage. No rotation is started without the owner's confirmation in the Stack session.

## Corrections from Stack (2026-11-22, accepted)
- Device-to-device setup carries the library, stacks and links through normal first sync (adopting an empty phone). It does **not** carry
  backup keys or partner keys, by design. The backup key comes from the recovery sheet. Partner sharing needs the one in-person re-pair.
- Per-install things cannot transfer: Android folder grants (so one Filen Allow per phone), the app lock, the auto-backup passphrase, and saf/owner locators.
- After a restore, links show "can't reach" until Ami Pro shares them again. Fix: "Set up again" (Stack, in progress) plus
  **Ami Pro "Share all my documents with Stack"**, a single-button batch re-share of Ami Pro's known Filen files.
- Recovery sheet QR is a bearer copy of the recovery key. Generate it straight into the print/PDF flow, never cache it, and set FLAG_SECURE.
- Companion installs: check getPackageArchiveInfo (package name, versionCode, cert) before installing. Pins: Stack e873f2fd…e311,
  Filen 59ccca4c…80bf, Ami Pro 9a387c70…868a. Stack itself is installed by hand once.

## The partner journey: "go here, do this"
The owner sends the partner **one link**: a static start page on the existing GitHub Pages site (e.g. `…/stack/start/`).
The page holds no secrets and detects the device.

**On the partner's phone** (the page shows these four steps, with a screenshot for step 1):
1. Tap **Install Stack**. Android asks once to allow installs from the browser.
2. Open Stack and choose **"I'm joining my partner"**. Stack installs Filen Files and Ami Pro (one Install tap each).
3. In Filen Files: **Create account** (new, in the app) or sign in. Filen's email confirmation is the one step outside the apps.
4. Scan the code on the owner's phone (in person), check the 4-digit code matches, then tap **Allow**. Shared documents appear.

**On the partner's computer:** tap **Download for Linux** → open the file → Install → open Stack → click **Connect phone**, then on your phone open Settings → Sync and scan the code on the computer. Done. (The computer shows the code and the phone scans it; the computer has no scanner.)

The owner's side: "Invite partner" in Stack copies or shares the start-page link, then "Pair with partner" when you're together.

### Pieces this adds
- Stack: the start page (static, inside the Pages repo next to /apt/), the "I'm joining my partner" first-run path, and "Invite partner".
- Filen Files: in-app **Create account** (SDK `register`) with a "check your email" wait that continues by itself once the email is confirmed.
- Filen 0.4.8: pairing enabled after the two-account test. Use a separate test account first, not the partner's real one.
- Ami Pro: "Share all my documents with Stack".

### Order and gates
1. Owner decision: Stack signing key. Recommendation (see the 2026-11-22 update above): do not rotate unless the owner asks, because an off-machine encrypted copy of the development key already exists.
   If the owner chooses to rotate, do it before the partner installs; Ami Pro and Filen must first accept both the old and new Stack certs. Pending the owner's decision.
2. Companion-apps screen, then first-run paths, recovery sheet and start page (Stack). Filen Create account. Filen 0.4.8 pairing.
3. Drill: run the partner journey on a wiped phone and a fresh computer, with a test Filen account. Count the steps against the list above.
4. Then send the link to the partner.

## Decisions by the Stack session (2026-11-22)
- **Companion integrity order:** SHA256SUMS asset, else GitHub's asset digest, else (neither exists, e.g. Filen Files 0.4.7) install on the pinned signing certificate + package name + higher versionCode alone (checked on the downloaded file before install; Android's own signature check still applies). A checksum that is present but wrong always refuses.
- **Certificate pins are lists** per app (ready for a later key rotation: accept old and new).

- **Computer install design:** the one-step .deb stays dependency-free (a double-clicked .deb is installed before its own repo is configured, so it cannot depend on repo packages): today's `stack-docs`, which writes the signed apt source in its postinst. The signed repo also carries `amipro-linux` (fetched and verified from nchiker/AmiPro-Linux releases) and a `stack-suite` metapackage (Depends: stack-docs, amipro-linux). On first launch Stack desktop offers "Install Ami Pro" through PackageKit (standard system install, one authentication prompt); from then on both apps update through the system updater. (To build: desktop first-run offer.)
- **Ami Pro on a new phone:** instead of "Share all", Ami Pro calls Stack's provider `list_owned` (own records only: link_id, title, hash, modified, state, filen locators; refused while the app lock is on) and rebuilds its link map by checking each Filen uuid with Filen's `lookup`; Stack's own stat then settles "Set up again" records. Adoption by filen uuid/hash remains the fallback.

## Plan of record: recovery order (owner decision, to be built after the 2026-11-28/29 weekend release)
Replaces "recovery sheet only". Order of ways back in:
1. **Phone biometrics with device PIN fallback.** Covers "this phone still works": show or reprint the recovery card, approve a new phone or computer by QR. It cannot cover a lost or wiped phone, because it only unlocks a key held on that phone.
2. **Paper card** (the recovery sheet). The real backstop for a lost phone. Parked code: local branch `recovery-sheet` (one WIP commit, not compiled or tested, in-memory PrintManager document, QR in the `stackkey1:` form the scanner already accepts).
3. **Partner, opt-in.** A slot for the partner in the owner's backups, set up in person during pairing.

Not this week: no key, format or sheet code. The design gets a security review before any code that stores the recovery key on the phone. People already set up (the owner) cannot get a printed sheet until the "make a new recovery key" step from Settings exists, because the phone keeps only derived slot keys and never the recovery key itself. "Restore from recovery sheet" on a new phone is a separate later piece.

### Design notes (Stack session, 2026-11-23; from the code and docs/STACK_ARCHIVE_FORMAT.md, nothing built or run)
**(a) Raw recovery key on the phone, sealed by a user-authenticated Keystore key.**
- Today only the two derived slot keys are kept, sealed by a non-exportable Keystore key that needs no authentication. A slot key opens only backups made with that setup's salt and cannot be shown or turned into a QR. The raw key opens backups from any salt, matches the desktop's saved key, can be shown, printed and turned into a QR. Holding it behind a biometric is therefore a real increase in what a stolen unlocked phone gives up.
- Seal it with a Keystore key that requires authentication on every use (BiometricPrompt, biometric-strong plus device credential; the combined form needs API 30, minSdk is 24, so older phones fall back to PIN only).
- Keep two records: the slot keys as today (for unattended backups) and the raw key behind the prompt (for show, reprint and approving a new device).
- A newly enrolled fingerprint invalidates a biometric-bound Keystore key by default. Then the on-phone copy is gone and paper is the way back. The alternative (accept new enrollments) lets anyone who knows the device PIN enroll a finger and read the key. Recommendation: keep invalidation on, with PIN-only as the fallback. Needs an owner decision and a device test.
**(b) Unattended automatic backups** keep working without a prompt: they use the slot keys. Only showing, reprinting or approving a new device prompts.
**(c) Passphrase optional.** The format already allows a recovery-slot-only envelope (desktop exports are that) and the phone's sealed record already has a "no passphrase" flag; today's Scan key path has no passphrase. With the raw key on paper and behind the fingerprint, the passphrase becomes a convenience. The recovery key stays mandatory. Cost: a lost card plus a lost phone means no way in.
**(d) Partner slot 3 in backups.**
- Opt-in, set up in person during pairing, keyed by a new partner key that is not the sharing key, so it can be revoked on its own.
- Exposure: the partner could open the owner's whole backup (documents, links, everything in it), far more than a share archive exposes.
- Revocation: a new backup without the slot locks the partner out of everything made afterwards. Files already made, including copies in Filen, stay readable with the partner's key; re-encrypting them needs the owner's key and time. The setup text must say so.
- Prerequisite from the format (deferred item): add `commit = HMAC(data_key, "stack-commit-v1" || header_hash)` to the header, so a slot holder cannot craft a slot that opens a different data key. That is a header format change in Java and Rust: a new envelope version, readers for both, new test vectors.
- Today's partner envelope (`encryptPartner`, one slot of type 3 keyed by the sharing key) is for share archives only and is unaffected.
**(e) Desktop** has no biometrics. The key already sits in the OS secret store, unlocked at login. For show, reprint or approve, ask for the account password in the app through the system's polkit authentication agent (which also handles a fingerprint where fprintd is set up). The desktop never shows the raw key unless asked. A keyring unlocked by auto-login makes the polkit prompt the only real gate, so treat the desktop as weaker than the phone.

### Open security questions (for the review before any key-storing code)
1. Keep invalidation on new fingerprint enrollment, or accept it? What does a PIN-only phone (no biometric) get?
2. Is a second sealed record holding the raw key acceptable at all, versus never storing it and offering only "make a new recovery key"? What is the threat from a rooted phone or malware with app-private access?
3. Should the raw key be shown on screen at all after setup, or only printed?
4. Partner slot: which key, who sets the revocation date, and is the whole-backup exposure acceptable? Is the `commit` field enough, or must the partner never be able to write to the folder?
5. Desktop: is the polkit prompt enough, given an auto-unlocked keyring?
6. Today's passphrase path: a phone set up through Scan key has no passphrase slot. Check the owner's existing backup headers (slot count) before relying on passphrase-only restore.

## Rehearsal notes (planning only; nothing run, nothing published)
Partner setup is likely the weekend of 2026-11-28/29, in person, Android phone and Linux computer. The owner is willing to have the TEST phone wiped and to use a VM here for the fresh computer. No wipe happens without the owner's explicit confirmation at that time.
- **Order:** the local rehearsal first (sideloaded APK over adb, the .deb from a local file, a local apt repo built by the tools, the start page served from a local folder). The live rehearsal only after the release approvals below.
- **Emulator as the owner (A):** the APK carries x86_64 and x86 libs and minSdk is 24, so it should install on an x86_64 emulator (not tried). The owner's code is shown by "Pair with partner" and scanned by the TEST phone's camera from the emulator screen. Needs Filen Files and a Filen test account on the emulator for the folder hand-over (an x86_64 Filen Files build is unverified); without it the partner picks a folder with the system picker. Making a test account needs the owner's approval. Unverified: how the emulator draws Stack's secure pairing screen.
- **Before a wipe:** make sure the real Stack 0.19.0 on the TEST phone holds only test data (if not, make an encrypted backup and keep its key); record installed packages and versions; record the last green device groups (RUNBOOK section 10); the Filen test account credentials stay with the owner; re-enable USB debugging and the adb prompt afterwards; set a screen lock before any Keystore-with-authentication test; take the "allow installs from this source" screenshot after the wipe, on a fresh install. Keystore data, the app lock, the auto-backup passphrase, folder grants and partner keys do not survive.
- **Partner's computer:** Pop!_OS 24.04 amd64 with COSMIC is the documented target (DESKTOP_DEB_PLAN; the .deb dependencies were resolved on Ubuntu 24.04). The partner's computer is **Linux Mint 22.3** (Ubuntu 24.04 base, Cinnamon, not COSMIC), per the owner on 2026-11-23; Ami Pro for Linux is already installed there. Everything about Mint below is untested. The "Add Ami Pro" PackageKit card is mock-tested only; double-click install and the updater offering the apt repo on COSMIC are unverified; so is the PackageKit session interface on a stock VM.
- **What must be published for the live rehearsal (each needs the owner's go):** the start page (`publish_start_page.py --push`); the apt repo with `stack-docs` 0.7.1, `amipro-linux` and `stack-suite` (`refresh_apt_repo.py`, dry run first); phone 0.20.0 and desktop 0.7.1 releases. Today the repo has desktop 0.7.0 only.
- **Ami Pro Linux in the apt repo:** a release already exists (nchiker/AmiPro-Linux v0.1.1; the sync tool's dry run found `amipro-linux_0.1.1_amd64.deb` and `SHA256SUMS`). Still needed: (1) the Ami Pro Linux project's owner confirms v0.1.1 is the build to ship and that it installs on the target computer; (2) a dry run of `refresh_apt_repo.py` (it needs network and builds `stack-suite_1.0_all.deb`); (3) the owner's go for the signed repo push; (4) a real install test of `stack-suite` on the VM. Nothing here is one of the three sessions' standing jobs today, so the owner should assign an owner for (1) and (4); (2) and (3) run from the Stack session once approved.
- **Start page and computer step (found 2026-11-23):** the computer shows the code and the phone scans it (Settings, Sync). The computer cannot scan. The start page's computer step and the line above in "The partner journey" said the computer scans; both are corrected.

## Partner's computer: Linux Mint 22.3 (read-only check, 2026-11-23; nothing tested on Mint)
- **Minimums met on paper.** The .deb needs glibc 2.39 or newer and libstdc++ 13.1 or newer; Mint 22.x is Ubuntu 24.04 based (glibc 2.39).
- **Start page:** the computer steps say "Double-click the file, then Install". Nothing on the page names COSMIC. On Mint, double-clicking a .deb opens Mint's own package installer (GDebi on some releases, Software Manager on others; which one Mint 22.3 uses is unconfirmed) with an Install button, and asks for the account password. Suggested wording if the owner wants Mint spelled out: "Double-click the file. Click Install and enter your password." Untested on Mint.
- **What does lean on COSMIC:**
  - The app is built on libcosmic (the COSMIC toolkit) and reads COSMIC's theme settings. It is a normal Wayland/X11 program that runs on other desktops, but how it looks and renders on Cinnamon (theme fallback, GPU or software drawing) is unverified.
  - The package description says "for the COSMIC desktop" (cosmetic). `Recommends: xdg-desktop-portal-cosmic | xdg-desktop-portal-gtk` is met on Mint by the GTK portal (file chooser). `gnome-keyring | libsecret-1-0` is the backup key store, normally present on Mint.
- **"Add Ami Pro?" card:** hidden when Ami Pro is installed by .deb or found on the program path or in `~/.local/bin` (fixed 2026-11-23, test added). The card also needs the system's PackageKit installer; if Mint has none, only the Settings hint appears. Either way the partner already has Ami Pro.
- **Auto-updates:** the .deb adds the Stack apt source, so Mint's Update Manager should list Stack updates. Untested on Mint.
