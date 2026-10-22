//! Settings, automatic synchronization and readable edit comparisons.
use crate::{
    Message, Stack,
    repository::{Request, Response, SyncRequest},
};
use cosmic::{Element, app, iced, widget};
use serde_json::Value;
use std::collections::BTreeMap;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Page {
    #[default]
    Overview,
    Settings,
}
#[derive(Clone, Debug)]
pub enum Action {
    Network(crate::network_ui::Action),
    Open,
    Settings,
    Page(Page),
    ConflictPage(i32),
    Resolve(String, usize),
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Pending {
    NetworkStatus,
    NetworkPairing,
    NetworkSession,
    Status,
    Resolve,
}
#[derive(Default)]
pub struct Ui {
    pub network: crate::network_ui::Ui,
    pub open: bool,
    pub busy: bool,
    pub page: Page,
    pending: Option<Pending>,
    refresh_after_status: bool,
    pub queued_request: Option<Request>,
    pub status: Option<Value>,
    pub error: Option<String>,
    conflict_offset: usize,
}
impl Ui {
    pub fn cancel(&self) {
        if let Some(control) = &self.network.control {
            control.cancel();
        }
    }
}
fn conflict_entries(status: &Value) -> Vec<(&String, &Value)> {
    let Some(conflicts) = status["conflicts"].as_object() else {
        return Vec::new();
    };
    conflicts
        .iter()
        .filter(|(key, _)| {
            if let Some(ids) = key
                .strip_prefix("choices/")
                .and_then(|v| serde_json::from_str::<Vec<String>>(v).ok())
                && ids.len() == 3
                && ids[2] == "title"
            {
                return !conflicts.contains_key(&format!(
                    "documents/{}/title",
                    serde_json::to_string(&[&ids[0]]).expect("document")
                ));
            }
            true
        })
        .collect()
}
fn conflict_count(status: &Value) -> usize {
    conflict_entries(status).len()
}

fn choice(status: &Value, key: &str, position: usize) -> Result<(Value, Value), String> {
    let alternatives = status["conflicts"][key]
        .as_array()
        .ok_or("Conflict changed; refresh review")?;
    let selected = alternatives
        .get(position)
        .ok_or("Alternative changed; refresh review")?;
    let value = selected["value"].clone();
    if (key.starts_with("documents/[") || key.starts_with("stacks/["))
        && key.ends_with(']')
        && alternatives.iter().any(|a| a["value"].is_null())
        && !value.is_null()
    {
        return Err(
            "Deleted entities stay deleted. Review the retained alternatives below.".into(),
        );
    }
    let expected = Value::Array(alternatives.iter().map(|a| a["id"].clone()).collect());
    Ok((expected, value))
}

fn retain_drafts_after_sync(drafts: &mut BTreeMap<String, crate::editor::Draft>) {
    for draft in drafts
        .values_mut()
        .filter(|draft| draft.has_unsaved_changes())
    {
        draft.status = Some(
            "Library changed during sync. Draft retained; review current document before saving."
                .into(),
        );
    }
}

impl Stack {
    pub(crate) fn sync_request(
        &mut self,
        request: SyncRequest,
        pending: Pending,
    ) -> app::Task<Message> {
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        if self.sync.busy {
            return app::Task::none();
        }
        self.sync.busy = true;
        self.sync.pending = Some(pending);
        self.sync.error = None;
        // Review and status must survive unrelated browse/thumbnail requests.
        // The repository worker serializes every operation, including these reads.
        let response = worker.submit_required(Request::Sync(request));
        cosmic::task::future(async move {
            Message::SyncResult(
                response
                    .await
                    .unwrap_or_else(|_| Err("Library worker stopped".into())),
            )
        })
    }

