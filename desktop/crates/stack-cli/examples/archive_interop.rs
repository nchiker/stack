//! Dev-only generator/checker for the archive interop test bed (desktop <-> phone). Never packaged.
//! Synthetic data and the public synthetic TEST_KEY only; no real key store is touched.
//!
//!   archive_interop make OUT                      fresh OUT under /tmp: archives + expected.json
//!   archive_interop check FILE EXPECTED           restore FILE and compare with expected.json
//!   archive_interop check FILE --phone-json JSON  compare with the phone's interop-write output
//!   archive_interop reexport FILE OUT_FILE        restore FILE, export it encrypted with TEST_KEY
//!   archive_interop check FILE EXPECTED|--phone-json JSON [--test-passphrase]   open an encrypted FILE with TEST_PASSPHRASE
//!   archive_interop make-large OUT                fresh OUT under /tmp: 40 documents, 1000 links, a near-32-MiB preview,
//!                                                 refusal variants (1001 links, preview over 32 MiB), expected-large.json
//!   archive_interop export LIB OUT_FILE [--encrypted]   export an existing library (the timed desktop export)
//! `make` also writes the no-link files (format 3 plain, format 4 encrypted with an empty links array) and expected-nolinks.json.
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_archive::{Control, Manifest, SourceVerifier, links as link_rules, synthetic, write};
use stack_core::{BackupKey, Sort, library::Library};
use stack_render::{Renderer, fixtures};
use std::{
    collections::BTreeMap,
    error::Error,
    fs,
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    time::Instant,
};

type Res<T> = Result<T, Box<dyn Error>>;

/// Public synthetic key: bytes 0x01..0x20 (same as KEY_TEXT in stack-core/tests/links.rs).
const TEST_KEY: &str = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";
/// Public synthetic passphrase. Deliberately not NFC: "e" + U+0301 and "u" + U+0308 compose to different bytes, so a
/// reader that skips normalisation cannot open a file whose key was derived from the NFC form (and vice versa).
const TEST_PASSPHRASE: &str = "Cafe\u{301} Stack u\u{308}ber 7";
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

fn restore(library: &mut Library, file: &Path, passphrase: bool) -> Res<()> {
    let control = Control::default();
    let verifier = verifier();
    let key = if passphrase {
        BackupKey::passphrase(TEST_PASSPHRASE.to_owned())
    } else {
        key()
    };
    let mut head = [0u8; 8];
    fs::File::open(file)?.read_exact(&mut head)?;
    let encrypted = head == *stack_archive::crypto::ENVELOPE_MAGIC;
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
    let base_json = base.into_json();
    let mut value = base_json.clone();
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

    // No-link files: a library with zero links, exported plain (format 3) and encrypted (format 4, empty links array).
    let nolinks_source = out.join("desktop-nolinks-source-v3.stackbackup");
    let mut file = fs::File::create(&nolinks_source)?;
    write(
        &Manifest::from_value(base_json)?,
        &root,
        &mut file,
        &control,
    )?;
    drop(file);
    let mut nolinks = Library::open(&out.join("desktop-nolinks-lib"))?;
    restore(&mut nolinks, &nolinks_source, false)?;
    if nolinks.link_count()? != 0 {
        return Err("the no-link library has links".into());
    }
    nolinks.export_archive_with_key(
        &out.join("desktop-nolinks-plain.stackbackup"),
        None,
        &control,
    )?;
    nolinks.export_archive_with_key(
        &out.join("desktop-nolinks-encrypted.stackbackup"),
        Some(&key()),
        &control,
    )?;
    if inner_version(&out.join("desktop-nolinks-plain.stackbackup"))? != 3 {
        return Err("the plain no-link export is not format 3".into());
    }

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
    let expected_nolinks = json!({"documents":{"count":titles.len(),"titles":titles},
        "links":[],"preview_pages":pdf_pages(&preview)});
    fs::write(
        out.join("expected-nolinks.json"),
        serde_json::to_vec_pretty(&expected_nolinks)?,
    )?;
    println!(
        "{}",
        json!({"ok":true,"out":out,"documents":titles.len(),"links":LINKS.len(),
        "files":["desktop-plain-v4.stackbackup","desktop-plain.stackbackup","desktop-encrypted.stackbackup","expected.json",
        "desktop-nolinks-plain.stackbackup","desktop-nolinks-encrypted.stackbackup","expected-nolinks.json"]})
    );
    Ok(())
}

