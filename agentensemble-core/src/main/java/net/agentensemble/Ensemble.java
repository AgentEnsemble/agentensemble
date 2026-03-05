package net.agentensemble;

import dev.langchain4j.model.chat.ChatModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.agentensemble.callback.ToolCallEvent;
import net.agentensemble.config.TemplateResolver;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.metrics.CostConfiguration;
import net.agentensemble.metrics.ExecutionMetrics;
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
 * An ensemble of agents collaborating on a sequence of tasks.
 *
 * The ensemble orchestrates task execution according to the configured workflow
 * strategy, passing context between tasks as declared in each task's context list.
 *
 * Example:
 * <pre>
 * EnsembleOutput result = Ensemble.builder()
 *     .agent(researcher)
 *     .agent(writer)
 *     .task(researchTask)
 *     .task(writeTask)
 *     .workflow(Workflow.SEQUENTIAL)
 *     .build()
 *     .run();
 * </pre>
 */
@Builder
@Getter
public class Ensemble {

    private static final Logger log = LoggerFactory.getLogger(Ensemble.class);

    /** All agents participating in this ensemble. */
    @Singular
    private final List<Agent> agents;

    /** All tasks to execute. */
    @Singular
    private final List<Task> tasks;

    /** How tasks are executed. Default: SEQUENTIAL. */
    @Builder.Default
    private final Workflow workflow = Workflow.SEQUENTIAL;

    /**
     * Optional LLM for the Manager agent in hierarchical workflow.
     * If not set, defaults to the first registered agent's LLM.
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
     * system prompt and tasks in the user prompt, matching the built-in behaviour.
     * Provide a custom implementation to inject domain-specific context, alter persona, or
     * completely replace the prompts without forking framework internals.
     *
     * <p>Only exercised when {@code workflow = Workflow.HIERARCHICAL}; sequential and parallel
     * workflows are unaffected.
     *
     * <pre>
     * Ensemble.builder()
     *     .workflow(Workflow.HIERARCHICAL)
     *     .managerPromptStrategy(new ManagerPromptStrategy() {
     *         {@literal @}Override
     *         public String buildSystemPrompt(ManagerPromptContext ctx) {
     *             return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx)
     *                 + "\n\nAlways prefer the Analyst for data tasks.";
     *         }
     *         {@literal @}Override
     *         public String buildUserPrompt(ManagerPromptContext ctx) {
     *             return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
     *         }
     *     })
     *     .build();
     * </pre>
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
     * tool call (Java 21), making I/O-bound tools (HTTP, subprocess) cheap without
     * blocking platform threads.
     *
     * <p>Provide a bounded {@link java.util.concurrent.ExecutorService} to cap
     * concurrency for rate-limited APIs:
     * <pre>
     * Ensemble.builder()
     *     .toolExecutor(Executors.newFixedThreadPool(4))
     *     .build();
     * </pre>
     *
     * <p>Default: virtual-thread-per-task executor.
     */
    @Builder.Default
    private final Executor toolExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Metrics backend for recording tool execution measurements.
     *
     * <p>Implement {@link ToolMetrics} or use the {@code MicrometerToolMetrics} adapter
     * from the {@code agentensemble-metrics-micrometer} module to integrate with your
     * metrics infrastructure. The default discards all measurements.
     *
     * <pre>
     * Ensemble.builder()
     *     .toolMetrics(new MicrometerToolMetrics(meterRegistry))
     *     .build();
     * </pre>
     *
     * <p>Default: {@link NoOpToolMetrics} (no-op).
     */
    @Builder.Default
    private final ToolMetrics toolMetrics = NoOpToolMetrics.INSTANCE;

    /**
     * Event listeners that will be notified of task lifecycle events during execution.
     *
     * Use the builder's {@code EnsembleBuilder.listener(EnsembleListener)} or
     * {@code EnsembleBuilder.listeners(Collection)} methods to add full listener
     * implementations, or the lambda convenience methods:
     * {@link EnsembleBuilder#onTaskStart}, {@link EnsembleBuilder#onTaskComplete},
     * {@link EnsembleBuilder#onTaskFailed}, {@link EnsembleBuilder#onToolCall}.
     */
    @Singular
    private final List<EnsembleListener> listeners;

