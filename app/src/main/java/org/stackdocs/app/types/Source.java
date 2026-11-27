package org.stackdocs.app.types;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** One local file handed to a handler. `context` is needed only by handlers that run OCR; `page` is the page a preview or text read is for. */
public final class Source {
    private final Context context;
    private final File file;
    private final ParcelFileDescriptor fd;
    private final int page;

    private Source(Context context, File file, ParcelFileDescriptor fd, int page) { this.context = context; this.file = file; this.fd = fd; this.page = page; }

    public static Source of(File file) { return new Source(null, file, null, 0); }
    public static Source of(Context context, File file) { return new Source(context == null ? null : context.getApplicationContext(), file, null, 0); }
    /**
     * A file the caller only holds an open descriptor of (the isolated extract process: it can open no path). `fd` must be seekable; it is never closed by
     * the Source, and every read starts at its beginning.
     */
    public static Source ofDescriptor(Context context, ParcelFileDescriptor fd) { return new Source(context == null ? null : context.getApplicationContext(), null, fd, 0); }
    public Source page(int index) { return new Source(context, file, fd, index); }

    /** The file's path, or null when the Source is only a descriptor. */
    public File file() { return file; }
    public Context context() { return context; }
    public int page() { return page; }
    public long length() { return fd != null ? fd.getStatSize() : file.length(); }
    public InputStream open() throws IOException { return fd != null ? new ParcelFileDescriptor.AutoCloseInputStream(descriptor()) : new FileInputStream(file); }
    /** A new descriptor at the start of the file; the caller closes it. */
    public ParcelFileDescriptor descriptor() throws IOException {
        if (fd == null) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        ParcelFileDescriptor copy = fd.dup();
        try { android.system.Os.lseek(copy.getFileDescriptor(), 0, android.system.OsConstants.SEEK_SET); }   // a dup shares the offset: start at 0
        catch (android.system.ErrnoException error) { copy.close(); throw new IOException("The file cannot be read from the start.", error); }
        return copy;
    }

    /** The first `max` bytes (fewer for a shorter file). */
    public byte[] head(int max) throws IOException {
        try (InputStream in = open()) {
            byte[] buffer = new byte[max];
            int n = 0, r;
            while (n < max && (r = in.read(buffer, n, max - n)) > 0) n += r;
            return n == max ? buffer : java.util.Arrays.copyOf(buffer, n);
        }
    }

    /** The whole file, only when it is at most `max` bytes; otherwise null. */
    public byte[] readAll(long max) throws IOException {
        long size = length();
        if (size > max) return null;
        byte[] data = new byte[(int) size];
        try (InputStream in = open()) {
            int n = 0, r;
            while (n < data.length && (r = in.read(data, n, data.length - n)) > 0) n += r;
            return n == data.length ? data : java.util.Arrays.copyOf(data, n);
        }
    }
}
