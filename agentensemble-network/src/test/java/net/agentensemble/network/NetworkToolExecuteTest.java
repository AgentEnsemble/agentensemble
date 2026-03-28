package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.web.protocol.HeartbeatMessage;
import net.agentensemble.web.protocol.ServerMessage;
import net.agentensemble.web.protocol.ToolResponseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkTool#doExecute(String)} with mocked network layer.
 */
class NetworkToolExecuteTest {

    private NetworkClientRegistry registry;
    private NetworkClient client;

    @BeforeEach
    void setUp() throws IOException {
        registry = mock(NetworkClientRegistry.class);
        client = mock(NetworkClient.class);
        when(registry.getOrCreate("kitchen")).thenReturn(client);
    }

    @Test
    void doExecute_completedResponse_returnsSuccess() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new ToolResponseMessage("req-1", "COMPLETED", "3 portions available", null, 200L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);
        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("3 portions available");
    }

    @Test
    void doExecute_failedResponse_returnsFailureWithErrorMessage() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new ToolResponseMessage("req-1", "FAILED", null, "Inventory unavailable", 100L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);
        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Inventory unavailable");
    }

    @Test
    void doExecute_failedResponseWithNullError_returnsDefaultMessage() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new ToolResponseMessage("req-1", "FAILED", null, null, 100L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);
        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Tool call failed");
    }

    @Test
    void doExecute_timeout_returnsFailureWithTimeoutMessage() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>(); // never completes
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = new NetworkTool("kitchen", "check-inventory", Duration.ofMillis(50), registry);
        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("timed out");
        assertThat(result.getErrorMessage()).contains("check-inventory");
        assertThat(result.getErrorMessage()).contains("kitchen");
    }

    @Test
    void doExecute_ioException_returnsFailureWithNetworkError() throws IOException {
        when(client.send(any(), anyString())).thenThrow(new IOException("Connection refused"));

        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);
        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Network error");
        assertThat(result.getErrorMessage()).contains("check-inventory");
        assertThat(result.getErrorMessage()).contains("kitchen");
    }

    @Test
    void doExecute_interruptedException_returnsFailureAndRestoresInterruptFlag() throws IOException {
        CompletableFuture<ServerMessage> interruptingFuture = new CompletableFuture<>();
        when(client.send(any(), anyString())).thenReturn(interruptingFuture);

        // Set interrupt flag before get() is called so it throws InterruptedException
        Thread.currentThread().interrupt();

        NetworkTool tool = new NetworkTool("kitchen", "check-inventory", Duration.ofSeconds(5), registry);
        ToolResult result = tool.execute("wagyu");

        // The interrupted flag should be restored
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("interrupted");

        // Clear interrupt flag for test cleanup
        Thread.interrupted();
    }

    @Test
    void doExecute_unexpectedResponseType_returnsFailure() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new HeartbeatMessage(System.currentTimeMillis()));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.from("kitchen", "check-inventory", registry);
        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Unexpected response type");
        assertThat(result.getErrorMessage()).contains("HeartbeatMessage");
    }
}
