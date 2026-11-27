//! The security-review reproducers, rebuilt in memory (synthetic files, no content from anywhere): each must finish within
//! its budget plus half a second and stay under a memory ceiling, measured with a counting allocator (per thread).

use super::*;
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

struct Counting;

thread_local! {
    static LIVE: Cell<usize> = const { Cell::new(0) };
    static PEAK: Cell<usize> = const { Cell::new(0) };
}

unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, l: Layout) -> *mut u8 {
        let p = unsafe { System.alloc(l) };
        if !p.is_null() {
            LIVE.with(|v| {
                v.set(v.get() + l.size());
                PEAK.with(|k| k.set(k.get().max(v.get())));
            });
        }
        p
    }
    unsafe fn dealloc(&self, p: *mut u8, l: Layout) {
        unsafe { System.dealloc(p, l) };
        LIVE.with(|v| v.set(v.get().saturating_sub(l.size())));
    }
    unsafe fn realloc(&self, p: *mut u8, l: Layout, new: usize) -> *mut u8 {
        let q = unsafe { System.realloc(p, l, new) };
        if !q.is_null() {
            LIVE.with(|v| {
                v.set(v.get().saturating_sub(l.size()) + new);
                PEAK.with(|k| k.set(k.get().max(v.get())));
            });
        }
        q
    }
}

#[global_allocator]
static A: Counting = Counting;

const BUDGET: Duration = Duration::from_secs(2);
const CEILING: usize = 256 * 1024 * 1024;

fn pdf(objs: &[(u32, Vec<u8>)]) -> Vec<u8> {
    let mut out = b"%PDF-1.4\n".to_vec();
    for (n, body) in objs {
        out.extend_from_slice(format!("{n} 0 obj\n").as_bytes());
        out.extend_from_slice(body);
        out.extend_from_slice(b"\nendobj\n");
    }
    out.extend_from_slice(b"trailer\n<< /Root 1 0 R >>\n%%EOF\n");
    out
}

fn stream(dict: &str, data: &[u8]) -> Vec<u8> {
    let mut v = format!("<< {dict} /Length {} >>\nstream\n", data.len()).into_bytes();
    v.extend_from_slice(data);
    v.extend_from_slice(b"\nendstream");
    v
}

