package net.agentensemble.network;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Test double that records all requests made to a shared task for later assertion.
 *
 * <p>Use in place of a real {@link NetworkTask} in unit tests to capture and verify
 * what was sent:
 *
 * <pre>
 * RecordingNetworkTask recorder = NetworkTask.recording("kitchen", "prepare-meal");
 *
 * // ... run ensemble with recorder as a tool ...
 *
 * assertThat(recorder.callCount()).isEqualTo(1);
 * assertThat(recorder.lastRequest()).contains("wagyu");
 * </pre>
 *
 * <p>Thread-safe: the request list uses {@link CopyOnWriteArrayList}.
 *
 * @see NetworkTask#recording(String, String)
 */
public final class RecordingNetworkTask extends AbstractAgentTool {

    private final String ensembleName;
    private final String taskName;
    private final String defaultResponse;
    private final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();

    RecordingNetworkTask(String ensembleName, String taskName, String defaultResponse) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.taskName = Objects.requireNonNull(taskName, "taskName must not be null");
        this.defaultResponse = Objects.requireNonNull(defaultResponse, "defaultResponse must not be null");
    }

    @Override
    public String name() {
        return ensembleName + "." + taskName;
    }

    @Override
    public String description() {
        return "Recording: delegates task '" + taskName + "' to ensemble '" + ensembleName + "'";
    }

    @Override
    protected ToolResult doExecute(String input) {
        requests.add(input != null ? input : "");
        return ToolResult.success(defaultResponse);
    }

    /**
     * Returns the last request input that was sent to this task.
     *
     * @return the last request string
     * @throws java.util.NoSuchElementException if no requests have been recorded
     */
    public String lastRequest() {
        if (requests.isEmpty()) {
            throw new java.util.NoSuchElementException("No requests recorded");
        }
        return requests.get(requests.size() - 1);
    }

    /**
     * Returns an immutable copy of all request inputs recorded so far, in order.
     *
     * @return immutable list of request strings; never null
     */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /**
     * Returns the number of times this task has been invoked.
     *
     * @return the call count; never negative
     */
    public int callCount() {
        return requests.size();
    }

    /** Returns the ensemble name this recording is configured for. */
    public String ensembleName() {
        return ensembleName;
    }

    /** Returns the task name this recording is configured for. */
    public String taskName() {
        return taskName;
    }
}
