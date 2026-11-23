//! Linked documents restored from archive format 4: read-only records with saved text and an optional preview PDF.
//! They live in their own tables (`link_records`, `link_search`) and `links/<row_id>.pdf`, outside the document graph,
//! so nothing that assumes a document has pages can meet one. The columns keep everything the archive carries
//! (locators, hash, flags) so a later desktop editor can use them; only the UI is read-only for now.
use crate::library::{Error, Library, Result};
use rusqlite::{OptionalExtension, params};
use serde::Serialize;
use std::path::{Path, PathBuf};
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
    /// Where the synced `filen` path resolves under the learned Filen folder, when that file exists and nothing was
    /// remembered for this document yet. Local only; never saved until the user opens it.
    pub derived_file: Option<String>,
    /// Where this computer last found the original file (a path from the file chooser portal). Local only.
    pub remembered_file: Option<String>,
    /// A document the partner shares with this phone (slice 7): read-only here, opened only on the phone. Never has
    /// a remembered file and is never offered to Ami Pro.
    pub foreign: bool,
    /// The partner's name for the "Shared by" badge; empty for the person's own links.
    pub shared_by: String,
    /// Where the file lives inside Filen (the synced `filen` locator's path); absent for desktop-only or placeholder links.
    pub filen_path: Option<String>,
    /// This computer's own copy of the file is newer than the phone's record and differs from it. Local only.
    pub changed_here: bool,
    /// `text` was read from this computer's copy (not the phone's saved text). Local only.
    pub text_from_computer: bool,
}


/// The `filen` locator's path from a locator list (JSON), if it has a non-empty one.
pub fn filen_path_of(locators: &str) -> Option<String> {
    let list: serde_json::Value = serde_json::from_str(locators).ok()?;
    list.as_array()?.iter().find_map(|entry| {
        (entry["type"] == "filen")
            .then(|| entry["path"].as_str())
            .flatten()
            .map(str::trim)
            .filter(|p| !p.is_empty())
            .map(str::to_owned)
    })
}

/// A Filen path as plain name parts: no empty or `.` parts, nothing that climbs out (`..`), no NUL.
fn filen_parts(path: &str) -> Option<Vec<&str>> {
    if path.contains('\0') {
        return None;
    }
    let parts: Vec<&str> = path
        .split(['/', '\\'])
        .filter(|p| !p.is_empty() && *p != ".")
        .collect();
    (!parts.is_empty() && !parts.contains(&"..")).then_some(parts)
}

/// The Filen folder on this computer: the chosen file's absolute path minus the document's `filen` path, matched as a
/// suffix of whole names (exactly first, then ignoring case). `None` when it cannot be told: the folders do not line up,
/// the chosen path is not absolute or is no longer than the Filen path, or it sits in the document portal's copy area.
pub fn derive_filen_root(chosen: &Path, filen_path: &str) -> Option<PathBuf> {
    use std::path::Component;
    let wanted = filen_parts(filen_path)?;
    if !chosen.is_absolute() {
        return None;
    }
    let names: Vec<std::ffi::OsString> = chosen
        .components()
        .filter_map(|c| match c {
            Component::Normal(name) => Some(name.to_owned()),
            _ => None,
        })
        .collect();
    if chosen.components().any(|c| matches!(c, Component::ParentDir)) || names.len() <= wanted.len() {
        return None;
    }
    let tail = &names[names.len() - wanted.len()..];
    let exact = tail.iter().zip(&wanted).all(|(a, b)| a.to_str() == Some(*b));
    let loose = || {
        tail.iter()
            .zip(&wanted)
            .all(|(a, b)| a.to_str().is_some_and(|a| a.to_lowercase() == b.to_lowercase()))
    };
    if !(exact || loose()) {
        return None;
    }
    let mut root = PathBuf::from("/");
    for name in &names[..names.len() - wanted.len()] {
        root.push(name);
    }
    // A file the chooser portal copied into /run/user/N/doc/<id>/ says nothing about where the folder is.
    let text = root.to_string_lossy();
    if text.starts_with("/run/user/") && text.contains("/doc/") || text.ends_with("/doc") && text.starts_with("/run/user/") {
        return None;
    }
    Some(root)
}