    pub fn sync_action(&mut self, action: Action) -> app::Task<Message> {
        match action {
            Action::Settings => {
                let task = self.sync_action(Action::Open);
                self.sync.page = Page::Settings;
                task
            }
            Action::Network(action) => self.network_action(action),
            Action::Open => {
                if self.saving {
                    return app::Task::none();
                }
                self.sync.page = Page::Overview;
                self.library_menu_open = false;
                if self.narrow {
                    self.core.nav_bar_set_toggled(false);
                }
                self.sync.open = true;
                self.catalog.open = false;
                self.editor_open = false;
                self.selected = None;
                self.opening_document = None;
                self.invalidate_preview();
                self.rebuild_navigation();
                self.sync_request(SyncRequest::Status, Pending::Status)
            }
            Action::Page(page) => {
                self.sync.page = page;
                iced::widget::scrollable::scroll_to(
                    "stack-sync".into(),
                    iced::widget::scrollable::AbsoluteOffset {
                        x: Some(0.),
                        y: Some(0.),
                    },
                )
            }
            Action::ConflictPage(direction) => {
                let count = self.sync.status.as_ref().map_or(0, conflict_count);
                self.sync.conflict_offset = if direction < 0 {
                    self.sync.conflict_offset.saturating_sub(10)
                } else {
                    self.sync
                        .conflict_offset
                        .saturating_add(10)
                        .min(count.saturating_sub(1) / 10 * 10)
                };
                app::Task::none()
            }
            Action::Resolve(key, position) => {
                let Some(status) = &self.sync.status else {
                    return app::Task::none();
                };
                let (expected, value) = match choice(status, &key, position) {
                    Ok(v) => v,
                    Err(e) => {
                        self.sync.error = Some(e);
                        return app::Task::none();
                    }
                };
                self.sync_request(
                    SyncRequest::Resolve {
                        key,
                        expected,
                        value,
                    },
                    Pending::Resolve,
                )
            }
        }
    }
    pub fn sync_result(&mut self, result: Result<Response, String>) -> app::Task<Message> {
        let pending = self.sync.pending.take();
        self.sync.busy = false;
        self.sync.network.control = None;
        if self.sync.network.disconnecting {
            self.sync.network.disconnecting = false;
            return self.sync_request(
                SyncRequest::Network(crate::network_ui::Request::Revoke),
                Pending::NetworkStatus,
            );
        }
        match result {
            Ok(Response::Sync(value)) => {
                self.sync.error = None;
                match pending {
                    Some(Pending::NetworkPairing) => {
                        self.sync.network.status = value["pairing"].clone();
                        return self.after_automatic_sync();
                    }
                    Some(Pending::NetworkStatus) => {
                        self.sync.network.status = value["pairing"].clone();
                        return self.sync_request(SyncRequest::Status, Pending::Status);
                    }
                    Some(Pending::NetworkSession) => {
                        self.sync.network.status = value["pairing"].clone();
                        self.sync.network.last_sync = Some(std::time::Instant::now());
                        // Neither side had anything new, so the shown status is current.
                        if value["session"]["skipped"] == true {
                            if let Some(request) = self.sync.queued_request.take() {
                                return self.repository_request(request);
                            }
                            return app::Task::none();
                        }
                        if value["session"]["received"]["applied"]
                            .as_u64()
                            .unwrap_or(0)
                            > 0
                            || value["session"]["final_receipt"]["applied"]
                                .as_u64()
                                .unwrap_or(0)
                                > 0
                        {
                            return self.after_automatic_sync();
                        }
                        return self.sync_request(SyncRequest::Status, Pending::Status);
                    }
                    Some(Pending::Resolve) => return self.after_automatic_sync(),
                    Some(Pending::Status) => {
                        self.sync.network.status = value["pairing"].clone();
                        if let Some(name) = self.sync.network.status["labels"]["own_name"].as_str()
                        {
                            self.sync.network.nearby.name = name.to_owned();
                        }
                        self.sync.conflict_offset = self
                            .sync
                            .conflict_offset
                            .min(conflict_count(&value).saturating_sub(1) / 10 * 10);
                        self.sync.status = Some(value);
                        if let Some(request) = self.sync.queued_request.take() {
                            self.sync.refresh_after_status = false;
                            return self.repository_request(request);
                        }
                        if self.sync.refresh_after_status {
                            self.sync.refresh_after_status = false;
                            return self.refresh_browser();
                        }
                    }
                    None => {}
                }
            }
            Err(_) => {
                self.sync.error=Some(match pending {
                    Some(Pending::NetworkSession) => "The connection was interrupted. Your documents are saved; Stack will try again automatically.",
                    Some(Pending::NetworkPairing) => "Could not connect. Check that Stack is open on your phone and both devices use the same Wi-Fi, then select Connect phone again.",
                    Some(Pending::Resolve) => "This document changed again. Review the current versions and try again.",
                    _ => "Could not update synchronization. Your documents are saved. Please try again.",
                }.into());
                if matches!(
                    pending,
                    Some(Pending::NetworkSession | Pending::NetworkPairing)
                ) {
                    return self.after_automatic_sync();
                }
                if let Some(request) = self.sync.queued_request.take() {
                    return self.repository_request(request);
                }
            }
            _ => {
                self.sync.error = Some("Could not update synchronization. Please try again.".into())
            }
        }
        app::Task::none()
    }

