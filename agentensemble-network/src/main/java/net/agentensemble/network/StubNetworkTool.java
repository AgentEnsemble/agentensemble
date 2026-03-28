package net.agentensemble.network;

import java.util.Objects;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Test double that returns a canned result for a shared tool.
 *
 * <p>Use in place of a real {@link NetworkTool} in unit tests to avoid real WebSocket
 * connections:
 *
 * <pre>
 * StubNetworkTool stub = NetworkTool.stub("kitchen", "check-inventory", "3 portions available");
 *
 * Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .task(Task.builder()
 *         .description("Handle room service request")
 *         .tools(stub)
 *         .build())
 *     .build()
 *     .run();
 * </pre>
 *
 * @see NetworkTool#stub(String, String, String)
 */
public final class StubNetworkTool extends AbstractAgentTool {

    private final String ensembleName;
    private final String toolName;
    private final String cannedResult;

    StubNetworkTool(String ensembleName, String toolName, String cannedResult) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.cannedResult = Objects.requireNonNull(cannedResult, "cannedResult must not be null");
    }

    @Override
    public String name() {
        return ensembleName + "." + toolName;
    }

    @Override
    public String description() {
        return "Stub: calls tool '" + toolName + "' on ensemble '" + ensembleName + "'";
    }

    @Override
    protected ToolResult doExecute(String input) {
        return ToolResult.success(cannedResult);
    }

    /** Returns the ensemble name this stub is configured for. */
    public String ensembleName() {
        return ensembleName;
    }

    /** Returns the tool name this stub is configured for. */
    public String toolName() {
        return toolName;
    }

    /** Returns the canned result this stub will always return. */
    public String cannedResult() {
        return cannedResult;
    }
}
