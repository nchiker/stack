//! A native-window capture route restricted to the six authored, readable PDFs.
use crate::{Message, Stack, editor};
use cosmic::app;
use std::{path::Path, sync::atomic::Ordering};

pub const CAPTURES: [&str; 5] = [
    "01-library.png",
    "02-viewer.png",
    "03-edit-information.png",
    "04-edit-details.png",
    "05-edit-pages.png",
];

pub fn verify(root: &Path) -> Result<stack_archive::Manifest, Box<dyn std::error::Error>> {
    if !root.is_absolute() || !root.starts_with("/tmp") {
        return Err("Visual review requires authored /tmp fixtures".into());
    }
    let files = [
        (
            "water-bill.pdf",
            "75769677ca23a724d31b304add3c9b4169c39198ec2009c93b8c4a0a5c220417",
        ),
        (
            "hardware-receipt.pdf",
            "ee64ec1c79b1238a3106b358e5e7a4b5e17dda9454c5826e7d83115542cfc9b2",
        ),
        (
            "health-visit.pdf",
            "70bb0e2b3bd8968be6c8836d0aabf9bd49a49fcde3f50d453a492e07f345a6df",
        ),
        (
            "home-insurance.pdf",
            "413f79b33aa97d8c55ebbc867d67f94f59898cd09e5cc8a8682a4699e1fe86b0",
        ),
        (
            "bank-statement.pdf",
            "f21924a025ae6f2976b6e5ce648eb715d51bd43fcb0b0f759399aa7cd4dc4de7",
        ),
        (
            "train-reservation.pdf",
            "5c8f787e9246e61a3f9072cce5d06630d0ae08b3a643e42b6e37c1ef8b052c42",
        ),
    ];
    for (name, hash) in files {
        let bytes = std::fs::read(root.join("sources").join(name))?;
        if bytes.len() > 16_384 || stack_archive::digest(&bytes) != hash {
            return Err("Visual sample changed".into());
        }
    }
    let bytes = std::fs::read(root.join("expected.json"))?;
    if bytes.len() > 1_048_576 {
        return Err("Visual manifest too large".into());
    }
    let manifest = stack_archive::Manifest::from_value(serde_json::from_slice(&bytes)?)?;
    if manifest.rows("documents").len() != 6
        || manifest.rows("files").iter().any(|file| {
            !files
                .iter()
                .any(|(_, hash)| file["sha256"].as_str() == Some(hash))
        })
    {
        return Err("Visual review requires only the authored originals".into());
    }
    Ok(manifest)
}

impl Stack {
    pub fn visual_after_capture(&mut self) -> app::Task<Message> {
        match self.smoke_step {
            1 => {
                let id = self
                    .hits
                    .iter()
                    .find(|h| h.title == "Water bill — October")
                    .expect("authored bill")
                    .document_id
                    .clone();
                self.select_document(id, 0)
            }
            2 => {
                drop(self.editor_action(editor::Action::Open));
                self.delayed_capture()
            }
            3 => {
                drop(self.editor_action(editor::Action::Panel(editor::Panel::Details)));
                self.delayed_capture()
            }
            4 => {
                drop(self.editor_action(editor::Action::Panel(editor::Panel::Pages)));
                self.delayed_capture()
            }
            _ => {
                self.flags.completed.store(true, Ordering::SeqCst);
                cosmic::task::future(async { Message::Close })
            }
        }
    }
}
