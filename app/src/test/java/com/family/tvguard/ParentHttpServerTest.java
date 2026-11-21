package com.family.tvguard;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Uses the real listener and socket parser rather than a simulated request object. */
public class ParentHttpServerTest {
    private ParentHttpServer server;
    private AtomicInteger handled;
    private java.util.concurrent.CountDownLatch failedListener;
    private volatile String reportedFailure;

    @Before
    public void setUp() throws Exception {
        handled = new AtomicInteger();
        failedListener = new java.util.concurrent.CountDownLatch(1);
        server = new ParentHttpServer(request -> {
            handled.incrementAndGet();
            return ParentHttpServer.Response.text(200, "text/plain; charset=utf-8",
                    request.method + " " + request.path + " 电视 " + request.body);
        }, 0, null, (message, failure) -> {
            reportedFailure = message;
            failedListener.countDown();
        });
        server.start();
    }

    @After
    public void tearDown() {
        server.close();
    }

    @Test
    public void servesUtf8WithByteLengthAndSecurityHeaders() throws Exception {
        String response = send("GET /?v=2 HTTP/1.1\r\nHost: " + host() + "\r\n\r\n");
        assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"));
        assertTrue(response.endsWith("GET / 电视 "));
        assertTrue(response.contains("Content-Length: 13\r\n"));
        assertTrue(response.contains("Cache-Control: no-store\r\n"));
        assertTrue(response.contains("X-Content-Type-Options: nosniff\r\n"));
        assertTrue(response.contains("X-Frame-Options: DENY\r\n"));
        assertTrue(response.contains("frame-ancestors 'none'"));
        assertFalse(response.contains("Access-Control-Allow-Origin"));
    }

    @Test
    public void acceptsJsonFromItsOwnOrigin() throws Exception {
        String json = "{\"pin\":\"123456\"}";
        String response = send(post(json, "Origin: http://" + host() + "\r\n"));
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertTrue(response.endsWith(json));
        assertEquals(1, handled.get());
    }

    @Test
    public void rejectsForeignOriginBeforeHandlerIsInvoked() throws Exception {
        assertTrue(send(post("{}", "Origin: http://attacker.example\r\n"))
                .startsWith("HTTP/1.1 403"));
        assertEquals(0, handled.get());
    }

    @Test
    public void rejectsCrossSiteFetchEvenWithoutOrigin() throws Exception {
        assertTrue(send(post("{}", "Sec-Fetch-Site: cross-site\r\n"))
                .startsWith("HTTP/1.1 403"));
        assertEquals(0, handled.get());
    }

