//! PDF text layer reader (I3 `PdfText`), written for Stack: no dependencies, no unsafe, every loop bounded.
//!
//! It never trusts the cross-reference tables (hostile or damaged files make them loop or lie): objects are found by
//! scanning the file, later copies replacing earlier ones, which also gives the incremental-update result. Object streams
//! are unpacked on demand. Limits: input size, per-stream and total decompressed bytes, parser nesting and node counts,
//! page-tree nodes, form nesting, operator count and a wall-clock budget. Encrypted files answer "protected".

mod content;
mod filters;
mod font;
mod lex;

use lex::{Dict, Lexer, Obj, get, is_regular, is_space};
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::rc::Rc;
use std::time::{Duration, Instant};

pub const MAX_INPUT: usize = 160 * 1024 * 1024;
const MAX_TOTAL_INFLATE: usize = 256 * 1024 * 1024;
const MAX_CONTENT: usize = 48 * 1024 * 1024;
const MAX_SMALL_STREAM: usize = 16 * 1024 * 1024;
const OBJECT_NODES: usize = 400_000;
const MAX_PAGE_NODES: usize = 250_000;
const MAX_REF_DEPTH: usize = 24;
const MAX_OPS: usize = 30_000_000;
const MAX_FORMS: usize = 5_000;
/// Nodes the whole document may hand out by resolving references (each resolve of a big array copies it, so a file that
/// points many references at one large array is cut off here rather than copied without end).
const DOC_NODES: usize = 30_000_000;
/// Nodes of parsed objects kept for reuse.
const CACHE_NODES: usize = 2_000_000;
/// The most bytes read for one classic trailer dictionary, and how many of the last trailers are read at all.
const TRAILER_BYTES: usize = 64 * 1024;
const TRAILERS_KEPT: usize = 64;
/// Decoded form streams kept for reuse (a form drawn many times is decoded once).
const FORM_CACHE_BYTES: usize = 32 * 1024 * 1024;
/// Work units between two looks at the clock.
const CHECK_EVERY: u64 = 2048;

pub struct Config {
    pub max_pages: usize,
    /// Characters kept over the whole file (0 = 1,000,000).
    pub max_chars: usize,
    pub budget: Duration,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Fail {
    Protected,
    Damaged,
    TooLarge,
}

#[derive(Clone, Copy)]
enum Loc {
    At(usize),
    InStm { stm: u32, index: usize },
}

struct ObjStm {
    data: Vec<u8>,
    first: usize,
    /// (object number, offset relative to first)
    entries: Vec<(u32, usize)>,
}

/// A form XObject: its decoded content and its own resources (None = inherits the caller's).
pub struct FormData {
    pub data: Vec<u8>,
    pub resources: Option<Rc<Obj>>,
}

pub struct Doc<'a> {
    data: &'a [u8],
    locs: HashMap<u32, Loc>,
    stms: RefCell<HashMap<u32, Option<Rc<ObjStm>>>>,
    inflate_left: Cell<usize>,
    pub deadline: Instant,
    root: Option<u32>,
    encrypted: bool,
    fonts: RefCell<HashMap<u32, Rc<font::Font>>>,
    cmaps: RefCell<HashMap<u32, Option<Rc<font::CMap>>>>,
    forms: RefCell<HashMap<u32, Option<Rc<FormData>>>>,
    form_bytes: Cell<usize>,
    /// Parsed objects by number, with their node counts (see `object`).
    objects: RefCell<HashMap<u32, Option<(Rc<Obj>, usize)>>>,
    cached_nodes: Cell<usize>,
    nodes_left: Cell<usize>,
    /// One work counter for the whole document: every loop that can run long charges it (see `charge`).
    work: Cell<u64>,
    next_check: Cell<u64>,
    dead: Cell<bool>,
    pub ops_left: Cell<usize>,
    pub forms_left: Cell<usize>,
    /// An "endstream" search that failed from here on fails from any later start too.
    no_endstream_from: Cell<usize>,
}

fn rfind(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return None;
    }
    hay.windows(needle.len()).rposition(|w| w == needle)
}

