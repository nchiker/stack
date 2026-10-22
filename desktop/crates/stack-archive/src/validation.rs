//! Complete row allowlists, exact typed values and graph validation for formats 1/2/3.
use crate::{Error, MANIFEST_LIMIT, Result, strict_json};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};

pub const DOCUMENT_V1: &str = "id,title,mime,added_at,page_count,title_user_edited,initial_page_count,source_scan_id,title_origin,filing_origin,title_decision,filing_decision";
pub const DOCUMENT: &str = "id,title,mime,added_at,page_count,title_user_edited,initial_page_count,source_scan_id,title_origin,filing_origin,title_decision,filing_decision,notes,notes_revision,metadata_revision";
pub const PAGE: &str = "id,document_id,position,source_page,rotation,crop,enhancement,ocr_text,ocr_state,ocr_engine,language,source_file,flatten,content_revision,text_revision,ocr_error,ocr_config,text_config";
pub const SUGGESTION: &str = "document_id,generation,status,input,evidence,rule,title_revision,filing_revision,payload,title_state,filing_state";
pub const DETAIL: &str =
    "document_id,field_id,kind,label,value,qualifier,origin,state,evidence,revision";
pub const TABLES: [&str; 10] = [
    "pages",
    "stacks",
    "memberships",
    "suggestions",
    "choices",
    "details",
    "tags",
    "document_tags",
    "files",
    "documents",
];

#[derive(Clone, Debug)]
pub struct Manifest {
    value: Value,
    source_version: u8,
}

impl Manifest {
    pub fn parse(bytes: &[u8]) -> Result<Self> {
        if bytes.len() > MANIFEST_LIMIT {
            return Err(Error::Invalid("Manifest exceeds 32 MiB"));
        }
        let mut value = strict_json::object(bytes)?;
        validate(&value)?;
        let source_version = number(&value, "version", 1, 3)? as u8;
        if source_version == 1 {
            for doc in value["documents"]
                .as_array_mut()
                .expect("validated documents")
            {
                doc["notes"] = json!("");
                doc["notes_revision"] = json!(0);
                doc["metadata_revision"] = json!(0);
            }
            for name in ["details", "tags", "document_tags"] {
                value[name] = json!([]);
            }
        }
        if source_version < 3 {
            for (position, stack) in value["stacks"]
                .as_array_mut()
                .expect("validated stacks")
                .iter_mut()
                .enumerate()
            {
                stack["sort_order"] = json!(position);
            }
            value["version"] = json!(3);
            value["schema"] = json!(8);
        }
        Ok(Self {
            value,
            source_version,
        })
    }
    pub fn from_value(value: Value) -> Result<Self> {
        Self::parse(&serde_json::to_vec(&value)?)
    }
    pub fn empty() -> Self {
        Self::from_value(
            json!({"format":"org.stackdocs.library","version":3,"schema":8,"created_at":0,
            "documents":[],"pages":[],"stacks":[],"memberships":[],"suggestions":[],"choices":[],
            "decision_evidence":{},"files":[],"details":[],"tags":[],"document_tags":[]}),
        )
        .expect("static empty graph")
    }
    pub fn json(&self) -> &Value {
        &self.value
    }
    pub fn into_json(self) -> Value {
        self.value
    }
    pub fn source_version(&self) -> u8 {
        self.source_version
    }
    /// Names are the fixed archive table constants, not external query input.
    pub fn rows(&self, table: &str) -> &[Value] {
        self.value[table].as_array().expect("validated table name")
    }
    pub fn bytes(&self) -> Result<Vec<u8>> {
        let bytes = serde_json::to_vec(&self.value)?;
        if bytes.len() > MANIFEST_LIMIT {
            return Err(Error::Invalid("Manifest exceeds 32 MiB"));
        }
        Ok(bytes)
    }
    pub fn current_evidence(&self, document: &str) -> String {
        current_evidence(&self.value, document)
    }
}

pub fn utf16_len(value: &str) -> usize {
    value.encode_utf16().count()
}
pub fn java_trim(value: &str) -> &str {
    value.trim_matches(|c: char| u32::from(c) <= 32)
}