    /**
     * Template variable inputs for task description and expected output substitution.
     *
     * Use {@code .input("key", "value")} on the builder to supply individual entries, or
     * {@code .inputs(map)} to supply a batch. These values are applied on every {@link #run()}
     * call. When the same key is also present in a {@link #run(Map)} invocation, the run-time
     * value takes precedence over the builder value.
     *
     * Example:
     * <pre>
     * Ensemble.builder()
     *     .agent(researcher)
     *     .task(researchTask)
     *     .input("topic", "AI agents")
     *     .build()
     *     .run();
     * </pre>
     */
    @Singular("input")
    private final Map<String, String> inputs;

    /**
     * Optional guardrails for the delegation graph in hierarchical workflow.
     *
     * <p>When set, a {@code HierarchicalConstraintEnforcer} is created for each run. The enforcer
     * is prepended to the delegation policy chain to enforce
     * pre-delegation checks: allowed workers, per-worker caps, global delegation cap, and stage
     * ordering. After the Manager finishes, the enforcer validates that all required workers were
     * called; if not, a {@link net.agentensemble.exception.ConstraintViolationException} is thrown
     * carrying the violations and any partial worker outputs.
     *
     * <p>Only exercised when {@code workflow = Workflow.HIERARCHICAL}. Ignored for sequential and
     * parallel workflows.
     *
     * <p>Default: null (no constraints).
     *
     * <pre>
     * Ensemble.builder()
     *     .workflow(Workflow.HIERARCHICAL)
     *     .hierarchicalConstraints(HierarchicalConstraints.builder()
     *         .requiredWorker("Researcher")
     *         .allowedWorker("Researcher")
     *         .allowedWorker("Analyst")
     *         .globalMaxDelegations(5)
     *         .build())
     *     .build();
     * </pre>
     */
    private final HierarchicalConstraints hierarchicalConstraints;

    /**
     * Delegation policies evaluated before each delegation attempt.
     *
     * <p>Policies run after built-in guards (self-delegation, depth limit, unknown agent)
     * and before the worker agent executes. They are evaluated in registration order.
     * A {@link DelegationPolicy} can {@link net.agentensemble.delegation.policy.DelegationPolicyResult#allow() allow},
     * {@link net.agentensemble.delegation.policy.DelegationPolicyResult#reject(String) reject}, or
     * {@link net.agentensemble.delegation.policy.DelegationPolicyResult#modify(net.agentensemble.delegation.DelegationRequest) modify}
     * each delegation request.
     *
     * <p>Use the builder's {@link Ensemble.EnsembleBuilder#delegationPolicy(DelegationPolicy)} method
     * to register individual policies (callable multiple times), or
     * {@link Ensemble.EnsembleBuilder#delegationPolicies(java.util.Collection) delegationPolicies(Collection)} for
     * batch registration. Multiple calls accumulate; none overwrite each other.
     *
     * <p>Policies apply to both peer delegation ({@code AgentDelegationTool}) and hierarchical
     * delegation ({@code DelegateTaskTool}).
     *
     * Example:
     * <pre>
     * Ensemble.builder()
     *     .delegationPolicy((request, ctx) -> {
     *         if ("UNKNOWN".equals(request.getScope().get("project_key"))) {
     *             return DelegationPolicyResult.reject("project_key must not be UNKNOWN");
     *         }
     *         return DelegationPolicyResult.allow();
     *     })
     *     .build();
     * </pre>
     */
    @Singular("delegationPolicy")
    private final List<DelegationPolicy> delegationPolicies;

