//! Content stream interpreter: only what moves or shows text. Output is in content-stream order, with a line break when the
//! baseline moves and a space for a visible gap.

use super::font::{Context, Font};
use super::lex::{Dict, Lexer, Obj, get, is_space};
use super::{CONTENT_CAP, Doc};
use std::collections::HashMap;
use std::rc::Rc;

const MAX_FORM_DEPTH: usize = 8;
const MAX_STACK: usize = 128;
const MIN_ALNUM: usize = 16;
/// Looked-up font names remembered per resource dictionary (more than this are looked up again each time, and charged).
const MAX_NAME_CACHE: usize = 2048;
/// Tokens one content stream may produce (operands and operators), and the longest array operand kept.
const STREAM_NODES: usize = 1_000_000;
const MAX_ARRAY_ITEMS: usize = 65_536;
/// Operand nodes held at once over a stream and the forms it draws (the operand stacks of every nesting level together).
const RETAINED_NODES: usize = 400_000;

/// A resource dictionary, resolved once per content stream: its /Font and /XObject sub-dictionaries and the fonts built
/// from it (by name), so a Tf or Do in a loop does no repeated parsing.
struct Res {
    res: Rc<Obj>,
    fonts: Option<Option<Dict>>,
    xobjects: Option<Option<Dict>>,
    font_cache: HashMap<Vec<u8>, Option<Rc<Font>>>,
}

impl Res {
    fn new(res: Rc<Obj>) -> Res {
        Res { res, fonts: None, xobjects: None, font_cache: HashMap::new() }
    }
}

#[derive(Clone)]
struct Gs {
    font: Option<Rc<Font>>,
    size: f64,
    tc: f64,
    tw: f64,
    th: f64,
    tl: f64,
}

struct Out {
    text: String,
    chars: usize,
    cap: usize,
    last_y: f64,
    last_end: f64,
    line_open: bool,
    codes: usize,
    unmapped: usize,
}

impl Out {
    fn push_str(&mut self, s: &str) {
        for c in s.chars() {
            if self.chars >= self.cap {
                return;
            }
            let c = match c {
                '\u{a0}' | '\u{2009}' | '\u{202f}' => ' ',
                '\0' | '\u{feff}' => continue,
                _ => c,
            };
            match c {
                '\u{fb00}' => self.text.push_str("ff"),
                '\u{fb01}' => self.text.push_str("fi"),
                '\u{fb02}' => self.text.push_str("fl"),
                '\u{fb03}' => self.text.push_str("ffi"),
                '\u{fb04}' => self.text.push_str("ffl"),
                _ => self.text.push(c),
            }
            self.chars += 1;
        }
    }
    fn space(&mut self) {
        if !self.text.is_empty() && !self.text.ends_with(' ') && !self.text.ends_with('\n') {
            self.push_str(" ");
        }
    }
    fn newline(&mut self) {
        while self.text.ends_with(' ') {
            self.text.pop();
        }
        if !self.text.is_empty() && !self.text.ends_with('\n') {
            self.push_str("\n");
        }
    }
}

struct Interp<'a> {
    doc: &'a Doc<'a>,
    out: Out,
    fallback: Rc<Font>,
    tm: [f64; 6],
    tlm: [f64; 6],
    /// Operand nodes held now by all the running streams (see `RETAINED_NODES`).
    retained: usize,
}

fn weight(o: &Obj) -> usize {
    match o {
        Obj::Arr(a) => 1 + a.iter().map(weight).sum::<usize>(),
        Obj::Dict(d) => 1 + d.iter().map(|(_, v)| 1 + weight(v)).sum::<usize>(),
        _ => 1,
    }
}

const IDENT: [f64; 6] = [1.0, 0.0, 0.0, 1.0, 0.0, 0.0];

fn mul(a: &[f64; 6], b: &[f64; 6]) -> [f64; 6] {
    [
        a[0] * b[0] + a[1] * b[2],
        a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2],
        a[2] * b[1] + a[3] * b[3],
        a[4] * b[0] + a[5] * b[2] + b[4],
        a[4] * b[1] + a[5] * b[3] + b[5],
    ]
}

fn nums(args: &[Obj]) -> Vec<f64> {
    args.iter().map(|o| o.num().unwrap_or(0.0)).collect()
}

