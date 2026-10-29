package io.lslegacy;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.lslegacy.net.HttpClient;

/**
 * Multicast discovery (announce on 224.0.0.167:53317, answer others via POST /register or a UDP reply),
 * plus the HTTP subnet scan LocalSend falls back to when multicast is filtered.
 */
final class Discovery {
    private static final String TAG = "LsDiscovery";

    private final LsService service;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private MulticastSocket socket;
    private InetAddress group;
    private volatile boolean running;
    private boolean joined;
    private NetworkInterface joinedNif;

    Discovery(LsService service) {
        this.service = service;
    }

    void start() throws IOException {
        group = InetAddress.getByName(Protocol.MULTICAST_GROUP);
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(Protocol.PORT));
        socket.setTimeToLive(4);
        try {
            joinGroup();
        } catch (IOException e) {
            // Sending still works; the next connectivity change retries the join.
            Log.w(TAG, "multicast join failed", e);
        }
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                receiveLoop();
            }
        }, "ls-multicast");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Joins on the local-network interface explicitly; some old ROMs otherwise pick rmnet or nothing.
     * Synchronized with {@link #leaveGroup()}: joining twice on one socket fails with "Address already in use".
     */
    private synchronized void joinGroup() throws IOException {
        leaveGroup();
        NetworkInterface nif = null;
        String ip = Util.localIp(service);
        if (ip != null) {
            try {
                nif = NetworkInterface.getByInetAddress(InetAddress.getByName(ip));
            } catch (IOException ignored) {
            }
        }
        if (nif != null) {
            socket.setNetworkInterface(nif);
            socket.joinGroup(new InetSocketAddress(group, Protocol.PORT), nif);
        } else {
            socket.joinGroup(group);
        }
        joined = true;
        joinedNif = nif;
    }

    /** Leaves exactly the membership {@link #joinGroup()} made, which is per interface. */
    private synchronized void leaveGroup() {
        if (!joined) return;
        try {
            if (joinedNif != null) socket.leaveGroup(new InetSocketAddress(group, Protocol.PORT), joinedNif);
            else socket.leaveGroup(group);
        } catch (IOException ignored) {
        }
        joined = false;
        joinedNif = null;
    }

    /** Re-joins after a network change; a stale membership silently stops receiving. */
    void rejoin() {
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    joinGroup();
                } catch (IOException e) {
                    Log.w(TAG, "rejoin failed", e);
                }
            }
        });
    }

    void stop() {
        running = false;
        if (socket != null) socket.close();
        pool.shutdownNow();
    }

    private void receiveLoop() {
        byte[] buf = new byte[65536];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                String text = new String(p.getData(), p.getOffset(), p.getLength(), "UTF-8");
                handle(new JSONObject(text), p.getAddress().getHostAddress());
            } catch (JSONException e) {
                Log.d(TAG, "bad packet: " + e);
            } catch (IOException e) {
                if (running) Log.w(TAG, "receive failed", e);
            }
        }
    }

    private void handle(JSONObject o, String ip) {
        final Device d = Device.fromJson(o, ip);
        if (service.identity() != null && service.identity().fingerprint.equalsIgnoreCase(d.fingerprint)) return;
        service.onDeviceFound(d);
        // v1 peers call the flag "announcement".
        boolean announce = o.optBoolean("announce", o.optBoolean("announcement", false));
        if (!announce) return;
        pool.execute(new Runnable() {
            @Override public void run() {
                try {
                    HttpClient.Response r = HttpClient.postJson(d.target(), service.identity(),
                            Protocol.API + "register", service.infoJson(true).toString(), HttpClient.CONNECT_TIMEOUT, 15000);
                    if (r.status == 200) return;
                } catch (IOException e) {
                    Log.d(TAG, "register to " + d.ip + " failed: " + e);
                }
                sendUdp(false);
            }
        });
    }

    void announce() {
        pool.execute(new Runnable() {
            @Override public void run() {
                // LocalSend sends a few times; multicast on Wi-Fi drops packets freely.
                long[] delays = {0, 500, 1500};
                for (long delay : delays) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        return;
                    }
                    sendUdp(true);
                }
            }
        });
    }

    private void sendUdp(boolean announce) {
        try {
            JSONObject o = service.infoJson(true);
            o.put("announce", announce);
            o.put("announcement", announce);
            byte[] data = o.toString().getBytes("UTF-8");
            socket.send(new DatagramPacket(data, data.length, group, Protocol.PORT));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "send failed", e);
        }
    }

    /** POSTs /register to every address in our /24, HTTPS first since that is LocalSend's default. */
    void scanSubnet(final Runnable done) {
        final String self = Util.localIp(service);
        if (self == null) {
            if (done != null) done.run();
            return;
        }
        final String prefix = self.substring(0, self.lastIndexOf('.') + 1);
        new Thread(new Runnable() {
            @Override public void run() {
                ExecutorService scan = Executors.newFixedThreadPool(24);
                final String body = service.infoJson(true).toString();
                for (int i = 1; i < 255; i++) {
                    final String ip = prefix + i;
                    if (ip.equals(self)) continue;
                    scan.execute(new Runnable() {
                        @Override public void run() {
                            probe(ip, true, body);
                        }
                    });
                }
                scan.shutdown();
                try {
                    scan.awaitTermination(3, TimeUnit.MINUTES);
                } catch (InterruptedException ignored) {
                }
                if (done != null) done.run();
            }
        }, "ls-scan").start();
    }

    private void probe(String ip, boolean https, String body) {
        try {
            HttpClient.Response r = HttpClient.postJson(new HttpClient.Target(ip, Protocol.PORT, https, null),
                    service.identity(), Protocol.API + "register", body, 1200, 15000);
            if (r.status != 200) return;
            JSONObject o = new JSONObject(r.text());
            o.put("port", Protocol.PORT);
            o.put("protocol", https ? "https" : "http");
            Device d = Device.fromJson(o, ip);
            if (!service.identity().fingerprint.equalsIgnoreCase(d.fingerprint)) service.onDeviceFound(d);
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            // Nothing listening.
        } catch (IOException e) {
            // Port open but TLS failed: maybe a peer with encryption turned off.
            if (https) probe(ip, false, body);
        } catch (JSONException ignored) {
        }
    }
}
