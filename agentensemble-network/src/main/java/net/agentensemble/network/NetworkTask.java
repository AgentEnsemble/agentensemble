package net.agentensemble.network;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.web.protocol.DeliveryMethod;
import net.agentensemble.web.protocol.DeliverySpec;
import net.agentensemble.web.protocol.Priority;
import net.agentensemble.web.protocol.ServerMessage;
import net.agentensemble.web.protocol.TaskRequestMessage;
import net.agentensemble.web.protocol.TaskResponseMessage;

/**
 * An {@link net.agentensemble.tool.AgentTool AgentTool} that delegates a full task execution
 * to a remote ensemble over WebSocket.
 *
 * <p>This is the core cross-ensemble delegation mechanism: "hire a department." The target
 * ensemble runs its complete task pipeline (agent synthesis, ReAct loop, tool calls, review
 * gates, sub-delegations) and returns only the final output.
 *
 * <p>From the calling agent's perspective, a {@code NetworkTask} is indistinguishable from
 * a local tool -- the existing ReAct loop, tool executor, metrics, and tracing all work
 * unchanged.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Production: connect to a real remote ensemble
 * NetworkClientRegistry registry = new NetworkClientRegistry(config);
 * Ensemble roomService = Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .task(Task.builder()
 *         .description("Handle room service request")
 *         .tools(NetworkTask.from("kitchen", "prepare-meal", registry))
 *         .build())
 *     .build();
 * </pre>
 *
 * <h2>Test doubles</h2>
 * <pre>
 * // Stub: returns a canned response
 * StubNetworkTask stub = NetworkTask.stub("kitchen", "prepare-meal", "Meal ready in 25 min");
 *
 * // Recording: captures requests for assertion
 * RecordingNetworkTask recorder = NetworkTask.recording("kitchen", "prepare-meal");
 * </pre>
 *
 * @see NetworkTool
 * @see StubNetworkTask
 * @see RecordingNetworkTask
 * @see RequestMode
 * @see DeadlineAction
 */
public class NetworkTask extends AbstractAgentTool {

    /** Default connect timeout for WebSocket connections. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Default execution timeout for task delegation. */
    public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(30);

    private final String ensembleName;
    private final String taskName;
    private final Duration executionTimeout;
    private final NetworkClientRegistry clientRegistry;
    private final RequestMode mode;
    private final Consumer<ToolResult> onComplete;
    private final Duration deadline;
    private final DeadlineAction deadlineAction;

    NetworkTask(String ensembleName, String taskName, Duration executionTimeout, NetworkClientRegistry clientRegistry) {
        this(
                ensembleName,
                taskName,
                executionTimeout,
                clientRegistry,
                RequestMode.AWAIT,
                null,
                null,
                DeadlineAction.RETURN_TIMEOUT_ERROR);
    }

    NetworkTask(
            String ensembleName,
            String taskName,
            Duration executionTimeout,
            NetworkClientRegistry clientRegistry,
            RequestMode mode,
            Consumer<ToolResult> onComplete,
            Duration deadline,
            DeadlineAction deadlineAction) {
        this.ensembleName = Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        this.taskName = Objects.requireNonNull(taskName, "taskName must not be null");
        this.executionTimeout = Objects.requireNonNull(executionTimeout, "executionTimeout must not be null");
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.onComplete = onComplete;
        this.deadline = deadline;
        this.deadlineAction = Objects.requireNonNull(deadlineAction, "deadlineAction must not be null");
    }

    /**
     * Create a {@code NetworkTask} that delegates to the named task on the specified ensemble.
     *
     * <p>Uses the default execution timeout of 30 minutes and the provided
     * {@link NetworkClientRegistry} for WebSocket connections.
     *
     * @param ensemble       the target ensemble name; must not be null
     * @param taskName       the shared task name on the target ensemble; must not be null
     * @param clientRegistry the client registry for WebSocket connections; must not be null
     * @return a new NetworkTask
     */
    public static NetworkTask from(String ensemble, String taskName, NetworkClientRegistry clientRegistry) {
        return new NetworkTask(ensemble, taskName, DEFAULT_EXECUTION_TIMEOUT, clientRegistry);
    }

    /**
     * Create a {@code NetworkTask} with a custom execution timeout.
     *
     * @param ensemble         the target ensemble name; must not be null
     * @param taskName         the shared task name on the target ensemble; must not be null
     * @param executionTimeout maximum time to wait for the task to complete; must not be null
     * @param clientRegistry   the client registry for WebSocket connections; must not be null
     * @return a new NetworkTask
     */
    public static NetworkTask from(
            String ensemble, String taskName, Duration executionTimeout, NetworkClientRegistry clientRegistry) {
        return new NetworkTask(ensemble, taskName, executionTimeout, clientRegistry);
    }

    /**
     * Returns a new {@link NetworkTaskBuilder} for fluent construction of a {@code NetworkTask}.
     *
     * @return a new builder
     */
    public static NetworkTaskBuilder builder() {
        return new NetworkTaskBuilder();
    }

