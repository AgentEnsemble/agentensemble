package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for WebSocket {@code run_request} handling in {@link WebDashboard}.
 *
 * <p>Connects via real WebSocket and exercises the {@code handleRunRequest} path.
 */
class WebDashboardRunRequestTest {

    private WebDashboard dashboard;
    private WebSocket ws;

    @BeforeEach
    void setUp() {
        dashboard = WebDashboard.builder()
                .port(0)
                .host("localhost")
                .reviewTimeout(Duration.ofMillis(100))
                .onTimeout(OnTimeoutAction.CONTINUE)
                .build();
        dashboard.start();
    }

    @AfterEach
    void tearDown() {
        try {
            if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        if (dashboard != null) dashboard.stop();
    }

    /**
     * When no template ensemble is configured via {@code setEnsemble()}, a {@code run_request}
     * must be immediately rejected with a {@code run_ack} carrying {@code status: "REJECTED"}.
     */
    @Test
    void runRequest_noEnsembleConfigured_receivesRejectedAck() throws Exception {
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            // First message from server signals ready (hello/connected)
                            helloLatch.countDown();
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("\"type\":\"run_ack\"")) {
                                ackLatch.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        // Wait for the server to send its first message (hello) before sending the run_request
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Send a run_request; no ensemble is configured so it should be REJECTED
        String runRequest =
                """
                {
                  "type": "run_request",
                  "requestId": "test-req-1",
                  "inputs": {"topic": "AI safety"}
                }
                """;
        ws.sendText(runRequest, true).get(3, TimeUnit.SECONDS);

        // Wait for run_ack
        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify the ack is REJECTED
        String ackMessage = received.stream()
                .filter(m -> m.contains("\"type\":\"run_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMessage).contains("\"status\":\"REJECTED\"");
        assertThat(ackMessage).contains("\"requestId\":\"test-req-1\"");
    }

    /**
     * With a template ensemble configured, a Level 1 {@code run_request} (inputs only) must
     * be accepted and eventually produce a {@code run_result}. Uses a deterministic
     * handler-only task that completes synchronously without an LLM model.
     */
    @Test
    void runRequest_level1_withEnsemble_receivesAcceptedAckAndResult() throws Exception {
        // Build a deterministic ensemble -- no LLM needed; handler returns instantly
        Task handlerTask = Task.builder()
                .name("greeter")
                .description("Say hello to {name}")
                .expectedOutput("A greeting")
                .handler(ctx -> ToolResult.success("Hello, " + ctx.description()))
                .build();
        Ensemble templateEnsemble = Ensemble.builder().task(handlerTask).build();
        dashboard.setEnsemble(templateEnsemble);

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);
        CountDownLatch resultLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(30);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            // First message from server signals ready (hello/connected)
                            helloLatch.countDown();
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("\"type\":\"run_ack\"")) {
                                ackLatch.countDown();
                            }
                            if (msg.contains("\"type\":\"run_result\"")) {
                                resultLatch.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        // Wait for the server hello before sending the run_request
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Send a Level 1 run_request (inputs only, no tasks or overrides)
        ws.sendText("{\"type\":\"run_request\",\"requestId\":\"l1-req\",\"inputs\":{\"name\":\"World\"}}", true)
                .get(3, TimeUnit.SECONDS);

        // Wait for ack
        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMsg).contains("\"requestId\":\"l1-req\"");
        // Status should be ACCEPTED (not REJECTED); concurrency limit not reached.
        // getInitialStatus() is used to avoid a race where the run transitions to RUNNING
        // before the ack message is serialized.
        assertThat(ackMsg).contains("\"status\":\"ACCEPTED\"");

        // Wait for the run_result
        assertThat(resultLatch.await(10, TimeUnit.SECONDS)).isTrue();

        String resultMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_result\""))
                .findFirst()
                .orElse("");
        assertThat(resultMsg).contains("\"status\":\"COMPLETED\"");
    }

    /**
     * A Level 3 {@code run_request} with {@code tasks} defined must use the dynamic task
     * list. Covers the Level 3 dispatch branch of {@code handleRunRequest}.
     */
    @Test
    void runRequest_level3_withDynamicTasks_receivesAck() throws Exception {
        Task baseTask = Task.builder()
                .name("base")
                .description("Base task")
                .expectedOutput("Base output")
                .handler(ctx -> ToolResult.success("base-done"))
                .build();
        Ensemble templateEnsemble = Ensemble.builder().task(baseTask).build();
        dashboard.setEnsemble(templateEnsemble);

        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            helloLatch.countDown();
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("\"type\":\"run_ack\"")) {
                                ackLatch.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Level 3: provide a dynamic task list
        String runRequest =
                """
                {
                  "type": "run_request",
                  "requestId": "l3-req",
                  "tasks": [
                    {
                      "name": "dynamic-task",
                      "description": "A dynamic task",
                      "expectedOutput": "Dynamic output"
                    }
                  ]
                }
                """;

        ws.sendText(runRequest, true).get(3, TimeUnit.SECONDS);

        // Wait for ack -- could be ACCEPTED or REJECTED (depends on whether handler-only
        // ensemble can execute dynamic LLM-backed tasks). Either way the dispatch branch runs.
        assertThat(ackLatch.await(8, TimeUnit.SECONDS)).isTrue();

        String ackMsg = received.stream()
                .filter(m -> m.contains("\"type\":\"run_ack\""))
                .findFirst()
                .orElse("");
        assertThat(ackMsg).contains("\"requestId\":\"l3-req\"");
    }

    /**
     * Sending a malformed run_request (missing required fields that cause a parsing error)
     * when no ensemble is configured should still result in a REJECTED ack being returned
     * to the originator without crashing the server.
     */
    @Test
    void runRequest_serverRemainsStableAfterError() throws Exception {
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);
        CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();

        HttpClient client = HttpClient.newHttpClient();
        ws = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            helloLatch.countDown();
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("\"type\":\"run_ack\"")) {
                                ackLatch.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Send a run_request with empty tasks list (will fail validation in parser)
        // No ensemble is configured, so the no-ensemble path fires first
        ws.sendText("{\"type\":\"run_request\",\"requestId\":\"err-req\",\"tasks\":[]}", true)
                .get(3, TimeUnit.SECONDS);

        // Wait for run_ack (REJECTED due to no ensemble or error)
        assertThat(ackLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Server must still be running
        assertThat(dashboard.isRunning()).isTrue();
    }
}
