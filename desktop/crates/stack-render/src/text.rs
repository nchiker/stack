//! Offline English OCR with a single owned native API, bounded request channel,
//! and the same saved-page renderer used by previews/export. No network fallback.
use crate::{RenderError, Renderer};
use libloading::Library;
use sha2::{Digest, Sha256};
use stack_core::{RenderSize, SavedPage, TextOutput};
use std::ffi::{CStr, CString, c_char, c_int, c_void};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, mpsc};

const MODEL_SHA: &str = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2";
const ENGINE_SHA: &str = "0d3b71cd757860c6639918c1b2d9407c8652ee22babc8d5c5f18cc35dde6334b";

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct OcrRuntime {
    pub library: PathBuf,
    pub tessdata: PathBuf,
}
impl OcrRuntime {
    pub fn from_environment() -> Result<Self, RenderError> {
        let library = std::env::var_os("STACK_TESSERACT_LIBRARY").map(PathBuf::from);
        let tessdata = std::env::var_os("STACK_TESSDATA_DIR").map(PathBuf::from);
        match (library, tessdata) {
            (Some(library), Some(tessdata)) if library.is_absolute() && tessdata.is_absolute() => {
                Ok(Self { library, tessdata })
            }
            _ => Err(RenderError::Invalid(
                "Offline English text reading needs the packaged OCR runtime",
            )),
        }
    }
}

struct Request {
    renderer: Renderer,
    path: PathBuf,
    page: SavedPage,
    runtime: Option<OcrRuntime>,
    reply: mpsc::Sender<Result<TextOutput, String>>,
}
struct Worker {
    sender: mpsc::SyncSender<Request>,
    thread: std::thread::JoinHandle<()>,
}
static WORKER: Mutex<Option<Worker>> = Mutex::new(None);

impl Renderer {
    pub fn read_saved_text(
        &self,
        path: &Path,
        page: &SavedPage,
    ) -> Result<TextOutput, RenderError> {
        self.read_saved_text_with_runtime(path, page, None)
    }

    pub fn read_saved_text_with_runtime(
        &self,
        path: &Path,
        page: &SavedPage,
        runtime: Option<OcrRuntime>,
    ) -> Result<TextOutput, RenderError> {
        let (reply, response) = mpsc::channel();
        {
            let mut worker = WORKER
                .lock()
                .map_err(|_| RenderError::Invalid("Text worker lock failed"))?;
            if worker.is_none() {
                let (sender, receiver) = mpsc::sync_channel::<Request>(1);
                let thread = std::thread::Builder::new()
                    .name("stack-offline-text".into())
                    .spawn(move || {
                        let mut engine: Option<(OcrRuntime, Engine)> = None;
                        while let Ok(request) = receiver.recv() {
                            let result = read(&request, &mut engine).map_err(|e| e.to_string());
                            let _ = request.reply.send(result);
                        }
                    })?;
                *worker = Some(Worker { sender, thread });
            }
            worker
                .as_ref()
                .expect("started")
                .sender
                .send(Request {
                    renderer: self.clone(),
                    path: path.to_owned(),
                    page: page.clone(),
                    runtime,
                    reply,
                })
                .map_err(|_| RenderError::Invalid("Text worker stopped"))?;
        }
        response
            .recv()
            .map_err(|_| RenderError::Invalid("Text worker stopped before returning text"))?
            .map_err(RenderError::Native)
    }
}

fn read(
    request: &Request,
    engine: &mut Option<(OcrRuntime, Engine)>,
) -> Result<TextOutput, RenderError> {
    let info = request.renderer.inspect_original(&request.path)?;
    // Cropping/perspective, enhancement and flattening may exclude or destroy
    // source glyphs. Use the final saved raster for every transformed page.
    if info.mime == "application/pdf" && !request.page.has_appearance() {
        let text = request
            .renderer
            .embedded_text(&request.path, request.page.source_page)?;
        if text.chars().any(char::is_alphanumeric) {
            return Ok(TextOutput {
                text,
                method: "embedded".into(),
            });
        }
    }
    let runtime = request
        .runtime
        .clone()
        .map(Ok)
        .unwrap_or_else(OcrRuntime::from_environment)?;
    if engine
        .as_ref()
        .is_some_and(|(current, _)| *current != runtime)
    {
        return Err(RenderError::Invalid(
            "Restart Stack before changing the OCR engine",
        ));
    }
    if engine.is_none() {
        *engine = Some((runtime.clone(), Engine::new(&runtime)?));
    }
    let preview = request.renderer.render_saved(
        &request.path,
        &request.page,
        RenderSize::new(2400, 2400).expect("bounded"),
    )?;
    let pixels = image::DynamicImage::ImageRgba8(preview.image).into_rgb8();
    let text = engine.as_mut().expect("initialized").1.recognize(&pixels)?;
    Ok(TextOutput {
        text,
        method: "ocr".into(),
    })
}

/// Drain text before PDFium, because OCR rasterization may be waiting on PDFium.
pub fn shutdown_text_worker() -> Result<(), RenderError> {
    let worker = WORKER
        .lock()
        .map_err(|_| RenderError::Invalid("Text worker lock failed"))?
        .take();
    if let Some(Worker { sender, thread }) = worker {
        drop(sender);
        thread
            .join()
            .map_err(|_| RenderError::Invalid("Text worker failed during shutdown"))?;
    }
    Ok(())
}

