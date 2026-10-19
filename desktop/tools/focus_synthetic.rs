//! Own-window test activation through the pinned COSMIC toolkit (MIT).
//! Never logs foreign titles and never closes, resizes or activates another window.
use cctk::toplevel_info::{ToplevelInfoHandler, ToplevelInfoState};
use cctk::toplevel_management::{ToplevelManagerHandler, ToplevelManagerState};
use cosmic_client_toolkit::{
    self as cctk, cosmic_protocols, sctk, wayland_client as wl, wayland_protocols as protocols,
};
use cosmic_protocols::toplevel_management::v1::client::zcosmic_toplevel_manager_v1::ZcosmicToplelevelManagementCapabilitiesV1;
use protocols::ext::foreign_toplevel_list::v1::client::ext_foreign_toplevel_handle_v1::ExtForeignToplevelHandleV1;
use sctk::{
    output::{OutputHandler, OutputState},
    registry::{ProvidesRegistryState, RegistryState},
    seat::{Capability, SeatHandler, SeatState},
};
use wl::{
    Connection, QueueHandle, WEnum,
    globals::registry_queue_init,
    protocol::{wl_output, wl_seat},
};
struct State {
    registry: RegistryState,
    output: OutputState,
    seat: SeatState,
    info: ToplevelInfoState,
    manager: ToplevelManagerState,
}
impl ProvidesRegistryState for State {
    fn registry(&mut self) -> &mut RegistryState {
        &mut self.registry
    }
    sctk::registry_handlers!(OutputState, SeatState);
}
impl OutputHandler for State {
    fn output_state(&mut self) -> &mut OutputState {
        &mut self.output
    }
    fn new_output(&mut self, _: &Connection, _: &QueueHandle<Self>, _: wl_output::WlOutput) {}
    fn update_output(&mut self, _: &Connection, _: &QueueHandle<Self>, _: wl_output::WlOutput) {}
    fn output_destroyed(&mut self, _: &Connection, _: &QueueHandle<Self>, _: wl_output::WlOutput) {}
}
impl SeatHandler for State {
    fn seat_state(&mut self) -> &mut SeatState {
        &mut self.seat
    }
    fn new_seat(&mut self, _: &Connection, _: &QueueHandle<Self>, _: wl_seat::WlSeat) {}
    fn new_capability(
        &mut self,
        _: &Connection,
        _: &QueueHandle<Self>,
        _: wl_seat::WlSeat,
        _: Capability,
    ) {
    }
    fn remove_capability(
        &mut self,
        _: &Connection,
        _: &QueueHandle<Self>,
        _: wl_seat::WlSeat,
        _: Capability,
    ) {
    }
    fn remove_seat(&mut self, _: &Connection, _: &QueueHandle<Self>, _: wl_seat::WlSeat) {}
}
impl ToplevelInfoHandler for State {
    fn toplevel_info_state(&mut self) -> &mut ToplevelInfoState {
        &mut self.info
    }
    fn new_toplevel(
        &mut self,
        _: &Connection,
        _: &QueueHandle<Self>,
        _: &ExtForeignToplevelHandleV1,
    ) {
    }
    fn update_toplevel(
        &mut self,
        _: &Connection,
        _: &QueueHandle<Self>,
        _: &ExtForeignToplevelHandleV1,
    ) {
    }
    fn toplevel_closed(
        &mut self,
        _: &Connection,
        _: &QueueHandle<Self>,
        _: &ExtForeignToplevelHandleV1,
    ) {
    }
}
impl ToplevelManagerHandler for State {
    fn toplevel_manager_state(&mut self) -> &mut ToplevelManagerState {
        &mut self.manager
    }
    fn capabilities(
        &mut self,
        _: &Connection,
        _: &QueueHandle<Self>,
        _: Vec<WEnum<ZcosmicToplelevelManagementCapabilitiesV1>>,
    ) {
    }
}
sctk::delegate_output!(State);
sctk::delegate_seat!(State);
sctk::delegate_registry!(State);
cctk::delegate_toplevel_info!(State);
cctk::delegate_toplevel_manager!(State);
fn main() -> Result<(), Box<dyn std::error::Error>> {
    let title = std::env::args()
        .nth(1)
        .ok_or("Expected synthetic window title")?;
    let suffix = title
        .strip_prefix("Stack synthetic M2 ")
        .ok_or("Refusing a non-synthetic title")?;
    if suffix.len() != 36 || !suffix.bytes().all(|c| c.is_ascii_hexdigit() || c == b'-') {
        return Err("Invalid synthetic window nonce".into());
    }
    let connection = Connection::connect_to_env()?;
    let (globals, mut queue) = registry_queue_init(&connection)?;
    let qh = queue.handle();
    let registry = RegistryState::new(&globals);
    let mut state = State {
        output: OutputState::new(&globals, &qh),
        seat: SeatState::new(&globals, &qh),
        info: ToplevelInfoState::new(&registry, &qh),
        manager: ToplevelManagerState::new(&registry, &qh),
        registry,
    };
    for _ in 0..20 {
        queue.roundtrip(&mut state)?;
        let target = state
            .info
            .toplevels()
            .find(|top| top.app_id == "org.stackdocs.desktop" && top.title == title)
            .and_then(|top| top.cosmic_toplevel.clone());
        if let Some(top) = target {
            let seat = state.seat.seats().next().ok_or("No Wayland seat")?;
            state.manager.manager.unset_minimized(&top);
            state.manager.manager.activate(&top, &seat);
            for _ in 0..40 {
                queue.roundtrip(&mut state)?;
                let own = state.info.toplevels().find(|t|t.title==title);
                let activated = own.is_some_and(|t|t.state.contains(&cosmic_protocols::toplevel_info::v1::client::zcosmic_toplevel_handle_v1::State::Activated));
                if activated { eprintln!("Own synthetic window activation acknowledged: true"); return Ok(()); }
                std::thread::sleep(std::time::Duration::from_millis(25));
            }
            let own = state.info.toplevels().find(|t|t.title==title).expect("own window still open");
            eprintln!("Own synthetic window activation acknowledged: false; states={:?}; outputs={}; workspaces={}", own.state, own.output.len(), own.workspace.len());
            return Ok(());
        }
        std::thread::sleep(std::time::Duration::from_millis(25));
    }
    let app_count = state
        .info
        .toplevels()
        .filter(|t| t.app_id == "org.stackdocs.desktop")
        .count();
    let nonce_count = state.info.toplevels().filter(|t| t.title == title).count();
    Err(format!("Own synthetic window was not found (known-app count {app_count}, nonce count {nonce_count})").into())
}
