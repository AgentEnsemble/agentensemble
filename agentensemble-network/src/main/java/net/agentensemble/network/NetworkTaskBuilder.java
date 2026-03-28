package net.agentensemble.network;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import net.agentensemble.tool.ToolResult;

/**
 * Builder for {@link NetworkTask} instances with support for {@link RequestMode} configuration.
 *
 * <p>Required fields: {@code ensembleName}, {@code taskName}, and {@code clientRegistry}.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Simple AWAIT (default) mode
 * NetworkTask task = NetworkTask.builder()
 *     .ensembleName("kitchen")
 *     .taskName("prepare-meal")
 *     .clientRegistry(registry)
 *     .build();
 *
 * // ASYNC mode with callback
 * NetworkTask asyncTask = NetworkTask.builder()
 *     .ensembleName("kitchen")
 *     .taskName("prepare-meal")
 *     .clientRegistry(registry)
 *     .mode(RequestMode.ASYNC)
 *     .onComplete(result -&gt; System.out.println("Done: " + result))
 *     .build();
 *
 * // AWAIT_WITH_DEADLINE mode
 * NetworkTask deadlineTask = NetworkTask.builder()
 *     .ensembleName("kitchen")
 *     .taskName("prepare-meal")
 *     .clientRegistry(registry)
 *     .mode(RequestMode.AWAIT_WITH_DEADLINE)
 *     .deadline(Duration.ofSeconds(5))
 *     .deadlineAction(DeadlineAction.RETURN_PARTIAL)
 *     .build();
 * </pre>
 *
 * @see NetworkTask
 * @see RequestMode
 * @see DeadlineAction
 */
public class NetworkTaskBuilder {

    private String ensembleName;
    private String taskName;
    private Duration executionTimeout = NetworkTask.DEFAULT_EXECUTION_TIMEOUT;
    private NetworkClientRegistry clientRegistry;
    private RequestMode mode = RequestMode.AWAIT;
    private Consumer<ToolResult> onComplete;
    private Duration deadline;
    private DeadlineAction deadlineAction = DeadlineAction.RETURN_TIMEOUT_ERROR;

    /**
     * Creates a new builder with default settings.
     */
    NetworkTaskBuilder() {}

    /**
     * Sets the target ensemble name.
     *
     * @param ensembleName the ensemble name; must not be null
     * @return this builder
     */
    public NetworkTaskBuilder ensembleName(String ensembleName) {
        this.ensembleName = ensembleName;
        return this;
    }

    /**
     * Sets the shared task name on the target ensemble.
     *
     * @param taskName the task name; must not be null
     * @return this builder
     */
    public NetworkTaskBuilder taskName(String taskName) {
        this.taskName = taskName;
        return this;
    }

    /**
     * Sets the maximum time to wait for the task to complete.
     *
     * <p>Defaults to {@link NetworkTask#DEFAULT_EXECUTION_TIMEOUT} (30 minutes).
     *
     * @param executionTimeout the execution timeout; must not be null
     * @return this builder
     */
    public NetworkTaskBuilder executionTimeout(Duration executionTimeout) {
        this.executionTimeout = executionTimeout;
        return this;
    }

    /**
     * Sets the client registry for WebSocket connections.
     *
     * @param clientRegistry the client registry; must not be null
     * @return this builder
     */
    public NetworkTaskBuilder clientRegistry(NetworkClientRegistry clientRegistry) {
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
    public NetworkTaskBuilder mode(RequestMode mode) {
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
    public NetworkTaskBuilder onComplete(Consumer<ToolResult> onComplete) {
        this.onComplete = onComplete;
        return this;
    }

    /**
     * Sets the deadline duration for {@link RequestMode#AWAIT_WITH_DEADLINE} mode.
     *
     * @param deadline the maximum time to wait before applying the deadline action
     * @return this builder
     */
    public NetworkTaskBuilder deadline(Duration deadline) {
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
    public NetworkTaskBuilder deadlineAction(DeadlineAction deadlineAction) {
        this.deadlineAction = deadlineAction;
        return this;
    }

    /**
     * Builds a new {@link NetworkTask} with the configured settings.
     *
     * @return a new NetworkTask
     * @throws NullPointerException  if {@code ensembleName}, {@code taskName}, or
     *                               {@code clientRegistry} is null
     * @throws IllegalStateException if {@link RequestMode#ASYNC} is set without an
     *                               {@code onComplete} callback, or if
     *                               {@link RequestMode#AWAIT_WITH_DEADLINE} is set
     *                               without a {@code deadline}
     */
    public NetworkTask build() {
        Objects.requireNonNull(ensembleName, "ensembleName must not be null");
        Objects.requireNonNull(taskName, "taskName must not be null");
        Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");

        if (mode == RequestMode.ASYNC && onComplete == null) {
            throw new IllegalStateException("ASYNC mode requires an onComplete callback");
        }
        if (mode == RequestMode.AWAIT_WITH_DEADLINE && deadline == null) {
            throw new IllegalStateException("AWAIT_WITH_DEADLINE mode requires a deadline");
        }

        return new NetworkTask(
                ensembleName, taskName, executionTimeout, clientRegistry, mode, onComplete, deadline, deadlineAction);
    }
}
