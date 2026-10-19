//! Headless application-message driver. This exercises product handlers/workers,
//! but deliberately is not native picker, layout or pointer/keyboard evidence.
use crate::{
    Flags, Message, Stack,
    transfers_ui::{Action, Phase, Pick},
};
use cosmic::{Application, Core, iced};
use iced::futures::{
    StreamExt,
    stream::{BoxStream, SelectAll},
};
use std::{
    path::{Path, PathBuf},
    sync::Arc,
};

static CONTROLLERS: std::sync::Mutex<()> = std::sync::Mutex::new(());

#[test]
fn first_saved_page_thumbnails_refresh_after_reorder_and_ignore_obsolete_results() {
    // PDFium bindings have a process-wide terminal shutdown contract. This
    // scenario needs its own engine lifetime rather than a preceding app's one.
    if std::env::var_os("STACK_THUMBNAIL_FRESH_PROCESS").is_none() {
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "controller_tests::first_saved_page_thumbnails_refresh_after_reorder_and_ignore_obsolete_results", "--nocapture"])
            .env("STACK_THUMBNAIL_FRESH_PROCESS", "1").output().unwrap();
        assert!(
            output.status.success(),
            "{}\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        for line in String::from_utf8_lossy(&output.stdout)
            .lines()
            .filter(|line| line.starts_with("M2_PREVIEW_CACHE "))
        {
            println!("{line}");
        }
        return;
    }
    use crate::editor::Action as Edit;
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let root = tempfile::tempdir().unwrap();
            let fixtures = root.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let mut driver = Driver::new(root.path().join("library"));
            driver.settle().await;
            driver
                .pick(
                    Pick::Documents,
                    vec![fixtures.join("two-pages.pdf"), fixtures.join("corners.png")],
                )
                .await;
            assert_eq!(
                driver.app.tiles.images.len(),
                2,
                "{:?}",
                driver.app.tiles.errors
            );
            let id = driver
                .app
                .hits
                .iter()
                .find(|h| h.title == "two-pages")
                .unwrap()
                .document_id
                .clone();
            let tile = &driver.app.tiles.metadata[&id];
            assert_eq!(tile.page.as_ref().unwrap().source_page, 0);
            assert!(tile.added_at > 0);
            let original = tile.source.clone().unwrap();
            let before_source = std::fs::read(&original).unwrap();
            let pixels_hash = |handle: &cosmic::widget::image::Handle| match handle {
                cosmic::widget::image::Handle::Rgba {
                    width,
                    height,
                    pixels,
                    ..
                } => {
                    assert!(*width <= 400 && *height <= 400);
                    stack_archive::digest(pixels.as_ref())
                }
                _ => panic!("thumbnail must contain decoded pixels"),
            };
            let before = pixels_hash(&driver.app.tiles.images[&id]);
            let thumbnail_id = driver.app.tiles.images[&id].id();
            let job = crate::RenderJob {
                source: original.clone(),
                page: 0,
                rotation: stack_core::Rotation::None,
                saved: tile.page.clone(),
            };
            let renderer = driver.app.renderer.as_ref().unwrap();
            let start = std::time::Instant::now();
            let first = renderer.submit(job.clone()).await.unwrap().unwrap();
            let cold = start.elapsed();
            let start = std::time::Instant::now();
            let cached = renderer.submit(job.clone()).await.unwrap().unwrap();
            let warm = start.elapsed();
            assert!(Arc::ptr_eq(&first, &cached), "unchanged PDF was rendered again");
            let mut rotated = job;
            rotated.saved.as_mut().unwrap().rotation = 90;
            let changed = renderer.submit(rotated).await.unwrap().unwrap();
            assert!(!Arc::ptr_eq(&first, &changed));
            assert_ne!(first.image.dimensions(), changed.image.dimensions());
            println!("M2_PREVIEW_CACHE {}", serde_json::json!({"cold_us":cold.as_micros(),"cached_us":warm.as_micros(),"identical_arc":true,"appearance_invalidated":true}));
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            assert_eq!(driver.app.selected.as_ref().unwrap().searchable_pages, 0);
            // Opening/closing an unchanged PDF must reuse its small tile instead
            // of decoding the original again. UI results still have generations.
            driver.send(Message::Back);
            driver.settle().await;
            assert_eq!(driver.app.tiles.images[&id].id(), thumbnail_id);
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            driver.send(Message::Editor(Edit::Open));
            driver.send(Message::Editor(Edit::MovePage(1)));
            driver.settle().await;
            assert_eq!(
                driver.app.selected.as_ref().unwrap().pages[0].source_page,
                1
            );
            driver.send(Message::Back);
            driver.send(Message::Back);
            driver.settle().await;
            assert_eq!(
                driver.app.tiles.metadata[&id]
                    .page
                    .as_ref()
                    .unwrap()
                    .source_page,
                1
            );
            assert_ne!(pixels_hash(&driver.app.tiles.images[&id]), before);
            assert_eq!(std::fs::read(&original).unwrap(), before_source);
            let old_generation = driver.app.tiles.generation;
            let old_image = driver.app.tiles.images[&id].clone();
            driver.send(Message::Query("no such document".into()));
            driver.settle().await;
            driver.send(Message::Thumbnail(old_generation, id, Ok(old_image)));
            driver.settle().await;
            assert!(driver.app.tiles.images.is_empty());
            driver.send(Message::Close);
            driver.settle().await;
        });
}

// Invoked only by test_m2_portal_protocol.py on its private D-Bus session. Uses
// actual portal requests/responses without a compositor, chooser UI or injected
// Message::Picked. File names exercise URI escaping and non-ASCII handling.
async fn exercise_portal_protocol(driver: &mut Driver, fixtures: &Path, root: &Path) {
    use serde_json::json;
    assert!(root.is_absolute() && root.starts_with("/tmp"));
    assert!(std::env::var_os("DISPLAY").is_none());
    assert!(std::env::var_os("WAYLAND_DISPLAY").is_none());
    let names = [
        "two pages # café.pdf",
        "rotated image.jpg",
        "corners #1.png",
        "corners 🦀.webp",
    ];
    let files: Vec<_> = stack_render::fixtures::NAMES
        .iter()
        .zip(names)
        .map(|(source, name)| {
            let path = root.join(name);
            std::fs::copy(fixtures.join(source), &path).unwrap();
            path
        })
        .collect();
    let archive = root.join("library # café.stackbackup");
    let pdf = root.join("saved # café.pdf");
    let requests = json!([
        {"method":"OpenFile","title":"Import documents","cancel":true,"multiple":true},
        {"method":"OpenFile","title":"Import documents","paths":files,"multiple":true},
        {"method":"SaveFile","title":"Export full portable library archive","paths":[archive]},
        {"method":"OpenFile","title":"Review a Stack archive","paths":[archive]},
        {"method":"OpenFile","title":"Review a Stack archive","paths":[archive]},
        {"method":"SaveFile","title":"Export saved document as PDF","paths":[pdf]}
    ]);
    std::fs::write(
        root.join("requests.json"),
        serde_json::to_vec(&requests).unwrap(),
    )
    .unwrap();
    let initial = driver.app.hits.len();
    driver.send(Message::Transfer(Action::Pick(Pick::Documents)));
    driver.settle().await;
    assert_eq!(driver.app.hits.len(), initial);
    assert!(
        driver
            .app
            .transfers
            .status
            .as_ref()
            .unwrap()
            .contains("selection canceled")
    );
    driver.send(Message::Transfer(Action::Pick(Pick::Documents)));
    driver.settle().await;
    assert_eq!(driver.app.hits.len(), initial + 4);
    assert!(
        driver
            .app
            .transfers
            .outcomes
            .iter()
            .all(|(_, s)| s == "Imported and saved")
    );
    driver.send(Message::Transfer(Action::Pick(Pick::ArchiveExport)));
    driver.settle().await;
    assert!(archive.is_file());
    driver.send(Message::Transfer(Action::Pick(Pick::Archive)));
    driver.settle().await;
    assert_eq!(
        driver.app.transfers.active.as_ref().unwrap().phase,
        Phase::Review
    );
    driver.send(Message::Transfer(Action::Cancel));
    driver.settle().await;
    assert_eq!(driver.app.hits.len(), initial + 4);
    driver.send(Message::Transfer(Action::Pick(Pick::Archive)));
    driver.settle().await;
    driver.send(Message::Transfer(Action::Commit));
    driver.settle().await;
    assert_eq!(driver.app.hits.len(), (initial + 4) * 2);
    let id = driver
        .app
        .hits
        .iter()
        .find(|hit| hit.title == "two pages # café")
        .unwrap()
        .document_id
        .clone();
    driver.send(Message::OpenDocument(id));
    driver.settle().await;
    let doc = driver.app.selected.as_ref().unwrap();
    driver.send(Message::Transfer(Action::Pick(Pick::Pdf {
        token: doc.token.clone(),
        title: doc.title.clone(),
    })));
    driver.settle().await;
    assert_eq!(
        std::fs::read(&pdf).unwrap(),
        std::fs::read(&files[0]).unwrap()
    );
    std::fs::write(
        root.join("application-report.json"),
        serde_json::to_vec_pretty(&json!({
            "status":"passed", "headless":true, "real_ashpd_requests":6,
            "chooser_ui_tested":false, "four_import_types_saved":true,
            "uri_spaces_unicode_fragment_characters":true,"cancel_preserved_saved_state":true,
            "archive_review_cancel_commit":true,"pdf_original_byte_identical":true,
            "final_documents":driver.app.hits.len()
        }))
        .unwrap(),
    )
    .unwrap();
}

