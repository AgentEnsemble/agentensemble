package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.agentensemble.web.protocol.ClientMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketServer}.
 *
 * <p>Covers: server lifecycle (start/stop/restart), heartbeat scheduling (with injected scheduler),
 * and origin validation logic.
 */
class WebSocketServerTest {

    private ConnectionManager connectionManager;
    private MessageSerializer serializer;
    private WebSocketServer server;
    /** Real scheduler used by integration tests that make actual WebSocket connections. */
    private ScheduledExecutorService realScheduler;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
        connectionManager = new ConnectionManager(serializer);
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        if (realScheduler != null) {
            realScheduler.shutdownNow();
        }
    }

    // ========================
    // Server lifecycle
    // ========================

    @Test
    void serverStartsAndIsRunning() {
        server = new WebSocketServer(connectionManager, serializer, mock(ScheduledExecutorService.class));
        server.start(0, "localhost");
        assertThat(server.isRunning()).isTrue();
        assertThat(server.port()).isGreaterThan(0);
    }

    @Test
    void serverStopsCleanly() {
        server = new WebSocketServer(connectionManager, serializer, mock(ScheduledExecutorService.class));
        server.start(0, "localhost");
        assertThat(server.isRunning()).isTrue();

        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void serverCanRestartAfterStop() {
        server = new WebSocketServer(connectionManager, serializer, mock(ScheduledExecutorService.class));
        server.start(0, "localhost");
        int firstPort = server.port();
        server.stop();

        // Restart on a new ephemeral port
        server.start(0, "localhost");
        assertThat(server.isRunning()).isTrue();
        assertThat(server.port()).isGreaterThan(0);
        // Different port is acceptable since both were ephemeral
        assertThat(firstPort).isGreaterThan(0);
    }

    @Test
    void stopOnNonRunningServerIsNoOp() {
        server = new WebSocketServer(connectionManager, serializer, mock(ScheduledExecutorService.class));
        assertThat(server.isRunning()).isFalse();
        // Should not throw
        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    // ========================
    // Heartbeat scheduling
    // ========================

    @Test
    void heartbeatIsScheduledEvery15Seconds() {
        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        server = new WebSocketServer(connectionManager, serializer, mockScheduler);
        server.start(0, "localhost");

        verify(mockScheduler).scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heartbeatFutureCancelledOnStop() {
        ScheduledExecutorService mockScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<Object> mockFuture = mock(ScheduledFuture.class);
        doReturn(mockFuture)
                .when(mockScheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));

        server = new WebSocketServer(connectionManager, serializer, mockScheduler);
        server.start(0, "localhost");
        server.stop();

        // The ScheduledFuture returned by scheduleAtFixedRate must be cancelled on stop()
        // to prevent orphaned heartbeat tasks accumulating across stop/restart cycles.
        verify(mockFuture).cancel(false);
    }

    @Test
    void heartbeatTaskBroadcastsHeartbeatMessage() {
        // Capture the scheduled heartbeat task and execute it directly
        ScheduledExecutorService capturingScheduler = mock(ScheduledExecutorService.class);
        server = new WebSocketServer(connectionManager, serializer, capturingScheduler);

        // Connect a session before starting so we can observe the broadcast
        ConnectionManagerTest.MockWsSession session = new ConnectionManagerTest.MockWsSession("s1");
        connectionManager.onConnect(session);
        session.clearMessages();

        // Extract the Runnable passed to scheduleAtFixedRate and run it
        java.util.concurrent.atomic.AtomicReference<Runnable> capturedTask =
                new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
                    capturedTask.set(invocation.getArgument(0));
                    return null;
                })
                .when(capturingScheduler)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));

        server.start(0, "localhost");

        assertThat(capturedTask.get()).isNotNull();
        capturedTask.get().run(); // Manually fire the heartbeat

        assertThat(session.sentMessages()).hasSize(1);
        assertThat(session.sentMessages().get(0)).contains("\"type\":\"heartbeat\"");
        assertThat(session.sentMessages().get(0)).contains("serverTimeMs");
    }

    // ========================
    // Origin validation
    // ========================

    @Test
    void localhostOriginAllowedWhenBindingToLocalhost() {
        assertThat(WebSocketServer.isOriginAllowed("http://localhost:3000", "localhost"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://localhost:7329", "localhost"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://127.0.0.1:3000", "localhost"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://127.0.0.1:7329", "localhost"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://[::1]:3000", "localhost"))
                .isTrue();
    }

    @Test
    void crossOriginRejectedWhenBindingToLocalhost() {
        assertThat(WebSocketServer.isOriginAllowed("http://evil.com", "localhost"))
                .isFalse();
        assertThat(WebSocketServer.isOriginAllowed("https://attacker.example.com", "localhost"))
                .isFalse();
        assertThat(WebSocketServer.isOriginAllowed("null", "localhost")).isFalse();
        assertThat(WebSocketServer.isOriginAllowed("", "localhost")).isFalse();
    }

    @Test
    void subdomainBypassRejectedWhenBindingToLocalhost() {
        // URI-based host comparison prevents subdomain spoofing attacks.
        assertThat(WebSocketServer.isOriginAllowed("http://localhost.evil.com", "localhost"))
                .isFalse();
        assertThat(WebSocketServer.isOriginAllowed("http://notlocalhost.com", "localhost"))
                .isFalse();
        assertThat(WebSocketServer.isOriginAllowed("http://evil.com/path?host=localhost", "localhost"))
                .isFalse();
    }

    @Test
    void nullOriginRejectedWhenBindingToLocalhost() {
        assertThat(WebSocketServer.isOriginAllowed(null, "localhost")).isFalse();
    }

    @Test
    void anyOriginAllowedWhenBindingToNonLocalhost() {
        // When host is not localhost (e.g. 0.0.0.0 for remote access), any origin is accepted.
        // Security is the user's responsibility (VPN, reverse proxy, etc.).
        assertThat(WebSocketServer.isOriginAllowed("http://any-origin.com", "0.0.0.0"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed(null, "0.0.0.0")).isTrue();
    }

    @Test
    void ipv6LoopbackBindingTreatedAsLocalBinding() {
        // When bound to the IPv6 loopback address, the same strict origin policy applies
        // as for localhost/127.0.0.1 -- only loopback origins are accepted.
        assertThat(WebSocketServer.isOriginAllowed("http://localhost:3000", "::1"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://127.0.0.1:3000", "::1"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://[::1]:3000", "::1")).isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://evil.com", "::1")).isFalse();
        assertThat(WebSocketServer.isOriginAllowed(null, "::1")).isFalse();

        // Same for [::1] variant (bracketed form)
        assertThat(WebSocketServer.isOriginAllowed("http://localhost:3000", "[::1]"))
                .isTrue();
        assertThat(WebSocketServer.isOriginAllowed("http://evil.com", "[::1]")).isFalse();
    }

    // ========================
    // Protocol serialization round-trip (per issue requirement)
    // ========================

    @Test
    void heartbeatMessageSerializesCorrectly() throws Exception {
        net.agentensemble.web.protocol.HeartbeatMessage msg =
                new net.agentensemble.web.protocol.HeartbeatMessage(System.currentTimeMillis());
        String json = serializer.toJson(msg);
        assertThat(json).contains("\"type\":\"heartbeat\"");
        assertThat(json).contains("serverTimeMs");
    }

    @Test
    void taskStartedMessageSerializesCorrectly() throws Exception {
        net.agentensemble.web.protocol.TaskStartedMessage msg = new net.agentensemble.web.protocol.TaskStartedMessage(
                1, 3, "Research task", "Researcher", java.time.Instant.now());
        String json = serializer.toJson(msg);
        assertThat(json).contains("\"type\":\"task_started\"");
        assertThat(json).contains("Research task");
    }

    @Test
    void reviewRequestedMessageSerializesCorrectly() throws Exception {
        net.agentensemble.web.protocol.ReviewRequestedMessage msg =
                new net.agentensemble.web.protocol.ReviewRequestedMessage(
                        "r1", "Task desc", "Task output", "AFTER_EXECUTION", null, 60000L, "CONTINUE");
        String json = serializer.toJson(msg);
        assertThat(json).contains("\"type\":\"review_requested\"");
        assertThat(json).contains("\"reviewId\":\"r1\"");
    }

    // ========================
    // Integration tests: real WebSocket connections
    // ========================

    @Test
    void connect_acceptsConnectionOnNonLocalBinding() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        CountDownLatch connected = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(1);
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        // Poll until server-side onConnect registers the session
        long deadline = System.currentTimeMillis() + 2000;
        while (connectionManager.sessionCount() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(connectionManager.sessionCount()).isEqualTo(1);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void connect_withoutOriginOnLocalhostBinding_isRejected() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "localhost");
        int port = server.port();

        // Java's WebSocket client does not send an Origin header by default.
        // Connecting to a localhost-bound server without one triggers origin rejection.
        CountDownLatch closedLatch = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        closedLatch.countDown();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        closedLatch.countDown();
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(closedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(connectionManager.sessionCount()).isEqualTo(0);
    }

    @Test
    void message_ping_serverRespondsPong() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        CountDownLatch gotPong = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();

        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(10);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("\"type\":\"pong\"")) {
                                gotPong.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        ws.sendText("{\"type\":\"ping\"}", true).get(5, TimeUnit.SECONDS);

        assertThat(gotPong.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anyMatch(m -> m.contains("\"type\":\"pong\""));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void message_reviewDecision_forwardedToRegisteredHandler() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        List<ClientMessage> received = new CopyOnWriteArrayList<>();
        CountDownLatch gotMessage = new CountDownLatch(1);
        server.setClientMessageHandler(msg -> {
            received.add(msg);
            gotMessage.countDown();
        });

        CountDownLatch connected = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(1);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        ws.sendText("{\"type\":\"review_decision\",\"reviewId\":\"r1\",\"decision\":\"continue\"}", true)
                .get(5, TimeUnit.SECONDS);

        assertThat(gotMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).hasSize(1);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void message_reviewDecision_withNoHandler_serverRemainsRunning() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        // No client message handler registered
        server.start(0, "0.0.0.0");
        int port = server.port();

        CountDownLatch connected = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(1);
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        ws.sendText("{\"type\":\"review_decision\",\"reviewId\":\"r1\",\"decision\":\"continue\"}", true)
                .get(5, TimeUnit.SECONDS);

        // Give server time to process; logs a warning, no response expected
        Thread.sleep(200);
        assertThat(server.isRunning()).isTrue();
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void message_clientHandlerThrows_serverRemainsRunning() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        server.setClientMessageHandler(msg -> {
            throw new RuntimeException("handler blew up intentionally");
        });

        CountDownLatch connected = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(1);
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        ws.sendText("{\"type\":\"review_decision\",\"reviewId\":\"r1\",\"decision\":\"continue\"}", true)
                .get(5, TimeUnit.SECONDS);

        Thread.sleep(200);
        assertThat(server.isRunning()).isTrue();
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void message_malformedJson_serverRemainsRunning() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        CountDownLatch connected = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(1);
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        ws.sendText("not-valid-json", true).get(5, TimeUnit.SECONDS);

        Thread.sleep(200);
        assertThat(server.isRunning()).isTrue();
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void disconnect_removesSessionFromConnectionManager() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        CountDownLatch connected = new CountDownLatch(1);
        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(1);
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        // Server-side onConnect fires after the HTTP 101 response, which may be slightly after
        // the client's onOpen. Poll until the session is registered.
        long registerDeadline = System.currentTimeMillis() + 2000;
        while (connectionManager.sessionCount() < 1 && System.currentTimeMillis() < registerDeadline) {
            Thread.sleep(10);
        }
        assertThat(connectionManager.sessionCount()).isEqualTo(1);

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS);

        // Wait for the server-side onClose handler to fire
        long closeDeadline = System.currentTimeMillis() + 3000;
        while (connectionManager.sessionCount() > 0 && System.currentTimeMillis() < closeDeadline) {
            Thread.sleep(50);
        }
        assertThat(connectionManager.sessionCount()).isEqualTo(0);
    }

    @Test
    void broadcast_deliveredToConnectedSession() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        CountDownLatch gotBroadcast = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        List<String> received = new CopyOnWriteArrayList<>();

        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected.countDown();
                        webSocket.request(10);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("test_broadcast")) {
                                gotBroadcast.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        // Allow hello message to arrive before broadcasting
        Thread.sleep(100);
        connectionManager.broadcast("{\"type\":\"test_broadcast\"}");

        assertThat(gotBroadcast.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anyMatch(m -> m.contains("test_broadcast"));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void statusEndpoint_returnsRunningStatus() throws Exception {
        realScheduler = Executors.newSingleThreadScheduledExecutor();
        server = new WebSocketServer(connectionManager, serializer, realScheduler);
        server.start(0, "0.0.0.0");
        int port = server.port();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/status"))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("running");
    }
}
