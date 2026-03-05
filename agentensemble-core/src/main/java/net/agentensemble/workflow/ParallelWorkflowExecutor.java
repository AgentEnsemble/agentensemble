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
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.ensemble.EnsembleOutput;
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
    }

    @Override
    public EnsembleOutput execute(List<Task> resolvedTasks, ExecutionContext executionContext) {
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
        Map<Task, TaskOutput> completedOutputs = Collections.synchronizedMap(new IdentityHashMap<>());
        Map<Task, Throwable> failedTaskCauses = Collections.synchronizedMap(new IdentityHashMap<>());

        // Tasks that were skipped (CONTINUE_ON_ERROR: dep failed or dep was skipped).
        // Tracked separately from failedTaskCauses so that transitive dependents of a
        // skipped task are also correctly skipped rather than being submitted for execution.
        Set<Task> skippedTasks = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

        // Per-task: count of in-graph dependencies that have not yet resolved.
        // When this reaches 0, the task can be submitted or skipped.
        IdentityHashMap<Task, AtomicInteger> pendingDepCounts = new IdentityHashMap<>();
        for (Task task : resolvedTasks) {
            int inGraphDeps =
                    (int) task.getContext().stream().filter(graph::isInGraph).count();
            pendingDepCounts.put(task, new AtomicInteger(inGraphDeps));
        }

        // FAIL_FAST: holds the first TaskExecutionException to throw after all threads finish
        AtomicReference<TaskExecutionException> firstFailureRef = new AtomicReference<>();

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
        IdentityHashMap<Task, Integer> taskIndexMap = new IdentityHashMap<>();
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
                    latch,
                    delegationContext,
                    executionContext,
                    totalTasks,
                    executor,
                    completionOrder,
                    taskIndexMap,
                    errorStrategy,
                    agentExecutor);

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

        // Handle FAIL_FAST failure
        TaskExecutionException firstFailure = firstFailureRef.get();
        if (firstFailure != null) {
            log.error(
                    "Parallel workflow failed (FAIL_FAST) | Completed: {} | Failed: {}",
                    completedOutputs.size(),
                    failedTaskCauses.size());
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
            log.error(
                    "Parallel workflow partial failure (CONTINUE_ON_ERROR) | Completed: {} | Failed: {}",
                    successOutputs.size(),
                    namedFailures.size());
            throw new ParallelExecutionException(
                    namedFailures.size() + " of " + totalTasks + " tasks failed", successOutputs, namedFailures);
        }

        // All tasks succeeded -- assemble output in topological completion order
        List<TaskOutput> allOutputs = List.copyOf(completionOrder);
        Duration totalDuration = Duration.between(startTime, Instant.now());
        String finalOutput = allOutputs.isEmpty() ? "" : allOutputs.getLast().getRaw();
        int totalToolCalls =
                allOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        log.info(
                "Parallel workflow completed | Tasks: {} | Duration: {} | Tool calls: {}",
                allOutputs.size(),
                totalDuration,
                totalToolCalls);

        return EnsembleOutput.builder()
                .raw(finalOutput)
                .taskOutputs(allOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .build();
    }
}
