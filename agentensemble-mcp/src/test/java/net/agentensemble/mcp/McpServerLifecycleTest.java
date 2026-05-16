package net.agentensemble.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpServerLifecycleTest {

    private McpClient mockClient;
    private McpServerLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        mockClient = mock(McpClient.class);
        doNothing().when(mockClient).checkHealth();
        lifecycle = new McpServerLifecycle(mockClient, List.of("npx", "test-server"));
    }

    // ========================
    // State transitions
    // ========================

    @Test
    void isAlive_falseBeforeStart() {
        assertThat(lifecycle.isAlive()).isFalse();
    }

    @Test
    void isAlive_trueAfterStart() {
        lifecycle.start();

        assertThat(lifecycle.isAlive()).isTrue();
    }

    @Test
    void isAlive_falseAfterClose() {
        lifecycle.start();
        lifecycle.close();

        assertThat(lifecycle.isAlive()).isFalse();
    }

    @Test
    void isAlive_falseIfClosedWithoutStart() {
        lifecycle.close();

        assertThat(lifecycle.isAlive()).isFalse();
    }

    // ========================
    // start()
    // ========================

    @Test
    void start_callsHealthCheck() {
        lifecycle.start();

        verify(mockClient).checkHealth();
    }

    @Test
    void start_whenAlreadyStarted_isNoOp() {
        lifecycle.start();
        // Second start() must not throw -- callers in long-running loops are expected to
        // call start() defensively each iteration.
        lifecycle.start();

        assertThat(lifecycle.isAlive()).isTrue();
        // Health check still only runs on the first start, since the second is a no-op.
        verify(mockClient, times(1)).checkHealth();
    }

    @Test
    void start_whenClosed_revivesLifecycle() {
        lifecycle.start();
        lifecycle.close();

        // Calling start() after close() must succeed (revive). This is the explicit fix
        // for the original bug: long-running processes can recover a closed MCP server
        // instead of being permanently broken.
        lifecycle.start();

        assertThat(lifecycle.isAlive()).isTrue();
        // Health check ran twice -- once for the original start, once for the revive.
        verify(mockClient, times(2)).checkHealth();
    }

    @Test
    void start_whenClosed_clearsCachedTools() {
        when(mockClient.listTools())
                .thenReturn(List.of(ToolSpecification.builder()
                        .name("tool1")
                        .description("Tool 1")
                        .parameters(JsonObjectSchema.builder().build())
                        .build()));

        lifecycle.start();
        var firstTools = lifecycle.tools();
        lifecycle.close();
        lifecycle.start();
        var secondTools = lifecycle.tools();

        // Cache must be invalidated across restart so tools() re-lists from the new
        // session. Tool *instances* are still safe to keep around (they look up the
        // current client via supplier in McpAgentTool).
        assertThat(secondTools).isNotSameAs(firstTools);
        verify(mockClient, times(2)).listTools();
    }

    @Test
    void start_healthCheckFails_cleansUpClient() throws Exception {
        doThrow(new RuntimeException("health check failed")).when(mockClient).checkHealth();

        assertThatThrownBy(() -> lifecycle.start()).isInstanceOf(RuntimeException.class);

        // Client should have been closed during cleanup
        verify(mockClient).close();
        // Lifecycle should not be marked as started
        assertThat(lifecycle.isAlive()).isFalse();
    }

    // ========================
    // close()
    // ========================

    @Test
    void close_closesClient() throws Exception {
        lifecycle.start();
        lifecycle.close();

        verify(mockClient).close();
    }

    @Test
    void close_isIdempotent() throws Exception {
        lifecycle.start();
        lifecycle.close();
        lifecycle.close();

        // close() on client should only be called once
        verify(mockClient, times(1)).close();
    }

    @Test
    void close_handlesClientCloseException() throws Exception {
        doThrow(new RuntimeException("close failed")).when(mockClient).close();

        lifecycle.start();
        // Should not throw
        lifecycle.close();

        assertThat(lifecycle.isAlive()).isFalse();
    }

    // ========================
    // tools()
    // ========================

    @Test
    void tools_returnsAgentToolList() {
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addStringProperty("path", "File path")
                .build();
        when(mockClient.listTools())
                .thenReturn(List.of(ToolSpecification.builder()
                        .name("read_file")
                        .description("Read a file")
                        .parameters(schema)
                        .build()));

        lifecycle.start();
        var tools = lifecycle.tools();

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("read_file");
    }

    @Test
    void tools_cachesResult() {
        when(mockClient.listTools())
                .thenReturn(List.of(ToolSpecification.builder()
                        .name("tool1")
                        .description("Tool 1")
                        .parameters(JsonObjectSchema.builder().build())
                        .build()));

        lifecycle.start();
        var first = lifecycle.tools();
        var second = lifecycle.tools();

        // Same list instance -- cached
        assertThat(first).isSameAs(second);
        // listTools should only have been called once
        verify(mockClient, times(1)).listTools();
    }

    @Test
    void tools_throwsIfNotStarted() {
        assertThatThrownBy(() -> lifecycle.tools())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not been started");
    }

    @Test
    void tools_throwsIfClosed() {
        lifecycle.start();
        lifecycle.close();

        assertThatThrownBy(() -> lifecycle.tools())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void tools_returnsUnmodifiableList() {
        when(mockClient.listTools())
                .thenReturn(List.of(ToolSpecification.builder()
                        .name("tool1")
                        .description("Tool 1")
                        .parameters(JsonObjectSchema.builder().build())
                        .build()));

        lifecycle.start();
        var tools = lifecycle.tools();

        assertThatThrownBy(() -> tools.add(mock(net.agentensemble.tool.AgentTool.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Null command handling
    // ========================

    @Test
    void constructor_nullCommand_defaultsToEmptyList() {
        var lc = new McpServerLifecycle(mockClient, null);
        assertThat(lc.command()).isEmpty();
    }

    @Test
    void commandOnlyConstructor_nullCommand_defaultsToEmptyList() {
        var lc = new McpServerLifecycle((List<String>) null);
        assertThat(lc.command()).isEmpty();
    }

    @Test
    void commandOnlyConstructor_closeWithoutStart_doesNotThrow() {
        var lc = new McpServerLifecycle(List.of("npx", "test"));
        // close without start should be safe (client is null)
        lc.close();
        assertThat(lc.isAlive()).isFalse();
    }

    // ========================
    // End-to-end: tool obtained before close still works after restart
    // ========================

    @Test
    void toolCapturedBeforeRestart_executesAgainstNewClientAfterRestart() {
        // The whole point of supplier indirection in McpAgentTool: a tool obtained
        // from lifecycle.tools() before a close()/start() cycle should keep working,
        // because it resolves the current client through the lifecycle on every call.
        when(mockClient.listTools())
                .thenReturn(List.of(ToolSpecification.builder()
                        .name("read_file")
                        .description("Read a file")
                        .parameters(JsonObjectSchema.builder().build())
                        .build()));
        when(mockClient.executeTool(org.mockito.ArgumentMatchers.any()))
                .thenReturn(dev.langchain4j.service.tool.ToolExecutionResult.builder()
                        .resultText("ok")
                        .build());

        lifecycle.start();
        var tools = lifecycle.tools();
        net.agentensemble.tool.AgentTool readFile = tools.get(0);

        // First execution -- baseline.
        net.agentensemble.tool.ToolResult firstResult = readFile.execute("{\"path\":\"a.txt\"}");
        assertThat(firstResult.isSuccess()).isTrue();
        assertThat(firstResult.getOutput()).isEqualTo("ok");

        // Close and restart the lifecycle. Because the test injects a mock client, the
        // same mock is reused after revive (production code path builds a fresh one).
        lifecycle.close();
        lifecycle.start();

        // The captured tool reference must still resolve to a working client via the
        // supplier indirection -- no rebuild required by the caller.
        net.agentensemble.tool.ToolResult secondResult = readFile.execute("{\"path\":\"b.txt\"}");
        assertThat(secondResult.isSuccess()).isTrue();
        assertThat(secondResult.getOutput()).isEqualTo("ok");
        verify(mockClient, times(2)).executeTool(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void toolCapturedBeforeClose_failsCleanlyIfUsedWhileClosed() {
        // Counterpart to the above: if the lifecycle is closed and not yet restarted,
        // a captured tool must surface a clean failure (ToolResult.failure) rather than
        // throwing or silently routing to a dead client. This is what protects an Agent
        // mid-loop from a transiently down MCP server.
        when(mockClient.listTools())
                .thenReturn(List.of(ToolSpecification.builder()
                        .name("read_file")
                        .description("Read a file")
                        .parameters(JsonObjectSchema.builder().build())
                        .build()));

        lifecycle.start();
        net.agentensemble.tool.AgentTool readFile = lifecycle.tools().get(0);
        lifecycle.close();

        net.agentensemble.tool.ToolResult result = readFile.execute("{}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("not running");
    }

    // ========================
    // ManagedResource contract
    // ========================

    @Test
    void implementsManagedResource() {
        assertThat(lifecycle).isInstanceOf(net.agentensemble.ensemble.ManagedResource.class);
    }

    @Test
    void isRunning_aliasesIsAlive() {
        assertThat(lifecycle.isRunning()).isFalse();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        lifecycle.close();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    // ========================
    // command()
    // ========================

    @Test
    void command_returnsCommandList() {
        assertThat(lifecycle.command()).containsExactly("npx", "test-server");
    }

    @Test
    void command_isUnmodifiable() {
        assertThatThrownBy(() -> lifecycle.command().add("extra")).isInstanceOf(UnsupportedOperationException.class);
    }
}
