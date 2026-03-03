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
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
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
    private final CountDownLatch latch;
    private final DelegationContext delegationContext;
    private final ExecutionContext executionContext;
    private final int totalTasks;
    private final ExecutorService executor;
    private final List<TaskOutput> completionOrder;
    private final Map<Task, Integer> taskIndexMap;
    private final ParallelErrorStrategy errorStrategy;
    private final AgentExecutor agentExecutor;

    ParallelTaskCoordinator(
            TaskDependencyGraph graph,
            Map<String, String> callerMdc,
            Map<Task, TaskOutput> completedOutputs,
            Map<Task, Throwable> failedTaskCauses,
            Set<Task> skippedTasks,
            Map<Task, AtomicInteger> pendingDepCounts,
            AtomicReference<TaskExecutionException> firstFailureRef,
            CountDownLatch latch,
            DelegationContext delegationContext,
            ExecutionContext executionContext,
            int totalTasks,
            ExecutorService executor,
            List<TaskOutput> completionOrder,
            Map<Task, Integer> taskIndexMap,
            ParallelErrorStrategy errorStrategy,
            AgentExecutor agentExecutor) {
        this.graph = graph;
        this.callerMdc = callerMdc;
        this.completedOutputs = completedOutputs;
        this.failedTaskCauses = failedTaskCauses;
        this.skippedTasks = skippedTasks;
        this.pendingDepCounts = pendingDepCounts;
        this.firstFailureRef = firstFailureRef;
        this.latch = latch;
        this.delegationContext = delegationContext;
        this.executionContext = executionContext;
        this.totalTasks = totalTasks;
        this.executor = executor;
        this.completionOrder = completionOrder;
        this.taskIndexMap = taskIndexMap;
        this.errorStrategy = errorStrategy;
        this.agentExecutor = agentExecutor;
    }

    /**
     * Submit a task for execution on a virtual thread.
     *
     * The task is run via {@link AgentExecutor}. On completion (success or failure),
     * the task's state is recorded and its dependents are evaluated via
     * {@link #resolveDependent}. The latch is decremented exactly once per task
     * invocation (in the finally block).
     */
    void submitTask(Task task) {
        var unused = executor.submit(() -> {
            Map<String, String> prevMdc = MDC.getCopyOfContextMap();
            MDC.setContextMap(callerMdc);
            MDC.put(MDC_AGENT_ROLE, task.getAgent().getRole());

            int taskIndex = taskIndexMap.getOrDefault(task, 0);
            Instant taskStart = Instant.now();
            try {
                log.info(
                        "Task starting (parallel) | Agent: {} | Description: {}",
                        task.getAgent().getRole(),
                        truncate(task.getDescription(), LOG_TRUNCATE_LENGTH));

                executionContext.fireTaskStart(new TaskStartEvent(
                        task.getDescription(), task.getAgent().getRole(), taskIndex, totalTasks));

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

                TaskOutput output = agentExecutor.execute(task, contextOutputs, executionContext, delegationContext);

                completedOutputs.put(task, output);
                completionOrder.add(output);

                log.info(
                        "Task completed (parallel) | Agent: {} | Duration: {} | Tool calls: {}",
                        task.getAgent().getRole(),
                        output.getDuration(),
                        output.getToolCallCount());

                executionContext.fireTaskComplete(new TaskCompleteEvent(
                        task.getDescription(),
                        task.getAgent().getRole(),
                        output,
                        output.getDuration(),
                        taskIndex,
                        totalTasks));

            } catch (Exception e) {
                Duration taskDuration = Duration.between(taskStart, Instant.now());
                log.error(
                        "Task failed (parallel) | Agent: {} | Description: {} | Error: {}",
                        task.getAgent().getRole(),
                        truncate(task.getDescription(), LOG_TRUNCATE_LENGTH),
                        e.getMessage());

                failedTaskCauses.put(task, e);

                executionContext.fireTaskFailed(new TaskFailedEvent(
                        task.getDescription(), task.getAgent().getRole(), e, taskDuration, taskIndex, totalTasks));

                if (errorStrategy == ParallelErrorStrategy.FAIL_FAST) {
                    firstFailureRef.compareAndSet(
                            null,
                            new TaskExecutionException(
                                    "Task failed: " + task.getDescription(),
                                    task.getDescription(),
                                    task.getAgent().getRole(),
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
                        dependent.getAgent().getRole(),
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
     * Determine whether a task should be skipped based on the current failure state.
     *
     * For {@link ParallelErrorStrategy#CONTINUE_ON_ERROR}, a task is skipped if any
     * of its in-graph dependencies either failed or was itself skipped. Both checks are
     * required for correct transitive skip propagation.
     *
     * @return true if the task should be skipped rather than submitted
     */
    private boolean shouldSkip(Task task) {
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

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
