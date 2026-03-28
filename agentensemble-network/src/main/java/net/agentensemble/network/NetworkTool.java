package net.agentensemble.network;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.web.protocol.ServerMessage;
import net.agentensemble.web.protocol.ToolRequestMessage;
import net.agentensemble.web.protocol.ToolResponseMessage;

/**
 * An {@link net.agentensemble.tool.AgentTool AgentTool} that executes a shared tool on a
 * remote ensemble over WebSocket.
 *
 * <p>Lighter weight than {@link NetworkTask}: a single tool call, not a full task pipeline.
 * The calling agent maintains control of its reasoning loop while borrowing a remote tool.
 *
 * <p>From the calling agent's perspective, a {@code NetworkTool} is indistinguishable from
 * a local tool -- the existing ReAct loop, tool executor, metrics, and tracing all work
 * unchanged.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Production: connect to a real remote ensemble
 * Ensemble roomService = Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .task(Task.builder()
 *         .description("Handle room service request")
 *         .tools(NetworkTool.from("kitchen", "check-inventory", registry))
 *         .build())
 *     .build();
 * </pre>
 *
 * <h2>Test doubles</h2>
 * <pre>
 * // Stub: returns a canned result
 * StubNetworkTool stub = NetworkTool.stub("kitchen", "check-inventory", "3 portions available");
 *
 * // Recording: captures calls for assertion
 * RecordingNetworkTool recorder = NetworkTool.recording("kitchen", "check-inventory");
 * </pre>
 *
 * @see NetworkTask
 * @see StubNetworkTool
 * @see RecordingNetworkTool
 */
public class NetworkTool extends AbstractAgentTool {

    /** Default execution timeout for tool calls. */
    public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(30);

    private final String ensembleName;
    private final String toolName;
    private final Duration executionTimeout;
    private final NetworkClientRegistry clientRegistry;

    NetworkTool(String ensembleName, String toolName, Duration executionTimeout, NetworkClientRegistry clientRegistry) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.toolName = Objects.requireNonNull(toolName, "toolName must not be null");
        this.executionTimeout = Objects.requireNonNull(executionTimeout, "executionTimeout must not be null");
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
    }

    /**
     * Create a {@code NetworkTool} that invokes the named tool on the specified ensemble.
     *
     * <p>Uses the default execution timeout of 30 seconds.
     *
     * @param ensemble       the target ensemble name; must not be null
     * @param toolName       the shared tool name on the target ensemble; must not be null
     * @param clientRegistry the client registry for WebSocket connections; must not be null
     * @return a new NetworkTool
     */
    public static NetworkTool from(String ensemble, String toolName, NetworkClientRegistry clientRegistry) {
        return new NetworkTool(ensemble, toolName, DEFAULT_EXECUTION_TIMEOUT, clientRegistry);
    }

    /**
     * Create a {@code NetworkTool} with a custom execution timeout.
     *
     * @param ensemble         the target ensemble name; must not be null
     * @param toolName         the shared tool name on the target ensemble; must not be null
     * @param executionTimeout maximum time to wait for the tool to complete; must not be null
     * @param clientRegistry   the client registry for WebSocket connections; must not be null
     * @return a new NetworkTool
     */
    public static NetworkTool from(
            String ensemble, String toolName, Duration executionTimeout, NetworkClientRegistry clientRegistry) {
        return new NetworkTool(ensemble, toolName, executionTimeout, clientRegistry);
    }

    /**
     * Create a stub that returns a canned result without making any network calls.
     *
     * @param ensemble the ensemble name (for tool naming)
     * @param toolName the tool name (for tool naming)
     * @param result   the canned result to return on every invocation
     * @return a new {@link StubNetworkTool}
     */
    public static StubNetworkTool stub(String ensemble, String toolName, String result) {
        return new StubNetworkTool(ensemble, toolName, result);
    }

    /**
     * Create a recording that captures all calls for later assertion.
     *
     * <p>Each invocation returns a default result of {@code "recorded"}.
     *
     * @param ensemble the ensemble name (for tool naming)
     * @param toolName the tool name (for tool naming)
     * @return a new {@link RecordingNetworkTool}
     */
    public static RecordingNetworkTool recording(String ensemble, String toolName) {
        return new RecordingNetworkTool(ensemble, toolName, "recorded");
    }

    /**
     * Create a recording that captures all calls and returns a custom default result.
     *
     * @param ensemble      the ensemble name (for tool naming)
     * @param toolName      the tool name (for tool naming)
     * @param defaultResult the result returned on each invocation
     * @return a new {@link RecordingNetworkTool}
     */
    public static RecordingNetworkTool recording(String ensemble, String toolName, String defaultResult) {
        return new RecordingNetworkTool(ensemble, toolName, defaultResult);
    }

    @Override
    public String name() {
        return ensembleName + "." + toolName;
    }

    @Override
    public String description() {
        return "Calls tool '" + toolName + "' on ensemble '" + ensembleName + "'";
    }

    @Override
    protected ToolResult doExecute(String input) {
        String requestId = UUID.randomUUID().toString();
        ToolRequestMessage request = new ToolRequestMessage(requestId, "local", toolName, input, null);

        try {
            NetworkClient client = clientRegistry.getOrCreate(ensembleName);
            CompletableFuture<ServerMessage> future = client.send(request, requestId);
            ServerMessage response = future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);

            if (response instanceof ToolResponseMessage toolResponse) {
                if ("COMPLETED".equals(toolResponse.status())) {
                    return ToolResult.success(toolResponse.result());
                } else {
                    String error = toolResponse.error() != null ? toolResponse.error() : "Tool call failed";
                    return ToolResult.failure(error);
                }
            }
            return ToolResult.failure(
                    "Unexpected response type: " + response.getClass().getSimpleName());
        } catch (TimeoutException e) {
            return ToolResult.failure(
                    "Tool '" + toolName + "' on ensemble '" + ensembleName + "' timed out after " + executionTimeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Tool '" + toolName + "' on ensemble '" + ensembleName + "' was interrupted");
        } catch (Exception e) {
            return ToolResult.failure(
                    "Network error calling '" + toolName + "' on '" + ensembleName + "': " + e.getMessage());
        }
    }

    /** Returns the target ensemble name. */
    public String ensembleName() {
        return ensembleName;
    }

    /** Returns the shared tool name on the target ensemble. */
    public String toolName() {
        return toolName;
    }

    /** Returns the execution timeout. */
    public Duration executionTimeout() {
        return executionTimeout;
    }
}
