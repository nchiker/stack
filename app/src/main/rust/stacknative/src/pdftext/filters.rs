//! Stream filters, all with an output cap and a deadline. Damaged data gives the part decoded so far, flagged incomplete.

use std::time::Instant;

pub struct Decoded {
    pub data: Vec<u8>,
    pub complete: bool,
}

// ---------- Flate (RFC 1950/1951) ----------

struct Bits<'a> {
    d: &'a [u8],
    pos: usize,
    buf: u32,
    n: u32,
}

impl<'a> Bits<'a> {
    fn need(&mut self, count: u32) -> Option<()> {
        while self.n < count {
            let b = *self.d.get(self.pos)?;
            self.pos += 1;
            self.buf |= (b as u32) << self.n;
            self.n += 8;
        }
        Some(())
    }
    fn get(&mut self, count: u32) -> Option<u32> {
        if count == 0 {
            return Some(0);
        }
        self.need(count)?;
        let v = self.buf & ((1u32 << count) - 1);
        self.buf >>= count;
        self.n -= count;
        Some(v)
    }
}

struct Huffman {
    count: [u16; 16],
    symbol: Vec<u16>,
}

impl Huffman {
    fn new(lengths: &[u8]) -> Option<Huffman> {
        let mut count = [0u16; 16];
        for &l in lengths {
            count[l as usize] += 1;
        }
        // Over-subscribed sets are invalid (incomplete ones are allowed, as in zlib for single codes).
        let mut left: i32 = 1;
        for len in 1..16 {
            left <<= 1;
            left -= count[len] as i32;
            if left < 0 {
                return None;
            }
        }
        let mut offs = [0u16; 16];
        for len in 1..15 {
            offs[len + 1] = offs[len] + count[len];
        }
        let mut symbol = vec![0u16; lengths.len()];
        for (sym, &l) in lengths.iter().enumerate() {
            if l != 0 {
                symbol[offs[l as usize] as usize] = sym as u16;
                offs[l as usize] += 1;
            }
        }
        Some(Huffman { count, symbol })
    }

    fn decode(&self, bits: &mut Bits) -> Option<u16> {
        let mut code: i32 = 0;
        let mut first: i32 = 0;
        let mut index: i32 = 0;
        for len in 1..16 {
            code |= bits.get(1)? as i32;
            let count = self.count[len] as i32;
            if code - count < first {
                return self.symbol.get((index + (code - first)) as usize).copied();
            }
            index += count;
            first += count;
            first <<= 1;
            code <<= 1;
        }
        None
    }
}

const LEN_BASE: [u16; 29] = [3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258];
const LEN_EXTRA: [u8; 29] = [0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0];
const DIST_BASE: [u16; 30] = [1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577];
const DIST_EXTRA: [u8; 30] = [0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13];

