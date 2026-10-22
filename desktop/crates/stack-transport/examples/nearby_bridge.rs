//! Opt-in host for the explicitly authorized spare's authored child libraries.
//! File approvals are test-harness inputs, never enabled in the application UI.
use serde_json::{Value, json};
use stack_transport::pairing::{PairingControl, invite_library};
use std::{
    fs,
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};
fn main() {
    let root =
        PathBuf::from(std::env::var_os("STACK_NEARBY_TEST_ROOT").expect("authored fixture root"));
    assert!(
        root.is_absolute()
            && root.starts_with("/tmp")
            && root.to_string_lossy().contains("stack-friendly-pairing")
    );
    let signals = root.parent().unwrap().join("signals");
    fs::create_dir_all(&signals).unwrap();
    let mut library = stack_core::library::Library::open(&root).unwrap();
    fs::write(
        signals.join("before.json"),
        serde_json::to_vec_pretty(&library.status().unwrap()).unwrap(),
    )
    .unwrap();
    let control = PairingControl::default();
    let polling = control.clone();
    let finished = Arc::new(AtomicBool::new(false));
    let stop = finished.clone();
    let directory = signals.clone();
    let monitor = std::thread::spawn(move || {
        let mut last = Value::Null;
        let until = Instant::now() + Duration::from_secs(150);
        while !stop.load(Ordering::Acquire) && Instant::now() < until {
            let view = polling.snapshot();
            if view != last {
                let tmp = directory.join("preview.tmp");
                fs::write(&tmp, serde_json::to_vec_pretty(&view).unwrap()).unwrap();
                fs::rename(tmp, directory.join("preview.json")).unwrap();
                last = view;
            }
            if let Ok(token) = fs::read_to_string(directory.join("approve.txt")) {
                polling.confirm(token.trim());
            }
            if directory.join("cancel").exists() {
                polling.cancel();
            }
            std::thread::sleep(Duration::from_millis(25));
        }
    });
    let address = std::env::var("STACK_NEARBY_TEST_ADDRESS")
        .unwrap_or_else(|_| "0.0.0.0:45874".into())
        .parse()
        .unwrap();
    let qr_host = std::env::var("STACK_NEARBY_TEST_HOST").unwrap_or_else(|_| "192.168.1.20".into());
    let result = invite_library(
        &mut library,
        "Authored computer",
        address,
        &[qr_host],
        &control,
    );
    let mut output = match result {
        Ok(v) => v,
        Err(e) => json!({"error":e.to_string()}),
    };
    if let Some(path) = control.received_path() {
        let review = library.sync_review_adoption(&path).unwrap();
        fs::write(
            signals.join("review.json"),
            serde_json::to_vec_pretty(&review).unwrap(),
        )
        .unwrap();
        if std::env::var("STACK_NEARBY_TEST_ADOPT").as_deref() == Ok("true") {
            library.sync_adopt_reviewed(&path, &review).unwrap();
            output["reviewed_adoption_applied"] = json!(true);
        }
    }
    output["status"] = library.sync_status().unwrap();
    output["library_status"] = serde_json::to_value(library.status().unwrap()).unwrap();
    fs::write(
        signals.join("result.json"),
        serde_json::to_vec_pretty(&output).unwrap(),
    )
    .unwrap();
    finished.store(true, Ordering::Release);
    monitor.join().unwrap();
    println!(
        "NEARBY_BRIDGE_RESULT={}",
        serde_json::to_string(&output).unwrap()
    );
}
