package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.dashboard.RequestHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WebDashboard} handling of incoming task and tool requests.
 *
 * <p>Uses a real WebSocket connection to send request messages and verify responses.
 * The dashboard is started on an ephemeral port with a non-localhost binding to
 * bypass origin restrictions (same pattern as {@link WebSocketServerTest}).
 */
class WebDashboardRequestHandlingTest {

    private WebDashboard dashboard;

    @BeforeEach
    void setUp() {
        dashboard = WebDashboard.builder().port(0).host("0.0.0.0").build();
        dashboard.start();
    }

    @AfterEach
    void tearDown() {
        if (dashboard != null && dashboard.isRunning()) {
            dashboard.stop();
        }
    }

    @Test
    void taskRequest_receivesTaskAcceptedAndTaskResponse() throws Exception {
        dashboard.setRequestHandler(new RequestHandler() {
            @Override
            public TaskResult handleTaskRequest(String taskName, String context) {
                return new TaskResult("COMPLETED", "Meal is ready: " + context, null, 1500);
            }

            @Override
            public ToolResult handleToolRequest(String toolName, String input) {
                return new ToolResult("COMPLETED", "done", null, 100);
            }
        });

        int port = dashboard.actualPort();

        CountDownLatch gotAccepted = new CountDownLatch(1);
        CountDownLatch gotResponse = new CountDownLatch(1);
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
                            if (msg.contains("\"type\":\"task_accepted\"")) {
                                gotAccepted.countDown();
                            }
                            if (msg.contains("\"type\":\"task_response\"")) {
                                gotResponse.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        // Send a task_request message
        String taskRequestJson = "{\"type\":\"task_request\",\"requestId\":\"req-42\",\"from\":\"caller\","
                + "\"task\":\"prepare-meal\",\"context\":\"make a salad\"}";
        ws.sendText(taskRequestJson, true).get(5, TimeUnit.SECONDS);

        assertThat(gotAccepted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(gotResponse.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify task_accepted message
        assertThat(received)
                .anyMatch(m -> m.contains("\"type\":\"task_accepted\"") && m.contains("\"requestId\":\"req-42\""));

        // Verify task_response message
        assertThat(received)
                .anyMatch(m -> m.contains("\"type\":\"task_response\"")
                        && m.contains("\"requestId\":\"req-42\"")
                        && m.contains("COMPLETED")
                        && m.contains("Meal is ready: make a salad"));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void toolRequest_receivesToolResponse() throws Exception {
        dashboard.setRequestHandler(new RequestHandler() {
            @Override
            public TaskResult handleTaskRequest(String taskName, String context) {
                return new TaskResult("COMPLETED", "done", null, 100);
            }

            @Override
            public ToolResult handleToolRequest(String toolName, String input) {
                return new ToolResult("COMPLETED", "3 portions of " + input, null, 200);
            }
        });

        int port = dashboard.actualPort();

        CountDownLatch gotResponse = new CountDownLatch(1);
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
                            if (msg.contains("\"type\":\"tool_response\"")) {
                                gotResponse.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        // Send a tool_request message
        String toolRequestJson = "{\"type\":\"tool_request\",\"requestId\":\"req-77\",\"from\":\"caller\","
                + "\"tool\":\"check-inventory\",\"input\":\"wagyu\"}";
        ws.sendText(toolRequestJson, true).get(5, TimeUnit.SECONDS);

        assertThat(gotResponse.await(5, TimeUnit.SECONDS)).isTrue();

        // Verify tool_response message
        assertThat(received)
                .anyMatch(m -> m.contains("\"type\":\"tool_response\"")
                        && m.contains("\"requestId\":\"req-77\"")
                        && m.contains("COMPLETED")
                        && m.contains("3 portions of wagyu"));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void taskRequest_withNoHandler_noResponse() throws Exception {
        // Do NOT set a request handler -- dashboard.setRequestHandler(...) is not called

        int port = dashboard.actualPort();

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
                            received.add(data.toString());
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        // Send a task_request message when no handler is set
        String taskRequestJson = "{\"type\":\"task_request\",\"requestId\":\"req-99\",\"from\":\"caller\","
                + "\"task\":\"prepare-meal\",\"context\":\"make a salad\"}";
        ws.sendText(taskRequestJson, true).get(5, TimeUnit.SECONDS);

        // Wait a bit to allow any response to arrive (there should be none)
        Thread.sleep(500);

        // No task_accepted or task_response should be sent
        assertThat(received).noneMatch(m -> m.contains("\"type\":\"task_accepted\""));
        assertThat(received).noneMatch(m -> m.contains("\"type\":\"task_response\""));

        // Server should remain running
        assertThat(dashboard.isRunning()).isTrue();

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void taskRequest_rejected_doesNotSendTaskAccepted() throws Exception {
        dashboard.setRequestHandler(new RequestHandler() {
            @Override
            public TaskResult handleTaskRequest(String taskName, String context) {
                return new TaskResult("REJECTED", null, "Ensemble is DRAINING", 0);
            }

            @Override
            public ToolResult handleToolRequest(String toolName, String input) {
                return new ToolResult("COMPLETED", "done", null, 100);
            }
        });

        int port = dashboard.actualPort();

        CountDownLatch gotResponse = new CountDownLatch(1);
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
                            if (msg.contains("\"type\":\"task_response\"")) {
                                gotResponse.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        String taskRequestJson = "{\"type\":\"task_request\",\"requestId\":\"req-rejected\",\"from\":\"caller\","
                + "\"task\":\"prepare-meal\",\"context\":\"make a salad\"}";
        ws.sendText(taskRequestJson, true).get(5, TimeUnit.SECONDS);

        assertThat(gotResponse.await(5, TimeUnit.SECONDS)).isTrue();

        // Rejected requests should NOT receive task_accepted
        assertThat(received).noneMatch(m -> m.contains("\"type\":\"task_accepted\""));

        // Should receive task_response with REJECTED status
        assertThat(received)
                .anyMatch(m -> m.contains("\"type\":\"task_response\"")
                        && m.contains("REJECTED")
                        && m.contains("Ensemble is DRAINING"));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void taskRequest_handlerThrows_sendsErrorResponse() throws Exception {
        dashboard.setRequestHandler(new RequestHandler() {
            @Override
            public TaskResult handleTaskRequest(String taskName, String context) {
                throw new RuntimeException("handler exploded");
            }

            @Override
            public ToolResult handleToolRequest(String toolName, String input) {
                return new ToolResult("COMPLETED", "done", null, 100);
            }
        });

        int port = dashboard.actualPort();

        CountDownLatch gotResponse = new CountDownLatch(1);
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
                            if (msg.contains("\"type\":\"task_response\"")) {
                                gotResponse.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        String taskRequestJson = "{\"type\":\"task_request\",\"requestId\":\"req-err\",\"from\":\"caller\","
                + "\"task\":\"prepare-meal\",\"context\":\"boom\"}";
        ws.sendText(taskRequestJson, true).get(5, TimeUnit.SECONDS);

        assertThat(gotResponse.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received)
                .anyMatch(m -> m.contains("\"type\":\"task_response\"")
                        && m.contains("FAILED")
                        && m.contains("handler exploded"));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void toolRequest_handlerThrows_sendsErrorResponse() throws Exception {
        dashboard.setRequestHandler(new RequestHandler() {
            @Override
            public TaskResult handleTaskRequest(String taskName, String context) {
                return new TaskResult("COMPLETED", "done", null, 100);
            }

            @Override
            public ToolResult handleToolRequest(String toolName, String input) {
                throw new RuntimeException("tool handler exploded");
            }
        });

        int port = dashboard.actualPort();

        CountDownLatch gotResponse = new CountDownLatch(1);
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
                            if (msg.contains("\"type\":\"tool_response\"")) {
                                gotResponse.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

        String toolRequestJson = "{\"type\":\"tool_request\",\"requestId\":\"req-terr\",\"from\":\"caller\","
                + "\"tool\":\"check-inventory\",\"input\":\"boom\"}";
        ws.sendText(toolRequestJson, true).get(5, TimeUnit.SECONDS);

        assertThat(gotResponse.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received)
                .anyMatch(m -> m.contains("\"type\":\"tool_response\"")
                        && m.contains("FAILED")
                        && m.contains("tool handler exploded"));

        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
    }

    @Test
    void stop_shutsDownRequestExecutor() throws Exception {
        dashboard.setRequestHandler(new RequestHandler() {
            @Override
            public TaskResult handleTaskRequest(String taskName, String context) {
                return new TaskResult("COMPLETED", "done", null, 100);
            }

            @Override
            public ToolResult handleToolRequest(String toolName, String input) {
                return new ToolResult("COMPLETED", "done", null, 100);
            }
        });

        assertThat(dashboard.isRunning()).isTrue();
        dashboard.stop();
        assertThat(dashboard.isRunning()).isFalse();
    }
}
