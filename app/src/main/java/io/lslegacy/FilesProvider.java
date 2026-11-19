package io.lslegacy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Hands one file on shared storage to a viewer app as a content:// URI. Android 7+ throws when an app
 * passes a file:// URI to another app, so opening a received file needs this there. It isn't exported:
 * other apps can only read a URI they were granted with the opening intent, and only for reading.
 */
public class FilesProvider extends ContentProvider {
    static final String AUTHORITY = "io.lslegacy.files";

    static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).path(f.getAbsolutePath()).build();
    }

    @Override public boolean onCreate() {
        return true;
    }

    /** The file a URI points to, if it's on shared storage. */
    private static File fileFor(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) throw new FileNotFoundException(String.valueOf(uri));
        try {
            File f = new File(path).getCanonicalFile();
            String root = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (!f.getPath().startsWith(root + "/")) throw new FileNotFoundException("Not on shared storage: " + path);
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override public String getType(Uri uri) {
        return Opener.mimeOf(uri.getLastPathSegment(), null);
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        if (projection == null) projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        // Answer only the columns we know, in the order asked for.
        int n = 0;
        for (String c : projection) if (OpenableColumns.DISPLAY_NAME.equals(c) || OpenableColumns.SIZE.equals(c)) n++;
        String[] cols = new String[n];
        Object[] row = new Object[n];
        int i = 0;
        for (String c : projection) {
            if (OpenableColumns.DISPLAY_NAME.equals(c)) {
                cols[i] = c;
                row[i++] = f.getName();
            } else if (OpenableColumns.SIZE.equals(c)) {
                cols[i] = c;
                row[i++] = f.length();
            }
        }
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
