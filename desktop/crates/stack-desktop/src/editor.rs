use crate::{
    Message, Stack,
    repository::{Request, Response},
};
use cosmic::{Element, app, iced, widget};
use serde_json::{Value, json};
use stack_core::{DocumentEdit, DocumentView, library::DocumentToken};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Clone, Debug)]
pub enum Action {
    Open,
    Close,
    Title(String),
    Notes(widget::text_editor::Action),
    Filing(String, bool),
    SaveTitle,
    SaveNotes,
    SaveFiling,
    NewDetail,
    ToggleHistory,
    Panel(Panel),
    ToggleFiling,
    DetailsPage(i32),
    SelectDetail(String),
    Kind(usize),
    Label(String),
    Value(String),
    Qualifier(String),
    SaveDetail,
    RemoveDetail(String),
    Refresh,
    UseLoadedVersion,
    Rotate,
    MovePage(i32),
    RemovePage,
    ConfirmRemovePage,
    Delete,
    ConfirmDelete,
    CancelDelete,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Panel {
    #[default]
    Information,
    Details,
    Pages,
}
#[derive(Clone, Copy, Debug)]
pub enum Saved {
    Title,
    Notes,
    Filing,
    Detail,
    Page,
    Delete,
}

pub struct Draft {
    pub panel: Panel,
    pub token: DocumentToken,
    pub detail_offset: u32,
    pub title: String,
    pub notes: widget::text_editor::Content,
    pub filing: BTreeSet<String>,
    pub detail: Value,
    detail_baseline: Value,
    detail_drafts: BTreeMap<String, (Value, Value)>,
    title_baseline: String,
    notes_baseline: String,
    filing_baseline: BTreeSet<String>,
    pub status: Option<String>,
    pub confirm_delete: bool,
    pub history_open: bool,
    pub confirm_remove: bool,
    pub filing_open: bool,
}
impl Draft {
    pub fn has_changes(&self, _document: &DocumentView) -> bool {
        self.has_unsaved_changes()
    }
    pub fn has_unsaved_changes(&self) -> bool {
        self.title != self.title_baseline
            || self.notes.text() != self.notes_baseline
            || self.filing != self.filing_baseline
            || self.detail != self.detail_baseline
            || !self.detail_drafts.is_empty()
    }
    fn switch_detail(&mut self, value: Value) {
        let field = value["field_id"].as_str().unwrap_or_default().to_owned();
        if self.detail["field_id"] == field {
            return;
        }
        if self.detail != self.detail_baseline {
            self.detail_drafts.insert(
                self.detail["field_id"]
                    .as_str()
                    .unwrap_or_default()
                    .to_owned(),
                (self.detail.clone(), self.detail_baseline.clone()),
            );
        }
        let (detail, baseline) = self
            .detail_drafts
            .remove(&field)
            .unwrap_or_else(|| (value.clone(), value));
        self.detail = detail;
        self.detail_baseline = baseline;
        self.status = None;
    }
    pub fn new(document: &DocumentView) -> Self {
        let detail = new_detail(&document.token.id);
        Self {
            title_baseline: document.title.clone(),
            notes_baseline: document.notes.clone(),
            filing_baseline: document.memberships.iter().cloned().collect(),
            detail_baseline: detail.clone(),
            detail_drafts: BTreeMap::new(),
            panel: Panel::Information,
            token: document.token.clone(),
            detail_offset: document.detail_offset,
            title: document.title.clone(),
            notes: widget::text_editor::Content::with_text(&document.notes),
            filing: document.memberships.iter().cloned().collect(),
            detail,
            status: None,
            confirm_delete: false,
            history_open: false,
            confirm_remove: false,
            filing_open: false,
        }
    }
    pub fn failed(&mut self, error: &str) {
        self.status = Some(format!(
            "Could not save: {error}. Your draft is retained. Refresh saved values, review them, then choose Use loaded version to retry."
        ));
    }
    pub fn saved(&mut self, document: &DocumentView, field: Saved) {
        self.token = document.token.clone();
        match field {
            Saved::Title => {
                self.title = document.title.clone();
                self.title_baseline = self.title.clone();
            }
            Saved::Notes => {
                self.notes = widget::text_editor::Content::with_text(&document.notes);
                self.notes_baseline = document.notes.clone();
            }
            Saved::Filing => {
                self.filing = document.memberships.iter().cloned().collect();
                self.filing_baseline = self.filing.clone();
            }
            Saved::Detail => {
                if let Some(row) = document
                    .details
                    .iter()
                    .find(|d| d["field_id"] == self.detail["field_id"])
                {
                    self.detail = row.clone();
                    self.detail_baseline = row.clone();
                }
            }
            _ => {}
        }
        self.status = Some(
            match field {
                Saved::Title => "Title saved",
                Saved::Notes => "Notes saved",
                Saved::Filing => "Stacks saved",
                Saved::Detail => "Detail saved",
                Saved::Page => "Page saved",
                Saved::Delete => "Document deleted",
            }
            .into(),
        );
        self.confirm_delete = false;
        self.confirm_remove = false;
    }
}
fn new_detail(document: &str) -> Value {
    json!({"document_id":document,"field_id":format!("detail:{}",uuid::Uuid::new_v4()),"kind":"text","label":"","value":"","qualifier":"","origin":"manual","state":"saved","evidence":"{}","revision":1})
}
fn msg(action: Action) -> Message {
    Message::Editor(action)
}

impl Stack {
    pub fn editor_action(&mut self, action: Action) -> app::Task<Message> {
        let Some(document) = self.selected.as_ref() else {
            return app::Task::none();
        };
        let id = document.token.id.clone();
        if matches!(action, Action::Open) {
            self.drafts
                .entry(id)
                .or_insert_with(|| Draft::new(document));
            self.editor_open = true;
            self.information_visible = true;
            return app::Task::none();
        }
        if matches!(action, Action::Close) {
            self.editor_open = false;
            return app::Task::none();
        }
        if self.saving {
            return app::Task::none();
        }
        let Some(draft) = self.drafts.get_mut(&id) else {
            return app::Task::none();
        };
        let mut field = Saved::Detail;
        let edit = match action {
            Action::ToggleFiling => {
                draft.filing_open = !draft.filing_open;
                None
            }
            Action::Panel(panel) => {
                draft.panel = panel;
                None
            }
            Action::Title(title) => {
                draft.title = title;
                draft.status = None;
                None
            }
            Action::Notes(action) => {
                if action.is_edit() {
                    draft.status = None;
                }
                draft.notes.perform(action);
                None
            }
            Action::Filing(stack, chosen) => {
                if chosen {
                    draft.filing.insert(stack);
                } else {
                    draft.filing.remove(&stack);
                }
                draft.status = None;
                None
            }
            Action::DetailsPage(delta) => {
                draft.detail_offset =
                    (draft.detail_offset as i32 + delta * 200).clamp(0, 20_000) as u32;
                return self.select_document(id, self.page as usize);
            }
            Action::ToggleHistory => {
                draft.history_open = !draft.history_open;
                None
            }
            Action::NewDetail => {
                draft.switch_detail(new_detail(&id));
                None
            }
            Action::SelectDetail(field) => {
                let row = draft
                    .detail_drafts
                    .get(&field)
                    .map(|(value, _)| value)
                    .or_else(|| document.details.iter().find(|d| d["field_id"] == field))
                    .cloned();
                if let Some(row) = row {
                    draft.switch_detail(row);
                }
                None
            }
            Action::Kind(index) => {
                let kinds = ["text", "amount", "date", "organization"];
                if let Some(kind) = kinds.get(index) {
                    draft.detail["kind"] = json!(kind);
                    draft.detail["qualifier"] = json!(match *kind {
                        "amount" => "unknown",
                        "date" => "day",
                        _ => "",
                    });
                }
                draft.status = None;
                None
            }
            Action::Label(value) => {
                draft.detail["label"] = json!(value);
                draft.status = None;
                None
            }
            Action::Value(value) => {
                draft.detail["value"] = json!(value);
                draft.status = None;
                None
            }
            Action::Qualifier(value) => {
                draft.detail["qualifier"] = json!(value);
                draft.status = None;
                None
            }
            Action::SaveTitle => {
                field = Saved::Title;
                Some(DocumentEdit::Rename(draft.title.clone()))
            }
            Action::SaveNotes => {
                field = Saved::Notes;
                Some(DocumentEdit::Notes(draft.notes.text()))
            }
            Action::SaveFiling => {
                field = Saved::Filing;
                Some(DocumentEdit::Filing(draft.filing.iter().cloned().collect()))
            }
            Action::SaveDetail => {
                let mut row = draft.detail.clone();
                row["state"] = json!("saved");
                Some(DocumentEdit::SetDetail(row))
            }
            Action::RemoveDetail(field) => Some(DocumentEdit::RemoveDetail(field)),
            Action::Refresh => return self.select_document(id, self.page as usize),
            Action::UseLoadedVersion => {
                draft.token = document.token.clone();
                draft.status =
                    Some("Loaded saved version selected for retry. Your draft is retained.".into());
                None
            }
            Action::Rotate => {
                let Some(page) = document.pages.get(self.page as usize) else {
                    return app::Task::none();
                };
                field = Saved::Page;
                Some(DocumentEdit::PageAppearance {
                    page: page.id.clone(),
                    rotation: (page.rotation + 90) % 360,
                    crop: page.crop.clone(),
                    enhancement: page.enhancement.clone(),
                    flatten: page.flatten,
                })
            }
            Action::MovePage(delta) => {
                let mut order: Vec<_> = document.pages.iter().map(|p| p.id.clone()).collect();
                let index = self.page as usize;
                let target = index as i32 + delta;
                if target < 0 || target >= order.len() as i32 {
                    return app::Task::none();
                }
                order.swap(index, target as usize);
                field = Saved::Page;
                Some(DocumentEdit::PageOrder(order))
            }
            Action::RemovePage => {
                draft.confirm_remove = true;
                None
            }
            Action::ConfirmRemovePage if draft.confirm_remove && document.pages.len() > 1 => {
                let mut order: Vec<_> = document.pages.iter().map(|p| p.id.clone()).collect();
                order.remove(self.page as usize);
                field = Saved::Page;
                Some(DocumentEdit::PageOrder(order))
            }
            Action::Delete => {
                draft.confirm_delete = true;
                None
            }
            Action::ConfirmDelete if draft.confirm_delete => {
                return self.document_save(id, Saved::Delete, None);
            }
            Action::CancelDelete => {
                draft.confirm_delete = false;
                draft.confirm_remove = false;
                None
            }
            _ => None,
        };
        if let Some(edit) = edit {
            return self.document_save(id, field, Some(edit));
        }
        app::Task::none()
    }

