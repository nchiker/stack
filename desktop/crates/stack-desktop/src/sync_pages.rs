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
            if self
                .sync
                .status
                .as_ref()
                .is_some_and(|s| s["conflicts"].as_object().is_some_and(|c| !c.is_empty()))
            {
                body = body.push(self.sync_conflict_view());
            }
        }
        widget::scrollable(widget::container(body).padding(24).max_width(760))
            .id("stack-sync")
            .into()
    }
}
