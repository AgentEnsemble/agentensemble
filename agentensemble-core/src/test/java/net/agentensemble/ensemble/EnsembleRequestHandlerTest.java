package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;

import net.agentensemble.Ensemble;
import net.agentensemble.dashboard.RequestHandler;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EnsembleRequestHandler}.
 */
class EnsembleRequestHandlerTest {

    private EnsembleRequestHandler handler;

    @BeforeEach
    void setUp() {
        AgentTool echoTool = new AgentTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echoes input";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("echo: " + input);
            }
        };

        AgentTool failingTool = new AgentTool() {
            @Override
            public String name() {
                return "boom";
            }

            @Override
            public String description() {
                return "Always throws";
            }

            @Override
            public ToolResult execute(String input) {
                throw new RuntimeException("tool went boom");
            }
        };

        Ensemble ensemble = Ensemble.builder()
                .shareTool("echo", echoTool)
                .shareTool("boom", failingTool)
                .build();

        handler = new EnsembleRequestHandler(ensemble);
    }

    // ========================
    // Tool requests
    // ========================

    @Test
    void handleToolRequest_knownTool_returnsCompleted() {
        RequestHandler.ToolResult result = handler.handleToolRequest("echo", "hello");

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.result()).isEqualTo("echo: hello");
        assertThat(result.error()).isNull();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void handleToolRequest_unknownTool_returnsFailed() {
        RequestHandler.ToolResult result = handler.handleToolRequest("nonexistent", "input");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.result()).isNull();
        assertThat(result.error()).contains("Unknown shared tool");
        assertThat(result.error()).contains("nonexistent");
    }

    @Test
    void handleToolRequest_toolThrowsException_returnsFailed() {
        RequestHandler.ToolResult result = handler.handleToolRequest("boom", "input");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.result()).isNull();
        assertThat(result.error()).contains("tool went boom");
    }

    // ========================
    // Task requests
    // ========================

    @Test
    void handleTaskRequest_unknownTask_returnsFailed() {
        RequestHandler.TaskResult result = handler.handleTaskRequest("nonexistent", "context");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.result()).isNull();
        assertThat(result.error()).contains("Unknown shared task");
        assertThat(result.error()).contains("nonexistent");
    }
}
