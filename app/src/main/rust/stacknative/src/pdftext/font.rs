//! Fonts: code to Unicode (ToUnicode CMaps, base encodings, /Differences) and glyph widths.

use super::lex::{Dict, Lexer, Obj, get};
use std::collections::HashMap;
use std::rc::Rc;

const MAX_CMAP_ENTRIES: usize = 200_000;
const MAX_WIDTHS: usize = 200_000;
/// Longest ToUnicode destination kept, in UTF-16 units (real maps use a handful; a longer one is cut).
const MAX_DEST_UNITS: usize = 32;
/// Items read from a /W array or a /Differences array (real ones are far shorter).
const MAX_DIFFERENCES: usize = 4096;

#[derive(Default)]
pub struct CMap {
    single: HashMap<u32, String>,
    /// (lo, hi, destination as UTF-16 units; the last unit is incremented by code - lo)
    ranges: Vec<(u32, u32, Vec<u16>)>,
    /// (bytes, lo, hi)
    pub codespace: Vec<(u8, u32, u32)>,
}

fn utf16(units: &[u16]) -> String {
    String::from_utf16_lossy(units)
}

fn be_units(b: &[u8]) -> Vec<u16> {
    b.chunks(2).take(MAX_DEST_UNITS).map(|c| if c.len() == 2 { u16::from_be_bytes([c[0], c[1]]) } else { c[0] as u16 }).collect()
}

fn code_of(b: &[u8]) -> u32 {
    b.iter().take(4).fold(0u32, |a, &x| (a << 8) | x as u32)
}

impl CMap {
    pub fn parse(data: &[u8]) -> CMap {
        let mut map = CMap::default();
        let mut lx = Lexer::new(data, 0, false, 2_000_000);
        let mut operands: Vec<Obj> = Vec::new();
        let mut mode = 0u8; // 0 none, 1 bfchar, 2 bfrange, 3 codespace
        while let Ok(Some(tok)) = lx.next() {
            match tok {
                Obj::Op(op) => {
                    match op.as_slice() {
                        b"beginbfchar" => mode = 1,
                        b"beginbfrange" => mode = 2,
                        b"begincodespacerange" => mode = 3,
                        b"endbfchar" | b"endbfrange" | b"endcodespacerange" => mode = 0,
                        _ => {}
                    }
                    operands.clear();
                }
                other => {
                    operands.push(other);
                    if map.single.len() + map.ranges.len() > MAX_CMAP_ENTRIES {
                        break;
                    }
                    match (mode, operands.as_slice()) {
                        (1, [Obj::Str(src), Obj::Str(dst)]) => {
                            map.single.insert(code_of(src), utf16(&be_units(dst)));
                            operands.clear();
                        }
                        (1, [Obj::Str(src), Obj::Name(_)]) => {
                            let _ = src;
                            operands.clear();
                        }
                        (2, [Obj::Str(lo), Obj::Str(hi), Obj::Str(dst)]) => {
                            let (l, h) = (code_of(lo), code_of(hi));
                            if h >= l && h - l <= 0xffff {
                                let units = be_units(dst);
                                if !units.is_empty() {
                                    map.ranges.push((l, h, units));
                                }
                            }
                            operands.clear();
                        }
                        (2, [Obj::Str(lo), Obj::Str(hi), Obj::Arr(list)]) => {
                            let l = code_of(lo);
                            if code_of(hi) >= l {
                                for (k, item) in list.iter().enumerate().take(0x10000) {
                                    if let Obj::Str(dst) = item {
                                        map.single.insert(l.saturating_add(k as u32), utf16(&be_units(dst)));
                                    }
                                }
                            }
                            operands.clear();
                        }
                        (3, [Obj::Str(lo), Obj::Str(hi)]) => {
                            if !lo.is_empty() && lo.len() <= 4 {
                                map.codespace.push((lo.len() as u8, code_of(lo), code_of(hi)));
                            }
                            operands.clear();
                        }
                        _ => {
                            if operands.len() > 3 {
                                operands.clear();
                            }
                        }
                    }
                }
            }
        }
        map.ranges.sort_by_key(|r| r.0);
        map
    }