    pub(crate) fn after_automatic_sync(&mut self) -> app::Task<Message> {
        self.invalidate_preview();
        self.repository_generation += 1;
        self.cancel_thumbnails();
        self.opening_document = self
            .selected
            .as_ref()
            .map(|document| document.token.id.clone());
        retain_drafts_after_sync(&mut self.drafts);
        self.sync.refresh_after_status = true;
        self.sync_request(SyncRequest::Status, Pending::Status)
    }

    fn sync_field_title(&self, key: &str) -> String {
        let field = if key.ends_with("/title") {
            "Document name"
        } else if key.ends_with("/notes") {
            "Notes"
        } else if key.starts_with("choices/") {
            "Naming or filing choice"
        } else if key.starts_with("details/") {
            "Saved detail"
        } else if key.starts_with("pages/") {
            "Page appearance"
        } else {
            "Saved change"
        };
        let identity = key
            .find('[')
            .and_then(|start| key[start..].find(']').map(|end| &key[start..=start + end]))
            .and_then(|text| serde_json::from_str::<Vec<Value>>(text).ok())
            .and_then(|ids| ids.first().and_then(Value::as_str).map(str::to_owned));
        match identity {
            Some(id) => {
                let name = self
                    .hits
                    .iter()
                    .find(|hit| hit.document_id == id)
                    .map(|hit| hit.title.clone())
                    .or_else(|| {
                        self.sync
                            .status
                            .as_ref()
                            .and_then(|s| s["document_names"][&id].as_str().map(str::to_owned))
                    })
                    .unwrap_or_else(|| "Document".to_owned());
                format!("{name} · {field}")
            }
            None => field.to_owned(),
        }
    }

    pub(crate) fn sync_conflict_view(&self) -> Element<'_, Message> {
        let mut content = widget::column::with_capacity(16).spacing(16);
        let Some(status) = &self.sync.status else {
            return content.into();
        };
        let Some(conflicts) = status["conflicts"].as_object() else {
            return content.into();
        };
        if conflicts.is_empty() {
            return content.into();
        }
        content = content
            .push(widget::text::title3("Changed on both devices"))
            .push(self.body(
                "Choose the version to keep. Your other documents continue to sync automatically.",
            ));
        let displayed = conflict_entries(status);
        for (key, values) in displayed
            .iter()
            .copied()
            .skip(self.sync.conflict_offset)
            .take(10)
        {
            content = content.push(self.body(self.sync_field_title(key)));
            let Some(values) = values.as_array() else {
                continue;
            };
            for (index, alternative) in values.iter().enumerate() {
                let phone = alternative["id"]["replica"] != status["replica"];
                content = content
                    .push(widget::text::title4(if phone {
                        "Phone"
                    } else {
                        "Desktop"
                    }))
                    .push(self.body(readable_value(&alternative["value"], status)))
                    .push(
                        self.button(if phone {
                            "Keep phone version"
                        } else {
                            "Keep desktop version"
                        })
                        .on_press_maybe(
                            (!self.sync.busy)
                                .then_some(Message::Sync(Action::Resolve(key.clone(), index))),
                        ),
                    );
            }
        }
        if displayed.len() > 10 {
            content = content.push(self.button("Previous changes").on_press_maybe(
                (self.sync.conflict_offset > 0).then_some(Message::Sync(Action::ConflictPage(-1))),
            ));
            content = content.push(
                self.button("Next changes")
                    .on_press(Message::Sync(Action::ConflictPage(1))),
            );
        }
        content.into()
    }
}