type Actions = BoxStream<'static, iced::runtime::Action<cosmic::Action<Message>>>;
struct Driver {
    app: Stack,
    tasks: SelectAll<Actions>,
    simulated_captures: u8,
    hold_workflow_close: bool,
}
impl Driver {
    fn new(root: PathBuf) -> Self {
        let flags = Flags {
            visual_review: None,
            demo: None,
            preferences: Some(root.parent().unwrap().join("config/view.json")),
            library: Some(root),
            browser_fixtures: None,
            browser_case: "saved".into(),
            expected: None,
            smoke_expected: None,
            smoke: None,
            completed: Arc::default(),
            frames: Arc::default(),
            smoke_title: None,
            ui_scale: 1.0,
        };
        let mut core = Core::default();
        core.set_main_window_id(Some(iced::window::Id::RESERVED));
        let (app, task) = Stack::init(core, flags);
        let mut driver = Self {
            app,
            tasks: SelectAll::new(),
            simulated_captures: 0,
            hold_workflow_close: false,
        };
        driver.push(task);
        driver
    }
    fn push(&mut self, task: cosmic::app::Task<Message>) {
        if let Some(stream) = iced::runtime::task::into_stream(task) {
            self.tasks.push(stream);
        }
    }
    fn send(&mut self, message: Message) {
        let task = self.app.update(message);
        self.push(task);
    }
    async fn settle(&mut self) {
        self.until(|_| false).await;
    }
    async fn until(&mut self, ready: impl Fn(&Stack) -> bool) {
        if ready(&self.app) {
            return;
        }
        while let Some(action) =
            tokio::time::timeout(std::time::Duration::from_secs(60), self.tasks.next())
                .await
                .expect("bounded controller work")
        {
            if let iced::runtime::Action::Output(cosmic::Action::App(message)) = action {
                if self.app.flags.browser_case == "workflow" && matches!(message, Message::Capture)
                {
                    // Headless only: omit the native capture and continue the data
                    // scenario. This never counts as visual or native evidence.
                    self.simulated_captures += 1;
                    self.send(Message::CaptureSaved(Ok(())));
                } else if !(self.hold_workflow_close && matches!(message, Message::Close)) {
                    self.send(message);
                }
            }
            if ready(&self.app) {
                return;
            }
        }
    }
    async fn probe(&mut self, probe: stack_core::library::Probe) {
        self.app
            .repository
            .as_ref()
            .unwrap()
            .submit_required(crate::repository::Request::Probe(probe))
            .await
            .unwrap()
            .unwrap();
    }
    async fn pick(&mut self, pick: Pick, paths: Vec<PathBuf>) {
        self.send(Message::Picked(pick, Ok(Some(paths))));
        self.settle().await;
    }
}

fn navigation_snapshot(
    app: &Stack,
    name: &str,
) -> (
    Option<iced_accessibility::accesskit::Rect>,
    iced_accessibility::accesskit::Rect,
    Option<iced_accessibility::accesskit::Rect>,
) {
    use cosmic::ApplicationExt;
    use iced::advanced::renderer::Headless;
    let software = iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
    #[cfg(feature = "gpu")]
    let mut renderer = cosmic::Renderer::Secondary(software);
    #[cfg(not(feature = "gpu"))]
    let mut renderer = software;
    let mut ui = iced::runtime::UserInterface::build(
        app.view_main(),
        iced::Size::new(1080., 780.),
        iced::runtime::user_interface::Cache::default(),
        &mut renderer,
    );
    ui.draw(
        &mut renderer,
        &cosmic::Theme::light(),
        &iced::advanced::renderer::Style::default(),
        iced::mouse::Cursor::Unavailable,
    );
    let nodes: Vec<(
        iced_accessibility::accesskit::NodeId,
        iced_accessibility::accesskit::Node,
    )> = ui.a11y_nodes(iced::mouse::Cursor::Unavailable).into();
    let bounds = |label: &str| {
        nodes
            .iter()
            .find(|(_, n)| n.label() == Some(label))
            .and_then(|(_, node)| node.bounds())
    };
    let chrome = (
        bounds("Back"),
        bounds("Backup & restore").expect("sidebar backup action"),
        bounds("Add documents…"),
    );
    let _cache = ui.into_cache(); // Keep paragraph buffers alive through screenshot.
    if let Some(out) = std::env::var_os("STACK_M2_UI_REVIEW_OUT") {
        let out = PathBuf::from(out);
        assert!(out.is_absolute() && out.starts_with("/tmp"));
        std::fs::create_dir_all(&out).unwrap();
        #[cfg(feature = "gpu")]
        let pixels = match &mut renderer {
            cosmic::Renderer::Secondary(r) => {
                r.screenshot(iced::Size::new(1080, 780), 1., iced::Color::WHITE)
            }
            _ => unreachable!(),
        };
        #[cfg(not(feature = "gpu"))]
        let pixels = renderer.screenshot(iced::Size::new(1080, 780), 1., iced::Color::WHITE);
        image::save_buffer(
            out.join(format!("{name}.png")),
            &pixels,
            1080,
            780,
            image::ColorType::Rgba8,
        )
        .unwrap();
    }
    chrome
}

#[test]
fn navigation_back_sidebar_and_background_refresh_keep_the_users_location() {
    use crate::{catalog, editor, navigation::Location};
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let mut driver = Driver::new(directory.path().join("library"));
            driver.settle().await;
            driver
                .pick(
                    Pick::Documents,
                    vec![fixtures.join("corners.png"), fixtures.join("oriented.jpg")],
                )
                .await;
            driver.send(Message::Transfer(Action::Dismiss));
            let first = navigation_snapshot(&driver.app, "01-documents");
            driver.send(Message::Query("corners".into()));
            driver.settle().await;
            driver.app.browse_scroll = 120.;
            let manage = driver
                .app
                .nav
                .iter()
                .find(|id| driver.app.nav.data::<Location>(*id) == Some(&Location::ManageStacks))
                .unwrap();
            let task = driver.app.on_nav_select(manage);
            driver.push(task);
            driver.settle().await;
            assert!(driver.app.catalog.open);
            assert_eq!(
                driver.app.nav.data::<Location>(driver.app.nav.active()),
                Some(&Location::ManageStacks)
            );
            let manager = navigation_snapshot(&driver.app, "02-manage-stacks");
            assert!(first.0.is_none(), "Back is hidden at the library root");
            assert!(manager.0.is_some());
            assert_eq!((manager.1, manager.2), (first.1, first.2));
            driver.send(Message::LibraryMenu(true));
            assert_eq!(navigation_snapshot(&driver.app, "05-library-menu"), manager);
            let task = driver.app.on_escape();
            driver.push(task);
            driver.settle().await;
            assert!(
                !driver.app.library_menu_open && driver.app.catalog.open,
                "Escape closes the action menu first"
            );
            driver.send(Message::Back);
            driver.settle().await;
            assert!(!driver.app.catalog.open && driver.app.selected.is_none());
            assert_eq!(driver.app.query, "corners");
            assert_eq!(driver.app.browse_scroll, 120.);
            assert_eq!(driver.app.hits.len(), 1);
            driver.send(Message::Catalog(catalog::Action::Open));
            driver.settle().await;
            let all = driver
                .app
                .nav
                .iter()
                .find(|id| driver.app.nav.data::<Location>(*id) == Some(&Location::Documents))
                .unwrap();
            let task = driver.app.on_nav_select(all);
            driver.push(task);
            driver.settle().await;
            assert!(
                !driver.app.catalog.open,
                "sidebar navigation actually leaves stack management"
            );
            driver.send(Message::Query(String::new()));
            driver.settle().await;
            let doc = driver.app.hits[0].document_id.clone();
            driver.send(Message::OpenDocument(doc.clone()));
            assert!(
                driver.app.can_go_back(),
                "Back exists while a document is loading"
            );
            driver.settle().await;
            let viewer = navigation_snapshot(&driver.app, "03-document");
            assert_eq!((viewer.0, viewer.1), (manager.0, manager.1));
            assert!(viewer.2.is_none(), "library Add is absent in the viewer");
            driver.send(Message::Editor(editor::Action::Open));
            assert_eq!(navigation_snapshot(&driver.app, "04-edit-document"), viewer);
            driver.send(Message::Editor(editor::Action::Title(
                "Unsaved authored title".into(),
            )));
            // A real completed import refreshes the saved list but must not kick the
            // user out of their current editor or replace the unrelated draft.
            driver
                .pick(Pick::Documents, vec![fixtures.join("corners.webp")])
                .await;
            assert!(driver.app.editor_open);
            assert_eq!(driver.app.selected.as_ref().unwrap().token.id, doc);
            assert_eq!(driver.app.drafts[&doc].title, "Unsaved authored title");
            assert_eq!(driver.app.hits.len(), 3);
            driver.send(Message::Back);
            driver.settle().await;
            assert!(!driver.app.editor_open && driver.app.selected.is_some());
            driver.send(Message::Back);
            driver.settle().await;
            assert!(driver.app.selected.is_none() && !driver.app.catalog.open);
            // Search from stack management also changes the actual visible location.
            driver.send(Message::Catalog(catalog::Action::Open));
            driver.settle().await;
            let task = driver.app.on_search();
            driver.push(task);
            driver.settle().await;
            assert!(!driver.app.catalog.open && !driver.app.editor_open);
            driver.send(Message::Close);
            driver.settle().await;
        });
}

#[derive(Default)]
struct FocusAudit {
    focused: Vec<Option<iced::widget::Id>>,
    total: usize,
}

// Pointer events traverse the real native widgets; resulting messages then run
// through the application driver. No OS window or compositor is involved.
fn click_workspace(app: &Stack, label: &str, size: iced::Size) -> Vec<Message> {
    let software = iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
    #[cfg(feature = "gpu")]
    let mut renderer = cosmic::Renderer::Secondary(software);
    #[cfg(not(feature = "gpu"))]
    let mut renderer = software;
    let mut ui = iced::runtime::UserInterface::build(
        app.view(),
        size,
        iced::runtime::user_interface::Cache::default(),
        &mut renderer,
    );
    ui.draw(
        &mut renderer,
        &cosmic::Theme::light(),
        &iced::advanced::renderer::Style::default(),
        iced::mouse::Cursor::Unavailable,
    );
    let nodes: Vec<_> = ui.a11y_nodes(iced::mouse::Cursor::Unavailable).into();
    let bounds = nodes
        .iter()
        .find(|(_, node)| node.label() == Some(label))
        .unwrap_or_else(|| {
            panic!(
                "missing {label}: {:?}",
                nodes
                    .iter()
                    .map(|(_, n)| (n.label(), n.description()))
                    .collect::<Vec<_>>()
            )
        })
        .1
        .bounds()
        .unwrap();
    let position = iced::Point::new(
        (bounds.x0 + bounds.x1) as f32 / 2.,
        (bounds.y0 + bounds.y1) as f32 / 2.,
    );
    assert!(
        position.x >= 0. && position.x < size.width && position.y >= 0. && position.y < size.height,
        "{label} must be reachable without clipping: {bounds:?}"
    );
    let mut messages = Vec::new();
    ui.update(
        &[
            iced::Event::Mouse(iced::mouse::Event::CursorMoved { position }),
            iced::Event::Mouse(iced::mouse::Event::ButtonPressed(iced::mouse::Button::Left)),
            iced::Event::Mouse(iced::mouse::Event::ButtonReleased(
                iced::mouse::Button::Left,
            )),
        ],
        iced::mouse::Cursor::Available(position),
        &mut renderer,
        &mut iced::advanced::clipboard::Null,
        &mut messages,
    );
    assert!(!messages.is_empty(), "{label} must dispatch an action");
    let _cache = ui.into_cache();
    if let Some(out) = std::env::var_os("STACK_M2_UI_REVIEW_OUT") {
        use iced::advanced::renderer::Headless;
        let out = PathBuf::from(out);
        assert!(out.is_absolute() && out.starts_with("/tmp"));
        std::fs::create_dir_all(&out).unwrap();
        let dimensions = iced::Size::new(size.width as u32, size.height as u32);
        #[cfg(feature = "gpu")]
        let pixels = match &mut renderer {
            cosmic::Renderer::Secondary(r) => r.screenshot(dimensions, 1., iced::Color::WHITE),
            _ => unreachable!(),
        };
        #[cfg(not(feature = "gpu"))]
        let pixels = renderer.screenshot(dimensions, 1., iced::Color::WHITE);
        let name: String = label.chars().filter(char::is_ascii_alphanumeric).collect();
        image::save_buffer(
            out.join(format!("before-{name}-{}.png", dimensions.width)),
            &pixels,
            dimensions.width,
            dimensions.height,
            image::ColorType::Rgba8,
        )
        .unwrap();
    }
    messages
}

