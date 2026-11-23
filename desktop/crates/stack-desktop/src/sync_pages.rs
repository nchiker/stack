//! Settings → Sync is the only interactive entry point to synchronization.
use crate::{
    Message, Stack,
    sync_ui::{Action, Page},
};
use cosmic::{Element, widget};
impl Stack {
    pub fn sync_view(&self) -> Element<'_, Message> {
        let mut body = widget::column::with_capacity(10).spacing(20);
        if self.sync.page == Page::Settings {
            body = body.push(widget::text::heading("Settings")).push(
                self.button("Sync")
                    .on_press(Message::Sync(Action::Page(Page::Overview))),
            );
            body = body
                .push(widget::text::heading("Filen folder"))
                .push(self.body(match &self.ami.filen_root {
                    Some(root) => format!(
                        "Found at {root}. Linked documents in it open without asking."
                    ),
                    None => "Stack finds your Filen folder the first time you open a linked document in Ami Pro.".to_owned(),
                }));
            body = body.push(self.ami_install_settings());
            if self.ami.filen_root.is_some() {
                body = body.push(
                    self.button("Forget this folder")
                        .on_press(Message::Ami(crate::ami_open::Event::ForgetFolder)),
                );
            }
        } else {
            body = body.push(widget::text::heading("Sync"));
            if self.sync.network.status["enrolled"] == true {
                body = body.push(self.network_view());
            } else {
                body = body.push(self.nearby_view());
            }
            if let Some(error) = &self.sync.error {
                body = body.push(self.body(error.clone()));
            }
            // Linked-document conflicts are not reviewable here, but a plain note is shown for them.
            if self
                .sync
                .status
                .as_ref()
                .is_some_and(crate::sync_ui::shows_conflicts)
            {
                body = body.push(self.sync_conflict_view());
            }
        }
        widget::scrollable(widget::container(body).padding(24).max_width(760))
            .id("stack-sync")
            .into()
    }
}
