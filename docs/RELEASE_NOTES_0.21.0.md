Android **0.21.0** (versionCode 37 → 38). Phone app. The desktop stays at 0.7.1 and keeps working with it.

**Needs Filen Files 0.5.0** for sharing. Stack tells you when Filen Files is too old and opens it so it can update itself.

**Your Filen folders in Stack search**
- **Folders → Add a Filen folder**: pick any folder where it already is in Filen. Its files (and subfolders) become searchable in Stack. Nothing is moved or copied in Filen.
- Stack reads PDFs (the text inside them, and scanned pages), photos (JPEG, PNG, WebP), plain text, and Ami Pro documents (.SAM, .amipro). Password-protected and unsupported files are still found by name, with the reason.
- New, changed, renamed, moved and deleted files are picked up by themselves. Search works offline for everything already read.

**Sharing with your partner**
- Share a folder or a single file with one switch (**Share with [name]**). Filen Files asks you to confirm. Your partner allows it once (**See files [name] shares with you**), and from then on everything you share appears in their Stack search, marked "From [name]". It works the same in the other direction.
- Your partner's files are read-only. Their own stacks and tags on your files are kept, also when you edit a file.
- **Shared scans**: long-press a stack and turn on **Share with [name]** to share its scans (Stack asks first and shows how many). They are copied as PDFs to a "Shared scans" folder in your Filen; the originals stay in Stack. Stacks you don't share stay private.
- Stopping a share removes the files from your partner's Stack. Nothing is ever removed because Filen was offline or could not be reached.

**Replaced**
- The QR-code partner sharing from 0.19.0–0.20.0 ("I'm joining my partner", Invite partner, Partner sharing folder) is gone. On update, records received that way and the old partner keys are deleted; your own documents, backups and sync are untouched. Set up again with Settings → Partner.

**Safety**
- Files from your partner are read in a separate background process with time and size limits, so a damaged or hostile file cannot crash or stall Stack; such a file is marked "Could not be read".
- The app lock covers your partner's files, search and the viewer.

**Installing**
- Update Filen Files to 0.5.0 first. Then tap **Update** in Stack (Home notice or Settings → Update check), or install `Stack-0.21.0.apk` as an update. It is signed with the same key as earlier versions. Make a backup first. The library format changes (older Stack versions cannot open a library this version has upgraded), so update every phone you use.

Checksums are in `SHA256SUMS`.
