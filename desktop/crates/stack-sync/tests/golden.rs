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