#[test]
fn archive_review_pointer_click_survives_a_progress_tick() {
    use cosmic::ApplicationExt;
    struct ButtonBounds(Option<iced::Rectangle>);
    impl iced::advanced::widget::Operation for ButtonBounds {
        fn traverse(
            &mut self,
            operate: &mut dyn FnMut(&mut dyn iced::advanced::widget::Operation),
        ) {
            operate(self);
        }
        fn focusable(
            &mut self,
            id: Option<&iced::widget::Id>,
            bounds: iced::Rectangle,
            _: &mut dyn iced::advanced::widget::operation::Focusable,
        ) {
            if id == Some(&iced::widget::Id::new("stack-import-reviewed")) {
                self.0 = Some(bounds);
            }
        }
    }
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let mut driver = Driver::new(directory.path().join("library"));
            driver.settle().await;
            driver
                .pick(Pick::Documents, vec![fixtures.join("corners.png")])
                .await;
            let archive = directory.path().join("review.stackbackup");
            driver
                .pick(Pick::ArchiveExport, vec![archive.clone()])
                .await;
            driver.pick(Pick::Archive, vec![archive]).await;
            assert_eq!(
                driver.app.transfers.active.as_ref().unwrap().phase,
                Phase::Review
            );
            let id = driver.app.transfers.active.as_ref().unwrap().id.clone();
            let (started, mut start) = tokio::sync::mpsc::unbounded_channel();
            let (release, wait) = std::sync::mpsc::channel();
            driver.probe(Box::new(move |point| {
                if point == "journal" {
                    started.send(()).unwrap();
                    wait.recv_timeout(std::time::Duration::from_secs(15)).unwrap();
                }
                Ok(())
            })).await;
            driver.send(Message::LibraryMenu(true));
            let software =
                iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
            #[cfg(feature = "gpu")]
            let mut renderer = cosmic::Renderer::Secondary(software);
            #[cfg(not(feature = "gpu"))]
            let mut renderer = software;
            let size = iced::Size::new(1080., 780.);
            let mut ui = iced::runtime::UserInterface::build(
                driver.app.view_main(),
                size,
                iced::runtime::user_interface::Cache::default(),
                &mut renderer,
            );
            ui.draw(
                &mut renderer,
                &cosmic::Theme::light(),
                &iced::advanced::renderer::Style::default(),
                iced::mouse::Cursor::Unavailable,
            );
            let nodes: Vec<_> = ui.a11y_nodes(iced::mouse::Cursor::Unavailable).into();
            assert!(nodes.iter().any(|(_, n)| n.label() == Some("Import reviewed copies")), "review action must be exposed to accessibility");
            let mut target = ButtonBounds(None);
            ui.operate(&renderer, &mut target);
            let bounds = target.0.expect("import commit control");
            let position = bounds.center();
            assert!(
                position.y > 0. && position.y < size.height,
                "review button clipped: {bounds:?}"
            );
            let mut messages = Vec::new();
            ui.update(
                &[
                    iced::Event::Mouse(iced::mouse::Event::CursorMoved { position }),
                    iced::Event::Mouse(iced::mouse::Event::ButtonPressed(
                        iced::mouse::Button::Left,
                    )),
                ],
                iced::mouse::Cursor::Available(position),
                &mut renderer,
                &mut iced::advanced::clipboard::Null,
                &mut messages,
            );
            let cache = ui.into_cache();
            for message in messages.drain(..) {
                if let cosmic::Action::App(message) = message { driver.send(message); }
            }
            assert!(!driver.app.library_menu_open, "an outside press must dismiss the backup menu instead of trapping the import button");
            driver.send(Message::TransferTick(id));
            let mut ui = iced::runtime::UserInterface::build(
                driver.app.view_main(),
                size,
                cache,
                &mut renderer,
            );
            ui.update(
                &[iced::Event::Mouse(iced::mouse::Event::ButtonReleased(
                    iced::mouse::Button::Left,
                ))],
                iced::mouse::Cursor::Available(position),
                &mut renderer,
                &mut iced::advanced::clipboard::Null,
                &mut messages,
            );
            drop(ui);
            assert!(
                messages
                    .iter()
                    .any(|m| matches!(m, cosmic::Action::App(Message::Transfer(Action::Commit)))),
                "review click did not dispatch Commit: {messages:?}"
            );
            for message in messages {
                if let cosmic::Action::App(message) = message {
                    driver.send(message);
                }
            }
            assert_eq!(driver.app.transfers.active.as_ref().unwrap().phase, Phase::Committing);
            assert!(driver.app.transfers.status.as_ref().unwrap().starts_with("Importing 1 reviewed documents"));
            driver.send(Message::Transfer(Action::Commit)); // A second click cannot enqueue another import.
            tokio::time::timeout(std::time::Duration::from_secs(5), start.recv()).await.unwrap().unwrap();
            for size in [iced::Size::new(1080., 780.), iced::Size::new(680., 680.)] {
                let dispatched = click_workspace(&driver.app, "Cancel operation", size);
                assert!(dispatched.iter().any(|m| matches!(m, Message::Transfer(Action::Cancel))));
                // Rendering/dispatch only: do not cancel this successful test import.
            }
            release.send(()).unwrap();
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 2);
            assert!(driver.app.transfers.status.as_ref().unwrap().contains("Saved 1 imported documents"));
            driver.send(Message::Close);
            driver.settle().await;
        });
}

#[test]
fn mockup_workspace_pointer_controls_preserve_drafts_across_panels_and_narrow_preview() {
    use crate::editor::{Action as Edit, Panel};
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let mut driver = Driver::new(directory.path().join("library"));
            driver.settle().await;
            driver
                .pick(Pick::Documents, vec![fixtures.join("corners.png")])
                .await;
            driver.send(Message::Transfer(Action::Dismiss));
            let wide = iced::Size::new(1080., 780.);
            for message in click_workspace(&driver.app, "List view", wide) {
                driver.send(message);
            }
            driver.settle().await;
            assert!(!driver.app.grid_view);
            let id = driver.app.hits[0].document_id.clone();
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            for message in click_workspace(&driver.app, "Edit document", wide) {
                driver.send(message);
            }
            driver.send(Message::Editor(Edit::Title(
                "Unfinished exact title 0001".into(),
            )));
            for (label, panel) in [
                ("Details", Panel::Details),
                ("Pages", Panel::Pages),
                ("Information", Panel::Information),
            ] {
                for message in click_workspace(&driver.app, label, wide) {
                    driver.send(message);
                }
                assert_eq!(driver.app.drafts[&id].panel, panel);
                assert_eq!(driver.app.drafts[&id].title, "Unfinished exact title 0001");
                if panel == Panel::Pages {
                    for message in click_workspace(&driver.app, "Rotate page", wide) {
                        driver.send(message);
                    }
                    driver.settle().await;
                    assert_eq!(driver.app.selected.as_ref().unwrap().pages[0].rotation, 90);
                    assert_eq!(driver.app.drafts[&id].title, "Unfinished exact title 0001");
                }
            }
            let narrow = iced::Size::new(680., 680.);
            driver
                .app
                .on_window_resize(iced::window::Id::RESERVED, narrow.width, narrow.height);
            for message in click_workspace(&driver.app, "Show preview", narrow) {
                driver.send(message);
            }
            assert!(!driver.app.information_visible && driver.app.editor_open);
            for message in click_workspace(&driver.app, "Document information", narrow) {
                driver.send(message);
            }
            assert!(driver.app.information_visible);
            for message in click_workspace(&driver.app, "Done editing", narrow) {
                driver.send(message);
            }
            assert!(!driver.app.editor_open);
            assert!(driver.app.drafts[&id].has_changes(driver.app.selected.as_ref().unwrap()));
            driver.send(Message::Back);
            driver.settle().await;
            assert!(driver.app.selected.is_none() && !driver.app.grid_view);
            driver.send(Message::Close);
            driver.settle().await;
        });
}
impl iced::advanced::widget::Operation for FocusAudit {
    fn traverse(&mut self, traverse: &mut dyn FnMut(&mut dyn iced::advanced::widget::Operation)) {
        traverse(self);
    }
    fn focusable(
        &mut self,
        id: Option<&iced::widget::Id>,
        _bounds: iced::Rectangle,
        state: &mut dyn iced::advanced::widget::operation::Focusable,
    ) {
        self.total += 1;
        if state.is_focused() {
            self.focused.push(id.cloned());
        }
    }
}
#[derive(Default, Debug)]
struct ScrollAudit(Vec<(iced::Rectangle, iced::Rectangle, iced::Vector)>);
impl iced::advanced::widget::Operation for ScrollAudit {
    fn traverse(&mut self, traverse: &mut dyn FnMut(&mut dyn iced::advanced::widget::Operation)) {
        traverse(self);
    }
    fn scrollable(
        &mut self,
        id: Option<&iced::widget::Id>,
        bounds: iced::Rectangle,
        content: iced::Rectangle,
        translation: iced::Vector,
        _state: &mut dyn iced::advanced::widget::operation::Scrollable,
    ) {
        if id == Some(&iced::widget::Id::new("stack-documents")) {
            self.0.push((bounds, content, translation));
        }
    }
}