/// Manifest `version` of a plain (unencrypted) archive file.
fn inner_version(file: &Path) -> Res<u64> {
    let bytes = fs::read(file)?;
    let (manifest, _) = split_plain(&bytes)?;
    Ok(manifest["version"].as_u64().unwrap_or(0))
}

/// Splits a plain archive into its manifest and the entry bytes after it: magic, i32 length, SHA-256, manifest, entries.
fn split_plain(bytes: &[u8]) -> Res<(Value, &[u8])> {
    if bytes.len() < 44 || &bytes[..8] != stack_archive::MAGIC {
        return Err("not a plain archive".into());
    }
    let length = i32::from_be_bytes(bytes[8..12].try_into()?) as usize;
    let manifest = bytes
        .get(44..44 + length)
        .ok_or("truncated archive manifest")?;
    if Sha256::digest(manifest).as_slice() != &bytes[12..44] {
        return Err("archive manifest hash differs".into());
    }
    Ok((serde_json::from_slice(manifest)?, &bytes[44 + length..]))
}

/// What a restored library holds: document titles and links keyed by (owner, link_id).
struct Seen {
    titles: Vec<String>,
    links: BTreeMap<(String, String), Value>,
}

/// `probes` are extra search words (the expected link markers): an empty search is capped at 1000 hits, documents
/// included, so a library with 1000 links and 40 documents cannot be listed by it alone.
fn load(file: &Path, temp: &Path, passphrase: bool, probes: &[String]) -> Res<(Library, Seen)> {
    let mut library = Library::open(&temp.join("library"))?;
    restore(&mut library, file, passphrase)?;
    let mut titles: Vec<String> = library
        .manifest()?
        .rows("documents")
        .iter()
        .map(|d| d["title"].as_str().unwrap_or_default().to_owned())
        .collect();
    titles.sort();
    let mut links = BTreeMap::new();
    let mut hits = library.search("", None, Sort::Newest, 1000)?;
    for word in probes {
        hits.extend(library.search(word, None, Sort::Newest, 5)?);
    }
    for hit in hits.iter().filter(|h| h.linked) {
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
        let pages = w.get("preview_pages").unwrap_or(&expected["preview_pages"]);
        if w["has_preview"] == true && &g["preview_pages"] != pages {
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
    let passphrase = mode.last().is_some_and(|m| m == "--test-passphrase");
    let mode = if passphrase {
        &mode[..mode.len() - 1]
    } else {
        mode
    };
    let temp = tempfile::tempdir()?;
    let wanted: Option<Value> = match mode {
        [expected] => Some(serde_json::from_slice(&fs::read(expected)?)?),
        _ => None,
    };
    let probes: Vec<String> = wanted
        .iter()
        .flat_map(|w| w["links"].as_array().into_iter().flatten())
        .filter_map(|l| l["text_marker"].as_str().map(str::to_owned))
        .collect();
    let (_library, seen) = load(file, temp.path(), passphrase, &probes)?;
    let mut bad = Vec::new();
    let kind = match mode {
        [flag, phone] if flag == "--phone-json" => {
            compare_phone(&seen, &serde_json::from_slice(&fs::read(phone)?)?, &mut bad);
            "phone"
        }
        [_] => {
            compare_expected(&seen, wanted.as_ref().ok_or("no expected")?, &mut bad);
            "expected"
        }
        _ => {
            return Err(
                "Usage: check FILE EXPECTED | check FILE --phone-json PHONE_JSON [--test-passphrase]"
                    .into(),
            );
        }
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
    let (mut library, seen) = load(file, temp.path(), false, &[])?;
    library.export_archive_with_key(out_file, Some(&key()), &Control::default())?;
    println!(
        "{}",
        json!({"ok":true,"documents":seen.titles.len(),"links":seen.links.len(),"encrypted":true})
    );
    Ok(())
}

// ------------------------------------------------------------------ size and limits (make-large)

const LARGE_DOCS: usize = 40;
const LARGE_LINKS: usize = 1000;
/// The link that carries the near-cap preview (just under 32 MiB).
const BIG_LINK: usize = 500;
const BIG_PREVIEW: usize = link_rules::MAX_PREVIEW as usize - 1024;
/// The link whose saved text is as long as the format allows (a little under 1 MiB).
const BIG_TEXT_LINK: usize = 250;

fn assemble_pdf(objects: &[Vec<u8>]) -> Vec<u8> {
    let mut bytes = b"%PDF-1.4\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(bytes.len());
        bytes.extend(format!("{} 0 obj\n", index + 1).as_bytes());
        bytes.extend(object);
        bytes.extend(b"\nendobj\n");
    }
    let xref = bytes.len();
    bytes.extend(format!("xref\n0 {}\n0000000000 65535 f \n", offsets.len() + 1).as_bytes());
    for offset in offsets {
        bytes.extend(format!("{offset:010} 00000 n \n").as_bytes());
    }
    bytes.extend(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n",
            objects.len() + 1
        )
        .as_bytes(),
    );
    bytes
}

fn stream_object(body: &[u8]) -> Vec<u8> {
    let mut object = format!("<< /Length {} >>\nstream\n", body.len()).into_bytes();
    object.extend(body);
    object.extend(b"\nendstream");
    object
}

/// (`pdf_pages` counts the `/Type /Page` spelling, so the page tree is written `/Type/Pages`.)
/// A readable PDF of exactly `size` bytes: one empty page plus an unreferenced stream of deterministic letters
/// (xorshift, so it does not compress and never contains `endstream` or `/Type /Page`). Nothing is stored in the repo.
fn big_pdf(size: usize) -> Vec<u8> {
    let build = |filler: usize| {
        let mut state = 0x9E37_79B9_7F4A_7C15_u64;
        let body: Vec<u8> = (0..filler)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 7;
                state ^= state << 17;
                b'a' + (state % 26) as u8
            })
            .collect();
        assemble_pdf(&[
            b"<< /Type /Catalog /Pages 2 0 R >>".to_vec(),
            b"<< /Type/Pages /Kids [3 0 R] /Count 1 >>".to_vec(),
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>".to_vec(),
            stream_object(&body),
        ])
    };
    let mut filler = size.saturating_sub(build(0).len());
    for _ in 0..4 {
        let total = build(filler).len();
        if total == size {
            break;
        }
        filler = (filler + size).saturating_sub(total);
    }
    let bytes = build(filler);
    assert_eq!(bytes.len(), size, "big_pdf size");
    bytes
}

