//! Format 4 manifest validation and the encrypted end-to-end read. Synthetic data only.
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_archive::crypto::*;
use stack_archive::{
    Control, Error, Manifest, SourceVerifier, read_encrypted_into, read_into, synthetic, write,
};
use std::{fs, io::Cursor, path::Path};

struct Accept;
impl SourceVerifier for Accept {
    fn verify(&self, _: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
        Ok(())
    }
}

fn link(n: u128) -> Value {
    json!({"id":synthetic::id(5000 + n),"owner":"org.example.amipro","link_id":synthetic::id(7000 + n),"kind":"doc",
        "title":format!("Linked {n}"),"mime":"application/vnd.example+json","modified":1700000000000_i64,"hash":"a".repeat(64),
        "shared":0,"protected":1,
        "locators":"[{\"type\":\"filen\",\"uuid\":\"00000000-0000-0000-0000-000000000009\",\"path\":\"/a/b\"},{\"type\":\"saf\",\"uri\":\"content://x\"},{\"type\":\"owner\",\"token\":\"t\"}]",
        "state":"live","title_user_edited":0,"added_at":5,"text":"search text"})
}
fn v3() -> Manifest {
    let dir = tempfile::tempdir().unwrap();
    synthetic::create(&dir.path().join("f"), [b"pdf", b"jpeg", b"png", b"webp"]).unwrap()
}
fn v4(links: Vec<Value>, previews: Vec<(String, i64)>) -> Value {
    let mut value = v3().into_json();
    value["version"] = json!(4);
    value["links"] = json!(links);
    for (id, length) in previews {
        value["files"].as_array_mut().unwrap().push(
            json!({"path":format!("previews/{id}.pdf"),"length":length,"sha256":"c".repeat(64)}),
        );
    }
    value
}
fn ok(value: Value) -> Manifest {
    Manifest::from_value(value).unwrap()
}
#[track_caller]
fn bad(value: Value) {
    assert!(
        Manifest::from_value(value.clone()).is_err(),
        "{}",
        value["links"]
    );
}
fn with(field: &str, new: Value) -> Value {
    let mut l = link(1);
    l[field] = new;
    v4(vec![l], vec![])
}

#[test]
fn format4_accepts_links_and_previews() {
    let m = ok(v4(vec![link(1), link(2)], vec![(synthetic::id(5001), 10)]));
    assert_eq!(m.source_version(), 4);
    assert_eq!(m.rows("links").len(), 2);
    for state in ["live", "unreachable", "missing", "trashed"] {
        ok(with("state", json!(state)));
    }
    ok(v4(vec![], vec![]));
    let mut l = link(1);
    l["text"] = json!("");
    ok(v4(vec![l], vec![]));
    ok(with("text", json!("a".repeat(1 << 20))));
    ok(with("title", json!("t".repeat(200))));
    ok(v4((1..=1000).map(link).collect(), vec![]));
    ok(v4(vec![link(1)], vec![(synthetic::id(5001), 32 << 20)]));
}

#[test]
fn formats_1_to_3_unchanged() {
    assert_eq!(v3().source_version(), 3);
    // A links array or a preview is refused outside format 4.
    let mut v = v3().into_json();
    v["links"] = json!([]);
    bad(v);
    let mut v = v4(vec![link(1)], vec![(synthetic::id(5001), 10)]);
    v["version"] = json!(3);
    bad(v);
    let mut v = v4(vec![], vec![]);
    v["schema"] = json!(7);
    bad(v);
    let mut v = v4(vec![], vec![]);
    v["version"] = json!(5);
    bad(v);
    let mut v = v4(vec![], vec![]);
    v.as_object_mut().unwrap().remove("links");
    bad(v);
}

