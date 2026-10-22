//! A local reset is scheduled only after separate recovery warnings and a typed
//! phrase, then applied on cold restart under the persistent writer lock.
use crate::{
    Message, Stack,
    repository::{Request, Response},
};
use cosmic::{Element, app, widget};
use serde_json::Value;
use stack_core::reset::Mode;
#[derive(Clone, Debug)]
pub enum Action {
    Open,
    Restore,
    Setup,
    Choose(Mode),
    Next,
    Typed(String),
    Commit,
    Cancel,
    Result(Box<Result<Response, String>>),
}
#[derive(Default)]
pub struct Ui {
    pub phase: u8,
    pub mode: Option<Mode>,
    pub phrase: String,
    pub preview: Value,
    pub error: Option<String>,
    pub busy: bool,
}
fn msg(a: Action) -> Message {
    Message::Reset(a)
}
impl Stack {
    pub fn reset_action(&mut self, action: Action) -> app::Task<Message> {
        match action {
            Action::Restore if self.reset.phase == 5 => {
                self.reset = Ui::default();
                return self.transfer_action(crate::transfers_ui::Action::Pick(
                    crate::transfers_ui::Pick::Archive,
                ));
            }
            Action::Setup if self.reset.phase == 5 => {
                self.reset = Ui::default();
                let task = self.sync_action(crate::sync_ui::Action::Open);
                self.sync.page = crate::sync_ui::Page::Overview;
                return task;
            }

            Action::Cancel if !self.reset.busy => self.reset = Ui::default(),
            Action::Open if !self.transfers.busy() && !self.sync.busy && !self.saving => {
                self.library_menu_open = false;
                self.reset = Ui::default();
                self.reset.phase = 1;
                self.reset.busy = true;
                if let Some(worker) = &self.repository {
                    let reply = worker.submit_required(Request::ResetPreview);
                    return cosmic::task::future(async move {
                        msg(Action::Result(Box::new(
                            reply
                                .await
                                .unwrap_or_else(|_| Err("Library worker stopped".into())),
                        )))
                    });
                }
            }
            Action::Choose(mode)
                if self.reset.phase == 1 && !self.reset.busy && !self.reset.preview.is_null() =>
            {
                self.reset.mode = Some(mode);
                self.reset.phase = 2;
                self.reset.phrase.clear();
            }
            Action::Next if self.reset.phase == 2 => self.reset.phase = 3,
            Action::Next if self.reset.phase == 3 && self.reset.mode == Some(Mode::Erase) => {
                self.reset.phase = 4
            }
            Action::Typed(value) if !self.reset.busy => self.reset.phrase = value,
            Action::Commit
                if !self.reset.busy
                    && self.reset.phrase == self.reset_phrase()
                    && ((self.reset.phase == 4 && self.reset.mode == Some(Mode::Erase))
                        || (self.reset.phase == 3
                            && self.reset.mode == Some(Mode::Connection))) =>
            {
                if let Some(worker) = &self.repository {
                    self.reset.busy = true;
                    let reply = worker.submit_required(Request::ScheduleReset {
                        mode: self.reset.mode.expect("chosen reset mode"),
                        library: self.reset.preview["library_id"]
                            .as_str()
                            .unwrap_or("")
                            .into(),
                        revision: self.reset.preview["revision"].as_i64().unwrap_or(-1),
                    });
                    return cosmic::task::future(async move {
                        msg(Action::Result(Box::new(
                            reply
                                .await
                                .unwrap_or_else(|_| Err("Library worker stopped".into())),
                        )))
                    });
                }
            }
            Action::Result(result) => {
                self.reset.busy = false;
                match *result {
                    Ok(Response::ResetRecovery(mode)) => {
                        if let Some(mode) = mode {
                            self.reset.mode = Some(mode);
                            self.reset.phase = 5;
                        }
                    }
                    Ok(Response::ResetPreview(preview)) => self.reset.preview = preview,
                    Ok(Response::ResetScheduled) => {
                        self.drafts.clear();
                        self.catalog.mark_saved();
                        self.reset = Ui::default();
                        return self.update_message(Message::Close);
                    }
                    Err(error) => self.reset.error = Some(error),
                    _ => self.reset.error = Some("Unexpected reset response".into()),
                }
            }
            _ => {}
        }
        app::Task::none()
    }
    fn reset_phrase(&self) -> &'static str {
        if self.reset.mode == Some(Mode::Erase) {
            "ERASE THIS COMPUTER"
        } else {
            "RESET CONNECTION"
        }
    }
    pub fn reset_recovery(&self) -> app::Task<Message> {
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        let reply = worker.submit_required(Request::ResetRecovery);
        cosmic::task::future(async move {
            msg(Action::Result(Box::new(
                reply
                    .await
                    .unwrap_or_else(|_| Err("Library worker stopped".into())),
            )))
        })
    }
    pub fn reset_dialog(&self) -> Option<Element<'_, Message>> {
        if self.reset.phase == 0 {
            return None;
        }
        let mut dialog = widget::dialog()
            .title("Reset or rebuild this computer's library")
            .primary_action(
                self.button("Keep my library")
                    .on_press_maybe((!self.reset.busy).then_some(msg(Action::Cancel))),
            );
        if let Some(error) = &self.reset.error {
            return Some(dialog.body(format!("Reset was not scheduled: {error}. Start the review again if the library changed.")).secondary_action(self.button("Start review again").on_press(msg(Action::Open))).into());
        }
        let count = format!(
            "{} documents · {} pages · {} stacks",
            self.reset.preview["documents"],
            self.reset.preview["pages"],
            self.reset.preview["stacks"]
        );
        match self.reset.phase {
            5 => {
                dialog=dialog.title(if self.reset.mode==Some(Mode::Erase){"This computer's library was erased"}else{"Ready for a new connection"}).body("Restore a saved backup, or connect to your phone again. To resync from a phone that still has your documents, choose Start a new connection on the phone while keeping its documents, then pair and review the libraries. Each device requires its own local approval.").primary_action(self.button("Later").on_press(msg(Action::Cancel)))
                .control(widget::column::with_capacity(2).spacing(12).push(self.button("Import backup…").on_press(msg(Action::Restore))).push(self.button("Set up a phone").on_press(msg(Action::Setup))));
            }

            1 => {
                dialog=dialog.body(if self.reset.busy{"Checking the current local library…".into()}else{format!("{count}\nOnly this computer is affected. Keep a checked backup or complete copy on another device. This never erases the phone remotely.")}).control(widget::column::with_capacity(2).spacing(12)
                .push(self.button("Start a new connection — keep documents").on_press_maybe((!self.reset.busy).then_some(msg(Action::Choose(Mode::Connection)))))
                .push(self.button("Erase this computer's library…").class(cosmic::theme::Button::Destructive).on_press_maybe((!self.reset.busy).then_some(msg(Action::Choose(Mode::Erase))))));
            }
            2 => {
                let erase = self.reset.mode == Some(Mode::Erase);
                dialog=dialog.title(if erase{"1 of 3 — Erase local library?"}else{"Start a new connection?"}).body(if erase{format!("{count}\nThis removes saved documents and originals, edits, typed details, OCR, local recovery data and sync history. Unsaved editor drafts are discarded when Stack closes. The phone and backup files outside this library are kept. You cannot undo this without another copy.")}else{"Saved documents remain. The old sync history, peer confirmations and enrollment are forgotten; this computer gets a fresh sync identity. Start a new connection on the phone too while keeping its documents, then pair and review the libraries again. Stack closes; reopen it to finish.".into()}).secondary_action(self.button(if erase{"Review recovery warning"}else{"Continue to confirmation"}).on_press(msg(Action::Next)));
            }
            3 if self.reset.mode == Some(Mode::Erase) => {
                dialog=dialog.title("2 of 3 — Check your recovery copy").body("Make and check a backup, or confirm that the phone has everything you need. Unsynced computer changes will be lost. Never erase both devices unless you have an independent backup. Sync cannot recover the only copy after both libraries are erased. Stack will close. Reopen the same library to finish, then choose Import backup or Set up a phone.").secondary_action(self.button("Continue to final confirmation").on_press(msg(Action::Next)));
            }
            _ => {
                let erase = self.reset.mode == Some(Mode::Erase);
                dialog=dialog.title(if erase{"3 of 3 — Final erase confirmation"}else{"Confirm new connection"}).body(format!("Type {} exactly. {} Stack closes after this action; the reset finishes when you reopen it.",self.reset_phrase(),if erase{"All documents in this local library will be removed."}else{"Saved documents are kept; the current sync connection is forgotten."}))
                .control(widget::text_input(self.reset_phrase(),&self.reset.phrase).on_input(|v|msg(Action::Typed(v))))
                .secondary_action(self.button(if erase{"Erase library and close Stack"}else{"Reset connection and close Stack"}).class(cosmic::theme::Button::Destructive).on_press_maybe((!self.reset.busy&&self.reset.phrase==self.reset_phrase()).then_some(msg(Action::Commit))));
            }
        }
        Some(dialog.into())
    }
}
