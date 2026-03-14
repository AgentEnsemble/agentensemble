package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.review.OnTimeoutAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebDashboard}.
 *
 * <p>Covers builder validation, the {@code onPort()} convenience factory, and lifecycle management.
 */
class WebDashboardTest {

    private WebDashboard dashboard;

    @AfterEach
    void tearDown() {
        if (dashboard != null) {
            dashboard.stop();
        }
    }

    // ========================
    // Builder validation
    // ========================

    @Test
    void portBelowRangeThrows() {
        assertThatThrownBy(() -> WebDashboard.builder().port(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void portAboveRangeThrows() {
        assertThatThrownBy(() -> WebDashboard.builder().port(65536).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void negativePortThrows() {
        assertThatThrownBy(() -> WebDashboard.builder().port(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
    }

    @Test
    void validPortRangeBoundaries() {
        // Should not throw -- 0 is valid (OS-assigned ephemeral port), 1 and 65535 are explicit boundaries
        WebDashboard ephemeral = WebDashboard.builder().port(0).build();
        ephemeral.stop();
        WebDashboard low = WebDashboard.builder().port(1).build();
        low.stop();
        WebDashboard high = WebDashboard.builder().port(65535).build();
        high.stop();
    }

    @Test
    void nullHostThrows() {
        assertThatThrownBy(() -> WebDashboard.builder().port(7329).host(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void blankHostThrows() {
        assertThatThrownBy(() -> WebDashboard.builder().port(7329).host("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void nullReviewTimeoutThrows() {
        assertThatThrownBy(() ->
                        WebDashboard.builder().port(7329).reviewTimeout(null).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewTimeout");
    }

    @Test
    void negativeReviewTimeoutThrows() {
        assertThatThrownBy(() -> WebDashboard.builder()
                        .port(7329)
                        .reviewTimeout(Duration.ofSeconds(-1))
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewTimeout");
    }

    // ========================
    // onPort() factory
    // ========================

    @Test
    void onPortFactoryCreatesValidDashboard() {
        dashboard = WebDashboard.onPort(7329);

        assertThat(dashboard).isNotNull();
        assertThat(dashboard.getPort()).isEqualTo(7329);
        assertThat(dashboard.getHost()).isEqualTo("localhost");
    }

    @Test
    void onPortFactoryWithInvalidPortThrows() {
        assertThatThrownBy(() -> WebDashboard.onPort(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // Default values
    // ========================

    @Test
    void defaultHostIsLocalhost() {
        dashboard = WebDashboard.builder().port(7329).build();
        assertThat(dashboard.getHost()).isEqualTo("localhost");
    }

    @Test
    void defaultReviewTimeoutIsFiveMinutes() {
        dashboard = WebDashboard.builder().port(7329).build();
        assertThat(dashboard.getReviewTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void defaultOnTimeoutActionIsContinue() {
        dashboard = WebDashboard.builder().port(7329).build();
        assertThat(dashboard.getOnTimeout()).isEqualTo(OnTimeoutAction.CONTINUE);
    }

    // ========================
    // Lifecycle management
    // ========================

    @Test
    void startAndStopWork() {
        dashboard = WebDashboard.onPort(0);

        assertThat(dashboard.isRunning()).isFalse();
        dashboard.start();
        assertThat(dashboard.isRunning()).isTrue();
        dashboard.stop();
        assertThat(dashboard.isRunning()).isFalse();
    }

    @Test
    void stopOnNotStartedDashboardIsNoOp() {
        dashboard = WebDashboard.onPort(0);
        assertThat(dashboard.isRunning()).isFalse();
        // Should not throw
        dashboard.stop();
        assertThat(dashboard.isRunning()).isFalse();
    }

    @Test
    void doubleStartIsIdempotent() {
        dashboard = WebDashboard.onPort(0);
        dashboard.start();
        // Second start should be a no-op (server already running)
        dashboard.start();
        assertThat(dashboard.isRunning()).isTrue();
    }

    @Test
    void close_delegatesToStop() {
        dashboard = WebDashboard.onPort(0);
        dashboard.start();
        assertThat(dashboard.isRunning()).isTrue();

        dashboard.close();

        assertThat(dashboard.isRunning()).isFalse();
        // tearDown will call stop() again -- idempotent, no problem
    }

    @Test
    void usableWithTryWithResources() {
        // Verify that try-with-resources automatically stops the server when the block exits.
        // Use a local variable (not the @AfterEach-managed field) to avoid a double-stop
        // in tearDown; stop() is idempotent but we keep the test self-contained.
        WebDashboard local = WebDashboard.onPort(0);
        try (local) {
            local.start();
            assertThat(local.isRunning()).isTrue();
        }
        assertThat(local.isRunning()).isFalse();
    }

    @Test
    void implementsAutoCloseable() {
        // Compile-time verification that WebDashboard satisfies AutoCloseable.
        dashboard = WebDashboard.onPort(0);
        @SuppressWarnings("unused")
        AutoCloseable asCloseable = dashboard;
        assertThat(asCloseable).isNotNull();
    }

    @Test
    void stop_terminatesHeartbeatScheduler() {
        // The heartbeat scheduler must be terminated when stop() returns so that
        // no lingering thread is left running after the dashboard has been stopped.
        // The heartbeat thread is a daemon thread and does not block JVM exit on its
        // own, but terminating the scheduler ensures clean lifecycle semantics and
        // avoids resource leaks in long-running processes that create multiple dashboards.
        dashboard = WebDashboard.onPort(0);
        dashboard.start();

        assertThat(dashboard.isHeartbeatSchedulerTerminated())
                .as("scheduler should not be terminated while running")
                .isFalse();

        dashboard.stop();

        assertThat(dashboard.isHeartbeatSchedulerTerminated())
                .as("heartbeat scheduler must be terminated after stop()")
                .isTrue();
    }

    @Test
    void stop_isIdempotent_doesNotThrowOnDoubleStop() {
        // stop() must be safe to call more than once. The scheduler is shut down on
        // the first stop() (when the server was running). The second stop() is a true
        // no-op: server.stop() is a no-op because the server is already stopped, and
        // the wasRunning guard prevents a second shutdownNow() call.
        dashboard = WebDashboard.onPort(0);
        dashboard.start();
        dashboard.stop();
        assertThat(dashboard.isHeartbeatSchedulerTerminated()).isTrue();
        // Second stop() must not throw.
        dashboard.stop();
        assertThat(dashboard.isRunning()).isFalse();
        assertThat(dashboard.isHeartbeatSchedulerTerminated()).isTrue();
    }

    @Test
    void close_terminatesHeartbeatScheduler() {
        // AutoCloseable.close() delegates to stop(), so it must also terminate the
        // heartbeat scheduler. Verifies the close() path applies the same resource
        // cleanup as stop().
        WebDashboard local = WebDashboard.onPort(0);
        local.start();

        assertThat(local.isHeartbeatSchedulerTerminated())
                .as("scheduler should not be terminated while running")
                .isFalse();

        local.close();

        assertThat(local.isHeartbeatSchedulerTerminated())
                .as("heartbeat scheduler must be terminated after close()")
                .isTrue();
    }

    @Test
    void stop_onNeverStartedDashboard_doesNotTerminateScheduler() {
        // When stop() is called on a dashboard that was never started, it is a true
        // no-op: the server was not running, so the scheduler should NOT be shut down.
        // This preserves the idempotency contract and avoids breaking a potential
        // future restart after a stop-before-start sequence.
        dashboard = WebDashboard.onPort(0);
        assertThat(dashboard.isRunning()).isFalse();

        dashboard.stop();

        assertThat(dashboard.isRunning()).isFalse();
        assertThat(dashboard.isHeartbeatSchedulerTerminated())
                .as("scheduler must NOT be terminated when stop() is called on a never-started dashboard")
                .isFalse();
    }

    // ========================
    // Component accessors
    // ========================

    @Test
    void streamingListenerReturnsNonNull() {
        dashboard = WebDashboard.onPort(7329);
        assertThat(dashboard.streamingListener()).isNotNull();
    }

    @Test
    void reviewHandlerReturnsNonNull() {
        dashboard = WebDashboard.onPort(7329);
        assertThat(dashboard.reviewHandler()).isNotNull();
    }

    @Test
    void streamingListenerIsSameInstance() {
        dashboard = WebDashboard.onPort(7329);
        // Multiple calls should return the same instance
        assertThat(dashboard.streamingListener()).isSameAs(dashboard.streamingListener());
    }

    @Test
    void reviewHandlerIsSameInstance() {
        dashboard = WebDashboard.onPort(7329);
        assertThat(dashboard.reviewHandler()).isSameAs(dashboard.reviewHandler());
    }

    // ========================
    // Custom builder configuration
    // ========================

    @Test
    void builderConfiguresAllFields() {
        dashboard = WebDashboard.builder()
                .port(8080)
                .host("0.0.0.0")
                .reviewTimeout(Duration.ofMinutes(10))
                .onTimeout(OnTimeoutAction.EXIT_EARLY)
                .build();

        assertThat(dashboard.getPort()).isEqualTo(8080);
        assertThat(dashboard.getHost()).isEqualTo("0.0.0.0");
        assertThat(dashboard.getReviewTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(dashboard.getOnTimeout()).isEqualTo(OnTimeoutAction.EXIT_EARLY);
    }

    // ========================
    // traceExportDir builder option
    // ========================

    @Test
    void builderWithoutTraceExportDirHasNullTraceExporter() {
        dashboard = WebDashboard.builder().port(0).build();
        assertThat(dashboard.traceExporter()).isNull();
    }

    @Test
    void builderWithTraceExportDirReturnsNonNullTraceExporter() {
        java.nio.file.Path dir = java.nio.file.Path.of("./traces-test");
        dashboard = WebDashboard.builder().port(0).traceExportDir(dir).build();
        assertThat(dashboard.traceExporter()).isNotNull();
    }

    @Test
    void getTraceExportDirReturnsConfiguredPath() {
        java.nio.file.Path dir = java.nio.file.Path.of("./traces-test");
        dashboard = WebDashboard.builder().port(0).traceExportDir(dir).build();
        assertThat(dashboard.getTraceExportDir()).isEqualTo(dir);
    }

    @Test
    void traceExporterIsSameInstanceOnMultipleCalls() {
        java.nio.file.Path dir = java.nio.file.Path.of("./traces-test");
        dashboard = WebDashboard.builder().port(0).traceExportDir(dir).build();
        assertThat(dashboard.traceExporter()).isSameAs(dashboard.traceExporter());
    }

    // ========================
    // maxRetainedRuns builder option
    // ========================

    @Test
    void defaultMaxRetainedRunsIsTen() {
        dashboard = WebDashboard.builder().port(0).build();
        assertThat(dashboard.getMaxRetainedRuns()).isEqualTo(10);
    }

    @Test
    void builderConfiguresMaxRetainedRuns() {
        dashboard = WebDashboard.builder().port(0).maxRetainedRuns(5).build();
        assertThat(dashboard.getMaxRetainedRuns()).isEqualTo(5);
    }

    @Test
    void maxRetainedRunsOfZeroThrows() {
        assertThatThrownBy(
                        () -> WebDashboard.builder().port(0).maxRetainedRuns(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetainedRuns");
    }

    @Test
    void maxRetainedRunsNegativeThrows() {
        assertThatThrownBy(
                        () -> WebDashboard.builder().port(0).maxRetainedRuns(-1).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetainedRuns");
    }

    // ========================
    // Ensemble lifecycle hooks
    // ========================

    @Test
    void onEnsembleStarted_broadcastsToConnectedClients() throws Exception {
        dashboard = WebDashboard.builder().port(0).host("0.0.0.0").build();
        dashboard.start();
        int port = dashboard.actualPort();

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch gotMessage = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch gotHello = new CountDownLatch(1);

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
                            if (msg.contains("\"type\":\"hello\"") && gotHello.getCount() > 0) {
                                gotHello.countDown();
                            }
                            if (msg.contains("ensemble_started")) {
                                gotMessage.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        // Wait for the hello message to arrive before firing the lifecycle hook, so the
        // subsequent ensemble_started is guaranteed to be delivered to this connection.
        assertThat(gotHello.await(5, TimeUnit.SECONDS)).isTrue();

        dashboard.onEnsembleStarted("test-ens-id", Instant.now(), 3, "SEQUENTIAL");

        assertThat(gotMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anyMatch(m -> m.contains("\"type\":\"ensemble_started\""));
        assertThat(received).anyMatch(m -> m.contains("\"totalTasks\":3"));
        assertThat(received).anyMatch(m -> m.contains("\"workflow\":\"SEQUENTIAL\""));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void onEnsembleCompleted_broadcastsToConnectedClients() throws Exception {
        dashboard = WebDashboard.builder().port(0).host("0.0.0.0").build();
        dashboard.start();
        int port = dashboard.actualPort();

        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch gotMessage = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch gotHello = new CountDownLatch(1);

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
                            if (msg.contains("\"type\":\"hello\"") && gotHello.getCount() > 0) {
                                gotHello.countDown();
                            }
                            if (msg.contains("ensemble_completed")) {
                                gotMessage.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gotHello.await(5, TimeUnit.SECONDS)).isTrue();

        dashboard.onEnsembleCompleted("test-ens-id", Instant.now(), 5000L, "COMPLETED", 1200L, 7);

        assertThat(gotMessage.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anyMatch(m -> m.contains("\"type\":\"ensemble_completed\""));
        assertThat(received).anyMatch(m -> m.contains("\"exitReason\":\"COMPLETED\""));
        assertThat(received).anyMatch(m -> m.contains("\"totalToolCalls\":7"));
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void onEnsembleStarted_withNoConnectedClients_doesNotThrow() {
        dashboard = WebDashboard.onPort(0);
        // Not started: no clients, no server; should not throw
        dashboard.onEnsembleStarted("ens-id", Instant.now(), 2, "SEQUENTIAL");
    }

    @Test
    void onEnsembleStarted_populatesSnapshotForLateJoiners() throws Exception {
        dashboard = WebDashboard.builder().port(0).host("0.0.0.0").build();
        dashboard.start();

        // Call lifecycle hook before any client connects
        dashboard.onEnsembleStarted("ens-snap", Instant.now(), 1, "SEQUENTIAL");

        int port = dashboard.actualPort();
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch gotHello = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(10);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("\"type\":\"hello\"")) {
                                gotHello.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(gotHello.await(5, TimeUnit.SECONDS)).isTrue();
        String helloJson = received.stream()
                .filter(m -> m.contains("\"type\":\"hello\""))
                .findFirst()
                .orElseThrow();

        // Late joiner receives ensembleId and snapshot containing ensemble_started
        assertThat(helloJson).contains("\"ensembleId\":\"ens-snap\"");
        assertThat(helloJson).contains("snapshotTrace");
        assertThat(helloJson).contains("ensemble_started");
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }
}
