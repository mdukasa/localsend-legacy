package io.lslegacy;

import android.content.Context;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Locale;

public final class Util {
    private Util() {}

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public static String hex(byte[] b) {
        char[] c = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            c[i * 2] = HEX[(b[i] >> 4) & 0xf];
            c[i * 2 + 1] = HEX[b[i] & 0xf];
        }
        return new String(c);
    }

    /** Upper-case hex SHA-256, the format LocalSend uses for certificate fingerprints. */
    public static String sha256Hex(byte[] data) {
        return hex(sha256().digest(data));
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /** org.json on old Android returns "null" for JSON nulls in optString; this returns real null. */
    public static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        return o.optString(key, null);
    }

    /**
     * The device's local-network IPv4 address: Wi-Fi if connected, otherwise Ethernet (TV boxes,
     * tablets with adapters) or USB/Wi-Fi tethering. Mobile data interfaces are ignored. Null if none.
     */
    public static String localIp(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null && wm.getConnectionInfo() != null) {
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                return String.format(Locale.US, "%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        }
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (!nif.getName().matches("(eth|wlan|swlan|ap|rndis|usb|bt-pan)\\d*.*")) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public interface CopyProgress {
        void onBytes(long total);
    }

    public static long copy(InputStream in, OutputStream out, MessageDigest digest, CopyProgress p) throws IOException {
        byte[] buf = new byte[32768];
        long total = 0;
        long lastReport = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (digest != null) digest.update(buf, 0, n);
            total += n;
            if (p != null && total - lastReport >= 256 * 1024) {
                lastReport = total;
                p.onBytes(total);
            }
        }
        if (p != null) p.onBytes(total);
        return total;
    }

    public static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
