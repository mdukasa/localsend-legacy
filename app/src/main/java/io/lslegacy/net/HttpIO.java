package io.lslegacy.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Minimal HTTP/1.1 wire helpers shared by the server and the client. */
public final class HttpIO {
    private HttpIO() {}

    /** Reads one CRLF/LF-terminated ASCII line, or null at EOF. */
    public static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 16384) throw new IOException("Header line too long");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Reads headers up to the blank line. Keys are lower-cased. */
    public static Map<String, String> readHeaders(InputStream in) throws IOException {
        Map<String, String> h = new HashMap<String, String>();
        String line;
        int count = 0;
        while ((line = readLine(in)) != null && line.length() > 0) {
            if (++count > 200) throw new IOException("Too many headers");
            int i = line.indexOf(':');
            if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(), line.substring(i + 1).trim());
        }
        return h;
    }

    public static long contentLength(Map<String, String> h) {
        String v = h.get("content-length");
        if (v == null) return -1;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Wraps {@code in} so that it ends where the message body ends.
     * @param untilEof whether a body with no length (a response) runs to EOF; requests get an empty body.
     */
    public static InputStream body(InputStream in, Map<String, String> h, boolean untilEof) {
        String te = h.get("transfer-encoding");
        if (te != null && te.toLowerCase().contains("chunked")) return new ChunkedInputStream(in);
        long len = contentLength(h);
        if (len >= 0) return new LimitedInputStream(in, len);
        return untilEof ? in : new LimitedInputStream(in, 0);
    }

    public static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > max) throw new IOException("Body too large");
        }
        return out.toByteArray();
    }

    public static String reason(int status) {
        switch (status) {
            case 100: return "Continue";
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            default: return status >= 500 ? "Internal Server Error" : "Unknown";
        }
    }

    static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream in, long length) {
            this.in = in;
            this.remaining = length;
        }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int c = in.read();
            if (c == -1) throw new IOException("Connection closed mid-body");
            remaining--;
            return c;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n == -1) throw new IOException("Connection closed mid-body");
            remaining -= n;
            return n;
        }

        @Override public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }
    }

    static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long chunkLeft = 0;
        private boolean eof = false;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        private boolean nextChunk() throws IOException {
            if (eof) return false;
            if (chunkLeft == 0) {
                String line = readLine(in);
                // The CRLF that ends the previous chunk's data shows up as an empty line.
                if (line != null && line.length() == 0) line = readLine(in);
                if (line == null) throw new IOException("Connection closed in chunked body");
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                chunkLeft = Long.parseLong(line.trim(), 16);
                if (chunkLeft == 0) {
                    readHeaders(in); // trailers
                    eof = true;
                    return false;
                }
            }
            return true;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n == -1 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (!nextChunk()) return -1;
            int n = in.read(b, off, (int) Math.min(len, chunkLeft));
            if (n == -1) throw new IOException("Connection closed in chunked body");
            chunkLeft -= n;
            return n;
        }
    }
}
