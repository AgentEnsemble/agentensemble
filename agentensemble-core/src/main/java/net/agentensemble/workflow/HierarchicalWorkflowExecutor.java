package net.agentensemble.workflow;

import dev.langchain4j.model.chat.ChatModel;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.AgentExecutionException;
import net.agentensemble.exception.MaxIterationsExceededException;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Executes tasks via a Manager agent that delegates to worker agents.
 *
 * The Manager is a virtual agent created at runtime with:
 * <ul>
 *   <li>A system prompt listing all available worker agents (via ManagerPromptBuilder)</li>
 *   <li>A user prompt listing all tasks to complete (via ManagerPromptBuilder)</li>
 *   <li>The delegateTask tool that dispatches tasks to workers</li>
 * </ul>
 *
 * The Manager uses the delegateTask tool to assign tasks, receives each worker's
 * output as the tool result, and synthesizes a final response. All worker outputs
 * and the manager's final output are included in the EnsembleOutput.
 *
 * When a {@link MemoryContext} is active, memory is shared across all
 * delegations: worker agents read from and write to the same context.
 *
 * Stateless -- all mutable state is held in per-execution local variables.
 */
public class HierarchicalWorkflowExecutor implements WorkflowExecutor {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalWorkflowExecutor.class);

    /** MDC key for the current agent role. */
    private static final String MDC_AGENT_ROLE = "agent.role";

    /** The role assigned to the automatically created manager agent. */
    static final String MANAGER_ROLE = "Manager";

    /** The goal assigned to the automatically created manager agent. */
    private static final String MANAGER_GOAL =
            "Coordinate worker agents to complete all tasks and synthesize a comprehensive final result";

    /** The expected output for the manager's meta-task. */
    private static final String MANAGER_EXPECTED_OUTPUT =
            "A comprehensive final response synthesizing the outputs of all delegated tasks";

    private final ChatModel managerLlm;
    private final List<Agent> workerAgents;
    private final int managerMaxIterations;
    private final int maxDelegationDepth;
    private final AgentExecutor agentExecutor;

    /**
     * @param managerLlm           LLM for the manager agent
     * @param workerAgents         the worker agents available for delegation
     * @param managerMaxIterations maximum number of tool call iterations for the manager
     * @param maxDelegationDepth   maximum peer-delegation depth for worker agents
     */
    public HierarchicalWorkflowExecutor(
            ChatModel managerLlm, List<Agent> workerAgents, int managerMaxIterations, int maxDelegationDepth) {
        this.managerLlm = managerLlm;
        this.workerAgents = List.copyOf(workerAgents);
        this.managerMaxIterations = managerMaxIterations;
        this.maxDelegationDepth = maxDelegationDepth;
        this.agentExecutor = new AgentExecutor();
    }

    @Override
    public EnsembleOutput execute(List<Task> resolvedTasks, ExecutionContext executionContext) {
        Instant startTime = Instant.now();
        MDC.put(MDC_AGENT_ROLE, MANAGER_ROLE);

        try {
            log.info(
                    "Hierarchical workflow starting | Tasks: {} | Worker agents: {}",
                    resolvedTasks.size(),
                    workerAgents.size());

            // 1. Create delegation context for peer delegation among worker agents
            DelegationContext workerDelegationContext = DelegationContext.create(
                    workerAgents,
                    maxDelegationDepth,
                    executionContext.getMemoryContext(),
                    agentExecutor,
                    executionContext.isVerbose());

            // 2. Create the stateful DelegateTaskTool (accumulates worker outputs, shares memory)
            DelegateTaskTool delegateTool = new DelegateTaskTool(
                    workerAgents,
                    agentExecutor,
                    executionContext.isVerbose(),
                    executionContext.getMemoryContext(),
                    workerDelegationContext);

            // 3. Build the virtual Manager agent
            Agent manager = Agent.builder()
                    .role(MANAGER_ROLE)
                    .goal(MANAGER_GOAL)
                    .background(ManagerPromptBuilder.buildBackground(workerAgents))
                    .llm(managerLlm)
                    .maxIterations(managerMaxIterations)
                    .tools(List.of(delegateTool))
                    .build();

            // 4. Build the meta-task that drives the manager
            Task managerTask = Task.builder()
                    .description(ManagerPromptBuilder.buildTaskDescription(resolvedTasks))
                    .expectedOutput(MANAGER_EXPECTED_OUTPUT)
                    .agent(manager)
                    .build();

            // 5. Fire TaskStartEvent for the hierarchical run (represented as manager task)
            executionContext.fireTaskStart(new TaskStartEvent(managerTask.getDescription(), MANAGER_ROLE, 1, 1));

            // 6. Execute the manager (ReAct loop: it calls delegateTask for each worker task)
            // The manager itself does not participate in shared memory (it is a meta-orchestrator)
            log.info("Manager agent starting | Max iterations: {}", managerMaxIterations);
            TaskOutput managerOutput;
            try {
                managerOutput = agentExecutor.execute(
                        managerTask,
                        List.of(),
                        ExecutionContext.of(executionContext.isVerbose(), MemoryContext.disabled()),
                        null);
            } catch (AgentExecutionException | MaxIterationsExceededException e) {
                // Wrap with partial outputs so callers can recover completed worker results
                List<TaskOutput> partial = delegateTool.getDelegatedOutputs();
                log.error("Manager agent failed after {} delegations: {}", partial.size(), e.getMessage(), e);
                throw new TaskExecutionException(
                        "Hierarchical workflow manager failed: " + e.getMessage(),
                        managerTask.getDescription(),
                        MANAGER_ROLE,
                        partial,
                        e);
            }
            log.info(
                    "Manager agent completed | Delegations: {} | Duration: {}",
                    delegateTool.getDelegatedOutputs().size(),
                    managerOutput.getDuration());

            // 7. Assemble output: worker outputs first, manager final output last
            List<TaskOutput> allOutputs = new ArrayList<>(delegateTool.getDelegatedOutputs());
            allOutputs.add(managerOutput);

            Duration totalDuration = Duration.between(startTime, Instant.now());
            int totalToolCalls =
                    allOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

            // 8. Fire TaskCompleteEvent for the hierarchical run
            executionContext.fireTaskComplete(new TaskCompleteEvent(
                    managerTask.getDescription(), MANAGER_ROLE, managerOutput, totalDuration, 1, 1));

            return EnsembleOutput.builder()
                    .raw(managerOutput.getRaw())
                    .taskOutputs(List.copyOf(allOutputs))
                    .totalDuration(totalDuration)
                    .totalToolCalls(totalToolCalls)
                    .build();

        } finally {
            MDC.remove(MDC_AGENT_ROLE);
        }
    }
}
