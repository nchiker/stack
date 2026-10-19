//! Portable graph projection. Sync history is deliberately outside format 3.
use crate::{Error, Result};
use serde_json::{Value, json};
use stack_archive::{Manifest, validation};
use std::collections::BTreeMap;

pub type Cells = BTreeMap<String, Value>;

fn key(table: &str, row: &Value) -> Result<String> {
    let fields: &[&str] = match table {
        "documents" | "pages" | "stacks" | "tags" => &["id"],
        "files" => &["path"],
        "suggestions" => &["document_id"],
        "memberships" => &["document_id", "stack_id"],
        "choices" => &["document_id", "evidence", "field"],
        "details" => &["document_id", "field_id"],
        "document_tags" => &["document_id", "tag_id"],
        _ => return Err(Error::Invalid("unsupported graph table")),
    };
    Ok(format!(
        "{table}/{}",
        serde_json::to_string(&fields.iter().map(|k| &row[*k]).collect::<Vec<_>>())?
    ))
}

fn take_fields(row: &mut Value, fields: &[&str]) -> Value {
    let mut output = serde_json::Map::new();
    for field in fields {
        if let Some(value) = row
            .as_object_mut()
            .expect("validated object")
            .remove(*field)
        {
            output.insert((*field).to_owned(), value);
        }
    }
    Value::Object(output)
}
fn extend(row: &mut Value, fields: &Value) -> Result<()> {
    let target = row
        .as_object_mut()
        .ok_or(Error::Invalid("decision target is not an object"))?;
    for (field, value) in fields
        .as_object()
        .ok_or(Error::Invalid("missing decision object"))?
    {
        target.insert(field.clone(), value.clone());
    }
    Ok(())
}
fn input(base: &Value, appearance: &Value) -> Result<Value> {
    let mut value = base.clone();
    extend(&mut value, appearance)?;
    Ok(value)
}
pub const GRAPH_MODEL: u32 = 2;

pub fn cells(manifest: &Manifest) -> Result<Cells> {
    let mut cells = Cells::new();
    for table in validation::TABLES {
        for row in manifest.rows(table) {
            let key = key(table, row)?;
            let mut base = row.clone();
            match table {
                "documents" => {
                    let title = take_fields(
                        &mut base,
                        &[
                            "title",
                            "title_origin",
                            "title_user_edited",
                            "title_decision",
                        ],
                    );
                    let notes = take_fields(&mut base, &["notes", "notes_revision"]);
                    base.as_object_mut().expect("document").remove("page_count");
                    for field in ["filing_origin", "filing_decision", "metadata_revision"] {
                        let value = take_fields(&mut base, &[field]);
                        cells.insert(format!("{key}/{field}"), value);
                    }
                    cells.insert(format!("{key}/title"), title);
                    cells.insert(format!("{key}/notes"), notes);
                    let id = row["id"].as_str().expect("doc id");
                    let mut filing: Vec<_> = manifest
                        .rows("memberships")
                        .iter()
                        .filter(|m| m["document_id"] == id)
                        .map(|m| m["stack_id"].clone())
                        .collect();
                    filing.sort_by_key(Value::to_string);
                    cells.insert(format!("filing_review/{id}"), json!(filing));
                    let mut pages: Vec<_> = manifest
                        .rows("pages")
                        .iter()
                        .filter(|p| p["document_id"] == id)
                        .collect();
                    pages.sort_by_key(|p| p["position"].as_i64());
                    cells.insert(
                        format!("page_order/{id}"),
                        json!(pages.iter().map(|p| p["id"].clone()).collect::<Vec<_>>()),
                    );
                }
                "pages" => {
                    let source = take_fields(
                        &mut base,
                        &["id", "document_id", "source_file", "source_page"],
                    );
                    base.as_object_mut().expect("page").remove("position");
                    let appearance = take_fields(
                        &mut base,
                        &[
                            "rotation",
                            "crop",
                            "enhancement",
                            "flatten",
                            "content_revision",
                        ],
                    );
                    cells.insert(
                        format!("{key}/text"),
                        json!({"input":input(&source,&appearance)?,"row":base}),
                    );
                    cells.insert(format!("{key}/appearance"), appearance);
                    base = source;
                }
                "stacks" => {
                    base.as_object_mut().expect("stack").remove("sort_order");
                    for field in ["name", "color", "visible"] {
                        let value = take_fields(&mut base, &[field]);
                        cells.insert(format!("{key}/{field}"), value);
                    }
                }
                _ => {}
            }
            cells.insert(key, base);
        }
    }
    let mut stacks: Vec<_> = manifest.rows("stacks").iter().collect();
    stacks.sort_by_key(|s| s["sort_order"].as_i64());
    cells.insert(
        "catalog_order/stacks".into(),
        json!(stacks.iter().map(|s| s["id"].clone()).collect::<Vec<_>>()),
    );
    Ok(cells)
}