    @Test
    public void rejectsSimpleFormSubmissionsAndPreflight() throws Exception {
        assertTrue(send("POST /api/login HTTP/1.1\r\nHost: " + host()
                + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: 0\r\n\r\n")
                .startsWith("HTTP/1.1 415"));
        assertTrue(send("OPTIONS /api/login HTTP/1.1\r\nHost: " + host() + "\r\n\r\n")
                .startsWith("HTTP/1.1 405"));
        assertEquals(0, handled.get());
    }

    @Test
    public void rejectsDnsRebindingHostAndAmbiguousFraming() throws Exception {
        assertTrue(send("GET / HTTP/1.1\r\nHost: attacker.example:" + server.port() + "\r\n\r\n")
                .startsWith("HTTP/1.1 403"));
        assertTrue(send(post("{}", "Content-Length: 2\r\n"))
                .startsWith("HTTP/1.1 400"));
        assertTrue(send(post("{}", "Transfer-Encoding: chunked\r\n"))
                .startsWith("HTTP/1.1 400"));
        assertEquals(0, handled.get());
    }

    @Test
    public void boundsHeaderAndBodyBeforeAllocatingPayload() throws Exception {
        assertTrue(send("POST /api/login HTTP/1.1\r\nHost: " + host()
                + "\r\nContent-Type: application/json\r\nContent-Length: 16384\r\n\r\n")
                .startsWith("HTTP/1.1 413"));
        char[] chars = new char[9000];
        java.util.Arrays.fill(chars, 'a');
        assertTrue(send("GET / HTTP/1.1\r\nHost: " + host() + "\r\nX-Padding: "
                + new String(chars) + "\r\n\r\n").startsWith("HTTP/1.1 431"));
        assertEquals(0, handled.get());
    }

    @Test
    public void acceptsOnlyExplicitAddressLiteralsOrLocalhostWithItsPort() {
        assertEquals(8888, ParentHttpServer.PORT);
        assertTrue(ParentHttpServer.validHost("192.168.2.51:8888", 8888));
        assertTrue(ParentHttpServer.validHost("localhost:8888", 8888));
        assertTrue(ParentHttpServer.validHost("[::1]:8888", 8888));
        assertFalse(ParentHttpServer.validHost("192.168.2.51", 8888));
        assertFalse(ParentHttpServer.validHost("999.168.2.51:8888", 8888));
        assertFalse(ParentHttpServer.validHost("127.1:8888", 8888));
        assertFalse(ParentHttpServer.validHost("192.168.2.51.example:8888", 8888));
        assertFalse(ParentHttpServer.validHost("192.168.2.51:80", 8888));
    }

    @Test
    public void closingListenerReleasesThePortForServiceRestart() throws Exception {
        int port = server.port();
        server.close();
        ParentHttpServer restarted = new ParentHttpServer(request ->
                ParentHttpServer.Response.text(200, "text/plain", "restarted"), port);
        try {
            restarted.start();
            server = restarted;
            assertTrue(send("GET / HTTP/1.1\r\nHost: " + host() + "\r\n\r\n")
                    .endsWith("restarted"));
        } finally {
            restarted.close();
        }
    }

    @Test
    public void rejectedRequestsCannotTrickleBodiesToKeepTheCleanupWorkers() throws Exception {
        java.util.concurrent.ScheduledExecutorService writer =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try (Socket first = new Socket("127.0.0.1", server.port());
                Socket second = new Socket("127.0.0.1", server.port())) {
            String invalid = "POST /api/action HTTP/1.1\r\nHost: " + host()
                    + "\r\nContent-Type: text/plain\r\nContent-Length: 10000\r\n\r\n";
            for (Socket socket : new Socket[]{first, second}) {
                socket.setSoTimeout(5000);
                socket.getOutputStream().write(invalid.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
            }
            writer.scheduleAtFixedRate(() -> {
                for (Socket socket : new Socket[]{first, second}) {
                    try { socket.getOutputStream().write('x'); socket.getOutputStream().flush(); }
                    catch (java.io.IOException ignored) { /* Deadline cleanup closes this peer. */ }
                }
            }, 0, 20, java.util.concurrent.TimeUnit.MILLISECONDS);
            for (Socket socket : new Socket[]{first, second}) {
                String status = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                assertTrue(status, status.startsWith("HTTP/1.1 415"));
            }

            assertTrue("错误响应后的清理也必须有总期限，不得占住所有worker",
                    send("GET / HTTP/1.1\r\nHost: " + host() + "\r\n\r\n")
                            .startsWith("HTTP/1.1 200"));
            assertEquals(1, handled.get());
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    public void tricklingClientsCannotKeepEveryWorkerPastTheRequestDeadline() throws Exception {
        java.util.concurrent.ScheduledExecutorService writer =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try (Socket first = new Socket("127.0.0.1", server.port());
                Socket second = new Socket("127.0.0.1", server.port())) {
            // Neither peer completes a header; a read-idle timeout alone is continually reset.
            writer.scheduleAtFixedRate(() -> {
                for (Socket socket : new Socket[]{first, second}) {
                    try { socket.getOutputStream().write('G'); socket.getOutputStream().flush(); }
                    catch (java.io.IOException ignored) { /* Expected when the total deadline closes it. */ }
                }
            }, 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
            java.util.concurrent.CountDownLatch trickling = new java.util.concurrent.CountDownLatch(1);
            writer.schedule(trickling::countDown, 1, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(trickling.await(2, java.util.concurrent.TimeUnit.SECONDS));

            assertTrue("慢连接总期限到期后必须释放线程处理正常请求",
                    send("GET / HTTP/1.1\r\nHost: " + host() + "\r\n\r\n")
                            .startsWith("HTTP/1.1 200"));
            assertEquals(1, handled.get());
        } finally {
            writer.shutdownNow();
        }
    }

    @Test
    public void aFailedListenerCanBeReboundByTheNextStart() throws Exception {
        // Force the same accept() failure as a broken listening socket, without closing the server.
        java.lang.reflect.Field listener = ParentHttpServer.class.getDeclaredField("listener");
        listener.setAccessible(true);
        ((java.net.ServerSocket) listener.get(server)).close();
        assertTrue("监听异常必须通知后台服务而非仅保存在私有字段",
                failedListener.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse("运行期监听异常必须留下可观察错误", server.lastFailure().isEmpty());
        assertEquals(server.lastFailure(), reportedFailure);
        assertFalse(server.isListening());

        server.start();
        assertTrue(server.isListening());
        assertEquals("", server.lastFailure());

        assertTrue("后续启动必须真正恢复监听，不能仍视坏socket为已启动",
                send("GET / HTTP/1.1\r\nHost: " + host() + "\r\n\r\n")
                        .startsWith("HTTP/1.1 200"));
    }

    @Test
    public void fallsBackToExplicitInterfaceWhenLoopbackPortIsOccupied() throws Exception {
        java.util.List<java.net.InetAddress> lan = ParentHttpServer.lanAddresses();
        if (lan.isEmpty()) return;
        // macOS proxy/VPN interfaces can precede Wi-Fi; their fake-IP route does not loop back
        // to this test listener. Exercise the same explicit bind on the real private LAN.
        java.net.InetAddress lanIp = lan.stream().filter(java.net.InetAddress::isSiteLocalAddress)
                .findFirst().orElse(lan.get(0));
        try (java.net.ServerSocket loopbackOccupier = new java.net.ServerSocket()) {
            loopbackOccupier.setReuseAddress(true);
            loopbackOccupier.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            int occupiedPort = loopbackOccupier.getLocalPort();
            ParentHttpServer fallbackServer = new ParentHttpServer(request ->
                    ParentHttpServer.Response.text(200, "text/plain", "lan-ok"),
                    occupiedPort, java.util.Collections.singletonList(lanIp));
            try {
                fallbackServer.start();
                try (Socket client = new Socket(lanIp, occupiedPort)) {
                    client.setSoTimeout(5000);
                    String req = "GET / HTTP/1.1\r\nHost: " + lanIp.getHostAddress() + ":"
                            + occupiedPort + "\r\n\r\n";
                    client.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[1024];
                    int n;
                    while ((n = client.getInputStream().read(buf)) != -1) out.write(buf, 0, n);
                    assertTrue(out.toString("UTF-8").endsWith("lan-ok"));
                }
            } finally {
                fallbackServer.close();
            }
        }
    }

    private String host() {
        return "127.0.0.1:" + server.port();
    }

    private String post(String body, String extraHeaders) {
        return "POST /api/action HTTP/1.1\r\nHost: " + host()
                + "\r\nContent-Type: application/json\r\n" + extraHeaders
                + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\n\r\n" + body;
    }

    private String send(String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int count;
            while ((count = socket.getInputStream().read(bytes)) != -1) result.write(bytes, 0, count);
            return result.toString("UTF-8");
        }
    }
}
