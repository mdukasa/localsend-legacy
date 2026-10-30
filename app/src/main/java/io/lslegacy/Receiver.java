package io.lslegacy;

import android.media.MediaScannerConnection;
import android.os.Environment;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.lslegacy.net.MiniHttpServer;

/** Server side of the protocol: register, info, prepare-upload, upload, cancel. */
final class Receiver implements MiniHttpServer.Handler {
    private static final String TAG = "LsReceiver";
    private static final long SESSION_IDLE_MS = 5 * 60 * 1000;
    private static final String NO_STORAGE = "no SD card, or it's being used by a computer over USB";

    static final class FileEntry {
        String id;
        String fileName;
        long size;
        String fileType;
        String sha256;
        String preview;
        String token;
        File saved;
        boolean done;
    }

    static final class Session {
        String id;
        String senderIp;
        Device sender;
        final Map<String, FileEntry> files = new LinkedHashMap<String, FileEntry>();
        long totalBytes;
        long receivedBytes;
        volatile long lastActivity;
        volatile boolean accepted;
    }

    /** A pending prepare-upload waiting on the user. */
    static final class IncomingRequest {
        final Device sender;
        final List<FileEntry> files;
        final long totalBytes;
        private final Object lock = new Object();
        private Boolean decision;

        IncomingRequest(Device sender, List<FileEntry> files, long totalBytes) {
            this.sender = sender;
            this.files = files;
            this.totalBytes = totalBytes;
        }

        void decide(boolean accept) {
            synchronized (lock) {
                if (decision == null) decision = accept;
                lock.notifyAll();
            }
        }

        boolean isDecided() {
            synchronized (lock) {
                return decision != null;
            }
        }

