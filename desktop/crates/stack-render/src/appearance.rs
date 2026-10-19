//! Saved appearance port of Stack's PageRenderer/PageGeometry/PageEnhancement/
//! PageFlattening.java (Apache-2.0). CPU bilinear sampling replaces Android Canvas;
//! host algorithm goldens are not Android bitmap parity evidence.
use crate::RenderError;
use image::{Rgba, RgbaImage};

pub(crate) fn crop_points(encoded: &str) -> Result<Option<[f32; 8]>, RenderError> {
    if encoded.is_empty() {
        return Ok(None);
    }
    let values: Vec<serde_json::Value> =
        serde_json::from_str(encoded).map_err(|_| RenderError::Invalid("Invalid saved crop"))?;
    if values.len() != 8 {
        return Err(RenderError::Invalid("Saved crop needs four corners"));
    }
    let mut points = [0.; 8];
    for (out, value) in points.iter_mut().zip(values) {
        *out = value
            .as_f64()
            .or_else(|| value.as_str().and_then(|s| s.parse().ok()))
            .ok_or(RenderError::Invalid("Invalid crop coordinate"))? as f32;
        if !out.is_finite() || !(0. ..=1.).contains(out) {
            return Err(RenderError::Invalid("Crop must stay inside the page"));
        }
    }
    let mut area = 0.;
    for i in 0..4 {
        let j = (i + 1) % 4;
        let k = (i + 2) % 4;
        let ax = points[2 * j] - points[2 * i];
        let ay = points[2 * j + 1] - points[2 * i + 1];
        let bx = points[2 * k] - points[2 * j];
        let by = points[2 * k + 1] - points[2 * j + 1];
        if ax * by - ay * bx < 0.002 || ax * ax + ay * ay < 0.0025 {
            return Err(RenderError::Invalid("Saved crop corners are invalid"));
        }
        area += points[2 * i] * points[2 * j + 1] - points[2 * j] * points[2 * i + 1];
    }
    if area < 0.08 {
        return Err(RenderError::Invalid("Saved crop is too small"));
    }
    Ok((points != [0., 0., 1., 0., 1., 1., 0., 1.]).then_some(points))
}

pub(crate) fn pixel_points(points: [f32; 8], width: u32, height: u32) -> [f32; 8] {
    std::array::from_fn(|i| {
        points[i]
            * if i % 2 == 0 {
                width.saturating_sub(1) as f32
            } else {
                height.saturating_sub(1) as f32
            }
    })
}

pub(crate) fn dimensions(points: &[f32; 8]) -> (u32, u32) {
    let distance = |a: usize, b: usize| {
        (points[2 * a] - points[2 * b]).hypot(points[2 * a + 1] - points[2 * b + 1])
    };
    (
        distance(0, 1).max(distance(3, 2)).round().max(1.) as u32,
        distance(0, 3).max(distance(1, 2)).round().max(1.) as u32,
    )
}

// White, opaque paper compositing; source alpha must not become black in exports.
fn sample(image: &RgbaImage, x: f32, y: f32) -> Rgba<u8> {
    if !x.is_finite()
        || !y.is_finite()
        || x < -0.5
        || y < -0.5
        || x > image.width() as f32 - 0.5
        || y > image.height() as f32 - 0.5
    {
        return Rgba([255; 4]);
    }
    let x = x.clamp(0., image.width().saturating_sub(1) as f32);
    let y = y.clamp(0., image.height().saturating_sub(1) as f32);
    let x0 = x.floor() as u32;
    let y0 = y.floor() as u32;
    let x1 = (x0 + 1).min(image.width() - 1);
    let y1 = (y0 + 1).min(image.height() - 1);
    let fx = x - x0 as f32;
    let fy = y - y0 as f32;
    let mut result = [255; 4];
    for (channel, out) in result.iter_mut().enumerate().take(3) {
        let paper = |px: u32, py: u32| {
            let p = image.get_pixel(px, py);
            (p[channel] as f32 * p[3] as f32 + 255. * (255 - p[3]) as f32) / 255.
        };
        *out = ((paper(x0, y0) * (1. - fx) + paper(x1, y0) * fx) * (1. - fy)
            + (paper(x0, y1) * (1. - fx) + paper(x1, y1) * fx) * fy)
            .round()
            .clamp(0., 255.) as u8;
    }
    Rgba(result)
}

