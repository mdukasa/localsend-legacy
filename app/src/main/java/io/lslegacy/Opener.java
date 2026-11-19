package io.lslegacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.ClipboardManager;
import android.webkit.MimeTypeMap;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Opens a received file in another app, the way LocalSend's history does, with fallbacks for bare old phones. */
final class Opener {
    private static final int TEXT_LIMIT = 256 * 1024;

    /** Types Android 2.x's MimeTypeMap doesn't know (or didn't yet), so viewers can still be found. */
    private static final Map<String, String> EXTRA_TYPES = new HashMap<String, String>();

    static {
        String[] pairs = {
                "apk", "application/vnd.android.package-archive",
                "txt", "text/plain", "log", "text/plain", "md", "text/plain", "csv", "text/csv",
                "json", "application/json", "xml", "text/xml", "html", "text/html", "htm", "text/html",
                "jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "gif", "image/gif",
                "webp", "image/webp", "bmp", "image/bmp", "heic", "image/heic",
                "mp3", "audio/mpeg", "m4a", "audio/mp4", "aac", "audio/aac", "ogg", "audio/ogg",
                "opus", "audio/ogg", "flac", "audio/flac", "wav", "audio/x-wav", "amr", "audio/amr",
                "mp4", "video/mp4", "3gp", "video/3gpp", "mkv", "video/x-matroska", "webm", "video/webm",
                "pdf", "application/pdf", "vcf", "text/x-vcard", "zip", "application/zip",
        };
        for (int i = 0; i < pairs.length; i += 2) EXTRA_TYPES.put(pairs[i], pairs[i + 1]);
    }

    private Opener() {
    }

    /** The MIME type for a file name, else the type the sender gave, else a wildcard. */
    static String mimeOf(String name, String senderType) {
        String ext = "";
        int dot = name != null ? name.lastIndexOf('.') : -1;
        if (dot >= 0) ext = name.substring(dot + 1).toLowerCase(Locale.US);
        String t = ext.length() > 0 ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) : null;
        if (t == null) t = EXTRA_TYPES.get(ext);
        if (t == null && senderType != null && senderType.indexOf('/') > 0 && !senderType.endsWith("octet-stream")) {
            t = senderType;
        }
        return t != null ? t : "*/*";
    }

    /**
     * Opens {@code f} in a viewer app. Plain text opens in a built-in viewer when nothing else can show it.
     *
     * @param onForget if not null, the "can't open" dialog offers to delete the entry from the history
     */
    static void open(final Activity a, File f, String senderType, final Runnable onForget) {
        if (!f.isFile()) {
            AlertDialog.Builder b = new AlertDialog.Builder(a)
                    .setTitle("Cannot open file")
                    .setMessage("Could not open \"" + f.getName() + "\". Has this file been moved, renamed or deleted?")
                    .setNegativeButton("Close", null);
            if (onForget != null) {
                b.setPositiveButton("Delete from history", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        onForget.run();
                    }
                });
            }
            b.show();
            return;
        }
        String type = mimeOf(f.getName(), senderType);
        // A wildcard type matches every app that takes "anything" (browsers, music players), which is no
        // help for a file of unknown type; offer text viewers for text and only generic openers otherwise.
        if ("*/*".equals(type)) type = looksLikeText(f) ? "text/plain" : "application/octet-stream";
        // Android 7+ throws if a file:// URI leaves the app; older viewers often only understand file://.
        Uri uri = Build.VERSION.SDK_INT >= 24 ? FilesProvider.uriFor(f) : Uri.fromFile(f);
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!a.getPackageManager().queryIntentActivities(view, 0).isEmpty()) {
            try {
                a.startActivity(view);
                return;
            } catch (ActivityNotFoundException ignored) {
            } catch (SecurityException ignored) {
            }
        }
        if (type.startsWith("text/") || type.equals("application/json")) {
            showText(a, f);
            return;
        }
        boolean unknown = type.equals("application/octet-stream");
        new AlertDialog.Builder(a)
                .setTitle("Cannot open file")
                .setMessage("No app on this phone can open \"" + f.getName() + "\"" + (unknown ? "." : " (" + type + ")."))
                .setNegativeButton("Close", null)
                .show();
    }

    /** A received text message, with a Copy button: LocalSend shows these the same way from its history. */
    static void showMessage(final Activity a, String sender, final String text) {
        new AlertDialog.Builder(a)
                .setTitle("Message from " + sender)
                .setMessage(text)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        copy(a, text);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    static void copy(Activity a, String text) {
        ((ClipboardManager) a.getSystemService(Activity.CLIPBOARD_SERVICE)).setText(text);
        Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show();
    }

    /** Stock Android 2.x often has no text viewer at all, so show text files here. */
    private static void showText(final Activity a, File f) {
        final String text;
        try {
            byte[] data = read(f, TEXT_LIMIT);
            String s = new String(data, "UTF-8");
            text = f.length() > TEXT_LIMIT ? s + "\n\n[…only the first " + Util.formatSize(TEXT_LIMIT) + " is shown]" : s;
        } catch (IOException e) {
            Toast.makeText(a, "Couldn't read " + f.getName() + ": " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        int pad = (int) (12 * a.getResources().getDisplayMetrics().density);
        TextView tv = new TextView(a);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextSize(14);
        tv.setText(text);
        ScrollView scroll = new ScrollView(a);
        scroll.addView(tv);
        new AlertDialog.Builder(a)
                .setTitle(f.getName())
                .setView(scroll)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        copy(a, text);
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** No NUL bytes in the first 4 KB: good enough to tell text from binary. */
    private static boolean looksLikeText(File f) {
        try {
            byte[] head = read(f, 4096);
            for (byte b : head) if (b == 0) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] read(File f, int limit) throws IOException {
        int len = (int) Math.min(f.length(), limit);
        byte[] buf = new byte[len];
        FileInputStream in = new FileInputStream(f);
        try {
            int n = 0;
            while (n < len) {
                int r = in.read(buf, n, len - n);
                if (r < 0) break;
                n += r;
            }
            if (n < len) {
                byte[] shorter = new byte[n];
                System.arraycopy(buf, 0, shorter, 0, n);
                return shorter;
            }
            return buf;
        } finally {
            in.close();
        }
    }
}
