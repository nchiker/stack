//! File exchange, adoption and causal review in the native library sidebar.
use crate::{
    Message, Stack,
    repository::{Request, Response, SyncRequest},
    transfers_ui::{Action as TransferAction, Pick},
};
use cosmic::{Element, app, iced, widget};
use serde_json::{Value, json};
use stack_archive::Control;
use std::{collections::{BTreeMap, BTreeSet}, path::PathBuf};

#[derive(Clone, Debug)]
pub enum Action {
    Open,
    Refresh,
    Members(String),
    Mapping(String, String, String),
    CatalogName(String, String, String),
    Peer(String),
    Create,
    PickImport,
    PickExport,
    Adopt,
    Resolve(String, usize),
    Cancel,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Pending {
    Status,
    Create,
    Review,
    Adopt,
    Import,
    Export,
    Resolve,
}

#[derive(Default)]
pub struct Ui {
    pub open: bool,
    pub busy: bool,
    pending: Option<Pending>,
    pub status: Option<Value>,
    pub review: Option<Value>,
    pub source: Option<PathBuf>,
    pub members: String,
    pub peer: String,
    pub notice: Option<String>,
    pub error: Option<String>,
    control: Option<Control>,
}
impl Ui {
    pub fn cancel(&self) {
        if let Some(control) = &self.control {
            control.cancel();
        }
    }
}

fn identities(text: &str) -> Result<Vec<String>, String> {
    let mut result = BTreeSet::new();
    for value in text.split(|c: char| c == ',' || c == '\n' || c.is_whitespace()) {
        if value.is_empty() {
            continue;
        }
        if uuid::Uuid::parse_str(value).is_err() {
            return Err(format!("Invalid replica ID: {value}"));
        }
        result.insert(value.to_owned());
    }
    Ok(result.into_iter().collect())
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
    for draft in drafts.values_mut().filter(|draft| draft.has_unsaved_changes()) {
        draft.status = Some("Library changed during sync. Draft retained; review current document before saving.".into());
    }
}

impl Stack {
    fn sync_request(&mut self, request: SyncRequest, pending: Pending) -> app::Task<Message> {
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        if self.sync.busy {
            return app::Task::none();
        }
        self.sync.busy = true;
        self.sync.pending = Some(pending);
        self.sync.error = None;
        let response = if matches!(pending, Pending::Status | Pending::Review) {
            worker.submit(Request::Sync(request))
        } else {
            worker.submit_required(Request::Sync(request))
        };
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
            Action::Open => {
                self.sync.open = true;
                self.catalog.open = false;
                self.editor_open = false;
                self.selected = None;
                self.opening_document = None;
                self.invalidate_preview();
                self.rebuild_navigation();
                self.sync_request(SyncRequest::Status, Pending::Status)
            }
            Action::Refresh => self.sync_request(SyncRequest::Status, Pending::Status),
            Action::Members(value) => {
                self.sync.members = value;
                app::Task::none()
            }
            Action::Mapping(table, source, target) => {
                if let Some(plan) = &mut self.sync.review {
                    plan["mappings"][&table][&source] = json!(target);
                }
                app::Task::none()
            }
            Action::CatalogName(table, source, chosen) => {
                if let Some(plan) = &mut self.sync.review {
                    plan["catalog_names"][&table][&source]["chosen"] = json!(chosen);
                }
                app::Task::none()
            }
            Action::Peer(value) => {
                self.sync.peer = value;
                app::Task::none()
            }
            Action::Create => {
                let members = match identities(&self.sync.members) {
                    Ok(v) => v,
                    Err(e) => {
                        self.sync.error = Some(e);
                        return app::Task::none();
                    }
                };
                if members.is_empty() {
                    self.sync.error = Some("Enter the complete group member list, including another device's replica ID.".into());
                    return app::Task::none();
                }
                self.sync_request(SyncRequest::Start(members), Pending::Create)
            }
            Action::PickImport => {
                if self.sync.busy || self.transfers.busy() {
                    return app::Task::none();
                }
                self.transfer_action(TransferAction::Pick(Pick::SyncImport))
            }
            Action::PickExport => {
                if self.sync.busy || self.transfers.busy() {
                    return app::Task::none();
                }
                if uuid::Uuid::parse_str(self.sync.peer.trim()).is_err() {
                    self.sync.error = Some("Enter a destination replica ID from the group.".into());
                    return app::Task::none();
                }
                self.transfer_action(TransferAction::Pick(Pick::SyncExport))
            }
            Action::Adopt => {
                let (Some(path), Some(plan)) = (self.sync.source.clone(), self.sync.review.clone())
                else {
                    return app::Task::none();
                };
                let members = match identities(&self.sync.members) {
                    Ok(v) => v,
                    Err(e) => {
                        self.sync.error = Some(e);
                        return app::Task::none();
                    }
                };
                let required: BTreeSet<_> = [
                    plan["sender"].as_str(),
                    self.sync
                        .status
                        .as_ref()
                        .and_then(|s| s["replica"].as_str()),
                ]
                .into_iter()
                .flatten()
                .map(str::to_owned)
                .collect();
                if !required.is_subset(&members.iter().cloned().collect()) {
                    self.sync.error =
                        Some("Members must include this replica and the bundle sender.".into());
                    return app::Task::none();
                }
                let mut reviewed = plan;
                reviewed["members"] = json!(members);
                self.sync.review = Some(reviewed.clone());
                let control = Control::default();
                self.sync.control = Some(control.clone());
                self.sync_request(
                    SyncRequest::Adopt {
                        path,
                        plan: reviewed,
                        control,
                    },
                    Pending::Adopt,
                )
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
            Action::Cancel => {
                if let Some(control) = &self.sync.control {
                    control.cancel();
                    self.sync.notice = Some(
                        "Cancellation requested. Waiting for the file operation to finish safely…"
                            .into(),
                    );
                }
                app::Task::none()
            }
        }
    }

