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
        ("archive", "validate" | "review" | "restore", [input]) => {
            let staged = library.stage_archive(
                &mut stack_archive::read_regular(Path::new(input))?,
                &renderer,
                &control,
            )?;
            let plan = library.review_archive(&staged)?;
            if action == "restore" {
                report(&library.commit_archive(&plan, &control)?)
            } else {
                report(plan.review())
            }
        }
        ("archive", "export", [output]) => {
            library.export_archive(Path::new(output), &control)?;
            println!("Archive published.");
            Ok(())
        }
        _ => Err(usage().into()),
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
    "Usage: stack-cli library ROOT status|reindex|manifest|journal|search QUERY [STACK]|import FILE TITLE|rename ID TITLE|notes ID TEXT|file ID [STACK...]|detail ID JSON_FILE|remove-detail ID FIELD|delete ID|page-order ID PAGE...|stack-create NAME COLOR show/hide|stack-rename ID NAME|stack-visibility ID show/hide|stack-delete ID|stack-order ID...; stack-cli archive ROOT validate|review|restore FILE|export NEW_FILE; stack-cli m1-fixtures M0_FIXTURES NEW_DIRECTORY"
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
