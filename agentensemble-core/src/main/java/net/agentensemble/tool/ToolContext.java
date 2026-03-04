package net.agentensemble.tool;

import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-cutting context injected by the framework into {@link AbstractAgentTool} instances
 * before their first execution.
 *
 * <p>Carries three concerns:
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
 * </ul>
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class ToolContext {

    private final Logger logger;
    private final ToolMetrics metrics;
    private final Executor executor;

    private ToolContext(Logger logger, ToolMetrics metrics, Executor executor) {
        this.logger = logger;
        this.metrics = metrics;
        this.executor = executor;
    }

    /**
     * Create a ToolContext for the given tool name.
     *
     * <p>The logger is pre-scoped to {@code net.agentensemble.tool.<toolName>}.
     *
     * @param toolName the tool's {@link AgentTool#name()} value; must not be null
     * @param metrics  the ToolMetrics implementation to use; must not be null
     * @param executor the executor for the tool; must not be null
     * @return a new ToolContext
     */
    public static ToolContext of(String toolName, ToolMetrics metrics, Executor executor) {
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
        return new ToolContext(logger, metrics, executor);
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
}
