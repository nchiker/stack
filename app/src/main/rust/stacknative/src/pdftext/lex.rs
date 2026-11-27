//! PDF tokens and objects. Bounded: nesting depth and total node count are limited, so hostile input fails instead of
//! recursing or allocating without end.

pub type Dict = Vec<(Vec<u8>, Obj)>;

#[derive(Clone, Debug)]
pub enum Obj {
    Null,
    Bool(bool),
    Int(i64),
    Real(f64),
    Name(Vec<u8>),
    Str(Vec<u8>),
    Arr(Vec<Obj>),
    Dict(Dict),
    Ref(u32),
    /// A bare keyword (an operator in a content stream, or `obj`, `stream`, ...).
    Op(Vec<u8>),
}

impl Obj {
    pub fn num(&self) -> Option<f64> {
        match self {
            Obj::Int(i) => Some(*i as f64),
            Obj::Real(r) if r.is_finite() => Some(*r),
            _ => None,
        }
    }
    pub fn int(&self) -> Option<i64> {
        match self {
            Obj::Int(i) => Some(*i),
            Obj::Real(r) if r.is_finite() => Some(*r as i64),
            _ => None,
        }
    }
    pub fn name(&self) -> Option<&[u8]> {
        if let Obj::Name(n) = self { Some(n) } else { None }
    }
    pub fn dict(&self) -> Option<&Dict> {
        if let Obj::Dict(d) = self { Some(d) } else { None }
    }
    pub fn is_name(&self, wanted: &[u8]) -> bool {
        self.name() == Some(wanted)
    }
}

pub fn get<'a>(d: &'a Dict, key: &[u8]) -> Option<&'a Obj> {
    d.iter().find(|(k, _)| k == key).map(|(_, v)| v)
}

pub fn is_space(b: u8) -> bool {
    matches!(b, 0 | 9 | 10 | 12 | 13 | 32)
}
fn is_delim(b: u8) -> bool {
    matches!(b, b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%')
}
pub fn is_regular(b: u8) -> bool {
    !is_space(b) && !is_delim(b)
}

const MAX_DEPTH: usize = 40;

pub struct Lexer<'a> {
    pub d: &'a [u8],
    pub pos: usize,
    /// "N G R" is read as a reference (file objects) or as three tokens (content streams).
    pub refs: bool,
    /// Nodes still allowed; every created object costs one.
    pub budget: usize,
    /// Longest array kept; a longer one is read to its end (each item still costs a node) and answers `Null`.
    pub max_items: usize,
}

impl<'a> Lexer<'a> {
    pub fn new(d: &'a [u8], pos: usize, refs: bool, budget: usize) -> Self {
        Lexer { d, pos: pos.min(d.len()), refs, budget, max_items: usize::MAX }
    }

    pub fn skip_space(&mut self) {
        while self.pos < self.d.len() {
            let b = self.d[self.pos];
            if is_space(b) {
                self.pos += 1;
            } else if b == b'%' {
                while self.pos < self.d.len() && self.d[self.pos] != b'\n' && self.d[self.pos] != b'\r' {
                    self.pos += 1;
                }
            } else {
                break;
            }
        }
    }

    fn spend(&mut self) -> Result<(), ()> {
        if self.budget == 0 {
            return Err(());
        }
        self.budget -= 1;
        Ok(())
    }

    /// The next object; Ok(None) at the end of data. Err on malformed or over-budget input.
    pub fn next(&mut self) -> Result<Option<Obj>, ()> {
        self.object(0)
    }

