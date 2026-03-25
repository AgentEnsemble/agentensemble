package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.TokenEvent;
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.config.TemplateResolver;
import net.agentensemble.dashboard.EnsembleDashboard;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.ensemble.EnsembleLifecycleState;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.SharedCapability;
import net.agentensemble.ensemble.SharedCapabilityType;
import net.agentensemble.exception.AgentEnsembleException;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.format.ContextFormat;
import net.agentensemble.format.ContextFormatters;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.ratelimit.RateLimitedChatModel;
import net.agentensemble.reflection.InMemoryReflectionStore;
import net.agentensemble.reflection.ReflectionStore;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewPolicy;
import net.agentensemble.synthesis.AgentSynthesizer;
import net.agentensemble.synthesis.SynthesisContext;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolMetrics;
import net.agentensemble.trace.AgentSummary;
import net.agentensemble.trace.CaptureMode;
import net.agentensemble.trace.ExecutionTrace;
import net.agentensemble.trace.TaskTrace;
import net.agentensemble.trace.export.ExecutionTraceExporter;
import net.agentensemble.trace.export.JsonTraceExporter;
import net.agentensemble.workflow.DefaultManagerPromptStrategy;
import net.agentensemble.workflow.HierarchicalConstraints;
import net.agentensemble.workflow.HierarchicalWorkflowExecutor;
import net.agentensemble.workflow.ManagerPromptStrategy;
import net.agentensemble.workflow.ParallelErrorStrategy;
import net.agentensemble.workflow.ParallelWorkflowExecutor;
import net.agentensemble.workflow.Phase;
import net.agentensemble.workflow.PhaseDagExecutor;
import net.agentensemble.workflow.SequentialWorkflowExecutor;
import net.agentensemble.workflow.Workflow;
import net.agentensemble.workflow.WorkflowExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * An ensemble of tasks executed by synthesized or explicit agents.
 *
 * <p>The ensemble orchestrates task execution according to the configured workflow
 * strategy, passing context between tasks as declared in each task's context list.
 *
 * <p>In the v2 task-first paradigm, agents are optional. When a task has no explicit
 * agent, the framework synthesizes one automatically from the task description using
 * the configured {@link AgentSynthesizer} (default: template-based, no extra LLM call).
 *
 * <p>For zero-ceremony use, prefer the static {@link #run(ChatModel, Task...)} factory:
 * <pre>
 * EnsembleOutput result = Ensemble.run(model,
 *     Task.of("Research AI trends"),
 *     Task.of("Write a summary report"));
 * </pre>
 *
 * <p>For full control, use the builder:
 * <pre>
 * EnsembleOutput result = Ensemble.builder()
 *     .chatLanguageModel(model)
 *     .task(researchTask)
 *     .task(writeTask)
 *     .workflow(Workflow.SEQUENTIAL)
 *     .build()
 *     .run();
 * </pre>
 *
 * <p>Explicit agents (power-user escape hatch) are declared on the task itself:
 * <pre>
 * Task task = Task.builder()
 *     .description("Research AI trends")
 *     .expectedOutput("A report")
 *     .agent(researcherAgent)
 *     .build();
 * </pre>
 */
@Builder
@Getter
public class Ensemble {

    private static final Logger log = LoggerFactory.getLogger(Ensemble.class);

    /** All tasks to execute. */
    @Singular
    private final List<Task> tasks;

    /**
     * Named task-group workstreams with a dependency DAG.
     *
     * <p>Independent phases run in parallel; a phase starts only when all phases declared in
     * its {@code after()} list have completed. Cannot be combined with {@code tasks} -- use
     * one style per ensemble.
     *
     * <p>Default: empty (flat task list is used instead).
     *
     * <p>Not annotated with {@code @Singular} or {@code @Builder.Default} because we need to
     * provide both {@code phase(Phase)} and {@code phase(String, Task...)} methods in the custom
     * {@code EnsembleBuilder}; Lombok annotations would conflict with those names. The custom
     * {@code phase()} methods each produce an immutable snapshot so that the list assigned to
     * this field by Lombok's generated {@code build()} is already immutable.
     */
    private final List<Phase> phases;

    /**
     * Default LLM for all tasks that do not carry their own {@code chatLanguageModel}
     * or explicit {@code agent}.
     *
     * <p>When a task is agentless (no explicit agent) and has no task-level
     * {@code chatLanguageModel}, this model is used to build the synthesized agent.
     * For hierarchical workflow, this is also used as the Manager's LLM if
     * {@link #managerLlm} is not set.
     *
     * <p>Default: null.
     */
    private final ChatModel chatLanguageModel;

    /**
     * Strategy for synthesizing agents for tasks that have no explicit agent.
     *
     * <p>The default ({@link AgentSynthesizer#template()}) derives role, goal, and
     * backstory from the task description using a verb-to-role lookup table. No extra
     * LLM call is made. Use {@link AgentSynthesizer#llmBased()} for higher-quality
     * personas at the cost of one additional LLM call per agentless task.
     *
     * <pre>
     * Ensemble.builder()
     *     .chatLanguageModel(model)
     *     .agentSynthesizer(AgentSynthesizer.llmBased())
     *     .task(Task.of("Research AI trends"))
     *     .build()
     *     .run();
     * </pre>
     *
     * <p>Default: {@link AgentSynthesizer#template()}.
     */
    @Builder.Default
    private final AgentSynthesizer agentSynthesizer = AgentSynthesizer.template();

    /**
     * How tasks are executed.
     *
     * <p>When {@code null} (not set on the builder), the framework infers the workflow
     * from task context declarations:
     * <ul>
     *   <li>If any task declares a {@code context} dependency on another task in this
     *       ensemble, DAG-based parallel execution ({@link Workflow#PARALLEL}) is inferred.</li>
     *   <li>If no task has context dependencies, sequential execution
     *       ({@link Workflow#SEQUENTIAL}) is used as the default.</li>
     * </ul>
     *
     * <p>Setting an explicit value always takes precedence over inference.
     *
     * <p>Default: {@code null} (inferred at run time).
     */
    private final Workflow workflow;

    /**
     * Optional LLM for the Manager agent in hierarchical workflow.
     * If not set, falls back to {@link #chatLanguageModel}, then to the first
     * resolved agent's LLM.
     */
    private final ChatModel managerLlm;

    /**
     * Maximum number of tool call iterations for the Manager agent in hierarchical workflow.
     * Default: 20. Must be greater than zero.
     */
    @Builder.Default
    private final int managerMaxIterations = 20;

    /** When true, elevates execution logging to INFO level. */
    @Builder.Default
    private final boolean verbose = false;

    /**
     * Optional memory store for task-scoped cross-execution memory (v2.0.0).
     *
     * <p>When set, tasks with declared memory scopes (via
     * {@link Task.TaskBuilder#memory(String)}) automatically read from their scopes before
     * execution and write their output into each scope after completion.
     *
     * <p>Default: null (no scoped memory).
     */
    private final MemoryStore memoryStore;

    /**
     * Maximum delegation depth for agent-to-agent delegation.
     * Prevents infinite recursion when agents have {@code allowDelegation = true}.
     * Only relevant when at least one agent has {@code allowDelegation = true}.
     * Default: 3. Must be greater than zero.
     */
    @Builder.Default
    private final int maxDelegationDepth = 3;

    /**
     * Error handling strategy for parallel workflow execution.
     * Only relevant when {@code workflow = Workflow.PARALLEL}.
     * Default: {@link ParallelErrorStrategy#FAIL_FAST}.
     */
    @Builder.Default
    private final ParallelErrorStrategy parallelErrorStrategy = ParallelErrorStrategy.FAIL_FAST;

    /**
     * Strategy for building the system and user prompts of the Manager agent in a hierarchical
     * workflow.
     *
     * <p>The default ({@link DefaultManagerPromptStrategy#DEFAULT}) lists worker agents in the
     * system prompt and tasks in the user prompt. Provide a custom implementation to inject
     * domain-specific context without forking framework internals.
     *
     * <p>Only exercised when {@code workflow = Workflow.HIERARCHICAL}.
     *
     * <p>Default: {@link DefaultManagerPromptStrategy#DEFAULT}.
     */
    @Builder.Default
    private final ManagerPromptStrategy managerPromptStrategy = DefaultManagerPromptStrategy.DEFAULT;

    /**
     * Executor for running tool calls within a single LLM turn.
     *
     * <p>When the LLM requests multiple tools in one response, they are executed
     * concurrently using this executor. The default creates a new virtual thread per
     * tool call (Java 21).
     *
     * <p>Default: virtual-thread-per-task executor.
     */
    @Builder.Default
    private final Executor toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Metrics backend for recording tool execution measurements.
     *
     * <p>Default: {@link NoOpToolMetrics} (no-op).
     */
    @Builder.Default
    private final ToolMetrics toolMetrics = NoOpToolMetrics.INSTANCE;

    /**
     * Event listeners that will be notified of task lifecycle events during execution.
     */
    @Singular
    private final List<EnsembleListener> listeners;

    /**
     * Template variable inputs for task description and expected output substitution.
     */
    @Singular("input")
    private final Map<String, String> inputs;

    /**
     * Optional guardrails for the delegation graph in hierarchical workflow.
     *
     * <p>Default: null (no constraints).
     */
    private final HierarchicalConstraints hierarchicalConstraints;

    /**
     * Delegation policies evaluated before each delegation attempt.
     */
    @Singular("delegationPolicy")
    private final List<DelegationPolicy> delegationPolicies;

    /**
     * Optional per-token cost rates for cost estimation.
     *
     * <p>Default: null (cost estimation disabled).
     */
    private final CostConfiguration costConfiguration;

    /**
     * Optional exporter called at the end of each {@link #run()} invocation.
     *
     * <p>Default: null (no automatic export).
     */
    private final ExecutionTraceExporter traceExporter;

    /**
     * Depth of data collection during each run.
     *
     * <p>Default: {@link CaptureMode#OFF}.
     */
    @Builder.Default
    private final CaptureMode captureMode = CaptureMode.OFF;

    /**
     * Optional review handler for human-in-the-loop review gates.
     *
     * <p>When set, the workflow executor fires review gates at configured timing points
     * (before execution, after execution). The {@code HumanInputTool} also uses this
     * handler to pause the ReAct loop during execution.
     *
     * <p>Default: null (no review gates fire).
     */
    private final ReviewHandler reviewHandler;

    /**
     * Ensemble-level policy that controls when after-execution review gates fire.
     *
     * <p>Task-level {@link net.agentensemble.review.Review#required()} overrides this to
     * always fire. Task-level {@link net.agentensemble.review.Review#skip()} overrides to
     * never fire.
     *
     * <p>Default: null (treated as {@code ReviewPolicy.NEVER} by the execution engine).
     */
    private final ReviewPolicy reviewPolicy;

    /**
     * Optional store for persisting task reflections across separate {@link #run()} invocations.
     *
     * <p>When set, tasks with {@code .reflect(true)} or {@code .reflect(ReflectionConfig)}
     * configured will have their post-execution reflection analysis stored here and retrieved
     * on subsequent runs to inject improvement notes into the prompt — creating a self-optimizing
     * prompt loop without changing the compile-time task definition.
     *
     * <p>The framework ships {@link net.agentensemble.reflection.InMemoryReflectionStore} as the
     * default (if a task has reflection enabled but no store is configured here, a warn is logged
     * and an ephemeral in-memory store is used). For persistence across JVM restarts, provide a
     * custom implementation backed by a database, SQLite, or REST API.
     *
     * <p>Default: null (ephemeral in-memory fallback when reflection is enabled on any task).
     */
    private final ReflectionStore reflectionStore;

    /**
     * Serialization format for structured data in LLM prompts.
     *
     * <p>When set to {@link ContextFormat#TOON}, context from prior tasks, tool results,
     * and memory entries are encoded in TOON format (30-60% token reduction vs JSON).
     * Requires {@code dev.toonformat:jtoon} on the runtime classpath.
     *
     * <p>Default: null (treated as {@link ContextFormat#JSON}).
     */
    private final ContextFormat contextFormat;

    /**
     * Optional live execution dashboard registered via
     * {@link EnsembleBuilder#webDashboard(EnsembleDashboard)}.
     *
     * <p>When non-null, {@link #runWithInputs} notifies the dashboard at the beginning
     * and end of each run so it can broadcast {@code ensemble_started} and
     * {@code ensemble_completed} wire-protocol messages.
     *
     * <p>Default: null.
     */
    private final EnsembleDashboard dashboard;

    /**
     * Controls whether this ensemble owns the dashboard lifecycle (start and stop).
     *
     * <p>Set to {@code true} (the default) when the dashboard was not yet running when
     * {@link EnsembleBuilder#webDashboard(EnsembleDashboard)} was called -- meaning the
     * ensemble started it and is responsible for stopping it in the {@code finally} block.
     *
     * <p>Set to {@code false} when the dashboard was already running at registration time,
     * indicating that the caller started it externally and retains lifecycle ownership.
     * In this case, {@link #runWithInputs} skips both the auto-start and auto-stop so
     * the server outlives the ensemble run.
     *
     * <p>Default: {@code true} (ensemble owns lifecycle when dashboard field is non-null).
     */
    @Builder.Default
    private final boolean ownsDashboardLifecycle = true;

    /**
     * Optional ensemble-level streaming model for token-by-token generation of final responses.
     *
     * <p>When set, agents that produce a direct LLM answer (no tool loop) stream each token
     * through {@link net.agentensemble.callback.EnsembleListener#onToken(TokenEvent)} and,
     * when using {@link EnsembleBuilder#webDashboard(EnsembleDashboard)}, over the WebSocket wire protocol.
     *
     * <p>Resolution order (first non-null wins):
     * {@code Agent.streamingLlm} &gt; {@code Task.streamingChatLanguageModel} &gt; this value.
     *
     * <p>Default: null (non-streaming).
     */
    private final StreamingChatModel streamingChatLanguageModel;

    /**
     * Ensemble-level request rate limit applied to {@link #chatLanguageModel}.
     *
     * <p>When set, all synthesized agents that inherit the ensemble-level model (i.e. tasks
     * without their own {@code chatLanguageModel} and without an explicit {@code agent})
     * share a single rate-limit token bucket, capping the total number of LLM requests
     * per time window across the entire ensemble run.
     *
     * <p>The {@link #chatLanguageModel} is wrapped with a {@link RateLimitedChatModel}
     * once per run in {@link #runWithInputs(Map)} before agent resolution. All tasks that
     * inherit the ensemble model therefore share the same bucket.
     *
     * <p>Tasks with their own {@code chatLanguageModel} or {@code rateLimit} are not
     * affected by this setting (they have independent buckets).
     *
     * <p>To create a shared bucket that also covers the hierarchical Manager agent,
     * use {@link RateLimitedChatModel#of(ChatModel, RateLimit)} explicitly and pass the
     * result to both {@code chatLanguageModel} and {@code managerLlm}.
     *
     * <p>Default: null (no ensemble-level rate limiting).
     */
    private final RateLimit rateLimit;

    /**
     * Maximum time to wait for in-flight work to complete during graceful shutdown.
     *
     * <p>When {@link #stop()} is called on a long-running ensemble, it transitions to
     * {@link EnsembleLifecycleState#DRAINING} and waits up to this duration for in-flight
     * tasks to finish before transitioning to {@link EnsembleLifecycleState#STOPPED}.
     *
     * <p>Default: 5 minutes.
     *
     * @see #stop()
     * @see EnsembleLifecycleState#DRAINING
     */
    @Builder.Default
    private final Duration drainTimeout = Duration.ofMinutes(5);

    /**
     * Tasks and tools that this ensemble shares with the network.
     *
     * <p>Shared capabilities are published during the capability handshake when peer
     * ensembles connect. Populated via
     * {@link EnsembleBuilder#shareTask(String, Task)} and
     * {@link EnsembleBuilder#shareTool(String, AgentTool)}.
     *
     * <p>Not annotated with {@code @Singular} or {@code @Builder.Default} because we
     * provide custom {@code shareTask()} and {@code shareTool()} methods in the custom
     * {@link EnsembleBuilder}. Managed identically to {@link #phases}.
     *
     * @see SharedCapability
     */
    private final List<SharedCapability> sharedCapabilities;

    /**
     * Internal lifecycle state for long-running mode.
     *
     * <p>Not exposed via the builder; managed internally by {@link #start(int)} and
     * {@link #stop()}. The {@code @Getter(AccessLevel.NONE)} annotation prevents Lombok
     * from generating a getter; use {@link #getLifecycleState()} instead.
     */
    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    private final AtomicReference<EnsembleLifecycleState> lifecycleStateRef = new AtomicReference<>();

    /**
     * Dashboard created dynamically by {@link #start(int)} when no dashboard was
     * configured at build time. Not part of the builder API.
     */
    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    private final AtomicReference<EnsembleDashboard> longRunningDashboard = new AtomicReference<>();

    // ========================
    // Lifecycle (long-running mode)
    // ========================

    /**
     * Returns the current lifecycle state, or {@code null} if the ensemble has never
     * been started (one-shot mode).
     *
     * @return the current lifecycle state, or null for one-shot ensembles
     */
    public EnsembleLifecycleState getLifecycleState() {
        return lifecycleStateRef.get();
    }

    /**
     * Start this ensemble in long-running mode on the given port.
     *
     * <p>The ensemble transitions through {@link EnsembleLifecycleState#STARTING} to
     * {@link EnsembleLifecycleState#READY}. A WebSocket server is bound to the specified
     * port, and shared capabilities are published for peer discovery.
     *
     * <p>A dashboard must be configured at build time via
     * {@link EnsembleBuilder#webDashboard(EnsembleDashboard)}.
     * If no dashboard is configured, {@code start()} throws {@link IllegalStateException}
     * with guidance on how to add one.
     *
     * <p>Calling {@code start()} on an already-started ensemble (state is {@code STARTING}
     * or {@code READY}) is a no-op (idempotent).
     *
     * <p>A JVM shutdown hook is registered to trigger {@link #stop()} on SIGTERM or
     * normal shutdown.
     *
     * @param port the port to bind the WebSocket server to
     * @throws ValidationException     if the ensemble configuration is invalid
     * @throws AgentEnsembleException  if the server cannot be started
     */
    public void start(int port) {
        EnsembleLifecycleState current = lifecycleStateRef.get();
        if (current == EnsembleLifecycleState.STARTING || current == EnsembleLifecycleState.READY) {
            return; // idempotent
        }

        new EnsembleValidator(this).validate();
        lifecycleStateRef.set(EnsembleLifecycleState.STARTING);

        try {
            if (dashboard == null) {
                throw new IllegalStateException("Ensemble.start(int) requires a dashboard. Configure one via "
                        + "Ensemble.builder().webDashboard(WebDashboard.builder().port("
                        + port
                        + ").build()).");
            }
            if (!dashboard.isRunning()) {
                dashboard.start();
            }

            lifecycleStateRef.set(EnsembleLifecycleState.READY);
            log.info("Ensemble started in long-running mode on port {}", port);

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                if (getLifecycleState() == EnsembleLifecycleState.READY) {
                                    stop();
                                }
                            },
                            "ensemble-shutdown-hook"));
        } catch (IllegalStateException e) {
            lifecycleStateRef.set(EnsembleLifecycleState.STOPPED);
            throw e;
        } catch (Exception e) {
            lifecycleStateRef.set(EnsembleLifecycleState.STOPPED);
            throw new AgentEnsembleException("Failed to start ensemble on port " + port, e);
        }
    }

    /**
     * Initiate graceful shutdown of a long-running ensemble.
     *
     * <p>The ensemble transitions to {@link EnsembleLifecycleState#DRAINING}, waits up
     * to {@link #drainTimeout} for in-flight work to complete, then transitions to
     * {@link EnsembleLifecycleState#STOPPED}.
     *
     * <p>Calling {@code stop()} on an already-stopped or never-started ensemble is a
     * no-op (idempotent).
     */
    public void stop() {
        EnsembleLifecycleState current = lifecycleStateRef.get();
        if (current == null
                || current == EnsembleLifecycleState.DRAINING
                || current == EnsembleLifecycleState.STOPPED) {
            return; // idempotent
        }

        lifecycleStateRef.set(EnsembleLifecycleState.DRAINING);
        log.info("Ensemble draining (timeout: {})", drainTimeout);

        try {
            // Stop whichever dashboard is in use (pre-configured or auto-created)
            EnsembleDashboard dash = longRunningDashboard.get();
            if (dash == null) {
                dash = this.dashboard;
            }
            if (dash != null && dash.isRunning()) {
                dash.stop();
            }
        } finally {
            lifecycleStateRef.set(EnsembleLifecycleState.STOPPED);
            log.info("Ensemble stopped");
        }
    }

    // ========================
    // Static zero-ceremony factory
    // ========================

    /**
     * Zero-ceremony static factory: create an ensemble with the given LLM and tasks,
     * then run it immediately with a sequential workflow.
     *
     * <p>Agents are synthesized automatically from the task descriptions. This is the
     * simplest possible way to use AgentEnsemble:
     *
     * <pre>
     * EnsembleOutput result = Ensemble.run(model,
     *     Task.of("Research AI trends"),
     *     Task.of("Write a summary report based on the research"));
     * </pre>
     *
     * @param model the LLM to use for all synthesized agents; must not be null
     * @param tasks the tasks to execute in order; must not be empty
     * @return the execution output
     */
    public static EnsembleOutput run(ChatModel model, Task... tasks) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (tasks == null || tasks.length == 0) {
            throw new IllegalArgumentException("tasks must not be null or empty");
        }
        EnsembleBuilder builder = Ensemble.builder().chatLanguageModel(model);
        for (Task task : tasks) {
            builder.task(task);
        }
        return builder.build().run();
    }

    /**
     * Zero-ceremony static factory for deterministic-only pipelines: run the given handler
     * tasks without any AI model or LLM configuration.
     *
     * <p>Use this to orchestrate deterministic (non-AI) steps such as REST API calls,
     * data transformation, JSON parsing, file processing, or any Java function -- with the
     * same sequential execution, DAG support, guardrails, callbacks, and metrics that
     * AI-backed ensembles provide.
     *
     * <p>The output of one task is accessible to downstream tasks via
     * {@code Task.builder().context(List.of(upstreamTask))} and read inside the handler
     * through {@link net.agentensemble.task.TaskHandlerContext#contextOutputs()}:
     *
     * <pre>
     * Task fetchTask = Task.builder()
     *     .description("Fetch product data from API")
     *     .expectedOutput("JSON response")
     *     .handler(ctx -> ToolResult.success(apiClient.fetchProduct()))
     *     .build();
     *
     * Task transformTask = Task.builder()
     *     .description("Transform product data into display format")
     *     .expectedOutput("Formatted product line")
     *     .context(List.of(fetchTask))
     *     .handler(ctx -> {
     *         String json = ctx.contextOutputs().get(0).getRaw();
     *         return ToolResult.success(format(json));
     *     })
     *     .build();
     *
     * EnsembleOutput result = Ensemble.run(fetchTask, transformTask);
     * </pre>
     *
     * <p>All tasks must have a {@link net.agentensemble.task.TaskHandler} configured. To
     * run AI-backed tasks, use {@link #run(ChatModel, Task...)} or the builder.
     *
     * @param tasks the deterministic tasks to execute; must not be null or empty; all must
     *              have a handler configured
     * @return the execution output
     * @throws IllegalArgumentException if {@code tasks} is null, empty, or any task has no
     *                                  handler -- use {@link #run(ChatModel, Task...)} or the
     *                                  builder with {@code chatLanguageModel(model)} for AI tasks
     */
    public static EnsembleOutput run(Task... tasks) {
        if (tasks == null || tasks.length == 0) {
            throw new IllegalArgumentException("tasks must not be null or empty");
        }
        for (int i = 0; i < tasks.length; i++) {
            if (tasks[i] == null) {
                throw new IllegalArgumentException("tasks[" + i + "] must not be null");
            }
            if (tasks[i].getHandler() == null) {
                throw new IllegalArgumentException("Task '"
                        + tasks[i].getDescription()
                        + "' has no handler configured. "
                        + "Ensemble.run(Task...) requires all tasks to have a handler. "
                        + "Provide a handler via Task.builder().handler(...), or use "
                        + "Ensemble.run(ChatModel, Task...) for AI-backed tasks, or "
                        + "configure chatLanguageModel(model) on the Ensemble builder.");
            }
        }
        EnsembleBuilder builder = Ensemble.builder();
        for (Task task : tasks) {
            builder.task(task);
        }
        return builder.build().run();
    }

    // ========================
    // Run methods
    // ========================

    /**
     * Execute the ensemble's tasks using the inputs configured on the builder.
     *
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run() {
        return runWithInputs(inputs);
    }

    /**
     * Execute the ensemble's tasks, merging the supplied run-time inputs with any inputs
     * configured on the builder. When the same key appears in both, the run-time value
     * takes precedence.
     *
     * @param runtimeInputs additional or overriding variable values
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run(Map<String, String> runtimeInputs) {
        if (runtimeInputs == null || runtimeInputs.isEmpty()) {
            return runWithInputs(inputs);
        }
        Map<String, String> merged = new LinkedHashMap<>(inputs);
        merged.putAll(runtimeInputs);
        return runWithInputs(Collections.unmodifiableMap(merged));
    }

    /**
     * Returns the unique agents participating in this ensemble, derived from the tasks.
     *
     * <p>Agents are collected from tasks that have an explicit agent set, deduplicated
     * by object identity, and returned in task-list order.
     *
     * <p>In the v2 task-first paradigm, agents are synthesized at runtime for agentless
     * tasks and are not included in this list (they are ephemeral).
     *
     * @return an immutable list of unique agents from tasks with explicit agents
     */
    public List<Agent> getAgents() {
        Map<Agent, Boolean> seen = new IdentityHashMap<>();
        List<Agent> result = new ArrayList<>();
        for (Task task : tasks) {
            if (task.getAgent() != null && seen.putIfAbsent(task.getAgent(), Boolean.TRUE) == null) {
                result.add(task.getAgent());
            }
        }
        return List.copyOf(result);
    }

    private EnsembleOutput runWithInputs(Map<String, String> resolvedInputs) {
        String ensembleId = UUID.randomUUID().toString();
        MDC.put("ensemble.id", ensembleId);
        Instant runStartedAt = Instant.now();

        // Resolve the effective capture mode
        CaptureMode effectiveCaptureMode = CaptureMode.resolve(captureMode);
        if (effectiveCaptureMode != CaptureMode.OFF) {
            log.info("CaptureMode active: {}", effectiveCaptureMode);
        }

        try {
            // Auto-start the dashboard at the beginning of each run only when the ensemble
            // owns the lifecycle (i.e. it started the server in webDashboard()). This is
            // idempotent for the first run (already started by the builder). For multiple
            // sequential run() calls it re-starts the server after the previous run's finally
            // block stopped it, ensuring streaming and review gates work on each run.
            // When the caller started the server externally before calling webDashboard(),
            // ownsDashboardLifecycle is false -- we skip start/stop so the server outlives
            // the ensemble run (e.g. the E2E test server holds the process open for Playwright).
            if (dashboard != null && ownsDashboardLifecycle) {
                try {
                    dashboard.start();
                } catch (Exception e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Failed to start dashboard before ensemble run: {}", e.getMessage(), e);
                    }
                }
            }

            if (log.isInfoEnabled()) {
                log.info("Ensemble run initializing | Workflow config: {} | Tasks: {}", workflow, tasks.size());
            }
            log.debug("Input variables: {}", resolvedInputs);

            // Step 1: Validate configuration
            new EnsembleValidator(this).validate();

            // Step 2: Resolve template variables in task descriptions and expected outputs
            List<Task> templateResolvedTasks = resolveTasks(resolvedInputs);

            // Step 2b: Resolve the effective workflow (inference happens after template resolution
            // so that context dependencies reference the correct identity-mapped task instances)
            Workflow effectiveWorkflow = resolveWorkflow(templateResolvedTasks);
            if (workflow == null) {
                log.info("Workflow inferred: {} (from task context declarations)", effectiveWorkflow);
            }

            // Step 2c: Compute the effective ensemble chat model (apply ensemble-level rate limit
            // once so all tasks that inherit the ensemble model share the same token bucket)
            ChatModel effectiveChatModel = buildEffectiveChatModel();

            // Step 3: Resolve agents -- synthesize for tasks without an explicit agent.
            // The raw (unwrapped) chatLanguageModel is also passed so that task-level rate
            // limits can wrap the bare model directly, preventing unintended nesting of the
            // ensemble-level rate limit on top of a task-level one.
            List<Task> agentResolvedTasks = resolveAgents(templateResolvedTasks, effectiveChatModel, chatLanguageModel);

            // Step 4: Derive unique agents (for delegation context, trace, hierarchical)
            List<Agent> derivedAgents = deriveAgents(agentResolvedTasks);

            if (log.isInfoEnabled()) {
                log.info(
                        "Ensemble run started | Workflow: {} | Tasks: {} | Agents: {}",
                        effectiveWorkflow,
                        agentResolvedTasks.size(),
                        derivedAgents.size());
            }

            // Step 5: Memory context is disabled in v2.0.0; scoped memory is handled
            // via MemoryStore in ExecutionContext (set on Ensemble via .memoryStore()).
            MemoryContext memoryContext = MemoryContext.disabled();

            if (memoryStore != null) {
                log.info("MemoryStore enabled for task-scoped memory");
            }

            // Provision a default InMemoryReflectionStore when tasks have reflection enabled
            // but no explicit store is configured. A single instance is used for the entire
            // run so that prior-reflection retrieval works correctly across tasks.
            ReflectionStore effectiveReflectionStore = reflectionStore;
            if (effectiveReflectionStore == null && hasReflectionEnabled(agentResolvedTasks)) {
                effectiveReflectionStore = new InMemoryReflectionStore();
                log.warn("One or more tasks have reflection enabled but no ReflectionStore is configured "
                        + "on the Ensemble. Using an ephemeral InMemoryReflectionStore for this run "
                        + "-- reflections will not persist across JVM restarts. "
                        + "Configure a durable store via Ensemble.builder().reflectionStore(...).");
            }

            // Step 6: Build execution context
            ExecutionContext executionContext = ExecutionContext.of(
                    memoryContext,
                    verbose,
                    listeners != null ? listeners : List.of(),
                    toolExecutor,
                    toolMetrics,
                    costConfiguration,
                    effectiveCaptureMode,
                    memoryStore,
                    reviewHandler,
                    reviewPolicy,
                    streamingChatLanguageModel,
                    effectiveReflectionStore,
                    contextFormat != null ? ContextFormatters.forFormat(contextFormat) : null);

            if (reviewHandler != null) {
                log.info("ReviewHandler enabled | Policy: {}", reviewPolicy);
            }
            if (effectiveReflectionStore != null) {
                if (log.isInfoEnabled()) {
                    log.info(
                            "ReflectionStore enabled for cross-run task reflection | Type: {}",
                            effectiveReflectionStore.getClass().getSimpleName());
                }
            }

            // Step 7: Notify dashboard that execution is about to begin.
            // This fires ensemble_started before the first task runs.
            if (dashboard != null) {
                try {
                    dashboard.onEnsembleStarted(
                            ensembleId, runStartedAt, agentResolvedTasks.size(), effectiveWorkflow.name());
                } catch (Exception e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Dashboard.onEnsembleStarted threw an exception: {}", e.getMessage(), e);
                    }
                }
            }

            // Step 8: Select and execute WorkflowExecutor (flat tasks) or PhaseDagExecutor (phases)
            EnsembleOutput output;
            if (phases != null && !phases.isEmpty()) {
                // Phase-based execution: PhaseDagExecutor handles the DAG; each phase
                // runner resolves template vars and agents independently.
                if (log.isInfoEnabled()) {
                    log.info("Ensemble run using Phase DAG | Phases: {}", phases.size());
                }
                output = executePhases(phases, resolvedInputs, buildEffectiveChatModel(), executionContext);
            } else {
                WorkflowExecutor executor = selectExecutor(effectiveWorkflow, derivedAgents);
                output = executor.execute(agentResolvedTasks, executionContext);
            }

            Instant runCompletedAt = Instant.now();

            if (log.isInfoEnabled()) {
                log.info(
                        "Ensemble run completed | Duration: {} | Tasks: {} | Tool calls: {}",
                        output.getTotalDuration(),
                        output.getTaskOutputs().size(),
                        output.getTotalToolCalls());
            }

            // Step 9: Notify dashboard that the run has completed.
            if (dashboard != null) {
                try {
                    long durationMs = java.time.Duration.between(runStartedAt, runCompletedAt)
                            .toMillis();
                    long totalTokens =
                            output.getMetrics() != null ? output.getMetrics().getTotalTokens() : -1L;
                    String exitReason = output.getExitReason() != null
                            ? output.getExitReason().name()
                            : "COMPLETED";
                    dashboard.onEnsembleCompleted(
                            ensembleId,
                            runCompletedAt,
                            durationMs,
                            exitReason,
                            totalTokens,
                            output.getTotalToolCalls());
                } catch (Exception e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Dashboard.onEnsembleCompleted threw an exception: {}", e.getMessage(), e);
                    }
                }
            }

            // Step 10: Build ExecutionTrace
            ExecutionTrace trace = buildExecutionTrace(
                    ensembleId,
                    runStartedAt,
                    runCompletedAt,
                    resolvedInputs,
                    output,
                    effectiveCaptureMode,
                    effectiveWorkflow,
                    derivedAgents);

            // Step 11: Attach trace to EnsembleOutput, preserving exitReason.
            // Remap the executor's taskOutputIndex (keyed by agent-resolved task instances)
            // back to the original task instances the caller holds, using the positional
            // correspondence: tasks.get(i) -> agentResolvedTasks.get(i).
            Map<Task, TaskOutput> executorIndex = output.getTaskOutputIndex();
            Map<Task, TaskOutput> originalIndex = null;
            if (executorIndex != null) {
                IdentityHashMap<Task, TaskOutput> idx = new IdentityHashMap<>();
                for (int i = 0; i < tasks.size() && i < agentResolvedTasks.size(); i++) {
                    Task original = tasks.get(i);
                    Task agentResolved = agentResolvedTasks.get(i);
                    TaskOutput taskOut = executorIndex.get(agentResolved);
                    if (taskOut != null) {
                        idx.put(original, taskOut);
                    }
                }
                originalIndex = idx;
            }

            EnsembleOutput outputWithTrace = EnsembleOutput.builder()
                    .raw(output.getRaw())
                    .taskOutputs(output.getTaskOutputs())
                    .totalDuration(output.getTotalDuration())
                    .totalToolCalls(output.getTotalToolCalls())
                    .metrics(output.getMetrics())
                    .trace(trace)
                    .exitReason(output.getExitReason())
                    .taskOutputIndex(originalIndex)
                    .phaseOutputs(output.getPhaseOutputs())
                    .build();

            // Step 12: Export trace
            ExecutionTraceExporter effectiveExporter = traceExporter;
            if (effectiveExporter == null && effectiveCaptureMode == CaptureMode.FULL) {
                effectiveExporter = new JsonTraceExporter(java.nio.file.Path.of("./traces/"));
                log.debug("CaptureMode.FULL: auto-registering JsonTraceExporter at ./traces/");
            }
            if (effectiveExporter != null) {
                try {
                    effectiveExporter.export(trace);
                } catch (Exception e) {
                    if (log.isWarnEnabled()) {
                        log.warn("TraceExporter threw exception during export: {}", e.getMessage(), e);
                    }
                }
            }

            return outputWithTrace;

        } catch (ValidationException e) {
            if (log.isWarnEnabled()) {
                log.warn("Ensemble validation failed: {}", e.getMessage());
            }
            throw e;
        } catch (Exception e) {
            log.error("Ensemble run failed", e);
            throw e;
        } finally {
            // Auto-stop the dashboard only when the ensemble owns the lifecycle. Ownership
            // is true when the dashboard was not running at webDashboard() call time (the
            // ensemble started it), and false when the caller started it externally before
            // passing it to webDashboard() (the caller retains lifecycle responsibility).
            // When ownsDashboardLifecycle is false the server stays up after run() returns,
            // allowing external processes (e.g. E2E test server) to keep it alive.
            if (dashboard != null && ownsDashboardLifecycle) {
                try {
                    dashboard.stop();
                } catch (Exception e) {
                    if (log.isWarnEnabled()) {
                        log.warn("Failed to stop dashboard after ensemble run: {}", e.getMessage(), e);
                    }
                }
            }
            MDC.remove("ensemble.id");
        }
    }

    /**
     * Builds the effective ensemble-level chat model, applying the ensemble-level rate limit
     * if configured. The resulting model (possibly wrapped) is created once per run, ensuring
     * all tasks that inherit the ensemble model share the same token bucket.
     */
    private ChatModel buildEffectiveChatModel() {
        if (rateLimit != null && chatLanguageModel != null) {
            return RateLimitedChatModel.of(chatLanguageModel, rateLimit);
        }
        return chatLanguageModel;
    }

    /**
     * Resolve agents for tasks that do not have an explicit agent set.
     *
     * <p>For each task without an agent, the configured {@link AgentSynthesizer} is
     * invoked with the task-level or ensemble-level LLM. Task-level tools and
     * maxIterations are applied to the synthesized agent. Tasks with explicit agents
     * are returned unchanged.
     *
     * <p>The {@code ensembleLlm} parameter is the effective ensemble chat model (already
     * wrapped with a rate limiter if configured at the ensemble level). It is passed in
     * rather than read from {@link #chatLanguageModel} directly so that a single wrapped
     * instance is reused across all tasks, which is required for shared-bucket semantics.
     *
     * <p>Uses a two-pass approach to keep context references consistent after synthesis
     * (fix for issue #148):
     * <ol>
     *   <li>Pass 1: synthesize agents, building an identity map of old task -&gt; new task.</li>
     *   <li>Pass 2: rewrite each task's context list so every reference points to the new
     *       agent-resolved instance. This mirrors the pattern used in
     *       {@link #resolveTasks(Map)} and ensures that the identity-based
     *       {@code completedOutputs} lookup in the workflow executor can find upstream
     *       outputs for context-bearing tasks.</li>
     * </ol>
     *
     * @param templateResolvedTasks  tasks after template variable resolution
     * @param ensembleLlm            the effective ensemble-level chat model (may be rate-limited)
     * @param rawChatLanguageModel   the unwrapped ensemble chat model; used when a task overrides
     *                               with its own {@code rateLimit} so the task-level limiter wraps
     *                               the bare model instead of nesting on the ensemble limiter
     */
    private List<Task> resolveAgents(
            List<Task> templateResolvedTasks, ChatModel ensembleLlm, ChatModel rawChatLanguageModel) {
        // Pass 1: synthesize agents; build old-identity -> new-identity map.
        IdentityHashMap<Task, Task> oldToNew = new IdentityHashMap<>();
        List<Task> firstPass = new ArrayList<>(templateResolvedTasks.size());
        for (Task task : templateResolvedTasks) {
            Task agentResolved;
            if (task.getAgent() != null) {
                // Explicit agent: use as-is (power-user escape hatch)
                agentResolved = task;
            } else if (task.getHandler() != null) {
                // Deterministic task -- no agent synthesis needed.
                // The workflow executor invokes the handler directly, bypassing the LLM.
                agentResolved = task;
                if (log.isDebugEnabled()) {
                    log.debug(
                            "Skipping agent synthesis for deterministic task '{}'",
                            truncate(task.getDescription(), 80));
                }
            } else {
                // LLM resolution order:
                // 1. Task has its own chatLanguageModel (already rate-limited at Task.build() time
                //    if task.rateLimit was also set)
                // 2. Task has no chatLanguageModel but has its own rateLimit: wrap the inherited
                //    ensemble LLM with the task-level rate limit (creates a separate bucket)
                // 3. Task inherits the ensemble LLM (already rate-limited by ensembleLlm)
                ChatModel llm;
                if (task.getChatLanguageModel() != null) {
                    llm = task.getChatLanguageModel();
                } else if (task.getRateLimit() != null) {
                    // Task has a rate limit but no task-level model: wrap the *raw* ensemble model
                    // (bypassing any ensemble-level rate-limit wrapper) so task-level limits truly
                    // replace rather than nest on top of the ensemble-level limit.
                    ChatModel baseModel = rawChatLanguageModel != null ? rawChatLanguageModel : ensembleLlm;
                    if (baseModel == null) {
                        throw new ValidationException("No LLM available for task '" + task.getDescription()
                                + "'. Provide a task-level chatLanguageModel or an ensemble-level chatLanguageModel.");
                    }
                    llm = RateLimitedChatModel.of(baseModel, task.getRateLimit());
                } else {
                    llm = ensembleLlm;
                }
                if (llm == null) {
                    // Should have been caught by EnsembleValidator.validateTasksHaveLlm()
                    throw new ValidationException("No LLM available for task '" + task.getDescription()
                            + "'. Provide a task-level chatLanguageModel or an ensemble-level chatLanguageModel.");
                }

                SynthesisContext ctx = new SynthesisContext(llm, Locale.getDefault());
                Agent synthesized = agentSynthesizer.synthesize(task, ctx);

                // Apply task-level maxIterations and tools to the synthesized agent
                Agent.AgentBuilder agentBuilder = synthesized.toBuilder();
                if (task.getMaxIterations() != null) {
                    agentBuilder.maxIterations(task.getMaxIterations());
                }
                if (!task.getTools().isEmpty()) {
                    agentBuilder.tools(task.getTools());
                }

                agentResolved = task.toBuilder().agent(agentBuilder.build()).build();

                if (log.isDebugEnabled()) {
                    log.debug(
                            "Synthesized agent '{}' for task '{}'",
                            agentResolved.getAgent().getRole(),
                            truncate(task.getDescription(), 80));
                }
            }
            oldToNew.put(task, agentResolved);
            firstPass.add(agentResolved);
        }

        // Pass 2: rewrite context references to point to the agent-resolved task instances.
        // Without this pass, a downstream task's context list still references the old
        // template-resolved task identity. The workflow executor stores outputs under the
        // new identity, so the identity-based completedOutputs.get(contextTask) lookup
        // would return null, triggering a spurious TaskExecutionException (and NPE in its
        // error path) for any agentless task with context dependencies.
        //
        // The map is updated when a new identity is created so that tasks processed later
        // in this same pass (with a context dependency on an already-remapped task) resolve
        // to the correct final identity. This mirrors the resolveTasks() pattern exactly.
        List<Task> result = new ArrayList<>(firstPass.size());
        for (int i = 0; i < firstPass.size(); i++) {
            Task agentResolved = firstPass.get(i);
            List<Task> originalContext = agentResolved.getContext();
            if (originalContext.isEmpty()) {
                result.add(agentResolved);
            } else {
                List<Task> remappedContext = new ArrayList<>(originalContext.size());
                for (Task ctxTask : originalContext) {
                    remappedContext.add(oldToNew.getOrDefault(ctxTask, ctxTask));
                }
                Task finalTask =
                        agentResolved.toBuilder().context(remappedContext).build();
                // Update the map so subsequent tasks in this pass that reference
                // templateResolvedTasks.get(i) will resolve to finalTask's identity.
                oldToNew.put(templateResolvedTasks.get(i), finalTask);
                result.add(finalTask);
            }
        }
        return result;
    }

    /**
     * Derive a list of unique agent instances from the resolved tasks.
     * Agents are deduplicated by object identity and returned in task-list order.
     */
    private static List<Agent> deriveAgents(List<Task> resolvedTasks) {
        IdentityHashMap<Agent, Boolean> seen = new IdentityHashMap<>();
        List<Agent> agents = new ArrayList<>();
        for (Task task : resolvedTasks) {
            if (task.getAgent() != null && seen.putIfAbsent(task.getAgent(), Boolean.TRUE) == null) {
                agents.add(task.getAgent());
            }
        }
        return List.copyOf(agents);
    }

    private ExecutionTrace buildExecutionTrace(
            String ensembleId,
            Instant startedAt,
            Instant completedAt,
            Map<String, String> resolvedInputs,
            EnsembleOutput output,
            CaptureMode effectiveCaptureMode,
            Workflow effectiveWorkflow,
            List<Agent> derivedAgents) {

        List<AgentSummary> agentSummaries = derivedAgents.stream()
                .map(agent -> AgentSummary.builder()
                        .role(agent.getRole())
                        .goal(agent.getGoal())
                        .background(agent.getBackground())
                        .toolNames(agent.getTools().stream()
                                .filter(t -> t instanceof AgentTool)
                                .map(t -> ((AgentTool) t).name())
                                .collect(Collectors.toList()))
                        .allowDelegation(agent.isAllowDelegation())
                        .build())
                .collect(Collectors.toList());

        List<TaskTrace> taskTraces = output.getTaskOutputs().stream()
                .map(TaskOutput::getTrace)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        ExecutionMetrics metrics = output.getMetrics();

        ExecutionTrace.ExecutionTraceBuilder builder = ExecutionTrace.builder()
                .ensembleId(ensembleId)
                .workflow(effectiveWorkflow.name())
                .captureMode(effectiveCaptureMode)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .totalDuration(java.time.Duration.between(startedAt, completedAt))
                .inputs(Map.copyOf(resolvedInputs != null ? resolvedInputs : Map.of()))
                .metrics(metrics);

        for (AgentSummary summary : agentSummaries) {
            builder.agent(summary);
        }
        for (TaskTrace trace : taskTraces) {
            builder.taskTrace(trace);
        }
        if (metrics.getTotalCostEstimate() != null) {
            builder.totalCostEstimate(metrics.getTotalCostEstimate());
        }

        return builder.build();
    }

    /**
     * Resolve template variables in task descriptions and expected outputs.
     */
    private List<Task> resolveTasks(Map<String, String> resolvedInputsMap) {
        // Pass 1: resolve description and expectedOutput; build original -> resolved map.
        IdentityHashMap<Task, Task> originalToResolved = new IdentityHashMap<>();
        for (Task task : tasks) {
            Task resolved = task.toBuilder()
                    .description(TemplateResolver.resolve(task.getDescription(), resolvedInputsMap))
                    .expectedOutput(TemplateResolver.resolve(task.getExpectedOutput(), resolvedInputsMap))
                    .build();
            originalToResolved.put(task, resolved);
        }

        // Pass 2: rewrite context lists to reference resolved instances.
        List<Task> result = new ArrayList<>(tasks.size());
        for (Task original : tasks) {
            Task resolvedBase = originalToResolved.get(original);
            List<Task> originalContext = original.getContext();
            if (originalContext.isEmpty()) {
                result.add(resolvedBase);
            } else {
                List<Task> resolvedContext = new ArrayList<>(originalContext.size());
                for (Task ctxTask : originalContext) {
                    resolvedContext.add(originalToResolved.getOrDefault(ctxTask, ctxTask));
                }
                Task finalTask =
                        resolvedBase.toBuilder().context(resolvedContext).build();
                originalToResolved.put(original, finalTask);
                result.add(finalTask);
            }
        }
        return result;
    }

    /**
     * Infer the effective {@link Workflow} from the template-resolved task list.
     *
     * <p>When {@link #workflow} is explicitly set, returns it unchanged. Otherwise:
     * <ul>
     *   <li>If any task declares a {@code context} dependency on another task in this
     *       ensemble, {@link Workflow#PARALLEL} (DAG-based execution) is returned.</li>
     *   <li>Otherwise {@link Workflow#SEQUENTIAL} is returned as the default.</li>
     * </ul>
     *
     * @param resolvedTasks the template-resolved task list; used to check context deps
     * @return the effective workflow to use for this run
     */
    private Workflow resolveWorkflow(List<Task> resolvedTasks) {
        if (workflow != null) {
            return workflow;
        }
        // Build an identity-based set of all tasks to detect in-ensemble context deps
        java.util.Set<Task> taskSet = Collections.newSetFromMap(new IdentityHashMap<>());
        taskSet.addAll(resolvedTasks);
        for (Task task : resolvedTasks) {
            for (Task dep : task.getContext()) {
                if (taskSet.contains(dep)) {
                    return Workflow.PARALLEL;
                }
            }
        }
        return Workflow.SEQUENTIAL;
    }

    /**
     * Execute the ensemble phases via the {@link PhaseDagExecutor}.
     *
     * <p>Builds a per-phase runner that:
     * <ol>
     *   <li>Resolves template variables in the phase's task descriptions.</li>
     *   <li>Synthesizes agents for agentless tasks.</li>
     *   <li>Selects the per-phase workflow (phase override or ensemble default).</li>
     *   <li>Calls {@link SequentialWorkflowExecutor#executeSeeded} so cross-phase
     *       {@code context()} references resolve correctly via the prior-outputs map.</li>
     * </ol>
     *
     * @param phases          all phases declared on the ensemble
     * @param resolvedInputs  template variable map
     * @param effectiveChatModel  ensemble-level chat model (already rate-limited if configured)
     * @param executionContext execution context shared across all phases
     * @return combined output from all completed phases
     */
    private EnsembleOutput executePhases(
            List<Phase> phases,
            Map<String, String> resolvedInputs,
            ChatModel effectiveChatModel,
            ExecutionContext executionContext) {

        // Determine the ensemble-level effective workflow for phases without a per-phase override.
        // For phases, SEQUENTIAL is the safe default when no context deps are declared.
        Workflow ensembleEffectiveWorkflow = workflow != null ? workflow : Workflow.SEQUENTIAL;

        List<DelegationPolicy> policies = delegationPolicies != null ? delegationPolicies : List.of();

        // Collect per-phase outputs for the phaseOutputs map on the final EnsembleOutput.
        ConcurrentHashMap<String, List<TaskOutput>> phaseResultsMap = new ConcurrentHashMap<>();

        // Precompute original task lists by phase name so that retry-rebuilt Phase objects
        // (which PhaseDagExecutor creates with new task identities during PhaseReview retries)
        // still map to the user-created originals when populating cumulativeOriginalToResolved.
        // Synthetic review phases are not in this map; they fall back to phase.getTasks().
        Map<String, List<Task>> originalTasksByPhaseName = new HashMap<>();
        for (Phase p : phases) {
            originalTasksByPhaseName.put(p.getName(), p.getTasks());
        }

        // Cumulative mapping: original (user-created) task -> agent-resolved task, accumulated
        // across all completed phases. Used to augment priorOutputs before each phase executes
        // so that cross-phase context() references can find prior outputs regardless of whether
        // resolveAgents() created new Task instances during synthesis.
        //
        // Must be synchronised: independent phases (no dependency on each other) may execute
        // concurrently on virtual threads, so updates from one phase must be visible to a later
        // phase that depends on the first without a happens-before beyond the DAG ordering.
        Map<Task, Task> cumulativeOriginalToResolved = Collections.synchronizedMap(new IdentityHashMap<>());

        PhaseDagExecutor dagExecutor = new PhaseDagExecutor();

        EnsembleOutput dagOutput = dagExecutor.execute(phases, (phase, priorOutputs) -> {
            // 1. Resolve template variables in this phase's tasks
            List<Task> templateResolved = resolveTasksFromList(phase.getTasks(), resolvedInputs);

            // 2. Resolve agents (synthesize where needed)
            List<Task> agentResolved = resolveAgents(templateResolved, effectiveChatModel, chatLanguageModel);

            // 3. Record original -> agent-resolved mapping for this phase's tasks.
            //    Uses positional correspondence: originalPhaseTasks.get(i) -> agentResolved.get(i).
            //    This captures the full transformation chain (template resolution + synthesis)
            //    in a single map entry so later phases can bridge the identity gap.
            //
            //    Use the precomputed original task list (not phase.getTasks()) so that
            //    PhaseReview retries -- where the DAG executor calls this lambda with a
            //    rebuilt Phase whose tasks are new objects -- still anchor the bridge to
            //    the user-created task identities that successor phases' context() lists hold.
            List<Task> originalPhaseTasks = originalTasksByPhaseName.getOrDefault(phase.getName(), phase.getTasks());

            // Invariant: resolveTasksFromList and resolveAgents are 1:1 with their inputs.
            // A size mismatch indicates a framework bug and must not silently corrupt the map.
            if (originalPhaseTasks.size() != agentResolved.size()) {
                throw new IllegalStateException("Phase '" + phase.getName()
                        + "': task count changed during resolution (original="
                        + originalPhaseTasks.size() + ", resolved=" + agentResolved.size()
                        + "). This is a framework bug; please report it.");
            }
            for (int i = 0; i < originalPhaseTasks.size(); i++) {
                cumulativeOriginalToResolved.put(originalPhaseTasks.get(i), agentResolved.get(i));
            }

            // 4. Augment priorOutputs with entries keyed by original task references.
            //
            //    Problem: globalTaskOutputs (and therefore priorOutputs) is keyed by the
            //    agent-resolved task instances produced in step 2 of a prior phase. However,
            //    the context() list of a later-phase task still holds the ORIGINAL (user-created)
            //    task references, because resolveTasksFromList / resolveAgents for the later
            //    phase only rewrites intra-phase context references -- cross-phase ones fall
            //    through unchanged. The identity-based completedOutputs.get(contextTask) lookup
            //    in gatherContextOutputs / ParallelTaskCoordinator therefore misses the output.
            //
            //    Fix: for each (original, resolved) pair in the cumulative map, if the resolved
            //    task has an entry in priorOutputs, also expose it under the original key. Both
            //    the resolved key (for intra-phase lookups) and the original key (for cross-phase
            //    lookups) then resolve correctly.
            Map<Task, TaskOutput> augmentedPriorOutputs;
            if (priorOutputs.isEmpty()) {
                augmentedPriorOutputs = new IdentityHashMap<>();
            } else {
                @SuppressWarnings("IdentityHashMapUsage")
                IdentityHashMap<Task, TaskOutput> augmented = new IdentityHashMap<>(priorOutputs);
                // Synchronise the iteration to prevent concurrent modification from a
                // concurrently executing independent phase updating the map at the same time.
                synchronized (cumulativeOriginalToResolved) {
                    cumulativeOriginalToResolved.forEach((original, resolved) -> {
                        TaskOutput out = priorOutputs.get(resolved);
                        if (out != null) {
                            // putIfAbsent: do not overwrite if original and resolved happen to be
                            // the same object (handler tasks preserved by resolveAgents).
                            augmented.putIfAbsent(original, out);
                        }
                    });
                }
                augmentedPriorOutputs = augmented;
            }

            // 5. Derive agents for delegation context
            List<Agent> phaseAgents = deriveAgents(agentResolved);

            // 6. Determine the per-phase workflow
            Workflow phaseWorkflow = phase.getWorkflow() != null ? phase.getWorkflow() : ensembleEffectiveWorkflow;

            if (log.isDebugEnabled()) {
                log.debug(
                        "Phase '{}' workflow: {} | Tasks: {} | Prior outputs: {} | Augmented: {}",
                        phase.getName(),
                        phaseWorkflow,
                        agentResolved.size(),
                        priorOutputs.size(),
                        augmentedPriorOutputs.size());
            }

            // 7. Execute with appropriate executor, seeding augmented prior outputs so that
            //    cross-phase context() references resolve correctly regardless of whether
            //    agent synthesis transformed the referenced task's identity.
            EnsembleOutput phaseOutput =
                    switch (phaseWorkflow) {
                        case SEQUENTIAL -> new SequentialWorkflowExecutor(phaseAgents, maxDelegationDepth, policies)
                                .executeSeeded(agentResolved, executionContext, augmentedPriorOutputs);
                        case PARALLEL -> new ParallelWorkflowExecutor(
                                        phaseAgents, maxDelegationDepth, parallelErrorStrategy, policies)
                                .executeSeeded(agentResolved, executionContext, augmentedPriorOutputs);
                        case HIERARCHICAL -> throw new ValidationException("Phase '"
                                + phase.getName()
                                + "': Workflow.HIERARCHICAL is not supported at the phase "
                                + "level. Use HIERARCHICAL at the ensemble level (without phases).");
                    };

            // 8. Record phase outputs for EnsembleOutput.getPhaseOutputs()
            phaseResultsMap.put(phase.getName(), phaseOutput.getTaskOutputs());

            return phaseOutput;
        });

        // Augment the dag output with the per-phase output map
        return EnsembleOutput.builder()
                .raw(dagOutput.getRaw())
                .taskOutputs(dagOutput.getTaskOutputs())
                .totalDuration(dagOutput.getTotalDuration())
                .totalToolCalls(dagOutput.getTotalToolCalls())
                .phaseOutputs(Collections.unmodifiableMap(phaseResultsMap))
                .build();
    }

    /**
     * Resolve template variables in a given task list.
     *
     * <p>Equivalent to {@link #resolveTasks(Map)} but operates on an arbitrary task list
     * instead of {@link #tasks}. Used by the per-phase runner in {@link #executePhases}.
     *
     * @param inputTasks        the task list to resolve
     * @param resolvedInputsMap template variable map
     * @return list of resolved tasks with context references remapped to resolved instances
     */
    private static List<Task> resolveTasksFromList(List<Task> inputTasks, Map<String, String> resolvedInputsMap) {
        // Pass 1: resolve description and expectedOutput.
        // Preserve the original task identity when nothing changed -- this is critical for
        // cross-phase context() references, which hold the original task identity. If we
        // always create new Task objects, the identity-based completedOutputs lookup in
        // subsequent phases would fail to find the prior phase's outputs.
        IdentityHashMap<Task, Task> originalToResolved = new IdentityHashMap<>();
        for (Task task : inputTasks) {
            String resolvedDesc = TemplateResolver.resolve(task.getDescription(), resolvedInputsMap);
            String resolvedExpected = TemplateResolver.resolve(task.getExpectedOutput(), resolvedInputsMap);
            // Preserve original identity when nothing changed (no template substitution occurred)
            Task resolved =
                    resolvedDesc.equals(task.getDescription()) && resolvedExpected.equals(task.getExpectedOutput())
                            ? task
                            : task.toBuilder()
                                    .description(resolvedDesc)
                                    .expectedOutput(resolvedExpected)
                                    .build();
            originalToResolved.put(task, resolved);
        }

        // Pass 2: rewrite context lists to reference resolved instances
        List<Task> result = new ArrayList<>(inputTasks.size());
        for (Task original : inputTasks) {
            Task resolvedBase = originalToResolved.get(original);
            List<Task> originalContext = original.getContext();
            if (originalContext.isEmpty()) {
                result.add(resolvedBase);
            } else {
                List<Task> resolvedContext = new ArrayList<>(originalContext.size());
                for (Task ctxTask : originalContext) {
                    // Context tasks may be from THIS phase OR from a prior phase.
                    // For within-phase refs: remap to the resolved instance.
                    // For cross-phase refs: keep the original identity (priorOutputs lookup
                    // uses the original task instances from the prior phase's execution).
                    resolvedContext.add(originalToResolved.getOrDefault(ctxTask, ctxTask));
                }
                Task finalTask =
                        resolvedBase.toBuilder().context(resolvedContext).build();
                originalToResolved.put(original, finalTask);
                result.add(finalTask);
            }
        }
        return result;
    }

    private WorkflowExecutor selectExecutor(Workflow effectiveWorkflow, List<Agent> derivedAgents) {
        List<DelegationPolicy> policies = delegationPolicies != null ? delegationPolicies : List.of();
        return switch (effectiveWorkflow) {
            case SEQUENTIAL -> new SequentialWorkflowExecutor(derivedAgents, maxDelegationDepth, policies);
            case HIERARCHICAL -> new HierarchicalWorkflowExecutor(
                    resolveManagerLlm(derivedAgents),
                    derivedAgents,
                    managerMaxIterations,
                    maxDelegationDepth,
                    managerPromptStrategy,
                    policies,
                    hierarchicalConstraints);
            case PARALLEL -> new ParallelWorkflowExecutor(
                    derivedAgents, maxDelegationDepth, parallelErrorStrategy, policies);
        };
    }

    private ChatModel resolveManagerLlm(List<Agent> derivedAgents) {
        if (managerLlm != null) return managerLlm;
        if (chatLanguageModel != null) return chatLanguageModel;
        if (!derivedAgents.isEmpty()) return derivedAgents.get(0).getLlm();
        throw new ValidationException("No Manager LLM available for hierarchical workflow. "
                + "Set ensemble-level chatLanguageModel or managerLlm.");
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    /**
     * Returns true when at least one task in the given list has a non-null
     * {@link net.agentensemble.reflection.ReflectionConfig}.
     *
     * <p>Used to decide whether to auto-provision a default {@link InMemoryReflectionStore}
     * when none is explicitly configured on the Ensemble.
     *
     * @param tasks the agent-resolved task list
     * @return true if any task has reflection enabled
     */
    private static boolean hasReflectionEnabled(List<Task> tasks) {
        return tasks.stream().anyMatch(t -> t.getReflectionConfig() != null);
    }

    // ========================
    // Custom builder methods (lambda convenience for event listeners)
    // ========================

    /**
     * Extends the Lombok-generated builder with lambda convenience methods for registering
     * event listeners without implementing the full {@link EnsembleListener} interface.
     */
    public static class EnsembleBuilder {

        // Phases accumulator. Kept as a plain List<Phase> field (not @Singular, not
        // @Builder.Default) so that both phase(Phase) and phase(String, Task...) methods can
        // be declared without conflicting with Lombok-generated names. The field starts as
        // an empty immutable list; each phase() call creates a new immutable copy so that
        // Lombok's generated build() always receives an immutable list.
        private List<Phase> phases = List.of();

        // Shared capabilities accumulator (same pattern as phases).
        private List<SharedCapability> sharedCapabilities = List.of();

        /**
         * Share a named task with the network.
         *
         * <p>Other ensembles can discover and delegate work to this task via
         * {@code NetworkTask.from(ensembleName, taskName)}.
         *
         * @param name unique name for this shared task
         * @param task the task definition to share
         * @return this builder
         */
        public EnsembleBuilder shareTask(String name, Task task) {
            Objects.requireNonNull(name, "Shared task name must not be null");
            Objects.requireNonNull(task, "Shared task must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Shared task name must not be blank");
            }
            String description = task.getDescription() != null ? task.getDescription() : "";
            List<SharedCapability> updated = new ArrayList<>(this.sharedCapabilities);
            updated.add(new SharedCapability(name, description, SharedCapabilityType.TASK));
            this.sharedCapabilities = List.copyOf(updated);
            return this;
        }

        /**
         * Share a named tool with the network.
         *
         * <p>Other ensembles' agents can invoke this tool remotely via
         * {@code NetworkTool.from(ensembleName, toolName)}.
         *
         * @param name unique name for this shared tool
         * @param tool the tool to share
         * @return this builder
         */
        public EnsembleBuilder shareTool(String name, AgentTool tool) {
            Objects.requireNonNull(name, "Shared tool name must not be null");
            Objects.requireNonNull(tool, "Shared tool must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Shared tool name must not be blank");
            }
            List<SharedCapability> updated = new ArrayList<>(this.sharedCapabilities);
            updated.add(new SharedCapability(name, name, SharedCapabilityType.TOOL));
            this.sharedCapabilities = List.copyOf(updated);
            return this;
        }

        /**
         * Add a single phase to this ensemble.
         *
         * @param phase the phase to add; must not be null
         * @return this builder
         */
        public EnsembleBuilder phase(Phase phase) {
            Objects.requireNonNull(phase, "phase must not be null");
            List<Phase> updated = new ArrayList<>(this.phases);
            updated.add(phase);
            this.phases = List.copyOf(updated);
            return this;
        }

        /**
         * Register a lambda that is called immediately before each task starts.
         */
        public EnsembleBuilder onTaskStart(Consumer<TaskStartEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onTaskStart(TaskStartEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called immediately after each task completes successfully.
         */
        public EnsembleBuilder onTaskComplete(Consumer<TaskCompleteEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onTaskComplete(TaskCompleteEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called when a task fails, before the exception propagates.
         */
        public EnsembleBuilder onTaskFailed(Consumer<TaskFailedEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onTaskFailed(TaskFailedEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called after each tool execution in the ReAct loop.
         */
        public EnsembleBuilder onToolCall(Consumer<ToolCallEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onToolCall(ToolCallEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called immediately before a delegation is handed off.
         */
        public EnsembleBuilder onDelegationStarted(Consumer<DelegationStartedEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onDelegationStarted(DelegationStartedEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called immediately after a delegation completes successfully.
         */
        public EnsembleBuilder onDelegationCompleted(Consumer<DelegationCompletedEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onDelegationCompleted(DelegationCompletedEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called when a delegation fails.
         */
        public EnsembleBuilder onDelegationFailed(Consumer<DelegationFailedEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onDelegationFailed(DelegationFailedEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called for each token emitted during streaming
         * generation of the final agent response.
         *
         * <p>Only invoked when a {@code StreamingChatModel} is resolved for the agent
         * (see {@link Ensemble#streamingChatLanguageModel}).
         */
        public EnsembleBuilder onToken(Consumer<TokenEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onToken(TokenEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Add a named phase with the given tasks (no workflow override, no dependencies).
         *
         * <p>Convenience method equivalent to {@code phase(Phase.of(name, tasks))}.
         *
         * @param name  unique phase name within the ensemble; must not be null or blank
         * @param tasks tasks for this phase; at least one required
         * @return this builder
         */
        public EnsembleBuilder phase(String name, Task... tasks) {
            return phase(Phase.of(name, tasks));
        }

        /**
         * Register a {@link EnsembleDashboard} (e.g. {@code WebDashboard}) as the live execution
         * dashboard for this ensemble run.
         *
         * <p>This convenience method performs four operations in one call:
         * <ol>
         *   <li><strong>Auto-start</strong> -- calls {@link EnsembleDashboard#start()} if the
         *       dashboard is not already running.</li>
         *   <li><strong>Streaming</strong> -- registers {@link EnsembleDashboard#streamingListener()}
         *       as an ensemble listener so execution events are broadcast over WebSocket.</li>
         *   <li><strong>Review gates</strong> -- sets {@link EnsembleDashboard#reviewHandler()}
         *       as the ensemble's review handler so that human-in-the-loop decisions are routed
         *       through the browser dashboard.</li>
         *   <li><strong>Lifecycle ownership</strong> -- if the dashboard was not already running
         *       when this method is called, the ensemble takes ownership: it will call
         *       {@link EnsembleDashboard#stop()} in the {@code finally} block of
         *       {@link Ensemble#run()}, even if the run throws. This ensures Javalin/Jetty
         *       non-daemon threads are released and the JVM can exit normally.
         *       If the dashboard was <em>already running</em> when this method is called (the
         *       caller started it externally), ownership stays with the caller -- the ensemble
         *       will NOT stop the server after the run, so it can outlive the ensemble run.</li>
         * </ol>
         *
         * <p><strong>Caller-owned lifecycle</strong>: start the dashboard yourself before
         * calling this method, and the server will stay alive after {@code run()} returns.
         * This is useful for E2E test harnesses or long-lived server processes that want to
         * keep the WebSocket endpoint up for late-joining clients:
         * <pre>
         * WebDashboard dashboard = WebDashboard.builder().port(7329).build();
         * dashboard.start();  // caller owns lifecycle -- run() will NOT stop it
         *
         * Ensemble.builder()
         *     .chatLanguageModel(model)
         *     .webDashboard(dashboard)
         *     .task(Task.of("Research AI trends"))
         *     .build()
         *     .run();
         *
         * // dashboard is still running here -- stop it whenever you are done
         * dashboard.stop();
         * </pre>
         *
         * <p>To use live streaming <em>without</em> review gates, register the listener
         * directly instead:
         * <pre>
         * Ensemble.builder()
         *     .listener(dashboard.streamingListener())
         *     .task(...)
         * </pre>
         *
         * @param dashboard the live dashboard to register; must not be null
         * @return this builder
         * @throws NullPointerException when {@code dashboard} is null
         */
        public EnsembleBuilder webDashboard(EnsembleDashboard dashboard) {
            Objects.requireNonNull(dashboard, "dashboard must not be null");
            // Track ownership before potentially starting the server. If the dashboard is
            // already running, the caller started it and retains lifecycle responsibility --
            // the ensemble must not stop it after run() completes.
            boolean alreadyRunning = dashboard.isRunning();
            if (!alreadyRunning) {
                dashboard.start();
            }
            // Store the dashboard reference so Ensemble.runWithInputs() can call the
            // onEnsembleStarted/onEnsembleCompleted lifecycle hooks, and set ownership so
            // the finally block knows whether to call stop().
            this.dashboard(dashboard);
            this.ownsDashboardLifecycle(!alreadyRunning);

            // Auto-wire a trace exporter when the dashboard provides one (e.g. when
            // WebDashboard.builder().traceExportDir(...) was set) and the caller has not
            // already configured an explicit traceExporter on this builder. This is a
            // convenience so callers do not need both .webDashboard(...) and
            // .traceExporter(new JsonTraceExporter(...)) in the common multi-run case.
            net.agentensemble.trace.export.ExecutionTraceExporter dashboardExporter = dashboard.traceExporter();
            if (dashboardExporter != null && this.traceExporter == null) {
                this.traceExporter(dashboardExporter);
            }

            return listener(dashboard.streamingListener()).reviewHandler(dashboard.reviewHandler());
        }
    }
}
