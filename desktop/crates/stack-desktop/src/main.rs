mod catalog;
mod catalog_smoke;
#[cfg(test)]
mod controller_tests;
mod editor;
mod editor_smoke;
mod frame;
mod launcher;
mod navigation;
mod operations;
mod portal_smoke;
mod preferences;
mod preview_cache;
mod repository;
mod sync_ui;
mod text_ui;
mod thumbnails;
mod transfers_ui;
mod visual_review;
mod worker;
mod workflow_smoke;
mod workspace;
use cosmic::prelude::*;
use cosmic::{Core, Element, app, executor, iced, widget};
use repository::{Request, Response};
use stack_core::{DocumentView, Rotation, SearchHit, Sort};
use stack_render::{Preview, fixtures};
use std::path::PathBuf;
use std::sync::{
    Arc,
    atomic::{AtomicBool, AtomicU64, Ordering},
};
use std::time::Duration;
use worker::Worker;
const RESULTS_PER_PAGE: usize = 50;

#[derive(Clone)]
struct Flags {
    visual_review: Option<PathBuf>,
    demo: Option<PathBuf>,
    library: Option<PathBuf>,
    preferences: Option<PathBuf>,
    browser_fixtures: Option<PathBuf>,
    browser_case: String,
    expected: Option<stack_archive::Manifest>,
    smoke_expected: Option<PathBuf>,
    smoke: Option<PathBuf>,
    completed: Arc<AtomicBool>,
    frames: Arc<AtomicU64>,
    smoke_title: Option<String>,
    ui_scale: f32,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut flags = Flags {
        visual_review: None,
        demo: None,
        library: None,
        preferences: None,
        browser_fixtures: None,
        browser_case: "saved".into(),
        expected: None,
        smoke_expected: None,
        smoke: None,
        completed: Arc::default(),
        frames: Arc::default(),
        smoke_title: None,
        ui_scale: std::env::var("COSMIC_SCALE")
            .ok()
            .and_then(|v| v.parse::<f32>().ok())
            .filter(|v| (1.0..=2.0).contains(v))
            .unwrap_or(1.0),
    };
    let mut requested_size = iced::Size::new(1080.0, 780.0);
    let mut theme = cosmic::theme::system_preference();
    let mut args = std::env::args().skip(1);
    while let Some(arg) = args.next() {
        let value = args.next().ok_or("Each option requires a value")?;
        match arg.as_str() {
            "--visual-review" => flags.visual_review = Some(value.into()),
            "--browser-smoke-case" => {
                if !["saved", "empty", "busy", "error", "catalog", "catalog-reopen", "editor", "editor-reopen", "workflow", "workflow-reopen", "interactive", "portal"].contains(&value.as_str()) {
                    return Err("Unknown synthetic browser case".into());
                }
                flags.browser_case = value;
            },
            "--browser-smoke-expected" => flags.smoke_expected = Some(value.into()),
            "--browser-smoke-fixtures" => flags.browser_fixtures = Some(value.into()),
            "--library" => {
                let root = PathBuf::from(value);
                if !root.is_absolute() { return Err("Library root must be absolute".into()); }
                flags.library = Some(root);
            },
            "--demo" => flags.demo = Some(value.into()),
            "--smoke-output" => flags.smoke = Some(value.into()),
            "--theme" => theme = match value.as_str() {
                "light" => cosmic::Theme::light(), "dark" => cosmic::Theme::dark(),
                _ => return Err("Theme must be light or dark".into()),
            },
            "--size" => {
                let (width, height) = value.split_once('x').ok_or("Size must be WIDTHxHEIGHT")?;
                let width: u16 = width.parse()?;
                let height: u16 = height.parse()?;
                if !(480..=3840).contains(&width) || !(360..=2160).contains(&height) {
                    return Err("Window dimensions must be 480–3840 by 360–2160".into());
                }
                requested_size = iced::Size::new(f32::from(width), f32::from(height));
            }
            "--text-scale" => {
                let scale: f32 = value.parse()?;
                if !(1.0..=2.0).contains(&scale) { return Err("UI scale must be 1–2".into()); }
                flags.ui_scale = scale;
            }
            _ => return Err("Usage: stack-desktop [--library ABSOLUTE_ROOT | --demo FIXTURE_DIR] [--smoke-output NEW_DIR] [--theme light|dark] [--size WIDTHxHEIGHT] [--text-scale 1|2]".into()),
        }
    }
    if let Some(root) = &flags.visual_review {
        if flags.smoke.is_none() {
            return Err("Visual review requires --smoke-output NEW_DIRECTORY".into());
        }
        if flags.demo.is_some() || flags.library.is_some() {
            return Err("Visual review chooses its isolated library".into());
        }
        flags.expected = Some(visual_review::verify(root)?);
        flags.library = Some(root.join("library"));
    }
    if flags.demo.is_some() && flags.library.is_some() {
        return Err("Choose either a saved library or the demo".into());
    }
    if flags.demo.is_none() && flags.library.is_none() {
        flags.library = Some(stack_core::library::default_root()?);
    }
    if flags.library.is_some() {
        flags.preferences = Some(preferences::path()?);
    }
    if let Some(directory) = &flags.demo {
        fixtures::verify(directory)?;
    }
    if let Some(directory) = &flags.smoke {
        if flags.demo.is_none() && flags.browser_fixtures.is_none() && flags.visual_review.is_none()
        {
            return Err("Smoke capture requires verified synthetic fixtures".into());
        }
        std::fs::create_dir(directory)?;
        if let Some(fixtures) = &flags.browser_fixtures {
            if flags.demo.is_some()
                || !flags
                    .library
                    .as_ref()
                    .is_some_and(|p| p.starts_with("/tmp"))
            {
                return Err("Browser smoke requires an explicit disposable /tmp library".into());
            }
            stack_render::fixtures::verify(fixtures)?;
            let bytes = stack_render::fixtures::NAMES
                .map(|name| std::fs::read(fixtures.join(name)))
                .into_iter()
                .collect::<Result<Vec<_>, _>>()?;
            let expected = stack_archive::synthetic::create(
                &directory.join("reference"),
                [&bytes[0], &bytes[1], &bytes[2], &bytes[3]],
            )?;
            if [
                "saved",
                "catalog",
                "catalog-reopen",
                "editor",
                "editor-reopen",
                "workflow",
                "workflow-reopen",
                "interactive",
                "portal",
            ]
            .contains(&flags.browser_case.as_str())
            {
                if flags.browser_case == "workflow-reopen" {
                    use std::io::Read;
                    let path = flags.smoke_expected.as_ref().ok_or(
                        "Workflow reopen needs its previously verified synthetic manifest",
                    )?;
                    if !path.starts_with("/tmp") {
                        return Err("Synthetic expected manifest must be under /tmp".into());
                    }
                    let mut bytes = Vec::new();
                    stack_archive::read_regular(path)?
                        .take(32 * 1024 * 1024 + 1)
                        .read_to_end(&mut bytes)?;
                    if bytes.len() > 32 * 1024 * 1024 {
                        return Err("Expected manifest exceeds bounds".into());
                    }
                    let expected =
                        stack_archive::Manifest::from_value(serde_json::from_slice(&bytes)?)?;
                    let authored: Vec<_> = stack_render::fixtures::NAMES
                        .iter()
                        .map(|name| {
                            std::fs::read(fixtures.join(name))
                                .map(|bytes| (bytes.len() as u64, stack_archive::digest(&bytes)))
                        })
                        .collect::<Result<_, _>>()?;
                    if expected.rows("documents").len() != 9
                        || expected.rows("files").iter().any(|f| {
                            !authored.iter().any(|(length, hash)| {
                                f["length"] == *length && f["sha256"] == *hash
                            })
                        })
                    {
                        return Err("Workflow reopen requires its nine authored documents and original hashes".into());
                    }
                    flags.expected = Some(expected);
                } else if flags.browser_case == "editor-reopen" {
                    flags.expected = Some(editor_smoke::expected(expected.into_json())?);
                } else if flags.browser_case == "catalog-reopen" {
                    let mut graph = expected.into_json();
                    graph["stacks"][2]["color"] = serde_json::json!(0xff123456_u32 as i32);
                    graph["stacks"][2]["visible"] = serde_json::json!(0);
                    flags.expected = Some(stack_archive::Manifest::from_value(graph)?);
                } else {
                    flags.expected = Some(expected);
                }
            }
        }
    }
    if flags.smoke_expected.is_some()
        && (flags.browser_case != "workflow-reopen"
            || flags.browser_fixtures.is_none()
            || flags.smoke.is_none())
    {
        return Err("Expected graph is only for a verified synthetic workflow reopen".into());
    }
    if flags.browser_fixtures.is_some() && flags.smoke.is_none() {
        return Err("Browser smoke requires --smoke-output".into());
    }
    if flags.smoke.is_some() {
        flags.smoke_title = Some(format!("Stack synthetic M2 {}", uuid::Uuid::new_v4()));
        eprintln!(
            "Synthetic test window: {}",
            flags.smoke_title.as_deref().expect("nonce")
        );
    }
    let interactive = flags.browser_case == "interactive";
    let completion = flags.completed.clone();
    let smoke = flags.smoke.is_some();
    let result = launcher::run(flags, requested_size, theme, smoke);
    stack_render::shutdown_text_worker()?;
    stack_render::shutdown_pdf_worker()?;
    result?;
    if smoke && !interactive && !completion.load(Ordering::SeqCst) {
        return Err("GUI smoke did not complete".into());
    }
    Ok(())
}