fn verify(path: &Path, expected: &str, limit: u64) -> Result<(), RenderError> {
    if !path.is_absolute() {
        return Err(RenderError::Invalid("OCR paths must be absolute"));
    }
    let mut file =
        stack_archive::read_regular(path).map_err(|e| RenderError::Native(e.to_string()))?;
    if file.metadata()?.len() > limit {
        return Err(RenderError::Invalid("OCR asset exceeds its limit"));
    }
    let mut digest = Sha256::new();
    let mut buffer = [0u8; 65536];
    loop {
        let length = file.read(&mut buffer)?;
        if length == 0 {
            break;
        }
        digest.update(&buffer[..length]);
    }
    if format!("{:x}", digest.finalize()) != expected {
        return Err(RenderError::Invalid("Packaged OCR asset checksum failed"));
    }
    Ok(())
}

type Handle = *mut c_void;
type Delete = unsafe extern "C" fn(Handle);
type SetImage = unsafe extern "C" fn(Handle, *const u8, c_int, c_int, c_int, c_int);
type Recognize = unsafe extern "C" fn(Handle, *mut c_void) -> c_int;
type GetText = unsafe extern "C" fn(Handle) -> *mut c_char;
type DeleteText = unsafe extern "C" fn(*const c_char);
struct Engine {
    handle: Handle,
    delete: Delete,
    clear: Delete,
    set_image: SetImage,
    recognize: Recognize,
    get_text: GetText,
    delete_text: DeleteText,
    // Retained after every function pointer and handle is no longer used.
    _library: Library,
}
impl Engine {
    fn new(runtime: &OcrRuntime) -> Result<Self, RenderError> {
        verify(&runtime.library, ENGINE_SHA, 16 * 1024 * 1024)?;
        verify(
            &runtime.tessdata.join("eng.traineddata"),
            MODEL_SHA,
            8 * 1024 * 1024,
        )?;
        let data = CString::new(runtime.tessdata.as_os_str().as_encoded_bytes())
            .map_err(|_| RenderError::Invalid("Invalid OCR model path"))?;
        // SAFETY: We verify a pinned Tesseract 5.3.4 binary before loading. All
        // signatures below are the upstream 5.3.4 capi.h ABI. Engine is created,
        // used and dropped exclusively on the single text worker thread.
        unsafe {
            let library =
                Library::new(&runtime.library).map_err(|e| RenderError::Native(e.to_string()))?;
            macro_rules! symbol {
                ($name:literal,$typ:ty) => {
                    *library
                        .get::<$typ>(concat!($name, "\0").as_bytes())
                        .map_err(|e| RenderError::Native(e.to_string()))?
                };
            }
            let create = symbol!("TessBaseAPICreate", unsafe extern "C" fn() -> Handle);
            let delete = symbol!("TessBaseAPIDelete", Delete);
            let clear = symbol!("TessBaseAPIClear", Delete);
            let init = symbol!(
                "TessBaseAPIInit2",
                unsafe extern "C" fn(Handle, *const c_char, *const c_char, c_int) -> c_int
            );
            let set_mode = symbol!(
                "TessBaseAPISetPageSegMode",
                unsafe extern "C" fn(Handle, c_int)
            );
            let set_image = symbol!("TessBaseAPISetImage", SetImage);
            let recognize = symbol!("TessBaseAPIRecognize", Recognize);
            let get_text = symbol!("TessBaseAPIGetUTF8Text", GetText);
            let delete_text = symbol!("TessDeleteText", DeleteText);
            let version = symbol!("TessVersion", unsafe extern "C" fn() -> *const c_char);
            if CStr::from_ptr(version()).to_bytes() != b"5.3.4" {
                return Err(RenderError::Invalid("Unsupported OCR engine version"));
            }
            let handle = create();
            if handle.is_null() {
                return Err(RenderError::Invalid("Could not allocate OCR engine"));
            }
            if init(handle, data.as_ptr(), c"eng".as_ptr(), 1) != 0 {
                delete(handle);
                return Err(RenderError::Invalid(
                    "Could not initialize offline English OCR",
                ));
            }
            set_mode(handle, 3);
            Ok(Self {
                handle,
                delete,
                clear,
                set_image,
                recognize,
                get_text,
                delete_text,
                _library: library,
            })
        }
    }
    fn recognize(&mut self, image: &image::RgbImage) -> Result<String, RenderError> {
        // SAFETY: the bounded RGB buffer stays live until recognition completes;
        // returned text belongs to Tesseract and is released with TessDeleteText.
        unsafe {
            (self.set_image)(
                self.handle,
                image.as_raw().as_ptr(),
                image.width() as c_int,
                image.height() as c_int,
                3,
                (image.width() * 3) as c_int,
            );
            let result = if (self.recognize)(self.handle, std::ptr::null_mut()) != 0 {
                Err(RenderError::Invalid("Offline OCR could not read this page"))
            } else {
                let text = (self.get_text)(self.handle);
                if text.is_null() {
                    Err(RenderError::Invalid("Offline OCR returned no result"))
                } else {
                    let bytes = CStr::from_ptr(text).to_bytes();
                    let result = if bytes.len() > 1024 * 1024 {
                        Err(RenderError::Invalid("OCR text exceeds the page limit"))
                    } else {
                        Ok(String::from_utf8_lossy(bytes).into_owned())
                    };
                    (self.delete_text)(text);
                    result
                }
            };
            (self.clear)(self.handle);
            result
        }
    }
}
impl Drop for Engine {
    fn drop(&mut self) {
        // SAFETY: the handle is owned here and the library remains loaded.
        unsafe {
            (self.delete)(self.handle);
        }
    }
}
