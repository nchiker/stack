//! Explicit group adoption and file exchange use the same serialized writer as
//! edits. Portable backups never carry this causal state or enroll a replica.
use crate::library::{Error, Library, Result, meta, put_meta, read_graph, write_graph};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use stack_sync::{
    Frontier, State,
    bundle::{self, Blob, Header},
    graph::{self, Cells},
};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, OpenOptions},
    path::Path,
};

fn load(db: &Connection) -> Result<Option<State>> {
    let value: Option<String> = db
        .query_row("SELECT state FROM sync_state WHERE singleton=1", [], |r| {
            r.get(0)
        })
        .optional()?;
    let state: Option<State> = value.map(|v| serde_json::from_str(&v)).transpose()?;
    if let Some(state) = &state {
        if !state.history.values().any(|op| {
            op.evidence["kind"] == "baseline.explicit"
                && op.evidence["graph_model"] == graph::GRAPH_MODEL
        }) {
            return Err(Error::Invalid(
                "Earlier experimental sync graph requires reviewed migration; the saved library remains intact",
            ));
        }
    }
    Ok(state)
}

fn save(db: &Connection, state: &State, projection: &Cells, baseline: &Cells) -> Result<()> {
    db.execute("INSERT INTO sync_state VALUES(1,?1,?2,?3) ON CONFLICT(singleton) DO UPDATE SET state=excluded.state,projection=excluded.projection",params![serde_json::to_string(state)?,serde_json::to_string(projection)?,serde_json::to_string(baseline)?])?;
    Ok(())
}

/// Called by every authoritative local journal write inside that transaction.
pub(crate) fn capture(db: &Connection, kind: &str, payload: &Value) -> Result<()> {
    let Some(mut state) = load(db)? else {
        return Ok(());
    };
    let previous: String = db.query_row(
        "SELECT projection FROM sync_state WHERE singleton=1",
        [],
        |r| r.get(0),
    )?;
    let previous: Cells = serde_json::from_str(&previous)?;
    let current = graph::cells(&read_graph(db)?)?;
    let mut changes = graph::diff(&previous, &current);
    if kind == "document.page.order" {
        // Whole-order decisions retain removed page records/originals as history.
        // A reviewed order can deliberately choose a concurrent alternative.
        changes.retain(|key, value| !key.starts_with("pages/") || !value.is_null());
        if let Some(id) = payload["change"]["after"]["documents"][0]["id"].as_str() {
            let key = format!("page_order/{id}");
            if let Some(value) = current.get(&key) {
                changes.insert(key, value.clone());
            }
        }
    }
    if kind == "stack.order" {
        changes.insert(
            "catalog_order/stacks".into(),
            current["catalog_order/stacks"].clone(),
        );
    }

    if matches!(kind, "document.filing" | "document.membership.delta") {
        let id = payload["change"]["after"]["documents"][0]["id"]
            .as_str()
            .ok_or(Error::Invalid("Filing event has no owner"))?;
        let review = format!("filing_review/{id}");
        if kind == "document.membership.delta" {
            changes.remove(&review);
            let stack = payload["change"]["membership_delta"][0]
                .as_str()
                .ok_or(Error::Invalid("Membership delta has no stack"))?;
            let key = format!("memberships/{}", serde_json::to_string(&[id, stack])?);
            changes.insert(
                key.clone(),
                current.get(&key).cloned().unwrap_or(Value::Null),
            );
        } else {
            changes.insert(review.clone(), current[&review].clone());
            for key in state
                .registers
                .keys()
                .chain(current.keys())
                .filter(|key| key.starts_with("memberships/"))
            {
                let identity: Vec<String> =
                    serde_json::from_str(key.strip_prefix("memberships/").expect("prefix"))?;
                if identity.first().is_some_and(|owner| owner == id) {
                    changes.insert(
                        key.clone(),
                        current.get(key).cloned().unwrap_or(Value::Null),
                    );
                }
            }
        }
    }
    if changes.is_empty() {
        return Ok(());
    }
    state.local(&meta(db, "replica_id")?, changes, json!({"kind":kind}))?;
    db.execute(
        "UPDATE sync_state SET state=?1,projection=?2 WHERE singleton=1",
        params![
            serde_json::to_string(&state)?,
            serde_json::to_string(&current)?
        ],
    )?;
    Ok(())
}