fn readable_value(value: &Value, status: &Value) -> String {
    if value.is_null() {
        return "Deleted".into();
    }
    if let Some(text) = value.as_str() {
        return text.into();
    }
    for field in ["notes", "title", "name", "value", "label"] {
        if let Some(text) = value[field].as_str() {
            return if text.is_empty() {
                "Empty".into()
            } else {
                text.into()
            };
        }
    }
    if value.get("rotation").is_some() {
        return format!(
            "Rotation: {}°\nColor: {}\nFlattening: {}\nCrop: {}",
            value["rotation"],
            value["enhancement"].as_str().unwrap_or("Original"),
            if value["flatten"] == 1 { "On" } else { "Off" },
            value["crop"].as_str().unwrap_or("Full page")
        );
    }
    if let Some(values) = value.as_array() {
        if values.is_empty() {
            return "None".into();
        }
        return values
            .iter()
            .enumerate()
            .map(|(i, id)| {
                let name = id.as_str().and_then(|id| {
                    status["stack_names"][id]
                        .as_str()
                        .or_else(|| status["page_names"][id].as_str())
                });
                name.map(str::to_owned)
                    .unwrap_or_else(|| format!("Item {}", i + 1))
            })
            .collect::<Vec<_>>()
            .join(" → ");
    }
    if let Some(visible) = value["visible"].as_i64() {
        return if visible == 1 {
            "Visible in the library"
        } else {
            "Hidden from the library"
        }
        .into();
    }
    if let Some(color) = value["color"].as_i64() {
        return format!("Stack color: #{:06X}", color & 0xffffff);
    }
    "Saved document or organization change".into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use stack_core::{DocumentView, library::DocumentToken};

    #[test]
    fn resolution_carries_every_reviewed_id_and_never_resurrects_deletion() {
        let status = json!({"conflicts": {"documents/[\"x\"]": [
            {"id":{"replica":"a","sequence":1},"value":null,"evidence":{"kind":"delete"}},
            {"id":{"replica":"b","sequence":2},"value":{"id":"x"},"evidence":{"kind":"edit"}}
        ]}});
        let (ids, value) = choice(&status, "documents/[\"x\"]", 0).unwrap();
        assert_eq!(ids.as_array().unwrap().len(), 2);
        assert!(value.is_null());
        assert!(choice(&status, "documents/[\"x\"]", 1).is_err());
        assert!(choice(&status, "documents/[\"x\"]", 2).is_err());
    }

    #[test]
    fn incoming_apply_keeps_exact_unsaved_draft_and_flags_stale() {
        let document = DocumentView {
            token: DocumentToken {
                id: uuid::Uuid::new_v4().to_string(),
                incarnation: "saved".into(),
                revision: 1,
            },
            title: "Saved".into(),
            notes: "Original".into(),
            pages: vec![],
            searchable_pages: 0,
            memberships: vec![],
            details: vec![],
            detail_count: 0,
            detail_offset: 0,
        };
        let mut draft = crate::editor::Draft::new(&document);
        draft.title = "Pending 🦀 edit".into();
        let mut drafts = BTreeMap::from([(document.token.id.clone(), draft)]);
        retain_drafts_after_sync(&mut drafts);
        let retained = &drafts[&document.token.id];
        assert_eq!(retained.title, "Pending 🦀 edit");
        assert!(retained.has_unsaved_changes());
        assert!(
            retained
                .status
                .as_deref()
                .unwrap()
                .contains("review current document")
        );
        assert_eq!(retained.token.revision, 1);
    }
}
