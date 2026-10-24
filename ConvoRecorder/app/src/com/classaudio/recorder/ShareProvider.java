package com.classaudio.recorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only access to recordings and transcripts for the share sheet (no AndroidX FileProvider in this build). */
public class ShareProvider extends ContentProvider {
    static final String AUTHORITY = "com.classaudio.recorder.files";

    static Uri uriFor(File f) { return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(f.getName())); }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        File f = name == null || name.contains("/") ? null : new File(RecorderService.dir(getContext()), name);
        if (f == null || !f.isFile()) throw new FileNotFoundException(uri.toString());
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = String.valueOf(uri.getLastPathSegment());
        return name.endsWith(".wav") ? "audio/wav" : name.endsWith(".txt") ? "text/plain"
                : name.endsWith(".json") ? "application/json" : "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
        try {
            File f = file(uri);
            c.addRow(new Object[] { f.getName(), f.length() });
        } catch (FileNotFoundException ignored) { }
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
