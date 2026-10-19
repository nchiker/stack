//! Bounded, read-only previews and saved-page appearance.
mod appearance;
pub mod fixtures;
pub mod pdf;
mod text;
pub use text::{OcrRuntime, shutdown_text_worker};

use image::{DynamicImage, ImageDecoder, ImageReader, RgbaImage};
use pdfium_render::prelude::*;
use stack_core::{MAX_PAGE_COUNT, MAX_SOURCE_BYTES, RenderSize, Rotation};
use std::fs::File;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, mpsc};

const MAX_IMAGE_PIXELS: u64 = 24_000_000;

#[derive(Debug, thiserror::Error)]
pub enum RenderError {
    #[error("Could not read preview source: {0}")]
    Io(#[from] std::io::Error),
    #[error("Could not decode image: {0}")]
    Image(#[from] image::ImageError),
    #[error("Could not render PDF: {0}")]
    Pdf(#[from] PdfiumError),
    #[error("PDF renderer: {0}")]
    Native(String),
    #[error("{0}")]
    Invalid(&'static str),
    #[error(
        "PDF preview needs the packaged PDFium engine; set STACK_PDFIUM_LIBRARY to its absolute library path"
    )]
    MissingPdfium,
}

#[derive(Debug)]
pub struct Preview {
    pub image: RgbaImage,
    pub page_count: u32,
}

#[derive(Clone, Debug)]
pub struct SourceInfo {
    pub mime: &'static str,
    pub page_count: u32,
}

#[derive(Clone, Debug)]
pub struct Renderer {
    pdfium_library: Option<PathBuf>,
}

impl Renderer {
    /// Only for our bounded, privately generated output, before publication.
    /// Untrusted original inspection keeps its separate 256 MiB limit.
    pub fn inspect_generated_pdf(&self, path: &Path, expected: u32) -> Result<(), RenderError> {
        let file =
            stack_archive::read_regular(path).map_err(|e| RenderError::Native(e.to_string()))?;
        if file.metadata()?.len() == 0 || file.metadata()?.len() > pdf::MAX_OUTPUT_BYTES {
            return Err(RenderError::Invalid(
                "Generated PDF exceeds its output limit",
            ));
        }
        let (_, count) = self.pdf_request(
            path,
            0,
            RenderSize::new(1, 1).expect("constant"),
            Rotation::None,
            true,
        )?;
        if count != expected {
            return Err(RenderError::Invalid(
                "Generated PDF has an unexpected page count",
            ));
        }
        Ok(())
    }
    /// One saved-state path for previews and rendered document output. The physical
    /// source count is diagnostic only; callers use the saved sequence's length.
    pub fn render_saved(
        &self,
        path: &Path,
        page: &stack_core::SavedPage,
        size: RenderSize,
    ) -> Result<Preview, RenderError> {
        let edge = size.width().max(size.height());
        let crop = appearance::crop_points(&page.crop)?;
        let square = RenderSize::new(edge, edge)
            .map_err(|_| RenderError::Invalid("Invalid preview size"))?;
        let mut preview = self.render(path, page.source_page, square, Rotation::None)?;
        if let Some(points) = crop {
            let (width, height) = appearance::dimensions(&appearance::pixel_points(
                points,
                preview.image.width(),
                preview.image.height(),
            ));
            let source_edge = preview.image.width().max(preview.image.height());
            let decode_edge = ((edge as f32 * source_edge as f32 / width.max(height).max(1) as f32)
                .ceil() as u32)
                .clamp(edge, 4096);
            if decode_edge > edge {
                preview = self.render(
                    path,
                    page.source_page,
                    RenderSize::new(decode_edge, decode_edge).expect("bounded decode"),
                    Rotation::None,
                )?;
            }
            preview.image = appearance::perspective(&preview.image, points, edge)?;
        }
        // Android renders onto white paper. Preserve transparent-image appearance.
        for pixel in preview.image.pixels_mut() {
            let alpha = u32::from(pixel[3]);
            for channel in 0..3 {
                pixel[channel] =
                    ((u32::from(pixel[channel]) * alpha + 255 * (255 - alpha) + 127) / 255) as u8;
            }
            pixel[3] = 255;
        }
        preview.image = rotate(
            DynamicImage::ImageRgba8(preview.image),
            Rotation::try_from(page.rotation)
                .map_err(|_| RenderError::Invalid("Invalid saved rotation"))?,
        )
        .into_rgba8();
        if page.flatten
            && let Some(model) = appearance::estimate(&preview.image)
        {
            preview.image = appearance::flatten(&preview.image, model);
        }
        appearance::enhance(&mut preview.image, &page.enhancement)?;
        if preview.image.width() > size.width() || preview.image.height() > size.height() {
            preview.image = DynamicImage::ImageRgba8(preview.image)
                .thumbnail(size.width(), size.height())
                .into_rgba8();
        }
        Ok(preview)
    }

    /// Never searches the current directory or downloads an engine at runtime.
    pub fn new(pdfium_library: Option<PathBuf>) -> Self {
        Self { pdfium_library }
    }

    pub fn from_environment() -> Self {
        let library = std::env::var_os("STACK_PDFIUM_LIBRARY").map(PathBuf::from);
        Self::new(library)
    }

    /// Type/page-count inspection without rendering or decoding full image pixels.
    pub fn inspect_original(&self, path: &Path) -> Result<SourceInfo, RenderError> {
        use std::io::Read;
        let file =
            stack_archive::read_regular(path).map_err(|e| RenderError::Native(e.to_string()))?;
        if file.metadata()?.len() == 0 || file.metadata()?.len() > MAX_SOURCE_BYTES {
            return Err(RenderError::Invalid(
                "Original size is outside the supported range",
            ));
        }
        let mut signature = [0; 5];
        (&file).read_exact(&mut signature)?;
        if signature == *b"%PDF-" {
            let (_, count) = self.pdf_request(
                path,
                0,
                RenderSize::new(1, 1).expect("constant size"),
                Rotation::None,
                true,
            )?;
            return Ok(SourceInfo {
                mime: "application/pdf",
                page_count: count,
            });
        }
        let reader = ImageReader::open(path)?.with_guessed_format()?;
        let mime = match reader.format() {
            Some(image::ImageFormat::Jpeg) => "image/jpeg",
            Some(image::ImageFormat::Png) => "image/png",
            Some(image::ImageFormat::WebP) => "image/webp",
            _ => return Err(RenderError::Invalid("Unsupported original image type")),
        };
        let (width, height) = reader.into_dimensions()?;
        if width == 0 || height == 0 {
            return Err(RenderError::Invalid("Image has no pixels"));
        }
        Ok(SourceInfo {
            mime,
            page_count: 1,
        })
    }

    pub fn render(
        &self,
        path: &Path,
        page: u32,
        size: RenderSize,
        rotation: Rotation,
    ) -> Result<Preview, RenderError> {
        let file = File::open(path)?;
        let metadata = file.metadata()?;
        if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_SOURCE_BYTES {
            return Err(RenderError::Invalid(
                "Source must be a nonempty regular file of at most 256 MiB",
            ));
        }
        // Detect from bytes, never from a user-controlled extension.
        use std::io::Read;
        let mut signature = [0; 5];
        (&file).read_exact(&mut signature)?;
        let pdf = signature == *b"%PDF-";
        let (image, page_count) = if pdf {
            self.pdf(path, page, size, rotation)?
        } else {
            if page != 0 {
                return Err(RenderError::Invalid("Image has only one page"));
            }
            // Reopen: format probing begins at byte zero after signature detection.
            let mut reader = ImageReader::open(path)?.with_guessed_format()?;
            if !matches!(
                reader.format(),
                Some(image::ImageFormat::Jpeg | image::ImageFormat::Png | image::ImageFormat::WebP)
            ) {
                return Err(RenderError::Invalid("Choose a JPEG, PNG, WebP or PDF"));
            }
            let mut limits = image::Limits::default();
            limits.max_image_width = Some(32_000);
            limits.max_image_height = Some(32_000);
            limits.max_alloc = Some(128 * 1024 * 1024);
            reader.limits(limits);
            let mut decoder = reader.into_decoder()?;
            let (width, height) = decoder.dimensions();
            if u64::from(width) * u64::from(height) > MAX_IMAGE_PIXELS {
                return Err(RenderError::Invalid(
                    "Image exceeds the 24 megapixel preview decode limit",
                ));
            }
            let orientation = decoder.orientation()?;
            let mut image = DynamicImage::from_decoder(decoder)?;
            image.apply_orientation(orientation);
            (rotate(image, rotation), 1)
        };
        let image = if image.width() > size.width() || image.height() > size.height() {
            image.thumbnail(size.width(), size.height())
        } else {
            image
        }
        .into_rgba8();
        if image.width() == 0 || image.height() == 0 {
            return Err(RenderError::Invalid("Source has no renderable pixels"));
        }
        Ok(Preview { image, page_count })
    }

    fn pdf(
        &self,
        path: &Path,
        page: u32,
        size: RenderSize,
        rotation: Rotation,
    ) -> Result<(DynamicImage, u32), RenderError> {
        let (image, count) = self.pdf_request(path, page, size, rotation, false)?;
        Ok((
            image.ok_or(RenderError::Invalid(
                "PDF preview worker returned no pixels",
            ))?,
            count,
        ))
    }

    /// Embedded text follows the same process-global PDFium lifecycle as previews.
    pub fn embedded_text(&self, path: &Path, page: u32) -> Result<String, RenderError> {
        // Size/signature/count inspection uses the already bounded source path.
        let info = self.inspect_original(path)?;
        if info.mime != "application/pdf" || page >= info.page_count {
            return Err(RenderError::Invalid(
                "Embedded text requires a saved PDF page",
            ));
        }
        let (reply, response) = mpsc::channel();
        let (unused, _) = mpsc::channel();
        {
            let worker = PDF_WORKER
                .lock()
                .map_err(|_| RenderError::Invalid("PDF worker lock failed"))?;
            worker
                .as_ref()
                .ok_or(RenderError::MissingPdfium)?
                .sender
                .send(PdfJob {
                    path: path.to_owned(),
                    page,
                    size: RenderSize::new(1, 1).expect("constant"),
                    rotation: Rotation::None,
                    inspect: false,
                    text_reply: Some(reply),
                    reply: unused,
                })
                .map_err(|_| RenderError::Invalid("PDF worker stopped"))?;
        }
        response
            .recv()
            .map_err(|_| RenderError::Invalid("PDF text worker stopped"))?
            .map_err(RenderError::Native)
    }

    fn pdf_request(
        &self,
        path: &Path,
        page: u32,
        size: RenderSize,
        rotation: Rotation,
        inspect: bool,
    ) -> Result<(Option<DynamicImage>, u32), RenderError> {
        let library = self
            .pdfium_library
            .as_ref()
            .ok_or(RenderError::MissingPdfium)?;
        if !library.is_absolute() {
            return Err(RenderError::Invalid("PDFium library path must be absolute"));
        }
        // pdfium-render 0.9 owns process-global bindings: initialize once on a
        // dedicated worker and keep Pdfium alive there for all preview requests.
        let library = library.canonicalize()?;
        let (reply, result) = mpsc::channel();
        {
            let mut worker = PDF_WORKER
                .lock()
                .map_err(|_| RenderError::Invalid("PDF worker lock failed"))?;
            if worker.is_none() {
                *worker = Some(PdfWorker::start(library.clone())?);
            }
            let worker = worker.as_ref().expect("initialized worker");
            if worker.library != library {
                return Err(RenderError::Invalid(
                    "Restart Stack before changing the PDFium engine",
                ));
            }
            worker
                .sender
                .send(PdfJob {
                    path: path.to_owned(),
                    page,
                    size,
                    rotation,
                    inspect,
                    text_reply: None,
                    reply,
                })
                .map_err(|_| RenderError::Invalid("PDF worker stopped; restart Stack"))?;
        }
        result
            .recv()
            .map_err(|_| RenderError::Invalid("PDF worker stopped before producing a preview"))?
            .map_err(RenderError::Native)
    }
}

struct PdfJob {
    path: PathBuf,
    page: u32,
    size: RenderSize,
    rotation: Rotation,
    inspect: bool,
    text_reply: Option<mpsc::Sender<Result<String, String>>>,
    reply: mpsc::Sender<Result<(Option<DynamicImage>, u32), String>>,
}

struct PdfWorker {
    library: PathBuf,
    sender: mpsc::SyncSender<PdfJob>,
    thread: std::thread::JoinHandle<()>,
}
static PDF_WORKER: Mutex<Option<PdfWorker>> = Mutex::new(None);

/// Terminal process cleanup: drain and join the native worker before app teardown.
/// Once bindings are initialized, a new engine cannot be started in this process.
pub fn shutdown_pdf_worker() -> Result<(), RenderError> {
    let worker = PDF_WORKER
        .lock()
        .map_err(|_| RenderError::Invalid("PDF worker lock failed during shutdown"))?
        .take();
    if let Some(PdfWorker { sender, thread, .. }) = worker {
        drop(sender);
        thread
            .join()
            .map_err(|_| RenderError::Invalid("PDF worker failed during shutdown"))?;
    }
    Ok(())
}

impl PdfWorker {
    fn start(library: PathBuf) -> Result<Self, RenderError> {
        let engine_path = library.clone();
        let (sender, receiver) = mpsc::sync_channel::<PdfJob>(1);
        let (ready, initialized) = mpsc::channel();
        let thread = std::thread::Builder::new()
            .name("stack-pdf-preview".into())
            .spawn(move || {
                let pdfium = match Pdfium::bind_to_library(engine_path) {
                    Ok(bindings) => Pdfium::new(bindings),
                    Err(error) => {
                        let _ = ready.send(Err(error.to_string()));
                        return;
                    }
                };
                let _ = ready.send(Ok(()));
                while let Ok(job) = receiver.recv() {
                    if let Some(reply) = job.text_reply {
                        let result = (|| -> Result<String, RenderError> {
                            let document = pdfium.load_pdf_from_file(&job.path, None)?;
                            let page = document.pages().get(job.page as i32)?;
                            let text = page.text()?;
                            if text.len() > 512 * 1024 {
                                return Err(RenderError::Invalid(
                                    "PDF text exceeds the page limit",
                                ));
                            }
                            Ok(text.all())
                        })()
                        .map_err(|e| e.to_string());
                        let _ = reply.send(result);
                        continue;
                    }
                    let result = if job.inspect {
                        inspect_pdf(&pdfium, &job.path).map(|count| (None, count))
                    } else {
                        render_pdf(&pdfium, &job.path, job.page, job.size, job.rotation)
                            .map(|(image, count)| (Some(image), count))
                    }
                    .map_err(|e| e.to_string());
                    let _ = job.reply.send(result);
                }
            })?;
        initialized
            .recv()
            .map_err(|_| RenderError::Invalid("PDF worker failed to initialize"))?
            .map_err(RenderError::Native)?;
        Ok(Self {
            library,
            sender,
            thread,
        })
    }
}

impl stack_archive::SourceVerifier for Renderer {
    fn verify(
        &self,
        path: &Path,
        expected_mime: &str,
        pdf_pages: Option<u32>,
    ) -> stack_archive::Result<()> {
        let info = self
            .inspect_original(path)
            .map_err(|e| stack_archive::Error::Source(e.to_string()))?;
        if info.mime != expected_mime
            || pdf_pages.is_some_and(|expected| expected != info.page_count)
        {
            return Err(stack_archive::Error::Invalid(
                "Original MIME type or PDF page count disagrees with metadata",
            ));
        }
        Ok(())
    }
}

fn inspect_pdf(pdfium: &Pdfium, path: &Path) -> Result<u32, RenderError> {
    let document = pdfium.load_pdf_from_file(path, None)?;
    let count = u32::try_from(document.pages().len())
        .map_err(|_| RenderError::Invalid("Invalid PDF page count"))?;
    if count == 0 || count > MAX_PAGE_COUNT {
        return Err(RenderError::Invalid(
            "PDF exceeds the supported 1–1,000 page range",
        ));
    }
    Ok(count)
}

fn render_pdf(
    pdfium: &Pdfium,
    path: &Path,
    page: u32,
    size: RenderSize,
    rotation: Rotation,
) -> Result<(DynamicImage, u32), RenderError> {
    let document = pdfium.load_pdf_from_file(path, None)?;
    let count = u32::try_from(document.pages().len())
        .map_err(|_| RenderError::Invalid("PDF has an invalid page count"))?;
    if count == 0 || count > MAX_PAGE_COUNT || page >= count {
        return Err(RenderError::Invalid(
            "PDF page is outside the supported 1–1,000 page range",
        ));
    }
    let page = document.pages().get(page as i32)?;
    if !page.width().value.is_finite()
        || !page.height().value.is_finite()
        || page.width().value <= 0.0
        || page.height().value <= 0.0
    {
        return Err(RenderError::Invalid("PDF page has invalid dimensions"));
    }
    let (width, height) = match rotation {
        Rotation::Clockwise90 | Rotation::Clockwise270 => (size.height(), size.width()),
        _ => (size.width(), size.height()),
    };
    // Maximum height preserves aspect ratio without auto-rotating landscape pages.
    let config = PdfRenderConfig::new()
        .set_target_width(width as i32)
        .set_maximum_width(width as i32)
        .set_maximum_height(height as i32)
        .limit_render_image_cache_size(true);
    let rendered = page.render_with_config(&config)?.as_image()?;
    Ok((rotate(rendered, rotation), count))
}

fn rotate(image: DynamicImage, rotation: Rotation) -> DynamicImage {
    match rotation {
        Rotation::None => image,
        Rotation::Clockwise90 => image.rotate90(),
        Rotation::Clockwise180 => image.rotate180(),
        Rotation::Clockwise270 => image.rotate270(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn root(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "stack-m0-{label}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ))
    }

    #[test]
    fn exif_rotation_and_explicit_rotation_preserve_corner_identity() {
        let dir = root("orientation");
        fixtures::create(&dir).unwrap();
        let renderer = Renderer::new(None);
        let size = RenderSize::new(800, 800).unwrap();
        let source = dir.join("oriented.jpg");
        let before = fixtures::digest(&source).unwrap();
        let preview = renderer.render(&source, 0, size, Rotation::None).unwrap();
        assert_eq!(preview.image.dimensions(), (360, 640));
        let top_left = preview.image.get_pixel(30, 30);
        assert!(
            top_left[1] > 150 && top_left[0] < 80,
            "EXIF clockwise rotation should put green at top left"
        );
        let turned = renderer
            .render(&source, 0, size, Rotation::Clockwise270)
            .unwrap();
        assert_eq!(turned.image.dimensions(), (640, 360));
        assert!(turned.image.get_pixel(30, 30)[0] > 150);
        assert_eq!(fixtures::digest(&source).unwrap(), before);
        for name in ["corners.png", "corners.webp"] {
            assert_eq!(
                renderer
                    .render(&dir.join(name), 0, size, Rotation::None)
                    .unwrap()
                    .image
                    .dimensions(),
                (640, 360)
            );
        }
        let saved = stack_core::SavedPage {
            id: "synthetic-saved-page".into(),
            position: 0,
            source_file: "source".into(),
            source_page: 0,
            content_revision: 1,
            rotation: 90,
            crop: "[0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9]".into(),
            enhancement: "medium".into(),
            flatten: true,
        };
        let saved_preview = renderer.render_saved(&source, &saved, size).unwrap();
        assert_eq!(saved_preview.image.dimensions(), (511, 287));
        // EXIF-upright green/ red /yellow /blue, then saved clockwise turn.
        let corner = saved_preview.image.get_pixel(20, 20);
        assert!(corner[0] > 200 && corner[1] > 180 && corner[2] < 100);
        assert_eq!(fixtures::digest(&source).unwrap(), before);
        assert!(renderer.render(&source, 1, size, Rotation::None).is_err());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn malformed_and_oversized_sources_fail_without_decoding() {
        let dir = root("rejection");
        std::fs::create_dir(&dir).unwrap();
        let path = dir.join("source");
        std::fs::write(&path, b"not a document").unwrap();
        let renderer = Renderer::new(None);
        let size = RenderSize::new(600, 800).unwrap();
        assert!(renderer.render(&path, 0, size, Rotation::None).is_err());
        File::create(&path)
            .unwrap()
            .set_len(MAX_SOURCE_BYTES + 1)
            .unwrap();
        assert!(matches!(
            renderer.render(&path, 0, size, Rotation::None),
            Err(RenderError::Invalid(_))
        ));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    #[ignore = "requires the pinned native engine via STACK_PDFIUM_LIBRARY"]
    fn native_pdf_pages_are_distinct_bounded_and_read_only() {
        let dir = root("pdfium");
        fixtures::create(&dir).unwrap();
        let renderer = Renderer::from_environment();
        let source = dir.join("two-pages.pdf");
        let before = fixtures::digest(&source).unwrap();
        let inspected = renderer.inspect_original(&source).unwrap();
        assert_eq!(inspected.mime, "application/pdf");
        assert_eq!(inspected.page_count, 2);
        assert!(
            stack_archive::SourceVerifier::verify(&renderer, &source, "application/pdf", Some(3))
                .is_err()
        );
        let size = RenderSize::new(600, 800).unwrap();
        let one = renderer.render(&source, 0, size, Rotation::None).unwrap();
        let two = renderer.render(&source, 1, size, Rotation::None).unwrap();
        assert_eq!(one.page_count, 2);
        assert!(one.image.width() < one.image.height());
        assert!(two.image.width() > two.image.height());
        assert!(one.image.width() <= 600 && one.image.height() <= 800);
        assert_ne!(one.image.as_raw(), two.image.as_raw());
        let red = one.image.get_pixel(35, 35);
        let blue = two.image.get_pixel(35, 35);
        assert!(red[0] > 180 && red[1] < 80 && red[2] < 80);
        assert!(blue[2] > 180 && blue[0] < 80 && blue[1] < 100);
        // Distinct callers can reuse the once-initialized engine concurrently.
        let jobs: Vec<_> = (0..2)
            .map(|page| {
                let source = source.clone();
                std::thread::spawn(move || {
                    Renderer::from_environment()
                        .render(&source, page, size, Rotation::Clockwise90)
                        .unwrap()
                })
            })
            .collect();
        for job in jobs {
            let preview = job.join().unwrap();
            assert_eq!(preview.page_count, 2);
            assert!(preview.image.width() <= 600 && preview.image.height() <= 800);
        }
        assert!(renderer.render(&source, 2, size, Rotation::None).is_err());
        let saved = stack_core::SavedPage {
            id: "later-page".into(),
            position: 0,
            source_file: "source".into(),
            source_page: 1,
            content_revision: 2,
            rotation: 90,
            crop: String::new(),
            enhancement: "bright".into(),
            flatten: false,
        };
        let saved_preview = renderer.render_saved(&source, &saved, size).unwrap();
        assert!(saved_preview.image.width() < saved_preview.image.height());
        assert_eq!(saved_preview.page_count, 2); // Physical count is never the saved sequence length.
        let blue = saved_preview
            .image
            .get_pixel(saved_preview.image.width() - 35, 35);
        assert!(blue[2] > 180 && blue[0] < 80 && blue[1] < 100);
        assert_eq!(fixtures::digest(&source).unwrap(), before);
        pdf::tests::native_exports(&dir, &renderer);
        shutdown_pdf_worker().unwrap();
        std::fs::remove_dir_all(dir).unwrap();
    }
}
