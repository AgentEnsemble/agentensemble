package net.agentensemble.network;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;

/**
 * Test double that records all calls made to a shared tool for later assertion.
 *
 * <p>Use in place of a real {@link NetworkTool} in unit tests to capture and verify
 * what was sent:
 *
 * <pre>
 * RecordingNetworkTool recorder = NetworkTool.recording("kitchen", "check-inventory");
 *
 * // ... run ensemble with recorder as a tool ...
 *
 * assertThat(recorder.callCount()).isEqualTo(2);
 * assertThat(recorder.lastRequest()).contains("wagyu");
 * </pre>
 *
 * <p>Thread-safe: the request list uses {@link CopyOnWriteArrayList}.
 *
 * @see NetworkTool#recording(String, String)
 */
public final class RecordingNetworkTool extends AbstractAgentTool {

    private final String ensembleName;
    private final String toolName;
    private final String defaultResult;
    private final CopyOnWriteArrayList<String> requests = new CopyOnWriteArrayList<>();

    RecordingNetworkTool(String ensembleName, String toolName, String defaultResult) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.defaultResult = Objects.requireNonNull(defaultResult, "defaultResult must not be null");
    }

    @Override
    public String name() {
        return ensembleName + "." + toolName;
    }

    @Override
    public String description() {
        return "Recording: calls tool '" + toolName + "' on ensemble '" + ensembleName + "'";
    }

    @Override
    protected ToolResult doExecute(String input) {
        requests.add(input != null ? input : "");
        return ToolResult.success(defaultResult);
    }

    /**
     * Returns the last input that was sent to this tool.
     *
     * @return the last input string
     * @throws java.util.NoSuchElementException if no calls have been recorded
     */
    public String lastRequest() {
        if (requests.isEmpty()) {
            throw new java.util.NoSuchElementException("No calls recorded");
        }
        return requests.get(requests.size() - 1);
    }

    /**
     * Returns an immutable copy of all inputs recorded so far, in order.
     *
     * @return immutable list of input strings; never null
     */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /**
     * Returns the number of times this tool has been invoked.
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

    /** Returns the tool name this recording is configured for. */
    public String toolName() {
        return toolName;
    }
}
