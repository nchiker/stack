# UI text review

Shortened user-facing strings in Stack phone and desktop. Word counts are before/after. Test assertions that checked old text were updated (`controller_tests.rs`, and the "already using" checks, now "already open").

| File | Before | After | Words |
|---|---|---|---|
| FilenFolder.java | Waiting for your partner to finish on their phone. Tap Try again once they have. | Waiting for your partner. Tap Try again when they are done. | 15 to 11 |
| FilenFolder.java | Sign in to Filen in Filen Files first, then try again. | Sign in to Filen Files first. | 11 to 6 |
| FilenFolder.java | Filen Drive could not be reached. Connect to the internet and try again. | Filen Drive is unreachable. Check your connection. | 13 to 7 |
| PartnerKeys.java | The partner sharing key is unavailable on this phone (its protection was lost or changed). Make a new key in Pair with partner. | The sharing key is unavailable on this phone. Make a new code in Pair with partner. | 23 to 16 |
| LinkActivity.java | Open it in Ami Pro and use Share with Stack. Stack will match it to this saved copy; your stacks and notes stay. | Open it in Ami Pro and choose Share with Stack. Your stacks and notes stay. | 23 to 15 |
| LinkActivity.java | A copy shared with you. Open it with Filen Files; the saved text and preview below are read-only. | Shared with you. Open it in Filen Files. The text and preview below are read-only. | 18 to 15 |
| LinkActivity.java | can't find this document. It may have been moved or deleted. The saved text and preview stay here; if it stays missing, Stack moves it to Recently deleted after 30 days. | can't find this document. If it stays missing, Stack moves it to Recently deleted after 30 days. | 31 to 17 |
| LinkActivity.java | This document came back from a backup, and ").append(owner).append(" does not know it yet. The saved text and preview are still here. | Restored from a backup. ").append(owner).append(" does not know it yet. | 22 to 10 |
| LinkActivity.java | right now. Nothing was removed; the saved text and preview are still here. | right now. Nothing was removed. | 13 to 5 |
| LinkActivity.java | — but this document is not in Filen, so your partner cannot open it. Move it into a shared Filen folder. | : not in Filen, so your partner cannot open it. Move it into a shared Filen folder. | 21 to 17 |
| LinkActivity.java | — the saved text and preview here are not protected by that password. | . The saved text and preview here are not. | 13 to 9 |
| LinkActivity.java | This document is not in Filen, so your partner cannot open it. It is not shared until you move it into a shared Filen folder. | Not shared yet: this document is not in Filen. Move it into a shared Filen folder. | 25 to 16 |
| LinkActivity.java | Choose a sharing folder in Settings, under Partner sharing folder, so this can reach your partner. | Choose a sharing folder in Partner sharing so your partner can get this. | 16 to 13 |
| LinkActivity.java | This preview could not be drawn, so Stack will not try again. The saved text below is still available. | The preview could not be drawn. The saved text is below. | 19 to 11 |
| LinkActivity.java | Checked: the document is there and up to date. | Up to date. | 9 to 3 |
| LinkActivity.java | is password-protected in " + LinkOpener.ownerName(target.owner) + ". The text and preview Stack saved are not protected by that password, so your partner would be able to read them. | is password-protected in " + LinkOpener.ownerName(target.owner) + ", but Stack's saved text and preview are not. Your partner could read them. | 29 to 21 |
| LinkActivity.java | moves to Recently deleted for 30 days, then it is deleted for good. The document itself is not touched; it stays in " + LinkOpener.ownerName(link.owner) + " and wherever it is stored. | moves to Recently deleted for 30 days. The document itself stays in " + LinkOpener.ownerName(link.owner) + ". | 32 to 17 |
| LinkOpener.java | isn't installed, or this version doesn't work with Stack yet. You can still read the saved text and preview here. | isn't installed, or is too old to work with Stack. You can still read the saved text here. | 20 to 18 |
| LinkOpener.java | isn't installed, or this version doesn't work with Stack yet.") | isn't installed, or is too old to work with Stack.") | 10 to 10 |
| LinkOpener.java | can't find this document any more. It may have been moved or deleted. | can't find this document any more. | 13 to 6 |
| TrashActivity.java | no longer shares this document. You can keep the saved copy here or delete it for good. The document itself is never touched. | no longer shares this document. Keep the saved copy, or delete it for good. | 23 to 14 |
| TrashActivity.java | Restore it to your library, or delete Stack's saved copy for good. The document itself is never touched. | Restore it, or delete Stack's saved copy for good. The original is untouched. | 18 to 13 |
| TrashActivity.java | ” and its information will be removed from this device. This cannot be undone. | ” will be deleted from this phone. This cannot be undone. | 14 to 11 |
| TrashActivity.java | Everything in Recently deleted will be removed from this device. This cannot be undone. | This deletes everything here for good. It cannot be undone. | 14 to 10 |
| Companions.java | No connection right now. Try again when you are online. | No connection. Try again when online. | 10 to 6 |
| Companions.java | GitHub is busy right now. Try again in a little while. | GitHub is busy. Try again later. | 11 to 6 |
| Companions.java | The download did not match what was published, so it was refused. Nothing was installed. | The download did not match the release. Nothing was installed. | 15 to 10 |
| Companions.java | The download was refused because it is not signed by the expected publisher. Nothing was installed. | The download is not from the expected publisher. Nothing was installed. | 16 to 11 |
| Companions.java | The download was not the app it should be, so it was refused. Nothing was installed. | The download was the wrong app. Nothing was installed. | 16 to 9 |
| Companions.java | The install did not finish. Nothing was changed. Try again later. | The install did not finish. Nothing was changed. | 11 to 8 |
| Companions.java | Android asks once: allow Stack to install apps. Then come back and tap Install. | Allow Stack to install apps, then come back and tap Install. | 14 to 11 |
| Companions.java | Open setting | Open settings | 2 to 2 |
| CompanionsActivity.java | Waiting for Android to ask you to confirm. | Confirm in the Android prompt. | 8 to 5 |
| CompanionsActivity.java | This is a test copy of Stack, so it is never offered a release update. | Test copy of Stack: no release updates. | 15 to 7 |
| MainActivity.java | +". Stack can install it for you; Android asks you to confirm. | +". Stack can install it; Android asks you to confirm. | 12 to 10 |
| ArchiveController.java | If the folder belongs to a cloud app, that app finishes the upload. | A cloud folder finishes the upload itself. | 13 to 7 |
| ArchiveController.java | Encrypted. Keep the recovery key safe in Key Vault; without it this backup cannot be opened. | Encrypted. Without the recovery key (in Key Vault) it cannot be opened. | 16 to 12 |
| ArchiveController.java | Keep it private; the archive is not encrypted. | Not encrypted: keep it private. | 8 to 5 |
| ArchiveController.java | linked "+(left==1?"document was":"documents were")+" left out. Turn on backup encryption to include them. | linked "+(left==1?"document was":"documents were")+" left out; turn on encryption to include them. | 13 to 12 |
| ArchiveController.java | The destination reports N bytes but Stack wrote M. The file was left in place; check it before relying on it. | The saved file is the wrong size (N of M bytes). Check it before relying on it. | 21 to 17 |
| ArchiveController.java | The encrypted backup has an unexpected inner format. | This backup is in a format Stack cannot read. | 8 to 9 |
| ArchiveController.java | This backup was made | Made | 4 to 1 |
| ArchiveController.java | +". Check it is the one you expect. | +". | 8 to 1 |
| ArchiveController.java | This backup is not encrypted, so it cannot be checked for tampering. Make sure it is yours. | Not encrypted, so Stack cannot tell if it was changed. Add it only if it is yours. | 17 to 17 |
| ArchiveController.java | . Existing documents and the unfinished scan were kept. | . Your existing documents were not changed. | 9 to 7 |
| ArchiveController.java | The operation failed. Free local space or choose another file/location and retry. | Could not finish. Free some space or choose another place. | 12 to 10 |
| BackupActivity.java | The previous operation was interrupted. Existing data was kept; select the file or destination again. Startup recovery removes uncommitted restored files. | The last backup or restore was interrupted. Nothing existing was changed. Start it again. | 21 to 14 |
| BackupActivity.java | Your saved originals, page order and edits, names, notes, typed details, organization tags, stacks, recognized text and review choices are included. No account is needed.  Unfinished scans and local consolidation recovery archives are excluded and stay on this device. Save an unfinished scan first to include it.  Linked documents (their text and preview) are included when backup encryption is on, or when you agree to an unencrypted backup. Without encryption, backup files are not protected: choose a private location. Automatic Android cloud backup and device transfer are disabled.  Restore adds documents and keeps your existing library. Selecting the same backup again imports another copy.  If saving fails or is canceled, your storage provider may leave an incomplete file. Delete that file and choose a destination again to retry. | Included: your documents, page edits, names, notes, details, tags, stacks and recognized text. No account is needed.  Unfinished scans stay on this phone. Save one first to include it.  Linked documents are included only when encryption is on or you agree to an unencrypted backup. Without encryption, anyone with the file can read it, so choose a private place.  Restore adds documents and keeps your library. Restoring the same backup twice adds a second copy. | 128 to 75 |
| BackupActivity.java | Choose a folder and Stack saves a new backup there about once a week, each …. The folder can be on this phone, a USB drive or a cloud app such as Filen Files or Google Drive. | Stack saves a backup in the folder you choose about once a week, each …. It can be on this phone, a USB drive or a cloud app. | 37 to 28 |
| BackupActivity.java | Stack does not encrypt backup files, so choose a private folder. | Backups are not encrypted, so choose a private folder. | 11 to 9 |
| BackupActivity.java | No folder picker is available on this phone. | No folder picker found. | 8 to 4 |
| BackupActivity.java | Stack could not keep access to that folder. Choose another folder. | Could not keep access to that folder. Choose another. | 11 to 9 |
| BackupActivity.java | Backups with encryption off leave them out, because their text and previews would be readable by anyone who gets the file. | Without encryption they are left out, because anyone with the file could read them. | 21 to 14 |
| BackupActivity.java | This backup is not encrypted, so Stack cannot tell whether someone changed it. Add it only if you made it and kept it private. | Stack cannot tell if this unencrypted backup was changed. Add it only if it is yours. | 24 to 16 |
| BackupActivity.java | Enter the passphrase, or scan the recovery key: in Key Vault on your computer, open "stack backup recovery key" and choose Show QR code. | Enter the passphrase, or scan the recovery key QR from Key Vault ("stack backup recovery key", Show QR code). | 24 to 19 |
| BackupActivity.java | Backups are encrypted. Older encrypted backups still need the passphrase or recovery key they were made with. | Backups are encrypted. Older ones still need the key they were made with. | 17 to 13 |
| BackupActivity.java | In the Key Vault app on your computer, click "Stack backup key (QR)". Then tap Scan key and point the camera at the code. That is all: nothing to type. | In Key Vault on your computer, click "Stack backup key (QR)". Then tap Scan key and point the camera at it. | 30 to 21 |
| BackupActivity.java | Choose a long passphrase. Stack will then show a recovery key once. You need the passphrase or the recovery key to open any encrypted backup; if you lose both, the backup is gone. | Choose a long passphrase. Stack then shows a recovery key once. If you lose both, backups cannot be opened. | 33 to 19 |
| BackupActivity.java | Stack needs a recovery key as a second way to open backups. Best: create it in Key Vault on your computer (it saves the key and shows a QR code), then scan the code here. Or let Stack make one now and show it to you once. | A recovery key is a second way to open backups. Make it in Key Vault and scan its QR code here, or let Stack make one now. | 47 to 27 |
| BackupActivity.java | Backup encryption is on. Check that this fingerprint matches the one Key Vault showed under the QR code: | Encryption is on. Check this fingerprint matches Key Vault's: | 18 to 9 |
| BackupActivity.java | If it differs, turn encryption off and set it up again. | If not, turn encryption off and set it up again. | 11 to 10 |
| BackupActivity.java | This is shown once. Save it somewhere safe, for example in Key Vault on your computer as "stack backup recovery key". | Shown once. Save it in Key Vault as "stack backup recovery key". Anyone with it can open your backups. | 21 to 19 |
| BackupActivity.java | Backup encryption is on. Do the recovery drill once: store the key, then check you can read it back before you trust backups. | Encryption is on. Check you can read the saved key back before relying on backups. | 23 to 15 |
| BackupActivity.java | No document provider is available. Install or enable a local document picker, then retry. | No file picker is available on this phone. | 14 to 8 |
| BackupActivity.java | OK | Got it | 1 to 2 |
| BackupActivity.java | Filen Files is not available. Choose the folder another way. | Filen Files is not available. Use another folder. | 10 to 8 |
| BackupScreen.java | Backup files are not encrypted, and leave out linked documents unless you choose otherwise. | Backups are not encrypted and leave out linked documents. | 14 to 9 |
| workspace.rs | Changed on this computer. Search shows the older saved text until your phone saves it again. | Changed on this computer. Search uses the older text until your phone saves it. | 16 to 14 |
| workspace.rs | Changed on this computer. Search and the text below come from this computer's copy until your phone saves it again. | Changed on this computer. Search and the text below use this copy until your phone saves it. | 20 to 17 |
| workspace.rs | Opening needs your phone: it opens through Filen Files. | Open this one on your phone, in Filen Files. | 9 to 9 |
| sync_pages.rs | Stack found your Filen folder at {root}. Linked documents inside it open without asking again. | Found at {root}. Linked documents in it open without asking. | 15 to 10 |
| sync_pages.rs | Stack learns where your Filen folder is the first time you choose a linked document's file for Open in Ami Pro. | Stack finds your Filen folder the first time you open a linked document in Ami Pro. | 21 to 16 |
| ami_open.rs | This file has a different name and content than the saved copy. It may be a different document. | This file looks different from the saved copy. Open it anyway? | 18 to 11 |
| ami_open.rs | The file is gone, or this app can no longer reach it. Press Open in Ami Pro to choose it again. | Stack cannot find the file. Choose it again with Open in Ami Pro. | 21 to 13 |
| ami_open.rs | Could not remember the file, so you will be asked again next time: {error} | Could not remember the file; you will be asked again: {error} | 14 to 11 |
| ami_open.rs | This document was removed, so it cannot be opened. | Removed, so it cannot be opened. | 9 to 6 |
| library.rs | Stack is already open on this computer (it is already using this library). Switch to its window. | Stack is already open on this computer. Switch to its window. | 17 to 11 |
| BackupActivity.java | (What's included dialog, four paragraphs) | Short bullet list of 4 items and "No account is needed."; the rest sits behind a Learn more button ("About library backups"). | 75 to 22 (main dialog) |
| Companions.java | The download did not match the release. Nothing was installed. / The download is not from the expected publisher. Nothing was installed. / The download was the wrong app. Nothing was installed. | That download did not match, so nothing was installed. / That download is not from the expected source, so nothing was installed. / That download is the wrong app, so nothing was installed. (test now asserts lowercase "nothing was installed") | 10/12/9 to 9/13/11 |
| TrashActivity.java | Deleted documents stay here for 30 days. If you sync with a computer, they stay there until they are deleted for good. | Deleted documents stay here, and on synced computers, for 30 days. | 23 to 11 |
| transfers_ui.rs | Backup key saved on this computer. Fingerprint {fingerprint}; check it matches Key Vault. | Backup key saved. Check fingerprint {fingerprint} matches Key Vault. | 13 to 9 |

## Left long on purpose

- Desktop "Retry saved import cleanup" and CLI messages: not in scope of the owner's complaint.

## New: Install Ami Pro offer (desktop, `ami_install.rs`)

| Where | Text |
|---|---|
| Library card title | Add Ami Pro? |
| Library card body | Edit your linked documents on this computer. |
| Buttons | Install / Not now |
| Working | Installing… |
| Result | Ami Pro is installed. / Ami Pro was not installed. |
| Open in Ami Pro, Ami Pro missing | Ami Pro is not installed (with Install) |
| PackageKit missing (Settings > Ami Pro only) | Install Ami Pro from the app store. |

## New: partner journey (phone: `PartnerJoinActivity`, `PartnerInviteActivity`, Home cards)

Rule held: titles 6 words or fewer, at most 2 short sentences, no internal terms, rare detail behind Learn more. `partnerJourney` checks assert the title and sentence limits.

| Where | Text |
|---|---|
| Home cards | I'm joining my partner / Install, sign in and scan; Invite partner / Send a link to get started; Not now |
| Join toolbar | Joining your partner (progress line: Step N of 4) |
| 1 Install | Install two apps. Stack works with Filen Files and Ami Pro. Android asks you to confirm each one. [Install both] [Cancel] |
| 2 Sign in | Sign in to Filen Files. Open Filen Files and sign in, or make an account. Then come back here. [Open Filen Files] [I'm signed in] [Learn more: Filen emails you a link when you make an account. Open it, then come back here.] |
| 3 Scan | Scan your partner's code. On their phone, open Invite partner and tap Pair with partner. Then scan the code you see. [Scan code] |
| 3 Trust (same wording as Pair with partner) | Trust this code? Short code ... It must match your partner's phone. |
| 4 Allow | Allow your partner's folder. Filen Files asks you to allow it. Tap Allow. (Without Filen's Allow screen: Choose the folder your partner shared with you.) [Open Filen Files / Choose folder] |
| 4 Problems | Waiting for your partner. Tap Try again when they are done. (existing) / Could not keep access to that folder. Try again. / Filen Files is not available. Choose a folder instead. |
| Finish | You're all set. Your partner's documents will appear in Stack. [Done] |
| Invite partner | Invite partner. Send your partner a link. It shows them what to install. [Share link] [Copy link] [Learn more: The link holds no secret and nothing about your library.] Pair with partner / When you are together |
| Share message | Join me on Stack: <start link>. Open it on your phone and your computer. (existing) |
| Menus | Home menu: I'm joining my partner; Invite partner. Partner sharing: Invite partner / Send a link to get started |

## Changed: backup encryption setup, for a partner without Key Vault (`BackupActivity.java`)

Only wording and the order of buttons changed. The Scan key function, the confirm-back step and all key handling are untouched; the Key Vault path is still there as the side choice. No device check asserted the old text.

| Where | Before | After |
|---|---|---|
| Encrypt backups (message) | In Key Vault on your computer, click "Stack backup key (QR)". Then tap Scan key and point the camera at it. | Choose a passphrase to protect your backups. Stack then gives you a recovery key to keep safe. |
| Encrypt backups (buttons) | Scan key (main) / Other options / Cancel | Use a passphrase (main) / I have a key to scan / Cancel |
| Recovery key (message) | A recovery key is a second way to open backups. Make it in Key Vault and scan its QR code here, or let Stack make one now. | Stack makes a recovery key now. Write it down and keep it safe. |
| Recovery key (buttons) | Scan key (main) / Make one here / Cancel setup | Make one here (main) / Scan key / Cancel setup |
| Your recovery key (message) | Shown once. Save it in Key Vault as "stack backup recovery key". Anyone with it can open your backups. | Write it down and keep it safe. Anyone with it can open your backups. |

Still mentions Key Vault (not changed): the restore prompt "Enter the passphrase, or scan the recovery key QR from Key Vault…" and the setup message for the passphrase path ("Choose a long passphrase…", three sentences, older text).

## Changed: restored links and the restore prompt (weekend, 2026-11-23)

| Where | Before | After |
|---|---|---|
| Linked document, restored from a backup and not yet reached (`LinkActivity.java`; other unreachable records keep the old text) | Can't reach Ami Pro right now. Nothing was removed. | Open it once in Ami Pro. Nothing was removed. |
| Restore prompt (`BackupActivity.java`) | Enter the passphrase, or scan the recovery key QR from Key Vault ("stack backup recovery key", Show QR code). | Enter the passphrase or the recovery key, or scan the recovery key QR code. |

The Scan key function behind the restore prompt is unchanged. No device check looked for either old text.

## Changed: joining by hand (pairing one-tap off, 2026-11-23)

| Where | Text |
|---|---|
| Join step 4 without Filen's Allow screen | Choose your partner's folder. Accept their shared folder in Filen Files first. Then choose it here. [Choose folder] |
| Join step 4 with Filen's Allow screen (unchanged) | Allow your partner's folder. Filen Files asks you to allow it. Tap Allow. |
| Pair with partner, owner side, without Filen's share screen (new line) | Once your partner has scanned, share your sharing folder with them in Filen Files. |

## Changed: one name for the folders (2026-11-23)

The two Settings rows are "Partner sharing folder" (the folder you put your shared documents in) and "Partner shared folder" (the folder your partner shares with you). Messages now use those names instead of "Partner sharing".

| Where | Before | After |
|---|---|---|
| Pair with partner, no folder yet | Choose a sharing folder in Partner sharing first. | Choose the Partner sharing folder first. |
| Pair with partner, paired but no folder | Choose the folder they share in Partner sharing. | Choose the Partner shared folder. |
| Pair with partner, Filen Files missing or refused | Filen Files is not available. Choose the folder in Partner sharing. | Filen Files is not available. Choose the Partner sharing folder. / ...Choose the Partner shared folder. (by which step) |
| Pair with partner, folder not kept | Could not keep access to that folder. Choose it in Partner sharing. | Could not keep access to that folder. Choose the Partner sharing folder. / ...Choose the Partner shared folder. |
| Pair with partner, owner, manual (my 63ccfde line) | ...share your sharing folder with them in Filen Files. | ...share that folder with them in Filen Files. |
| Linked document, not shared yet | Choose a sharing folder in Partner sharing so your partner can get this. | Choose the Partner sharing folder so your partner can get this. |

## Changed: where to find the shared folder (from the Filen session, 2026-11-23)

Filen Files has no "accept" button for a shared folder; it appears under Shared, Shared with me. The sender can only share with a Filen contact, and the contact request is accepted in Filen's web or desktop app (Filen Files 0.4.7 has no screen for it). Wording is from Filen's code, not re-checked on the phone.

| Where | Text |
|---|---|
| Join step 4 by hand (replaces "Accept their shared folder in Filen Files first. Then choose it here.") | Choose your partner's folder. In Filen Files, open Shared, then Shared with me. Then choose that folder here. [Choose folder] [Learn more: Your partner has to be your Filen contact first. Accept their contact request in Filen's web app.] |