pub fn diff(before: &Cells, after: &Cells) -> Cells {
    let mut changes: Cells = after
        .iter()
        .filter(|(k, v)| before.get(*k) != Some(*v))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    for key in before.keys().filter(|k| !after.contains_key(*k)) {
        changes.insert(key.clone(), Value::Null);
    }
    changes
}

pub fn project(cells: &Cells) -> Result<Manifest> {
    let mut graph = Manifest::empty().into_json();
    for (key, value) in cells {
        if value.is_null()
            || [
                "/title",
                "/notes",
                "/appearance",
                "/text",
                "/name",
                "/color",
                "/visible",
                "/filing_origin",
                "/filing_decision",
                "/metadata_revision",
            ]
            .iter()
            .any(|suffix| key.ends_with(suffix))
            || key.starts_with("page_order/")
            || key.starts_with("catalog_order/")
            || key.starts_with("filing_review/")
        {
            continue;
        }
        let (table, _) = key
            .split_once('/')
            .ok_or(Error::Invalid("invalid graph cell"))?;
        if !validation::TABLES.contains(&table) {
            return Err(Error::Invalid("unsupported graph cell"));
        }
        if !value.is_object() {
            return Err(Error::Invalid("graph rows must be objects"));
        }
        let mut row = value.clone();
        match table {
            "documents" => {
                for suffix in [
                    "/title",
                    "/notes",
                    "/filing_origin",
                    "/filing_decision",
                    "/metadata_revision",
                ] {
                    extend(
                        &mut row,
                        cells
                            .get(&format!("{key}{suffix}"))
                            .ok_or(Error::Invalid("missing document decision"))?,
                    )?;
                }
            }
            "stacks" => {
                for suffix in ["/name", "/color", "/visible"] {
                    extend(
                        &mut row,
                        cells
                            .get(&format!("{key}{suffix}"))
                            .ok_or(Error::Invalid("missing stack decision"))?,
                    )?;
                }
            }
            "pages" => {
                let appearance = cells
                    .get(&format!("{key}/appearance"))
                    .ok_or(Error::Invalid("missing saved appearance"))?;
                let expected = input(value, appearance)?;
                extend(&mut row, appearance)?;
                let text = cells
                    .get(&format!("{key}/text"))
                    .ok_or(Error::Invalid("missing page text decision"))?;
                extend(&mut row, &text["row"])?;
                if text["input"] != expected {
                    // Equal numeric revisions on two replicas do not prove that
                    // an OCR result describes the same crop/rotation/source.
                    row["ocr_text"] = json!("");
                    row["ocr_state"] = json!("pending");
                    row["text_revision"] = json!(-1);
                    row["text_config"] = json!("");
                    row["ocr_error"] = json!("");
                }
            }
            _ => {}
        }
        graph[table].as_array_mut().expect("table").push(row);
    }
    let docs: std::collections::BTreeSet<String> = graph["documents"]
        .as_array()
        .expect("docs")
        .iter()
        .map(|d| d["id"].as_str().unwrap_or_default().to_owned())
        .collect();
    for table in validation::TABLES
        .into_iter()
        .filter(|t| !matches!(*t, "documents" | "stacks" | "tags"))
    {
        graph[table].as_array_mut().expect("rows").retain(|row| {
            let id = if table == "files" {
                row["path"]
                    .as_str()
                    .and_then(|p| stack_archive::original_path(p).ok().map(|(id, _)| id))
            } else {
                row["document_id"].as_str()
            };
            id.is_some_and(|id| docs.contains(id))
        });
    }
    for document in graph["documents"].as_array_mut().expect("documents") {
        let id = document["id"]
            .as_str()
            .ok_or(Error::Invalid("document identity missing"))?;
        let order = cells
            .get(&format!("page_order/{id}"))
            .and_then(Value::as_array)
            .ok_or(Error::Invalid("missing whole page order"))?;
        if order.is_empty() || order.iter().any(|id| !id.is_string()) {
            return Err(Error::Invalid("invalid whole page order"));
        }
        document["page_count"] = json!(order.len());
    }
    for id in &docs {
        let order = cells[&format!("page_order/{id}")]
            .as_array()
            .expect("checked order");
        let mut seen = std::collections::BTreeSet::new();
        for (position, page_id) in order.iter().enumerate() {
            if !seen.insert(page_id.to_string()) {
                return Err(Error::Invalid("duplicate page in whole order"));
            }
            let page = graph["pages"]
                .as_array_mut()
                .expect("pages")
                .iter_mut()
                .find(|p| p["document_id"] == *id && p["id"] == *page_id)
                .ok_or(Error::Invalid("page order references an unavailable page"))?;
            page["position"] = json!(position);
        }
        graph["pages"]
            .as_array_mut()
            .expect("pages")
            .retain(|page| page["document_id"] != *id || seen.contains(&page["id"].to_string()));
    }
    let stack_order = cells
        .get("catalog_order/stacks")
        .and_then(Value::as_array)
        .ok_or(Error::Invalid("missing whole catalog order"))?;
    let mut seen = std::collections::BTreeSet::new();
    for id in stack_order {
        if !id.is_string() {
            return Err(Error::Invalid("invalid whole catalog order"));
        }
        let base = format!("stacks/{}", serde_json::to_string(&[id])?);
        if cells.get(&base).is_some_and(Value::is_null) {
            continue;
        }
        if !seen.insert(id.to_string()) {
            return Err(Error::Invalid("duplicate stack in catalog order"));
        }
        let row = graph["stacks"]
            .as_array_mut()
            .expect("stacks")
            .iter_mut()
            .find(|s| s["id"] == *id)
            .ok_or(Error::Invalid("catalog order references unavailable stack"))?;
        row["sort_order"] = json!(seen.len() - 1);
    }
    if seen.len() != graph["stacks"].as_array().expect("stacks").len() {
        return Err(Error::Invalid("catalog order omits a stack"));
    }
    graph["pages"]
        .as_array_mut()
        .expect("pages")
        .sort_by_key(|p| {
            (
                p["document_id"].as_str().unwrap_or_default().to_owned(),
                p["position"].as_i64(),
            )
        });
    graph["stacks"]
        .as_array_mut()
        .expect("stacks")
        .sort_by_key(|s| s["sort_order"].as_i64());
    for id in docs {
        graph["decision_evidence"][&id] = json!(validation::current_evidence(&graph, &id));
    }
    Manifest::from_value(graph).map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn graph_decisions_round_trip_exactly_and_independent_fields_compose() {
        let dir = tempfile::tempdir().unwrap();
        let original = stack_archive::synthetic::create(
            &dir.path().join("authored"),
            [b"pdf", b"jpg", b"png", b"webp"],
        )
        .unwrap();
        let cells = cells(&original).unwrap();
        let actual = project(&cells).unwrap();
        for table in validation::TABLES {
            let mut left = original.rows(table).to_vec();
            let mut right = actual.rows(table).to_vec();
            if !matches!(table, "pages" | "stacks") {
                left.sort_by_key(Value::to_string);
                right.sort_by_key(Value::to_string);
            }
            assert_eq!(left, right, "{table}");
        }
        assert!(diff(&cells, &cells).is_empty());
    }
}

