use crate::library::{DocumentToken, Error, Library, Result, sync_id};
use serde_json::{Value, json};
use stack_archive::{Control, Manifest, validation};
use std::collections::BTreeSet;
use uuid::Uuid;

#[derive(Debug)]
pub enum DocumentEdit {
    Rename(String),
    Notes(String),
    Filing(Vec<String>),
    Membership {
        stack: String,
        selected: bool,
    },
    SetDetail(Value),
    RemoveDetail(String),
    PageAppearance {
        page: String,
        rotation: u16,
        crop: String,
        enhancement: String,
        flatten: bool,
    },
    PageOrder(Vec<String>),
}
#[derive(Debug)]
pub enum StackEdit {
    Create {
        name: String,
        color: i32,
        visible: bool,
    },
    Rename {
        id: String,
        name: String,
    },
    Visibility {
        id: String,
        visible: bool,
    },
    Color {
        id: String,
        color: i32,
    },
    Order(Vec<String>),
    Delete(String),
}

impl Library {
    pub fn edit_document(
        &mut self,
        token: &DocumentToken,
        edit: DocumentEdit,
        control: &Control,
    ) -> Result<DocumentToken> {
        self.checked_token(token)?;
        control.check()?;
        let mut graph = self.manifest()?.into_json();
        let id = &token.id;
        let index = doc_index(&graph, id)?;
        let before = document_graph(&graph, id);
        let delta = match &edit {
            DocumentEdit::Membership { stack, selected } => Some((stack.clone(), *selected)),
            _ => None,
        };
        let edit = if let Some((stack, selected)) = &delta {
            if !graph["stacks"]
                .as_array()
                .expect("stacks")
                .iter()
                .any(|row| row["id"] == *stack)
            {
                return Err(Error::Invalid("Membership references a deleted stack"));
            }
            let mut filing: Vec<String> = graph["memberships"]
                .as_array()
                .expect("memberships")
                .iter()
                .filter(|m| m["document_id"] == *id)
                .map(|m| m["stack_id"].as_str().expect("stack id").to_owned())
                .collect();
            filing.retain(|id| id != stack);
            if *selected {
                filing.push(stack.clone());
            }
            DocumentEdit::Filing(filing)
        } else {
            edit
        };
        let kind = match edit {
            DocumentEdit::Rename(title) => {
                let title = validation::clean_name(&title)?;
                let doc = &mut graph["documents"][index];
                doc["title"] = json!(title);
                doc["title_origin"] = json!("manual");
                doc["title_user_edited"] = json!(1);
                bump(doc, "title_decision")?;
                manual_choice(&mut graph, id, "title", title)?;
                "document.rename"
            }
            DocumentEdit::Notes(notes) => {
                if validation::utf16_len(&notes) > 20_000 {
                    return Err(Error::Invalid("Notes exceed 20,000 UTF-16 units"));
                }
                let doc = &mut graph["documents"][index];
                doc["notes"] = json!(notes);
                bump(doc, "notes_revision")?;
                "document.notes"
            }
            DocumentEdit::Filing(stacks) => {
                let chosen: BTreeSet<_> = stacks.iter().collect();
                if chosen.len() != stacks.len()
                    || stacks.iter().any(|id| {
                        !graph["stacks"]
                            .as_array()
                            .expect("stacks")
                            .iter()
                            .any(|s| s["id"] == *id)
                    })
                {
                    return Err(Error::Invalid(
                        "Filing references a duplicate or deleted stack",
                    ));
                }
                graph["memberships"]
                    .as_array_mut()
                    .expect("memberships")
                    .retain(|m| m["document_id"] != *id);
                for stack in &stacks {
                    graph["memberships"]
                        .as_array_mut()
                        .expect("memberships")
                        .push(json!({"document_id":id,"stack_id":stack}));
                }
                graph["documents"][index]["filing_origin"] = json!("manual");
                bump(&mut graph["documents"][index], "filing_decision")?;
                manual_choice(&mut graph, id, "filing", stacks.join(","))?;
                if delta.is_some() {
                    "document.membership.delta"
                } else {
                    "document.filing"
                }
            }
            DocumentEdit::Membership { .. } => {
                return Err(Error::Invalid("Invalid membership decision"));
            }
            DocumentEdit::SetDetail(mut detail) => {
                validation::validate_detail(&detail)?;
                if detail["document_id"] != *id || detail["state"] != "saved" {
                    return Err(Error::Invalid(
                        "Manual save needs a saved detail for this document",
                    ));
                }
                let field = detail["field_id"].as_str().expect("field ID").to_owned();
                if let Some(old) = graph["details"]
                    .as_array()
                    .expect("details")
                    .iter()
                    .find(|d| d["document_id"] == *id && d["field_id"] == field)
                {
                    // Ordinary value edits preserve source provenance. An empty editor
                    // evidence object means retain the prior source, never erase it.
                    if detail["evidence"] != "{}" && detail["evidence"] != old["evidence"] {
                        return Err(Error::Invalid(
                            "Manual edits must preserve existing source evidence",
                        ));
                    }
                    detail["evidence"] = old["evidence"].clone();
                }
                detail["origin"] = json!("manual");
                bump(&mut graph["documents"][index], "metadata_revision")?;
                detail["revision"] = graph["documents"][index]["metadata_revision"].clone();
                graph["details"]
                    .as_array_mut()
                    .expect("details")
                    .retain(|d| !(d["document_id"] == *id && d["field_id"] == field));
                graph["document_tags"]
                    .as_array_mut()
                    .expect("tags")
                    .retain(|d| !(d["document_id"] == *id && d["field_id"] == field));
                if detail["kind"] == "organization" {
                    let name = validation::clean_name(detail["value"].as_str().expect("value"))?;
                    if detail["value"] != name {
                        return Err(Error::Invalid("Organization name must be normalized"));
                    }
                    let tag = graph["tags"]
                        .as_array()
                        .expect("tags")
                        .iter()
                        .find(|t| {
                            validation::java_case_key(t["name"].as_str().expect("name"))
                                == validation::java_case_key(&name)
                        })
                        .map(|t| t["id"].as_str().expect("ID").to_owned());
                    let tag = match tag {
                        Some(id) => id,
                        None => {
                            let tag = Uuid::new_v4().to_string();
                            graph["tags"]
                                .as_array_mut()
                                .expect("tags")
                                .push(json!({"id":tag,"name":name}));
                            tag
                        }
                    };
                    graph["document_tags"]
                        .as_array_mut()
                        .expect("relations")
                        .push(json!({"document_id":id,"tag_id":tag,"field_id":field}));
                }
                graph["details"]
                    .as_array_mut()
                    .expect("details")
                    .push(detail);
                "document.detail.save"
            }
            DocumentEdit::RemoveDetail(field) => {
                bump(&mut graph["documents"][index], "metadata_revision")?;
                let revision = graph["documents"][index]["metadata_revision"].clone();
                let detail = graph["details"]
                    .as_array_mut()
                    .expect("details")
                    .iter_mut()
                    .find(|d| d["document_id"] == *id && d["field_id"] == field)
                    .ok_or(Error::Stale)?;
                detail["state"] = json!("removed");
                detail["origin"] = json!("edited");
                detail["revision"] = revision;
                graph["document_tags"]
                    .as_array_mut()
                    .expect("relations")
                    .retain(|d| !(d["document_id"] == *id && d["field_id"] == field));
                "document.detail.remove"
            }
            DocumentEdit::PageAppearance {
                page,
                rotation,
                crop,
                enhancement,
                flatten,
            } => {
                let row = graph["pages"]
                    .as_array_mut()
                    .expect("pages")
                    .iter_mut()
                    .find(|p| p["document_id"] == *id && p["id"] == page)
                    .ok_or(Error::Stale)?;
                row["rotation"] = json!(rotation);
                row["crop"] = json!(crop);
                row["enhancement"] = json!(enhancement);
                row["flatten"] = json!(i64::from(flatten));
                bump(row, "content_revision")?;
                row["text_revision"] = json!(-1);
                row["text_config"] = json!("");
                row["ocr_state"] = json!("pending");
                "document.page.appearance"
            }
            DocumentEdit::PageOrder(order) => {
                let selected: BTreeSet<_> = order.iter().collect();
                if order.is_empty()
                    || selected.len() != order.len()
                    || order.iter().any(|page| {
                        !graph["pages"]
                            .as_array()
                            .expect("pages")
                            .iter()
                            .any(|p| p["document_id"] == *id && p["id"] == *page)
                    })
                {
                    return Err(Error::Invalid(
                        "Choose a nonempty unique order of existing pages",
                    ));
                }
                graph["pages"].as_array_mut().expect("pages").retain(|p| {
                    p["document_id"] != *id
                        || selected.contains(&p["id"].as_str().expect("ID").to_owned())
                });
                for row in graph["pages"]
                    .as_array_mut()
                    .expect("pages")
                    .iter_mut()
                    .filter(|p| p["document_id"] == *id)
                {
                    row["position"] = json!(
                        order
                            .iter()
                            .position(|p| row["id"] == *p)
                            .expect("selected page")
                    );
                }
                graph["documents"][index]["page_count"] = json!(order.len());
                "document.page.order"
            }
        };
        graph["decision_evidence"][id] = json!(validation::current_evidence(&graph, id));
        let manifest = Manifest::from_value(graph)?;
        let payload = json!({"document_sync_id":sync_id(&self.db,"document",id)?,"before":before,"after":document_graph(manifest.json(),id),"membership_delta":delta});
        self.commit_graph(
            &manifest,
            kind,
            payload,
            &BTreeSet::from([id.clone()]),
            false,
            control,
        )?;
        self.token(id)
    }