/// What this computer last saw of a link's own file.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct LinkState {
    pub mtime_ms: i64,
    pub size: i64,
    pub file_hash: String,
    pub text: Option<String>,
}

impl LinkState {
    /// Slice 6 merge rule: the computer's file counts as newer only when its content differs from the record's hash AND
    /// its modification time is later than the record's `modified` (epoch ms). Otherwise the phone's record stands.
    pub(crate) fn differs_from(&self, record_hash: &str, record_modified: i64) -> bool {
        let known = record_hash.trim();
        known.len() == 64
            && known.bytes().all(|b| b.is_ascii_hexdigit())
            && !known.eq_ignore_ascii_case(&self.file_hash)
            && self.mtime_ms > record_modified
    }
}

/// The text read from this computer's copy, when it still applies to a record with this hash and `modified`.
pub(crate) fn local_text_for(
    db: &rusqlite::Connection,
    row_id: &str,
    hash: &str,
    modified: i64,
) -> Option<String> {
    let state = read_state(db, row_id).ok().flatten()?;
    state.differs_from(hash, modified).then_some(state.text).flatten()
}

fn read_state(db: &rusqlite::Connection, row_id: &str) -> Result<Option<LinkState>> {
    Ok(db
        .query_row(
            "SELECT mtime_ms,size,file_hash,text FROM link_local_state WHERE row_id=?1",
            [row_id],
            |r| {
                Ok(LinkState {
                    mtime_ms: r.get(0)?,
                    size: r.get(1)?,
                    file_hash: r.get(2)?,
                    text: r.get(3)?,
                })
            },
        )
        .optional()?)
}

fn sha256_hex(path: &Path) -> std::io::Result<String> {
    use sha2::{Digest, Sha256};
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buffer)?;
        if n == 0 {
            break;
        }
        hasher.update(&buffer[..n]);
    }
    Ok(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// The longest text kept from the computer's copy, the same bound link sync uses (1 MiB of UTF-8).
const LOCAL_TEXT_LIMIT: usize = 1 << 20;

/// Outcome of one pass over the remembered or derived files.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct LocalRefresh {
    /// Files that existed and were looked at (cheap stat compare, or hashed when touched).
    pub checked: usize,
    /// Links whose file on this computer is newer than the phone's record and differs from it.
    pub changed: usize,
    /// Of those, how many now have text read from the computer's copy.
    pub with_text: usize,
}

impl Library {
    /// The learned Filen folder on this computer, if any. Local only.
    pub fn link_filen_root(&self) -> Result<Option<String>> {
        Ok(self
            .db
            .query_row(
                "SELECT value FROM link_local_settings WHERE key='filen_root'",
                [],
                |r| r.get(0),
            )
            .optional()?)
    }

    /// Remember (or, with `None`, forget) the Filen folder. Only an absolute path is kept.
    pub fn set_link_filen_root(&self, root: Option<&str>) -> Result<()> {
        match root {
            Some(root) if Path::new(root).is_absolute() => {
                self.db.execute(
                    "INSERT OR REPLACE INTO link_local_settings(key,value) VALUES('filen_root',?1)",
                    [root],
                )?;
            }
            Some(_) => return Err(Error::Invalid("The Filen folder must be an absolute path")),
            None => {
                self.db
                    .execute("DELETE FROM link_local_settings WHERE key='filen_root'", [])?;
            }
        }
        Ok(())
    }

    /// After the user chose `chosen` for link `id`: learn the Filen folder from it when the link has a `filen` path
    /// and the path lines up. Returns the folder when one was learned; `None` (nothing changes) otherwise.
    pub fn learn_filen_root(&self, id: &str, chosen: &Path) -> Result<Option<PathBuf>> {
        stack_archive::uuid(id)?;
        let locators: Option<String> = self
            .db
            .query_row(
                "SELECT locators FROM link_records WHERE row_id=?1 AND origin='own'",
                [id],
                |r| r.get(0),
            )
            .optional()?;
        let Some(filen) = locators.and_then(|l| filen_path_of(&l)) else {
            return Ok(None);
        };
        let Some(root) = derive_filen_root(chosen, &filen) else {
            return Ok(None);
        };
        self.set_link_filen_root(Some(&root.to_string_lossy()))?;
        Ok(Some(root))
    }

