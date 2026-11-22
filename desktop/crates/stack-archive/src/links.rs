//! Format 4 link rows (`links` array) and `previews/<id>.pdf` paths. Mirrors the phone's
//! `LinkArchive.validate` and `LinkRecord.parse` (contract 1): strict fields, exact types, bounded text.
use crate::validation::{keys, number, string, utf16_len};
use crate::{Error, Result, strict_json};
use serde_json::Value;
use std::collections::BTreeSet;

pub const LINK_LIMIT: usize = 1000;
pub const FIELDS: &str = "id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text";
pub const MAX_TITLE: usize = 200;
pub const MAX_TEXT: usize = 1 << 20;
pub const MAX_PREVIEW: u64 = 32 << 20;
const MAX_LOCATORS: usize = 8;
const MAX_MIME: usize = 100;
const MAX_PATH: usize = 1024;
const MAX_URI: usize = 2048;
const MAX_TOKEN: usize = 512;
const MAX_LOCATORS_JSON: usize = 16 * 1024;

fn bad(message: &'static str) -> Error {
    Error::Metadata(message.to_owned())
}

pub fn preview_path(id: &str) -> String {
    format!("previews/{id}.pdf")
}

/// `previews/<canonical UUID>.pdf` (exactly 49 bytes); returns the id.
pub fn preview_id(path: &str) -> Result<&str> {
    let id = path
        .strip_prefix("previews/")
        .and_then(|rest| rest.strip_suffix(".pdf"))
        .filter(|id| path.len() == 9 + 36 + 4 && id.len() == 36)
        .ok_or(Error::Invalid("Unsupported preview path"))?;
    crate::uuid(id)?;
    Ok(id)
}

/// Strict check of every row; returns the ids so previews can be matched to links.
pub fn validate(rows: &[Value]) -> Result<BTreeSet<&str>> {
    let mut ids = BTreeSet::new();
    let mut owners = BTreeSet::new();
    for row in rows {
        keys(row, FIELDS)?;
        let id = string(row, "id", 36)?;
        crate::uuid(id)?;
        if !ids.insert(id) {
            return Err(bad("Duplicate link id"));
        }
        for field in ["owner", "link_id", "kind", "title", "mime", "hash"] {
            string(row, field, 4096)?;
        }
        let owner = string(row, "owner", 200)?;
        let link_id = string(row, "link_id", 36)?;
        free(owner, 200)?;
        if !package(owner) {
            return Err(bad("owner has the wrong form"));
        }
        free(link_id, 36)?;
        if !uuid_form(link_id) {
            return Err(bad("link_id has the wrong form"));
        }
        let kind = string(row, "kind", 16)?;
        if kind.is_empty() || !kind.bytes().all(|b| b.is_ascii_lowercase()) {
            return Err(bad("kind has the wrong form"));
        }
        free(string(row, "title", MAX_TITLE)?, MAX_TITLE)?;
        let mime = string(row, "mime", MAX_MIME)?;
        free(mime, MAX_MIME)?;
        if !mime_form(mime) {
            return Err(bad("mime has the wrong form"));
        }
        let hash = string(row, "hash", 64)?;
        if hash.len() != 64
            || !hash
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        {
            return Err(bad("hash has the wrong form"));
        }
        number(row, "modified", 0, i64::MAX)?;
        flag(row, "shared")?;
        flag(row, "protected")?;
        locators(string(row, "locators", MAX_LOCATORS_JSON)?)?;
        if !owners.insert((owner, link_id)) {
            return Err(bad("Duplicate link (owner, link_id)"));
        }
        let state = string(row, "state", 32)?;
        if !matches!(state, "live" | "unreachable" | "missing" | "trashed") {
            return Err(bad("Unsupported link state"));
        }
        flag(row, "title_user_edited")?;
        number(row, "added_at", 0, i64::MAX)?;
        let text = string(row, "text", MAX_TEXT)?;
        if text.contains('\0') {
            return Err(bad("text contains NUL"));
        }
    }
    Ok(ids)
}

fn flag(row: &Value, key: &str) -> Result<()> {
    number(row, key, 0, 1).map(|_| ())
}

/// Non-empty, at most `max` UTF-16 units, no control or direction-changing characters.
fn free(value: &str, max: usize) -> Result<()> {
    if value.is_empty() || utf16_len(value) > max {
        return Err(bad("A link field is empty or too long"));
    }
    for c in value.chars() {
        let c = u32::from(c);
        if c < 0x20
            || (0x7f..=0x9f).contains(&c)
            || (0x202a..=0x202e).contains(&c)
            || (0x2066..=0x2069).contains(&c)
            || matches!(c, 0x200e | 0x200f | 0x061c)
        {
            return Err(bad(
                "A link field contains a control or direction-changing character",
            ));
        }
    }
    Ok(())
}

fn uuid_form(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(i, b)| match i {
            8 | 13 | 18 | 23 => b == b'-',
            _ => b.is_ascii_digit() || (b'a'..=b'f').contains(&b),
        })
}

/// `[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+`
fn package(value: &str) -> bool {
    let parts: Vec<_> = value.split('.').collect();
    parts.len() >= 2
        && parts.iter().all(|part| {
            let b = part.as_bytes();
            !b.is_empty()
                && b[0].is_ascii_lowercase()
                && b.iter()
                    .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'_')
        })
}

/// `[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*`
fn mime_form(value: &str) -> bool {
    let mut parts = value.split('/');
    let (Some(a), Some(b), None) = (parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    [a, b].iter().all(|part| {
        let bytes = part.as_bytes();
        !bytes.is_empty()
            && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
            && bytes.iter().all(|c| {
                c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'.' | b'+' | b'-')
            })
    })
}

fn locators(json: &str) -> Result<()> {
    let value = strict_json::value(json.as_bytes())?;
    let list = value
        .as_array()
        .ok_or_else(|| bad("locators must be a list"))?;
    if list.is_empty() || list.len() > MAX_LOCATORS {
        return Err(bad("locators must hold 1 to 8 entries"));
    }
    for entry in list {
        let object = entry
            .as_object()
            .ok_or_else(|| bad("each locator must be an object"))?;
        match object.get("type").and_then(Value::as_str) {
            Some("filen") => {
                keys(entry, "type,uuid,path")?;
                let uuid = string(entry, "uuid", 36)?;
                free(uuid, 36)?;
                if !uuid_form(uuid) {
                    return Err(bad("locator uuid has the wrong form"));
                }
                free(string(entry, "path", MAX_PATH)?, MAX_PATH)?;
            }
            Some("saf") => {
                keys(entry, "type,uri")?;
                free(string(entry, "uri", MAX_URI)?, MAX_URI)?;
            }
            Some("owner") => {
                keys(entry, "type,token")?;
                free(string(entry, "token", MAX_TOKEN)?, MAX_TOKEN)?;
            }
            _ => return Err(bad("unknown locator type")),
        }
    }
    Ok(())
}