fn refs(state: &State) -> Result<BTreeMap<String, (String, u64)>> {
    let mut files = BTreeMap::new();
    for op in state.history.values() {
        for (key, value) in &op.changes {
            if !key.starts_with("files/") || value.is_null() {
                continue;
            }
            let path = value["path"]
                .as_str()
                .ok_or(Error::Invalid("Sync file path is missing"))?;
            stack_archive::original_path(path)?;
            let sha = value["sha256"]
                .as_str()
                .ok_or(Error::Invalid("Sync file digest is missing"))?;
            let length = value["length"]
                .as_u64()
                .filter(|n| *n <= stack_archive::FILE_LIMIT)
                .ok_or(Error::Invalid("Sync file length is invalid"))?;
            if sha.len() != 64
                || !sha
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            {
                return Err(Error::Invalid("Sync file digest is invalid"));
            }
            if let Some(old) = files.insert(path.to_owned(), (sha.to_owned(), length))
                && old != (sha.to_owned(), length)
            {
                return Err(Error::Invalid("An immutable sync original was replaced"));
            }
        }
    }
    Ok(files)
}

impl Library {
    fn add_reviewed_local(&self, state: &mut State, review: &Value) -> Result<()> {
        let local=self.manifest()?;
        if local.rows("documents").is_empty() && meta(&self.db,"catalog_pristine")? == "1" { return Ok(()); }
        let maps:crate::interchange::IdMappings=serde_json::from_value(review["mappings"].clone())?;
        let source=graph::project(&state.values())?;
        for (table,mapping) in [("documents",&maps.documents),("pages",&maps.pages),("stacks",&maps.stacks),("tags",&maps.tags)] {
            let actual:BTreeSet<_>=local.rows(table).iter().map(|row| row["id"].as_str().unwrap_or("").to_owned()).collect();
            if actual!=mapping.keys().cloned().collect() || mapping.values().collect::<BTreeSet<_>>().len()!=mapping.len() {
                return Err(Error::Invalid("Reviewed mapping must cover each local identity exactly once"));
            }
            for target in mapping.values() {
                if !source.rows(table).iter().any(|row| row["id"]==*target) { stack_archive::uuid(target)?; }
            }
        }
        let scans:BTreeSet<_>=local.rows("documents").iter().filter_map(|row| row["source_scan_id"].as_str().map(str::to_owned)).collect();
        if scans!=maps.scans.keys().cloned().collect() || maps.scans.values().collect::<BTreeSet<_>>().len()!=maps.scans.len() { return Err(Error::Invalid("Incomplete scan mapping")); }
        for target in maps.scans.values(){ stack_archive::uuid(target)?; }
        for page in local.rows("pages") {
            let target=&maps.pages[page["id"].as_str().expect("page")];
            if let Some(existing)=source.rows("pages").iter().find(|row| row["id"]==*target) {
                if existing["document_id"]!=maps.documents[page["document_id"].as_str().expect("document")] { return Err(Error::Invalid("Matched page belongs to another reviewed document")); }
            }
        }
        let mut remapped=crate::interchange::remap(&local,&maps)?;
        for (table,mapping) in [("stacks",&maps.stacks),("tags",&maps.tags)] {
            for row in remapped[table].as_array_mut().expect("table") {
                let old=row["id"].as_str().expect("identity").to_owned();
                let name=review["catalog_names"][table][&old]["chosen"].as_str().ok_or(Error::Invalid("Review each local catalog name"))?;
                row["name"]=json!(stack_archive::validation::clean_name(name)?);
                row["id"]=json!(mapping[&old]);
            }
        }
        // Distinct catalogs append in their original order. When all catalog
        // identities are explicitly matched, retain the local whole-order intent.
        let mut local_cells=graph::cells(&stack_archive::Manifest::parse(&serde_json::to_vec(&remapped)?)?)?;
        let source_cells=state.values();
        if maps.stacks.values().any(|id| !source.rows("stacks").iter().any(|row| row["id"]==*id)) {
            let mut order=source_cells["catalog_order/stacks"].as_array().ok_or(Error::Invalid("Invalid catalog order"))?.clone();
            for id in local_cells["catalog_order/stacks"].as_array().ok_or(Error::Invalid("Invalid local catalog order"))? { if !order.contains(id) {order.push(id.clone());} }
            local_cells.insert("catalog_order/stacks".to_owned(),json!(order));
        }
        let mut changes:Cells=local_cells.into_iter().filter(|(key,value)| source_cells.get(key)!=Some(value)).collect();
        let distinct_catalog=maps.stacks.values().any(|id| !source.rows("stacks").iter().any(|row| row["id"]==*id));
        let reviewed_catalog=if distinct_catalog {changes.remove("catalog_order/stacks")} else {None};
        // Originals become digest-addressed durable input before the one metadata
        // install; no existing original is removed or overwritten by adoption.
        let blobs=self.root.join("sync_blobs");
        crate::library::private_dir(&blobs)?;
        for file in local.rows("files") {
            let sha=file["sha256"].as_str().ok_or(Error::Invalid("Missing original digest"))?;
            let length=file["length"].as_u64().ok_or(Error::Invalid("Missing original size"))?;
            let original=self.root.join(file["path"].as_str().ok_or(Error::Invalid("Missing original path"))?);
            if stack_archive::file_hash(&original,length,&stack_archive::Control::default())? != sha { return Err(Error::Invalid("Local original changed after review")); }
            let target=blobs.join(sha);
            if !target.exists(){fs::hard_link(&original,&target)?;stack_archive::read_regular(&target)?.sync_all()?;}
            if stack_archive::file_hash(&target,length,&stack_archive::Control::default())? != sha {return Err(Error::Invalid("Retained original hash mismatch"));}
        }
        stack_archive::sync_directory(&blobs)?;
        let replica=meta(&self.db,"replica_id")?;
        if !changes.is_empty() {
            // Existing local decisions did not observe the source baseline. Their
            // imported identity match is reviewed; their decision remains concurrent.
            let op=stack_sync::Operation{version:1,library:state.library.clone(),epoch:1,id:stack_sync::Dot{replica:replica.clone(),sequence:1},context:Frontier::new(),changes,evidence:json!({"kind":"baseline.adopt.local","graph_model":graph::GRAPH_MODEL,"review":review})};
            state.receive(&[op])?;
        }
        if let Some(order)=reviewed_catalog {
            state.local(&replica,BTreeMap::from([("catalog_order/stacks".to_owned(),order)]),json!({"kind":"baseline.adopt.catalog","review":review}))?;
        }
        Ok(())
    }