    /**
     * Create a stub that returns a canned response without making any network calls.
     *
     * @param ensemble the ensemble name (for tool naming)
     * @param taskName the task name (for tool naming)
     * @param response the canned response to return on every invocation
     * @return a new {@link StubNetworkTask}
     */
    public static StubNetworkTask stub(String ensemble, String taskName, String response) {
        return new StubNetworkTask(ensemble, taskName, response);
    }

    /**
     * Create a recording that captures all requests for later assertion.
     *
     * <p>Each invocation returns a default response of {@code "recorded"}.
     *
     * @param ensemble the ensemble name (for tool naming)
     * @param taskName the task name (for tool naming)
     * @return a new {@link RecordingNetworkTask}
     */
    public static RecordingNetworkTask recording(String ensemble, String taskName) {
        return new RecordingNetworkTask(ensemble, taskName, "recorded");
    }

    /**
     * Create a recording that captures all requests and returns a custom default response.
     *
     * @param ensemble        the ensemble name (for tool naming)
     * @param taskName        the task name (for tool naming)
     * @param defaultResponse the response returned on each invocation
     * @return a new {@link RecordingNetworkTask}
     */
    public static RecordingNetworkTask recording(String ensemble, String taskName, String defaultResponse) {
        return new RecordingNetworkTask(ensemble, taskName, defaultResponse);
    }

    @Override
    public String name() {
        return ensembleName + "." + taskName;
    }

    @Override
    public String description() {
        return "Delegates task '" + taskName + "' to ensemble '" + ensembleName + "'";
    }

    @Override
    protected ToolResult doExecute(String input) {
        String requestId = UUID.randomUUID().toString();
        TaskRequestMessage request = new TaskRequestMessage(
                requestId,
                "local",
                taskName,
                input,
                Priority.NORMAL,
                null,
                new DeliverySpec(DeliveryMethod.WEBSOCKET, null),
                null,
                null,
                null,
                null);

        try {
            NetworkClient client = clientRegistry.getOrCreate(ensembleName);
            CompletableFuture<ServerMessage> future = client.send(request, requestId);

            return switch (mode) {
                case AWAIT -> executeAwait(future);
                case ASYNC -> executeAsync(future);
                case AWAIT_WITH_DEADLINE -> executeWithDeadline(future);
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Task '" + taskName + "' on ensemble '" + ensembleName + "' was interrupted");
        } catch (Exception e) {
            return ToolResult.failure(
                    "Network error calling '" + taskName + "' on '" + ensembleName + "': " + e.getMessage());
        }
    }

    private ToolResult executeAwait(CompletableFuture<ServerMessage> future) throws Exception {
        try {
            ServerMessage response = future.get(executionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return convertResponse(response);
        } catch (TimeoutException e) {
            return ToolResult.failure(
                    "Task '" + taskName + "' on ensemble '" + ensembleName + "' timed out after " + executionTimeout);
        }
    }

    private ToolResult executeAsync(CompletableFuture<ServerMessage> future) {
        var unused = future.whenComplete((response, error) -> {
            if (onComplete != null) {
                if (error != null) {
                    onComplete.accept(ToolResult.failure("Async task failed: " + error.getMessage()));
                } else {
                    onComplete.accept(convertResponse(response));
                }
            }
        });
        return ToolResult.success("Request submitted asynchronously");
    }

    private ToolResult executeWithDeadline(CompletableFuture<ServerMessage> future) throws Exception {
        try {
            ServerMessage response = future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            return convertResponse(response);
        } catch (TimeoutException e) {
            return switch (deadlineAction) {
                case RETURN_TIMEOUT_ERROR -> ToolResult.failure("Deadline exceeded after " + deadline);
                case RETURN_PARTIAL -> ToolResult.success("Deadline exceeded, task continuing in background");
                case CONTINUE_IN_BACKGROUND -> {
                    var unused = future.whenComplete((response, err) -> {
                        if (onComplete != null) {
                            if (err != null) {
                                onComplete.accept(ToolResult.failure("Background task failed: " + err.getMessage()));
                            } else {
                                onComplete.accept(convertResponse(response));
                            }
                        }
                    });
                    yield ToolResult.success("Deadline exceeded, continuing in background");
                }
            };
        }
    }

    private ToolResult convertResponse(ServerMessage response) {
        if (response instanceof TaskResponseMessage taskResponse) {
            if ("COMPLETED".equals(taskResponse.status())) {
                return ToolResult.success(taskResponse.result());
            } else {
                String error = taskResponse.error() != null ? taskResponse.error() : "Task failed";
                return ToolResult.failure(error);
            }
        }
        return ToolResult.failure(
                "Unexpected response type: " + response.getClass().getSimpleName());
    }

    /** Returns the target ensemble name. */
    public String ensembleName() {
        return ensembleName;
    }

    /** Returns the shared task name on the target ensemble. */
    public String taskName() {
        return taskName;
    }

    /** Returns the execution timeout. */
    public Duration executionTimeout() {
        return executionTimeout;
    }

    /** Returns the request mode. */
    public RequestMode mode() {
        return mode;
    }

    /** Returns the deadline duration, or {@code null} if not set. */
    public Duration deadline() {
        return deadline;
    }

    /** Returns the deadline action. */
    public DeadlineAction deadlineAction() {
        return deadlineAction;
    }
}
