package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.List;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for ToolResolver -- the extracted tool resolution and dispatch logic.
 */
class ToolResolverTest {

    // ========================
    // Helpers
    // ========================

    /** Simple @Tool-annotated class used as a test fixture. */
    static class SearchTool {
        @Tool("Perform a web search")
        public String search(@P("query to search for") String query) {
            return "search result for: " + query;
        }
    }

    private static AgentTool mockAgentTool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn("A tool called " + name);
        return tool;
    }

    // ========================
    // hasTools()
    // ========================

    @Test
    void hasTools_emptyList_returnsFalse() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of());
        assertThat(resolved.hasTools()).isFalse();
    }

    @Test
    void hasTools_withAgentTool_returnsTrue() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(mockAgentTool("calculator")));
        assertThat(resolved.hasTools()).isTrue();
    }

    @Test
    void hasTools_withAnnotatedObject_returnsTrue() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(new SearchTool()));
        assertThat(resolved.hasTools()).isTrue();
    }

    // ========================
    // allSpecifications()
    // ========================

    @Test
    void resolve_agentTool_createsSpecification() {
        AgentTool tool = mockAgentTool("calculator");
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(tool));
        assertThat(resolved.allSpecifications()).hasSize(1);
        assertThat(resolved.allSpecifications().get(0).name()).isEqualTo("calculator");
    }

    @Test
    void resolve_annotatedObject_createsSpecification() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(new SearchTool()));
        assertThat(resolved.allSpecifications()).hasSize(1);
        assertThat(resolved.allSpecifications().get(0).name()).isEqualTo("search");
    }

    @Test
    void resolve_mixedTools_createsAllSpecifications() {
        AgentTool agentTool = mockAgentTool("calculator");
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(agentTool, new SearchTool()));
        assertThat(resolved.allSpecifications()).hasSize(2);
    }

    // ========================
    // execute()
    // ========================

    @Test
    void execute_agentTool_dispatchesAndReturnsResult() {
        AgentTool tool = mockAgentTool("calculator");
        when(tool.execute("{\"expression\":\"1+1\"}")).thenReturn(ToolResult.success("2"));

        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(tool));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req1")
                .name("calculator")
                .arguments("{\"expression\":\"1+1\"}")
                .build();

        String result = resolved.execute(request);
        assertThat(result).isEqualTo("2");
    }

    @Test
    void execute_annotatedTool_dispatchesAndReturnsResult() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(new SearchTool()));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req2")
                .name("search")
                .arguments("{\"query\":\"Java 21\"}")
                .build();

        String result = resolved.execute(request);
        assertThat(result).isEqualTo("search result for: Java 21");
    }

    @Test
    void execute_unknownToolName_returnsErrorMessage() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(mockAgentTool("calculator")));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req3")
                .name("nonexistent_tool")
                .arguments("{}")
                .build();

        String result = resolved.execute(request);
        assertThat(result).startsWith("Error:").contains("nonexistent_tool");
    }

    @Test
    void execute_agentToolTakesPrecedenceOverAnnotated_whenSameName() {
        // AgentTool and @Tool-annotated object both named the same: AgentTool wins
        AgentTool agentTool = mockAgentTool("search");
        when(agentTool.execute(any())).thenReturn(ToolResult.success("from AgentTool"));

        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(agentTool, new SearchTool()));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req4")
                .name("search")
                .arguments("{\"query\":\"test\"}")
                .build();

        String result = resolved.execute(request);
        assertThat(result).isEqualTo("from AgentTool");
    }
}