#[test]
fn each_limit_and_rule_is_refused() {
    bad(with("text", json!("a".repeat((1 << 20) + 1))));
    bad(with("text", json!("nul\0byte")));
    bad(with("title", json!("t".repeat(201))));
    bad(with("title", json!("")));
    bad(with("title", json!("bad\u{202e}title")));
    bad(with("title", json!("tab\there")));
    bad(v4((1..=1001).map(link).collect(), vec![]));
    bad(with("state", json!("deleted")));
    bad(with("state", json!(1)));
    bad(with("id", json!("NOT-A-UUID")));
    bad(with("id", json!("00000000-0000-0000-0000-0000000000AB")));
    bad(with("owner", json!("nodots")));
    bad(with("owner", json!("Org.Example")));
    bad(with("link_id", json!("not-a-uuid")));
    bad(with("kind", json!("Doc")));
    bad(with("kind", json!("a".repeat(17))));
    bad(with("mime", json!("noslash")));
    bad(with("hash", json!("A".repeat(64))));
    bad(with("hash", json!("a".repeat(63))));
    bad(with("modified", json!(-1)));
    bad(with("modified", json!(1.5)));
    bad(with("modified", json!("1")));
    bad(with("shared", json!(2)));
    bad(with("shared", json!(true)));
    bad(with("protected", json!(-1)));
    bad(with("title_user_edited", json!(2)));
    bad(with("added_at", json!(-1)));
    bad(with("locators", json!("[]")));
    bad(with("locators", json!("not json")));
    bad(with("locators", json!("{}")));
    bad(with("locators", json!("[{\"type\":\"ftp\"}]")));
    bad(with(
        "locators",
        json!("[{\"type\":\"saf\",\"uri\":\"x\",\"extra\":1}]"),
    ));
    bad(with(
        "locators",
        json!(format!(
            "[{}]",
            ["{\"type\":\"saf\",\"uri\":\"x\"}"; 9].join(",")
        )),
    ));
    bad(with(
        "locators",
        json!(format!(
            "[{{\"type\":\"saf\",\"uri\":\"{}\"}}]",
            "u".repeat(2049)
        )),
    ));
    bad(with(
        "locators",
        json!(format!(
            "[{{\"type\":\"owner\",\"token\":\"{}\"}}]",
            "u".repeat(513)
        )),
    ));
    bad(with("locators", json!(" ".repeat(16 * 1024 + 1))));
    // Unknown and missing fields, wrong shape, duplicates.
    let mut l = link(1);
    l["extra"] = json!(1);
    bad(v4(vec![l], vec![]));
    let mut l = link(1);
    l.as_object_mut().unwrap().remove("text");
    bad(v4(vec![l], vec![]));
    bad(v4(vec![json!("row")], vec![]));
    bad(v4(vec![link(1), link(1)], vec![]));
    let mut dup = link(2);
    dup["owner"] = link(1)["owner"].clone();
    dup["link_id"] = link(1)["link_id"].clone();
    bad(v4(vec![link(1), dup], vec![]));
}

#[test]
fn preview_rules() {
    let id = synthetic::id(5001);
    bad(v4(vec![link(1)], vec![(id.clone(), 32 * 1024 * 1024 + 1)]));
    bad(v4(vec![link(1)], vec![(id.clone(), 0)]));
    bad(v4(vec![link(1)], vec![(synthetic::id(9999), 10)])); // no such link
    bad(v4(vec![link(1)], vec![(id.clone(), 10), (id.clone(), 10)])); // duplicate
    let mut lower = link(1);
    lower["id"] = json!(synthetic::id(0xab));
    ok(v4(vec![lower.clone()], vec![(synthetic::id(0xab), 10)]));
    bad(v4(
        vec![lower],
        vec![("00000000-0000-0000-0000-0000000000AB".into(), 10)],
    ));
    let mut v = v4(vec![link(1)], vec![(id.clone(), 10)]);
    v["files"][7]["path"] = json!(format!("previews/{id}.png"));
    bad(v);
    let mut v = v4(vec![link(1)], vec![(id.clone(), 10)]);
    v["files"][7]["path"] = json!(format!("previews/x/{id}.pdf"));
    bad(v);
    let mut v = v4(vec![link(1)], vec![(id.clone(), 10)]);
    v["files"][7]["extra"] = json!(1);
    bad(v);
    // Previews count toward the 8 GiB total: 256 x 32 MiB alone is exactly the limit, one more byte is not.
    let links: Vec<_> = (1..=257).map(link).collect();
    let previews = |last: i64| -> Vec<(String, i64)> {
        (1..=256)
            .map(|n| (synthetic::id(5000 + n), 32 << 20))
            .chain([(synthetic::id(5257), last)])
            .collect()
    };
    let base: i64 = v3()
        .rows("files")
        .iter()
        .map(|f| f["length"].as_i64().unwrap())
        .sum();
    assert!(base < 1000);
    bad(v4(links.clone(), previews(1))); // originals plus 256 x 32 MiB plus 1 exceeds 8 GiB
    bad(v4(links, previews(32 << 20)));
}

