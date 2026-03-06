package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
}