    pub fn delete_document(&mut self, token: &DocumentToken, control: &Control) -> Result<()> {
        self.checked_token(token)?;
        control.check()?;
        let mut graph = self.manifest()?.into_json();
        let before = document_graph(&graph, &token.id);
        graph["documents"]
            .as_array_mut()
            .expect("docs")
            .retain(|d| d["id"] != token.id);
        for table in validation::TABLES
            .into_iter()
            .filter(|t| !matches!(*t, "documents" | "stacks" | "tags"))
        {
            graph[table]
                .as_array_mut()
                .expect("rows")
                .retain(|row| crate::library::owner(table, row) != Some(token.id.as_str()));
        }
        graph["decision_evidence"]
            .as_object_mut()
            .expect("evidence")
            .remove(&token.id);
        let manifest = Manifest::from_value(graph)?;
        let sync = sync_id(&self.db, "document", &token.id)?;
        let payload = json!({"document_sync_id":sync,"deleted":true,"retained":before});
        control.check()?;
        let tx = self.db.transaction()?;
        crate::library::write_graph(&tx, &manifest)?;
        tx.execute(
            "INSERT INTO tombstones VALUES('document',?1,?2)",
            rusqlite::params![sync, payload.to_string()],
        )?;
        crate::library::journal(&tx, "document.delete", payload)?;
        (self.probe)("metadata_transaction")?;
        control.check()?;
        tx.commit()?;
        Ok(())
    }

