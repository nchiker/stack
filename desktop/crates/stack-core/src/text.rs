//! Local derived work. Jobs and engine configuration never extend the portable format.
use crate::SavedPage;
use crate::library::{DocumentToken, Error, Library, Result};
use rusqlite::{Connection, OptionalExtension, params};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub const TEXT_CONFIG: &str = "desktop-text-v1/pdfium/eng-fast-87416418/2400";
const MAX_ATTEMPTS: i64 = 3;

#[derive(Clone, Debug)]
pub struct TextJob {
    pub token: DocumentToken,
    pub page: SavedPage,
    pub source: PathBuf,
    pub config: String,
    pub nonce: String,
}

#[derive(Clone, Debug)]
pub struct TextOutput {
    pub text: String,
    /// `embedded` or `ocr`; kept locally, independently of portable metadata.
    pub method: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct TextStatus {
    pub page_id: String,
    pub state: String,
    pub attempts: i64,
    pub error: String,
    pub method: String,
}

/// Called in the same transaction as graph/index writes. Existing current text,
/// including successful empty imported text, needs no recognition.
pub(crate) fn reconcile(db: &Connection, config: &str) -> Result<usize> {
    let changed = db.execute(
        "INSERT INTO text_jobs(page_id,document_id,incarnation,content_revision,config,state)
         SELECT json_extract(p.payload,'$.id'),p.document_id,d.incarnation,json_extract(p.payload,'$.content_revision'),?1,
          CASE WHEN json_extract(p.payload,'$.text_revision')=json_extract(p.payload,'$.content_revision') AND json_extract(p.payload,'$.text_config')=json_extract(p.payload,'$.ocr_config')
          THEN CASE WHEN length(trim(json_extract(p.payload,'$.ocr_text')))>0 THEN 'ready' ELSE 'no_text' END ELSE 'queued' END
         FROM objects p JOIN documents d ON d.id=p.document_id AND d.active=1 WHERE p.kind='pages' AND p.active=1
         ON CONFLICT(page_id) DO UPDATE SET incarnation=excluded.incarnation,content_revision=excluded.content_revision,config=excluded.config,state=excluded.state,attempt=0,nonce='',error='',method=''
         WHERE text_jobs.incarnation<>excluded.incarnation OR text_jobs.content_revision<>excluded.content_revision",
        [config],
    )?;
    db.execute("UPDATE text_jobs SET state='cancelled',nonce='' WHERE NOT EXISTS(SELECT 1 FROM objects p JOIN documents d ON p.document_id=d.id AND d.active=1 WHERE p.kind='pages' AND p.active=1 AND json_extract(p.payload,'$.id')=text_jobs.page_id AND d.incarnation=text_jobs.incarnation)", [])?;
    db.execute("UPDATE text_jobs SET state=CASE WHEN length(trim(json_extract(p.payload,'$.ocr_text')))>0 THEN 'ready' ELSE 'no_text' END FROM objects p WHERE p.kind='pages' AND p.active=1 AND json_extract(p.payload,'$.id')=text_jobs.page_id AND json_extract(p.payload,'$.text_revision')=json_extract(p.payload,'$.content_revision') AND json_extract(p.payload,'$.text_config')=json_extract(p.payload,'$.ocr_config')", [])?;
    Ok(changed)
}

impl Library {
    /// Queue missing/stale pages only. A valid phone OCR result is preserved even
    /// when its engine/config differs. Bounded by the portable 10,000-page limit.
    pub fn queue_text(&mut self, config: &str, document: Option<&str>) -> Result<usize> {
        if config.is_empty() || config.len() > 256 {
            return Err(Error::Invalid("Invalid text configuration"));
        }
        let tx = self.db.transaction()?;
        reconcile(&tx, config)?;
        tx.execute("UPDATE text_jobs SET config=?1,state='queued',attempt=0,nonce='',error='' WHERE (?2 IS NULL OR document_id=?2) AND config<>?1 AND state NOT IN('ready','no_text')", params![config,document])?;
        let count: i64 = tx.query_row("SELECT count(*) FROM text_jobs WHERE state='queued' AND (?1 IS NULL OR document_id=?1)", [document], |r| r.get(0))?;
        tx.commit()?;
        Ok(count as usize)
    }

