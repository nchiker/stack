//! Dev-only generator/checker for the archive interop test bed (desktop <-> phone). Never packaged.
//! Synthetic data and the public synthetic TEST_KEY only; no real key store is touched.
//!
//!   archive_interop make OUT                      fresh OUT under /tmp: archives + expected.json
//!   archive_interop check FILE EXPECTED           restore FILE and compare with expected.json
//!   archive_interop check FILE --phone-json JSON  compare with the phone's interop-write output
//!   archive_interop reexport FILE OUT_FILE        restore FILE, export it encrypted with TEST_KEY
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_archive::{Control, Manifest, SourceVerifier, synthetic, write};
use stack_core::{BackupKey, Sort, library::Library};
use stack_render::{Renderer, fixtures};
use std::{
    collections::BTreeMap,
    error::Error,
    fs,
    io::Read,
    path::{Component, Path, PathBuf},
};

type Res<T> = Result<T, Box<dyn Error>>;

/// Public synthetic key: bytes 0x01..0x20 (same as KEY_TEXT in stack-core/tests/links.rs).
const TEST_KEY: &str = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
const OWNER: &str = "org.example.interop";
const MARKER: &str = ".stack-interop-test-bed";

struct Accept;
impl SourceVerifier for Accept {
    fn verify(&self, _: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
        Ok(())
    }
}

fn verifier() -> Box<dyn SourceVerifier> {
    if std::env::var_os("STACK_PDFIUM_LIBRARY").is_some() {
        Box::new(Renderer::from_environment())
    } else {
        Box::new(Accept)
    }
}

fn key() -> BackupKey {
    BackupKey::recovery(TEST_KEY.to_owned())
}