    pub fn sync_picked(
        &mut self,
        pick: Pick,
        result: Result<Option<Vec<PathBuf>>, String>,
    ) -> app::Task<Message> {
        self.transfers.picking = false;
        let path = match result {
            Ok(Some(paths)) if paths.len() == 1 => paths[0].clone(),
            Ok(None) => return app::Task::none(),
            Ok(_) => {
                self.sync.error = Some("Choose one exchange file.".into());
                return app::Task::none();
            }
            Err(error) => {
                self.sync.error = Some(format!("Could not choose exchange file: {error}"));
                return app::Task::none();
            }
        };
        match pick {
            Pick::SyncImport => {
                self.sync.source = Some(path.clone());
                self.sync.review = None;
                if self
                    .sync
                    .status
                    .as_ref()
                    .is_some_and(|s| s["adopted"] == true)
                {
                    let control = Control::default();
                    self.sync.control = Some(control.clone());
                    self.sync_request(SyncRequest::Import { path, control }, Pending::Import)
                } else {
                    self.sync_request(SyncRequest::Review(path), Pending::Review)
                }
            }
            Pick::SyncExport => {
                let control = Control::default();
                self.sync.control = Some(control.clone());
                self.sync_request(
                    SyncRequest::Export {
                        peer: self.sync.peer.trim().to_owned(),
                        path,
                        control,
                    },
                    Pending::Export,
                )
            }
            _ => app::Task::none(),
        }
    }

