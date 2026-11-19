package io.lslegacy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Recently received files and messages, newest first, as in LocalSend's receive history: up to 30
 * entries, one per file, kept in the app's private storage. Removing an entry leaves the file alone.
 */
final class History {
    private static final String TAG = "LsHistory";
    private static final int MAX_ENTRIES = 30;
    private static final String FILE = "history.json";

    static final class Entry {
        String id;
        /** The file name, or the text itself for a message. */
        String name;
        /** MIME type the sender gave, may be null. */
        String type;
        /** Where the file was saved; null for a message. */
        String path;
        boolean message;
        long size;
        String sender;
        /** When it arrived, in ms since the epoch. */
        long time;

        File file() {
            return path != null ? new File(path) : null;
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            if (type != null) o.put("type", type);
            if (path != null) o.put("path", path);
            o.put("message", message);
            o.put("size", size);
            o.put("sender", sender);
            o.put("time", time);
            return o;
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.id = Util.str(o, "id");
            e.name = Util.str(o, "name");
            e.type = Util.str(o, "type");
            e.path = Util.str(o, "path");
            e.message = o.optBoolean("message", false);
            e.size = o.optLong("size", 0);
            e.sender = Util.str(o, "sender");
            e.time = o.optLong("time", 0);
            return e.id != null && e.name != null ? e : null;
        }
    }

    private static List<Entry> cache;
    private static Runnable listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private History() {
    }

    static void addFile(Context ctx, String sender, String type, File saved) {
        Entry e = new Entry();
        e.name = saved.getName();
        e.type = type;
        e.path = saved.getAbsolutePath();
        e.size = saved.length();
        e.sender = sender;
        add(ctx, e);
    }

    static void addMessage(Context ctx, String sender, String text) {
        Entry e = new Entry();
        e.name = text;
        e.type = "text/plain";
        e.message = true;
        try {
            e.size = text.getBytes("UTF-8").length;
        } catch (java.io.UnsupportedEncodingException ignored) {
            e.size = text.length();
        }
        e.sender = sender;
        add(ctx, e);
    }

    private static void add(Context ctx, Entry e) {
        e.id = Rand.id();
        e.time = System.currentTimeMillis();
        synchronized (History.class) {
            List<Entry> list = load(ctx);
            list.add(0, e);
            while (list.size() > MAX_ENTRIES) list.remove(list.size() - 1);
            save(ctx, list);
        }
        changed();
    }

    /** A copy of the entries, newest first. */
    static List<Entry> entries(Context ctx) {
        synchronized (History.class) {
            return new ArrayList<Entry>(load(ctx));
        }
    }

    static void remove(Context ctx, String id) {
        synchronized (History.class) {
            List<Entry> list = load(ctx);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id.equals(id)) {
                    list.remove(i);
                    save(ctx, list);
                    break;
                }
            }
        }
        changed();
    }

    static void clear(Context ctx) {
        synchronized (History.class) {
            List<Entry> list = load(ctx);
            list.clear();
            save(ctx, list);
        }
        changed();
    }

    /** Called on the main thread whenever the history changes; one listener at a time (the history screen). */
    static void setListener(Runnable r) {
        listener = r;
    }

    private static void changed() {
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.run();
            }
        });
    }

    private static List<Entry> load(Context ctx) {
        if (cache != null) return cache;
        cache = new ArrayList<Entry>();
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return cache;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            JSONArray arr = new JSONArray(new String(buf, 0, n, "UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                Entry e = o != null ? Entry.fromJson(o) : null;
                if (e != null) cache.add(e);
            }
        } catch (IOException e) {
            Log.w(TAG, "couldn't read history", e);
        } catch (JSONException e) {
            Log.w(TAG, "history is corrupt, starting over", e);
        } finally {
            Util.closeQuietly(in);
        }
        return cache;
    }

    /** Writes to a temporary file first, so a crash mid-write can't lose the history. */
    private static void save(Context ctx, List<Entry> list) {
        File f = new File(ctx.getFilesDir(), FILE);
        File tmp = new File(ctx.getFilesDir(), FILE + ".tmp");
        FileOutputStream out = null;
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) arr.put(e.toJson());
            out = new FileOutputStream(tmp);
            out.write(arr.toString().getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            out = null;
            if (!tmp.renameTo(f)) throw new IOException("rename failed");
        } catch (IOException e) {
            Log.w(TAG, "couldn't save history", e);
        } catch (JSONException e) {
            Log.w(TAG, "couldn't save history", e);
        } finally {
            Util.closeQuietly(out);
        }
    }
}