fn find(hay: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    if needle.is_empty() || from >= hay.len() {
        return None;
    }
    let first = needle[0];
    let mut i = from;
    while i + needle.len() <= hay.len() {
        match hay[i..hay.len() - needle.len() + 1].iter().position(|&b| b == first) {
            None => return None,
            Some(p) => {
                i += p;
                if &hay[i..i + needle.len()] == needle {
                    return Some(i);
                }
                i += 1;
            }
        }
    }
    None
}

impl<'a> Doc<'a> {
    pub fn open(data: &'a [u8], deadline: Instant) -> Result<Doc<'a>, Fail> {
        if data.len() > MAX_INPUT {
            return Err(Fail::TooLarge);
        }
        let mut doc = Doc {
            data,
            locs: HashMap::new(),
            stms: RefCell::new(HashMap::new()),
            inflate_left: Cell::new(MAX_TOTAL_INFLATE),
            deadline,
            root: None,
            encrypted: false,
            fonts: RefCell::new(HashMap::new()),
            cmaps: RefCell::new(HashMap::new()),
            forms: RefCell::new(HashMap::new()),
            form_bytes: Cell::new(0),
            objects: RefCell::new(HashMap::new()),
            cached_nodes: Cell::new(0),
            nodes_left: Cell::new(DOC_NODES),
            work: Cell::new(0),
            next_check: Cell::new(CHECK_EVERY),
            dead: Cell::new(false),
            ops_left: Cell::new(MAX_OPS),
            forms_left: Cell::new(MAX_FORMS),
            no_endstream_from: Cell::new(usize::MAX),
        };
        doc.scan();
        if doc.locs.is_empty() {
            return Err(Fail::Damaged);
        }
        if doc.encrypted {
            return Err(Fail::Protected);
        }
        if doc.root.is_none() {
            doc.root = doc.find_catalog();
        }
        Ok(doc)
    }

    fn expired(&self) -> bool {
        self.dead.get() || Instant::now() > self.deadline
    }

    /// Charge `units` of work to the document's shared budget; false once the deadline has passed (sticky).
    /// The clock is read about every `CHECK_EVERY` units, so callers charge in proportion to the work they do.
    pub fn charge(&self, units: u64) -> bool {
        if self.dead.get() {
            return false;
        }
        let w = self.work.get().saturating_add(units);
        self.work.set(w);
        if w >= self.next_check.get() {
            self.next_check.set(w.saturating_add(CHECK_EVERY));
            if Instant::now() > self.deadline {
                self.dead.set(true);
                return false;
            }
        }
        true
    }

    pub fn is_dead(&self) -> bool {
        self.dead.get() || Instant::now() > self.deadline
    }

    /// Stop all further work on this document (a limit other than time was reached).
    pub fn kill(&self) {
        self.dead.set(true);
    }

    fn loc_key(&self, num: u32) -> Option<(u32, usize)> {
        match *self.locs.get(&num)? {
            Loc::At(off) => Some((0, off)),
            // Inside an object stream the place is the byte offset, not the slot: many slots can point at one object.
            Loc::InStm { stm, index } => {
                let parsed = self.objstm(stm)?;
                Some((stm.wrapping_add(1), parsed.first.saturating_add(parsed.entries.get(index)?.1)))
            }
        }
    }

    /// The parsed ToUnicode map behind this reference, shared by every font that names it. Charged to the inflate budget.
    pub fn cmap(&self, o: &Obj) -> Option<Rc<font::CMap>> {
        let Obj::Ref(n) = o else { return None };
        if let Some(c) = self.cmaps.borrow().get(n) {
            return c.clone();
        }
        let built = self.stream_of(o, 8 * 1024 * 1024).and_then(|data| {
            self.charge(data.len() as u64 / 8);
            let map = font::CMap::parse(&data);
            if map.is_empty() { None } else { Some(Rc::new(map)) }
        });
        self.cmaps.borrow_mut().insert(*n, built.clone());
        built
    }

    /// A form XObject's decoded content, cached by object number while the cache is small.
    pub fn form_data(&self, entry: &Obj, dict: &Dict) -> Option<Rc<FormData>> {
        let num = if let Obj::Ref(n) = entry { Some(*n) } else { None };
        if let Some(n) = num {
            if let Some(c) = self.forms.borrow().get(&n) {
                return c.clone();
            }
        }
        let built = self.stream_of(entry, MAX_CONTENT).map(|data| {
            self.charge(data.len() as u64 / 16);
            let resources = get(dict, b"Resources").map(|r| Rc::new(r.clone()));
            Rc::new(FormData { data, resources })
        });
        if let Some(n) = num {
            let size = built.as_ref().map_or(0, |b| b.data.len());
            if built.is_none() || self.form_bytes.get() + size <= FORM_CACHE_BYTES {
                self.form_bytes.set(self.form_bytes.get() + size);
                self.forms.borrow_mut().insert(n, built.clone());
            }
        }
        built
    }

    /// Find every "N G obj" and the trailers; later definitions replace earlier ones.
    fn scan(&mut self) {
        let d = self.data;
        let mut stm_nums: Vec<u32> = Vec::new();
        let mut i = 0usize;
                while let Some(p) = find(d, b"obj", i) {
            if self.expired() {
                break;
            }
            i = p + 3;
            if p + 3 < d.len() && is_regular(d[p + 3]) && !matches!(d[p + 3], b'<' | b'[' | b'(' | b'/') {
                continue;
            }
            // Walk back over "N G ".
            let mut q = p;
            let mut ok = true;
            let mut numbers = [0u64; 2];
            for slot in 0..2 {
                let mut sp = 0;
                while q > 0 && is_space(d[q - 1]) {
                    q -= 1;
                    sp += 1;
                }
                let end = q;
                while q > 0 && d[q - 1].is_ascii_digit() && end - q < 10 {
                    q -= 1;
                }
                if sp == 0 || q == end {
                    ok = false;
                    break;
                }
                numbers[slot] = std::str::from_utf8(&d[q..end]).ok().and_then(|s| s.parse().ok()).unwrap_or(u64::MAX);
            }
            if !ok || (q > 0 && !is_space(d[q - 1]) && !matches!(d[q - 1], b'>' | b']' | b')')) {
                continue;
            }
            let num = numbers[1];
            if num > u32::MAX as u64 {
                continue;
            }
            let num = num as u32;
            let body = p + 3;
            let mut lx = Lexer::new(d, body, true, OBJECT_NODES);
            let Ok(Some(obj)) = lx.next() else { continue };
            self.locs.insert(num, Loc::At(body));
            let mut end = lx.pos;
            if let Obj::Dict(dict) = &obj {
                lx.skip_space();
                if d[lx.pos.min(d.len())..].starts_with(b"stream") {
                    if let Some((_, range)) = self.stream_range(dict, lx.pos + 6) {
                        end = range.1;
                    }
                    match get(dict, b"Type") {
                        Some(t) if t.is_name(b"ObjStm") => stm_nums.push(num),
                        Some(t) if t.is_name(b"XRef") => self.take_trailer(dict),
                        _ => {}
                    }
                }
            }
            i = end.max(body);
        }
        // Classic trailers (the last one wins). Only the last few are read, each from at most `TRAILER_BYTES`, and the
        // search stops at the deadline: an unterminated string after every "trailer" must not cost a pass over the file each.
        let mut places: std::collections::VecDeque<usize> = std::collections::VecDeque::new();
        let mut t = 0;
        while let Some(p) = find(d, b"trailer", t) {
            t = p + 7;
            if places.len() == TRAILERS_KEPT {
                places.pop_front();
            }
            places.push_back(p + 7);
            if !self.charge(8) {
                break;
            }
        }
        // Trailers after the last startxref are the live ones: read them last so they win.
        let last_startxref = rfind(d, b"startxref");
        let (before, after): (Vec<usize>, Vec<usize>) = places.into_iter().partition(|&p| last_startxref.is_some_and(|s| p <= s));
        for at in before.into_iter().chain(after) {
            if self.expired() {
                break;
            }
            let end = d.len().min(at.saturating_add(TRAILER_BYTES));
            let mut lx = Lexer::new(&d[..end], at, true, 10_000);
            let parsed = lx.next();
            self.charge((lx.pos.saturating_sub(at) / 16) as u64 + 1);
            if let Ok(Some(Obj::Dict(dict))) = parsed {
                self.take_trailer(&dict);
            }
        }
        for stm in stm_nums {
            if self.expired() {
                break;
            }
            let Some(parsed) = self.objstm(stm) else { continue };
            for (index, (n, _)) in parsed.entries.iter().enumerate() {
                self.locs.entry(*n).or_insert(Loc::InStm { stm, index });
            }
        }
    }

    fn take_trailer(&mut self, dict: &Dict) {
        if let Some(Obj::Ref(r)) = get(dict, b"Root") {
            self.root = Some(*r);
        }
        if get(dict, b"Encrypt").is_some_and(|e| !matches!(e, Obj::Null)) {
            self.encrypted = true;
        }
    }

    fn find_catalog(&self) -> Option<u32> {
        let mut nums: Vec<u32> = self.locs.keys().copied().collect();
        nums.sort_unstable();
        for n in nums.into_iter().take(500_000) {
            if self.expired() {
                return None;
            }
            if let Some(Obj::Dict(d)) = self.object(n) {
                if get(&d, b"Type").is_some_and(|t| t.is_name(b"Catalog")) {
                    return Some(n);
                }
            }
        }
        None
    }

    /// The data range of a stream whose "stream" keyword ends at `after`.
    fn stream_range(&self, dict: &Dict, after: usize) -> Option<((usize, usize), (usize, usize))> {
        let d = self.data;
        let mut start = after;
        if d.get(start) == Some(&b'\r') {
            start += 1;
        }
        if d.get(start) == Some(&b'\n') {
            start += 1;
        }
        if start > d.len() {
            return None;
        }
        let declared = match get(dict, b"Length") {
            Some(Obj::Int(n)) if *n >= 0 => Some(*n as usize),
            Some(Obj::Ref(r)) => match self.object(*r) {
                Some(Obj::Int(n)) if n >= 0 => Some(n as usize),
                _ => None,
            },
            _ => None,
        };
        if let Some(len) = declared {
            if let Some(end) = start.checked_add(len) {
                if end <= d.len() {
                    let mut q = end;
                    while q < d.len() && is_space(d[q]) {
                        q += 1;
                    }
                    if d[q.min(d.len())..].starts_with(b"endstream") {
                        return Some(((start, end), (start, q + 9)));
                    }
                }
            }
        }
        if start >= self.no_endstream_from.get() {
            return None;
        }
        let Some(e) = find(d, b"endstream", start) else {
            self.no_endstream_from.set(start);
            return None;
        };
        let mut end = e;
        if end > start && d[end - 1] == b'\n' {
            end -= 1;
        }
        if end > start && d[end - 1] == b'\r' {
            end -= 1;
        }
        Some(((start, end), (start, e + 9)))
    }

    /// The object with this number (not following references). Parsed once and kept (as long as the kept nodes stay
    /// under `CACHE_NODES`); None once the document's budget is spent.
    pub fn object(&self, num: u32) -> Option<Obj> {
        let (found, nodes) = self.object_rc(num)?;
        self.copy_of(&found, nodes)
    }

    /// A copy of a kept object, paid for out of the document's node budget (the document stops when it runs out).
    fn copy_of(&self, o: &Rc<Obj>, nodes: usize) -> Option<Obj> {
        let left = self.nodes_left.get();
        if nodes > left || !self.charge(nodes as u64 / 4) {
            self.kill();
            return None;
        }
        self.nodes_left.set(left - nodes);
        Some((**o).clone())
    }

    fn object_rc(&self, num: u32) -> Option<(Rc<Obj>, usize)> {
        if !self.charge(1) {
            return None;
        }
        if let Some(hit) = self.objects.borrow().get(&num) {
            return hit.clone();
        }
        let parsed = self.parse_object(num).map(|o| {
            let n = count_nodes(&o);
            (Rc::new(o), n)
        });
        let size = parsed.as_ref().map_or(1, |p| p.1);
        if self.cached_nodes.get().saturating_add(size) <= CACHE_NODES {
            self.cached_nodes.set(self.cached_nodes.get() + size);
            self.objects.borrow_mut().insert(num, parsed.clone());
        }
        parsed
    }

    fn parse_object(&self, num: u32) -> Option<Obj> {
        match *self.locs.get(&num)? {
            Loc::At(off) => {
                let mut lx = Lexer::new(self.data, off, true, OBJECT_NODES);
                let parsed = lx.next().ok().flatten();
                self.charge((lx.pos.saturating_sub(off) / 16) as u64);
                parsed
            }
            Loc::InStm { stm, index } => {
                let parsed = self.objstm(stm)?;
                let (n, rel) = *parsed.entries.get(index)?;
                if n != num {
                    return None;
                }
                let start = parsed.first.checked_add(rel)?;
                let mut lx = Lexer::new(&parsed.data, start, true, OBJECT_NODES);
                let obj = lx.next().ok().flatten();
                self.charge((lx.pos.saturating_sub(start) / 16) as u64);
                obj
            }
        }
    }

    /// Follows references to the object they lead to (Null when missing, too deep, or the document's budget is spent).
    /// Handing out a copy costs its node count: the total over the document is `DOC_NODES`, and the work counter is
    /// charged too, so a file that points many references at one large array stops instead of copying it without end.
    pub fn resolve(&self, o: &Obj) -> Obj {
        let mut cur = o.clone();
        for _ in 0..MAX_REF_DEPTH {
            match cur {
                Obj::Ref(n) => match self.object_rc(n) {
                    Some((next, nodes)) => match self.copy_of(&next, nodes) {
                        Some(copy) => cur = copy,
                        None => return Obj::Null,
                    },
                    None => return Obj::Null,
                },
                other => return other,
            }
        }
        Obj::Null
    }

    pub fn dict_of(&self, o: &Obj) -> Option<Dict> {
        match self.resolve(o) {
            Obj::Dict(d) => Some(d),
            _ => None,
        }
    }

    /// A stream object's decoded bytes, capped; None when not a stream, unreadable or over the shared budget.
    pub fn stream_of(&self, o: &Obj, cap: usize) -> Option<Vec<u8>> {
        let num = match o {
            Obj::Ref(n) => *n,
            _ => return None,
        };
        // Follow reference chains to the object that holds the stream.
        let mut num = num;
        let mut loc = *self.locs.get(&num)?;
        for _ in 0..MAX_REF_DEPTH {
            let Loc::At(off) = loc else { return None };
            let mut lx = Lexer::new(self.data, off, true, OBJECT_NODES);
            match lx.next().ok().flatten()? {
                Obj::Ref(next) => {
                    num = next;
                    loc = *self.locs.get(&num)?;
                }
                Obj::Dict(dict) => {
                    lx.skip_space();
                    if !self.data[lx.pos.min(self.data.len())..].starts_with(b"stream") {
                        return None;
                    }
                    let ((s, e), _) = self.stream_range(&dict, lx.pos + 6)?;
                    return self.decode(&dict, &self.data[s..e], cap);
                }
                _ => return None,
            }
        }
        None
    }

    fn decode(&self, dict: &Dict, raw: &[u8], cap: usize) -> Option<Vec<u8>> {
        let filter = get(dict, b"Filter").or_else(|| get(dict, b"F")).map(|o| self.resolve(o));
        let parms = get(dict, b"DecodeParms").or_else(|| get(dict, b"DP")).map(|o| self.resolve(o));
        let names: Vec<Vec<u8>> = match filter {
            None | Some(Obj::Null) => Vec::new(),
            Some(Obj::Name(n)) => vec![n],
            Some(Obj::Arr(a)) => a.iter().filter_map(|o| self.resolve(o).name().map(|n| n.to_vec())).collect(),
            _ => return None,
        };
        let mut data: std::borrow::Cow<[u8]> = std::borrow::Cow::Borrowed(raw);
        if names.is_empty() {
            // Unfiltered bytes count against the shared budget too, so a stream used again and again cannot be free.
            let left = self.inflate_left.get();
            if raw.len() > left {
                return None;
            }
            self.inflate_left.set(left - raw.len());
        }
        for (i, name) in names.iter().enumerate() {
            let budget = cap.min(self.inflate_left.get());
            if budget == 0 {
                return None;
            }
            let decoded = match name.as_slice() {
                b"FlateDecode" | b"Fl" => filters::flate(&data, budget, self.deadline),
                b"LZWDecode" | b"LZW" => {
                    let early = match &parms {
                        Some(Obj::Dict(p)) => get(p, b"EarlyChange").and_then(|o| o.int()) != Some(0),
                        Some(Obj::Arr(a)) => match a.get(i).map(|o| self.resolve(o)) {
                            Some(Obj::Dict(p)) => get(&p, b"EarlyChange").and_then(|o| o.int()) != Some(0),
                            _ => true,
                        },
                        _ => true,
                    };
                    filters::lzw(&data, budget, early)
                }
                b"ASCII85Decode" | b"A85" => filters::ascii85(&data, budget),
                b"ASCIIHexDecode" | b"AHx" => filters::ascii_hex(&data, budget),
                b"RunLengthDecode" | b"RL" => filters::run_length(&data, budget),
                _ => return None,
            };
            self.inflate_left.set(self.inflate_left.get().saturating_sub(decoded.data.len()));
            // A predictor would need undoing; content, fonts and CMaps do not use one. Reject rather than misread.
            if has_predictor(&parms, i) {
                return None;
            }
            // Damaged compression keeps what decoded; a stream that gave nothing is no stream.
            if decoded.data.is_empty() && !decoded.complete && !raw.is_empty() {
                return None;
            }
            data = std::borrow::Cow::Owned(decoded.data);
        }
        Some(data.into_owned())
    }

    fn objstm(&self, num: u32) -> Option<Rc<ObjStm>> {
        if let Some(cached) = self.stms.borrow().get(&num) {
            return cached.clone();
        }
        let built = self.build_objstm(num).map(Rc::new);
        self.stms.borrow_mut().insert(num, built.clone());
        built
    }

    fn build_objstm(&self, num: u32) -> Option<ObjStm> {
        let Loc::At(off) = *self.locs.get(&num)? else { return None };
        let mut lx = Lexer::new(self.data, off, true, OBJECT_NODES);
        let Obj::Dict(dict) = lx.next().ok().flatten()? else { return None };
        let n = get(&dict, b"N").map(|o| self.resolve(o)).and_then(|o| o.int())?;
        let first = get(&dict, b"First").map(|o| self.resolve(o)).and_then(|o| o.int())?;
        if !(0..=200_000).contains(&n) || first < 0 {
            return None;
        }
        let data = self.stream_of(&Obj::Ref(num), MAX_SMALL_STREAM)?;
        let first = first as usize;
        if first > data.len() {
            return None;
        }
        let mut head = Lexer::new(&data[..first], 0, false, 1_000_000);
        let mut entries = Vec::new();
        for _ in 0..n {
            let (Ok(Some(a)), Ok(Some(b))) = (head.next(), head.next()) else { break };
            let (Some(a), Some(b)) = (a.int(), b.int()) else { break };
            if a < 0 || b < 0 || a > u32::MAX as i64 {
                break;
            }
            entries.push((a as u32, b as usize));
        }
        Some(ObjStm { data, first, entries })
    }

    /// Walks the page tree and hands each page (dictionary, inherited resources) to `visit`, in document order, at most `max`
    /// pages; `visit` answers false to stop. Nothing is collected: pages are read one by one. Resources are shared
    /// (`Rc`), never copied per kid. Every kid pushed counts against `MAX_PAGE_NODES`, and two object numbers that
    /// lead to the same place in the file count once. None when the tree is too big, too deep, cut short or out of time.
    pub fn each_page(&self, max: usize, mut visit: impl FnMut(&Dict, &Rc<Obj>) -> bool) -> Option<()> {
        let root = self.dict_of(&Obj::Ref(self.root?))?;
        let top = get(&root, b"Pages")?.clone();
        let mut emitted = 0usize;
        let mut seen: HashSet<u32> = HashSet::new();
        let mut seen_at: HashSet<(u32, usize)> = HashSet::new();
        let mut stack: Vec<(Obj, Rc<Obj>, usize)> = vec![(top, Rc::new(Obj::Null), 0)];
        let mut pushed = 1usize;
        while emitted < max {
            let Some((node, inherited, depth)) = stack.pop() else { break };
            if depth > 64 || !self.charge(8) {
                return None;
            }
            if let Obj::Ref(n) = node {
                if !seen.insert(n) {
                    continue;
                }
                if let Some(key) = self.loc_key(n) {
                    if !seen_at.insert(key) {
                        continue;
                    }
                }
            }
            let Some(dict) = self.dict_of(&node) else { continue };
            let kids = get(&dict, b"Kids").map(|k| self.resolve(k));
            match kids {
                Some(Obj::Arr(list)) => {
                    if pushed.saturating_add(list.len()) > MAX_PAGE_NODES {
                        return None;
                    }
                    pushed += list.len();
                    let resources = get(&dict, b"Resources").map_or(inherited, |r| Rc::new(r.clone()));
                    for kid in list.into_iter().rev() {
                        stack.push((kid, resources.clone(), depth + 1));
                    }
                }
                _ => {
                    emitted += 1;
                    if !visit(&dict, &inherited) {
                        return None;
                    }
                }
            }
        }
        // More pages than allowed, or the walk was cut short: the caller treats it as a mismatch.
        if !stack.is_empty() {
            return None;
        }
        Some(())
    }
}

fn count_nodes(o: &Obj) -> usize {
    match o {
        Obj::Arr(a) => 1 + a.iter().map(count_nodes).sum::<usize>(),
        Obj::Dict(d) => 1 + d.iter().map(|(_, v)| 1 + count_nodes(v)).sum::<usize>(),
        _ => 1,
    }
}

fn has_predictor(parms: &Option<Obj>, i: usize) -> bool {
    let check = |d: &Dict| get(d, b"Predictor").and_then(|o| o.int()).is_some_and(|p| p > 1);
    match parms {
        Some(Obj::Dict(d)) => check(d),
        Some(Obj::Arr(a)) => matches!(a.get(i), Some(Obj::Dict(d)) if check(d)),
        _ => false,
    }
}

/// Per page: Some(text) when the page has a usable text layer, None when it needs OCR. Err for files not read at all.
pub fn page_texts(data: &[u8], cfg: &Config) -> Result<Vec<Option<String>>, Fail> {
    let started = Instant::now();
    let deadline = started + cfg.budget;
    if !data.starts_with(b"%PDF-") && find(&data[..data.len().min(1024)], b"%PDF-", 0).is_none() {
        return Err(Fail::Damaged);
    }
    let doc = Doc::open(data, deadline)?;
    let max_pages = if cfg.max_pages == 0 { 20_000 } else { cfg.max_pages };
    let mut remaining = if cfg.max_chars == 0 { 1_000_000 } else { cfg.max_chars };
    let mut out: Vec<Option<String>> = Vec::new();
    doc.each_page(max_pages, |page, inherited| {
        if doc.is_dead() {
            out.push(None);
            return true;
        }
        if remaining == 0 {
            out.push(Some(String::new()));
            return true;
        }
        // The page's own /Resources wins over the inherited ones; they are not copied unless the page has its own.
        let own = get(page, b"Resources").map(|r| Rc::new(r.clone()));
        let resources = own.as_ref().unwrap_or(inherited);
        match content::page_text(&doc, page, resources, remaining) {
            Some(t) => {
                remaining = remaining.saturating_sub(t.chars().count());
                out.push(Some(t));
            }
            None => out.push(None),
        }
        true
    })
    .ok_or(Fail::Damaged)?;
    Ok(out)
}

pub(crate) const CONTENT_CAP: usize = MAX_CONTENT;

#[cfg(test)]
mod tests;
#[cfg(test)]
mod poc_tests;
