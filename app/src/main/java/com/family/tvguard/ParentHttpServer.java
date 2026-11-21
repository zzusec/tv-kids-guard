package com.family.tvguard;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Small, bounded HTTP/1.1 listener for the television's parent console. No external runtime. */
final class ParentHttpServer implements Closeable {
    static final int PORT = 8888;
    static final int MAX_BODY_BYTES = 16 * 1024;
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 5000;

    interface Handler {
        Response handle(Request request) throws Exception;
    }

    interface FailureListener {
        void onFailure(String message, Exception error);
    }

    static final class Request {
        final String method;
        final String path;
        final String body;
        private final Map<String, String> headers;

        Request(String method, String path, String body, Map<String, String> headers) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.headers = Collections.unmodifiableMap(headers);
        }

        String header(String name) {
            String value = headers.get(name.toLowerCase(Locale.US));
            return value == null ? "" : value;
        }
    }

    static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();

        private Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }

        static Response text(int status, String contentType, String body) {
            return new Response(status, contentType, body.getBytes(StandardCharsets.UTF_8));
        }

        Response header(String name, String value) {
            if (!name.matches("[A-Za-z0-9-]+") || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Invalid response header");
            }
            if (name.equalsIgnoreCase("Content-Length") || name.equalsIgnoreCase("Connection")) {
                throw new IllegalArgumentException("Framing headers belong to the HTTP transport");
            }
            headers.put(name, value);
            return this;
        }
    }

    private final Handler handler;
    private final FailureListener failureListener;
    private final int requestedPort;
    private final List<InetAddress> fallbackAddresses;
    private final List<ServerSocket> listeners = new CopyOnWriteArrayList<>();
    private final Set<Socket> sockets = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "tvguard-http-request");
                thread.setDaemon(true);
                return thread;
            });
    private volatile ServerSocket listener;
    private volatile boolean closed;
    private volatile String lastFailure = "";

    ParentHttpServer(Handler handler) {
        this(handler, PORT);
    }

    /** Port zero is used only by real-socket tests. */
    ParentHttpServer(Handler handler, int port) {
        this(handler, port, null);
    }

    ParentHttpServer(Handler handler, int port, List<InetAddress> fallbackAddresses) {
        this(handler, port, fallbackAddresses, null);
    }

    ParentHttpServer(Handler handler, int port, List<InetAddress> fallbackAddresses,
                     FailureListener failureListener) {
        this.handler = handler;
        this.requestedPort = port;
        this.fallbackAddresses = fallbackAddresses;
        this.failureListener = failureListener;
    }

    synchronized void start() throws IOException {
        if (closed) throw new IOException("HTTP server was closed");
        if (listener != null) return;
        try {
            ServerSocket socket = bindSocket(new InetSocketAddress("0.0.0.0", requestedPort));
            listeners.add(socket);
            listener = socket;
        } catch (BindException primary) {
            if (requestedPort <= 0) throw primary;
            List<InetAddress> candidates = fallbackAddresses != null
                    ? fallbackAddresses : lanAddresses();
            for (InetAddress address : candidates) {
                try {
                    listeners.add(bindSocket(new InetSocketAddress(address, requestedPort)));
                } catch (IOException ignored) {
                }
            }
            if (listeners.isEmpty()) throw primary;
            listener = listeners.get(0);
        }
        lastFailure = "";
        for (ServerSocket bound : listeners) {
            Thread acceptor = new Thread(() -> accept(bound), "tvguard-http-listener");
            acceptor.setDaemon(true);
            acceptor.start();
        }
    }

    private static ServerSocket bindSocket(InetSocketAddress endpoint) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(endpoint, 12);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    static List<InetAddress> lanAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return addresses;
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> inetAddresses = iface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress address = inetAddresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress() && !addresses.contains(address)) {
                        addresses.add(address);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return addresses;
    }

    int port() {
        ServerSocket socket = listener;
        return socket == null ? requestedPort : socket.getLocalPort();
    }

    String lastFailure() {
        return lastFailure;
    }

    synchronized boolean isListening() {
        return !listeners.isEmpty();
    }

    private synchronized void listenerFailed(ServerSocket failed, IOException error) {
        try { failed.close(); } catch (IOException ignored) { }
        listeners.remove(failed);
        if (listener == failed) listener = listeners.isEmpty() ? null : listeners.get(0);
        if (closed) return;
        lastFailure = "HTTP listener: " + error;
        if (failureListener != null) failureListener.onFailure(lastFailure, error);
    }

    private void accept(ServerSocket activeListener) {
        while (!closed) {
            try {
                Socket socket = activeListener.accept();
                long acceptedAt = System.nanoTime();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                sockets.add(socket);
                try {
                    workers.execute(() -> serve(socket, acceptedAt));
                } catch (RejectedExecutionException e) {
                    sockets.remove(socket);
                    socket.close();
                }
            } catch (IOException e) {
                listenerFailed(activeListener, e);
                break;
            }
        }
    }

    private void serve(Socket socket, long acceptedAt) {
        try {
            Response response;
            boolean rejected = false;
            try {
                Request request = read(new java.io.BufferedInputStream(
                        new DeadlineInputStream(socket, acceptedAt, SOCKET_TIMEOUT_MS)));
                response = handler.handle(request);
                if (response == null) throw new IllegalStateException("No HTTP response");
            } catch (HttpError e) {
                rejected = true;
                response = error(e.status, e.getMessage());
            } catch (Exception e) {
                lastFailure = "HTTP request: " + e.getClass().getSimpleName();
                response = error(500, "后台处理失败，请稍后重试");
            }
            write(socket.getOutputStream(), response);
            if (rejected) {
                // Send FIN before discarding an already-sent rejected body. Closing with unread
                // bytes would reset the TCP connection and hide the useful 4xx response.
                socket.shutdownOutput();
                InputStream cleanup = new DeadlineInputStream(socket, System.nanoTime(), 150);
                byte[] discarded = new byte[1024];
                int left = MAX_BODY_BYTES;
                try {
                    while (left > 0) {
                        int count = cleanup.read(discarded, 0,
                                Math.min(left, discarded.length));
                        if (count < 0) break;
                        left -= count;
                    }
                } catch (IOException ignored) {
                    // The error response has already been flushed; bounded draining is cleanup.
                }
            }
        } catch (IOException e) {
            if (!closed) lastFailure = "HTTP connection: " + e.getClass().getSimpleName();
        } finally {
            sockets.remove(socket);
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** A peer cannot reset its total header/body budget by trickling bytes below SO_TIMEOUT. */
    private static final class DeadlineInputStream extends java.io.FilterInputStream {
        private final Socket socket;
        private final long deadline;

        DeadlineInputStream(Socket socket, long acceptedAt, long timeoutMillis) throws IOException {
            super(socket.getInputStream());
            this.socket = socket;
            deadline = acceptedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        }

        private void checkDeadline() throws IOException {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) throw new java.net.SocketTimeoutException("HTTP request deadline exceeded");
            socket.setSoTimeout((int) Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }

        @Override public int read() throws IOException {
            checkDeadline();
            return in.read();
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            checkDeadline();
            return in.read(bytes, offset, length);
        }
    }

    private Request read(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = {'\r', '\n', '\r', '\n'};
        while (matched < end.length) {
            int value = input.read();
            if (value < 0) throw new HttpError(400, "请求不完整");
            if (value > 127 || value == 0) throw new HttpError(400, "请求头无效");
            header.write(value);
            if (header.size() > MAX_HEADER_BYTES) throw new HttpError(431, "请求头过大");
            matched = value == end[matched] ? matched + 1 : (value == '\r' ? 1 : 0);
        }
        String[] lines = header.toString("US-ASCII").split("\r\n", -1);
        String[] first = lines[0].split(" ", -1);
        if (first.length != 3 || !first[2].equals("HTTP/1.1") || !first[1].startsWith("/")
                || first[1].startsWith("//") || first[1].indexOf('#') >= 0
                || first[1].indexOf('\\') >= 0 || first[1].matches(".*[\\x00-\\x20\\x7f].*")) {
            throw new HttpError(400, "请求格式无效");
        }
        String method = first[0];
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new HttpError(405, "仅支持 GET 和 POST");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length - 2; index++) {
            String line = lines[index];
            int colon = line.indexOf(':');
            if (colon <= 0 || !line.substring(0, colon).matches("[A-Za-z0-9-]+")) {
                throw new HttpError(400, "请求头无效");
            }
            String name = line.substring(0, colon).toLowerCase(Locale.US);
            String value = line.substring(colon + 1).trim();
            if (value.matches(".*[\\x00-\\x08\\x0a-\\x1f\\x7f].*")) {
                throw new HttpError(400, "请求头无效");
            }
            // Reject ambiguous framing and authentication, including duplicate Cookie/Host.
            if (headers.put(name, value) != null) throw new HttpError(400, "重复请求头");
        }
        String host = headers.get("host");
        if (!validHost(host, port())) throw new HttpError(403, "请通过电视 IP 访问后台");
        if (headers.containsKey("transfer-encoding")) throw new HttpError(400, "不支持分块请求");
        int length = 0;
        String lengthValue = headers.get("content-length");
        if (lengthValue != null) {
            if (!lengthValue.matches("[0-9]{1,8}")) throw new HttpError(400, "请求长度无效");
            length = Integer.parseInt(lengthValue);
            if (length >= MAX_BODY_BYTES) throw new HttpError(413, "请求内容过大");
        }
        if (method.equals("GET") && length != 0) throw new HttpError(400, "GET 请求不接受内容");
        if (method.equals("POST")) {
            String contentType = headers.get("content-type");
            if (contentType == null || !contentType.toLowerCase(Locale.US)
                    .matches("application/json(?:\\s*;\\s*charset=utf-8)?")) {
                throw new HttpError(415, "请使用 JSON 请求");
            }
            String origin = headers.get("origin");
            if (origin != null && !origin.equalsIgnoreCase("http://" + host)) {
                throw new HttpError(403, "不接受其他网站发起的操作");
            }
            String fetchSite = headers.get("sec-fetch-site");
            if (fetchSite != null && !fetchSite.equals("same-origin") && !fetchSite.equals("none")) {
                throw new HttpError(403, "不接受其他网站发起的操作");
            }
        }
        byte[] body = new byte[length];
        int count = 0;
        while (count < length) {
            int size = input.read(body, count, length - count);
            if (size < 0) throw new HttpError(400, "请求内容不完整");
            count += size;
        }
        return new Request(method, first[1].split("\\?", 2)[0],
                new String(body, StandardCharsets.UTF_8), headers);
    }

    static boolean validHost(String host, int port) {
        if (host == null || !host.endsWith(":" + port)) return false;
        String address = host.substring(0, host.length() - (":" + port).length());
        if (address.equalsIgnoreCase("localhost")) return true;
        if (address.startsWith("[") && address.endsWith("]")) {
            String literal = address.substring(1, address.length() - 1);
            if (!literal.matches("[0-9a-fA-F:.]+") || !literal.contains(":")) return false;
            try {
                return java.net.InetAddress.getByName(literal) instanceof java.net.Inet6Address;
            } catch (IOException e) {
                return false;
            }
        }
        String[] parts = address.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    private static Response error(int status, String message) {
        // All transport messages are fixed strings, never reflected request content.
        return Response.text(status, "application/json; charset=utf-8", "{\"error\":\"" + message + "\"}");
    }

    private static void write(OutputStream output, Response response) throws IOException {
        StringBuilder header = new StringBuilder("HTTP/1.1 ").append(response.status)
                .append(' ').append(reason(response.status)).append("\r\n")
                .append("Content-Type: ").append(response.contentType).append("\r\n")
                .append("Content-Length: ").append(response.body.length).append("\r\n")
                .append("Connection: close\r\nCache-Control: no-store\r\n")
                .append("X-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\n")
                .append("Referrer-Policy: no-referrer\r\n")
                .append("Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; ")
                .append("img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; ")
                .append("base-uri 'none'; form-action 'self'\r\n");
        for (Map.Entry<String, String> extra : response.headers.entrySet()) {
            header.append(extra.getKey()).append(": ").append(extra.getValue()).append("\r\n");
        }
        header.append("\r\n");
        output.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        output.write(response.body);
        output.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Content Too Large";
            case 415: return "Unsupported Media Type";
            case 429: return "Too Many Requests";
            case 431: return "Request Header Fields Too Large";
            case 503: return "Service Unavailable";
            default: return "Internal Server Error";
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (ServerSocket bound : listeners) {
            try { bound.close(); } catch (IOException ignored) { }
        }
        listeners.clear();
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) { }
        }
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        sockets.clear();
        workers.shutdownNow();
    }

    private static final class HttpError extends IOException {
        final int status;
        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
