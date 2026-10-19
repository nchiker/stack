//! Authored synthetic editor scenario, sharing product message handlers.
use crate::{Message, Stack, editor::Action};
use cosmic::{app, iced, widget};
use serde_json::{Value, json};

pub const TITLE: &str = "Edited synthetic title";
pub const NOTES: &str = "M2 authored notes\n0000123; café 🦀\n";

pub fn expected(mut graph: Value) -> stack_archive::Result<stack_archive::Manifest> {
    let id = stack_archive::synthetic::id(1);
    let evidence = stack_archive::validation::current_evidence(&graph, &id);
    graph["documents"][0]["title"] = json!(TITLE);
    graph["documents"][0]["title_decision"] = json!(3);
    graph["documents"][0]["notes"] = json!(NOTES);
    graph["documents"][0]["notes_revision"] = json!(5);
    graph["documents"][0]["filing_decision"] = json!(5);
    graph["documents"][0]["metadata_revision"] = json!(22);
    graph["documents"][0]["page_count"] = json!(1);
    graph["memberships"]
        .as_array_mut()
        .unwrap()
        .retain(|m| m["document_id"] != id);
    for detail in graph["details"].as_array_mut().unwrap() {
        if detail["document_id"] == id && detail["field_id"] == "total" {
            detail["value"] = json!("-00024.500100");
            detail["qualifier"] = json!("unknown");
            detail["origin"] = json!("manual");
            detail["revision"] = json!(21);
        }
        if detail["document_id"] == id && detail["field_id"] == "account" {
            detail["state"] = json!("removed");
            detail["origin"] = json!("edited");
            detail["revision"] = json!(22);
        }
    }
    for choice in graph["choices"].as_array_mut().unwrap() {
        if choice["document_id"] == id
            && choice["field"] == "filing"
            && choice["evidence"] == evidence
        {
            choice["value"] = json!("");
        }
    }
    graph["choices"].as_array_mut().unwrap().push(json!({"document_id":id,"evidence":evidence,"field":"title","choice":"edited","value":TITLE}));
    graph["suggestions"][0]["filing_state"] = json!("edited");
    graph["pages"]
        .as_array_mut()
        .unwrap()
        .retain(|p| p["id"] != stack_archive::synthetic::id(100));
    for page in graph["pages"].as_array_mut().unwrap() {
        if page["document_id"] == id {
            page["position"] = json!(0);
        }
    }
    graph["decision_evidence"][&id] =
        json!(stack_archive::validation::current_evidence(&graph, &id));
    let deleted = stack_archive::synthetic::id(5);
    for table in stack_archive::validation::TABLES {
        graph[table].as_array_mut().unwrap().retain(|row| {
            if table == "documents" {
                row["id"] != deleted
            } else if table == "files" {
                !row["path"]
                    .as_str()
                    .unwrap()
                    .starts_with(&format!("originals/{deleted}/"))
            } else {
                row["document_id"] != deleted
            }
        });
    }
    graph["decision_evidence"]
        .as_object_mut()
        .unwrap()
        .remove(&deleted);
    stack_archive::Manifest::from_value(graph)
}

impl Stack {
    pub fn editor_scenario(&mut self) -> app::Task<Message> {
        let step = self.scenario_step;
        self.scenario_step += 1;
        let send = |message| cosmic::task::future(async move { message });
        match step {
            0 => {
                let _ = self.editor_action(Action::Open);
                let _ = self.editor_action(Action::Title("x".repeat(201)));
                self.editor_action(Action::SaveTitle)
            }
            1 => {
                let Some(draft) = self.drafts.get(&stack_archive::synthetic::id(1)) else {
                    return iced::exit();
                };
                if draft.title.len() != 201
                    || !draft
                        .status
                        .as_ref()
                        .is_some_and(|s| s.contains("Could not save"))
                {
                    return iced::exit();
                }
                let _ = self.editor_action(Action::Title(TITLE.into()));
                // Deliberate stale-token injection, separate from actual UI actions.
                self.drafts
                    .get_mut(&stack_archive::synthetic::id(1))
                    .unwrap()
                    .token
                    .revision = -1;
                self.editor_action(Action::SaveTitle)
            }
            2 => {
                let draft = self.drafts.get(&stack_archive::synthetic::id(1)).unwrap();
                if draft.title != TITLE
                    || !draft.status.as_ref().is_some_and(|s| s.contains("changed"))
                {
                    return iced::exit();
                }
                let _ = self.editor_action(Action::UseLoadedVersion);
                self.editor_action(Action::SaveTitle)
            }
            3 => {
                let _ = self.editor_action(Action::Notes(widget::text_editor::Action::SelectAll));
                let _ = self.editor_action(Action::Notes(widget::text_editor::Action::Edit(
                    widget::text_editor::Edit::Paste(std::sync::Arc::new(NOTES.into())),
                )));
                self.editor_action(Action::SaveNotes)
            }
            4 => {
                let _ = self.editor_action(Action::Filing("receipts".into(), true));
                self.editor_action(Action::SaveFiling)
            }
            5 => {
                let _ = self.editor_action(Action::Filing("receipts".into(), false));
                let _ = self.editor_action(Action::Filing("custom-first".into(), false));
                self.editor_action(Action::SaveFiling)
            }
            6 => {
                let _ = self.editor_action(Action::SelectDetail("total".into()));
                let _ = self.editor_action(Action::Value("-00024.500100".into()));
                let _ = self.editor_action(Action::Qualifier("unknown".into()));
                self.editor_action(Action::SaveDetail)
            }
            7 => self.editor_action(Action::RemoveDetail("account".into())),
            8 => self.delayed_capture(),
            9 => self.editor_action(Action::Rotate),
            10 => self.editor_action(Action::MovePage(1)),
            11 => send(Message::Next),
            12 => {
                let _ = self.editor_action(Action::RemovePage);
                self.editor_action(Action::ConfirmRemovePage)
            }
            13 => {
                let _ = self.editor_action(Action::Close);
                self.delayed_capture()
            }
            14 => send(Message::OpenDocument(stack_archive::synthetic::id(5))),
            15 => {
                let _ = self.editor_action(Action::Open);
                let _ = self.editor_action(Action::Delete);
                self.editor_action(Action::ConfirmDelete)
            }
            16 => send(Message::OpenDocument(stack_archive::synthetic::id(1))),
            17 => self.delayed_capture(),
            _ => {
                self.flags
                    .completed
                    .store(true, std::sync::atomic::Ordering::SeqCst);
                send(Message::Close)
            }
        }
    }
}