    /// A source-matched proposal; identities remain distinct until the user
    /// explicitly changes a mapping and accepts the complete group membership.
    pub fn sync_review_adoption(&mut self, path: &Path) -> Result<Value> {
        if load(&self.db)?.is_some() {
            return Err(Error::Invalid("This library already adopted a sync baseline"));
        }
        let (_, operation) = self.operation("sync-review")?;
        let stage = operation.join("blobs");
        fs::create_dir(&stage)?;
        let result = (|| {
            let header = bundle::read(&mut stack_archive::read_regular(path)?, &stage)?;
            let replica = meta(&self.db, "replica_id")?;
            let mut members = BTreeSet::from([replica.clone(), header.sender.clone()]);
            for op in &header.operations {
                members.insert(op.id.replica.clone());
                members.extend(op.context.keys().cloned());
            }
            let mut incoming = State::new(header.library.clone(), members.clone())?;
            incoming.receive(&header.operations)?;
            if incoming.history.is_empty() || !incoming.pending.is_empty() {
                return Err(Error::Invalid("Review requires a complete initial baseline"));
            }
            let source = graph::project(&incoming.values())?;
            let local = self.manifest()?;
            let mut local_fingerprint = local.json().clone();
            local_fingerprint["created_at"] = json!(0);
            let namespace = uuid::Uuid::parse_str(&header.library).map_err(|_| Error::Invalid("Invalid group"))?;
            let mut maps = crate::interchange::IdMappings::default();
            for (table, mapping) in [("documents", &mut maps.documents),("pages", &mut maps.pages),("stacks", &mut maps.stacks),("tags", &mut maps.tags)] {
                for row in local.rows(table) {
                    let id=row["id"].as_str().ok_or(Error::Invalid("Missing identity"))?;
                    mapping.insert(id.to_owned(), uuid::Uuid::new_v5(&namespace, format!("adopt:{replica}:{table}:{id}").as_bytes()).to_string());
                }
            }
            for row in local.rows("documents") {
                if let Some(scan)=row["source_scan_id"].as_str() {
                    maps.scans.insert(scan.to_owned(), uuid::Uuid::new_v5(&namespace, format!("adopt:{replica}:scans:{scan}").as_bytes()).to_string());
                }
            }
            let mut catalog_names=serde_json::Map::new();
            for table in ["stacks","tags"] {
                let mut names=serde_json::Map::new();
                let mut used:BTreeSet<String>=source.rows(table).iter().filter_map(|row| row["name"].as_str().map(|s|s.to_lowercase())).collect();
                for row in local.rows(table) {
                    let original=row["name"].as_str().ok_or(Error::Invalid("Missing catalog name"))?;
                    let mut name=original.to_owned();let mut n=0;
                    while used.contains(&name.to_lowercase()) {n+=1;name=format!("{} (adopted {n})",original.chars().take(60).collect::<String>());}
                    used.insert(name.to_lowercase());names.insert(row["id"].as_str().expect("identity").to_owned(),json!({"original":original,"chosen":name}));
                }
                catalog_names.insert(table.to_owned(),Value::Object(names));
            }
            let counts=|manifest:&stack_archive::Manifest|->Value { stack_archive::validation::TABLES.iter().map(|table| (table.to_string(),json!(manifest.rows(table).len()))).collect::<serde_json::Map<_,_>>().into() };
            let length=stack_archive::read_regular(path)?.metadata()?.len();
            Ok(json!({"version":1,"library":header.library,"sender":header.sender,"members":members,"source_sha256":stack_archive::file_hash(path,length,&stack_archive::Control::default())?,"local_sha256":stack_archive::digest(&serde_json::to_vec(&local_fingerprint)?),"mappings":maps,"catalog_names":catalog_names,"local_counts":counts(&local),"source_counts":counts(&source)}))
        })();
        let _ = fs::remove_dir_all(operation);
        result
    }

