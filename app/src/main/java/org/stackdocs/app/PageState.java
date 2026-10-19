package org.stackdocs.app;

/** Geometry uses the upright original's normalized coordinates, before user rotation. */
final class PageState {
    final String id;
    final String sourceFile;
    final int sourcePage;
    final int rotation;
    final float[] crop;
    final String enhancement;
    final boolean flatten;

    PageState(String id, String sourceFile, int sourcePage, int rotation, float[] crop, String enhancement) {
        this(id, sourceFile, sourcePage, rotation, crop, enhancement, false);
    }

    PageState(String id, String sourceFile, int sourcePage, int rotation, float[] crop, String enhancement, boolean flatten) {
        this.id = id;
        this.sourceFile = sourceFile;
        this.sourcePage = sourcePage;
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("Invalid page rotation.");
        }
        this.rotation = rotation;
        this.crop = crop == null ? null : PageGeometry.checked(crop);
        if (!enhancement.equals("original") && !enhancement.equals("medium") && !enhancement.equals("bright")
                && !enhancement.equals("black_white")) {
            throw new IllegalArgumentException("Unknown page enhancement.");
        }
        this.enhancement = enhancement;
        this.flatten = flatten;
    }

    PageState edited(int rotation, float[] crop, String enhancement) {
        return new PageState(id, sourceFile, sourcePage, rotation, crop, enhancement, flatten);
    }

    PageState flattened(boolean enabled) {
        return new PageState(id, sourceFile, sourcePage, rotation, crop, enhancement, enabled);
    }

    boolean unchanged() { return !flatten && rotation == 0 && crop == null && enhancement.equals("original"); }
}