    fn object(&mut self, depth: usize) -> Result<Option<Obj>, ()> {
        if depth > MAX_DEPTH {
            return Err(());
        }
        self.skip_space();
        let Some(&b) = self.d.get(self.pos) else { return Ok(None) };
        self.spend()?;
        match b {
            b'/' => {
                self.pos += 1;
                Ok(Some(Obj::Name(self.name())))
            }
            b'(' => Ok(Some(Obj::Str(self.literal_string()))),
            b'<' => {
                if self.d.get(self.pos + 1) == Some(&b'<') {
                    self.pos += 2;
                    self.dictionary(depth)
                } else {
                    Ok(Some(Obj::Str(self.hex_string())))
                }
            }
            b'[' => {
                self.pos += 1;
                let mut items = Vec::new();
                let mut too_long = false;
                loop {
                    self.skip_space();
                    match self.d.get(self.pos) {
                        None => return Err(()),
                        Some(b']') => {
                            self.pos += 1;
                            break;
                        }
                        _ => match self.object(depth + 1)? {
                            // Inside an array only null/true/false are keywords; anything else is damage.
                            Some(Obj::Op(_)) | None => return Err(()),
                            Some(item) => {
                                if items.len() < self.max_items {
                                    items.push(item);
                                } else {
                                    too_long = true;
                                }
                            }
                        },
                    }
                }
                if too_long {
                    return Ok(Some(Obj::Null));
                }
                Ok(Some(Obj::Arr(items)))
            }
            b']' | b'>' | b')' | b'{' | b'}' => {
                // A stray delimiter becomes an operator-like token so callers can resynchronise.
                self.pos += 1;
                if b == b'>' && self.d.get(self.pos) == Some(&b'>') {
                    self.pos += 1;
                    return Ok(Some(Obj::Op(b">>".to_vec())));
                }
                Ok(Some(Obj::Op(vec![b])))
            }
            b'0'..=b'9' | b'+' | b'-' | b'.' => {
                let n = self.number();
                if self.refs {
                    if let Obj::Int(num) = n {
                        if (0..=u32::MAX as i64).contains(&num) {
                            let save = self.pos;
                            if let Some(r) = self.try_ref(num as u32) {
                                return Ok(Some(r));
                            }
                            self.pos = save;
                        }
                    }
                }
                Ok(Some(n))
            }
            _ => {
                let start = self.pos;
                while self.pos < self.d.len() && is_regular(self.d[self.pos]) {
                    self.pos += 1;
                }
                if self.pos == start {
                    self.pos += 1;
                    return Err(());
                }
                let word = &self.d[start..self.pos];
                Ok(Some(match word {
                    b"null" => Obj::Null,
                    b"true" => Obj::Bool(true),
                    b"false" => Obj::Bool(false),
                    _ => Obj::Op(word.to_vec()),
                }))
            }
        }
    }

    fn try_ref(&mut self, num: u32) -> Option<Obj> {
        self.skip_space();
        let s = self.pos;
        while self.pos < self.d.len() && self.d[self.pos].is_ascii_digit() {
            self.pos += 1;
        }
        if self.pos == s || self.pos - s > 5 {
            return None;
        }
        self.skip_space();
        if self.d.get(self.pos) == Some(&b'R') && self.d.get(self.pos + 1).is_none_or(|&b| !is_regular(b)) {
            self.pos += 1;
            return Some(Obj::Ref(num));
        }
        None
    }

    fn dictionary(&mut self, depth: usize) -> Result<Option<Obj>, ()> {
        let mut dict: Dict = Vec::new();
        loop {
            self.skip_space();
            match self.d.get(self.pos) {
                None => return Err(()),
                Some(b'>') => {
                    self.pos += 1;
                    if self.d.get(self.pos) == Some(&b'>') {
                        self.pos += 1;
                    }
                    break;
                }
                Some(b'/') => {
                    self.pos += 1;
                    let key = self.name();
                    self.skip_space();
                    if self.d.get(self.pos) == Some(&b'>') {
                        dict.push((key, Obj::Null));
                        continue;
                    }
                    match self.object(depth + 1)? {
                        Some(Obj::Op(_)) | None => return Err(()),
                        Some(v) => dict.push((key, v)),
                    }
                }
                Some(_) => {
                    // Junk where a key should be: skip one token.
                    if self.object(depth + 1)?.is_none() {
                        return Err(());
                    }
                }
            }
        }
        Ok(Some(Obj::Dict(dict)))
    }

