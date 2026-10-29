package io.lslegacy;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.lslegacy.net.HttpClient;

/** Client side: prepare-upload then one upload per file. */
final class Sender {
    interface Callback {
        void onStatus(String status);

        void onProgress(long sent, long total);

        void onDone(boolean ok, String message);
    }

    private static final class Item {
        String id = UUID.randomUUID().toString();
        String name;
        long size;
        String type;
        String preview;
        Uri uri;
        byte[] inline;
    }

    private final LsService service;
    private volatile boolean cancelled;
    private volatile String sessionId;

    Sender(LsService service) {
        this.service = service;
    }

    void cancel() {
        cancelled = true;
    }

    void sendFiles(final Device d, final List<Uri> uris, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                List<Item> items = new ArrayList<Item>();
                try {
                    for (Uri u : uris) items.add(describe(service, u));
                } catch (IOException e) {
                    cb.onDone(false, "Can't read file: " + e.getMessage());
                    return;
                }
                transfer(d, items, cb);
            }
        }, "ls-send").start();
    }

    void sendText(final Device d, final String text, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                Item it = new Item();
                try {
                    it.inline = text.getBytes("UTF-8");
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
                it.name = it.id + ".txt";
                it.size = it.inline.length;
                it.type = "text/plain";
                it.preview = text;
                List<Item> items = new ArrayList<Item>();
                items.add(it);
                transfer(d, items, cb);
            }
        }, "ls-send-text").start();
    }

    private void transfer(Device d, List<Item> items, Callback cb) {
        HttpClient.Target t = d.target();
        boolean accepted = false;
        try {
            JSONObject files = new JSONObject();
            long total = 0;
            for (Item it : items) {
                JSONObject f = new JSONObject();
                f.put("id", it.id);
                f.put("fileName", it.name);
                f.put("size", it.size);
                f.put("fileType", it.type);
                f.put("sha256", JSONObject.NULL);
                f.put("preview", it.preview != null ? it.preview : JSONObject.NULL);
                files.put(it.id, f);
                total += it.size;
            }
            JSONObject body = new JSONObject();
            body.put("info", service.infoJson(true));
            body.put("files", files);

            cb.onStatus("Waiting for " + d.alias + " to accept…");
            // The receiver holds this request open until its user accepts.
            HttpClient.Response r = HttpClient.postJson(t, service.identity(), Protocol.API + "prepare-upload",
                    body.toString(), HttpClient.CONNECT_TIMEOUT, 180000);
            switch (r.status) {
                case 200:
                    break;
                case 204:
                    cb.onDone(true, items.size() == 1 && items.get(0).preview != null ? "Message sent" : "Nothing to send");
                    return;
                case 401:
                    cb.onDone(false, "The receiver requires a PIN (not supported yet)");
                    return;
                case 403:
                    cb.onDone(false, "Declined by " + d.alias);
                    return;
                case 409:
                    cb.onDone(false, d.alias + " is busy with another transfer");
                    return;
                case 429:
                    cb.onDone(false, "Too many requests, try again shortly");
                    return;
                default:
                    cb.onDone(false, "Receiver error (" + r.status + ")");
                    return;
            }

            accepted = true;
            JSONObject resp = new JSONObject(r.text());
            sessionId = resp.getString("sessionId");
            JSONObject tokens = resp.getJSONObject("files");
            final long fTotal = total;
            long sentBefore = 0;
            for (final Item it : items) {
                if (cancelled) throw new IOException("Cancelled");
                String token = Util.str(tokens, it.id);
                if (token == null) { // receiver already has this one
                    sentBefore += it.size;
                    continue;
                }
                cb.onStatus("Sending " + it.name);
                final long base = sentBefore;
                String path = Protocol.API + "upload?sessionId=" + HttpClient.enc(sessionId)
                        + "&fileId=" + HttpClient.enc(it.id) + "&token=" + HttpClient.enc(token);
                HttpClient.Response u = HttpClient.request(t, service.identity(), "POST", path,
                        "application/octet-stream", it.size, new HttpClient.BodyWriter() {
                            @Override public void write(OutputStream out) throws IOException {
                                InputStream in = open(service, it);
                                try {
                                    byte[] buf = new byte[16384];
                                    long sent = 0;
                                    long last = 0;
                                    int n;
                                    while ((n = in.read(buf)) != -1) {
                                        if (cancelled) throw new IOException("Cancelled");
                                        out.write(buf, 0, n);
                                        sent += n;
                                        if (sent - last >= 128 * 1024) {
                                            last = sent;
                                            cb.onProgress(base + sent, fTotal);
                                        }
                                    }
                                    if (sent != it.size) throw new IOException("File changed while sending");
                                } finally {
                                    Util.closeQuietly(in);
                                }
                            }
                        }, HttpClient.CONNECT_TIMEOUT, 120000);
                if (u.status != 200 && u.status != 204) throw new IOException("Upload failed (" + u.status + ")");
                sentBefore += it.size;
                cb.onProgress(sentBefore, fTotal);
            }
            cb.onDone(true, "Sent " + items.size() + (items.size() == 1 ? " file" : " files") + " to " + d.alias);
        } catch (IOException | JSONException e) {
            if (sessionId != null) {
                try {
                    HttpClient.postJson(t, service.identity(), Protocol.API + "cancel?sessionId=" + HttpClient.enc(sessionId),
                            "", HttpClient.CONNECT_TIMEOUT, 5000);
                } catch (IOException ignored) {
                }
            }
            cb.onDone(false, cancelled ? "Cancelled" : failure(d, e, accepted));
        }
    }

    /** Timeouts and some TLS errors carry no message, which used to show as "Failed: null". */
    private static String failure(Device d, Exception e, boolean accepted) {
        if (e instanceof SocketTimeoutException) {
            return accepted ? d.alias + " stopped responding" : d.alias + " didn't accept in time";
        }
        String why = e.getMessage();
        return "Failed: " + (why != null ? why : e.getClass().getSimpleName());
    }

    private static InputStream open(Context ctx, Item it) throws IOException {
        if (it.inline != null) return new ByteArrayInputStream(it.inline);
        if ("file".equals(it.uri.getScheme())) return new FileInputStream(it.uri.getPath());
        InputStream in = ctx.getContentResolver().openInputStream(it.uri);
        if (in == null) throw new IOException("Can't open " + it.uri);
        return in;
    }

    private static Item describe(Context ctx, Uri uri) throws IOException {
        Item it = new Item();
        it.uri = uri;
        ContentResolver cr = ctx.getContentResolver();
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            if (!f.isFile()) throw new IOException(f.getName() + " not found");
            it.name = f.getName();
            it.size = f.length();
        } else {
            Cursor c = null;
            try {
                c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
                if (c != null && c.moveToFirst()) {
                    it.name = c.getString(0);
                    if (!c.isNull(1)) it.size = c.getLong(1);
                }
            } catch (RuntimeException ignored) {
                // Some old providers don't support OpenableColumns.
            } finally {
                if (c != null) c.close();
            }
            if (it.name == null) it.name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "file";
            if (it.size <= 0) it.size = measure(ctx, it);
            it.type = cr.getType(uri);
        }
        if (it.type == null) {
            int dot = it.name.lastIndexOf('.');
            if (dot >= 0) it.type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.name.substring(dot + 1).toLowerCase());
        }
        if (it.type == null) it.type = "application/octet-stream";
        return it;
    }

    private static long measure(Context ctx, Item it) throws IOException {
        InputStream in = open(ctx, it);
        try {
            long n = 0;
            byte[] buf = new byte[32768];
            int r;
            while ((r = in.read(buf)) != -1) n += r;
            return n;
        } finally {
            Util.closeQuietly(in);
        }
    }
}
