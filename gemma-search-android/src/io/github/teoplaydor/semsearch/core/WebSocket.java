package io.github.teoplaydor.semsearch.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A WebSocket client (RFC 6455), just what a streaming API needs: wss:// (or ws:// for tests), extra headers on the
 * handshake, text and binary messages out (masked, as a client must), messages in (fragments joined), ping answered,
 * close both ways. Android has no WebSocket of its own and the app carries no network library. Messages arrive on a
 * reading thread of its own; sends are safe from any thread.
 */
public final class WebSocket implements Closeable {
    public interface Listener {
        void onText(String text);

        void onBinary(byte[] data);

        /** The connection is over: the peer's close code and reason, or -1 and what went wrong. */
        void onClosed(int code, String reason);
    }

    /** A message bigger than this is refused (the peer misbehaves). */
    static final int MAX_MESSAGE = 16 << 20;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private volatile boolean closed;
    private final Thread reader;

    private WebSocket(Socket socket, InputStream in, Listener listener) throws IOException {
        this.socket = socket;
        this.in = in;
        this.out = socket.getOutputStream();
        this.listener = listener;
        reader = new Thread(new Runnable() {
            @Override
            public void run() {
                read();
            }
        }, "websocket");
        reader.setDaemon(true);
    }

    /** Connected and upgraded (throws with the server's answer if it refused); messages start coming at once. */
    public static WebSocket connect(String url, Map<String, String> headers, int timeoutMs, Listener listener) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket s = new Socket();
        s.connect(new InetSocketAddress(u.getHost(), port), timeoutMs);
        if (tls) {
            SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, u.getHost(), port, true);
            ssl.startHandshake();
            s = ssl;
        }
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = b64(nonce);
        String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(u.getHost()).append(u.getPort() > 0 ? ":" + u.getPort() : "").append("\r\n");
        req.append("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        req.append("\r\n");
        OutputStream o = s.getOutputStream();
        o.write(req.toString().getBytes(StandardCharsets.UTF_8));
        o.flush();
        InputStream in = new BufferedInputStream(s.getInputStream());
        String status = line(in);
        StringBuilder head = new StringBuilder(status);
        String accept = null;
        String l;
        while (!(l = line(in)).isEmpty()) {
            head.append('\n').append(l);
            int c = l.indexOf(':');
            if (c > 0 && l.substring(0, c).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) accept = l.substring(c + 1).trim();
        }
        if (!status.matches("HTTP/1\\.[01] 101.*")) {
            // the refusal's body, for the reason (an error JSON, usually)
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            try {
                while (body.size() < 8192 && (n = in.read(b)) > 0) body.write(b, 0, n);
            } catch (IOException ignored) {
                // what came is enough
            }
            s.close();
            throw new IOException(status + (body.size() > 0 ? " — " + new String(body.toByteArray(), StandardCharsets.UTF_8).trim() : ""));
        }
        if (accept == null || !accept.equals(acceptFor(key))) {
            s.close();
            throw new IOException("WebSocket: неверный ответ сервера (" + head + ")");
        }
        s.setSoTimeout(0);
        WebSocket ws = new WebSocket(s, in, listener);
        ws.reader.start();
        return ws;
    }

    static String acceptFor(String key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            return b64(sha.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("WebSocket: слишком длинная строка ответа");
        }
        if (c < 0 && b.size() == 0) throw new IOException("WebSocket: соединение закрыто при подключении");
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    static String b64(byte[] b) {
        return java.util.Base64.getEncoder().encodeToString(b);
    }

    public void sendText(String text) throws IOException {
        frame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendBinary(byte[] data) throws IOException {
        frame(0x2, data);
    }

    private synchronized void frame(int opcode, byte[] payload) throws IOException {
        if (closed) throw new IOException("WebSocket закрыт");
        int n = payload.length;
        byte[] head = new byte[14];
        int h = 0;
        head[h++] = (byte) (0x80 | opcode);
        if (n < 126) {
            head[h++] = (byte) (0x80 | n);
        } else if (n < 65536) {
            head[h++] = (byte) (0x80 | 126);
            head[h++] = (byte) (n >>> 8);
            head[h++] = (byte) n;
        } else {
            head[h++] = (byte) (0x80 | 127);
            for (int i = 7; i >= 0; i--) head[h++] = (byte) (i >= 4 ? 0 : n >>> (8 * i));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        System.arraycopy(mask, 0, head, h, 4);
        h += 4;
        byte[] masked = new byte[n];
        for (int i = 0; i < n; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        out.write(head, 0, h);
        out.write(masked);
        out.flush();
    }

    private void read() {
        int code = -1;
        String reason = "соединение оборвалось";
        try {
            ByteArrayOutputStream message = null;
            int messageType = 0;
            while (true) {
                int b0 = in.read(), b1 = in.read();
                if (b0 < 0 || b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                long n = b1 & 0x7f;
                if (n == 126) n = (read8() << 8) | read8();
                else if (n == 127) {
                    n = 0;
                    for (int i = 0; i < 8; i++) n = (n << 8) | read8();
                }
                if (n > MAX_MESSAGE) throw new IOException("WebSocket: сообщение слишком большое (" + n + ")");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] p = new byte[(int) n];
                readFully(p);
                if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
                if (op == 0x8) {
                    code = p.length >= 2 ? ((p[0] & 0xff) << 8) | (p[1] & 0xff) : 1005;
                    reason = p.length > 2 ? new String(p, 2, p.length - 2, StandardCharsets.UTF_8) : "";
                    if (!closed) {
                        try {
                            frame(0x8, p.length >= 2 ? new byte[]{p[0], p[1]} : new byte[0]);
                        } catch (IOException ignored) {
                            // closing anyway
                        }
                    }
                    break;
                } else if (op == 0x9) {
                    frame(0xA, p);
                } else if (op == 0xA) {
                    // pong: nothing to do
                } else if (op == 0x1 || op == 0x2 || op == 0x0) {
                    if (op != 0) {
                        message = new ByteArrayOutputStream(Math.max(64, p.length));
                        messageType = op;
                    }
                    if (message == null) throw new IOException("WebSocket: продолжение без начала");
                    message.write(p);
                    if (message.size() > MAX_MESSAGE) throw new IOException("WebSocket: сообщение слишком большое");
                    if (fin) {
                        byte[] all = message.toByteArray();
                        message = null;
                        if (messageType == 0x1) listener.onText(new String(all, StandardCharsets.UTF_8));
                        else listener.onBinary(all);
                    }
                }
            }
        } catch (IOException e) {
            if (!closed) reason = e.getMessage() != null ? e.getMessage() : e.toString();
        } catch (RuntimeException e) {
            reason = "ошибка в обработке: " + e;
        }
        boolean was = closed;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
            // closed
        }
        listener.onClosed(was && code == -1 ? 1000 : code, reason);
    }

    private int read8() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("WebSocket: конец потока");
        return b;
    }

    private void readFully(byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) {
            int n = in.read(b, o, b.length - o);
            if (n < 0) throw new IOException("WebSocket: конец потока");
            o += n;
        }
    }

    public boolean isOpen() {
        return !closed;
    }

    /** A normal close (1000); the reading thread ends when the peer answers, or the socket is shut a second later. */
    @Override
    public void close() {
        if (closed) return;
        try {
            frame(0x8, new byte[]{0x03, (byte) 0xE8});
        } catch (IOException ignored) {
            // gone already
        }
        closed = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    reader.join(1000);
                    socket.close();
                } catch (Exception ignored) {
                    // closed
                }
            }
        }, "websocket-close").start();
    }
}