#[test]
fn previews_are_written_staged_and_read_back() {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("root");
    let base = synthetic::create(&root, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap();
    let id = synthetic::id(5001);
    let pdf = b"%PDF-1.4 synthetic preview";
    let mut value = base.into_json();
    value["version"] = json!(4);
    value["links"] = json!([link(1)]);
    value["files"]
        .as_array_mut()
        .unwrap()
        .push(json!({"path":format!("previews/{id}.pdf"),
        "length":pdf.len(),"sha256":format!("{:x}",Sha256::digest(pdf))}));
    let manifest = ok(value);
    fs::create_dir(root.join("previews")).unwrap();
    fs::write(root.join(format!("previews/{id}.pdf")), pdf).unwrap();
    let mut inner = Vec::new();
    write(&manifest, &root, &mut inner, &Control::default()).unwrap();

    // Plain format 4 stream.
    let staged = read_into(
        &mut Cursor::new(&inner),
        &dir.path().join("plain"),
        &Accept,
        &Control::default(),
    )
    .unwrap();
    assert_eq!(staged.manifest.source_version(), 4);
    assert_eq!(
        fs::read(dir.path().join(format!("plain/previews/{id}.pdf"))).unwrap(),
        pdf
    );

    // Encrypted: key-only envelope.
    let mut key_bytes = [0u8; 32];
    key_bytes
        .iter_mut()
        .enumerate()
        .for_each(|(i, b)| *b = i as u8 + 1);
    let key = RecoveryKey::from_bytes(key_bytes);
    let salt = [3u8; 16];
    let slot = recovery_slot_key(&key, &salt);
    let mut seed = 0u8;
    let mut rand = |b: &mut [u8]| {
        b.iter_mut().for_each(|x| {
            seed = seed.wrapping_add(1);
            *x = seed
        })
    };
    let mut file = Vec::new();
    let mut w = encrypt_to(
        &mut file,
        &salt,
        MIN_ITERATIONS,
        &[Slot {
            slot_type: SLOT_RECOVERY,
            key: &slot,
        }],
        &mut rand,
    )
    .unwrap();
    std::io::Write::write_all(&mut w, &inner).unwrap();
    w.finish().unwrap();
    let staged = read_encrypted_into(
        Cursor::new(&file),
        Secret::Recovery(&key),
        &dir.path().join("enc"),
        &Accept,
        &Control::default(),
    )
    .unwrap();
    assert_eq!(staged.manifest.json(), manifest.json());
    assert!(dir.path().join(format!("enc/previews/{id}.pdf")).is_file());

    // Truncated envelope: nothing is left staged.
    let cut = &file[..file.len() - 1];
    let result = read_encrypted_into(
        Cursor::new(cut),
        Secret::Recovery(&key),
        &dir.path().join("cut"),
        &Accept,
        &Control::default(),
    );
    assert!(matches!(
        result,
        Err(Error::Envelope(EnvelopeError::Damaged(_)))
    ));
    assert!(!dir.path().join("cut").exists());
    // Extended: also refused and removed.
    let mut longer = file.clone();
    longer.push(0);
    assert!(
        read_encrypted_into(
            Cursor::new(longer),
            Secret::Recovery(&key),
            &dir.path().join("long"),
            &Accept,
            &Control::default()
        )
        .is_err()
    );
    assert!(!dir.path().join("long").exists());

    // Inside an envelope the inner manifest must be version 4: a format 3 stream is refused.
    let mut v3_stream = Vec::new();
    let r3 = dir.path().join("r3");
    let m3 = synthetic::create(&r3, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap();
    write(&m3, &r3, &mut v3_stream, &Control::default()).unwrap();
    let mut file3 = Vec::new();
    let mut seed = 0u8;
    let mut rand = |b: &mut [u8]| {
        b.iter_mut().for_each(|x| {
            seed = seed.wrapping_add(1);
            *x = seed
        })
    };
    let mut w = encrypt_to(
        &mut file3,
        &salt,
        MIN_ITERATIONS,
        &[Slot {
            slot_type: SLOT_RECOVERY,
            key: &slot,
        }],
        &mut rand,
    )
    .unwrap();
    std::io::Write::write_all(&mut w, &v3_stream).unwrap();
    w.finish().unwrap();
    assert!(
        read_encrypted_into(
            Cursor::new(file3),
            Secret::Recovery(&key),
            &dir.path().join("v3"),
            &Accept,
            &Control::default()
        )
        .is_err()
    );
    assert!(!dir.path().join("v3").exists());
}

struct NeedsPage;
impl SourceVerifier for NeedsPage {
    fn verify(&self, path: &Path, mime: &str, _: Option<u32>) -> stack_archive::Result<()> {
        if path.to_string_lossy().contains("previews")
            && mime == "application/pdf"
            && !fs::read(path)?.ends_with(b"%%PAGE")
        {
            return Err(Error::Invalid("not a one-page PDF"));
        }
        Ok(())
    }
}

fn staged_with_preview(
    pdf: &[u8],
    name: &str,
) -> (
    tempfile::TempDir,
    stack_archive::Result<stack_archive::StagedArchive>,
) {
    let dir = tempfile::tempdir().unwrap();
    let root = dir.path().join("root");
    let base = synthetic::create(&root, [b"pdf", b"jpeg", b"png", b"webp"]).unwrap();
    let id = synthetic::id(5001);
    let mut value = base.into_json();
    value["version"] = json!(4);
    value["links"] = json!([link(1)]);
    value["files"]
        .as_array_mut()
        .unwrap()
        .push(json!({"path":format!("previews/{id}.pdf"),
        "length":pdf.len(),"sha256":format!("{:x}",Sha256::digest(pdf))}));
    let manifest = ok(value);
    fs::create_dir(root.join("previews")).unwrap();
    fs::write(root.join(format!("previews/{id}.pdf")), pdf).unwrap();
    let mut inner = Vec::new();
    write(&manifest, &root, &mut inner, &Control::default()).unwrap();
    let target = dir.path().join(name);
    let result = read_into(
        &mut Cursor::new(&inner),
        &target,
        &NeedsPage,
        &Control::default(),
    );
    (dir, result)
}

#[test]
fn previews_go_through_the_verifier() {
    let (dir, junk) = staged_with_preview(b"%PDF-junk", "junk");
    assert!(junk.is_err());
    assert!(!dir.path().join("junk").exists(), "staging is cleaned");
    let (_dir, good) = staged_with_preview(b"%PDF-1.4 ok %%PAGE", "good");
    assert!(good.is_ok());
}
