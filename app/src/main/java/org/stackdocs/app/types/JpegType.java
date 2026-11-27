package org.stackdocs.app.types;

final class JpegType extends ImageType {
    JpegType() { super("jpeg", "image/jpeg", "jpg", "jpeg"); }
    @Override public boolean matches(byte[] head, String name, String mime) { return starts(head, 0, 0xFF, 0xD8, 0xFF); }
}