        boolean await(long timeoutMs) throws InterruptedException {
            long end = System.currentTimeMillis() + timeoutMs;
            synchronized (lock) {
                while (decision == null) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0) {
                        decision = false;
                        break;
                    }
                    lock.wait(left);
                }
                return decision;
            }
        }
    }

    private final LsService service;
    private Session session;
    private IncomingRequest pending;

    Receiver(LsService service) {
        this.service = service;
    }

    synchronized IncomingRequest pendingRequest() {
        return pending != null && !pending.isDecided() ? pending : null;
    }

    static File saveDir() {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "LocalSend");
        dir.mkdirs();
        return dir;
    }

    @Override public void handle(MiniHttpServer.Request req, MiniHttpServer.Response res) throws Exception {
        String p = req.path;
        if (p.startsWith("/api/localsend/v1/")) p = Protocol.API + p.substring("/api/localsend/v1/".length());
        if (p.equals(Protocol.API + "info")) {
            res.json(200, service.infoJson(false).toString());
        } else if (p.equals(Protocol.API + "register") && "POST".equals(req.method)) {
            register(req, res);
        } else if (p.equals(Protocol.API + "prepare-upload") && "POST".equals(req.method)) {
            prepareUpload(req, res);
        } else if (p.equals(Protocol.API + "upload") && "POST".equals(req.method)) {
            upload(req, res);
        } else if (p.equals(Protocol.API + "cancel") && "POST".equals(req.method)) {
            cancel(req, res);
        } else {
            res.json(404, "{\"message\":\"Not found\"}");
        }
    }

    private void register(MiniHttpServer.Request req, MiniHttpServer.Response res) throws IOException {
        try {
            Device d = Device.fromJson(new JSONObject(new String(req.readBody(65536), "UTF-8")), req.remoteIp);
            if (!service.identity().fingerprint.equalsIgnoreCase(d.fingerprint)) service.onDeviceFound(d);
        } catch (JSONException e) {
            res.json(400, "{\"message\":\"Invalid body\"}");
            return;
        }
        res.json(200, service.infoJson(false).toString());
    }

    private void prepareUpload(MiniHttpServer.Request req, MiniHttpServer.Response res) throws Exception {
        JSONObject body;
        try {
            body = new JSONObject(new String(req.readBody(4 * 1024 * 1024), "UTF-8"));
        } catch (JSONException e) {
            res.json(400, "{\"message\":\"Invalid body\"}");
            return;
        }
        JSONObject info = body.optJSONObject("info");
        JSONObject files = body.optJSONObject("files");
        if (info == null || files == null || files.length() == 0) {
            res.json(400, "{\"message\":\"Invalid body\"}");
            return;
        }
        Device sender = Device.fromJson(info, req.remoteIp);
        service.onDeviceFound(sender);

        List<FileEntry> list = new ArrayList<FileEntry>();
        long total = 0;
        boolean allText = true;
        Iterator<?> keys = files.keys();
        while (keys.hasNext()) {
            JSONObject f = files.optJSONObject((String) keys.next());
            if (f == null) continue;
            FileEntry e = new FileEntry();
            e.id = Util.str(f, "id");
            e.fileName = Util.str(f, "fileName");
            e.size = f.optLong("size", 0);
            e.fileType = Util.str(f, "fileType");
            e.sha256 = Util.str(f, "sha256");
            e.preview = Util.str(f, "preview");
            if (e.id == null || e.fileName == null) {
                res.json(400, "{\"message\":\"Invalid file\"}");
                return;
            }
            boolean isText = e.preview != null && (e.fileType == null || e.fileType.startsWith("text"));
            allText &= isText;
            total += e.size;
            list.add(e);
        }
        // The files map arrives in hash order; show it alphabetically instead.
        Collections.sort(list, new Comparator<FileEntry>() {
            @Override public int compare(FileEntry a, FileEntry b) {
                return a.fileName.compareToIgnoreCase(b.fileName);
            }
        });

        // A text message arrives as a file whose preview is the message; show it, nothing to transfer.
        if (allText) {
            for (FileEntry e : list) service.onMessage(sender, e.preview);
            res.status(204);
            return;
        }

        // Files are saved to the SD card. Without one, refuse now instead of failing after the user accepts.
        if (!storageReady()) {
            service.onRefused(sender, NO_STORAGE);
            res.json(403, "{\"message\":\"No storage to save files to\"}");
            return;
        }

        IncomingRequest ask;
        synchronized (this) {
            if (session != null && System.currentTimeMillis() - session.lastActivity < SESSION_IDLE_MS) {
                res.json(409, "{\"message\":\"Blocked by another session\"}");
                return;
            }
            if (pending != null && !pending.isDecided()) {
                res.json(409, "{\"message\":\"Blocked by another session\"}");
                return;
            }
            ask = new IncomingRequest(sender, list, total);
            pending = ask;
        }

        boolean accepted;
        if (service.autoAccept()) {
            ask.decide(true);
            accepted = true;
        } else {
            service.onIncoming(ask);
            accepted = ask.await(120000);
            service.clearIncomingNotification();
        }
        if (!accepted) {
            res.json(403, "{\"message\":\"Rejected\"}");
            return;
        }

        Session s = new Session();
        s.id = Rand.id();
        s.senderIp = req.remoteIp;
        s.sender = sender;
        s.totalBytes = total;
        s.lastActivity = System.currentTimeMillis();
        s.accepted = true;
        JSONObject tokens = new JSONObject();
        for (FileEntry e : list) {
            e.token = Rand.id();
            s.files.put(e.id, e);
            tokens.put(e.id, e.token);
        }
        synchronized (this) {
            session = s;
        }
        service.onTransferStarted(s);
        JSONObject out = new JSONObject();
        out.put("sessionId", s.id);
        out.put("files", tokens);
        res.json(200, out.toString());
    }

    private void upload(MiniHttpServer.Request req, MiniHttpServer.Response res) throws Exception {
        String sessionId = req.query.get("sessionId");
        String fileId = req.query.get("fileId");
        String token = req.query.get("token");
        if (sessionId == null || fileId == null || token == null) {
            res.json(400, "{\"message\":\"Missing parameters\"}");
            return;
        }
        Session s;
        FileEntry e;
        synchronized (this) {
            s = session;
            if (s == null || !s.id.equals(sessionId)) {
                res.json(409, "{\"message\":\"Blocked by another session\"}");
                return;
            }
            e = s.files.get(fileId);
            if (e == null || !token.equals(e.token) || !req.remoteIp.equals(s.senderIp) || e.done) {
                res.json(403, "{\"message\":\"Invalid token or IP address\"}");
                return;
            }
        }

        File dest = uniqueFile(saveDir(), e.fileName);
        dest.getParentFile().mkdirs();
        MessageDigest digest = e.sha256 != null ? Util.sha256() : null;
        OutputStream out = null;
        long written;
        final Session fs = s;
        final long base;
        synchronized (this) {
            base = s.receivedBytes;
        }
        try {
            out = new BufferedOutputStream(new FileOutputStream(dest), 65536);
            written = Util.copy(req.body, out, digest, new Util.CopyProgress() {
                @Override public void onBytes(long total) {
                    fs.lastActivity = System.currentTimeMillis();
                    service.onTransferProgress(fs, base + total);
                }
            });
            out.close();
        } catch (IOException ex) {
            Util.closeQuietly(out);
            dest.delete();
            fail(s, ex);
            throw ex;
        }
        if (written != e.size && e.size > 0) {
            dest.delete();
            res.json(400, "{\"message\":\"Size mismatch\"}");
            return;
        }
        if (digest != null && !Util.hex(digest.digest()).equalsIgnoreCase(e.sha256)) {
            dest.delete();
            res.json(422, "{\"message\":\"Checksum mismatch\"}");
            return;
        }
        MediaScannerConnection.scanFile(service, new String[]{dest.getAbsolutePath()}, null, null);

        boolean finished;
        synchronized (this) {
            e.done = true;
            e.saved = dest;
            s.receivedBytes += written;
            finished = true;
            for (FileEntry f : s.files.values()) finished &= f.done;
            if (finished && session == s) session = null;
        }
        if (finished) service.onTransferFinished(s, true);
        res.status(200);
    }

    private void cancel(MiniHttpServer.Request req, MiniHttpServer.Response res) {
        String sessionId = req.query.get("sessionId");
        Session s;
        synchronized (this) {
            s = session;
            if (s == null || (sessionId != null && !s.id.equals(sessionId)) || !req.remoteIp.equals(s.senderIp)) {
                res.status(200);
                return;
            }
            session = null;
        }
        Log.i(TAG, "sender cancelled session " + s.id);
        service.onTransferFinished(s, false);
        res.status(200);
    }

    /** Ends the session after a file couldn't be written or the sender dropped mid-file. */
    private void fail(Session s, IOException ex) {
        synchronized (this) {
            if (session != s) return; // already finished or cancelled
            session = null;
        }
        String why = !storageReady() ? NO_STORAGE : ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
        Log.w(TAG, "receive failed", ex);
        service.onTransferFailed(s, why);
    }

    private static boolean storageReady() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    /** Keeps sub-folders the sender sent, strips anything that would escape {@code dir}, and avoids overwriting. */
    static File uniqueFile(File dir, String name) {
        StringBuilder clean = new StringBuilder();
        for (String part : name.replace('\\', '/').split("/")) {
            if (part.length() == 0 || part.equals(".") || part.equals("..")) continue;
            if (clean.length() > 0) clean.append('/');
            clean.append(part.replaceAll("[:*?\"<>|\\x00-\\x1f]", "_"));
        }
        if (clean.length() == 0) clean.append("file");
        File f = new File(dir, clean.toString());
        if (!f.exists()) return f;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String stem = dot > 0 ? n.substring(0, dot) : n;
        String ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 1; ; i++) {
            File c = new File(f.getParentFile(), stem + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
    }
}
