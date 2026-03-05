package net.agentensemble.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.exception.MaxIterationsExceededException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.guardrail.GuardrailViolationException;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes tasks one after another in list order.
 *
 * Each task's output is stored and made available as context to subsequent tasks
 * that declare a context dependency on it. MDC values (task.index, agent.role)
 * are set for the duration of each task execution to enable structured logging.
 *
 * When the {@link ExecutionContext} carries an active memory context, memory
 * injection and recording are handled transparently by {@link AgentExecutor}.
 *
 * When the {@link ExecutionContext} carries registered {@link net.agentensemble.callback.EnsembleListener}
 * instances, {@link TaskStartEvent}, {@link TaskCompleteEvent}, and {@link TaskFailedEvent}
 * are fired at the appropriate points in the execution lifecycle.
 *
 * Stateless -- all state is held in local variables.
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
    }

    @Override
    public EnsembleOutput execute(List<Task> resolvedTasks, ExecutionContext executionContext) {
        Instant ensembleStartTime = Instant.now();
        int totalTasks = resolvedTasks.size();
        Map<Task, TaskOutput> completedOutputs = new LinkedHashMap<>();

        // Create the delegation context once for the entire run; all agents share it.
        // Policies are threaded in so that AgentDelegationTool can evaluate them per delegation.
        DelegationContext delegationContext = DelegationContext.create(
                agents, maxDelegationDepth, executionContext, agentExecutor, delegationPolicies);

        for (int i = 0; i < totalTasks; i++) {
            Task task = resolvedTasks.get(i);
            int taskIndex = i + 1;
            String indexLabel = taskIndex + "/" + totalTasks;

            MDC.put(MDC_TASK_INDEX, indexLabel);
            MDC.put(MDC_AGENT_ROLE, task.getAgent().getRole());

            Instant taskStart = Instant.now();
            try {
                log.info(
                        "Task {}/{} starting | Description: {} | Agent: {}",
                        taskIndex,
                        totalTasks,
                        truncate(task.getDescription(), MDC_DESCRIPTION_MAX_LENGTH),
                        task.getAgent().getRole());

                // Fire TaskStartEvent
                executionContext.fireTaskStart(new TaskStartEvent(
                        task.getDescription(), task.getAgent().getRole(), taskIndex, totalTasks));

                // Gather explicit context outputs for this task
                List<TaskOutput> contextOutputs = gatherContextOutputs(task, completedOutputs);
                log.debug("Task {}/{} context: {} prior outputs", taskIndex, totalTasks, contextOutputs.size());

                // Execute the task with delegation context -- delegation tool is injected
                // automatically when the agent has allowDelegation=true
                TaskOutput taskOutput =
                        agentExecutor.execute(task, contextOutputs, executionContext, delegationContext);
                completedOutputs.put(task, taskOutput);

                log.info(
                        "Task {}/{} completed | Duration: {} | Tool calls: {}",
                        taskIndex,
                        totalTasks,
                        taskOutput.getDuration(),
                        taskOutput.getToolCallCount());

                if (executionContext.isVerbose()) {
                    log.info(
                            "Task {}/{} output preview: {}", taskIndex, totalTasks, truncate(taskOutput.getRaw(), 200));
                } else {
                    log.debug(
                            "Task {}/{} output preview: {}", taskIndex, totalTasks, truncate(taskOutput.getRaw(), 200));
                }

                // Fire TaskCompleteEvent
                executionContext.fireTaskComplete(new TaskCompleteEvent(
                        task.getDescription(),
                        task.getAgent().getRole(),
                        taskOutput,
                        taskOutput.getDuration(),
                        taskIndex,
                        totalTasks));

            } catch (AgentExecutionException | MaxIterationsExceededException | GuardrailViolationException e) {
                Duration taskDuration = Duration.between(taskStart, Instant.now());
                log.error("Task {}/{} failed: {}", taskIndex, totalTasks, e.getMessage());

                // Fire TaskFailedEvent before propagating
                executionContext.fireTaskFailed(new TaskFailedEvent(
                        task.getDescription(), task.getAgent().getRole(), e, taskDuration, taskIndex, totalTasks));

                throw new TaskExecutionException(
                        "Task failed: " + task.getDescription(),
                        task.getDescription(),
                        task.getAgent().getRole(),
                        List.copyOf(completedOutputs.values()),
                        e);
            } finally {
                MDC.remove(MDC_TASK_INDEX);
                MDC.remove(MDC_AGENT_ROLE);
            }
        }

        // Assemble EnsembleOutput
        Duration totalDuration = Duration.between(ensembleStartTime, Instant.now());
        List<TaskOutput> allOutputs = List.copyOf(completedOutputs.values());
        String finalOutput = allOutputs.isEmpty() ? "" : allOutputs.getLast().getRaw();
        int totalToolCalls =
                allOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

        return EnsembleOutput.builder()
                .raw(finalOutput)
                .taskOutputs(allOutputs)
                .totalDuration(totalDuration)
                .totalToolCalls(totalToolCalls)
                .build();
    }

    private List<TaskOutput> gatherContextOutputs(Task task, Map<Task, TaskOutput> completedOutputs) {
        List<TaskOutput> contextOutputs = new ArrayList<>();
        for (Task contextTask : task.getContext()) {
            TaskOutput output = completedOutputs.get(contextTask);
            if (output == null) {
                // This should not happen if Ensemble validation passed, but guard defensively
                throw new TaskExecutionException(
                        "Context task not yet completed: " + contextTask.getDescription(),
                        contextTask.getDescription(),
                        contextTask.getAgent().getRole(),
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
