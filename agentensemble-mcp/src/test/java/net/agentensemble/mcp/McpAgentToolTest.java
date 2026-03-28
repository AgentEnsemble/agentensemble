package net.agentensemble.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.util.List;
import net.agentensemble.tool.CustomSchemaAgentTool;
import net.agentensemble.tool.LangChain4jToolAdapter;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class McpAgentToolTest {

    private McpClient mockClient;
    private JsonObjectSchema schema;
    private McpAgentTool tool;

    @BeforeEach
    void setUp() {
        mockClient = mock(McpClient.class);
        schema = JsonObjectSchema.builder()
                .addStringProperty("path", "The file path")
                .addStringProperty("content", "File content")
                .required(List.of("path"))
                .build();
        tool = new McpAgentTool(mockClient, "write_file", "Write content to a file", schema);
    }

    // ========================
    // Metadata
    // ========================

    @Test
    void name_returnsToolName() {
        assertThat(tool.name()).isEqualTo("write_file");
    }

    @Test
    void description_returnsToolDescription() {
        assertThat(tool.description()).isEqualTo("Write content to a file");
    }

    @Test
    void parameterSchema_returnsProvidedSchema() {
        assertThat(tool.parameterSchema()).isSameAs(schema);
    }

    @Test
    void implementsCustomSchemaAgentTool() {
        assertThat(tool).isInstanceOf(CustomSchemaAgentTool.class);
    }

    // ========================
    // Construction validation
    // ========================

    @Test
    void constructor_nullClient_throws() {
        assertThatThrownBy(() -> new McpAgentTool(null, "tool", "desc", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client");
    }

    @Test
    void constructor_nullName_throws() {
        assertThatThrownBy(() -> new McpAgentTool(mockClient, null, "desc", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName");
    }

    @Test
    void constructor_blankName_throws() {
        assertThatThrownBy(() -> new McpAgentTool(mockClient, "  ", "desc", schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName");
    }

    @Test
    void constructor_nullDescription_defaultsToEmpty() {
        var t = new McpAgentTool(mockClient, "tool", null, schema);
        assertThat(t.description()).isEmpty();
    }

    @Test
    void constructor_nullSchema_defaultsToEmptyObject() {
        var t = new McpAgentTool(mockClient, "tool", "desc", null);
        assertThat(t.parameterSchema()).isNotNull();
    }

    // ========================
    // Execution -- success
    // ========================

    @Test
    void execute_callsClientWithCorrectRequest() {
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder().resultText("ok").build());

        tool.execute("{\"path\": \"test.txt\", \"content\": \"hello\"}");

        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(mockClient).executeTool(captor.capture());

        ToolExecutionRequest request = captor.getValue();
        assertThat(request.name()).isEqualTo("write_file");
        assertThat(request.arguments()).isEqualTo("{\"path\": \"test.txt\", \"content\": \"hello\"}");
    }

    @Test
    void execute_returnsSuccessOnValidResponse() {
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder()
                        .resultText("File written successfully")
                        .build());

        ToolResult result = tool.execute("{\"path\": \"test.txt\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("File written successfully");
    }

    @Test
    void execute_handlesNullInput() {
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder().resultText("ok").build());

        ToolResult result = tool.execute(null);

        assertThat(result.isSuccess()).isTrue();
        // Null input should be replaced with empty JSON object
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(mockClient).executeTool(captor.capture());
        assertThat(captor.getValue().arguments()).isEqualTo("{}");
    }

    @Test
    void execute_handlesEmptyResultText() {
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder().resultText("").build());

        ToolResult result = tool.execute("{}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    // ========================
    // Execution -- error from MCP
    // ========================

    @Test
    void execute_returnsFailureOnMcpError() {
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder()
                        .isError(true)
                        .resultText("File not found")
                        .build());

        ToolResult result = tool.execute("{\"path\": \"missing.txt\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("File not found");
    }

    @Test
    void execute_returnsFailureOnMcpErrorWithEmptyText() {
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder()
                        .isError(true)
                        .resultText("")
                        .build());

        ToolResult result = tool.execute("{}");

        assertThat(result.isSuccess()).isFalse();
    }

    // ========================
    // Execution -- exception
    // ========================

    @Test
    void execute_returnsFailureOnClientException() {
        when(mockClient.executeTool(any())).thenThrow(new RuntimeException("Transport error"));

        ToolResult result = tool.execute("{}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("Transport error");
    }

    @Test
    void execute_returnsFailureOnClientExceptionWithNullMessage() {
        when(mockClient.executeTool(any())).thenThrow(new RuntimeException((String) null));

        ToolResult result = tool.execute("{}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("MCP tool execution failed");
    }

    // ========================
    // Execution -- empty input
    // ========================

    @Test
    void execute_emptyJsonObject_callsClientSuccessfully() {
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder().resultText("result").build());

        ToolResult result = tool.execute("{}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("result");
    }

    // ========================
    // Integration with LangChain4jToolAdapter
    // ========================

    @Test
    void toSpecification_usesCustomSchema() {
        ToolSpecification spec = LangChain4jToolAdapter.toSpecification(tool);

        assertThat(spec.name()).isEqualTo("write_file");
        assertThat(spec.description()).isEqualTo("Write content to a file");
        assertThat(spec.parameters().properties()).containsKey("path");
        assertThat(spec.parameters().properties()).containsKey("content");
        assertThat(spec.parameters().properties()).doesNotContainKey("input");
        assertThat(spec.parameters().required()).contains("path");
    }

    @Test
    void executeForResult_passesFullJsonArguments() {
        String fullJson = "{\"path\": \"a.txt\", \"content\": \"data\"}";
        when(mockClient.executeTool(any()))
                .thenReturn(ToolExecutionResult.builder().resultText("done").build());

        ToolResult result = LangChain4jToolAdapter.executeForResult(tool, fullJson);

        assertThat(result.isSuccess()).isTrue();
        // Verify the full JSON was passed through, not just an extracted "input" key
        ArgumentCaptor<ToolExecutionRequest> captor = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(mockClient).executeTool(captor.capture());
        assertThat(captor.getValue().arguments()).isEqualTo(fullJson);
    }
}