    pub fn lookup(&self, code: u32) -> Option<String> {
        if let Some(s) = self.single.get(&code) {
            return Some(s.clone());
        }
        let idx = self.ranges.partition_point(|r| r.0 <= code);
        // Ranges normally do not overlap; look back a few for ones that do.
        for r in self.ranges[..idx].iter().rev().take(8) {
            if code >= r.0 && code <= r.1 {
                let mut units = r.2.clone();
                if let Some(last) = units.last_mut() {
                    *last = last.wrapping_add((code - r.0) as u16);
                }
                return Some(utf16(&units));
            }
        }
        None
    }

    pub fn is_empty(&self) -> bool {
        self.single.is_empty() && self.ranges.is_empty()
    }
}

// ---------- encodings ----------

const ASCII_NAMES: [&str; 95] = [
    "space", "exclam", "quotedbl", "numbersign", "dollar", "percent", "ampersand", "quotesingle", "parenleft", "parenright", "asterisk", "plus",
    "comma", "hyphen", "period", "slash", "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "colon", "semicolon",
    "less", "equal", "greater", "question", "at", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R", "S",
    "T", "U", "V", "W", "X", "Y", "Z", "bracketleft", "backslash", "bracketright", "asciicircum", "underscore", "grave", "a", "b", "c", "d", "e",
    "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "braceleft", "bar", "braceright",
    "asciitilde",
];

const LATIN1_NAMES: [&str; 96] = [
    "space", "exclamdown", "cent", "sterling", "currency", "yen", "brokenbar", "section", "dieresis", "copyright", "ordfeminine", "guillemotleft",
    "logicalnot", "hyphen", "registered", "macron", "degree", "plusminus", "twosuperior", "threesuperior", "acute", "mu", "paragraph",
    "periodcentered", "cedilla", "onesuperior", "ordmasculine", "guillemotright", "onequarter", "onehalf", "threequarters", "questiondown",
    "Agrave", "Aacute", "Acircumflex", "Atilde", "Adieresis", "Aring", "AE", "Ccedilla", "Egrave", "Eacute", "Ecircumflex", "Edieresis", "Igrave",
    "Iacute", "Icircumflex", "Idieresis", "Eth", "Ntilde", "Ograve", "Oacute", "Ocircumflex", "Otilde", "Odieresis", "multiply", "Oslash",
    "Ugrave", "Uacute", "Ucircumflex", "Udieresis", "Yacute", "Thorn", "germandbls", "agrave", "aacute", "acircumflex", "atilde", "adieresis",
    "aring", "ae", "ccedilla", "egrave", "eacute", "ecircumflex", "edieresis", "igrave", "iacute", "icircumflex", "idieresis", "eth", "ntilde",
    "ograve", "oacute", "ocircumflex", "otilde", "odieresis", "divide", "oslash", "ugrave", "uacute", "ucircumflex", "udieresis", "yacute",
    "thorn", "ydieresis",
];

const EXTRA_NAMES: [(&str, u32); 46] = [
    ("quoteleft", 0x2018), ("quoteright", 0x2019), ("quotedblleft", 0x201C), ("quotedblright", 0x201D), ("quotesinglbase", 0x201A),
    ("quotedblbase", 0x201E), ("endash", 0x2013), ("emdash", 0x2014), ("bullet", 0x2022), ("ellipsis", 0x2026), ("dagger", 0x2020),
    ("daggerdbl", 0x2021), ("perthousand", 0x2030), ("trademark", 0x2122), ("Euro", 0x20AC), ("fi", 0xFB01), ("fl", 0xFB02), ("ff", 0xFB00),
    ("ffi", 0xFB03), ("ffl", 0xFB04), ("OE", 0x0152), ("oe", 0x0153), ("Scaron", 0x0160), ("scaron", 0x0161), ("Zcaron", 0x017D),
    ("zcaron", 0x017E), ("Ydieresis", 0x0178), ("florin", 0x0192), ("circumflex", 0x02C6), ("tilde", 0x02DC), ("guilsinglleft", 0x2039),
    ("guilsinglright", 0x203A), ("minus", 0x2212), ("fraction", 0x2044), ("dotlessi", 0x0131), ("Lslash", 0x0141), ("lslash", 0x0142),
    ("nbspace", 0x00A0), ("nonbreakingspace", 0x00A0), ("hyphenminus", 0x2D), ("sfthyphen", 0xAD), ("middot", 0xB7), ("Delta", 0x394),
    ("Omega", 0x3A9), ("mu1", 0xB5), ("space1", 0x20),
];

