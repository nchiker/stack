package org.stackdocs.app;

import org.json.JSONArray;
import org.json.JSONException;

final class PageGeometry {
    private PageGeometry() {}

    static float[] full() { return new float[]{0, 0, 1, 0, 1, 1, 0, 1}; }

    static float[] checked(float[] points) {
        if (points.length != 8) throw new IllegalArgumentException("Choose all four page corners.");
        for (float point : points) {
            if (Float.isNaN(point) || Float.isInfinite(point) || point < 0 || point > 1) {
                throw new IllegalArgumentException("Keep the crop inside the page.");
            }
        }
        float area = 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            int k = (i + 2) % 4;
            float ax = points[j * 2] - points[i * 2];
            float ay = points[j * 2 + 1] - points[i * 2 + 1];
            float bx = points[k * 2] - points[j * 2];
            float by = points[k * 2 + 1] - points[j * 2 + 1];
            if (ax * by - ay * bx < 0.002f) throw new IllegalArgumentException("Keep the corners in order around the page.");
            if (ax * ax + ay * ay < 0.0025f) throw new IllegalArgumentException("The crop is too small.");
            area += points[i * 2] * points[j * 2 + 1] - points[j * 2] * points[i * 2 + 1];
        }
        if (area < 0.08f) throw new IllegalArgumentException("The crop is too small.");
        return points.clone();
    }

    static String encode(float[] points) {
        if (points == null) return "";
        checked(points);
        JSONArray array = new JSONArray();
        try {
            for (float point : points) array.put((double) point);
        } catch (JSONException error) { throw new IllegalArgumentException("Invalid crop", error); }
        return array.toString();
    }

    static float[] decode(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            JSONArray array = new JSONArray(value);
            if (array.length() != 8) throw new JSONException("Expected four corners");
            float[] points = new float[8];
            for (int i = 0; i < 8; i++) points[i] = (float) array.getDouble(i);
            return checked(points);
        } catch (JSONException error) { throw new IllegalArgumentException("The saved crop could not be read.", error); }
    }

    static float[] display(float[] original, int rotation) {
        float[] points = original == null ? full() : checked(original);
        float[] result = new float[8];
        int turns = rotation / 90;
        for (int i = 0; i < 4; i++) {
            int from = (i - turns + 4) % 4;
            float u = points[from * 2], v = points[from * 2 + 1];
            result[i * 2] = turns == 1 ? 1 - v : turns == 2 ? 1 - u : turns == 3 ? v : u;
            result[i * 2 + 1] = turns == 1 ? u : turns == 2 ? 1 - v : turns == 3 ? 1 - u : v;
        }
        return result;
    }

    static float[] original(float[] displayed, int rotation) {
        return display(displayed, (360 - rotation) % 360);
    }
}