    fn document_save(
        &mut self,
        id: String,
        field: Saved,
        edit: Option<DocumentEdit>,
    ) -> app::Task<Message> {
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        let Some(draft) = self.drafts.get_mut(&id) else {
            return app::Task::none();
        };
        self.saving = true;
        draft.status = Some("Saving…".into());
        let request = match edit {
            Some(edit) => Request::Edit {
                token: draft.token.clone(),
                edit,
                detail_offset: draft.detail_offset,
            },
            None => Request::Delete {
                token: draft.token.clone(),
            },
        };
        let receive = worker.submit_required(request);
        cosmic::task::future(async move {
            Message::DocumentSaved(
                id,
                field,
                receive
                    .await
                    .unwrap_or_else(|_| Err("Library worker stopped".into())),
            )
        })
    }

    pub fn document_saved(
        &mut self,
        id: String,
        field: Saved,
        result: Result<Response, String>,
    ) -> app::Task<Message> {
        self.saving = false;
        match result {
            Ok(Response::SavedDocument(document)) => {
                if let Some(draft) = self.drafts.get_mut(&id) {
                    draft.saved(&document, field);
                }
                for hit in &mut self.hits {
                    if hit.document_id == id {
                        hit.title = document.title.clone();
                    }
                }
                if self
                    .selected
                    .as_ref()
                    .is_some_and(|selected| selected.token.id == id)
                {
                    self.page = self.page.min(document.pages.len().saturating_sub(1) as u32);
                    self.page_count = document.pages.len() as u32;
                    self.selected = Some(document);
                    return self.select_document(id, self.page as usize);
                }
            }
            Ok(Response::DeletedDocument(deleted)) => {
                self.drafts.remove(&deleted);
                self.editor_open = false;
                return self.browse();
            }
            Err(error) => {
                if let Some(draft) = self.drafts.get_mut(&id) {
                    draft.failed(&error);
                }
            }
            _ => {}
        }
        app::Task::none()
    }