    pub fn edit_stacks(
        &mut self,
        expected_revision: i64,
        edit: StackEdit,
        control: &Control,
    ) -> Result<Option<String>> {
        if expected_revision != self.revision()? {
            return Err(Error::Stale);
        }
        let mut graph = self.manifest()?.into_json();
        let mut affected = BTreeSet::new();
        let mut created = None;
        let kind = match edit {
            StackEdit::Create {
                name,
                color,
                visible,
            } => {
                let name = validation::clean_name(&name)?;
                let id = Uuid::new_v4().to_string();
                let stacks = graph["stacks"].as_array_mut().expect("stacks");
                stacks.push(json!({"id":id,"name":name,"color":color,"visible":i64::from(visible),"sort_order":stacks.len()}));
                created = Some(id);
                "stack.create"
            }
            StackEdit::Rename { id, name } => {
                let name = validation::clean_name(&name)?;
                stack_mut(&mut graph, &id)?["name"] = json!(name);
                "stack.rename"
            }
            StackEdit::Visibility { id, visible } => {
                stack_mut(&mut graph, &id)?["visible"] = json!(i64::from(visible));
                "stack.visibility"
            }
            StackEdit::Color { id, color } => {
                stack_mut(&mut graph, &id)?["color"] = json!(color);
                "stack.color"
            }
            StackEdit::Order(order) => {
                let ids: BTreeSet<_> = graph["stacks"]
                    .as_array()
                    .expect("stacks")
                    .iter()
                    .map(|s| s["id"].as_str().expect("ID").to_owned())
                    .collect();
                if order.len() != ids.len()
                    || order.iter().collect::<BTreeSet<_>>().len() != ids.len()
                    || order.iter().any(|id| !ids.contains(id))
                {
                    return Err(Error::Invalid(
                        "Stack order must include every current stack once",
                    ));
                }
                for (position, id) in order.iter().enumerate() {
                    stack_mut(&mut graph, id)?["sort_order"] = json!(position);
                }
                graph["stacks"]
                    .as_array_mut()
                    .expect("stacks")
                    .sort_by_key(|s| s["sort_order"].as_i64().expect("position"));
                "stack.order"
            }
            StackEdit::Delete(id) => {
                stack_mut(&mut graph, &id)?;
                for relation in graph["memberships"]
                    .as_array()
                    .expect("memberships")
                    .iter()
                    .filter(|m| m["stack_id"] == id)
                {
                    affected.insert(relation["document_id"].as_str().expect("doc ID").to_owned());
                }
                graph["memberships"]
                    .as_array_mut()
                    .expect("memberships")
                    .retain(|m| m["stack_id"] != id);
                graph["stacks"]
                    .as_array_mut()
                    .expect("stacks")
                    .retain(|s| s["id"] != id);
                for (position, row) in graph["stacks"]
                    .as_array_mut()
                    .expect("stacks")
                    .iter_mut()
                    .enumerate()
                {
                    row["sort_order"] = json!(position);
                }
                for doc in graph["documents"]
                    .as_array_mut()
                    .expect("docs")
                    .iter_mut()
                    .filter(|d| affected.contains(d["id"].as_str().expect("ID")))
                {
                    doc["filing_origin"] = json!("manual");
                    bump(doc, "filing_decision")?;
                }
                for doc in &affected {
                    let selected = graph["memberships"]
                        .as_array()
                        .expect("memberships")
                        .iter()
                        .filter(|m| m["document_id"] == *doc)
                        .map(|m| m["stack_id"].as_str().expect("stack ID"))
                        .collect::<Vec<_>>()
                        .join(",");
                    manual_choice(&mut graph, doc, "filing", selected)?;
                }
                "stack.delete"
            }
        };
        let manifest = Manifest::from_value(graph)?;
        self.commit_graph(
            &manifest,
            kind,
            json!({"catalog":manifest.rows("stacks"),"memberships":manifest.rows("memberships"),"affected_documents":affected.iter().map(|id|document_graph(manifest.json(),id)).collect::<Vec<_>>()}),
            &affected,
            false,
            control,
        )?;
        Ok(created)
    }