/// A multi-page text PDF (uncompressed, about 4 KiB of text per page) for the large library's documents.
fn text_pdf(label: &str, pages: usize) -> Vec<u8> {
    let mut objects = vec![
        b"<< /Type /Catalog /Pages 2 0 R >>".to_vec(),
        Vec::new(),
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
    ];
    let mut kids = String::new();
    for page in 0..pages {
        kids += &format!("{} 0 R ", 4 + page * 2);
        objects.push(
            format!("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {} 0 R >>", 5 + page * 2).into_bytes(),
        );
        let mut text = String::new();
        for line in 0..60 {
            text += &format!(
                "BT /F1 9 Tf 20 {} Td ({label} page {page} line {line} ledger entry lorem ipsum dolor sit amet consectetur) Tj ET\n",
                780 - line * 12
            );
        }
        objects.push(stream_object(text.as_bytes()));
    }
    objects[1] = format!("<< /Type/Pages /Kids [{kids}] /Count {pages} >>").into_bytes();
    assemble_pdf(&objects)
}

fn utf16(text: &str) -> usize {
    text.encode_utf16().count()
}

/// Titles that exercise NFC accents, emoji, CJK and the 200 UTF-16 unit limit.
fn large_title(n: usize) -> String {
    let title = match n % 5 {
        0 => format!("Large link {n:04} quarterly ledger"),
        1 => format!("Caf\u{e9} r\u{e9}sum\u{e9} na\u{ef}ve {n:04} \u{dc}n\u{ef}c\u{f6}d\u{e9}"),
        2 => format!("\u{1F4C4} Report {n:04} \u{1F980}\u{1F680} \u{2705}"),
        3 => format!(
            "\u{56DB}\u{534A}\u{671F}\u{4E88}\u{7B97}\u{5831}\u{544A}\u{66F8} {n:04} \u{65E5}\u{672C}\u{8A9E}\u{4E2D}\u{6587}\u{6807}\u{9898} \u{D55C}\u{AD6D}\u{C5B4}"
        ),
        _ if n % 50 == 4 => {
            // Exactly the limit: CJK filler plus five emoji (two UTF-16 units each).
            let mut text = format!("{n:04} ");
            while utf16(&text) < 190 {
                text.push('\u{754C}');
            }
            text + &"\u{1F600}".repeat(5)
        }
        _ => format!("Mixed {n:04} \u{1F4DA} \u{5B66}\u{7FD2} caf\u{e9}"),
    };
    debug_assert!(utf16(&title) <= link_rules::MAX_TITLE);
    title
}

