package org.stackdocs.app;

import android.graphics.Bitmap;
import java.util.ArrayList;
import java.util.List;

/** Local page corners; every region and line proposal passes the same edge checks. */
final class BoundaryDetector {
    private BoundaryDetector() {}

    static float[] suggest(Bitmap bitmap) {
        return EdgePageDetector.suggest(bitmap, brightRegions(bitmap));
    }

    private static List<float[]> brightRegions(Bitmap bitmap) {
        float scale = Math.min(1, 160f / Math.max(bitmap.getWidth(), bitmap.getHeight()));
        int width = Math.max(1, Math.round(bitmap.getWidth() * scale));
        int height = Math.max(1, Math.round(bitmap.getHeight() * scale));
        int[] luminance = new int[width * height], histogram = new int[256];
        Bitmap sample = Bitmap.createScaledBitmap(bitmap, width, height, true);
        try { sample.getPixels(luminance, 0, width, 0, 0, width, height); }
        finally { if (sample != bitmap) sample.recycle(); }
        for (int i = 0; i < luminance.length; i++) {
            int pixel = luminance[i];
            int light = (((pixel >> 16) & 255) * 77 + ((pixel >> 8) & 255) * 150 + (pixel & 255) * 29) >> 8;
            luminance[i] = light;
            histogram[light]++;
        }
        int percentile = 0, count = 0;
        while (percentile < 255 && count < luminance.length * .7) count += histogram[percentile++];
        int high = Math.max(40, Math.min(235, percentile - 15));
        int low = separation(histogram, luminance.length);
        List<float[]> candidates = new ArrayList<>();
        // A single high cutoff can split a shadowed sheet; a histogram separation
        // proposal supplements it without making brightness alone a crop decision.
        int[] thresholds = {high, low, (high + low) / 2};
        for (int t = 0; t < thresholds.length; t++) {
            int threshold = thresholds[t];
            if ((t > 0 && threshold == thresholds[0]) || (t == 2 && threshold == thresholds[1])) continue;
            boolean[] visited = new boolean[luminance.length];
            int[] queue = new int[luminance.length];
            for (int seed = 0; seed < luminance.length; seed++) {
                if (visited[seed] || luminance[seed] < threshold) continue;
                int head = 0, tail = 1;
                queue[0] = seed; visited[seed] = true;
                int[] corners = {seed, seed, seed, seed};
                while (head < tail) {
                    int index = queue[head++], x = index % width, y = index / width;
                    for (int corner = 0; corner < 4; corner++) {
                        int oldX = corners[corner] % width, oldY = corners[corner] / width;
                        int score = corner % 2 == 0 ? x + y : x - y;
                        int old = corner % 2 == 0 ? oldX + oldY : oldX - oldY;
                        if ((corner == 0 || corner == 3) ? score < old : score > old) corners[corner] = index;
                    }
                    for (int side = 0; side < 4; side++) {
                        int neighbor = switch (side) {
                            case 0 -> x > 0 ? index - 1 : -1;
                            case 1 -> x + 1 < width ? index + 1 : -1;
                            case 2 -> y > 0 ? index - width : -1;
                            default -> y + 1 < height ? index + width : -1;
                        };
                        if (neighbor >= 0 && !visited[neighbor] && luminance[neighbor] >= threshold) {
                            visited[neighbor] = true; queue[tail++] = neighbor;
                        }
                    }
                }
                if (tail < luminance.length * .12 || tail > luminance.length * .94) continue;
                float[] points = new float[8];
                for (int i = 0; i < 4; i++) {
                    points[i * 2] = (float) (corners[i] % width) / Math.max(1, width - 1);
                    points[i * 2 + 1] = (float) (corners[i] / width) / Math.max(1, height - 1);
                }
                try { candidates.add(PageGeometry.checked(points)); }
                catch (IllegalArgumentException ignored) { /* Irregular region, not a quadrilateral. */ }
            }
        }
        return candidates;
    }

    /** Otsu's between-class variance; requires neither an absolute white level nor a model. */
    private static int separation(int[] histogram, int total) {
        long sum = 0, belowSum = 0;
        for (int i = 0; i < histogram.length; i++) sum += (long) i * histogram[i];
        int below = 0, best = 0; double bestVariance = -1;
        for (int i = 0; i < 255; i++) {
            below += histogram[i]; belowSum += (long) i * histogram[i];
            if (below == 0 || below == total) continue;
            double difference = belowSum / (double) below - (sum - belowSum) / (double) (total - below);
            double variance = (double) below * (total - below) * difference * difference;
            if (variance > bestVariance) { bestVariance = variance; best = i + 1; }
        }
        return Math.max(40, Math.min(235, best));
    }
}
