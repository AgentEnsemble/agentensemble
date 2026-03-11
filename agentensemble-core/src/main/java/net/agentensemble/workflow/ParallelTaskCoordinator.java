package net.agentensemble.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.agent.DeterministicTaskExecutor;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewPolicy;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTiming;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.tool.HumanInputTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Coordinates task submission, completion, and skip cascading for a single parallel
 * workflow execution.
 *
 * Per-execution shared state (outputs, failures, latches, indices, etc.) is held as
 * fields, eliminating the need to thread them through every method call.
 *
 * <p>Exit-early is supported: when a review gate fires {@link ReviewDecision.ExitEarly}
 * or a {@link HumanInputTool} throws {@link ExitEarlyException}, the
 * {@code exitEarlyReasonRef} is set and all pending (not yet started) tasks are skipped.
 * Tasks already running are allowed to complete. The caller reads
 * {@code exitEarlyReasonRef} after the latch reaches zero to determine whether to build
 * a partial or full output.
 *
 * Package-private. Created and used exclusively by {@link ParallelWorkflowExecutor}.
 */
class ParallelTaskCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ParallelTaskCoordinator.class);

    private static final String MDC_AGENT_ROLE = "agent.role";
    private static final int LOG_TRUNCATE_LENGTH = 80;

    private final TaskDependencyGraph graph;
    private final Map<String, String> callerMdc;
    private final Map<Task, TaskOutput> completedOutputs;
    private final Map<Task, Throwable> failedTaskCauses;
    private final Set<Task> skippedTasks;
    private final Map<Task, AtomicInteger> pendingDepCounts;
    private final AtomicReference<TaskExecutionException> firstFailureRef;
    private final AtomicReference<ExitReason> exitEarlyReasonRef;
    private final CountDownLatch latch;
    private final DelegationContext delegationContext;
    private final ExecutionContext executionContext;
    private final int totalTasks;
    private final ExecutorService executor;
    private final List<TaskOutput> completionOrder;
    private final Map<Task, Integer> taskIndexMap;
    private final ParallelErrorStrategy errorStrategy;
    private final AgentExecutor agentExecutor;
    private final DeterministicTaskExecutor deterministicExecutor;

    /**
     * Mutex for the after-execution review gate.
     *
     * <p>In a parallel workflow multiple tasks may complete concurrently and each
     * independently check whether a review gate should fire. Without synchronization,
     * two threads can both observe {@code exitEarlyReasonRef == null} at the same time
     * and both invoke the {@link ReviewHandler} -- producing duplicate prompts for
     * interactive handlers such as {@code ConsoleReviewHandler}. Holding this lock
     * for the duration of the check-and-invoke sequence eliminates the race.
     */
    private final Object reviewGateLock = new Object();

    ParallelTaskCoordinator(
            TaskDependencyGraph graph,
            Map<String, String> callerMdc,
            Map<Task, TaskOutput> completedOutputs,
            Map<Task, Throwable> failedTaskCauses,
            Set<Task> skippedTasks,
            Map<Task, AtomicInteger> pendingDepCounts,
            AtomicReference<TaskExecutionException> firstFailureRef,
            AtomicReference<ExitReason> exitEarlyReasonRef,
            CountDownLatch latch,
            DelegationContext delegationContext,
            ExecutionContext executionContext,
            int totalTasks,
            ExecutorService executor,
            List<TaskOutput> completionOrder,
            Map<Task, Integer> taskIndexMap,
            ParallelErrorStrategy errorStrategy,
            AgentExecutor agentExecutor,
            DeterministicTaskExecutor deterministicExecutor) {
        this.graph = graph;
        this.callerMdc = callerMdc;
        this.completedOutputs = completedOutputs;
        this.failedTaskCauses = failedTaskCauses;
        this.skippedTasks = skippedTasks;
        this.pendingDepCounts = pendingDepCounts;
        this.firstFailureRef = firstFailureRef;
        this.exitEarlyReasonRef = exitEarlyReasonRef;
        this.latch = latch;
        this.delegationContext = delegationContext;
        this.executionContext = executionContext;
        this.totalTasks = totalTasks;
        this.executor = executor;
        this.completionOrder = completionOrder;
        this.taskIndexMap = taskIndexMap;
        this.errorStrategy = errorStrategy;
        this.agentExecutor = agentExecutor;
        this.deterministicExecutor = deterministicExecutor;
    }

    /**
     * Submit a task for execution on a virtual thread.
     *
     * The task is run via {@link AgentExecutor}. On completion (success or failure),
     * the task's state is recorded and its dependents are evaluated via
     * {@link #resolveDependent}. The latch is decremented exactly once per task
     * invocation (in the finally block).
     *
     * <p>After successful task execution, an after-execution review gate is fired when
     * configured. If the review returns {@link ReviewDecision.ExitEarly}, the
     * {@code exitEarlyReasonRef} is set and no further tasks are submitted.
     *
     * <p>If a {@link HumanInputTool} throws {@link ExitEarlyException} during execution,
     * the exit-early reason is set and the task is treated as not completed (its output
     * is not recorded).
     */
    void submitTask(Task task) {
        var unused = executor.submit(() -> {
            Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            MDC.setContextMap(callerMdc);
            MDC.put(MDC_AGENT_ROLE, agentRole(task));

            int taskIndex = taskIndexMap.getOrDefault(task, 0);
            Instant taskStart = Instant.now();
            try {
                log.info(
                        "Task starting (parallel) | Agent: {} | Description: {}",
                        agentRole(task),
                        truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));

                executionContext.fireTaskStart(
                        new TaskStartEvent(task.getDescription(), agentRole(task), taskIndex, totalTasks));

                // Inject ReviewHandler into HumanInputTool instances (AI tasks only)
                ReviewHandler reviewHandler = executionContext.reviewHandler();
                if (reviewHandler != null && task.getAgent() != null) {
                    injectReviewHandlerIntoTools(task, reviewHandler);
                }

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

                // Execute the task -- deterministic handler tasks bypass AgentExecutor entirely
                TaskOutput output;
                if (task.getHandler() != null) {
                    output = deterministicExecutor.execute(task, contextOutputs, executionContext);
                } else {
                    output = agentExecutor.execute(task, contextOutputs, executionContext, delegationContext);
                }

                // === After-execution review gate ===
                // Outer check avoids acquiring the lock when no gate is needed.
                if (exitEarlyReasonRef.get() == null && shouldApplyAfterReview(task, taskIndex)) {
                    // Serialize review gate invocations to prevent concurrent prompts
                    // (TOCTOU: two threads can both pass the null-check above; the inner
                    // re-check inside the lock ensures only one fires the ReviewHandler).
                    synchronized (reviewGateLock) {
                        // Re-check: another thread may have set exit-early while we waited
                        if (exitEarlyReasonRef.get() == null) {
                            Review review = task.getReview();
                            Duration timeout = review != null ? review.getTimeout() : Review.DEFAULT_TIMEOUT;
                            OnTimeoutAction onTimeout =
                                    review != null ? review.getOnTimeoutAction() : Review.DEFAULT_ON_TIMEOUT;
                            String prompt = review != null ? review.getPrompt() : null;

                            ReviewRequest afterRequest = ReviewRequest.of(
                                    task.getDescription(),
                                    output.getRaw(),
                                    ReviewTiming.AFTER_EXECUTION,
                                    timeout,
                                    onTimeout,
                                    prompt);

                            ReviewDecision afterDecision = reviewHandler.review(afterRequest);
                            log.info(
                                    "Task (parallel) after-review decision: {} | Task: {}",
                                    afterDecision.getClass().getSimpleName(),
                                    truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));

                            if (afterDecision instanceof ReviewDecision.ExitEarly afterExitEarlyDecision) {
                                // Include this task output, then stop the pipeline
                                ExitReason afterReason = afterExitEarlyDecision.timedOut()
                                        ? ExitReason.TIMEOUT
                                        : ExitReason.USER_EXIT_EARLY;
                                exitEarlyReasonRef.compareAndSet(null, afterReason);
                                completedOutputs.put(task, output);
                                completionOrder.add(output);
                                log.info(
                                        "Parallel exit-early ({}) triggered after task: {}",
                                        afterReason,
                                        truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));

                                executionContext.fireTaskComplete(new TaskCompleteEvent(
                                        task.getDescription(),
                                        agentRole(task),
                                        output,
                                        output.getDuration(),
                                        taskIndex,
                                        totalTasks));
                                return;
                            } else if (afterDecision instanceof ReviewDecision.Edit edit) {
                                output = output.toBuilder()
                                        .raw(edit.revisedOutput())
                                        .build();
                                log.info(
                                        "Task (parallel) output replaced by reviewer | Task: {}",
                                        truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));
                            }
                            // Continue: proceed with output unchanged
                        }
                    }
                }

                completedOutputs.put(task, output);
                completionOrder.add(output);

                log.info(
                        "Task completed (parallel) | Agent: {} | Duration: {} | Tool calls: {}",
                        agentRole(task),
                        output.getDuration(),
                        output.getToolCallCount());

                executionContext.fireTaskComplete(new TaskCompleteEvent(
                        task.getDescription(), agentRole(task), output, output.getDuration(), taskIndex, totalTasks));

            } catch (ExitEarlyException e) {
                // HumanInputTool requested exit-early during task execution.
                // This task did NOT complete; its output is not recorded.
                ExitReason toolReason = e.isTimedOut() ? ExitReason.TIMEOUT : ExitReason.USER_EXIT_EARLY;
                exitEarlyReasonRef.compareAndSet(null, toolReason);
                log.info(
                        "Parallel HumanInputTool exit-early ({}) | Task: {}",
                        toolReason,
                        truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));

            } catch (Exception e) {
                Duration taskDuration = Duration.between(taskStart, Instant.now());
                log.error(
                        "Task failed (parallel) | Agent: {} | Description: {} | Error: {}",
                        agentRole(task),
                        truncate(task.getDescription(), LOG_TRUNCATE_LENGTH),
                        e.getMessage());

                failedTaskCauses.put(task, e);

                executionContext.fireTaskFailed(new TaskFailedEvent(
                        task.getDescription(), agentRole(task), e, taskDuration, taskIndex, totalTasks));

                if (errorStrategy == ParallelErrorStrategy.FAIL_FAST) {
                    firstFailureRef.compareAndSet(
                            null,
                            new TaskExecutionException(
                                    "Task failed: " + task.getDescription(),
                                    task.getDescription(),
                                    agentRole(task),
                                    List.copyOf(completedOutputs.values()),
                                    e));
                }

            } finally {
                if (prevMdc != null) {
                    MDC.setContextMap(prevMdc);
                } else {
                    MDC.clear();
                }
                latch.countDown();
                resolveDependent(task);
            }
        });
    }

    /**
     * Evaluate dependents of a task that has just been resolved (completed, failed, or skipped).
     *
     * For each dependent, decrement its pending dependency count. If the count reaches 0,
     * either submit the task (all deps succeeded, no FAIL_FAST failure pending) or skip it
     * (a dep failed or was skipped, or FAIL_FAST applies). Skipped tasks cascade their
     * resolution to their own dependents recursively.
     */
    private void resolveDependent(Task resolvedTask) {
        for (Task dependent : graph.getDependents(resolvedTask)) {
            int remaining = pendingDepCounts.get(dependent).decrementAndGet();
            if (remaining > 0) {
                continue;
            }

            boolean shouldSkip = shouldSkip(dependent);

            if (shouldSkip) {
                log.debug(
                        "Task skipped (parallel) | Agent: {} | Description: {}",
                        agentRole(dependent),
                        truncate(dependent.getDescription(), LOG_TRUNCATE_LENGTH));

                skippedTasks.add(dependent);
                latch.countDown();
                resolveDependent(dependent);
            } else {
                submitTask(dependent);
            }
        }
    }

    /**
     * Determine whether a task should be skipped based on the current failure/exit state.
     *
     * For {@link ParallelErrorStrategy#CONTINUE_ON_ERROR}, a task is skipped if any
     * of its in-graph dependencies either failed or was itself skipped. Both checks are
     * required for correct transitive skip propagation.
     *
     * <p>When exit-early has been requested (by a review gate or HumanInputTool), all
     * pending tasks are skipped.
     *
     * @return true if the task should be skipped rather than submitted
     */
    private boolean shouldSkip(Task task) {
        // Exit-early: skip all pending tasks
        if (exitEarlyReasonRef.get() != null) {
            return true;
        }

        if (errorStrategy == ParallelErrorStrategy.FAIL_FAST && firstFailureRef.get() != null) {
            return true;
        }

        if (errorStrategy == ParallelErrorStrategy.CONTINUE_ON_ERROR) {
            return task.getContext().stream()
                    .filter(graph::isInGraph)
                    .anyMatch(dep -> failedTaskCauses.containsKey(dep) || skippedTasks.contains(dep));
        }

        return false;
    }

    /**
     * Returns true when an after-execution review gate should fire for the given task.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>No handler configured -- never fire.</li>
     *   <li>Task-level {@link Review#skip()} -- never fire.</li>
     *   <li>Task-level {@link Review#required()} -- always fire.</li>
     *   <li>Ensemble {@link ReviewPolicy} -- NEVER / AFTER_EVERY_TASK.
     *       {@link ReviewPolicy#AFTER_LAST_TASK} is not supported for parallel execution
     *       (tasks complete in DAG order, not declaration order; the concept of "last task"
     *       is ambiguous). Use {@link Review#required()} on the terminal task explicitly.</li>
     * </ol>
     */
    private boolean shouldApplyAfterReview(Task task, int taskIndex) {
        ReviewHandler handler = executionContext.reviewHandler();
        if (handler == null) {
            return false;
        }

        Review review = task.getReview();

        if (review != null && review.isSkip()) {
            return false;
        }

        if (review != null && review.isRequired()) {
            return true;
        }

        ReviewPolicy policy = executionContext.reviewPolicy();
        return switch (policy) {
            case NEVER -> false;
            case AFTER_EVERY_TASK -> true;
                // AFTER_LAST_TASK is undefined in parallel: tasks complete in DAG order,
                // not declaration order. Always return false; use Review.required() on the
                // intended terminal task instead.
            case AFTER_LAST_TASK -> false;
        };
    }

    /**
     * Inject the {@link ReviewHandler} into any {@link HumanInputTool} instances
     * present in the task's agent tool list.
     */
    private static void injectReviewHandlerIntoTools(Task task, ReviewHandler reviewHandler) {
        for (Object tool : task.getAgent().getTools()) {
            if (tool instanceof HumanInputTool humanInputTool) {
                humanInputTool.injectReviewHandler(reviewHandler);
            }
        }
    }

    /**
     * Returns the agent role for a task.
     *
     * <ul>
     *   <li>If the task has an explicit agent, returns the agent's role.</li>
     *   <li>If the task has a handler configured (deterministic task), returns
     *       {@link DeterministicTaskExecutor#DETERMINISTIC_ROLE}.</li>
     *   <li>Otherwise returns {@code "(synthesized)"} -- guards against NPEs in error paths
     *       where a task may not yet have a resolved agent (issue #148).</li>
     * </ul>
     */
    private static String agentRole(Task task) {
        if (task.getAgent() != null) {
            return task.getAgent().getRole();
        }
        if (task.getHandler() != null) {
            return DeterministicTaskExecutor.DETERMINISTIC_ROLE;
        }
        return "(synthesized)";
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
