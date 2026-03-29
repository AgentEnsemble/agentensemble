package net.agentensemble.tool;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-cutting context injected by the framework into {@link AbstractAgentTool} instances
 * before their first execution.
 *
 * <p>Carries five concerns:
 *
 * <ul>
 *   <li><strong>Logger</strong>: a pre-scoped SLF4J logger named
 *       {@code net.agentensemble.tool.<toolName>}. Accessible via
 *       {@link AbstractAgentTool#log()}.
 *   <li><strong>ToolMetrics</strong>: the metrics backend configured on the Ensemble.
 *       Default is {@link NoOpToolMetrics}. Accessible via
 *       {@link AbstractAgentTool#metrics()}.
 *   <li><strong>Executor</strong>: the tool executor configured on the Ensemble. Used
 *       internally by the framework to parallelize multi-tool turns; also available to
 *       tools for their own sub-task scheduling. Default produces virtual threads.
 *       Accessible via {@link AbstractAgentTool#executor()}.
 *   <li><strong>ReviewHandler</strong>: the human-in-the-loop handler for tool-level
 *       approval gates. {@code null} when no review is configured on the ensemble.
 *       Accessible via {@link #reviewHandler()}.
 *   <li><strong>FileChangeListener</strong>: an optional callback for file change events.
 *       {@code null} when no file change tracking is configured. Typed as {@code Object}
 *       to avoid a hard dependency on the callback package from tool implementations.
 *       Accessible via {@link #fileChangeListener()}.
 * </ul>
 *
 * <p>The {@code reviewHandler} field is typed as {@code Object} rather than
 * {@code ReviewHandler} to avoid forcing a class load of
 * {@code net.agentensemble.review.ReviewHandler} when the
 * {@code agentensemble-review} module is absent from the runtime classpath.
 * {@link AbstractAgentTool#requestApproval(String)} casts it with a
 * {@link NoClassDefFoundError} guard.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ToolContext {

    private final Logger logger;
    private final ToolMetrics metrics;
    private final Executor executor;
    private final Object reviewHandler;
    private final Object fileChangeListener;

    private ToolContext(
            Logger logger, ToolMetrics metrics, Executor executor, Object reviewHandler, Object fileChangeListener) {
        this.logger = logger;
        this.metrics = metrics;
        this.executor = executor;
        this.reviewHandler = reviewHandler;
        this.fileChangeListener = fileChangeListener;
    }

    /**
     * Create a ToolContext for the given tool name without a ReviewHandler or file change listener.
     *
     * <p>The logger is pre-scoped to {@code net.agentensemble.tool.<toolName>}.
     *
     * @param toolName the tool's {@link AgentTool#name()} value; must not be null
     * @param metrics  the ToolMetrics implementation to use; must not be null
     * @param executor the executor for the tool; must not be null
     * @return a new ToolContext with null reviewHandler and fileChangeListener
     */
    public static ToolContext of(String toolName, ToolMetrics metrics, Executor executor) {
        return of(toolName, metrics, executor, null, null);
    }

    /**
     * Create a ToolContext for the given tool name, including an optional ReviewHandler.
     *
     * <p>The logger is pre-scoped to {@code net.agentensemble.tool.<toolName>}.
     *
     * @param toolName      the tool's {@link AgentTool#name()} value; must not be null
     * @param metrics       the ToolMetrics implementation to use; must not be null
     * @param executor      the executor for the tool; must not be null
     * @param reviewHandler the review handler for tool-level approval gates, stored as
     *                      {@code Object} to avoid runtime class loading; may be null
     * @return a new ToolContext with null fileChangeListener
     */
    public static ToolContext of(String toolName, ToolMetrics metrics, Executor executor, Object reviewHandler) {
        return of(toolName, metrics, executor, reviewHandler, null);
    }

    /**
     * Create a ToolContext for the given tool name, including an optional ReviewHandler
     * and an optional file change listener.
     *
     * <p>The logger is pre-scoped to {@code net.agentensemble.tool.<toolName>}.
     *
     * @param toolName           the tool's {@link AgentTool#name()} value; must not be null
     * @param metrics            the ToolMetrics implementation to use; must not be null
     * @param executor           the executor for the tool; must not be null
     * @param reviewHandler      the review handler for tool-level approval gates, stored as
     *                           {@code Object} to avoid runtime class loading; may be null
     * @param fileChangeListener the file change listener for workspace file change events,
     *                           stored as {@code Object} to avoid hard dependency; may be null
     * @return a new ToolContext
     */
    public static ToolContext of(
            String toolName, ToolMetrics metrics, Executor executor, Object reviewHandler, Object fileChangeListener) {
        if (toolName == null) {
            throw new IllegalArgumentException("toolName must not be null");
        }
        if (metrics == null) {
            throw new IllegalArgumentException("metrics must not be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor must not be null");
        }
        Logger logger = LoggerFactory.getLogger("net.agentensemble.tool." + toolName);
        return new ToolContext(logger, metrics, executor, reviewHandler, fileChangeListener);
    }

    /**
     * The SLF4J logger scoped to this tool's name.
     *
     * @return the logger; never null
     */
    public Logger logger() {
        return logger;
    }

    /**
     * The ToolMetrics backend configured for this tool.
     *
     * @return the metrics; never null
     */
    public ToolMetrics metrics() {
        return metrics;
    }

    /**
     * The Executor for tool scheduling. The framework uses this to parallelize
     * concurrent tool calls within a single LLM turn. Tools may also use it to
     * schedule sub-tasks.
     *
     * @return the executor; never null
     */
    public Executor executor() {
        return executor;
    }

    /**
     * The ReviewHandler for tool-level approval gates.
     *
     * <p>Stored as {@code Object} to avoid forcing a class load of
     * {@code net.agentensemble.review.ReviewHandler} when the
     * {@code agentensemble-review} module is absent from the runtime classpath.
     * Cast to {@code ReviewHandler} inside
     * {@link AbstractAgentTool#requestApproval(String)} with a
     * {@link NoClassDefFoundError} guard.
     *
     * @return the review handler, or {@code null} when review is not configured
     */
    public Object reviewHandler() {
        return reviewHandler;
    }

    /**
     * The optional file change listener for workspace file change events.
     *
     * <p>Stored as {@code Object} to avoid a hard dependency on the callback package
     * from tool implementations. Tools use
     * {@link AbstractAgentTool#fireFileChanged(String, String, int, int)} which handles
     * the casting and invocation via reflection.
     *
     * @return the file change listener, or {@code null} when file change tracking is not configured
     */
    public Object fileChangeListener() {
        return fileChangeListener;
    }
}
