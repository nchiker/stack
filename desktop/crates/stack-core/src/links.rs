//! Linked documents restored from archive format 4: read-only records with saved text and an optional preview PDF.
//! They live in their own tables (`link_records`, `link_search`) and `links/<row_id>.pdf`, outside the document graph,
//! so nothing that assumes a document has pages can meet one. The columns keep everything the archive carries
//! (locators, hash, flags) so a later desktop editor can use them; only the UI is read-only for now.
use crate::library::{Error, Library, Result};
use rusqlite::{OptionalExtension, params};
use serde::Serialize;
use std::path::PathBuf;
use zeroize::Zeroizing;

/// The secret that opens an encrypted backup. Held in memory only, zeroed on drop, never printed.
pub struct BackupKey {
    pub(crate) recovery: bool,
    pub(crate) text: Zeroizing<String>,
}
impl BackupKey {
    /// The 64-character recovery key (spaces and dashes allowed). Takes ownership so no extra copy is made.
    pub fn recovery(text: String) -> Self {
        Self {
            recovery: true,
            text: Zeroizing::new(text),
        }
    }
    /// A backup passphrase (used exactly, no trimming).
    pub fn passphrase(text: String) -> Self {
        Self {
            recovery: false,
            text: Zeroizing::new(text),
        }
    }
}
impl std::fmt::Debug for BackupKey {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("BackupKey(<redacted>)")
    }
}

#[derive(Clone, Debug, Serialize, PartialEq, Eq)]
pub struct LinkedDocument {
    pub id: String,
    pub owner: String,
    pub link_id: String,
    pub kind: String,
    pub title: String,
    pub mime: String,
    pub modified: i64,
    pub added_at: i64,
    pub state: String,
    pub hash: String,
    pub text: String,
    /// Saved preview PDF, when the archive carried one.
    pub preview: Option<PathBuf>,
}

pub(crate) fn preview_file(row_id: &str) -> String {
    format!("links/{row_id}.pdf")
}

impl Library {
    pub fn link_count(&self) -> Result<usize> {
        Ok(self.db.query_row(
            "SELECT count(*) FROM link_records WHERE state<>'trashed'",
            [],
            |r| r.get::<_, i64>(0),
        )? as usize)
    }

    pub fn linked_document(&self, id: &str) -> Result<LinkedDocument> {
        stack_archive::uuid(id)?;
        let row = self
            .db
            .query_row(
                "SELECT owner,link_id,kind,title,mime,modified,added_at,state,hash,text,preview AND NOT preview_pending FROM link_records WHERE row_id=?1",
                [id],
                |r| {
                    Ok((
                        LinkedDocument {
                            id: id.to_owned(),
                            owner: r.get(0)?,
                            link_id: r.get(1)?,
                            kind: r.get(2)?,
                            title: r.get(3)?,
                            mime: r.get(4)?,
                            modified: r.get(5)?,
                            added_at: r.get(6)?,
                            state: r.get(7)?,
                            hash: r.get(8)?,
                            text: r.get(9)?,
                            preview: None,
                        },
                        r.get::<_, i64>(10)? != 0,
                    ))
                },
            )
            .optional()?
            .ok_or(Error::Stale)?;
        let (mut link, has_preview) = row;
        if has_preview {
            stack_archive::read_directory(&self.root.join("links"))?;
            let path = self.root.join(preview_file(id));
            stack_archive::read_regular(&path)?;
            link.preview = Some(path);
        }
        Ok(link)
    }

    pub(crate) fn link_hits(
        &self,
        expression: &str,
        sort: crate::Sort,
        limit: u32,
    ) -> Result<Vec<(String, String, i64, String)>> {
        let order = match sort {
            crate::Sort::Newest => "l.added_at DESC,l.row_id",
            crate::Sort::Name => "l.title COLLATE NOCASE,l.row_id",
        };
        let mut out = Vec::new();
        if expression.is_empty() {
            let mut statement = self.db.prepare(&format!(
                "SELECT l.row_id,l.title,l.added_at FROM link_records l WHERE l.state<>'trashed' ORDER BY {order} LIMIT ?1"
            ))?;
            for row in statement.query_map(params![limit], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, String::new()))
            })? {
                out.push(row?);
            }
        } else {
            let mut statement = self.db.prepare(&format!(
                "SELECT l.row_id,l.title,l.added_at,snippet(link_search,2,'','','…',24) FROM link_search s JOIN link_records l ON l.row_id=s.row_id WHERE link_search MATCH ?1 AND l.state<>'trashed' ORDER BY {order} LIMIT ?2"
            ))?;
            for row in statement.query_map(params![expression, limit], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?))
            })? {
                out.push(row?);
            }
        }
        Ok(out)
    }
}