    pub fn sync_result(&mut self, result: Result<Response, String>) -> app::Task<Message> {
        let pending = self.sync.pending.take();
        self.sync.busy = false;
        self.sync.control = None;
        match result {
            Ok(Response::Sync(value)) => {
                self.sync.error = None;
                match pending {
                    Some(Pending::Status | Pending::Create | Pending::Resolve) => {
                        if value["adopted"] == false && self.sync.members.is_empty() {
                            self.sync.members =
                                value["replica"].as_str().unwrap_or_default().to_owned();
                        }
                        self.sync.status = Some(value);
                        if pending == Some(Pending::Create) {
                            self.sync.notice = Some(
                                "Sync group created. Exchange files with the listed replicas."
                                    .into(),
                            );
                        }
                        if pending == Some(Pending::Resolve) {
                            self.sync.notice = Some("Reviewed decision saved.".into());
                            return self.after_sync_apply();
                        }
                    }
                    Some(Pending::Review) => {
                        self.sync.members = value["members"]
                            .as_array()
                            .map(|a| {
                                a.iter()
                                    .filter_map(Value::as_str)
                                    .collect::<Vec<_>>()
                                    .join("\n")
                            })
                            .unwrap_or_default();
                        self.sync.review = Some(value);
                        self.sync.notice = Some("Review every source identity mapping and the complete group list before adopting.".into());
                    }
                    Some(Pending::Adopt | Pending::Import) => {
                        self.sync.review = None;
                        self.sync.notice = Some(format!(
                            "Exchange saved locally: {} applied operation(s), {} pending gap(s). Delivery is not an acknowledgment.",
                            value["applied"].as_u64().unwrap_or(0),
                            value["pending_gaps"].as_u64().unwrap_or(0)
                        ));
                        return self.after_sync_apply();
                    }
                    Some(Pending::Export) => {
                        self.sync.notice = Some(format!(
                            "Exchange file published. Peer receipt still pending. {}",
                            value
                        ));
                    }
                    None => {}
                }
            }
            Err(error) => {
                self.sync.error = Some(format!("Sync failed: {error}"));
                if matches!(
                    pending,
                    Some(Pending::Adopt | Pending::Resolve | Pending::Import)
                ) {
                    self.sync.notice = Some("Review is retained. Refresh status or choose the file again if the library changed.".into());
                }
            }
            _ => self.sync.error = Some("Unexpected library response".into()),
        }
        app::Task::none()
    }

    fn after_sync_apply(&mut self) -> app::Task<Message> {
        self.invalidate_preview();
        self.repository_generation += 1;
        self.cancel_thumbnails();
        self.selected = None;
        self.opening_document = None;
        self.editor_open = false;
        retain_drafts_after_sync(&mut self.drafts);
        let status = self.sync_request(SyncRequest::Status, Pending::Status);
        app::Task::batch([status, self.refresh_browser()])
    }