const WIN_80: [u32; 32] = [
    0x20AC, 0, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017D, 0, 0, 0x2018, 0x2019, 0x201C,
    0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0, 0x017E, 0x0178,
];

const MAC_ROMAN_HIGH: &str = "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø¿¡¬√ƒ≈∆«»…\u{a0}ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔ\u{f8ff}ÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ";

pub fn glyph_to_char(name: &[u8]) -> Option<char> {
    let s = std::str::from_utf8(name).ok()?;
    if let Some(i) = ASCII_NAMES.iter().position(|n| *n == s) {
        return char::from_u32(0x20 + i as u32);
    }
    if let Some(i) = LATIN1_NAMES.iter().position(|n| *n == s) {
        return char::from_u32(0xA0 + i as u32);
    }
    if let Some((_, u)) = EXTRA_NAMES.iter().find(|(n, _)| *n == s) {
        return char::from_u32(*u);
    }
    // uniXXXX, uXXXX[XX]
    let hex_part = s.strip_prefix("uni").and_then(|r| r.get(..4)).or_else(|| s.strip_prefix('u').filter(|r| (4..=6).contains(&r.len())));
    if let Some(h) = hex_part {
        if let Ok(v) = u32::from_str_radix(h, 16) {
            return char::from_u32(v);
        }
    }
    // Names like "a.alt" or "f_i": use the base.
    if let Some((base, _)) = s.split_once('.') {
        if !base.is_empty() {
            return glyph_to_char(base.as_bytes());
        }
    }
    None
}

#[derive(Clone, Copy, PartialEq)]
pub enum Base {
    Win,
    Mac,
}

fn base_table(base: Base) -> [u32; 256] {
    let mut t = [0u32; 256];
    for c in 0x20..0x7f {
        t[c] = c as u32;
    }
    match base {
        Base::Win => {
            for c in 0x80..0xa0 {
                t[c] = WIN_80[c - 0x80];
            }
            for c in 0xa0..=0xff {
                t[c] = c as u32;
            }
            t[0x7f] = 0x2022;
        }
        Base::Mac => {
            for (i, ch) in MAC_ROMAN_HIGH.chars().enumerate().take(128) {
                t[0x80 + i] = ch as u32;
            }
        }
    }
    t
}

pub struct Font {
    pub composite: bool,
    to_unicode: Option<Rc<CMap>>,
    table: [u32; 256],
    widths: HashMap<u32, f32>,
    /// Sorted by first code (stable, so earlier definitions stay ahead of later ones with the same start).
    width_ranges: Vec<(u32, u32, f32)>,
    /// Largest last code among `width_ranges[..=i]`, so a lookup knows when to stop looking back.
    width_reach: Vec<u32>,
    default_width: f32,
    codespace: Vec<(u8, u32, u32)>,
}

pub struct Context<'a> {
    pub resolve: &'a dyn Fn(&Obj) -> Obj,
    /// The parsed ToUnicode map for a reference (cached by the document, charged to its budget).
    pub cmap: &'a dyn Fn(&Obj) -> Option<Rc<CMap>>,
    /// Charge units of work to the document; false once its budget is spent.
    pub charge: &'a dyn Fn(u64) -> bool,
}