    /// The file a `filen` path points to under the learned folder, only when it is there as a regular file.
    pub(crate) fn derived_link_file(&self, filen_path: Option<&str>) -> Result<Option<PathBuf>> {
        let (Some(filen), Some(root)) = (filen_path, self.link_filen_root()?) else {
            return Ok(None);
        };
        let Some(parts) = filen_parts(filen) else {
            return Ok(None);
        };
        let mut path = PathBuf::from(root);
        path.extend(parts);
        Ok(std::fs::metadata(&path).is_ok_and(|m| m.is_file()).then_some(path))
    }

    /// True when this computer's copy of an own link's file is newer than, and differs from, the phone's record.
    pub fn link_changed_here(&self, id: &str) -> bool {
        let record: Option<(String, i64)> = self
            .db
            .query_row(
                "SELECT hash,modified FROM link_records WHERE row_id=?1 AND origin='own'",
                [id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()
            .ok()
            .flatten();
        let (Some((hash, modified)), Ok(Some(state))) = (record, self.link_state(id)) else {
            return false;
        };
        state.differs_from(&hash, modified)
    }

    pub(crate) fn link_state(&self, id: &str) -> Result<Option<LinkState>> {
        read_state(&self.db, id)
    }

    fn set_search_body(&self, row_id: &str, body: &str) -> Result<()> {
        let current: Option<String> = self
            .db
            .query_row("SELECT body FROM link_search WHERE row_id=?1", [row_id], |r| r.get(0))
            .optional()?;
        if current.as_deref() != Some(body) {
            self.db
                .execute("UPDATE link_search SET body=?1 WHERE row_id=?2", params![body, row_id])?;
        }
        Ok(())
    }

    /// Look at this computer's own copy of every own link's file (remembered, else derived from the Filen folder).
    /// Cheap first: unchanged modification time and size reuse the stored hash. A file that differs from the record's
    /// hash AND is newer than its `modified` is "changed on this computer"; its text is read with `extract` (None when
    /// no reader applies, or the link is protected) and replaces the search text locally. Nothing is written to the file
    /// and no synced cell or record column is touched, so the phone's next sync of a newer `modified` outranks it.
    pub fn refresh_local_files(&self, extract: &dyn Fn(&Path) -> Option<String>) -> Result<LocalRefresh> {
        let rows: Vec<(String, i64, String, bool, String, String)> = {
            let mut statement = self.db.prepare(
                "SELECT row_id,modified,hash,protected,locators,text FROM link_records WHERE origin='own' AND state<>'trashed'",
            )?;
            statement
                .query_map([], |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get::<_, i64>(3)? != 0, r.get(4)?, r.get(5)?))
                })?
                .collect::<std::result::Result<_, _>>()?
        };
        let mut report = LocalRefresh::default();
        for (id, modified, hash, protected, locators, saved_text) in rows {
            let path = match self.link_local_file(&id)? {
                Some(path) => Some(PathBuf::from(path)),
                None => self.derived_link_file(filen_path_of(&locators).as_deref())?,
            };
            let meta = path
                .as_ref()
                .and_then(|p| std::fs::metadata(p).ok())
                .filter(|m| m.is_file());
            let (Some(path), Some(meta)) = (path, meta) else {
                self.db.execute("DELETE FROM link_local_state WHERE row_id=?1", [&id])?;
                self.set_search_body(&id, &saved_text)?;
                continue;
            };
            report.checked += 1;
            let mtime_ms = meta
                .modified()
                .ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map_or(0, |d| d.as_millis() as i64);
            let size = meta.len() as i64;
            let old = read_state(&self.db, &id)?;
            let state = match old {
                Some(old) if old.mtime_ms == mtime_ms && old.size == size => old,
                _ => {
                    let Ok(file_hash) = sha256_hex(&path) else {
                        continue;
                    };
                    let mut state = LinkState { mtime_ms, size, file_hash, text: None };
                    if !protected && state.differs_from(&hash, modified) {
                        state.text = extract(&path).map(|mut text| {
                            if text.len() > LOCAL_TEXT_LIMIT {
                                let mut end = LOCAL_TEXT_LIMIT;
                                while !text.is_char_boundary(end) {
                                    end -= 1;
                                }
                                text.truncate(end);
                            }
                            text
                        });
                    }
                    self.db.execute(
                        "INSERT OR REPLACE INTO link_local_state(row_id,mtime_ms,size,file_hash,text) VALUES(?1,?2,?3,?4,?5)",
                        params![id, state.mtime_ms, state.size, state.file_hash, state.text],
                    )?;
                    state
                }
            };
            let differs = state.differs_from(&hash, modified);
            if differs {
                report.changed += 1;
            }
            match state.text.as_deref().filter(|_| differs && !protected) {
                Some(text) => {
                    report.with_text += 1;
                    self.set_search_body(&id, text)?;
                }
                None => self.set_search_body(&id, &saved_text)?,
            }
        }
        Ok(report)
    }
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
                            filen_path: None,
                            derived_file: None,
                            changed_here: false,
                            text_from_computer: false,
                        },
                        r.get::<_, i64>(10)? != 0,
                    ))
                },
            )
            .optional()?
            .ok_or(Error::Stale)?;
        let (mut link, has_preview) = row;
        if !link.foreign {
            let locators: String = self
                .db
                .query_row("SELECT locators FROM link_records WHERE row_id=?1", [id], |r| r.get(0))?;
            link.filen_path = filen_path_of(&locators);
            link.remembered_file = self.link_local_file(id)?;
            if link.remembered_file.is_none()
                && let Some(path) = self.derived_link_file(link.filen_path.as_deref())?
            {
                link.derived_file = Some(path.to_string_lossy().into_owned());
            }
            if let Some(state) = self.link_state(id)?
                && state.differs_from(&link.hash, link.modified)
            {
                link.changed_here = true;
                if let Some(text) = state.text {
                    link.text = text;
                    link.text_from_computer = true;
                }
            }
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
    use std::path::{Path, PathBuf};

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

    fn set_locators(library: &Library, filen_path: &str) {
        library
            .db
            .execute(
                "UPDATE link_records SET locators=?1",
                [format!(r#"[{{"type":"saf","uri":"content://x"}},{{"type":"filen","uuid":"u","path":"{filen_path}"}}]"#)],
            )
            .unwrap();
    }

    #[test]
    fn filen_root_is_the_chosen_path_minus_the_filen_path() {
        use super::derive_filen_root as derive;
        let root = |chosen: &str, filen: &str| derive(Path::new(chosen), filen);
        assert_eq!(
            root("/home/a/Filen/Docs/Letters/Hello.sam", "/Docs/Letters/Hello.sam"),
            Some(PathBuf::from("/home/a/Filen"))
        );
        // No leading slash, extra slashes and dots in the Filen path do not matter.
        assert_eq!(root("/home/a/Filen/Docs/Hello.sam", "Docs//./Hello.sam"), Some(PathBuf::from("/home/a/Filen")));
        // Exact match first, then ignoring case.
        assert_eq!(root("/x/FILEN/docs/hello.SAM", "/Docs/Hello.sam"), Some(PathBuf::from("/x/FILEN")));
        // The file name alone is not enough when the Filen path has folders that do not line up.
        assert_eq!(root("/home/a/Other/Hello.sam", "/Docs/Hello.sam"), None);
        assert_eq!(root("/home/a/Filen/Docs/Other.sam", "/Docs/Hello.sam"), None);
        // A file at the top of Filen: the folder is the chosen file's own folder.
        assert_eq!(root("/home/a/Filen/Hello.sam", "/Hello.sam"), Some(PathBuf::from("/home/a/Filen")));
        // Nothing left over for a folder, not absolute, empty, climbing, or a document-portal copy: ignored.
        assert_eq!(root("/Hello.sam", "/Hello.sam"), None);
        assert_eq!(root("a/Docs/Hello.sam", "/Docs/Hello.sam"), None);
        assert_eq!(root("/a/b.sam", ""), None);
        assert_eq!(root("/a/b.sam", "/../b.sam"), None);
        assert_eq!(root("/run/user/1000/doc/abc123/Hello.sam", "/Hello.sam"), None);
    }

    #[test]
    fn learning_the_folder_from_one_document_resolves_the_others() {
        let (dir, library) = library();
        set_locators(&library, "/Docs/Hello.sam");
        assert_eq!(library.linked_document(ID).unwrap().filen_path.as_deref(), Some("/Docs/Hello.sam"));
        // Nothing learned yet: no derived file, so the chooser is still needed.
        assert_eq!(library.linked_document(ID).unwrap().derived_file, None);
        let filen = dir.path().join("Filen");
        std::fs::create_dir_all(filen.join("Docs")).unwrap();
        let chosen = filen.join("Docs/Hello.sam");
        std::fs::write(&chosen, b"x").unwrap();
        // A path that does not line up learns nothing and changes nothing.
        assert_eq!(library.learn_filen_root(ID, &dir.path().join("elsewhere/Hello.sam")).unwrap(), None);
        assert_eq!(library.link_filen_root().unwrap(), None);
        assert_eq!(library.learn_filen_root(ID, &chosen).unwrap(), Some(filen.clone()));
        assert_eq!(library.link_filen_root().unwrap(), Some(filen.to_string_lossy().into_owned()));
        // Another document with a filen path under the same folder is found without asking, when the file exists.
        library
            .db
            .execute(
                "INSERT INTO link_records(row_id,owner,link_id,kind,title,mime,modified,hash,shared,protected,locators,state,title_user_edited,added_at,text,preview) VALUES('33333333-3333-4333-8333-333333333333','o','44444444-4444-4444-8444-444444444444','doc','Other','m',1,'',0,0,?1,'live',0,1,'',0)",
                [r#"[{"type":"filen","uuid":"v","path":"/Docs/Other.sam"}]"#],
            )
            .unwrap();
        let other = "33333333-3333-4333-8333-333333333333";
        assert_eq!(library.linked_document(other).unwrap().derived_file, None, "file not there");
        std::fs::write(filen.join("Docs/Other.sam"), b"y").unwrap();
        let view = library.linked_document(other).unwrap();
        assert_eq!(view.derived_file.as_deref(), Some(filen.join("Docs/Other.sam").to_str().unwrap()));
        assert_eq!(view.remembered_file, None, "derived is never saved by itself");
        // A remembered choice wins over the derived one; forgetting the folder takes derived paths away.
        library.set_link_local_file(other, Some("/elsewhere/o.sam")).unwrap();
        assert_eq!(library.linked_document(other).unwrap().derived_file, None);
        library.set_link_local_file(other, None).unwrap();
        library.set_link_filen_root(None).unwrap();
        assert_eq!(library.linked_document(other).unwrap().derived_file, None);
        // A link with no filen path (desktop-only or placeholder) never learns or derives anything.
        library.db.execute("UPDATE link_records SET locators='[]'", []).unwrap();
        assert_eq!(library.linked_document(ID).unwrap().filen_path, None);
        assert_eq!(library.learn_filen_root(ID, &chosen).unwrap(), None);
        assert!(library.set_link_filen_root(Some("relative")).is_err());
    }

    fn write_file(path: &Path, bytes: &[u8], seconds_after_epoch: u64) {
        std::fs::write(path, bytes).unwrap();
        let time = std::time::UNIX_EPOCH + std::time::Duration::from_secs(seconds_after_epoch);
        std::fs::File::options().write(true).open(path).unwrap().set_modified(time).unwrap();
    }

    #[test]
    fn a_newer_different_file_is_changed_here_and_its_text_stays_local() {
        let (dir, library) = library();
        let hash = "a".repeat(64);
        // Saved record: modified 1_000_000 ms (1000 s), hash known, saved text "old saved words".
        library
            .db
            .execute(
                "UPDATE link_records SET hash=?1,modified=1000000,text='old saved words'",
                [&hash],
            )
            .unwrap();
        library.db.execute("INSERT INTO link_search VALUES(?1,'T','old saved words')", [ID]).unwrap();
        let file = dir.path().join("T.sam");
        write_file(&file, b"new content", 2000);
        library.set_link_local_file(ID, Some(file.to_str().unwrap())).unwrap();
        let calls = std::cell::Cell::new(0);
        let extract = |_: &Path| {
            calls.set(calls.get() + 1);
            Some("fresh local words".to_owned())
        };
        let report = library.refresh_local_files(&extract).unwrap();
        assert_eq!((report.checked, report.changed, report.with_text), (1, 1, 1));
        let view = library.linked_document(ID).unwrap();
        assert!(view.changed_here && view.text_from_computer);
        assert_eq!(view.text, "fresh local words");
        let found = |q: &str| library.link_hits(q, crate::Sort::Name, 10).unwrap().len();
        assert_eq!((found("fresh"), found("old")), (1, 0));
        // The record itself (what sync reads and sends) is untouched.
        let (text, hash_now): (String, String) = library
            .db
            .query_row("SELECT text,hash FROM link_records", [], |r| Ok((r.get(0)?, r.get(1)?)))
            .unwrap();
        assert_eq!((text.as_str(), hash_now.as_str()), ("old saved words", hash.as_str()));
        // Unchanged size and time: no second read of the file, no second extraction.
        library.refresh_local_files(&extract).unwrap();
        assert_eq!(calls.get(), 1);
        // The phone's record moves on to a NEWER modified (slice 6: later modified wins): the local text no longer applies.
        library.db.execute("UPDATE link_records SET modified=5000000", []).unwrap();
        let view = library.linked_document(ID).unwrap();
        assert!(!view.changed_here && !view.text_from_computer);
        assert_eq!(view.text, "old saved words");
        let report = library.refresh_local_files(&extract).unwrap();
        assert_eq!(report.changed, 0);
        assert_eq!((found("fresh"), found("old")), (0, 1));
        // The file is saved again, later than the phone's record: changed again, text read again.
        write_file(&file, b"newer still", 9000);
        assert_eq!(library.refresh_local_files(&extract).unwrap().changed, 1);
        assert_eq!(found("fresh"), 1);
        // The file is the same as the record: nothing changed, saved text back in search.
        library.db.execute("UPDATE link_records SET hash=?1", [crate::links::sha256_hex(&file).unwrap()]).unwrap();
        assert_eq!(library.refresh_local_files(&extract).unwrap().changed, 0);
        assert_eq!((found("fresh"), found("old")), (0, 1));
        // The file disappears: no state left.
        std::fs::remove_file(&file).unwrap();
        library.refresh_local_files(&extract).unwrap();
        assert!(library.link_state(ID).unwrap().is_none());
    }

    #[test]
    fn an_older_file_unknown_hash_missing_reader_and_protected_links_are_handled() {
        let (dir, library) = library();
        let hash = "b".repeat(64);
        let none = |_: &Path| None;
        let file = dir.path().join("T.sam");
        library.set_link_local_file(ID, Some(file.to_str().unwrap())).unwrap();
        // The file differs but is OLDER than the phone's record: the phone is ahead, no notice.
        library.db.execute("UPDATE link_records SET hash=?1,modified=9000000", [&hash]).unwrap();
        write_file(&file, b"older", 100);
        assert_eq!(library.refresh_local_files(&none).unwrap().changed, 0);
        assert!(!library.linked_document(ID).unwrap().changed_here);
        // A record without a usable hash cannot be compared: no notice.
        library.db.execute("UPDATE link_records SET hash='',modified=1", []).unwrap();
        assert_eq!(library.refresh_local_files(&none).unwrap().changed, 0);
        // Newer and different but no text reader: the notice only, saved text stays.
        library.db.execute("UPDATE link_records SET hash=?1", [&hash]).unwrap();
        let view = library.linked_document(ID).unwrap();
        assert!(view.changed_here && !view.text_from_computer);
        // Protected: the notice, never text from the file.
        library.db.execute("UPDATE link_records SET protected=1", []).unwrap();
        library.db.execute("DELETE FROM link_local_state", []).unwrap();
        let report = library.refresh_local_files(&|_: &Path| Some("secret".to_owned())).unwrap();
        assert_eq!((report.changed, report.with_text), (1, 0));
        assert!(!library.linked_document(ID).unwrap().text_from_computer);
    }
}