fn sha(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn pdf_pages(bytes: &[u8]) -> usize {
    bytes
        .windows(11)
        .filter(|w| w.starts_with(b"/Type /Page") && w[10] != b's')
        .count()
}

/// Fixed link rows: (n, title, text, has_preview).
const LINKS: [(u128, &str, &str, bool); 3] = [
    (
        1,
        "Interop Alpha Ledger",
        "interopalpha quarterly zebra budget",
        true,
    ),
    (
        2,
        "Interop Beta Memo",
        "interopbeta meeting notes giraffe",
        true,
    ),
    (
        3,
        "Interop Gamma Sketch",
        "interopgamma napkin drawing walrus",
        false,
    ),
];

fn link_row(n: u128, title: &str, text: &str) -> Value {
    json!({"id":synthetic::id(5000 + n),"owner":OWNER,"link_id":synthetic::id(7000 + n),"kind":"doc",
        "title":title,"mime":"application/vnd.example+json","modified":1700000000000_i64,
        "hash":format!("{:064x}", 0xA0A0_0000u128 + n),"shared":0,"protected":1,
        "locators":"[{\"type\":\"filen\",\"uuid\":\"00000000-0000-0000-0000-000000000009\",\"path\":\"/a/b\"}]",
        "state":"live","title_user_edited":0,"added_at":5 + n as i64,"text":text})
}

fn is_safe_out(out: &Path) -> bool {
    out.is_absolute()
        && out.starts_with("/tmp")
        && out.components().all(|c| !matches!(c, Component::ParentDir))
}

fn restore(library: &mut Library, file: &Path, with_key: bool) -> Res<()> {
    let control = Control::default();
    let verifier = verifier();
    let key = key();
    let mut head = [0u8; 8];
    fs::File::open(file)?.read_exact(&mut head)?;
    let encrypted = head == *stack_archive::crypto::ENVELOPE_MAGIC;
    if encrypted && !with_key {
        return Err("encrypted file but no key".into());
    }
    let mut input = fs::File::open(file)?;
    let staged = library.stage_archive_with_key(
        &mut input,
        encrypted.then_some(&key),
        verifier.as_ref(),
        &control,
    )?;
    let plan = library.review_archive(&staged)?;
    library.commit_archive(&plan, &control)?;
    Ok(())
}

fn make(out: &Path) -> Res<()> {
    if !is_safe_out(out) {
        return Err("OUT must be an absolute non-existing path under /tmp".into());
    }
    fs::create_dir(out)?; // fails when it already exists
    fs::write(out.join(MARKER), b"stack-interop-synthetic-only\n")?;
    let m0 = out.join("m0");
    fixtures::create(&m0)?;
    let bytes = fixtures::NAMES
        .map(|n| fs::read(m0.join(n)))
        .into_iter()
        .collect::<std::io::Result<Vec<_>>>()?;
    let preview = fs::read(m0.join("two-pages.pdf"))?;
    let preview_sha = sha(&preview);
    let root = out.join("source");
    let base = synthetic::create(&root, [&bytes[0], &bytes[1], &bytes[2], &bytes[3]])?;
    let mut value = base.into_json();
    value["version"] = json!(4);
    value["links"] = json!(
        LINKS
            .iter()
            .map(|(n, t, x, _)| link_row(*n, t, x))
            .collect::<Vec<_>>()
    );
    fs::create_dir(root.join("previews"))?;
    for (n, _, _, has) in LINKS {
        if has {
            let name = format!("previews/{}.pdf", synthetic::id(5000 + n));
            value["files"]
                .as_array_mut()
                .unwrap()
                .push(json!({"path":name,"length":preview.len(),"sha256":preview_sha}));
            fs::write(root.join(&name), &preview)?;
        }
    }
    let manifest = Manifest::from_value(value)?;
    let control = Control::default();
    let plain_v4 = out.join("desktop-plain-v4.stackbackup");
    let mut file = fs::File::create(&plain_v4)?;
    write(&manifest, &root, &mut file, &control)?;
    drop(file);

    let mut library = Library::open(&out.join("desktop-lib"))?;
    restore(&mut library, &plain_v4, false)?;
    library.export_archive_with_key(&out.join("desktop-plain.stackbackup"), None, &control)?;
    library.export_archive_with_key(
        &out.join("desktop-encrypted.stackbackup"),
        Some(&key()),
        &control,
    )?;

    let mut titles: Vec<String> = manifest
        .rows("documents")
        .iter()
        .map(|d| d["title"].as_str().unwrap().to_owned())
        .collect();
    titles.sort();
    let links: Vec<Value> = LINKS
        .iter()
        .map(|(n, t, x, has)| {
            json!({"owner":OWNER,"link_id":synthetic::id(7000 + n),"title":t,
            "text_marker":x.split(' ').next().unwrap(),"has_preview":has,
            "preview_sha256":has.then_some(preview_sha.clone())})
        })
        .collect();
    let expected = json!({"documents":{"count":titles.len(),"titles":titles},
        "links":links,"preview_pages":pdf_pages(&preview)});
    fs::write(
        out.join("expected.json"),
        serde_json::to_vec_pretty(&expected)?,
    )?;
    println!(
        "{}",
        json!({"ok":true,"out":out,"documents":titles.len(),"links":LINKS.len(),
        "files":["desktop-plain-v4.stackbackup","desktop-plain.stackbackup","desktop-encrypted.stackbackup","expected.json"]})
    );
    Ok(())
}

/// What a restored library holds: document titles and links keyed by (owner, link_id).
struct Seen {
    titles: Vec<String>,
    links: BTreeMap<(String, String), Value>,
}

fn load(file: &Path, temp: &Path) -> Res<(Library, Seen)> {
    let mut library = Library::open(&temp.join("library"))?;
    restore(&mut library, file, true)?;
    let mut titles: Vec<String> = library
        .manifest()?
        .rows("documents")
        .iter()
        .map(|d| d["title"].as_str().unwrap_or_default().to_owned())
        .collect();
    titles.sort();
    let mut links = BTreeMap::new();
    for hit in library
        .search("", None, Sort::Newest, 1000)?
        .iter()
        .filter(|h| h.linked)
    {
        let l = library.linked_document(&hit.document_id)?;
        let (psha, pages) = match &l.preview {
            Some(p) => {
                let b = fs::read(p)?;
                (Some(sha(&b)), Some(pdf_pages(&b)))
            }
            None => (None, None),
        };
        links.insert(
            (l.owner.clone(), l.link_id.clone()),
            json!({"owner":l.owner,"link_id":l.link_id,"title":l.title,"hash":l.hash,"text":l.text,
            "has_preview":psha.is_some(),"preview_sha256":psha,"preview_pages":pages}),
        );
    }
    Ok((library, Seen { titles, links }))
}

fn str_of<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().unwrap_or_default()
}