#[test]
fn native_widgets_scale_draw_repeatedly_and_keep_keyboard_search_focus_without_windows() {
    use cosmic::app::cosmic::Cosmic;
    use iced::advanced::renderer::Headless;
    use iced::advanced::widget::{
        Operation,
        operation::{Outcome, focusable},
    };
    use iced::{Size, mouse::Cursor};
    let _serial = CONTROLLERS.lock().unwrap();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    runtime.block_on(async {
        let directory = tempfile::tempdir().unwrap();
        let mut driver = Driver::new(directory.path().join("library"));
        driver.settle().await;
        let mut shell = Cosmic::new(driver.app);
        for scale in [1.0, 2.0] {
            drop(
                shell.update(cosmic::Action::Cosmic(cosmic::app::Action::ScaleFactor(
                    scale,
                ))),
            );
            assert_eq!(
                shell.scale_factor(iced::window::Id::RESERVED),
                f64::from(scale)
            );
            for size in [Size::new(1080., 780.), Size::new(680., 680.)] {
                drop(
                    shell.update(cosmic::Action::Cosmic(cosmic::app::Action::WindowResize(
                        iced::window::Id::RESERVED,
                        size.width,
                        size.height,
                    ))),
                );
                for theme in [cosmic::Theme::light(), cosmic::Theme::dark()] {
                    let software =
                        iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
                    #[cfg(feature = "gpu")]
                    let mut renderer = cosmic::Renderer::Secondary(software);
                    #[cfg(not(feature = "gpu"))]
                    let mut renderer = software;
                    let mut cache = iced::runtime::user_interface::Cache::default();
                    for frame in 0..5 {
                        let mut ui = iced::runtime::UserInterface::build(
                            shell.view(iced::window::Id::RESERVED),
                            size / scale,
                            cache,
                            &mut renderer,
                        );
                        ui.operate(
                            &renderer,
                            &mut focusable::focus::<()>(iced::widget::Id::new("stack-search")),
                        );
                        let mut audit = FocusAudit::default();
                        ui.operate(&renderer, &mut audit);
                        assert_eq!(
                            audit.focused,
                            vec![Some(iced::widget::Id::new("stack-search"))]
                        );
                        assert!(audit.total >= 5);
                        if frame == 0 {
                            let key = iced::keyboard::Key::Character("a".into());
                            let event = iced::Event::Keyboard(iced::keyboard::Event::KeyPressed {
                                key: key.clone(),
                                modified_key: key,
                                physical_key: iced::keyboard::key::Physical::Code(
                                    iced::keyboard::key::Code::KeyA,
                                ),
                                location: iced::keyboard::Location::Standard,
                                modifiers: iced::keyboard::Modifiers::empty(),
                                text: Some("a".into()),
                                repeat: false,
                            });
                            let mut messages = Vec::new();
                            ui.update(
                                &[event],
                                Cursor::Unavailable,
                                &mut renderer,
                                &mut iced::advanced::clipboard::Null,
                                &mut messages,
                            );
                            assert!(messages.iter().any(
                                |m| matches!(m,cosmic::Action::App(Message::Query(q)) if q=="a")
                            ));
                        }
                        // Traverse the same operations used by native Tab/Shift-Tab,
                        // including their chained count/traversal phases.
                        let mut operation: Box<dyn Operation> = Box::new(focusable::focus_next());
                        loop {
                            ui.operate(&renderer, operation.as_mut());
                            if let Outcome::Chain(next) = operation.finish() {
                                operation = next;
                            } else {
                                break;
                            }
                        }
                        let mut audit = FocusAudit::default();
                        ui.operate(&renderer, &mut audit);
                        assert_eq!(audit.focused.len(), 1);
                        assert_ne!(
                            audit.focused[0],
                            Some(iced::widget::Id::new("stack-search"))
                        );
                        ui.draw(
                            &mut renderer,
                            &theme,
                            &iced::advanced::renderer::Style::default(),
                            Cursor::Unavailable,
                        );
                        let nodes: Vec<_> = ui.a11y_nodes(Cursor::Unavailable).into();
                        assert!(
                            nodes.len() >= 8,
                            "native widgets expose an accessibility tree"
                        );
                        cache = ui.into_cache();
                        #[cfg(feature = "gpu")]
                        let pixels = match &mut renderer {
                            cosmic::Renderer::Secondary(r) => r.screenshot(
                                Size::new(size.width as u32, size.height as u32),
                                scale,
                                iced::Color::WHITE,
                            ),
                            _ => unreachable!(),
                        };
                        #[cfg(not(feature = "gpu"))]
                        let pixels = renderer.screenshot(
                            Size::new(size.width as u32, size.height as u32),
                            scale,
                            iced::Color::WHITE,
                        );
                        assert_eq!(pixels.len(), size.width as usize * size.height as usize * 4);
                        assert!(
                            pixels
                                .as_chunks::<4>()
                                .0
                                .iter()
                                .filter(|p| p[..3] != [255, 255, 255])
                                .count()
                                > 1000,
                            "frame {frame} must draw real shell/content"
                        );
                    }
                }
            }
        }
        drop(shell.app.renderer.take());
        drop(shell.app.transfer_worker.take());
        drop(shell.app.repository.take());
    });
}

#[test]
fn native_notes_pixels_survive_repeated_headless_widget_rebuilds() {
    use crate::editor::Action as Edit;
    use iced::advanced::renderer::Headless;
    use iced::{Size, mouse::Cursor};
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let mut driver = Driver::new(directory.path().join("library"));
            driver.settle().await;
            driver
                .pick(Pick::Documents, vec![fixtures.join("oriented.jpg")])
                .await;
            let id = driver.app.hits[0].document_id.clone();
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            driver.send(Message::Editor(Edit::Open));
            let notes = "Authored multiline notes\n000123.400 café 🦀\n";
            driver.app.drafts.get_mut(&id).unwrap().notes =
                cosmic::widget::text_editor::Content::with_text(notes);
            driver.send(Message::Editor(Edit::SaveNotes));
            driver.settle().await;
            for theme in [cosmic::Theme::light(), cosmic::Theme::dark()] {
                let software =
                    iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
                #[cfg(feature = "gpu")]
                let mut renderer = cosmic::Renderer::Secondary(software);
                #[cfg(not(feature = "gpu"))]
                let mut renderer = software;
                let mut cache = iced::runtime::user_interface::Cache::default();
                let mut empty = Vec::new();
                for frame in 0..6 {
                    if frame == 0 {
                        driver.app.drafts.get_mut(&id).unwrap().notes =
                            cosmic::widget::text_editor::Content::new();
                    }
                    if frame == 1 {
                        driver.app.drafts.get_mut(&id).unwrap().notes =
                            cosmic::widget::text_editor::Content::with_text(notes);
                    }
                    let mut ui = iced::runtime::UserInterface::build(
                        driver.app.view(),
                        Size::new(1080., 780.),
                        cache,
                        &mut renderer,
                    );
                    ui.update(
                        &[iced::Event::Window(iced::window::Event::RedrawRequested(
                            std::time::Instant::now(),
                        ))],
                        Cursor::Unavailable,
                        &mut renderer,
                        &mut iced::advanced::clipboard::Null,
                        &mut Vec::new(),
                    );
                    ui.draw(
                        &mut renderer,
                        &theme,
                        &iced::advanced::renderer::Style::default(),
                        Cursor::Unavailable,
                    );
                    cache = ui.into_cache();
                    #[cfg(feature = "gpu")]
                    let pixels = match &mut renderer {
                        cosmic::Renderer::Secondary(r) => {
                            r.screenshot(Size::new(1080, 780), 1., iced::Color::WHITE)
                        }
                        _ => unreachable!(),
                    };
                    #[cfg(not(feature = "gpu"))]
                    let pixels = renderer.screenshot(Size::new(1080, 780), 1., iced::Color::WHITE);
                    if frame == 0 {
                        empty = pixels;
                    } else {
                        assert!(
                            pixels
                                .as_chunks::<4>()
                                .0
                                .iter()
                                .zip(empty.as_chunks::<4>().0.iter())
                                .filter(|(a, b)| a != b)
                                .count()
                                > 100,
                            "saved notes must contribute pixels after widget rebuild {frame}"
                        );
                    }
                }
            }
            driver.send(Message::Close);
            driver.settle().await;
        });
}

#[test]
fn application_import_batch_retry_archive_review_cancel_commit_and_export_are_real() {
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let root = directory.path().join("library");
            let bad = directory.path().join("bad-input.png");
            std::fs::write(&bad, b"authored invalid input").unwrap();
            let before: Vec<_> = ["oriented.jpg", "corners.png", "corners.webp"]
                .iter()
                .map(|name| stack_render::fixtures::digest(&fixtures.join(name)).unwrap())
                .collect();
            let mut driver = Driver::new(root.clone());
            driver.settle().await;
            driver
                .pick(
                    Pick::Documents,
                    vec![
                        fixtures.join("oriented.jpg"),
                        bad.clone(),
                        fixtures.join("corners.png"),
                        fixtures.join("corners.webp"),
                    ],
                )
                .await;
            assert_eq!(driver.app.hits.len(), 3);
            assert_eq!(driver.app.transfers.outcomes.len(), 4);
            assert_eq!(
                driver
                    .app
                    .transfers
                    .outcomes
                    .iter()
                    .filter(|(_, s)| s == "Imported and saved")
                    .count(),
                3
            );
            assert!(
                driver
                    .app
                    .transfers
                    .outcomes
                    .iter()
                    .any(|(_, s)| s.contains("Failed before saving"))
            );
            std::fs::copy(fixtures.join("corners.png"), &bad).unwrap();
            driver.send(Message::Transfer(Action::Retry));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 4); // Retry includes only the failed input.
            let output = directory.path().join("library.stackbackup");
            driver.pick(Pick::ArchiveExport, vec![output.clone()]).await;
            assert!(output.is_file());
            let archive_hash = stack_render::fixtures::digest(&output).unwrap();
            driver.pick(Pick::Archive, vec![output.clone()]).await;
            assert_eq!(
                driver.app.transfers.active.as_ref().unwrap().phase,
                Phase::Review
            );
            let review = driver
                .app
                .transfers
                .active
                .as_ref()
                .unwrap()
                .review
                .as_ref()
                .unwrap();
            assert_eq!(review.documents_added, 4);
            assert_eq!(review.document_collisions, 4);
            driver.send(Message::Transfer(Action::Cancel));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 4);
            assert!(driver.app.transfers.active.is_none());
            let malformed = directory.path().join("malformed.stackbackup");
            std::fs::write(&malformed, b"authored malformed archive").unwrap();
            driver.pick(Pick::Archive, vec![malformed]).await;
            assert_eq!(driver.app.hits.len(), 4);
            assert!(driver.app.transfers.active.is_none());
            assert!(
                driver
                    .app
                    .transfers
                    .status
                    .as_ref()
                    .unwrap()
                    .contains("Failed before saving")
            );
            driver.pick(Pick::Archive, vec![output.clone()]).await;
            // A real saved edit while the review is open invalidates its plan.
            let edited = driver.app.hits[0].document_id.clone();
            driver.send(Message::OpenDocument(edited.clone()));
            driver.settle().await;
            driver.send(Message::Editor(crate::editor::Action::Open));
            driver.send(Message::Editor(crate::editor::Action::Title(
                "Saved while reviewing".into(),
            )));
            driver.send(Message::Editor(crate::editor::Action::SaveTitle));
            driver.settle().await;
            driver.send(Message::Transfer(Action::Commit));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 4);
            assert_eq!(
                driver.app.transfers.active.as_ref().unwrap().phase,
                Phase::Review
            );
            assert!(
                driver
                    .app
                    .transfers
                    .status
                    .as_ref()
                    .unwrap()
                    .contains("Review the staged archive again")
            );
            driver.send(Message::Transfer(Action::ReviewAgain));
            driver.settle().await;
            driver.send(Message::Transfer(Action::Commit));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 8);
            driver.send(Message::Transfer(Action::Commit));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 8); // No active review means no second commit.
            driver.pick(Pick::ArchiveExport, vec![output.clone()]).await;
            assert_eq!(
                stack_render::fixtures::digest(&output).unwrap(),
                archive_hash
            );
            assert!(
                driver
                    .app
                    .transfers
                    .status
                    .as_ref()
                    .unwrap()
                    .contains("Failed before saving")
            );
            assert_eq!(driver.app.hits.len(), 8);
            driver.pick(Pick::Archive, vec![output.clone()]).await;
            assert_eq!(
                driver.app.transfers.active.as_ref().unwrap().phase,
                Phase::Review
            );
            driver.send(Message::Close);
            driver.settle().await;
            let library = stack_core::library::Library::open(&root).unwrap();
            assert_eq!(library.status().unwrap().documents, 8);
            assert_eq!(
                library.document_view(&edited).unwrap().title,
                "Saved while reviewing"
            );
            assert_eq!(
                std::fs::read_dir(root.join("operations")).unwrap().count(),
                0
            );
            for (index, name) in ["oriented.jpg", "corners.png", "corners.webp"]
                .iter()
                .enumerate()
            {
                assert_eq!(
                    stack_render::fixtures::digest(&fixtures.join(name)).unwrap(),
                    before[index]
                );
            }
            assert!(Path::new(&output).is_file());
        });
}

