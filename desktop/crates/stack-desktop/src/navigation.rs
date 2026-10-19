//! Keep library navigation and backup tools in one stable sidebar.
use crate::{
    Message, Stack, catalog,
    transfers_ui::{Action, Pick},
};
use cosmic::{Element, app, iced, widget};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Location {
    Documents,
    Stack(String),
    ManageStacks,
    Sync,
}

impl Stack {
    pub fn rebuild_navigation(&mut self) {
        self.nav.clear();
        let target = if self.catalog.open {
            Location::ManageStacks
        } else if self.sync.open {
            Location::Sync
        } else {
            self.filter
                .clone()
                .map(Location::Stack)
                .unwrap_or(Location::Documents)
        };
        let id = self
            .nav
            .insert()
            .text("All documents")
            .icon(
                widget::icon::from_name("folder-documents-symbolic")
                    .icon()
                    .size(20),
            )
            .data(Location::Documents)
            .id();
        self.nav.activate(id);
        for (index, stack) in self.stacks.iter().filter(|s| s.visible).enumerate() {
            let location = Location::Stack(stack.id.clone());
            let entry = self
                .nav
                .insert()
                .text(stack.name.clone())
                .divider_above(index == 0)
                .icon(catalog::icon(stack))
                .data(location.clone());
            if target == location {
                entry.activate();
            }
        }
        let entry = self
            .nav
            .insert()
            .text("Manage stacks")
            .divider_above(true)
            .data(Location::ManageStacks);
        if target == Location::ManageStacks {
            entry.activate();
        }
        let entry = self.nav.insert().text("Sync exchange").data(Location::Sync);
        if target == Location::Sync {
            entry.activate();
        }
    }

    pub fn library_location(&self) -> String {
        self.filter
            .as_ref()
            .and_then(|id| self.stacks.iter().find(|s| &s.id == id))
            .map(|s| s.name.clone())
            .unwrap_or_else(|| "All documents".into())
    }

    pub fn can_go_back(&self) -> bool {
        self.catalog.open
            || self.sync.open
            || self.editor_open
            || self.selected.is_some()
            || self.opening_document.is_some()
    }

    pub fn go_back(&mut self) -> app::Task<Message> {
        if self.sync.open {
            self.sync.open = false;
            self.rebuild_navigation();
            return self.refresh_browser();
        }
        if self.catalog.open {
            self.catalog.open = false;
            self.rebuild_navigation();
            let refresh = self.refresh_browser();
            return self.restore_list_position(refresh);
        }
        if self.editor_open {
            return self.editor_action(crate::editor::Action::Close);
        }
        if self.selected.is_some() || self.opening_document.is_some() {
            let browse = self.browse();
            return self.restore_list_position(browse);
        }
        app::Task::none()
    }

    fn restore_list_position(&self, refresh: app::Task<Message>) -> app::Task<Message> {
        app::Task::batch([
            refresh,
            iced::widget::scrollable::scroll_to(
                "stack-documents".into(),
                iced::widget::scrollable::AbsoluteOffset {
                    x: Some(0.),
                    y: Some(self.browse_scroll),
                },
            ),
        ])
    }

    pub fn library_menu(&self) -> Element<'_, Message> {
        let mut popup = widget::popover(
            self.button("Backup & restore")
                .on_press(Message::LibraryMenu(!self.library_menu_open)),
        )
        .position(widget::popover::Position::Top)
        // Outside clicks must dismiss this menu instead of trapping every other
        // control, including the reviewed-import action while a transfer is active.
        .modal(false)
        .on_close(Message::LibraryMenu(false));
        if self.library_menu_open {
            let actions = widget::column::with_capacity(2)
                .spacing(8)
                .push(
                    self.button("Import backup…").on_press_maybe(
                        (!self.transfers.busy())
                            .then_some(Message::Transfer(Action::Pick(Pick::Archive))),
                    ),
                )
                .push(
                    self.button("Export backup…").on_press_maybe(
                        (!self.transfers.busy())
                            .then_some(Message::Transfer(Action::Pick(Pick::ArchiveExport))),
                    ),
                );
            popup = popup.popup(
                widget::container(actions)
                    .padding(12)
                    .class(cosmic::theme::Container::Dropdown),
            );
        }
        popup.into()
    }

    pub fn search_bar(&self) -> Element<'_, Message> {
        let pick =
            (!self.transfers.busy()).then_some(Message::Transfer(Action::Pick(Pick::Documents)));
        let add: Element<'_, Message> = if self.narrow {
            widget::button::icon(widget::icon::from_name("list-add-symbolic"))
                .name("Add documents")
                .label("Add")
                .on_press_maybe(pick)
                .into()
        } else {
            widget::button::suggested("Add documents…")
                .on_press_maybe(pick)
                .into()
        };
        widget::row::with_capacity(3)
            .spacing(8)
            .push(
                widget::text_input(format!("Search {}", self.library_location()), &self.query)
                    .id(iced::widget::Id::new("stack-search"))
                    .on_input(Message::Query),
            )
            .push(
                widget::button::icon(widget::icon::from_name("edit-clear-symbolic"))
                    .name("Clear search")
                    .on_press_maybe(
                        (!self.query.is_empty()).then_some(Message::Query(String::new())),
                    ),
            )
            .push(add)
            .into()
    }
}
