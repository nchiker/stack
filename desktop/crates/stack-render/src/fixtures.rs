//! Authored synthetic fixtures, version 1. No reference or personal documents.
//! Generated fixture content is dedicated to CC0-1.0; generator code is Apache-2.0.

use image::{Rgb, RgbImage};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;

pub const NAMES: [&str; 4] = [
    "two-pages.pdf",
    "oriented.jpg",
    "corners.png",
    "corners.webp",
];

#[derive(Serialize)]
struct FixtureFile {
    name: &'static str,
    sha256: String,
}

pub fn create(directory: &Path) -> Result<(), Box<dyn std::error::Error>> {
    // A caller must choose a fresh directory; never overwrite an existing input.
    fs::create_dir(directory)?;
    let mut files = Vec::new();
    for name in NAMES {
        let bytes = match name {
            "two-pages.pdf" => pdf(),
            "oriented.jpg" => jpeg_with_orientation()?,
            _ => {
                let image = image::DynamicImage::ImageRgb8(corners());
                let mut output = std::io::Cursor::new(Vec::new());
                let format = if name.ends_with("png") {
                    image::ImageFormat::Png
                } else {
                    image::ImageFormat::WebP
                };
                image.write_to(&mut output, format)?;
                output.into_inner()
            }
        };
        let path = directory.join(name);
        let mut output = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)?;
        output.write_all(&bytes)?;
        output.sync_all()?;
        files.push(FixtureFile {
            name,
            sha256: digest(&path)?,
        });
    }
    let manifest = serde_json::json!({
        "fixture_version": 1, "license": "CC0-1.0",
        "description": "Independently authored Stack synthetic PDF and four-corner image fixtures",
        "files": files,
        "expectations": { "pdf_pages": 2, "pdf_page_1": "portrait, red top-left marker",
            "pdf_page_2": "landscape, blue top-left marker", "jpeg_orientation": 6,
            "jpeg_display": "360x640, green top-left", "png_webp_display": "640x360, red top-left" }
    });
    fs::write(
        directory.join("manifest.json"),
        serde_json::to_vec_pretty(&manifest)?,
    )?;
    Ok(())
}

pub fn digest(path: &Path) -> std::io::Result<String> {
    let mut file = fs::File::open(path)?;
    let mut hash = Sha256::new();
    let mut buffer = [0; 64 * 1024];
    loop {
        let count = file.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        hash.update(&buffer[..count]);
    }
    Ok(format!("{:x}", hash.finalize()))
}

/// Independently authored, CC0 text-search input; no system fonts or downloads.
pub fn readable_pdf(path: &Path) -> std::io::Result<()> {
    use std::io::Write;
    let streams = [
        "BT /F1 28 Tf 40 710 Td (LEFTWORD) Tj 300 0 Td (RIGHTWORD) Tj ET\nBT /F1 24 Tf 40 650 Td (OFFLINE READING) Tj ET",
        "BT /F1 28 Tf 40 710 Td (LATERPHRASE page two only) Tj ET",
        "",
    ];
    let mut objects = vec![
        "<< /Type /Catalog /Pages 2 0 R >>".to_owned(),
        "<< /Type /Pages /Kids [4 0 R 6 0 R 8 0 R] /Count 3 >>".to_owned(),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_owned(),
    ];
    for (index, stream) in streams.iter().enumerate() {
        objects.push(format!("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents {} 0 R >>",5+index*2));
        objects.push(format!(
            "<< /Length {} >>\nstream\n{stream}\nendstream",
            stream.len() + 1
        ));
    }
    let mut bytes = b"%PDF-1.4\n".to_vec();
    let mut offsets = vec![0];
    for (index, object) in objects.iter().enumerate() {
        offsets.push(bytes.len());
        write!(&mut bytes, "{} 0 obj\n{object}\nendobj\n", index + 1)?;
    }
    let xref = bytes.len();
    write!(
        &mut bytes,
        "xref\n0 {}\n0000000000 65535 f \n",
        offsets.len()
    )?;
    for offset in offsets.iter().skip(1) {
        writeln!(&mut bytes, "{offset:010} 00000 n ")?;
    }
    write!(
        &mut bytes,
        "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n",
        offsets.len()
    )?;
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)?
        .write_all(&bytes)
}

/// Smoke capture accepts only the exact authored samples, never arbitrary content.
pub fn verify(directory: &Path) -> Result<(), Box<dyn std::error::Error>> {
    for name in NAMES {
        let bytes = match name {
            "two-pages.pdf" => pdf(),
            "oriented.jpg" => jpeg_with_orientation()?,
            _ => {
                let mut output = std::io::Cursor::new(Vec::new());
                let format = if name.ends_with("png") {
                    image::ImageFormat::Png
                } else {
                    image::ImageFormat::WebP
                };
                image::DynamicImage::ImageRgb8(corners()).write_to(&mut output, format)?;
                output.into_inner()
            }
        };
        if digest(&directory.join(name))? != format!("{:x}", Sha256::digest(&bytes)) {
            return Err(
                "Smoke fixture bytes do not match the authored synthetic fixture set".into(),
            );
        }
    }
    Ok(())
}

fn corners() -> RgbImage {
    RgbImage::from_fn(640, 360, |x, y| match (x < 320, y < 180) {
        (true, true) => Rgb([220, 35, 45]),
        (false, true) => Rgb([30, 80, 220]),
        (true, false) => Rgb([35, 190, 65]),
        (false, false) => Rgb([235, 200, 35]),
    })
}

