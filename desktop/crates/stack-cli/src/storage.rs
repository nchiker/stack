use stack_archive::{Control, synthetic};
use stack_core::{DocumentEdit, Sort, StackEdit, library::Library};
use stack_render::{Renderer, fixtures};
use std::{
    ffi::{OsStr, OsString},
    fs,
    path::Path,
};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
fn text(value: &OsStr) -> Result<&str> {
    value
        .to_str()
        .ok_or_else(|| "This argument must be Unicode".into())
}
fn report(value: &impl serde::Serialize) -> Result<()> {
    println!("{}", serde_json::to_string_pretty(value)?);
    Ok(())
}

pub fn run(args: &[OsString]) -> Result<()> {
    if let [command, rest @ ..] = args
        && command == "key"
    {
        let line = key_command(&stack_core::keystore::SystemStore, rest, || {
            read_secret_stdin("Recovery key: ")
        })?;
        println!("{line}");
        return Ok(());
    }
    let control = Control::default();
    let renderer = Renderer::from_environment();
    if let [command, source, output] = args
        && command == "m1-fixtures"
    {
        let source = Path::new(source);
        fixtures::verify(source)?;
        let bytes = fixtures::NAMES
            .map(|name| fs::read(source.join(name)))
            .into_iter()
            .collect::<std::io::Result<Vec<_>>>()?;
        let manifest = synthetic::create(
            Path::new(output),
            [&bytes[0], &bytes[1], &bytes[2], &bytes[3]],
        )?;
        return report(
            &serde_json::json!({"fixture":"stack-m1","version":1,"documents":manifest.rows("documents").len(),"files":manifest.rows("files").len()}),
        );
    }
    if let [command, root, kind, input, checkpoint, ready] = args
        && command == "m1-interrupt"
    {
        return interruption(
            Path::new(root),
            text(kind)?,
            Path::new(input),
            text(checkpoint)?,
            Path::new(ready),
            &renderer,
        );
    }
    let [command, root, action, tail @ ..] = args else {
        return Err(usage().into());
    };
    let mut library = Library::open(Path::new(root))?;
    match (text(command)?, text(action)?, tail) {
        ("sync", "status", []) => report(&library.sync_status()?),
        ("sync", "start", peers) => {
            let peers = peers
                .iter()
                .map(|p| text(p).map(str::to_owned))
                .collect::<Result<Vec<_>>>()?;
            report(&library.sync_start(&peers)?)
        }
        ("sync", "export", [peer, path]) => {
            report(&library.sync_export(text(peer)?, Path::new(path))?)
        }
        ("sync", "review-adoption", [path]) => {
            report(&library.sync_review_adoption(Path::new(path))?)
        }
        ("sync", "adopt-reviewed", [path, review]) => {
            let review = stack_archive::strict_json::object(&fs::read(review)?)?;
            report(&library.sync_adopt_reviewed(Path::new(path), &review)?)
        }
        ("sync", "resolve", [key, expected, value]) => {
            library.sync_resolve_json(
                text(key)?,
                &serde_json::from_str(text(expected)?)?,
                serde_json::from_str(text(value)?)?,
            )?;
            report(&library.sync_status()?)
        }
        ("sync", "import", [path]) => report(&library.sync_import(Path::new(path), None)?),
        ("sync", "adopt", [path, group, members @ ..]) => {
            let members = members
                .iter()
                .map(|p| text(p).map(str::to_owned))
                .collect::<Result<Vec<_>>>()?;
            report(&library.sync_import(Path::new(path), Some((text(group)?, &members)))?)
        }
        ("library", "text-status", [id]) => report(&library.text_status(text(id)?)?),
        ("library", "text-retry", [id]) => report(&library.retry_text(text(id)?)?),
        ("library", "text-cancel", [id]) => report(&library.cancel_text(text(id)?)?),
        ("library", "read-text", []) => {
            let mut read = 0;
            let mut failed = 0;
            while let Some(job) = library.claim_text()? {
                let outcome = renderer
                    .read_saved_text(&job.source, &job.page)
                    .map_err(|e| e.to_string());
                if outcome.is_err() {
                    failed += 1;
                }
                library.finish_text(&job, outcome)?;
                read += 1;
            }
            report(
                &serde_json::json!({"processed":read,"failed":failed,"offline":true,"language":"English"}),
            )
        }
        ("library", "status", []) => report(&library.status()?),
        ("library", "reindex", []) => {
            library.rebuild_search()?;
            println!("Search index rebuilt.");
            Ok(())
        }
        ("library", "manifest", []) => report(library.manifest()?.json()),
        ("library", "journal", []) => report(&library.journal()?),
        ("library", "search", [query]) => {
            report(&library.search(text(query)?, None, Sort::Newest, 1000)?)
        }
        ("library", "search", [query, stack]) => {
            report(&library.search(text(query)?, Some(text(stack)?), Sort::Newest, 1000)?)
        }
        ("library", "import", [source, title]) => {
            let info = renderer.inspect_original(Path::new(source))?;
            report(&library.import_original(
                Path::new(source),
                text(title)?,
                &renderer,
                info.mime,
                info.page_count,
                &control,
            )?)
        }
        ("library", "rename", [id, title]) => {
            let token = library.token(text(id)?)?;
            report(&library.edit_document(
                &token,
                DocumentEdit::Rename(text(title)?.into()),
                &control,
            )?)
        }
        ("library", "notes", [id, notes]) => {
            let token = library.token(text(id)?)?;
            report(&library.edit_document(
                &token,
                DocumentEdit::Notes(text(notes)?.into()),
                &control,
            )?)
        }
        ("library", "membership", [id, stack, action]) => {
            let token = library.token(text(id)?)?;
            let selected = match text(action)? {
                "add" => true,
                "remove" => false,
                _ => return Err("Expected add or remove".into()),
            };
            report(&library.edit_document(
                &token,
                DocumentEdit::Membership {
                    stack: text(stack)?.into(),
                    selected,
                },
                &control,
            )?)
        }
        ("library", "file", [id, stacks @ ..]) => {
            let token = library.token(text(id)?)?;
            let stacks = stacks
                .iter()
                .map(|s| text(s).map(str::to_owned))
                .collect::<Result<Vec<_>>>()?;
            report(&library.edit_document(&token, DocumentEdit::Filing(stacks), &control)?)
        }
        ("library", "delete", [id]) => {
            let token = library.token(text(id)?)?;
            library.delete_document(&token, &control)?;
            report(&library.status()?)
        }
        ("library", "detail", [id, input]) => {
            let token = library.token(text(id)?)?;
            let file = stack_archive::read_regular(Path::new(input))?;
            if file.metadata()?.len() > 16384 {
                return Err("Detail input exceeds 16 KiB".into());
            }
            use std::io::Read;
            let mut bytes = Vec::new();
            file.take(16385).read_to_end(&mut bytes)?;
            let value = stack_archive::strict_json::object(&bytes)?;
            report(&library.edit_document(&token, DocumentEdit::SetDetail(value), &control)?)
        }
        ("library", "remove-detail", [id, field]) => {
            let token = library.token(text(id)?)?;
            report(&library.edit_document(
                &token,
                DocumentEdit::RemoveDetail(text(field)?.into()),
                &control,
            )?)
        }
        ("library", "page-order", [id, pages @ ..]) => {
            let token = library.token(text(id)?)?;
            let pages = pages
                .iter()
                .map(|s| text(s).map(str::to_owned))
                .collect::<Result<Vec<_>>>()?;
            report(&library.edit_document(&token, DocumentEdit::PageOrder(pages), &control)?)
        }
        ("library", "stack-create", [name, color, visible]) => report(&library.edit_stacks(
            library.revision()?,
            StackEdit::Create {
                name: text(name)?.into(),
                color: text(color)?.parse()?,
                visible: visibility(text(visible)?)?,
            },
            &control,
        )?),
        ("library", "stack-rename", [id, name]) => report(&library.edit_stacks(
            library.revision()?,
            StackEdit::Rename {
                id: text(id)?.into(),
                name: text(name)?.into(),
            },
            &control,
        )?),
        ("library", "stack-visibility", [id, visible]) => report(&library.edit_stacks(
            library.revision()?,
            StackEdit::Visibility {
                id: text(id)?.into(),
                visible: visibility(text(visible)?)?,
            },
            &control,
        )?),
        ("library", "stack-delete", [id]) => report(&library.edit_stacks(
            library.revision()?,
            StackEdit::Delete(text(id)?.into()),
            &control,
        )?),
        ("library", "stack-order", ids) => {
            let ids = ids
                .iter()
                .map(|s| text(s).map(str::to_owned))
                .collect::<Result<Vec<_>>>()?;
            report(&library.edit_stacks(library.revision()?, StackEdit::Order(ids), &control)?)
        }
        ("archive", "validate" | "review" | "restore", [input, options @ ..]) => {
            // `--full` (review/validate only) prints the whole id mapping instead of the short summary.
            let full = options.iter().any(|o| o == "--full");
            if full && action == "restore" {
                return Err(usage().into());
            }
            let options: Vec<OsString> =
                options.iter().filter(|o| *o != "--full").cloned().collect();
            // An encrypted backup needs its key: from a file or from stdin (no echo on a terminal), never an argument.
            let key = backup_key(&options)?;
            let staged = library.stage_archive_with_key(
                &mut stack_archive::read_regular(Path::new(input))?,
                key.as_ref(),
                &renderer,
                &control,
            )?;
            let plan = library.review_archive(&staged)?;
            if action == "restore" {
                report(&library.commit_archive(&plan, &control)?)
            } else if full {
                report(plan.review())
            } else {
                report(&review_summary(plan.review()))
            }
        }
        ("archive", "export", [output, options @ ..]) => {
            // The only option is `--saved-key`: encrypt with the key the desktop app saved on this computer.
            // Without it the file is plain. A key is never accepted as an argument.
            let key = match options {
                [] => None,
                [flag] if flag == "--saved-key" => Some(saved_key()?),
                _ => return Err(usage().into()),
            };
            let links = library.link_count()?;
            library.export_archive_with_key(Path::new(output), key.as_ref(), &control)?;
            println!(
                "Archive published ({}, {links} linked documents).",
                if key.is_some() {
                    "encrypted"
                } else {
                    "not encrypted"
                }
            );
            Ok(())
        }
        _ => Err(usage().into()),
    }
}
const NO_STORE: &str =
    "No key store is available on this computer, so the saved key cannot be used";