    fn name(&mut self) -> Vec<u8> {
        let mut out = Vec::new();
        while self.pos < self.d.len() && is_regular(self.d[self.pos]) {
            let b = self.d[self.pos];
            if b == b'#' && self.pos + 2 < self.d.len() {
                if let (Some(h), Some(l)) = (hex(self.d[self.pos + 1]), hex(self.d[self.pos + 2])) {
                    out.push(h * 16 + l);
                    self.pos += 3;
                    continue;
                }
            }
            out.push(b);
            self.pos += 1;
        }
        out
    }

    fn number(&mut self) -> Obj {
        let mut neg = false;
        let mut int: i64 = 0;
        let mut frac = 0f64;
        let mut scale = 1f64;
        let mut seen_dot = false;
        let mut any_digit = false;
        let mut overflow = false;
        while self.pos < self.d.len() {
            let b = self.d[self.pos];
            match b {
                b'+' => {}
                b'-' => neg = true,
                b'.' => {
                    if seen_dot {
                        break;
                    }
                    seen_dot = true;
                }
                b'0'..=b'9' => {
                    any_digit = true;
                    let v = (b - b'0') as i64;
                    if seen_dot {
                        scale /= 10.0;
                        frac += v as f64 * scale;
                    } else {
                        match int.checked_mul(10).and_then(|x| x.checked_add(v)) {
                            Some(x) => int = x,
                            None => overflow = true,
                        }
                    }
                }
                _ => break,
            }
            self.pos += 1;
        }
        if !any_digit {
            return Obj::Int(0);
        }
        if overflow {
            return Obj::Real(if neg { -1e18 } else { 1e18 });
        }
        if seen_dot {
            let v = int as f64 + frac;
            Obj::Real(if neg { -v } else { v })
        } else {
            Obj::Int(if neg { -int } else { int })
        }
    }

    fn literal_string(&mut self) -> Vec<u8> {
        self.pos += 1; // (
        let mut out = Vec::new();
        let mut depth = 1u32;
        while self.pos < self.d.len() {
            let b = self.d[self.pos];
            self.pos += 1;
            match b {
                b'(' => {
                    depth += 1;
                    out.push(b);
                }
                b')' => {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                    out.push(b);
                }
                b'\\' => {
                    let Some(&e) = self.d.get(self.pos) else { break };
                    self.pos += 1;
                    match e {
                        b'n' => out.push(b'\n'),
                        b'r' => out.push(b'\r'),
                        b't' => out.push(b'\t'),
                        b'b' => out.push(8),
                        b'f' => out.push(12),
                        b'0'..=b'7' => {
                            let mut v = (e - b'0') as u32;
                            for _ in 0..2 {
                                match self.d.get(self.pos) {
                                    Some(&c @ b'0'..=b'7') => {
                                        v = v * 8 + (c - b'0') as u32;
                                        self.pos += 1;
                                    }
                                    _ => break,
                                }
                            }
                            out.push((v & 0xff) as u8);
                        }
                        b'\r' => {
                            if self.d.get(self.pos) == Some(&b'\n') {
                                self.pos += 1;
                            }
                        }
                        b'\n' => {}
                        other => out.push(other),
                    }
                }
                _ => out.push(b),
            }
        }
        out
    }

    fn hex_string(&mut self) -> Vec<u8> {
        self.pos += 1; // <
        let mut out = Vec::new();
        let mut high: Option<u8> = None;
        while self.pos < self.d.len() {
            let b = self.d[self.pos];
            self.pos += 1;
            if b == b'>' {
                break;
            }
            if let Some(v) = hex(b) {
                match high.take() {
                    Some(h) => out.push(h * 16 + v),
                    None => high = Some(v),
                }
            }
        }
        if let Some(h) = high {
            out.push(h * 16);
        }
        out
    }
}

pub fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}
