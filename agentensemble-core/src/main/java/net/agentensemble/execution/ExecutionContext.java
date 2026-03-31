package net.agentensemble.execution;

import dev.langchain4j.model.chat.StreamingChatModel;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.FileChangedEvent;
import net.agentensemble.callback.LlmIterationCompletedEvent;
import net.agentensemble.callback.LlmIterationStartedEvent;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskInputEvent;
import net.agentensemble.callback.TaskReflectedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.TokenEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.directive.DirectiveStore;
import net.agentensemble.format.ContextFormat;
import net.agentensemble.format.ContextFormatter;
import net.agentensemble.format.ContextFormatters;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.reflection.ReflectionStore;
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
    private final StreamingChatModel streamingChatModel;
    private final ReflectionStore reflectionStore;
    private final ContextFormatter contextFormatter;
    private final DirectiveStore directiveStore;
    private final int currentTaskIndex;

    /**
     * Maximum characters of tool output sent to the LLM. {@code -1} means unlimited.
     *
     * @see #maxToolOutputLength()
     */
    private final int maxToolOutputLength;

    /**
     * Maximum characters of tool output written to log statements. {@code -1} means unlimited.
     *
     * @see #toolLogTruncateLength()
     */
    private final int toolLogTruncateLength;

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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel,
            ReflectionStore reflectionStore,
            ContextFormatter contextFormatter,
            DirectiveStore directiveStore) {
        this(
                memoryContext,
                verbose,
                listeners,
                toolExecutor,
                toolMetrics,
                costConfiguration,
                captureMode,
                memoryStore,
                reviewHandler,
                reviewPolicy,
                streamingChatModel,
                reflectionStore,
                contextFormatter,
                directiveStore,
                0);
    }

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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel,
            ReflectionStore reflectionStore,
            ContextFormatter contextFormatter,
            DirectiveStore directiveStore,
            int currentTaskIndex) {
        this(
                memoryContext,
                verbose,
                listeners,
                toolExecutor,
                toolMetrics,
                costConfiguration,
                captureMode,
                memoryStore,
                reviewHandler,
                reviewPolicy,
                streamingChatModel,
                reflectionStore,
                contextFormatter,
                directiveStore,
                currentTaskIndex,
                -1,
                200);
    }

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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel,
            ReflectionStore reflectionStore,
            ContextFormatter contextFormatter,
            DirectiveStore directiveStore,
            int currentTaskIndex,
            int maxToolOutputLength,
            int toolLogTruncateLength) {
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
        this.streamingChatModel = streamingChatModel;
        this.reflectionStore = reflectionStore;
        this.contextFormatter =
                contextFormatter != null ? contextFormatter : ContextFormatters.forFormat(ContextFormat.JSON);
        this.directiveStore = directiveStore;
        this.currentTaskIndex = currentTaskIndex;
        this.maxToolOutputLength = maxToolOutputLength;
        this.toolLogTruncateLength = toolLogTruncateLength;
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
                reviewPolicy,
                null,
                null,
                null,
                null);
    }

    /**
     * Create an ExecutionContext with all fields including review handler, policy, and an
     * optional ensemble-level streaming chat model for token-by-token streaming.
     *
     * <p>This is the primary factory used by {@code Ensemble} when streaming is configured.
     *
     * @param memoryContext        runtime memory state for this run; must not be null
     * @param verbose              when true, elevates execution logging to INFO level
     * @param listeners            event listeners to notify; must not be null
     * @param toolExecutor         executor for parallel tool calls; must not be null
     * @param toolMetrics          metrics backend for tool execution; must not be null
     * @param costConfiguration    optional per-token cost rates; may be {@code null}
     * @param captureMode          depth of data collection; defaults to {@link CaptureMode#OFF}
     * @param memoryStore          optional scoped memory store; may be {@code null}
     * @param reviewHandler        optional review handler; may be {@code null}
     * @param reviewPolicy         ensemble-level review policy; defaults to {@link ReviewPolicy#NEVER}
     * @param streamingChatModel   optional streaming model for token-by-token final responses;
     *                             may be {@code null} (disables streaming for agents that have no
     *                             agent-level or task-level streaming model configured)
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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel) {
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
                reviewPolicy,
                streamingChatModel,
                null,
                null,
                null);
    }

    /**
     * Create an ExecutionContext with all fields including review handler, policy, streaming
     * model, and an optional reflection store for task reflection.
     *
     * <p>This is the primary factory used by {@code Ensemble} when reflection is configured.
     *
     * @param memoryContext      runtime memory state for this run; must not be null
     * @param verbose            when true, elevates execution logging to INFO level
     * @param listeners          event listeners to notify; must not be null
     * @param toolExecutor       executor for parallel tool calls; must not be null
     * @param toolMetrics        metrics backend for tool execution; must not be null
     * @param costConfiguration  optional per-token cost rates; may be {@code null}
     * @param captureMode        depth of data collection; defaults to {@link CaptureMode#OFF}
     * @param memoryStore        optional scoped memory store; may be {@code null}
     * @param reviewHandler      optional review handler; may be {@code null}
     * @param reviewPolicy       ensemble-level review policy; defaults to {@link ReviewPolicy#NEVER}
     * @param streamingChatModel optional streaming model; may be {@code null}
     * @param reflectionStore    optional reflection store; may be {@code null}
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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel,
            ReflectionStore reflectionStore) {
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
                reviewPolicy,
                streamingChatModel,
                reflectionStore,
                null,
                null);
    }

    /**
     * Create an ExecutionContext with all fields including reflection store and context formatter.
     *
     * <p>This is the primary factory used by {@code Ensemble} when TOON format is configured.
     *
     * @param memoryContext      runtime memory state for this run; must not be null
     * @param verbose            when true, elevates execution logging to INFO level
     * @param listeners          event listeners to notify; must not be null
     * @param toolExecutor       executor for parallel tool calls; must not be null
     * @param toolMetrics        metrics backend for tool execution; must not be null
     * @param costConfiguration  optional per-token cost rates; may be {@code null}
     * @param captureMode        depth of data collection; defaults to {@link CaptureMode#OFF}
     * @param memoryStore        optional scoped memory store; may be {@code null}
     * @param reviewHandler      optional review handler; may be {@code null}
     * @param reviewPolicy       ensemble-level review policy; defaults to {@link ReviewPolicy#NEVER}
     * @param streamingChatModel optional streaming model; may be {@code null}
     * @param reflectionStore    optional reflection store; may be {@code null}
     * @param contextFormatter   optional context formatter; defaults to JSON when {@code null}
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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel,
            ReflectionStore reflectionStore,
            ContextFormatter contextFormatter) {
        return of(
                memoryContext,
                verbose,
                listeners,
                toolExecutor,
                toolMetrics,
                costConfiguration,
                captureMode,
                memoryStore,
                reviewHandler,
                reviewPolicy,
                streamingChatModel,
                reflectionStore,
                contextFormatter,
                null);
    }

    /**
     * Create an ExecutionContext with all fields including directive store.
     *
     * <p>This is the primary factory used by {@code Ensemble} when directives are configured.
     *
     * @param memoryContext      runtime memory state for this run; must not be null
     * @param verbose            when true, elevates execution logging to INFO level
     * @param listeners          event listeners to notify; must not be null
     * @param toolExecutor       executor for parallel tool calls; must not be null
     * @param toolMetrics        metrics backend for tool execution; must not be null
     * @param costConfiguration  optional per-token cost rates; may be {@code null}
     * @param captureMode        depth of data collection; defaults to {@link CaptureMode#OFF}
     * @param memoryStore        optional scoped memory store; may be {@code null}
     * @param reviewHandler      optional review handler; may be {@code null}
     * @param reviewPolicy       ensemble-level review policy; defaults to {@link ReviewPolicy#NEVER}
     * @param streamingChatModel optional streaming model; may be {@code null}
     * @param reflectionStore    optional reflection store; may be {@code null}
     * @param contextFormatter   optional context formatter; defaults to JSON when {@code null}
     * @param directiveStore     optional directive store for human directives; may be {@code null}
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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel,
            ReflectionStore reflectionStore,
            ContextFormatter contextFormatter,
            DirectiveStore directiveStore) {
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
                reviewPolicy,
                streamingChatModel,
                reflectionStore,
                contextFormatter,
                directiveStore);
    }

    /**
     * Create an ExecutionContext with all fields including directive store and tool output
     * length controls.
     *
     * <p>This is the primary factory used by {@code Ensemble} when
     * {@link net.agentensemble.execution.RunOptions} overrides are applied.
     *
     * @param memoryContext          runtime memory state for this run; must not be null
     * @param verbose                when true, elevates execution logging to INFO level
     * @param listeners              event listeners to notify; must not be null
     * @param toolExecutor           executor for parallel tool calls; must not be null
     * @param toolMetrics            metrics backend for tool execution; must not be null
     * @param costConfiguration      optional per-token cost rates; may be {@code null}
     * @param captureMode            depth of data collection; defaults to {@link CaptureMode#OFF}
     * @param memoryStore            optional scoped memory store; may be {@code null}
     * @param reviewHandler          optional review handler; may be {@code null}
     * @param reviewPolicy           ensemble-level review policy; defaults to {@link ReviewPolicy#NEVER}
     * @param streamingChatModel     optional streaming model; may be {@code null}
     * @param reflectionStore        optional reflection store; may be {@code null}
     * @param contextFormatter       optional context formatter; defaults to JSON when {@code null}
     * @param directiveStore         optional directive store for human directives; may be {@code null}
     * @param maxToolOutputLength    max chars of tool output sent to the LLM; {@code -1} = unlimited
     * @param toolLogTruncateLength  max chars of tool output written to logs; {@code -1} = unlimited
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
            ReviewPolicy reviewPolicy,
            StreamingChatModel streamingChatModel,
            ReflectionStore reflectionStore,
            ContextFormatter contextFormatter,
            DirectiveStore directiveStore,
            int maxToolOutputLength,
            int toolLogTruncateLength) {
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
                reviewPolicy,
                streamingChatModel,
                reflectionStore,
                contextFormatter,
                directiveStore,
                0,
                maxToolOutputLength,
                toolLogTruncateLength);
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
                null,
                null,
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

    /**
     * The optional ensemble-level streaming chat model for token-by-token final responses.
     *
     * <p>Used by {@link net.agentensemble.agent.AgentExecutor} as the lowest-priority fallback
     * in the streaming model resolution chain:
     * {@code Agent.streamingLlm} &gt; {@code Task.streamingChatLanguageModel} &gt; this value.
     *
     * @return the ensemble-level streaming model, or {@code null} when not configured
     */
    public StreamingChatModel streamingChatModel() {
        return streamingChatModel;
    }

    /**
     * The optional reflection store for persisting and retrieving task reflections.
     *
     * <p>When non-null, tasks with {@code .reflect(true)} or {@code .reflect(ReflectionConfig)}
     * configured will have their post-execution reflection analysis stored here and retrieved
     * on subsequent runs to inject improvement notes into the prompt.
     *
     * @return the reflection store, or {@code null} when reflection is not configured
     */
    public ReflectionStore reflectionStore() {
        return reflectionStore;
    }

    /**
     * The context formatter used to serialize structured data in LLM prompts.
     *
     * <p>Defaults to {@link ContextFormat#JSON} when not explicitly configured.
     * When {@link ContextFormat#TOON} is selected on the ensemble builder, this
     * returns a TOON formatter that encodes data using the JToon library.
     *
     * @return the context formatter; never null
     */
    public ContextFormatter contextFormatter() {
        return contextFormatter;
    }

    /**
     * The optional directive store for human-injected directives.
     *
     * <p>When non-null, active context directives are injected into the agent prompt
     * as an {@code ## Active Directives} section. The store is shared across all tasks
     * in a single ensemble run.
     *
     * @return the directive store, or {@code null} when directives are not configured
     */
    public DirectiveStore directiveStore() {
        return directiveStore;
    }

    /**
     * The 1-based index of the task currently being executed, or {@code 0} when unknown.
     *
     * <p>Set by workflow executors via {@link #withTaskIndex(int)} before passing the
     * context to {@link net.agentensemble.agent.AgentExecutor}. Used to populate
     * {@link ToolCallEvent#taskIndex()} so tool calls can be correlated to tasks.
     *
     * @return the current task index (1-based), or 0 when not set
     */
    public int currentTaskIndex() {
        return currentTaskIndex;
    }

    /**
     * Maximum characters of tool output sent to the LLM. {@code -1} means unlimited.
     *
     * <p>When positive, tool results are truncated to this length before being added to the
     * LLM message history. The full result is still stored in the trace and fired to listeners.
     *
     * @return the limit, or {@code -1} when output is passed through unchanged
     */
    public int maxToolOutputLength() {
        return maxToolOutputLength;
    }

    /**
     * Maximum characters of tool output emitted to log statements. {@code -1} means unlimited;
     * {@code 0} suppresses output content from logs entirely.
     *
     * @return the limit, or {@code -1} when log output is not truncated
     */
    public int toolLogTruncateLength() {
        return toolLogTruncateLength;
    }

    /**
     * Return a copy of this context with the given task index. All other fields are
     * shared with the original (listeners, executor, metrics, etc. are the same objects).
     *
     * @param taskIndex the 1-based task index to set
     * @return a new ExecutionContext with the updated task index
     */
    public ExecutionContext withTaskIndex(int taskIndex) {
        return new ExecutionContext(
                this.memoryContext,
                this.verbose,
                this.listeners,
                this.toolExecutor,
                this.toolMetrics,
                this.costConfiguration,
                this.captureMode,
                this.memoryStore,
                this.reviewHandler,
                this.reviewPolicy,
                this.streamingChatModel,
                this.reflectionStore,
                this.contextFormatter,
                this.directiveStore,
                taskIndex,
                this.maxToolOutputLength,
                this.toolLogTruncateLength);
    }

    // ========================
    // Event dispatch
    // ========================

    /**
     * Fire an ensemble-started event to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param ensembleId unique identifier for this ensemble run
     * @param workflow   the workflow strategy name
     * @param totalTasks total number of tasks in this run
     */
    public void fireEnsembleStarted(String ensembleId, String workflow, int totalTasks) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onEnsembleStarted(ensembleId, workflow, totalTasks);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onEnsembleStarted: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fire an ensemble-completed event to all registered listeners.
     *
     * <p>Exceptions from individual listeners are caught and logged.
     *
     * @param ensembleId    unique identifier for this ensemble run
     * @param totalDuration total elapsed time for the run
     * @param exitReason    the reason the ensemble run exited
     */
    public void fireEnsembleCompleted(String ensembleId, java.time.Duration totalDuration, String exitReason) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onEnsembleCompleted(ensembleId, totalDuration, exitReason);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onEnsembleCompleted: {}", e.getMessage(), e);
                }
            }
        }
    }

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
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onTaskStart: {}", e.getMessage(), e);
                }
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
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onTaskComplete: {}", e.getMessage(), e);
                }
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
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onTaskFailed: {}", e.getMessage(), e);
                }
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
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onToolCall: {}", e.getMessage(), e);
                }
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
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onDelegationStarted: {}", e.getMessage(), e);
                }
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
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onDelegationCompleted: {}", e.getMessage(), e);
                }
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
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onDelegationFailed: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fire a {@link TaskReflectedEvent} to all registered listeners.
     *
     * <p>Called after a task reflection completes and the result is stored. Exceptions
     * from individual listeners are caught and logged.
     *
     * @param event the task reflected event to fire; must not be null
     */
    public void fireTaskReflected(TaskReflectedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskReflected(event);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onTaskReflected: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fire a {@link TokenEvent} to all registered listeners.
     *
     * <p>Called once per token during streaming generation of the final agent response.
     * Exceptions from individual listeners are caught and logged.
     *
     * @param event the token event to fire; must not be null
     */
    public void fireToken(TokenEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onToken(event);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onToken: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fire a {@link LlmIterationStartedEvent} to all registered listeners.
     *
     * <p>Called at the beginning of each ReAct iteration, just before the LLM is called.
     * Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireLlmIterationStarted(LlmIterationStartedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onLlmIterationStarted(event);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onLlmIterationStarted: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fire a {@link LlmIterationCompletedEvent} to all registered listeners.
     *
     * <p>Called after the LLM responds in each ReAct iteration. Exceptions from
     * individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireLlmIterationCompleted(LlmIterationCompletedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onLlmIterationCompleted(event);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onLlmIterationCompleted: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fire a {@link TaskInputEvent} to all registered listeners.
     *
     * <p>Called after task context is assembled but before the first LLM call.
     * Exceptions from individual listeners are caught and logged.
     *
     * @param event the event to fire; must not be null
     */
    public void fireTaskInput(TaskInputEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onTaskInput(event);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onTaskInput: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Fire a {@link FileChangedEvent} to all registered listeners.
     *
     * <p>Called when a coding tool modifies a file in the workspace. Exceptions from
     * individual listeners are caught and logged.
     *
     * @param event the file changed event to fire; must not be null
     */
    public void fireFileChanged(FileChangedEvent event) {
        for (EnsembleListener listener : listeners) {
            try {
                listener.onFileChanged(event);
            } catch (Exception e) {
                if (log.isWarnEnabled()) {
                    log.warn("EnsembleListener threw exception in onFileChanged: {}", e.getMessage(), e);
                }
            }
        }
    }
}
