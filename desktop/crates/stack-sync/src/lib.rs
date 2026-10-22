//! Transport-independent causal registers. Persistence and authorization belong to
//! the receiving transaction; a returned frontier is never a receipt by itself.
pub mod bundle;
pub mod graph;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;

pub type Frontier = BTreeMap<String, u64>;
pub const MAX_SEQUENCE: u64 = 9_007_199_254_740_991;
pub const MAX_OPERATIONS: usize = 4096;
pub const MAX_CHANGES: usize = 65536;
pub const MAX_JSON: usize = 32 * 1024 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("Sync rejected: {0}")]
    Invalid(&'static str),
    #[error("Sync JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("Sync archive graph: {0}")]
    Archive(#[from] stack_archive::Error),
    #[error("Sync I/O: {0}")]
    Io(#[from] std::io::Error),
}
pub type Result<T> = std::result::Result<T, Error>;

#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Dot {
    pub replica: String,
    pub sequence: u64,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Operation {
    pub version: u32,
    pub library: String,
    pub epoch: u64,
    pub id: Dot,
    pub context: Frontier,
    pub changes: BTreeMap<String, Value>,
    pub evidence: Value,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Alternative {
    pub id: Dot,
    pub value: Value,
    pub evidence: Arc<Value>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(deny_unknown_fields)]
pub struct State {
    pub version: u32,
    pub library: String,
    pub epoch: u64,
    pub members: BTreeSet<String>,
    pub frontier: Frontier,
    /// Keep all accepted history, including superseded decisions and tombstones.
    pub history: BTreeMap<String, Operation>,
    pub pending: BTreeMap<String, Operation>,
    // Registers are a derived index. In particular, a library adoption review
    // must not be serialized again for every field in that library.
    #[serde(skip_serializing)]
    pub registers: BTreeMap<String, Vec<Alternative>>,
}

impl<'de> Deserialize<'de> for State {
    fn deserialize<D: serde::Deserializer<'de>>(
        deserializer: D,
    ) -> std::result::Result<Self, D::Error> {
        #[derive(Deserialize)]
        #[serde(deny_unknown_fields)]
        struct Saved {
            version: u32,
            library: String,
            epoch: u64,
            members: BTreeSet<String>,
            frontier: Frontier,
            history: BTreeMap<String, Operation>,
            pending: BTreeMap<String, Operation>,
            // Read legacy caches without allocating their repeated evidence.
            #[serde(default, rename = "registers")]
            _registers: Option<serde::de::IgnoredAny>,
        }
        let saved = Saved::deserialize(deserializer)?;
        let restore = || -> Result<Self> {
            if saved.version != 1 || saved.epoch != 1 {
                return Err(Error::Invalid("unsupported local state version or epoch"));
            }
            let mut state = Self::new(saved.library, saved.members)?;
            let history_len = saved.history.len();
            let pending_len = saved.pending.len();
            let mut history = saved.history;
            let mut known = Frontier::new();
            while !history.is_empty() {
                let mut batch = Vec::with_capacity(MAX_OPERATIONS.min(history.len()));
                while batch.len() < MAX_OPERATIONS {
                    let ready = state.members.iter().find_map(|replica| {
                        let id = Dot {
                            replica: replica.clone(),
                            sequence: known.get(replica).copied().unwrap_or(0) + 1,
                        };
                        let key = operation_key(&id);
                        history
                            .get(&key)
                            .filter(|op| {
                                op.id == id
                                    && op
                                        .context
                                        .iter()
                                        .all(|(r, n)| known.get(r).copied().unwrap_or(0) >= *n)
                            })
                            .map(|_| key)
                    });
                    let Some(key) = ready else { break };
                    let op = history.remove(&key).expect("selected history operation");
                    known.insert(op.id.replica.clone(), op.id.sequence);
                    batch.push(op);
                }
                if batch.is_empty() {
                    return Err(Error::Invalid("local history has missing dependencies"));
                }
                state.receive(&batch)?;
            }
            let mut pending = Vec::new();
            for (key, op) in saved.pending {
                if key != operation_key(&op.id) {
                    return Err(Error::Invalid("local operation key mismatch"));
                }
                pending.push(op);
            }
            state.receive(&pending)?;
            if state.frontier != saved.frontier
                || state.history.len() != history_len
                || state.pending.len() != pending_len
            {
                return Err(Error::Invalid("local history and frontier disagree"));
            }
            Ok(state)
        };
        restore().map_err(serde::de::Error::custom)
    }
}

pub fn entity_base(key: &str) -> bool {
    ["documents/[", "stacks/["]
        .iter()
        .any(|prefix| key.starts_with(prefix))
        && key.ends_with(']')
}

fn derived_counter(key: &str) -> Option<&'static str> {
    ["metadata_revision", "filing_decision"]
        .into_iter()
        .find(|field| key.ends_with(&format!("/{field}")))
}

fn edited_filing(values: &[Alternative]) -> Option<&str> {
    if values.len() < 2
        || !values.iter().all(|a| {
            a.value["field"] == "filing"
                && a.value["choice"] == "edited"
                && a.value["value"]
                    .as_str()
                    .is_some_and(|s| !s.starts_with('{'))
        })
    {
        return None;
    }
    values[0].value["document_id"].as_str()
}

pub fn operation_key(id: &Dot) -> String {
    format!("{}:{:016}", id.replica, id.sequence)
}

fn valid_frontier(frontier: &Frontier) -> Result<()> {
    if frontier.len() > 64 {
        return Err(Error::Invalid("too many replicas"));
    }
    for (replica, sequence) in frontier {
        stack_archive::uuid(replica)?;
        if *sequence > MAX_SEQUENCE {
            return Err(Error::Invalid("counter outside exact integer range"));
        }
    }
    Ok(())
}

impl Operation {
    pub fn validate(&self) -> Result<()> {
        stack_archive::uuid(&self.library)?;
        stack_archive::uuid(&self.id.replica)?;
        if self.version != 1
            || self.epoch != 1
            || self.id.sequence == 0
            || self.id.sequence > MAX_SEQUENCE
        {
            return Err(Error::Invalid("unsupported version, epoch or counter"));
        }
        valid_frontier(&self.context)?;
        validate_numbers(&serde_json::to_value(self)?)?;
        if self.context.get(&self.id.replica).copied().unwrap_or(0) != self.id.sequence - 1 {
            return Err(Error::Invalid(
                "origin context must include exactly the previous operation",
            ));
        }
        if self.changes.is_empty()
            || self.changes.len() > MAX_CHANGES
            || self
                .changes
                .keys()
                .any(|k| k.is_empty() || k.len() > 1024 || k.chars().any(char::is_control))
        {
            return Err(Error::Invalid("invalid change count or key"));
        }
        if serde_json::to_vec(self)?.len() > MAX_JSON {
            return Err(Error::Invalid("operation too large"));
        }
        Ok(())
    }
}

fn validate_numbers(value: &Value) -> Result<()> {
    match value {
        Value::Number(n) if !n.as_i64().is_some_and(|v| v.unsigned_abs() <= MAX_SEQUENCE) => {
            return Err(Error::Invalid(
                "wire numbers must be exact safe integers; typed decimal values are strings",
            ));
        }
        Value::Object(object) => {
            for value in object.values() {
                validate_numbers(value)?;
            }
        }
        Value::Array(array) => {
            for value in array {
                validate_numbers(value)?;
            }
        }
        _ => {}
    }
    Ok(())
}

impl State {
    fn owned_by(&self, entity: &str, field: &str, value: &Value) -> bool {
        if field.starts_with(&format!("{entity}/")) {
            return true;
        }
        let Some((kind, identity)) = entity.split_once('/') else {
            return false;
        };
        let Ok(ids) = serde_json::from_str::<Vec<String>>(identity) else {
            return false;
        };
        let Some(id) = ids.first() else {
            return false;
        };
        if kind == "documents" {
            if field == format!("page_order/{id}")
                || field == format!("filing_review/{id}")
                || value["document_id"] == *id
            {
                return true;
            }
            if let Some((table, raw)) = field.split_once('/') {
                if matches!(
                    table,
                    "memberships" | "details" | "document_tags" | "choices" | "suggestions"
                ) && serde_json::from_str::<Vec<Value>>(raw)
                    .ok()
                    .is_some_and(|keys| keys.first() == Some(&Value::String(id.clone())))
                {
                    return true;
                }
                if table == "pages" {
                    let base = field
                        .split_once("]/")
                        .map_or_else(|| field.to_owned(), |(base, _)| format!("{base}]"));
                    return self.history.values().any(|op| {
                        op.changes
                            .get(&base)
                            .is_some_and(|row| row["document_id"] == *id)
                    });
                }
            }
        } else if kind == "stacks" {
            if value["stack_id"] == *id {
                return true;
            }
            if field == "catalog_order/stacks"
                && value
                    .as_array()
                    .is_some_and(|order| order.contains(&Value::String(id.clone())))
            {
                return true;
            }
            if let Some(raw) = field.strip_prefix("memberships/") {
                return serde_json::from_str::<Vec<Value>>(raw)
                    .ok()
                    .is_some_and(|keys| keys.get(1) == Some(&Value::String(id.clone())));
            }
        }
        false
    }
    pub fn new(library: String, members: BTreeSet<String>) -> Result<Self> {
        stack_archive::uuid(&library)?;
        if members.is_empty() || members.len() > 64 {
            return Err(Error::Invalid("invalid membership"));
        }
        for member in &members {
            stack_archive::uuid(member)?;
        }
        Ok(Self {
            version: 1,
            library,
            epoch: 1,
            members,
            frontier: Frontier::new(),
            history: BTreeMap::new(),
            pending: BTreeMap::new(),
            registers: BTreeMap::new(),
        })
    }

    pub fn local(
        &mut self,
        replica: &str,
        changes: BTreeMap<String, Value>,
        evidence: Value,
    ) -> Result<Operation> {
        let sequence = self
            .frontier
            .get(replica)
            .copied()
            .unwrap_or(0)
            .checked_add(1)
            .ok_or(Error::Invalid("counter exhausted"))?;
        let op = Operation {
            version: 1,
            library: self.library.clone(),
            epoch: self.epoch,
            id: Dot {
                replica: replica.to_owned(),
                sequence,
            },
            context: self.frontier.clone(),
            changes,
            evidence,
        };
        self.receive(std::slice::from_ref(&op))?;
        Ok(op)
    }

    /// Validate the complete batch and work on a copy: failure changes no state.
    /// Caller persists this state and its projection atomically before acking it.
    pub fn receive(&mut self, batch: &[Operation]) -> Result<usize> {
        if batch.len() > MAX_OPERATIONS {
            return Err(Error::Invalid("too many operations"));
        }
        let mut next = self.clone();
        for op in batch {
            op.validate()?;
            if op.library != next.library
                || op.epoch != next.epoch
                || !next.members.contains(&op.id.replica)
                || op.context.keys().any(|r| !next.members.contains(r))
            {
                return Err(Error::Invalid("unknown library, membership or origin"));
            }
            let key = operation_key(&op.id);
            if let Some(previous) = next.history.get(&key).or_else(|| next.pending.get(&key)) {
                if previous != op {
                    return Err(Error::Invalid("operation ID reused with another payload"));
                }
                continue;
            }
            if op.id.sequence <= next.frontier.get(&op.id.replica).copied().unwrap_or(0) {
                return Err(Error::Invalid("missing retained history"));
            }
            if op.context.iter().any(|(origin, sequence)| {
                *sequence
                    > next
                        .frontier
                        .get(origin)
                        .copied()
                        .unwrap_or(0)
                        .saturating_add(MAX_OPERATIONS as u64)
            }) {
                return Err(Error::Invalid("pending dependency gap limit exceeded"));
            }
            if next.pending.len() >= MAX_OPERATIONS
                || op.id.sequence
                    > next
                        .frontier
                        .get(&op.id.replica)
                        .copied()
                        .unwrap_or(0)
                        .saturating_add(MAX_OPERATIONS as u64)
            {
                return Err(Error::Invalid("pending gap limit exceeded"));
            }
            next.pending.insert(key, op.clone());
        }
        let before = next.history.len();
        loop {
            let ready = next
                .pending
                .iter()
                .find(|(_, op)| {
                    op.id.sequence == next.frontier.get(&op.id.replica).copied().unwrap_or(0) + 1
                        && op.context.iter().all(|(replica, sequence)| {
                            next.frontier.get(replica).copied().unwrap_or(0) >= *sequence
                        })
                })
                .map(|(key, _)| key.clone());
            let Some(key) = ready else {
                break;
            };
            let op = next
                .pending
                .remove(&key)
                .expect("selected pending operation");
            let evidence = Arc::new(op.evidence.clone());
            for (field, value) in &op.changes {
                let values = next.registers.entry(field.clone()).or_default();
                values.retain(|a| {
                    op.context.get(&a.id.replica).copied().unwrap_or(0) < a.id.sequence
                });
                values.push(Alternative {
                    id: op.id.clone(),
                    value: value.clone(),
                    evidence: Arc::clone(&evidence),
                });
                values.sort_by(|a, b| a.id.cmp(&b.id));
            }
            next.frontier.insert(op.id.replica.clone(), op.id.sequence);
            next.history.insert(key, op);
        }
        let count = next.history.len() - before;
        *self = next;
        Ok(count)
    }

    pub fn conflicts(&self) -> BTreeMap<String, Vec<Alternative>> {
        let mut conflicts: BTreeMap<_, _> = self
            .registers
            .iter()
            .filter(|(key, alternatives)| {
                derived_counter(key).is_none()
                    && edited_filing(alternatives).is_none()
                    && alternatives
                        .iter()
                        .skip(1)
                        .any(|a| a.value != alternatives[0].value)
            })
            .map(|(key, alternatives)| (key.clone(), alternatives.clone()))
            .collect();
        for (key, alternatives) in self.registers.iter().filter(|(k, _)| entity_base(k)) {
            let Some(deletion) = alternatives.iter().find(|a| a.value.is_null()) else {
                continue;
            };
            let delete_op = &self.history[&operation_key(&deletion.id)];
            let mut review = vec![deletion.clone()];
            for op in self.history.values().filter(|op| {
                op.id != deletion.id
                    && op.context.get(&deletion.id.replica).copied().unwrap_or(0)
                        < deletion.id.sequence
                    && delete_op.context.get(&op.id.replica).copied().unwrap_or(0) < op.id.sequence
            }) {
                if let Some((_, value)) = op.changes.iter().find(|(k, v)| {
                    self.owned_by(key, k, v) && op.evidence["kind"] != "page.text.commit"
                }) {
                    review.push(Alternative {
                        id: op.id.clone(),
                        value: value.clone(),
                        evidence: Arc::new(op.evidence.clone()),
                    });
                }
            }
            if review.len() > 1 {
                conflicts.insert(key.clone(), review);
            }
        }
        // A full filing replacement records what it observed. A concurrent
        // unseen membership contribution requires review, including explicit empty.
        for (key, alternatives) in self
            .registers
            .iter()
            .filter(|(key, _)| key.starts_with("filing_review/"))
        {
            let document = key.strip_prefix("filing_review/").expect("prefix");
            let mut review = alternatives.clone();
            for decision in alternatives {
                let replace = &self.history[&operation_key(&decision.id)];
                if replace.evidence["kind"]
                    .as_str()
                    .is_some_and(|kind| kind.starts_with("baseline"))
                {
                    continue;
                }
                for op in self.history.values().filter(|op| {
                    op.id != replace.id
                        && op.context.get(&replace.id.replica).copied().unwrap_or(0)
                            < replace.id.sequence
                        && replace.context.get(&op.id.replica).copied().unwrap_or(0)
                            < op.id.sequence
                }) {
                    if review.iter().any(|a| a.id == op.id) {
                        continue;
                    }
                    let Some(original) = decision.value.as_array() else {
                        continue;
                    };
                    let mut candidate = original.clone();
                    let mut touched = false;
                    for (field, value) in op
                        .changes
                        .iter()
                        .filter(|(field, _)| field.starts_with("memberships/"))
                    {
                        let Ok(ids) = serde_json::from_str::<Vec<String>>(
                            field.strip_prefix("memberships/").expect("prefix"),
                        ) else {
                            continue;
                        };
                        if ids.len() != 2 || ids[0] != document {
                            continue;
                        }
                        let stack = Value::String(ids[1].clone());
                        candidate.retain(|id| id != &stack);
                        if !value.is_null() {
                            candidate.push(stack);
                        }
                        touched = true;
                    }
                    if touched {
                        candidate.sort_by_key(Value::to_string);
                        review.push(Alternative {
                            id: op.id.clone(),
                            value: Value::Array(candidate),
                            evidence: Arc::new(op.evidence.clone()),
                        });
                    }
                }
            }
            if review.iter().skip(1).any(|a| a.value != review[0].value) {
                review.sort_by(|a, b| a.id.cmp(&b.id));
                conflicts.insert(key.clone(), review);
            }
        }
        // A page removal is a whole-order decision. An unseen edit of a page
        // that decision omits requires review of the editor's observed order.
        for (key, alternatives) in self
            .registers
            .iter()
            .filter(|(key, _)| key.starts_with("page_order/"))
        {
            let document = key.strip_prefix("page_order/").expect("prefix");
            let mut review = alternatives.clone();
            for decision in alternatives {
                let Some(order) = decision.value.as_array() else {
                    continue;
                };
                let replace = &self.history[&operation_key(&decision.id)];
                for op in self.history.values().filter(|op| {
                    op.id != replace.id
                        && op.context.get(&replace.id.replica).copied().unwrap_or(0)
                            < replace.id.sequence
                        && replace.context.get(&op.id.replica).copied().unwrap_or(0)
                            < op.id.sequence
                        && op.evidence["kind"] != "page.text.commit"
                }) {
                    if review.iter().any(|a| a.id == op.id) {
                        continue;
                    }
                    let observed = self
                        .history
                        .values()
                        .filter(|past| {
                            op.context.get(&past.id.replica).copied().unwrap_or(0)
                                >= past.id.sequence
                        })
                        .filter_map(|past| {
                            past.changes
                                .get(key)
                                .and_then(Value::as_array)
                                .map(|value| (past, value))
                        })
                        .rfind(|(past, _)| {
                            !self.history.values().any(|later| {
                                op.context.get(&later.id.replica).copied().unwrap_or(0)
                                    >= later.id.sequence
                                    && later.context.get(&past.id.replica).copied().unwrap_or(0)
                                        >= past.id.sequence
                                    && later.changes.contains_key(key)
                            })
                        })
                        .map(|(_, order)| order);
                    let Some(observed) = observed else { continue };
                    let touched = op.changes.keys().any(|field| {
                        let Some(raw) = field.strip_prefix("pages/") else {
                            return false;
                        };
                        let raw = raw.split_once("]/").map_or(raw, |(base, _)| base);
                        let raw = if raw.ends_with(']') {
                            raw.to_owned()
                        } else {
                            format!("{raw}]")
                        };
                        serde_json::from_str::<Vec<String>>(&raw)
                            .ok()
                            .and_then(|ids| ids.first().cloned())
                            .is_some_and(|page| {
                                observed.contains(&Value::String(page.clone()))
                                    && !order.contains(&Value::String(page))
                            })
                    });
                    if touched {
                        review.push(Alternative {
                            id: op.id.clone(),
                            value: Value::Array(observed.clone()),
                            evidence: Arc::new(op.evidence.clone()),
                        });
                    }
                }
            }
            if review.iter().skip(1).any(|a| a.value != review[0].value) {
                review.sort_by(|a, b| a.id.cmp(&b.id));
                conflicts.insert(format!("page_order/{document}"), review);
            }
        }
        conflicts
    }

    pub fn values(&self) -> BTreeMap<String, Value> {
        self.registers
            .iter()
            .filter_map(|(key, values)| {
                values.last().map(|a| {
                    let deleted = entity_base(key)
                        && self
                            .history
                            .values()
                            .any(|op| op.changes.get(key).is_some_and(Value::is_null));
                    let mut selected = if deleted {
                        Value::Null
                    } else {
                        a.value.clone()
                    };
                    if let Some(field) = derived_counter(key)
                        && let Some(maximum) =
                            values.iter().filter_map(|a| a.value[field].as_i64()).max()
                    {
                        selected = serde_json::json!({field:maximum});
                    }
                    if let Some(document) = edited_filing(values) {
                        // These edited CSV rows are projections of independently
                        // merged membership decisions, not competing full-set
                        // intents. Full replacements have their own review cell;
                        // every original choice remains verbatim in history.
                        let selected_stacks: BTreeSet<String> = self
                            .registers
                            .iter()
                            .filter(|(key, _)| key.starts_with("memberships/"))
                            .filter_map(|(_, alts)| alts.last())
                            .filter(|a| a.value["document_id"] == document)
                            .filter_map(|a| a.value["stack_id"].as_str().map(str::to_owned))
                            .collect();
                        selected["value"] = Value::String(
                            selected_stacks.into_iter().collect::<Vec<_>>().join(","),
                        );
                    }
                    (key.clone(), selected)
                })
            })
            .collect()
    }

    pub fn operations_after(&self, ack: &Frontier) -> Result<Vec<Operation>> {
        valid_frontier(ack)?;
        if ack.iter().any(|(r, n)| {
            !self.members.contains(r) || *n > self.frontier.get(r).copied().unwrap_or(0)
        }) {
            return Err(Error::Invalid("acknowledgment is beyond durable history"));
        }
        let mut known = ack.clone();
        let mut result = Vec::new();
        while result.len() < MAX_OPERATIONS {
            let Some(op) = self.history.values().find(|op| {
                op.id.sequence == known.get(&op.id.replica).copied().unwrap_or(0) + 1
                    && op.context.iter().all(|(origin, sequence)| {
                        known.get(origin).copied().unwrap_or(0) >= *sequence
                    })
            }) else {
                break;
            };
            known.insert(op.id.replica.clone(), op.id.sequence);
            result.push(op.clone());
        }
        Ok(result)
    }
}

/// Strict JSON rejects duplicate keys, floating counters and unsupported fields.
pub fn decode_operation(bytes: &[u8]) -> Result<Operation> {
    let value = stack_archive::strict_json::object(bytes)?;
    let op: Operation = serde_json::from_value(value)?;
    op.validate()?;
    Ok(op)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn ids() -> (String, Vec<String>) {
        (
            uuid::Uuid::new_v4().to_string(),
            (0..3).map(|_| uuid::Uuid::new_v4().to_string()).collect(),
        )
    }
    fn change(key: &str, value: Value) -> BTreeMap<String, Value> {
        BTreeMap::from([(key.to_owned(), value)])
    }
    #[test]
    fn populated_adoption_cache_shares_evidence_and_restores_legacy_history_exactly() {
        let (lib, ids) = ids();
        let mut state = State::new(lib, ids.iter().cloned().collect()).unwrap();
        let evidence = json!({"kind":"baseline.adopt.local","review":{"notes":"Exact 🦀\n00024.500 ".repeat(4096)}});
        let changes = (0..256)
            .map(|i| (format!("field/{i:03}"), json!(format!("value {i} 🦀"))))
            .collect();
        let baseline = state.local(&ids[0], changes, evidence.clone()).unwrap();
        let first = &state.registers["field/000"][0].evidence;
        assert!(
            state
                .registers
                .values()
                .all(|v| Arc::ptr_eq(first, &v[0].evidence))
        );
        let mut peer = State::new(state.library.clone(), state.members.clone()).unwrap();
        let concurrent = peer
            .local(
                &ids[1],
                change("field/000", json!("concurrent")),
                json!({"kind":"manual"}),
            )
            .unwrap();
        state.receive(&[concurrent]).unwrap();
        let mut legacy = serde_json::to_value(&state).unwrap();
        legacy["registers"] = serde_json::to_value(&state.registers).unwrap();
        let legacy_bytes = serde_json::to_vec(&legacy).unwrap();
        let compact = serde_json::to_vec(&state).unwrap();
        assert!(legacy_bytes.len() > compact.len() * 100);
        assert!(compact.len() < serde_json::to_vec(&baseline).unwrap().len() * 2);
        for bytes in [&legacy_bytes, &compact] {
            let mut restored: State = serde_json::from_slice(bytes).unwrap();
            assert_eq!(restored.history, state.history);
            assert_eq!(restored.frontier, state.frontier);
            assert_eq!(restored.values(), state.values());
            assert_eq!(restored.conflicts(), state.conflicts());
            assert_eq!(*restored.registers["field/001"][0].evidence, evidence);
            assert_eq!(
                restored.receive(std::slice::from_ref(&baseline)).unwrap(),
                0
            );
            let mut changed = baseline.clone();
            changed.evidence = json!({"kind":"tampered"});
            assert!(restored.receive(&[changed]).is_err());
            assert_eq!(serde_json::to_vec(&restored).unwrap(), compact);
        }
    }

    #[test]
    fn restore_rebuilds_registers_and_preserves_pending_gaps() {
        let (lib, ids) = ids();
        let mut source = State::new(lib, ids.iter().cloned().collect()).unwrap();
        let first = source
            .local(&ids[0], change("title", json!("first")), json!(null))
            .unwrap();
        let second = source
            .local(&ids[0], change("title", json!("second")), json!(null))
            .unwrap();
        let mut waiting = State::new(source.library.clone(), source.members.clone()).unwrap();
        waiting.receive(std::slice::from_ref(&second)).unwrap();
        let mut restored: State =
            serde_json::from_slice(&serde_json::to_vec(&waiting).unwrap()).unwrap();
        assert_eq!(restored.pending, waiting.pending);
        assert!(restored.values().is_empty());
        restored.receive(&[first]).unwrap();
        assert_eq!(restored.values(), source.values());
        let mut legacy = serde_json::to_value(&source).unwrap();
        legacy["registers"] = json!({"stale cache":"ignored in favor of validated history"});
        let restored: State = serde_json::from_value(legacy).unwrap();
        assert_eq!(restored.values(), source.values());
        let mut corrupt = serde_json::to_value(&source).unwrap();
        corrupt["frontier"][&ids[0]] = json!(3);
        assert!(serde_json::from_value::<State>(corrupt).is_err());
    }

    #[test]
    fn restore_replays_large_history_in_causal_order_across_replica_keys() {
        let (lib, mut ids) = ids();
        ids.sort();
        let mut state = State::new(lib, ids.iter().cloned().collect()).unwrap();
        let operations: Vec<_> = (1..=MAX_OPERATIONS as u64 + 1)
            .map(|sequence| Operation {
                version: 1,
                library: state.library.clone(),
                epoch: 1,
                id: Dot {
                    replica: ids[1].clone(),
                    sequence,
                },
                context: if sequence == 1 {
                    Frontier::new()
                } else {
                    Frontier::from([(ids[1].clone(), sequence - 1)])
                },
                changes: change("title", json!(sequence)),
                evidence: Value::Null,
            })
            .collect();
        for batch in operations.chunks(MAX_OPERATIONS) {
            state.receive(batch).unwrap();
        }
        // This sorts before its >4096 dependencies in the persisted history map.
        state
            .local(
                &ids[0],
                change("notes", json!("after the whole history")),
                Value::Null,
            )
            .unwrap();
        let restored: State = serde_json::from_slice(&serde_json::to_vec(&state).unwrap()).unwrap();
        assert_eq!(restored.history, state.history);
        assert_eq!(restored.values(), state.values());
        assert_eq!(restored.frontier, state.frontier);
    }

    #[test]
    fn permutations_forwarding_resolution_gaps_and_exact_replay_converge() {
        let (lib, ids) = ids();
        let mut a = State::new(lib, ids.iter().cloned().collect()).unwrap();
        let mut b = a.clone();
        let mut c = a.clone();
        let a1 = a
            .local(
                &ids[0],
                change("title", json!("00024.500 🦀")),
                json!({"source":"manual"}),
            )
            .unwrap();
        let a2 = a
            .local(&ids[0], change("notes", json!("line\nexact")), json!(null))
            .unwrap();
        let b1 = b
            .local(
                &ids[1],
                change("title", json!("alternative")),
                json!("evidence"),
            )
            .unwrap();
        assert_eq!(b.receive(std::slice::from_ref(&a2)).unwrap(), 0);
        assert!(!b.frontier.contains_key(&ids[0]));
        b.receive(std::slice::from_ref(&a1)).unwrap();
        a.receive(std::slice::from_ref(&b1)).unwrap();
        c.receive(&[a2.clone(), b1.clone(), a1.clone()]).unwrap();
        assert_eq!(a.conflicts(), b.conflicts());
        assert_eq!(a.values(), c.values());
        let resolve = c
            .local(
                &ids[2],
                change("title", json!("chosen")),
                json!({"reviewed":[a1.id,b1.id]}),
            )
            .unwrap();
        for state in [&mut a, &mut b] {
            state.receive(std::slice::from_ref(&resolve)).unwrap();
            assert!(state.conflicts().is_empty());
            assert_eq!(state.values(), c.values());
        }
        let saved = serde_json::to_vec(&b).unwrap();
        let mut b: State = serde_json::from_slice(&saved).unwrap();
        assert_eq!(b.receive(&[a1, a2]).unwrap(), 0);
        let mut corrupt = b1;
        corrupt.evidence = json!("changed");
        let before = serde_json::to_vec(&b).unwrap();
        assert!(b.receive(&[corrupt]).is_err());
        assert_eq!(before, serde_json::to_vec(&b).unwrap());
    }
    #[test]
    fn malformed_unknown_and_unbounded_context_are_atomic_failures() {
        let (lib, ids) = ids();
        let mut state = State::new(lib, ids.iter().cloned().collect()).unwrap();
        let op = state
            .local(
                &ids[0],
                change("detail", json!({"value":"0001.2000","currency":"GBP"})),
                json!(null),
            )
            .unwrap();
        let mut next = op.clone();
        next.id.sequence = 2;
        next.context.insert(ids[0].clone(), 1);
        next.context.insert(uuid::Uuid::new_v4().to_string(), 1);
        assert!(state.receive(&[next]).is_err());
        let bytes = serde_json::to_vec(&op).unwrap();
        assert_eq!(decode_operation(&bytes).unwrap(), op);
        assert!(decode_operation(b"{\"version\":1,\"version\":1}").is_err());
    }
}
