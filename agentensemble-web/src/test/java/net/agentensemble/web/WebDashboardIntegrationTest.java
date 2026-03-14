package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the full WebSocket message flow from lifecycle events to browser client.
 *
 * <p>Each test starts a real embedded Javalin server on an ephemeral port, connects a Java
 * {@link HttpClient} WebSocket client, fires events through the dashboard or streaming listener,
 * and asserts that the expected JSON messages arrive at the client in the correct order.
 *
 * <p>These tests exercise the full path: Java API -&gt; ConnectionManager -&gt; WebSocket server
 * -&gt; in-process client. They do not run a real {@code Ensemble}; they call the dashboard's
 * public APIs directly to verify the wire protocol.
 */
class WebDashboardIntegrationTest {

    private WebDashboard dashboard;
    private List<String> received;
    private WebSocket ws;

    @BeforeEach
    void setUp() throws Exception {
        received = new CopyOnWriteArrayList<>();
        dashboard = WebDashboard.builder().port(0).host("0.0.0.0").build();
        dashboard.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort close
            }
        }
        if (dashboard != null) {
            dashboard.stop();
        }
    }

    // ========================
    // Helpers
    // ========================

    /**
     * Connects a WebSocket client that:
     * <ul>
     *   <li>Decrements {@code connectedLatch} on {@code onOpen}.</li>
     *   <li>Decrements {@code helloLatch} when the first {@code hello} message arrives, so callers
     *       can await deterministically instead of using a fixed sleep.</li>
     *   <li>Decrements {@code messageLatch} (up to {@code countDown} times) when any message
     *       matching {@code messageFilter} arrives.</li>
     * </ul>
     */
    private WebSocket connectClientForCount(
            CountDownLatch connectedLatch,
            CountDownLatch helloLatch,
            CountDownLatch messageLatch,
            String messageFilter,
            int countDown) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            return client.newWebSocketBuilder()
                    .buildAsync(
                            URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                                @Override
                                public void onOpen(WebSocket webSocket) {
                                    if (connectedLatch != null) connectedLatch.countDown();
                                    webSocket.request(50);
                                }

                                @Override
                                public CompletableFuture<?> onText(
                                        WebSocket webSocket, CharSequence data, boolean last) {
                                    if (last) {
                                        String msg = data.toString();
                                        received.add(msg);
                                        // Deterministic hello wait: decrement latch when server
                                        // sends the hello message after connection is established.
                                        if (helloLatch != null
                                                && msg.contains("\"type\":\"hello\"")
                                                && helloLatch.getCount() > 0) {
                                            helloLatch.countDown();
                                        }
                                        if (messageFilter == null || msg.contains(messageFilter)) {
                                            for (int i = 0; i < countDown; i++) {
                                                if (messageLatch.getCount() > 0) messageLatch.countDown();
                                            }
                                        }
                                    }
                                    webSocket.request(1);
                                    return null;
                                }
                            })
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WebSocket connectClient(
            CountDownLatch connectedLatch,
            CountDownLatch helloLatch,
            CountDownLatch messageLatch,
            String messageFilter) {
        return connectClientForCount(connectedLatch, helloLatch, messageLatch, messageFilter, 1);
    }

    // ========================
    // ensemble_started -> task events -> ensemble_completed flow
    // ========================

    @Test
    void allLifecycleMessageTypesArriveInOrder() throws Exception {
        // Connect before run starts; wait for hello before firing lifecycle events.
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch gotCompleted = new CountDownLatch(1);
        ws = connectClientForCount(connected, helloLatch, gotCompleted, "ensemble_completed", 1);
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Fire the full lifecycle sequence
        dashboard.onEnsembleStarted("ens-integ", Instant.now(), 2, "SEQUENTIAL");

        net.agentensemble.callback.EnsembleListener listener = dashboard.streamingListener();
        listener.onTaskStart(new TaskStartEvent("Research AI", "Researcher", 1, 2));
        listener.onToolCall(
                new ToolCallEvent("web_search", "{}", "results", null, "Researcher", Duration.ofMillis(500)));
        listener.onTaskComplete(new TaskCompleteEvent("Research AI", "Researcher", null, Duration.ofSeconds(3), 1, 2));

        listener.onTaskStart(new TaskStartEvent("Write report", "Writer", 2, 2));
        listener.onTaskComplete(new TaskCompleteEvent("Write report", "Writer", null, Duration.ofSeconds(2), 2, 2));

        dashboard.onEnsembleCompleted("ens-integ", Instant.now(), 5000L, "COMPLETED", 800L, 1);

        assertThat(gotCompleted.await(10, TimeUnit.SECONDS)).isTrue();

        // Check all expected message types arrived
        List<String> messages = List.copyOf(received);
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("\"type\":\"hello\""));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("\"type\":\"ensemble_started\""));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("\"type\":\"task_started\""));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("\"type\":\"tool_called\""));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("\"type\":\"task_completed\""));
        assertThat(messages).anySatisfy(m -> assertThat(m).contains("\"type\":\"ensemble_completed\""));
    }

    @Test
    void taskFailedEventArrivesAtClient() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch gotFailed = new CountDownLatch(1);
        ws = connectClient(connected, helloLatch, gotFailed, "task_failed");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        dashboard.onEnsembleStarted("ens-fail", Instant.now(), 1, "SEQUENTIAL");
        net.agentensemble.callback.EnsembleListener listener = dashboard.streamingListener();
        listener.onTaskStart(new TaskStartEvent("Failing task", "Agent", 1, 1));
        listener.onTaskFailed(new TaskFailedEvent(
                "Failing task", "Agent", new RuntimeException("timeout"), Duration.ofMillis(100), 1, 1));

        assertThat(gotFailed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anySatisfy(m -> {
            assertThat(m).contains("\"type\":\"task_failed\"");
            assertThat(m).contains("timeout");
        });
    }

    @Test
    void delegationEventsArriveAtClient() throws Exception {
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch gotCompleted = new CountDownLatch(1);
        ws = connectClient(connected, helloLatch, gotCompleted, "delegation_completed");
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(helloLatch.await(5, TimeUnit.SECONDS)).isTrue();

        net.agentensemble.callback.EnsembleListener listener = dashboard.streamingListener();
        listener.onDelegationStarted(new DelegationStartedEvent("del-1", "Manager", "Worker", "Do work", 1, null));
        listener.onDelegationCompleted(
                new DelegationCompletedEvent("del-1", "Manager", "Worker", null, Duration.ofSeconds(2)));

        assertThat(gotCompleted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received).anySatisfy(m -> assertThat(m).contains("\"type\":\"delegation_started\""));
        assertThat(received).anySatisfy(m -> assertThat(m).contains("\"type\":\"delegation_completed\""));
    }

    @Test
    void lateJoinerReceivesSnapshotWithAllPastEvents() throws Exception {
        // Fire events before any client connects. All snapshot operations are synchronous
        // in-memory writes so no sleep is needed before connecting the late-joining client.
        dashboard.onEnsembleStarted("ens-late", Instant.now(), 2, "SEQUENTIAL");
        net.agentensemble.callback.EnsembleListener listener = dashboard.streamingListener();
        listener.onTaskStart(new TaskStartEvent("Task 1", "Agent", 1, 2));
        listener.onTaskComplete(new TaskCompleteEvent("Task 1", "Agent", null, Duration.ofSeconds(1), 1, 2));
        listener.onTaskStart(new TaskStartEvent("Task 2", "Agent", 2, 2));

        // Late-joining client: use separate latches for connection and for hello message receipt.
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch gotHello = new CountDownLatch(1);
        ws = connectClientForCount(connected, gotHello, gotHello, "hello", 1);
        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
        // Wait for the actual hello message to be received by the client.
        assertThat(gotHello.await(5, TimeUnit.SECONDS)).isTrue();

        String helloJson = received.stream()
                .filter(m -> m.contains("\"type\":\"hello\""))
                .findFirst()
                .orElse("");

        // Hello must contain snapshot of all past events
        assertThat(helloJson).contains("snapshotTrace");
        assertThat(helloJson).contains("ensemble_started");
        assertThat(helloJson).contains("task_started");
        assertThat(helloJson).contains("task_completed");
        // Should be ensembleId from noteEnsembleStarted
        assertThat(helloJson).contains("\"ensembleId\":\"ens-late\"");
    }

    @Test
    void multipleClientsAllReceiveBroadcasts() throws Exception {
        // Connect two clients, verify both receive the same events.
        List<String> received2 = new CopyOnWriteArrayList<>();
        CountDownLatch connected1 = new CountDownLatch(1);
        CountDownLatch connected2 = new CountDownLatch(1);
        CountDownLatch hello1 = new CountDownLatch(1);
        CountDownLatch hello2 = new CountDownLatch(1);
        CountDownLatch got1 = new CountDownLatch(1);
        CountDownLatch got2 = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();

        // Client 1
        ws = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected1.countDown();
                        webSocket.request(10);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            received.add(msg);
                            if (msg.contains("\"type\":\"hello\"") && hello1.getCount() > 0) hello1.countDown();
                            if (msg.contains("ensemble_started")) got1.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        // Client 2
        WebSocket ws2 = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        connected2.countDown();
                        webSocket.request(10);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            received2.add(msg);
                            if (msg.contains("\"type\":\"hello\"") && hello2.getCount() > 0) hello2.countDown();
                            if (msg.contains("ensemble_started")) got2.countDown();
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected1.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(connected2.await(5, TimeUnit.SECONDS)).isTrue();
        // Wait for both hello messages before broadcasting, to avoid a race where
        // ensemble_started is sent before the server processes both connections.
        assertThat(hello1.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(hello2.await(5, TimeUnit.SECONDS)).isTrue();

        dashboard.onEnsembleStarted("ens-multi", Instant.now(), 1, "SEQUENTIAL");

        assertThat(got1.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(got2.await(5, TimeUnit.SECONDS)).isTrue();

        // Both clients must have received ensemble_started
        assertThat(received).anySatisfy(m -> assertThat(m).contains("\"type\":\"ensemble_started\""));
        assertThat(received2).anySatisfy(m -> assertThat(m).contains("\"type\":\"ensemble_started\""));

        ws2.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }
}