    pub fn sync_view(&self) -> Element<'_, Message> {
        let mut content = widget::column::with_capacity(24).spacing(10)
            .push(widget::text::heading("Sync exchange"))
            .push(self.body("Exchange files through a folder you choose. A published file is pending until its peer sends a receipt."));
        if self.sync.busy {
            content = content
                .push(widget::indeterminate_linear().width(iced::Length::Fill))
                .push(self.body("Preparing or saving sync state… Keep this window open."));
        }
        if self.sync.busy && self.sync.control.is_some() {
            content = content.push(
                self.button("Cancel file operation")
                    .on_press(Message::Sync(Action::Cancel)),
            );
        }
        if let Some(error) = &self.sync.error {
            content = content.push(self.body(error.clone()));
        }
        if let Some(notice) = &self.sync.notice {
            content = content.push(self.body(notice.clone()));
        }
        content = content.push(
            self.button("Refresh sync status")
                .on_press_maybe((!self.sync.busy).then_some(Message::Sync(Action::Refresh))),
        );
        if let Some(status) = &self.sync.status {
            content = content.push(self.body(format!(
                "This replica: {}",
                status["replica"].as_str().unwrap_or("unknown")
            )));
            if status["adopted"] == true {
                content = content.push(self.body(format!(
                    "Group: {}",
                    status["library"].as_str().unwrap_or("unknown")
                )));
                content =
                    content.push(self.body(format!("Members: {}", pretty(&status["members"]))));
                content = content.push(self.body(format!(
                    "Durable operations: {} · Pending gaps: {} · Projection valid: {}",
                    status["operations"], status["pending_gaps"], status["projection_valid"]
                )));
                content = content
                    .push(self.body(format!("Local frontier: {}", pretty(&status["frontier"]))));
                content = content.push(self.body(format!(
                    "Peer acknowledgments: {}",
                    pretty(&status["acknowledged_by"])
                )));
                content = content.push(
                    widget::text_input("Destination replica ID", &self.sync.peer)
                        .on_input(|v| Message::Sync(Action::Peer(v))),
                );
                content = content.push(
                    widget::row::with_capacity(2)
                        .spacing(8)
                        .push(
                            self.button("Export exchange…").on_press_maybe(
                                (!self.sync.busy && !self.transfers.busy())
                                    .then_some(Message::Sync(Action::PickExport)),
                            ),
                        )
                        .push(
                            self.button("Import exchange…").on_press_maybe(
                                (!self.sync.busy && !self.transfers.busy())
                                    .then_some(Message::Sync(Action::PickImport)),
                            ),
                        ),
                );
                let conflicts = status["conflicts"].as_object();
                content = content.push(widget::text::heading(format!(
                    "Conflicts · {}",
                    conflicts.map_or(0, |c| c.len())
                )));
                if let Some(conflicts) = conflicts {
                    for (key, alternatives) in conflicts {
                        content = content.push(widget::text::body(key.clone()));
                        if let Some(alternatives) = alternatives.as_array() {
                            for (index, alternative) in alternatives.iter().enumerate() {
                                content = content.push(self.body(format!(
                                    "Alternative {} · ID {} · Value {} · Evidence {}",
                                    index + 1,
                                    pretty(&alternative["id"]),
                                    pretty(&alternative["value"]),
                                    pretty(&alternative["evidence"])
                                )));
                                let deletion = (key.starts_with("documents/[")
                                    || key.starts_with("stacks/["))
                                    && key.ends_with(']')
                                    && alternatives.iter().any(|a| a["value"].is_null());
                                content = content.push(
                                    self.button(if deletion && alternative["value"].is_null() {
                                        "Keep deleted"
                                    } else {
                                        "Choose this value"
                                    })
                                    .on_press_maybe(
                                        (!self.sync.busy
                                            && (!deletion || alternative["value"].is_null()))
                                        .then_some(Message::Sync(Action::Resolve(
                                            key.clone(),
                                            index,
                                        ))),
                                    ),
                                );
                            }
                        }
                    }
                }
            } else {
                content = content.push(self.body("Create a group with the complete replica list, or import an exchange file and review its adoption plan."));
                content = content.push(
                    widget::text_input(
                        "Complete member IDs, separated by lines or commas",
                        &self.sync.members,
                    )
                    .on_input(|v| Message::Sync(Action::Members(v))),
                );
                content = content.push(
                    widget::row::with_capacity(2)
                        .spacing(8)
                        .push(self.button("Create reviewed group").on_press_maybe(
                            (!self.sync.busy).then_some(Message::Sync(Action::Create)),
                        ))
                        .push(
                            self.button("Review exchange for adoption…").on_press_maybe(
                                (!self.sync.busy && !self.transfers.busy())
                                    .then_some(Message::Sync(Action::PickImport)),
                            ),
                        ),
                );
            }
        }
        if let Some(review) = &self.sync.review {
            content = content.push(widget::text::heading("Adoption review"));
            content = content.push(self.body(format!(
                    "Source: {} · Group: {} · Sender: {}",
                    self.sync
                        .source
                        .as_ref()
                        .map(|p| p.display().to_string())
                        .unwrap_or_default(),
                    review["library"],
                    review["sender"]
                )));
            content = content.push(self.body(format!(
                "Source fingerprint: {} · Local fingerprint: {}",
                review["source_sha256"], review["local_sha256"]
            )));
            content = content.push(self.body(format!(
                "Source counts: {} · Local counts: {}",
                pretty(&review["source_counts"]),
                pretty(&review["local_counts"])
            )));
            content = content.push(self.body("Every mapping keeps the local identity distinct by default. Edit a target ID only when you have verified common ancestry with that source identity."));
            for table in ["documents", "pages", "stacks", "tags", "scans"] {
                content = content.push(widget::text::body(format!("{table} local ID → group ID")));
                if let Some(mapping) = review["mappings"][table].as_object() {
                    if mapping.is_empty() {
                        content = content.push(self.body("No identities"));
                    }
                    for (source, target) in mapping {
                        let table = table.to_owned();
                        let source_id = source.clone();
                        content = content.push(self.body(format!("Local: {source}")));
                        content = content.push(
                            widget::text_input(
                                "Reviewed group identity",
                                target.as_str().unwrap_or_default(),
                            )
                            .on_input(move |v| {
                                Message::Sync(Action::Mapping(table.clone(), source_id.clone(), v))
                            }),
                        );
                    }
                }
            }
            for table in ["stacks", "tags"] {
                content = content.push(widget::text::body(format!("Reviewed {table} names")));
                if let Some(names) = review["catalog_names"][table].as_object() {
                    for (id, entry) in names {
                        content = content.push(self.body(format!(
                            "Local {id} · Original: {}",
                            entry["original"].as_str().unwrap_or_default()
                        )));
                        let table = table.to_owned();
                        let id = id.clone();
                        content = content.push(
                            widget::text_input(
                                "Chosen name",
                                entry["chosen"].as_str().unwrap_or_default(),
                            )
                            .on_input(move |v| {
                                Message::Sync(Action::CatalogName(table.clone(), id.clone(), v))
                            }),
                        );
                    }
                }
            }
            content =
                content.push(self.body(
                    "Complete group membership (include every device that may exchange files):",
                ));
            content = content.push(
                widget::text_input(
                    "Member IDs separated by lines or commas",
                    &self.sync.members,
                )
                .on_input(|v| Message::Sync(Action::Members(v))),
            );
            content = content.push(
                self.button("Adopt reviewed baseline")
                    .on_press_maybe((!self.sync.busy).then_some(Message::Sync(Action::Adopt))),
            );
        }
        widget::container(widget::scrollable(content).height(iced::Length::Fill))
            .padding([18, 20])
            .height(iced::Length::Fill)
            .into()
    }
}