#[derive(Clone, Copy, Debug)]
enum Sample {
    Report,
    Orientation,
}

impl Sample {
    fn filename(self) -> &'static str {
        match self {
            Self::Report => "two-pages.pdf",
            Self::Orientation => "oriented.jpg",
        }
    }
    fn title(self) -> &'static str {
        match self {
            Self::Report => "Sample report",
            Self::Orientation => "Orientation sample",
        }
    }
}

struct Stack {
    core: Core,
    nav: widget::nav_bar::Model,
    flags: Flags,
    sample: Sample,
    page: u32,
    page_count: u32,
    rotation: u16,
    request: u64,
    busy: bool,
    preview: Option<widget::image::Handle>,
    error: Option<String>,
    smoke_step: usize,
    scenario_step: usize,
    repository: Option<Worker<Request, Response>>,
    transfer_worker: Option<Worker<operations::TransferRequest, operations::TransferOutcome>>,
    transfers: transfers_ui::Ui,
    sync: sync_ui::Ui,
    renderer: Option<Worker<RenderJob, Arc<Preview>>>,
    thumbnail_worker: Option<Worker<stack_core::DocumentTile, widget::image::Handle>>,
    tiles: thumbnails::Tiles,
    text: text_ui::Ui,
    text_worker: Option<Worker<stack_core::TextJob, stack_core::TextOutput>>,
    context_title: String,
    closing: bool,
    confirm_quit: bool,
    repository_generation: u64,
    library_loading: bool,
    library_error: Option<String>,
    hits: Vec<SearchHit>,
    browse_offset: usize,
    browse_scroll: f32,
    viewport_height: f32,
    opening_document: Option<String>,
    selected: Option<DocumentView>,
    filter: Option<String>,
    query: String,
    sort: Option<Sort>,
    stacks: Vec<stack_core::StackSummary>,
    catalog_revision: i64,
    catalog: catalog::Editor,
    saving: bool,
    editor_open: bool,
    narrow: bool,
    library_menu_open: bool,
    grid_view: bool,
    layout_loaded: bool,
    information_visible: bool,
    drafts: std::collections::BTreeMap<String, editor::Draft>,
    save_status: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct RenderJob {
    source: PathBuf,
    page: u32,
    rotation: Rotation,
    saved: Option<stack_core::SavedPage>,
}

#[derive(Debug, Clone)]
enum Message {
    Close,
    Text(text_ui::Event),
    KeepEditing,
    DiscardAndQuit,
    Shutdown(Result<(), String>),
    Select(Sample),
    OpenDocument(String),
    Query(String),
    SearchReady(u64),
    SortByName(bool),
    ResultsPage(i32),
    Catalog(catalog::Action),
    Editor(editor::Action),
    DocumentSaved(String, editor::Saved, Result<Response, String>),
    CatalogSaved(Result<Response, String>),
    Transfer(transfers_ui::Action),
    Sync(sync_ui::Action),
    SyncResult(Result<Response, String>),
    Picked(transfers_ui::Pick, Result<Option<Vec<PathBuf>>, String>),
    TransferRepository(Result<Response, String>),
    Transferred(Result<operations::TransferOutcome, String>),
    TransferTick(String),
    Repository(u64, Result<Response, String>),
    RefreshBrowser,
    LibraryMenu(bool),
    GridView(bool),
    LayoutSaved(Result<Response, String>),
    Information(bool),
    Back,
    BrowserScrolled(f32),
    Previous,
    Next,
    Rotate,
    Retry,
    Rendered(u64, Result<Arc<Preview>, String>),
    Tiles(u64, Result<Response, String>),
    Thumbnail(u64, String, Result<widget::image::Handle, String>),
    Capture,
    Captured(iced::window::Screenshot),
    CaptureSaved(Result<(), String>),
}

impl cosmic::Application for Stack {
    type Executor = executor::Default;
    type Flags = Flags;
    type Message = Message;
    const APP_ID: &'static str = stack_core::APP_ID;
    fn core(&self) -> &Core {
        &self.core
    }
    fn core_mut(&mut self) -> &mut Core {
        &mut self.core
    }