impl Font {
    pub fn fallback() -> Font {
        Font { composite: false, to_unicode: None, table: base_table(Base::Win), widths: HashMap::new(), width_ranges: Vec::new(), width_reach: Vec::new(), default_width: 500.0, codespace: Vec::new() }
    }

    pub fn build(dict: &Dict, cx: &Context) -> Font {
        let sub = get(dict, b"Subtype").map(|o| (cx.resolve)(o));
        let composite = sub.as_ref().is_some_and(|s| s.is_name(b"Type0"));
        let base_font = get(dict, b"BaseFont").map(|o| (cx.resolve)(o)).and_then(|o| o.name().map(|n| n.to_vec())).unwrap_or_default();
        let mut font = Font::fallback();
        font.composite = composite;
        if base_font.windows(7).any(|w| w.eq_ignore_ascii_case(b"courier")) {
            font.default_width = 600.0;
        }
        if let Some(tu) = get(dict, b"ToUnicode") {
            if let Some(map) = (cx.cmap)(tu) {
                font.codespace = map.codespace.clone();
                font.to_unicode = Some(map);
            }
        }
        if composite {
            font.default_width = 1000.0;
            let desc = get(dict, b"DescendantFonts").map(|o| (cx.resolve)(o));
            let first = match desc {
                Some(Obj::Arr(a)) => a.first().map(|o| (cx.resolve)(o)),
                Some(d @ Obj::Dict(_)) => Some(d),
                _ => None,
            };
            if let Some(Obj::Dict(cid)) = first {
                if let Some(dw) = get(&cid, b"DW").map(|o| (cx.resolve)(o)).and_then(|o| o.num()) {
                    font.default_width = dw as f32;
                }
                if let Some(Obj::Arr(w)) = get(&cid, b"W").map(|o| (cx.resolve)(o)) {
                    font.read_cid_widths(&w, cx);
                    font.index_width_ranges();
                }
            }
            // Identity encodings and unknown CMaps are two bytes unless ToUnicode's codespace says otherwise.
            return font;
        }
        // Simple font: base encoding, then /Differences, then widths.
        let enc = get(dict, b"Encoding").map(|o| (cx.resolve)(o));
        let mut base = Base::Win;
        let mut differences: Option<Vec<Obj>> = None;
        match &enc {
            Some(Obj::Name(n)) => {
                if n.starts_with(b"MacRoman") {
                    base = Base::Mac;
                }
            }
            Some(Obj::Dict(e)) => {
                if let Some(b) = get(e, b"BaseEncoding").map(|o| (cx.resolve)(o)) {
                    if b.name().is_some_and(|n| n.starts_with(b"MacRoman")) {
                        base = Base::Mac;
                    }
                }
                if let Some(Obj::Arr(d)) = get(e, b"Differences").map(|o| (cx.resolve)(o)) {
                    differences = Some(d);
                }
            }
            _ => {}
        }
        font.table = base_table(base);
        if let Some(diffs) = differences {
            let mut code = 0usize;
            for item in diffs.into_iter().take(MAX_DIFFERENCES) {
                match (cx.resolve)(&item) {
                    Obj::Int(n) if (0..256).contains(&n) => code = n as usize,
                    Obj::Name(name) => {
                        if code < 256 {
                            font.table[code] = glyph_to_char(&name).map_or(0, |c| c as u32);
                        }
                        code += 1;
                    }
                    _ => {}
                }
            }
        }
        let first_char = get(dict, b"FirstChar").map(|o| (cx.resolve)(o)).and_then(|o| o.int()).unwrap_or(0).clamp(0, 255) as u32;
        if let Some(Obj::Arr(ws)) = get(dict, b"Widths").map(|o| (cx.resolve)(o)) {
            for (i, w) in ws.iter().enumerate().take(256) {
                if let Some(v) = (cx.resolve)(w).num() {
                    font.widths.insert(first_char + i as u32, v as f32);
                }
            }
        }
        if let Some(Obj::Dict(fd)) = get(dict, b"FontDescriptor").map(|o| (cx.resolve)(o)) {
            if let Some(mw) = get(&fd, b"MissingWidth").map(|o| (cx.resolve)(o)).and_then(|o| o.num()) {
                if mw > 0.0 {
                    font.default_width = mw as f32;
                }
            }
        }
        font
    }

