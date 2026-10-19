package org.stackdocs.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Debug;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

/** Authored, deterministic fixtures; no camera, personal library, or reference assets. */
final class ScannerQualityChecks {
    private static final float[] PAGE = {.18f, .10f, .84f, .15f, .78f, .89f, .12f, .83f};

    static void run(Context context, boolean baseline, Consumer<String> report) throws Exception {
        report.accept("Synthetic artifacts: " + context.getCacheDir());
        boundaries(context, baseline, report);
        cleanup(context, baseline, report);
        sampling(context, report);
        flattening(context, report);
    }

    private static void boundaries(Context context, boolean baseline, Consumer<String> report) throws Exception {
        int detected = 0;
        double worst = 0;
        long start = SystemClock.elapsedRealtime();
        for (int scene = 0; scene < 8; scene++) {
            float[] truth = scene == 6 ? new float[]{.32f, .08f, .65f, .12f, .64f, .92f, .27f, .88f}
                    : scene == 7 ? new float[]{.32f, .08f, .91f, .40f, .64f, .92f, .05f, .59f} : PAGE;
            Bitmap source = scene(truth, scene);
            float[] found = BoundaryDetector.suggest(source);
            double error = found == null ? 1 : cornerError(found, truth);
            if (error <= .025) detected++;
            worst = Math.max(worst, error);
            report.accept(String.format(Locale.ROOT, "crop scene=%d maxCorner/diagonal=%.5f corners=%s", scene, error, Arrays.toString(found)));
            // A similar-brightness object touches the sheet in scene 4. Abstaining
            // is acceptable there; proposing a crop into that object is not.
            require(baseline || error <= .025 || (scene == 4 && found == null), "Crop scene " + scene + " exceeds 2.5% diagonal error");
            if (scene == 5) writePng(source, new File(context.getCacheDir(), "quality-background.png"));
            source.recycle();
        }
        for (int negative = 0; negative < 3; negative++) {
            Bitmap source = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
            source.eraseColor(negative == 0 ? Color.WHITE : Color.DKGRAY);
            Canvas canvas = new Canvas(source);
            Paint paint = new Paint(); paint.setColor(Color.WHITE);
            if (negative == 1) {
                canvas.drawRect(80, 80, 500, 240, paint);
                canvas.drawRect(80, 80, 210, 700, paint);
            } else if (negative == 2) canvas.drawCircle(300, 400, 230, paint);
            float[] proposal = BoundaryDetector.suggest(source);
            boolean rejected = proposal == null;
            report.accept("negative=" + negative + " rejected=" + rejected + " corners=" + Arrays.toString(proposal));
            require(baseline || rejected, "Unsupported shape must leave manual whole-frame crop available");
            source.recycle();
        }
        report.accept(String.format(Locale.ROOT, "boundary summary usable=%d/8 worst=%.5f elapsedMs=%d", detected, worst,
                SystemClock.elapsedRealtime() - start));
    }

