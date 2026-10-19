use serde_json::json;
use stack_archive::{strict_json, synthetic, *};
use std::{
    fs,
    io::{Cursor, Read, Write},
    path::Path,
};

struct SyntheticBytes;
impl SourceVerifier for SyntheticBytes {
    fn verify(&self, path: &Path, mime: &str, pages: Option<u32>) -> Result<()> {
        let expected = match mime {
            "application/pdf" => b"pdf".as_slice(),
            "image/jpeg" => b"jpeg",
            "image/png" => b"png",
            "image/webp" => b"webp",
            _ => return Err(Error::Source("unexpected type".into())),
        };
        if fs::read(path)? != expected || (mime == "application/pdf" && pages != Some(2)) {
            return Err(Error::Source("synthetic original mismatch".into()));
        }
        Ok(())
    }
}
fn fixture(root: &Path) -> Manifest {
    synthetic::create(root, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap()
}

#[test]
fn three_formats_complete_graph_and_immutable_originals_roundtrip() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("fixture");
    let manifest = fixture(&root);
    for version in 1..=3 {
        let target = dir.path().join(format!("staged{version}"));
        let archive = read_into(
            &mut fs::File::open(root.join(format!("format-{version}.stackbackup"))).unwrap(),
            &target,
            &SyntheticBytes,
            &Control::default(),
        )
        .unwrap();
        assert_eq!(archive.manifest.source_version(), version);
        let expected = Manifest::from_value(synthetic::legacy(manifest.json(), version)).unwrap();
        assert_eq!(archive.manifest.json(), expected.json());
        let mut exported = Vec::new();
        write(
            &archive.manifest,
            &target,
            &mut exported,
            &Control::default(),
        )
        .unwrap();
        let roundtrip = read_into(
            &mut Cursor::new(exported),
            &dir.path().join(format!("again{version}")),
            &SyntheticBytes,
            &Control::default(),
        )
        .unwrap();
        assert_eq!(roundtrip.manifest.source_version(), 3);
        assert_eq!(roundtrip.manifest.json(), expected.json());
        for file in expected.rows("files") {
            let path = file["path"].as_str().unwrap();
            assert_eq!(
                fs::read(root.join(path)).unwrap(),
                fs::read(target.join(path)).unwrap()
            );
        }
    }
    assert_eq!(manifest.rows("details")[0]["value"], "00024.500000");
    assert_eq!(manifest.rows("files").len(), 7); // Includes the removed scan original.
}