    /// One outstanding native job for this serialized repository owner. Snapshot
    /// contains the original physical page plus saved appearance, never page order
    /// inferred from the original PDF. Claim is durable before native work starts.
    pub fn claim_text(&mut self) -> Result<Option<TextJob>> {
        let reading: bool = self.db.query_row(
            "SELECT EXISTS(SELECT 1 FROM text_jobs WHERE state='reading')",
            [],
            |r| r.get(0),
        )?;
        if reading {
            return Ok(None);
        }
        let next: Option<(String,String,String)> = self.db.query_row(
            "SELECT j.page_id,j.document_id,j.config FROM text_jobs j JOIN documents d ON d.id=j.document_id AND d.active=1 JOIN objects p ON p.kind='pages' AND p.active=1 AND json_extract(p.payload,'$.id')=j.page_id WHERE j.state='queued' ORDER BY d.rowid,CAST(json_extract(p.payload,'$.position') AS INTEGER) LIMIT 1", [], |r| Ok((r.get(0)?,r.get(1)?,r.get(2)?))).optional()?;
        let Some((id, document, config)) = next else {
            return Ok(None);
        };
        let view = self.document_view(&document)?;
        let page = view
            .pages
            .into_iter()
            .find(|p| p.id == id)
            .ok_or(Error::Stale)?;
        let source = self.page_original(&view.token, &page)?;
        let nonce = uuid::Uuid::new_v4().to_string();
        self.db.execute("UPDATE text_jobs SET state='reading',attempt=attempt+1,nonce=?2,error='' WHERE page_id=?1",params![id,nonce])?;
        Ok(Some(TextJob {
            token: view.token,
            page,
            source,
            config,
            nonce,
        }))
    }

    /// Returns false for cancelled, superseded, edited or deleted inputs. Neither
    /// success nor failure from an old attempt can replace a newer decision.
    pub fn finish_text(
        &mut self,
        job: &TextJob,
        outcome: std::result::Result<TextOutput, String>,
    ) -> Result<bool> {
        let current: bool = self.db.query_row("SELECT EXISTS(SELECT 1 FROM text_jobs WHERE page_id=?1 AND nonce=?2 AND state='reading' AND config=?3 AND incarnation=?4 AND content_revision=?5)", params![job.page.id,job.nonce,job.config,job.token.incarnation,job.page.content_revision], |r|r.get(0))?;
        if !current {
            return Ok(false);
        }
        if self.token(&job.token.id).ok().as_ref() != Some(&job.token)
            || !self
                .document_view(&job.token.id)
                .is_ok_and(|v| v.pages.contains(&job.page))
        {
            self.db.execute("UPDATE text_jobs SET state='queued',nonce='',attempt=0 WHERE page_id=?1 AND nonce=?2",params![job.page.id,job.nonce])?;
            reconcile(&self.db, TEXT_CONFIG)?;
            return Ok(false);
        }
        match outcome {
            Ok(output) => {
                if output.text.len() > 1024 * 1024
                    || !matches!(output.method.as_str(), "embedded" | "ocr")
                {
                    return self
                        .finish_text(job, Err("Text output exceeded its supported limits".into()));
                }
                self.commit_page_text(
                    &job.token,
                    &job.page.id,
                    job.page.content_revision,
                    &output.text,
                    &job.config,
                    &stack_archive::Control::default(),
                )?;
                self.db.execute(
                    "UPDATE text_jobs SET method=?2,error='' WHERE page_id=?1",
                    params![job.page.id, output.method],
                )?;
            }
            Err(_) => {
                // Engine errors may contain source paths or recognized text. Keep
                // a safe fixed product error; diagnostics stay with the caller.
                self.db.execute("UPDATE text_jobs SET state='failed',error='Could not read this page',nonce='' WHERE page_id=?1 AND nonce=?2",params![job.page.id,job.nonce])?;
            }
        }
        Ok(true)
    }

    pub fn text_status(&self, document: &str) -> Result<Vec<TextStatus>> {
        let mut query=self.db.prepare("SELECT j.page_id,j.state,j.attempt,j.error,j.method FROM text_jobs j JOIN objects p ON p.kind='pages' AND p.active=1 AND json_extract(p.payload,'$.id')=j.page_id JOIN documents d ON d.id=j.document_id AND d.active=1 WHERE j.document_id=?1 ORDER BY CAST(json_extract(p.payload,'$.position') AS INTEGER) LIMIT 1000")?;
        Ok(query
            .query_map([document], |r| {
                Ok(TextStatus {
                    page_id: r.get(0)?,
                    state: r.get(1)?,
                    attempts: r.get(2)?,
                    error: r.get(3)?,
                    method: r.get(4)?,
                })
            })?
            .collect::<std::result::Result<_, _>>()?)
    }