pub fn page_text(doc: &Doc, page: &Dict, resources: &Rc<Obj>, cap: usize) -> Option<String> {
    let contents = get(page, b"Contents").cloned().unwrap_or(Obj::Null);
    let parts: Vec<Obj> = match &contents {
        Obj::Ref(n) => match doc.object(*n) {
            Some(Obj::Arr(a)) => a.into_iter().take(10_000).collect(),
            _ => vec![contents.clone()],
        },
        Obj::Arr(a) => a.iter().take(10_000).cloned().collect(),
        _ => Vec::new(),
    };
    let mut data: Vec<u8> = Vec::new();
    for part in &parts {
        if let Some(chunk) = doc.stream_of(part, CONTENT_CAP.saturating_sub(data.len())) {
            data.extend_from_slice(&chunk);
            data.push(b'\n');
        }
        if data.len() >= CONTENT_CAP || !doc.charge(16) {
            break;
        }
    }
    let mut it = Interp {
        doc,
        out: Out { text: String::new(), chars: 0, cap, last_y: f64::NAN, last_end: 0.0, line_open: false, codes: 0, unmapped: 0 },
        fallback: Rc::new(Font::fallback()),
        tm: IDENT,
        tlm: IDENT,
        retained: 0,
    };
    let gs = Gs { font: None, size: 12.0, tc: 0.0, tw: 0.0, th: 1.0, tl: 0.0 };
    let mut res = Res::new(resources.clone());
    it.run(&data, &mut res, gs, 0);
    it.finish()
}

impl<'a> Interp<'a> {
    fn finish(mut self) -> Option<String> {
        self.out.newline();
        let text = self.out.text;
        let alnum = text.chars().filter(|c| c.is_alphanumeric()).count();
        // Mostly unmappable codes (subset fonts without a ToUnicode) read as noise: leave the page to OCR.
        let garbled = self.out.codes > 0 && self.out.unmapped * 2 > self.out.codes;
        if alnum < MIN_ALNUM || garbled { None } else { Some(text) }
    }

    fn font(&self, res: &mut Res, name: &[u8]) -> Option<Rc<Font>> {
        if let Some(hit) = res.font_cache.get(name) {
            return hit.clone();
        }
        if res.fonts.is_none() {
            let loaded = self.doc.dict_of(&res.res).and_then(|r| get(&r, b"Font").and_then(|f| self.doc.dict_of(f)));
            self.doc.charge(loaded.as_ref().map_or(1, |d| d.len() as u64 / 4 + 1));
            res.fonts = Some(loaded);
        }
        let fonts = res.fonts.as_ref().and_then(|f| f.as_ref())?;
        // Looking a name up scans the dictionary: pay for it.
        self.doc.charge(fonts.len() as u64 / 16 + 1);
        let built = get(fonts, name).and_then(|entry| self.build_font(entry));
        if res.font_cache.len() < MAX_NAME_CACHE {
            res.font_cache.insert(name.to_vec(), built.clone());
        }
        built
    }

    fn build_font(&self, entry: &Obj) -> Option<Rc<Font>> {
        let key = if let Obj::Ref(n) = entry { Some(*n) } else { None };
        if let Some(k) = key {
            if let Some(f) = self.doc.fonts.borrow().get(&k) {
                return Some(f.clone());
            }
        }
        let dict = self.doc.dict_of(entry)?;
        let resolve = |o: &Obj| self.doc.resolve(o);
        let cmap = |o: &Obj| self.doc.cmap(o);
        let charge = |units: u64| self.doc.charge(units);
        let font = Rc::new(Font::build(&dict, &Context { resolve: &resolve, cmap: &cmap, charge: &charge }));
        if let Some(k) = key {
            self.doc.fonts.borrow_mut().insert(k, font.clone());
        }
        Some(font)
    }