#[test]
fn application_storage_failure_keeps_draft_and_committed_retry_keeps_one_import() {
    use crate::editor::Action as Edit;
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let root = directory.path().join("library");
            let mut driver = Driver::new(root.clone());
            driver.settle().await;
            driver
                .pick(Pick::Documents, vec![fixtures.join("oriented.jpg")])
                .await;
            let id = driver.app.hits[0].document_id.clone();
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            driver.send(Message::Editor(Edit::Open));
            let notes = "Failed save keeps exact 0001.200 café 🦀\n";
            driver.app.drafts.get_mut(&id).unwrap().notes =
                cosmic::widget::text_editor::Content::with_text(notes);
            driver
                .probe(Box::new(|point| {
                    if point == "metadata_transaction" {
                        Err(std::io::Error::other("authored storage failure"))
                    } else {
                        Ok(())
                    }
                }))
                .await;
            driver.send(Message::Editor(Edit::SaveNotes));
            driver.settle().await;
            assert_eq!(driver.app.drafts[&id].notes.text(), notes);
            assert!(
                driver.app.drafts[&id]
                    .status
                    .as_ref()
                    .unwrap()
                    .contains("draft is retained")
            );
            assert!(driver.app.selected.as_ref().unwrap().notes.is_empty());
            driver.probe(Box::new(|_| Ok(()))).await;
            driver.send(Message::Editor(Edit::Refresh));
            driver.settle().await;
            driver.send(Message::Editor(Edit::UseLoadedVersion));
            driver.send(Message::Editor(Edit::SaveNotes));
            driver.settle().await;
            assert_eq!(driver.app.selected.as_ref().unwrap().notes, notes);
            // Escape cancels a confirmation before leaving the retained draft.
            driver.app.editor_open = true;
            driver.app.drafts.get_mut(&id).unwrap().confirm_delete = true;
            drop(driver.app.on_escape());
            assert!(driver.app.editor_open);
            assert!(!driver.app.drafts[&id].confirm_delete);
            drop(driver.app.on_escape());
            assert!(!driver.app.editor_open);
            let unreadable = directory.path().join("not-yet-readable.png");
            driver.pick(Pick::Documents, vec![unreadable.clone()]).await;
            assert_eq!(driver.app.hits.len(), 1);
            assert!(
                driver.app.transfers.outcomes[0]
                    .1
                    .contains("Failed before saving")
            );
            std::fs::copy(fixtures.join("corners.png"), &unreadable).unwrap();
            driver.send(Message::Transfer(Action::Retry));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 2);
            driver
                .probe(Box::new(|point| {
                    if point == "db_committed" {
                        Err(std::io::Error::other(
                            "authored post-commit cleanup failure",
                        ))
                    } else {
                        Ok(())
                    }
                }))
                .await;
            driver
                .pick(Pick::Documents, vec![fixtures.join("corners.webp")])
                .await;
            assert_eq!(
                driver.app.hits.len(),
                3,
                "committed data visible even while cleanup needs retry"
            );
            let active = driver.app.transfers.active.as_ref().unwrap();
            assert_eq!(active.phase, Phase::CleanupNeeded);
            assert!(active.committed.is_some());
            assert!(
                driver
                    .app
                    .transfers
                    .status
                    .as_ref()
                    .unwrap()
                    .contains("Saved 1 imported documents")
            );
            driver.probe(Box::new(|_| Ok(()))).await;
            driver.send(Message::Transfer(Action::RetryCleanup));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 3);
            driver.send(Message::Transfer(Action::RetryCleanup));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 3);
            driver.send(Message::Close);
            driver.settle().await;
            let library = stack_core::library::Library::open(&root).unwrap();
            assert_eq!(library.status().unwrap().documents, 3);
            assert_eq!(library.document_view(&id).unwrap().notes, notes);
            assert_eq!(
                library
                    .journal()
                    .unwrap()
                    .iter()
                    .filter(|e| e["kind"] == "archive.restore")
                    .count(),
                3
            );
            assert_eq!(
                std::fs::read_dir(root.join("operations")).unwrap().count(),
                0
            );
        });
}

#[test]
fn application_selection_delete_and_close_during_preview_reject_late_results() {
    use crate::{RenderJob, editor::Action as Edit, worker::Worker};
    use std::time::{Duration, Instant};
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let root = directory.path().join("library");
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let mut driver = Driver::new(root.clone());
            driver.settle().await;
            driver
                .pick(
                    Pick::Documents,
                    vec![fixtures.join("oriented.jpg"), fixtures.join("corners.png")],
                )
                .await;
            let ids: Vec<_> = driver
                .app
                .hits
                .iter()
                .map(|h| h.document_id.clone())
                .collect();
            let originals: Vec<_> = ids
                .iter()
                .map(|id| {
                    let p = root.join("originals").join(id).join("source");
                    let hash = stack_render::fixtures::digest(&p).unwrap();
                    (p, hash)
                })
                .collect();
            let (started, mut start) = tokio::sync::mpsc::unbounded_channel();
            let (release, wait) = std::sync::mpsc::channel();
            let mut first = true;
            driver.app.renderer = Some(Worker::new("m2-gated-preview", move |job: RenderJob| {
                let result = stack_render::Renderer::from_environment()
                    .render_saved(
                        &job.source,
                        &job.saved.unwrap(),
                        stack_core::RenderSize::new(200, 200).unwrap(),
                    )
                    .map(Arc::new)
                    .map_err(|e| e.to_string());
                if first {
                    first = false;
                    started.send(()).unwrap();
                    wait.recv_timeout(Duration::from_secs(10)).unwrap();
                }
                result
            }));
            driver.send(Message::OpenDocument(ids[0].clone()));
            driver.until(|a| a.selected.is_some() && a.busy).await;
            tokio::time::timeout(Duration::from_secs(5), start.recv())
                .await
                .unwrap()
                .unwrap();
            let stale = driver.app.request;
            // Superseded selects/searches are bounded; only the last stable selection
            // may display when the deliberately delayed real render is released.
            for n in 0..30 {
                driver.send(Message::OpenDocument(ids[n % 2].clone()));
            }
            driver.send(Message::OpenDocument(ids[1].clone()));
            driver
                .until(|a| a.selected.as_ref().is_some_and(|d| d.token.id == ids[1]) && a.busy)
                .await;
            driver.send(Message::Editor(Edit::Open));
            driver.send(Message::Editor(Edit::Delete));
            driver.send(Message::Editor(Edit::ConfirmDelete));
            driver
                .until(|a| !a.saving && a.selected.is_none() && a.hits.len() == 1)
                .await;
            release.send(()).unwrap();
            driver.settle().await;
            assert!(driver.app.selected.is_none());
            assert!(driver.app.preview.is_none());
            driver.send(Message::Rendered(
                stale,
                Err("late deleted preview must be ignored".into()),
            ));
            assert!(driver.app.error.is_none());
            // Exercise a real preview job during Close. Joining remains off the
            // executor; queued completions cannot revive selected/deleted UI state.
            let (release, wait) = std::sync::mpsc::channel();
            let (started, mut start) = tokio::sync::mpsc::unbounded_channel();
            driver.app.renderer = Some(Worker::new("m2-closing-preview", move |job: RenderJob| {
                started.send(()).unwrap();
                wait.recv_timeout(Duration::from_secs(5)).unwrap();
                stack_render::Renderer::from_environment()
                    .render_saved(
                        &job.source,
                        &job.saved.unwrap(),
                        stack_core::RenderSize::new(200, 200).unwrap(),
                    )
                    .map(Arc::new)
                    .map_err(|e| e.to_string())
            }));
            driver.send(Message::OpenDocument(ids[0].clone()));
            driver.until(|a| a.selected.is_some() && a.busy).await;
            start.recv().await.unwrap();
            driver.send(Message::Close);
            let generation = driver.app.repository_generation;
            let request = driver.app.request;
            driver.send(Message::OpenDocument(ids[1].clone()));
            driver.send(Message::Query("late text".into()));
            driver.send(Message::Rendered(
                request,
                Err("late current completion".into()),
            ));
            assert_eq!(driver.app.repository_generation, generation);
            assert_eq!(driver.app.request, request);
            assert!(driver.app.error.is_none());
            let tick = Instant::now();
            tokio::time::sleep(Duration::from_millis(10)).await;
            assert!(tick.elapsed() < Duration::from_millis(250));
            release.send(()).unwrap();
            driver.settle().await;
            let library = stack_core::library::Library::open(&root).unwrap();
            assert_eq!(library.status().unwrap().documents, 1);
            assert!(library.document_view(&ids[1]).is_err());
            assert!(library.document_view(&ids[0]).is_ok());
            for (path, hash) in originals {
                assert_eq!(
                    stack_render::fixtures::digest(&path).unwrap(),
                    hash,
                    "deletion retains original bytes"
                );
            }
        });
}