    fn init(core: Core, flags: Flags) -> (Self, app::Task<Message>) {
        let mut nav = widget::nav_bar::Model::default();
        nav.insert()
            .text("All documents")
            .data(navigation::Location::Documents);
        if flags.demo.is_some() {
            nav.insert().text("Bills");
            nav.insert().text("Receipts");
        }
        nav.activate_position(0);
        let operation_store: operations::Store = Arc::default();
        let transfer_worker = Some(operations::start(operation_store.clone()));
        let repository = flags.library.clone().map(|root| {
            repository::start_operations(
                root,
                flags.expected.clone(),
                flags.preferences.clone(),
                operation_store,
            )
        });
        let renderer = preview_cache::start();
        let mut app = Self {
            core,
            nav,
            flags,
            sample: Sample::Report,
            page: 0,
            page_count: 0,
            rotation: 0,
            request: 0,
            busy: false,
            preview: None,
            error: None,
            smoke_step: 0,
            scenario_step: 0,
            repository,
            transfer_worker,
            transfers: Default::default(),
            sync: Default::default(),
            renderer: Some(renderer),
            thumbnail_worker: Some(thumbnails::start()),
            tiles: thumbnails::Tiles::default(),
            text: text_ui::Ui::default(),
            text_worker: Some(text_ui::worker()),
            context_title: String::new(),
            closing: false,
            confirm_quit: false,
            repository_generation: 0,
            library_loading: false,
            library_error: None,
            hits: Vec::new(),
            browse_offset: 0,
            browse_scroll: 0.0,
            viewport_height: 780.,
            opening_document: None,
            selected: None,
            filter: None,
            query: String::new(),
            sort: None,
            stacks: Vec::new(),
            catalog_revision: 0,
            catalog: catalog::Editor::default(),
            saving: false,
            editor_open: false,
            narrow: false,
            library_menu_open: false,
            grid_view: true,
            layout_loaded: false,
            information_visible: false,
            drafts: Default::default(),
            save_status: None,
        };
        app.set_header_title("Stack — All documents".to_owned());
        let window_title = app
            .flags
            .smoke_title
            .clone()
            .unwrap_or_else(|| "Stack".into());
        let title = app
            .core
            .main_window_id()
            .map(|id| app.set_window_title(window_title, id))
            .unwrap_or_else(app::Task::none);
        let render = if app.flags.demo.is_some() {
            app.render()
        } else {
            app.browse()
        };
        (app, app::Task::batch([title, render]))
    }

    fn subscription(&self) -> iced::Subscription<Message> {
        iced::Subscription::batch([
            transfers_ui::progress_subscription(&self.transfers),
            text_ui::subscription(self),
        ])
    }

    fn nav_model(&self) -> Option<&widget::nav_bar::Model> {
        Some(&self.nav)
    }
    fn dialog(&self) -> Option<Element<'_, Message>> {
        self.confirm_quit.then(|| widget::dialog()
            .title("Discard unsaved changes and quit?")
            .body("Unfinished document and stack edits are kept only while this app is open. Keep editing to save them individually. Discard and quit loses those drafts; accepted saves finish before closing.")
            .primary_action(self.button("Keep editing").id("stack-keep-editing".into()).class(cosmic::theme::Button::Suggested).on_press(Message::KeepEditing))
            .secondary_action(self.button("Discard and quit").id("stack-discard-quit".into()).class(cosmic::theme::Button::Destructive).on_press(Message::DiscardAndQuit))
            .into())
    }
    fn on_app_exit(&mut self) -> Option<Message> {
        (!self.closing).then_some(Message::Close)
    }
    fn on_close_requested(&self, _id: iced::window::Id) -> Option<Message> {
        Some(Message::Close)
    }
    fn header_start(&self) -> Vec<Element<'_, Message>> {
        if self.can_go_back() {
            vec![self.button("Back").on_press(Message::Back).into()]
        } else {
            Vec::new()
        }
    }
    fn nav_bar(&self) -> Option<Element<'_, cosmic::Action<Message>>> {
        self.library_sidebar()
    }
    fn footer(&self) -> Option<Element<'_, Message>> {
        self.library_footer()
    }
    fn on_search(&mut self) -> app::Task<Message> {
        self.catalog.open = false;
        self.sync.open = false;
        self.rebuild_navigation();
        let browse = self.browse();
        app::Task::batch([browse, iced::widget::operation::focus("stack-search")])
    }
    fn on_escape(&mut self) -> app::Task<Message> {
        if self.confirm_quit {
            self.confirm_quit = false;
            return app::Task::none();
        }
        if self.library_menu_open {
            self.library_menu_open = false;
            return app::Task::none();
        }
        if self.editor_open {
            if let Some(draft) = self
                .selected
                .as_ref()
                .and_then(|doc| self.drafts.get_mut(&doc.token.id))
                && (draft.confirm_delete || draft.confirm_remove)
            {
                draft.confirm_delete = false;
                draft.confirm_remove = false;
            } else {
                self.editor_open = false;
            }
        } else {
            return self.go_back();
        }
        app::Task::none()
    }
    fn on_nav_select(&mut self, id: widget::nav_bar::Id) -> app::Task<Message> {
        if self.saving {
            return app::Task::none();
        }
        if self.narrow {
            self.core.nav_bar_set_toggled(false);
        }
        self.library_menu_open = false;
        self.nav.activate(id);
        if self.repository.is_some() {
            let location = self
                .nav
                .data::<navigation::Location>(id)
                .cloned()
                .unwrap_or(navigation::Location::Documents);
            if location == navigation::Location::ManageStacks {
                self.sync.open = false;
                return self.catalog_action(catalog::Action::Open);
            }
            if location == navigation::Location::Sync {
                return self.sync_action(sync_ui::Action::Open);
            }
            self.sync.open = false;
            self.catalog.open = false;
            self.browse_offset = 0;
            self.browse_scroll = 0.;
            self.filter = match location {
                navigation::Location::Stack(id) => Some(id),
                _ => None,
            };
            self.rebuild_navigation();
            return self.browse();
        }
        app::Task::none()
    }

    fn on_window_resize(&mut self, _id: iced::window::Id, width: f32, height: f32) {
        self.viewport_height = height / self.core.scale_factor();
        let narrow = width / self.core.scale_factor() <= 800.0;
        if narrow != self.narrow {
            self.narrow = narrow;
            self.core.nav_bar_set_toggled(!narrow);
        }
    }

    fn update(&mut self, message: Message) -> app::Task<Message> {
        let work = self.update_message(message);
        let context = self
            .selected
            .as_ref()
            .map(|d| d.title.clone())
            .unwrap_or_else(|| {
                if self.catalog.open {
                    "Manage stacks".into()
                } else if self.sync.open {
                    "Sync exchange".into()
                } else {
                    self.library_location()
                }
            });
        let title = format!("Stack — {context}");
        if title != self.context_title {
            self.context_title = title.clone();
            self.set_header_title(title.clone());
            if let Some(id) = self.core.main_window_id() {
                return app::Task::batch([
                    work,
                    self.set_window_title(self.flags.smoke_title.clone().unwrap_or(title), id),
                ]);
            }
        }
        work
    }

    fn view(&self) -> Element<'_, Message> {
        let content = self.view_content();
        if self.flags.smoke.is_some() {
            frame::track(content, self.flags.frames.clone())
        } else {
            content
        }
    }
}