    /// Reads a /W array one item at a time: each reference is resolved only when reached and dropped after use (never
    /// collected), every step is charged to the document, and the loop ends when the document's budget is spent.
    fn read_cid_widths(&mut self, w: &[Obj], cx: &Context) {
        let at = |i: usize| -> Option<Obj> {
            if i >= MAX_WIDTHS || !(cx.charge)(1) {
                return None;
            }
            w.get(i).map(|o| (cx.resolve)(o))
        };
        let mut i = 0;
        while i < w.len().min(MAX_WIDTHS) {
            let Some(c1) = at(i).and_then(|o| o.int()) else { break };
            let c1 = c1.clamp(0, u32::MAX as i64) as u32;
            match at(i + 1) {
                Some(Obj::Arr(list)) => {
                    for (k, v) in list.iter().enumerate() {
                        if self.widths.len() >= MAX_WIDTHS || !(cx.charge)(1) {
                            return;
                        }
                        let (Some(code), Some(x)) = (c1.checked_add(k as u32), (cx.resolve)(v).num()) else { continue };
                        self.widths.insert(code, x as f32);
                    }
                    i += 2;
                }
                Some(n) if n.int().is_some() => {
                    let c2 = n.int().unwrap_or(0).clamp(0, u32::MAX as i64) as u32;
                    if let Some(x) = at(i + 2).and_then(|o| o.num()) {
                        if self.width_ranges.len() < MAX_WIDTHS && c2 >= c1 {
                            self.width_ranges.push((c1, c2, x as f32));
                        }
                    }
                    i += 3;
                }
                _ => break,
            }
        }
    }

    fn index_width_ranges(&mut self) {
        self.width_ranges.sort_by_key(|r| r.0);
        let mut reach = 0u32;
        self.width_reach = self.width_ranges.iter().map(|r| { reach = reach.max(r.1); reach }).collect();
    }

    /// The next character code in `s` starting at `*i`: (code, bytes used).
    pub fn next_code(&self, s: &[u8], i: &mut usize) -> (u32, usize) {
        if !self.composite {
            let c = s[*i] as u32;
            *i += 1;
            return (c, 1);
        }
        // Codespace ranges from ToUnicode, shortest match first; default two bytes.
        let rest = &s[*i..];
        if !self.codespace.is_empty() {
            let mut code = 0u32;
            for n in 1..=4usize.min(rest.len()) {
                code = (code << 8) | rest[n - 1] as u32;
                if self.codespace.iter().any(|&(len, lo, hi)| len as usize == n && code >= lo && code <= hi) {
                    *i += n;
                    return (code, n);
                }
            }
        }
        let n = 2.min(rest.len());
        let code = rest[..n].iter().fold(0u32, |a, &x| (a << 8) | x as u32);
        *i += n;
        (code, n)
    }

    /// The text of a code, None when it cannot be mapped.
    pub fn text(&self, code: u32) -> Option<String> {
        if let Some(map) = &self.to_unicode {
            if let Some(s) = map.lookup(code) {
                return Some(s);
            }
        }
        if !self.composite && code < 256 {
            let u = self.table[code as usize];
            return if u == 0 { None } else { char::from_u32(u).map(|c| c.to_string()) };
        }
        None
    }

    /// Advance width in 1/1000 em.
    pub fn width(&self, code: u32) -> f32 {
        if let Some(w) = self.widths.get(&code) {
            return *w;
        }
        // Binary search to the last range starting at or before `code`, then back while an earlier range can still reach it.
        let idx = self.width_ranges.partition_point(|r| r.0 <= code);
        for k in (0..idx).rev().take(32) {
            if self.width_reach[k] < code {
                break;
            }
            let r = &self.width_ranges[k];
            if code <= r.1 {
                return r.2;
            }
        }
        self.default_width
    }
}
