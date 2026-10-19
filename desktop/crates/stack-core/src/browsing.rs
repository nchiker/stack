//! Bounded read projections for the native browser. No full-library graph/OCR copy.
use crate::library::{DocumentToken, Error, Library, Result};
use rusqlite::params;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct StackSummary {
    pub id: String,
    pub name: String,
    pub color: i32,
    pub visible: bool,
    pub sort_order: i64,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
pub struct SavedPage {
    pub id: String,
    pub position: u32,
    pub source_file: String,
    pub source_page: u32,
    pub content_revision: i64,
    pub rotation: u16,
    pub crop: String,
    pub enhancement: String,
    pub flatten: bool,
}

impl SavedPage {
    pub fn has_appearance(&self) -> bool {
        self.rotation != 0
            || !self.crop.is_empty()
            || self.enhancement != "original"
            || self.flatten
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct DocumentView {
    pub token: DocumentToken,
    pub title: String,
    pub notes: String,
    pub pages: Vec<SavedPage>,
    pub searchable_pages: u32,
    pub memberships: Vec<String>,
    pub details: Vec<serde_json::Value>,
    pub detail_count: u32,
    pub detail_offset: u32,
}

/// Read-only projection for visible library tiles; no details/OCR or original bytes.
#[derive(Clone, Debug)]
pub struct DocumentTile {
    pub token: DocumentToken,
    pub added_at: i64,
    pub memberships: Vec<String>,
    pub page: Option<SavedPage>,
    pub source: Option<PathBuf>,
}

impl Library {
    pub fn document_tile(&self, id: &str) -> Result<DocumentTile> {
        use rusqlite::OptionalExtension;
        let token = self.token(id)?;
        let added_at = self.db.query_row(
            "SELECT json_extract(payload,'$.added_at') FROM documents WHERE id=?1 AND active=1",
            [id],
            |r| r.get(0),
        )?;
        let mut query = self.db.prepare("SELECT json_extract(payload,'$.stack_id') FROM objects WHERE kind='memberships' AND document_id=?1 AND active=1 ORDER BY key LIMIT 1000")?;
        let memberships = query
            .query_map([id], |r| r.get(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        let payload: Option<String> = self.db.query_row("SELECT payload FROM objects WHERE kind='pages' AND document_id=?1 AND active=1 ORDER BY CAST(json_extract(payload,'$.position') AS INTEGER) LIMIT 1", [id], |r| r.get(0)).optional()?;
        let page = payload
            .map(|p| -> Result<SavedPage> {
                let mut value: serde_json::Value = serde_json::from_str(&p)?;
                value["flatten"] = serde_json::json!(value["flatten"] == 1);
                Ok(serde_json::from_value(value)?)
            })
            .transpose()?;
        let source = page
            .as_ref()
            .and_then(|p| self.page_original(&token, p).ok());
        Ok(DocumentTile {
            token,
            added_at,
            memberships,
            page,
            source,
        })
    }
    /// The portable contract limits the complete catalog to 1,000 stacks.
    pub fn stacks(&self) -> Result<Vec<StackSummary>> {
        let mut query = self.db.prepare("SELECT payload FROM objects WHERE kind='stacks' AND active=1 ORDER BY CAST(json_extract(payload,'$.sort_order') AS INTEGER),key LIMIT 1000")?;
        query
            .query_map([], |r| r.get::<_, String>(0))?
            .map(|row| {
                let row: serde_json::Value = serde_json::from_str(&row?)?;
                Ok(StackSummary {
                    id: row["id"]
                        .as_str()
                        .ok_or(Error::Invalid("Invalid stack ID"))?
                        .into(),
                    name: row["name"]
                        .as_str()
                        .ok_or(Error::Invalid("Invalid stack name"))?
                        .into(),
                    color: row["color"]
                        .as_i64()
                        .ok_or(Error::Invalid("Invalid stack color"))?
                        as i32,
                    visible: row["visible"] == 1,
                    sort_order: row["sort_order"]
                        .as_i64()
                        .ok_or(Error::Invalid("Invalid stack order"))?,
                })
            })
            .collect()
    }

    pub fn document_view(&self, id: &str) -> Result<DocumentView> {
        self.document_view_page(id, 0)
    }

    pub fn document_view_page(&self, id: &str, detail_offset: u32) -> Result<DocumentView> {
        if detail_offset > 20_000 {
            return Err(Error::Invalid("Detail offset exceeds bounds"));
        }
        let token = self.token(id)?;
        let (title, notes) = self.db.query_row("SELECT json_extract(payload,'$.title'),json_extract(payload,'$.notes') FROM documents WHERE id=?1 AND active=1", [id], |r| Ok((r.get(0)?,r.get(1)?)))?;
        let mut query = self.db.prepare("SELECT json_extract(payload,'$.id'),json_extract(payload,'$.position'),json_extract(payload,'$.source_file'),json_extract(payload,'$.source_page'),json_extract(payload,'$.content_revision'),json_extract(payload,'$.rotation'),json_extract(payload,'$.crop'),json_extract(payload,'$.enhancement'),json_extract(payload,'$.flatten') FROM objects WHERE kind='pages' AND document_id=?1 AND active=1 ORDER BY CAST(json_extract(payload,'$.position') AS INTEGER) LIMIT 1000")?;
        let pages = query
            .query_map([id], |r| {
                Ok(SavedPage {
                    id: r.get(0)?,
                    position: r.get(1)?,
                    source_file: r.get(2)?,
                    source_page: r.get(3)?,
                    content_revision: r.get(4)?,
                    rotation: r.get(5)?,
                    crop: r.get(6)?,
                    enhancement: r.get(7)?,
                    flatten: r.get::<_, i64>(8)? != 0,
                })
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        let mut query=self.db.prepare("SELECT json_extract(payload,'$.stack_id') FROM objects WHERE kind='memberships' AND document_id=?1 AND active=1 ORDER BY key LIMIT 1000")?;
        let memberships = query
            .query_map([id], |r| r.get(0))?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        let detail_count = self.db.query_row(
            "SELECT count(*) FROM objects WHERE kind='details' AND document_id=?1 AND active=1",
            [id],
            |r| r.get(0),
        )?;
        let mut query=self.db.prepare("SELECT payload FROM objects WHERE kind='details' AND document_id=?1 AND active=1 ORDER BY key LIMIT 200 OFFSET ?2")?;
        let details = query
            .query_map(params![id, detail_offset], |r| r.get::<_, String>(0))?
            .map(|r| Ok(serde_json::from_str(&r?)?))
            .collect::<Result<Vec<_>>>()?;
        // Use the same revision/configuration predicate as rebuild_index. Count
        // nonempty current text without copying OCR bodies into the UI projection.
        let searchable_pages = self.db.query_row(
            "SELECT count(*) FROM objects WHERE kind='pages' AND document_id=?1 AND active=1 AND json_extract(payload,'$.text_revision')=json_extract(payload,'$.content_revision') AND json_extract(payload,'$.text_config')=json_extract(payload,'$.ocr_config') AND length(trim(json_extract(payload,'$.ocr_text')))>0",
            [id], |r| r.get(0),
        )?;
        Ok(DocumentView {
            token,
            title,
            notes,
            pages,
            searchable_pages,
            memberships,
            details,
            detail_count,
            detail_offset,
        })
    }

    /// Resolve a currently saved page without materializing every document's graph.
    pub fn page_original(&self, token: &DocumentToken, page: &SavedPage) -> Result<PathBuf> {
        self.checked_token(token)?;
        let current = self.document_view(&token.id)?;
        if !current.pages.contains(page) {
            return Err(Error::Stale);
        }
        let path = format!("originals/{}/{}", token.id, page.source_file);
        stack_archive::original_path(&path)?;
        let exists: bool = self.db.query_row("SELECT EXISTS(SELECT 1 FROM objects WHERE kind='files' AND document_id=?1 AND active=1 AND json_extract(payload,'$.path')=?2)",params![token.id,path],|r|r.get(0))?;
        if !exists {
            return Err(Error::Invalid("Page original is unavailable"));
        }
        stack_archive::read_directory(&self.root.join("originals").join(&token.id))?;
        let path = self.root.join(path);
        stack_archive::read_regular(&path)?;
        Ok(path)
    }
}
