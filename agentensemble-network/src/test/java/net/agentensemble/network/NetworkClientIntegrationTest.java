package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.agentensemble.dashboard.RequestHandler;
import net.agentensemble.web.WebDashboard;
import net.agentensemble.web.protocol.ServerMessage;
import net.agentensemble.web.protocol.TaskRequestMessage;
import net.agentensemble.web.protocol.TaskResponseMessage;
import net.agentensemble.web.protocol.ToolRequestMessage;
import net.agentensemble.web.protocol.ToolResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link NetworkClient} using a real embedded WebSocket server
 * via {@link WebDashboard}.
 */
class NetworkClientIntegrationTest {

    private WebDashboard dashboard;
    private int port;

    @BeforeEach
    void setUp() {
        dashboard = WebDashboard.builder().port(0).host("0.0.0.0").build();
        dashboard.start();
        port = dashboard.actualPort();

        // Wire up request handler that echoes back responses
        dashboard.setRequestHandler(new RequestHandler() {
            @Override
            public TaskResult handleTaskRequest(String taskName, String context) {
                return new TaskResult("COMPLETED", "Result for: " + context, null, 100L);
            }

            @Override
            public ToolResult handleToolRequest(String toolName, String input) {
                return new ToolResult("COMPLETED", "Tool result: " + input, null, 50L);
            }
        });
    }

    @AfterEach
    void tearDown() {
        dashboard.stop();
    }

    @Test
    void sendTaskRequest_receivesResponse() throws Exception {
        NetworkClient client =
                new NetworkClient("test-ensemble", "ws://localhost:" + port + "/ws", Duration.ofSeconds(5));
        try {
            TaskRequestMessage request = new TaskRequestMessage(
                    "req-1", "caller", "my-task", "hello", null, null, null, null, null, null, null);
            CompletableFuture<ServerMessage> future = client.send(request, "req-1");

            ServerMessage response = future.get(5, TimeUnit.SECONDS);
            assertThat(response).isInstanceOf(TaskResponseMessage.class);
            TaskResponseMessage taskResponse = (TaskResponseMessage) response;
            assertThat(taskResponse.requestId()).isEqualTo("req-1");
            assertThat(taskResponse.status()).isEqualTo("COMPLETED");
            assertThat(taskResponse.result()).isEqualTo("Result for: hello");
        } finally {
            client.close();
        }
    }

    @Test
    void sendToolRequest_receivesResponse() throws Exception {
        NetworkClient client =
                new NetworkClient("test-ensemble", "ws://localhost:" + port + "/ws", Duration.ofSeconds(5));
        try {
            ToolRequestMessage request = new ToolRequestMessage("req-2", "caller", "my-tool", "input-data", null);
            CompletableFuture<ServerMessage> future = client.send(request, "req-2");

            ServerMessage response = future.get(5, TimeUnit.SECONDS);
            assertThat(response).isInstanceOf(ToolResponseMessage.class);
            ToolResponseMessage toolResponse = (ToolResponseMessage) response;
            assertThat(toolResponse.requestId()).isEqualTo("req-2");
            assertThat(toolResponse.result()).isEqualTo("Tool result: input-data");
        } finally {
            client.close();
        }
    }

    @Test
    void close_failsPendingFutures() throws Exception {
        // Use a dashboard that never responds (no request handler for this test)
        WebDashboard silentDashboard =
                WebDashboard.builder().port(0).host("0.0.0.0").build();
        silentDashboard.start();
        int silentPort = silentDashboard.actualPort();

        NetworkClient client =
                new NetworkClient("test-ensemble", "ws://localhost:" + silentPort + "/ws", Duration.ofSeconds(5));
        try {
            TaskRequestMessage request = new TaskRequestMessage(
                    "req-3", "caller", "my-task", "data", null, null, null, null, null, null, null);
            CompletableFuture<ServerMessage> future = client.send(request, "req-3");

            assertThat(future).isNotDone();
            client.close();
            assertThat(future).isCompletedExceptionally();
        } finally {
            silentDashboard.stop();
        }
    }

    @Test
    void pendingCount_tracksInflightRequests() throws Exception {
        // Use a dashboard that never responds
        WebDashboard silentDashboard =
                WebDashboard.builder().port(0).host("0.0.0.0").build();
        silentDashboard.start();
        int silentPort = silentDashboard.actualPort();

        NetworkClient client =
                new NetworkClient("test-ensemble", "ws://localhost:" + silentPort + "/ws", Duration.ofSeconds(5));
        try {
            assertThat(client.pendingCount()).isEqualTo(0);

            TaskRequestMessage request = new TaskRequestMessage(
                    "req-4", "caller", "my-task", "data", null, null, null, null, null, null, null);
            client.send(request, "req-4");
            assertThat(client.pendingCount()).isEqualTo(1);
        } finally {
            client.close();
            silentDashboard.stop();
        }
    }

    @Test
    void connectionReuse_secondSendReusesSameConnection() throws Exception {
        NetworkClient client =
                new NetworkClient("test-ensemble", "ws://localhost:" + port + "/ws", Duration.ofSeconds(5));
        try {
            TaskRequestMessage req1 =
                    new TaskRequestMessage("req-5", "caller", "task", "a", null, null, null, null, null, null, null);
            client.send(req1, "req-5").get(5, TimeUnit.SECONDS);

            TaskRequestMessage req2 =
                    new TaskRequestMessage("req-6", "caller", "task", "b", null, null, null, null, null, null, null);
            ServerMessage resp2 = client.send(req2, "req-6").get(5, TimeUnit.SECONDS);
            assertThat(resp2).isInstanceOf(TaskResponseMessage.class);
        } finally {
            client.close();
        }
    }

    @Test
    void serverShutdown_failsPendingRequests() throws Exception {
        WebDashboard tempDashboard =
                WebDashboard.builder().port(0).host("0.0.0.0").build();
        tempDashboard.start();
        int tempPort = tempDashboard.actualPort();

        NetworkClient client =
                new NetworkClient("test-ensemble", "ws://localhost:" + tempPort + "/ws", Duration.ofSeconds(5));
        try {
            TaskRequestMessage request =
                    new TaskRequestMessage("req-7", "caller", "task", "data", null, null, null, null, null, null, null);
            CompletableFuture<ServerMessage> future = client.send(request, "req-7");

            tempDashboard.stop();
            Thread.sleep(500);

            assertThat(future).isCompletedExceptionally();
        } finally {
            client.close();
        }
    }

    @Test
    void fullRoundTrip_networkTaskWithRealServer() throws Exception {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("test-server", "ws://localhost:" + port + "/ws")
                .build();

        try (NetworkClientRegistry registry = new NetworkClientRegistry(config)) {
            NetworkTask task = NetworkTask.from("test-server", "prepare-meal", registry);
            net.agentensemble.tool.ToolResult result = task.execute("wagyu steak, medium-rare");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Result for:");
        }
    }

    @Test
    void fullRoundTrip_networkToolWithRealServer() throws Exception {
        NetworkConfig config = NetworkConfig.builder()
                .ensemble("test-server", "ws://localhost:" + port + "/ws")
                .build();

        try (NetworkClientRegistry registry = new NetworkClientRegistry(config)) {
            NetworkTool tool = NetworkTool.from("test-server", "check-inventory", registry);
            net.agentensemble.tool.ToolResult result = tool.execute("wagyu beef");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Tool result:");
        }
    }
}
