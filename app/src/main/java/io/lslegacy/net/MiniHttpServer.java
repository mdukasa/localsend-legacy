package io.lslegacy.net;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A tiny thread-per-connection HTTP/1.1 server (one request per connection). */
public final class MiniHttpServer {
    private static final String TAG = "LsHttpServer";

    public interface Handler {
        void handle(Request req, Response res) throws Exception;
    }

    public static final class Request {
        public String method;
        public String path;
        public String remoteIp;
        public final Map<String, String> query = new HashMap<String, String>();
        public Map<String, String> headers;
        public InputStream body;

        public byte[] readBody(int max) throws IOException {
            return HttpIO.readAll(body, max);
        }

        public long contentLength() {
            return HttpIO.contentLength(headers);
        }
    }

    public static final class Response {
        int status = 200;
        String contentType = null;
        byte[] body = new byte[0];

        public void status(int status) {
            this.status = status;
        }

        public void json(int status, String json) {
            this.status = status;
            this.contentType = "application/json";
            try {
                this.body = json.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new AssertionError(e);
            }
        }
    }

    private final Handler handler;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile boolean running;

    public MiniHttpServer(Handler handler) {
        this.handler = handler;
    }

    public void start(int port) throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                acceptLoop();
            }
        }, "ls-http-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = serverSocket.accept();
                pool.execute(new Runnable() {
                    @Override public void run() {
                        serve(s);
                    }
                });
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(60000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 65536);
            OutputStream out = s.getOutputStream();
            String line = HttpIO.readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;

            Request req = new Request();
            req.method = parts[0].toUpperCase();
            req.remoteIp = s.getInetAddress().getHostAddress();
            String target = parts[1];
            int q = target.indexOf('?');
            req.path = q >= 0 ? target.substring(0, q) : target;
            if (q >= 0) parseQuery(target.substring(q + 1), req.query);
            req.headers = HttpIO.readHeaders(in);
            if ("100-continue".equalsIgnoreCase(req.headers.get("expect"))) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("US-ASCII"));
                out.flush();
            }
            req.body = HttpIO.body(in, req.headers, false);

            Response res = new Response();
            try {
                handler.handle(req, res);
            } catch (Exception e) {
                Log.w(TAG, "handler failed for " + req.path, e);
                res = new Response();
                res.json(500, "{\"message\":\"Internal error\"}");
            }

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(res.status).append(' ').append(HttpIO.reason(res.status)).append("\r\n");
            if (res.contentType != null) h.append("Content-Type: ").append(res.contentType).append("\r\n");
            h.append("Content-Length: ").append(res.body.length).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes("US-ASCII"));
            out.write(res.body);
            out.flush();
        } catch (IOException e) {
            Log.d(TAG, "connection error: " + e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void parseQuery(String qs, Map<String, String> out) {
        for (String pair : qs.split("&")) {
            if (pair.length() == 0) continue;
            int eq = pair.indexOf('=');
            try {
                String k = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, "UTF-8");
                String v = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), "UTF-8") : "";
                out.put(k, v);
            } catch (UnsupportedEncodingException | IllegalArgumentException ignored) {
            }
        }
    }
}