    #[allow(unused_assignments)]
    fn run(&mut self, data: &[u8], res: &mut Res, start: Gs, depth: usize) {
        let mut gs = start;
        let mut saved: Vec<Gs> = Vec::new();
        let mut lx = Lexer::new(data, 0, false, STREAM_NODES);
        lx.max_items = MAX_ARRAY_ITEMS;
        let mut args: Vec<Obj> = Vec::new();
        // Operand nodes this stream holds in `args`; given back to `self.retained` whenever they are dropped and on return.
        let mut held = 0usize;
        macro_rules! release {
            () => {
                self.retained -= held;
                held = 0;
            };
        }
        loop {
            if self.out.chars >= self.out.cap {
                release!();
                return;
            }
            // Every token is paid for in proportion to the bytes it took, operands included: a stream of numbers with
            // no operator at all still meets the deadline.
            let before = lx.pos;
            let next = lx.next();
            if !self.doc.charge(1 + (lx.pos.saturating_sub(before) / 16) as u64) {
                release!();
                return;
            }
            let tok = match next {
                Ok(Some(t)) => t,
                Ok(None) => {
                    release!();
                    return;
                }
                Err(()) => {
                    // Damaged operand: drop what we have and carry on from the next token. Out of nodes: stop.
                    args.clear();
                    release!();
                    if lx.pos >= data.len() || lx.budget == 0 {
                        return;
                    }
                    continue;
                }
            };
            let op = match tok {
                Obj::Op(op) => op,
                other => {
                    if args.len() >= MAX_STACK {
                        let dropped = args.remove(0);
                        let w = weight(&dropped);
                        self.retained -= w;
                        held -= w;
                    }
                    let w = weight(&other);
                    if self.retained + w > RETAINED_NODES {
                        // Too much held across the nesting: this operand is dropped (the operator will see fewer).
                        continue;
                    }
                    self.retained += w;
                    held += w;
                    args.push(other);
                    continue;
                }
            };
            let ops = self.doc.ops_left.get();
            if ops == 0 {
                self.doc.kill();
                release!();
                return;
            }
            self.doc.ops_left.set(ops - 1);
            match op.as_slice() {
                b"q" => {
                    if saved.len() < 64 {
                        saved.push(gs.clone());
                    }
                }
                b"Q" => {
                    if let Some(g) = saved.pop() {
                        gs = g;
                    }
                }
                b"BT" => {
                    self.tm = IDENT;
                    self.tlm = IDENT;
                }
                b"Tf" => {
                    if let (Some(Obj::Name(n)), Some(size)) = (args.first(), args.get(1).and_then(|o| o.num())) {
                        gs.font = self.font(res, n);
                        gs.size = size;
                    }
                }
                b"Tc" => gs.tc = nums(&args).first().copied().unwrap_or(0.0),
                b"Tw" => gs.tw = nums(&args).first().copied().unwrap_or(0.0),
                b"Tz" => gs.th = nums(&args).first().copied().unwrap_or(100.0) / 100.0,
                b"TL" => gs.tl = nums(&args).first().copied().unwrap_or(0.0),
                b"Td" | b"TD" => {
                    let n = nums(&args);
                    if n.len() >= 2 {
                        if op.as_slice() == b"TD" {
                            gs.tl = -n[1];
                        }
                        self.tlm = mul(&[1.0, 0.0, 0.0, 1.0, n[0], n[1]], &self.tlm);
                        self.tm = self.tlm;
                    }
                }
                b"Tm" => {
                    let n = nums(&args);
                    if n.len() >= 6 {
                        self.tlm = [n[0], n[1], n[2], n[3], n[4], n[5]];
                        self.tm = self.tlm;
                    }
                }
                b"T*" => self.next_line(&gs),
                b"Tj" => {
                    if let Some(Obj::Str(s)) = args.last() {
                        let s = s.clone();
                        self.show(&gs, &s, true);
                    }
                }
                b"'" | b"\"" => {
                    if op.as_slice() == b"\"" && args.len() >= 3 {
                        gs.tw = args[0].num().unwrap_or(0.0);
                        gs.tc = args[1].num().unwrap_or(0.0);
                    }
                    self.next_line(&gs);
                    if let Some(Obj::Str(s)) = args.last() {
                        let s = s.clone();
                        self.show(&gs, &s, true);
                    }
                }
                b"TJ" => {
                    if let Some(Obj::Arr(items)) = args.last() {
                        let mut first = true;
                        if !self.doc.charge(items.len() as u64 / 4) {
                            release!();
                            return;
                        }
                        for item in items {
                            match item {
                                Obj::Str(s) => {
                                    self.show(&gs, s, first);
                                    first = false;
                                }
                                other => {
                                    if let Some(n) = other.num() {
                                        let tx = -n / 1000.0 * gs.size * gs.th;
                                        self.tm = mul(&[1.0, 0.0, 0.0, 1.0, tx, 0.0], &self.tm);
                                        if n < -200.0 && !first {
                                            self.out.space();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                b"Do" => {
                    if let Some(Obj::Name(n)) = args.first() {
                        let n = n.clone();
                        self.form(&n, res, &gs, depth);
                    }
                }
                b"BI" => self.skip_inline_image(&mut lx),
                _ => {}
            }
            args.clear();
            release!();
        }
    }

    fn next_line(&mut self, gs: &Gs) {
        self.tlm = mul(&[1.0, 0.0, 0.0, 1.0, 0.0, -gs.tl], &self.tlm);
        self.tm = self.tlm;
    }

    fn form(&mut self, name: &[u8], res: &mut Res, gs: &Gs, depth: usize) {
        let left = self.doc.forms_left.get();
        if depth >= MAX_FORM_DEPTH || left == 0 || !self.doc.charge(64) {
            return;
        }
        self.doc.forms_left.set(left - 1);
        if res.xobjects.is_none() {
            let loaded = self.doc.dict_of(&res.res).and_then(|r| get(&r, b"XObject").and_then(|x| self.doc.dict_of(x)));
            self.doc.charge(loaded.as_ref().map_or(1, |d| d.len() as u64 / 4 + 1));
            res.xobjects = Some(loaded);
        }
        let Some(xobjects) = res.xobjects.as_ref().and_then(|x| x.as_ref()) else { return };
        self.doc.charge(xobjects.len() as u64 / 16 + 1);
        let Some(entry) = get(xobjects, name) else { return };
        let entry = entry.clone();
        let Some(dict) = self.doc.dict_of(&entry) else { return };
        if !get(&dict, b"Subtype").is_some_and(|s| self.doc.resolve(s).is_name(b"Form")) {
            return;
        }
        let Some(form) = self.doc.form_data(&entry, &dict) else { return };
        let (tm, tlm) = (self.tm, self.tlm);
        self.out.newline();
        self.out.last_y = f64::NAN;
        match &form.resources {
            Some(own) => {
                let mut inner = Res::new(own.clone());
                self.run(&form.data, &mut inner, gs.clone(), depth + 1);
            }
            None => self.run(&form.data, res, gs.clone(), depth + 1),
        }
        self.out.newline();
        self.out.last_y = f64::NAN;
        self.tm = tm;
        self.tlm = tlm;
    }

    fn skip_inline_image(&mut self, lx: &mut Lexer) {
        // Dictionary entries up to ID, then binary data up to a standalone EI.
        let mut guard = 0;
        while let Ok(Some(t)) = lx.next() {
            guard += 1;
            if guard > 200 {
                break;
            }
            if matches!(&t, Obj::Op(o) if o == b"ID") {
                break;
            }
        }
        let d = lx.d;
        let mut i = lx.pos + 1;
        while i + 2 <= d.len() {
            if d[i] == b'E' && d[i + 1] == b'I' && (i == 0 || is_space(d[i - 1])) && d.get(i + 2).is_none_or(|&b| is_space(b)) {
                lx.pos = i + 2;
                return;
            }
            i += 1;
        }
        lx.pos = d.len();
    }

    fn show(&mut self, gs: &Gs, s: &[u8], check_position: bool) {
        let fallback = self.fallback.clone();
        let font: &Font = match &gs.font {
            Some(f) => f,
            None => &fallback,
        };
        let scale = (self.tm[2].hypot(self.tm[3])).max(1e-6);
        let size_eff = (gs.size.abs() * scale).max(1e-3);
        if check_position {
            let (x, y) = (self.tm[4], self.tm[5]);
            if self.out.last_y.is_nan() {
                self.out.newline();
            } else if (y - self.out.last_y).abs() > 0.3 * size_eff {
                self.out.newline();
            } else {
                let gap = x - self.out.last_end;
                if gap > 0.2 * size_eff || gap < -size_eff {
                    self.out.space();
                }
            }
        }
        let mut i = 0;
        let mut glyphs = 0u32;
        while i < s.len() {
            if self.out.chars >= self.out.cap {
                break;
            }
            // One unit per glyph; the clock is looked at every few thousand glyphs.
            glyphs += 1;
            if glyphs & 0x3ff == 1 && !self.doc.charge(1024) {
                return;
            }
            let (code, used) = font.next_code(s, &mut i);
            self.out.codes += 1;
            match font.text(code) {
                Some(t) => self.out.push_str(&t),
                None => {
                    if !(used == 1 && code == 32) {
                        self.out.unmapped += 1;
                    }
                }
            }
            let w = font.width(code) as f64 / 1000.0;
            let word = if used == 1 && code == 32 { gs.tw } else { 0.0 };
            let tx = (w * gs.size + gs.tc + word) * gs.th;
            self.tm = mul(&[1.0, 0.0, 0.0, 1.0, tx, 0.0], &self.tm);
        }
        self.out.last_y = self.tm[5];
        self.out.last_end = self.tm[4];
        self.out.line_open = true;
    }
}