#[test]
fn strict_json_duplicates_depth_numeric_tokens_and_utf16() {
    for invalid in [
        r#"{"x":1,"\u0078":2}"#,
        r#"{"x":01}"#,
        r#"{"x":true,}"#,
        r#"{"x":NaN}"#,
        r#"{"x":1} x"#,
        r#"{"x":"\ud800"}"#,
    ] {
        assert!(
            strict_json::object(invalid.as_bytes()).is_err(),
            "{invalid}"
        );
    }
    assert_eq!(
        strict_json::object(br#"{"x":-0}"#).unwrap()["x"].as_i64(),
        Some(0)
    );
    assert!(
        strict_json::object(
            format!("{{\"x\":{}}}", "[".repeat(17) + "0" + &"]".repeat(17)).as_bytes()
        )
        .is_err()
    );
    assert!(strict_json::object(format!("{{\"x\":{}}}", "1".repeat(25)).as_bytes()).is_err());
    assert!(
        strict_json::object(format!("{{\"x\":\"{}\"}}", "a".repeat(2 * 1024 * 1024)).as_bytes())
            .is_err()
    );
    assert_eq!(utf16_len("🦀"), 2);
    assert_eq!(clean_name("\t A\n B\u{7f} ").unwrap(), "A  B");
    assert_eq!(java_case_key("İıIſΣςK"), java_case_key("iiisσσk"));
    assert_ne!(java_case_key("ß"), java_case_key("SS"));
}

#[test]
fn metadata_rejections_cover_exact_types_fields_values_and_relationships() {
    let dir = tempfile::tempdir().unwrap();
    let manifest = fixture(&dir.path().join("fixture"));
    let cases: Vec<(&str, serde_json::Value)> = vec![
        ("/version", json!(4)),
        ("/schema", json!(7)),
        ("/documents/0/title", json!(" bad ")),
        ("/documents/0/page_count", json!(true)),
        ("/documents/0/page_count", json!(2.0)),
        ("/documents/0/page_count", json!(1001)),
        ("/documents/0/notes", json!("🦀".repeat(10001))),
        ("/documents/0/mime", json!("image/gif")),
        ("/documents/0/unknown", json!(1)),
        ("/pages/1/position", json!(0)),
        ("/pages/0/document_id", json!(synthetic::id(999))),
        ("/pages/0/rotation", json!(45)),
        ("/pages/0/enhancement", json!("auto")),
        ("/pages/0/crop", json!("[0,0,0,1,1,1,1,0]")),
        ("/pages/0/crop", json!("[0,0,0.01,0,0.01,0.01,0,0.01]")),
        ("/pages/0/ocr_state", json!("done")),
        ("/pages/0/text_revision", json!(3)),
        ("/files/0/path", json!("originals/../source")),
        ("/files/0/length", json!(FILE_LIMIT + 1)),
        ("/files/0/sha256", json!("A".repeat(64))),
        ("/memberships/0/stack_id", json!("missing")),
        ("/details/0/value", json!("1e2")),
        ("/details/0/qualifier", json!("usd")),
        ("/details/0/value", json!("1234567890123456")),
        ("/details/0/value", json!("0.1234567")),
        ("/details/2/value", json!("0000")),
        ("/details/4/value", json!("2023-02-29")),
        ("/details/4/value", json!("1582-10-10")),
        ("/details/8/value", json!("wrong organization")),
        ("/document_tags", json!([])),
        ("/suggestions/0/payload", json!("{\"a\":1,\"a\":2}")),
        ("/stacks/1/name", json!("invoices")),
        ("/stacks/0/sort_order", json!(1)),
    ];
    for (pointer, replacement) in cases {
        let mut value = manifest.json().clone();
        if pointer == "/documents/0/unknown" {
            value["documents"][0]["unknown"] = replacement;
        } else {
            *value.pointer_mut(pointer).unwrap() = replacement;
        }
        assert!(Manifest::from_value(value).is_err(), "accepted {pointer}");
    }
    for date in ["1500-02-29", "1582-10-04", "1582-10-15"] {
        let mut value = manifest.json().clone();
        value["details"][4]["value"] = json!(date);
        Manifest::from_value(value).unwrap();
    }
    let mut v1 = synthetic::legacy(manifest.json(), 1);
    v1["documents"][0]["notes"] = json!("must reject before defaulting");
    assert!(Manifest::from_value(v1).is_err());
}

fn envelope(bytes: &[u8]) -> Vec<u8> {
    use sha2::Digest;
    let mut result = MAGIC.to_vec();
    result.extend_from_slice(&(bytes.len() as i32).to_be_bytes());
    result.extend_from_slice(&sha2::Sha256::digest(bytes));
    result.extend_from_slice(bytes);
    result
}
#[test]
fn envelope_failure_and_cancellation_leave_no_staged_directory() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("fixture");
    fixture(&root);
    let original = fs::read(root.join("format-3.stackbackup")).unwrap();
    let mut inputs = Vec::new();
    for end in [0, 7, 12, 43, original.len() - 1] {
        inputs.push(original[..end].to_vec());
    }
    for at in [0, 12, 45, original.len() - 1] {
        let mut changed = original.clone();
        changed[at] ^= 1;
        inputs.push(changed);
    }
    let mut extra = original.clone();
    extra.push(0);
    inputs.push(extra);
    inputs.push(envelope(br#"{"version":3,"version":3}"#));
    for (n, input) in inputs.into_iter().enumerate() {
        let path = dir.path().join(format!("reject{n}"));
        assert!(
            read_into(
                &mut Cursor::new(input),
                &path,
                &SyntheticBytes,
                &Control::default()
            )
            .is_err()
        );
        assert!(!path.exists());
    }
    let control = Control::default();
    control.cancel();
    let path = dir.path().join("cancel");
    assert!(
        read_into(
            &mut Cursor::new(original.clone()),
            &path,
            &SyntheticBytes,
            &control
        )
        .is_err()
    );
    assert!(!path.exists());
    struct CancelReader {
        inner: Cursor<Vec<u8>>,
        control: Control,
    }
    impl Read for CancelReader {
        fn read(&mut self, b: &mut [u8]) -> std::io::Result<usize> {
            let n = self.inner.read(b)?;
            if self.inner.position() > 100 {
                self.control.cancel();
            }
            Ok(n)
        }
    }
    let control = Control::default();
    let path = dir.path().join("mid-cancel");
    assert!(
        read_into(
            &mut CancelReader {
                inner: Cursor::new(original),
                control: control.clone()
            },
            &path,
            &SyntheticBytes,
            &control
        )
        .is_err()
    );
    assert!(!path.exists());
}

#[test]
fn symlinks_changed_sources_disk_reserve_and_failing_writer_are_rejected() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("fixture");
    let manifest = fixture(&root);
    let first = root.join(manifest.rows("files")[0]["path"].as_str().unwrap());
    let moved = root.join("moved");
    fs::rename(&first, &moved).unwrap();
    std::os::unix::fs::symlink(&moved, &first).unwrap();
    assert!(write(&manifest, &root, &mut Vec::new(), &Control::default()).is_err());
    fs::remove_file(&first).unwrap();
    fs::rename(&moved, &first).unwrap();
    struct Full;
    impl Write for Full {
        fn write(&mut self, _: &[u8]) -> std::io::Result<usize> {
            Err(std::io::Error::from_raw_os_error(28))
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
    assert!(write(&manifest, &root, &mut Full, &Control::default()).is_err());
    assert!(reserve_space(&root, u64::MAX - 16 * 1024 * 1024).is_err());
    fs::write(first, b"changed").unwrap();
    assert!(write(&manifest, &root, &mut Vec::new(), &Control::default()).is_err());
}