impl Stack {
    fn view_content(&self) -> Element<'_, Message> {
        if self.closing {
            return widget::container(self.body("Closing library…"))
                .padding(24)
                .into();
        }
        if self.repository.is_some() {
            return widget::column::with_capacity(3)
                .push_maybe(
                    (!self.sync.open && self.selected.is_none() && self.opening_document.is_none())
                        .then(|| widget::container(self.search_bar()).padding([12, 20])),
                )
                .push_maybe(
                    (self.transfers.status.is_some() || self.transfers.active.is_some())
                        .then(|| self.transfer_panel()),
                )
                .push(self.library_view())
                .height(iced::Length::Fill)
                .into();
        }
        if self.nav.position(self.nav.active()) != Some(0) {
            return widget::container(
                widget::column::with_capacity(2)
                    .push(widget::text::title2("No documents in this stack"))
                    .push(widget::text::body(
                        "The synthetic samples are in All documents.",
                    ))
                    .spacing(16),
            )
            .padding(24)
            .into();
        }
        let samples = widget::row::with_capacity(2)
            .push(
                self.button("Sample report")
                    .on_press_maybe((!self.busy).then_some(Message::Select(Sample::Report))),
            )
            .push(
                self.button("Orientation sample")
                    .on_press_maybe((!self.busy).then_some(Message::Select(Sample::Orientation))),
            )
            .spacing(8)
            .wrap();
        let controls = widget::row::with_capacity(4)
            .push(
                self.button("Previous")
                    .on_press_maybe((!self.busy && self.page > 0).then_some(Message::Previous)),
            )
            .push(self.body(format!(
                "Page {} of {}",
                self.page + 1,
                self.page_count.max(1)
            )))
            .push(self.button("Next").on_press_maybe(
                (!self.busy && self.page + 1 < self.page_count).then_some(Message::Next),
            ))
            .push(
                self.button("Rotate")
                    .on_press_maybe((!self.busy).then_some(Message::Rotate)),
            )
            .spacing(12)
            .align_y(iced::Alignment::Center)
            .wrap();
        let content: Element<'_, Message> = if self.busy {
            self.body("Loading preview…")
        } else if let Some(error) = &self.error {
            widget::column::with_capacity(2)
                .push(self.body(error.as_str()))
                .push(self.button("Retry").on_press(Message::Retry))
                .spacing(12)
                .into()
        } else if let Some(handle) = &self.preview {
            widget::image::viewer(handle.clone())
                .width(iced::Length::Fill)
                .height(iced::Length::Fill)
                .into()
        } else {
            self.body("Choose a document to preview.")
        };
        widget::container(
            widget::column::with_capacity(5)
                .push(
                    widget::text::heading(self.sample.title())
                        .size(16.0)
                        .line_height(iced::widget::text::LineHeight::Relative(1.5)),
                )
                .push(self.body("Synthetic samples · Preview changes are temporary"))
                .push(samples)
                .push(controls)
                .push(content)
                .spacing(12)
                .height(iced::Length::Fill),
        )
        .padding(20)
        .into()
    }
}

impl Stack {
    fn delayed_capture(&self) -> app::Task<Message> {
        cosmic::task::future(async {
            tokio::time::sleep(Duration::from_millis(900)).await;
            Message::Capture
        })
    }

