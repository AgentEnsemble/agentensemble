package net.agentensemble.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;

/**
 * Base class for {@link AgentTool} implementations that provides built-in metrics,
 * structured logging, and exception handling via a template method pattern.
 *
 * <h2>Usage</h2>
 *
 * <p>Extend this class and implement {@link #doExecute(String)}:
 *
 * <pre>
 * public class CalculatorTool extends AbstractAgentTool {
 *     {@literal @}Override
 *     public String name() { return "calculator"; }
 *
 *     {@literal @}Override
 *     public String description() { return "Evaluates a math expression."; }
 *
 *     {@literal @}Override
 *     protected ToolResult doExecute(String input) {
 *         log().debug("Evaluating: {}", input);
 *         double result = evaluate(input);
 *         metrics().incrementCounter("expressions_evaluated", name(), null);
 *         return ToolResult.success(String.valueOf(result));
 *     }
 * }
 * </pre>
 *
 * <h2>Automatic Instrumentation</h2>
 *
 * <p>The {@link #execute(String)} method (declared {@code final}) wraps {@link #doExecute(String)}
 * with:
 *
 * <ul>
 *   <li><strong>Timing</strong>: duration is recorded on every execution
 *   <li><strong>Success/failure counters</strong>: incremented based on {@link ToolResult#isSuccess()}
 *   <li><strong>Error counter</strong>: incremented if {@link #doExecute(String)} throws
 *   <li><strong>Exception safety</strong>: any exception from {@link #doExecute(String)} is caught,
 *       logged, counted, and converted to a {@link ToolResult#failure(String)}
 * </ul>
 *
 * <p>All metrics are tagged with the tool name and the role of the agent that invoked the tool,
 * allowing disambiguation when the same tool is used by multiple agents.
 *
 * <h2>Framework Context Injection</h2>
 *
 * <p>The framework injects a {@link ToolContext} before first execution via
 * {@link #setContext(ToolContext)}. Before injection, fallback implementations
 * are used so that {@link #doExecute(String)} can be safely called in unit tests
 * without framework wiring.
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Subclasses must be thread-safe. When the parallel tool executor is active,
 * multiple virtual threads may call {@link #execute(String)} concurrently.
 */
public abstract class AbstractAgentTool implements AgentTool {

    /**
     * Thread-local carrying the role of the agent currently invoking this tool.
     * Set by the framework in {@link net.agentensemble.agent.ToolResolver} before
     * calling {@link #execute(String)}, then cleared after execution.
     * Works correctly with virtual threads -- each virtual thread has its own ThreadLocal.
     */
    static final ThreadLocal<String> CURRENT_AGENT_ROLE = new ThreadLocal<>();

    /** Default agentRole string used when no agent context is set (e.g., in unit tests). */
    static final String UNKNOWN_AGENT = "unknown";

    /**
     * Fallback executor used before the framework injects a ToolContext.
     * Creates a new virtual thread per task.
     */
    private static final Executor FALLBACK_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * The context injected by the framework. Volatile for safe publication across
     * the virtual thread boundary during injection vs. first execution.
     */
    private volatile ToolContext context;

    // ========================
    // Template method -- final so framework instrumentation cannot be bypassed
    // ========================

    /**
     * Execute this tool with automatic timing, metrics, and exception handling.
     *
     * <p>Delegates to {@link #doExecute(String)}. Exceptions thrown by
     * {@link #doExecute(String)} are caught, logged, counted as errors, and
     * converted to a {@link ToolResult#failure(String)}.
     *
     * @param input the input string from the LLM tool call
     * @return a ToolResult -- never null, never throws
     */
    @Override
    public final ToolResult execute(String input) {
        String agentRole = currentAgentRole();
        Instant start = Instant.now();
        try {
            ToolResult result = doExecute(input);
            Duration elapsed = Duration.between(start, Instant.now());
            if (result == null) {
                result = ToolResult.success("");
            }
            if (result.isSuccess()) {
                metrics().incrementSuccess(name(), agentRole);
            } else {
                metrics().incrementFailure(name(), agentRole);
                log().debug("Tool '{}' returned failure: {}", name(), result.getErrorMessage());
            }
            metrics().recordDuration(name(), agentRole, elapsed);
            return result;
        } catch (Exception e) {
            Duration elapsed = Duration.between(start, Instant.now());
            metrics().incrementError(name(), agentRole);
            metrics().recordDuration(name(), agentRole, elapsed);
            log().warn("Tool '{}' threw exception: {}", name(), e.getMessage(), e);
            return ToolResult.failure(e.getMessage());
        }
    }

