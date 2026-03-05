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
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.delegation.DelegationContext;
import net.agentensemble.delegation.DelegationRequest;
import net.agentensemble.delegation.DelegationResponse;
import net.agentensemble.delegation.DelegationStatus;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.delegation.policy.DelegationPolicyContext;
import net.agentensemble.delegation.policy.DelegationPolicyResult;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.task.TaskOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A tool that allows the Manager agent to delegate tasks to worker agents.
 *
 * When invoked, the tool locates the target agent by role, evaluates any registered
 * {@link net.agentensemble.delegation.policy.DelegationPolicy} instances, creates an
 * ephemeral Task, executes it via AgentExecutor, and returns the worker's output. All
 * delegated task outputs are accumulated and accessible after execution for inclusion in
 * the EnsembleOutput.
 *
 * When the {@link ExecutionContext} contains an active memory context, worker agent
 * execution participates in shared memory: prior run outputs are injected into the
 * worker's prompt and the worker's output is recorded into memory after completion.
 *
 * For each invocation the framework internally constructs a {@link DelegationRequest} and
 * produces a {@link DelegationResponse}. Successful delegations accumulate as
 * {@link TaskOutput} objects (accessible via {@link #getDelegatedOutputs()}) and as typed
 * {@link DelegationResponse} objects (accessible via {@link #getDelegationResponses()}).
 * Guard and policy failures produce a {@link DelegationResponse} with
 * {@link DelegationStatus#FAILURE} status so all delegation attempts are auditable.
 *
 * Lifecycle events ({@link DelegationStartedEvent}, {@link DelegationCompletedEvent},
 * {@link DelegationFailedEvent}) are fired to all registered
 * {@link net.agentensemble.callback.EnsembleListener} instances through the
 * {@link ExecutionContext}. Policy rejections fire a {@link DelegationFailedEvent} directly
 * (no corresponding start event). Worker execution failures fire both start and failed events.
 *
 * This class is stateful -- a new instance must be created for each ensemble run.
 */
public class DelegateTaskTool {

    private static final Logger log = LoggerFactory.getLogger(DelegateTaskTool.class);

    /** Logical role for the Manager in delegation events. */
    private static final String MANAGER_ROLE = HierarchicalWorkflowExecutor.MANAGER_ROLE;

    /** MDC key for the active delegation's correlation ID. */
    private static final String MDC_DELEGATION_ID = "delegation.id";

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
     * The tool locates the agent by role name (case-insensitive), evaluates all registered
     * {@link DelegationPolicy} instances in order, then creates a task with the provided
     * description, executes it, and returns the output as a plain String (preserving the
     * LLM-facing tool contract). Internally a {@link DelegationRequest} is constructed and a
     * {@link DelegationResponse} is produced and accumulated.
     *
     * If no agent is found for the given role, or if a policy rejects the delegation, an error
     * message listing available roles or the rejection reason is returned and a
     * {@link DelegationStatus#FAILURE} response is recorded.
     *
     * @param agentRole       the exact role of the worker agent to delegate to
     * @param taskDescription a clear description of the task for the agent to complete
     * @return the worker agent's output, or an error message if the role is not found or
     *         a policy rejects the delegation
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
            DelegationResponse response = failureResponse(request, agentRole, error, requestStart);
            delegationResponses.add(response);
            executionContext.fireDelegationFailed(new DelegationFailedEvent(
                    request.getTaskId(),
                    MANAGER_ROLE,
                    agentRole,
                    error,
                    null,
                    response,
                    Duration.between(requestStart, Instant.now())));
            return error;
        }

        // Policy evaluation: evaluate all registered policies in order.
        // A REJECT short-circuits immediately. A MODIFY replaces the working request.
        DelegationRequest workingRequest = request;
        List<String> availableWorkerRoles = agents.stream().map(Agent::getRole).toList();
        DelegationPolicyContext policyCtx = new DelegationPolicyContext(
                MANAGER_ROLE,
                delegationContext.getCurrentDepth(),
                delegationContext.getMaxDepth(),
                availableWorkerRoles);

        for (DelegationPolicy policy : delegationContext.getPolicies()) {
            DelegationPolicyResult result = policy.evaluate(workingRequest, policyCtx);
            if (result == null) {
                throw new IllegalStateException("DelegationPolicy.evaluate() returned null for policy "
                        + policy.getClass().getName()
                        + ". Policies must return a non-null DelegationPolicyResult.");
            }
            if (result instanceof DelegationPolicyResult.Reject reject) {
                String msg = "Delegation rejected by policy: " + reject.reason();
                log.warn("Delegation from Manager to '{}' rejected by policy: {}", agentRole, reject.reason());
                DelegationResponse response = failureResponse(workingRequest, agentRole, msg, requestStart);
                delegationResponses.add(response);
                executionContext.fireDelegationFailed(new DelegationFailedEvent(
                        workingRequest.getTaskId(),
                        MANAGER_ROLE,
                        agentRole,
                        msg,
                        null,
                        response,
                        Duration.between(requestStart, Instant.now())));
                return msg;
            } else if (result instanceof DelegationPolicyResult.Modify modify) {
                DelegationRequest modifiedReq = modify.modifiedRequest();
                if (modifiedReq == null) {
                    throw new IllegalStateException(
                            "DelegationPolicyResult.Modify.modifiedRequest() is null for policy "
                                    + policy.getClass().getName()
                                    + ". Modify policies must supply a non-null replacement request.");
                }
                log.debug("Delegation from Manager to '{}' modified by policy", agentRole);
                workingRequest = modifiedReq;
            }
            // Allow: continue to next policy
        }

        // All policies passed -- fire DelegationStartedEvent
        log.info(
                "Delegating task to agent '{}': {}",
                agent.getRole(),
                workingRequest.getTaskDescription().length() > 80
                        ? workingRequest.getTaskDescription().substring(0, 80) + "..."
                        : workingRequest.getTaskDescription());

        executionContext.fireDelegationStarted(new DelegationStartedEvent(
                workingRequest.getTaskId(),
                MANAGER_ROLE,
                agent.getRole(),
                workingRequest.getTaskDescription(),
                delegationContext.getCurrentDepth() + 1,
                workingRequest));

        final DelegationRequest finalRequest = workingRequest;

        Task delegatedTask = Task.builder()
                .description(finalRequest.getTaskDescription())
                .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                .agent(agent)
                .build();

        // Descend: worker receives depth+1 so its peer-delegation depth tracking is correct.
        // Without descend(), a worker at depth=0 could peer-delegate even when maxDelegationDepth=1.
        DelegationContext workerCtx = delegationContext.descend();

        // Set MDC delegation.id during the worker execution window so worker logs can be
        // correlated to the DelegationStarted/Completed/Failed events by delegationId.
        String priorDelegationId = MDC.get(MDC_DELEGATION_ID);
        MDC.put(MDC_DELEGATION_ID, finalRequest.getTaskId());
        try {
            TaskOutput output = agentExecutor.execute(delegatedTask, List.of(), executionContext, workerCtx);
            delegatedOutputs.add(output);

            Duration elapsed = Duration.between(requestStart, Instant.now());
            DelegationResponse response = new DelegationResponse(
                    finalRequest.getTaskId(),
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

            executionContext.fireDelegationCompleted(new DelegationCompletedEvent(
                    finalRequest.getTaskId(), MANAGER_ROLE, agent.getRole(), response, elapsed));

            // Option C hybrid design: the @Tool method returns the worker's plain-text output to
            // the LLM to preserve backward compatibility. DelegationRequest and DelegationResponse
            // are framework-internal observability contracts, not serialized to the LLM.
            return output.getRaw();

        } catch (Exception e) {
            Duration elapsed = Duration.between(requestStart, Instant.now());
            DelegationResponse response = new DelegationResponse(
                    finalRequest.getTaskId(),
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
            executionContext.fireDelegationFailed(new DelegationFailedEvent(
                    finalRequest.getTaskId(),
                    MANAGER_ROLE,
                    agent.getRole(),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    e,
                    response,
                    elapsed));
            throw e;
        } finally {
            // Restore prior MDC delegation.id (null when there was no outer delegation)
            if (priorDelegationId != null) {
                MDC.put(MDC_DELEGATION_ID, priorDelegationId);
            } else {
                MDC.remove(MDC_DELEGATION_ID);
            }
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
     * outcomes in hierarchical workflow, implement an {@code EnsembleListener} and handle the
     * {@code onDelegationCompleted} / {@code onDelegationFailed} callbacks. For peer delegation
     * ({@code AgentDelegationTool}), the same method is available on the tool instance injected
     * at execution time.
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
