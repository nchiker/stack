package org.stackdocs.app.types;

final class PngType extends ImageType {
    PngType() { super("png", "image/png", "png"); }
    @Override public boolean matches(byte[] head, String name, String mime) { return starts(head, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A); }
}
