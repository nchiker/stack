use serde_json::{Value, json};
use stack_sync::{Operation, State};

#[test]
fn shared_java_rust_golden_vectors() {
    let vectors: Value =
        serde_json::from_str(include_str!("../../../protocol/golden-v1.json")).unwrap();
    for case in vectors["cases"].as_array().unwrap() {
        let members = case["members"]
            .as_array()
            .unwrap()
            .iter()
            .map(|m| m.as_str().unwrap().to_owned())
            .collect();
        let mut state = State::new(case["library"].as_str().unwrap().to_owned(), members).unwrap();
        for op in case["operations"].as_array().unwrap() {
            let op: Operation = serde_json::from_value(op.clone()).unwrap();
            state.receive(&[op]).unwrap();
            state = serde_json::from_slice(&serde_json::to_vec(&state).unwrap()).unwrap();
        }
        let actual = json!({"values":state.values(),"frontier":state.frontier,"conflicts":state.conflicts().keys().collect::<Vec<_>>(),"pending":state.pending.len()});
        assert_eq!(actual, case["expected"], "{}", case["name"]);
    }
}

#[test]
fn shared_java_rust_rejected_vectors_are_atomic() {
    let vectors: Value =
        serde_json::from_str(include_str!("../../../protocol/golden-v1.json")).unwrap();
    for case in vectors["reject_cases"].as_array().unwrap() {
        let mut state = State::new(
            case["library"].as_str().unwrap().to_owned(),
            serde_json::from_value(case["members"].clone()).unwrap(),
        )
        .unwrap();
        let before = serde_json::to_value(&state).unwrap();
        let operation: Operation = serde_json::from_value(case["operation"].clone()).unwrap();
        assert!(state.receive(&[operation]).is_err(), "{}", case["name"]);
        assert_eq!(before, serde_json::to_value(&state).unwrap());
    }
}

// ---------------------------------------------------------------------------
// Link graph cases (`graph_cases`, slice 6 design section 8, work item D1).
//
// All of these run against the link arm in `stack_sync::links` (D2).
// ---------------------------------------------------------------------------

fn graph_cases() -> Value {
    let vectors: Value =
        serde_json::from_str(include_str!("../../../protocol/golden-v1.json")).unwrap();
    expand(vectors["graph_cases"].clone())
}

/// `{"repeat": s, "count": n}` stands for `s` repeated `n` times.
fn expand(value: Value) -> Value {
    match value {
        Value::Object(map) if map.len() == 2 && map.contains_key("repeat") => {
            let unit = map["repeat"].as_str().expect("repeat is a string");
            let count = map["count"].as_u64().expect("count is an integer") as usize;
            Value::String(unit.repeat(count))
        }
        Value::Object(map) => Value::Object(map.into_iter().map(|(k, v)| (k, expand(v))).collect()),
        Value::Array(items) => Value::Array(items.into_iter().map(expand).collect()),
        other => other,
    }
}

fn cells_of(changes: &Value) -> stack_sync::graph::Cells {
    changes
        .as_object()
        .expect("changes is an object")
        .iter()
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect()
}

fn named<'a>(cases: &'a Value, list: &str) -> &'a Vec<Value> {
    cases[list].as_array().unwrap_or_else(|| panic!("{list}"))
}