#[test]
#[ignore = "requires the pinned native engine via STACK_PDFIUM_LIBRARY"]
fn application_pdf_import_reorder_subset_and_output_use_saved_snapshot() {
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let directory = tempfile::tempdir().unwrap();
            let fixtures = directory.path().join("fixtures");
            stack_render::fixtures::create(&fixtures).unwrap();
            let root = directory.path().join("library");
            let mut driver = Driver::new(root.clone());
            driver.settle().await;
            driver
                .pick(Pick::Documents, vec![fixtures.join("two-pages.pdf")])
                .await;
            assert_eq!(driver.app.hits.len(), 1);
            let id = driver.app.hits[0].document_id.clone();
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            // Same saved PDF and app Retry path, with a deliberately missing
            // engine configuration on the preview worker only. No global env or
            // process-wide native binding is reset.
            let renderer = driver.app.renderer.take();
            driver.app.renderer = Some(crate::worker::Worker::new(
                "m2-missing-engine",
                |job: crate::RenderJob| {
                    stack_render::Renderer::new(None)
                        .render_saved(
                            &job.source,
                            &job.saved.unwrap(),
                            stack_core::RenderSize::new(300, 300).unwrap(),
                        )
                        .map(Arc::new)
                        .map_err(|e| e.to_string())
                },
            ));
            driver.send(Message::Retry);
            driver.settle().await;
            assert!(
                driver
                    .app
                    .error
                    .as_ref()
                    .unwrap()
                    .contains("STACK_PDFIUM_LIBRARY")
            );
            assert!(driver.app.selected.is_some());
            drop(driver.app.renderer.take());
            driver.app.renderer = renderer;
            driver.send(Message::Retry);
            driver.settle().await;
            assert!(driver.app.error.is_none() && driver.app.preview.is_some());
            // Hold a real old-page render across a real saved reorder. The
            // delayed portrait result must not replace the new landscape page.
            let ordinary = driver.app.renderer.take();
            let (started, mut start) = tokio::sync::mpsc::unbounded_channel();
            let (release, wait) = std::sync::mpsc::channel();
            let mut first = true;
            driver.app.renderer = Some(crate::worker::Worker::new(
                "m2-reorder-preview",
                move |job: crate::RenderJob| {
                    let result = stack_render::Renderer::from_environment()
                        .render_saved(
                            &job.source,
                            &job.saved.unwrap(),
                            stack_core::RenderSize::new(300, 300).unwrap(),
                        )
                        .map(Arc::new)
                        .map_err(|e| e.to_string());
                    if first {
                        first = false;
                        started.send(()).unwrap();
                        wait.recv_timeout(std::time::Duration::from_secs(10))
                            .unwrap();
                    }
                    result
                },
            ));
            driver.send(Message::Retry);
            driver.until(|a| a.selected.is_some() && a.busy).await;
            start.recv().await.unwrap();
            driver.send(Message::Editor(crate::editor::Action::Open));
            driver.send(Message::Editor(crate::editor::Action::MovePage(1)));
            driver
                .until(|a| {
                    !a.saving
                        && a.selected
                            .as_ref()
                            .is_some_and(|d| d.pages[0].source_page == 1)
                        && a.busy
                })
                .await;
            release.send(()).unwrap();
            driver.settle().await;
            match driver.app.preview.as_ref().unwrap() {
                cosmic::widget::image::Handle::Rgba { width, height, .. } => {
                    assert!(width > height)
                }
                _ => panic!("saved page must render into bounded pixels"),
            }
            driver.send(Message::Editor(crate::editor::Action::MovePage(1)));
            driver.settle().await;
            assert_eq!(
                driver.app.selected.as_ref().unwrap().pages[0].source_page,
                0
            );
            drop(driver.app.renderer.take());
            driver.app.renderer = ordinary;
            let pick = |app: &Stack| {
                let doc = app.selected.as_ref().unwrap();
                Pick::Pdf {
                    token: doc.token.clone(),
                    title: doc.title.clone(),
                }
            };
            let original = directory.path().join("original.pdf");
            driver.pick(pick(&driver.app), vec![original.clone()]).await;
            assert_eq!(
                std::fs::read(&original).unwrap(),
                std::fs::read(fixtures.join("two-pages.pdf")).unwrap()
            );
            assert!(
                driver
                    .app
                    .transfers
                    .status
                    .as_ref()
                    .unwrap()
                    .contains("copied exactly")
            );
            driver.send(Message::Editor(crate::editor::Action::Open));
            driver.send(Message::Editor(crate::editor::Action::MovePage(1)));
            driver.settle().await;
            let reordered = directory.path().join("reordered.pdf");
            driver
                .pick(pick(&driver.app), vec![reordered.clone()])
                .await;
            assert!(
                driver
                    .app
                    .transfers
                    .status
                    .as_ref()
                    .unwrap()
                    .contains("no selectable text layer")
            );
            let renderer = stack_render::Renderer::from_environment();
            renderer.inspect_generated_pdf(&reordered, 2).unwrap();
            let preview = renderer
                .render(
                    &reordered,
                    0,
                    stack_core::RenderSize::new(600, 800).unwrap(),
                    stack_core::Rotation::None,
                )
                .unwrap();
            assert!(preview.image.width() > preview.image.height());
            assert!(preview.image.get_pixel(35, 35)[2] > 180);
            driver.send(Message::Editor(crate::editor::Action::RemovePage));
            driver.send(Message::Editor(crate::editor::Action::ConfirmRemovePage));
            driver.settle().await;
            driver.send(Message::Editor(crate::editor::Action::Rotate));
            driver.settle().await;
            let subset = directory.path().join("subset.pdf");
            driver.pick(pick(&driver.app), vec![subset.clone()]).await;
            renderer.inspect_generated_pdf(&subset, 1).unwrap();
            assert_eq!(driver.app.selected.as_ref().unwrap().pages.len(), 1);
            if let Some(protocol) = std::env::var_os("STACK_M2_PORTAL_PROTOCOL") {
                exercise_portal_protocol(&mut driver, &fixtures, Path::new(&protocol)).await;
            }
            drop(driver.app.renderer.take());
            drop(driver.app.transfer_worker.take());
            drop(driver.app.repository.take());
            let workflow_root = directory.path().join("workflow-library");
            let workflow_output = directory.path().join("workflow");
            std::fs::create_dir(&workflow_output).unwrap();
            let bytes: Vec<_> = stack_render::fixtures::NAMES
                .iter()
                .map(|name| std::fs::read(fixtures.join(name)).unwrap())
                .collect();
            stack_archive::synthetic::create(
                &workflow_output.join("reference"),
                [&bytes[0], &bytes[1], &bytes[2], &bytes[3]],
            )
            .unwrap();
            {
                let mut library = stack_core::library::Library::open(&workflow_root).unwrap();
                let control = stack_archive::Control::default();
                let staged = library
                    .stage_archive(
                        &mut std::fs::File::open(
                            workflow_output.join("reference/format-3.stackbackup"),
                        )
                        .unwrap(),
                        &renderer,
                        &control,
                    )
                    .unwrap();
                let plan = library.review_archive(&staged).unwrap();
                library.commit_archive(&plan, &control).unwrap();
            }
            let mut workflow = Driver::new(workflow_root.clone());
            workflow.app.flags.browser_case = "workflow".into();
            workflow.app.flags.browser_fixtures = Some(fixtures.clone());
            workflow.app.flags.smoke = Some(workflow_output.clone());
            workflow.hold_workflow_close = true;
            workflow.settle().await;
            assert_eq!(workflow.simulated_captures, 4);
            assert!(
                workflow
                    .app
                    .flags
                    .completed
                    .load(std::sync::atomic::Ordering::SeqCst)
            );
            assert_eq!(
                std::fs::read(workflow_output.join("original.pdf")).unwrap(),
                bytes[0]
            );
            renderer
                .inspect_generated_pdf(&workflow_output.join("saved.pdf"), 1)
                .unwrap();
            assert!(workflow_output.join("library.stackbackup").is_file());
            workflow.send(Message::Close);
            workflow.settle().await;
            let saved = stack_core::library::Library::open(&workflow_root).unwrap();
            assert_eq!(saved.status().unwrap().documents, 9);
            let selected = saved
                .document_view(&stack_archive::synthetic::id(1))
                .unwrap();
            assert_eq!(selected.title, crate::editor_smoke::TITLE);
            assert_eq!(selected.notes, crate::editor_smoke::NOTES);
            assert_eq!(
                selected.memberships,
                vec!["custom-first", "custom-last", "receipts"]
            );
            assert_eq!(selected.pages.len(), 1);
            assert_eq!(selected.pages[0].source_page, 1);
            assert_eq!(selected.pages[0].rotation, 90);
            assert_eq!(
                selected
                    .details
                    .iter()
                    .find(|d| d["field_id"] == "total")
                    .unwrap()["value"],
                "-00024.500100"
            );
            assert_eq!(
                std::fs::read_dir(workflow_root.join("operations"))
                    .unwrap()
                    .count(),
                0
            );
            driver.send(Message::Close);
            driver.settle().await;
            let library = stack_core::library::Library::open(&root).unwrap();
            assert_eq!(library.document_view(&id).unwrap().pages.len(), 1);
            assert_eq!(
                std::fs::read_dir(root.join("operations")).unwrap().count(),
                0
            );
            if std::env::var_os("STACK_M2_PRESERVE_WORKFLOW").as_deref()
                == Some(std::ffi::OsStr::new("1"))
            {
                drop(saved);
                drop(library);
                eprintln!(
                    "M2_HEADLESS_WORKFLOW_ARTIFACTS={}",
                    directory.keep().display()
                );
            }
        });
}

