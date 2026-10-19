//! Strict, duplicate-aware JSON gate, matching the Android archive's depth/token limits.
use crate::{Error, Result};
use serde_json::{Map, Value};

pub fn object(bytes: &[u8]) -> Result<Value> {
    let text =
        std::str::from_utf8(bytes).map_err(|_| Error::Invalid("JSON must be valid UTF-8"))?;
    let mut parser = Parser { bytes, at: 0, text };
    let value = parser.value(0)?;
    parser.space();
    if parser.at != bytes.len() || !value.is_object() {
        return Err(Error::Invalid("Expected one complete JSON object"));
    }
    Ok(value)
}

pub fn value(bytes: &[u8]) -> Result<Value> {
    let text =
        std::str::from_utf8(bytes).map_err(|_| Error::Invalid("JSON must be valid UTF-8"))?;
    let mut parser = Parser { bytes, at: 0, text };
    let value = parser.value(0)?;
    parser.space();
    if parser.at != bytes.len() {
        return Err(Error::Invalid("Unexpected trailing JSON"));
    }
    Ok(value)
}

struct Parser<'a> {
    bytes: &'a [u8],
    at: usize,
    text: &'a str,
}

impl Parser<'_> {
    fn space(&mut self) {
        while self
            .bytes
            .get(self.at)
            .is_some_and(|b| matches!(b, b' ' | b'\r' | b'\n' | b'\t'))
        {
            self.at += 1;
        }
    }
    fn take(&mut self, byte: u8) -> Result<()> {
        self.space();
        if self.bytes.get(self.at) != Some(&byte) {
            return Err(Error::Invalid("Malformed JSON syntax"));
        }
        self.at += 1;
        Ok(())
    }
    fn string(&mut self) -> Result<String> {
        if self.bytes.get(self.at) != Some(&b'"') {
            return Err(Error::Invalid("Expected a JSON string"));
        }
        let start = self.at;
        self.at += 1;
        loop {
            let b = *self
                .bytes
                .get(self.at)
                .ok_or(Error::Invalid("Unterminated JSON string"))?;
            self.at += 1;
            if b == b'"' {
                break;
            }
            if b < 32 {
                return Err(Error::Invalid("Unescaped JSON control character"));
            }
            if b == b'\\' {
                let escape = *self
                    .bytes
                    .get(self.at)
                    .ok_or(Error::Invalid("Truncated JSON escape"))?;
                self.at += 1;
                if escape == b'u' {
                    for _ in 0..4 {
                        if !self.bytes.get(self.at).is_some_and(u8::is_ascii_hexdigit) {
                            return Err(Error::Invalid("Invalid Unicode escape"));
                        }
                        self.at += 1;
                    }
                } else if !matches!(
                    escape,
                    b'"' | b'\\' | b'/' | b'b' | b'f' | b'n' | b'r' | b't'
                ) {
                    return Err(Error::Invalid("Invalid JSON escape"));
                }
            }
            if self.at - start > 8 * 1024 * 1024 {
                return Err(Error::Invalid("JSON string token too long"));
            }
        }
        // Android's parser bounds encoded tokens in UTF-16 code units, not UTF-8 bytes.
        if self.text[start..self.at - 1].encode_utf16().count() > 2 * 1024 * 1024 {
            return Err(Error::Invalid("JSON string token too long"));
        }
        serde_json::from_slice(&self.bytes[start..self.at])
            .map_err(|_| Error::Invalid("Invalid Unicode string"))
    }
    fn value(&mut self, depth: usize) -> Result<Value> {
        if depth > 16 {
            return Err(Error::Invalid("JSON nesting exceeds 16"));
        }
        self.space();
        match self.bytes.get(self.at) {
            Some(b'{') => {
                self.at += 1;
                let mut object = Map::new();
                self.space();
                if self.bytes.get(self.at) == Some(&b'}') {
                    self.at += 1;
                    return Ok(Value::Object(object));
                }
                loop {
                    self.space();
                    let key = self.string()?;
                    self.take(b':')?;
                    let value = self.value(depth + 1)?;
                    if object.insert(key, value).is_some() {
                        return Err(Error::Invalid("Duplicate JSON key"));
                    }
                    self.space();
                    match self.bytes.get(self.at) {
                        Some(b'}') => {
                            self.at += 1;
                            break;
                        }
                        Some(b',') => self.at += 1,
                        _ => return Err(Error::Invalid("Malformed JSON object")),
                    }
                }
                Ok(Value::Object(object))
            }
            Some(b'[') => {
                self.at += 1;
                let mut array = Vec::new();
                self.space();
                if self.bytes.get(self.at) == Some(&b']') {
                    self.at += 1;
                    return Ok(Value::Array(array));
                }
                loop {
                    array.push(self.value(depth + 1)?);
                    self.space();
                    match self.bytes.get(self.at) {
                        Some(b']') => {
                            self.at += 1;
                            break;
                        }
                        Some(b',') => self.at += 1,
                        _ => return Err(Error::Invalid("Malformed JSON array")),
                    }
                }
                Ok(Value::Array(array))
            }
            Some(b'"') => self.string().map(Value::String),
            Some(b't') if self.bytes[self.at..].starts_with(b"true") => {
                self.at += 4;
                Ok(Value::Bool(true))
            }
            Some(b'f') if self.bytes[self.at..].starts_with(b"false") => {
                self.at += 5;
                Ok(Value::Bool(false))
            }
            Some(b'n') if self.bytes[self.at..].starts_with(b"null") => {
                self.at += 4;
                Ok(Value::Null)
            }
            Some(b'-' | b'0'..=b'9') => {
                let start = self.at;
                while self
                    .bytes
                    .get(self.at)
                    .is_some_and(|b| matches!(b, b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9'))
                {
                    self.at += 1;
                }
                if self.at - start > 24 {
                    return Err(Error::Invalid("JSON number token too long"));
                }
                let number: Value = serde_json::from_slice(&self.bytes[start..self.at])
                    .map_err(|_| Error::Invalid("Invalid JSON number"))?;
                if !number.is_number() {
                    return Err(Error::Invalid("Invalid JSON number"));
                }
                let token = &self.text[start..self.at];
                if !token.contains(['.', 'e', 'E'])
                    && let Ok(integer) = token.parse::<i64>()
                {
                    return Ok(Value::from(integer));
                }
                Ok(number)
            }
            _ => Err(Error::Invalid("Malformed JSON value")),
        }
    }
}
