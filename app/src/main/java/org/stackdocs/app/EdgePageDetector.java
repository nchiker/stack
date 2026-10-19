package org.stackdocs.app;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Independent, bounded-resolution edge/line detector for a flat paper quadrilateral. */
final class EdgePageDetector {
    private static final int ANGLES = 180;
    private final int width, height, radius;
    private final int[] gray, strength;
    private final byte[] direction;
    private final float[] cosine = new float[ANGLES], sine = new float[ANGLES];
    private int threshold;

    private EdgePageDetector(Bitmap bitmap) {
        float scale = Math.min(1, 320f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        radius = (int) Math.ceil(Math.hypot(width, height));
        int[] pixels = new int[width * height];
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, width, height, true);
        try { sample.getPixels(pixels, 0, width, 0, 0, width, height); }
        finally { if (sample != bitmap) sample.recycle(); }
        int[] raw = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            raw[i] = (((pixel >> 16) & 255) * 77 + ((pixel >> 8) & 255) * 150 + (pixel & 255) * 29) >> 8;
        }
        gray = new int[pixels.length];
        strength = new int[pixels.length];
        direction = new byte[pixels.length];
        // Suppress small sensor/compression noise before looking for sustained page edges.
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int total = 0, count = 0;
            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                int sx = x + dx, sy = y + dy;
                if (sx >= 0 && sx < width && sy >= 0 && sy < height) { total += raw[sy * width + sx]; count++; }
            }
            gray[y * width + x] = total / count;
        }
        for (int angle = 0; angle < ANGLES; angle++) {
            cosine[angle] = (float) Math.cos(Math.toRadians(angle));
            sine[angle] = (float) Math.sin(Math.toRadians(angle));
        }
    }

    static float[] suggest(Bitmap bitmap, List<float[]> regions) {
        if (Math.min(bitmap.getWidth(), bitmap.getHeight()) < 32) return null;
        return new EdgePageDetector(bitmap).detect(regions);
    }

    private float[] detect(List<float[]> regions) {
        int[] histogram = new int[512];
        for (int y = 1; y < height - 1; y++) for (int x = 1; x < width - 1; x++) {
            int p = y * width + x;
            int gx = -gray[p - width - 1] + gray[p - width + 1] - 2 * gray[p - 1]
                    + 2 * gray[p + 1] - gray[p + width - 1] + gray[p + width + 1];
            int gy = -gray[p - width - 1] - 2 * gray[p - width] - gray[p - width + 1]
                    + gray[p + width - 1] + 2 * gray[p + width] + gray[p + width + 1];
            int magnitude = Math.abs(gx) + Math.abs(gy);
            strength[p] = magnitude;
            histogram[Math.min(511, magnitude / 4)]++;
            if (magnitude >= 24) {
                int angle = (int) Math.round(Math.toDegrees(Math.atan2(gy, gx)));
                direction[p] = (byte) ((angle % ANGLES + ANGLES) % ANGLES);
            }
        }
        int percentile = 0, count = 0;
        while (percentile < 511 && count < (width - 2) * (height - 2) * .92) count += histogram[percentile++];
        // Dense text must not raise the cutoff above a low-contrast paper edge.
        threshold = Math.max(24, Math.min(64, percentile * 4));
        int[][] votes = new int[ANGLES][radius * 2 + 1];
        for (int y = 1; y < height - 1; y++) for (int x = 1; x < width - 1; x++) {
            int p = y * width + x;
            if (strength[p] < threshold) continue;
            int normal = direction[p] & 255;
            int weight = 1 + Math.min(3, strength[p] / 300);
            for (int delta = -6; delta <= 6; delta++) {
                int angle = (normal + delta + ANGLES) % ANGLES;
                int distance = Math.round(x * cosine[angle] + y * sine[angle]);
                votes[angle][distance + radius] += weight;
            }
        }
        List<Line> horizontal = new ArrayList<>(), vertical = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int best = 0, angle = 0, bin = 0;
            for (int a = 0; a < ANGLES; a++) for (int r = 0; r < votes[a].length; r++) {
                if (votes[a][r] > best) { best = votes[a][r]; angle = a; bin = r; }
            }
            if (best < Math.max(16, Math.min(width, height) / 6)) break;
            Line line = new Line(cosine[angle], sine[angle], bin - radius, angle);
            (angle >= 45 && angle < 135 ? horizontal : vertical).add(line);
            for (int da = -6; da <= 6; da++) for (int dr = -6; dr <= 6; dr++) {
                int a = angle + da, r = bin - radius + dr;
                if (a < 0 || a >= ANGLES) { a = (a + ANGLES) % ANGLES; r = -r; }
                if (r >= -radius && r <= radius) votes[a][r + radius] = 0;
            }
        }
        float[] best = null;
        double bestScore = 0;
        for (float[] region : regions) {
            region = refine(region);
            double regionScore = score(region, area(region));
            if (regionScore > bestScore) { best = region; bestScore = regionScore; }
        }
        for (int a = 0; a < horizontal.size(); a++) for (int b = a + 1; b < horizontal.size(); b++) {
            if (!similar(horizontal.get(a), horizontal.get(b))) continue;
            for (int c = 0; c < vertical.size(); c++) for (int d = c + 1; d < vertical.size(); d++) {
                if (!similar(vertical.get(c), vertical.get(d))) continue;
                float[] points = quadrilateral(horizontal.get(a), horizontal.get(b), vertical.get(c), vertical.get(d));
                if (points == null) continue;
                double area = area(points);
                if (area < .15 || area > .97) continue;
                points = refine(points);
                double score = score(points, area(points));
                if (score > bestScore) { bestScore = score; best = points; }
            }
        }
        return best == null ? null : fitBoundary(best);
    }

    /** Fit the accepted sides to local transitions, removing Hough angle quantization. */
    private float[] fitBoundary(float[] points) {
        Line[] sides = new Line[4];
        for (int side = 0; side < 4; side++) {
            int next = (side + 1) % 4;
            float ax = points[side * 2] * (width - 1), ay = points[side * 2 + 1] * (height - 1);
            float bx = points[next * 2] * (width - 1), by = points[next * 2 + 1] * (height - 1);
            float length = (float) Math.hypot(bx - ax, by - ay);
            float tx = (bx - ax) / length, ty = (by - ay) / length, nx = -ty, ny = tx;
            int polarity = edgeContrast(ax, ay, bx, by, nx, ny, 0) < 0 ? -1 : 1;
            float[] along = new float[25], offsets = new float[25]; int count = 0;
            for (int sample = 0; sample < 25; sample++) {
                float s = length * (.06f + .88f * sample / 24);
                float best = 0, offset = 0;
                for (int d = -8; d <= 8; d++) {
                    float x = ax + tx * s + nx * d, y = ay + ty * s + ny * d;
                    int in = light(x + nx * 2, y + ny * 2), out = light(x - nx * 2, y - ny * 2);
                    if (in < 0 || out < 0) continue;
                    float value = polarity * (in - out) - Math.abs(d);
                    if (value > best) { best = value; offset = d; }
                }
                if (best >= 10) { along[count] = s; offsets[count++] = offset; }
            }
            double slope = 0, intercept = 0;
            for (int pass = 0; pass < 2 && count >= 16; pass++) {
                double sx = 0, sy = 0, sxx = 0, sxy = 0; int used = 0;
                for (int i = 0; i < count; i++) {
                    if (pass == 1 && Math.abs(offsets[i] - intercept - slope * along[i]) > 2) continue;
                    sx += along[i]; sy += offsets[i]; sxx += along[i] * along[i]; sxy += along[i] * offsets[i]; used++;
                }
                double denominator = used * sxx - sx * sx;
                if (used < 16 || denominator < 1) break;
                slope = (used * sxy - sx * sy) / denominator;
                intercept = (sy - slope * sx) / used;
            }
            float fx = nx - tx * (float) slope, fy = ny - ty * (float) slope;
            float norm = (float) Math.hypot(fx, fy);
            sides[side] = new Line(fx / norm, fy / norm, (fx * ax + fy * ay + (float) intercept) / norm, 0);
        }
        float[] fitted = new float[8];
        for (int corner = 0; corner < 4; corner++) {
            if (!intersection(sides[(corner + 3) % 4], sides[corner], fitted, corner * 2)) return points;
            fitted[corner * 2] /= width - 1; fitted[corner * 2 + 1] /= height - 1;
        }
        try { fitted = PageGeometry.checked(fitted); }
        catch (IllegalArgumentException ignored) { return points; }
        double after = area(fitted);
        return after > area(points) * .8 && after < area(points) * 1.2 && score(fitted, after) > 0 ? fitted : points;
    }

    /** Move coarse Hough lines onto the strongest nearby paper/background transition. */
    private float[] refine(float[] points) {
        Line[] sides = new Line[4];
        for (int side = 0; side < 4; side++) {
            int next = (side + 1) % 4;
            float ax = points[side * 2] * (width - 1), ay = points[side * 2 + 1] * (height - 1);
            float bx = points[next * 2] * (width - 1), by = points[next * 2 + 1] * (height - 1);
            float length = (float) Math.hypot(bx - ax, by - ay);
            float nx = -(by - ay) / length, ny = (bx - ax) / length;
            int polarity = edgeContrast(ax, ay, bx, by, nx, ny, 0) < -5 ? -1 : 1;
            int bestOffset = 0;
            int bestContrast = polarity * edgeContrast(ax, ay, bx, by, nx, ny, 0);
            for (int offset = -8; offset <= 8; offset++) {
                int contrast = polarity * edgeContrast(ax, ay, bx, by, nx, ny, offset);
                if (contrast > bestContrast + 2 || (contrast == bestContrast && Math.abs(offset) < Math.abs(bestOffset))) {
                    bestContrast = contrast;
                    bestOffset = offset;
                }
            }
            sides[side] = new Line(nx, ny, nx * ax + ny * ay + bestOffset, 0);
        }
        float[] refined = new float[8];
        for (int corner = 0; corner < 4; corner++) {
            if (!intersection(sides[(corner + 3) % 4], sides[corner], refined, corner * 2)) return points;
            refined[corner * 2] /= width - 1;
            refined[corner * 2 + 1] /= height - 1;
        }
        try { refined = PageGeometry.checked(refined); }
        catch (IllegalArgumentException ignored) { return points; }
        double before = area(points), after = area(refined);
        return after >= before * .75 && after <= before * 1.25 && score(refined, after) > 0 ? refined : points;
    }

    private int edgeContrast(float ax, float ay, float bx, float by, float nx, float ny, int offset) {
        int[] differences = new int[15];
        int valid = 0;
        for (int sample = 0; sample < differences.length; sample++) {
            float t = .10f + .80f * sample / (differences.length - 1);
            float x = ax + (bx - ax) * t + nx * offset, y = ay + (by - ay) * t + ny * offset;
            int inside = light(x + nx * 2, y + ny * 2), outside = light(x - nx * 2, y - ny * 2);
            if (inside >= 0 && outside >= 0) differences[valid++] = inside - outside;
        }
        if (valid < 7) return 0;
        Arrays.sort(differences, 0, valid);
        return differences[valid / 2];
    }

    private static boolean similar(Line a, Line b) {
        int delta = Math.abs(a.angle - b.angle);
        return Math.min(delta, ANGLES - delta) <= 50;
    }

    private float[] quadrilateral(Line top, Line bottom, Line left, Line right) {
        float[] points = new float[8];
        if (!intersection(top, left, points, 0) || !intersection(top, right, points, 2)
                || !intersection(bottom, right, points, 4) || !intersection(bottom, left, points, 6)) return null;
        float centerX = 0, centerY = 0;
        for (int i = 0; i < 4; i++) {
            float x = points[i * 2] / (width - 1), y = points[i * 2 + 1] / (height - 1);
            if (x < 0 || x > 1 || y < 0 || y > 1) return null;
            points[i * 2] = x; points[i * 2 + 1] = y;
            centerX += x / 4; centerY += y / 4;
        }
        for (int i = 0; i < 3; i++) for (int j = i + 1; j < 4; j++) {
            if (Math.atan2(points[i * 2 + 1] - centerY, points[i * 2] - centerX)
                    > Math.atan2(points[j * 2 + 1] - centerY, points[j * 2] - centerX)) {
                float x = points[i * 2], y = points[i * 2 + 1];
                points[i * 2] = points[j * 2]; points[i * 2 + 1] = points[j * 2 + 1];
                points[j * 2] = x; points[j * 2 + 1] = y;
            }
        }
        int start = 0;
        for (int i = 1; i < 4; i++) if (points[i * 2] + points[i * 2 + 1] < points[start * 2] + points[start * 2 + 1]) start = i;
        float[] ordered = new float[8];
        for (int i = 0; i < 4; i++) { ordered[i * 2] = points[(i + start) % 4 * 2]; ordered[i * 2 + 1] = points[(i + start) % 4 * 2 + 1]; }
        try { return PageGeometry.checked(ordered); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static boolean intersection(Line a, Line b, float[] result, int position) {
        float determinant = a.x * b.y - b.x * a.y;
        if (Math.abs(determinant) < .25f) return false;
        result[position] = (a.distance * b.y - b.distance * a.y) / determinant;
        result[position + 1] = (a.x * b.distance - b.x * a.distance) / determinant;
        return true;
    }

    private double score(float[] points, double area) {
        double coverage = 0, contrast = 0;
        int lighter = 0, darker = 0;
        for (int side = 0; side < 4; side++) {
            int next = (side + 1) % 4;
            float ax = points[side * 2] * (width - 1), ay = points[side * 2 + 1] * (height - 1);
            float bx = points[next * 2] * (width - 1), by = points[next * 2 + 1] * (height - 1);
            float length = (float) Math.hypot(bx - ax, by - ay);
            int samples = Math.max(16, Math.min(64, (int) length / 4)), supported = 0;
            for (int i = 0; i < samples; i++) {
                float t = .04f + .92f * i / (samples - 1);
                int x = Math.round(ax + (bx - ax) * t), y = Math.round(ay + (by - ay) * t);
                boolean edge = false;
                for (int dy = -2; dy <= 2 && !edge; dy++) for (int dx = -2; dx <= 2; dx++) {
                    int sx = x + dx, sy = y + dy;
                    if (sx >= 0 && sx < width && sy >= 0 && sy < height && strength[sy * width + sx] >= threshold) { edge = true; break; }
                }
                if (edge) supported++;
            }
            double support = (double) supported / samples;
            if (support < .80) return 0;
            coverage += support / 4;
            float nx = -(by - ay) / length, ny = (bx - ax) / length;
            int[] difference = new int[9];
            int valid = 0;
            for (int i = 0; i < 9; i++) {
                float t = .15f + .7f * i / 8;
                float x = ax + (bx - ax) * t, y = ay + (by - ay) * t;
                int inside = light(x + nx * 4, y + ny * 4), outside = light(x - nx * 4, y - ny * 4);
                if (inside >= 0 && outside >= 0) difference[valid++] = inside - outside;
            }
            if (valid < 3) continue;
            Arrays.sort(difference, 0, valid);
            int median = difference[valid / 2];
            int consistent = 0;
            for (int i = 0; i < valid; i++) {
                if ((median > 5 && difference[i] > 5) || (median < -5 && difference[i] < -5)) consistent++;
            }
            // Partial edges can outline one leg of an L-shaped object, or join a
            // table edge to a sheet. Demand sustained paper/background separation.
            if (consistent < Math.ceil(valid * .80)) return 0;
            if (median > 5) lighter++;
            if (median < -5) darker++;
            contrast += Math.min(1, Math.abs(median) / 48.0) / 4;
        }
        // Four supported lines alone could describe an internal table or a text block.
        if (coverage < .65 || Math.max(lighter, darker) < 3) return 0;
        // A rectangle surrounding both paper and a nearby object can have strong edges.
        // Reject broad regions of the outside surface within the proposed sheet.
        double fill = paperFill(points, lighter >= darker);
        if (fill < .88) return 0;
        return area * (.55 + coverage * .30 + contrast * .15) * fill * fill;
    }

    private double paperFill(float[] points, boolean lightPaper) {
        int[] values = new int[400];
        int count = 0;
        for (int row = 0; row < 20; row++) {
            float v = .05f + .90f * row / 19;
            float lx = points[0] + (points[6] - points[0]) * v;
            float ly = points[1] + (points[7] - points[1]) * v;
            float rx = points[2] + (points[4] - points[2]) * v;
            float ry = points[3] + (points[5] - points[3]) * v;
            for (int column = 0; column < 20; column++) {
                float u = .05f + .90f * column / 19;
                int value = light((lx + (rx - lx) * u) * (width - 1), (ly + (ry - ly) * u) * (height - 1));
                if (value >= 0) values[count++] = lightPaper ? value : 255 - value;
            }
        }
        if (count == 0) return 0;
        Arrays.sort(values, 0, count);
        // In an oversized crop the outside surface can be the majority. Refer to
        // the paper's upper quartile, not a median that may describe the table.
        float floor = values[count * 3 / 4] * .65f;
        int paper = 0;
        for (int i = 0; i < count; i++) if (values[i] >= floor) paper++;
        return (double) paper / count;
    }

    private int light(float x, float y) {
        int sx = Math.round(x), sy = Math.round(y);
        return sx < 0 || sx >= width || sy < 0 || sy >= height ? -1 : gray[sy * width + sx];
    }

    private static double area(float[] points) {
        double area = 0;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            area += points[i * 2] * points[next * 2 + 1] - points[next * 2] * points[i * 2 + 1];
        }
        return area / 2;
    }

    private static final class Line {
        final float x, y, distance;
        final int angle;
        Line(float x, float y, float distance, int angle) { this.x = x; this.y = y; this.distance = distance; this.angle = angle; }
    }
}
