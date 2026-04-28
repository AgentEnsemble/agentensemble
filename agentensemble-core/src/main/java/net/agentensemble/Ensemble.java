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
import net.agentensemble.audit.AuditPolicy;
import net.agentensemble.audit.AuditSink;
import net.agentensemble.audit.AuditingListener;
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
import net.agentensemble.directive.AutoDirectiveRule;
import net.agentensemble.directive.DirectiveDispatcher;
import net.agentensemble.directive.DirectiveStore;
import net.agentensemble.ensemble.BroadcastHandler;
import net.agentensemble.ensemble.EnsembleLifecycleState;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.EnsembleScheduler;
import net.agentensemble.ensemble.ScheduledTask;
import net.agentensemble.ensemble.SharedCapability;
import net.agentensemble.ensemble.SharedCapabilityType;
import net.agentensemble.exception.AgentEnsembleException;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.execution.RunOptions;
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
     * Bounded iteration loops over a sub-ensemble of tasks.
     *
     * <p>Each {@link net.agentensemble.workflow.loop.Loop} declares a body of tasks that
     * repeats until either an {@code until} predicate fires or a {@code maxIterations} cap
     * is hit. Loops are first-class workflow nodes and may carry outer-DAG dependencies via
     * {@code Loop.builder().context(...)}.
     *
     * <p>Ordering rules:
     * <ul>
     *   <li>{@code Workflow.SEQUENTIAL} -- declared tasks execute first, then declared loops
     *       in declaration order. To execute a task strictly after a loop, use
     *       {@code Workflow.PARALLEL} (with {@code Loop.context} declaring the loop's outer
     *       dependencies) or place the post-loop work as the final task in the loop body.</li>
     *   <li>{@code Workflow.PARALLEL} -- loops are nodes in the dependency DAG. Loops with
     *       no unmet dependencies run alongside other roots; tasks waiting on a loop is not
     *       supported in v1 since {@code Task.context} accepts only Tasks (track upstream
     *       dependencies on the Loop instead).</li>
     *   <li>{@code Workflow.HIERARCHICAL} -- loops are not supported; using both rejects
     *       at validation time.</li>
     * </ul>
     *
     * <p>Default: empty list.
     */
    @Singular
    private final List<net.agentensemble.workflow.loop.Loop> loops;

    /**
     * Optional state-machine graph. Mutually exclusive with {@code tasks}, {@code loops},
     * and {@code phases} — a Graph ensemble has exactly one Graph and no other workflow
     * nodes.
     *
     * <p>See {@link net.agentensemble.workflow.graph.Graph} for the construct used to
     * express LangGraph-style state-machine flows: tool routers, selective-feedback edges,
     * multi-turn negotiation, and other patterns where the next node is decided per step
     * from the prior output.
     *
     * <p>Default: {@code null}.
     */
    private final net.agentensemble.workflow.graph.Graph graph;

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
     * Maximum characters of tool output sent to the LLM per tool call.
     *
     * <p>{@code -1} (the default) means no truncation — the LLM always sees the full output.
     * Set a positive value (e.g. {@code 5000}) to cap every tool result before it is added
     * to the message history. When truncation occurs, a note is appended so the LLM knows
     * the output was cut.
     *
     * <p>Can be overridden per-run via {@link RunOptions#getMaxToolOutputLength()}.
     *
     * <p>Default: {@code -1} (unlimited).
     */
    @Builder.Default
    private final int maxToolOutputLength = -1;

    /**
     * Maximum characters of tool output emitted to log statements.
     *
     * <p>{@code -1} means full output is logged; {@code 0} suppresses output content
     * from logs entirely. This is purely for developer visibility and does not affect what
     * the LLM sees.
     *
     * <p>Can be overridden per-run via {@link RunOptions#getToolLogTruncateLength()}.
     *
     * <p>Default: {@code 200} (matches the pre-configurable behaviour).
     */
    @Builder.Default
    private final int toolLogTruncateLength = 200;

    /**
     * Reserved for future graceful shutdown behavior.
     *
     * <p>Currently, {@link #stop()} stops the ensemble immediately without waiting for
     * in-flight tasks to finish. This field is reserved for a future drain implementation
     * that will wait up to this duration before forcing shutdown. It does not affect
     * current behavior.
     *
     * <p>Default: 5 minutes.
     *
     * @see #stop()
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
     * Registry mapping shared task names to their {@link Task} instances.
     *
     * <p>Populated by {@link EnsembleBuilder#shareTask(String, Task)} in parallel with
     * {@link #sharedCapabilities}. Used by the request handler to look up tasks for
     * incoming cross-ensemble work requests.
     */
    @Getter(lombok.AccessLevel.PUBLIC)
    private final Map<String, Task> sharedTaskRegistry;

    /**
     * Registry mapping shared tool names to their {@link AgentTool} instances.
     *
     * <p>Populated by {@link EnsembleBuilder#shareTool(String, AgentTool)} in parallel with
     * {@link #sharedCapabilities}. Used by the request handler to look up tools for
     * incoming cross-ensemble work requests.
     */
    @Getter(lombok.AccessLevel.PUBLIC)
    private final Map<String, AgentTool> sharedToolRegistry;

    /**
     * Tasks that fire on a schedule in long-running mode.
     *
     * <p>Populated via {@link EnsembleBuilder#scheduledTask(ScheduledTask)}. Each task fires
     * according to its {@link net.agentensemble.ensemble.Schedule} after {@link #start(int)}
     * transitions the ensemble to {@link EnsembleLifecycleState#READY}.
     *
     * <p>Not annotated with {@code @Singular} or {@code @Builder.Default} because we
     * provide a custom accumulator method in {@link EnsembleBuilder}. Managed identically
     * to {@link #phases} and {@link #sharedCapabilities}.
     *
     * @see ScheduledTask
     */
    private final List<ScheduledTask> scheduledTasks;

    /**
     * Optional handler for broadcasting scheduled task results to named topics.
     *
     * <p>When set and a {@link ScheduledTask} has a non-null {@code broadcastTo}, the
     * result string is forwarded to this handler after the task completes.
     *
     * <p>Default: null (no broadcasting).
     *
     * @see BroadcastHandler
     */
    private final BroadcastHandler broadcastHandler;

    /**
     * Optional audit policy for leveled audit trail with dynamic rules.
     *
     * <p>When set alongside {@link #auditSinks}, an {@link AuditingListener} is automatically
     * created and added to the listeners list at the start of each {@link #runWithInputs} call.
     * The listener records audit events at the configured verbosity level and supports dynamic
     * escalation via rules defined in the policy.
     *
     * <p>Default: null (no auditing).
     *
     * @see AuditPolicy
     * @see AuditingListener
     */
    private final AuditPolicy auditPolicy;

    /**
     * Audit sinks for writing audit records to backends.
     *
     * <p>Used in conjunction with {@link #auditPolicy} to create an {@link AuditingListener}.
     * Multiple sinks can be registered to write records to different backends simultaneously
     * (e.g. SLF4J logging and a database).
     *
     * <p>Not annotated with {@code @Singular} or {@code @Builder.Default} because we provide
     * a custom {@code auditSink()} accumulator method in {@link EnsembleBuilder}. Managed
     * identically to {@link #phases} and {@link #sharedCapabilities}.
     *
     * <p>Default: empty list.
     *
     * @see AuditSink
     */
    private final List<AuditSink> auditSinks;

    /**
     * Thread-safe store of active human directives for this ensemble.
     *
     * <p>Directives are injected by humans (or automated policies) at runtime and included
     * in agent prompts as an {@code ## Active Directives} section. This store is shared
     * across all tasks in a single ensemble run and supports concurrent reads during
     * prompt building.
     *
     * <p>Default: a new empty {@link DirectiveStore}.
     *
     * @see net.agentensemble.directive.Directive
     */
    @Builder.Default
    private final DirectiveStore directiveStore = new DirectiveStore();

    /**
     * Optional fallback LLM for cost-saving model tier switching at runtime.
     *
     * <p>When set, control plane directives ({@code SET_MODEL_TIER FALLBACK}) can switch
     * the ensemble to this cheaper model without restarting. The switch applies to new tasks
     * only; in-flight tasks continue with their current model.
     *
     * <p>Default: null (no fallback model available).
     *
     * @see #switchToFallbackModel()
     * @see #switchToPrimaryModel()
     */
    private final ChatModel fallbackModel;

    /**
     * Runtime-switchable model reference. Reads are lock-free via AtomicReference.
     * When null, {@link #chatLanguageModel} is used.
     */
    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    private final AtomicReference<ChatModel> activeModel = new AtomicReference<>();

    /**
     * Dispatcher for control plane directives (SET_MODEL_TIER, APPLY_PROFILE, etc.).
     */
    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    private final DirectiveDispatcher directiveDispatcher = new DirectiveDispatcher();

    /**
     * Rules that automatically fire control plane directives based on execution metrics.
     *
     * <p>Evaluated after each task completion. When a rule's condition is met, its
     * associated directive is dispatched through the {@link DirectiveDispatcher}.
     *
     * <p>Not annotated with {@code @Singular} or {@code @Builder.Default} because we
     * provide a custom {@code autoDirectiveRule()} accumulator in {@link EnsembleBuilder}.
     */
    private final List<AutoDirectiveRule> autoDirectiveRules;

    /**
     * Internal scheduler for proactive/scheduled tasks in long-running mode.
     *
     * <p>Not exposed via the builder; created and managed internally by
     * {@link #start(int)} and {@link #stop()}. Volatile because it is set in
     * {@code start()} and read in {@code stop()} potentially from different threads.
     */
    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    private final AtomicReference<EnsembleScheduler> schedulerRef = new AtomicReference<>();

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
     * Registered JVM shutdown hook thread for this ensemble, stored so it can be
     * deregistered by {@link #stop()} to avoid accumulation across restart cycles.
     */
    @Builder.Default
    @Getter(lombok.AccessLevel.NONE)
    private final AtomicReference<Thread> shutdownHookRef = new AtomicReference<>();

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
     * Start this ensemble in long-running mode.
     *
     * <p>The ensemble transitions through {@link EnsembleLifecycleState#STARTING} to
     * {@link EnsembleLifecycleState#READY}. Shared capabilities are published for peer
     * discovery, and any configured dashboard is started.
     *
     * <p>A dashboard must be configured at build time via
     * {@link EnsembleBuilder#webDashboard(EnsembleDashboard)}.
     * If no dashboard is configured, {@code start()} throws {@link IllegalStateException}
     * with guidance on how to add one.
     *
     * <p>The {@code port} argument is advisory: it is included in error messages and logs
     * to help identify which port is expected. The configured {@link EnsembleDashboard}
     * is responsible for actually binding to the port -- it uses the port set on its own
     * builder (e.g., {@code WebDashboard.builder().port(7329).build()}).
     *
     * <p>Calling {@code start()} on an already-started ensemble (state is {@code STARTING}
     * or {@code READY}) is a no-op (idempotent).
     *
     * <p>A JVM shutdown hook is registered to trigger {@link #stop()} on SIGTERM or
     * normal shutdown. Any previously registered hook from an earlier {@code start()} call
     * is deregistered first to avoid accumulation.
     *
     * @param port advisory port hint used in error messages and logging
     * @throws ValidationException     if the ensemble configuration is invalid
     * @throws AgentEnsembleException  if the server cannot be started
     */
    public void start(int port) {
        EnsembleLifecycleState current = lifecycleStateRef.get();
        if (current == EnsembleLifecycleState.STARTING || current == EnsembleLifecycleState.READY) {
            return; // idempotent
        }

        new EnsembleValidator(this).validate();

        // Atomically transition to STARTING; if another thread started concurrently, this is a no-op.
        if (!lifecycleStateRef.compareAndSet(current, EnsembleLifecycleState.STARTING)) {
            return;
        }

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

            // Wire lifecycle state, drain action, request handler, and directive store into the dashboard
            dashboard.setLifecycleStateProvider(this::getLifecycleState);
            dashboard.setDrainAction(this::stop);
            dashboard.setDirectiveStore(directiveStore);
            if (!sharedTaskRegistry.isEmpty() || !sharedToolRegistry.isEmpty()) {
                dashboard.setRequestHandler(new net.agentensemble.ensemble.EnsembleRequestHandler(this));
            }

            // Use CAS to transition STARTING -> READY. If stop() was called concurrently
            // (which set the state to DRAINING), this returns false and we abort cleanly.
            if (!lifecycleStateRef.compareAndSet(EnsembleLifecycleState.STARTING, EnsembleLifecycleState.READY)) {
                log.warn("Ensemble start aborted: stop() was called concurrently during startup");
                return;
            }
            log.info("Ensemble started in long-running mode on port {}", port);

            // Start scheduler for any registered scheduled tasks
            if (scheduledTasks != null && !scheduledTasks.isEmpty()) {
                EnsembleScheduler scheduler = new EnsembleScheduler(this::getLifecycleState);
                schedulerRef.set(scheduler);
                for (ScheduledTask st : scheduledTasks) {
                    scheduler.schedule(st, () -> executeScheduledTask(st));
                }
                log.info("Started {} scheduled task(s)", scheduledTasks.size());
            }

            // Register shutdown hook, deregistering any previous one to avoid accumulation
            // when the ensemble is stopped and restarted within the same JVM.
            Thread oldHook = shutdownHookRef.getAndSet(null);
            if (oldHook != null) {
                try {
                    Runtime.getRuntime().removeShutdownHook(oldHook);
                } catch (IllegalStateException ignored) {
                    // JVM is already shutting down; ignore
                }
            }
            Thread newHook = new Thread(
                    () -> {
                        if (getLifecycleState() == EnsembleLifecycleState.READY) {
                            stop();
                        }
                    },
                    "ensemble-shutdown-hook");
            shutdownHookRef.set(newHook);
            Runtime.getRuntime().addShutdownHook(newHook);
        } catch (IllegalStateException e) {
            lifecycleStateRef.set(EnsembleLifecycleState.STOPPED);
            throw e;
        } catch (Exception e) {
            lifecycleStateRef.set(EnsembleLifecycleState.STOPPED);
            throw new AgentEnsembleException("Failed to start ensemble on port " + port, e);
        }
    }

    /**
     * Initiate shutdown of a long-running ensemble.
     *
     * <p>The ensemble transitions to {@link EnsembleLifecycleState#DRAINING}, stops the
     * configured dashboard if this ensemble owns its lifecycle, and then transitions to
     * {@link EnsembleLifecycleState#STOPPED}.
     *
     * <p>Note: the {@link #drainTimeout} field is retained for future use when in-flight
     * task draining is implemented. The current implementation stops the WebSocket server
     * immediately without waiting for in-flight work to complete.
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

        // Remove the registered shutdown hook (if any) to prevent it from firing during
        // normal JVM shutdown after an explicit stop() call, and to avoid accumulation if
        // the ensemble is started again in the same JVM.
        Thread hook = shutdownHookRef.getAndSet(null);
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down; the hook will fire naturally
            }
        }

        // Stop scheduler before dashboard
        EnsembleScheduler sched = schedulerRef.getAndSet(null);
        if (sched != null) {
            sched.stop();
        }

        try {
            // Only stop the dashboard when this ensemble owns the lifecycle. An externally-
            // managed dashboard (ownsDashboardLifecycle=false) must not be stopped here, since
            // the caller retains lifecycle responsibility -- same contract as one-shot run().
            if (ownsDashboardLifecycle && dashboard != null && dashboard.isRunning()) {
                dashboard.stop();
            }
        } finally {
            lifecycleStateRef.set(EnsembleLifecycleState.STOPPED);
            log.info("Ensemble stopped");
        }
    }

    /**
     * Switch the active chat model to the fallback model configured via
     * {@code Ensemble.builder().fallbackModel(...)}.
     *
     * <p>The switch applies to new tasks only; in-flight tasks continue with their
     * current model. Has no effect if no fallback model was configured.
     */
    public void switchToFallbackModel() {
        if (fallbackModel != null) {
            activeModel.set(fallbackModel);
            log.info("Switched to fallback model");
        } else {
            log.warn("switchToFallbackModel() called but no fallback model is configured");
        }
    }

    /**
     * Switch the active chat model back to the primary model.
     *
     * <p>The switch applies to new tasks only; in-flight tasks continue with their
     * current model.
     */
    public void switchToPrimaryModel() {
        activeModel.set(null); // null means use the primary chatLanguageModel
        log.info("Switched to primary model");
    }

    /**
     * Switch the active chat model to an arbitrary model provided at runtime.
     *
     * <p>Unlike {@link #switchToFallbackModel()} (which requires the fallback to be
     * pre-configured at build time), this method accepts any {@link ChatModel} instance
     * and takes effect immediately on the next LLM call. In-flight tasks continue with
     * their current model.
     *
     * <p>Used by the Ensemble Control API Phase 3 ({@code switch_model} action) to apply
     * a catalog-resolved model to a running ensemble without restarting it.
     *
     * @param model the model to switch to; must not be null
     * @throws NullPointerException if {@code model} is null
     */
    public void switchToModel(ChatModel model) {
        Objects.requireNonNull(model, "model must not be null");
        activeModel.set(model);
        log.info("Switched to custom model");
    }

    /**
     * Returns the currently active chat model, respecting any runtime model switches.
     *
     * @return the active chat model (primary or fallback)
     */
    public ChatModel getActiveModel() {
        ChatModel active = activeModel.get();
        return active != null ? active : chatLanguageModel;
    }

    /**
     * Returns the directive dispatcher for routing control plane directives.
     *
     * @return the directive dispatcher; never null
     */
    public DirectiveDispatcher getDirectiveDispatcher() {
        return directiveDispatcher;
    }

    /**
     * Execute a scheduled task by running it as a one-shot ensemble and optionally
     * broadcasting the result.
     *
     * <p>Called by the {@link EnsembleScheduler} on each firing. Creates a minimal
     * one-shot ensemble with the scheduled task's {@link Task} definition, executes
     * it, and forwards the result to the {@link BroadcastHandler} if configured.
     *
     * @param st the scheduled task definition
     */
    private void executeScheduledTask(ScheduledTask st) {
        try {
            EnsembleOutput output = Ensemble.builder()
                    .chatLanguageModel(getActiveModel())
                    .agentSynthesizer(getAgentSynthesizer())
                    .task(st.task())
                    .build()
                    .run();

            String result = output.lastCompletedOutput().map(TaskOutput::getRaw).orElse("");

            // Broadcast if configured
            if (st.broadcastTo() != null && broadcastHandler != null) {
                broadcastHandler.broadcast(st.broadcastTo(), result);
            }

            log.info("Scheduled task '{}' completed", st.name());
        } catch (Exception e) {
            log.warn("Scheduled task '{}' execution failed: {}", st.name(), e.getMessage(), e);
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
        return runWithInputs(inputs, maxToolOutputLength, toolLogTruncateLength);
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
            return runWithInputs(inputs, maxToolOutputLength, toolLogTruncateLength);
        }
        Map<String, String> merged = new LinkedHashMap<>(inputs);
        merged.putAll(runtimeInputs);
        return runWithInputs(Collections.unmodifiableMap(merged), maxToolOutputLength, toolLogTruncateLength);
    }

    /**
     * Execute the ensemble's tasks with per-run option overrides.
     *
     * <p>Non-null fields in {@code runOptions} override the ensemble-level defaults set on
     * the builder. {@code null} fields inherit the builder defaults.
     *
     * @param runOptions per-run overrides; {@code null} means no overrides (identical to {@link #run()})
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run(RunOptions runOptions) {
        if (runOptions == null) {
            return run();
        }
        return runWithInputs(
                inputs,
                resolveRunOption(maxToolOutputLength, runOptions.getMaxToolOutputLength()),
                resolveRunOption(toolLogTruncateLength, runOptions.getToolLogTruncateLength()));
    }

    /**
     * Execute the ensemble's tasks, merging the supplied run-time inputs with any inputs
     * configured on the builder, and applying per-run option overrides.
     *
     * @param runtimeInputs additional or overriding variable values
     * @param runOptions    per-run overrides; {@code null} means no overrides (identical to {@link #run(Map)})
     * @return EnsembleOutput containing all results
     * @throws ValidationException if the ensemble configuration is invalid
     */
    public EnsembleOutput run(Map<String, String> runtimeInputs, RunOptions runOptions) {
        if (runOptions == null) {
            return run(runtimeInputs);
        }
        Map<String, String> merged;
        if (runtimeInputs == null || runtimeInputs.isEmpty()) {
            merged = inputs;
        } else {
            Map<String, String> m = new LinkedHashMap<>(inputs);
            m.putAll(runtimeInputs);
            merged = Collections.unmodifiableMap(m);
        }
        return runWithInputs(
                merged,
                resolveRunOption(maxToolOutputLength, runOptions.getMaxToolOutputLength()),
                resolveRunOption(toolLogTruncateLength, runOptions.getToolLogTruncateLength()));
    }

    private static int resolveRunOption(int ensembleDefault, Integer runOverride) {
        return runOverride != null ? runOverride : ensembleDefault;
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

    /**
     * Returns a copy of this ensemble with the given task list replacing the original.
     *
     * <p>All core execution settings are preserved: model, agent synthesizer, workflow,
     * listeners, review handler, memory store, rate limits, output settings, etc.
     *
     * <p>The dashboard lifecycle is not inherited: {@code ownsDashboardLifecycle} is set to
     * {@code false} on the returned ensemble, since the dashboard is already running and
     * should not be stopped when the returned ensemble's run completes.
     *
     * <p>This method is used by the Ensemble Control API (Phase 2+) to execute Level 2
     * (per-task overrides) and Level 3 (dynamic tasks) runs against a configured template.
     *
     * @param newTasks the replacement task list; must not be null, empty, or contain null elements
     * @return a new Ensemble with {@code newTasks} and all other settings from this instance
     * @throws NullPointerException     if {@code newTasks} is null or contains a null element
     * @throws IllegalArgumentException if {@code newTasks} is empty
     */
    public Ensemble withTasks(List<Task> newTasks) {
        Objects.requireNonNull(newTasks, "newTasks must not be null");
        if (newTasks.isEmpty()) {
            throw new IllegalArgumentException("newTasks must not be empty");
        }
        for (int i = 0; i < newTasks.size(); i++) {
            if (newTasks.get(i) == null) {
                throw new NullPointerException("newTasks must not contain null elements (at index " + i + ")");
            }
        }
        return Ensemble.builder()
                // Replace the task list; phases are intentionally not copied
                // (API-submitted runs always use the flat task list)
                .tasks(newTasks)
                // Core LLM settings
                .chatLanguageModel(chatLanguageModel)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .agentSynthesizer(agentSynthesizer)
                // Workflow and manager
                .workflow(workflow)
                .managerLlm(managerLlm)
                .managerMaxIterations(managerMaxIterations)
                .managerPromptStrategy(managerPromptStrategy)
                // Execution settings
                .verbose(verbose)
                .toolExecutor(toolExecutor)
                .toolMetrics(toolMetrics)
                .maxDelegationDepth(maxDelegationDepth)
                .parallelErrorStrategy(parallelErrorStrategy)
                .hierarchicalConstraints(hierarchicalConstraints)
                .delegationPolicies(delegationPolicies)
                // Memory and context
                .memoryStore(memoryStore)
                .contextFormat(contextFormat)
                .reflectionStore(reflectionStore)
                // Review integration
                .reviewHandler(reviewHandler)
                .reviewPolicy(reviewPolicy)
                // Event streaming (critical: carries dashboard listener)
                .listeners(listeners)
                // Observability
                .captureMode(captureMode)
                .traceExporter(traceExporter)
                .costConfiguration(costConfiguration)
                // Dashboard -- not owned; it is already running
                .dashboard(dashboard)
                .ownsDashboardLifecycle(false)
                // Rate limiting and output settings
                .rateLimit(rateLimit)
                .maxToolOutputLength(maxToolOutputLength)
                .toolLogTruncateLength(toolLogTruncateLength)
                .drainTimeout(drainTimeout)
                // Default inputs from the template (merged with API inputs at run time)
                .inputs(inputs)
                .build();
    }

    /**
     * Returns a copy of this ensemble with an additional listener appended to the existing
     * listener list.
     *
     * <p>All other settings (tasks or phases, model, workflow, review handler, etc.) are
     * preserved unchanged. For phase-based ensembles the full phase list is copied; for
     * flat-task ensembles the task list is copied. The dashboard lifecycle is not inherited:
     * {@code ownsDashboardLifecycle} is set to {@code false} on the returned ensemble.
     *
     * <p>Used by the Ensemble Control API (Phase 3) to attach a per-run
     * cancellation-check listener without mutating the template ensemble.
     *
     * @param additional the listener to append; must not be null
     * @return a new Ensemble with the combined listener list and all other settings from this instance
     * @throws NullPointerException if {@code additional} is null
     */
    public Ensemble withAdditionalListener(EnsembleListener additional) {
        Objects.requireNonNull(additional, "additional listener must not be null");
        List<EnsembleListener> combined = new ArrayList<>(listeners != null ? listeners : List.of());
        combined.add(additional);
        EnsembleBuilder b = Ensemble.builder();
        // Preserve the task representation: phase-based ensembles use phases; flat-task ensembles
        // use the tasks list. Using phases here is important so that adding a listener to a
        // phase-based ensemble does not produce an invalid ensemble with an empty task list.
        if (phases != null && !phases.isEmpty()) {
            for (Phase p : phases) {
                b.phase(p);
            }
        } else {
            b.tasks(tasks);
        }
        return b.chatLanguageModel(chatLanguageModel)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .agentSynthesizer(agentSynthesizer)
                .workflow(workflow)
                .managerLlm(managerLlm)
                .managerMaxIterations(managerMaxIterations)
                .managerPromptStrategy(managerPromptStrategy)
                .verbose(verbose)
                .toolExecutor(toolExecutor)
                .toolMetrics(toolMetrics)
                .maxDelegationDepth(maxDelegationDepth)
                .parallelErrorStrategy(parallelErrorStrategy)
                .hierarchicalConstraints(hierarchicalConstraints)
                .delegationPolicies(delegationPolicies)
                .memoryStore(memoryStore)
                .contextFormat(contextFormat)
                .reflectionStore(reflectionStore)
                .reviewHandler(reviewHandler)
                .reviewPolicy(reviewPolicy)
                .listeners(List.copyOf(combined))
                .captureMode(captureMode)
                .traceExporter(traceExporter)
                .costConfiguration(costConfiguration)
                .dashboard(dashboard)
                .ownsDashboardLifecycle(false)
                .rateLimit(rateLimit)
                .maxToolOutputLength(maxToolOutputLength)
                .toolLogTruncateLength(toolLogTruncateLength)
                .drainTimeout(drainTimeout)
                .inputs(inputs)
                .build();
    }

    private EnsembleOutput runWithInputs(
            Map<String, String> resolvedInputs, int maxToolOutputLength, int toolLogTruncateLength) {
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

            // Wire directive store into the dashboard so incoming directives are routed
            // to the ensemble's store regardless of how the dashboard was started.
            if (dashboard != null && directiveStore != null) {
                dashboard.setDirectiveStore(directiveStore);
            }

            // Wire the ensemble reference into the dashboard so that control-plane
            // directives can be dispatched through the DirectiveDispatcher.
            if (dashboard != null) {
                dashboard.setEnsemble(this);
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

            // Step 5b: Wire audit listener when audit policy and sinks are configured
            List<EnsembleListener> effectiveListeners = listeners != null ? listeners : List.of();
            if (auditPolicy != null && auditSinks != null && !auditSinks.isEmpty()) {
                AuditingListener auditingListener = new AuditingListener(auditPolicy, auditSinks, ensembleId);
                List<EnsembleListener> augmented = new ArrayList<>(effectiveListeners);
                augmented.add(auditingListener);
                effectiveListeners = List.copyOf(augmented);
                log.info(
                        "AuditingListener enabled | Level: {} | Sinks: {}",
                        auditPolicy.defaultLevel(),
                        auditSinks.size());
            }

            // Step 5c: Wire auto-directive rule listener when rules are configured
            if (autoDirectiveRules != null && !autoDirectiveRules.isEmpty()) {
                List<net.agentensemble.task.TaskOutput> completedOutputs =
                        Collections.synchronizedList(new ArrayList<>());
                Ensemble self = this;
                List<EnsembleListener> augmented = new ArrayList<>(effectiveListeners);
                augmented.add(new EnsembleListener() {
                    @Override
                    public void onTaskComplete(TaskCompleteEvent event) {
                        if (event.taskOutput() != null) {
                            completedOutputs.add(event.taskOutput());
                        }
                        try {
                            List<net.agentensemble.task.TaskOutput> snapshot;
                            synchronized (completedOutputs) {
                                snapshot = List.copyOf(completedOutputs);
                            }
                            ExecutionMetrics currentMetrics = ExecutionMetrics.from(snapshot);
                            for (AutoDirectiveRule rule : autoDirectiveRules) {
                                if (rule.condition().test(currentMetrics)) {
                                    log.info("Auto-directive rule '{}' triggered; dispatching directive", rule.name());
                                    directiveDispatcher.dispatch(rule.directiveToFire(), self);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Auto-directive rule evaluation failed: {}", e.getMessage(), e);
                        }
                    }
                });
                effectiveListeners = List.copyOf(augmented);
                log.info("AutoDirectiveRules enabled | Rules: {}", autoDirectiveRules.size());
            }

            // Step 6: Build execution context
            ExecutionContext executionContext = ExecutionContext.of(
                    memoryContext,
                    verbose,
                    effectiveListeners,
                    toolExecutor,
                    toolMetrics,
                    costConfiguration,
                    effectiveCaptureMode,
                    memoryStore,
                    reviewHandler,
                    reviewPolicy,
                    streamingChatLanguageModel,
                    effectiveReflectionStore,
                    contextFormat != null ? ContextFormatters.forFormat(contextFormat) : null,
                    directiveStore,
                    maxToolOutputLength,
                    toolLogTruncateLength);

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

            // Step 7b: Notify EnsembleListeners that the ensemble run is starting.
            executionContext.fireEnsembleStarted(ensembleId, effectiveWorkflow.name(), agentResolvedTasks.size());

            // Step 7c: Resolve loop body tasks (templates + agents) using the same
            // pipeline as regular tasks. Also remap each loop's outer-DAG context() entries
            // from the original Task identities to the agent-resolved instances so the
            // parallel scheduler's identity-based dependency lookups succeed.
            IdentityHashMap<Task, Task> originalToResolvedTopLevelTask = new IdentityHashMap<>();
            if (tasks != null) {
                for (int i = 0; i < tasks.size() && i < agentResolvedTasks.size(); i++) {
                    originalToResolvedTopLevelTask.put(tasks.get(i), agentResolvedTasks.get(i));
                }
            }
            List<net.agentensemble.workflow.loop.Loop> resolvedLoops = resolveLoops(
                    loops, resolvedInputs, effectiveChatModel, chatLanguageModel, originalToResolvedTopLevelTask);
            if (log.isDebugEnabled()) {
                log.debug(
                        "Resolved loops | declared: {} | resolved: {}",
                        loops != null ? loops.size() : 0,
                        resolvedLoops.size());
            }

            // Step 7d: Resolve graph state Tasks if a Graph is configured. Same template +
            // agent resolution pipeline as regular tasks.
            net.agentensemble.workflow.graph.Graph resolvedGraph = null;
            if (graph != null) {
                resolvedGraph = resolveGraph(graph, resolvedInputs, effectiveChatModel, chatLanguageModel);
            }

            // Step 8: Select and execute WorkflowExecutor (flat tasks) or PhaseDagExecutor (phases)
            //         or GraphExecutor (state-machine graph)
            EnsembleOutput output;
            if (resolvedGraph != null) {
                // Graph ensemble: dispatch to GraphExecutor. Graph is mutually exclusive with
                // tasks/loops/phases (validated at Ensemble.run() entry).
                if (log.isInfoEnabled()) {
                    log.info(
                            "Ensemble run using Graph | states: {} | maxSteps: {}",
                            resolvedGraph.getStates().size(),
                            resolvedGraph.getMaxSteps());
                }
                output = executeGraph(resolvedGraph, derivedAgents, executionContext);
            } else if (phases != null && !phases.isEmpty()) {
                // Phase-based execution: PhaseDagExecutor handles the DAG; each phase
                // runner resolves template vars and agents independently.
                if (log.isInfoEnabled()) {
                    log.info("Ensemble run using Phase DAG | Phases: {}", phases.size());
                }
                output = executePhases(phases, resolvedInputs, buildEffectiveChatModel(), executionContext);
            } else {
                WorkflowExecutor executor = selectExecutor(effectiveWorkflow, derivedAgents);
                if (resolvedLoops.isEmpty()) {
                    output = executor.execute(agentResolvedTasks, executionContext);
                } else {
                    // Loops integrate via WorkflowNode-aware dispatch. SequentialWorkflowExecutor
                    // and ParallelWorkflowExecutor support loops; HierarchicalWorkflowExecutor
                    // rejects them via the default executeNodes() guard.
                    java.util.List<net.agentensemble.workflow.WorkflowNode> nodes = new ArrayList<>();
                    nodes.addAll(agentResolvedTasks);
                    nodes.addAll(resolvedLoops);
                    output = executor.executeNodes(nodes, executionContext);
                }
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

            // Step 9b: Notify EnsembleListeners that the ensemble run has completed.
            {
                java.time.Duration totalDuration = java.time.Duration.between(runStartedAt, runCompletedAt);
                String exitReason =
                        output.getExitReason() != null ? output.getExitReason().name() : "COMPLETED";
                executionContext.fireEnsembleCompleted(ensembleId, totalDuration, exitReason);
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
                    derivedAgents,
                    effectiveListeners);

            // Step 11: Attach trace to EnsembleOutput, preserving exitReason.
            // Remap the executor's taskOutputIndex (keyed by agent-resolved task instances)
            // back to the original task instances the caller holds, using the positional
            // correspondence: tasks.get(i) -> agentResolvedTasks.get(i).
            //
            // For Graph ensembles, executeGraph() has already remapped its index to the
            // original state-Task instances (graph.getStates() values), so we preserve it
            // unchanged rather than overwriting with an empty map (since `tasks` is empty
            // when a graph is configured).
            Map<Task, TaskOutput> executorIndex = output.getTaskOutputIndex();
            Map<Task, TaskOutput> originalIndex = null;
            if (resolvedGraph != null) {
                originalIndex = executorIndex;
            } else if (executorIndex != null) {
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
                    // Loop side channels survive the trace re-attachment.
                    .loopHistory(output.getLoopHistory())
                    .loopTerminationReasons(output.getLoopTerminationReasons())
                    .loopsTerminatedByMaxIterations(output.getLoopsTerminatedByMaxIterations())
                    // Graph side channels survive too.
                    .graphHistory(output.getGraphHistory())
                    .graphTerminationReason(output.getGraphTerminationReason().orElse(null))
                    .graphTerminatedByMaxSteps(output.wasGraphTerminatedByMaxSteps() ? Boolean.TRUE : null)
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
        ChatModel model = getActiveModel();
        if (rateLimit != null && model != null) {
            return RateLimitedChatModel.of(model, rateLimit);
        }
        return model;
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
            List<Agent> derivedAgents,
            List<EnsembleListener> effectiveListeners) {

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

        // Populate traceId from any listener that provides one (e.g. OTelTracingListener)
        if (effectiveListeners != null) {
            for (EnsembleListener listener : effectiveListeners) {
                String listenerTraceId = listener.getTraceId();
                if (listenerTraceId != null) {
                    builder.traceId(listenerTraceId);
                    break;
                }
            }
        }

        // Populate graph trace from the EnsembleOutput's graph side channels, if a Graph ran.
        if (graph != null && !output.getGraphHistory().isEmpty()) {
            net.agentensemble.trace.GraphTrace.GraphTraceBuilder gtb = net.agentensemble.trace.GraphTrace.builder()
                    .graphName(graph.getName())
                    .startState(graph.getStartState())
                    .terminationReason(output.getGraphTerminationReason().orElse("unknown"))
                    .stepsRun(output.getGraphHistory().size())
                    .maxSteps(graph.getMaxSteps());
            for (net.agentensemble.workflow.graph.GraphStep step : output.getGraphHistory()) {
                gtb.step(new net.agentensemble.trace.GraphTrace.GraphStepTrace(
                        step.getStateName(), step.getStepNumber(), step.getNextState()));
            }
            builder.graphTrace(gtb.build());
        }

        // Populate per-loop traces from the EnsembleOutput's loop side channels.
        Map<String, List<Map<String, net.agentensemble.task.TaskOutput>>> loopHistory = output.getLoopHistory();
        Map<String, String> loopTermReasons = output.getLoopTerminationReasons();
        if (loopHistory != null && !loopHistory.isEmpty() && loops != null) {
            for (net.agentensemble.workflow.loop.Loop loop : loops) {
                List<Map<String, net.agentensemble.task.TaskOutput>> history = loopHistory.get(loop.getName());
                if (history == null) continue;
                String reason = loopTermReasons != null ? loopTermReasons.get(loop.getName()) : "unknown";
                net.agentensemble.trace.LoopTrace.LoopTraceBuilder ltb = net.agentensemble.trace.LoopTrace.builder()
                        .loopName(loop.getName())
                        .iterationsRun(history.size())
                        .maxIterations(loop.getMaxIterations())
                        .terminationReason(reason != null ? reason : "unknown")
                        .onMaxIterations(loop.getOnMaxIterations().name())
                        .outputMode(loop.getOutputMode().name())
                        .memoryMode(loop.getMemoryMode().name());
                for (Map<String, net.agentensemble.task.TaskOutput> iter : history) {
                    ltb.iteration(List.copyOf(iter.keySet()));
                }
                builder.loopTrace(ltb.build());
            }
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
     * Resolve template variables and synthesize agents for every {@link net.agentensemble.workflow.loop.Loop}'s
     * body tasks. Mirrors the {@link #resolveTasks} + {@link #resolveAgents} pipeline applied
     * to top-level tasks, but operates per-loop on the loop's body.
     *
     * <p>Each loop's body tasks have their {@code description} and {@code expectedOutput}
     * template-substituted and (for AI-backed tasks) an agent is synthesized when none is
     * explicitly set. The resolved loop is rebuilt via {@link
     * net.agentensemble.workflow.loop.Loop#toBuilder()} preserving the loop's name,
     * predicate, and configuration.
     *
     * <p>Body-task {@code context()} references within the loop are remapped to the resolved
     * instances (same identity-rewrite pattern used by {@link #resolveTasks}).
     */
    private List<net.agentensemble.workflow.loop.Loop> resolveLoops(
            List<net.agentensemble.workflow.loop.Loop> loops,
            Map<String, String> resolvedInputs,
            ChatModel ensembleLlm,
            ChatModel rawChatLanguageModel,
            IdentityHashMap<Task, Task> originalToResolvedTopLevelTask) {
        if (loops == null || loops.isEmpty()) {
            return List.of();
        }
        List<net.agentensemble.workflow.loop.Loop> result = new ArrayList<>(loops.size());
        for (net.agentensemble.workflow.loop.Loop loop : loops) {
            // Pass 1: template-substitute body task description/expectedOutput.
            IdentityHashMap<Task, Task> originalToTemplate = new IdentityHashMap<>();
            List<Task> templateBody = new ArrayList<>(loop.getBody().size());
            for (Task t : loop.getBody()) {
                Task resolved = t.toBuilder()
                        .description(TemplateResolver.resolve(t.getDescription(), resolvedInputs))
                        .expectedOutput(TemplateResolver.resolve(t.getExpectedOutput(), resolvedInputs))
                        .build();
                originalToTemplate.put(t, resolved);
                templateBody.add(resolved);
            }
            // Remap intra-body context references to the template-resolved instances.
            List<Task> rewiredTemplateBody = new ArrayList<>(templateBody.size());
            IdentityHashMap<Task, Task> templateToRewired = new IdentityHashMap<>();
            for (int i = 0; i < templateBody.size(); i++) {
                Task tr = templateBody.get(i);
                List<Task> ctx = tr.getContext();
                Task rewired;
                if (ctx == null || ctx.isEmpty()) {
                    rewired = tr;
                } else {
                    List<Task> newCtx = new ArrayList<>(ctx.size());
                    for (Task c : ctx) {
                        Task remapped = originalToTemplate.getOrDefault(c, c);
                        newCtx.add(templateToRewired.getOrDefault(remapped, remapped));
                    }
                    rewired = tr.toBuilder().context(newCtx).build();
                }
                templateToRewired.put(tr, rewired);
                rewiredTemplateBody.add(rewired);
            }

            // Pass 2: synthesize agents for body tasks that need them.
            List<Task> agentBody = resolveAgents(rewiredTemplateBody, ensembleLlm, rawChatLanguageModel);

            // Remap the loop's outer-DAG context() entries from the original Task identities
            // (which are no longer in the parallel executor's task list after agent synthesis)
            // to the resolved instances. Without this remap the dependency graph treats the
            // shadow loop task as a root and runs it immediately.
            List<Task> remappedOuterContext;
            List<Task> originalOuterContext = loop.getContext();
            if (originalOuterContext == null || originalOuterContext.isEmpty()) {
                remappedOuterContext = List.of();
            } else {
                remappedOuterContext = new ArrayList<>(originalOuterContext.size());
                for (Task ctx : originalOuterContext) {
                    Task resolved = originalToResolvedTopLevelTask.get(ctx);
                    remappedOuterContext.add(resolved != null ? resolved : ctx);
                }
            }

            net.agentensemble.workflow.loop.Loop resolvedLoop = loop.toBuilder()
                    .clearBody()
                    .body(agentBody)
                    .clearContext()
                    .context(remappedOuterContext)
                    .build();
            result.add(resolvedLoop);
        }
        return result;
    }

    /**
     * Resolve a {@link net.agentensemble.workflow.graph.Graph}'s state Tasks by applying
     * template substitution and agent synthesis to each state's Task. Mirrors
     * {@link #resolveLoops} but operates per-state rather than per-body.
     */
    private net.agentensemble.workflow.graph.Graph resolveGraph(
            net.agentensemble.workflow.graph.Graph graph,
            Map<String, String> resolvedInputs,
            ChatModel ensembleLlm,
            ChatModel rawChatLanguageModel) {
        if (graph == null) {
            return null;
        }
        // Pass 1: template-substitute each state Task's description / expectedOutput.
        java.util.LinkedHashMap<String, Task> templateStates = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Task> entry : graph.getStates().entrySet()) {
            Task t = entry.getValue();
            Task resolved = t.toBuilder()
                    .description(TemplateResolver.resolve(t.getDescription(), resolvedInputs))
                    .expectedOutput(TemplateResolver.resolve(t.getExpectedOutput(), resolvedInputs))
                    .build();
            templateStates.put(entry.getKey(), resolved);
        }

        // Pass 2: synthesize agents for state Tasks via resolveAgents. We pass each state's
        // Task as a singleton list so the existing pipeline applies (rate limits, agent
        // synthesis, deterministic-task pass-through). State Tasks are independent of each
        // other -- they have no context() linking, so order doesn't matter for resolution.
        java.util.LinkedHashMap<String, Task> agentStates = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Task> entry : templateStates.entrySet()) {
            List<Task> resolved = resolveAgents(List.of(entry.getValue()), ensembleLlm, rawChatLanguageModel);
            agentStates.put(entry.getKey(), resolved.get(0));
        }

        // Rebuild the Graph with the resolved state Tasks. Edges, start, maxSteps,
        // onMaxSteps, noFeedbackStates all carry over unchanged.
        net.agentensemble.workflow.graph.Graph.GraphBuilder gb = net.agentensemble.workflow.graph.Graph.builder()
                .name(graph.getName())
                .start(graph.getStartState())
                .maxSteps(graph.getMaxSteps())
                .onMaxSteps(graph.getOnMaxSteps())
                .injectFeedbackOnRevisit(graph.isInjectFeedbackOnRevisit());
        for (Map.Entry<String, Task> entry : agentStates.entrySet()) {
            if (graph.getNoFeedbackStates() != null
                    && graph.getNoFeedbackStates().contains(entry.getKey())) {
                gb.stateNoFeedback(entry.getKey(), entry.getValue());
            } else {
                gb.state(entry.getKey(), entry.getValue());
            }
        }
        for (net.agentensemble.workflow.graph.GraphEdge edge : graph.getEdges()) {
            gb.edge(edge.getFrom(), edge.getTo(), edge.getCondition(), edge.getConditionDescription());
        }
        return gb.build();
    }

    /**
     * Execute a resolved {@link net.agentensemble.workflow.graph.Graph} via
     * {@link net.agentensemble.workflow.graph.GraphExecutor} and assemble an
     * {@link EnsembleOutput} that exposes:
     * <ul>
     *   <li>{@code taskOutputs} — one entry per step, in execution order;</li>
     *   <li>{@code taskOutputIndex} — identity-keyed by the original state Task instances,
     *       holding the LAST output for each state (matches the Loop projection contract);</li>
     *   <li>{@code graphHistory} — full per-step trace for downstream consumers;</li>
     *   <li>{@code graphTerminationReason} — {@code "terminal"} or {@code "maxSteps"};</li>
     *   <li>{@code graphTerminatedByMaxSteps} — true when {@code RETURN_WITH_FLAG} fired.</li>
     * </ul>
     */
    private EnsembleOutput executeGraph(
            net.agentensemble.workflow.graph.Graph resolvedGraph,
            List<Agent> derivedAgents,
            ExecutionContext executionContext) {
        List<DelegationPolicy> policies = delegationPolicies != null ? delegationPolicies : List.of();
        SequentialWorkflowExecutor bodyRunner =
                new SequentialWorkflowExecutor(derivedAgents, Math.max(maxDelegationDepth, 1), policies);
        net.agentensemble.workflow.graph.GraphExecutor executor =
                new net.agentensemble.workflow.graph.GraphExecutor(bodyRunner);

        Instant graphStart = Instant.now();
        net.agentensemble.workflow.graph.GraphExecutionResult result =
                executor.execute(resolvedGraph, executionContext);
        Duration totalDuration = Duration.between(graphStart, Instant.now());

        // Flat task outputs in step order
        List<net.agentensemble.task.TaskOutput> taskOutputs =
                new ArrayList<>(result.getHistory().size());
        for (net.agentensemble.workflow.graph.GraphStep step : result.getHistory()) {
            taskOutputs.add(step.getOutput());
        }

        // Remap projectedOutputs from resolved-state-Task identities back to the original
        // state-Task identities that the user holds, so EnsembleOutput.getOutput(originalTask)
        // works as documented. Walk by state name: graph (original) and resolvedGraph (resolved)
        // share state names.
        IdentityHashMap<Task, net.agentensemble.task.TaskOutput> originalKeyedIndex = new IdentityHashMap<>();
        if (graph != null && graph.getStates() != null) {
            for (Map.Entry<String, Task> origEntry : graph.getStates().entrySet()) {
                Task resolvedTask = resolvedGraph.getStates().get(origEntry.getKey());
                if (resolvedTask != null) {
                    net.agentensemble.task.TaskOutput out =
                            result.getProjectedOutputs().get(resolvedTask);
                    if (out != null) {
                        originalKeyedIndex.put(origEntry.getValue(), out);
                    }
                }
            }
        }

        // RETURN_WITH_FLAG semantic: only flag when the configured action AND we hit maxSteps
        Boolean maxStepsFlag = null;
        if (result.stoppedByMaxSteps()
                && resolvedGraph.getOnMaxSteps() == net.agentensemble.workflow.graph.MaxStepsAction.RETURN_WITH_FLAG) {
            maxStepsFlag = Boolean.TRUE;
        }

        String finalRaw = taskOutputs.isEmpty()
                ? ""
                : taskOutputs.get(taskOutputs.size() - 1).getRaw();
        int totalToolCalls = taskOutputs.stream()
                .mapToInt(net.agentensemble.task.TaskOutput::getToolCallCount)
                .sum();

        return EnsembleOutput.builder()
                .raw(finalRaw)
                .taskOutputs(taskOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .taskOutputIndex(originalKeyedIndex)
                .graphHistory(result.getHistory())
                .graphTerminationReason(result.getTerminationReason())
                .graphTerminatedByMaxSteps(maxStepsFlag)
                .build();
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
        ChatModel active = getActiveModel();
        if (active != null) return active;
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

        // Shared task/tool registries (name -> instance).
        private Map<String, Task> sharedTaskRegistry = Map.of();
        private Map<String, AgentTool> sharedToolRegistry = Map.of();

        // Scheduled tasks accumulator (same pattern as phases and sharedCapabilities).
        private List<ScheduledTask> scheduledTasks = List.of();

        // Broadcast handler for scheduled task results.
        private BroadcastHandler broadcastHandler;

        // Audit sinks accumulator (same pattern as phases and sharedCapabilities).
        private List<AuditSink> auditSinks = List.of();

        // Auto-directive rules accumulator (same pattern as phases and sharedCapabilities).
        private List<AutoDirectiveRule> autoDirectiveRules = List.of();

        /**
         * Add a scheduled task that fires on the given schedule in long-running mode.
         *
         * <p>May be called multiple times; each call adds a task to the list.
         *
         * @param scheduledTask the scheduled task to register; must not be null
         * @return this builder
         */
        public EnsembleBuilder scheduledTask(ScheduledTask scheduledTask) {
            Objects.requireNonNull(scheduledTask, "scheduledTask must not be null");
            List<ScheduledTask> updated = new ArrayList<>(this.scheduledTasks);
            updated.add(scheduledTask);
            this.scheduledTasks = List.copyOf(updated);
            return this;
        }

        /**
         * Set the broadcast handler for scheduled task results.
         *
         * <p>When a {@link ScheduledTask} has a non-null {@code broadcastTo}, the handler
         * is invoked with the topic name and result string after the task completes.
         *
         * @param broadcastHandler the broadcast handler; may be null to disable broadcasting
         * @return this builder
         */
        public EnsembleBuilder broadcastHandler(BroadcastHandler broadcastHandler) {
            this.broadcastHandler = broadcastHandler;
            return this;
        }

        /**
         * Add an audit sink for writing audit records.
         *
         * <p>May be called multiple times; each call adds a sink to the list. Used in
         * conjunction with {@code auditPolicy(AuditPolicy)} to enable the leveled audit trail.
         *
         * @param sink the audit sink to register; must not be null
         * @return this builder
         */
        public EnsembleBuilder auditSink(AuditSink sink) {
            Objects.requireNonNull(sink, "auditSink must not be null");
            List<AuditSink> updated = new ArrayList<>(this.auditSinks);
            updated.add(sink);
            this.auditSinks = List.copyOf(updated);
            return this;
        }

        /**
         * Add an auto-directive rule that fires a control plane directive when a
         * condition is met during task completion.
         *
         * <p>May be called multiple times; each call adds a rule to the list.
         *
         * @param rule the auto-directive rule; must not be null
         * @return this builder
         */
        public EnsembleBuilder autoDirectiveRule(AutoDirectiveRule rule) {
            Objects.requireNonNull(rule, "autoDirectiveRule must not be null");
            List<AutoDirectiveRule> updated = new ArrayList<>(this.autoDirectiveRules);
            updated.add(rule);
            this.autoDirectiveRules = List.copyOf(updated);
            return this;
        }

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
            return shareTask(name, task, new String[0]);
        }

        /**
         * Share a named task with the network, attaching discovery tags.
         *
         * <p>Other ensembles can discover and delegate work to this task via
         * {@code NetworkTask.from(ensembleName, taskName)}.
         *
         * @param name unique name for this shared task
         * @param task the task definition to share
         * @param tags optional classification tags for capability-based discovery
         * @return this builder
         */
        public EnsembleBuilder shareTask(String name, Task task, String... tags) {
            Objects.requireNonNull(name, "Shared task name must not be null");
            Objects.requireNonNull(task, "Shared task must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Shared task name must not be blank");
            }
            String description = task.getDescription() != null ? task.getDescription() : "";
            List<SharedCapability> updated = new ArrayList<>(this.sharedCapabilities);
            updated.add(new SharedCapability(name, description, SharedCapabilityType.TASK, List.of(tags)));
            this.sharedCapabilities = List.copyOf(updated);
            // Also store the Task instance for request handling
            Map<String, Task> updatedRegistry = new HashMap<>(this.sharedTaskRegistry);
            updatedRegistry.put(name, task);
            this.sharedTaskRegistry = Map.copyOf(updatedRegistry);
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
            return shareTool(name, tool, new String[0]);
        }

        /**
         * Share a named tool with the network, attaching discovery tags.
         *
         * <p>Other ensembles' agents can invoke this tool remotely via
         * {@code NetworkTool.from(ensembleName, toolName)}.
         *
         * @param name unique name for this shared tool
         * @param tool the tool to share
         * @param tags optional classification tags for capability-based discovery
         * @return this builder
         */
        public EnsembleBuilder shareTool(String name, AgentTool tool, String... tags) {
            Objects.requireNonNull(name, "Shared tool name must not be null");
            Objects.requireNonNull(tool, "Shared tool must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Shared tool name must not be blank");
            }
            String description = tool.description() != null ? tool.description() : "";
            List<SharedCapability> updated = new ArrayList<>(this.sharedCapabilities);
            updated.add(new SharedCapability(name, description, SharedCapabilityType.TOOL, List.of(tags)));
            this.sharedCapabilities = List.copyOf(updated);
            // Also store the AgentTool instance for request handling
            Map<String, AgentTool> updatedRegistry = new HashMap<>(this.sharedToolRegistry);
            updatedRegistry.put(name, tool);
            this.sharedToolRegistry = Map.copyOf(updatedRegistry);
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
         * Register a lambda that is called after each {@link net.agentensemble.workflow.loop.Loop}
         * iteration completes (before the loop's predicate is evaluated). Useful for live
         * progress reporting and per-iteration metrics.
         *
         * @param handler the lambda to invoke per loop iteration; must not be null
         * @return this builder
         */
        public EnsembleBuilder onLoopIterationCompleted(
                Consumer<net.agentensemble.callback.LoopIterationCompletedEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onLoopIterationCompleted(net.agentensemble.callback.LoopIterationCompletedEvent event) {
                    handler.accept(event);
                }
            });
        }

        /**
         * Register a lambda that is called after each {@link net.agentensemble.workflow.graph.Graph}
         * state's Task completes, with the routed-to next state already determined. Useful
         * for live progress reporting and per-state metrics.
         *
         * @param handler the lambda to invoke per graph step; must not be null
         * @return this builder
         */
        public EnsembleBuilder onGraphStateCompleted(
                Consumer<net.agentensemble.callback.GraphStateCompletedEvent> handler) {
            Objects.requireNonNull(handler, "handler");
            return listener(new EnsembleListener() {
                @Override
                public void onGraphStateCompleted(net.agentensemble.callback.GraphStateCompletedEvent event) {
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
