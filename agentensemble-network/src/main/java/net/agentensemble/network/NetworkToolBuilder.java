package net.agentensemble.network;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.tool.ToolResult;

/**
 * Builder for {@link NetworkTool} instances with support for {@link RequestMode} configuration.
 *
 * <p>Required fields: {@code ensembleName}, {@code toolName}, and {@code clientRegistry}.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Simple AWAIT (default) mode
 * NetworkTool tool = NetworkTool.builder()
 *     .ensembleName("kitchen")
 *     .toolName("check-inventory")
 *     .clientRegistry(registry)
 *     .build();
 *
 * // ASYNC mode with callback
 * NetworkTool asyncTool = NetworkTool.builder()
 *     .ensembleName("kitchen")
 *     .toolName("check-inventory")
 *     .clientRegistry(registry)
 *     .mode(RequestMode.ASYNC)
 *     .onComplete(result -&gt; System.out.println("Done: " + result))
 *     .build();
 *
 * // AWAIT_WITH_DEADLINE mode
 * NetworkTool deadlineTool = NetworkTool.builder()
 *     .ensembleName("kitchen")
 *     .toolName("check-inventory")
 *     .clientRegistry(registry)
 *     .mode(RequestMode.AWAIT_WITH_DEADLINE)
 *     .deadline(Duration.ofSeconds(5))
 *     .deadlineAction(DeadlineAction.RETURN_PARTIAL)
 *     .build();
 * </pre>
 *
 * @see NetworkTool
 * @see RequestMode
 * @see DeadlineAction
 */
public class NetworkToolBuilder {

    private String ensembleName;
    private String toolName;
    private Duration executionTimeout = NetworkTool.DEFAULT_EXECUTION_TIMEOUT;
    private NetworkClientRegistry clientRegistry;
    private RequestMode mode = RequestMode.AWAIT;
    private Consumer<ToolResult> onComplete;
    private Duration deadline;
    private DeadlineAction deadlineAction = DeadlineAction.RETURN_TIMEOUT_ERROR;

    /**
     * Creates a new builder with default settings.
     */
    NetworkToolBuilder() {}

    /**
     * Sets the target ensemble name.
     *
     * @param ensembleName the ensemble name; must not be null
     * @return this builder
     */
    public NetworkToolBuilder ensembleName(String ensembleName) {
        this.ensembleName = ensembleName;
        return this;
    }

    /**
     * Sets the shared tool name on the target ensemble.
     *
     * @param toolName the tool name; must not be null
     * @return this builder
     */
    public NetworkToolBuilder toolName(String toolName) {
        this.toolName = toolName;
        return this;
    }

    /**
     * Sets the maximum time to wait for the tool to complete.
     *
     * <p>Defaults to {@link NetworkTool#DEFAULT_EXECUTION_TIMEOUT} (30 seconds).
     *
     * @param executionTimeout the execution timeout; must not be null
     * @return this builder
     */
    public NetworkToolBuilder executionTimeout(Duration executionTimeout) {
        this.executionTimeout = executionTimeout;
        return this;
    }

    /**
     * Sets the client registry for WebSocket connections.
     *
     * @param clientRegistry the client registry; must not be null
     * @return this builder
     */
    public NetworkToolBuilder clientRegistry(NetworkClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
        return this;
    }

    /**
     * Sets the request mode.
     *
     * <p>Defaults to {@link RequestMode#AWAIT}.
     *
     * @param mode the request mode; must not be null
     * @return this builder
     */
    public NetworkToolBuilder mode(RequestMode mode) {
        this.mode = mode;
        return this;
    }

    /**
     * Sets the callback invoked when the result arrives asynchronously.
     *
     * <p>Required for {@link RequestMode#ASYNC} mode. Also used by
     * {@link DeadlineAction#CONTINUE_IN_BACKGROUND} when a deadline is exceeded.
     *
     * @param onComplete the completion callback
     * @return this builder
     */
    public NetworkToolBuilder onComplete(Consumer<ToolResult> onComplete) {
        this.onComplete = onComplete;
        return this;
    }

    /**
     * Sets the deadline duration for {@link RequestMode#AWAIT_WITH_DEADLINE} mode.
     *
     * @param deadline the maximum time to wait before applying the deadline action
     * @return this builder
     */
    public NetworkToolBuilder deadline(Duration deadline) {
        this.deadline = deadline;
        return this;
    }

    /**
     * Sets the action to take when the deadline is exceeded.
     *
     * <p>Defaults to {@link DeadlineAction#RETURN_TIMEOUT_ERROR}.
     *
     * @param deadlineAction the deadline action; must not be null
     * @return this builder
     */
    public NetworkToolBuilder deadlineAction(DeadlineAction deadlineAction) {
        this.deadlineAction = deadlineAction;
        return this;
    }

    /**
     * Builds a new {@link NetworkTool} with the configured settings.
     *
     * @return a new NetworkTool
     * @throws NullPointerException  if {@code ensembleName}, {@code toolName}, or
     *                               {@code clientRegistry} is null
     * @throws IllegalStateException if {@link RequestMode#ASYNC} is set without an
     *                               {@code onComplete} callback, or if
     *                               {@link RequestMode#AWAIT_WITH_DEADLINE} is set
     *                               without a {@code deadline}
     */
    public NetworkTool build() {
        Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        Objects.requireNonNull(toolName, "toolName must not be null");
        Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");

        if (mode == RequestMode.ASYNC && onComplete == null) {
            throw new IllegalStateException("ASYNC mode requires an onComplete callback");
        }
        if (mode == RequestMode.AWAIT_WITH_DEADLINE && deadline == null) {
            throw new IllegalStateException("AWAIT_WITH_DEADLINE mode requires a deadline");
        }

        return new NetworkTool(
                ensembleName, toolName, executionTimeout, clientRegistry, mode, onComplete, deadline, deadlineAction);
    }
}
