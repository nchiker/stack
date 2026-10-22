//! Native document workspace following the user's HTML design reference.
use crate::{Message, RESULTS_PER_PAGE, Stack, editor, transfers_ui};
use cosmic::{Element, iced, widget};
use stack_core::{SearchHit, Sort};

impl Stack {
    pub fn library_sidebar(&self) -> Option<Element<'_, cosmic::Action<Message>>> {
        if !self.core.nav_bar_active() {
            return None;
        }
        let mut nav = widget::column::with_capacity(self.stacks.len() + 4).spacing(4);
        for id in self.nav.iter() {
            let label = self.nav.text(id).unwrap_or_default();
            if self.nav.data::<crate::navigation::Location>(id)
                == Some(&crate::navigation::Location::ManageStacks)
            {
                nav = nav.push(widget::divider::horizontal::default());
            }
            let row = widget::row::with_capacity(2)
                .spacing(10)
                .align_y(iced::Alignment::Center)
                .push_maybe(self.nav.icon(id).cloned())
                .push(widget::text::body(label).width(iced::Length::Fill));
            nav = nav.push(
                widget::button::custom(row)
                    .name(label)
                    .padding([6, 8])
                    .width(iced::Length::Fill)
                    .class(cosmic::theme::Button::ListItem([6.; 4]))
                    .selected(self.nav.is_active(id))
                    .on_press(cosmic::Action::Cosmic(cosmic::app::Action::NavBar(id))),
            );
        }
        Some(
            widget::container(
                widget::column::with_capacity(4)
                    .push(widget::scrollable(nav).height(iced::Length::Fill))
                    .push(widget::divider::horizontal::default())
                    .push(
                        widget::container(
                            widget::button::custom(
                                widget::row::with_capacity(2)
                                    .spacing(10)
                                    .push(widget::icon::from_name("phone-symbolic").icon().size(20))
                                    .push(widget::text::body("Settings")),
                            )
                            .name("Settings")
                            .width(iced::Length::Fill)
                            .class(cosmic::theme::Button::ListItem([6.; 4]))
                            .selected(self.sync.open)
                            .on_press(cosmic::Action::App(
                                Message::Sync(crate::sync_ui::Action::Settings),
                            )),
                        )
                        .padding([0, 8]),
                    )
                    .push(
                        widget::container(self.library_menu().map(cosmic::Action::App)).padding(8),
                    )
                    .spacing(8),
            )
            .class(cosmic::theme::Container::custom(|theme| {
                flat_surface(theme, false)
            }))
            .width(180)
            .height(iced::Length::Fill)
            .clip(true)
            .into(),
        )
    }

    pub fn library_footer(&self) -> Option<Element<'_, Message>> {
        self.repository.as_ref()?;
        let status = if self.saving {
            "Saving…".to_owned()
        } else if self.selected.as_ref().is_some_and(|document| {
            self.drafts
                .get(&document.token.id)
                .is_some_and(|draft| draft.has_changes(document))
        }) {
            "Unsaved changes · use Edit document to review and save".to_owned()
        } else if let Some(document) = &self.selected {
            let count = document.pages.len();
            format!(
                "{count} page{} · Stored on this device",
                if count == 1 { "" } else { "s" }
            )
        } else {
            let count = self.hits.len();
            format!(
                "{count} document{} · Stored on this device",
                if count == 1 { "" } else { "s" }
            )
        };
        Some(
            widget::container(widget::text::caption(status))
                .padding([6, 16])
                .into(),
        )
    }

    pub fn workspace_view(&self) -> Element<'_, Message> {
        if self.sync.open {
            return self.sync_view();
        }
        if self.catalog.open {
            return self.catalog_view();
        }
        if self.selected.is_some() {
            return self.document_workspace();
        }
        if self.opening_document.is_some() {
            return widget::container(self.body("Loading document…"))
                .padding(24)
                .height(iced::Length::Fill)
                .into();
        }
        self.document_browser()
    }

    fn document_browser(&self) -> Element<'_, Message> {
        let heading = if self.query.is_empty() {
            self.library_location()
        } else {
            format!("Search results · {}", self.library_location())
        };
        let views = widget::row::with_capacity(2)
            .push(self.layout_button("Grid view", "view-grid-symbolic", true))
            .push(self.layout_button("List view", "view-list-symbolic", false))
            .spacing(4);
        let choices = widget::row::with_capacity(2)
            .push(widget::dropdown(
                vec!["Newest", "Name"],
                Some(usize::from(matches!(self.sort, Some(Sort::Name)))),
                |i| Message::SortByName(i == 1),
            ))
            .push(views)
            .align_y(iced::Alignment::Center)
            .spacing(8)
            .width(iced::Length::Shrink);
        let toolbar: Element<'_, Message> = if self.narrow {
            widget::column::with_capacity(2)
                .push(widget::text::heading(heading).size(22))
                .push(choices)
                .spacing(8)
                .into()
        } else {
            widget::row::with_capacity(2)
                .push(
                    widget::text::heading(heading)
                        .size(22)
                        .width(iced::Length::Fill),
                )
                .push(choices)
                .align_y(iced::Alignment::Center)
                .spacing(16)
                .into()
        };
        let mut documents = widget::column::with_capacity(16).spacing(0);
        if let Some(error) = &self.library_error {
            documents = documents.push(self.body(error.as_str())).push(
                self.button("Retry library")
                    .on_press(Message::RefreshBrowser),
            );
        }
        if self.library_loading {
            documents = documents.push(self.body("Loading library…"));
        } else if self.hits.is_empty() && self.library_error.is_none() {
            documents = documents.push(self.body(if self.query.is_empty() {
                "No documents here yet. Use Add documents to get started."
            } else {
                "No documents match your search."
            }));
        }
        let visible: Vec<_> = self
            .hits
            .iter()
            .skip(self.browse_offset)
            .take(RESULTS_PER_PAGE)
            .collect();
        let (columns, row_height, start_row, rows) = self.visible_tile_rows();
        let row_count = visible.len().div_ceil(columns);
        let first = start_row.min(row_count);
        let end = (first + rows).min(row_count);
        if first > 0 {
            documents = documents.push(widget::space::vertical().height(first as f32 * row_height));
        }
        if self.grid_view {
            for group in visible.chunks(columns).skip(first).take(end - first) {
                let mut row = widget::row::with_capacity(columns).spacing(12);
                for hit in group {
                    row = row.push(self.document_card(hit, true));
                }
                for _ in group.len()..columns {
                    row = row.push(widget::space::horizontal());
                }
                documents = documents.push(
                    widget::container(row)
                        .height(row_height)
                        .padding([0, 0, 12, 0]),
                );
            }
        } else {
            for hit in visible.into_iter().skip(first).take(end - first) {
                documents = documents.push(
                    widget::container(self.document_card(hit, false))
                        .height(row_height)
                        .padding([0, 0, 12, 0]),
                );
            }
        }
        if end < row_count {
            documents = documents
                .push(widget::space::vertical().height((row_count - end) as f32 * row_height));
        }
        widget::container(
            widget::column::with_capacity(3)
                .push(toolbar)
                .push_maybe((self.hits.len() > RESULTS_PER_PAGE).then(|| {
                    widget::column::with_capacity(3)
                        .spacing(8)
                        .push(self.button("Previous results").on_press_maybe(
                            (self.browse_offset > 0).then_some(Message::ResultsPage(-1)),
                        ))
                        .push(self.body(format!(
                            "{}–{} of {}",
                            self.browse_offset + 1,
                            (self.browse_offset + RESULTS_PER_PAGE).min(self.hits.len()),
                            self.hits.len()
                        )))
                        .push(
                            self.button("Next results").on_press_maybe(
                                (self.browse_offset + RESULTS_PER_PAGE < self.hits.len())
                                    .then_some(Message::ResultsPage(1)),
                            ),
                        )
                }))
                .push(
                    widget::scrollable(documents)
                        .id("stack-documents")
                        .on_scroll(|viewport| {
                            Message::BrowserScrolled(viewport.absolute_offset().y)
                        })
                        .height(iced::Length::Fill),
                )
                .spacing(14)
                .height(iced::Length::Fill),
        )
        .padding([18, 20])
        .into()
    }

    fn layout_button(
        &self,
        label: &'static str,
        icon: &'static str,
        grid: bool,
    ) -> Element<'_, Message> {
        widget::button::icon(widget::icon::from_name(icon))
            .name(label)
            .selected(self.grid_view == grid)
            .class(if self.grid_view == grid {
                cosmic::theme::Button::Suggested
            } else {
                cosmic::theme::Button::Text
            })
            .on_press(Message::GridView(grid))
            .into()
    }

    fn document_card<'a>(&'a self, hit: &'a SearchHit, grid: bool) -> Element<'a, Message> {
        let marker: Element<'_, Message> =
            if let Some(image) = self.tiles.images.get(&hit.document_id) {
                widget::image(image.clone())
                    .content_fit(iced::ContentFit::Contain)
                    .width(iced::Length::Fill)
                    .height(iced::Length::Fill)
                    .into()
            } else {
                widget::container(
                    widget::icon::from_name("text-x-generic-symbolic")
                        .icon()
                        .size(28),
                )
                .center_x(iced::Length::Fill)
                .center_y(iced::Length::Fill)
                .into()
            };
        let thumb = widget::container(marker)
            .padding(if grid { 8 } else { 3 })
            .width(if grid {
                iced::Length::Fill
            } else {
                iced::Length::Fixed(50.)
            })
            .height(if grid { 164 } else { 64 })
            .class(cosmic::theme::Container::custom(|theme| {
                flat_surface(theme, true)
            }));
        let mut text = widget::column::with_capacity(2).spacing(4).push(
            widget::text::body(&hit.title)
                .size(13)
                .height(32)
                .ellipsize(iced::advanced::text::Ellipsize::End(
                    iced::advanced::text::EllipsizeHeightLimit::Lines(2),
                )),
        );
        if let Some(tile) = self.tiles.metadata.get(&hit.document_id) {
            let names: Vec<_> = tile
                .memberships
                .iter()
                .filter_map(|id| self.stacks.iter().find(|s| &s.id == id))
                .map(|s| s.name.clone())
                .take(1)
                .collect();
            text = text.push(
                widget::row::with_capacity(2)
                    .spacing(6)
                    .align_y(iced::Alignment::Center)
                    .push(widget::text::caption(crate::thumbnails::added_date(
                        tile.added_at,
                    )))
                    .push(self.membership_chips(&names)),
            );
        }
        if !self.query.is_empty() && !hit.snippet.is_empty() {
            text = text.push(widget::text::caption(
                hit.snippet.chars().take(160).collect::<String>(),
            ));
        }
        let content: Element<'_, Message> = if grid {
            widget::column::with_capacity(2)
                .push(thumb)
                .push(text)
                .spacing(6)
                .into()
        } else {
            widget::row::with_capacity(2)
                .push(thumb)
                .push(text)
                .align_y(iced::Alignment::Center)
                .spacing(12)
                .into()
        };
        widget::button::custom(content)
            .name(hit.title.clone())
            .width(iced::Length::Fill)
            .height(if grid { 228. } else { 68. })
            .padding(0)
            .class(cosmic::theme::Button::ListItem([6.; 4]))
            .on_press(Message::OpenDocument(hit.document_id.clone()))
            .into()
    }

    fn document_workspace(&self) -> Element<'_, Message> {
        let document = self.selected.as_ref().expect("selected document");
        let pages = widget::row::with_capacity(3)
            .spacing(8)
            .push(
                self.button("Previous")
                    .on_press_maybe((self.page > 0).then_some(Message::Previous)),
            )
            .push(self.body(format!("Page {} of {}", self.page + 1, self.page_count)))
            .push(
                self.button("Next")
                    .on_press_maybe((self.page + 1 < self.page_count).then_some(Message::Next)),
            )
            .align_y(iced::Alignment::Center);
        let mut actions = widget::row::with_capacity(2).spacing(8);
        if self.narrow {
            actions = actions.push(
                self.button(if self.information_visible {
                    "Show preview"
                } else {
                    "Document information"
                })
                .on_press(Message::Information(!self.information_visible)),
            );
        }
        actions = actions.push(self.button("Export PDF…").on_press_maybe(
            (!self.saving && !self.transfers.busy()).then_some(Message::Transfer(
                transfers_ui::Action::Pick(transfers_ui::Pick::Pdf {
                    token: document.token.clone(),
                    title: document.title.clone(),
                }),
            )),
        ));
        let toolbar: Element<'_, Message> = if self.narrow {
            widget::column::with_capacity(2)
                .push(pages)
                .push(actions)
                .spacing(8)
                .into()
        } else {
            widget::row::with_capacity(2)
                .push(pages)
                .push(actions)
                .spacing(16)
                .into()
        };
        let info = if self.editor_open {
            self.editor_view()
        } else {
            self.document_information()
        };
        let body: Element<'_, Message> = if self.narrow {
            if self.information_visible {
                widget::column::with_capacity(3)
                    .push(
                        widget::container(self.preview_view()).height(iced::Length::FillPortion(1)),
                    )
                    .push(widget::divider::horizontal::default())
                    .push(widget::container(info).height(iced::Length::FillPortion(1)))
                    .height(iced::Length::Fill)
                    .into()
            } else {
                self.preview_view()
            }
        } else {
            widget::row::with_capacity(2)
                .push(widget::container(self.preview_view()).width(iced::Length::FillPortion(3)))
                .push(widget::divider::vertical::default())
                .push(widget::container(info).width(280))
                .spacing(0)
                .height(iced::Length::Fill)
                .into()
        };
        widget::container(
            widget::column::with_capacity(3)
                .push(widget::container(toolbar).padding([8, 16]))
                .push(widget::divider::horizontal::default())
                .push(body)
                .spacing(0)
                .height(iced::Length::Fill),
        )
        .padding(0)
        .into()
    }

    fn preview_view(&self) -> Element<'_, Message> {
        let preview: Element<'_, Message> = if self.busy {
            self.body("Loading preview…")
        } else if let Some(error) = &self.error {
            widget::column::with_capacity(2)
                .spacing(12)
                .push(self.body(error.as_str()))
                .push(self.button("Retry preview").on_press(Message::Retry))
                .into()
        } else if let Some(handle) = &self.preview {
            widget::image::viewer(handle.clone())
                .content_fit(iced::ContentFit::Contain)
                .padding(0)
                .width(iced::Length::Fill)
                .height(iced::Length::Fill)
                .into()
        } else {
            self.body("Preview unavailable.")
        };
        widget::container(preview)
            .padding([16, 20])
            .width(iced::Length::Fill)
            .height(iced::Length::Fill)
            .class(cosmic::theme::Container::custom(|theme| {
                flat_surface(theme, false)
            }))
            .into()
    }

    fn document_information(&self) -> Element<'_, Message> {
        let document = self.selected.as_ref().expect("selected document");
        let mut info = widget::column::with_capacity(12)
            .spacing(12)
            .push(widget::text::heading("Document information"))
            .push(
                self.button("Edit document")
                    .font_size(12)
                    .height(26.)
                    .on_press_maybe(
                        (!self.saving).then_some(Message::Editor(editor::Action::Open)),
                    ),
            )
            .push(widget::text::caption("Name"))
            .push(self.body(document.title.clone()))
            .push(widget::text::caption("Stacks"));
        let names: Vec<_> = document
            .memberships
            .iter()
            .filter_map(|id| {
                self.stacks
                    .iter()
                    .find(|s| &s.id == id)
                    .map(|s| s.name.as_str())
            })
            .collect();
        let names: Vec<String> = names.iter().map(|s| s.to_string()).collect();
        info = info.push(self.membership_chips(&names));
        info = info.push(widget::text::caption("Saved details"));
        let saved: Vec<_> = document
            .details
            .iter()
            .filter(|d| d["state"] == "saved")
            .collect();
        if saved.is_empty() {
            info = info.push(self.body("No saved details"));
        }
        for detail in saved {
            info = info
                .push(widget::text::caption(
                    detail["label"].as_str().unwrap_or_default(),
                ))
                .push(self.body(format!(
                    "{} {}",
                    detail["value"].as_str().unwrap_or_default(),
                    detail["qualifier"].as_str().unwrap_or_default()
                )));
        }
        if document.detail_count > document.details.len() as u32 {
            info = info.push(self.body("Open Details in the editor to see more."));
        }
        info = info.push(widget::text::caption("Notes")).push(
            widget::container(self.body(if document.notes.is_empty() {
                "No notes"
            } else {
                &document.notes
            }))
            .padding(8)
            .width(iced::Length::Fill)
            .class(cosmic::theme::Container::custom(|theme| {
                flat_surface(theme, true)
            })),
        );
        info = info.push(widget::text::caption("Document text"));
        if self.text.status_document.as_deref() == Some(&document.token.id) {
            for (position, status) in self.text.statuses.iter().enumerate() {
                let state = match status.state.as_str() {
                    "queued" => "Queued",
                    "reading" => "Reading offline…",
                    "ready" => {
                        if status.method == "embedded" {
                            "Ready · PDF text"
                        } else {
                            "Ready · recognized text"
                        }
                    }
                    "no_text" => "No text found",
                    "failed" => "Could not read · Retry available",
                    "cancelled" => "Cancelled",
                    _ => "Waiting",
                };
                info = info.push(widget::text::caption(format!(
                    "Page {}: {state}",
                    position + 1
                )));
            }
        } else {
            info = info.push(self.body(format!(
                "Searchable text on {} of {} pages · checking offline queue…",
                document.searchable_pages,
                document.pages.len()
            )));
        }
        info = info
            .push(self.button("Retry text reading").on_press(Message::Text(
                crate::text_ui::Event::Retry(document.token.id.clone()),
            )))
            .push(self.button("Cancel text reading").on_press(Message::Text(
                crate::text_ui::Event::Cancel(document.token.id.clone()),
            )));
        widget::container(widget::scrollable(info).height(iced::Length::Fill))
            .padding(16)
            .width(iced::Length::Fill)
            .class(cosmic::theme::Container::Transparent)
            .height(iced::Length::Fill)
            .into()
    }

    pub fn membership_chips(&self, names: &[String]) -> Element<'_, Message> {
        if names.is_empty() {
            return widget::text::caption("No stacks").into();
        }
        let mut chips = widget::column::with_capacity(names.len().div_ceil(2)).spacing(4);
        for pair in names.chunks(2) {
            let mut row = widget::row::with_capacity(2).spacing(4);
            for name in pair {
                row = row.push(
                    widget::container(
                        widget::row::with_capacity(2)
                            .spacing(4)
                            .push_maybe(
                                self.stacks
                                    .iter()
                                    .find(|s| s.name == *name)
                                    .map(|s| crate::catalog::icon(s).size(12)),
                            )
                            .push(widget::text::caption(name.clone()).size(11)),
                    )
                    .padding([2, 6])
                    .class(cosmic::theme::Container::custom(|theme| {
                        let mut style = flat_surface(theme, false);
                        style.border.radius = 4.into();
                        style
                    })),
                );
            }
            chips = chips.push(row);
        }
        chips.into()
    }
}

fn flat_surface(theme: &cosmic::Theme, border: bool) -> iced::widget::container::Style {
    iced::widget::container::Style {
        background: Some(theme.cosmic().primary(theme.transparent).base.into()),
        border: iced::Border {
            color: theme.cosmic().bg_divider().into(),
            width: if border { 1. } else { 0. },
            radius: if border { 6.into() } else { 0.into() },
        },
        ..Default::default()
    }
}
