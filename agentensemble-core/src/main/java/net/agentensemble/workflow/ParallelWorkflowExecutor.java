package net.agentensemble.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.agent.DeterministicTaskExecutor;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes independent tasks concurrently using Java 21 virtual threads.
 *
 * Analyses the task dependency graph (derived from each task's {@code context} list)
 * and executes tasks with maximum parallelism while respecting all ordering constraints:
 *
 * <ul>
 *   <li>Tasks with no unmet dependencies start immediately on virtual threads.</li>
 *   <li>When a task completes, any dependent tasks whose all dependencies are now
 *       satisfied are submitted for execution.</li>
 *   <li>This naturally handles mixed sequential and parallel patterns -- no explicit
 *       user configuration is required beyond declaring {@code context} dependencies.</li>
 * </ul>
 *
 * <strong>Exit-early:</strong> When a review gate or {@code HumanInputTool} triggers
 * exit-early, all pending (not yet started) tasks are skipped and already-running tasks
 * are allowed to complete. A partial {@link EnsembleOutput} is returned with the
 * appropriate {@link ExitReason}.
 *
 * <strong>Error handling:</strong>
 * <ul>
 *   <li>{@link ParallelErrorStrategy#FAIL_FAST} (default): when any task fails,
 *       subsequent unstarted tasks are skipped and a {@link TaskExecutionException}
 *       is thrown after all currently-running tasks complete.</li>
 *   <li>{@link ParallelErrorStrategy#CONTINUE_ON_ERROR}: independent tasks continue
 *       running after a failure. Tasks that depend on a failed task are skipped;
 *       transitive dependents (tasks that depend on a skipped task) are also skipped.
 *       A {@link ParallelExecutionException} is thrown at the end if any tasks failed.</li>
 * </ul>
 *
 * <strong>Event callbacks:</strong> {@link net.agentensemble.callback.TaskStartEvent},
 * {@link net.agentensemble.callback.TaskCompleteEvent}, and
 * {@link net.agentensemble.callback.TaskFailedEvent} are fired via the
 * {@link ExecutionContext}. Listener exceptions are caught and logged without aborting
 * execution.
 *
 * <strong>MDC propagation:</strong> The MDC context of the calling thread is captured
 * before tasks are submitted and restored in each virtual thread for consistent structured
 * logging.
 *
 * <strong>Thread safety:</strong> Virtual threads created by
 * {@link Executors#newVirtualThreadPerTaskExecutor()} are lightweight and do not block
 * OS threads during LLM HTTP calls. {@link ExecutionContext} is immutable. Listener
 * implementations must be thread-safe when registered with a parallel workflow.
 *
 * Stateless -- all execution state is held in per-invocation local variables.
 * Task submission and dependency resolution are delegated to {@link ParallelTaskCoordinator}.
 */
public class ParallelWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelWorkflowExecutor.class);

    private final List<Agent> agents;
    private final int maxDelegationDepth;
    private final List<DelegationPolicy> delegationPolicies;
    private final ParallelErrorStrategy errorStrategy;
    private final AgentExecutor agentExecutor;
    private final DeterministicTaskExecutor deterministicExecutor;

    /**
     * Create a ParallelWorkflowExecutor with no delegation policies.
     *
     * @param agents             all agents registered with the ensemble; used to build
     *                           the delegation context shared across parallel tasks
     * @param maxDelegationDepth maximum allowed peer-delegation depth
     * @param errorStrategy      how to respond when a task fails during parallel execution
     */
    public ParallelWorkflowExecutor(List<Agent> agents, int maxDelegationDepth, ParallelErrorStrategy errorStrategy) {
        this(agents, maxDelegationDepth, errorStrategy, List.of());
    }

    /**
     * Create a ParallelWorkflowExecutor with delegation policies.
     *
     * @param agents             all agents registered with the ensemble
     * @param maxDelegationDepth maximum allowed peer-delegation depth
     * @param errorStrategy      how to respond when a task fails during parallel execution
     * @param delegationPolicies policies to evaluate before each delegation attempt;
     *                           evaluated in list order; must not be null
     */
    public ParallelWorkflowExecutor(
            List<Agent> agents,
            int maxDelegationDepth,
            ParallelErrorStrategy errorStrategy,
            List<DelegationPolicy> delegationPolicies) {
        this.agents = List.copyOf(agents);
        this.maxDelegationDepth = maxDelegationDepth;
        this.delegationPolicies = delegationPolicies != null ? List.copyOf(delegationPolicies) : List.of();
        this.errorStrategy = errorStrategy != null ? errorStrategy : ParallelErrorStrategy.FAIL_FAST;
        this.agentExecutor = new AgentExecutor();
        this.deterministicExecutor = new DeterministicTaskExecutor();
    }

    @Override
    public EnsembleOutput execute(List<Task> resolvedTasks, ExecutionContext executionContext) {
        return executeSeeded(resolvedTasks, executionContext, Map.of());
    }

    @Override
    public EnsembleOutput executeNodes(List<WorkflowNode> nodes, ExecutionContext executionContext) {
        // PARALLEL workflow with loops: each loop is wrapped in a "shadow" deterministic
        // handler task that runs the LoopExecutor inside its own virtual thread. The shadow
        // task carries the loop's outer-DAG dependencies via Task.context(Loop.getContext()),
        // so loops with no deps run alongside other root tasks; loops with deps wait until
        // their named tasks complete -- exactly the same way regular tasks do.
        //
        // After the parallel DAG completes, the shadow tasks are stripped from the visible
        // output and replaced with each loop's projected outputs (per LoopOutputMode) keyed
        // by the original body Task instances, matching the SequentialWorkflowExecutor's
        // behaviour. Loop side channels (history, termination reasons, RETURN_WITH_FLAG set)
        // are populated from the shared result map written by each shadow handler.
        List<Task> taskNodes = new ArrayList<>();
        List<net.agentensemble.workflow.loop.Loop> loopNodes = new ArrayList<>();
        for (WorkflowNode node : nodes) {
            if (node instanceof Task t) {
                taskNodes.add(t);
            } else if (node instanceof net.agentensemble.workflow.loop.Loop loop) {
                loopNodes.add(loop);
            } else {
                throw new net.agentensemble.exception.ValidationException(
                        "Unknown WorkflowNode type: " + node.getClass().getName());
            }
        }

        if (loopNodes.isEmpty()) {
            return execute(taskNodes, executionContext);
        }

        // Each shadow handler writes its loop's result into this concurrent map so the
        // post-parallel merge can read history / termination data without further locking.
        java.util.concurrent.ConcurrentHashMap<String, net.agentensemble.workflow.loop.LoopExecutionResult>
                loopResults = new java.util.concurrent.ConcurrentHashMap<>();

        // Body runner for each loop's iterations. Stateless; safe to share across loops.
        SequentialWorkflowExecutor bodyRunner =
                new SequentialWorkflowExecutor(agents, Math.max(maxDelegationDepth, 1), delegationPolicies);
        net.agentensemble.workflow.loop.LoopExecutor loopExecutor =
                new net.agentensemble.workflow.loop.LoopExecutor(bodyRunner);

        // Build one shadow task per loop. The shadow's context() declares the loop's outer
        // DAG dependencies; the dependency graph treats the shadow like any other Task.
        java.util.IdentityHashMap<Task, net.agentensemble.workflow.loop.Loop> shadowToLoop =
                new java.util.IdentityHashMap<>();
        List<Task> shadows = new ArrayList<>(loopNodes.size());
        for (net.agentensemble.workflow.loop.Loop loop : loopNodes) {
            Task shadow = buildLoopShadow(loop, loopExecutor, loopResults, executionContext);
            shadows.add(shadow);
            shadowToLoop.put(shadow, loop);
        }

        // Combined node list -- parallel tasks first, shadows last. The dependency graph
        // determines actual execution order; declaration order only affects taskIndex labels.
        List<Task> combined = new ArrayList<>(taskNodes.size() + shadows.size());
        combined.addAll(taskNodes);
        combined.addAll(shadows);

        EnsembleOutput parallelOutput = execute(combined, executionContext);

        // Strip shadow tasks from the visible output and inject each loop's projected outputs.
        return mergeLoopProjections(parallelOutput, loopNodes, shadows, shadowToLoop, loopResults);
    }

    /**
     * Build a deterministic-handler {@link Task} that, when invoked by the parallel
     * coordinator, runs the given {@link net.agentensemble.workflow.loop.Loop} via the
     * shared {@link net.agentensemble.workflow.loop.LoopExecutor} and stores the result in
     * {@code loopResults} for the post-parallel merge to consume.
     *
     * <p>The shadow's {@code context()} is {@code loop.getContext()}, so dependency
     * resolution treats the shadow as a regular Task with the same outer-DAG deps as the
     * loop. The shadow's {@code TaskOutput} carries a one-line summary; the real per-body
     * outputs are surfaced via {@link #mergeLoopProjections(EnsembleOutput, List, List,
     * java.util.IdentityHashMap, java.util.Map)}.
     */
    private Task buildLoopShadow(
            net.agentensemble.workflow.loop.Loop loop,
            net.agentensemble.workflow.loop.LoopExecutor loopExecutor,
            java.util.concurrent.ConcurrentHashMap<String, net.agentensemble.workflow.loop.LoopExecutionResult>
                    loopResults,
            ExecutionContext executionContext) {
        return Task.builder()
                .name("$loop:" + loop.getName())
                .description("Loop super-node: " + loop.getName())
                .expectedOutput("(loop projected outputs)")
                .context(loop.getContext() != null ? loop.getContext() : List.of())
                .handler(ctx -> {
                    net.agentensemble.workflow.loop.LoopExecutionResult result =
                            loopExecutor.execute(loop, executionContext);
                    loopResults.put(loop.getName(), result);
                    return net.agentensemble.tool.ToolResult.success(
                            "Loop " + loop.getName() + " ran " + result.getIterationsRun()
                                    + " iteration(s); termination=" + result.getTerminationReason());
                })
                .build();
    }

    /**
     * After the parallel DAG (tasks + shadow loops) has completed, replace shadow-task
     * outputs in the merged output with each loop's projected outputs (keyed by original
     * body Task instances) and populate the loop side channels on the returned
     * {@link EnsembleOutput}.
     */
    private EnsembleOutput mergeLoopProjections(
            EnsembleOutput parallelOutput,
            List<net.agentensemble.workflow.loop.Loop> loopNodes,
            List<Task> shadows,
            java.util.IdentityHashMap<Task, net.agentensemble.workflow.loop.Loop> shadowToLoop,
            java.util.Map<String, net.agentensemble.workflow.loop.LoopExecutionResult> loopResults) {

        java.util.IdentityHashMap<Task, TaskOutput> mergedIndex = new java.util.IdentityHashMap<>();
        if (parallelOutput.getTaskOutputIndex() != null) {
            mergedIndex.putAll(parallelOutput.getTaskOutputIndex());
        }
        // Remove shadow entries from the index -- callers should never see them.
        for (Task shadow : shadows) {
            mergedIndex.remove(shadow);
        }

        // Rebuild taskOutputs in declaration order, dropping shadow entries. Loop body
        // projections are appended at the end (one per loop, in declaration order).
        java.util.IdentityHashMap<Task, TaskOutput> shadowSet = new java.util.IdentityHashMap<>();
        for (Task shadow : shadows) {
            shadowSet.put(shadow, null);
        }
        List<TaskOutput> mergedOutputs =
                new ArrayList<>(parallelOutput.getTaskOutputs().size());
        for (TaskOutput out : parallelOutput.getTaskOutputs()) {
            // Shadows have a known synthetic description. We need a more reliable filter.
            // The shadow itself isn't accessible from TaskOutput, so we filter via the index:
            // any TaskOutput whose corresponding Task is in shadowSet should be dropped.
            // Without identity from the output back to the task, we conservatively rebuild
            // from the index using declaration order of non-shadow tasks below.
            mergedOutputs.add(out);
        }
        // Replace mergedOutputs by walking parallel output but excluding shadows by checking
        // the original index. The parallelOutput's taskOutputIndex was identity-keyed by the
        // resolved Task instances; we use that to reverse-look-up by value.
        // Simpler: just rebuild from mergedIndex, preserving existing parallelOutput order.
        java.util.LinkedHashSet<TaskOutput> shadowOutputs = new java.util.LinkedHashSet<>();
        if (parallelOutput.getTaskOutputIndex() != null) {
            for (Task shadow : shadows) {
                TaskOutput so = parallelOutput.getTaskOutputIndex().get(shadow);
                if (so != null) shadowOutputs.add(so);
            }
        }
        mergedOutputs.removeAll(shadowOutputs);

        // Append each loop's projected outputs (LoopOutputMode-driven) keyed by original
        // body Task instances. Maintains declaration order across loops.
        java.util.LinkedHashMap<String, List<java.util.Map<String, TaskOutput>>> loopHistory =
                new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> loopTermReasons = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> loopsHitMaxIters = new java.util.LinkedHashSet<>();
        for (net.agentensemble.workflow.loop.Loop loop : loopNodes) {
            net.agentensemble.workflow.loop.LoopExecutionResult result = loopResults.get(loop.getName());
            if (result == null) {
                // Shadow never ran (e.g., upstream task failed under FAIL_FAST). Skip.
                continue;
            }
            loopHistory.put(loop.getName(), result.getHistory());
            loopTermReasons.put(loop.getName(), result.getTerminationReason());
            if (result.stoppedByMaxIterations()
                    && loop.getOnMaxIterations()
                            == net.agentensemble.workflow.loop.MaxIterationsAction.RETURN_WITH_FLAG) {
                loopsHitMaxIters.add(loop.getName());
            }
            for (Task originalBodyTask : loop.getBody()) {
                TaskOutput out = result.getProjectedOutputs().get(originalBodyTask);
                if (out != null) {
                    mergedOutputs.add(out);
                    mergedIndex.put(originalBodyTask, out);
                }
            }
        }

        String finalRaw = mergedOutputs.isEmpty() ? "" : mergedOutputs.getLast().getRaw();
        int totalToolCalls =
                mergedOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        return EnsembleOutput.builder()
                .raw(finalRaw)
                .taskOutputs(mergedOutputs)
                .totalDuration(parallelOutput.getTotalDuration())
                .totalToolCalls(totalToolCalls)
                .taskOutputIndex(mergedIndex)
                .exitReason(parallelOutput.getExitReason())
                .loopHistory(loopHistory)
                .loopTerminationReasons(loopTermReasons)
                .loopsTerminatedByMaxIterations(loopsHitMaxIters)
                .build();
    }

    /**
     * Execute with a pre-seeded completed-outputs map.
     *
     * <p>Used by {@link PhaseDagExecutor} to inject outputs from previously-completed phases
     * so that cross-phase {@code context()} references resolve correctly in this phase's tasks.
     * Tasks whose cross-phase dependencies were satisfied in a prior phase will find their
     * context outputs present in {@code seedOutputs} without blocking on non-existent
     * in-graph predecessors.
     *
     * @param resolvedTasks    tasks to execute (with template vars resolved, agents synthesized)
     * @param executionContext execution context
     * @param seedOutputs      outputs from prior phases, keyed by task identity
     * @return the ensemble output for this phase's tasks only (excludes seed outputs)
     */
    public EnsembleOutput executeSeeded(
            List<Task> resolvedTasks, ExecutionContext executionContext, Map<Task, TaskOutput> seedOutputs) {
        if (resolvedTasks.isEmpty()) {
            return EnsembleOutput.builder()
                    .raw("")
                    .taskOutputs(List.of())
                    .totalDuration(Duration.ZERO)
                    .totalToolCalls(0)
                    .build();
        }

        Instant startTime = Instant.now();
        int totalTasks = resolvedTasks.size();

        log.info("Parallel workflow starting | Tasks: {} | Error strategy: {}", totalTasks, errorStrategy);

        // Build the dependency graph from task context declarations
        TaskDependencyGraph graph = new TaskDependencyGraph(resolvedTasks);

        // Thread-safe shared state -- IdentityHashMap for identity-based task lookup,
        // wrapped with Collections.synchronizedMap to allow concurrent access.
        // Pre-seed with prior phase outputs so cross-phase context() references resolve correctly.
        @SuppressWarnings("IdentityHashMapUsage")
        Map<Task, TaskOutput> completedOutputs = Collections.synchronizedMap(new IdentityHashMap<>(seedOutputs));
        Map<Task, Throwable> failedTaskCauses = Collections.synchronizedMap(new IdentityHashMap<>());

        // Tasks that were skipped (CONTINUE_ON_ERROR: dep failed or dep was skipped).
        // Tracked separately from failedTaskCauses so that transitive dependents of a
        // skipped task are also correctly skipped rather than being submitted for execution.
        Set<Task> skippedTasks = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

        // Per-task: count of in-graph dependencies that have not yet resolved.
        // When this reaches 0, the task can be submitted or skipped.
        Map<Task, AtomicInteger> pendingDepCounts = new IdentityHashMap<>();
        for (Task task : resolvedTasks) {
            int inGraphDeps =
                    (int) task.getContext().stream().filter(graph::isInGraph).count();
            pendingDepCounts.put(task, new AtomicInteger(inGraphDeps));
        }

        // FAIL_FAST: holds the first TaskExecutionException to throw after all threads finish
        AtomicReference<TaskExecutionException> firstFailureRef = new AtomicReference<>();

        // Exit-early: set when a review gate or HumanInputTool requests pipeline termination
        AtomicReference<ExitReason> exitEarlyReasonRef = new AtomicReference<>();

        // Each task decrements the latch exactly once (on completion, failure, or skip).
        // When latch reaches 0, all tasks have been resolved and the main thread can proceed.
        CountDownLatch latch = new CountDownLatch(totalTasks);

        // Capture the caller's MDC context for propagation into virtual threads
        Map<String, String> callerMdc = MDC.getCopyOfContextMap();
        if (callerMdc == null) {
            callerMdc = Map.of();
        }

        // Delegation context shared across all tasks in this run.
        // Policies are threaded in so that AgentDelegationTool can evaluate them per delegation.
        DelegationContext delegationContext = DelegationContext.create(
                agents, maxDelegationDepth, executionContext, agentExecutor, delegationPolicies);

        // Pre-compute 1-based task indices so events carry a stable, deterministic index
        // that listeners can use to correlate start/complete/fail events per task.
        Map<Task, Integer> taskIndexMap = new IdentityHashMap<>();
        for (int i = 0; i < resolvedTasks.size(); i++) {
            taskIndexMap.put(resolvedTasks.get(i), i + 1);
        }

        // Task outputs in topological completion order (append-only; synchronized for safe concurrent add)
        List<TaskOutput> completionOrder = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            ParallelTaskCoordinator coordinator = new ParallelTaskCoordinator(
                    graph,
                    callerMdc,
                    completedOutputs,
                    failedTaskCauses,
                    skippedTasks,
                    pendingDepCounts,
                    firstFailureRef,
                    exitEarlyReasonRef,
                    latch,
                    delegationContext,
                    executionContext,
                    totalTasks,
                    executor,
                    completionOrder,
                    taskIndexMap,
                    errorStrategy,
                    agentExecutor,
                    deterministicExecutor);

            // Submit all root tasks (no in-graph dependencies) immediately
            for (Task root : graph.getRoots()) {
                coordinator.submitTask(root);
            }

            // Wait for all tasks to resolve (complete, fail, or be skipped)
            latch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(
                    "Parallel workflow interrupted", "multiple", "multiple", List.copyOf(completedOutputs.values()), e);
        }
        // try-with-resources calls executor.close(), which awaits all running threads

        // Handle exit-early (review gate or HumanInputTool) -- before error checks
        ExitReason exitEarlyReason = exitEarlyReasonRef.get();
        if (exitEarlyReason != null) {
            List<TaskOutput> partialOutputs = List.copyOf(completionOrder);
            Duration partialDuration = Duration.between(startTime, Instant.now());
            String partialFinalOutput =
                    partialOutputs.isEmpty() ? "" : partialOutputs.getLast().getRaw();
            int partialToolCalls = partialOutputs.stream()
                    .mapToInt(TaskOutput::getToolCallCount)
                    .sum();

            if (log.isInfoEnabled()) {
                log.info(
                        "Parallel workflow exit-early ({}) | Completed: {} | Total: {}",
                        exitEarlyReason,
                        partialOutputs.size(),
                        totalTasks);
            }

            return EnsembleOutput.builder()
                    .raw(partialFinalOutput)
                    .taskOutputs(partialOutputs)
                    .totalDuration(partialDuration)
                    .totalToolCalls(partialToolCalls)
                    .exitReason(exitEarlyReason)
                    .taskOutputIndex(buildPhaseOnlyIndex(resolvedTasks, completedOutputs))
                    .build();
        }

        // Handle FAIL_FAST failure
        TaskExecutionException firstFailure = firstFailureRef.get();
        if (firstFailure != null) {
            if (log.isErrorEnabled()) {
                log.error(
                        "Parallel workflow failed (FAIL_FAST) | Completed: {} | Failed: {}",
                        completedOutputs.size(),
                        failedTaskCauses.size());
            }
            throw firstFailure;
        }

        // Handle CONTINUE_ON_ERROR with partial failures
        if (!failedTaskCauses.isEmpty()) {
            List<TaskOutput> successOutputs = List.copyOf(completionOrder);
            Map<String, Throwable> namedFailures = new HashMap<>();
            synchronized (failedTaskCauses) {
                for (Map.Entry<Task, Throwable> entry : failedTaskCauses.entrySet()) {
                    namedFailures.put(entry.getKey().getDescription(), entry.getValue());
                }
            }
            if (log.isErrorEnabled()) {
                log.error(
                        "Parallel workflow partial failure (CONTINUE_ON_ERROR) | Completed: {} | Failed: {}",
                        successOutputs.size(),
                        namedFailures.size());
            }
            throw new ParallelExecutionException(
                    namedFailures.size() + " of " + totalTasks + " tasks failed", successOutputs, namedFailures);
        }

        // All tasks succeeded -- assemble output in topological completion order
        List<TaskOutput> allOutputs = List.copyOf(completionOrder);
        Duration totalDuration = Duration.between(startTime, Instant.now());
        String finalOutput = allOutputs.isEmpty() ? "" : allOutputs.getLast().getRaw();
        int totalToolCalls =
                allOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        if (log.isInfoEnabled()) {
            log.info(
                    "Parallel workflow completed | Tasks: {} | Duration: {} | Tool calls: {}",
                    allOutputs.size(),
                    totalDuration,
                    totalToolCalls);
        }

        return EnsembleOutput.builder()
                .raw(finalOutput)
                .taskOutputs(allOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .taskOutputIndex(buildPhaseOnlyIndex(resolvedTasks, completedOutputs))
                .build();
    }

    /**
     * Build an identity-based task-to-output index containing only the tasks from the
     * current execution phase (excludes any seed outputs injected from prior phases).
     *
     * <p>This ensures that when {@link PhaseDagExecutor} merges each phase's
     * {@code taskOutputIndex} into the global output map, it does not redundantly
     * re-add prior-phase outputs that are already present.
     *
     * @param resolvedTasks    the tasks that belong to the current phase
     * @param completedOutputs the shared completed-outputs map (may include seed entries)
     * @return a new {@link IdentityHashMap} containing only current-phase task outputs
     */
    private static Map<Task, TaskOutput> buildPhaseOnlyIndex(
            List<Task> resolvedTasks, Map<Task, TaskOutput> completedOutputs) {
        IdentityHashMap<Task, TaskOutput> index = new IdentityHashMap<>();
        for (Task task : resolvedTasks) {
            TaskOutput to = completedOutputs.get(task);
            if (to != null) {
                index.put(task, to);
            }
        }
        return index;
    }
}