pub(crate) fn perspective(
    image: &RgbaImage,
    points: [f32; 8],
    max_edge: u32,
) -> Result<RgbaImage, RenderError> {
    let points = pixel_points(points, image.width(), image.height());
    let (width, height) = dimensions(&points);
    let scale = (max_edge as f32 / width.max(height) as f32).min(1.);
    let width = (width as f32 * scale).round().max(1.) as u32;
    let height = (height as f32 * scale).round().max(1.) as u32;
    let x = [points[0], points[2], points[4], points[6]];
    let y = [points[1], points[3], points[5], points[7]];
    let dx1 = x[1] - x[2];
    let dx2 = x[3] - x[2];
    let dx3 = x[0] - x[1] + x[2] - x[3];
    let dy1 = y[1] - y[2];
    let dy2 = y[3] - y[2];
    let dy3 = y[0] - y[1] + y[2] - y[3];
    let determinant = dx1 * dy2 - dx2 * dy1;
    if determinant.abs() < 0.000001 {
        return Err(RenderError::Invalid("Saved crop is singular"));
    }
    let g = (dx3 * dy2 - dx2 * dy3) / determinant;
    let h = (dx1 * dy3 - dx3 * dy1) / determinant;
    Ok(RgbaImage::from_fn(width, height, |px, py| {
        let u = (px as f32 + 0.5) / width as f32;
        let v = (py as f32 + 0.5) / height as f32;
        let d = g * u + h * v + 1.;
        let sx = ((x[1] - x[0] + g * x[1]) * u + (x[3] - x[0] + h * x[3]) * v + x[0]) / d;
        let sy = ((y[1] - y[0] + g * y[1]) * u + (y[3] - y[0] + h * y[3]) * v + y[0]) / d;
        sample(image, sx - 0.5, sy - 0.5)
    }))
}

pub(crate) fn enhance(image: &mut RgbaImage, preset: &str) -> Result<(), RenderError> {
    match preset {
        "original" => {}
        "medium" | "bright" => {
            let (contrast, offset) = if preset == "bright" {
                (1.18_f32, 18.)
            } else {
                (1.10, 5.)
            };
            for pixel in image.pixels_mut() {
                for channel in 0..3 {
                    pixel[channel] = (pixel[channel] as f32 * contrast + offset)
                        .round()
                        .clamp(0., 255.) as u8;
                }
            }
        }
        "black_white" => black_white(image),
        _ => return Err(RenderError::Invalid("Unknown saved enhancement")),
    }
    Ok(())
}

fn median(source: &[u8], target: &mut [u8], width: usize, height: usize, rows: bool) {
    let (length, lines, stride) = if rows {
        (width, height, 1)
    } else {
        (height, width, width)
    };
    for line in 0..lines {
        let base = if rows { line * width } else { line };
        for p in 0..length {
            let a = source[base + p.saturating_sub(1) * stride];
            let b = source[base + p * stride];
            let c = source[base + (p + 1).min(length - 1) * stride];
            target[base + p * stride] = a.max(b.min(c)).min(b.max(c));
        }
    }
}

fn extrema(
    source: &[u8],
    target: &mut [u8],
    width: usize,
    height: usize,
    radius: usize,
    rows: bool,
    maximum: bool,
) {
    let (length, lines, stride) = if rows {
        (width, height, 1)
    } else {
        (height, width, width)
    };
    let mut deque = vec![0; length];
    for line in 0..lines {
        let base = if rows { line * width } else { line };
        let (mut head, mut tail, mut next) = (0, 0, 0);
        for position in 0..length {
            let right = (position + radius).min(length - 1);
            while next <= right {
                let value = source[base + next * stride];
                while tail > head
                    && if maximum {
                        source[base + deque[tail - 1] * stride] <= value
                    } else {
                        source[base + deque[tail - 1] * stride] >= value
                    }
                {
                    tail -= 1;
                }
                deque[tail] = next;
                tail += 1;
                next += 1;
            }
            while deque[head] < position.saturating_sub(radius) {
                head += 1;
            }
            target[base + position * stride] = source[base + deque[head] * stride];
        }
    }
}