/// Raw DEFLATE. Stops at `limit` output bytes (complete = false), at the deadline, or at damaged data.
fn inflate_raw(d: &[u8], limit: usize, deadline: Instant) -> Decoded {
    let mut bits = Bits { d, pos: 0, buf: 0, n: 0 };
    let mut out: Vec<u8> = Vec::new();
    let result = (|| -> Option<()> {
        loop {
            let last = bits.get(1)?;
            let kind = bits.get(2)?;
            match kind {
                0 => {
                    bits.buf = 0;
                    bits.n = 0;
                    let p = bits.pos;
                    let len = u16::from_le_bytes([*d.get(p)?, *d.get(p + 1)?]) as usize;
                    let nlen = u16::from_le_bytes([*d.get(p + 2)?, *d.get(p + 3)?]) as usize;
                    if len != (!nlen & 0xffff) {
                        return None;
                    }
                    let end = p + 4 + len;
                    let room = limit.saturating_sub(out.len());
                    let take = len.min(room);
                    let avail = d.len().saturating_sub(p + 4).min(take);
                    out.extend_from_slice(&d[p + 4..p + 4 + avail]);
                    if take < len || end > d.len() {
                        return None;
                    }
                    bits.pos = end;
                }
                1 | 2 => {
                    let (lit, dist);
                    if kind == 1 {
                        let mut l = [0u8; 288];
                        for (i, v) in l.iter_mut().enumerate() {
                            *v = if i < 144 { 8 } else if i < 256 { 9 } else if i < 280 { 7 } else { 8 };
                        }
                        lit = Huffman::new(&l)?;
                        dist = Huffman::new(&[5u8; 30])?;
                    } else {
                        let nlen = bits.get(5)? as usize + 257;
                        let ndist = bits.get(5)? as usize + 1;
                        let ncode = bits.get(4)? as usize + 4;
                        if nlen > 286 || ndist > 30 {
                            return None;
                        }
                        const ORDER: [usize; 19] = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15];
                        let mut cl = [0u8; 19];
                        for &o in ORDER.iter().take(ncode) {
                            cl[o] = bits.get(3)? as u8;
                        }
                        let clh = Huffman::new(&cl)?;
                        let mut lengths = vec![0u8; nlen + ndist];
                        let mut i = 0;
                        while i < nlen + ndist {
                            let sym = clh.decode(&mut bits)?;
                            if sym < 16 {
                                lengths[i] = sym as u8;
                                i += 1;
                            } else {
                                let (value, rep) = match sym {
                                    16 => {
                                        if i == 0 {
                                            return None;
                                        }
                                        (lengths[i - 1], 3 + bits.get(2)? as usize)
                                    }
                                    17 => (0, 3 + bits.get(3)? as usize),
                                    _ => (0, 11 + bits.get(7)? as usize),
                                };
                                if i + rep > nlen + ndist {
                                    return None;
                                }
                                for _ in 0..rep {
                                    lengths[i] = value;
                                    i += 1;
                                }
                            }
                        }
                        lit = Huffman::new(&lengths[..nlen])?;
                        dist = Huffman::new(&lengths[nlen..])?;
                    }
                    let mut tick = 0u32;
                    loop {
                        let sym = lit.decode(&mut bits)? as usize;
                        if sym < 256 {
                            if out.len() >= limit {
                                return None;
                            }
                            out.push(sym as u8);
                        } else if sym == 256 {
                            break;
                        } else {
                            let s = sym - 257;
                            if s >= 29 {
                                return None;
                            }
                            let len = LEN_BASE[s] as usize + bits.get(LEN_EXTRA[s] as u32)? as usize;
                            let ds = dist.decode(&mut bits)? as usize;
                            if ds >= 30 {
                                return None;
                            }
                            let dd = DIST_BASE[ds] as usize + bits.get(DIST_EXTRA[ds] as u32)? as usize;
                            if dd > out.len() || out.len() + len > limit {
                                return None;
                            }
                            let start = out.len() - dd;
                            for k in 0..len {
                                let b = out[start + k];
                                out.push(b);
                            }
                        }
                        tick += 1;
                        if tick & 0xffff == 0 && Instant::now() > deadline {
                            return None;
                        }
                    }
                }
                _ => return None,
            }
            if last == 1 {
                return Some(());
            }
            if Instant::now() > deadline {
                return None;
            }
        }
    })();
    Decoded { data: out, complete: result.is_some() }
}

pub fn flate(d: &[u8], limit: usize, deadline: Instant) -> Decoded {
    let zlib_header = d.len() >= 2 && d[0] & 0x0f == 8 && (((d[0] as u32) << 8) | d[1] as u32) % 31 == 0;
    if zlib_header {
        let r = inflate_raw(&d[2..], limit, deadline);
        if r.complete || !r.data.is_empty() {
            return r;
        }
    }
    inflate_raw(d, limit, deadline)
}

// ---------- LZW ----------

