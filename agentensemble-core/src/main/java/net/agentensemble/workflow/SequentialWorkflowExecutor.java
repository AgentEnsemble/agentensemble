package net.agentensemble.workflow;

import dev.langchain4j.model.chat.ChatModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.agent.DeterministicTaskExecutor;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.exception.MaxIterationsExceededException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.guardrail.GuardrailViolationException;
import net.agentensemble.memory.MemoryEntry;
import net.agentensemble.memory.MemoryStore;
import net.agentensemble.reflection.TaskReflector;
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
 * Executes tasks one after another in list order.
 *
 * <p>Each task's output is stored and made available as context to subsequent tasks
 * that declare a context dependency on it. MDC values (task.index, agent.role)
 * are set for the duration of each task execution to enable structured logging.
 *
 * <p>Review gates are fired at three timing points when a {@link ReviewHandler} is
 * configured on the {@link ExecutionContext}:
 * <ul>
 *   <li>{@link ReviewTiming#BEFORE_EXECUTION} -- fires when
 *       {@code Task.builder().beforeReview(Review)} is set; ExitEarly stops the pipeline
 *       before the task runs.</li>
 *   <li>{@link ReviewTiming#DURING_EXECUTION} -- fires when the agent invokes
 *       {@link HumanInputTool}; ExitEarly propagates as {@link ExitEarlyException}.</li>
 *   <li>{@link ReviewTiming#AFTER_EXECUTION} -- fires when
 *       {@code Task.builder().review(Review)} is set or the ensemble
 *       {@link ReviewPolicy} requires it; Edit replaces the task output; ExitEarly
 *       includes the current task output and stops the pipeline.</li>
 * </ul>
 *
 * <p>Stateless -- all state is held in local variables.
 */
public class SequentialWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(SequentialWorkflowExecutor.class);

    /** MDC key for the current task position (e.g., "2/5"). */
    private static final String MDC_TASK_INDEX = "task.index";

    /** MDC key for the current agent's role. */
    private static final String MDC_AGENT_ROLE = "agent.role";

    /** Truncation length for task description in MDC and logs. */
    private static final int MDC_DESCRIPTION_MAX_LENGTH = 80;

    private final List<Agent> agents;
    private final int maxDelegationDepth;
    private final List<DelegationPolicy> delegationPolicies;
    private final AgentExecutor agentExecutor;
    private final DeterministicTaskExecutor deterministicExecutor;

    /**
     * Create a SequentialWorkflowExecutor with delegation support but no delegation policies.
     *
     * @param agents             all agents registered with the ensemble (used to build DelegationContext)
     * @param maxDelegationDepth maximum allowed delegation depth for agents with allowDelegation=true
     */
    public SequentialWorkflowExecutor(List<Agent> agents, int maxDelegationDepth) {
        this(agents, maxDelegationDepth, List.of());
    }

    /**
     * Create a SequentialWorkflowExecutor with delegation support and delegation policies.
     *
     * @param agents              all agents registered with the ensemble
     * @param maxDelegationDepth  maximum allowed delegation depth for agents with allowDelegation=true
     * @param delegationPolicies  policies to evaluate before each delegation attempt;
     *                            evaluated in list order; must not be null
     */
    public SequentialWorkflowExecutor(
            List<Agent> agents, int maxDelegationDepth, List<DelegationPolicy> delegationPolicies) {
        this.agents = List.copyOf(agents);
        this.maxDelegationDepth = maxDelegationDepth;
        this.delegationPolicies = delegationPolicies != null ? List.copyOf(delegationPolicies) : List.of();
        this.agentExecutor = new AgentExecutor();
        this.deterministicExecutor = new DeterministicTaskExecutor();
    }

    @Override
    public EnsembleOutput execute(List<Task> resolvedTasks, ExecutionContext executionContext) {
        return executeSeeded(resolvedTasks, executionContext, new LinkedHashMap<>());
    }

    /**
     * Execute with a pre-seeded completed-outputs map.
     *
     * <p>Used by {@link PhaseDagExecutor} to inject outputs from previously-completed phases
     * so that cross-phase {@code context()} references resolve correctly in this phase's tasks.
     *
     * @param resolvedTasks    tasks to execute (with template vars resolved, agents synthesized)
     * @param executionContext execution context
     * @param seedOutputs      outputs from prior phases, keyed by identity; the executor
     *                         pre-populates its completedOutputs map from these entries
     * @return the ensemble output for this phase's tasks
     */
    public EnsembleOutput executeSeeded(
            List<Task> resolvedTasks, ExecutionContext executionContext, Map<Task, TaskOutput> seedOutputs) {
        Instant ensembleStartTime = Instant.now();
        int totalTasks = resolvedTasks.size();
        // Pre-seed with prior outputs so cross-phase context() references resolve correctly.
        // Uses LinkedHashMap to preserve insertion order for the flat task output list.
        Map<Task, TaskOutput> completedOutputs = new LinkedHashMap<>(seedOutputs);

        // Create the delegation context once for the entire run; all agents share it.
        DelegationContext delegationContext = DelegationContext.create(
                agents, maxDelegationDepth, executionContext, agentExecutor, delegationPolicies);

        ReviewHandler reviewHandler = executionContext.reviewHandler();

        for (int i = 0; i < totalTasks; i++) {
            Task task = resolvedTasks.get(i);
            int taskIndex = i + 1;
            String indexLabel = taskIndex + "/" + totalTasks;

            MDC.put(MDC_TASK_INDEX, indexLabel);
            MDC.put(MDC_AGENT_ROLE, agentRole(task));

            Instant taskStart = Instant.now();
            try {
                // === Before-execution review gate ===
                if (shouldApplyBeforeReview(task, reviewHandler)) {
                    Review beforeRev = task.getBeforeReview();
                    ReviewRequest beforeRequest = ReviewRequest.of(
                            task.getDescription(),
                            "",
                            ReviewTiming.BEFORE_EXECUTION,
                            beforeRev.getTimeout(),
                            beforeRev.getOnTimeoutAction(),
                            beforeRev.getPrompt(),
                            beforeRev.getRequiredRole());

                    ReviewDecision beforeDecision = reviewHandler.review(beforeRequest);
                    if (log.isInfoEnabled()) {
                        log.info(
                                "Task {}/{} before-review decision: {}",
                                taskIndex,
                                totalTasks,
                                beforeDecision.getClass().getSimpleName());
                    }

                    if (beforeDecision instanceof ReviewDecision.ExitEarly exitEarlyDecision) {
                        // Task does not execute; return what's been done so far
                        ExitReason beforeExitReason =
                                exitEarlyDecision.timedOut() ? ExitReason.TIMEOUT : ExitReason.USER_EXIT_EARLY;
                        log.info(
                                "Before-review gate: exit early ({}) before task {}/{}",
                                beforeExitReason,
                                taskIndex,
                                totalTasks);
                        return buildPartialOutput(completedOutputs, ensembleStartTime, beforeExitReason);
                    }
                    // Continue or Edit (Edit before execution is treated as Continue)
                }

                // === Inject ReviewHandler into HumanInputTool instances (AI tasks only) ===
                if (reviewHandler != null && task.getAgent() != null) {
                    injectReviewHandlerIntoTools(task, reviewHandler);
                }

                if (log.isInfoEnabled()) {
                    log.info(
                            "Task {}/{} starting | Description: {} | Agent: {}",
                            taskIndex,
                            totalTasks,
                            truncate(task.getDescription(), MDC_DESCRIPTION_MAX_LENGTH),
                            agentRole(task));
                }

                // Fire TaskStartEvent
                executionContext.fireTaskStart(
                        new TaskStartEvent(task.getDescription(), agentRole(task), taskIndex, totalTasks));

                // Gather explicit context outputs for this task
                List<TaskOutput> contextOutputs = gatherContextOutputs(task, completedOutputs);
                if (log.isDebugEnabled()) {
                    log.debug("Task {}/{} context: {} prior outputs", taskIndex, totalTasks, contextOutputs.size());
                }

                // Execute the task -- deterministic handler tasks bypass AgentExecutor entirely
                TaskOutput taskOutput;
                if (task.getHandler() != null) {
                    taskOutput = deterministicExecutor.execute(task, contextOutputs, executionContext);
                } else {
                    taskOutput = agentExecutor.execute(task, contextOutputs, executionContext, delegationContext);
                }

                // === After-execution review gate ===
                if (shouldApplyAfterReview(task, taskIndex, totalTasks, executionContext)) {
                    Review review = task.getReview();
                    Duration timeout = review != null ? review.getTimeout() : Review.DEFAULT_TIMEOUT;
                    OnTimeoutAction onTimeout =
                            review != null ? review.getOnTimeoutAction() : Review.DEFAULT_ON_TIMEOUT;
                    String prompt = review != null ? review.getPrompt() : null;
                    String requiredRole = review != null ? review.getRequiredRole() : null;

                    ReviewRequest afterRequest = ReviewRequest.of(
                            task.getDescription(),
                            taskOutput.getRaw(),
                            ReviewTiming.AFTER_EXECUTION,
                            timeout,
                            onTimeout,
                            prompt,
                            requiredRole);

                    ReviewDecision afterDecision = reviewHandler.review(afterRequest);
                    if (log.isInfoEnabled()) {
                        log.info(
                                "Task {}/{} after-review decision: {}",
                                taskIndex,
                                totalTasks,
                                afterDecision.getClass().getSimpleName());
                    }

                    if (afterDecision instanceof ReviewDecision.Edit edit) {
                        // Replace task output with revised text and update memory
                        taskOutput = applyEdit(taskOutput, edit.revisedOutput(), task, executionContext);
                        log.info("Task {}/{} output replaced by reviewer", taskIndex, totalTasks);
                    } else if (afterDecision instanceof ReviewDecision.ExitEarly afterExitEarlyDecision) {
                        // Include this task in output, then stop.
                        // Run reflection on the accepted output before recording and returning early.
                        ChatModel reflectionModel =
                                task.getAgent() != null ? task.getAgent().getLlm() : null;
                        TaskReflector.reflect(task, taskOutput.getRaw(), reflectionModel, executionContext);
                        completedOutputs.put(task, taskOutput);
                        ExitReason afterExitReason =
                                afterExitEarlyDecision.timedOut() ? ExitReason.TIMEOUT : ExitReason.USER_EXIT_EARLY;
                        log.info(
                                "After-review gate: exit early ({}) after task {}/{}",
                                afterExitReason,
                                taskIndex,
                                totalTasks);
                        return buildPartialOutput(completedOutputs, ensembleStartTime, afterExitReason);
                    }
                    // Continue: pass output forward unchanged
                }

                // Run reflection on the final accepted output -- after all review gates pass
                // and after any reviewer edits are applied.
                ChatModel reflectionModel =
                        task.getAgent() != null ? task.getAgent().getLlm() : null;
                TaskReflector.reflect(task, taskOutput.getRaw(), reflectionModel, executionContext);

                completedOutputs.put(task, taskOutput);

                if (log.isInfoEnabled()) {
                    log.info(
                            "Task {}/{} completed | Duration: {} | Tool calls: {}",
                            taskIndex,
                            totalTasks,
                            taskOutput.getDuration(),
                            taskOutput.getToolCallCount());
                }

                if (executionContext.isVerbose()) {
                    if (log.isInfoEnabled()) {
                        log.info(
                                "Task {}/{} output preview: {}",
                                taskIndex,
                                totalTasks,
                                truncate(taskOutput.getRaw(), 200));
                    }
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Task {}/{} output preview: {}",
                                taskIndex,
                                totalTasks,
                                truncate(taskOutput.getRaw(), 200));
                    }
                }

                // Fire TaskCompleteEvent -- use the role reported in the output (set by the executor)
                executionContext.fireTaskComplete(new TaskCompleteEvent(
                        task.getDescription(),
                        taskOutput.getAgentRole(),
                        taskOutput,
                        taskOutput.getDuration(),
                        taskIndex,
                        totalTasks));

            } catch (ExitEarlyException e) {
                // HumanInputTool requested exit-early during agent execution.
                // completedOutputs does NOT include the current task (it did not complete normally).
                ExitReason toolExitReason = e.isTimedOut() ? ExitReason.TIMEOUT : ExitReason.USER_EXIT_EARLY;
                if (log.isInfoEnabled()) {
                    log.info(
                            "HumanInputTool exit-early ({}): pipeline stopping after {}/{} tasks completed",
                            toolExitReason,
                            completedOutputs.size(),
                            totalTasks);
                }
                return buildPartialOutput(completedOutputs, ensembleStartTime, toolExitReason);

            } catch (AgentExecutionException | MaxIterationsExceededException | GuardrailViolationException e) {
                Duration taskDuration = Duration.between(taskStart, Instant.now());
                if (log.isErrorEnabled()) {
                    log.error("Task {}/{} failed: {}", taskIndex, totalTasks, e.getMessage());
                }

                // Fire TaskFailedEvent before propagating
                executionContext.fireTaskFailed(new TaskFailedEvent(
                        task.getDescription(), agentRole(task), e, taskDuration, taskIndex, totalTasks));

                throw new TaskExecutionException(
                        "Task failed: " + task.getDescription(),
                        task.getDescription(),
                        agentRole(task),
                        List.copyOf(completedOutputs.values()),
                        e);
            } finally {
                MDC.remove(MDC_TASK_INDEX);
                MDC.remove(MDC_AGENT_ROLE);
            }
        }

        // Assemble EnsembleOutput for the tasks executed in THIS invocation only.
        // The completedOutputs map may contain seed outputs from prior phases; those must
        // NOT be included in the returned task list to prevent duplicates when the phase
        // DAG executor aggregates outputs across phases.
        List<TaskOutput> thisRunOutputs = resolvedTasks.stream()
                .map(completedOutputs::get)
                .filter(to -> to != null)
                .collect(java.util.stream.Collectors.toUnmodifiableList());

        // Build a phase-only taskOutputIndex (excludes seed outputs from prior phases).
        java.util.IdentityHashMap<Task, TaskOutput> phaseOnlyIndex = new java.util.IdentityHashMap<>();
        for (Task task : resolvedTasks) {
            TaskOutput to = completedOutputs.get(task);
            if (to != null) {
                phaseOnlyIndex.put(task, to);
            }
        }

        Duration totalDuration = Duration.between(ensembleStartTime, Instant.now());
        String finalOutput =
                thisRunOutputs.isEmpty() ? "" : thisRunOutputs.getLast().getRaw();
        int totalToolCalls =
                thisRunOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        return EnsembleOutput.builder()
                .raw(finalOutput)
                .taskOutputs(thisRunOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .taskOutputIndex(phaseOnlyIndex) // identity-based Task -> TaskOutput index (this phase only)
                .build(); // exitReason defaults to COMPLETED
    }

    // ========================
    // Review gate helpers
    // ========================

    /**
     * Returns true when a before-execution review gate should fire for the given task.
     *
     * <p>Fires when the task has an explicit {@link Review} configured via
     * {@code Task.builder().beforeReview(Review)} and that review is not marked as skip,
     * and a {@link ReviewHandler} is available.
     *
     * @param task          the task to check
     * @param reviewHandler the ensemble-level review handler; may be null
     * @return true if the before-review gate should fire
     */
    private static boolean shouldApplyBeforeReview(Task task, ReviewHandler reviewHandler) {
        if (reviewHandler == null) {
            return false;
        }
        Review beforeReview = task.getBeforeReview();
        return beforeReview != null && beforeReview.isRequired();
    }

    /**
     * Returns true when an after-execution review gate should fire for the given task.
     *
     * <p>Evaluation order:
     * <ol>
     *   <li>No handler configured -- never fire.</li>
     *   <li>Task-level {@link Review#skip()} -- never fire regardless of ensemble policy.</li>
     *   <li>Task-level {@link Review#required()} -- always fire regardless of ensemble policy.</li>
     *   <li>Ensemble {@link ReviewPolicy} -- NEVER/AFTER_EVERY_TASK/AFTER_LAST_TASK.</li>
     * </ol>
     *
     * @param task           the task to check
     * @param taskIndex      1-based position of this task in the pipeline
     * @param totalTasks     total number of tasks in the pipeline
     * @param ctx            the execution context
     * @return true if the after-review gate should fire
     */
    private static boolean shouldApplyAfterReview(Task task, int taskIndex, int totalTasks, ExecutionContext ctx) {
        ReviewHandler handler = ctx.reviewHandler();
        if (handler == null) {
            return false;
        }

        Review review = task.getReview();

        // Task-level skip overrides everything
        if (review != null && review.isSkip()) {
            return false;
        }

        // Task-level required overrides ensemble policy
        if (review != null && review.isRequired()) {
            return true;
        }

        // Ensemble policy applies
        ReviewPolicy policy = ctx.reviewPolicy();
        return switch (policy) {
            case NEVER -> false;
            case AFTER_EVERY_TASK -> true;
            case AFTER_LAST_TASK -> taskIndex == totalTasks;
        };
    }

    /**
     * Inject the {@link ReviewHandler} into any {@link HumanInputTool} instances
     * present in the task's agent tool list.
     *
     * @param task          the resolved task (with a synthesized or explicit agent)
     * @param reviewHandler the handler to inject
     */
    private static void injectReviewHandlerIntoTools(Task task, ReviewHandler reviewHandler) {
        for (Object tool : task.getAgent().getTools()) {
            if (tool instanceof HumanInputTool humanInputTool) {
                humanInputTool.injectReviewHandler(reviewHandler);
            }
        }
    }

    /**
     * Apply a reviewer's edit decision: create a revised {@link TaskOutput} with the
     * replacement text and re-store it in any declared memory scopes.
     *
     * @param original       the original task output
     * @param revisedRaw     the reviewer's replacement text
     * @param task           the task that produced the output
     * @param ctx            the execution context (used for MemoryStore access)
     * @return a new TaskOutput with {@code raw} replaced by {@code revisedRaw}
     */
    private static TaskOutput applyEdit(TaskOutput original, String revisedRaw, Task task, ExecutionContext ctx) {

        TaskOutput revised = original.toBuilder().raw(revisedRaw).build();

        // Overwrite in memory scopes if the task has declared any
        MemoryStore memStore = ctx.memoryStore();
        if (memStore != null
                && task.getMemoryScopes() != null
                && !task.getMemoryScopes().isEmpty()) {

            MemoryEntry entry = MemoryEntry.builder()
                    .content(revisedRaw)
                    .structuredContent(null)
                    .storedAt(original.getCompletedAt())
                    .metadata(Map.of(
                            MemoryEntry.META_AGENT_ROLE,
                            original.getAgentRole() != null ? original.getAgentRole() : "",
                            MemoryEntry.META_TASK_DESCRIPTION,
                            original.getTaskDescription() != null ? original.getTaskDescription() : ""))
                    .build();

            for (net.agentensemble.memory.MemoryScope scope : task.getMemoryScopes()) {
                memStore.store(scope.getName(), entry);
                if (scope.getEvictionPolicy() != null) {
                    memStore.evict(scope.getName(), scope.getEvictionPolicy());
                }
            }
        }

        return revised;
    }

    /**
     * Build a partial {@link EnsembleOutput} for an early-exit scenario.
     *
     * @param completedOutputs map of all tasks completed before the exit signal
     * @param startTime        the ensemble run start time (for duration calculation)
     * @param exitReason       why the run stopped early
     * @return a partial EnsembleOutput
     */
    private static EnsembleOutput buildPartialOutput(
            Map<Task, TaskOutput> completedOutputs, Instant startTime, ExitReason exitReason) {

        Duration totalDuration = Duration.between(startTime, Instant.now());
        List<TaskOutput> allOutputs = List.copyOf(completedOutputs.values());
        String finalOutput = allOutputs.isEmpty() ? "" : allOutputs.getLast().getRaw();
        int totalToolCalls =
                allOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        return EnsembleOutput.builder()
                .raw(finalOutput)
                .taskOutputs(allOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .exitReason(exitReason)
                .taskOutputIndex(completedOutputs) // identity-based Task -> TaskOutput index
                .build();
    }

    // ========================
    // Context / utility helpers
    // ========================

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

    private List<TaskOutput> gatherContextOutputs(Task task, Map<Task, TaskOutput> completedOutputs) {
        List<TaskOutput> contextOutputs = new ArrayList<>();
        for (Task contextTask : task.getContext()) {
            TaskOutput output = completedOutputs.get(contextTask);
            if (output == null) {
                throw new TaskExecutionException(
                        "Context task not yet completed: " + contextTask.getDescription(),
                        contextTask.getDescription(),
                        agentRole(contextTask),
                        List.copyOf(completedOutputs.values()));
            }
            contextOutputs.add(output);
        }
        return contextOutputs;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return "";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