fn large_text(n: usize) -> String {
    let mut text = format!("largemark{n:04} ");
    let words = ["ledger", "invoice", "quarter", "budget", "harbor", "violet"];
    let target = if n == BIG_TEXT_LINK { 1_000_000 } else { 6000 };
    let mut i = n;
    while text.len() < target {
        text += words[i % words.len()];
        text.push(' ');
        i += 1;
    }
    text.truncate(target);
    text
}

fn large_link_row(n: usize, title: &str) -> Value {
    let mut row = link_row(n as u128, title, &large_text(n));
    row["hash"] = json!(format!("{:064x}", 0xB0B0_0000u128 + n as u128));
    row
}

/// Appends links (and their preview files) to a plain archive: header and file table are rebuilt, entries are copied.
/// With `validate` the result must pass the reader's own manifest checks; the refusal variants skip it on purpose.
fn augment(
    base: &Path,
    out: &Path,
    links: Vec<Value>,
    previews: &[(String, &[u8])],
    validate: bool,
) -> Res<()> {
    let bytes = fs::read(base)?;
    let (mut value, entries) = split_plain(&bytes)?;
    value["version"] = json!(4);
    value["links"] = json!(links);
    for (path, body) in previews {
        value["files"]
            .as_array_mut()
            .ok_or("no files")?
            .push(json!({"path":path,"length":body.len(),"sha256":sha(body)}));
    }
    if validate {
        Manifest::from_value(value.clone())?;
    }
    let manifest = serde_json::to_vec(&value)?;
    let mut file = std::io::BufWriter::new(fs::File::create(out)?);
    file.write_all(stack_archive::MAGIC)?;
    file.write_all(&(manifest.len() as i32).to_be_bytes())?;
    file.write_all(&Sha256::digest(&manifest))?;
    file.write_all(&manifest)?;
    file.write_all(entries)?;
    for (path, body) in previews {
        file.write_all(&(path.len() as u16).to_be_bytes())?;
        file.write_all(path.as_bytes())?;
        file.write_all(&(body.len() as i64).to_be_bytes())?;
        file.write_all(body)?;
    }
    file.flush()?;
    Ok(())
}

fn ms(start: Instant) -> u128 {
    start.elapsed().as_millis()
}

