package org.stackdocs.app;

import android.graphics.Bitmap;

/** Local paper normalization that retains gray edge pixels around printed strokes. */
final class PageEnhancement {
    private PageEnhancement() {}

    static Bitmap blackAndWhite(Bitmap source) {
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = new int[width * height];
        byte[] gray = new byte[pixels.length];
        source.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            gray[i] = (byte) ((((pixel >> 16) & 255) * 77 + ((pixel >> 8) & 255) * 150 + (pixel & 255) * 29) >> 8);
        }
        // Filter only the illumination estimate: original stroke pixels stay untouched.
        // Closing fills dark text holes, then contracts the expanded bright regions
        // back onto shadow boundaries. A maximum alone spreads highlights into shadows.
        int radius = Math.max(8, Math.min(64, Math.min(width, height) / 50));
        byte[] horizontal = new byte[gray.length], paper = new byte[gray.length];
        medianThree(gray, horizontal, width, height, true);
        medianThree(horizontal, paper, width, height, false);
        extrema(paper, horizontal, width, height, radius, true, true);
        extrema(horizontal, paper, width, height, radius, false, true);
        extrema(paper, horizontal, width, height, radius, true, false);
        extrema(horizontal, paper, width, height, radius, false, false);
        for (int i = 0; i < pixels.length; i++) {
            float normalized = (gray[i] & 255) / (float) Math.max(32, paper[i] & 255);
            // A small paper/noise allowance, followed by continuous contrast. The old
            // white point at .88 erased marks only 10% darker than their local paper.
            int value = Math.max(0, Math.min(255, 255 - Math.round((1 - normalized - .035f) * (255f / .60f))));
            pixels[i] = 0xff000000 | value << 16 | value << 8 | value;
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
    }

    /** Separable sliding extrema; each input position enters the deque once. */
    private static void extrema(byte[] source, byte[] target, int width, int height, int radius, boolean rows, boolean maximum) {
        int length = rows ? width : height, lines = rows ? height : width;
        int stride = rows ? 1 : width;
        int[] deque = new int[length];
        for (int line = 0; line < lines; line++) {
            int base = rows ? line * width : line, head = 0, tail = 0, next = 0;
            for (int position = 0; position < length; position++) {
                int right = Math.min(length - 1, position + radius);
                while (next <= right) {
                    int value = source[base + next * stride] & 255;
                    while (tail > head && (maximum ? (source[base + deque[tail - 1] * stride] & 255) <= value
                            : (source[base + deque[tail - 1] * stride] & 255) >= value)) tail--;
                    deque[tail++] = next++;
                }
                while (deque[head] < position - radius) head++;
                target[base + position * stride] = source[base + deque[head] * stride];
            }
        }
    }

    /** Suppress isolated highlights before they can bias a large paper-estimation window. */
    private static void medianThree(byte[] source, byte[] target, int width, int height, boolean rows) {
        int length = rows ? width : height, lines = rows ? height : width, stride = rows ? 1 : width;
        for (int line = 0; line < lines; line++) {
            int base = rows ? line * width : line;
            for (int position = 0; position < length; position++) {
                int a = source[base + Math.max(0, position - 1) * stride] & 255;
                int b = source[base + position * stride] & 255;
                int c = source[base + Math.min(length - 1, position + 1) * stride] & 255;
                target[base + position * stride] = (byte) (a + b + c - Math.min(a, Math.min(b, c)) - Math.max(a, Math.max(b, c)));
            }
        }
    }
}