    fn button(&self, label: &'static str) -> widget::button::TextButton<'static, Message> {
        widget::button::text(label)
            .font_size(14)
            .line_height(20)
            .height(32.0)
    }

    fn body<'a>(&self, label: impl Into<std::borrow::Cow<'a, str>> + 'a) -> Element<'a, Message> {
        widget::text::body(label)
            .size(14.0)
            .line_height(iced::widget::text::LineHeight::Relative(1.5))
            .into()
    }

    fn render(&mut self) -> app::Task<Message> {
        let Some(directory) = &self.flags.demo else {
            return app::Task::none();
        };
        let source = directory.join(self.sample.filename());
        let page = self.page;
        let rotation = Rotation::try_from(self.rotation).expect("quarter-turn rotation");
        self.render_source(source, page, rotation)
    }

    fn render_source(
        &mut self,
        source: PathBuf,
        page: u32,
        rotation: Rotation,
    ) -> app::Task<Message> {
        self.render_job(RenderJob {
            source,
            page,
            rotation,
            saved: None,
        })
    }

    fn render_job(&mut self, job: RenderJob) -> app::Task<Message> {
        self.busy = true;
        self.error = None;
        self.preview = None;
        self.request += 1;
        let token = self.request;
        let Some(renderer) = &self.renderer else {
            return app::Task::none();
        };
        let receive = renderer.submit(job);
        cosmic::task::future(async move {
            Message::Rendered(
                token,
                receive
                    .await
                    .unwrap_or_else(|_| Err("Preview worker stopped".into())),
            )
        })
    }

    fn repository_request(&mut self, request: Request) -> app::Task<Message> {
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        self.repository_generation += 1;
        let generation = self.repository_generation;
        self.library_loading = true;
        self.library_error = None;
        let receive = worker.submit(request);
        cosmic::task::future(async move {
            Message::Repository(
                generation,
                receive
                    .await
                    .unwrap_or_else(|_| Err("Library worker stopped".into())),
            )
        })
    }

    fn invalidate_preview(&mut self) {
        self.request += 1;
        self.preview = None;
        self.error = None;
        self.busy = false;
    }

    fn browse(&mut self) -> app::Task<Message> {
        self.editor_open = false;
        self.invalidate_preview();
        self.selected = None;
        self.opening_document = None;
        self.page = 0;
        self.page_count = 0;
        self.refresh_browser()
    }

    fn refresh_browser(&mut self) -> app::Task<Message> {
        self.repository_request(Request::Browse {
            query: self.query.clone(),
            stack: self.filter.clone(),
            sort: self.sort,
        })
    }

    fn select_document(&mut self, id: String, page: usize) -> app::Task<Message> {
        self.select_matching(id, page, None)
    }

    fn select_matching(
        &mut self,
        id: String,
        page: usize,
        matching_page: Option<String>,
    ) -> app::Task<Message> {
        self.cancel_thumbnails();
        let same_document = self.selected.as_ref().is_some_and(|d| d.token.id == id);
        if !same_document {
            self.selected = None;
            self.invalidate_preview();
        }
        self.opening_document = Some(id.clone());
        self.page = page as u32;
        let detail_offset = self.drafts.get(&id).map(|d| d.detail_offset).unwrap_or(0);
        self.repository_request(Request::Select {
            id,
            page,
            matching_page,
            detail_offset,
        })
    }

    fn catalog_save(&mut self, edit: stack_core::StackEdit) -> app::Task<Message> {
        let Some(worker) = &self.repository else {
            return app::Task::none();
        };
        self.saving = true;
        self.save_status = Some("Saving…".into());
        let receive = worker.submit_required(Request::Catalog {
            revision: self.catalog_revision,
            edit,
        });
        cosmic::task::future(async move {
            Message::CatalogSaved(
                receive
                    .await
                    .unwrap_or_else(|_| Err("Library worker stopped".into())),
            )
        })
    }

    fn library_view(&self) -> Element<'_, Message> {
        self.workspace_view()
    }
}