    pub fn editor_view(&self) -> Element<'_, Message> {
        let Some(document) = self.selected.as_ref() else {
            return widget::container(self.body("Loading saved document…"))
                .padding(16)
                .into();
        };
        let Some(draft) = self.drafts.get(&document.token.id) else {
            return self.body("Choose Edit document again.");
        };
        let mut content =
            widget::column::with_capacity(self.stacks.len() + document.details.len() + 20)
                .spacing(8)
                .push(widget::text::heading("Document information"))
                .push(self.button("Done editing").on_press(msg(Action::Close)))
                .push({
                    let mut tabs = widget::row::with_capacity(3).spacing(4);
                    for (panel, label) in [
                        (Panel::Information, "Information"),
                        (Panel::Details, "Details"),
                        (Panel::Pages, "Pages"),
                    ] {
                        tabs = tabs.push(
                            self.button(label)
                                .font_size(12)
                                .height(28.)
                                .class(if draft.panel == panel {
                                    cosmic::theme::Button::Suggested
                                } else {
                                    cosmic::theme::Button::Text
                                })
                                .on_press(msg(Action::Panel(panel))),
                        );
                    }
                    tabs
                });
        if let Some(status) = &draft.status {
            content = content.push(self.body(status.as_str()));
        }
        if draft.has_changes(document) {
            content = content.push(widget::text::caption(
                "Unsaved changes · kept while this app is open",
            ));
        }
        if let Some(error) = &self.library_error {
            content = content.push(self.body(error.as_str()));
        }
        if draft.panel == Panel::Information {
            content = content
                .push(widget::text::caption("Name"))
                .push(widget::text_input("Name", &draft.title).on_input(|s| msg(Action::Title(s))))
                .push(
                    self.button("Save title")
                        .font_size(12)
                        .height(26.)
                        .on_press_maybe((!self.saving).then_some(msg(Action::SaveTitle))),
                );
            let names: Vec<_> = draft
                .filing
                .iter()
                .filter_map(|id| self.stacks.iter().find(|s| &s.id == id))
                .map(|s| s.name.clone())
                .collect();
            content = content
                .push(widget::text::caption("Stacks"))
                .push(self.membership_chips(&names))
                .push(
                    self.button(if draft.filing_open {
                        "Hide stack choices"
                    } else {
                        "Change stacks"
                    })
                    .font_size(12)
                    .height(26.)
                    .on_press(msg(Action::ToggleFiling)),
                );
            if draft.filing_open {
                let mut choices = widget::column::with_capacity(self.stacks.len()).spacing(4);
                for stack in &self.stacks {
                    let id = stack.id.clone();
                    choices = choices.push(
                        widget::checkbox(draft.filing.contains(&id))
                            .label(format!(
                                "{}{}",
                                stack.name,
                                if stack.visible { "" } else { " (hidden)" }
                            ))
                            .on_toggle(move |chosen| msg(Action::Filing(id.clone(), chosen))),
                    );
                }
                content = content.push(widget::scrollable(choices).height(140)).push(
                    self.button("Save filing")
                        .font_size(12)
                        .height(26.)
                        .on_press_maybe((!self.saving).then_some(msg(Action::SaveFiling))),
                );
            }
            content = content
                .push(widget::text::caption("Notes"))
                .push(
                    widget::text_editor::text_editor(&draft.notes)
                        .height(100)
                        .size(14.0)
                        .padding(8)
                        .style(|theme, status| {
                            let mut style = <cosmic::Theme as widget::text_editor::Catalog>::style(
                                theme,
                                &Default::default(),
                                status,
                            );
                            style.value = theme.cosmic().background(false).on.into();
                            style.placeholder = style.value;
                            style.border.width = 1.0;
                            style.border.radius = 8.0.into();
                            style
                        })
                        .on_action(|a| msg(Action::Notes(a))),
                )
                .push(
                    self.button("Save notes")
                        .font_size(12)
                        .height(26.)
                        .on_press_maybe((!self.saving).then_some(msg(Action::SaveNotes))),
                );
        }
        if draft.panel == Panel::Details {
            if !draft.detail_drafts.is_empty() {
                content = content.push(widget::text::caption(
                    "Unfinished details · retained until you quit",
                ));
                for (field, (value, _)) in &draft.detail_drafts {
                    content = content.push(
                        widget::button::text(format!(
                            "Resume: {}",
                            value["label"]
                                .as_str()
                                .filter(|s| !s.is_empty())
                                .unwrap_or("Untitled detail")
                        ))
                        .on_press_maybe(
                            (!self.saving).then_some(msg(Action::SelectDetail(field.clone()))),
                        ),
                    );
                }
            }
            content = content.push(widget::text::heading("Saved details"));
            for detail in &document.details {
                let field = detail["field_id"].as_str().unwrap_or_default().to_owned();
                let state = detail["state"].as_str().unwrap_or_default();
                content = content.push(
                    widget::button::text(format!(
                        "{} · {} {} · {}",
                        detail["label"].as_str().unwrap_or_default(),
                        detail["value"].as_str().unwrap_or_default(),
                        detail["qualifier"].as_str().unwrap_or_default(),
                        state
                    ))
                    .on_press_maybe(
                        (!self.saving).then_some(msg(Action::SelectDetail(field.clone()))),
                    ),
                );
                if state == "saved" {
                    content = content.push(self.button("Remove detail").on_press_maybe(
                        (!self.saving).then_some(msg(Action::RemoveDetail(field))),
                    ));
                }
            }
            if document.detail_count > 200 {
                content = content
                    .push(self.body(format!(
                        "Details {}–{} of {}",
                        document.detail_offset + 1,
                        document.detail_offset + document.details.len() as u32,
                        document.detail_count
                    )))
                    .push(
                        widget::row::with_capacity(2)
                            .spacing(8)
                            .push(
                                self.button("Previous details").on_press_maybe(
                                    (!self.saving && document.detail_offset > 0)
                                        .then_some(msg(Action::DetailsPage(-1))),
                                ),
                            )
                            .push(
                                self.button("Next details").on_press_maybe(
                                    (!self.saving
                                        && document.detail_offset + 200 < document.detail_count)
                                        .then_some(msg(Action::DetailsPage(1))),
                                ),
                            ),
                    );
            }
            let value = |key: &str| draft.detail[key].as_str().unwrap_or_default().to_owned();
            let kind = value("kind");
            let kind_index = ["text", "amount", "date", "organization"]
                .iter()
                .position(|k| *k == kind);
            content = content
                .push(
                    self.button("New detail")
                        .on_press_maybe((!self.saving).then_some(msg(Action::NewDetail))),
                )
                .push(widget::dropdown(
                    vec!["Text", "Amount", "Date", "Organization"],
                    kind_index,
                    |i| msg(Action::Kind(i)),
                ))
                .push(self.body("Label"))
                .push(
                    widget::text_input("Label", value("label")).on_input(|s| msg(Action::Label(s))),
                )
                .push(self.body("Exact value"))
                .push(
                    widget::text_input("Value", value("value")).on_input(|s| msg(Action::Value(s))),
                );
            if kind == "amount" || kind == "date" {
                content = content
                    .push(self.body(if kind == "amount" {
                        "Currency code or unknown"
                    } else {
                        "Date precision · day, month or year"
                    }))
                    .push(
                        widget::text_input("Qualifier", value("qualifier"))
                            .on_input(|s| msg(Action::Qualifier(s))),
                    );
            }
            content = content
                .push(
                    self.button("Save detail")
                        .on_press_maybe((!self.saving).then_some(msg(Action::SaveDetail))),
                )
                .push(
                    self.button(if draft.history_open {
                        "Hide source history"
                    } else {
                        "Show source history"
                    })
                    .on_press(msg(Action::ToggleHistory)),
                );
            if draft.history_open {
                content = content.push(self.body(format!(
                    "Source: {} · {}",
                    value("origin"),
                    value("state")
                )));
                if value("evidence") != "{}" {
                    content = content.push(self.body(value("evidence")));
                }
            }
        }
        if draft.status.as_ref().is_some_and(|s| {
            s.starts_with("Could not save") || s.starts_with("Loaded saved version")
        }) {
            content = content
                .push(self.body(format!("Saved title: {}", document.title)))
                .push(self.body(format!("Saved notes: {}", document.notes)))
                .push(
                    widget::column::with_capacity(2)
                        .spacing(8)
                        .push(
                            self.button("Refresh saved values")
                                .on_press_maybe((!self.saving).then_some(msg(Action::Refresh))),
                        )
                        .push(self.button("Use loaded version for retry").on_press_maybe(
                            (!self.saving).then_some(msg(Action::UseLoadedVersion)),
                        )),
                );
        }
        if draft.panel == Panel::Pages {
            content = content
                .push(widget::text::heading(format!(
                    "Saved page {} of {}",
                    self.page + 1,
                    document.pages.len()
                )))
                .push(
                    widget::column::with_capacity(4)
                        .spacing(8)
                        .push(
                            self.button("Rotate page")
                                .on_press_maybe((!self.saving).then_some(msg(Action::Rotate))),
                        )
                        .push(self.button("Move page earlier").on_press_maybe(
                            (!self.saving && self.page > 0).then_some(msg(Action::MovePage(-1))),
                        ))
                        .push(
                            self.button("Move page later").on_press_maybe(
                                (!self.saving && (self.page as usize + 1) < document.pages.len())
                                    .then_some(msg(Action::MovePage(1))),
                            ),
                        )
                        .push(
                            self.button("Remove page…").on_press_maybe(
                                (!self.saving && document.pages.len() > 1)
                                    .then_some(msg(Action::RemovePage)),
                            ),
                        ),
                );
            if draft.confirm_remove {
                content =
                    content
                        .push(self.body(
                            "Remove this page from the saved document? Its original is retained.",
                        ))
                        .push(self.button("Remove page").on_press_maybe(
                            (!self.saving).then_some(msg(Action::ConfirmRemovePage)),
                        ))
                        .push(self.button("Keep page").on_press(msg(Action::CancelDelete)));
            }
        }
        if draft.panel == Panel::Information {
            content = content.push(
                self.button("Delete document…")
                    .on_press_maybe((!self.saving).then_some(msg(Action::Delete))),
            );
            if draft.confirm_delete {
                content=content.push(self.body("Delete this document from the library? Unsaved edits are discarded; original files are retained locally."))
            .push(self.button("Delete document").on_press_maybe((!self.saving).then_some(msg(Action::ConfirmDelete)))).push(self.button("Keep document").on_press(msg(Action::CancelDelete)));
            }
        }
        widget::container(widget::scrollable(content))
            .padding(16)
            .width(iced::Length::Fill)
            .height(iced::Length::Fill)
            .into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn document() -> DocumentView {
        DocumentView {
            token: DocumentToken {
                id: stack_archive::synthetic::id(1),
                incarnation: "saved-incarnation".into(),
                revision: 4,
            },
            title: "Saved title".into(),
            notes: "Exact notes\r\n0000123; 🦀\n".into(),
            pages: vec![],
            searchable_pages: 0,
            memberships: vec!["one".into()],
            details: vec![],
            detail_count: 0,
            detail_offset: 0,
        }
    }
    #[test]
    fn detail_switches_retain_exact_values_baselines_and_paginated_drafts() {
        let mut document = document();
        let mut first = new_detail(&document.token.id);
        first["label"] = json!("First");
        first["value"] = json!("saved");
        let mut second = new_detail(&document.token.id);
        second["label"] = json!("Second");
        document.details = vec![first.clone(), second.clone()];
        let mut draft = Draft::new(&document);
        assert!(!draft.has_unsaved_changes());
        draft.switch_detail(first.clone());
        assert!(!draft.has_unsaved_changes());
        draft.detail["value"] = json!("-00024.500100 🦀");
        let exact = draft.detail.clone();
        draft.switch_detail(second.clone());
        assert!(draft.has_unsaved_changes());
        draft.detail["label"] = json!("Second unfinished");
        draft.switch_detail(first.clone());
        assert_eq!(draft.detail, exact);
        // A refresh or another details page need not contain the dirty row.
        document.details.clear();
        document.detail_offset = 200;
        assert!(draft.has_changes(&document));
        draft.failed("stale document");
        assert_eq!(draft.detail, exact);
        draft.switch_detail(second.clone());
        assert_eq!(draft.detail["label"], "Second unfinished");
        draft.switch_detail(first);
        document.details.push(exact);
        draft.saved(&document, Saved::Detail);
        assert!(
            draft.has_unsaved_changes(),
            "saving one field must retain the other detail draft"
        );
        draft.switch_detail(second);
        assert_eq!(draft.detail["label"], "Second unfinished");
        draft.detail["label"] = json!("Second");
        assert!(
            !draft.has_unsaved_changes(),
            "reverting all edits clears dirty status"
        );
        draft.detail["qualifier"] = json!("unfinished qualifier");
        draft.switch_detail(new_detail(&document.token.id));
        assert!(
            draft.has_unsaved_changes(),
            "qualifier-only edits are drafts too"
        );
    }
    #[test]
    fn failed_stale_and_partial_saves_preserve_other_exact_drafts() {
        let mut saved = document();
        let mut draft = Draft::new(&saved);
        assert_eq!(draft.notes.text(), saved.notes);
        draft.title = "Unsaved title".into();
        draft.filing.clear();
        draft.detail["value"] = json!("-00024.500100");
        draft.detail["qualifier"] = json!("unknown");
        let detail = draft.detail.clone();
        let token = draft.token.clone();
        draft.failed("The library changed; review this action again");
        assert_eq!(draft.title, "Unsaved title");
        assert_eq!(draft.notes.text(), saved.notes);
        assert_eq!(draft.detail, detail);
        assert_eq!(draft.token, token);
        assert!(draft.filing.is_empty());
        saved.token.revision += 1;
        saved.title = "Saved replacement".into();
        draft.saved(&saved, Saved::Title);
        assert_eq!(draft.title, "Saved replacement");
        assert_eq!(draft.notes.text(), saved.notes);
        assert_eq!(draft.detail, detail);
        assert!(draft.filing.is_empty());
        assert_eq!(draft.token, saved.token);
    }
}
