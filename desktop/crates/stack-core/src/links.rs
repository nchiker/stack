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
    /// Where this computer last found the original file (a path from the file chooser portal). Local only.
    pub remembered_file: Option<String>,
    /// A document the partner shares with this phone (slice 7): read-only here, opened only on the phone. Never has
    /// a remembered file and is never offered to Ami Pro.
    pub foreign: bool,
    /// The partner's name for the "Shared by" badge; empty for the person's own links.
    pub shared_by: String,
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

    /// The remembered original-file path for a link on this computer, if one was chosen.
    pub fn link_local_file(&self, id: &str) -> Result<Option<String>> {
        stack_archive::uuid(id)?;
        Ok(self
            .db
            .query_row(
                "SELECT path FROM link_local_files WHERE row_id=?1",
                [id],
                |r| r.get(0),
            )
            .optional()?)
    }

    /// Remember (or, with `None`, forget) the original file for a link. Refuses ids that are not link rows.
    pub fn set_link_local_file(&self, id: &str, path: Option<&str>) -> Result<()> {
        stack_archive::uuid(id)?;
        match path {
            Some(path) if !path.is_empty() => {
                // A shared copy has no file on this computer to remember; only own links can have one.
                let known: bool = self.db.query_row(
                    "SELECT EXISTS(SELECT 1 FROM link_records WHERE row_id=?1 AND origin='own')",
                    [id],
                    |r| r.get(0),
                )?;
                if !known {
                    return Err(Error::Stale);
                }
                self.db.execute(
                    "INSERT OR REPLACE INTO link_local_files(row_id,path) VALUES(?1,?2)",
                    params![id, path],
                )?;
            }
            _ => {
                self.db
                    .execute("DELETE FROM link_local_files WHERE row_id=?1", [id])?;
            }
        }
        Ok(())
    }

    pub fn linked_document(&self, id: &str) -> Result<LinkedDocument> {
        stack_archive::uuid(id)?;
        let row = self
            .db
            .query_row(
                "SELECT owner,link_id,kind,title,mime,modified,added_at,state,hash,text,preview AND NOT preview_pending,origin,shared_by FROM link_records WHERE row_id=?1",
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
                            remembered_file: None,
                            foreign: r.get::<_, String>(11)? == "foreign",
                            shared_by: r.get(12)?,
                        },
                        r.get::<_, i64>(10)? != 0,
                    ))
                },
            )
            .optional()?
            .ok_or(Error::Stale)?;
        let (mut link, has_preview) = row;
        if !link.foreign {
            link.remembered_file = self.link_local_file(id)?;
        }
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
    ) -> Result<Vec<(String, String, i64, String, String)>> {
        let order = match sort {
            crate::Sort::Newest => "l.added_at DESC,l.row_id",
            crate::Sort::Name => "l.title COLLATE NOCASE,l.row_id",
        };
        let mut out = Vec::new();
        if expression.is_empty() {
            let mut statement = self.db.prepare(&format!(
                "SELECT l.row_id,l.title,l.added_at,CASE l.origin WHEN 'foreign' THEN l.shared_by ELSE '' END FROM link_records l WHERE l.state<>'trashed' ORDER BY {order} LIMIT ?1"
            ))?;
            for row in statement.query_map(params![limit], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, String::new(), r.get(3)?))
            })? {
                out.push(row?);
            }
        } else {
            let mut statement = self.db.prepare(&format!(
                "SELECT l.row_id,l.title,l.added_at,snippet(link_search,2,'','','…',24),CASE l.origin WHEN 'foreign' THEN l.shared_by ELSE '' END FROM link_search s JOIN link_records l ON l.row_id=s.row_id WHERE link_search MATCH ?1 AND l.state<>'trashed' ORDER BY {order} LIMIT ?2"
            ))?;
            for row in statement.query_map(params![expression, limit], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?))
            })? {
                out.push(row?);
            }
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use crate::library::Library;

    const ID: &str = "11111111-1111-4111-8111-111111111111";

    fn library() -> (tempfile::TempDir, Library) {
        let dir = tempfile::tempdir().unwrap();
        let library = Library::open(&dir.path().join("library")).unwrap();
        library
            .db
            .execute(
                "INSERT INTO link_records(row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text,preview) VALUES(?1,'o','22222222-2222-4222-8222-222222222222','doc','T','m',1,'',0,0,'[]','live',0,1,'',0)",
                [ID],
            )
            .unwrap();
        (dir, library)
    }

    #[test]
    fn remembered_file_is_per_link_replaceable_and_forgettable() {
        let (_dir, library) = library();
        assert_eq!(library.linked_document(ID).unwrap().remembered_file, None);
        library.set_link_local_file(ID, Some("/a/b.sam")).unwrap();
        library.set_link_local_file(ID, Some("/c/d.sam")).unwrap();
        assert_eq!(
            library
                .linked_document(ID)
                .unwrap()
                .remembered_file
                .as_deref(),
            Some("/c/d.sam")
        );
        library.set_link_local_file(ID, None).unwrap();
        assert_eq!(library.link_local_file(ID).unwrap(), None);
    }

    #[test]
    fn a_shared_copy_never_gets_a_remembered_file_and_says_who_shared_it() {
        let (_dir, library) = library();
        library
            .db
            .execute(
                "UPDATE link_records SET origin='foreign',shared_by='Sam'",
                [],
            )
            .unwrap();
        assert!(library.set_link_local_file(ID, Some("/x")).is_err());
        let view = library.linked_document(ID).unwrap();
        assert!(view.foreign);
        assert_eq!(view.shared_by, "Sam");
        assert_eq!(view.remembered_file, None);
        let hits = library.link_hits("", crate::Sort::Name, 10).unwrap();
        assert_eq!(hits[0].4, "Sam");
    }

    #[test]
    fn unknown_link_and_bad_id_are_refused() {
        let (_dir, library) = library();
        assert!(
            library
                .set_link_local_file("33333333-3333-4333-8333-333333333333", Some("/x"))
                .is_err()
        );
        assert!(library.set_link_local_file("nope", Some("/x")).is_err());
    }
}
