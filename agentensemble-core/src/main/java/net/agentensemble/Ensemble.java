package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.metrics.ExecutionMetrics;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.ratelimit.RateLimitedChatModel;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewPolicy;
import net.agentensemble.synthesis.AgentSynthesizer;
import net.agentensemble.synthesis.SynthesisContext;
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
     * Optional ensemble-level streaming model for token-by-token generation of final responses.
     *
     * <p>When set, agents that produce a direct LLM answer (no tool loop) stream each token
     * through {@link net.agentensemble.callback.EnsembleListener#onToken(TokenEvent)} and,
     * when using {@link #webDashboard(EnsembleDashboard)}, over the WebSocket wire protocol.
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
        IdentityHashMap<Agent, Boolean> seen = new IdentityHashMap<>();
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
            log.info("Ensemble run initializing | Workflow config: {} | Tasks: {}", workflow, tasks.size());
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

            // Step 3: Resolve agents -- synthesize for tasks without an explicit agent
            List<Task> agentResolvedTasks = resolveAgents(templateResolvedTasks, effectiveChatModel);

            // Step 4: Derive unique agents (for delegation context, trace, hierarchical)
            List<Agent> derivedAgents = deriveAgents(agentResolvedTasks);

            log.info(
                    "Ensemble run started | Workflow: {} | Tasks: {} | Agents: {}",
                    effectiveWorkflow,
                    agentResolvedTasks.size(),
                    derivedAgents.size());

            // Step 5: Memory context is disabled in v2.0.0; scoped memory is handled
            // via MemoryStore in ExecutionContext (set on Ensemble via .memoryStore()).
            MemoryContext memoryContext = MemoryContext.disabled();

            if (memoryStore != null) {
                log.info("MemoryStore enabled for task-scoped memory");
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
                    streamingChatLanguageModel);

            if (reviewHandler != null) {
                log.info("ReviewHandler enabled | Policy: {}", reviewPolicy);
            }

            // Step 7: Notify dashboard that execution is about to begin.
            // This fires ensemble_started before the first task runs.
            if (dashboard != null) {
                try {
                    dashboard.onEnsembleStarted(
                            ensembleId, runStartedAt, agentResolvedTasks.size(), effectiveWorkflow.name());
                } catch (Exception e) {
                    log.warn("Dashboard.onEnsembleStarted threw an exception: {}", e.getMessage(), e);
                }
            }

            // Step 8: Select and execute WorkflowExecutor
            WorkflowExecutor executor = selectExecutor(effectiveWorkflow, derivedAgents);
            EnsembleOutput output = executor.execute(agentResolvedTasks, executionContext);

            Instant runCompletedAt = Instant.now();

            log.info(
                    "Ensemble run completed | Duration: {} | Tasks: {} | Tool calls: {}",
                    output.getTotalDuration(),
                    output.getTaskOutputs().size(),
                    output.getTotalToolCalls());

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
                    log.warn("Dashboard.onEnsembleCompleted threw an exception: {}", e.getMessage(), e);
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
            java.util.Map<Task, net.agentensemble.task.TaskOutput> executorIndex = output.getTaskOutputIndex();
            java.util.Map<Task, net.agentensemble.task.TaskOutput> originalIndex = null;
            if (executorIndex != null) {
                IdentityHashMap<Task, net.agentensemble.task.TaskOutput> idx = new IdentityHashMap<>();
                for (int i = 0; i < tasks.size() && i < agentResolvedTasks.size(); i++) {
                    Task original = tasks.get(i);
                    Task agentResolved = agentResolvedTasks.get(i);
                    net.agentensemble.task.TaskOutput taskOut = executorIndex.get(agentResolved);
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
                    log.warn("TraceExporter threw exception during export: {}", e.getMessage(), e);
                }
            }

            return outputWithTrace;

        } catch (ValidationException e) {
            log.warn("Ensemble validation failed: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Ensemble run failed", e);
            throw e;
        } finally {
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
     * @param templateResolvedTasks tasks after template variable resolution
     * @param ensembleLlm           the effective ensemble-level chat model (may be rate-limited)
     */
    private List<Task> resolveAgents(List<Task> templateResolvedTasks, ChatModel ensembleLlm) {
        // Pass 1: synthesize agents; build old-identity -> new-identity map.
        IdentityHashMap<Task, Task> oldToNew = new IdentityHashMap<>();
        List<Task> firstPass = new ArrayList<>(templateResolvedTasks.size());
        for (Task task : templateResolvedTasks) {
            Task agentResolved;
            if (task.getAgent() != null) {
                // Explicit agent: use as-is (power-user escape hatch)
                agentResolved = task;
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
                    // Task has a rate limit but no task-level model: apply it to the ensemble model
                    if (ensembleLlm == null) {
                        throw new ValidationException("No LLM available for task '" + task.getDescription()
                                + "'. Provide a task-level chatLanguageModel or an ensemble-level chatLanguageModel.");
                    }
                    llm = RateLimitedChatModel.of(ensembleLlm, task.getRateLimit());
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

                log.debug(
                        "Synthesized agent '{}' for task '{}'",
                        agentResolved.getAgent().getRole(),
                        truncate(task.getDescription(), 80));
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
                                .filter(t -> t instanceof net.agentensemble.tool.AgentTool)
                                .map(t -> ((net.agentensemble.tool.AgentTool) t).name())
                                .collect(Collectors.toList()))
                        .allowDelegation(agent.isAllowDelegation())
                        .build())
                .collect(Collectors.toList());

        List<TaskTrace> taskTraces = output.getTaskOutputs().stream()
                .map(net.agentensemble.task.TaskOutput::getTrace)
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

    // ========================
    // Custom builder methods (lambda convenience for event listeners)
    // ========================

    /**
     * Extends the Lombok-generated builder with lambda convenience methods for registering
     * event listeners without implementing the full {@link EnsembleListener} interface.
     */
    public static class EnsembleBuilder {

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
         * Register a {@link EnsembleDashboard} (e.g. {@code WebDashboard}) as the live execution
         * dashboard for this ensemble run.
         *
         * <p>This convenience method performs three operations in one call:
         * <ol>
         *   <li><strong>Auto-start</strong> -- calls {@link EnsembleDashboard#start()} if the
         *       dashboard is not already running.</li>
         *   <li><strong>Streaming</strong> -- registers {@link EnsembleDashboard#streamingListener()}
         *       as an ensemble listener so execution events are broadcast over WebSocket.</li>
         *   <li><strong>Review gates</strong> -- sets {@link EnsembleDashboard#reviewHandler()}
         *       as the ensemble's review handler so that human-in-the-loop decisions are routed
         *       through the browser dashboard.</li>
         * </ol>
         *
         * <p>Usage:
         * <pre>
         * WebDashboard dashboard = WebDashboard.onPort(7329);
         *
         * Ensemble.builder()
         *     .chatLanguageModel(model)
         *     .webDashboard(dashboard)
         *     .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
         *     .task(Task.of("Research AI trends"))
         *     .build()
         *     .run();
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
            if (!dashboard.isRunning()) {
                dashboard.start();
            }
            // Store the dashboard reference so Ensemble.runWithInputs() can call the
            // onEnsembleStarted/onEnsembleCompleted lifecycle hooks.
            this.dashboard(dashboard);
            return listener(dashboard.streamingListener()).reviewHandler(dashboard.reviewHandler());
        }
    }
}
