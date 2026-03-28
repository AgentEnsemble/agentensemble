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
    void start_whenAlreadyStarted_throws() {
        lifecycle.start();

        assertThatThrownBy(() -> lifecycle.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already started");
    }

    @Test
    void start_whenClosed_throws() {
        lifecycle.close();

        assertThatThrownBy(() -> lifecycle.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
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
