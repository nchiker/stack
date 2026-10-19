//! Authored-only native integrated scenario. Picker responses and commands are
//! injected through product handlers. Native dialogs and physical input remain
//! separate gates. Entry requires the exact M1 synthetic saved graph/originals.
use crate::{
    Message, Stack, editor,
    transfers_ui::{Action, Phase, Pick},
};
use cosmic::{app, widget};
use std::sync::Arc;

impl Stack {
    pub fn workflow_scenario(&mut self) -> app::Task<Message> {
        let send = |message| cosmic::task::future(async move { message });
        let input = self
            .flags
            .browser_fixtures
            .as_ref()
            .expect("verified fixtures");
        let output = self.flags.smoke.as_ref().expect("synthetic output").clone();
        let ready = !self.transfers.busy() && !self.busy && !self.library_loading && !self.saving;
        let selected = self.selected.is_some();
        let step = self.scenario_step;
        match step {
            0 if ready => {
                self.scenario_step = 1;
                send(Message::Picked(
                    Pick::Documents,
                    Ok(Some(
                        stack_render::fixtures::NAMES
                            .iter()
                            .map(|name| input.join(name))
                            .collect(),
                    )),
                ))
            }
            1 if ready && self.hits.len() == 9 => {
                self.scenario_step = 2;
                send(Message::Picked(
                    Pick::Archive,
                    Ok(Some(vec![output.join("reference/format-3.stackbackup")])),
                ))
            }
            2 if self
                .transfers
                .active
                .as_ref()
                .is_some_and(|a| a.phase == Phase::Review && a.review.is_some()) =>
            {
                self.scenario_step = 3;
                self.delayed_capture()
            }
            3 => {
                self.scenario_step = 4;
                send(Message::Transfer(Action::Cancel))
            }
            4 if ready && self.hits.len() == 9 => {
                self.scenario_step = 5;
                send(Message::OpenDocument(stack_archive::synthetic::id(1)))
            }
            5 if ready && selected => {
                self.scenario_step = 6;
                let _ = self.editor_action(editor::Action::Open);
                let _ =
                    self.editor_action(editor::Action::Title(crate::editor_smoke::TITLE.into()));
                self.editor_action(editor::Action::SaveTitle)
            }
            6 if ready && selected => {
                self.scenario_step = 7;
                let _ = self.editor_action(editor::Action::Notes(
                    widget::text_editor::Action::SelectAll,
                ));
                let _ = self.editor_action(editor::Action::Notes(
                    widget::text_editor::Action::Edit(widget::text_editor::Edit::Paste(Arc::new(
                        crate::editor_smoke::NOTES.into(),
                    ))),
                ));
                self.editor_action(editor::Action::SaveNotes)
            }
            7 if ready && selected => {
                self.scenario_step = 8;
                let _ = self.editor_action(editor::Action::Filing("receipts".into(), true));
                let _ = self.editor_action(editor::Action::Filing("custom-last".into(), true));
                self.editor_action(editor::Action::SaveFiling)
            }
            8 if ready && selected => {
                self.scenario_step = 9;
                let _ = self.editor_action(editor::Action::SelectDetail("total".into()));
                let _ = self.editor_action(editor::Action::Value("-00024.500100".into()));
                let _ = self.editor_action(editor::Action::Qualifier("unknown".into()));
                self.editor_action(editor::Action::SaveDetail)
            }
            9 if ready && selected => {
                self.scenario_step = 10;
                self.delayed_capture()
            }
            10 => {
                self.scenario_step = 11;
                send(Message::Query("laterpagesentinel".into()))
            }
            11 if ready && self.hits.len() == 2 => {
                self.scenario_step = 12;
                send(Message::OpenDocument(stack_archive::synthetic::id(1)))
            }
            12 if ready && selected && self.page == 1 => {
                self.scenario_step = 13;
                let _ = self.editor_action(editor::Action::Close);
                self.delayed_capture()
            }
            13 => {
                self.scenario_step = 14;
                self.workflow_pdf(output.join("original.pdf"))
            }
            14 if ready && selected => {
                self.scenario_step = 15;
                let _ = self.editor_action(editor::Action::Open);
                self.editor_action(editor::Action::Rotate)
            }
            15 if ready && selected => {
                self.scenario_step = 16;
                self.editor_action(editor::Action::MovePage(-1))
            }
            16 if ready && selected => {
                self.scenario_step = 17;
                let _ = self.editor_action(editor::Action::RemovePage);
                self.editor_action(editor::Action::ConfirmRemovePage)
            }
            17 if ready && selected => {
                self.scenario_step = 18;
                let _ = self.editor_action(editor::Action::Close);
                self.workflow_pdf(output.join("saved.pdf"))
            }
            18 if ready => {
                self.scenario_step = 19;
                send(Message::Picked(
                    Pick::ArchiveExport,
                    Ok(Some(vec![output.join("library.stackbackup")])),
                ))
            }
            19 if ready => {
                self.scenario_step = 20;
                self.delayed_capture()
            }
            20 => {
                self.flags
                    .completed
                    .store(true, std::sync::atomic::Ordering::SeqCst);
                send(Message::Close)
            }
            _ => app::Task::none(),
        }
    }
    fn workflow_pdf(&self, output: std::path::PathBuf) -> app::Task<Message> {
        let doc = self.selected.as_ref().expect("saved selection");
        let pick = Pick::Pdf {
            token: doc.token.clone(),
            title: doc.title.clone(),
        };
        cosmic::task::future(async move { Message::Picked(pick, Ok(Some(vec![output]))) })
    }
}