fn make_large(out: &Path) -> Res<()> {
    if !is_safe_out(out) {
        return Err("OUT must be an absolute non-existing path under /tmp".into());
    }
    fs::create_dir(out)?;
    fs::write(out.join(MARKER), b"stack-interop-synthetic-only\n")?;
    let control = Control::default();
    let mut times = serde_json::Map::new();

    // 40 documents with real multi-page content, imported the normal way, exported as a plain format-3 base.
    let started = Instant::now();
    let verifier = verifier();
    let mut library = Library::open(&out.join("docs-lib"))?;
    let scratch = out.join("docs-src");
    fs::create_dir(&scratch)?;
    let mut titles = Vec::new();
    for i in 0..LARGE_DOCS {
        let pages = 3 + (i * 7) % 38;
        let title = format!("Large document {i:02}");
        let path = scratch.join(format!("doc-{i:02}.pdf"));
        fs::write(&path, text_pdf(&title, pages))?;
        library.import_original(
            &path,
            &title,
            verifier.as_ref(),
            "application/pdf",
            pages as u32,
            &control,
        )?;
        fs::remove_file(&path)?;
        titles.push(title);
    }
    titles.sort();
    let base = out.join("docs-v3.stackbackup");
    library.export_archive_with_key(&base, None, &control)?;
    drop(library);
    times.insert("documents_ms".into(), json!(ms(started)));

    // The links: 1000 (the limit), a preview on every 25th, one near the 32 MiB cap, one with ~1 MB of text.
    let started = Instant::now();
    let m0 = out.join("m0");
    fixtures::create(&m0)?;
    let small = fs::read(m0.join("two-pages.pdf"))?;
    let small_sha = sha(&small);
    let small_pages = pdf_pages(&small);
    let big = big_pdf(BIG_PREVIEW);
    let big_sha = sha(&big);
    let mut rows = Vec::new();
    let mut previews: Vec<(String, &[u8])> = Vec::new();
    let mut expected_links = Vec::new();
    for n in 1..=LARGE_LINKS {
        let title = large_title(n);
        let body: Option<&[u8]> = if n == BIG_LINK {
            Some(&big)
        } else if n % 25 == 0 {
            Some(&small)
        } else {
            None
        };
        rows.push(large_link_row(n, &title));
        if let Some(body) = body {
            previews.push((
                format!("previews/{}.pdf", synthetic::id(5000 + n as u128)),
                body,
            ));
        }
        expected_links.push(
            json!({"owner":OWNER,"link_id":synthetic::id(7000 + n as u128),"title":title,
            "text_marker":format!("largemark{n:04}"),"has_preview":body.is_some(),
            "preview_sha256":body.map(sha),
            "preview_pages":body.map(|b| if n == BIG_LINK {1} else {pdf_pages(b)})}),
        );
    }
    let source = out.join("desktop-large-source-v4.stackbackup");
    augment(&base, &source, rows, &previews, true)?;
    times.insert("build_archive_ms".into(), json!(ms(started)));

    // Refusal variants: one link too many, and a preview one byte over the cap. Not validated on purpose.
    let started = Instant::now();
    let too_many: Vec<Value> = (1..=LARGE_LINKS + 1)
        .map(|n| large_link_row(n, &format!("Over the limit {n:04}")))
        .collect();
    augment(
        &base,
        &out.join("refuse-links-1001.stackbackup"),
        too_many,
        &[],
        false,
    )?;
    let over = big_pdf(link_rules::MAX_PREVIEW as usize + 1);
    augment(
        &base,
        &out.join("refuse-preview-over.stackbackup"),
        vec![large_link_row(1, "Preview over the cap")],
        &[(format!("previews/{}.pdf", synthetic::id(5001)), &over)],
        false,
    )?;
    times.insert("refusal_variants_ms".into(), json!(ms(started)));

    // The desktop library the timed export runs on: the real restore path.
    let started = Instant::now();
    let mut target = Library::open(&out.join("large-lib"))?;
    restore(&mut target, &source, false)?;
    if target.link_count()? != LARGE_LINKS {
        return Err("the large library lost links".into());
    }
    times.insert("restore_source_ms".into(), json!(ms(started)));

    let expected = json!({"documents":{"count":titles.len(),"titles":titles},"links":expected_links,
        "preview_pages":small_pages,
        "large":{"big_link_id":synthetic::id(7000 + BIG_LINK as u128),"big_preview_bytes":big.len(),
        "big_preview_sha256":big_sha,"small_preview_sha256":small_sha,"limit_links":LARGE_LINKS,
        "limit_preview_bytes":link_rules::MAX_PREVIEW}});
    fs::write(
        out.join("expected-large.json"),
        serde_json::to_vec(&expected)?,
    )?;
    println!(
        "{}",
        json!({"ok":true,"out":out,"documents":LARGE_DOCS,"links":LARGE_LINKS,"big_preview_bytes":big.len(),
        "source_bytes":fs::metadata(&source)?.len(),"times_ms":times,"library":"large-lib",
        "files":["docs-v3.stackbackup","desktop-large-source-v4.stackbackup","refuse-links-1001.stackbackup",
        "refuse-preview-over.stackbackup","expected-large.json"]})
    );
    Ok(())
}