#[test]
fn graph_cases_are_present_and_well_formed() {
    let cases = graph_cases();
    assert_eq!(cases["format"], "org.stackdocs.link-graph-golden");
    assert_eq!(cases["pending"], json!({"rust": "D2", "java": "D3"}));
    let mut seen = std::collections::BTreeSet::new();
    for list in [
        "receiver_rejects",
        "receiver_accepts_skips",
        "writer_refuses",
        "enable_ops",
    ] {
        let items = named(&cases, list);
        assert!(!items.is_empty(), "{list} is empty");
        for case in items {
            let name = case["name"].as_str().expect("case name");
            assert!(seen.insert(format!("{list}/{name}")), "duplicate {name}");
            match list {
                "receiver_rejects" => {
                    assert!(case["changes"].is_object(), "{name}");
                    assert!(case["why"].is_string(), "{name}");
                }
                "receiver_accepts_skips" => {
                    assert!(case["changes"].is_object(), "{name}");
                    let projection = case["projection"].as_str().expect(name);
                    assert!(
                        [
                            "applied",
                            "applied_metadata_only",
                            "skipped",
                            "gone",
                            "not_applicable"
                        ]
                        .contains(&projection),
                        "{name}"
                    );
                }
                "writer_refuses" => {
                    assert!(case["input"]["row"].is_object(), "{name}");
                    let outcome = case["expect"]["outcome"].as_str().expect(name);
                    assert!(
                        ["emitted", "row_refused", "cell_skipped"].contains(&outcome),
                        "{name}"
                    );
                }
                _ => {
                    assert!(case["operation_changes"].is_object(), "{name}");
                    assert!(case["expect"]["kernel"].is_string(), "{name}");
                    assert!(case["expect"]["receiver"].is_string(), "{name}");
                }
            }
        }
    }
    println!(
        "graph_cases: {} receiver_rejects, {} receiver_accepts_skips, {} writer_refuses, {} enable_ops (link arm pending D2)",
        named(&cases, "receiver_rejects").len(),
        named(&cases, "receiver_accepts_skips").len(),
        named(&cases, "writer_refuses").len(),
        named(&cases, "enable_ops").len()
    );
}

/// Kernel-only half of the enable op shapes: runs today.
#[test]
fn graph_enable_ops_against_the_unchanged_kernel() {
    let cases = graph_cases();
    let library = "10000000-0000-0000-0000-000000000000";
    let replica = "20000000-0000-0000-0000-000000000001";
    for case in named(&cases, "enable_ops") {
        let mut state = State::new(library.to_owned(), [replica.to_owned()].into()).unwrap();
        let operation: Operation = serde_json::from_value(json!({
            "version": 1, "library": library, "epoch": 1,
            "id": {"replica": replica, "sequence": 1},
            "context": {}, "changes": case["operation_changes"], "evidence": null
        }))
        .unwrap();
        let result = state.receive(&[operation]);
        match case["expect"]["kernel"].as_str().unwrap() {
            "accepted" => assert!(result.is_ok(), "{}", case["name"]),
            "rejected" => assert!(result.is_err(), "{}", case["name"]),
            other => panic!("unknown kernel expectation {other}"),
        }
    }
}

#[test]
fn graph_receiver_rejects() {
    let cases = graph_cases();
    for case in named(&cases, "receiver_rejects") {
        let result = stack_sync::graph::validate_changes(&cells_of(&case["changes"]));
        assert!(result.is_err(), "must reject: {}", case["name"]);
    }
}

#[test]
fn graph_receiver_accepts_skips() {
    let cases = graph_cases();
    for case in named(&cases, "receiver_accepts_skips") {
        let name = &case["name"];
        let cells = cells_of(&case["changes"]);
        stack_sync::graph::validate_changes(&cells)
            .unwrap_or_else(|error| panic!("must accept {name}: {error}"));
        let projection = stack_sync::graph::project_links(&cells, &Default::default());
        let actual = if projection.skipped == 1 {
            "skipped"
        } else if projection.gone.len() == 1 {
            "gone"
        } else if let [row] = projection.rows.as_slice() {
            if row.metadata_only {
                "applied_metadata_only"
            } else {
                "applied"
            }
        } else {
            "not_applicable"
        };
        assert_eq!(actual, case["projection"].as_str().unwrap(), "{name}");
        let expected_skips = case["skip_count"].as_u64().unwrap_or(0) as usize;
        assert_eq!(projection.skipped, expected_skips, "{name} skip_count");
        assert_eq!(projection.conflicted, 0, "{name}");
        if case["projection"] == "applied_metadata_only" {
            let row = &projection.rows[0];
            assert!(row.text.is_none() && row.preview.is_none(), "{name}");
        }
    }
}

