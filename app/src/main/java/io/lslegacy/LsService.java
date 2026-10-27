package io.lslegacy;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.lslegacy.net.MiniHttpServer;

/** Keeps the HTTP server and multicast discovery running while the app is in the background. */
public class LsService extends Service {
    private static final String TAG = "LsService";
    private static final int NOTIF_RUNNING = 1;
    private static final int NOTIF_INCOMING = 2;
    private static final int NOTIF_TRANSFER = 3;

    interface Listener {
        void onStateChanged();

        void onIncoming(Receiver.IncomingRequest req);

        void onMessage(Device from, String text);

        void onLog(String line);
    }

    private static LsService instance;

    static LsService get() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Device> devices = new LinkedHashMap<String, Device>();
    private final List<String> log = new ArrayList<String>();
    private Listener listener;
    private SharedPreferences prefs;
    private volatile Identity identity;
    private volatile String status = "Starting…";
    private MiniHttpServer server;
    private Discovery discovery;
    private Receiver receiver;
    private Sender activeSender;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private long lastProgressNotif;

    private final BroadcastReceiver netReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (discovery != null && Util.wifiIp(LsService.this) != null) {
                discovery.rejoin();
                discovery.announce();
            }
            stateChanged();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicastLock = wm.createMulticastLock("localsend");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "localsend");
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
        wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "localsend");
        wakeLock.setReferenceCounted(false);

        startForeground(NOTIF_RUNNING, Notifs.build(this, getString(R.string.app_name), "Ready to receive",
                openApp(), true, false, 0, 0));
        registerReceiver(netReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        new Thread(new Runnable() {
            @Override public void run() {
                init();
            }
        }, "ls-init").start();
    }

    private void init() {
        try {
            setStatus("Creating device identity…");
            identity = Identity.loadOrCreate(prefs);
            receiver = new Receiver(this);
            server = new MiniHttpServer(receiver);
            server.start(Protocol.PORT);
            discovery = new Discovery(this);
            discovery.start();
            discovery.announce();
            setStatus("Ready");
        } catch (Exception e) {
            Log.e(TAG, "startup failed", e);
            setStatus("Startup failed: " + e.getMessage());
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        unregisterReceiver(netReceiver);
        if (discovery != null) discovery.stop();
        if (server != null) server.stop();
        if (multicastLock.isHeld()) multicastLock.release();
        if (wifiLock.isHeld()) wifiLock.release();
        if (wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- state for the UI ----

    void setListener(Listener l) {
        listener = l;
    }

    Identity identity() {
        return identity;
    }

    String status() {
        return status;
    }

    boolean ready() {
        return identity != null && server != null;
    }

    String alias() {
        String a = prefs.getString("alias", null);
        return a != null && a.trim().length() > 0 ? a.trim() : Build.MODEL;
    }

    boolean autoAccept() {
        return prefs.getBoolean("auto_accept", false);
    }

    void saveSettings(String alias, boolean autoAccept) {
        prefs.edit().putString("alias", alias).putBoolean("auto_accept", autoAccept).commit();
        if (discovery != null) discovery.announce();
        stateChanged();
    }

    List<Device> devices() {
        synchronized (devices) {
            return new ArrayList<Device>(devices.values());
        }
    }

    List<String> log() {
        synchronized (log) {
            return new ArrayList<String>(log);
        }
    }

    Receiver.IncomingRequest pendingRequest() {
        return receiver != null ? receiver.pendingRequest() : null;
    }

    void refresh() {
        synchronized (devices) {
            devices.clear();
        }
        stateChanged();
        if (discovery != null) {
            discovery.rejoin();
            discovery.announce();
        }
    }

    void scan(final Runnable done) {
        if (discovery == null) return;
        setStatus("Scanning network…");
        discovery.scanSubnet(new Runnable() {
            @Override public void run() {
                setStatus("Ready");
                if (done != null) main.post(done);
            }
        });
    }

    /** Own device info. {@code network} adds port/protocol, used in announcements and register/prepare bodies. */
    JSONObject infoJson(boolean network) {
        JSONObject o = new JSONObject();
        try {
            o.put("alias", alias());
            o.put("version", Protocol.VERSION);
            o.put("deviceModel", Build.MODEL);
            o.put("deviceType", "mobile");
            o.put("fingerprint", identity != null ? identity.fingerprint : "");
            if (network) {
                o.put("port", Protocol.PORT);
                // We serve plain HTTP; peers connect to us the way we announce.
                o.put("protocol", "http");
            }
            o.put("download", false);
        } catch (JSONException e) {
            throw new AssertionError(e);
        }
        return o;
    }

    // ---- sending ----

    Sender newSender() {
        activeSender = new Sender(this);
        return activeSender;
    }

    void cancelSend() {
        if (activeSender != null) activeSender.cancel();
    }

    void acquireWake() {
        if (!wakeLock.isHeld()) wakeLock.acquire();
    }

    void releaseWake() {
        if (wakeLock.isHeld()) wakeLock.release();
    }

    // ---- callbacks from Discovery / Receiver (any thread) ----

    void onDeviceFound(Device d) {
        if (d.fingerprint == null && d.alias == null) return;
        synchronized (devices) {
            Device old = devices.remove(d.key());
            // Some bodies (e.g. prepare-upload info) carry no port/protocol; keep what we learned before.
            if (old != null && !d.hasNetwork) {
                d.port = old.port;
                d.https = old.https;
            }
            devices.put(d.key(), d);
        }
        stateChanged();
    }

    void onIncoming(final Receiver.IncomingRequest req) {
        String what = req.files.size() == 1 ? req.files.get(0).fileName : req.files.size() + " files";
        notifyMgr().notify(NOTIF_INCOMING, Notifs.build(this, req.sender.alias + " wants to send " + what,
                Util.formatSize(req.totalBytes) + " · tap to answer", openApp(), false, true, 0, 0));
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onIncoming(req);
            }
        });
    }

    void clearIncomingNotification() {
        notifyMgr().cancel(NOTIF_INCOMING);
    }

    void onMessage(final Device from, final String text) {
        addLog("Message from " + from.alias + ": " + text);
        notifyMgr().notify(NOTIF_INCOMING, Notifs.build(this, "Message from " + from.alias, text, openApp(), false, true, 0, 0));
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onMessage(from, text);
            }
        });
    }

    void onTransferStarted(Receiver.Session s) {
        acquireWake();
        addLog("Receiving " + s.files.size() + " file(s) from " + s.sender.alias);
        onTransferProgress(s, 0);
    }

    void onTransferProgress(Receiver.Session s, long received) {
        long now = System.currentTimeMillis();
        if (received > 0 && now - lastProgressNotif < 1000) return;
        lastProgressNotif = now;
        int pct = s.totalBytes > 0 ? (int) (received * 100 / s.totalBytes) : 0;
        notifyMgr().notify(NOTIF_TRANSFER, Notifs.build(this, "Receiving from " + s.sender.alias,
                Util.formatSize(received) + " of " + Util.formatSize(s.totalBytes), openApp(), true, false, 100, pct));
    }

    void onTransferFinished(Receiver.Session s, boolean ok) {
        releaseWake();
        notifyMgr().cancel(NOTIF_TRANSFER);
        String msg = ok ? "Received " + s.files.size() + " file(s) from " + s.sender.alias + " → Download/LocalSend"
                : "Transfer from " + s.sender.alias + " was cancelled";
        addLog(msg);
        notifyMgr().notify(NOTIF_INCOMING, Notifs.build(this, ok ? "Transfer complete" : "Transfer cancelled", msg,
                openApp(), false, ok, 0, 0));
    }

    void addLog(final String line) {
        synchronized (log) {
            log.add(0, line);
            while (log.size() > 30) log.remove(log.size() - 1);
        }
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onLog(line);
            }
        });
    }

    private void setStatus(String s) {
        status = s;
        stateChanged();
    }

    private void stateChanged() {
        main.post(new Runnable() {
            @Override public void run() {
                if (listener != null) listener.onStateChanged();
            }
        });
    }

    private NotificationManager notifyMgr() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
