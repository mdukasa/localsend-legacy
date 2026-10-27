package io.lslegacy;

import org.json.JSONObject;

import io.lslegacy.net.HttpClient;

/** A LocalSend peer seen on the network. */
public final class Device {
    public final String ip;
    public int port = Protocol.PORT;
    public boolean https = true;
    public String alias;
    public String version;
    public String deviceModel;
    public String deviceType;
    public String fingerprint;
    public long lastSeen;
    /** Whether port/protocol came from the peer rather than defaults. */
    public boolean hasNetwork;

    public Device(String ip) {
        this.ip = ip;
    }

    /** Parses an announcement / register body. Fields missing from {@code o} keep their defaults. */
    public static Device fromJson(JSONObject o, String ip) {
        Device d = new Device(ip);
        d.alias = Util.str(o, "alias");
        if (d.alias == null) d.alias = ip;
        d.version = Util.str(o, "version");
        d.deviceModel = Util.str(o, "deviceModel");
        d.deviceType = Util.str(o, "deviceType");
        d.fingerprint = Util.str(o, "fingerprint");
        d.port = o.optInt("port", Protocol.PORT);
        String protocol = Util.str(o, "protocol");
        d.https = protocol == null || !"http".equalsIgnoreCase(protocol);
        d.hasNetwork = o.has("port") || o.has("protocol");
        d.lastSeen = System.currentTimeMillis();
        return d;
    }

    public String key() {
        return fingerprint != null && fingerprint.length() > 0 ? fingerprint : ip;
    }

    public HttpClient.Target target() {
        return new HttpClient.Target(ip, port, https, https ? fingerprint : null);
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder(ip);
        if (deviceModel != null) sb.append(" · ").append(deviceModel);
        if (deviceType != null) sb.append(" · ").append(deviceType);
        if (!https) sb.append(" · http");
        return sb.toString();
    }
}
