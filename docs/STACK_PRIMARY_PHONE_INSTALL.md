# Install Stack on your primary Android phone

**Already restored on the primary phone:** the user reports all documents are
present. For the icon repair, install
`~/Downloads/Stack-0.8.9/Stack-0.8.9.apk` as an **Update**. Keep the existing app
and library; do not repeat the restore steps below. See the
[restored-icon repair](STACK_RESTORED_ICONS.md) for the cause and verification.

The local delivery folder is `/home/charlie.day/Downloads/Stack-0.8.7/`.
It contains `Stack-0.8.7.apk`, `Google-Stack-Migration-final.stackbackup`, this
guide and `SHA256SUMS.txt`. Keep the backup private: it contains your documents
and is not encrypted. These files are outside Git.

1. Connect your primary phone to the computer, unlock it yourself and choose
   **File transfer** on the phone. Copy the APK and `.stackbackup` file from
   that folder to the phone's **Download** folder.
2. On the phone, open **Files → Downloads → Stack-0.8.7.apk → Install**.
   If Android requests it, allow the Files app to install this APK using
   **Settings → Allow from this source**, then return to the installer.
   You can turn that permission off afterward. Menu names vary by phone;
   [Google's Pixel instructions](https://support.google.com/pixelphone/answer/7391672)
   describe this per-app permission.
3. Open **Stack**. This APK is the normal `org.stackdocs.app` development build,
   signed with the same original key as the existing installation, not Stack
   Checks. Android 7.0 or later is required. If Stack is already installed, this
   updates it in place. If Android refuses an update, keep the existing app/data
   and investigate the message; do not uninstall it to force installation.
4. In Stack, tap the library menu beside the search bar, then **Backup & restore
   → Restore library**. Select **Google-Stack-Migration-final.stackbackup**.
   Wait for **Review backup**, check the summary, then tap **Add restored documents**
   once. Selecting a file alone does not import it.
5. Check a few document names, pages, details and stack memberships. Restore adds
   to an existing library, keeping its documents and stack choices. Repeating the
   import creates another set of document copies; it is not a synchronization step.

Use the **converted `.stackbackup`** file. The original Google Android `.ab`
backup and an ordinary PDF/ZIP export cannot be restored directly through this
screen. The converted archive preserves the prepared Google document information,
originals, categories and filing; legacy details retain their original text and
source provenance. Missing categories are added and matching names merge. Stack's
reader supports this archive's format 2 as well as current format 3.

This Google archive represents the original migration snapshot. For your **latest
Stack library**, including changes you made afterward, use **Backup & restore →
Backup library** on the current phone, copy that new `.stackbackup` instead, and
restore it on the primary phone using the same steps. Choose one source for a
fresh migration rather than importing both overlapping snapshots.

App lock, photo monitoring, notification permissions, unfinished scans and unsaved
editor drafts do not transfer with a library backup. On a fresh phone monitoring
and app lock start off; enable them deliberately if wanted. Restoring into an
existing installation preserves its local lock policy. **Last backup** means a
successful backup saved on that phone; restore does not set it.

To sort, open **All documents**, tap **Newest ▾** and choose **Name (A–Z)** or
**Newest first**. Stack remembers the choice; Home's Recent documents stay newest
first. With app lock enabled, opening Stack goes straight to Android's **Unlock
Stack** prompt. Canceling leaves an explicit **Try again** option.
