package net.agentensemble.tool;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ConsoleReviewHandler;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTiming;
import org.slf4j.Logger;

/**
 * Base class for {@link AgentTool} implementations that provides built-in metrics,
 * structured logging, exception handling, and optional human approval gates via a
 * template method pattern.
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
 * <h2>Tool-Level Approval Gates</h2>
 *
 * <p>Subclasses can request human approval before executing a dangerous or irreversible
 * action by calling {@link #requestApproval(String)}:
 *
 * <pre>
 * {@literal @}Override
 * protected ToolResult doExecute(String input) {
 *     String command = parseCommand(input);
 *
 *     ReviewDecision decision = requestApproval("Execute command: " + command);
 *     if (decision instanceof ReviewDecision.ExitEarly) {
 *         return ToolResult.failure("Command rejected by reviewer: " + command);
 *     }
 *     if (decision instanceof ReviewDecision.Edit edit) {
 *         command = edit.revisedOutput();
 *     }
 *
 *     return executeCommand(command);
 * }
 * </pre>
 *
 * <p>When no {@link ReviewHandler} is configured on the ensemble,
 * {@code requestApproval()} returns {@link ReviewDecision#continueExecution()} (auto-approve).
 *
 * <p>Built-in tools that set a {@code requireApproval(true)} builder option perform an
 * explicit null-handler check before calling {@code requestApproval()} and throw
 * {@link IllegalStateException} with a clear configuration message rather than silently
 * auto-approving. Custom tools may choose their own policy.
 *
 * <h2>Parallel Tool Approval Serialization</h2>
 *
 * <p>When the agent executor runs multiple tools concurrently and both request approval,
 * console-based approval prompts are serialized via a shared {@link ReentrantLock}
 * ({@link #CONSOLE_APPROVAL_LOCK}) to prevent interleaved output. Non-console handlers
 * (e.g., webhook, auto-approve) are not serialized.
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
 *       logged, counted, and converted to a {@link ToolResult#failure(String)}, with the
 *       exception of {@link ExitEarlyException} and {@link IllegalStateException} which are
 *       re-thrown to preserve framework control flow and signal configuration errors
 *       respectively.
 * </ul>
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
     * Shared lock used to serialize approval requests to {@link ConsoleReviewHandler}
     * during parallel tool execution.
     *
     * <p>When the agent executor runs multiple tools concurrently and both call
     * {@link #requestApproval(String)}, two {@link ConsoleReviewHandler} prompts printed
     * to stdout simultaneously would be interleaved and unreadable. This lock ensures that
     * only one console review prompt is displayed at a time.
     *
     * <p>Non-console handlers (e.g., auto-approve, webhook) do not acquire this lock, so
     * concurrent approvals via those handlers proceed without serialization.
     */
    static final ReentrantLock CONSOLE_APPROVAL_LOCK = new ReentrantLock();

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
     * converted to a {@link ToolResult#failure(String)}, with the following exceptions:
     *
     * <ul>
     *   <li>{@link ExitEarlyException}: re-thrown to propagate reviewer exit decisions
     *   <li>{@link net.agentensemble.exception.ToolConfigurationException}: re-thrown to surface
     *       tool configuration errors (e.g., {@code requireApproval(true)} with no
     *       ReviewHandler, or missing {@code agentensemble-review} module). Ordinary
     *       {@link IllegalStateException} from tool implementations is NOT re-thrown -- it
     *       is converted to a {@link ToolResult#failure(String)} so the agent can adapt.
     * </ul>
     *
     * @param input the input string from the LLM tool call
     * @return a ToolResult -- never null, never throws (except ExitEarlyException
     *         and ToolConfigurationException as documented above)
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
                if (log().isDebugEnabled()) {
                    log().debug("Tool '{}' returned failure: {}", name(), result.getErrorMessage());
                }
            }
            metrics().recordDuration(name(), agentRole, elapsed);
            return result;
        } catch (ExitEarlyException e) {
            // Re-throw exit-early signals without converting to a tool failure.
            // The workflow executor catches this and assembles partial results.
            throw e;
        } catch (ToolConfigurationException e) {
            // Re-throw tool configuration errors (e.g., requireApproval=true with no ReviewHandler,
            // or missing agentensemble-review module). These are programmer errors that must surface
            // clearly rather than being silently absorbed as tool failures.
            throw e;
        } catch (Exception e) {
            Duration elapsed = Duration.between(start, Instant.now());
            metrics().incrementError(name(), agentRole);
            metrics().recordDuration(name(), agentRole, elapsed);
            if (log().isWarnEnabled()) {
                log().warn("Tool '{}' threw exception: {}", name(), e.getMessage(), e);
            }
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
    // Tool-level approval gate helpers
    // ========================

    /**
     * Request human approval before proceeding with a potentially dangerous action.
     *
     * <p>Returns the {@link ReviewDecision} -- callers must handle
     * {@link ReviewDecision.Edit} and {@link ReviewDecision.ExitEarly} appropriately:
     *
     * <pre>
     * ReviewDecision decision = requestApproval("Execute command: " + command);
     * if (decision instanceof ReviewDecision.ExitEarly) {
     *     return ToolResult.failure("Rejected by reviewer: " + command);
     * }
     * if (decision instanceof ReviewDecision.Edit edit) {
     *     command = edit.revisedOutput();
     * }
     * // proceed with command
     * </pre>
     *
     * <p>When no {@link ReviewHandler} is configured on the ensemble (i.e., the
     * {@link ToolContext} carries a null handler), this method returns
     * {@link ReviewDecision#continueExecution()} (auto-approve). Custom tools that
     * require approval should check for a null handler explicitly before calling
     * this method if they want fail-fast behaviour.
     *
     * <p>Uses {@link Review#DEFAULT_TIMEOUT} (5 minutes) and
     * {@link Review#DEFAULT_ON_TIMEOUT} ({@code EXIT_EARLY}).
     *
     * <p>When the configured handler is a {@link ConsoleReviewHandler}, concurrent
     * approval requests from parallel tool executions are serialized via
     * {@link #CONSOLE_APPROVAL_LOCK} to prevent interleaved console output.
     *
     * @param description a human-readable description of the action requiring approval
     * @return the reviewer's decision; never null
     * @throws IllegalStateException if the {@code agentensemble-review} module is absent
     *                               from the runtime classpath when an approval is requested
     */
    protected ReviewDecision requestApproval(String description) {
        return requestApproval(description, Review.DEFAULT_TIMEOUT, Review.DEFAULT_ON_TIMEOUT);
    }

    /**
     * Request human approval with a custom timeout and on-timeout action.
     *
     * <p>See {@link #requestApproval(String)} for full contract and usage notes.
     *
     * @param description   a human-readable description of the action requiring approval
     * @param timeout       how long to wait for a reviewer response; must not be null
     * @param onTimeout     the action to take when the review gate times out; must not be null
     * @return the reviewer's decision; never null
     * @throws IllegalStateException if the {@code agentensemble-review} module is absent
     *                               from the runtime classpath when an approval is requested
     */
    protected ReviewDecision requestApproval(String description, Duration timeout, OnTimeoutAction onTimeout) {
        try {
            return doRequestApproval(description, timeout, onTimeout);
        } catch (NoClassDefFoundError e) {
            throw new ToolConfigurationException(
                    "Tool '"
                            + name()
                            + "' requires the agentensemble-review module to be on the classpath. "
                            + "Add 'net.agentensemble:agentensemble-review' to your project dependencies.",
                    e);
        }
    }

    private ReviewDecision doRequestApproval(String description, Duration timeout, OnTimeoutAction onTimeout) {
        Object rawHandler = rawReviewHandler();
        if (rawHandler == null) {
            // No handler configured -- auto-approve (caller is responsible for fail-fast
            // checks when requireApproval semantics are needed)
            if (log().isDebugEnabled()) {
                log().debug(
                                "Tool '{}' requestApproval called but no ReviewHandler is configured; auto-approving.",
                                name());
            }
            return ReviewDecision.continueExecution();
        }

        if (!(rawHandler instanceof ReviewHandler)) {
            throw new ToolConfigurationException("Tool '"
                    + name()
                    + "' is misconfigured: reviewHandler must be an instance of "
                    + ReviewHandler.class.getName()
                    + " but was "
                    + rawHandler.getClass().getName()
                    + ".");
        }
        ReviewHandler handler = (ReviewHandler) rawHandler;
        ReviewRequest request =
                ReviewRequest.of(description, "", ReviewTiming.DURING_EXECUTION, timeout, onTimeout, null);

        // Serialize console reviews to prevent interleaved prompts during parallel tool execution
        boolean isConsole = handler instanceof ConsoleReviewHandler;
        if (isConsole) {
            CONSOLE_APPROVAL_LOCK.lock();
        }
        try {
            return handler.review(request);
        } finally {
            if (isConsole) {
                CONSOLE_APPROVAL_LOCK.unlock();
            }
        }
    }

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

    /**
     * Returns the raw ReviewHandler object from the injected {@link ToolContext}, or
     * {@code null} when no review is configured.
     *
     * <p>Stored as {@code Object} to avoid forcing a class load of
     * {@code ReviewHandler} when the {@code agentensemble-review} module is absent
     * from the runtime classpath. Cast to {@code ReviewHandler} inside
     * {@link #requestApproval(String)} with a {@link NoClassDefFoundError} guard.
     *
     * <p>Used by {@link #requestApproval(String)} and by subclasses that need to check
     * whether a handler is configured before proceeding (e.g., for fail-fast null checks
     * in tools with {@code requireApproval=true}).
     *
     * @return the raw review handler, or {@code null}
     */
    protected Object rawReviewHandler() {
        ToolContext ctx = context;
        return ctx != null ? ctx.reviewHandler() : null;
    }

    // ========================
    // File change event helpers
    // ========================

    /**
     * Fire a file change event if a file change listener is available in the {@link ToolContext}.
     *
     * <p>The listener is an {@link net.agentensemble.callback.EnsembleListener} obtained from
     * {@link ToolContext#fileChangeListener()}, typed as {@code Object} to avoid a hard
     * dependency on the callback package. Invocation is performed via reflection.
     *
     * <p>If no listener is configured, or if reflection fails, this method silently does nothing
     * (debug-level logging only).
     *
     * @param filePath    relative path of the changed file within the workspace
     * @param changeType  type of change: "CREATED", "MODIFIED", or "DELETED"
     * @param linesAdded  number of lines added (0 for deletions)
     * @param linesRemoved number of lines removed (0 for creations)
     */
    protected void fireFileChanged(String filePath, String changeType, int linesAdded, int linesRemoved) {
        ToolContext ctx = context;
        Object listener = ctx != null ? ctx.fileChangeListener() : null;
        if (listener == null) {
            return;
        }
        try {
            String agentRole = CURRENT_AGENT_ROLE.get();
            if (agentRole == null) {
                agentRole = UNKNOWN_AGENT;
            }
            var event = new net.agentensemble.callback.FileChangedEvent(
                    agentRole, filePath, changeType, linesAdded, linesRemoved, java.time.Instant.now());
            var method =
                    listener.getClass().getMethod("onFileChanged", net.agentensemble.callback.FileChangedEvent.class);
            method.invoke(listener, event);
        } catch (Exception e) {
            if (log().isDebugEnabled()) {
                log().debug("Could not fire file change event: {}", e.getMessage());
            }
        }
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
