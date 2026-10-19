use crate::library::{Error, Library, Result};
use rusqlite::params;
use serde::Serialize;
use stack_archive::validation;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Sort {
    Newest,
    Name,
}
#[derive(Clone, Debug, Serialize)]
pub struct SearchHit {
    pub document_id: String,
    pub title: String,
    pub snippet: String,
    pub page_id: Option<String>,
}

impl Library {
    pub fn search(
        &self,
        query: &str,
        stack: Option<&str>,
        sort: Sort,
        limit: u32,
    ) -> Result<Vec<SearchHit>> {
        if validation::utf16_len(query) > 512 || query.contains('\0') || limit == 0 || limit > 1000
        {
            return Err(Error::Invalid(
                "Search query or result limit is outside bounds",
            ));
        }
        let terms: Vec<_> = query.split_whitespace().collect();
        if terms.len() > 32 {
            return Err(Error::Invalid("Search has too many terms"));
        }
        let expression = terms
            .iter()
            .map(|s| format!("\"{}\"", s.replace('"', "\"\"")))
            .collect::<Vec<_>>()
            .join(" AND ");
        let order = match sort {
            Sort::Newest => "json_extract(d.payload,'$.added_at') DESC,d.id",
            Sort::Name => "s.title COLLATE NOCASE,d.id",
        };
        let mut sql = String::from(
            "SELECT d.id,s.title FROM document_search s JOIN documents d ON d.id=s.document_id WHERE d.active=1 AND (?1 IS NULL OR EXISTS(SELECT 1 FROM objects o WHERE o.kind='memberships' AND o.active=1 AND o.document_id=d.id AND json_extract(o.payload,'$.stack_id')=?1))",
        );
        if !expression.is_empty() {
            sql.push_str(" AND document_search MATCH ?2");
        } else {
            sql.push_str(" AND ?2=''");
        }
        sql.push_str(&format!(" ORDER BY {order} LIMIT ?3"));
        let mut statement = self.db.prepare(&sql)?;
        let rows = statement.query_map(params![stack, expression, limit], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
        })?;
        let mut hits = Vec::new();
        for row in rows {
            let (id, title) = row?;
            let page: Option<(String, String)> = if expression.is_empty() {
                None
            } else {
                use rusqlite::OptionalExtension;
                self.db.query_row("SELECT page_id,snippet(page_search,2,'','','…',24) FROM page_search WHERE document_id=?1 AND page_search MATCH ?2 ORDER BY CAST(position AS INTEGER) LIMIT 1",params![id,expression],|r|Ok((r.get(0)?,r.get(1)?))).optional()?
            };
            let (page_id, snippet) = match page {
                Some((p, s)) => (Some(p), s),
                None => (None, String::new()),
            };
            hits.push(SearchHit {
                document_id: id,
                title,
                snippet,
                page_id,
            });
        }
        Ok(hits)
    }
}