    pub fn sync_adopt_reviewed(&mut self, path: &Path, review: &Value) -> Result<Value> {
        self.sync_adopt_reviewed_control(path,review,&stack_archive::Control::default())
    }
    pub fn sync_adopt_reviewed_control(&mut self,path:&Path,review:&Value,control:&stack_archive::Control)->Result<Value> {
        control.check()?;
        let current=self.sync_review_adoption(path)?;
        for field in ["version","library","sender","source_sha256","local_sha256","local_counts","source_counts"] {
            if current[field]!=review[field] { return Err(Error::Stale); }
        }
        let group=review["library"].as_str().ok_or(Error::Invalid("Missing reviewed group"))?;
        let members:Vec<String>=serde_json::from_value(review["members"].clone())?;
        let (_, operation)=self.operation("sync-adoption")?;
        let stage=operation.join("blobs");
        fs::create_dir(&stage)?;
        let result=self.sync_import_staged(path,Some((group,&members)),&stage,Some(review),control);
        let _=fs::remove_dir_all(operation);
        result
    }

    pub fn sync_resolve_json(&mut self, key: &str, expected: &Value, value: Value) -> Result<()> {
        let expected:Vec<stack_sync::Dot>=serde_json::from_value(expected.clone())?;
        self.sync_resolve(key,&expected,value)
    }

    /// Explicit baseline creation, including the complete reviewed member list.
    /// New members cannot self-enroll through an operation or a received file.
    pub fn sync_start(&mut self, peers: &[String]) -> Result<Value> {
        if load(&self.db)?.is_some() {
            return Err(Error::Invalid("This library already has a sync baseline"));
        }
        let replica = meta(&self.db, "replica_id")?;
        let mut members: BTreeSet<String> = peers.iter().cloned().collect();
        members.insert(replica.clone());
        let mut state = State::new(meta(&self.db, "library_id")?, members)?;
        let baseline = graph::cells(&self.manifest()?)?;
        state.local(
            &replica,
            baseline.clone(),
            json!({"kind":"baseline.explicit","graph_model":graph::GRAPH_MODEL}),
        )?;
        let tx = self.db.transaction()?;
        save(&tx, &state, &baseline, &baseline)?;
        tx.commit()?;
        self.sync_status()
    }

