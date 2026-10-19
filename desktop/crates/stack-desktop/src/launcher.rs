//! Native COSMIC shell using the pinned public Iced builder. libcosmic's pinned
//! app::run omits `.scale_factor(...)`; register it here for the entire shell.
//! Keep theme, navigation, subscriptions, window handling and updates in COSMIC.
use crate::{Flags, Stack};
use cosmic::{Application, Core, Theme, app::cosmic::Cosmic, iced};
use std::{cell::RefCell, rc::Rc};

pub fn boot(
    flags: Flags,
    size: iced::Size,
    theme: Theme,
) -> (Cosmic<Stack>, cosmic::app::Task<crate::Message>) {
    let scale = flags.ui_scale;
    let mut core = Core::default();
    core.set_main_window_id(Some(iced::window::Id::RESERVED));
    let (mut shell, init) = Cosmic::<Stack>::init((core, flags));
    let scale = shell.update(cosmic::Action::Cosmic(cosmic::app::Action::ScaleFactor(
        scale,
    )));
    let theme = shell.update(cosmic::Action::Cosmic(cosmic::app::Action::AppThemeChange(
        theme,
    )));
    let size = shell.update(cosmic::Action::Cosmic(cosmic::app::Action::WindowResize(
        iced::window::Id::RESERVED,
        size.width,
        size.height,
    )));
    (shell, iced::Task::batch([init, scale, theme, size]))
}

pub fn run(flags: Flags, size: iced::Size, theme: Theme, fixed: bool) -> iced::Result {
    let flags = Rc::new(RefCell::new(Some(flags)));
    let window = iced::window::Settings {
        size,
        min_size: fixed.then_some(size),
        max_size: fixed.then_some(size),
        decorations: false,
        transparent: true,
        exit_on_close_request: false,
        platform_specific: iced::window::settings::PlatformSpecific {
            application_id: Stack::APP_ID.into(),
            ..Default::default()
        },
        ..Default::default()
    };
    let boot = move || {
        let (shell, init) = boot(
            flags.borrow_mut().take().expect("one native boot"),
            size,
            theme.clone(),
        );
        let window = window.clone();
        let open = iced::runtime::task::oneshot(|channel| {
            iced::runtime::Action::Window(iced::runtime::window::Action::Open(
                iced::window::Id::RESERVED,
                window,
                channel,
            ))
        });
        (shell, iced::Task::batch([init, open.discard()]))
    };
    iced::daemon(boot, Cosmic::update, Cosmic::view)
        .subscription(Cosmic::subscription)
        .title(Cosmic::title)
        .style(Cosmic::style)
        .theme(Cosmic::theme)
        .scale_factor(Cosmic::scale_factor)
        .settings(iced::Settings {
            id: Some(Stack::APP_ID.into()),
            default_font: cosmic::font::default(),
            ..Default::default()
        })
        .run()
}