fn pretty(value: &Value) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "?".into())
}

#[cfg(test)]
mod tests {
    use super::*;
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
    fn member_input_is_complete_and_rejects_unknown_identity_shapes() {
        let a = uuid::Uuid::new_v4().to_string();
        let b = uuid::Uuid::new_v4().to_string();
        assert_eq!(identities(&format!("{a}, {b}\n{a}")).unwrap().len(), 2);
        assert!(identities("some friendly device name").is_err());
    }

    #[test]
    fn incoming_apply_keeps_exact_unsaved_draft_and_flags_stale() {
        let document = DocumentView {
            token: DocumentToken { id: uuid::Uuid::new_v4().to_string(), incarnation: "saved".into(), revision: 1 },
            title: "Saved".into(), notes: "Original".into(), pages: vec![], searchable_pages: 0,
            memberships: vec![], details: vec![], detail_count: 0, detail_offset: 0,
        };
        let mut draft = crate::editor::Draft::new(&document);
        draft.title = "Pending 🦀 edit".into();
        let mut drafts = BTreeMap::from([(document.token.id.clone(), draft)]);
        retain_drafts_after_sync(&mut drafts);
        let retained = &drafts[&document.token.id];
        assert_eq!(retained.title, "Pending 🦀 edit");
        assert!(retained.has_unsaved_changes());
        assert!(retained.status.as_deref().unwrap().contains("review current document"));
        assert_eq!(retained.token.revision, 1);
    }
}
