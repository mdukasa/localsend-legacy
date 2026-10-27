package io.lslegacy;

/** LocalSend protocol v2 constants (https://github.com/localsend/protocol). */
public final class Protocol {
    private Protocol() {}

    public static final String VERSION = "2.1";
    public static final String MULTICAST_GROUP = "224.0.0.167";
    public static final int PORT = 53317;
    public static final String API = "/api/localsend/v2/";
}