## Changed: recovery-key scanner (PairingQrActivity, recovery-key scan only; sync and partner scans unchanged)

| Where | Before | After |
|---|---|---|
| Scanner line | Point the camera at the QR code in the Key Vault window on your computer. Hold the phone about 20 to 30 cm away and keep it steady. | Point the camera at your recovery key's QR code. Hold the phone about 20 to 30 cm away and keep it steady. |
| Wrong code | That QR code is not a Stack recovery key. Use the one from keyvault. | That QR code is not a Stack recovery key. |

## Folder sharing (new, 2026-11-26)

The QR partner screens above were removed with the QR sharing. The partner is always called by the Filen display name; with no name yet it reads "your partner".

| Where | Text |
|---|---|
| Settings row | **Folders** / Filen folders Stack reads |
| Settings, Partner row | **Partner** / Share with your partner (none); Sharing with [name]; [name] wants to share files with you (incoming); Waiting for [name] to accept; Show [name]'s files here (connected, nothing shown yet) |
| Partner row tapped, with a partner | Sharing with [name] / Stack shows [name]'s shared files in search. Nothing is deleted in Filen. / Close, **Stop showing [name]'s files** |
| Search screen card | [name] wants to share files with you. / See files [name] shares with you (connected; was "[name] shares files with you", which was wrong when nothing is shared) **Show them here**, **Not now** (hides it for 30 days) |
| Partner problems | Update Filen Files to share with your partner. / Filen Files could not be opened. / Filen did not share anything yet. Try again in a moment. |
| Folders screen | Folders; **Add a Filen folder**; No folders yet. Add a Filen folder to find its files in search. |
| Source names | [folder name]; [name]'s shared files; Shared by me; Scans shared with [name] |
| Source states | Reading 12 of 57; Looking for files; Up to date; Waiting: Stack is locked; Waiting: low battery; Can't reach Filen; Sign in to Filen; Filen is signed in as someone else **[Use this account]**; Access lost **[Fix]**; Saving scans (scan folder) |
| Share switch (own folder) | Share with [name]; Shared with [name] in [folder] (no switch); Can't check sharing right now |
| Remove | **Remove from Stack** / Remove from Stack? Stack stops showing the files of [source]. Nothing is deleted in Filen. |
| Folder errors | Android could not open the folder picker. / That folder could not be added. / Choose the same folder again. / Only folders from Filen Files can be added. / Stack was not given access to this folder. |
| Result menu (three dots) | Add to stack; Tags (New tag, Save); Share with [name]; Stop sharing with [name]; Shared with [name] in [folder] (disabled) |
| Result tile (second line) | Partner file: From [name] · [folder]; the partner's scan folder reads Shared scans (also for the older "Stack scans shared with ..." name). Own file: [folder]/[subfolder]. Shared by you · [folder]. The line before it, a reason (Reading; Password-protected; Not a supported type; ...), shows only when there is one (Reading; Password-protected; Not a supported type; ...) |
| Opening a result | Needs the internet; Sign in to Filen to open this file; Stack no longer has access to this folder; This file could not be opened; No app can open this file |
| Share problems | Update Filen Files to share folders. / Update Filen Files to share files / Update Filen Files to share scans. |
| Stack menu | Switch **Share with [name]** / Every scan in this stack is saved as a PDF in a Filen folder shared with your partner. Buttons: Hide from Home / Show on Home, Delete stack, Close |
| Scan folder | Name asked of Filen: Shared scans (no names in it, so it reads the same on the partner's side; was Stack scans shared with [name]). Source state words: Locked. Saving continues when you unlock Stack. / Can't reach Filen right now. Saving will try again. |
| Library menu | Folders |


## Changed: folder picker, live lists (2026-11-26)

Add a Filen folder asks Android's picker to open in Filen Drive (EXTRA_INITIAL_URI, a document URI for filen:root). Android 13's picker ignored it in the test, so on a first run it still opens on Recent files. Search results and the Folders screen now refresh themselves when the indexer adds, removes or reads files; no new words.


## Folder sharing: pre-release polish (2026-11-26)

| Where | Text |
|---|---|
| Filen Files too old or call missing (F12), in place of the Settings Partner row, the search card, the Folders share row, the result menu item and the stack share switch | Update Filen Files to share with your partner. Button **Update** (opens Filen Files). The search card shows only for this case; no card when Filen is not installed. |
| Filen Files not installed (F12), Settings Partner row and Folders share row | Get Filen Files. Button **Get Filen Files** (opens Filen's release page) |
| Search card, connected | See files [name] shares with you (or "your partner") |
| Opening a file, after 5 s | Waiting for Filen… with **Cancel** (a dialog from a result, the screen in the viewer) |
| Opening a file, after 30 s | Needs the internet (dialog button **OK**) |
| Tags dialog | unchanged words; the existing tags and the New tag box now show together, and Save applies a typed new tag |
