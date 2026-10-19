//! Snapshot-only document output. The lossless image PDF layout follows Stack's
//! Apache-2.0 StreamingPdf.java: one rendered bitmap plus one RGB scanline, an
//! indirect compressed-stream length, and bounded object offsets. No OCR layer.
use crate::Renderer;
use flate2::{Compression, write::ZlibEncoder};
use stack_archive::Control;
use stack_core::{
    FrozenSnapshot, RenderSize, SavedPage,
    library::{Error, Result},
};
use std::io::{self, Write};

pub const MAX_OUTPUT_BYTES: u64 = 8 * 1024 * 1024 * 1024;
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Original,
    Images,
}
impl Mode {
    pub fn text_status(self) -> &'static str {
        match self {
            Self::Original => "Original PDF copied exactly; existing selectable text is retained.",
            Self::Images => {
                "Saved pages rendered as images; this PDF has no selectable text layer."
            }
        }
    }
}
pub struct Plan {
    document: String,
    pages: Vec<SavedPage>,
    pub mode: Mode,
}
impl Plan {
    pub fn new(snapshot: &FrozenSnapshot, document: &str) -> Result<Self> {
        let doc = snapshot
            .manifest
            .rows("documents")
            .iter()
            .find(|d| d["id"] == document)
            .ok_or(Error::Stale)?;
        let mut pages: Vec<SavedPage> = snapshot
            .manifest
            .rows("pages")
            .iter()
            .filter(|p| p["document_id"] == document)
            .map(|p| SavedPage {
                id: p["id"].as_str().expect("validated ID").into(),
                position: p["position"].as_u64().expect("validated position") as u32,
                source_file: p["source_file"]
                    .as_str()
                    .expect("validated filename")
                    .into(),
                source_page: p["source_page"].as_u64().expect("validated source page") as u32,
                content_revision: p["content_revision"].as_i64().expect("validated revision"),
                rotation: p["rotation"].as_u64().expect("validated rotation") as u16,
                crop: p["crop"].as_str().expect("validated crop").into(),
                enhancement: p["enhancement"]
                    .as_str()
                    .expect("validated enhancement")
                    .into(),
                flatten: p["flatten"] == 1,
            })
            .collect();
        pages.sort_by_key(|p| p.position);
        if pages.is_empty() || pages.len() > stack_core::MAX_PAGE_COUNT as usize {
            return Err(Error::Invalid("Invalid saved page count"));
        }
        let untouched = doc["mime"] == "application/pdf"
            && doc["initial_page_count"].as_u64() == Some(pages.len() as u64)
            && pages.iter().enumerate().all(|(i, p)| {
                p.position == i as u32
                    && p.source_page == i as u32
                    && p.source_file == "source"
                    && !p.has_appearance()
            });
        Ok(Self {
            document: document.into(),
            pages,
            mode: if untouched {
                Mode::Original
            } else {
                Mode::Images
            },
        })
    }
    pub fn page_count(&self) -> u32 {
        self.pages.len() as u32
    }
    pub fn write(
        &self,
        snapshot: &FrozenSnapshot,
        renderer: &Renderer,
        destination: &mut impl Write,
        control: &Control,
    ) -> Result<()> {
        control.check()?;
        if self.mode == Mode::Original {
            let source = snapshot.original(&self.document, "source")?;
            let mut input = stack_archive::read_regular(&source)?;
            let length = input.metadata()?.len();
            stack_archive::transfer(&mut input, destination, length, control)?;
            return Ok(());
        }
        let mut out = Counted {
            destination,
            count: 0,
            control,
        };
        let mut offsets = vec![0_u64; 3 + self.pages.len() * 4];
        out.write_all(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")?;
        out.object(&mut offsets, 1)?;
        writeln!(out, "<< /Type /Catalog /Pages 2 0 R >>\nendobj")?;
        out.object(&mut offsets, 2)?;
        write!(out, "<< /Type /Pages /Count {} /Kids [", self.pages.len())?;
        for i in 0..self.pages.len() {
            write!(out, "{} 0 R ", 3 + i * 4)?;
        }
        writeln!(out, "] >>\nendobj")?;
        // Same 3200-pixel longest edge and half-pixel paper points as Android.
        let size = RenderSize::new(3200, 3200).expect("bounded constant");
        for (i, page) in self.pages.iter().enumerate() {
            control.check()?;
            let source = snapshot.original(&self.document, &page.source_file)?;
            let image = renderer
                .render_saved(&source, page, size)
                .map_err(|e| Error::Io(io::Error::other(e)))?
                .image;
            control.check()?;
            let (width, height) = image.dimensions();
            let (paper_width, paper_height) = (width.div_ceil(2), height.div_ceil(2));
            let page_id = 3 + i * 4;
            out.object(&mut offsets, page_id)?;
            writeln!(
                out,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {paper_width} {paper_height}] /Resources << /XObject << /Im0 {} 0 R >> >> /Contents {} 0 R >>\nendobj",
                page_id + 1,
                page_id + 2
            )?;
            out.object(&mut offsets, page_id + 1)?;
            writeln!(
                out,
                "<< /Type /XObject /Subtype /Image /Width {width} /Height {height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length {} 0 R >>\nstream",
                page_id + 3
            )?;
            let start = out.count;
            let mut compressed = ZlibEncoder::new(&mut out, Compression::default());
            let mut row = vec![0; width as usize * 3];
            for pixels in image.rows() {
                control.check()?;
                for (x, pixel) in pixels.enumerate() {
                    row[x * 3..x * 3 + 3].copy_from_slice(&pixel.0[..3]);
                }
                // Buffered transfer reports actual processed RGB bytes and checks
                // cancellation during compression, without retaining page streams.
                let mut input = &row[..];
                stack_archive::transfer(&mut input, &mut compressed, row.len() as u64, control)?;
            }
            compressed.finish()?;
            let length = out.count - start;
            writeln!(out, "\nendstream\nendobj")?;
            out.object(&mut offsets, page_id + 3)?;
            writeln!(out, "{length}\nendobj")?;
            let commands = format!("q\n{paper_width} 0 0 {paper_height} 0 0 cm\n/Im0 Do\nQ\n");
            out.object(&mut offsets, page_id + 2)?;
            write!(
                out,
                "<< /Length {} >>\nstream\n{commands}endstream\nendobj\n",
                commands.len()
            )?;
        }
        let xref = out.count;
        writeln!(out, "xref\n0 {}\n0000000000 65535 f ", offsets.len())?;
        for offset in &offsets[1..] {
            writeln!(out, "{offset:010} 00000 n ")?;
        }
        writeln!(
            out,
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF",
            offsets.len()
        )?;
        Ok(())
    }
}
struct Counted<'a, W> {
    destination: &'a mut W,
    count: u64,
    control: &'a Control,
}
impl<W: Write> Counted<'_, W> {
    fn object(&mut self, offsets: &mut [u64], id: usize) -> io::Result<()> {
        offsets[id] = self.count;
        writeln!(self, "{id} 0 obj")
    }
}
impl<W: Write> Write for Counted<'_, W> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        self.control.check().map_err(io::Error::other)?;
        if self.count + bytes.len() as u64 > MAX_OUTPUT_BYTES {
            return Err(io::Error::other(
                "Generated PDF exceeds the 8 GiB output limit",
            ));
        }
        let count = self.destination.write(bytes)?;
        self.count += count as u64;
        Ok(count)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.destination.flush()
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::{Rotation, fixtures};
    use stack_archive::synthetic;
    use stack_core::{DocumentEdit, library::Library};
    use std::{fs, path::Path};

    // Called by the existing single native-engine test, before its one shutdown.
    // This avoids parallel tests tearing down each other's process-wide PDF worker.
    pub(crate) fn native_exports(directory: &Path, renderer: &Renderer) {
        let control = Control::default();
        let bytes: Vec<_> = fixtures::NAMES
            .iter()
            .map(|name| fs::read(directory.join(name)).unwrap())
            .collect();
        let archive = directory.join("pdf-export-graph");
        synthetic::create(&archive, [&bytes[0], &bytes[1], &bytes[2], &bytes[3]]).unwrap();
        let root = directory.join("pdf-library");
        let mut library = Library::open(&root).unwrap();
        let staged = library
            .stage_archive(
                &mut fs::File::open(archive.join("format-3.stackbackup")).unwrap(),
                renderer,
                &control,
            )
            .unwrap();
        let review = library.review_archive(&staged).unwrap();
        library.commit_archive(&review, &control).unwrap();
        drop(review);
        drop(staged);
        let original_graph = library.manifest().unwrap().into_json();
        let snapshot = library.snapshot(&control).unwrap();
        let original = Plan::new(&snapshot, &synthetic::id(1)).unwrap();
        assert_eq!(original.mode, Mode::Original);
        let path = directory.join("untouched.pdf");
        original
            .write(
                &snapshot,
                renderer,
                &mut fs::File::create(&path).unwrap(),
                &control,
            )
            .unwrap();
        assert_eq!(fs::read(&path).unwrap(), bytes[0]);
        renderer.inspect_generated_pdf(&path, 2).unwrap();
        drop(snapshot);

        let token = library.token(&synthetic::id(1)).unwrap();
        library
            .edit_document(
                &token,
                DocumentEdit::PageOrder(vec![synthetic::id(101), synthetic::id(100)]),
                &control,
            )
            .unwrap();
        let snapshot = library.snapshot(&control).unwrap();
        let reordered = Plan::new(&snapshot, &synthetic::id(1)).unwrap();
        assert_eq!(reordered.mode, Mode::Images);
        let token = library.token(&synthetic::id(1)).unwrap();
        library
            .edit_document(
                &token,
                DocumentEdit::PageOrder(vec![synthetic::id(101)]),
                &control,
            )
            .unwrap();
        // Old coherent snapshot still exports both saved pages in its captured order.
        let reordered_path = directory.join("reordered.pdf");
        reordered
            .write(
                &snapshot,
                renderer,
                &mut fs::File::create(&reordered_path).unwrap(),
                &control,
            )
            .unwrap();
        renderer.inspect_generated_pdf(&reordered_path, 2).unwrap();
        let first = renderer
            .render(
                &reordered_path,
                0,
                RenderSize::new(600, 800).unwrap(),
                Rotation::None,
            )
            .unwrap();
        let second = renderer
            .render(
                &reordered_path,
                1,
                RenderSize::new(600, 800).unwrap(),
                Rotation::None,
            )
            .unwrap();
        assert!(first.image.width() > first.image.height());
        assert!(second.image.width() < second.image.height());
        assert!(first.image.get_pixel(35, 35)[2] > 180);
        assert!(second.image.get_pixel(35, 35)[0] > 180);
        drop(snapshot);

        let token = library.token(&synthetic::id(1)).unwrap();
        library
            .edit_document(
                &token,
                DocumentEdit::PageAppearance {
                    page: synthetic::id(101),
                    rotation: 90,
                    crop: "[0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9]".into(),
                    enhancement: "black_white".into(),
                    flatten: true,
                },
                &control,
            )
            .unwrap();
        let snapshot = library.snapshot(&control).unwrap();
        // Saved subset/appearance, multi-original scan and every image type use
        // only the captured saved sequence and the preview's appearance path.
        for doc in 1..=5 {
            let plan = Plan::new(&snapshot, &synthetic::id(doc)).unwrap();
            assert_eq!(plan.mode, Mode::Images);
            let path = directory.join(format!("rendered-{doc}.pdf"));
            plan.write(
                &snapshot,
                renderer,
                &mut fs::File::create(&path).unwrap(),
                &control,
            )
            .unwrap();
            renderer
                .inspect_generated_pdf(&path, if doc == 2 { 2 } else { 1 })
                .unwrap();
            assert!(renderer.inspect_generated_pdf(&path, 9).is_err());
            for (index, page) in plan.pages.iter().enumerate() {
                let source = snapshot
                    .original(&plan.document, &page.source_file)
                    .unwrap();
                let expected = renderer
                    .render_saved(&source, page, RenderSize::new(640, 640).unwrap())
                    .unwrap()
                    .image;
                let actual = renderer
                    .render(
                        &path,
                        index as u32,
                        RenderSize::new(expected.width(), expected.height()).unwrap(),
                        Rotation::None,
                    )
                    .unwrap()
                    .image;
                let aspect = |w: u32, h: u32| w as f32 / h as f32;
                assert!(
                    (aspect(expected.width(), expected.height())
                        - aspect(actual.width(), actual.height()))
                    .abs()
                        < 0.02
                );
                for (x, y) in [(0.05, 0.05), (0.95, 0.05), (0.05, 0.95), (0.95, 0.95)] {
                    let sample = |image: &image::RgbaImage| {
                        *image.get_pixel(
                            (x * (image.width() - 1) as f32) as u32,
                            (y * (image.height() - 1) as f32) as u32,
                        )
                    };
                    let a = sample(&actual);
                    let e = sample(&expected);
                    assert!(
                        a.0[..3]
                            .iter()
                            .zip(&e.0[..3])
                            .all(|(a, e)| a.abs_diff(*e) <= 8),
                        "doc {doc} page {index}: {a:?} vs {e:?}"
                    );
                }
            }
        }
        let canceled = Control::default();
        canceled.cancel();
        let mut output = Vec::new();
        assert!(
            Plan::new(&snapshot, &synthetic::id(1))
                .unwrap()
                .write(&snapshot, renderer, &mut output, &canceled)
                .is_err()
        );
        assert!(output.is_empty());
        for entry in original_graph["files"].as_array().unwrap() {
            assert_eq!(
                fixtures::digest(&root.join(entry["path"].as_str().unwrap())).unwrap(),
                entry["sha256"].as_str().unwrap()
            );
        }
    }
}