    private static Bitmap scene(float[] corners, int scene) {
        int width = 800, height = 1000;
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            float u = x / (float) (width - 1), v = y / (float) (height - 1);
            boolean paper = inside(corners, u, v);
            int background = scene == 1 ? 205 : scene == 2 ? 120 : 45;
            int shade = scene == 2 ? Math.round(90 * u) : scene == 3 ? Math.round(100 * Math.max(0, 1 - u * 2)) : 0;
            int value = paper ? 235 - shade : background;
            if (scene == 4 && !paper && x > 610 && y > 90 && y < 850) value = 242;
            if (scene == 5 && !paper && (x / 40 + y / 50) % 2 == 0) value += 55;
            int noise = ((x * 13 + y * 37) % 9) - 4;
            value = Math.max(0, Math.min(255, value + noise));
            pixels[y * width + x] = Color.rgb(value, value, value);
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        Canvas canvas = new Canvas(bitmap);
        Matrix matrix = new Matrix(); float[] target = corners.clone();
        for (int i = 0; i < 4; i++) { target[i * 2] *= width - 1; target[i * 2 + 1] *= height - 1; }
        matrix.setPolyToPoly(new float[]{0, 0, 600, 0, 600, 800, 0, 800}, 0, target, 0, 4);
        canvas.concat(matrix);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.rgb(60, 60, 60));
        for (int row = 0; row < 20; row++) {
            paint.setTextSize(row % 3 == 0 ? 13 : 20);
            canvas.drawText("Synthetic invoice 0123456789 AaBb", 35, 65 + row * 33, paint);
        }
        return bitmap;
    }

    private static boolean inside(float[] p, float x, float y) {
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            if ((p[j * 2] - p[i * 2]) * (y - p[i * 2 + 1])
                    - (p[j * 2 + 1] - p[i * 2 + 1]) * (x - p[i * 2]) < 0) return false;
        }
        return true;
    }

    private static double cornerError(float[] actual, float[] expected) {
        double max = 0;
        for (int i = 0; i < 4; i++) max = Math.max(max, Math.hypot((actual[i * 2] - expected[i * 2]) * 800,
                (actual[i * 2 + 1] - expected[i * 2 + 1]) * 1000) / Math.hypot(800, 1000));
        return max;
    }

    private static void cleanup(Context context, boolean baseline, Consumer<String> report) throws Exception {
        for (int size : new int[]{800, 1600, 3200}) {
            int width = size * 3 / 4, height = size;
            int[] pixels = new int[width * height];
            // Blank paper with broad lighting gradient, hard shadow, texture and bright sensor outliers.
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int paper = 155 + 75 * x / width - (y > height / 2 ? 25 : 0);
                int noise = (x * 13 + y * 37) % 9 - 4;
                int value = (x * 31 + y * 17) % 997 == 0 ? 255 : paper + noise;
                pixels[y * width + x] = Color.rgb(value, value, value);
            }
            Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            source.setPixels(pixels, 0, width, 0, 0, width, height);
            Canvas canvas = new Canvas(source); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            float scale = size / 800f;
            canvas.scale(scale, scale);
            paint.setTextSize(12); paint.setColor(Color.rgb(50, 50, 50));
            canvas.drawText("Small text: 0123456789 AaBb", 35, 90, paint);
            // Low-contrast pencil-like strokes (roughly 90% of nearby paper luminance).
            paint.setColor(Color.rgb(151, 151, 151)); canvas.drawRect(80, 170, 180, 172, paint);
            paint.setColor(Color.rgb(75, 75, 75)); canvas.drawRect(80, 200, 180, 202, paint);
            int[] before = new int[pixels.length]; source.getPixels(before, 0, width, 0, 0, width, height);
            long start = SystemClock.elapsedRealtime();
            Bitmap result = PageEnhancement.blackAndWhite(source);
            long elapsed = SystemClock.elapsedRealtime() - start;
            int white = 0, total = 0;
            // Include the hard shadow boundary and outliers, exclude the authored text region.
            for (int y = height / 3; y < height - 20; y++) for (int x = 20; x < width - 20; x++) {
                if (Color.red(result.getPixel(x, y)) >= 250) white++;
                total++;
            }
            double clean = (double) white / total;
            int core = 0, retained = 0, grayEdges = 0;
            for (int y = Math.round(73 * scale); y < Math.round(94 * scale); y++) {
                for (int x = Math.round(35 * scale); x < Math.round(235 * scale); x++) {
                    int input = Color.red(source.getPixel(x, y)), output = Color.red(result.getPixel(x, y));
                    if (input < 100) { core++; if (output < 120) retained++; }
                    if (input >= 100 && output > 20 && output < 235) grayEdges++;
                }
            }
            require(core > 0 && retained >= core * .98, "Small text stroke cores lost");
            require(baseline || grayEdges > 10, "Continuous small-text edge tones lost");
            int faint = Color.red(result.getPixel(Math.round(120 * scale), Math.round(171 * scale)));
            int dark = Color.red(result.getPixel(Math.round(120 * scale), Math.round(201 * scale)));
            source.getPixels(pixels, 0, width, 0, 0, width, height);
            require(Arrays.equals(before, pixels), "Cleanup modified the source bitmap");
            require(result.getWidth() == width && result.getHeight() == height, "Cleanup changed sampling size");
            require(baseline || clean >= .98, "Paper cleanup below 98% near-white");
            require(baseline || (faint >= 120 && faint <= 235 && dark < 100), "Faint/dark strokes lost");
            report.accept(String.format(Locale.ROOT, "cleanup edge=%d nearWhite=%.5f faint=%d dark=%d elapsedMs=%d nativeHeapBytes=%d javaUsedBytes=%d",
                    size, clean, faint, dark, elapsed, Debug.getNativeHeapAllocatedSize(),
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()));
            report.accept("small text edge=" + size + " retainedCore=" + retained + "/" + core + " grayEdgePixels=" + grayEdges);
            if (size == 800) {
                writePng(source, new File(context.getCacheDir(), "quality-source.png"));
                writePng(result, new File(context.getCacheDir(), "quality-cleaned.png"));
            }
            source.recycle(); result.recycle();
        }
    }

    private static void sampling(Context context, Consumer<String> report) throws Exception {
        Bitmap source = Bitmap.createBitmap(2400, 3200, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.DKGRAY);
        Canvas canvas = new Canvas(source); Paint paint = new Paint(); paint.setColor(Color.WHITE);
        canvas.drawRect(600, 800, 1800, 2400, paint);
        paint.setColor(Color.BLACK);
        for (int x = 650; x < 1750; x += 8) canvas.drawRect(x, 900, x + 3, 1100, paint);
        File file = new File(context.getCacheDir(), "sampling.png"); writePng(source, file); source.recycle();
        byte[] original = ScannerChecks.bytes(file);
        PageState state = new PageState("synthetic", "source", 0, 0,
                new float[]{.25f, .25f, .75f, .25f, .75f, .75f, .25f, .75f}, "original");
        Bitmap result = PageRenderer.render(file, "image/png", state, 1600);
        require(result.getHeight() >= 1599 && result.getHeight() <= 1600, "Crop source detail was decoded too small");
        require(Color.red(result.getPixel(51, 150)) < 60 && Color.red(result.getPixel(55, 150)) > 200,
                "Three-pixel strokes and adjacent paper remain distinct after crop");
        require(Arrays.equals(original, ScannerChecks.bytes(file)), "Rendering changed source bytes");
        require(state.rotation == 0 && state.enhancement.equals("original") && state.crop[0] == .25f, "Rendering changed saved edits");
        report.accept("Crop sampling retains 3px lines at 1600px; source bytes and edit state unchanged");
        result.recycle();
    }

    private static void writePng(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "Fixture write failed");
        }
    }

    private static void flattening(Context context, Consumer<String> report) throws Exception {
        for (int bow : new int[]{14, -14}) {
            Bitmap source = bowedText(bow, false);
            int[] before = new int[600 * 800]; source.getPixels(before, 0, 600, 0, 0, 600, 800);
            long start = SystemClock.elapsedRealtime();
            PageFlattening.Model model = PageFlattening.estimate(source);
            require(model != null, "Gentle text bow must be detected: " + bow);
            Bitmap flat = PageFlattening.apply(source, model);
            double spreadBefore = baselineSpread(source), spreadAfter = baselineSpread(flat);
            require(spreadAfter <= 2 && spreadAfter < spreadBefore * .35, "Text bow was not straightened: " + spreadBefore + " -> " + spreadAfter + " a=" + model.quadratic + " b=" + model.linear);
            int[] after = new int[before.length]; source.getPixels(after, 0, 600, 0, 0, 600, 800);
            require(Arrays.equals(before, after), "Flattening changed original samples");
            require(flat.getWidth() == 600 && flat.getHeight() == 800, "Flattening changed page bounds");
            report.accept(String.format(Locale.ROOT, "flatten bow=%d baselineSpreadPx=%.3f->%.3f elapsedMs=%d a=%.5f b=%.5f",
                    bow, spreadBefore, spreadAfter, SystemClock.elapsedRealtime() - start, model.quadratic, model.linear));
            if (bow > 0) {
                writePng(source, new File(context.getCacheDir(), "quality-bowed.png"));
                writePng(flat, new File(context.getCacheDir(), "quality-flattened.png"));
                File original = new File(context.getCacheDir(), "bowed-original.png"); writePng(source, original);
                PageState state = new PageState("synthetic", "source", 0, 0, null, "original");
                require(state.unchanged() && !state.flattened(true).unchanged(), "Flatten must invalidate untouched PDF passthrough");
                Bitmap rendered = PageRenderer.render(original, "image/png", state.flattened(true), 800);
                require(baselineSpread(rendered) <= 2, "Renderer omitted flattening"); rendered.recycle();
                Bitmap restored = PageRenderer.render(original, "image/png", state.flattened(true).flattened(false), 800);
                require(baselineSpread(restored) == spreadBefore, "Turning off flattening did not restore the source geometry"); restored.recycle();
            }
            source.recycle(); flat.recycle();
        }
        for (int scenario = 0; scenario < 4; scenario++) {
            Bitmap source = bowedText(scenario == 0 ? 0 : scenario == 1 ? 60 : 14, scenario == 2);
            if (scenario == 3) source.eraseColor(Color.WHITE);
            require(PageFlattening.estimate(source) == null, "Ambiguous/flat/severe page must be left unchanged: " + scenario);
            source.recycle();
        }
        report.accept("Flatten abstains on straight text, severe bow, inconsistent curves and blank paper; reversible renderer checked");
    }

    /** Independent sine displacement, unlike the fitted quadratic model. */
    private static Bitmap bowedText(int bow, boolean inconsistent) {
        Bitmap flat = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE); Canvas canvas = new Canvas(flat);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(17);
        for (int row = 0; row < 12; row++) {
            String text = row % 2 == 0 ? "Invoice number 0123456789 Amount due today" : "Printed statement AaBbCc company reference";
            paint.setTextScaleX(1); paint.setTextScaleX(500 / paint.measureText(text));
            int baseline = 65 + row * 56 + row % 3 * 3;
            canvas.drawText(text, 45, baseline, paint);
            for (int x : new int[]{55, 300, 545}) canvas.drawRect(x, baseline + 3, x + 8, baseline + 5, paint);
        }
        int[] original = new int[600 * 800], curved = new int[original.length];
        flat.getPixels(original, 0, 600, 0, 0, 600, 800); flat.recycle();
        for (int y = 0; y < 800; y++) for (int x = 0; x < 600; x++) {
            int displacement = (int) Math.round(bow * Math.sin(Math.PI * x / 599) * (inconsistent && y > 400 ? -1 : 1));
            int sy = y - displacement;
            curved[y * 600 + x] = sy >= 0 && sy < 800 ? original[sy * 600 + x] : Color.WHITE;
        }
        Bitmap result = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888);
        result.setPixels(curved, 0, 600, 0, 0, 600, 800);
        return result;
    }

    private static double baselineSpread(Bitmap bitmap) {
        double total = 0;
        for (int row = 2; row < 10; row++) {
            int baseline = 65 + row * 56 + row % 3 * 3, min = 1000, max = -1;
            for (int x : new int[]{59, 304, 549}) {
                int last = -1;
                for (int y = baseline - 20; y <= baseline + 20; y++) {
                    if (Color.red(bitmap.getPixel(x, y)) < 128) last = y;
                }
                require(last >= 0, "Flattened baseline marker missing"); min = Math.min(min, last); max = Math.max(max, last);
            }
            total += max - min;
        }
        return total / 8;
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