#[test]
#[ignore = "maximum-count application benchmark; explicitly run for M2 closeout"]
fn application_maximum_counts_import_search_and_worker_responsiveness() {
    use serde_json::json;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::time::{Duration, Instant};
    let _serial = CONTROLLERS.lock().unwrap();
    let directory = tempfile::tempdir().unwrap();
    let fixtures = directory.path().join("fixtures");
    stack_render::fixtures::create(&fixtures).unwrap();
    let original = std::fs::read(fixtures.join("oriented.jpg")).unwrap();
    let template = stack_archive::synthetic::create(
        &directory.path().join("template"),
        [b"pdf", b"jpeg", b"png", b"webp"],
    )
    .unwrap()
    .into_json();
    let mut graph = stack_archive::Manifest::empty().into_json();
    graph["stacks"] = template["stacks"].clone();
    let sources = directory.path().join("sources");
    std::fs::create_dir(&sources).unwrap();
    for n in 0..1000_u128 {
        let id = stack_archive::synthetic::id(10_000 + n);
        let mut doc = template["documents"][2].clone();
        doc["id"] = json!(id);
        doc["title"] = json!(format!("Synthetic count {n:04}"));
        doc["page_count"] = json!(10);
        doc["initial_page_count"] = json!(10);
        graph["documents"].as_array_mut().unwrap().push(doc);
        let mut suggestion = template["suggestions"][2].clone();
        suggestion["document_id"] = json!(id);
        graph["suggestions"]
            .as_array_mut()
            .unwrap()
            .push(suggestion);
        for position in 0..10 {
            let mut page = template["pages"][4].clone();
            page["id"] = json!(stack_archive::synthetic::id(100_000 + n * 10 + position));
            page["document_id"] = json!(id);
            page["position"] = json!(position);
            page["ocr_text"] = json!(if position == 9 {
                "maxcountlatersentinel"
            } else {
                "initial authored page"
            });
            graph["pages"].as_array_mut().unwrap().push(page);
        }
        graph["decision_evidence"][&id] =
            json!(stack_archive::validation::current_evidence(&graph, &id));
        let path = format!("originals/{id}/source");
        std::fs::create_dir_all(sources.join("originals").join(&id)).unwrap();
        std::fs::write(sources.join(&path), &original).unwrap();
        graph["files"].as_array_mut().unwrap().push(
            json!({"path":path,"length":original.len(),"sha256":stack_archive::digest(&original)}),
        );
    }
    let manifest = stack_archive::Manifest::from_value(graph).unwrap();
    let archive = directory.path().join("maximum.stackbackup");
    stack_archive::write(
        &manifest,
        &sources,
        &mut std::fs::File::create(&archive).unwrap(),
        &stack_archive::Control::default(),
    )
    .unwrap();
    let mut measurements = json!({"scope":"headless application handlers/workers; injected picker; no native layout or physical input claim","documents":1000,"pages":10000,"visible_rows_per_page":crate::RESULTS_PER_PAGE});
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let max_gap = Arc::new(AtomicU64::new(0));
            let gap = max_gap.clone();
            let ticker = tokio::spawn(async move {
                let mut previous = Instant::now();
                loop {
                    tokio::time::sleep(Duration::from_millis(5)).await;
                    let now = Instant::now();
                    gap.fetch_max(
                        now.duration_since(previous).as_micros() as u64,
                        Ordering::Relaxed,
                    );
                    previous = now;
                }
            });
            let mut driver = Driver::new(directory.path().join("library"));
            let initial = Instant::now();
            driver.settle().await;
            measurements["empty_initial_browse_ms"] = json!(initial.elapsed().as_millis());
            // Cancel an active large transfer after real bytes start moving.
            driver.send(Message::Picked(
                Pick::Archive,
                Ok(Some(vec![archive.clone()])),
            ));
            driver
                .until(|a| {
                    a.transfers
                        .active
                        .as_ref()
                        .is_some_and(|a| a.phase == Phase::Reading)
                })
                .await;
            let control = driver
                .app
                .transfers
                .active
                .as_ref()
                .unwrap()
                .control
                .clone();
            tokio::time::timeout(Duration::from_secs(20), async {
                while control.transferred_bytes() < 65536 {
                    tokio::time::sleep(Duration::from_millis(1)).await;
                }
            })
            .await
            .expect("large transfer starts");
            measurements["cancel_after_processed_bytes"] = json!(control.transferred_bytes());
            let cancel = Instant::now();
            driver.send(Message::Transfer(Action::Cancel));
            measurements["cancel_dispatch_us"] = json!(cancel.elapsed().as_micros());
            assert!(
                control.check().is_err(),
                "cancellation reaches work directly"
            );
            driver.settle().await;
            measurements["cancel_cleanup_ms"] = json!(cancel.elapsed().as_millis());
            assert!(driver.app.hits.is_empty() && driver.app.transfers.active.is_none());
            assert_eq!(
                std::fs::read_dir(
                    driver
                        .app
                        .flags
                        .library
                        .as_ref()
                        .unwrap()
                        .join("operations")
                )
                .unwrap()
                .count(),
                0
            );
            let start = Instant::now();
            driver.pick(Pick::Archive, vec![archive]).await;
            measurements["stage_review_ms"] = json!(start.elapsed().as_millis());
            assert_eq!(
                driver
                    .app
                    .transfers
                    .active
                    .as_ref()
                    .unwrap()
                    .review
                    .as_ref()
                    .unwrap()
                    .documents_added,
                1000
            );
            let start = Instant::now();
            driver.send(Message::Transfer(Action::Commit));
            driver.settle().await;
            measurements["commit_browse_ms"] = json!(start.elapsed().as_millis());
            assert_eq!(driver.app.hits.len(), 1000);
            assert!(
                driver.app.tiles.images.len() < crate::RESULTS_PER_PAGE,
                "offscreen pages decode lazily"
            );
            let before_scroll = driver.app.tiles.images.len();
            let mut scroll_messages = Vec::new();
            {
                let software =
                    iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
                #[cfg(feature = "gpu")]
                let mut renderer = cosmic::Renderer::Secondary(software);
                #[cfg(not(feature = "gpu"))]
                let mut renderer = software;
                let start = Instant::now();
                let view = driver.app.view();
                measurements["profile_view_us"] = json!(start.elapsed().as_micros());
                let mut ui = iced::runtime::UserInterface::build(
                    view,
                    iced::Size::new(1080., 780.),
                    iced::runtime::user_interface::Cache::default(),
                    &mut renderer,
                );
                measurements["profile_build_us"] = json!(start.elapsed().as_micros());
                ui.draw(
                    &mut renderer,
                    &cosmic::Theme::light(),
                    &iced::advanced::renderer::Style::default(),
                    iced::mouse::Cursor::Unavailable,
                );
                measurements["first_results_page_layout_draw_ms"] =
                    json!(start.elapsed().as_millis());
                let mut audit = ScrollAudit::default();
                ui.operate(&renderer, &mut audit);
                assert_eq!(audit.0.len(), 1);
                let (bounds, content, _) = audit.0[0];
                assert!(
                    content.height > bounds.height && bounds.height > 100.,
                    "{audit:?}"
                );
                let start = Instant::now();
                let (_, status) = ui.update(
                    &[iced::Event::Mouse(iced::mouse::Event::WheelScrolled {
                        delta: iced::mouse::ScrollDelta::Lines { x: 0., y: -30. },
                    })],
                    iced::mouse::Cursor::Available(bounds.center()),
                    &mut renderer,
                    &mut iced::advanced::clipboard::Null,
                    &mut scroll_messages,
                );
                assert!(status.contains(&iced::event::Status::Captured), "{audit:?}");
                ui.draw(
                    &mut renderer,
                    &cosmic::Theme::light(),
                    &iced::advanced::renderer::Style::default(),
                    iced::mouse::Cursor::Unavailable,
                );
                measurements["results_page_scroll_draw_ms"] = json!(start.elapsed().as_millis());
            }
            for message in scroll_messages {
                driver.send(message);
            }
            driver.settle().await;
            assert!(
                driver.app.tiles.images.len() > before_scroll,
                "scrolling loads the newly visible thumbnails"
            );
            assert!(driver.app.tiles.images.len() <= crate::RESULTS_PER_PAGE);
            let start = Instant::now();
            for n in 0..100 {
                driver.send(Message::Query(format!("superseded {n}")));
            }
            driver.send(Message::Query("maxcountlatersentinel".into()));
            driver.settle().await;
            measurements["debounced_search_ms"] = json!(start.elapsed().as_millis());
            assert_eq!(driver.app.hits.len(), 1000);
            assert!(driver.app.hits.iter().all(|h| {
                h.page_id
                    .as_ref()
                    .is_some_and(|p| uuid::Uuid::parse_str(p).unwrap().as_u128() % 10 == 9)
            }));
            driver.send(Message::ResultsPage(1000));
            driver.settle().await;
            assert_eq!(driver.app.browse_offset, 950);
            let last = driver.app.hits.last().unwrap().document_id.clone();
            driver.send(Message::OpenDocument(last));
            driver.settle().await;
            assert_eq!(
                driver.app.page, 9,
                "last result page still opens its stable matching page"
            );
            measurements["executor_max_tick_gap_us"] = json!(max_gap.load(Ordering::Relaxed));
            ticker.abort();
            driver.send(Message::Close);
            driver.settle().await;
        });
    let status = std::fs::read_to_string("/proc/self/status").unwrap();
    measurements["process_peak_rss_kib"] = json!(
        status
            .lines()
            .find(|line| line.starts_with("VmHWM:"))
            .unwrap()
            .split_whitespace()
            .nth(1)
            .unwrap()
            .parse::<u64>()
            .unwrap()
    );
    eprintln!("M2_APPLICATION_COUNTS={measurements}");
}