pub fn clean_name(value: &str) -> Result<String> {
    let clean: String = value
        .chars()
        .map(|c| if c.is_ascii_control() { ' ' } else { c })
        .collect();
    let clean = java_trim(&clean);
    if clean.is_empty() || utf16_len(clean) > 200 {
        return Err(Error::Invalid("Names must contain 1–200 UTF-16 units"));
    }
    Ok(clean.to_owned())
}

/// Java equalsIgnoreCase uses simple upper/lower mappings, without expanding ß to SS.
pub fn java_case_key(value: &str) -> String {
    value
        .chars()
        .map(|c| {
            let mut upper = c.to_uppercase();
            let first = upper.next().unwrap_or(c);
            let simple = if upper.next().is_none() { first } else { c };
            simple.to_lowercase().next().unwrap_or(simple)
        })
        .collect()
}

pub fn string<'a>(row: &'a Value, key: &str, limit: usize) -> Result<&'a str> {
    row.get(key)
        .and_then(Value::as_str)
        .filter(|v| utf16_len(v) <= limit)
        .ok_or_else(|| Error::Metadata(format!("{key} must be a bounded string")))
}
pub fn number(row: &Value, key: &str, min: i64, max: i64) -> Result<i64> {
    row.get(key)
        .and_then(Value::as_i64)
        .filter(|v| *v >= min && *v <= max)
        .ok_or_else(|| Error::Metadata(format!("{key} must be an in-range integer")))
}
pub fn keys(row: &Value, fields: &str) -> Result<()> {
    let object = row
        .as_object()
        .ok_or(Error::Invalid("Expected an object row"))?;
    if object.len() != fields.split(',').count()
        || fields.split(',').any(|field| !object.contains_key(field))
    {
        return Err(Error::Invalid("Unknown or missing archive fields"));
    }
    Ok(())
}
fn rows<'a>(value: &'a Value, name: &str, limit: usize) -> Result<&'a [Value]> {
    value
        .get(name)
        .and_then(Value::as_array)
        .filter(|rows| rows.len() <= limit)
        .map(Vec::as_slice)
        .ok_or_else(|| {
            Error::Metadata(format!("{name} exceeds its count limit or is not an array"))
        })
}
pub fn sha(value: &str) -> Result<()> {
    if value.len() != 64
        || !value
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(Error::Invalid("Expected lowercase SHA-256"));
    }
    Ok(())
}
fn name(row: &Value, key: &str) -> Result<()> {
    let value = string(row, key, 200)?;
    if clean_name(value)? != value {
        return Err(Error::Invalid("Stored name is not normalized"));
    }
    Ok(())
}
fn one_of(value: &str, choices: &[&str]) -> Result<()> {
    if choices.contains(&value) {
        Ok(())
    } else {
        Err(Error::Invalid("Unsupported enum value"))
    }
}
pub fn decision(value: &str) -> Result<()> {
    one_of(value, &["offered", "accepted", "edited", "dismissed"])
}

pub fn validate_detail(row: &Value) -> Result<()> {
    keys(row, DETAIL)?;
    crate::uuid(string(row, "document_id", 36)?)?;
    let field = string(row, "field_id", 100)?;
    if field.is_empty()
        || !field
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b":_-".contains(&b))
    {
        return Err(Error::Invalid("Invalid stable detail field ID"));
    }
    clean_name(string(row, "label", 200)?)?;
    let value = string(row, "value", 2000)?;
    let qualifier = string(row, "qualifier", 16)?;
    if java_trim(value).is_empty() || value.contains('\0') {
        return Err(Error::Invalid(
            "Detail value must be nonempty and contain no NUL",
        ));
    }
    match string(row, "kind", 20)? {
        "amount" => {
            let unsigned = value.strip_prefix('-').unwrap_or(value);
            let parts: Vec<_> = unsigned.split('.').collect();
            if !(1..=2).contains(&parts.len())
                || !(1..=15).contains(&parts[0].len())
                || !parts[0].bytes().all(|b| b.is_ascii_digit())
                || (parts.len() == 2
                    && (!(1..=6).contains(&parts[1].len())
                        || !parts[1].bytes().all(|b| b.is_ascii_digit())))
            {
                return Err(Error::Invalid("Amount must be an exact decimal string"));
            }
            if qualifier != "unknown"
                && !include_str!("currency_codes.txt")
                    .split_whitespace()
                    .any(|code| code == qualifier)
            {
                return Err(Error::Invalid("Unsupported currency code"));
            }
        }
        "date" => validate_date(value, qualifier)?,
        "organization" => {
            clean_name(value)?;
            if !qualifier.is_empty() {
                return Err(Error::Invalid("Organization qualifier must be empty"));
            }
        }
        "text" => {
            if !qualifier.is_empty() {
                return Err(Error::Invalid("Text qualifier must be empty"));
            }
        }
        _ => return Err(Error::Invalid("Unsupported detail kind")),
    }
    one_of(
        string(row, "origin", 16)?,
        &["manual", "accepted", "edited", "dismissed"],
    )?;
    one_of(string(row, "state", 16)?, &["saved", "removed", "rejected"])?;
    number(row, "revision", 1, i64::MAX - 1)?;
    strict_json::object(string(row, "evidence", 8192)?.as_bytes())?;
    Ok(())
}

