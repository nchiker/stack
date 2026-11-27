package org.stackdocs.app.types;

/**
 * A scan made in Stack. Its pages are stored as pictures, so a page source file reads and previews like any picture;
 * the type is known only by the library's internal MIME (there is no content signature of its own).
 */
final class StackScanType extends ImageType {
    static final String MIME = "application/x-stack-scan";
    StackScanType() { super("stack-scan", MIME); }
    @Override public boolean matches(byte[] head, String name, String mime) { return MIME.equals(mime); }
    @Override public boolean isImage() { return false; }
}