#[cfg(test)]
mod causal_tests {
    use super::*;
    use crate::State;
    use std::collections::BTreeSet;
    #[test]
    fn whole_order_conflicts_and_same_number_different_crop_text_are_preserved() {
        let root = tempfile::tempdir().unwrap();
        let baseline = stack_archive::synthetic::create(
            &root.path().join("authored"),
            [b"pdf", b"jpg", b"png", b"webp"],
        )
        .unwrap();
        let ids = [
            uuid::Uuid::new_v4().to_string(),
            uuid::Uuid::new_v4().to_string(),
        ];
        let mut a = State::new(
            uuid::Uuid::new_v4().to_string(),
            ids.iter().cloned().collect::<BTreeSet<_>>(),
        )
        .unwrap();
        a.local(
            &ids[0],
            cells(&baseline).unwrap(),
            json!({"kind":"baseline"}),
        )
        .unwrap();
        let mut b = a.clone();
        let doc = baseline.rows("documents")[0]["id"].as_str().unwrap();
        let page = &baseline.rows("pages")[0];
        let pageid = page["id"].as_str().unwrap();
        let edit = |crop: &str, text: &str| {
            let mut graph = baseline.json().clone();
            let page = graph["pages"]
                .as_array_mut()
                .unwrap()
                .iter_mut()
                .find(|p| p["id"] == pageid)
                .unwrap();
            page["crop"] = json!(crop);
            page["content_revision"] = json!(1);
            page["text_revision"] = json!(1);
            page["ocr_text"] = json!(text);
            Manifest::from_value(graph).unwrap()
        };
        let left = edit("[0,0,0.5,0,0.5,1,0,1]", "leftsentinel");
        let right = edit("[0.5,0,1,0,1,1,0.5,1]", "rightsentinel");
        let old = cells(&baseline).unwrap();
        let left = a
            .local(
                &ids[0],
                diff(&old, &cells(&left).unwrap()),
                json!({"kind":"manual-left"}),
            )
            .unwrap();
        let right = b
            .local(
                &ids[1],
                diff(&old, &cells(&right).unwrap()),
                json!({"kind":"manual-right"}),
            )
            .unwrap();
        a.receive(&[right]).unwrap();
        b.receive(&[left]).unwrap();
        assert_eq!(a.values(), b.values());
        let appearance = format!("pages/[\"{pageid}\"]/appearance");
        let current_text =
            a.values()[&format!("pages/[\"{pageid}\"]/text")]["row"]["ocr_text"].clone();
        let opposite = if current_text == "leftsentinel" {
            &ids[1]
        } else {
            &ids[0]
        };
        let chosen = a.registers[&appearance]
            .iter()
            .find(|v| &v.id.replica == opposite)
            .unwrap()
            .value
            .clone();
        a.local(
            &ids[0],
            BTreeMap::from([(appearance, chosen)]),
            json!({"kind":"review-appearance"}),
        )
        .unwrap();
        let projected = project(&a.values()).unwrap();
        let row = projected
            .rows("pages")
            .iter()
            .find(|p| p["id"] == pageid)
            .unwrap();
        assert_eq!(row["ocr_text"], "");
        assert_eq!(row["text_revision"], -1);
        // Two whole-list decisions conflict even when the affected row-level
        // position changes would otherwise be disjoint.
        let key = format!("page_order/{doc}");
        let order = old[&key].as_array().unwrap();
        assert!(order.len() >= 2);
        let mut reversed = order.clone();
        reversed.reverse();
        let mut x = b.clone();
        let mut y = b;
        let xop = x
            .local(
                &ids[0],
                BTreeMap::from([(key.clone(), json!(reversed))]),
                json!("reorder"),
            )
            .unwrap();
        let yop = y
            .local(
                &ids[1],
                BTreeMap::from([(key.clone(), json!([order[0]]))]),
                json!("remove"),
            )
            .unwrap();
        x.receive(&[yop]).unwrap();
        y.receive(&[xop]).unwrap();
        assert_eq!(x.conflicts(), y.conflicts());
        assert!(x.conflicts().contains_key(&key));
    }
    #[test]
    fn malformed_appearance_is_an_error_instead_of_native_panic() {
        let root = tempfile::tempdir().unwrap();
        let baseline = stack_archive::synthetic::create(
            &root.path().join("authored"),
            [b"pdf", b"jpg", b"png", b"webp"],
        )
        .unwrap();
        let mut cells = cells(&baseline).unwrap();
        let key = cells
            .keys()
            .find(|k| k.ends_with("/appearance"))
            .unwrap()
            .clone();
        cells.insert(key, json!("invalid"));
        assert!(project(&cells).is_err());
    }
}
