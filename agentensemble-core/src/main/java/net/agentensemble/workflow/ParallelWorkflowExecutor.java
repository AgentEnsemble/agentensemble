package net.agentensemble.workflow;

import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.ParallelExecutionException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
 * <strong>MDC propagation:</strong> The MDC context of the calling thread is captured
 * before tasks are submitted and restored in each virtual thread for consistent structured
 * logging. Each task additionally sets {@code agent.role} in MDC for its duration.
 *
 * <strong>Thread safety:</strong> Virtual threads created by
 * {@link Executors#newVirtualThreadPerTaskExecutor()} are lightweight and do not block
 * OS threads during LLM HTTP calls. Shared state ({@link MemoryContext},
 * {@link DelegationContext}) is accessed concurrently -- see their respective Javadocs
 * for thread-safety guarantees.
 *
 * Stateless -- all execution state is held in per-invocation local variables.
 */
public class ParallelWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(ParallelWorkflowExecutor.class);

    /** MDC key for the current agent's role, set per task execution. */
    private static final String MDC_AGENT_ROLE = "agent.role";

    /** Truncation length for task descriptions in log messages. */
    private static final int LOG_TRUNCATE_LENGTH = 80;

    private final List<Agent> agents;
    private final int maxDelegationDepth;
    private final ParallelErrorStrategy errorStrategy;
    private final AgentExecutor agentExecutor;

    /**
     * Create a ParallelWorkflowExecutor.
     *
     * @param agents             all agents registered with the ensemble; used to build
     *                           the delegation context shared across parallel tasks
     * @param maxDelegationDepth maximum allowed peer-delegation depth
     * @param errorStrategy      how to respond when a task fails during parallel execution
     */
    public ParallelWorkflowExecutor(List<Agent> agents, int maxDelegationDepth,
            ParallelErrorStrategy errorStrategy) {
        this.agents = List.copyOf(agents);
        this.maxDelegationDepth = maxDelegationDepth;
        this.errorStrategy = errorStrategy != null ? errorStrategy : ParallelErrorStrategy.FAIL_FAST;
        this.agentExecutor = new AgentExecutor();
    }

    @Override
    public EnsembleOutput execute(List<Task> resolvedTasks, boolean verbose,
            MemoryContext memoryContext) {
        if (resolvedTasks.isEmpty()) {
            return EnsembleOutput.builder()
                    .raw("").taskOutputs(List.of())
                    .totalDuration(Duration.ZERO).totalToolCalls(0)
                    .build();
        }

        Instant startTime = Instant.now();
        int totalTasks = resolvedTasks.size();

        log.info("Parallel workflow starting | Tasks: {} | Error strategy: {}", totalTasks, errorStrategy);

        // Build the dependency graph from task context declarations
        TaskDependencyGraph graph = new TaskDependencyGraph(resolvedTasks);

        // Thread-safe shared state -- IdentityHashMap for identity-based task lookup,
        // wrapped with Collections.synchronizedMap to allow concurrent access.
        Map<Task, TaskOutput> completedOutputs =
                Collections.synchronizedMap(new IdentityHashMap<>());
        Map<Task, Throwable> failedTaskCauses =
                Collections.synchronizedMap(new IdentityHashMap<>());

        // Tasks that were skipped (CONTINUE_ON_ERROR: dep failed or dep was skipped).
        // Tracked separately from failedTaskCauses so that transitive dependents of a
        // skipped task are also correctly skipped rather than being submitted for execution.
        Set<Task> skippedTasks = Collections.synchronizedSet(
                Collections.newSetFromMap(new IdentityHashMap<>()));

        // Per-task: count of in-graph dependencies that have not yet resolved.
        // When this reaches 0, the task can be submitted or skipped.
        Map<Task, AtomicInteger> pendingDepCounts = new IdentityHashMap<>();
        for (Task task : resolvedTasks) {
            int inGraphDeps = (int) task.getContext().stream()
                    .filter(graph::isInGraph)
                    .count();
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

        // Delegation context shared across all tasks in this run
        DelegationContext delegationContext = DelegationContext.create(
                agents, maxDelegationDepth, memoryContext, agentExecutor, verbose);

        // Task outputs in topological completion order (append-only; synchronized for safe concurrent add)
        List<TaskOutput> completionOrder = Collections.synchronizedList(new LinkedList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Submit all root tasks (no in-graph dependencies) immediately
            for (Task root : graph.getRoots()) {
                submitTask(root, graph, callerMdc, completedOutputs, failedTaskCauses,
                        skippedTasks, pendingDepCounts, firstFailureRef, latch, delegationContext,
                        verbose, memoryContext, executor, completionOrder);
            }

            // Wait for all tasks to resolve (complete, fail, or be skipped)
            latch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskExecutionException(
                    "Parallel workflow interrupted",
                    "multiple", "multiple",
                    List.copyOf(completedOutputs.values()), e);
        }
        // try-with-resources calls executor.close(), which awaits all running threads

        // Handle FAIL_FAST failure
        TaskExecutionException firstFailure = firstFailureRef.get();
        if (firstFailure != null) {
            log.error("Parallel workflow failed (FAIL_FAST) | Completed: {} | Failed: {}",
                    completedOutputs.size(), failedTaskCauses.size());
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
            log.error("Parallel workflow partial failure (CONTINUE_ON_ERROR) | Completed: {} | Failed: {}",
                    successOutputs.size(), namedFailures.size());
            throw new ParallelExecutionException(
                    namedFailures.size() + " of " + totalTasks + " tasks failed",
                    successOutputs,
                    namedFailures);
        }

        // All tasks succeeded -- assemble output in topological completion order
        List<TaskOutput> allOutputs = List.copyOf(completionOrder);
        Duration totalDuration = Duration.between(startTime, Instant.now());
        String finalOutput = allOutputs.isEmpty() ? "" : allOutputs.getLast().getRaw();
        int totalToolCalls = allOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        log.info("Parallel workflow completed | Tasks: {} | Duration: {} | Tool calls: {}",
                allOutputs.size(), totalDuration, totalToolCalls);

        return EnsembleOutput.builder()
                .raw(finalOutput)
                .taskOutputs(allOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .build();
    }

    /**
     * Submit a task for execution on a virtual thread.
     *
     * The task is run via {@link AgentExecutor}. On completion (success or failure),
     * the task's state is recorded and its dependents are evaluated via
     * {@link #resolveDependent}. The latch is decremented exactly once per task
     * invocation (in the finally block).
     */
    private void submitTask(
            Task task,
            TaskDependencyGraph graph,
            Map<String, String> callerMdc,
            Map<Task, TaskOutput> completedOutputs,
            Map<Task, Throwable> failedTaskCauses,
            Set<Task> skippedTasks,
            Map<Task, AtomicInteger> pendingDepCounts,
            AtomicReference<TaskExecutionException> firstFailureRef,
            CountDownLatch latch,
            DelegationContext delegationContext,
            boolean verbose,
            MemoryContext memoryContext,
            ExecutorService executor,
            List<TaskOutput> completionOrder) {

        executor.submit(() -> {
            // Restore caller's MDC in this virtual thread, then add task-specific keys
            Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            MDC.setContextMap(callerMdc);
            MDC.put(MDC_AGENT_ROLE, task.getAgent().getRole());

            try {
                log.info("Task starting (parallel) | Agent: {} | Description: {}",
                        task.getAgent().getRole(), truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));

                // Collect outputs from completed in-graph dependencies as context
                List<TaskOutput> contextOutputs = new ArrayList<>();
                for (Task dep : task.getContext()) {
                    if (graph.isInGraph(dep)) {
                        TaskOutput out = completedOutputs.get(dep);
                        if (out != null) {
                            contextOutputs.add(out);
                        }
                    }
                }

                TaskOutput output = agentExecutor.execute(
                        task, contextOutputs, verbose, memoryContext, delegationContext);

                completedOutputs.put(task, output);
                completionOrder.add(output);

                log.info("Task completed (parallel) | Agent: {} | Duration: {} | Tool calls: {}",
                        task.getAgent().getRole(), output.getDuration(), output.getToolCallCount());

            } catch (Exception e) {
                log.error("Task failed (parallel) | Agent: {} | Description: {} | Error: {}",
                        task.getAgent().getRole(), truncate(task.getDescription(), LOG_TRUNCATE_LENGTH),
                        e.getMessage());

                failedTaskCauses.put(task, e);

                if (errorStrategy == ParallelErrorStrategy.FAIL_FAST) {
                    // Record the first failure; subsequent failures are ignored
                    firstFailureRef.compareAndSet(null, new TaskExecutionException(
                            "Task failed: " + task.getDescription(),
                            task.getDescription(),
                            task.getAgent().getRole(),
                            List.copyOf(completedOutputs.values()),
                            e));
                }

            } finally {
                // Restore MDC from before this thread's execution
                if (prevMdc != null) {
                    MDC.setContextMap(prevMdc);
                } else {
                    MDC.clear();
                }

                // Signal that this task has been resolved (success or failure)
                latch.countDown();

                // Propagate resolution to dependents: some may now be ready to run or skip
                resolveDependent(task, graph, callerMdc, completedOutputs, failedTaskCauses,
                        skippedTasks, pendingDepCounts, firstFailureRef, latch, delegationContext,
                        verbose, memoryContext, executor, completionOrder);
            }
        });
    }

    /**
     * Evaluate dependents of a task that has just been resolved (completed, failed, or skipped).
     *
     * For each dependent, decrement its pending dependency count. If the count reaches 0,
     * either submit the task (if all deps succeeded and no FAIL_FAST failure is pending)
     * or skip it (if a dep failed or was skipped, or FAIL_FAST applies). Skipped tasks
     * cascade their resolution to their own dependents recursively, and are added to
     * {@code skippedTasks} so that transitive dependents are also correctly skipped.
     *
     * <p>This method is called from within a virtual thread's finally block (after that
     * thread's own latch.countDown()). It may itself call latch.countDown() for skipped
     * tasks and recurse. The recursion depth is bounded by the longest chain in the DAG.
     */
    private void resolveDependent(
            Task resolvedTask,
            TaskDependencyGraph graph,
            Map<String, String> callerMdc,
            Map<Task, TaskOutput> completedOutputs,
            Map<Task, Throwable> failedTaskCauses,
            Set<Task> skippedTasks,
            Map<Task, AtomicInteger> pendingDepCounts,
            AtomicReference<TaskExecutionException> firstFailureRef,
            CountDownLatch latch,
            DelegationContext delegationContext,
            boolean verbose,
            MemoryContext memoryContext,
            ExecutorService executor,
            List<TaskOutput> completionOrder) {

        for (Task dependent : graph.getDependents(resolvedTask)) {
            int remaining = pendingDepCounts.get(dependent).decrementAndGet();
            if (remaining > 0) {
                // This dependent still has other unresolved deps -- nothing to do yet
                continue;
            }

            // All of this dependent's dependencies have been resolved
            boolean shouldSkip = shouldSkip(dependent, graph, failedTaskCauses,
                    skippedTasks, firstFailureRef);

            if (shouldSkip) {
                log.debug("Task skipped (parallel) | Agent: {} | Description: {}",
                        dependent.getAgent().getRole(),
                        truncate(dependent.getDescription(), LOG_TRUNCATE_LENGTH));

                // Track this task as skipped so its own transitive dependents are also skipped
                skippedTasks.add(dependent);

                // Count this task as resolved (skipped) and cascade to its dependents
                latch.countDown();
                resolveDependent(dependent, graph, callerMdc, completedOutputs, failedTaskCauses,
                        skippedTasks, pendingDepCounts, firstFailureRef, latch, delegationContext,
                        verbose, memoryContext, executor, completionOrder);
            } else {
                // All deps succeeded and no blocking failure -- submit this task
                submitTask(dependent, graph, callerMdc, completedOutputs, failedTaskCauses,
                        skippedTasks, pendingDepCounts, firstFailureRef, latch, delegationContext,
                        verbose, memoryContext, executor, completionOrder);
            }
        }
    }

    /**
     * Determine whether a task should be skipped based on the current failure state.
     *
     * For {@link ParallelErrorStrategy#CONTINUE_ON_ERROR}, a task is skipped if any
     * of its in-graph dependencies either failed (present in {@code failedTaskCauses})
     * or was itself skipped (present in {@code skippedTasks}). Checking both sets is
     * required to correctly propagate skips transitively through chains: if A fails,
     * B (depends on A) is skipped, and C (depends on B) must also be skipped even
     * though B is not in {@code failedTaskCauses}.
     *
     * @return true if the task should be skipped rather than submitted for execution
     */
    private boolean shouldSkip(Task task, TaskDependencyGraph graph,
            Map<Task, Throwable> failedTaskCauses,
            Set<Task> skippedTasks,
            AtomicReference<TaskExecutionException> firstFailureRef) {

        if (errorStrategy == ParallelErrorStrategy.FAIL_FAST && firstFailureRef.get() != null) {
            // A failure has been registered -- skip all unstarted tasks
            return true;
        }

        if (errorStrategy == ParallelErrorStrategy.CONTINUE_ON_ERROR) {
            // Skip if any in-graph dependency either failed OR was itself skipped.
            // Both checks are required: failed tasks appear in failedTaskCauses;
            // skipped tasks appear in skippedTasks (not in failedTaskCauses).
            return task.getContext().stream()
                    .filter(graph::isInGraph)
                    .anyMatch(dep -> failedTaskCauses.containsKey(dep)
                            || skippedTasks.contains(dep));
        }

        return false;
    }

    // ========================
    // Private utilities
    // ========================

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
