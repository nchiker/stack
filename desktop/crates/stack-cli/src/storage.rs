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
/// The key the desktop app saved on this computer. The user asked for it, so a missing key store is an error here
/// (the CLI never falls back to a plain file).
fn saved_key() -> Result<stack_core::BackupKey> {
    use stack_core::keystore::{StoreError, SystemStore, saved_backup_key};
    match saved_backup_key(&SystemStore) {
        Ok(Some(key)) => Ok(key),
        Ok(None) => Err("No backup key is saved on this computer".into()),
        Err(StoreError::Unavailable) => Err(
            "No key store is available on this computer, so the saved key cannot be used".into(),
        ),
        Err(error) => Err(error.into()),
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
/// terminal echo off when it is a terminal.
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
        quiet.local_modes.remove(LocalModes::ECHO);
        tcsetattr(stdin, OptionalActions::Flush, &quiet)?;
        eprint!("{prompt}");
        std::io::stderr().flush()?;
        Some(Restore(original))
    } else {
        None
    };
    let mut bytes = zeroize::Zeroizing::new(Vec::new());
    let mut one = [0u8; 1];
    loop {
        if rustix::io::read(stdin, &mut one)? == 0 || one[0] == b'\n' {
            break;
        }
        if bytes.len() >= 4096 {
            return Err("The key is too long".into());
        }
        bytes.push(one[0]);
    }
    if _restore.is_some() {
        eprintln!();
    }
    String::from_utf8(std::mem::take(&mut *bytes)).map_err(|_| "The key must be UTF-8 text".into())
}
fn visibility(value: &str) -> Result<bool> {
    match value {
        "show" => Ok(true),
        "hide" => Ok(false),
        _ => Err("Use show or hide".into()),
    }
}
fn usage() -> &'static str {
    "Usage: stack-cli library ROOT status|reindex|manifest|journal|search QUERY [STACK]|import FILE TITLE|rename ID TITLE|notes ID TEXT|file ID [STACK...]|detail ID JSON_FILE|remove-detail ID FIELD|delete ID|page-order ID PAGE...|stack-create NAME COLOR show/hide|stack-rename ID NAME|stack-visibility ID show/hide|stack-delete ID|stack-order ID...; stack-cli archive ROOT validate|review|restore FILE [--saved-key|--key-file PATH|--key-stdin|--passphrase-file PATH|--passphrase-stdin] (validate|review print a short summary; add --full for the whole id mapping)|export NEW_FILE [--saved-key]; stack-cli m1-fixtures M0_FIXTURES NEW_DIRECTORY"
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