fn luminance(p: &Rgba<u8>) -> u8 {
    ((p[0] as u32 * 77 + p[1] as u32 * 150 + p[2] as u32 * 29) >> 8) as u8
}
fn black_white(image: &mut RgbaImage) {
    let (width, height) = (image.width() as usize, image.height() as usize);
    let gray: Vec<_> = image.pixels().map(luminance).collect();
    let mut horizontal = vec![0; gray.len()];
    let mut paper = vec![0; gray.len()];
    let radius = (width.min(height) / 50).clamp(8, 64);
    median(&gray, &mut horizontal, width, height, true);
    median(&horizontal, &mut paper, width, height, false);
    extrema(&paper, &mut horizontal, width, height, radius, true, true);
    extrema(&horizontal, &mut paper, width, height, radius, false, true);
    extrema(&paper, &mut horizontal, width, height, radius, true, false);
    extrema(&horizontal, &mut paper, width, height, radius, false, false);
    for ((pixel, gray), paper) in image.pixels_mut().zip(gray).zip(paper) {
        let normalized = gray as f32 / paper.max(32) as f32;
        let value =
            (255. - ((1. - normalized - 0.035) * (255. / 0.60)).round()).clamp(0., 255.) as u8;
        *pixel = Rgba([value, value, value, 255]);
    }
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct Model {
    pub quadratic: f32,
    pub linear: f32,
}
impl Model {
    fn offset(self, u: f32) -> f32 {
        let x = u - 0.5;
        self.quadratic * x * x + self.linear * x
    }
}

fn displacement(
    reference: &[f64],
    profile: &[f64],
    first: usize,
    last: usize,
    limit: i32,
) -> Option<f64> {
    let mut best = 0.65;
    let mut shift = 0_i32;
    for d in -limit..=limit {
        let (mut xy, mut xx, mut yy, mut sx, mut sy, mut count) = (0., 0., 0., 0., 0., 0.);
        for (y, &x) in reference.iter().enumerate().take(last).skip(first) {
            let yd = y as i32 + d;
            if yd < 0 || yd >= profile.len() as i32 {
                continue;
            }
            let v = profile[yd as usize];
            xy += x * v;
            xx += x * x;
            yy += v * v;
            sx += x;
            sy += v;
            count += 1.;
        }
        let (vx, vy) = (xx - sx * sx / count, yy - sy * sy / count);
        if vx < count || vy < count {
            continue;
        }
        let correlation = (xy - sx * sy / count) / (vx * vy).sqrt();
        if correlation > best + 0.0001
            || ((correlation - best).abs() < 0.0001 && d.abs() < shift.abs())
        {
            best = correlation;
            shift = d;
        }
    }
    (best > 0.65).then_some(shift as f64)
}

pub(crate) fn estimate(source: &RgbaImage) -> Option<Model> {
    let scale = (800. / source.width().max(source.height()) as f32).min(1.);
    let width = (source.width() as f32 * scale).round() as u32;
    let height = (source.height() as f32 * scale).round() as u32;
    if width < 180 || height < 240 {
        return None;
    }
    let sample =
        image::imageops::resize(source, width, height, image::imageops::FilterType::Triangle);
    let gray: Vec<_> = sample.pixels().map(luminance).collect();
    let (width, height) = (width as usize, height as usize);
    let mut profiles = vec![vec![0.; height]; 9];
    for (strip, profile) in profiles.iter_mut().enumerate() {
        let left = (width as f32 * (0.05 + 0.90 * strip as f32 / 9.)).round() as usize;
        let right = (width as f32 * (0.05 + 0.90 * (strip + 1) as f32 / 9.)).round() as usize;
        for y in 2..height - 2 {
            let mut ink = 0.;
            for x in left..right {
                ink += (gray[y * width + x] as i32 - gray[(y - 2) * width + x] as i32 - 6).max(0)
                    as f64;
            }
            profile[y] = ink / (right - left).max(1) as f64;
        }
        let mut smoothed = profile.clone();
        for y in 1..height - 1 {
            smoothed[y] = (profile[y - 1] + 2. * profile[y] + profile[y + 1]) / 4.;
        }
        *profile = smoothed;
    }
    let (mut shifts, mut valid) = ([0.; 9], [false; 9]);
    let mut accepted = 0;
    let margin = 12.max(height / 10);
    let limit = 4.max((height as f32 * 0.035).round() as i32);
    for strip in 0..9 {
        let top = displacement(&profiles[4], &profiles[strip], margin, height / 2, limit);
        let bottom = displacement(
            &profiles[4],
            &profiles[strip],
            height / 2,
            height - margin,
            limit,
        );
        if let (Some(top), Some(bottom)) = (top, bottom)
            && (top - bottom).abs() <= 2.
        {
            shifts[strip] = (top + bottom) / 2.;
            valid[strip] = true;
            accepted += 1;
        }
    }
    if accepted < 7 {
        return None;
    }
    let (mut s4, mut s3, mut s2, mut y2, mut y1) = (0., 0., 0., 0., 0.);
    for strip in 0..9 {
        if valid[strip] {
            let x = 0.05 + 0.90 * (strip as f64 + 0.5) / 9. - 0.5;
            s4 += x * x * x * x;
            s3 += x * x * x;
            s2 += x * x;
            y2 += x * x * shifts[strip];
            y1 += x * shifts[strip];
        }
    }
    let determinant = s4 * s2 - s3 * s3;
    if determinant < 0.000001 {
        return None;
    }
    let (a, b) = (
        (y2 * s2 - y1 * s3) / determinant,
        (y1 * s4 - y2 * s3) / determinant,
    );
    if a.abs() * 0.25 < height as f64 * 0.003
        || b.abs() > a.abs() * 0.35
        || a.abs() * 0.25 + b.abs() * 0.5 > height as f64 * 0.035
    {
        return None;
    }
    let mut squared = 0.;
    for strip in 0..9 {
        if valid[strip] {
            let x = 0.05 + 0.90 * (strip as f64 + 0.5) / 9. - 0.5;
            let error = shifts[strip] - a * x * x - b * x;
            if error.abs() > 2. {
                return None;
            }
            squared += error * error;
        }
    }
    if (squared / accepted as f64).sqrt() > 1.2 {
        return None;
    }
    Some(Model {
        quadratic: (a / height as f64) as f32,
        linear: (b / height as f64) as f32,
    })
}

// Same 32×64 forward mesh as Android's drawBitmapMesh. Rasterize its triangles
// with inverse barycentric coordinates, bilinear source sampling, fixed borders.
pub(crate) fn flatten(source: &RgbaImage, model: Model) -> RgbaImage {
    let (w, h) = (source.width() as f32, source.height() as f32);
    let mut result = RgbaImage::from_pixel(source.width(), source.height(), Rgba([255; 4]));
    let vertex = |column: usize, row: usize| {
        let u = column as f32 / 32.;
        let v = row as f32 / 64.;
        let taper = (v.min(1. - v) / 0.08).min(1.);
        ([u * w, (v - model.offset(u) * taper) * h], [u * w, v * h])
    };
    for row in 0..64 {
        for column in 0..32 {
            let a = vertex(column, row);
            let b = vertex(column + 1, row);
            let c = vertex(column, row + 1);
            let d = vertex(column + 1, row + 1);
            for triangle in [[a, b, c], [b, d, c]] {
                let [a, b, c] = triangle;
                let cross = |x: [f32; 2], y: [f32; 2]| x[0] * y[1] - x[1] * y[0];
                let sub = |x: [f32; 2], y: [f32; 2]| [x[0] - y[0], x[1] - y[1]];
                let det = cross(sub(b.0, a.0), sub(c.0, a.0));
                if det.abs() < 0.00001 {
                    continue;
                }
                let min_x = a.0[0].min(b.0[0]).min(c.0[0]).floor().max(0.) as u32;
                let max_x = a.0[0].max(b.0[0]).max(c.0[0]).ceil().min(w) as u32;
                let min_y = a.0[1].min(b.0[1]).min(c.0[1]).floor().max(0.) as u32;
                let max_y = a.0[1].max(b.0[1]).max(c.0[1]).ceil().min(h) as u32;
                for y in min_y..max_y {
                    for x in min_x..max_x {
                        let p = sub([x as f32 + 0.5, y as f32 + 0.5], a.0);
                        let ub = cross(p, sub(c.0, a.0)) / det;
                        let uc = cross(sub(b.0, a.0), p) / det;
                        let ua = 1. - ub - uc;
                        if ua >= -0.00001 && ub >= -0.00001 && uc >= -0.00001 {
                            result.put_pixel(
                                x,
                                y,
                                sample(
                                    source,
                                    ua * a.1[0] + ub * b.1[0] + uc * c.1[0] - 0.5,
                                    ua * a.1[1] + ub * b.1[1] + uc * c.1[1] - 0.5,
                                ),
                            );
                        }
                    }
                }
            }
        }
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bowed_lines(quadratic: f32, linear: f32) -> RgbaImage {
        let mut page = RgbaImage::from_pixel(600, 800, Rgba([240, 240, 240, 255]));
        for baseline in [85, 143, 211, 267, 319, 381, 444, 517, 581, 642, 709] {
            for x in 20..580 {
                let u = x as f32 / 600. - 0.5;
                let shift = quadratic * u * u + linear * u;
                let y = (baseline as f32 + shift).round() as u32;
                for dy in 0..4 {
                    page.put_pixel(x, y + dy, Rgba([30, 30, 30, 255]));
                }
            }
        }
        page
    }

    #[test]
    fn perspective_crop_has_independent_corner_order_and_bounds() {
        let page = RgbaImage::from_fn(200, 100, |x, y| match (x < 100, y < 50) {
            (true, true) => Rgba([220, 30, 40, 255]),
            (false, true) => Rgba([30, 40, 220, 255]),
            (true, false) => Rgba([30, 190, 40, 255]),
            (false, false) => Rgba([230, 190, 30, 255]),
        });
        let crop = crop_points("[0.1,0.2,0.9,0.1,0.8,0.9,0.2,0.8]")
            .unwrap()
            .unwrap();
        let output = perspective(&page, crop, 160).unwrap();
        assert!(output.width() <= 160 && output.height() <= 160);
        let (w, h) = output.dimensions();
        assert_eq!(*output.get_pixel(5, 5), Rgba([220, 30, 40, 255]));
        assert_eq!(*output.get_pixel(w - 6, 5), Rgba([30, 40, 220, 255]));
        assert_eq!(*output.get_pixel(w - 6, h - 6), Rgba([230, 190, 30, 255]));
        assert_eq!(*output.get_pixel(5, h - 6), Rgba([30, 190, 40, 255]));
        assert!(crop_points("[0,0,1,1,1,0,0,1]").is_err());
        assert!(crop_points("[0,0,0.01,0,0.01,0.01,0,0.01]").is_err());
        assert!(crop_points("[0,0,1,0,1,1,0,1]").unwrap().is_none());
        assert!(crop_points("[\"0.1\",0.1,0.9,0.1,0.9,0.9,0.1,0.9]").is_ok());
    }

    #[test]
    fn enhancement_preserves_gray_strokes_and_exact_color_matrix() {
        let mut medium = RgbaImage::from_pixel(1, 1, Rgba([100, 180, 240, 255]));
        enhance(&mut medium, "medium").unwrap();
        assert_eq!(medium.get_pixel(0, 0).0, [115, 203, 255, 255]);
        let mut bright = RgbaImage::from_pixel(1, 1, Rgba([100, 180, 240, 255]));
        enhance(&mut bright, "bright").unwrap();
        assert_eq!(bright.get_pixel(0, 0).0, [136, 230, 255, 255]);
        let mut paper = RgbaImage::from_pixel(200, 300, Rgba([200, 200, 200, 255]));
        for y in 90..94 {
            for x in 30..170 {
                paper.put_pixel(x, y, Rgba([0, 0, 0, 255]));
            }
        }
        for x in 30..170 {
            paper.put_pixel(x, 94, Rgba([180, 180, 180, 255]));
        }
        enhance(&mut paper, "black_white").unwrap();
        assert_eq!(paper.get_pixel(50, 20).0, [255; 4]);
        assert_eq!(paper.get_pixel(50, 91).0, [0, 0, 0, 255]);
        assert_eq!(paper.get_pixel(50, 94).0, [227, 227, 227, 255]);
    }

    #[test]
    fn conservative_flatten_estimates_curvature_and_keeps_borders() {
        let source = bowed_lines(80., 0.);
        let model = estimate(&source).expect("synthetic bowed baselines");
        assert!((model.quadratic - 0.1).abs() < 0.02, "{model:?}");
        assert!(model.linear.abs() < 0.01, "{model:?}");
        let output = flatten(&source, model);
        for x in [40, 100, 300, 500, 560] {
            let dark: Vec<_> = (75..115)
                .filter(|y| output.get_pixel(x, *y)[0] < 100)
                .collect();
            assert!(!dark.is_empty());
            assert!((dark[0] as i32 - 85).abs() <= 2, "x={x}, baseline={dark:?}");
        }
        assert_eq!(output.get_pixel(0, 0), source.get_pixel(0, 0));
        assert_eq!(output.get_pixel(599, 799), source.get_pixel(599, 799));
        assert!(estimate(&bowed_lines(0., 0.)).is_none());
        assert!(estimate(&bowed_lines(80., 60.)).is_none());
        assert!(estimate(&RgbaImage::from_pixel(600, 800, Rgba([255; 4]))).is_none());
        assert!(estimate(&RgbaImage::from_pixel(100, 100, Rgba([255; 4]))).is_none());
    }
}