fn int(row: &Value, key: &str) -> i64 {
    // 2^53 is exact in f64 and i64; serde_json keeps it as an integer.
    row[key].as_i64().unwrap_or_else(|| panic!("{key} integer"))
}

fn writer_input(row: &Value) -> stack_sync::links::LinkInput {
    use stack_sync::links::{LinkBlob, LinkFilen, LinkInput, LinkPresence, LinkState};
    let s = |k: &str| row[k].as_str().unwrap().to_owned();
    let filen = |v: &Value| LinkFilen {
        uuid: v["uuid"].as_str().unwrap().to_owned(),
        path: v["path"].as_str().unwrap().to_owned(),
    };
    let locators = match row.get("filen_locators").and_then(Value::as_array) {
        Some(list) => list.iter().map(filen).collect(),
        None if row["filen"].is_object() => vec![filen(&row["filen"])],
        None => vec![],
    };
    LinkInput {
        owner: s("owner"),
        link_id: s("link_id"),
        kind: s("kind"),
        added_at: int(row, "added_at"),
        hash: s("hash"),
        mime: s("mime"),
        modified: int(row, "modified"),
        shared: int(row, "shared") == 1,
        protected: int(row, "protected") == 1,
        title: s("title"),
        title_user_edited: int(row, "title_user_edited") == 1,
        filen: locators,
        text: row["text"].as_str().map(str::to_owned),
        preview: row["preview"].as_object().map(|p| LinkBlob {
            sha256: p["sha256"].as_str().unwrap().to_owned(),
            length: p["length"].as_u64().unwrap(),
        }),
        presence: LinkPresence {
            state: LinkState::parse(row["presence"]["state"].as_str().unwrap()).unwrap(),
            reason: row["presence"]["reason"].as_str().unwrap().to_owned(),
            since: int(&row["presence"], "since"),
        },
        omit_text: false,
        omit_preview: false,
    }
}

/// Input `{row, context?}` -> `{"outcome": ..., "cells": {suffix: value}}`.
fn writer_self_check(input: &Value) -> Value {
    use stack_sync::links::{WriterOutcome, writer_self_check};
    let captured = input["context"]["live_or_trashed_links_already_captured"]
        .as_u64()
        .unwrap_or(0) as usize;
    let result = writer_self_check(&writer_input(&input["row"]), captured);
    let outcome = match result.outcome {
        WriterOutcome::Emitted => "emitted",
        WriterOutcome::CellSkipped => "cell_skipped",
        WriterOutcome::RowRefused => "row_refused",
    };
    json!({"outcome": outcome, "cells": result.cells})
}

#[test]
fn graph_writer_refuses() {
    let cases = graph_cases();
    for case in named(&cases, "writer_refuses") {
        let name = &case["name"];
        let actual = writer_self_check(&case["input"]);
        let expect = &case["expect"];
        assert_eq!(actual["outcome"], expect["outcome"], "{name}");
        if let Some(cells) = expect["cells"].as_object() {
            for (suffix, value) in cells {
                assert_eq!(&actual["cells"][suffix], value, "{name} {suffix}");
            }
        }
        for suffix in expect["absent"].as_array().into_iter().flatten() {
            let suffix = suffix.as_str().unwrap();
            assert!(
                actual["cells"].get(suffix).is_none_or(Value::is_null),
                "{name} must not emit {suffix}"
            );
        }
    }
}

#[test]
fn graph_enable_ops_receiver_side() {
    let cases = graph_cases();
    for case in named(&cases, "enable_ops") {
        let result = stack_sync::graph::validate_changes(&cells_of(&case["operation_changes"]));
        match case["expect"]["receiver"].as_str().unwrap() {
            "accepted" => assert!(result.is_ok(), "{}", case["name"]),
            "rejected" => assert!(result.is_err(), "{}", case["name"]),
            _ => {}
        }
    }
}
