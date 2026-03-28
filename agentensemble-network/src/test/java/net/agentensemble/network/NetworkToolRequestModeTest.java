package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.web.protocol.ServerMessage;
import net.agentensemble.web.protocol.ToolResponseMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NetworkTool} request mode behavior (AWAIT, ASYNC, AWAIT_WITH_DEADLINE).
 */
class NetworkToolRequestModeTest {

    private NetworkClientRegistry registry;
    private NetworkClient client;

    @BeforeEach
    void setUp() throws IOException {
        registry = mock(NetworkClientRegistry.class);
        client = mock(NetworkClient.class);
        when(registry.getOrCreate("kitchen")).thenReturn(client);
    }

    @Test
    void awaitMode_blocksUntilResult() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new ToolResponseMessage("req-1", "COMPLETED", "3 portions available", null, 200L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.AWAIT)
                .build();

        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("3 portions available");
    }

    @Test
    void asyncMode_returnsImmediately() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        // Intentionally never completed
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.ASYNC)
                .onComplete(result -> {})
                .build();

        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Request submitted asynchronously");
    }

    @Test
    void asyncMode_callbackFiresOnCompletion() throws Exception {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        when(client.send(any(), anyString())).thenReturn(future);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ToolResult> callbackResult = new AtomicReference<>();

        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.ASYNC)
                .onComplete(result -> {
                    callbackResult.set(result);
                    latch.countDown();
                })
                .build();

        ToolResult immediateResult = tool.execute("wagyu");
        assertThat(immediateResult.isSuccess()).isTrue();
        assertThat(immediateResult.getOutput()).isEqualTo("Request submitted asynchronously");

        // Complete the future after doExecute has returned
        future.complete(new ToolResponseMessage("req-1", "COMPLETED", "3 portions available", null, 200L));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackResult.get()).isNotNull();
        assertThat(callbackResult.get().isSuccess()).isTrue();
        assertThat(callbackResult.get().getOutput()).isEqualTo("3 portions available");
    }

    @Test
    void deadlineMode_withinDeadline_returnsResult() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        future.complete(new ToolResponseMessage("req-1", "COMPLETED", "3 portions available", null, 200L));
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.AWAIT_WITH_DEADLINE)
                .deadline(Duration.ofSeconds(5))
                .build();

        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("3 portions available");
    }

    @Test
    void deadlineMode_exceeded_returnTimeoutError() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        // Never completed -- will exceed the deadline
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.AWAIT_WITH_DEADLINE)
                .deadline(Duration.ofMillis(10))
                .deadlineAction(DeadlineAction.RETURN_TIMEOUT_ERROR)
                .build();

        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Deadline exceeded");
    }

    @Test
    void deadlineMode_exceeded_returnPartial() throws IOException {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        // Never completed -- will exceed the deadline
        when(client.send(any(), anyString())).thenReturn(future);

        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.AWAIT_WITH_DEADLINE)
                .deadline(Duration.ofMillis(10))
                .deadlineAction(DeadlineAction.RETURN_PARTIAL)
                .build();

        ToolResult result = tool.execute("wagyu");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Deadline exceeded");
        assertThat(result.getOutput()).contains("background");
    }

    @Test
    void deadlineMode_exceeded_continueInBackground() throws Exception {
        CompletableFuture<ServerMessage> future = new CompletableFuture<>();
        when(client.send(any(), anyString())).thenReturn(future);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ToolResult> callbackResult = new AtomicReference<>();

        NetworkTool tool = NetworkTool.builder()
                .ensembleName("kitchen")
                .toolName("check-inventory")
                .clientRegistry(registry)
                .mode(RequestMode.AWAIT_WITH_DEADLINE)
                .deadline(Duration.ofMillis(10))
                .deadlineAction(DeadlineAction.CONTINUE_IN_BACKGROUND)
                .onComplete(result -> {
                    callbackResult.set(result);
                    latch.countDown();
                })
                .build();

        ToolResult immediateResult = tool.execute("wagyu");

        assertThat(immediateResult.isSuccess()).isTrue();
        assertThat(immediateResult.getOutput()).contains("Deadline exceeded");
        assertThat(immediateResult.getOutput()).contains("background");

        // Complete the future after the deadline has passed
        future.complete(new ToolResponseMessage("req-1", "COMPLETED", "3 portions available", null, 200L));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(callbackResult.get()).isNotNull();
        assertThat(callbackResult.get().isSuccess()).isTrue();
        assertThat(callbackResult.get().getOutput()).isEqualTo("3 portions available");
    }
}
