package net.agentensemble.network;

import java.util.Objects;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Test double that returns a canned response for a shared task.
 *
 * <p>Use in place of a real {@link NetworkTask} in unit tests to avoid real WebSocket
 * connections:
 *
 * <pre>
 * StubNetworkTask stub = NetworkTask.stub("kitchen", "prepare-meal", "Meal ready in 25 min");
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
 * @see NetworkTask#stub(String, String, String)
 */
public final class StubNetworkTask extends AbstractAgentTool {

    private final String ensembleName;
    private final String taskName;
    private final String cannedResponse;

    StubNetworkTask(String ensembleName, String taskName, String cannedResponse) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.taskName = Objects.requireNonNull(taskName, "taskName must not be null");
        this.cannedResponse = Objects.requireNonNull(cannedResponse, "cannedResponse must not be null");
    }

    @Override
    public String name() {
        return ensembleName + "." + taskName;
    }

    @Override
    public String description() {
        return "Stub: delegates task '" + taskName + "' to ensemble '" + ensembleName + "'";
    }

    @Override
    protected ToolResult doExecute(String input) {
        return ToolResult.success(cannedResponse);
    }

    /** Returns the ensemble name this stub is configured for. */
    public String ensembleName() {
        return ensembleName;
    }

    /** Returns the task name this stub is configured for. */
    public String taskName() {
        return taskName;
    }

    /** Returns the canned response this stub will always return. */
    public String cannedResponse() {
        return cannedResponse;
    }
}
