package net.agentensemble.execution;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewPolicy;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolMetrics;
import net.agentensemble.trace.CaptureMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable execution context bundling all cross-cutting concerns for a single
 * ensemble run: memory state, verbosity, event listeners, tool executor, tool metrics,
 * cost configuration, and capture mode.
 *
 * <p>An {@code ExecutionContext} is created once per {@link net.agentensemble.Ensemble#run()}
 * invocation and threaded through the entire execution stack -- workflow executors,
 * {@link net.agentensemble.agent.AgentExecutor}, delegation tools, and delegation
 * contexts.
 *
 * <p><strong>Tool execution:</strong> The {@link #toolExecutor()} is used by
 * {@link net.agentensemble.agent.AgentExecutor} to parallelize concurrent tool calls
 * within a single LLM turn. The default is a virtual-thread-per-task executor (Java 21),
 * making blocking tool calls (I/O, subprocess) cheap without dedicated platform threads.
 * Configure via {@code Ensemble.builder().toolExecutor(executor)}.
 *
 * <p><strong>Tool metrics:</strong> The {@link #toolMetrics()} backend is injected into
 * every {@link net.agentensemble.tool.AbstractAgentTool} via a
 * {@link net.agentensemble.tool.ToolContext}. The default is {@link NoOpToolMetrics},
 * which discards all measurements.
 * Configure via {@code Ensemble.builder().toolMetrics(metrics)}.
 *
 * <p><strong>Cost configuration:</strong> When non-null, the {@link #costConfiguration()}
 * is used by {@link net.agentensemble.trace.internal.TaskTraceAccumulator} to estimate
 * monetary cost from token counts. Set via {@code Ensemble.builder().costConfiguration(cfg)}.
 *
 * <p><strong>Capture mode:</strong> Controls the depth of data collection during execution.
 * The default is {@link CaptureMode#OFF}. Set via {@code Ensemble.builder().captureMode(mode)},
 * or activate without code changes via the {@code agentensemble.captureMode} system property
 * or {@code AGENTENSEMBLE_CAPTURE_MODE} environment variable.
 *
 * <p>Fire methods ({@link #fireTaskStart}, {@link #fireTaskComplete}, {@link #fireTaskFailed},
 * {@link #fireToolCall}) dispatch events to all registered listeners. Each listener is
 * called independently; an exception from one listener is caught, logged, and does not
 * prevent subsequent listeners from being notified or abort task execution.
 *
 * <p>Thread safety: this class is immutable. Fire methods may be called concurrently from
 * parallel workflow virtual threads. Listener implementations must be thread-safe when
 * used with parallel workflows.
 */
public final class ExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(ExecutionContext.class);

    private final MemoryContext memoryContext;
    private final boolean verbose;
    private final List<EnsembleListener> listeners;
    private final Executor toolExecutor;
    private final ToolMetrics toolMetrics;
    private final CostConfiguration costConfiguration;
    private final CaptureMode captureMode;
    private final MemoryStore memoryStore;
    private final ReviewHandler reviewHandler;
    private final ReviewPolicy reviewPolicy;

    private ExecutionContext(
            MemoryContext memoryContext,
            boolean verbose,
            List<EnsembleListener> listeners,
            Executor toolExecutor,
            ToolMetrics toolMetrics,
            CostConfiguration costConfiguration,
            CaptureMode captureMode,
            MemoryStore memoryStore,
            ReviewHandler reviewHandler,
            ReviewPolicy reviewPolicy) {
        this.memoryContext = memoryContext;
        this.verbose = verbose;
        this.listeners = listeners;
        this.toolExecutor = toolExecutor;
        this.toolMetrics = toolMetrics;
        this.costConfiguration = costConfiguration;
        this.captureMode = captureMode != null ? captureMode : CaptureMode.OFF;
        this.memoryStore = memoryStore;
        this.reviewHandler = reviewHandler;
        this.reviewPolicy = reviewPolicy != null ? reviewPolicy : ReviewPolicy.NEVER;
    }

    // ========================
    // Factory methods
    // ========================

    /**
     * Create an ExecutionContext with all fields specified, including capture mode and
     * memory store. This is the primary factory used by {@code Ensemble}.
     *
     * @param memoryContext     runtime memory state for this run; must not be null
     * @param verbose           when true, elevates execution logging to INFO level
     * @param listeners         event listeners to notify; must not be null
     * @param toolExecutor      executor for parallel tool calls; must not be null
     * @param toolMetrics       metrics backend for tool execution; must not be null
     * @param costConfiguration optional per-token cost rates; may be {@code null}
     * @param captureMode       depth of data collection; defaults to {@link CaptureMode#OFF}
     *                          when {@code null}
     * @param memoryStore       optional scoped memory store; may be {@code null}
     * @return a new ExecutionContext
     */
    public static ExecutionContext of(
            MemoryContext memoryContext,
            boolean verbose,
            List<EnsembleListener> listeners,
            Executor toolExecutor,
            ToolMetrics toolMetrics,
            CostConfiguration costConfiguration,
            CaptureMode captureMode,
            MemoryStore memoryStore) {
        return of(
                memoryContext,
                verbose,
                listeners,
                toolExecutor,
                toolMetrics,
                costConfiguration,
                captureMode,
                memoryStore,
                null,
                null);
    }

    /**
     * Create an ExecutionContext with all fields including review handler and policy.
     *
     * <p>This is the primary factory used by {@code Ensemble} when a review handler is
     * configured.
     *
     * @param memoryContext     runtime memory state for this run; must not be null
     * @param verbose           when true, elevates execution logging to INFO level
     * @param listeners         event listeners to notify; must not be null
     * @param toolExecutor      executor for parallel tool calls; must not be null
     * @param toolMetrics       metrics backend for tool execution; must not be null
     * @param costConfiguration optional per-token cost rates; may be {@code null}
     * @param captureMode       depth of data collection; defaults to {@link CaptureMode#OFF}
     * @param memoryStore       optional scoped memory store; may be {@code null}
     * @param reviewHandler     optional review handler for human-in-the-loop gates; may be {@code null}
     * @param reviewPolicy      ensemble-level review policy; defaults to {@link ReviewPolicy#NEVER}
     * @return a new ExecutionContext
     */
    public static ExecutionContext of(
            MemoryContext memoryContext,
            boolean verbose,
            List<EnsembleListener> listeners,
            Executor toolExecutor,
            ToolMetrics toolMetrics,
            CostConfiguration costConfiguration,
            CaptureMode captureMode,
            MemoryStore memoryStore,
            ReviewHandler reviewHandler,
            ReviewPolicy reviewPolicy) {
        if (memoryContext == null) {
            throw new IllegalArgumentException("memoryContext must not be null");
        }
        if (listeners == null) {
            throw new IllegalArgumentException("listeners must not be null");
        }
        if (toolExecutor == null) {
            throw new IllegalArgumentException("toolExecutor must not be null");
        }
        if (toolMetrics == null) {
            throw new IllegalArgumentException("toolMetrics must not be null");
        }
        return new ExecutionContext(
                memoryContext,
                verbose,
                List.copyOf(listeners),
                toolExecutor,
                toolMetrics,
                costConfiguration,
                captureMode,
                memoryStore,
                reviewHandler,
                reviewPolicy);
    }

    /**
     * Create an ExecutionContext with all fields specified, using {@link CaptureMode#OFF}.
     *
     * @param memoryContext     runtime memory state for this run; must not be null
     * @param verbose           when true, elevates execution logging to INFO level
     * @param listeners         event listeners to notify; must not be null
     * @param toolExecutor      executor for parallel tool calls; must not be null
     * @param toolMetrics       metrics backend for tool execution; must not be null
     * @param costConfiguration optional per-token cost rates; may be {@code null}
     * @return a new ExecutionContext
     */
    public static ExecutionContext of(
            MemoryContext memoryContext,
            boolean verbose,
            List<EnsembleListener> listeners,
            Executor toolExecutor,
            ToolMetrics toolMetrics,
            CostConfiguration costConfiguration,
            CaptureMode captureMode) {
        return of(memoryContext, verbose, listeners, toolExecutor, toolMetrics, costConfiguration, captureMode, null);
    }

    public static ExecutionContext of(
            MemoryContext memoryContext,
            boolean verbose,
            List<EnsembleListener> listeners,
            Executor toolExecutor,
            ToolMetrics toolMetrics,
            CostConfiguration costConfiguration) {
        return of(
                memoryContext, verbose, listeners, toolExecutor, toolMetrics, costConfiguration, CaptureMode.OFF, null);
    }

    /**
     * Create an ExecutionContext with all fields except cost configuration.
     *
     * <p>Cost estimation will be disabled ({@link #costConfiguration()} returns {@code null}).
     *
     * @param memoryContext runtime memory state for this run; must not be null
     * @param verbose       when true, elevates execution logging to INFO level
     * @param listeners     event listeners to notify; must not be null
     * @param toolExecutor  executor for parallel tool calls; must not be null
     * @param toolMetrics   metrics backend for tool execution; must not be null
     * @return a new ExecutionContext
     */
    public static ExecutionContext of(
            MemoryContext memoryContext,
            boolean verbose,
            List<EnsembleListener> listeners,
            Executor toolExecutor,
            ToolMetrics toolMetrics) {
        return of(memoryContext, verbose, listeners, toolExecutor, toolMetrics, null, CaptureMode.OFF);
    }

    /**
     * Create an ExecutionContext with the default tool executor (virtual threads)
     * and no-op tool metrics.
     *
     * @param memoryContext runtime memory state for this run; must not be null
     * @param verbose       when true, elevates execution logging to INFO level
     * @param listeners     event listeners to notify; must not be null
     * @return a new ExecutionContext
     */
    public static ExecutionContext of(MemoryContext memoryContext, boolean verbose, List<EnsembleListener> listeners) {
        return of(
                memoryContext,
                verbose,
                listeners,
                Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                null);
    }

    /**
     * Create an ExecutionContext with no event listeners, the default tool executor,
     * and no-op tool metrics.
     *
     * @param memoryContext runtime memory state for this run; must not be null
     * @param verbose       when true, elevates execution logging to INFO level
     * @return a new ExecutionContext with an empty listeners list
     */
    public static ExecutionContext of(MemoryContext memoryContext, boolean verbose) {
        return of(memoryContext, verbose, List.of());
    }

    /**
     * Create an ExecutionContext with disabled memory, verbose=false, no listeners,
     * default virtual-thread executor, no-op metrics, and {@link CaptureMode#OFF}.
     *
     * <p>Suitable for backward-compatible use in tests and delegation tools where the
     * full context is not available or not needed.
     *
     * @return a new disabled ExecutionContext
     */
    public static ExecutionContext disabled() {
        return new ExecutionContext(
                MemoryContext.disabled(),
                false,
                List.of(),
                Executors.newVirtualThreadPerTaskExecutor(),
                NoOpToolMetrics.INSTANCE,
                null,
                CaptureMode.OFF,
                null,
                null,
                null);
    }

    // ========================
    // Accessors
    // ========================

    /**
     * @return the memory context for this run; never null
     */
    public MemoryContext memoryContext() {
        return memoryContext;
    }

    /**
     * @return true if verbose logging is enabled for this run
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * @return the immutable list of registered event listeners; never null
     */
    public List<EnsembleListener> listeners() {
        return listeners;
    }

    /**
     * The executor used to parallelize concurrent tool calls within a single LLM turn.
     * Default is a virtual-thread-per-task executor.
     *
     * @return the tool executor; never null
     */
    public Executor toolExecutor() {
        return toolExecutor;
    }

    /**
     * The metrics backend for recording tool execution measurements.
     * Default is {@link NoOpToolMetrics#INSTANCE}.
     *
     * @return the tool metrics; never null
     */
    public ToolMetrics toolMetrics() {
        return toolMetrics;
    }

    /**
     * Optional per-token cost rates for cost estimation.
     * When non-null, {@link net.agentensemble.trace.internal.TaskTraceAccumulator}
     * computes a {@link net.agentensemble.metrics.CostEstimate} for each task.
     *
     * @return the cost configuration, or {@code null} when cost estimation is disabled
     */
    public CostConfiguration costConfiguration() {
        return costConfiguration;
    }

    /**
     * The active capture mode for this run.
     *
     * <p>Controls the depth of data collection by {@link net.agentensemble.agent.AgentExecutor}:
     * {@link CaptureMode#OFF} captures only the base trace; {@link CaptureMode#STANDARD} also
     * captures full LLM message history per iteration and memory operation counts;
     * {@link CaptureMode#FULL} additionally enriches tool I/O with parsed JSON arguments.
     *
     * @return the capture mode; never null, defaults to {@link CaptureMode#OFF}
     */
    public CaptureMode captureMode() {
        return captureMode;
    }

    /**
     * The optional memory store for task-scoped cross-execution memory.
     *
     * <p>When non-null, tasks with declared memory scopes read from and write to
     * this store during execution. The {@link net.agentensemble.agent.AgentExecutor}
     * retrieves scope entries before execution and stores task output after completion.
     *
     * @return the memory store, or {@code null} when scoped memory is not configured
     */
    public MemoryStore memoryStore() {
        return memoryStore;
    }

    /**
     * The optional review handler for human-in-the-loop gates.
     *
     * <p>When non-null, the workflow executor fires review gates at the configured
     * timing points (before execution, after execution) and the {@code HumanInputTool}
     * can pause the ReAct loop to collect human input.
     *
     * @return the review handler, or {@code null} when review gates are not configured
     */
    public ReviewHandler reviewHandler() {
        return reviewHandler;
    }

    /**
     * The ensemble-level review policy controlling when after-execution review gates fire.
     *
     * @return the review policy; never null, defaults to {@link ReviewPolicy#NEVER}
     */
    public ReviewPolicy reviewPolicy() {
        return reviewPolicy;
    }

    // ========================
    // Event dispatch
    // ========================

    /**
     * Fire a {@link TaskStartEvent} to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged. All listeners
     * are called regardless of whether a previous listener threw.
     *
     * @param event the event to fire; must not be null
     */
    public void fireTaskStart(TaskStartEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskStart(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onTaskStart: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link TaskCompleteEvent} to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireTaskComplete(TaskCompleteEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskComplete(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onTaskComplete: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link TaskFailedEvent} to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireTaskFailed(TaskFailedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskFailed(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onTaskFailed: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link ToolCallEvent} to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireToolCall(ToolCallEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onToolCall(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onToolCall: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link DelegationStartedEvent} to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireDelegationStarted(DelegationStartedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onDelegationStarted(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onDelegationStarted: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link DelegationCompletedEvent} to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireDelegationCompleted(DelegationCompletedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onDelegationCompleted(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onDelegationCompleted: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Fire a {@link DelegationFailedEvent} to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireDelegationFailed(DelegationFailedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onDelegationFailed(event);
            } catch (Exception e) {
                log.warn("EnsembleListener threw exception in onDelegationFailed: {}", e.getMessage(), e);
            }
        }
    }
}