fn one_page(res: &str, content: &[u8], extra: Vec<(u32, Vec<u8>)>) -> Vec<u8> {
    let mut objs = vec![
        (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
        (2, b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>".to_vec()),
        (3, format!("<< /Type /Page /Parent 2 0 R /Resources {res} /Contents 4 0 R >>").into_bytes()),
        (4, stream("", content)),
    ];
    objs.extend(extra);
    pdf(&objs)
}

/// Runs the file, returns (result, elapsed, peak extra heap bytes on this thread).
fn measure(data: &[u8]) -> (Result<Vec<Option<String>>, Fail>, Duration, usize) {
    let base = LIVE.with(|v| v.get());
    PEAK.with(|k| k.set(base));
    let t = Instant::now();
    let r = page_texts(data, &Config { max_pages: 1000, max_chars: 0, budget: BUDGET });
    let took = t.elapsed();
    let peak = PEAK.with(|k| k.get()).saturating_sub(base);
    (r, took, peak)
}

fn check(name: &str, data: &[u8]) -> Result<Vec<Option<String>>, Fail> {
    let (r, took, peak) = measure(data);
    println!("{name}: {} bytes in, {:?}, peak {} MB", data.len(), took, peak >> 20);
    // Release builds (the shipped library) keep to half a second over; an unoptimised test build is about ten times slower
    // between two looks at the clock and shares the machine with the other tests, so it gets more room.
    let slack = if cfg!(debug_assertions) { Duration::from_millis(3000) } else { Duration::from_millis(500) };
    assert!(took < BUDGET + slack, "{name} took {took:?}");
    assert!(peak < CEILING, "{name} peaked at {} MB", peak >> 20);
    r
}

#[test]
fn poc_pagetree_clone() {
    // Inherited resources with 2000 fonts, 50,000 kids: used to clone the resources per kid.
    let res: Vec<String> = (0..2000).map(|i| format!("/F{i} 9 0 R")).collect();
    let kids: Vec<String> = (0..50_000).map(|i| format!("{} 0 R", 100 + i)).collect();
    let objs = vec![
        (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
        (2, format!("<< /Type /Pages /Resources << /Font << {} >> >> /Kids [{}] /Count 1 >>", res.join(" "), kids.join(" ")).into_bytes()),
    ];
    let _ = check("pagetree-clone", &pdf(&objs));
}

#[test]
fn poc_pagetree_nodes_are_capped_by_pushes() {
    let kids: Vec<String> = (0..300_000).map(|i| format!("{} 0 R", 100 + i)).collect();
    let objs = vec![(1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()), (2, format!("<< /Type /Pages /Kids [{}] >>", kids.join(" ")).into_bytes())];
    assert_eq!(check("pagetree-300k", &pdf(&objs)).unwrap_err(), Fail::Damaged);
}

#[test]
fn poc_page_share() {
    // 1000 object numbers in one object stream, all pointing at the same 200 KB page dictionary.
    let head: Vec<String> = (10..1010).map(|n| format!("{n} 0")).collect();
    let head = head.join(" ") + " ";
    let page = format!("<< /Type /Page /Junk [{}] >>", "1 ".repeat(100_000));
    let mut body = head.clone().into_bytes();
    body.extend_from_slice(page.as_bytes());
    let kids: Vec<String> = (10..1010).map(|n| format!("{n} 0 R")).collect();
    let objs = vec![
        (1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()),
        (2, format!("<< /Type /Pages /Kids [{}] >>", kids.join(" ")).into_bytes()),
        (5, stream(&format!("/Type /ObjStm /N 1000 /First {}", head.len()), &body)),
    ];
    let r = check("page-share", &pdf(&objs));
    // The same place in the file counts once.
    assert!(r.map_or(true, |p| p.len() <= 1));
}

#[test]
fn poc_form_without_operators() {
    let junk = "0 ".repeat(2_000_000);
    let content = format!("BT /F1 12 Tf (hello world this is text) Tj ET {}", "/X Do ".repeat(1000));
    let data = one_page("<< /XObject << /X 5 0 R >> >>", content.as_bytes(), vec![(5, stream("/Subtype /Form", junk.as_bytes()))]);
    let _ = check("form-noticks", &data);
}

#[test]
fn poc_inline_font_rebuilt_at_every_tf() {
    let mut cm = b"/CIDInit begincmap 1 begincodespacerange <00> <ff> endcodespacerange\n".to_vec();
    for _ in 0..100_000 {
        cm.extend_from_slice(b"1 beginbfchar <41> <0041> endbfchar\n");
    }
    cm.extend_from_slice(b"endcmap");
    let content = format!("BT {}(hello) Tj ET", "/F1 12 Tf ".repeat(1000));
    let data = one_page("<< /Font << /F1 << /Type /Font /Subtype /Type1 /ToUnicode 5 0 R >> >> >>", content.as_bytes(), vec![(5, stream("", &cm))]);
    let _ = check("inline-font-tf", &data);
}

#[test]
fn poc_width_ranges_scanned_per_character() {
    let w: Vec<String> = (0..60_000).map(|i| format!("{} {} 500", i * 4, i * 4 + 1)).collect();
    let content = [b"BT /F1 12 Tf <".as_slice(), "FFFF".repeat(500_000).as_bytes(), b"> Tj ET"].concat();
    let tu = b"/CIDInit begincmap 1 begincodespacerange <0000> <FFFF> endcodespacerange 1 beginbfchar <0001> <0041> endbfchar endcmap";
    let extra = vec![
        (5, b"<< /Type /Font /Subtype /Type0 /BaseFont /X /DescendantFonts [6 0 R] /ToUnicode 7 0 R >>".to_vec()),
        (6, format!("<< /Type /Font /Subtype /CIDFontType2 /DW 1000 /W [{}] >>", w.join(" ")).into_bytes()),
        (7, stream("", tu)),
    ];
    let _ = check("w-quadratic", &one_page("<< /Font << /F1 5 0 R >> >>", &content, extra));
}

#[test]
fn width_ranges_binary_search_matches_the_definition() {
    let cmap: &dyn Fn(&lex::Obj) -> Option<Rc<font::CMap>> = &|_| None;
    let resolve: &dyn Fn(&lex::Obj) -> lex::Obj = &|o| o.clone();
    let mut lx = Lexer::new(b"<< /Subtype /Type0 /DescendantFonts [<< /DW 700 /W [10 20 300 15 15 400 5 [111 222]] >>] >>", 0, false, 1000);
    let Ok(Some(Obj::Dict(d))) = lx.next() else { panic!("dict") };
    let charge: &dyn Fn(u64) -> bool = &|_| true;
    let font = font::Font::build(&d, &font::Context { resolve, cmap, charge });
    assert_eq!(font.width(5), 111.0);
    assert_eq!(font.width(6), 222.0);
    assert_eq!(font.width(10), 300.0);
    assert_eq!(font.width(15), 400.0, "on an overlap the range starting nearest below wins (files should not overlap)");
    assert_eq!(font.width(20), 300.0);
    assert_eq!(font.width(21), 700.0);
    assert_eq!(font.width(9), 700.0);
}

#[test]
fn one_budget_for_the_whole_document() {
    // Many pages, each with a large operand-only stream: the shared counter stops the whole run, not each page.
    let junk = "0 ".repeat(1_000_000);
    let kids: Vec<String> = (0..200).map(|i| format!("{} 0 R", 10 + i)).collect();
    let mut objs = vec![(1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()), (2, format!("<< /Type /Pages /Kids [{}] >>", kids.join(" ")).into_bytes())];
    for i in 0..200u32 {
        objs.push((10 + i, b"<< /Type /Page /Contents 5 0 R >>".to_vec()));
    }
    objs.push((5, stream("", junk.as_bytes())));
    let _ = check("many-pages-one-stream", &pdf(&objs));
}

// ---- The review's second round: each rebuilt in memory (synthetic), each within its budget plus half a second and 256 MB.

fn font_page(font: &[u8], content: &[u8], extra: Vec<(u32, Vec<u8>)>) -> Vec<u8> {
    let mut objs = vec![(10, font.to_vec())];
    objs.extend(extra);
    one_page("<< /Font << /F1 10 0 R >> >>", content, objs)
}

const HELLO: &[u8] = b"BT /F1 12 Tf 10 10 Td (Hello world this is a test of text) Tj ET";

#[test]
fn poc_trailer_quadratic() {
    let mut data = b"%PDF-1.4\n1 0 obj<<>>endobj\n".to_vec();
    data.extend_from_slice(&b"trailer(".repeat(131_072));
    let _ = check("trailer-1mb", &data);
    for opener in [&b"trailer<"[..], b"trailer<<", b"trailer["] {
        let mut data = b"%PDF-1.4\n".to_vec();
        data.extend_from_slice(&opener.repeat(131_072));
        let _ = check("trailer-openers", &data);
    }
}

#[test]
fn trailer_after_the_last_startxref_wins() {
    let mut data = pdf(&[(1, b"<< /Type /Catalog /Pages 2 0 R >>".to_vec()), (2, b"<< /Type /Pages /Kids [] >>".to_vec())]);
    data.extend_from_slice(b"startxref\n0\n%%EOF\ntrailer\n<< /Root 1 0 R >>\n");
    assert!(page_texts(&data, &Config { max_pages: 10, max_chars: 0, budget: BUDGET }).is_ok());
}

#[test]
fn poc_cid_widths_amplify() {
    let font = b"<< /Type /Font /Subtype /Type0 /BaseFont /X /Encoding /Identity-H /DescendantFonts [11 0 R] >>";
    let extra = vec![
        (11, b"<< /Type /Font /Subtype /CIDFontType2 /W 12 0 R >>".to_vec()),
        (12, [b"[".as_slice(), "13 0 R ".repeat(200_000).as_bytes(), b"]"].concat()),
        (13, [b"[".as_slice(), "0 ".repeat(399_000).as_bytes(), b"]"].concat()),
    ];
    let _ = check("cid-widths", &font_page(font, b"BT /F1 12 Tf 10 10 Td <00410042> Tj ET", extra));
}

#[test]
fn poc_differences_cpu() {
    let font = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Differences 12 0 R >> >>";
    let extra = vec![
        (12, [b"[".as_slice(), "13 0 R ".repeat(390_000).as_bytes(), b"]"].concat()),
        (13, [b"[".as_slice(), "0 ".repeat(399_000).as_bytes(), b"]"].concat()),
    ];
    let _ = check("differences", &font_page(font, HELLO, extra));
}

#[test]
fn poc_content_array_memory() {
    let content = [b"[".as_slice(), "0 ".repeat(12_000_000).as_bytes(), b"] TJ"].concat();
    let _ = check("content-array", &one_page("<< >>", &content, vec![]));
}

#[test]
fn poc_form_recursion_memory() {
    let form = format!("{}/Fm Do", "[ {} ] ".replace("{}", &"0 ".repeat(60_000)).repeat(100));
    let extra = vec![(5, stream("/Subtype /Form /Resources << /XObject << /Fm 5 0 R >> >>", form.as_bytes()))];
    let _ = check("form-recursion", &one_page("<< /XObject << /Fm 5 0 R >> >>", b"/Fm Do", extra));
}

#[test]
fn poc_cmap_range_clone() {
    let dst = format!("<{}>", "0041".repeat(2 * 1024 * 1024));
    let cmap = format!("begincmap 1 begincodespacerange <00> <ff> endcodespacerange 1 beginbfrange <00> <ff> {dst} endbfrange endcmap");
    let content = [b"BT /F1 12 Tf 10 10 Td <".as_slice(), "41".repeat(200_000).as_bytes(), b"> Tj ET"].concat();
    let font = b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 11 0 R >>";
    let r = check("cmap-range", &font_page(font, &content, vec![(11, stream("", cmap.as_bytes()))]));
    let text = r.unwrap().remove(0).unwrap();
    assert!(text.starts_with("AAAA"));
}

#[test]
fn poc_glyph_name_multibyte() {
    let font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding << /Differences [65 /uniAAAé /uéé /u00é9 /unié] >> >>";
    let r = check("glyph-name", &font_page(font.as_bytes(), HELLO, vec![]));
    assert!(r.unwrap()[0].is_some());
    assert_eq!(font::glyph_to_char("uniAAAé".as_bytes()), None);
}

#[test]
fn width_code_near_u32_max_does_not_overflow() {
    let font = b"<< /Type /Font /Subtype /Type0 /BaseFont /X /DescendantFonts [11 0 R] >>";
    let extra = vec![(11, b"<< /Type /Font /Subtype /CIDFontType2 /W [4294967295 [1 2 3] 4294967290 4294967295 7] >>".to_vec())];
    let _ = check("w-overflow", &font_page(font, b"BT /F1 12 Tf <0041> Tj ET", extra));
}
