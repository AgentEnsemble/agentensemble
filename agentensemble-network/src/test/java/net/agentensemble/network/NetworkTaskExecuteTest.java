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
import net.agentensemble.web.protocol.TaskResponseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkTask#doExecute(String)} with mocked network layer.
 */
class NetworkTaskExecuteTest {

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
        future.complete(new TaskResponseMessage("req-1", "COMPLETED", "Meal ready", null, 5000L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);
        ToolResult result = task.execute("make a salad");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Meal ready");
    }

    @Test
    void doExecute_failedResponse_returnsFailureWithErrorMessage() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new TaskResponseMessage("req-1", "FAILED", null, "Out of ingredients", 1000L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);
        ToolResult result = task.execute("make a salad");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Out of ingredients");
    }

    @Test
    void doExecute_failedResponseWithNullError_returnsDefaultMessage() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new TaskResponseMessage("req-1", "FAILED", null, null, 1000L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);
        ToolResult result = task.execute("make a salad");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Task failed");
    }

    @Test
    void doExecute_timeout_returnsFailureWithTimeoutMessage() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>(); // never completes
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTask task = new NetworkTask("kitchen", "prepare-meal", Duration.ofMillis(50), registry);
        ToolResult result = task.execute("make a salad");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("timed out");
        assertThat(result.getErrorMessage()).contains("prepare-meal");
        assertThat(result.getErrorMessage()).contains("kitchen");
    }

    @Test
    void doExecute_ioException_returnsFailureWithNetworkError() throws IOException {
        when(client.send(any(), anyString())).thenThrow(new IOException("Connection refused"));

        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);
        ToolResult result = task.execute("make a salad");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Network error");
        assertThat(result.getErrorMessage()).contains("prepare-meal");
        assertThat(result.getErrorMessage()).contains("kitchen");
    }

    @Test
    void doExecute_interruptedException_returnsFailureAndRestoresInterruptFlag() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.completeExceptionally(new InterruptedException("interrupted"));
        when(client.send(any(), anyString())).thenReturn(future);

        // The future.get() wraps InterruptedException in ExecutionException, so
        // we need to simulate a real interruption. Use a future that we manually
        // complete exceptionally with an ExecutionException wrapping InterruptedException.
        CompletableFuture<ServerMessage> interruptingFuture = new CompletableFuture<>();
        when(client.send(any(), anyString())).thenReturn(interruptingFuture);

        // Complete the future on a separate thread after a short delay to allow
        // the main thread to enter future.get(). Instead, we can trigger the
        // interrupt before get() is called.
        Thread.currentThread().interrupt();

        NetworkTask task = new NetworkTask("kitchen", "prepare-meal", Duration.ofSeconds(5), registry);
        ToolResult result = task.execute("make a salad");

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

        NetworkTask task = NetworkTask.from("kitchen", "prepare-meal", registry);
        ToolResult result = task.execute("make a salad");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Unexpected response type");
        assertThat(result.getErrorMessage()).contains("HeartbeatMessage");
    }
}