fn compare_expected(seen: &Seen, expected: &Value, bad: &mut Vec<String>) {
    let want_titles: Vec<&str> = expected["documents"]["titles"]
        .as_array()
        .map(|a| a.iter().filter_map(Value::as_str).collect())
        .unwrap_or_default();
    if seen.titles != want_titles {
        bad.push(format!(
            "documents: got {:?}, want {:?}",
            seen.titles, want_titles
        ));
    }
    if expected["documents"]["count"].as_u64() != Some(seen.titles.len() as u64) {
        bad.push("document count differs".into());
    }
    let want = expected["links"].as_array().cloned().unwrap_or_default();
    if want.len() != seen.links.len() {
        bad.push(format!(
            "link count: got {}, want {}",
            seen.links.len(),
            want.len()
        ));
    }
    for w in &want {
        let id = (
            str_of(w, "owner").to_owned(),
            str_of(w, "link_id").to_owned(),
        );
        let Some(g) = seen.links.get(&id) else {
            bad.push(format!("missing link {}/{}", id.0, id.1));
            continue;
        };
        if g["title"] != w["title"] {
            bad.push(format!("link {} title differs", id.1));
        }
        if !str_of(g, "text").contains(str_of(w, "text_marker")) {
            bad.push(format!("link {} text marker missing", id.1));
        }
        if g["has_preview"] != w["has_preview"] || g["preview_sha256"] != w["preview_sha256"] {
            bad.push(format!("link {} preview differs", id.1));
        }
        if w["has_preview"] == true && g["preview_pages"] != expected["preview_pages"] {
            bad.push(format!("link {} preview page count differs", id.1));
        }
    }
}

fn compare_phone(seen: &Seen, phone: &Value, bad: &mut Vec<String>) {
    let links = phone["links"].as_array().cloned().unwrap_or_default();
    if links.len() != seen.links.len() {
        bad.push(format!(
            "link count: desktop {}, phone {}",
            seen.links.len(),
            links.len()
        ));
    }
    for p in &links {
        let id = (
            str_of(p, "owner").to_owned(),
            str_of(p, "link_id").to_owned(),
        );
        let Some(g) = seen.links.get(&id) else {
            bad.push(format!("phone link {}/{} not on desktop", id.0, id.1));
            continue;
        };
        for k in ["title", "hash", "text"] {
            if !p[k].is_null() && g[k] != p[k] {
                bad.push(format!("link {} {k} differs", id.1));
            }
        }
        let phone_has = p["has_preview"]
            .as_bool()
            .unwrap_or(!p["preview_sha256"].is_null());
        if g["has_preview"] != json!(phone_has) {
            bad.push(format!("link {} has_preview differs", id.1));
        }
        if phone_has && g["preview_sha256"] != p["preview_sha256"] {
            bad.push(format!("link {} preview hash differs", id.1));
        }
    }
    if let Some(docs) = phone["documents"].as_array() {
        let mut want: Vec<String> = docs
            .iter()
            .filter_map(|d| {
                d.as_str()
                    .or_else(|| d["title"].as_str())
                    .map(str::to_owned)
            })
            .collect();
        want.sort();
        if want != seen.titles {
            bad.push(format!(
                "documents: desktop {:?}, phone {:?}",
                seen.titles, want
            ));
        }
    }
}

fn check(file: &Path, mode: &[String]) -> Res<()> {
    let temp = tempfile::tempdir()?;
    let (_library, seen) = load(file, temp.path())?;
    let mut bad = Vec::new();
    let kind = match mode {
        [flag, phone] if flag == "--phone-json" => {
            compare_phone(&seen, &serde_json::from_slice(&fs::read(phone)?)?, &mut bad);
            "phone"
        }
        [expected] => {
            compare_expected(
                &seen,
                &serde_json::from_slice(&fs::read(expected)?)?,
                &mut bad,
            );
            "expected"
        }
        _ => return Err("Usage: check FILE EXPECTED | check FILE --phone-json PHONE_JSON".into()),
    };
    println!(
        "{}",
        json!({"ok":bad.is_empty(),"mode":kind,"documents":seen.titles.len(),
        "links":seen.links.len(),"mismatches":bad})
    );
    if bad.is_empty() {
        Ok(())
    } else {
        Err(format!("{} mismatch(es)", bad.len()).into())
    }
}

fn reexport(file: &Path, out_file: &Path) -> Res<()> {
    let temp = tempfile::tempdir()?;
    let (mut library, seen) = load(file, temp.path())?;
    library.export_archive_with_key(out_file, Some(&key()), &Control::default())?;
    println!(
        "{}",
        json!({"ok":true,"documents":seen.titles.len(),"links":seen.links.len(),"encrypted":true})
    );
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.as_slice() {
        [c, out] if c == "make" => make(&PathBuf::from(out)),
        [c, file, rest @ ..] if c == "check" && !rest.is_empty() => check(Path::new(file), rest),
        [c, file, out] if c == "reexport" => reexport(Path::new(file), Path::new(out)),
        _ => Err("Usage: archive_interop make OUT | check FILE EXPECTED|--phone-json JSON | reexport FILE OUT_FILE".into()),
    };
    if let Err(e) = result {
        eprintln!("archive_interop: {e}");
        std::process::exit(1);
    }
}