#[test]
fn detail_drafts_quit_choices_and_saved_palette_overrides_are_headless() {
    // Shutdown has one terminal PDFium lifecycle; isolate it from other drivers.
    if std::env::var_os("STACK_DRAFT_CLOSEOUT_FRESH_PROCESS").is_none() {
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "controller_tests::detail_drafts_quit_choices_and_saved_palette_overrides_are_headless", "--nocapture"])
            .env("STACK_DRAFT_CLOSEOUT_FRESH_PROCESS", "1").output().unwrap();
        assert!(
            output.status.success(),
            "{}\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        return;
    }
    use crate::{catalog::Action as Catalog, editor::Action as Edit};
    use iced::advanced::widget::operation::focusable;
    use serde_json::json;
    assert!(std::env::var_os("DISPLAY").is_none());
    assert!(std::env::var_os("WAYLAND_DISPLAY").is_none());
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap().block_on(async {
        let root = tempfile::tempdir().unwrap();
        let mut driver = Driver::new(root.path().join("library"));
        driver.settle().await;
        driver.send(Message::DiscardAndQuit);
        assert!(!driver.app.closing, "discard requires a visible quit confirmation");
        driver.send(Message::Catalog(Catalog::New));
        driver.send(Message::Catalog(Catalog::Name("Authored bills".into())));
        driver.send(Message::Catalog(Catalog::SaveName));
        driver.settle().await;
        assert!(!driver.app.catalog.has_unsaved_changes());
        let id = driver.app.stacks.iter().find(|s| s.name == "Authored bills").unwrap().id.clone();
        driver.send(Message::Catalog(Catalog::Select(id.clone())));
        driver.send(Message::Catalog(Catalog::Name("Unfinished name".into())));
        driver.send(Message::Catalog(Catalog::Color("123456".into())));
        driver.send(Message::Catalog(Catalog::SaveColor));
        driver.settle().await;
        let stack = driver.app.stacks.iter().find(|s| s.id == id).unwrap();
        assert_eq!(stack.name, "Authored bills");
        assert_eq!(crate::catalog::accent(stack), 0xff123456);
        assert!(driver.app.catalog.has_unsaved_changes(), "saving color must retain an unsaved name");
        driver.send(Message::Close);
        assert!(driver.app.confirm_quit && !driver.app.closing);
        drop(driver.app.on_escape());
        assert!(!driver.app.confirm_quit);
        assert_eq!(driver.app.catalog.name, "Unfinished name");
        driver.send(Message::Catalog(Catalog::SaveName));
        driver.settle().await;
        assert!(!driver.app.catalog.has_unsaved_changes());
        driver.send(Message::Catalog(Catalog::Color("not RGB".into())));
        driver.send(Message::Catalog(Catalog::SaveColor));
        assert!(!driver.app.saving);
        assert!(driver.app.save_status.as_ref().unwrap().contains("six-digit"));
        driver.send(Message::Catalog(Catalog::Select(id)));
        let document_id = stack_archive::synthetic::id(123);
        let row = |field: &str| json!({"document_id":document_id,"field_id":field,"kind":"text","label":field,"value":"saved","qualifier":"","origin":"manual","state":"saved","evidence":"{}","revision":1});
        driver.app.selected = Some(stack_core::DocumentView {
            token: stack_core::library::DocumentToken { id: document_id.clone(), incarnation: "synthetic".into(), revision: 1 },
            title: "Synthetic document".into(), notes: "".into(), pages: vec![], searchable_pages: 0,
            memberships: vec![], details: vec![row("first"), row("second")], detail_count: 2, detail_offset: 0,
        });
        driver.send(Message::Editor(Edit::Open));
        driver.send(Message::Editor(Edit::Panel(crate::editor::Panel::Details)));
        driver.send(Message::Editor(Edit::SelectDetail("first".into())));
        driver.send(Message::Editor(Edit::Value("-00024.500100 🦀".into())));
        driver.send(Message::Editor(Edit::SelectDetail("second".into())));
        driver.send(Message::Editor(Edit::Label("Unfinished second".into())));
        driver.send(Message::Editor(Edit::SelectDetail("first".into())));
        assert_eq!(driver.app.drafts[&document_id].detail["value"], "-00024.500100 🦀");
        driver.send(Message::Editor(Edit::NewDetail));
        driver.send(Message::Editor(Edit::Label("New unfinished".into())));
        driver.send(Message::Editor(Edit::Close));
        driver.app.selected = None;
        // Closing from another view still protects every process-local draft.
        driver.send(Message::Close);
        assert!(driver.app.confirm_quit && !driver.app.closing);
        for theme in [cosmic::Theme::light(), cosmic::Theme::dark()] {
            for size in [iced::Size::new(480., 680.), iced::Size::new(1080., 780.)] {
                let software = iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
                #[cfg(feature = "gpu")]
                let mut renderer = cosmic::Renderer::Secondary(software);
                #[cfg(not(feature = "gpu"))]
                let mut renderer = software;
                let mut ui = iced::runtime::UserInterface::build(driver.app.dialog().unwrap(), size, iced::runtime::user_interface::Cache::default(), &mut renderer);
                ui.draw(&mut renderer, &theme, &iced::advanced::renderer::Style::default(), iced::mouse::Cursor::Unavailable);
                ui.operate(&renderer, &mut focusable::focus::<()>(iced::widget::Id::new("stack-keep-editing")));
                let mut audit = FocusAudit::default();
                ui.operate(&renderer, &mut audit);
                assert_eq!(audit.focused, vec![Some(iced::widget::Id::new("stack-keep-editing"))]);
                let nodes: Vec<_> = ui.a11y_nodes(iced::mouse::Cursor::Unavailable).into();
                for label in ["Keep editing", "Discard and quit"] {
                    let bounds = nodes.iter().find(|(_, n)| n.label() == Some(label)).unwrap().1.bounds().unwrap();
                    assert!(bounds.x0 >= 0. && bounds.x1 <= f64::from(size.width) && bounds.y0 >= 0. && bounds.y1 <= f64::from(size.height), "{label}: {bounds:?}");
                }
                drop(ui);
                let mut palette = iced::runtime::UserInterface::build(driver.app.catalog_view(), size, iced::runtime::user_interface::Cache::default(), &mut renderer);
                palette.operate(&renderer, &mut iced::advanced::widget::operation::scrollable::scroll_to::<()>(iced::widget::Id::new("stack-catalog"), iced::advanced::widget::operation::scrollable::AbsoluteOffset { x:Some(0.), y:Some(280.) }));
                palette.draw(&mut renderer, &theme, &iced::advanced::renderer::Style::default(), iced::mouse::Cursor::Unavailable);
                let nodes: Vec<_> = palette.a11y_nodes(iced::mouse::Cursor::Unavailable).into();
                assert!(nodes.iter().any(|(_, n)| n.label() == Some("Purple")), "native palette choices expose labels {size:?}: {:?}",nodes.iter().filter_map(|(_,n)|n.label()).collect::<Vec<_>>());
            }
        }
        driver.send(Message::KeepEditing);
        assert!(!driver.app.confirm_quit && !driver.app.closing);
        driver.app.selected = Some(stack_core::DocumentView {
            token: stack_core::library::DocumentToken { id: document_id.clone(), incarnation: "synthetic".into(), revision: 1 },
            title: "Synthetic document".into(), notes: "".into(), pages: vec![], searchable_pages: 0,
            memberships: vec![], details: vec![row("first"), row("second")], detail_count: 2, detail_offset: 0,
        });
        driver.send(Message::Editor(Edit::Open));
        driver.send(Message::Editor(Edit::SelectDetail("second".into())));
        assert_eq!(driver.app.drafts[&document_id].detail["label"], "Unfinished second");
        driver.send(Message::Close);
        driver.send(Message::DiscardAndQuit);
        assert!(driver.app.closing && driver.app.drafts.is_empty());
        driver.settle().await;
        // Drafts are deliberately not restart recovery or implicit saves.
        let saved = stack_core::library::Library::open(&root.path().join("library")).unwrap();
        assert_eq!(saved.status().unwrap().documents, 0);
    });
}

#[test]
fn native_accessibility_tree_publishes_wrapped_children_and_routes_only_target() {
    use iced_accessibility::accesskit::{Action, ActionRequest, NodeId, TreeId};
    let _serial = CONTROLLERS.lock().unwrap();
    let software = iced_tiny_skia::Renderer::new(cosmic::font::default(), iced::Pixels(14.));
    #[cfg(feature = "gpu")]
    let mut renderer = cosmic::Renderer::Secondary(software);
    #[cfg(not(feature = "gpu"))]
    let mut renderer = software;
    let content = cosmic::widget::row::with_capacity(2)
        .push(
            cosmic::widget::button::text("Left action")
                .name("Left action")
                .id("a11y-left".into())
                .on_press(1_u8),
        )
        .push(
            cosmic::widget::button::text("Right action")
                .name("Right action")
                .id("a11y-right".into())
                .on_press(2_u8),
        )
        .wrap();
    let mut ui = iced::runtime::UserInterface::build(
        content,
        iced::Size::new(150., 180.),
        iced::runtime::user_interface::Cache::default(),
        &mut renderer,
    );
    ui.draw(
        &mut renderer,
        &cosmic::Theme::light(),
        &iced::advanced::renderer::Style::default(),
        iced::mouse::Cursor::Unavailable,
    );
    let tree = iced_winit::a11y::tree_update(
        NodeId(u64::MAX),
        "Authored native tree",
        ui.a11y_nodes(iced::mouse::Cursor::Unavailable),
        None,
    );
    assert!(
        tree.nodes
            .iter()
            .any(|(_, n)| n.label() == Some("Left action"))
    );
    let target = tree
        .nodes
        .iter()
        .find(|(_, n)| n.label() == Some("Right action"))
        .unwrap()
        .0;
    let root = tree.nodes.iter().find(|(id, _)| id.0 == u64::MAX).unwrap();
    assert!(root.1.children().len() >= 2);
    let request = ActionRequest {
        action: Action::Click,
        target_tree: TreeId::ROOT,
        target_node: target,
        data: None,
    };
    let events = iced_winit::a11y::route_action(&mut ui, &renderer, &request);
    let mut messages: Vec<u8> = Vec::new();
    ui.update(
        &events,
        iced::mouse::Cursor::Unavailable,
        &mut renderer,
        &mut iced::advanced::clipboard::Null,
        &mut messages,
    );
    assert_eq!(
        messages,
        vec![2],
        "accessibility activation must not invoke a sibling"
    );
    let unknown = ActionRequest {
        action: Action::Click,
        target_tree: TreeId::ROOT,
        target_node: NodeId(u64::MAX - 1),
        data: None,
    };
    assert!(iced_winit::a11y::route_action(&mut ui, &renderer, &unknown).is_empty());
}

#[test]
#[ignore = "requires pinned offline PDF/OCR runtimes"]
fn ordinary_desktop_import_reads_text_offline_and_preserves_typed_draft() {
    if std::env::var_os("STACK_TEXT_UI_FRESH_PROCESS").is_none() {
        let output=std::process::Command::new(std::env::current_exe().unwrap()).args(["--exact","controller_tests::ordinary_desktop_import_reads_text_offline_and_preserves_typed_draft","--include-ignored","--nocapture"]).env("STACK_TEXT_UI_FRESH_PROCESS","1").output().unwrap();
        assert!(
            output.status.success(),
            "{}\n{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        return;
    }
    use crate::editor::Action as Edit;
    let _serial = CONTROLLERS.lock().unwrap();
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let root = tempfile::tempdir().unwrap();
            let input = root.path().join("readable.pdf");
            stack_render::fixtures::readable_pdf(&input).unwrap();
            let bytes = std::fs::read(&input).unwrap();
            let mut driver = Driver::new(root.path().join("library"));
            driver.settle().await;
            driver.pick(Pick::Documents, vec![input.clone()]).await;
            let id = driver.app.hits[0].document_id.clone();
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            driver.send(Message::Editor(Edit::Open));
            driver.send(Message::Editor(Edit::Title(
                "Exact unsaved 00024.500 🦀".into(),
            )));
            for _ in 0..3 {
                driver.send(Message::Text(crate::text_ui::Event::Tick));
                driver.settle().await;
            }
            assert!(
                driver
                    .app
                    .text
                    .statuses
                    .iter()
                    .all(|s| matches!(s.state.as_str(), "ready" | "no_text"))
            );
            assert_eq!(driver.app.drafts[&id].title, "Exact unsaved 00024.500 🦀");
            assert_eq!(driver.app.selected.as_ref().unwrap().title, "readable");
            assert_eq!(
                driver.app.drafts[&id].token,
                driver.app.selected.as_ref().unwrap().token,
                "derived-text completion rebases only its exact input revision"
            );
            driver.send(Message::Editor(Edit::SaveTitle));
            driver.settle().await;
            assert_eq!(
                driver.app.selected.as_ref().unwrap().title,
                "Exact unsaved 00024.500 🦀"
            );
            driver.send(Message::Query("LATERPHRASE".into()));
            driver.settle().await;
            assert_eq!(driver.app.hits.len(), 1);
            assert!(driver.app.hits[0].page_id.is_some());
            driver.send(Message::OpenDocument(id.clone()));
            driver.settle().await;
            assert_eq!(driver.app.page, 1);
            driver.send(Message::Close);
            driver.settle().await;
            let library = stack_core::library::Library::open(&root.path().join("library")).unwrap();
            assert_eq!(
                library
                    .search("LATERPHRASE", None, stack_core::Sort::Newest, 10)
                    .unwrap()
                    .len(),
                1
            );
            assert_eq!(std::fs::read(input).unwrap(), bytes);
        });
}
