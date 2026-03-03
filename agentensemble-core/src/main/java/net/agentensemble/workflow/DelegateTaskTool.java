package net.agentensemble.workflow;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.memory.MemoryContext;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A tool that allows the Manager agent to delegate tasks to worker agents.
 *
 * When invoked, the tool locates the target agent by role, creates an ephemeral
 * Task, executes it via AgentExecutor, and returns the worker's output. All
 * delegated task outputs are accumulated and accessible after execution for
 * inclusion in the EnsembleOutput.
 *
 * When a {@link MemoryContext} is provided, worker agent execution participates
 * in the shared memory: prior run outputs are injected into the worker's prompt
 * and the worker's output is recorded into memory after completion.
 *
 * This class is stateful -- a new instance must be created for each ensemble run.
 */
public class DelegateTaskTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateTaskTool.class);

    /** Default expected output text for delegated tasks. */
    private static final String DEFAULT_EXPECTED_OUTPUT = "Complete the assigned task thoroughly";

    private final List<Agent> agents;
    private final AgentExecutor agentExecutor;
    private final boolean verbose;
    private final MemoryContext memoryContext;
    private final DelegationContext delegationContext;
    private final List<TaskOutput> delegatedOutputs = new ArrayList<>();

    /**
     * @param agents             the worker agents available for delegation
     * @param agentExecutor      the executor to use when running delegated tasks
     * @param verbose            whether to enable verbose logging for delegated tasks
     * @param memoryContext      runtime memory state for this run; null is normalized to
     *                           {@link MemoryContext#disabled()}
     * @param delegationContext  peer-delegation context so workers can further delegate if allowed
     */
    public DelegateTaskTool(List<Agent> agents, AgentExecutor agentExecutor, boolean verbose,
            MemoryContext memoryContext, DelegationContext delegationContext) {
        this.agents = List.copyOf(agents);
        this.agentExecutor = agentExecutor;
        this.verbose = verbose;
        this.memoryContext = memoryContext != null ? memoryContext : MemoryContext.disabled();
        this.delegationContext = delegationContext;
    }

    /**
     * Delegate a task to a specific worker agent.
     *
     * The tool locates the agent by role name (case-insensitive), creates a task
     * with the provided description, executes it, and returns the output. If no
     * agent is found for the given role, an error message listing available roles
     * is returned instead.
     *
     * @param agentRole       the exact role of the worker agent to delegate to
     * @param taskDescription a clear description of the task for the agent to complete
     * @return the worker agent's output, or an error message if the role is not found
     */
    @Tool("Delegate a task to a worker agent. Provide the agent's role and a clear task description. "
            + "Use this tool for each task that needs to be completed by a team member.")
    public String delegateTask(
            @P("The exact role of the worker agent to delegate to. "
                    + "Must match one of the available agent roles.") String agentRole,
            @P("A clear description of the task for the agent to complete.") String taskDescription) {

        if (agentRole == null || agentRole.isBlank()) {
            log.warn("DelegateTaskTool invoked with null or blank agentRole");
            return "Error: agentRole must not be null or blank.";
        }
        if (taskDescription == null || taskDescription.isBlank()) {
            log.warn("DelegateTaskTool invoked with null or blank taskDescription");
            return "Error: taskDescription must not be null or blank.";
        }

        Agent agent = findAgentByRole(agentRole);
        if (agent == null) {
            List<String> availableRoles = agents.stream().map(Agent::getRole).toList();
            String error = "No agent found with role '" + agentRole
                    + "'. Available roles: " + availableRoles;
            log.warn("Delegation failed: {}", error);
            return error;
        }

        log.info("Delegating task to agent '{}': {}", agent.getRole(),
                taskDescription.length() > 80 ? taskDescription.substring(0, 80) + "..." : taskDescription);

        Task delegatedTask = Task.builder()
                .description(taskDescription)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .build();

        TaskOutput output = agentExecutor.execute(delegatedTask, List.of(), verbose, memoryContext,
                delegationContext);
        delegatedOutputs.add(output);

        log.info("Delegation to '{}' completed | Tool calls: {} | Duration: {}",
                agent.getRole(), output.getToolCallCount(), output.getDuration());

        return output.getRaw();
    }

    /**
     * Returns an immutable snapshot of all task outputs produced by delegated tasks,
     * in delegation order.
     *
     * @return immutable list of delegated TaskOutput instances
     */
    public List<TaskOutput> getDelegatedOutputs() {
        return List.copyOf(delegatedOutputs);
    }

    private Agent findAgentByRole(String role) {
        if (role == null) {
            return null;
        }
        String normalizedRole = role.trim();
        return agents.stream()
                .filter(a -> a.getRole().equalsIgnoreCase(normalizedRole))
                .findFirst()
                .orElse(null);
    }
}
