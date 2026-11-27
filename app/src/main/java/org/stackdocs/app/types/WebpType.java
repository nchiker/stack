package org.stackdocs.app.types;

final class WebpType extends ImageType {
    WebpType() { super("webp", "image/webp", "webp"); }
    @Override public boolean matches(byte[] head, String name, String mime) {
        return starts(head, 0, 'R', 'I', 'F', 'F') && starts(head, 8, 'W', 'E', 'B', 'P');
    }
}