    /// Commit a future local OCR job only against its original document/page input.
    /// This stores submitted text; no desktop OCR engine is implemented here.
    pub fn commit_page_text(
        &mut self,
        token: &DocumentToken,
        page_id: &str,
        expected_content_revision: i64,
        text: &str,
        config: &str,
        control: &Control,
    ) -> Result<DocumentToken> {
        self.checked_token(token)?;
        let mut graph = self.manifest()?.into_json();
        let page = graph["pages"]
            .as_array_mut()
            .expect("pages")
            .iter_mut()
            .find(|p| p["id"] == page_id && p["document_id"] == token.id)
            .ok_or(Error::Stale)?;
        if page["content_revision"].as_i64() != Some(expected_content_revision) {
            return Err(Error::Stale);
        }
        page["ocr_text"] = json!(text);
        page["ocr_config"] = json!(config);
        page["text_config"] = json!(config);
        page["text_revision"] = json!(expected_content_revision);
        page["ocr_state"] = json!("succeeded");
        page["ocr_error"] = json!("");
        graph["decision_evidence"][&token.id] =
            json!(validation::current_evidence(&graph, &token.id));
        let manifest = Manifest::from_value(graph)?;
        self.commit_graph(&manifest,"page.text.commit",json!({"page_id":page_id,"document_sync_id":sync_id(&self.db,"document",&token.id)?,"graph":document_graph(manifest.json(),&token.id)}),&BTreeSet::from([token.id.clone()]),false,control)?;
        self.token(&token.id)
    }
}

fn doc_index(graph: &Value, id: &str) -> Result<usize> {
    graph["documents"]
        .as_array()
        .expect("docs")
        .iter()
        .position(|d| d["id"] == id)
        .ok_or(Error::Stale)
}
fn stack_mut<'a>(graph: &'a mut Value, id: &str) -> Result<&'a mut Value> {
    graph["stacks"]
        .as_array_mut()
        .expect("stacks")
        .iter_mut()
        .find(|s| s["id"] == id)
        .ok_or(Error::Stale)
}
fn bump(row: &mut Value, key: &str) -> Result<()> {
    let current = row[key]
        .as_i64()
        .filter(|v| *v < i64::MAX - 1)
        .ok_or(Error::Invalid("Decision revision exhausted"))?;
    row[key] = json!(current + 1);
    Ok(())
}
fn manual_choice(graph: &mut Value, id: &str, field: &str, value: String) -> Result<()> {
    let evidence = validation::current_evidence(graph, id);
    graph["choices"]
        .as_array_mut()
        .expect("choices")
        .retain(|c| !(c["document_id"] == id && c["field"] == field && c["evidence"] == evidence));
    graph["choices"].as_array_mut().expect("choices").push(
        json!({"document_id":id,"evidence":evidence,"field":field,"choice":"edited","value":value}),
    );
    let suggestion = graph["suggestions"]
        .as_array_mut()
        .expect("suggestions")
        .iter_mut()
        .find(|s| s["document_id"] == id)
        .ok_or(Error::Stale)?;
    suggestion[format!("{field}_state")] = json!("edited");
    Ok(())
}
pub(crate) fn document_graph(graph: &Value, id: &str) -> Value {
    let mut result = json!({});
    result["documents"] = json!(
        graph["documents"]
            .as_array()
            .expect("docs")
            .iter()
            .filter(|d| d["id"] == id)
            .collect::<Vec<_>>()
    );
    for table in validation::TABLES
        .into_iter()
        .filter(|t| !matches!(*t, "documents" | "stacks" | "tags"))
    {
        result[table] = json!(
            graph[table]
                .as_array()
                .expect("rows")
                .iter()
                .filter(|row| crate::library::owner(table, row) == Some(id))
                .collect::<Vec<_>>()
        );
    }
    result["decision_evidence"] = graph["decision_evidence"][id].clone();
    result
}