    pub fn sync_status(&self) -> Result<Value> {
        let replica = meta(&self.db, "replica_id")?;
        let Some(state) = load(&self.db)? else {
            return Ok(json!({"adopted":false,"replica":replica}));
        };
        let projected = graph::project(&state.values());
        let mut query = self
            .db
            .prepare("SELECT peer,frontier FROM sync_receipts ORDER BY peer")?;
        let rows = query.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))?;
        let mut receipts = BTreeMap::<String, Frontier>::new();
        for row in rows {
            let (p, f) = row?;
            receipts.insert(p, serde_json::from_str(&f)?);
        }
        Ok(
            json!({"adopted":true,"replica":replica,"library":state.library,"members":state.members,"frontier":state.frontier,"operations":state.history.len(),"pending_gaps":state.pending.len(),"conflicts":state.conflicts(),"projection_valid":projected.is_ok(),"acknowledged_by":receipts}),
        )
    }

    pub(crate) fn ensure_sync_exportable(&self) -> Result<()> {
        if let Some(state) = load(&self.db)?
            && (!state.conflicts().is_empty() || graph::project(&state.values()).is_err())
        {
            return Err(Error::Invalid(
                "Resolve sync conflicts before exporting a complete portable backup; document PDF copies remain available",
            ));
        }
        Ok(())
    }

    /// Explicit review UI passes all alternatives back as the expected IDs. A
    /// background change invalidates review rather than resolving unseen values.
    pub fn sync_resolve(
        &mut self,
        key: &str,
        expected: &[stack_sync::Dot],
        value: Value,
    ) -> Result<()> {
        let mut state = load(&self.db)?.ok_or(Error::Invalid("No sync baseline"))?;
        let conflicts = state.conflicts();
        let current = conflicts
            .get(key)
            .or_else(|| state.registers.get(key))
            .ok_or(Error::Invalid("Unknown conflict field"))?;
        if current.iter().map(|a| &a.id).collect::<BTreeSet<_>>()
            != expected.iter().collect::<BTreeSet<_>>()
        {
            return Err(Error::Stale);
        }
        let mut changes = BTreeMap::from([(key.to_owned(), value.clone())]);
        if let Some(document) = key.strip_prefix("filing_review/") {
            let chosen = value
                .as_array()
                .ok_or(Error::Invalid("Choose a complete filing set"))?;
            let mut ids = BTreeSet::new();
            let current = state.values();
            for id in chosen {
                let id = id
                    .as_str()
                    .ok_or(Error::Invalid("Filing identities must be strings"))?;
                let stack_key = format!("stacks/{}", serde_json::to_string(&[id])?);
                if !ids.insert(id) || !current.get(&stack_key).is_some_and(|s| !s.is_null()) {
                    return Err(Error::Invalid("Choose unique existing stacks"));
                }
            }
            for field in state
                .registers
                .keys()
                .filter(|k| k.starts_with("memberships/"))
            {
                let pair: Vec<String> =
                    serde_json::from_str(field.strip_prefix("memberships/").expect("prefix"))?;
                if pair.len() == 2 && pair[0] == document {
                    changes.insert(
                        field.clone(),
                        if ids.contains(pair[1].as_str()) {
                            json!({"document_id":document,"stack_id":pair[1]})
                        } else {
                            Value::Null
                        },
                    );
                }
            }
            for id in ids {
                changes.insert(
                    format!("memberships/{}", serde_json::to_string(&[document, id])?),
                    json!({"document_id":document,"stack_id":id}),
                );
            }
        }
        state.local(
            &meta(&self.db, "replica_id")?,
            changes,
            json!({"kind":"conflict.resolve","reviewed":expected}),
        )?;
        self.install_sync_state(&state, None)
    }

    fn install_sync_state(&mut self, state: &State, baseline: Option<&Cells>) -> Result<()> {
        let fallback: Cells = if let Some(baseline) = baseline {
            baseline.clone()
        } else {
            let value: String = self.db.query_row(
                "SELECT baseline FROM sync_state WHERE singleton=1",
                [],
                |r| r.get(0),
            )?;
            serde_json::from_str(&value)?
        };
        let values = state.values();
        let projected = graph::project(&values).or_else(|_| {

            // A provisional baseline layout still respects every tombstone.
            let mut safe = fallback.clone();
            for (key, value) in &values {
                if stack_sync::entity_base(key) && value.is_null() {
                    safe.insert(key.clone(), Value::Null);
                }
            }
            graph::project(&safe)
        })?;
        let cells = graph::cells(&projected)?;
        let tx = self
            .db
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        write_graph(&tx, &projected)?;
        // Invalidate every outstanding worker/editor across an incoming apply.
        tx.execute(
            "UPDATE documents SET revision=revision+1 WHERE active=1",
            [],
        )?;
        save(&tx, state, &cells, &fallback)?;
        put_meta(&tx, "library_id", &state.library)?;
        put_meta(&tx, "catalog_pristine", "0")?;
        let revision = meta(&tx, "revision")?
            .parse::<i64>()
            .map_err(|_| Error::Invalid("Invalid revision"))?;
        put_meta(
            &tx,
            "revision",
            &revision
                .checked_add(1)
                .ok_or(Error::Invalid("Revision exhausted"))?
                .to_string(),
        )?;
        (self.probe)("sync_transaction")?;
        tx.commit()?;
        (self.probe)("sync_committed")?;
        Ok(())
    }

    pub fn sync_export(&self, peer: &str, destination: &Path) -> Result<Value> {
        self.sync_export_control(peer,destination,&stack_archive::Control::default())
    }
    pub fn sync_export_control(&self, peer:&str,destination:&Path,control:&stack_archive::Control)->Result<Value> {
        control.check()?;
        let state = load(&self.db)?.ok_or(Error::Invalid(
            "Create or adopt a reviewed sync baseline first",
        ))?;
        if !state.members.contains(peer) || peer == meta(&self.db, "replica_id")? {
            return Err(Error::Invalid("Unknown destination replica"));
        }
        let ack: Option<String> = self
            .db
            .query_row(
                "SELECT frontier FROM sync_receipts WHERE peer=?1",
                [peer],
                |r| r.get(0),
            )
            .optional()?;
        let ack: Frontier = ack
            .map(|s| serde_json::from_str(&s))
            .transpose()?
            .unwrap_or_default();
        let operations = state.operations_after(&ack)?;
        let (_, operation) = self.operation("sync")?;
        let stage = operation.join("blobs");
        fs::create_dir(&stage)?;
        let result = (|| {
            let files = refs(&state)?;
            let mut unique = BTreeMap::new();
            for (path, (sha, length)) in files {
                if stack_archive::file_hash(
                    &self.root.join(&path),
                    length,
                    control,
                )? != sha
                {
                    return Err(Error::Invalid(
                        "Retained sync original failed integrity verification",
                    ));
                }
                if let std::collections::btree_map::Entry::Vacant(entry) = unique.entry(sha) {
                    fs::hard_link(self.root.join(path), stage.join(entry.key()))?;
                    entry.insert(length);
                }
            }
            let header = Header {
                format: "org.stackdocs.exchange".into(),
                version: 1,
                library: state.library.clone(),
                sender: meta(&self.db, "replica_id")?,
                acknowledged: state.frontier.clone(),
                operations,
                blobs: unique
                    .into_iter()
                    .map(|(sha256, length)| Blob { sha256, length })
                    .collect(),
            };
            bundle::publish_control(&header, &stage, destination,control)?;
            Ok(
                json!({"published":true,"operations":header.operations.len(),"acknowledged":false,"message":"Written exchange file; peer acknowledgment still required"}),
            )
        })();
        let _ = fs::remove_dir_all(operation);
        result
    }

    /// For baseline adoption, the user reviews the exact library and complete
    /// member list. Existing nonempty libraries cannot be silently identified by
    /// hashes, names, archives or matching legacy IDs.
    pub fn sync_import(
        &mut self,
        path: &Path,
        adoption: Option<(&str, &[String])>,
    ) -> Result<Value> {
        self.sync_import_control(path,adoption,&stack_archive::Control::default())
    }
    pub fn sync_import_control(&mut self,path:&Path,adoption:Option<(&str,&[String])>,control:&stack_archive::Control)->Result<Value> {
        control.check()?;
        let (_, operation) = self.operation("sync")?;
        let stage = operation.join("blobs");
        fs::create_dir(&stage)?;
        let result = self.sync_import_staged(path, adoption, &stage, None,control);
        let _ = fs::remove_dir_all(operation);
        result
    }

    fn sync_import_staged(
        &mut self,
        path: &Path,
        adoption: Option<(&str, &[String])>,
        stage: &Path,
        review: Option<&Value>,
        control:&stack_archive::Control,
    ) -> Result<Value> {
        let header = bundle::read_control(&mut stack_archive::read_regular(path)?, stage,control)?;
        let replica = meta(&self.db, "replica_id")?;
        let existing = load(&self.db)?;
        let adopting = existing.is_none();
        let mut state = if let Some(state) = existing {
            state
        } else {
            let (expected, members) = adoption.ok_or(Error::Invalid(
                "Review and explicitly adopt this baseline first",
            ))?;
            if expected != header.library || (review.is_none() && !self.manifest()?.rows("documents").is_empty()) {
                return Err(Error::Invalid(
                    "Baseline adoption requires the reviewed group and an empty destination; existing libraries require reviewed source mapping",
                ));
            }
            let members: BTreeSet<String> = members.iter().cloned().collect();
            if !members.contains(&replica) {
                return Err(Error::Invalid(
                    "This replica was not included in the reviewed membership",
                ));
            }
            State::new(expected.to_owned(), members)?
        };
        if header.library != state.library || !state.members.contains(&header.sender) {
            return Err(Error::Invalid("Unreviewed exchange sender or group"));
        }
        let state_before = serde_json::to_vec(&state)?;
        let applied = state.receive(&header.operations)?;
        let inbox_changed = state_before != serde_json::to_vec(&state)?;
        let blobs = self.root.join("sync_blobs");
        crate::library::private_dir(&blobs)?;
        for blob in &header.blobs {
            control.check()?;
            let target = blobs.join(&blob.sha256);
            if target.exists() {
                if stack_archive::file_hash(
                    &target,
                    blob.length,
                    control,
                )? != blob.sha256
                {
                    return Err(Error::Invalid(
                        "Retained pending blob failed integrity verification",
                    ));
                }
            } else {
                fs::hard_link(stage.join(&blob.sha256), &target)?;
                fs::set_permissions(&target, fs::Permissions::from_mode(0o400))?;
                stack_archive::read_regular(&target)?.sync_all()?;
            }
        }
        stack_archive::sync_directory(&blobs)?;
        if adopting && (!state.pending.is_empty() || state.history.is_empty()) {
            return Err(Error::Invalid(
                "Complete initial baseline operations are required",
            ));
        }
        let baseline = if adopting {
            let genesis = state
                .history
                .values()
                .find(|op| op.evidence["kind"] == "baseline.explicit" && op.id.sequence == 1)
                .ok_or(Error::Invalid("Missing explicit baseline"))?;
            if genesis.evidence["graph_model"] != graph::GRAPH_MODEL {
                return Err(Error::Invalid("Unsupported sync graph model"));
            }
            graph::project(&genesis.changes)?;
            Some(genesis.changes.clone())
        } else {
            None
        };
        if let Some(review) = review {
            self.add_reviewed_local(&mut state, review)?;
        }
        let needed = refs(&state)?;
        // All immutable data is durably installed before the metadata transaction.
        // A process stop can leave only verified unreferenced bytes; retry is safe.
        for (relative, (sha, length)) in needed {
            control.check()?;
            let output = self.root.join(&relative);
            if output.exists() {
                if stack_archive::file_hash(&output, length, control)?
                    != sha
                {
                    return Err(Error::Invalid("Sync original path collision"));
                }
                continue;
            }
            let source = blobs.join(&sha);
            if stack_archive::file_hash(&source, length, control)? != sha
            {
                return Err(Error::Invalid("Required original blob is missing"));
            }
            let parent = output.parent().expect("validated original path");
            if !parent.exists() {
                fs::create_dir(parent)?;
                fs::set_permissions(parent, fs::Permissions::from_mode(0o700))?;
            }
            stack_archive::read_directory(parent)?;
            let temporary = parent.join(format!(".sync-{}.part", uuid::Uuid::new_v4()));
            let mut out = OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o400)
                .open(&temporary)?;
            let hash=stack_archive::transfer(&mut stack_archive::read_regular(&source)?, &mut out,length,control)?;
            if hash!=sha {return Err(Error::Invalid("Original changed during sync install"));}
            out.sync_all()?;
            fs::hard_link(&temporary, &output)?;
            fs::remove_file(&temporary)?;
            stack_archive::sync_directory(parent)?;
        }
        stack_archive::sync_directory(&self.root.join("originals"))?;
        (self.probe)("sync_originals")?;
        // An advertised frontier can acknowledge only operations we retain. Gaps
        // never advance any origin frontier merely because bytes were received.
        let mut acknowledged = Frontier::new();
        for (origin, sequence) in &header.acknowledged {
            let current = state.frontier.get(origin).copied().unwrap_or(0);
            if !state.members.contains(origin) || (origin == &replica && *sequence > current) {
                return Err(Error::Invalid(
                    "Peer advertised an impossible local acknowledgment",
                ));
            }
            acknowledged.insert(origin.clone(), (*sequence).min(current));
        }
        control.check()?;
        if adopting || applied > 0 {
            self.install_sync_state(&state, baseline.as_ref())?;
        } else if inbox_changed {
            self.db.execute(
                "UPDATE sync_state SET state=?1 WHERE singleton=1",
                [serde_json::to_string(&state)?],
            )?;
        }
        // Receipt after apply durability. A crash before this write only causes
        // replay; it cannot acknowledge an uncommitted operation.
        (self.probe)("sync_before_receipt")?;
        self.db.execute("INSERT INTO sync_receipts VALUES(?1,?2) ON CONFLICT(peer) DO UPDATE SET frontier=excluded.frontier",params![header.sender,serde_json::to_string(&acknowledged)?])?;
        (self.probe)("sync_receipt")?;
        Ok(
            json!({"applied":applied,"pending_gaps":state.pending.len(),"durable_frontier":state.frontier,"conflicts":state.conflicts().len(),"adopted":adopting}),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use stack_archive::{Control, SourceVerifier};
    struct Authored;
    impl SourceVerifier for Authored {
        fn verify(&self, _: &Path, _: &str, _: Option<u32>) -> stack_archive::Result<()> {
            Ok(())
        }
    }
    #[test]
    fn invalid_concurrent_layout_cannot_resurrect_a_tombstone_in_provisional_view() {
        let root = tempfile::tempdir().unwrap();
        let source = root.path().join("authored.png");
        fs::write(&source, b"authored state-only original").unwrap();
        let mut library = Library::open(&root.path().join("library")).unwrap();
        library
            .import_original(
                &source,
                "To delete",
                &Authored,
                "image/png",
                1,
                &Control::default(),
            )
            .unwrap();
        let id = library.manifest().unwrap().rows("documents")[0]["id"]
            .as_str()
            .unwrap()
            .to_owned();
        library.sync_start(&[]).unwrap();
        let token = library.token(&id).unwrap();
        library
            .delete_document(&token, &Control::default())
            .unwrap();
        library
            .import_original(
                &source,
                "Later document",
                &Authored,
                "image/png",
                1,
                &Control::default(),
            )
            .unwrap();
        let mut state = load(&library.db).unwrap().unwrap();
        let mut bad = graph::cells(&library.manifest().unwrap())
            .unwrap()
            .into_iter()
            .find(|(k, _)| k.starts_with("page_order/"))
            .unwrap();
        bad.1 = json!(["missing-authored-page"]);
        state
            .local(
                &meta(&library.db, "replica_id").unwrap(),
                BTreeMap::from([bad]),
                json!({"kind":"synthetic.invalid-order"}),
            )
            .unwrap();
        assert!(graph::project(&state.values()).is_err());
        library.install_sync_state(&state, None).unwrap();
        assert!(library.token(&id).is_err());
        assert!(library.ensure_sync_exportable().is_err());
        drop(library);
        let library = Library::open(&root.path().join("library")).unwrap();
        assert!(library.token(&id).is_err());
    }
}
