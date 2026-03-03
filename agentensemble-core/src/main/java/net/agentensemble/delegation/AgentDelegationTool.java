package net.agentensemble.delegation;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A tool that allows an agent to delegate a subtask to another agent within the same ensemble.
 *
 * When an agent has {@code allowDelegation = true}, the framework auto-injects an instance of
 * this tool into its tool list at execution time. The agent can then call {@code delegate}
 * during its ReAct loop to hand off work to a peer agent. The peer executes the subtask and
 * returns its output as the tool result, which the calling agent can incorporate into its
 * final answer.
 *
 * Guards enforced before executing a delegation:
 * <ul>
 *   <li>Self-delegation: an agent cannot delegate to itself</li>
 *   <li>Depth limit: delegation depth is capped at {@link DelegationContext#getMaxDepth()}</li>
 *   <li>Unknown agent: the target role must match an agent registered with the ensemble</li>
 * </ul>
 *
 * All delegated outputs are accumulated and accessible via {@link #getDelegatedOutputs()}
 * for metrics and audit purposes.
 *
 * This class is stateful (accumulates outputs). A new instance must be created per
 * agent execution.
 */
public class AgentDelegationTool {

    private static final Logger log = LoggerFactory.getLogger(AgentDelegationTool.class);

    /** MDC key for the delegation depth. */
    static final String MDC_DELEGATION_DEPTH = "delegation.depth";

    /** MDC key for the parent agent's role. */
    static final String MDC_DELEGATION_PARENT = "delegation.parent";

    /** Default expected output for delegated tasks. */
    private static final String DEFAULT_EXPECTED_OUTPUT = "Complete the assigned subtask thoroughly";

    private final String callerRole;
    private final DelegationContext delegationContext;
    private final List<TaskOutput> delegatedOutputs = new ArrayList<>();

    /**
     * @param callerRole        the role of the agent that owns this tool instance
     * @param delegationContext the delegation state for the current run
     */
    public AgentDelegationTool(String callerRole, DelegationContext delegationContext) {
        this.callerRole = callerRole;
        this.delegationContext = delegationContext;
    }

    /**
     * Delegate a subtask to another agent.
     *
     * The target agent is located by role name (case-insensitive). If found, the subtask
     * is executed and the result returned. If any guard fails (depth limit, self-delegation,
     * unknown role), a descriptive error message is returned to the calling agent instead.
     *
     * @param agentRole       the role of the target agent
     * @param taskDescription a description of the subtask for the target agent to complete
     * @return the target agent's output, or an error message if the delegation cannot proceed
     */
    @Tool("Delegate a subtask to another agent in the ensemble. "
            + "Use this when you need specialised help from a team member. "
            + "Provide the target agent's role and a clear description of the subtask.")
    public String delegate(
            @P("The role of the agent to delegate to. Must match one of the available agent roles.") String agentRole,
            @P("A clear description of the subtask for the target agent to complete.") String taskDescription) {

        // Guard: null/blank parameters (the LLM may omit arguments)
        if (agentRole == null || agentRole.isBlank()) {
            log.warn("AgentDelegationTool invoked with null or blank agentRole by '{}'", callerRole);
            return "Error: agentRole must not be null or blank. Provide the target agent's role.";
        }
        if (taskDescription == null || taskDescription.isBlank()) {
            log.warn("AgentDelegationTool invoked with null or blank taskDescription by '{}'", callerRole);
            return "Error: taskDescription must not be null or blank. Provide a clear subtask description.";
        }

        // Guard: depth limit
        if (delegationContext.isAtLimit()) {
            String msg = "Delegation depth limit reached (max: " + delegationContext.getMaxDepth()
                    + ", current: " + delegationContext.getCurrentDepth()
                    + "). Complete this task yourself without further delegation.";
            log.warn("Delegation blocked for agent '{}': {}", callerRole, msg);
            return msg;
        }

        // Guard: self-delegation
        Agent target = findAgentByRole(agentRole);
        if (target != null && target.getRole().equalsIgnoreCase(callerRole)) {
            String msg = "Cannot delegate to yourself (role: '" + callerRole + "'). Choose a different agent.";
            log.warn("{}", msg);
            return msg;
        }

        // Guard: unknown agent
        if (target == null) {
            List<String> availableRoles = delegationContext.getPeerAgents().stream()
                    .map(Agent::getRole)
                    .toList();
            String msg = "Agent not found with role '" + agentRole + "'. Available roles: " + availableRoles;
            log.warn("Delegation from '{}' failed: {}", callerRole, msg);
            return msg;
        }

        log.info(
                "Agent '{}' delegating subtask to '{}' (depth {}/{}): {}",
                callerRole,
                target.getRole(),
                delegationContext.getCurrentDepth() + 1,
                delegationContext.getMaxDepth(),
                taskDescription.length() > 80 ? taskDescription.substring(0, 80) + "..." : taskDescription);

        // Save prior MDC values so nested delegations (A->B->C) restore the outer
        // context correctly when the inner finally block runs
        String priorDepth = MDC.get(MDC_DELEGATION_DEPTH);
        String priorParent = MDC.get(MDC_DELEGATION_PARENT);
        MDC.put(MDC_DELEGATION_DEPTH, String.valueOf(delegationContext.getCurrentDepth() + 1));
        MDC.put(MDC_DELEGATION_PARENT, callerRole);

        try {
            Task delegatedTask = Task.builder()
                    .description(taskDescription)
                    .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                    .agent(target)
                    .build();

            // Descend: child runs at depth + 1
            DelegationContext childCtx = delegationContext.descend();

            TaskOutput output = delegationContext
                    .getAgentExecutor()
                    .execute(
                            delegatedTask,
                            List.of(),
                            delegationContext.isVerbose(),
                            delegationContext.getMemoryContext(),
                            childCtx);

            delegatedOutputs.add(output);

            log.info(
                    "Delegation to '{}' completed | Tool calls: {} | Duration: {}",
                    target.getRole(),
                    output.getToolCallCount(),
                    output.getDuration());

            return output.getRaw();

        } finally {
            // Restore prior MDC values rather than unconditionally removing them.
            // This preserves outer delegation context when delegation is nested (A->B->C).
            if (priorDepth != null) {
                MDC.put(MDC_DELEGATION_DEPTH, priorDepth);
            } else {
                MDC.remove(MDC_DELEGATION_DEPTH);
            }
            if (priorParent != null) {
                MDC.put(MDC_DELEGATION_PARENT, priorParent);
            } else {
                MDC.remove(MDC_DELEGATION_PARENT);
            }
        }
    }

    /**
     * Returns an immutable snapshot of all task outputs produced through delegation,
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
        String normalized = role.trim();
        return delegationContext.getPeerAgents().stream()
                .filter(a -> a.getRole().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }
}
