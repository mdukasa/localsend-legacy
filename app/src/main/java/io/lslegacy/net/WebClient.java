package io.lslegacy.net;

import org.bouncycastle.tls.AlertDescription;
import org.bouncycastle.tls.CertificateRequest;
import org.bouncycastle.tls.DefaultTlsClient;
import org.bouncycastle.tls.NameType;
import org.bouncycastle.tls.ServerName;
import org.bouncycastle.tls.TlsAuthentication;
import org.bouncycastle.tls.TlsClientProtocol;
import org.bouncycastle.tls.TlsCredentials;
import org.bouncycastle.tls.TlsFatalAlert;
import org.bouncycastle.tls.TlsServerCertificate;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import io.lslegacy.Rand;

/**
 * HTTPS GET for the open internet (GitHub), over BouncyCastle's TLS: Android 2.x – 4.x can't negotiate
 * the TLS versions and ciphers today's servers require. Server certificates are checked against the
 * bundled roots by {@link CertChecker}, at least as late as {@code clockFloor}, so a phone whose clock is
 * in 1980 can still connect. Follows redirects; only https is allowed.
 */
public final class WebClient {
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static BcTlsCrypto crypto;

    private WebClient() {}

    public interface Progress {
        void onBytes(long done, long total) throws IOException;
    }

    /** A failed request whose server answered: the HTTP status is kept. */
    public static final class HttpException extends IOException {
        public final int status;

        HttpException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static synchronized BcTlsCrypto crypto() {
        if (crypto == null) crypto = new BcTlsCrypto(Rand.get());
        return crypto;
    }

    /** GETs {@code url} and returns the body, up to {@code max} bytes. */
    public static byte[] get(String url, String accept, String userAgent, int max, long clockFloor) throws IOException {
        Conn c = open(url, accept, userAgent, clockFloor, null);
        try {
            return HttpIO.readAll(c.body, max);
        } finally {
            c.close();
        }
    }

    /** GETs {@code url} into {@code out}; returns the number of bytes. */
    public static long download(String url, String userAgent, OutputStream out, Progress progress, long clockFloor,
                                HttpClient.Abort abort) throws IOException {
        Conn c = open(url, "application/octet-stream", userAgent, clockFloor, abort);
        try {
            long total = HttpIO.contentLength(c.headers), done = 0;
            byte[] buf = new byte[16384];
            int n;
            while ((n = c.body.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.onBytes(done, total);
            }
            return done;
        } finally {
            c.close();
        }
    }

    private static final class Conn {
        Socket socket;
        TlsClientProtocol tls;
        Map<String, String> headers;
        InputStream body;

        void close() {
            if (tls != null) {
                try {
                    tls.close();
                } catch (IOException ignored) {
                }
            }
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static Conn open(String url, String accept, String userAgent, long clockFloor, HttpClient.Abort abort)
            throws IOException {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri;
            try {
                uri = new URI(url);
            } catch (URISyntaxException e) {
                throw new IOException("Bad address: " + url);
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IOException("Not an https address: " + url);
            }
            String host = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 443;
            String path = uri.getRawPath() == null || uri.getRawPath().length() == 0 ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

            Conn c = new Conn();
            boolean ok = false;
            try {
                c.socket = new Socket();
                if (abort != null) abort.attach(c.socket);
                c.socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
                c.socket.setSoTimeout(READ_TIMEOUT);
                c.tls = new TlsClientProtocol(c.socket.getInputStream(), c.socket.getOutputStream());
                Client client = new Client(host, Math.max(System.currentTimeMillis(), clockFloor));
                try {
                    c.tls.connect(client);
                } catch (IOException e) {
                    // Report why the certificate was refused rather than BouncyCastle's bare alert.
                    if (client.failure != null) throw new IOException(host + ": " + client.failure);
                    throw e;
                }
                OutputStream out = c.tls.getOutputStream();
                String req = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + host + (port != 443 ? ":" + port : "") + "\r\n"
                        + "User-Agent: " + userAgent + "\r\n"
                        + "Accept: " + accept + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(req.getBytes("US-ASCII"));
                out.flush();

                InputStream in = new BufferedInputStream(c.tls.getInputStream(), 16384);
                String status = HttpIO.readLine(in);
                if (status == null) throw new IOException(host + " sent no response");
                String[] parts = status.split(" ");
                if (parts.length < 2) throw new IOException("Bad status line: " + status);
                int code;
                try {
                    code = Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    throw new IOException("Bad status line: " + status);
                }
                c.headers = HttpIO.readHeaders(in);
                c.body = HttpIO.body(in, c.headers, true);

                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = c.headers.get("location");
                    if (location == null) throw new IOException("Redirect without a location");
                    url = uri.resolve(location).toString();
                    continue; // closes this connection in finally
                }
                if (code != 200) {
                    String detail = "";
                    try {
                        detail = new String(HttpIO.readAll(c.body, 4096), "UTF-8");
                    } catch (IOException ignored) {
                    }
                    throw new HttpException(code, host + " answered " + code
                            + (detail.contains("rate limit") ? " (too many requests; try again in an hour)" : ""));
                }
                ok = true;
                return c;
            } finally {
                if (!ok) c.close();
            }
        }
        throw new IOException("Too many redirects");
    }

    /** TLS with the host name sent (SNI, which shared hosts like GitHub's need) and the chain checked. */
    private static final class Client extends DefaultTlsClient {
        private final String host;
        private final long now;
        String failure;

        Client(String host, long now) {
            super(crypto());
            this.host = host;
            this.now = now;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        @Override protected Vector getSNIServerNames() {
            Vector names = new Vector();
            try {
                names.add(new ServerName(NameType.host_name, host.getBytes("US-ASCII")));
            } catch (java.io.UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
            return names;
        }

        @Override public TlsAuthentication getAuthentication() {
            return new TlsAuthentication() {
                @Override public void notifyServerCertificate(TlsServerCertificate server) throws IOException {
                    org.bouncycastle.tls.Certificate chain = server.getCertificate();
                    List<byte[]> ders = new ArrayList<byte[]>();
                    for (int i = 0; chain != null && i < chain.getLength(); i++) {
                        ders.add(chain.getCertificateAt(i).getEncoded());
                    }
                    try {
                        CertChecker.check(ders, host, now);
                    } catch (IOException e) {
                        failure = e.getMessage();
                        throw new TlsFatalAlert(AlertDescription.bad_certificate);
                    }
                }

                @Override public TlsCredentials getClientCredentials(CertificateRequest request) {
                    return null;
                }
            };
        }
    }
}