fn jpeg_with_orientation() -> image::ImageResult<Vec<u8>> {
    let mut jpeg = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut jpeg, 92).encode_image(&corners())?;
    // Minimal little-endian TIFF IFD: orientation SHORT=6 (90 degrees clockwise).
    let exif = b"Exif\0\0II\x2a\0\x08\0\0\0\x01\0\x12\x01\x03\0\x01\0\0\0\x06\0\0\0\0\0\0\0";
    let mut output = jpeg[..2].to_vec();
    output.extend_from_slice(&[0xff, 0xe1]);
    output.extend_from_slice(&((exif.len() + 2) as u16).to_be_bytes());
    output.extend_from_slice(exif);
    output.extend_from_slice(&jpeg[2..]);
    Ok(output)
}

fn pdf() -> Vec<u8> {
    let page_one = "1 0.08 0.10 rg 20 692 80 80 re f\n0.1 0.1 0.1 rg\nBT /F1 24 Tf 120 720 Td (Stack synthetic page one) Tj ET\nBT /F1 14 Tf 40 620 Td (Portrait. Originals stay unchanged.) Tj ET\n";
    let page_two = "0.08 0.2 1 rg 20 512 80 80 re f\n0.1 0.1 0.1 rg\nBT /F1 24 Tf 120 540 Td (Stack synthetic page two) Tj ET\nBT /F1 14 Tf 40 440 Td (Landscape. Later-page preview works.) Tj ET\n";
    let objects = [
        "<< /Type /Catalog /Pages 2 0 R >>".to_owned(),
        "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>".to_owned(),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 7 0 R >> >> /Contents 4 0 R >>".to_owned(),
        format!("<< /Length {} >>\nstream\n{page_one}endstream", page_one.len()),
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 792 612] /Resources << /Font << /F1 7 0 R >> >> /Contents 6 0 R >>".to_owned(),
        format!("<< /Length {} >>\nstream\n{page_two}endstream", page_two.len()),
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_owned(),
    ];
    let mut bytes = b"%PDF-1.4\n%Stack synthetic fixture v1\n".to_vec();
    let mut offsets = Vec::new();
    for (index, object) in objects.iter().enumerate() {
        offsets.push(bytes.len());
        bytes.extend_from_slice(format!("{} 0 obj\n{object}\nendobj\n", index + 1).as_bytes());
    }
    let xref = bytes.len();
    bytes.extend_from_slice(
        format!("xref\n0 {}\n0000000000 65535 f \n", objects.len() + 1).as_bytes(),
    );
    for offset in offsets {
        bytes.extend_from_slice(format!("{offset:010} 00000 n \n").as_bytes());
    }
    bytes.extend_from_slice(
        format!(
            "trailer\n<< /Size {} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n",
            objects.len() + 1
        )
        .as_bytes(),
    );
    bytes
}

/// Independently authored paper/curved-line inputs for host algorithm comparison.
/// This is synthetic diagnostic output, never library content or Android bitmap proof.
pub fn appearance_smoke(directory: &Path) -> Result<(), Box<dyn std::error::Error>> {
    use crate::appearance;
    use image::{Rgba, RgbaImage};
    fs::create_dir(directory)?;
    let mut paper = RgbaImage::from_fn(240, 320, |x, y| {
        let light = (170 + (x * 37 / 240) + (y * 23 / 320)) as u8;
        Rgba([light, light, light, 255])
    });
    for baseline in [40, 90, 145, 210, 270] {
        for x in 24..216 {
            for dy in 0..4 {
                let value = if dy == 3 { 160 } else { 40 };
                paper.put_pixel(x, baseline + dy, Rgba([value, value, value, 255]));
            }
        }
    }
    paper.save(directory.join("paper-input.png"))?;
    appearance::enhance(&mut paper, "black_white")?;
    paper.save(directory.join("paper-black-white.png"))?;
    fs::write(directory.join("paper-black-white.rgba"), paper.as_raw())?;
    let mut models = serde_json::Map::new();
    for (label, quadratic, linear) in [
        ("bowed", 80., 0.),
        ("straight", 0., 0.),
        ("slanted", 80., 60.),
    ] {
        let mut page = RgbaImage::from_pixel(600, 800, Rgba([240, 240, 240, 255]));
        for baseline in [85, 143, 211, 267, 319, 381, 444, 517, 581, 642, 709] {
            for x in 20..580 {
                let u = x as f32 / 600. - 0.5;
                let y = (baseline as f32 + quadratic * u * u + linear * u).round() as u32;
                for dy in 0..4 {
                    page.put_pixel(x, y + dy, Rgba([30, 30, 30, 255]));
                }
            }
        }
        page.save(directory.join(format!("{label}-input.png")))?;
        let model = appearance::estimate(&page);
        models.insert(
            label.into(),
            model
                .map(|m| serde_json::json!({"quadratic":m.quadratic,"linear":m.linear}))
                .unwrap_or(serde_json::Value::Null),
        );
        if let Some(model) = model {
            appearance::flatten(&page, model)
                .save(directory.join(format!("{label}-flattened.png")))?;
        }
    }
    fs::write(
        directory.join("models.json"),
        serde_json::to_vec_pretty(&models)?,
    )?;
    Ok(())
}