    /**
     * Optional per-token cost rates for cost estimation.
     *
     * <p>When set, each task's LLM token usage is converted to a monetary cost estimate
     * and made available on {@link net.agentensemble.metrics.TaskMetrics#getCostEstimate()}
     * and {@link net.agentensemble.metrics.ExecutionMetrics#getTotalCostEstimate()}.
     *
     * <p>Token counts are derived from {@code ChatResponse} usage metadata. When the LLM
     * provider does not populate token counts, cost estimates are omitted.
     *
     * <pre>
     * Ensemble.builder()
     *     .costConfiguration(CostConfiguration.builder()
     *         .inputTokenRate(new BigDecimal("0.0000025"))
     *         .outputTokenRate(new BigDecimal("0.0000100"))
     *         .build())
     *     .build();
     * </pre>
     *
     * <p>Default: null (cost estimation disabled).
     */
    private final CostConfiguration costConfiguration;

    /**
     * Optional exporter called at the end of each {@link #run()} invocation with the
     * complete {@link ExecutionTrace}.
     *
     * <p>Use {@link net.agentensemble.trace.export.JsonTraceExporter} to write traces to
     * JSON files, or implement {@link ExecutionTraceExporter} for custom destinations.
     *
     * <pre>
     * Ensemble.builder()
     *     .traceExporter(new JsonTraceExporter(Path.of("traces/")))
     *     .build();
     * </pre>
     *
     * <p>Default: null (no automatic export).
     */
    private final ExecutionTraceExporter traceExporter;

