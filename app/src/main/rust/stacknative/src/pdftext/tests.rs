use super::*;
use std::path::PathBuf;
use std::time::Instant;

fn asset(name: &str) -> Vec<u8> {
    let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    p.push("../../../androidTest/assets/types/pdf");
    p.push(name);
    std::fs::read(&p).unwrap_or_else(|e| panic!("{}: {e}", p.display()))
}

fn cfg() -> Config {
    Config { max_pages: 1000, max_chars: 0, budget: Duration::from_secs(20) }
}

fn read(name: &str) -> Vec<Option<String>> {
    page_texts(&asset(name), &cfg()).unwrap_or_else(|e| panic!("{name}: {e:?}"))
}

#[test]
fn born_digital_reads_all_fonts() {
    for name in ["born-digital.pdf", "born-digital-objstm.pdf"] {
        let pages = read(name);
        assert_eq!(pages.len(), 3, "{name}");
        let first = pages[0].as_deref().unwrap_or_else(|| panic!("{name}: page 1 has text"));
        println!("--- {name} page 1\n{first}");
        let lines: Vec<&str> = first.lines().collect();
        assert_eq!(lines[0], "Stack types: born digital page one", "{name}");
        assert_eq!(lines[1], "Times Roman line with café and naïve", "{name}: WinAnsi accents");
        assert_eq!(lines[2], "Courier fixed width: 1234567890", "{name}");
        assert_eq!(lines[3], "first office costs € 5 – a long dash", "{name}: Differences, ligature split to letters");
        assert_eq!(lines[4], "Mac Roman: é è ö", "{name}: MacRoman");
        assert_eq!(lines[5], "Kerned wordsstay apart", "{name}: a wide TJ gap becomes a space, a small kerning (20) does not");
        assert_eq!(lines[6], "Next line by T-star follows on the same line", "{name}: T* and a same-line gap");
        assert_eq!(lines[7], "T01yab", "{name}: Type0 Identity-H through ToUnicode");
        assert!(pages[1].as_deref().unwrap().contains("Invoice 2026-1126 total 1,234.50"), "{name}");
        assert!(pages[2].as_deref().unwrap().contains("Third page: types"), "{name}");
    }
}

#[test]
fn image_only_pages_need_ocr_and_mixed_files_split_per_page() {
    assert_eq!(read("image-only.pdf"), vec![None]);
    let mixed = read("mixed-text-and-image.pdf");
    assert_eq!(mixed.len(), 2);
    assert!(mixed[0].as_deref().unwrap().contains("these types of words are read without OCR"));
    assert!(mixed[1].is_none());
    // The existing synthetic file has a text layer too.
    assert!(read("synthetic.pdf")[0].as_deref().unwrap().contains("STACK TYPES"));
}

fn timed(name: &str, budget: Duration) -> (Result<Vec<Option<String>>, Fail>, Duration) {
    let data = asset(name);
    let t = Instant::now();
    let r = page_texts(&data, &Config { max_pages: 1000, max_chars: 0, budget });
    (r, t.elapsed())
}

#[test]
fn hostile_deep_nesting() {
    let (r, took) = timed("hostile-deep-nesting.pdf", Duration::from_secs(20));
    println!("deep nesting: {:?} in {:?}", r.as_ref().map(|p| p.len()), took);
    let pages = r.unwrap();
    assert!(pages[0].as_deref().unwrap().contains("the types survive"));
    assert!(took < Duration::from_secs(5));
}

#[test]
fn hostile_huge_stream_is_capped() {
    let (r, took) = timed("hostile-huge-stream.pdf", Duration::from_secs(20));
    println!("huge stream: {:?} in {:?}", r.as_ref().map(|p| p.len()), took);
    let pages = r.unwrap();
    assert_eq!(pages.len(), 2);
    assert!(pages[0].as_deref().unwrap().contains("the types survive"));
    assert!(took < Duration::from_secs(10));
}

#[test]
fn hostile_xref_and_page_tree_loops() {
    let (r, took) = timed("hostile-xref-loop.pdf", Duration::from_secs(20));
    let pages = r.unwrap();
    assert_eq!(pages.len(), 1, "the cycle does not repeat or add pages");
    assert!(pages[0].as_deref().unwrap().contains("the types survive"));
    assert!(took < Duration::from_secs(2));
}

#[test]
fn hostile_broken_compression_keeps_what_decodes() {
    let pages = read("hostile-broken-flate.pdf");
    assert_eq!(pages.len(), 3);
    assert!(pages[0].is_some());
    assert!(pages[1].as_deref().unwrap().contains("first part is readable"));
    assert!(pages[2].is_none(), "garbage stream: no text, OCR instead");
}

#[test]
fn the_time_budget_is_kept() {
    // A budget of 1 ms on the largest hostile file: answers (possibly with nothing) at once and does not hang.
    let (r, took) = timed("hostile-huge-stream.pdf", Duration::from_millis(1));
    println!("1 ms budget: {:?} in {:?}", r.as_ref().map(|p| p.len()), took);
    assert!(took < Duration::from_secs(2));
}

#[test]
fn protected_and_junk_files() {
    let enc = b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\ntrailer\n<< /Root 1 0 R /Encrypt 5 0 R >>\n";
    assert_eq!(page_texts(enc, &cfg()).unwrap_err(), Fail::Protected);
    assert_eq!(page_texts(b"", &cfg()).unwrap_err(), Fail::Damaged);
    assert_eq!(page_texts(b"%PDF-1.4 and then nothing", &cfg()).unwrap_err(), Fail::Damaged);
    assert_eq!(page_texts(&[0xff; 5000], &cfg()).unwrap_err(), Fail::Damaged);
}

