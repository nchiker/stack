//! Authored public M1 graph, version 1 (generated content CC0-1.0).
//! Callers supply the four M0 synthetic original byte streams; never private inputs.
use crate::{Control, Manifest, Result};
use serde_json::{Value, json};
use std::{fs, io::Write, path::Path};
use uuid::Uuid;

pub fn id(number: u128) -> String {
    Uuid::from_u128(number).to_string()
}

pub fn create(root: &Path, originals: [&[u8]; 4]) -> Result<Manifest> {
    fs::create_dir(root)?;
    fs::create_dir(root.join("originals"))?;
    let mut graph = Manifest::empty().into_json();
    graph["created_at"] = json!(1700000000000_i64);
    graph["stacks"] = json!([
        {"id":"custom-first","name":"İnvoices","color":-123456,"visible":0,"sort_order":0},
        {"id":"receipts","name":"Receipts","color":-22,"visible":1,"sort_order":1},
        {"id":"custom-last","name":"Archive 🦀","color":42,"visible":1,"sort_order":2}]);
    for (index, mime) in [
        "application/pdf",
        "application/x-stack-scan",
        "image/jpeg",
        "image/png",
        "image/webp",
    ]
    .iter()
    .enumerate()
    {
        let doc = id(index as u128 + 1);
        let count = if index < 2 { 2 } else { 1 };
        graph["documents"].as_array_mut().unwrap().push(json!({"id":doc,"title":format!("Synthetic {index} 🦀"),"mime":mime,
            "added_at":1700000000000_i64+index as i64,"page_count":count,"initial_page_count":if index==1 {3}else{count},
            "title_user_edited":1,"source_scan_id":if index==1 {Some(id(600))}else{None},"title_origin":"manual","filing_origin":"manual",
            "title_decision":2,"filing_decision":3,"notes":"Exact notes\nzero 0000123; café 🦀; noteonlysentinel","notes_revision":4,"metadata_revision":20}));
        for position in 0..count {
            let page = id(100 + index as u128 * 10 + position as u128);
            let filename = if index == 1 {
                format!("{}.jpg", id(300 + position as u128))
            } else {
                "source".into()
            };
            graph["pages"].as_array_mut().unwrap().push(json!({"id":page,"document_id":doc,"position":position,
                "source_page":if index==0 {position}else{0},"rotation":if index==1 {90}else{0},
                "crop":if index==1 {"[0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9]"}else{""},
                "enhancement":if index==1 {"medium"}else{"original"},"flatten":if index==1 {1}else{0},
                "ocr_text":if position==1 {"laterpagesentinel synthetic current text 🦀"}else{"first page synthetic café 🦀"},
                "ocr_state":"succeeded","ocr_engine":"synthetic-engine","language":"eng","source_file":filename,
                "content_revision":2,"text_revision":2,"ocr_error":"","ocr_config":"synthetic-config-1","text_config":"synthetic-config-1"}));
        }
        graph["suggestions"].as_array_mut().unwrap().push(json!({"document_id":doc,"generation":7,"status":"ready","input":"a".repeat(64),
            "evidence":"b".repeat(64),"rule":"synthetic-v1","title_revision":1,"filing_revision":1,
            "payload":"{\"title\":\"Historical proposal\",\"partial\":true,\"truncated\":false,\"stacks\":{\"receipts\":\"synthetic source\"}}",
            "title_state":"edited","filing_state":"dismissed"}));
        if index == 0 {
            graph["memberships"]
                .as_array_mut()
                .unwrap()
                .push(json!({"document_id":doc,"stack_id":"custom-first"}));
        }
        let files: Vec<(String, &[u8])> = if index == 1 {
            (0..3)
                .map(|n| (format!("{}.jpg", id(300 + n)), originals[1]))
                .collect()
        } else {
            vec![(
                "source".into(),
                originals[if index == 0 { 0 } else { index - 1 }],
            )]
        };
        fs::create_dir(root.join("originals").join(&doc))?;
        for (name, bytes) in files {
            let path = format!("originals/{doc}/{name}");
            fs::write(root.join(&path), bytes)?;
            graph["files"]
                .as_array_mut()
                .unwrap()
                .push(json!({"path":path,"length":bytes.len(),"sha256":crate::digest(bytes)}));
        }
    }
    let doc = id(1);
    let page = id(101);
    for (field, kind, value, qualifier, state) in [
        ("total", "amount", "00024.500000", "USD", "saved"),
        ("unknown", "amount", "-0000.0100", "unknown", "saved"),
        ("year", "date", "2024", "year", "saved"),
        ("month", "date", "2024-02", "month", "saved"),
        ("day", "date", "2024-02-29", "day", "saved"),
        ("account", "text", "0000123", "", "saved"),
        ("old", "text", "retained removed", "", "removed"),
        ("rejected", "date", "2020", "year", "rejected"),
        (
            "organization",
            "organization",
            "Synthetic Café 🦀",
            "",
            "saved",
        ),
    ] {
        let evidence=json!({"page_id":page,"excerpt":"$24.50","currency_source":"bare-dollar fallback USD","historical_engine":"retained"}).to_string();
        graph["details"].as_array_mut().unwrap().push(json!({"document_id":doc,"field_id":field,"kind":kind,"label":field,
            "value":value,"qualifier":qualifier,"origin":"accepted","state":state,"evidence":evidence,"revision":5}));
    }
    // A historical page reference remains historical, even when not a current page.
    graph["details"][6]["evidence"] =
        json!(json!({"page_id":id(999),"excerpt":"removed page"}).to_string());
    graph["tags"] = json!([{"id":id(500),"name":"Synthetic Café 🦀"}]);
    graph["document_tags"] =
        json!([{"document_id":doc,"tag_id":id(500),"field_id":"organization"}]);
    for n in 1..=5 {
        let doc = id(n);
        let current = crate::validation::current_evidence(&graph, &doc);
        graph["decision_evidence"][&doc] = json!(current);
        graph["choices"].as_array_mut().unwrap().push(json!({"document_id":doc,"evidence":current,"field":"filing","choice":"edited","value":if n==1 {"custom-first"}else{""}}));
        graph["choices"].as_array_mut().unwrap().push(json!({"document_id":doc,"evidence":"0".repeat(64),"field":"title","choice":"dismissed","value":"Historical title 🦀"}));
    }
    let manifest = Manifest::from_value(graph)?;
    fs::write(
        root.join("manifest-v3.json"),
        serde_json::to_vec_pretty(manifest.json())?,
    )?;
    for version in 1..=3 {
        let graph = legacy(manifest.json(), version);
        let bytes = serde_json::to_vec(&graph)?;
        Manifest::parse(&bytes)?;
        let mut output = fs::File::create(root.join(format!("format-{version}.stackbackup")))?;
        // Deliberately construct legacy envelopes too; the shipping writer emits only v3.
        output.write_all(crate::MAGIC)?;
        output.write_all(&(bytes.len() as i32).to_be_bytes())?;
        use sha2::Digest;
        output.write_all(&sha2::Sha256::digest(&bytes))?;
        output.write_all(&bytes)?;
        for file in manifest.rows("files") {
            let path = file["path"].as_str().unwrap();
            let length = file["length"].as_u64().unwrap();
            output.write_all(&(path.len() as u16).to_be_bytes())?;
            output.write_all(path.as_bytes())?;
            output.write_all(&(length as i64).to_be_bytes())?;
            crate::transfer(
                &mut fs::File::open(root.join(path))?,
                &mut output,
                length,
                &Control::default(),
            )?;
        }
    }
    fs::write(
        root.join("synthetic-fixture.json"),
        b"{\"fixture\":\"stack-m1\",\"version\":1,\"license\":\"CC0-1.0\"}\n",
    )?;
    Ok(manifest)
}

pub fn legacy(graph: &Value, version: u8) -> Value {
    let mut graph = graph.clone();
    graph["version"] = json!(version);
    graph["schema"] = json!(version + 5);
    if version < 3 {
        for stack in graph["stacks"].as_array_mut().unwrap() {
            stack.as_object_mut().unwrap().remove("sort_order");
        }
    }
    if version == 1 {
        for doc in graph["documents"].as_array_mut().unwrap() {
            for key in ["notes", "notes_revision", "metadata_revision"] {
                doc.as_object_mut().unwrap().remove(key);
            }
        }
        for key in ["details", "tags", "document_tags"] {
            graph.as_object_mut().unwrap().remove(key);
        }
    }
    graph
}