/// Exports an existing library (plain, or encrypted with TEST_KEY) and reports the time of the export alone.
fn export(lib: &Path, out_file: &Path, encrypted: bool) -> Res<()> {
    if !is_safe_out(lib) || !is_safe_out(out_file) {
        return Err("LIB and OUT_FILE must be absolute paths under /tmp".into());
    }
    let mut library = Library::open(lib)?;
    let started = Instant::now();
    library.export_archive_with_key(out_file, encrypted.then(key).as_ref(), &Control::default())?;
    println!(
        "{}",
        json!({"ok":true,"encrypted":encrypted,"bytes":fs::metadata(out_file)?.len(),"export_ms":ms(started)})
    );
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.as_slice() {
        [c, out] if c == "make" => make(&PathBuf::from(out)),
        [c, out] if c == "make-large" => make_large(&PathBuf::from(out)),
        [c, lib, out] if c == "export" => export(Path::new(lib), Path::new(out), false),
        [c, lib, out, flag] if c == "export" && flag == "--encrypted" => {
            export(Path::new(lib), Path::new(out), true)
        }
        [c, file, rest @ ..] if c == "check" && !rest.is_empty() => check(Path::new(file), rest),
        [c, file, out] if c == "reexport" => reexport(Path::new(file), Path::new(out)),
        _ => Err("Usage: archive_interop make OUT | make-large OUT | check FILE EXPECTED|--phone-json JSON [--test-passphrase] | reexport FILE OUT_FILE | export LIB OUT_FILE [--encrypted]".into()),
    };
    if let Err(e) = result {
        eprintln!("archive_interop: {e}");
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn big_pdf_is_exact_and_plain() {
        for size in [4000usize, 70_001, 1 << 20] {
            let pdf = big_pdf(size);
            assert_eq!(pdf.len(), size);
            assert!(pdf.starts_with(b"%PDF-"));
            assert_eq!(pdf_pages(&pdf), 1);
            assert_eq!(pdf, big_pdf(size), "deterministic");
        }
    }

    #[test]
    fn cap_arithmetic() {
        assert!(BIG_PREVIEW < link_rules::MAX_PREVIEW as usize);
        assert!(BIG_PREVIEW + 4096 > link_rules::MAX_PREVIEW as usize);
        assert_eq!(LARGE_LINKS, link_rules::LINK_LIMIT);
    }

    #[test]
    fn large_titles_and_texts_obey_the_format() {
        let mut longest = 0;
        for n in 1..=LARGE_LINKS {
            let title = large_title(n);
            longest = longest.max(utf16(&title));
            assert!(
                !title.is_empty() && utf16(&title) <= link_rules::MAX_TITLE,
                "{n}"
            );
            assert!(large_text(n).len() <= link_rules::MAX_TEXT);
            assert!(large_text(n).starts_with(&format!("largemark{n:04} ")));
        }
        assert_eq!(longest, link_rules::MAX_TITLE);
        assert!(!(1..=LARGE_LINKS).any(|n| large_title(n).chars().any(char::is_control)));
        let rows: Vec<Value> = (1..=LARGE_LINKS)
            .map(|n| large_link_row(n, &large_title(n)))
            .collect();
        link_rules::validate(&rows).expect("rows pass the reader's link rules");
    }

    #[test]
    fn passphrase_is_not_already_nfc() {
        assert!(!TEST_PASSPHRASE.is_ascii());
        assert_ne!(TEST_PASSPHRASE, "Caf\u{e9} Stack \u{fc}ber 7");
    }

    #[test]
    fn text_pdf_counts_pages() {
        let pdf = text_pdf("t", 7);
        assert_eq!(pdf_pages(&pdf), 7);
        assert!(pdf.starts_with(b"%PDF-"));
    }

    #[test]
    fn out_paths_must_be_under_tmp() {
        assert!(is_safe_out(Path::new("/tmp/x")));
        assert!(!is_safe_out(Path::new("/home/x")));
        assert!(!is_safe_out(Path::new("/tmp/../etc")));
        assert!(!is_safe_out(Path::new("tmp/x")));
    }
}
