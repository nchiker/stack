package org.stackdocs.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/** Conservative vertical dewarping of gently bowed, horizontal text; no OCR or model. */
final class PageFlattening {
    private static final int STRIPS = 9;
    private PageFlattening() {}

    static final class Model {
        // Normalized vertical displacement, relative to the central text strip.
        final float quadratic, linear;
        Model(float quadratic, float linear) { this.quadratic = quadratic; this.linear = linear; }
        float offset(float u) { float x = u - .5f; return quadratic * x * x + linear * x; }
    }

    static Model estimate(Bitmap source) {
        float scale = Math.min(1, 800f / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.round(source.getWidth() * scale), height = Math.round(source.getHeight() * scale);
        if (width < 180 || height < 240) return null;
        Bitmap sample = Bitmap.createScaledBitmap(source, width, height, true);
        int[] gray = new int[width * height];
        try { sample.getPixels(gray, 0, width, 0, 0, width, height); }
        finally { if (sample != source) sample.recycle(); }
        for (int i = 0; i < gray.length; i++) {
            int p = gray[i]; gray[i] = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
        }
        double[][] profiles = new double[STRIPS][height];
        for (int strip = 0; strip < STRIPS; strip++) {
            int left = Math.round(width * (.05f + .90f * strip / STRIPS));
            int right = Math.round(width * (.05f + .90f * (strip + 1) / STRIPS));
            for (int y = 2; y < height - 2; y++) {
                double ink = 0;
                // Lower stroke edges align with baselines more consistently than
                // letter tops, whose heights vary with capitals and ascenders.
                for (int x = left; x < right; x++) ink += Math.max(0, gray[y * width + x] - gray[(y - 2) * width + x] - 6);
                profiles[strip][y] = ink / Math.max(1, right - left);
            }
            double[] smoothed = profiles[strip].clone();
            for (int y = 1; y < height - 1; y++) smoothed[y] = (profiles[strip][y - 1] + 2 * profiles[strip][y] + profiles[strip][y + 1]) / 4;
            profiles[strip] = smoothed;
        }
        double[] shifts = new double[STRIPS]; boolean[] valid = new boolean[STRIPS];
        int accepted = 0, margin = Math.max(12, height / 10), limit = Math.max(4, Math.round(height * .035f));
        for (int strip = 0; strip < STRIPS; strip++) {
            double top = displacement(profiles[STRIPS / 2], profiles[strip], margin, height / 2, limit);
            double bottom = displacement(profiles[STRIPS / 2], profiles[strip], height / 2, height - margin, limit);
            if (Double.isNaN(top) || Double.isNaN(bottom) || Math.abs(top - bottom) > 2) continue;
            shifts[strip] = (top + bottom) / 2; valid[strip] = true; accepted++;
        }
        if (accepted < 7) return null;
        double s4 = 0, s3 = 0, s2 = 0, y2 = 0, y1 = 0;
        for (int strip = 0; strip < STRIPS; strip++) if (valid[strip]) {
            double x = .05 + .90 * (strip + .5) / STRIPS - .5;
            s4 += x * x * x * x; s3 += x * x * x; s2 += x * x;
            y2 += x * x * shifts[strip]; y1 += x * shifts[strip];
        }
        double determinant = s4 * s2 - s3 * s3;
        if (determinant < .000001) return null;
        double a = (y2 * s2 - y1 * s3) / determinant, b = (y1 * s4 - y2 * s3) / determinant;
        if (Math.abs(a) * .25 < height * .003 || Math.abs(b) > Math.abs(a) * .35) return null;
        if (Math.abs(a) * .25 + Math.abs(b) * .5 > height * .035) return null;
        double squaredError = 0;
        for (int strip = 0; strip < STRIPS; strip++) if (valid[strip]) {
            double x = .05 + .90 * (strip + .5) / STRIPS - .5;
            double error = shifts[strip] - a * x * x - b * x;
            if (Math.abs(error) > 2) return null;
            squaredError += error * error;
        }
        if (Math.sqrt(squaredError / accepted) > 1.2) return null;
        return new Model((float) (a / height), (float) (b / height));
    }

    private static double displacement(double[] reference, double[] profile, int first, int last, int limit) {
        double best = .65; int shift = 0;
        for (int d = -limit; d <= limit; d++) {
            double xy = 0, xx = 0, yy = 0, sx = 0, sy = 0; int count = 0;
            for (int y = first; y < last; y++) {
                if (y + d < 0 || y + d >= profile.length) continue;
                double x = reference[y], v = profile[y + d];
                xy += x * v; xx += x * x; yy += v * v; sx += x; sy += v; count++;
            }
            double vx = xx - sx * sx / count, vy = yy - sy * sy / count;
            if (vx < count || vy < count) continue;
            double correlation = (xy - sx * sy / count) / Math.sqrt(vx * vy);
            if (correlation > best + .0001 || (Math.abs(correlation - best) < .0001 && Math.abs(d) < Math.abs(shift))) {
                best = correlation; shift = d;
            }
        }
        return best > .65 ? shift : Double.NaN;
    }

    /** Small mesh keeps memory bounded; page borders stay fixed and mapping stays monotonic. */
    static Bitmap apply(Bitmap source, Model model) {
        int width = source.getWidth(), height = source.getHeight(), columns = 32, rows = 64;
        float[] mesh = new float[(columns + 1) * (rows + 1) * 2]; int index = 0;
        for (int row = 0; row <= rows; row++) {
            float v = row / (float) rows;
            float taper = Math.min(1, Math.min(v, 1 - v) / .08f);
            for (int column = 0; column <= columns; column++) {
                float u = column / (float) columns;
                mesh[index++] = u * width;
                mesh[index++] = (v - model.offset(u) * taper) * height;
            }
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result); canvas.drawColor(Color.WHITE);
        canvas.drawBitmapMesh(source, columns, rows, mesh, 0, null, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        return result;
    }
}
