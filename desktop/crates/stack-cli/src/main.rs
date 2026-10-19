mod storage;
use stack_core::{RenderSize, Rotation};
use stack_render::{Renderer, fixtures};
use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let result = run();
    let text_shutdown = stack_render::shutdown_text_worker();
    let shutdown = stack_render::shutdown_pdf_worker();
    result?;
    text_shutdown?;
    shutdown?;
    Ok(())
}

fn run() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.first().is_some_and(|c| {
        c == "sync" || c == "library" || c == "archive" || c == "m1-fixtures" || c == "m1-interrupt"
    }) {
        return storage::run(&args);
    }
    match args.as_slice() {
        [command, directory] if command == "appearance-smoke" => {
            fixtures::appearance_smoke(&PathBuf::from(directory))?;
            println!("Authored saved-appearance fixtures created (CC0-1.0, version 1).");
        }
        [command, directory] if command == "fixtures" => {
            fixtures::create(&PathBuf::from(directory))?;
            println!("Synthetic fixtures created (CC0-1.0, version 1).");
        }
        [command, directory, output] if command == "render-smoke" => {
            let root = PathBuf::from(directory);
            fixtures::verify(&root)?;
            let output = PathBuf::from(output);
            std::fs::create_dir(&output)?;
            let renderer = Renderer::from_environment();
            let mut results = Vec::new();
            for name in fixtures::NAMES {
                let source = root.join(name);
                let before = fixtures::digest(&source)?;
                let pages = if name.ends_with("pdf") { 2 } else { 1 };
                for page in 0..pages {
                    let preview = renderer.render(&source, page, RenderSize::new(600, 800)?, Rotation::None)?;
                    let rendered = format!("{name}-page-{}.png", page + 1);
                    preview.image.save(output.join(&rendered))?;
                    let dimensions = preview.image.dimensions();
                    let expected_landscape = name != "oriented.jpg" && !(name.ends_with("pdf") && page == 0);
                    if (dimensions.0 > dimensions.1) != expected_landscape || preview.page_count != pages {
                        return Err("Preview dimensions/page count do not match fixture expectations".into());
                    }
                    results.push(serde_json::json!({"fixture": name, "page": page + 1,
                        "page_count": preview.page_count, "width": dimensions.0, "height": dimensions.1,
                        "rendered_sha256": fixtures::digest(&output.join(rendered))?}));
                }
                if fixtures::digest(&source)? != before { return Err("Renderer modified an original".into()); }
            }
            let report = serde_json::json!({"milestone": "M0", "fixture_version": 1,
                "originals_unchanged": true, "results": results});
            let bytes = serde_json::to_vec_pretty(&report)?;
            std::fs::write(output.join("report.json"), &bytes)?;
            println!("{}", String::from_utf8(bytes)?);
        }
        _ => return Err("Usage: stack-cli fixtures NEW_DIRECTORY | render-smoke FIXTURE_DIRECTORY NEW_OUTPUT_DIRECTORY".into()),
    }
    Ok(())
}
