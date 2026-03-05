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
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.ensemble.EnsembleOutput;
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
 *   <li>A system prompt produced by the configured {@link ManagerPromptStrategy}
 *       (default: {@link DefaultManagerPromptStrategy#DEFAULT})</li>
 *   <li>A user prompt produced by the same strategy</li>
 *   <li>The delegateTask tool that dispatches tasks to workers</li>
 * </ul>
 *
 * The Manager uses the delegateTask tool to assign tasks, receives each worker's
 * output as the tool result, and synthesizes a final response. All worker outputs
 * and the manager's final output are included in the EnsembleOutput.
 *
 * When the {@link ExecutionContext} carries an active memory context, memory is shared
 * across all delegations: worker agents read from and write to the same context. The
 * Manager itself runs with disabled memory (it is a meta-orchestrator) but still
 * participates in event callbacks.
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
    private final ManagerPromptStrategy promptStrategy;

    /**
     * Creates an executor using the {@link DefaultManagerPromptStrategy}.
     *
     * @param managerLlm           LLM for the manager agent
     * @param workerAgents         the worker agents available for delegation
     * @param managerMaxIterations maximum number of tool call iterations for the manager
     * @param maxDelegationDepth   maximum peer-delegation depth for worker agents
     */
    public HierarchicalWorkflowExecutor(
            ChatModel managerLlm, List<Agent> workerAgents, int managerMaxIterations, int maxDelegationDepth) {
        this(managerLlm, workerAgents, managerMaxIterations, maxDelegationDepth, DefaultManagerPromptStrategy.DEFAULT);
    }

    /**
     * Creates an executor with a custom {@link ManagerPromptStrategy}.
     *
     * @param managerLlm           LLM for the manager agent
     * @param workerAgents         the worker agents available for delegation
     * @param managerMaxIterations maximum number of tool call iterations for the manager
     * @param maxDelegationDepth   maximum peer-delegation depth for worker agents
     * @param promptStrategy       strategy for building the manager's system and user prompts;
     *                             must not be null
     */
    public HierarchicalWorkflowExecutor(
            ChatModel managerLlm,
            List<Agent> workerAgents,
            int managerMaxIterations,
            int maxDelegationDepth,
            ManagerPromptStrategy promptStrategy) {
        this.managerLlm = managerLlm;
        this.workerAgents = List.copyOf(workerAgents);
        this.managerMaxIterations = managerMaxIterations;
        this.maxDelegationDepth = maxDelegationDepth;
        this.agentExecutor = new AgentExecutor();
        this.promptStrategy = promptStrategy != null ? promptStrategy : DefaultManagerPromptStrategy.DEFAULT;
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

            // 1. Create delegation context for peer delegation among worker agents.
            //    Workers share the full executionContext (memory + listeners).
            DelegationContext workerDelegationContext =
                    DelegationContext.create(workerAgents, maxDelegationDepth, executionContext, agentExecutor);

            // 2. Create the stateful DelegateTaskTool (accumulates worker outputs, shares memory)
            DelegateTaskTool delegateTool =
                    new DelegateTaskTool(workerAgents, agentExecutor, executionContext, workerDelegationContext);

            // 3. Build the ManagerPromptContext and invoke the configured strategy to produce prompts.
            //    previousOutputs and workflowDescription are reserved for future use; they are
            //    intentionally empty/null in this release. Custom strategies should treat them as
            //    optional supplemental context that may be populated in a later version.
            ManagerPromptContext promptContext = new ManagerPromptContext(workerAgents, resolvedTasks, List.of(), null);
            String systemPrompt = promptStrategy.buildSystemPrompt(promptContext);
            String userPrompt = promptStrategy.buildUserPrompt(promptContext);

            // Validation of strategy output is the caller's responsibility (per ManagerPromptStrategy
            // contract). The Task model requires a non-blank description, so fall back to a sensible
            // default when the strategy returns null or blank, rather than propagating a ValidationException
            // that would be confusing for callers who intentionally use minimal prompts.
            String effectiveUserPrompt = (userPrompt != null && !userPrompt.isBlank())
                    ? userPrompt
                    : "Coordinate your team to complete all assigned tasks and synthesize a comprehensive final response.";

            // 4. Build the virtual Manager agent using the strategy-provided system prompt.
            //    The background field is optional; null/blank from the strategy is passed through as-is.
            Agent manager = Agent.builder()
                    .role(MANAGER_ROLE)
                    .goal(MANAGER_GOAL)
                    .background(systemPrompt != null ? systemPrompt : "")
                    .llm(managerLlm)
                    .maxIterations(managerMaxIterations)
                    .tools(List.of(delegateTool))
                    .build();

            // 5. Build the meta-task that drives the manager using the effective user prompt
            Task managerTask = Task.builder()
                    .description(effectiveUserPrompt)
                    .expectedOutput(MANAGER_EXPECTED_OUTPUT)
                    .agent(manager)
                    .build();

            // 6. The manager uses disabled memory (it is a meta-orchestrator) but still
            //    participates in listeners (taskIndex=1, totalTasks=1 for the meta-task).
            ExecutionContext managerContext = ExecutionContext.of(
                    MemoryContext.disabled(), executionContext.isVerbose(), executionContext.listeners());

            log.info("Manager agent starting | Max iterations: {}", managerMaxIterations);

            // Fire TaskStartEvent for the manager meta-task
            executionContext.fireTaskStart(new TaskStartEvent(managerTask.getDescription(), MANAGER_ROLE, 1, 1));

            Instant managerStart = Instant.now();
            TaskOutput managerOutput;
            try {
                managerOutput = agentExecutor.execute(managerTask, List.of(), managerContext);
            } catch (Exception e) {
                Duration managerDuration = Duration.between(managerStart, Instant.now());
                // Wrap with partial outputs so callers can recover completed worker results
                List<TaskOutput> partial = delegateTool.getDelegatedOutputs();
                log.error("Manager agent failed after {} delegations: {}", partial.size(), e.getMessage(), e);

                // Fire TaskFailedEvent before propagating
                executionContext.fireTaskFailed(
                        new TaskFailedEvent(managerTask.getDescription(), MANAGER_ROLE, e, managerDuration, 1, 1));

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

            // Fire TaskCompleteEvent for the manager meta-task
            executionContext.fireTaskComplete(new TaskCompleteEvent(
                    managerTask.getDescription(), MANAGER_ROLE, managerOutput, managerOutput.getDuration(), 1, 1));

            // 7. Assemble output: worker outputs first, manager final output last
            List<TaskOutput> allOutputs = new ArrayList<>(delegateTool.getDelegatedOutputs());
            allOutputs.add(managerOutput);

            Duration totalDuration = Duration.between(startTime, Instant.now());
            int totalToolCalls =
                    allOutputs.stream().mapToInt(TaskOutput::getToolCallCount).sum();

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