fn validate_date(value: &str, precision: &str) -> Result<()> {
    let expected = match precision {
        "year" => 4,
        "month" => 7,
        "day" => 10,
        _ => return Err(Error::Invalid("Unsupported date precision")),
    };
    if value.len() != expected
        || !value.bytes().enumerate().all(|(i, b)| {
            if i == 4 || i == 7 {
                b == b'-'
            } else {
                b.is_ascii_digit()
            }
        })
    {
        return Err(Error::Invalid("Date must retain exact ISO precision"));
    }
    let year: u32 = value[..4]
        .parse()
        .map_err(|_| Error::Invalid("Invalid date year"))?;
    if year == 0 {
        return Err(Error::Invalid("Calendar has no year zero"));
    }
    if expected == 4 {
        return Ok(());
    }
    let month: u32 = value[5..7]
        .parse()
        .map_err(|_| Error::Invalid("Invalid date month"))?;
    if !(1..=12).contains(&month) {
        return Err(Error::Invalid("Invalid date month"));
    }
    if expected == 7 {
        return Ok(());
    }
    let day: u32 = value[8..10]
        .parse()
        .map_err(|_| Error::Invalid("Invalid date day"))?;
    // Android SimpleDateFormat uses the Julian/Gregorian cutover, not proleptic ISO.
    let leap = year.is_multiple_of(4)
        && (year < 1582 || !year.is_multiple_of(100) || year.is_multiple_of(400));
    let days = match month {
        2 => {
            if leap {
                29
            } else {
                28
            }
        }
        4 | 6 | 9 | 11 => 30,
        _ => 31,
    };
    if day == 0 || day > days || (year == 1582 && month == 10 && (5..=14).contains(&day)) {
        return Err(Error::Invalid("Invalid calendar date"));
    }
    Ok(())
}

pub fn crop(value: &str) -> Result<()> {
    if value.is_empty() {
        return Ok(());
    }
    let parsed = strict_json::value(value.as_bytes())?;
    let array = parsed
        .as_array()
        .filter(|a| a.len() == 8)
        .ok_or(Error::Invalid("Crop needs four corners"))?;
    let mut points = [0_f32; 8];
    for (i, n) in array.iter().enumerate() {
        let point = n
            .as_f64()
            .map(|v| v as f32)
            .or_else(|| n.as_str().and_then(|s| s.parse::<f32>().ok()))
            .ok_or(Error::Invalid("Invalid crop coordinate"))?;
        if !point.is_finite() || !(0.0..=1.0).contains(&point) {
            return Err(Error::Invalid("Crop is outside the page"));
        }
        points[i] = point;
    }
    let mut area = 0_f32;
    for i in 0..4 {
        let j = (i + 1) % 4;
        let k = (i + 2) % 4;
        let ax = points[j * 2] - points[i * 2];
        let ay = points[j * 2 + 1] - points[i * 2 + 1];
        let bx = points[k * 2] - points[j * 2];
        let by = points[k * 2 + 1] - points[j * 2 + 1];
        if ax * by - ay * bx < 0.002 || ax * ax + ay * ay < 0.0025 {
            return Err(Error::Invalid("Crop is too small or corners are unordered"));
        }
        area += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1];
    }
    if area < 0.08 {
        return Err(Error::Invalid("Crop is too small"));
    }
    Ok(())
}

