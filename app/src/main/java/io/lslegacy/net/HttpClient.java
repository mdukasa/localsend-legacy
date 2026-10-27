package io.lslegacy.net;

import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsFatalAlertReceived;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.Map;

import io.lslegacy.Identity;
import io.lslegacy.Rand;

/**
 * HTTP/1.1 client over plain sockets, with HTTPS via BouncyCastle's pure-Java TLS.
 * The platform TLS on Android 4.x lacks AES-GCM, which LocalSend's server requires,
 * and LocalSend also requires a client certificate, which this presents.
 */
public final class HttpClient {
    private HttpClient() {}

    public static final int CONNECT_TIMEOUT = 4000;

    private static BcTlsCrypto crypto;
    /** Flipped off if a peer refuses ChaCha20-only offers. */
    private static volatile boolean preferChaCha = true;

    public interface BodyWriter {
        void write(OutputStream out) throws IOException;
    }

    public static final class Response {
        public final int status;
        public final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        public String text() {
            try {
                return new String(body, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Where to connect: address, scheme and, for HTTPS, the certificate fingerprint to pin (may be null). */
    public static final class Target {
        public final String ip;
        public final int port;
        public final boolean https;
        public final String fingerprint;

        public Target(String ip, int port, boolean https, String fingerprint) {
            this.ip = ip;
            this.port = port;
            this.https = https;
            this.fingerprint = fingerprint;
        }
    }

    private static synchronized BcTlsCrypto crypto() {
        if (crypto == null) crypto = new BcTlsCrypto(Rand.get());
        return crypto;
    }

    public static Response postJson(Target t, Identity id, String pathQuery, String json, int connectTimeout, int readTimeout)
            throws IOException {
        final byte[] bytes = json.getBytes("UTF-8");
        return request(t, id, "POST", pathQuery, "application/json", bytes.length, new BodyWriter() {
            @Override public void write(OutputStream out) throws IOException {
                out.write(bytes);
            }
        }, connectTimeout, readTimeout);
    }

    public static Response request(Target t, Identity id, String method, String pathQuery, String contentType,
                                   long contentLength, BodyWriter body, int connectTimeout, int readTimeout)
            throws IOException {
        boolean chacha = t.https && preferChaCha;
        try {
            return requestOnce(t, id, method, pathQuery, contentType, contentLength, body, connectTimeout, readTimeout, chacha);
        } catch (TlsFatalAlertReceived e) {
            if (!chacha) throw e;
            preferChaCha = false;
            return requestOnce(t, id, method, pathQuery, contentType, contentLength, body, connectTimeout, readTimeout, false);
        }
    }

    private static Response requestOnce(Target t, Identity id, String method, String pathQuery, String contentType,
                                        long contentLength, BodyWriter body, int connectTimeout, int readTimeout,
                                        boolean chachaOnly) throws IOException {
        Socket s = new Socket();
        TlsClientProtocol tls = null;
        try {
            s.connect(new InetSocketAddress(t.ip, t.port), connectTimeout);
            s.setSoTimeout(readTimeout);
            s.setTcpNoDelay(true);
            InputStream rawIn;
            OutputStream rawOut;
            if (t.https) {
                tls = new TlsClientProtocol(s.getInputStream(), s.getOutputStream());
                tls.connect(new LsTlsClient(crypto(), id, t.fingerprint, chachaOnly));
                rawIn = tls.getInputStream();
                rawOut = tls.getOutputStream();
            } else {
                rawIn = s.getInputStream();
                rawOut = s.getOutputStream();
            }
            OutputStream out = new BufferedOutputStream(rawOut, 16384);
            InputStream in = new BufferedInputStream(rawIn, 16384);

            StringBuilder h = new StringBuilder();
            h.append(method).append(' ').append(pathQuery).append(" HTTP/1.1\r\n");
            h.append("Host: ").append(t.ip).append(':').append(t.port).append("\r\n");
            h.append("User-Agent: LocalSendLegacy\r\n");
            h.append("Accept: */*\r\n");
            h.append("Connection: close\r\n");
            if (body != null || "POST".equals(method)) {
                if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
                h.append("Content-Length: ").append(Math.max(0, contentLength)).append("\r\n");
            }
            h.append("\r\n");
            out.write(h.toString().getBytes("US-ASCII"));
            if (body != null) body.write(out);
            out.flush();

            String status = HttpIO.readLine(in);
            if (status == null) throw new IOException("Empty response");
            String[] parts = status.split(" ");
            if (parts.length < 2) throw new IOException("Bad status line: " + status);
            int code = Integer.parseInt(parts[1]);
            Map<String, String> headers = HttpIO.readHeaders(in);
            byte[] respBody = code == 204 || "HEAD".equals(method)
                    ? new byte[0] : HttpIO.readAll(HttpIO.body(in, headers, true), 4 * 1024 * 1024);
            return new Response(code, respBody);
        } finally {
            if (tls != null) {
                try {
                    tls.close();
                } catch (IOException ignored) {
                }
            }
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
