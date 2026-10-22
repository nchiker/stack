//! Native named-device invitation and the common verification for discovery/QR.
use crate::{
    Message, Stack, network_ui,
    repository::SyncRequest,
    sync_ui::{Action as SyncAction, Pending},
};
use cosmic::{Element, app, widget};
use serde_json::Value;
use stack_transport::pairing::PairingControl;

#[derive(Clone, Debug)]
pub enum Action {
    Start,
    Cancel,
}
pub struct Ui {
    pub name: String,
    pub control: Option<PairingControl>,
    pub view: Value,
}
impl Default for Ui {
    fn default() -> Self {
        Self {
            name: "My computer".into(),
            control: None,
            view: Value::Null,
        }
    }
}
fn msg(action: Action) -> Message {
    Message::Sync(SyncAction::Network(network_ui::Action::Nearby(action)))
}
impl Ui {
    pub fn poll(&mut self) {
        self.view = self
            .control
            .as_ref()
            .map(PairingControl::snapshot)
            .unwrap_or(Value::Null);
    }
}
#[cfg(test)]
fn qr_image(text: &str) -> Option<widget::image::Handle> {
    if text.is_empty() {
        return None;
    }
    let qr = qrcodegen::QrCode::encode_text(text, qrcodegen::QrCodeEcc::Medium).ok()?;
    let modules = qr.size() + 8;
    let scale = (320 / modules).max(2);
    let side = modules * scale;
    let mut rgba = vec![255u8; (side * side * 4) as usize];
    for y in 0..side {
        for x in 0..side {
            if qr.get_module(x / scale - 4, y / scale - 4) {
                let i = ((y * side + x) * 4) as usize;
                rgba[i..i + 3].fill(0);
            }
        }
    }
    Some(widget::image::Handle::from_rgba(
        side as u32,
        side as u32,
        rgba,
    ))
}
impl Stack {
    pub fn nearby_action(&mut self, action: Action) -> app::Task<Message> {
        match action {
            Action::Cancel => {
                if let Some(control) = &self.sync.network.nearby.control {
                    control.cancel();
                }
            }
            Action::Start if !self.sync.busy && !self.transfers.busy() => {
                self.sync.network.last_setup_attempt = Some(std::time::Instant::now());
                let name = self.sync.network.nearby.name.trim().to_owned();
                if !stack_transport::pairing::valid_name(&name) {
                    self.sync.error = Some("Enter a device name of 1–64 characters.".into());
                    return app::Task::none();
                }
                let addresses = self.sync.network.status["local_addresses"]
                    .as_array()
                    .map(|a| {
                        a.iter()
                            .filter_map(Value::as_str)
                            .take(8)
                            .map(str::to_owned)
                            .collect()
                    })
                    .unwrap_or_default();
                let control = PairingControl::default();
                control.approve_initiator();
                self.sync.network.stop_listener();
                self.sync.network.control = Some(control.session.clone());
                self.sync.network.nearby.control = Some(control.clone());
                self.sync.network.nearby.view = Value::Null;
                return self.sync_request(
                    SyncRequest::Network(network_ui::Request::Nearby {
                        name,
                        address: "0.0.0.0:45874".parse().expect("fixed pairing port"),
                        addresses,
                        control,
                    }),
                    Pending::NetworkPairing,
                );
            }
            _ => {}
        }
        app::Task::none()
    }
    pub fn nearby_view(&self) -> Element<'_, Message> {
        let nearby = &self.sync.network.nearby;
        let mut body = widget::column::with_capacity(5).spacing(16);
        if self.sync.busy && nearby.control.is_some() {
            body=body.push(widget::text::title3("Approve on your phone"))
                .push(self.body("Open Stack on your phone and approve the connection. Both devices need to be on the same Wi-Fi."));
            if let Some(code) = nearby.view["code"].as_str() {
                body = body.push(widget::text::title1(code));
            }
            body = body.push(self.button("Cancel").on_press(msg(Action::Cancel)));
        } else {
            body=body.push(widget::text::title3("No phone connected"))
                .push(self.body("Connect once. Documents, edits and stacks then sync automatically in both directions."))
                .push(self.button("Connect phone").on_press_maybe((!self.sync.busy).then_some(msg(Action::Start))));
        }
        body.into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn qr_fixture_uses_real_encoder_and_a_quiet_zone() {
        let text = r#"{"stack_pair":1,"invite":"33333333333333333333333333333333","fingerprint":"4444444444444444444444444444444444444444444444444444444444444444","port":45874,"addresses":["192.168.1.20"]}"#;
        let image = qr_image(text).unwrap();
        let widget::image::Handle::Rgba {
            width,
            height,
            pixels,
            ..
        } = image
        else {
            panic!("raster QR")
        };
        assert_eq!(width, height);
        assert_eq!(&pixels[..4], &[255, 255, 255, 255]);
        if let Some(path) = std::env::var_os("STACK_PAIRING_QR_FIXTURE") {
            let path = std::path::PathBuf::from(path);
            assert!(
                path.starts_with("/tmp")
                    && path.to_string_lossy().contains("stack-friendly-pairing")
            );
            image::save_buffer(path, &pixels, width, height, image::ColorType::Rgba8).unwrap();
        }
    }
}
