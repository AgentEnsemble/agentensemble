package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.util.List;
import net.agentensemble.exception.ExitEarlyException;
import org.junit.jupiter.api.Test;

class LangChain4jToolAdapterTest {

    private AgentTool mockTool(String name, String description) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn(description);
        return tool;
    }

    // ========================
    // ToolSpecification creation
    // ========================

    @Test
    void testToSpecification_hasCorrectName() {
        var tool = mockTool("web_search", "Search the web");
        var spec = LangChain4jToolAdapter.toSpecification(tool);

        assertThat(spec.name()).isEqualTo("web_search");
    }

    @Test
    void testToSpecification_hasCorrectDescription() {
        var tool = mockTool("calculator", "Performs arithmetic");
        var spec = LangChain4jToolAdapter.toSpecification(tool);

        assertThat(spec.description()).isEqualTo("Performs arithmetic");
    }

    @Test
    void testToSpecification_hasSingleInputParameter() {
        var tool = mockTool("web_search", "Search the web");
        var spec = LangChain4jToolAdapter.toSpecification(tool);

        assertThat(spec.parameters()).isNotNull();
        assertThat(spec.parameters().properties()).containsKey("input");
        assertThat(spec.parameters().required()).contains("input");
    }

    // ========================
    // Tool execution
    // ========================

    @Test
    void testExecute_callsAgentToolWithInputValue() {
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("search");
        when(tool.description()).thenReturn("Search");
        when(tool.execute("AI trends")).thenReturn(ToolResult.success("Search results"));

        String result = LangChain4jToolAdapter.execute(tool, "{\"input\": \"AI trends\"}");

        verify(tool).execute("AI trends");
        assertThat(result).isEqualTo("Search results");
    }

    @Test
    void testExecute_withSuccessResult_returnsOutput() {
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("calc");
        when(tool.description()).thenReturn("Calculate");
        when(tool.execute(anyString())).thenReturn(ToolResult.success("42"));

        String result = LangChain4jToolAdapter.execute(tool, "{\"input\": \"6 * 7\"}");

        assertThat(result).isEqualTo("42");
    }

    @Test
    void testExecute_withFailureResult_returnsErrorString() {
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("search");
        when(tool.description()).thenReturn("Search");
        when(tool.execute(anyString())).thenReturn(ToolResult.failure("Network timeout"));

        String result = LangChain4jToolAdapter.execute(tool, "{\"input\": \"query\"}");

        assertThat(result).startsWith("Error:").contains("Network timeout");
    }

    @Test
    void testExecute_whenToolThrows_returnsErrorString() {
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("search");
        when(tool.description()).thenReturn("Search");
        when(tool.execute(anyString())).thenThrow(new RuntimeException("Service unavailable"));

        String result = LangChain4jToolAdapter.execute(tool, "{\"input\": \"query\"}");

        assertThat(result).startsWith("Error:").contains("Service unavailable");
    }

    @Test
    void testExecuteForResult_exitEarlyException_isRethrownNotConverted() {
        // ExitEarlyException must propagate through the adapter unchanged,
        // not be swallowed and converted to ToolResult.failure().
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("human_input");
        when(tool.description()).thenReturn("Human input");
        when(tool.execute(anyString())).thenThrow(new ExitEarlyException("User requested exit early"));

        assertThatThrownBy(() -> LangChain4jToolAdapter.executeForResult(tool, "{\"input\": \"question\"}"))
                .isInstanceOf(ExitEarlyException.class)
                .hasMessageContaining("exit early");
    }

    @Test
    void testExecute_whenToolReturnsNull_returnsEmpty() {
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("search");
        when(tool.description()).thenReturn("Search");
        when(tool.execute(anyString())).thenReturn(null);

        String result = LangChain4jToolAdapter.execute(tool, "{\"input\": \"query\"}");

        assertThat(result).isEmpty();
    }

    @Test
    void testExecute_withMalformedJson_passesRawString() {
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("search");
        when(tool.description()).thenReturn("Search");
        when(tool.execute("not valid json")).thenReturn(ToolResult.success("ok"));

        String result = LangChain4jToolAdapter.execute(tool, "not valid json");

        verify(tool).execute("not valid json");
        assertThat(result).isEqualTo("ok");
    }

    // ========================
    // TypedAgentTool spec generation
    // ========================

    @ToolInput(description = "Test input")
    record TestInput(
            @ToolParam(description = "A required field") String required,
            @ToolParam(description = "An optional field", required = false) String optional) {}

    private static final class TestTypedTool extends AbstractTypedAgentTool<TestInput> {
        @Override
        public String name() {
            return "typed_tool";
        }

        @Override
        public String description() {
            return "A typed test tool";
        }

        @Override
        public Class<TestInput> inputType() {
            return TestInput.class;
        }

        @Override
        public ToolResult execute(TestInput input) {
            return ToolResult.success("typed: " + input.required());
        }
    }

    @Test
    void toSpecification_typedTool_generatesMultiParamSchema() {
        var tool = new TestTypedTool();
        var spec = LangChain4jToolAdapter.toSpecification(tool);

        assertThat(spec.name()).isEqualTo("typed_tool");
        assertThat(spec.description()).isEqualTo("A typed test tool");
        // Multi-param schema -- no single "input" key
        assertThat(spec.parameters().properties()).containsKey("required");
        assertThat(spec.parameters().properties()).containsKey("optional");
        assertThat(spec.parameters().properties()).doesNotContainKey("input");
    }

    @Test
    void toSpecification_typedTool_requiredFieldInRequired() {
        var tool = new TestTypedTool();
        var spec = LangChain4jToolAdapter.toSpecification(tool);

        assertThat(spec.parameters().required()).contains("required");
        assertThat(spec.parameters().required()).doesNotContain("optional");
    }

    @Test
    void toSpecification_legacyTool_generatesSingleInputSchema() {
        var tool = mockTool("legacy", "A legacy tool");
        var spec = LangChain4jToolAdapter.toSpecification(tool);

        // Legacy single-"input" string schema
        assertThat(spec.parameters().properties()).containsKey("input");
        assertThat(spec.parameters().required()).contains("input");
    }

    // ========================
    // TypedAgentTool execution routing
    // ========================

    @Test
    void executeForResult_typedTool_passesFullJsonArgs() {
        var tool = new TestTypedTool();

        // Typed tool receives full JSON args -- all fields at top level
        var result = LangChain4jToolAdapter.executeForResult(tool, "{\"required\": \"hello\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("typed: hello");
    }

    @Test
    void executeForResult_typedTool_missingRequiredField_returnsFailure() {
        var tool = new TestTypedTool();

        // Missing required "required" field -- deserialization should fail
        var result = LangChain4jToolAdapter.executeForResult(tool, "{\"optional\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("required");
    }

    @Test
    void executeForResult_legacyTool_extractsInputKey() {
        var tool = mock(AgentTool.class);
        when(tool.name()).thenReturn("legacy");
        when(tool.description()).thenReturn("Legacy");
        when(tool.execute("the value")).thenReturn(ToolResult.success("done"));

        // Legacy tool: adapter extracts "input" key
        var result = LangChain4jToolAdapter.executeForResult(tool, "{\"input\": \"the value\"}");

        assertThat(result.isSuccess()).isTrue();
        verify(tool).execute("the value");
    }

    // ========================
    // CustomSchemaAgentTool spec generation
    // ========================

    @Test
    void toSpecification_customSchema_usesProvidedSchema() {
        JsonObjectSchema schema = JsonObjectSchema.builder()
                .addStringProperty("path", "The file path")
                .addStringProperty("content", "File content")
                .required(List.of("path", "content"))
                .build();

        var tool = mock(CustomSchemaAgentTool.class);
        when(tool.name()).thenReturn("write_file");
        when(tool.description()).thenReturn("Write a file");
        when(tool.parameterSchema()).thenReturn(schema);

        var spec = LangChain4jToolAdapter.toSpecification(tool);

        assertThat(spec.name()).isEqualTo("write_file");
        assertThat(spec.description()).isEqualTo("Write a file");
        assertThat(spec.parameters().properties()).containsKey("path");
        assertThat(spec.parameters().properties()).containsKey("content");
        assertThat(spec.parameters().properties()).doesNotContainKey("input");
        assertThat(spec.parameters().required()).containsExactlyInAnyOrder("path", "content");
    }

    @Test
    void executeForResult_customSchema_passesFullJson() {
        String fullJson = "{\"path\": \"test.txt\", \"content\": \"hello\"}";

        var tool = mock(CustomSchemaAgentTool.class);
        when(tool.name()).thenReturn("write_file");
        when(tool.description()).thenReturn("Write a file");
        when(tool.execute(fullJson)).thenReturn(ToolResult.success("written"));

        var result = LangChain4jToolAdapter.executeForResult(tool, fullJson);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("written");
        // Verify full JSON was passed, not just an extracted "input" key
        verify(tool).execute(fullJson);
    }
}