    /**
     * Depth of data collection during each run.
     *
     * <p>The default is {@link CaptureMode#OFF} (current behavior: prompts, tool args/results,
     * timing, token counts). Set to {@link CaptureMode#STANDARD} to also capture full LLM
     * message history per iteration and wire memory operation counts into the trace. Set to
     * {@link CaptureMode#FULL} to additionally auto-export traces to {@code ./traces/} and
     * enrich tool I/O with parsed JSON arguments.
     *
     * <p>The effective capture mode is resolved in the following order (first wins):
     * <ol>
     *   <li>This field when not {@link CaptureMode#OFF}</li>
     *   <li>JVM system property {@code agentensemble.captureMode}</li>
     *   <li>Environment variable {@code AGENTENSEMBLE_CAPTURE_MODE}</li>
     *   <li>{@link CaptureMode#OFF}</li>
     * </ol>
     *
     * <p>This means the same application can be put into debug mode without any code changes:
     * <pre>
     * java -Dagentensemble.captureMode=FULL -jar my-app.jar
     * </pre>
     * or:
     * <pre>
     * AGENTENSEMBLE_CAPTURE_MODE=STANDARD java -jar my-app.jar
     * </pre>
     *
     * <p>{@link CaptureMode#OFF} has zero performance impact beyond what the base trace
     * infrastructure already adds. {@link CaptureMode} is orthogonal to {@code verbose}
     * and {@code traceExporter}; all three can be combined independently.
     *
     * <p>Default: {@link CaptureMode#OFF}.
     */
    @Builder.Default
    private final CaptureMode captureMode = CaptureMode.OFF;

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
     * Use this overload when the same {@code Ensemble} instance is executed multiple times
     * with different variable values (for example, iterating over a list of topics or weeks).
     * For the common single-run case, prefer setting inputs on the builder via
     * {@code .input("key", "value")} and calling the no-arg {@link #run()}.
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

    private EnsembleOutput runWithInputs(Map<String, String> resolvedInputs) {
        String ensembleId = UUID.randomUUID().toString();
        MDC.put("ensemble.id", ensembleId);
        Instant runStartedAt = Instant.now();

        // Resolve the effective capture mode: builder field wins unless OFF, then
        // fall back to system property / env var / OFF via CaptureMode.resolve().
        CaptureMode effectiveCaptureMode = CaptureMode.resolve(captureMode);
        if (effectiveCaptureMode != CaptureMode.OFF) {
            log.info("CaptureMode active: {}", effectiveCaptureMode);
        }

        try {
            log.info(
                    "Ensemble run started | Workflow: {} | Tasks: {} | Agents: {}",
                    workflow,
                    tasks.size(),
                    agents.size());
            log.debug("Input variables: {}", resolvedInputs);

            // Step 1: Validate configuration
            new EnsembleValidator(this).validate();

            // Step 2: Resolve template variables in task descriptions and expected outputs
            List<Task> resolvedTasks = resolveTasks(resolvedInputs);

            // Step 3: Memory context is disabled in v2.0.0; scoped memory is handled
            // via MemoryStore in ExecutionContext (set on Ensemble via .memoryStore()).
            MemoryContext memoryContext = MemoryContext.disabled();

            if (memoryStore != null) {
                log.info("MemoryStore enabled for task-scoped memory");
            }

            // Step 4: Build execution context -- bundles memory, verbosity, listeners,
            // tool executor, tool metrics, cost configuration, capture mode, and memoryStore
            ExecutionContext executionContext = ExecutionContext.of(
                    memoryContext,
                    verbose,
                    listeners != null ? listeners : List.of(),
                    toolExecutor,
                    toolMetrics,
                    costConfiguration,
                    effectiveCaptureMode,
                    memoryStore);

            // Step 5: Select and execute WorkflowExecutor
            WorkflowExecutor executor = selectExecutor();
            EnsembleOutput output = executor.execute(resolvedTasks, executionContext);

            Instant runCompletedAt = Instant.now();

            log.info(
                    "Ensemble run completed | Duration: {} | Tasks: {} | Tool calls: {}",
                    output.getTotalDuration(),
                    output.getTaskOutputs().size(),
                    output.getTotalToolCalls());

            // Step 6: Build ExecutionTrace from collected task traces
            ExecutionTrace trace = buildExecutionTrace(
                    ensembleId, runStartedAt, runCompletedAt, resolvedInputs, output, effectiveCaptureMode);

            // Step 7: Attach trace to EnsembleOutput
            EnsembleOutput outputWithTrace = EnsembleOutput.builder()
                    .raw(output.getRaw())
                    .taskOutputs(output.getTaskOutputs())
                    .totalDuration(output.getTotalDuration())
                    .totalToolCalls(output.getTotalToolCalls())
                    .metrics(output.getMetrics())
                    .trace(trace)
                    .build();

            // Step 8: Export trace.
            // When captureMode == FULL and no explicit exporter is configured,
            // auto-register a JsonTraceExporter writing to ./traces/
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

    private ExecutionTrace buildExecutionTrace(
            String ensembleId,
            Instant startedAt,
            Instant completedAt,
            Map<String, String> resolvedInputs,
            EnsembleOutput output,
            CaptureMode effectiveCaptureMode) {

        List<AgentSummary> agentSummaries = agents.stream()
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
                .workflow(workflow.name())
                .captureMode(effectiveCaptureMode)
                .startedAt(startedAt)
                .completedAt(completedAt)
                // Use Duration.between(startedAt, completedAt) so that totalDuration is
                // consistent with the startedAt and completedAt timestamps on this trace.
                // output.getTotalDuration() covers workflow execution only and would not
                // match completedAt - startedAt (which also includes validation, template
                // resolution, and memory setup).
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
     *
     * Two-pass approach: first resolve descriptions/expectedOutputs and build an
     * original-to-resolved mapping, then rewrite context lists so they reference the
     * resolved Task instances. Without the second pass, context lookups in
     * SequentialWorkflowExecutor would fail when a task with a template variable is
     * also referenced by another task's context list (value equality would not match
     * the original and resolved copies).
     */
    private List<Task> resolveTasks(Map<String, String> inputs) {
        // Pass 1: resolve description and expectedOutput; build original -> resolved map.
        // Use identity-based map because Task uses value equality and two pre-resolution
        // tasks with different descriptions must be treated as distinct keys.
        IdentityHashMap<Task, Task> originalToResolved = new IdentityHashMap<>();
        for (Task task : tasks) {
            Task resolved = task.toBuilder()
                    .description(TemplateResolver.resolve(task.getDescription(), inputs))
                    .expectedOutput(TemplateResolver.resolve(task.getExpectedOutput(), inputs))
                    .build();
            originalToResolved.put(task, resolved);
        }

        // Pass 2: rewrite context lists to reference resolved instances.
        // IMPORTANT: update originalToResolved as each final task is created so that
        // later tasks in this pass (e.g., task D depending on task B) pick up the
        // fully-rewritten version (with correct context) rather than the pass-1 draft.
        // Without this update, a diamond A->B->D / A->C->D would produce D with stale
        // context references not present in the result list, breaking DAG lookup.
        List<Task> result = new ArrayList<>(tasks.size());
        for (Task original : tasks) {
            Task resolvedBase = originalToResolved.get(original);
            List<Task> originalContext = original.getContext();
            if (originalContext.isEmpty()) {
                result.add(resolvedBase);
                // resolvedBase == originalToResolved.get(original) already; no update needed
            } else {
                List<Task> resolvedContext = new ArrayList<>(originalContext.size());
                for (Task ctxTask : originalContext) {
                    resolvedContext.add(originalToResolved.getOrDefault(ctxTask, ctxTask));
                }
                Task finalTask =
                        resolvedBase.toBuilder().context(resolvedContext).build();
                // Update the mapping so subsequent tasks in this pass see the final version
                originalToResolved.put(original, finalTask);
                result.add(finalTask);
            }
        }
        return result;
    }

    private WorkflowExecutor selectExecutor() {
        List<DelegationPolicy> policies = delegationPolicies != null ? delegationPolicies : List.of();
        return switch (workflow) {
            case SEQUENTIAL -> new SequentialWorkflowExecutor(agents, maxDelegationDepth, policies);
            case HIERARCHICAL -> new HierarchicalWorkflowExecutor(
                    resolveManagerLlm(),
                    agents,
                    managerMaxIterations,
                    maxDelegationDepth,
                    managerPromptStrategy,
                    policies,
                    hierarchicalConstraints);
            case PARALLEL -> new ParallelWorkflowExecutor(agents, maxDelegationDepth, parallelErrorStrategy, policies);
        };
    }

    private ChatModel resolveManagerLlm() {
        return managerLlm != null ? managerLlm : agents.get(0).getLlm();
    }

    // ========================
    // Custom builder methods (lambda convenience for event listeners)
    // ========================

    /**
     * Extends the Lombok-generated builder with lambda convenience methods for registering
     * event listeners without implementing the full {@link EnsembleListener} interface.
     *
     * Each method wraps the provided {@link Consumer} in an anonymous {@link EnsembleListener}
     * and delegates to {@code listener(EnsembleListener)}, which is generated by Lombok's
     * {@code @Singular} annotation. Multiple calls accumulate; none overwrite each other.
     */
    public static class EnsembleBuilder {

        /**
         * Register a lambda that is called immediately before each task starts.
         *
         * @param handler consumer receiving a {@link TaskStartEvent}
         * @return this builder
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
         *
         * @param handler consumer receiving a {@link TaskCompleteEvent}
         * @return this builder
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
         *
         * @param handler consumer receiving a {@link TaskFailedEvent}
         * @return this builder
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
         *
         * @param handler consumer receiving a {@link ToolCallEvent}
         * @return this builder
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
         * Register a lambda that is called immediately before a delegation is handed off to
         * a worker agent. Only fired for delegations that pass all guards and policies.
         *
         * @param handler consumer receiving a {@link DelegationStartedEvent}
         * @return this builder
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
         *
         * @param handler consumer receiving a {@link DelegationCompletedEvent}
         * @return this builder
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
         * Register a lambda that is called when a delegation fails, whether due to a guard
         * violation, policy rejection, or worker agent exception.
         *
         * @param handler consumer receiving a {@link DelegationFailedEvent}
         * @return this builder
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
    }
}
