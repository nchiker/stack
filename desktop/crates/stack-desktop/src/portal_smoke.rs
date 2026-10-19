//! Real native portal responses; only opening actions and restore decisions are
//! test-injected. The external driver operates the installed chooser's controls.
use crate::{
    Message, Stack,
    transfers_ui::{Action, Phase, Pick},
};
use cosmic::app;
impl Stack {
    pub fn portal_scenario(&mut self) -> app::Task<Message> {
        let ready = !self.transfers.busy() && !self.busy && !self.library_loading;
        match self.scenario_step {
            0 if ready => {
                self.scenario_step = 1;
                self.transfer_action(Action::Pick(Pick::Documents))
            }
            1 if ready
                && self.hits.len() == 5
                && self
                    .transfers
                    .status
                    .as_ref()
                    .is_some_and(|s| s.contains("selection canceled")) =>
            {
                self.scenario_step = 2;
                self.transfer_action(Action::Pick(Pick::Documents))
            }
            2 if ready && self.hits.len() < 9 => {
                self.transfer_action(Action::Pick(Pick::Documents))
            }
            2 if ready && self.hits.len() == 9 => {
                self.scenario_step = 3;
                self.transfer_action(Action::Pick(Pick::ArchiveExport))
            }
            3 if ready
                && self
                    .transfers
                    .status
                    .as_ref()
                    .is_some_and(|s| s.starts_with("Archive published")) =>
            {
                self.scenario_step = 4;
                self.transfer_action(Action::Pick(Pick::Archive))
            }
            4 if self.portal_review_ready() => {
                self.scenario_step = 5;
                self.transfer_action(Action::Cancel)
            }
            5 if ready && self.hits.len() == 9 => {
                self.scenario_step = 6;
                self.transfer_action(Action::Pick(Pick::Archive))
            }
            6 if self.portal_review_ready() => {
                self.scenario_step = 7;
                self.transfer_action(Action::Commit)
            }
            7 if ready && self.hits.len() == 18 => {
                self.scenario_step = 8;
                cosmic::task::future(async {
                    Message::OpenDocument(stack_archive::synthetic::id(1))
                })
            }
            8 if ready && self.selected.is_some() => {
                self.scenario_step = 9;
                let doc = self.selected.as_ref().expect("selected");
                self.transfer_action(Action::Pick(Pick::Pdf {
                    token: doc.token.clone(),
                    title: doc.title.clone(),
                }))
            }
            9 if ready
                && self
                    .transfers
                    .status
                    .as_ref()
                    .is_some_and(|s| s.starts_with("PDF published")) =>
            {
                self.flags
                    .completed
                    .store(true, std::sync::atomic::Ordering::SeqCst);
                cosmic::task::future(async { Message::Close })
            }
            _ => app::Task::none(),
        }
    }
    fn portal_review_ready(&self) -> bool {
        self.transfers.active.as_ref().is_some_and(|a| {
            a.phase == Phase::Review && a.review.as_ref().is_some_and(|r| r.documents_added == 9)
        })
    }
}