pub fn lzw(d: &[u8], limit: usize, early_change: bool) -> Decoded {
    let mut out: Vec<u8> = Vec::new();
    let mut table: Vec<(u32, u8)> = Vec::new(); // (prefix index or u32::MAX, byte); entries start at 258
    let mut width = 9u32;
    let mut buf: u32 = 0;
    let mut nbits = 0u32;
    let mut prev: Option<u32> = None;
    let early = if early_change { 1 } else { 0 };
    let mut complete = false;
    let mut scratch: Vec<u8> = Vec::new();
    // Expands code into scratch (reversed), returns the first byte.
    fn expand(table: &[(u32, u8)], mut code: u32, scratch: &mut Vec<u8>) -> Option<u8> {
        scratch.clear();
        let mut guard = 0;
        while code >= 258 {
            let (p, b) = *table.get((code - 258) as usize)?;
            scratch.push(b);
            code = p;
            guard += 1;
            if guard > 5000 {
                return None;
            }
        }
        scratch.push(code as u8);
        scratch.last().copied()
    }
    'outer: for &byte in d {
        buf = (buf << 8) | byte as u32;
        nbits += 8;
        while nbits >= width {
            let code = (buf >> (nbits - width)) & ((1 << width) - 1);
            nbits -= width;
            if code == 256 {
                table.clear();
                width = 9;
                prev = None;
                continue;
            }
            if code == 257 {
                complete = true;
                break 'outer;
            }
            let first;
            if code < 256 || (code - 258) < table.len() as u32 {
                let Some(f) = expand(&table, code, &mut scratch) else { break 'outer };
                first = f;
            } else if code == 258 + table.len() as u32 && prev.is_some() {
                let Some(f) = expand(&table, prev.unwrap(), &mut scratch) else { break 'outer };
                first = f;
                scratch.insert(0, f);
            } else {
                break 'outer;
            }
            if out.len() + scratch.len() > limit {
                break 'outer;
            }
            out.extend(scratch.iter().rev());
            if let Some(p) = prev {
                if table.len() < 4096 - 258 {
                    table.push((p, first));
                }
            }
            prev = Some(code);
            let size = 258 + table.len() as u32 + early;
            width = if size >= 2048 { 12 } else if size >= 1024 { 11 } else if size >= 512 { 10 } else { 9 };
        }
    }
    Decoded { data: out, complete }
}

// ---------- ASCII filters, RunLength ----------

pub fn ascii_hex(d: &[u8], limit: usize) -> Decoded {
    let mut out = Vec::new();
    let mut high: Option<u8> = None;
    for &b in d {
        if b == b'>' {
            break;
        }
        if let Some(v) = super::lex::hex(b) {
            match high.take() {
                Some(h) => out.push(h * 16 + v),
                None => high = Some(v),
            }
            if out.len() >= limit {
                return Decoded { data: out, complete: false };
            }
        }
    }
    if let Some(h) = high {
        out.push(h * 16);
    }
    Decoded { data: out, complete: true }
}

pub fn ascii85(d: &[u8], limit: usize) -> Decoded {
    let mut out = Vec::new();
    let mut group = [0u32; 5];
    let mut n = 0;
    let mut i = 0;
    if d.starts_with(b"<~") {
        i = 2;
    }
    let mut complete = false;
    while i < d.len() {
        let b = d[i];
        i += 1;
        match b {
            b'~' => {
                complete = true;
                break;
            }
            b'z' if n == 0 => out.extend_from_slice(&[0, 0, 0, 0]),
            b'!'..=b'u' => {
                group[n] = (b - b'!') as u32;
                n += 1;
                if n == 5 {
                    let v = group.iter().fold(0u64, |a, &g| a * 85 + g as u64) as u32;
                    out.extend_from_slice(&v.to_be_bytes());
                    n = 0;
                }
            }
            _ => {}
        }
        if out.len() >= limit {
            return Decoded { data: out, complete: false };
        }
    }
    if n > 1 {
        for g in group.iter_mut().skip(n) {
            *g = 84;
        }
        let v = group.iter().fold(0u64, |a, &g| a * 85 + g as u64) as u32;
        out.extend_from_slice(&v.to_be_bytes()[..n - 1]);
    }
    Decoded { data: out, complete }
}

pub fn run_length(d: &[u8], limit: usize) -> Decoded {
    let mut out = Vec::new();
    let mut i = 0;
    while i < d.len() {
        let l = d[i] as usize;
        i += 1;
        if l == 128 {
            return Decoded { data: out, complete: true };
        }
        if l < 128 {
            let end = (i + l + 1).min(d.len());
            out.extend_from_slice(&d[i..end]);
            i = end;
        } else {
            let Some(&b) = d.get(i) else { break };
            i += 1;
            out.resize(out.len() + 257 - l, b);
        }
        if out.len() > limit {
            out.truncate(limit);
            return Decoded { data: out, complete: false };
        }
    }
    Decoded { data: out, complete: false }
}
