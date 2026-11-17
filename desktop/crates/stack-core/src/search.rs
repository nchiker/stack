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

/// Another stack that shares documents with a chosen stack.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StackOverlap {
    pub stack_id: String,
    pub count: usize,
}

impl Library {
    /// Other stacks whose documents are also in `stack`, most shared first.
    pub fn overlapping_stacks(&self, stack: &str) -> Result<Vec<StackOverlap>> {
        let mut statement = self.db.prepare(
            "WITH m AS (SELECT o.document_id AS doc, json_extract(o.payload,'$.stack_id') AS stack FROM objects o WHERE o.kind='memberships' AND o.active=1) \
             SELECT b.stack, COUNT(DISTINCT a.doc) AS total FROM m a JOIN m b ON b.doc=a.doc AND b.stack<>a.stack \
             JOIN documents d ON d.id=a.doc AND d.active=1 \
             JOIN objects s ON s.kind='stacks' AND s.active=1 AND json_extract(s.payload,'$.id')=b.stack \
             WHERE a.stack=?1 GROUP BY b.stack ORDER BY total DESC, b.stack",
        )?;
        let rows = statement.query_map(params![stack], |r| {
            Ok(StackOverlap {
                stack_id: r.get(0)?,
                count: r.get::<_, i64>(1)? as usize,
            })
        })?;
        rows.collect::<std::result::Result<_, _>>()
            .map_err(Into::into)
    }

    pub fn search(
        &self,
        query: &str,
        stack: Option<&str>,
        sort: Sort,
        limit: u32,
    ) -> Result<Vec<SearchHit>> {
        self.search_in(query, stack, None, sort, limit)
    }

    /// Like [`Library::search`], but documents must also be in `also` when it is given.
    pub fn search_in(
        &self,
        query: &str,
        stack: Option<&str>,
        also: Option<&str>,
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
            "SELECT d.id,s.title FROM document_search s JOIN documents d ON d.id=s.document_id WHERE d.active=1 AND (?1 IS NULL OR EXISTS(SELECT 1 FROM objects o WHERE o.kind='memberships' AND o.active=1 AND o.document_id=d.id AND json_extract(o.payload,'$.stack_id')=?1)) AND (?4 IS NULL OR EXISTS(SELECT 1 FROM objects o WHERE o.kind='memberships' AND o.active=1 AND o.document_id=d.id AND json_extract(o.payload,'$.stack_id')=?4))",
        );
        if !expression.is_empty() {
            sql.push_str(" AND document_search MATCH ?2");
        } else {
            sql.push_str(" AND ?2=''");
        }
        sql.push_str(&format!(" ORDER BY {order} LIMIT ?3"));
        let mut statement = self.db.prepare(&sql)?;
        let rows = statement.query_map(params![stack, expression, limit, also], |r| {
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
