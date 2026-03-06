package net.agentensemble.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.util.List;
import java.util.concurrent.Executors;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.NoOpToolMetrics;
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
        when(tool.execute(any())).thenReturn(ToolResult.success("2"));

        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(tool));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req1")
                .name("calculator")
                .arguments("{\"input\":\"1+1\"}")
                .build();

        ToolResult result = resolved.execute(request, "test-agent");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("2");
        assertThat(result.getStructuredOutput()).isNull();
    }

    @Test
    void execute_annotatedTool_dispatchesAndReturnsResult() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(new SearchTool()));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req2")
                .name("search")
                .arguments("{\"query\":\"Java 21\"}")
                .build();

        ToolResult result = resolved.execute(request, "test-agent");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("search result for: Java 21");
    }

    @Test
    void execute_unknownToolName_returnsFailureResult() {
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(mockAgentTool("calculator")));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req3")
                .name("nonexistent_tool")
                .arguments("{}")
                .build();

        ToolResult result = resolved.execute(request, "test-agent");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("nonexistent_tool");
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
                .arguments("{\"input\":\"test\"}")
                .build();

        ToolResult result = resolved.execute(request, "test-agent");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("from AgentTool");
    }

    @Test
    void execute_agentTool_withStructuredOutput_preservesStructuredResult() {
        AgentTool tool = mockAgentTool("enricher");
        record SearchResults(String query, int count) {}
        SearchResults structured = new SearchResults("Java", 42);
        when(tool.execute(any())).thenReturn(ToolResult.success("Found 42 results", structured));

        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(List.of(tool));
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req5")
                .name("enricher")
                .arguments("{\"input\":\"Java\"}")
                .build();

        ToolResult result = resolved.execute(request, "test-agent");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Found 42 results");
        assertThat(result.getStructuredOutput(SearchResults.class)).isEqualTo(structured);
    }

    // ========================
    // ReviewHandler threading into ToolContext
    // ========================

    /** Minimal AbstractAgentTool subclass that exposes rawReviewHandler() for testing. */
    static class InspectableTool extends AbstractAgentTool {
        @Override
        public String name() {
            return "inspectable";
        }

        @Override
        public String description() {
            return "For testing context injection";
        }

        @Override
        protected ToolResult doExecute(String input) {
            return ToolResult.success(input);
        }

        Object capturedReviewHandler() {
            return rawReviewHandler();
        }
    }

    @Test
    void resolve_withReviewHandler_injectsHandlerIntoAbstractAgentToolContext() {
        ReviewHandler handler = ReviewHandler.autoApprove();
        var tool = new InspectableTool();

        ToolResolver.resolve(
                List.of(tool), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);

        assertThat(tool.capturedReviewHandler()).isSameAs(handler);
    }

    @Test
    void resolve_withNullReviewHandler_injectsNullHandlerIntoAbstractAgentToolContext() {
        var tool = new InspectableTool();

        ToolResolver.resolve(
                List.of(tool), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), null);

        assertThat(tool.capturedReviewHandler()).isNull();
    }

    @Test
    void resolve_threeArgOverload_injectsNullHandler() {
        var tool = new InspectableTool();

        ToolResolver.resolve(List.of(tool), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor());

        assertThat(tool.capturedReviewHandler()).isNull();
    }

    @Test
    void resolve_reviewHandler_notInjectedIntoAnnotatedObjects() {
        // @Tool-annotated objects don't go through AbstractAgentTool -- no injection path
        ReviewHandler handler = ReviewHandler.autoApprove();
        var annotatedTool = new SearchTool();

        // Should not throw -- just resolves normally without injection
        ToolResolver.ResolvedTools resolved = ToolResolver.resolve(
                List.of(annotatedTool), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);

        assertThat(resolved.hasTools()).isTrue();
    }

    @Test
    void resolve_withReviewHandler_toolContextContainsHandler() {
        // Verify the ToolContext created during resolution carries the handler
        ReviewHandler handler = ReviewHandler.autoApprove();
        var tool = new InspectableTool();

        ToolResolver.resolve(
                List.of(tool), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);

        // The ToolContext is accessible via rawReviewHandler() on the tool
        Object stored = tool.capturedReviewHandler();
        assertThat(stored).isInstanceOf(ReviewHandler.class);
        assertThat((ReviewHandler) stored).isSameAs(handler);
    }
}
