//! Dev-only desktop helper for the real-LAN link sync harness (desktop/tools/test_android_link_lan.py). Never packaged.
//! Synthetic data only; no key store, no real library. Every path must be absolute and under /tmp.
//!
//!   link_lan make LIB DESKTOP_LINKS   fresh library LIB (restored from a synthetic archive that carries DESKTOP_LINKS links,
//!                                     link n has link id 7000+n, title "Desktop link n", hash 0xD0D0_0000+n, modified 1700000000000)
//!   link_lan dump LIB                 one JSON line: counts and, per link, title, state, origin, text sha256, preview sha256/length
//! The numbers and ids match `LinkLanChecks.java`, so a phone link with the same id and `modified` but another hash is a real tie.
use rusqlite::{Connection, OpenFlags};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use stack_archive::{Control, Manifest, SourceVerifier, synthetic, write};
use stack_core::library::Library;
use std::{
    error::Error,
    fs,
    io::Cursor,
    path::{Component, Path},
};

type Res<T> = Result<T, Box<dyn Error>>;
const OWNER: &str = "org.example.amipro";
const MODIFIED: i64 = 1_700_000_000_000;

struct Accept;
impl SourceVerifier for Accept {
    fn verify(&self, _: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
        Ok(())
    }
}

fn sha(bytes: &[u8]) -> String {
    format!("{:x}", Sha256::digest(bytes))
}

fn safe(path: &Path) -> bool {
    path.is_absolute()
        && path.starts_with("/tmp")
        && path.components().all(|c| !matches!(c, Component::ParentDir))
}

fn desktop_link(n: u128) -> Value {
    json!({"id":synthetic::id(5000 + n),"owner":OWNER,"link_id":synthetic::id(7000 + n),"kind":"doc",
        "title":format!("Desktop link {n}"),"mime":"application/x-amipro-document","modified":MODIFIED,
        "hash":format!("{:064x}", 0xD0D0_0000u128 + n),"shared":0,"protected":0,
        "locators":"[{\"type\":\"filen\",\"uuid\":\"00000000-0000-0000-0000-000000000009\",\"path\":\"/a/b\"}]",
        "state":"live","title_user_edited":0,"added_at":5 + n as i64,"text":format!("Synthetic desktop text {n}")})
}

fn make(lib: &Path, links: u128) -> Res<()> {
    let work = tempdir_beside(lib)?;
    let root = work.join("source");
    let mut value = synthetic::create(&root, [b"pdf", b"jpeg", b"png", b"webp"])?.into_json();
    if links > 0 {
        value["version"] = json!(4);
        value["links"] = json!((1..=links).map(desktop_link).collect::<Vec<_>>());
    }
    let manifest = Manifest::from_value(value)?;
    let mut bytes = Vec::new();
    write(&manifest, &root, &mut bytes, &Control::default())?;
    let mut library = Library::open(lib)?;
    let control = Control::default();
    let staged = library.stage_archive_with_key(&mut Cursor::new(bytes), None, &Accept, &control)?;
    let plan = library.review_archive(&staged)?;
    library.commit_archive(&plan, &control)?;
    fs::remove_dir_all(work)?;
    println!("{}", json!({"made": true, "desktop_links": links}));
    Ok(())
}

fn tempdir_beside(lib: &Path) -> Res<std::path::PathBuf> {
    let work = lib.with_extension("make-work");
    fs::create_dir_all(&work)?;
    Ok(work)
}

fn dump(lib: &Path) -> Res<()> {
    let conn = Connection::open_with_flags(lib.join("library.sqlite3"), OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let mut statement = conn.prepare("SELECT row_id,link_id,title,state,origin,text FROM link_records ORDER BY link_id")?;
    let rows = statement
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?, r.get::<_, String>(4)?, r.get::<_, String>(5)?)))?
        .collect::<Result<Vec<_>, _>>()?;
    let (mut live, mut trashed) = (0, 0);
    let mut links = Vec::new();
    for (row_id, link_id, title, state, origin, text) in rows {
        match state.as_str() {
            "live" => live += 1,
            "trashed" => trashed += 1,
            _ => {}
        }
        let preview = fs::read(lib.join("links").join(format!("{row_id}.pdf"))).ok();
        links.push(json!({"link_id":link_id,"title":title,"state":state,"origin":origin,"text_sha":sha(text.as_bytes()),
            "preview_sha":preview.as_ref().map(|p| sha(p)).unwrap_or_default(),"preview_len":preview.map(|p| p.len()).unwrap_or(0)}));
    }
    println!("{}", json!({"count":links.len(),"live":live,"trashed":trashed,"links":links}));
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.as_slice() {
        [c, lib, n] if c == "make" && safe(Path::new(lib)) => n.parse::<u128>().map_err(Into::into).and_then(|n| if n <= 1000 { make(Path::new(lib), n) } else { Err("at most 1000".into()) }),
        [c, lib] if c == "dump" && safe(Path::new(lib)) => dump(Path::new(lib)),
        _ => Err("usage: link_lan make LIB DESKTOP_LINKS | link_lan dump LIB (LIB absolute under /tmp)".into()),
    };
    if let Err(error) = result {
        eprintln!("link_lan: {error}");
        std::process::exit(1);
    }
}
