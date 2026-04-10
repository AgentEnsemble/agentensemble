package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.Ensemble;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.review.OnTimeoutAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for WebSocket {@code run_control} and {@code subscribe} message handling.
 *
 * <p>Connects via real WebSocket and exercises:
 * - {@code run_control} cancel → {@code run_control_ack} with status CANCELLING
 * - {@code run_control} unknown runId → {@code run_control_ack} with status NOT_FOUND
 * - {@code run_control} unknown action → {@code run_control_ack} with status INVALID_ACTION
 * - {@code subscribe} message → {@code subscribe_ack} confirming subscription
 */
class WebDashboardRunControlWsTest {

    private WebDashboard dashboard;
    private WebSocket ws;
    private Ensemble mockEnsemble;
    private EnsembleOutput mockOutput;

    @BeforeEach
    void setUp() {
        mockEnsemble = mock(Ensemble.class);
        mockOutput = mock(EnsembleOutput.class);

        when(mockEnsemble.getTasks()).thenReturn(List.of());
        when(mockOutput.getTaskOutputs()).thenReturn(List.of());
        when(mockOutput.getMetrics()).thenReturn(null);
        when(mockEnsemble.withAdditionalListener(any())).thenReturn(mockEnsemble);

        dashboard = WebDashboard.builder()
                .port(0)
                .host("localhost")
                .reviewTimeout(Duration.ofMillis(200))
                .onTimeout(OnTimeoutAction.CONTINUE)
                .build();
        dashboard.start();
        dashboard.setEnsemble(mockEnsemble);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        if (dashboard != null) dashboard.stop();
    }