const BAD_KEY: &str = "That is not a Stack backup key";
const KEY_EXISTS: &str =
    "A backup key is already saved on this computer; add --replace to replace it";
const LOCKED: &str =
    "The key store is locked; unlock it (for example by signing in again) and retry";
const NOTHING_SAVED: &str = "No backup key was saved on this computer.";

/// Exit codes of `stack-cli key ...` (anything else that fails exits 1).
pub const EXIT_USAGE: i32 = 2;
pub const EXIT_NO_STORE: i32 = 3;
pub const EXIT_LOCKED: i32 = 4;
pub const EXIT_KEY_EXISTS: i32 = 5;

/// A failure with its own exit code.
#[derive(Debug)]
struct CliError {
    code: i32,
    text: String,
}
impl std::fmt::Display for CliError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.text)
    }
}
impl std::error::Error for CliError {}
fn coded(code: i32, text: impl Into<String>) -> Box<dyn std::error::Error> {
    Box::new(CliError {
        code,
        text: text.into(),
    })
}
/// The process exit code for a failure: 1 unless it carries its own.
pub fn exit_code(error: &(dyn std::error::Error + 'static)) -> i32 {
    error.downcast_ref::<CliError>().map_or(1, |e| e.code)
}
fn store_error(error: stack_core::keystore::StoreError) -> Box<dyn std::error::Error> {
    use stack_core::keystore::StoreError;
    match error {
        StoreError::Unavailable => coded(EXIT_NO_STORE, NO_STORE),
        StoreError::Locked => coded(EXIT_LOCKED, LOCKED),
        StoreError::Failed(text) => text.into(),
    }
}
fn usage_error() -> Box<dyn std::error::Error> {
    coded(EXIT_USAGE, usage())
}

/// `key save --key-stdin [--replace]`, `key status`, `key remove`: manage the backup key saved on this computer
/// (the same store the desktop app uses). The key comes only from standard input and is never printed; `status`
/// shows only whether a key is saved and its short fingerprint. The desktop app replaces a saved key only after the
/// new key has opened a backup; a command with no backup to prove it needs `--replace` to overwrite (saving the
/// very same key again is fine). A locked store is reported, never unlocked with a prompt, except for `--replace`.
fn key_command(
    store: &dyn stack_core::keystore::SecretStore,
    args: &[OsString],
    read_stdin: impl FnOnce() -> Result<String>,
) -> Result<String> {
    use stack_core::keystore::{
        KeyStatus, StoreError, fingerprint, key_status_typed, parse_key_text,
    };
    let word = |a: &OsString| a.to_str().map(str::to_owned);
    let not_locked = || -> Result<()> {
        if store.is_locked().map_err(store_error)? {
            return Err(store_error(StoreError::Locked));
        }
        Ok(())
    };
    match args.iter().map(word).collect::<Option<Vec<_>>>().as_deref() {
        Some([action, flag]) | Some([action, flag, _])
            if action == "save" && flag == "--key-stdin" =>
        {
            let replace = match args {
                [_, _] => false,
                [_, _, r] if r == "--replace" => true,
                _ => return Err(usage_error()),
            };
            // Without --replace the store is looked at first, so a locked store or a different saved key is
            // reported before the user has typed anything more than needed.
            let existing = if replace {
                None
            } else {
                not_locked()?;
                store.get().map_err(store_error)?
            };
            let text = zeroize::Zeroizing::new(read_stdin()?);
            let hex = parse_key_text(&text).ok_or(BAD_KEY)?;
            let line = |what: &str| format!("{what} Fingerprint {}", fingerprint(&hex));
            match existing {
                Some(saved) if *saved == *hex => {
                    return Ok(line("Backup key already saved on this computer."));
                }
                Some(_) => return Err(coded(EXIT_KEY_EXISTS, KEY_EXISTS)),
                None => {}
            }
            store.set(&hex).map_err(store_error)?;
            Ok(line("Backup key saved on this computer."))
        }
        Some([action]) if action == "status" => match key_status_typed(store) {
            Ok(KeyStatus::Saved { fingerprint }) => {
                Ok(format!("saved: yes\nfingerprint: {fingerprint}"))
            }
            Ok(KeyStatus::NotSet) => Ok("saved: no".into()),
            Ok(KeyStatus::Locked) => Ok("saved: unknown (the key store is locked)".into()),
            Err(error) => Err(store_error(error)),
        },
        Some([action]) if action == "remove" => {
            not_locked()?;
            if store.get().map_err(store_error)?.is_none() {
                return Ok(NOTHING_SAVED.into());
            }
            store.remove().map_err(store_error)?;
            Ok("Saved backup key removed from this computer.".into())
        }
        _ => Err(usage_error()),
    }
}
/// The key the desktop app saved on this computer. The user asked for it, so a missing key store is an error here
/// (the CLI never falls back to a plain file).
fn saved_key() -> Result<stack_core::BackupKey> {
    use stack_core::keystore::{SystemStore, saved_backup_key};
    match saved_backup_key(&SystemStore) {
        Ok(Some(key)) => Ok(key),
        Ok(None) => Err("No backup key is saved on this computer".into()),
        Err(error) => Err(store_error(error)),
    }
}
/// Short review output: the counts and warnings, without the id mapping (`--full` prints that).
fn review_summary(receipt: &stack_core::RestoreReceipt) -> serde_json::Value {
    let mut warnings = Vec::new();
    if !receipt.encrypted {
        warnings.push("Not encrypted, so it cannot be checked for tampering");
    }
    serde_json::json!({
        "format": receipt.source_version,
        "encrypted": receipt.encrypted,
        "documents": receipt.documents_added,
        "links_imported": receipt.links_added,
        "links_skipped": receipt.links_skipped,
        "existing_documents_skipped": receipt.document_collisions,
        "matching_stacks": receipt.merged_stacks,
        "warnings": warnings,
    })
}
/// `--saved-key` (the key saved on this computer by the desktop app, from the OS secret store), `--key-file PATH`, `--key-stdin`, `--passphrase-file PATH`, `--passphrase-stdin`. The secret is read into a
/// buffer that is zeroed on drop; it is never printed, logged or accepted as a command-line value.
fn backup_key(options: &[OsString]) -> Result<Option<stack_core::BackupKey>> {
    use stack_core::BackupKey;
    let (recovery, source) = match options {
        [] => return Ok(None),
        [flag] if flag == "--saved-key" => {
            return saved_key().map(Some);
        }
        [flag] if flag == "--key-stdin" => (true, None),
        [flag] if flag == "--passphrase-stdin" => (false, None),
        [flag, path] if flag == "--key-file" => (true, Some(path)),
        [flag, path] if flag == "--passphrase-file" => (false, Some(path)),
        _ => return Err(usage().into()),
    };
    let mut text = match source {
        Some(path) => read_secret_file(Path::new(path))?,
        None => read_secret_stdin(if recovery {
            "Recovery key: "
        } else {
            "Passphrase: "
        })?,
    };
    // One trailing line break from a file or a terminal is not part of the secret.
    while text.ends_with(['\n', '\r']) {
        text.pop();
    }
    if text.is_empty() {
        return Err("The key is empty".into());
    }
    Ok(Some(if recovery {
        BackupKey::recovery(text)
    } else {
        BackupKey::passphrase(text)
    }))
}

fn read_secret_file(path: &Path) -> Result<String> {
    use std::io::Read;
    let mut bytes = zeroize::Zeroizing::new(Vec::new());
    stack_archive::read_regular(path)?
        .take(4097)
        .read_to_end(&mut bytes)?;
    if bytes.len() > 4096 {
        return Err("The key file is too large".into());
    }
    String::from_utf8(std::mem::take(&mut *bytes))
        .map_err(|_| "The key file must be UTF-8 text".into())
}

/// Reads one line from standard input a byte at a time (no buffered copy of the secret is left behind), with
/// terminal echo off when it is a terminal. On a terminal Ctrl-C is read as a byte (signals are off while the
/// secret is typed), so the terminal is always put back.
fn read_secret_stdin(prompt: &str) -> Result<String> {
    use rustix::termios::{LocalModes, OptionalActions, isatty, tcgetattr, tcsetattr};
    use std::io::Write;
    let stdin = rustix::stdio::stdin();
    struct Restore(rustix::termios::Termios);
    impl Drop for Restore {
        fn drop(&mut self) {
            let _ = tcsetattr(rustix::stdio::stdin(), OptionalActions::Flush, &self.0);
        }
    }
    let _restore = if isatty(stdin) {
        let original = tcgetattr(stdin)?;
        let mut quiet = original.clone();
        quiet
            .local_modes
            .remove(LocalModes::ECHO | LocalModes::ISIG);
        tcsetattr(stdin, OptionalActions::Flush, &quiet)?;
        eprint!("{prompt}");
        std::io::stderr().flush()?;
        Some(Restore(original))
    } else {
        None
    };
    let result = read_secret_line(|one| Ok(rustix::io::read(stdin, one)?));
    if _restore.is_some() {
        eprintln!();
    }
    result
}

/// One line (up to a line feed or the end of input; a CR before the line feed is dropped) of at most 4096 bytes of
/// UTF-8 text, from a one-byte-at-a-time reader. Every buffer holding secret bytes is zeroed on every path.
fn read_secret_line(mut read: impl FnMut(&mut [u8; 1]) -> Result<usize>) -> Result<String> {
    let mut bytes = zeroize::Zeroizing::new(Vec::with_capacity(4097));
    let mut one = zeroize::Zeroizing::new([0u8; 1]);
    loop {
        if read(&mut one)? == 0 || one[0] == b'\n' {
            break;
        }
        if one[0] == 3 {
            return Err("Cancelled".into());
        }
        if bytes.len() >= 4096 {
            return Err("The key is too long".into());
        }
        bytes.push(one[0]);
    }
    if bytes.last() == Some(&b'\r') {
        bytes.pop();
    }
    match String::from_utf8(std::mem::take(&mut *bytes)) {
        Ok(text) => Ok(text),
        Err(invalid) => {
            drop(zeroize::Zeroizing::new(invalid.into_bytes()));
            Err("The key must be UTF-8 text".into())
        }
    }
}
fn visibility(value: &str) -> Result<bool> {
    match value {
        "show" => Ok(true),
        "hide" => Ok(false),
        _ => Err("Use show or hide".into()),
    }
}
fn usage() -> &'static str {
    "Usage: stack-cli library ROOT status|reindex|manifest|journal|search QUERY [STACK]|import FILE TITLE|rename ID TITLE|notes ID TEXT|file ID [STACK...]|detail ID JSON_FILE|remove-detail ID FIELD|delete ID|page-order ID PAGE...|stack-create NAME COLOR show/hide|stack-rename ID NAME|stack-visibility ID show/hide|stack-delete ID|stack-order ID...; stack-cli archive ROOT validate|review|restore FILE [--saved-key|--key-file PATH|--key-stdin|--passphrase-file PATH|--passphrase-stdin] (validate|review print a short summary; add --full for the whole id mapping)|export NEW_FILE [--saved-key]; stack-cli key save --key-stdin [--replace]|status|remove (the key is read from standard input only); stack-cli m1-fixtures M0_FIXTURES NEW_DIRECTORY"
}