fn validate(value: &Value) -> Result<()> {
    let version = number(value, "version", 1, i64::MAX)?;
    let schema = number(value, "schema", 0, i64::MAX)?;
    if string(value, "format", 64)? != "org.stackdocs.library"
        || !matches!((version, schema), (1, 6) | (2, 7) | (3, 8))
    {
        return Err(Error::Invalid(
            "Unsupported archive version/schema; keep it for a compatible release",
        ));
    }
    keys(
        value,
        if version == 1 {
            "format,version,schema,created_at,documents,pages,stacks,memberships,suggestions,choices,decision_evidence,files"
        } else {
            "format,version,schema,created_at,documents,pages,stacks,memberships,suggestions,choices,decision_evidence,files,details,tags,document_tags"
        },
    )?;
    number(value, "created_at", 0, i64::MAX)?;
    let mut documents = BTreeMap::new();
    let mut scans = BTreeSet::new();
    for row in rows(value, "documents", 1000)? {
        keys(row, if version == 1 { DOCUMENT_V1 } else { DOCUMENT })?;
        let id = string(row, "id", 36)?;
        crate::uuid(id)?;
        if documents.insert(id, row).is_some() {
            return Err(Error::Invalid("Duplicate document ID"));
        }
        name(row, "title")?;
        one_of(
            string(row, "mime", 40)?,
            &[
                "application/pdf",
                "application/x-stack-scan",
                "image/jpeg",
                "image/png",
                "image/webp",
            ],
        )?;
        if version >= 2 {
            string(row, "notes", 20_000)?;
            number(row, "notes_revision", 0, i64::MAX - 1)?;
            number(row, "metadata_revision", 0, i64::MAX - 1)?;
        }
        number(row, "added_at", 0, i64::MAX)?;
        let count = number(row, "page_count", 1, 1000)?;
        number(row, "initial_page_count", count, 1000)?;
        number(row, "title_user_edited", 0, 1)?;
        if !row["source_scan_id"].is_null() {
            let scan = string(row, "source_scan_id", 36)?;
            crate::uuid(scan)?;
            if !scans.insert(scan) {
                return Err(Error::Invalid("Duplicate source scan ID"));
            }
        }
        for key in ["title_origin", "filing_origin"] {
            one_of(
                string(row, key, 16)?,
                &[
                    "default", "filename", "legacy", "manual", "accepted", "edited",
                ],
            )?;
        }
        for key in ["title_decision", "filing_decision"] {
            number(row, key, 0, i64::MAX - 1)?;
        }
    }
    let evidence = value["decision_evidence"]
        .as_object()
        .ok_or(Error::Invalid("Missing decision evidence"))?;
    if evidence.len() != documents.len() {
        return Err(Error::Invalid("Decision evidence must cover all documents"));
    }
    for id in documents.keys() {
        sha(string(&value["decision_evidence"], id, 64)?)?;
    }
    let mut stack_ids = BTreeSet::new();
    let mut stack_names = BTreeSet::new();
    for (position, row) in rows(value, "stacks", 1000)?.iter().enumerate() {
        keys(
            row,
            if version == 3 {
                "id,name,color,visible,sort_order"
            } else {
                "id,name,color,visible"
            },
        )?;
        if version == 3 {
            number(row, "sort_order", position as i64, position as i64)?;
        }
        let id = string(row, "id", 80)?;
        if id.is_empty()
            || !id
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
            || !stack_ids.insert(id)
        {
            return Err(Error::Invalid("Invalid or duplicate stack ID"));
        }
        name(row, "name")?;
        if !stack_names.insert(java_case_key(string(row, "name", 200)?)) {
            return Err(Error::Invalid("Duplicate stack name"));
        }
        number(row, "color", i64::from(i32::MIN), i64::from(i32::MAX))?;
        number(row, "visible", 0, 1)?;
    }
    let mut page_ids = BTreeSet::new();
    let mut positions: BTreeMap<&str, BTreeSet<i64>> = BTreeMap::new();
    for row in rows(value, "pages", 10_000)? {
        keys(row, PAGE)?;
        let id = string(row, "id", 36)?;
        crate::uuid(id)?;
        if !page_ids.insert(id) {
            return Err(Error::Invalid("Duplicate page ID"));
        }
        let doc = string(row, "document_id", 36)?;
        let document = documents
            .get(doc)
            .ok_or(Error::Invalid("Page references missing document"))?;
        let pos = number(
            row,
            "position",
            0,
            number(document, "page_count", 1, 1000)? - 1,
        )?;
        if !positions.entry(doc).or_default().insert(pos) {
            return Err(Error::Invalid("Duplicate page position"));
        }
        let source_page = number(
            row,
            "source_page",
            0,
            number(document, "initial_page_count", 1, 1000)? - 1,
        )?;
        let source = string(row, "source_file", 40)?;
        crate::original_path(&format!("originals/{doc}/{source}"))?;
        if document["mime"] != "application/x-stack-scan" && source != "source" {
            return Err(Error::Invalid("Unexpected primary source filename"));
        }
        if document["mime"] != "application/pdf" && source_page != 0 {
            return Err(Error::Invalid("Image source page must be zero"));
        }
        if ![0, 90, 180, 270].contains(&number(row, "rotation", 0, 270)?) {
            return Err(Error::Invalid("Invalid rotation"));
        }
        crop(string(row, "crop", 256)?)?;
        one_of(
            string(row, "enhancement", 32)?,
            &["original", "medium", "bright", "black_white"],
        )?;
        number(row, "flatten", 0, 1)?;
        for (key, limit) in [
            ("ocr_text", 1024 * 1024),
            ("ocr_engine", 256),
            ("language", 64),
            ("ocr_error", 80),
            ("ocr_config", 256),
            ("text_config", 256),
        ] {
            string(row, key, limit)?;
        }
        let revision = number(row, "content_revision", 0, i64::MAX - 1)?;
        number(row, "text_revision", -1, revision)?;
        one_of(
            string(row, "ocr_state", 16)?,
            &["pending", "running", "succeeded", "failed"],
        )?;
    }
    for (id, doc) in &documents {
        if positions.get(id).map_or(0, BTreeSet::len)
            != number(doc, "page_count", 1, 1000)? as usize
        {
            return Err(Error::Invalid(
                "Page positions are not complete and contiguous",
            ));
        }
    }
    let mut members = BTreeSet::new();
    for row in rows(value, "memberships", 100_000)? {
        keys(row, "document_id,stack_id")?;
        let doc = string(row, "document_id", 36)?;
        let stack = string(row, "stack_id", 80)?;
        if !documents.contains_key(doc)
            || !stack_ids.contains(stack)
            || !members.insert((doc, stack))
        {
            return Err(Error::Invalid("Invalid or duplicate membership"));
        }
    }
    let mut suggestions = BTreeSet::new();
    for row in rows(value, "suggestions", 1000)? {
        keys(row, SUGGESTION)?;
        let id = string(row, "document_id", 36)?;
        if !documents.contains_key(id) || !suggestions.insert(id) {
            return Err(Error::Invalid("Invalid or duplicate suggestion row"));
        }
        for key in ["generation", "title_revision", "filing_revision"] {
            number(row, key, 0, i64::MAX - 1)?;
        }
        one_of(
            string(row, "status", 16)?,
            &["pending", "running", "waiting", "ready", "empty", "failed"],
        )?;
        for key in ["input", "evidence"] {
            let s = string(row, key, 64)?;
            if !s.is_empty() {
                sha(s)?;
            }
        }
        string(row, "rule", 128)?;
        strict_json::object(string(row, "payload", 1024 * 1024)?.as_bytes())?;
        decision(string(row, "title_state", 16)?)?;
        decision(string(row, "filing_state", 16)?)?;
    }
    if suggestions.len() != documents.len() {
        return Err(Error::Invalid("Suggestions must cover all documents"));
    }
    let mut choices = BTreeSet::new();
    for row in rows(value, "choices", 20_000)? {
        keys(row, "document_id,evidence,field,choice,value")?;
        let doc = string(row, "document_id", 36)?;
        let evidence = string(row, "evidence", 64)?;
        let field = string(row, "field", 16)?;
        sha(evidence)?;
        one_of(field, &["title", "filing"])?;
        decision(string(row, "choice", 16)?)?;
        string(row, "value", 1024 * 1024)?;
        if !documents.contains_key(doc) || !choices.insert((doc, evidence, field)) {
            return Err(Error::Invalid("Invalid or duplicate decision record"));
        }
    }
    if version >= 2 {
        let mut fields = BTreeSet::new();
        let mut organizations = BTreeMap::new();
        for row in rows(value, "details", 20_000)? {
            validate_detail(row)?;
            let doc = string(row, "document_id", 36)?;
            let field = string(row, "field_id", 100)?;
            if !documents.contains_key(doc) || !fields.insert((doc, field)) {
                return Err(Error::Invalid("Invalid or duplicate detail slot"));
            }
            if row["kind"] == "organization" && row["state"] == "saved" {
                organizations.insert((doc, field), string(row, "value", 2000)?);
            }
        }
        let mut tags = BTreeMap::new();
        let mut names = BTreeSet::new();
        for row in rows(value, "tags", 20_000)? {
            keys(row, "id,name")?;
            let id = string(row, "id", 36)?;
            crate::uuid(id)?;
            name(row, "name")?;
            let name = string(row, "name", 200)?;
            if tags.insert(id, name).is_some() || !names.insert(java_case_key(name)) {
                return Err(Error::Invalid("Duplicate organization tag"));
            }
        }
        let mut links = BTreeSet::new();
        for row in rows(value, "document_tags", 20_000)? {
            keys(row, "document_id,tag_id,field_id")?;
            let key = (
                string(row, "document_id", 36)?,
                string(row, "field_id", 100)?,
            );
            let tag = tags
                .get(string(row, "tag_id", 36)?)
                .ok_or(Error::Invalid("Missing organization tag"))?;
            let detail = organizations
                .get(&key)
                .ok_or(Error::Invalid("Missing saved organization field"))?;
            if !links.insert(key) || java_case_key(tag) != java_case_key(detail) {
                return Err(Error::Invalid("Contradictory organization relation"));
            }
        }
        if links.len() != organizations.len() {
            return Err(Error::Invalid(
                "Saved organizations need matching tag relations",
            ));
        }
    }
    let mut paths = BTreeSet::new();
    let mut owners = BTreeSet::new();
    let mut total = 0_u64;
    for row in rows(value, "files", 20_000)? {
        keys(row, "path,length,sha256")?;
        let path = string(row, "path", 160)?;
        let (doc, _) = crate::original_path(path)?;
        if !documents.contains_key(doc) || !paths.insert(path) {
            return Err(Error::Invalid("Invalid or duplicate original entry"));
        }
        owners.insert(doc);
        total += number(row, "length", 1, crate::FILE_LIMIT as i64)? as u64;
        if total > crate::TOTAL_BYTES {
            return Err(Error::Invalid("Archive exceeds 8 GiB original-byte limit"));
        }
        sha(string(row, "sha256", 64)?)?;
    }
    for doc in documents.keys() {
        if !owners.contains(doc) {
            return Err(Error::Invalid("Document has no retained original"));
        }
    }
    for page in rows(value, "pages", 10_000)? {
        let path = format!(
            "originals/{}/{}",
            string(page, "document_id", 36)?,
            string(page, "source_file", 40)?
        );
        if !paths.contains(path.as_str()) {
            return Err(Error::Invalid("Page references a missing original"));
        }
    }
    Ok(())
}

pub fn current_evidence(value: &Value, document: &str) -> String {
    use sha2::{Digest, Sha256};
    let mut digest = Sha256::new();
    let mut add = |value: &str| {
        digest.update(format!("{}:", utf16_len(value)).as_bytes());
        digest.update(value.as_bytes());
    };
    add(document);
    let mut pages: Vec<_> = value["pages"]
        .as_array()
        .expect("validated pages")
        .iter()
        .filter(|p| p["document_id"] == document)
        .collect();
    pages.sort_by_key(|p| p["position"].as_i64().expect("validated position"));
    for page in pages {
        for key in [
            "id",
            "position",
            "content_revision",
            "text_revision",
            "ocr_config",
            "text_config",
            "ocr_text",
        ] {
            if let Some(text) = page[key].as_str() {
                add(text);
            } else {
                add(&page[key].to_string());
            }
        }
    }
    format!("{:x}", digest.finalize())
}