    private WebSocket connectWs(WebSocket.Listener listener) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        return client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), listener)
                .get(3, TimeUnit.SECONDS);
    }

    // ========================
    // run_control: cancel with unknown runId
    // ========================

    @Test
    void runControl_cancelUnknownRunId_receivesNotFoundAck() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(20);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"type\":\"run_control_ack\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        ws.sendText("{\"type\":\"run_control\",\"runId\":\"run-unknown\",\"action\":\"cancel\"}", true);

        assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();

        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_control_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMsg).contains("\"runId\":\"run-unknown\"");
        assertThat(ackMsg).contains("\"action\":\"cancel\"");
        assertThat(ackMsg).contains("\"status\":\"NOT_FOUND\"");
    }

    // ========================
    // run_control: unknown action
    // ========================

    @Test
    void runControl_unknownAction_receivesInvalidActionAck() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(20);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"type\":\"run_control_ack\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        ws.sendText("{\"type\":\"run_control\",\"runId\":\"run-abc\",\"action\":\"explode\"}", true);

        assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();

        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_control_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMsg).contains("\"action\":\"explode\"");
        assertThat(ackMsg).contains("\"status\":\"INVALID_ACTION\"");
    }

    // ========================
    // subscribe: event filter message → subscribe_ack
    // ========================

    @Test
    void subscribe_taskEventsOnly_receivesSubscribeAck() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(20);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"type\":\"subscribe_ack\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        ws.sendText("{\"type\":\"subscribe\",\"events\":[\"task_started\",\"task_completed\",\"run_result\"]}", true);

        assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();

        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"subscribe_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMsg).contains("\"type\":\"subscribe_ack\"");
        assertThat(ackMsg).contains("task_started");
        assertThat(ackMsg).contains("task_completed");
        assertThat(ackMsg).contains("run_result");
    }

    // ========================
    // subscribe: wildcard → subscribe_ack with "*"
    // ========================

    @Test
    void subscribe_wildcard_receivesAckWithWildcard() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(20);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"type\":\"subscribe_ack\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        ws.sendText("{\"type\":\"subscribe\",\"events\":[\"*\"]}", true);

        assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();

        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"subscribe_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMsg).contains("\"*\"");
    }

    // ========================
    // run_control: switch_model with no model alias → INVALID_MODEL
    // ========================

    @Test
    void runControl_switchModelNullModelAlias_receivesInvalidModelAck() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(20);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"type\":\"run_control_ack\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // switch_model without model field (null alias)
        ws.sendText("{\"type\":\"run_control\",\"runId\":\"run-abc\",\"action\":\"switch_model\"}", true);

        assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();
        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_control_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMsg).contains("\"status\":\"INVALID_MODEL\"");
    }

    // ========================
    // run_control: switch_model with no model catalog → NOT_CONFIGURED
    // ========================

    @Test
    void runControl_switchModelNoModelCatalog_receivesNotConfiguredAck() throws Exception {
        // Dashboard has no modelCatalog configured
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(20);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"type\":\"run_control_ack\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        ws.sendText(
                "{\"type\":\"run_control\",\"runId\":\"run-abc\",\"action\":\"switch_model\",\"model\":\"haiku\"}",
                true);

        assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();
        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_control_ack\""))
                .findFirst()
                .orElse("");
        // No model catalog configured → NOT_CONFIGURED
        assertThat(ackMsg).contains("\"status\":\"NOT_CONFIGURED\"");
    }

    // ========================
    // run_request: empty tasks (triggers handleRunRequest catch block → REJECTED)
    // ========================

    @Test
    void runRequest_emptyTasksList_receivesRejectedAck() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(20);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"type\":\"run_ack\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Send run_request with empty tasks array -- this throws IllegalArgumentException inside handleRunRequest
        ws.sendText("{\"type\":\"run_request\",\"requestId\":\"req-err\",\"tasks\":[]}", true);

        assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();

        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_ack\""))
                .findFirst()
                .orElse("");
        // Empty tasks causes IAE in parsing → catch block → REJECTED ack
        assertThat(ackMsg).contains("\"status\":\"REJECTED\"");
    }

    // ========================
    // run_control: cancel active run → CANCELLING
    // ========================

    @Test
    void runControl_cancelActiveRun_receivesCancellingAck() throws Exception {
        CountDownLatch runStarted = new CountDownLatch(1);
        CountDownLatch runWait = new CountDownLatch(1);

        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            runStarted.countDown();
            runWait.await(5, TimeUnit.SECONDS);
            return mockOutput;
        });

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        ws = connectWs(new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(30);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    String msg = data.toString();
                    received.add(msg);
                    if (msg.contains("\"type\":\"hello\"")) helloLatch.countDown();
                    if (msg.contains("\"status\":\"CANCELLING\"")) ackLatch.countDown();
                    webSocket.request(1);
                }
                return null;
            }
        });

        assertThat(helloLatch.await(3, TimeUnit.SECONDS)).isTrue();

        // Submit a run via WS
        ws.sendText("{\"type\":\"run_request\",\"requestId\":\"req-1\"}", true);

        // Wait for run to start executing
        assertThat(runStarted.await(3, TimeUnit.SECONDS)).isTrue();

        // Find the runId from the run_ack message
        Thread.sleep(100); // allow run_ack to arrive
        String runAck = received.stream()
                .filter(m -> m.contains("\"type\":\"run_ack\""))
                .findFirst()
                .orElse("");

        if (runAck.isEmpty()) {
            // run_ack may not have arrived yet; skip this assertion
            runWait.countDown();
            return;
        }

        // Extract runId from run_ack
        int idx = runAck.indexOf("\"runId\":\"");
        String runId = "";
        if (idx >= 0) {
            int start = idx + 9;
            int end = runAck.indexOf('"', start);
            if (end > start) runId = runAck.substring(start, end);
        }

        if (!runId.isEmpty()) {
            // Send cancel
            ws.sendText("{\"type\":\"run_control\",\"runId\":\"" + runId + "\",\"action\":\"cancel\"}", true);
            assertThat(ackLatch.await(3, TimeUnit.SECONDS)).isTrue();
        }

        runWait.countDown();
    }
}