/// External SIGKILL harness hook, restricted to explicit synthetic /tmp roots.
fn interruption(
    root: &Path,
    kind: &str,
    input: &Path,
    checkpoint: &str,
    ready: &Path,
    renderer: &Renderer,
) -> Result<()> {
    if !cfg!(debug_assertions) {
        return Err("Interruption probes are debug-build only".into());
    }
    let root = root.canonicalize()?;
    let ready_parent = ready
        .parent()
        .ok_or("Missing ready parent")?
        .canonicalize()?;
    if !root.starts_with("/tmp/")
        || !ready_parent.starts_with("/tmp/")
        || fs::read(root.join(".stack-m1-disposable"))? != b"stack-m1-synthetic-only\n"
    {
        return Err(
            "Interruption probe requires an explicitly marked disposable /tmp library".into(),
        );
    }
    let mut library = Library::open(&root)?;
    let checkpoint = checkpoint.to_owned();
    let ready = ready.to_owned();
    let mut probe = move |point: &str| {
        if point == checkpoint {
            use std::io::Write;
            let mut marker = fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&ready)?;
            marker.write_all(point.as_bytes())?;
            marker.sync_all()?;
            loop {
                std::thread::park();
            }
        }
        Ok(())
    };
    if kind == "sync-publish" {
        let blobs = root.join("publish-blobs");
        fs::create_dir(&blobs)?;
        let header = stack_sync::bundle::read(&mut stack_archive::read_regular(input)?, &blobs)?;
        stack_sync::bundle::publish_with(
            &header,
            &blobs,
            &root.join("published.stackexchange"),
            &Control::default(),
            &mut probe,
        )?;
        return Err("Publication checkpoint was not reached".into());
    }
    library.set_probe(Box::new(probe));
    let control = Control::default();
    match kind {
        "restore" => {
            let staged = library.stage_archive(
                &mut stack_archive::read_regular(input)?,
                renderer,
                &control,
            )?;
            let plan = library.review_archive(&staged)?;
            library.commit_archive(&plan, &control)?;
        }
        "export" => library.export_archive(input, &control)?,
        "sync-import" => {
            library.sync_import(input, None)?;
        }
        _ => return Err("Expected restore, export or sync-import probe".into()),
    }
    Err("Checkpoint was not reached".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn key_options_read_a_file_and_refuse_anything_else() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("key.txt");
        fs::write(&file, format!("{}\n", "ab".repeat(32))).unwrap();
        let args = |v: &[&str]| v.iter().map(OsString::from).collect::<Vec<_>>();
        assert!(backup_key(&[]).unwrap().is_none());
        let key = backup_key(&args(&["--key-file", file.to_str().unwrap()]))
            .unwrap()
            .unwrap();
        assert_eq!(format!("{key:?}"), "BackupKey(<redacted>)");
        assert!(
            backup_key(&args(&["--passphrase-file", file.to_str().unwrap()]))
                .unwrap()
                .is_some()
        );
        // A key on the command line is not an option; unknown or missing values are usage errors.
        assert!(backup_key(&args(&["--key", &"ab".repeat(32)])).is_err());
        assert!(backup_key(&args(&["--key-file"])).is_err());
        assert!(
            backup_key(&args(&[
                "--key-file",
                dir.path().join("none").to_str().unwrap()
            ]))
            .is_err()
        );
        fs::write(&file, "\n").unwrap();
        assert!(backup_key(&args(&["--key-file", file.to_str().unwrap()])).is_err());
    }

    #[test]
    fn key_commands_use_the_store_and_never_show_the_key() {
        use stack_core::keystore::{MemoryStore, SecretStore, fingerprint};
        use std::sync::atomic::Ordering::SeqCst;
        let args = |v: &[&str]| v.iter().map(OsString::from).collect::<Vec<_>>();
        let hex: String = (1u8..=32).map(|b| format!("{b:02x}")).collect();
        let stdin = |text: String| move || Ok(text);
        let store = MemoryStore::default();
        let status = |s: &MemoryStore| key_command(s, &args(&["status"]), || unreachable!());
        assert_eq!(status(&store).unwrap(), "saved: no");
        let saved = key_command(&store, &args(&["save", "--key-stdin"]), stdin(hex.clone()));
        assert_eq!(
            saved.unwrap(),
            format!(
                "Backup key saved on this computer. Fingerprint {}",
                fingerprint(&hex)
            )
        );
        let shown = status(&store).unwrap();
        assert_eq!(
            shown,
            format!("saved: yes\nfingerprint: {}", fingerprint(&hex))
        );
        assert!(!shown.contains(&hex));
        // An existing key is replaced only with --replace.
        let other = "ab".repeat(32);
        let refused = key_command(
            &store,
            &args(&["save", "--key-stdin"]),
            stdin(other.clone()),
        );
        assert_eq!(refused.unwrap_err().to_string(), KEY_EXISTS);
        assert_eq!(&**store.get().unwrap().unwrap(), hex.as_str());
        key_command(
            &store,
            &args(&["save", "--key-stdin", "--replace"]),
            stdin(other.clone()),
        )
        .unwrap();
        assert_eq!(&**store.get().unwrap().unwrap(), other.as_str());
        // An invalid key gets a generic message that does not echo it.
        let bad = key_command(
            &store,
            &args(&["save", "--key-stdin", "--replace"]),
            stdin("nope-secret".into()),
        );
        let message = bad.unwrap_err().to_string();
        assert_eq!(message, BAD_KEY);
        assert!(!message.contains("secret"));
        // A key (or anything else) on the command line is a usage error and is never read.
        for bad in [
            args(&["save", &hex]),
            args(&["save", "--key", &hex]),
            args(&["save", "--key-stdin", &hex]),
            args(&["save"]),
            args(&["status", "x"]),
            args(&["remove", "x"]),
            args(&["wat"]),
            args(&[]),
        ] {
            let error = key_command(&store, &bad, || unreachable!())
                .unwrap_err()
                .to_string();
            assert!(error.starts_with("Usage:") && !error.contains(&hex));
        }
        assert_eq!(
            key_command(&store, &args(&["remove"]), || unreachable!()).unwrap(),
            "Saved backup key removed from this computer."
        );
        assert_eq!(status(&store).unwrap(), "saved: no");
        // Locked and unreachable stores.
        store.locked.store(true, SeqCst);
        assert!(status(&store).unwrap().starts_with("saved: unknown"));
        assert_eq!(store.unlocks.load(SeqCst), 0);
        store.locked.store(false, SeqCst);
        store.unavailable.store(true, SeqCst);
        for a in [vec!["status"], vec!["remove"]] {
            let e = key_command(&store, &args(&a), || unreachable!()).unwrap_err();
            assert_eq!(e.to_string(), NO_STORE);
        }
        let e = key_command(&store, &args(&["save", "--key-stdin"]), stdin(hex));
        assert_eq!(e.unwrap_err().to_string(), NO_STORE);
    }

    fn line_of(input: &[u8]) -> Result<String> {
        let mut rest = input.iter();
        read_secret_line(|one| match rest.next() {
            Some(byte) => {
                one[0] = *byte;
                Ok(1)
            }
            None => Ok(0),
        })
    }

    #[test]
    fn reading_a_secret_line_handles_line_ends_limits_and_bad_text() {
        assert_eq!(line_of(b"abc").unwrap(), "abc"); // end of input, no line feed
        assert_eq!(line_of(b"abc\n").unwrap(), "abc");
        assert_eq!(line_of(b"abc\r\n").unwrap(), "abc"); // CRLF
        assert_eq!(line_of(b"one\ntwo\n").unwrap(), "one"); // only the first line
        assert_eq!(line_of(b"").unwrap(), "");
        assert_eq!(line_of(&vec![b'a'; 4096]).unwrap().len(), 4096);
        let long = vec![b'a'; 4097];
        assert_eq!(
            line_of(&long).unwrap_err().to_string(),
            "The key is too long"
        );
        let bad = line_of(b"ab\xff\xfe\n").unwrap_err().to_string();
        assert_eq!(bad, "The key must be UTF-8 text");
        assert_eq!(line_of(b"ab\x03cd\n").unwrap_err().to_string(), "Cancelled");
        // A read failure passes through.
        let failed = read_secret_line(|_| Err("read failed".into()));
        assert_eq!(failed.unwrap_err().to_string(), "read failed");
    }

    #[test]
    fn key_command_failures_have_their_own_exit_codes() {
        use stack_core::keystore::{MemoryStore, SecretStore, StoreError};
        use std::sync::atomic::Ordering::SeqCst;
        let args = |v: &[&str]| v.iter().map(OsString::from).collect::<Vec<_>>();
        let hex: String = (1u8..=32).map(|b| format!("{b:02x}")).collect();
        let other = "ab".repeat(32);
        let code = |r: Result<String>| exit_code(r.unwrap_err().as_ref());
        let store = MemoryStore::default();
        // Usage: 2, and the key is never read.
        assert_eq!(
            code(key_command(&store, &args(&["wat"]), || unreachable!())),
            EXIT_USAGE
        );
        // A bad key and other failures: 1.
        let bad = key_command(
            &store,
            &args(&["save", "--key-stdin"]),
            || Ok("nope".into()),
        );
        assert_eq!(code(bad), 1);
        // Remove with nothing saved is fine and says so.
        assert_eq!(
            key_command(&store, &args(&["remove"]), || unreachable!()).unwrap(),
            NOTHING_SAVED
        );
        // Saving the same key twice succeeds; a different one is refused with 5.
        let save = |s: &MemoryStore, k: &str, extra: &[&str]| {
            let k = k.to_string();
            let mut a = vec!["save", "--key-stdin"];
            a.extend_from_slice(extra);
            key_command(s, &args(&a), move || Ok(k))
        };
        save(&store, &hex, &[]).unwrap();
        assert!(save(&store, &hex, &[]).unwrap().contains("already saved"));
        assert_eq!(code(save(&store, &other, &[])), EXIT_KEY_EXISTS);
        assert_eq!(&**store.get().unwrap().unwrap(), hex.as_str());
        // A locked store: save (no --replace), remove and status never prompt; 4 for save and remove.
        store.locked.store(true, SeqCst);
        assert_eq!(code(save(&store, &other, &[])), EXIT_LOCKED);
        assert_eq!(
            code(key_command(&store, &args(&["remove"]), || unreachable!())),
            EXIT_LOCKED
        );
        assert!(
            key_command(&store, &args(&["status"]), || unreachable!())
                .unwrap()
                .contains("locked")
        );
        assert_eq!(store.unlocks.load(SeqCst), 0);
        store.locked.store(false, SeqCst);
        // No store: 3. A store that fails: its text passes through with 1.
        store.unavailable.store(true, SeqCst);
        for a in [vec!["status"], vec!["remove"]] {
            assert_eq!(
                code(key_command(&store, &args(&a), || unreachable!())),
                EXIT_NO_STORE
            );
        }
        assert_eq!(code(save(&store, &hex, &[])), EXIT_NO_STORE);
        assert_eq!(code(save(&store, &hex, &["--replace"])), EXIT_NO_STORE);
        store.unavailable.store(false, SeqCst);
        *store.fail.lock().unwrap() = Some("denied by the store".into());
        for a in [vec!["status"], vec!["remove"]] {
            let e = key_command(&store, &args(&a), || unreachable!()).unwrap_err();
            assert_eq!(e.to_string(), "denied by the store");
            assert_eq!(exit_code(e.as_ref()), 1);
        }
        // A store that gave up waiting reports itself as unavailable (the store enforces its own time limits).
        struct Silent;
        impl SecretStore for Silent {
            fn get(&self) -> std::result::Result<Option<zeroize::Zeroizing<String>>, StoreError> {
                Err(StoreError::Unavailable)
            }
            fn set(&self, _: &str) -> std::result::Result<(), StoreError> {
                Err(StoreError::Unavailable)
            }
            fn remove(&self) -> std::result::Result<(), StoreError> {
                Err(StoreError::Unavailable)
            }
            fn is_locked(&self) -> std::result::Result<bool, StoreError> {
                Err(StoreError::Unavailable)
            }
        }
        assert_eq!(
            code(key_command(&Silent, &args(&["status"]), || unreachable!())),
            EXIT_NO_STORE
        );
        assert_eq!(
            code(key_command(&Silent, &args(&["remove"]), || unreachable!())),
            EXIT_NO_STORE
        );
    }

    #[test]
    fn review_summary_is_short_and_has_no_mapping() {
        let receipt: stack_core::RestoreReceipt = serde_json::from_value(serde_json::json!({
            "operation": "op", "documents_added": 3, "document_collisions": 1, "merged_stacks": 2,
            "source_version": 4, "source_hash": "h", "links_added": 5, "links_skipped": 6,
            "mappings": {"documents": {"a": "b"}, "pages": {}, "stacks": {}, "tags": {}, "scans": {}}
        }))
        .unwrap();
        let v = review_summary(&receipt);
        assert_eq!(v["format"], 4);
        assert_eq!(v["encrypted"], false);
        assert_eq!(v["documents"], 3);
        assert_eq!(v["links_imported"], 5);
        assert_eq!(v["links_skipped"], 6);
        assert_eq!(v["existing_documents_skipped"], 1);
        assert_eq!(v["warnings"].as_array().unwrap().len(), 1);
        assert!(v.get("mappings").is_none());
    }
}