impl Stack {
    fn update_message(&mut self, message: Message) -> app::Task<Message> {
        // Jobs already completing when Close was accepted must not repopulate
        // widgets or dispatch new work while the owners are being drained.
        if self.closing && !matches!(message, Message::Shutdown(_)) {
            return app::Task::none();
        }
        match message {
            Message::Text(event) => return self.text_action(event),
            Message::Close
                if !self.closing
                    && (self.catalog.has_unsaved_changes()
                        || self.drafts.values().any(editor::Draft::has_unsaved_changes)) =>
            {
                self.confirm_quit = true;
                return iced::widget::operation::focus("stack-keep-editing");
            }
            Message::KeepEditing => self.confirm_quit = false,
            Message::DiscardAndQuit if self.confirm_quit => {
                self.confirm_quit = false;
                self.drafts.clear();
                self.catalog.mark_saved();
                return self.update_message(Message::Close);
            }
            Message::Close if !self.closing => {
                self.closing = true;
                if let Some(active) = &self.transfers.active {
                    active.control.cancel();
                }
                self.sync.cancel();
                self.invalidate_preview();
                self.repository_generation += 1;
                self.cancel_thumbnails();
                let text = self.text_worker.take();
                let thumbnails = self.thumbnail_worker.take();
                let renderer = self.renderer.take();
                let repository = self.repository.take();
                let transfer = self.transfer_worker.take();
                return cosmic::task::future(async move {
                    let result = tokio::task::spawn_blocking(move || {
                        drop(text);
                        stack_render::shutdown_text_worker().map_err(|e| e.to_string())?;
                        drop(thumbnails);
                        drop(renderer);
                        drop(transfer);
                        drop(repository);
                        stack_render::shutdown_pdf_worker().map_err(|e| e.to_string())
                    })
                    .await
                    .map_err(|e| e.to_string())
                    .and_then(|r| r);
                    Message::Shutdown(result)
                });
            }
            Message::Shutdown(result) => {
                if let Err(error) = result {
                    eprintln!("Renderer shutdown failed: {error}");
                }
                return iced::exit();
            }
            Message::Transfer(action) => {
                self.library_menu_open = false;
                return self.transfer_action(action);
            }
            Message::Sync(action) => return self.sync_action(action),
            Message::SyncResult(result) => return self.sync_result(result),
            Message::Picked(pick, result) => {
                if matches!(
                    pick,
                    transfers_ui::Pick::SyncImport | transfers_ui::Pick::SyncExport
                ) {
                    return self.sync_picked(pick, result);
                }
                let result = if self.flags.browser_case == "portal" {
                    match result {
                        Ok(Some(paths)) => {
                            let root = self
                                .flags
                                .browser_fixtures
                                .as_ref()
                                .expect("verified fixtures")
                                .parent()
                                .expect("test root");
                            if paths.iter().any(|p| {
                                !p.starts_with(root)
                                    || p.components()
                                        .any(|c| matches!(c, std::path::Component::ParentDir))
                            }) {
                                Err("Synthetic chooser must stay inside its authored test root"
                                    .into())
                            } else {
                                eprintln!(
                                    "Synthetic picker paths: {:?}",
                                    paths
                                        .iter()
                                        .map(|p| p.strip_prefix(root).expect("inside root"))
                                        .collect::<Vec<_>>()
                                );
                                Ok(Some(paths))
                            }
                        }
                        result => result,
                    }
                } else {
                    result
                };
                let task = self.picked(pick, result);
                if self.flags.browser_case == "portal" {
                    return app::Task::batch([task, self.portal_scenario()]);
                }
                return task;
            }
            Message::TransferRepository(result) => {
                let failed = result.is_err();
                let task = self.transfer_repository_result(result);
                if ["workflow", "portal"].contains(&self.flags.browser_case.as_str()) {
                    if failed {
                        eprintln!("Synthetic transfer failed: {:?}", self.transfers.status);
                        return iced::exit();
                    }
                    let next = if self.flags.browser_case == "portal" {
                        self.portal_scenario()
                    } else {
                        self.workflow_scenario()
                    };
                    return app::Task::batch([task, next]);
                }
                return task;
            }
            Message::Transferred(result) => {
                let failed = result.is_err();
                let task = self.transferred(result);
                if ["workflow", "portal"].contains(&self.flags.browser_case.as_str()) {
                    if failed {
                        eprintln!("Synthetic transfer failed: {:?}", self.transfers.status);
                        return iced::exit();
                    }
                    let next = if self.flags.browser_case == "portal" {
                        self.portal_scenario()
                    } else {
                        self.workflow_scenario()
                    };
                    return app::Task::batch([task, next]);
                }
                return task;
            }
            Message::TransferTick(id) => {
                if let Some(active) = self.transfers.active.as_ref().filter(|a| a.id == id) {
                    self.transfers.bytes = active.control.transferred_bytes();
                }
            }
            Message::Back => return self.go_back(),
            Message::LibraryMenu(open) => self.library_menu_open = open,
            Message::RefreshBrowser => {
                self.library_menu_open = false;
                return self.refresh_browser();
            }
            Message::Tiles(generation, result) => return self.tiles_loaded(generation, result),
            Message::Thumbnail(generation, id, result) => {
                return self.thumbnail_loaded(generation, id, result);
            }
            Message::BrowserScrolled(offset) => {
                self.browse_scroll = offset;
                return self.queue_visible_thumbnails();
            }
            Message::OpenDocument(id) => {
                self.editor_open = false;
                self.information_visible = false;
                let matching_page = self
                    .hits
                    .iter()
                    .find(|h| h.document_id == id)
                    .and_then(|h| h.page_id.clone());
                return self.select_matching(id, 0, matching_page);
            }
            Message::Query(query) => {
                self.catalog.open = false;
                self.editor_open = false;
                self.opening_document = None;
                self.rebuild_navigation();
                self.browse_scroll = 0.;
                self.browse_offset = 0;
                self.query = query;
                self.cancel_thumbnails();
                self.repository_generation += 1;
                self.invalidate_preview();
                self.selected = None;
                self.library_loading = true;
                let generation = self.repository_generation;
                return cosmic::task::future(async move {
                    tokio::time::sleep(Duration::from_millis(220)).await;
                    Message::SearchReady(generation)
                });
            }
            Message::SearchReady(generation) if generation == self.repository_generation => {
                return self.browse();
            }
            Message::SortByName(by_name) => {
                self.browse_offset = 0;
                self.sort = Some(if by_name { Sort::Name } else { Sort::Newest });
                return self.browse();
            }
            Message::GridView(grid) => {
                self.grid_view = grid;
                self.layout_loaded = true;
                self.browse_scroll = 0.;
                let save = if let Some(worker) = &self.repository {
                    let receive = worker.submit_required(Request::Layout(grid));
                    cosmic::task::future(async move {
                        Message::LayoutSaved(
                            receive
                                .await
                                .unwrap_or_else(|_| Err("Library worker stopped".into())),
                        )
                    })
                } else {
                    app::Task::none()
                };
                return app::Task::batch([
                    save,
                    self.queue_visible_thumbnails(),
                    iced::widget::scrollable::scroll_to(
                        "stack-documents".into(),
                        iced::widget::scrollable::AbsoluteOffset::default(),
                    ),
                ]);
            }
            Message::LayoutSaved(result) => match result {
                Ok(Response::SavedLayout) => {}
                Err(error) => self.library_error = Some(error),
                _ => {}
            },
            Message::Information(visible) => self.information_visible = visible,
            Message::ResultsPage(delta) if self.selected.is_none() => {
                self.browse_scroll = 0.;
                let last = self.hits.len().saturating_sub(1) / RESULTS_PER_PAGE * RESULTS_PER_PAGE;
                self.browse_offset = (self.browse_offset as i64
                    + i64::from(delta) * RESULTS_PER_PAGE as i64)
                    .clamp(0, last as i64) as usize;
                let load = self.load_tiles();
                return app::Task::batch([
                    load,
                    iced::widget::scrollable::scroll_to(
                        "stack-documents".into(),
                        iced::widget::scrollable::AbsoluteOffset::default(),
                    ),
                ]);
            }
            Message::Catalog(action) => return self.catalog_action(action),
            Message::Editor(action) => return self.editor_action(action),
            Message::DocumentSaved(id, field, result) => {
                let failed = result.is_err();
                let task = self.document_saved(id, field, result);
                if self.flags.browser_case == "editor" && failed {
                    if [1, 2].contains(&self.scenario_step) {
                        return self.delayed_capture();
                    }
                    return iced::exit();
                }
                return task;
            }
            Message::CatalogSaved(result) => {
                self.saving = false;
                match result {
                    Ok(Response::SavedCatalog) => {
                        self.save_status = Some("Saved".into());
                        self.catalog.saved_result();
                        self.catalog.confirm_delete = false;
                        return self.refresh_browser();
                    }
                    Err(error) => {
                        self.save_status =
                            Some(format!("Could not save: {error}. Your draft is retained."));
                        if self.flags.browser_case == "catalog" {
                            return iced::exit();
                        }
                    }
                    _ => {}
                }
            }
            Message::Repository(generation, result) if generation == self.repository_generation => {
                self.library_loading = false;
                match result {
                    Ok(Response::TextClaim { .. } | Response::TextFinished { .. }) => {}
                    Ok(Response::Browser {
                        stacks,
                        hits,
                        revision,
                        sort,
                        grid,
                    }) => {
                        self.catalog_revision = revision;
                        if !self.layout_loaded {
                            if let Some(grid) = grid {
                                self.grid_view = grid;
                            }
                            self.layout_loaded = true;
                        }
                        self.sort = Some(sort);
                        self.stacks = stacks.clone();
                        if self.flags.browser_fixtures.is_some()
                            && ![
                                "saved",
                                "catalog",
                                "catalog-reopen",
                                "editor",
                                "editor-reopen",
                                "workflow",
                                "workflow-reopen",
                                "interactive",
                                "portal",
                            ]
                            .contains(&self.flags.browser_case.as_str())
                        {
                            let names = [
                                "Bills", "Banking", "IDs", "House", "Medical", "Receipts",
                                "Starred",
                            ];
                            if self.flags.browser_case != "empty"
                                || !hits.is_empty()
                                || stacks.iter().map(|s| s.name.as_str()).collect::<Vec<_>>()
                                    != names
                            {
                                return iced::exit();
                            }
                        }
                        if self
                            .filter
                            .as_ref()
                            .is_some_and(|id| !self.stacks.iter().any(|s| &s.id == id))
                        {
                            self.filter = None;
                            return self.refresh_browser();
                        }
                        self.rebuild_navigation();
                        self.hits = hits;
                        self.browse_offset = self.browse_offset.min(
                            self.hits.len().saturating_sub(1) / RESULTS_PER_PAGE * RESULTS_PER_PAGE,
                        );
                        if let Some(id) = self.opening_document.clone() {
                            return self.select_document(id, self.page as usize);
                        }
                        if self.flags.browser_fixtures.is_some() {
                            if self.flags.browser_case == "interactive" {
                                return app::Task::none();
                            }
                            if self.flags.browser_case == "portal" {
                                return self.portal_scenario();
                            }
                            if self.flags.browser_case == "workflow" {
                                return self.workflow_scenario();
                            }
                            if self.flags.browser_case == "catalog" {
                                return self.catalog_scenario();
                            }
                            if self.flags.browser_case == "editor" && self.scenario_step == 16 {
                                return self.editor_scenario();
                            }
                            if self.flags.browser_case == "empty" {
                                return self.delayed_capture();
                            }
                            return self.select_document(stack_archive::synthetic::id(1), 0);
                        }
                        return self.load_tiles();
                    }
                    Ok(Response::Selected {
                        document,
                        page,
                        source,
                    }) => {
                        self.page = page as u32;
                        self.page_count = document.pages.len() as u32;
                        let saved = document.pages[page].clone();
                        self.opening_document = None;
                        self.selected = Some(document);
                        return self.render_job(RenderJob {
                            source,
                            page: saved.source_page,
                            rotation: Rotation::None,
                            saved: Some(saved),
                        });
                    }

                    Ok(
                        Response::Tiles(_)
                        | Response::SavedLayout
                        | Response::SavedCatalog
                        | Response::SavedDocument(_)
                        | Response::DeletedDocument(_)
                        | Response::Operation(_)
                        | Response::Sync(_),
                    ) => {}
                    Err(error) => {
                        self.busy = false;
                        self.opening_document = None;
                        self.library_error = Some(error);
                        if self.flags.browser_fixtures.is_some() {
                            if self.flags.browser_case == "busy"
                                && self
                                    .library_error
                                    .as_ref()
                                    .is_some_and(|e| e.contains("already using"))
                                || self.flags.browser_case == "error"
                                    && self
                                        .library_error
                                        .as_ref()
                                        .is_some_and(|e| e.contains("Choose an empty directory"))
                            {
                                return self.delayed_capture();
                            }
                            return iced::exit();
                        }
                    }
                }
            }
            Message::Select(sample) if !self.busy => {
                self.sample = sample;
                self.page = 0;
                self.rotation = 0;
                return self.render();
            }
            Message::Previous if self.page > 0 => {
                if let Some(document) = &self.selected {
                    return self.select_document(document.token.id.clone(), self.page as usize - 1);
                }
                self.page -= 1;
                return self.render();
            }
            Message::Next if self.page + 1 < self.page_count => {
                if let Some(document) = &self.selected {
                    return self.select_document(document.token.id.clone(), self.page as usize + 1);
                }
                self.page += 1;
                return self.render();
            }
            Message::Rotate if !self.busy => {
                self.rotation = (self.rotation + 90) % 360;
                return self.render();
            }
            Message::Retry if !self.busy => {
                if let Some(document) = &self.selected {
                    return self.select_document(document.token.id.clone(), self.page as usize);
                }
                return self.render();
            }
            Message::Rendered(token, result) if token == self.request => {
                self.busy = false;
                match result {
                    Ok(preview) => {
                        if self.flags.demo.is_some() {
                            self.page_count = preview.page_count;
                        }
                        self.preview = Some(widget::image::Handle::from_rgba(
                            preview.image.width(),
                            preview.image.height(),
                            preview.image.as_raw().clone(),
                        ));
                        if self.flags.browser_case == "portal" {
                            return self.portal_scenario();
                        }
                        if self.flags.browser_case == "workflow" {
                            return self.workflow_scenario();
                        }
                        if self.flags.browser_case == "editor" {
                            return self.editor_scenario();
                        }
                        if self.flags.smoke.is_some() && self.flags.browser_case != "interactive" {
                            return cosmic::task::future(async {
                                tokio::time::sleep(Duration::from_millis(900)).await;
                                Message::Capture
                            });
                        }
                    }
                    Err(error) => {
                        self.error = Some(error);
                        if self.flags.smoke.is_some() {
                            return iced::exit();
                        }
                    }
                }
            }
            Message::Capture => {
                if let Some(id) = self.core.main_window_id() {
                    let frames = self.flags.frames.clone();
                    let previous = frames.load(Ordering::Acquire);
                    let smoke_title = self.flags.smoke_title.clone();
                    let title_task = self.set_window_title(
                        smoke_title.clone().unwrap_or_else(|| "Stack".into()),
                        id,
                    );
                    let capture = iced::Task::perform(
                        async move {
                            if let Some(helper) = std::env::var_os("STACK_SYNTHETIC_FOCUS_HELPER")
                                && let Some(title) = smoke_title
                            {
                                tokio::task::spawn_blocking(move || {
                                    std::process::Command::new(helper)
                                        .env_remove("WAYLAND_DEBUG")
                                        .arg(title)
                                        .status()
                                })
                                .await
                                .map_err(|_| "Synthetic window helper failed")?
                                .map_err(|_| "Synthetic window helper could not run")?
                                .success()
                                .then_some(())
                                .ok_or("Synthetic window activation failed")?;
                            }
                            for _ in 0..300 {
                                tokio::time::sleep(Duration::from_millis(16)).await;
                                if frames.load(Ordering::Acquire) > previous {
                                    return Ok(());
                                }
                            }
                            eprintln!(
                                "Synthetic draw timeout: previous={previous}, current={}",
                                frames.load(Ordering::Acquire)
                            );
                            Err("Own window did not draw a fresh capture frame")
                        },
                        |result| result,
                    )
                    .then(move |result| match result {
                        Ok(()) => iced::window::screenshot(id)
                            .map(|s| cosmic::Action::App(Message::Captured(s))),
                        Err(error) => {
                            cosmic::task::future(
                                async move { Message::CaptureSaved(Err(error.into())) },
                            )
                        }
                    });
                    return app::Task::batch([
                        title_task,
                        iced::window::gain_focus(id),
                        iced::runtime::task::effect(iced::runtime::Action::Window(
                            iced::runtime::window::Action::RedrawAll,
                        )),
                        capture,
                    ]);
                }
                return iced::exit();
            }
            Message::Captured(screenshot) => {
                if let Some(directory) = &self.flags.smoke {
                    let name = if self.flags.visual_review.is_some() {
                        visual_review::CAPTURES[self.smoke_step]
                    } else if self.flags.browser_case == "workflow" {
                        [
                            "01-archive-review.png",
                            "02-saved-editor.png",
                            "03-later-page.png",
                            "04-exported.png",
                        ][self.smoke_step]
                    } else if self.flags.browser_case == "editor" {
                        [
                            "01-invalid-draft.png",
                            "02-stale-draft.png",
                            "03-saved-editor.png",
                            "04-saved-subset.png",
                            "05-after-delete.png",
                        ][self.smoke_step]
                    } else if self.flags.browser_case == "workflow-reopen" {
                        "01-reopened-workflow.png"
                    } else if self.flags.browser_case == "editor-reopen" {
                        "01-reopened-editor.png"
                    } else if self.flags.browser_case == "catalog" {
                        ["01-search-later-page.png", "02-saved-catalog.png"][self.smoke_step]
                    } else if self.flags.browser_fixtures.is_some()
                        && ![
                            "saved",
                            "catalog",
                            "catalog-reopen",
                            "editor",
                            "editor-reopen",
                            "workflow",
                            "workflow-reopen",
                            "interactive",
                            "portal",
                        ]
                        .contains(&self.flags.browser_case.as_str())
                    {
                        "01-library-state.png"
                    } else if self.flags.browser_fixtures.is_some() {
                        [
                            "01-saved-pdf.png",
                            "02-saved-later-page.png",
                            "03-saved-image.png",
                            "04-saved-transform.png",
                        ][self.smoke_step]
                    } else {
                        [
                            "01-report-page-1.png",
                            "02-report-page-2.png",
                            "03-oriented-image.png",
                        ][self.smoke_step]
                    };
                    let path = directory.join(name);
                    let draft=self.selected.as_ref().and_then(|doc|self.drafts.get(&doc.token.id)).map(|draft|serde_json::json!({"editor_debug":format!("{:?}",draft.notes),"title":draft.title,"notes":draft.notes.text(),"filing":draft.filing,"detail":draft.detail,"status":draft.status}));
                    let trace = serde_json::json!({"draft":draft,"saved_page_index": self.page, "document": self.selected, "stacks":self.stacks,"query":self.query,"sort_name":matches!(self.sort,Some(Sort::Name)),"transfer_status":self.transfers.status,"batch_outcomes":self.transfers.outcomes});
                    return cosmic::task::future(async move {
                        let result = tokio::task::spawn_blocking(move || {
                            std::fs::write(
                                path.with_extension("json"),
                                serde_json::to_vec_pretty(&trace).map_err(|e| e.to_string())?,
                            )
                            .map_err(|e| e.to_string())?;
                            image::save_buffer(
                                path,
                                &screenshot.rgba,
                                screenshot.size.width,
                                screenshot.size.height,
                                image::ColorType::Rgba8,
                            )
                            .map_err(|e| e.to_string())
                        })
                        .await
                        .map_err(|e| e.to_string())
                        .and_then(|result| result);
                        Message::CaptureSaved(result)
                    });
                }
            }
            Message::CaptureSaved(result) => {
                if let Err(error) = result {
                    eprintln!("Synthetic screenshot failed: {error}");
                    return iced::exit();
                }
                self.smoke_step += 1;
                if self.flags.visual_review.is_some() {
                    return self.visual_after_capture();
                }
                if self.flags.browser_case == "workflow" {
                    return self.workflow_scenario();
                }
                if self.flags.browser_case == "catalog" {
                    return self.catalog_scenario();
                }
                if self.flags.browser_case == "editor" {
                    return self.editor_scenario();
                }
                if ["editor-reopen", "workflow-reopen"].contains(&self.flags.browser_case.as_str())
                {
                    self.flags.completed.store(true, Ordering::SeqCst);
                    return cosmic::task::future(async { Message::Close });
                }
                if self.flags.browser_fixtures.is_some() {
                    if ![
                        "saved",
                        "catalog",
                        "catalog-reopen",
                        "editor",
                        "editor-reopen",
                        "workflow",
                        "workflow-reopen",
                        "interactive",
                        "portal",
                    ]
                    .contains(&self.flags.browser_case.as_str())
                    {
                        self.flags.completed.store(true, Ordering::SeqCst);
                        return iced::exit();
                    }
                    match self.smoke_step {
                        1 => return self.select_document(stack_archive::synthetic::id(1), 1),
                        2 => return self.select_document(stack_archive::synthetic::id(3), 0),
                        3 => return self.select_document(stack_archive::synthetic::id(2), 1),
                        _ => {
                            if let Err(error) = stack_render::shutdown_pdf_worker() {
                                eprintln!("Renderer shutdown failed: {error}");
                                return iced::exit();
                            }
                            self.flags.completed.store(true, Ordering::SeqCst);
                            return iced::exit();
                        }
                    }
                }
                match self.smoke_step {
                    1 => self.page = 1,
                    2 => {
                        self.sample = Sample::Orientation;
                        self.page = 0;
                    }
                    _ => {
                        // Every preview and capture is complete. Drain/join the
                        // native worker before tearing down the window/runtime.
                        if let Err(error) = stack_render::shutdown_pdf_worker() {
                            eprintln!("Renderer shutdown failed: {error}");
                            return iced::exit();
                        }
                        self.flags.completed.store(true, Ordering::SeqCst);
                        return iced::exit();
                    }
                }
                return self.render();
            }
            _ => {}
        }
        app::Task::none()
    }
}
