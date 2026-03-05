package net.agentensemble.workflow;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.DelegationRequest;
import net.agentensemble.delegation.DelegationResponse;
import net.agentensemble.delegation.DelegationStatus;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tool that allows the Manager agent to delegate tasks to worker agents.
 *
 * When invoked, the tool locates the target agent by role, creates an ephemeral
 * Task, executes it via AgentExecutor, and returns the worker's output. All
 * delegated task outputs are accumulated and accessible after execution for
 * inclusion in the EnsembleOutput.
 *
 * When the {@link ExecutionContext} contains an active memory context, worker agent
 * execution participates in shared memory: prior run outputs are injected into the
 * worker's prompt and the worker's output is recorded into memory after completion.
 *
 * For each invocation the framework internally constructs a {@link DelegationRequest} and
 * produces a {@link DelegationResponse}. Successful delegations accumulate as
 * {@link TaskOutput} objects (accessible via {@link #getDelegatedOutputs()}) and as typed
 * {@link DelegationResponse} objects (accessible via {@link #getDelegationResponses()}).
 * Guard failures produce a {@link DelegationResponse} with {@link DelegationStatus#FAILURE}
 * status so all delegation attempts are auditable.
 *
 * This class is stateful -- a new instance must be created for each ensemble run.
 */
public class DelegateTaskTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateTaskTool.class);

    /** Default expected output text for delegated tasks. */
    private static final String DEFAULT_EXPECTED_OUTPUT = "Complete the assigned task thoroughly";

    private final List<Agent> agents;
    private final AgentExecutor agentExecutor;
    private final ExecutionContext executionContext;
    private final DelegationContext delegationContext;
    private final List<TaskOutput> delegatedOutputs = new ArrayList<>();
    private final List<DelegationResponse> delegationResponses = new ArrayList<>();

    /**
     * @param agents            the worker agents available for delegation
     * @param agentExecutor     the executor to use when running delegated tasks
     * @param executionContext   execution context bundling memory, verbose flag, and listeners
     * @param delegationContext peer-delegation context so workers can further delegate if allowed
     */
    public DelegateTaskTool(
            List<Agent> agents,
            AgentExecutor agentExecutor,
            ExecutionContext executionContext,
            DelegationContext delegationContext) {
        this.agents = List.copyOf(agents);
        this.agentExecutor = agentExecutor;
        this.executionContext = executionContext != null ? executionContext : ExecutionContext.disabled();
        this.delegationContext = delegationContext;
    }

    /**
     * Delegate a task to a specific worker agent.
     *
     * The tool locates the agent by role name (case-insensitive), creates a task
     * with the provided description, executes it, and returns the output as a plain
     * String (preserving the LLM-facing tool contract). Internally a
     * {@link DelegationRequest} is constructed and a {@link DelegationResponse} is
     * produced and accumulated.
     *
     * If no agent is found for the given role, an error message listing available roles
     * is returned and a {@link DelegationStatus#FAILURE} response is recorded.
     *
     * @param agentRole       the exact role of the worker agent to delegate to
     * @param taskDescription a clear description of the task for the agent to complete
     * @return the worker agent's output, or an error message if the role is not found
     */
    @Tool("Delegate a task to a worker agent. Provide the agent's role and a clear task description. "
            + "Use this tool for each task that needs to be completed by a team member.")
    public String delegateTask(
            @P("The exact role of the worker agent to delegate to. " + "Must match one of the available agent roles.")
                    String agentRole,
            @P("A clear description of the task for the agent to complete.") String taskDescription) {

        if (agentRole == null || agentRole.isBlank()) {
            log.warn("DelegateTaskTool invoked with null or blank agentRole");
            return "Error: agentRole must not be null or blank.";
        }
        if (taskDescription == null || taskDescription.isBlank()) {
            log.warn("DelegateTaskTool invoked with null or blank taskDescription");
            return "Error: taskDescription must not be null or blank.";
        }

        // Build the typed request; taskId is auto-generated
        DelegationRequest request = DelegationRequest.builder()
                .agentRole(agentRole)
                .taskDescription(taskDescription)
                .build();

        Instant requestStart = Instant.now();

        Agent agent = findAgentByRole(agentRole);
        if (agent == null) {
            List<String> availableRoles = agents.stream().map(Agent::getRole).toList();
            String error = "No agent found with role '" + agentRole + "'. Available roles: " + availableRoles;
            log.warn("Delegation failed: {}", error);
            delegationResponses.add(failureResponse(request, agentRole, error, requestStart));
            return error;
        }

        log.info(
                "Delegating task to agent '{}': {}",
                agent.getRole(),
                taskDescription.length() > 80 ? taskDescription.substring(0, 80) + "..." : taskDescription);

        Task delegatedTask = Task.builder()
                .description(taskDescription)
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .build();

        try {
            TaskOutput output = agentExecutor.execute(delegatedTask, List.of(), executionContext, delegationContext);
            delegatedOutputs.add(output);

            Duration elapsed = Duration.between(requestStart, Instant.now());
            DelegationResponse response = new DelegationResponse(
                    request.getTaskId(),
                    DelegationStatus.SUCCESS,
                    agent.getRole(),
                    output.getRaw(),
                    output.getParsedOutput(),
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    elapsed);
            delegationResponses.add(response);

            log.info(
                    "Delegation to '{}' completed | Tool calls: {} | Duration: {}",
                    agent.getRole(),
                    output.getToolCallCount(),
                    output.getDuration());

            // Option C hybrid design: the @Tool method returns the worker's plain-text output to
            // the LLM to preserve backward compatibility. DelegationRequest and DelegationResponse
            // are framework-internal observability contracts, not serialized to the LLM.
            return output.getRaw();

        } catch (Exception e) {
            Duration elapsed = Duration.between(requestStart, Instant.now());
            DelegationResponse response = new DelegationResponse(
                    request.getTaskId(),
                    DelegationStatus.FAILURE,
                    agent.getRole(),
                    null,
                    null,
                    Collections.emptyMap(),
                    List.of(
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName()),
                    Collections.emptyMap(),
                    elapsed);
            delegationResponses.add(response);
            throw e;
        }
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

    /**
     * Returns an immutable snapshot of all {@link DelegationResponse} objects produced
     * by this tool instance, in invocation order. Includes both successful delegations and
     * guard-blocked attempts ({@link DelegationStatus#FAILURE}).
     *
     * <p><strong>Note on accessibility in hierarchical workflow:</strong> In hierarchical mode,
     * this tool instance is an internal detail of {@code HierarchicalWorkflowExecutor} and is
     * not directly accessible after {@code Ensemble.run()} returns. To observe delegation
     * outcomes in hierarchical workflow, implement an {@code EnsembleListener} or use a custom
     * {@link net.agentensemble.workflow.ManagerPromptStrategy} that wraps the tool.
     * For peer delegation ({@code AgentDelegationTool}), the same method is available on
     * the tool instance injected at execution time.
     *
     * @return immutable list of delegation responses
     */
    public List<DelegationResponse> getDelegationResponses() {
        return List.copyOf(delegationResponses);
    }

    // ========================
    // Internal helpers
    // ========================

    private DelegationResponse failureResponse(
            DelegationRequest request, String targetRole, String errorMessage, Instant startedAt) {
        return new DelegationResponse(
                request.getTaskId(),
                DelegationStatus.FAILURE,
                targetRole,
                null,
                null,
                Collections.emptyMap(),
                List.of(errorMessage),
                Collections.emptyMap(),
                Duration.between(startedAt, Instant.now()));
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
