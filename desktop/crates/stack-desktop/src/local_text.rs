//! Reading the text of an Ami Pro file on this computer (`.sam` and `.amipro`), read-only, so a document edited here
//! can be found by search before the phone saves it again. Uses the vendored Ami Pro for Linux importer
//! (`third_party/amipro`, MPL-2.0). The file is only ever read. A password-protected or unreadable file gives `None`.
use std::path::Path;

/// Files larger than this are not read for text (the importer reads the whole file into memory).
const LARGEST_FILE: u64 = 32 << 20;

/// All text of a document, one line per paragraph: the body, then text frames, table cells and the other flows,
/// in the importer's own flow order. `None` when the file is not a readable Ami Pro document.
pub fn extract(path: &Path) -> Option<String> {
    let meta = std::fs::metadata(path).ok()?;
    if !meta.is_file() || meta.len() > LARGEST_FILE {
        return None;
    }
    let extension = path.extension()?.to_string_lossy().to_lowercase();
    let document = match extension.as_str() {
        "sam" => amipro_formats::import_sam_path(path).ok()?.document,
        "amipro" => amipro_document::Document::load_native(path).ok()?,
        _ => return None,
    };
    let main = document.main_flow_id();
    let mut parts = vec![document.main_flow().plain_text()];
    parts.extend(
        document
            .flows()
            .filter(|(id, _)| *id != main)
            .map(|(_, flow)| flow.plain_text()),
    );
    let text = parts
        .into_iter()
        .filter(|part| !part.trim().is_empty())
        .collect::<Vec<_>>()
        .join("\n");
    Some(text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_body_and_the_frame_text_of_a_synthetic_sam() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("Frames.SAM");
        std::fs::write(&file, include_bytes!("../testdata/pages-and-frames.sam")).unwrap();
        let before = std::fs::read(&file).unwrap();
        let text = extract(&file).expect("text");
        assert!(text.contains("Anchored"), "{text}");
        assert!(text.contains("Grouped"), "{text}");
        assert_eq!(std::fs::read(&file).unwrap(), before, "never writes");
    }

    #[test]
    fn other_files_and_junk_give_no_text() {
        let dir = tempfile::tempdir().unwrap();
        let junk = dir.path().join("a.sam");
        std::fs::write(&junk, b"not an ami pro file").unwrap();
        assert_eq!(extract(&junk), None);
        let other = dir.path().join("a.txt");
        std::fs::write(&other, b"x").unwrap();
        assert_eq!(extract(&other), None);
        assert_eq!(extract(&dir.path().join("missing.sam")), None);
        assert_eq!(extract(dir.path()), None);
    }
}