#[test]
fn page_limits() {
    let mut c = cfg();
    c.max_pages = 2;
    // Three pages in the file, a limit of two: reported as damaged (the caller rejects such files before this point).
    assert!(page_texts(&asset("born-digital.pdf"), &c).is_err());
    let mut c = cfg();
    c.max_chars = 40;
    let pages = page_texts(&asset("born-digital.pdf"), &c).unwrap();
    assert!(pages[0].as_deref().map_or(0, |t| t.chars().count()) <= 40);
}

/// Deterministic damage: cuts, flipped bytes and spliced chunks over the good files must never panic or run long.
#[test]
fn damaged_copies_never_panic() {
    let mut seed = 0x2026_1126u64;
    let mut rnd = move || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        (seed >> 33) as usize
    };
    for name in ["born-digital.pdf", "born-digital-objstm.pdf", "mixed-text-and-image.pdf", "hostile-broken-flate.pdf", "hostile-xref-loop.pdf"] {
        let good = asset(name);
        for round in 0..400 {
            let mut d = good.clone();
            match round % 4 {
                0 => d.truncate(rnd() % d.len()),
                1 => {
                    for _ in 0..1 + rnd() % 20 {
                        let i = rnd() % d.len();
                        d[i] = rnd() as u8;
                    }
                }
                2 => {
                    let (a, b) = (rnd() % d.len(), rnd() % d.len());
                    let chunk: Vec<u8> = d[a..(a + 50).min(d.len())].to_vec();
                    let at = b.min(d.len());
                    d.splice(at..at, chunk);
                }
                _ => {
                    let i = rnd() % d.len();
                    d[i..].iter_mut().for_each(|b| *b = b' ');
                }
            }
            let t = Instant::now();
            let r = std::panic::catch_unwind(|| page_texts(&d, &Config { max_pages: 1000, max_chars: 0, budget: Duration::from_secs(5) }));
            assert!(r.is_ok(), "{name} round {round} panicked");
            assert!(t.elapsed() < Duration::from_secs(5), "{name} round {round} took {:?}", t.elapsed());
        }
    }
}

#[test]
fn the_wrapper_catches_and_answers() {
    assert!(crate::pdf_texts(&asset("born-digital.pdf"), 100, 0, 5000).is_some());
    assert!(crate::pdf_texts(b"junk", 100, 0, 5000).is_none());
}

#[test]
fn encodings_and_cmaps() {
    assert_eq!(font::glyph_to_char(b"eacute"), Some('é'));
    assert_eq!(font::glyph_to_char(b"uni20AC"), Some('€'));
    assert_eq!(font::glyph_to_char(b"fi"), Some('\u{fb01}'));
    assert_eq!(font::glyph_to_char(b"a.alt"), Some('a'));
    assert_eq!(font::glyph_to_char(b"g123"), None);
    let map = font::CMap::parse(b"1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <41> <D83DDE00> endbfchar 1 beginbfrange <61> <63> <0041> endbfrange");
    assert_eq!(map.lookup(0x41).as_deref(), Some("\u{1F600}"));
    assert_eq!(map.lookup(0x62).as_deref(), Some("B"));
    assert_eq!(map.lookup(0x70), None);
}

/// Real-world check, not part of the normal run: STACK_PDF_SAMPLES="a.pdf:b.pdf" cargo test real_files -- --ignored --nocapture
#[test]
#[ignore]
fn real_files() {
    for path in std::env::var("STACK_PDF_SAMPLES").unwrap_or_default().split(':').filter(|p| !p.is_empty()) {
        let data = std::fs::read(path).unwrap();
        let t = Instant::now();
        let r = page_texts(&data, &Config { max_pages: 5000, max_chars: 0, budget: Duration::from_secs(60) });
        match r {
            Ok(p) => {
                let with = p.iter().filter(|x| x.is_some()).count();
                let chars: usize = p.iter().flatten().map(|s| s.chars().count()).sum();
                println!("{path}: {} pages, {with} with text, {chars} chars, {:?}", p.len(), t.elapsed());
                if let Ok(dir) = std::env::var("STACK_PDF_DUMP") {
                    let name = std::path::Path::new(path).file_stem().unwrap().to_string_lossy().to_string();
                    let all: Vec<String> = p.iter().map(|x| x.clone().unwrap_or_else(|| "<<OCR>>".into())).collect();
                    std::fs::write(format!("{dir}/{name}.txt"), all.join("\n\u{c}\n")).unwrap();
                }
            }
            Err(e) => println!("{path}: {e:?} in {:?}", t.elapsed()),
        }
    }
}

#[test]
fn filters_roundtrip_small_cases() {
    let far = Instant::now() + Duration::from_secs(5);
    // stored block
    let stored = [0x78, 0x01, 0x01, 0x03, 0x00, 0xfc, 0xff, b'a', b'b', b'c'];
    assert_eq!(filters::flate(&stored, 100, far).data, b"abc");
    // fixed Huffman "aaaaaaaaaa" (zlib of 10 x 'a')
    let fixed = [0x78, 0x9c, 0x4b, 0x4c, 0x84, 0x01, 0x00, 0x14, 0xe1, 0x03, 0xcb];
    assert_eq!(filters::flate(&fixed, 100, far).data, b"aaaaaaaaaa");
    let capped = filters::flate(&fixed, 4, far);
    assert!(!capped.complete && capped.data.len() <= 4);
    assert_eq!(filters::ascii_hex(b"41 42>", 10).data, b"AB");
    assert_eq!(filters::ascii85(b"87cURDZ~>", 10).data, b"Hello");
    assert_eq!(filters::run_length(&[2, b'a', b'b', b'c', 254, b'x', 128], 100).data, b"abcxxx");
}
