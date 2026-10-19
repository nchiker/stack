use stack_archive::Control;
use stack_core::{DocumentEdit, RenderSize, Sort, library::Library};
use stack_render::{Renderer, fixtures, pdf::Plan};

#[test]
#[ignore = "requires pinned PDFium and packaged Tesseract/English runtime"]
fn authored_pdf_scans_crops_blank_and_restart_use_real_offline_engines() {
    let root = tempfile::tempdir().unwrap();
    let pdf = root.path().join("authored.pdf");
    fixtures::readable_pdf(&pdf).unwrap();
    let original = std::fs::read(&pdf).unwrap();
    let renderer = Renderer::from_environment();
    let control = Control::default();
    let mut library = Library::open(&root.path().join("library")).unwrap();
    library
        .import_original(
            &pdf,
            "Exact manual 00024.500 🦀",
            &renderer,
            "application/pdf",
            3,
            &control,
        )
        .unwrap();
    let id = library.search("", None, Sort::Newest, 10).unwrap()[0]
        .document_id
        .clone();
    let first = library.claim_text().unwrap().unwrap();
    let text = renderer
        .read_saved_text(&first.source, &first.page)
        .unwrap();
    assert_eq!(text.method, "embedded");
    assert!(text.text.contains("LEFTWORD") && text.text.contains("RIGHTWORD"));
    library.finish_text(&first, Ok(text)).unwrap();
    let later = library.claim_text().unwrap().unwrap();
    let text = renderer
        .read_saved_text(&later.source, &later.page)
        .unwrap();
    assert_eq!(text.method, "embedded");
    assert!(text.text.contains("LATERPHRASE"));
    library.finish_text(&later, Ok(text)).unwrap();
    let blank = library.claim_text().unwrap().unwrap();
    let text = renderer
        .read_saved_text(&blank.source, &blank.page)
        .unwrap();
    assert_eq!(text.method, "ocr");
    assert!(text.text.trim().is_empty());
    library.finish_text(&blank, Ok(text)).unwrap();
    let hit = library
        .search("LATERPHRASE", None, Sort::Newest, 10)
        .unwrap();
    assert_eq!(hit[0].page_id.as_ref(), Some(&later.page.id));
    let scan = root.path().join("scan.png");
    renderer
        .render_saved(
            &first.source,
            &first.page,
            RenderSize::new(1800, 2400).unwrap(),
        )
        .unwrap()
        .image
        .save(&scan)
        .unwrap();
    library
        .import_original(&scan, "Authored scan", &renderer, "image/png", 1, &control)
        .unwrap();
    let job = library.claim_text().unwrap().unwrap();
    let text = renderer.read_saved_text(&job.source, &job.page).unwrap();
    assert_eq!(text.method, "ocr");
    assert!(text.text.contains("LEFTWORD"), "{}", text.text);
    library.finish_text(&job, Ok(text)).unwrap();
    // Rendered PDF is image-only: no hidden source text is consulted.
    let snapshot = library.snapshot(&control).unwrap();
    let plan = Plan::new(&snapshot, &job.token.id).unwrap();
    let image_pdf = root.path().join("image-only.pdf");
    plan.write(
        &snapshot,
        &renderer,
        &mut std::fs::File::create(&image_pdf).unwrap(),
        &control,
    )
    .unwrap();
    drop(snapshot);
    assert!(
        renderer
            .embedded_text(&image_pdf, 0)
            .unwrap()
            .trim()
            .is_empty()
    );
    library
        .import_original(
            &image_pdf,
            "Authored image PDF",
            &renderer,
            "application/pdf",
            1,
            &control,
        )
        .unwrap();
    let job = library.claim_text().unwrap().unwrap();
    let text = renderer.read_saved_text(&job.source, &job.page).unwrap();
    assert_eq!(text.method, "ocr");
    assert!(text.text.contains("RIGHTWORD"));
    library.finish_text(&job, Ok(text)).unwrap();
    let token = library.token(&id).unwrap();
    library
        .edit_document(
            &token,
            DocumentEdit::PageAppearance {
                page: first.page.id.clone(),
                rotation: 0,
                crop: "[0,0,0.48,0,0.48,1,0,1]".into(),
                enhancement: "original".into(),
                flatten: false,
            },
            &control,
        )
        .unwrap();
    let crop = library.claim_text().unwrap().unwrap();
    let text = renderer.read_saved_text(&crop.source, &crop.page).unwrap();
    assert_eq!(text.method, "ocr");
    assert!(text.text.contains("LEFTWORD"));
    assert!(!text.text.contains("RIGHTWORD"));
    library.finish_text(&crop, Ok(text)).unwrap();
    assert!(
        library
            .search("RIGHTWORD", None, Sort::Newest, 10)
            .unwrap()
            .iter()
            .all(|h| h.document_id != id)
    );
    drop(library);
    let library = Library::open(&root.path().join("library")).unwrap();
    assert_eq!(
        library
            .search("LATERPHRASE", None, Sort::Newest, 10)
            .unwrap()[0]
            .page_id
            .as_ref(),
        Some(&later.page.id)
    );
    assert_eq!(std::fs::read(pdf).unwrap(), original);
    stack_render::shutdown_text_worker().unwrap();
    stack_render::shutdown_pdf_worker().unwrap();
}