    // ========================
    // Subclass contract
    // ========================

    /**
     * Perform the actual tool work. Called by the framework via {@link #execute(String)}.
     *
     * <p>May throw any exception; the framework will catch, log, and convert it to a
     * {@link ToolResult#failure(String)}. Checked exceptions do not need to be declared.
     *
     * @param input the input string from the LLM tool call
     * @return a ToolResult indicating success or failure; must not be null
     */
    protected abstract ToolResult doExecute(String input);

    // ========================
    // Context accessors for subclasses
    // ========================

    /**
     * Returns the SLF4J logger scoped to this tool's name.
     *
     * <p>Available immediately -- falls back to a logger scoped to the class name before
     * the framework injects a {@link ToolContext}.
     *
     * @return the logger; never null
     */
    protected Logger log() {
        ToolContext ctx = context;
        if (ctx != null) {
            return ctx.logger();
        }
        return org.slf4j.LoggerFactory.getLogger(getClass());
    }

    /**
     * Returns the {@link ToolMetrics} backend for this tool.
     *
     * <p>Falls back to {@link NoOpToolMetrics#INSTANCE} before the framework injects
     * a {@link ToolContext}.
     *
     * @return the metrics; never null
     */
    protected ToolMetrics metrics() {
        ToolContext ctx = context;
        if (ctx != null) {
            return ctx.metrics();
        }
        return NoOpToolMetrics.INSTANCE;
    }

    /**
     * Returns the {@link Executor} for this tool.
     *
     * <p>Falls back to a virtual-thread-per-task executor before the framework injects
     * a {@link ToolContext}.
     *
     * @return the executor; never null
     */
    protected Executor executor() {
        ToolContext ctx = context;
        if (ctx != null) {
            return ctx.executor();
        }
        return FALLBACK_EXECUTOR;
    }

    // ========================
    // Framework injection -- package-private
    // ========================

    /**
     * Inject the {@link ToolContext} provided by the framework.
     *
     * <p>Called by {@link net.agentensemble.agent.ToolResolver} during tool resolution,
     * before any {@link #execute(String)} calls. Not part of the public API.
     *
     * @param toolContext the context to inject; must not be null
     */
    void setContext(ToolContext toolContext) {
        if (toolContext == null) {
            throw new IllegalArgumentException("toolContext must not be null");
        }
        this.context = toolContext;
    }

    // ========================
    // Thread-local agent role -- set/cleared by the framework
    // ========================

    /**
     * Set the agent role on the current thread before tool execution.
     * Called by the framework in {@link net.agentensemble.agent.ToolResolver}.
     * Package-private -- not part of the public API.
     *
     * @param agentRole the role of the agent invoking the tool; null clears the role
     */
    static void setCurrentAgentRole(String agentRole) {
        if (agentRole == null) {
            CURRENT_AGENT_ROLE.remove();
        } else {
            CURRENT_AGENT_ROLE.set(agentRole);
        }
    }

    /**
     * Clear the agent role from the current thread after tool execution.
     * Called by the framework in {@link net.agentensemble.agent.ToolResolver}.
     * Package-private -- not part of the public API.
     */
    static void clearCurrentAgentRole() {
        CURRENT_AGENT_ROLE.remove();
    }

    /**
     * Return the current agent role, or {@value #UNKNOWN_AGENT} if none is set.
     */
    private static String currentAgentRole() {
        String role = CURRENT_AGENT_ROLE.get();
        return role != null ? role : UNKNOWN_AGENT;
    }
}