    pub fn cancel_text(&mut self, document: &str) -> Result<usize> {
        Ok(self.db.execute("UPDATE text_jobs SET state='cancelled',nonce='' WHERE document_id=?1 AND state IN('queued','reading','failed')",[document])?)
    }

    pub fn retry_text(&mut self, document: &str) -> Result<usize> {
        Ok(self.db.execute("UPDATE text_jobs SET state='queued',nonce='',attempt=0,error='' WHERE document_id=?1 AND state IN('failed','cancelled') AND EXISTS(SELECT 1 FROM objects p WHERE p.kind='pages' AND p.active=1 AND json_extract(p.payload,'$.id')=text_jobs.page_id)",[document])?)
    }

    pub(crate) fn recover_text(&mut self) -> Result<()> {
        self.db.execute("UPDATE text_jobs SET state=CASE WHEN attempt>=?1 THEN 'failed' ELSE 'queued' END,nonce='',error=CASE WHEN attempt>=?1 THEN 'Reading interrupted repeatedly; retry this page' ELSE '' END WHERE state='reading'",[MAX_ATTEMPTS])?;
        reconcile(&self.db, TEXT_CONFIG)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{DocumentEdit, Sort};
    use stack_archive::{Control, SourceVerifier};
    struct StateOnlyVerifier;
    impl SourceVerifier for StateOnlyVerifier {
        fn verify(
            &self,
            _: &std::path::Path,
            _: &str,
            _: Option<u32>,
        ) -> stack_archive::Result<()> {
            Ok(())
        }
    }
    fn fixture() -> (tempfile::TempDir, Library, String) {
        let root = tempfile::tempdir().unwrap();
        let source = root.path().join("authored-state-only.pdf");
        std::fs::write(&source, b"%PDF-state-machine-fixture-not-a-render-test").unwrap();
        let mut library = Library::open(&root.path().join("library")).unwrap();
        library
            .import_original(
                &source,
                "Manual title",
                &StateOnlyVerifier,
                "application/pdf",
                2,
                &Control::default(),
            )
            .unwrap();
        let id = library.search("", None, Sort::Newest, 10).unwrap()[0]
            .document_id
            .clone();
        (root, library, id)
    }
    fn output(text: &str) -> std::result::Result<TextOutput, String> {
        Ok(TextOutput {
            text: text.into(),
            method: "ocr".into(),
        })
    }

    #[test]
    fn queue_restart_failure_cancel_retry_search_and_portable_text_preservation() {
        let (root, mut library, id) = fixture();
        assert_eq!(library.queue_text(TEXT_CONFIG, None).unwrap(), 2);
        let interrupted = library.claim_text().unwrap().unwrap();
        assert!(library.claim_text().unwrap().is_none());
        drop(library);
        let mut library = Library::open(&root.path().join("library")).unwrap();
        assert_eq!(library.text_status(&id).unwrap()[0].state, "queued");
        let first = library.claim_text().unwrap().unwrap();
        assert_ne!(first.nonce, interrupted.nonce);
        assert!(
            !library
                .finish_text(&interrupted, output("obsolete"))
                .unwrap()
        );
        assert!(
            library
                .finish_text(&first, Err("private engine path".into()))
                .unwrap()
        );
        assert_eq!(
            library.text_status(&id).unwrap()[0].error,
            "Could not read this page"
        );
        assert_eq!(library.retry_text(&id).unwrap(), 1);
        let first = library.claim_text().unwrap().unwrap();
        library.finish_text(&first, output("")).unwrap();
        let second = library.claim_text().unwrap().unwrap();
        assert_eq!(second.page.position, 1);
        library
            .finish_text(&second, output("Laterpage sentinel 🦀𐐀"))
            .unwrap();
        assert_eq!(library.text_status(&id).unwrap()[0].state, "no_text");
        let hits = library.search("Laterpage", None, Sort::Newest, 10).unwrap();
        assert_eq!(hits[0].page_id.as_ref(), Some(&second.page.id));
        assert_eq!(library.document_view(&id).unwrap().title, "Manual title");
        assert_eq!(library.queue_text("new-configuration", None).unwrap(), 0);
        let archive = root.path().join("export.stackbackup");
        library
            .export_archive(&archive, &Control::default())
            .unwrap();
        let mut restored = Library::open(&root.path().join("restored")).unwrap();
        let staged = restored
            .stage_archive(
                &mut std::fs::File::open(archive).unwrap(),
                &StateOnlyVerifier,
                &Control::default(),
            )
            .unwrap();
        let plan = restored.review_archive(&staged).unwrap();
        restored.commit_archive(&plan, &Control::default()).unwrap();
        assert_eq!(restored.queue_text(TEXT_CONFIG, None).unwrap(), 0);
        assert_eq!(
            restored
                .search("Laterpage", None, Sort::Newest, 10)
                .unwrap()
                .len(),
            1
        );
        drop(library);
        let library = Library::open(&root.path().join("library")).unwrap();
        assert_eq!(
            library.search("Laterpage", None, Sort::Newest, 10).unwrap()[0]
                .page_id
                .as_ref(),
            Some(&second.page.id)
        );
    }

    #[test]
    fn stale_edit_remove_delete_and_cancel_cannot_publish_or_resurrect() {
        let (_root, mut library, id) = fixture();
        let old = library.claim_text().unwrap().unwrap();
        let _token = library
            .edit_document(
                &old.token,
                DocumentEdit::Rename("Kept manual name".into()),
                &Control::default(),
            )
            .unwrap();
        assert!(!library.finish_text(&old, output("stalesentinel")).unwrap());
        let old = library.claim_text().unwrap().unwrap();
        library.cancel_text(&id).unwrap();
        library.retry_text(&id).unwrap();
        let current = library.claim_text().unwrap().unwrap();
        assert!(
            !library
                .finish_text(&old, Err("old failure".into()))
                .unwrap()
        );
        library
            .finish_text(&current, output("currentphrase"))
            .unwrap();
        let old = library.claim_text().unwrap().unwrap();
        // Page removal makes even a successful native result stale.
        let retained = library.document_view(&id).unwrap().pages[0].id.clone();
        let token = library.token(&id).unwrap();
        let token = library
            .edit_document(
                &token,
                DocumentEdit::PageOrder(vec![retained]),
                &Control::default(),
            )
            .unwrap();
        assert!(!library.finish_text(&old, output("removedphrase")).unwrap());
        assert!(
            library
                .search("removedphrase", None, Sort::Newest, 10)
                .unwrap()
                .is_empty()
        );
        library
            .delete_document(&token, &Control::default())
            .unwrap();
        assert!(
            !library
                .finish_text(&old, output("resurrectphrase"))
                .unwrap()
        );
        assert!(
            library
                .search("resurrectphrase", None, Sort::Newest, 10)
                .unwrap()
                .is_empty()
        );
        assert!(library.claim_text().unwrap().is_none());
        let _ = token;
    }

    #[test]
    fn appearance_and_configuration_invalidate_claims_and_repeated_crashes_stop() {
        let (root, mut library, id) = fixture();
        let old = library.claim_text().unwrap().unwrap();
        library
            .edit_document(
                &old.token,
                DocumentEdit::PageAppearance {
                    page: old.page.id.clone(),
                    rotation: 0,
                    crop: "[0,0,0.5,0,0.5,1,0,1]".into(),
                    enhancement: "original".into(),
                    flatten: false,
                },
                &Control::default(),
            )
            .unwrap();
        assert!(
            !library
                .finish_text(&old, output("excludedcroptext"))
                .unwrap()
        );
        let old = library.claim_text().unwrap().unwrap();
        library.queue_text("changed-config", None).unwrap();
        assert!(!library.finish_text(&old, output("oldconfigtext")).unwrap());
        let first = library.claim_text().unwrap().unwrap();
        assert_eq!(first.config, "changed-config");
        drop(library);
        for _ in 0..2 {
            let mut library = Library::open(&root.path().join("library")).unwrap();
            assert!(library.claim_text().unwrap().is_some());
        }
        let library = Library::open(&root.path().join("library")).unwrap();
        assert_eq!(library.text_status(&id).unwrap()[0].state, "failed");
    }
}
