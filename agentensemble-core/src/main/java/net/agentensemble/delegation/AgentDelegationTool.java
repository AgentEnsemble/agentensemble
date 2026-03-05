package net.agentensemble.delegation;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.delegation.policy.DelegationPolicy;
import net.agentensemble.delegation.policy.DelegationPolicyContext;
import net.agentensemble.delegation.policy.DelegationPolicyResult;
import net.agentensemble.task.TaskOutput;
import net.agentensemble.trace.DelegationTrace;
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
 * After all built-in guards pass, registered
 * {@link net.agentensemble.delegation.policy.DelegationPolicy} instances are evaluated in
 * order. A REJECT result blocks worker execution; a MODIFY result replaces the working request
 * for subsequent policies and for the worker invocation.
 *
 * Lifecycle events ({@link DelegationStartedEvent}, {@link DelegationCompletedEvent},
 * {@link DelegationFailedEvent}) are fired to all registered
 * {@link net.agentensemble.callback.EnsembleListener} instances through the
 * {@link net.agentensemble.execution.ExecutionContext}. Guard and policy rejections fire a
 * {@link DelegationFailedEvent} directly (no corresponding start event). Worker execution
 * failures fire both start and failed events.
 *
 * For each invocation the framework internally constructs a {@link DelegationRequest} and
 * produces a {@link DelegationResponse}. Successful delegations accumulate as
 * {@link TaskOutput} objects (accessible via {@link #getDelegatedOutputs()}) and as typed
 * {@link DelegationResponse} objects (accessible via {@link #getDelegationResponses()}).
 * Guard failures produce a {@link DelegationResponse} with {@link DelegationStatus#FAILURE}
 * status so all delegation attempts are auditable.
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

    /** MDC key for the active delegation's correlation ID. */
    static final String MDC_DELEGATION_ID = "delegation.id";

    /** Default expected output for delegated tasks. */
    private static final String DEFAULT_EXPECTED_OUTPUT = "Complete the assigned subtask thoroughly";

    private final String callerRole;
    private final DelegationContext delegationContext;
    private final Consumer<DelegationTrace> delegationTraceConsumer;
    private final List<TaskOutput> delegatedOutputs = new ArrayList<>();
    private final List<DelegationResponse> delegationResponses = new ArrayList<>();

    /**
     * @param callerRole        the role of the agent that owns this tool instance
     * @param delegationContext the delegation state for the current run
     */
    public AgentDelegationTool(String callerRole, DelegationContext delegationContext) {
        this(callerRole, delegationContext, null);
    }

    /**
     * @param callerRole               the role of the agent that owns this tool instance
     * @param delegationContext        the delegation state for the current run
     * @param delegationTraceConsumer  optional consumer called after each successful delegation;
     *                                 receives a {@link DelegationTrace} with the worker's full
     *                                 execution trace; {@code null} to disable trace capture
     */
    public AgentDelegationTool(
            String callerRole, DelegationContext delegationContext, Consumer<DelegationTrace> delegationTraceConsumer) {
        this.callerRole = callerRole;
        this.delegationContext = delegationContext;
        this.delegationTraceConsumer = delegationTraceConsumer;
    }

    /**
     * Delegate a subtask to another agent.
     *
     * The target agent is located by role name (case-insensitive). If found, all registered
     * {@link DelegationPolicy} instances are evaluated. If all policies allow, the subtask
     * is executed and the result returned as a plain String (preserving the LLM-facing
     * tool contract). Internally a {@link DelegationRequest} is constructed and a
     * {@link DelegationResponse} is produced and accumulated.
     *
     * If any guard fails (depth limit, self-delegation, unknown role) or any policy rejects
     * the delegation, a descriptive error message is returned to the calling agent and a
     * {@link DelegationStatus#FAILURE} response is recorded.
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

        // Build the typed request; taskId is auto-generated
        DelegationRequest request = DelegationRequest.builder()
                .agentRole(agentRole)
                .taskDescription(taskDescription)
                .build();

        Instant requestStart = Instant.now();

        // Guard: depth limit
        if (delegationContext.isAtLimit()) {
            String msg = "Delegation depth limit reached (max: " + delegationContext.getMaxDepth()
                    + ", current: " + delegationContext.getCurrentDepth()
                    + "). Complete this task yourself without further delegation.";
            log.warn("Delegation blocked for agent '{}': {}", callerRole, msg);
            DelegationResponse response = failureResponse(request, agentRole, msg, requestStart);
            delegationResponses.add(response);
            delegationContext
                    .getExecutionContext()
                    .fireDelegationFailed(new DelegationFailedEvent(
                            request.getTaskId(),
                            callerRole,
                            agentRole,
                            msg,
                            null,
                            response,
                            Duration.between(requestStart, Instant.now())));
            return msg;
        }

        // Guard: self-delegation
        Agent target = findAgentByRole(agentRole);
        if (target != null && target.getRole().equalsIgnoreCase(callerRole)) {
            String msg = "Cannot delegate to yourself (role: '" + callerRole + "'). Choose a different agent.";
            log.warn("{}", msg);
            DelegationResponse response = failureResponse(request, agentRole, msg, requestStart);
            delegationResponses.add(response);
            delegationContext
                    .getExecutionContext()
                    .fireDelegationFailed(new DelegationFailedEvent(
                            request.getTaskId(),
                            callerRole,
                            agentRole,
                            msg,
                            null,
                            response,
                            Duration.between(requestStart, Instant.now())));
            return msg;
        }

        // Guard: unknown agent
        if (target == null) {
            List<String> availableRoles = delegationContext.getPeerAgents().stream()
                    .map(Agent::getRole)
                    .toList();
            String msg = "Agent not found with role '" + agentRole + "'. Available roles: " + availableRoles;
            log.warn("Delegation from '{}' failed: {}", callerRole, msg);
            DelegationResponse response = failureResponse(request, agentRole, msg, requestStart);
            delegationResponses.add(response);
            delegationContext
                    .getExecutionContext()
                    .fireDelegationFailed(new DelegationFailedEvent(
                            request.getTaskId(),
                            callerRole,
                            agentRole,
                            msg,
                            null,
                            response,
                            Duration.between(requestStart, Instant.now())));
            return msg;
        }

        // Policy evaluation: evaluate all registered policies in order.
        // A REJECT short-circuits immediately. A MODIFY replaces the working request.
        DelegationRequest workingRequest = request;
        List<String> availableWorkerRoles =
                delegationContext.getPeerAgents().stream().map(Agent::getRole).toList();
        DelegationPolicyContext policyCtx = new DelegationPolicyContext(
                callerRole, delegationContext.getCurrentDepth(), delegationContext.getMaxDepth(), availableWorkerRoles);

        for (DelegationPolicy policy : delegationContext.getPolicies()) {
            DelegationPolicyResult result = policy.evaluate(workingRequest, policyCtx);
            if (result == null) {
                throw new IllegalStateException("DelegationPolicy.evaluate() returned null for policy "
                        + policy.getClass().getName()
                        + ". Policies must return a non-null DelegationPolicyResult.");
            }
            if (result instanceof DelegationPolicyResult.Reject reject) {
                String msg = "Delegation rejected by policy: " + reject.reason();
                log.warn("Delegation from '{}' to '{}' rejected by policy: {}", callerRole, agentRole, reject.reason());
                DelegationResponse response = failureResponse(workingRequest, agentRole, msg, requestStart);
                delegationResponses.add(response);
                delegationContext
                        .getExecutionContext()
                        .fireDelegationFailed(new DelegationFailedEvent(
                                workingRequest.getTaskId(),
                                callerRole,
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
                log.debug("Delegation from '{}' to '{}' modified by policy", callerRole, agentRole);
                workingRequest = modifiedReq;
            }
            // Allow: continue to next policy
        }

        // Re-resolve target agent from workingRequest.agentRole() in case a MODIFY policy
        // changed the agentRole. This ensures DelegationStartedEvent and worker execution
        // always reference the same agent, and unknown-agent/self-delegation guards are
        // applied to the final agentRole.
        Agent resolvedTarget = workingRequest.getAgentRole().equals(target.getRole())
                ? target
                : findAgentByRole(workingRequest.getAgentRole());
        if (resolvedTarget == null) {
            String msg = "A policy modified agentRole to '"
                    + workingRequest.getAgentRole()
                    + "' but no agent found with that role. Available roles: "
                    + delegationContext.getPeerAgents().stream()
                            .map(Agent::getRole)
                            .toList();
            log.warn("Delegation from '{}' failed: {}", callerRole, msg);
            DelegationResponse response =
                    failureResponse(workingRequest, workingRequest.getAgentRole(), msg, requestStart);
            delegationResponses.add(response);
            delegationContext
                    .getExecutionContext()
                    .fireDelegationFailed(new DelegationFailedEvent(
                            workingRequest.getTaskId(),
                            callerRole,
                            workingRequest.getAgentRole(),
                            msg,
                            null,
                            response,
                            Duration.between(requestStart, Instant.now())));
            return msg;
        }
        if (resolvedTarget.getRole().equalsIgnoreCase(callerRole)) {
            String msg = "A policy modified agentRole to '"
                    + callerRole
                    + "' which results in self-delegation. Choose a different target.";
            log.warn("{}", msg);
            DelegationResponse response = failureResponse(workingRequest, callerRole, msg, requestStart);
            delegationResponses.add(response);
            delegationContext
                    .getExecutionContext()
                    .fireDelegationFailed(new DelegationFailedEvent(
                            workingRequest.getTaskId(),
                            callerRole,
                            callerRole,
                            msg,
                            null,
                            response,
                            Duration.between(requestStart, Instant.now())));
            return msg;
        }

        // All guards and policies passed -- fire DelegationStartedEvent
        int childDepth = delegationContext.getCurrentDepth() + 1;
        log.info(
                "Agent '{}' delegating subtask to '{}' (depth {}/{}): {}",
                callerRole,
                resolvedTarget.getRole(),
                childDepth,
                delegationContext.getMaxDepth(),
                workingRequest.getTaskDescription().length() > 80
                        ? workingRequest.getTaskDescription().substring(0, 80) + "..."
                        : workingRequest.getTaskDescription());

        delegationContext
                .getExecutionContext()
                .fireDelegationStarted(new DelegationStartedEvent(
                        workingRequest.getTaskId(),
                        callerRole,
                        resolvedTarget.getRole(),
                        workingRequest.getTaskDescription(),
                        childDepth,
                        workingRequest));

        // Save prior MDC values so nested delegations (A->B->C) restore the outer
        // context correctly when the inner finally block runs
        String priorDepth = MDC.get(MDC_DELEGATION_DEPTH);
        String priorParent = MDC.get(MDC_DELEGATION_PARENT);
        String priorDelegationId = MDC.get(MDC_DELEGATION_ID);
        MDC.put(MDC_DELEGATION_DEPTH, String.valueOf(childDepth));
        MDC.put(MDC_DELEGATION_PARENT, callerRole);
        MDC.put(MDC_DELEGATION_ID, workingRequest.getTaskId());

        final DelegationRequest finalRequest = workingRequest;
        final Agent finalTarget = resolvedTarget;
        try {
            Task delegatedTask = Task.builder()
                    .description(finalRequest.getTaskDescription())
                    .expectedOutput(DEFAULT_EXPECTED_OUTPUT)
                    .agent(finalTarget)
                    .build();

            // Descend: child runs at depth + 1
            DelegationContext childCtx = delegationContext.descend();

            TaskOutput output = delegationContext
                    .getAgentExecutor()
                    .execute(delegatedTask, List.of(), delegationContext.getExecutionContext(), childCtx);

            delegatedOutputs.add(output);

            Duration elapsed = Duration.between(requestStart, Instant.now());
            DelegationResponse response = new DelegationResponse(
                    finalRequest.getTaskId(),
                    DelegationStatus.SUCCESS,
                    finalTarget.getRole(),
                    output.getRaw(),
                    output.getParsedOutput(),
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    elapsed);
            delegationResponses.add(response);

            log.info(
                    "Delegation to '{}' completed | Tool calls: {} | Duration: {}",
                    finalTarget.getRole(),
                    output.getToolCallCount(),
                    output.getDuration());

            delegationContext
                    .getExecutionContext()
                    .fireDelegationCompleted(new DelegationCompletedEvent(
                            finalRequest.getTaskId(), callerRole, finalTarget.getRole(), response, elapsed));

            // Capture delegation trace for the parent agent's TaskTrace
            if (delegationTraceConsumer != null) {
                Instant completedAt = Instant.now();
                DelegationTrace trace = DelegationTrace.builder()
                        .delegatorRole(callerRole)
                        .workerRole(finalTarget.getRole())
                        .taskDescription(finalRequest.getTaskDescription())
                        .startedAt(requestStart)
                        .completedAt(completedAt)
                        .duration(Duration.between(requestStart, completedAt))
                        .depth(childDepth)
                        .result(output.getRaw())
                        .succeeded(true)
                        .workerTrace(output.getTrace())
                        .build();
                delegationTraceConsumer.accept(trace);
            }

            // Option C hybrid design: the @Tool method returns the worker's plain-text output to
            // the LLM to preserve backward compatibility. DelegationRequest and DelegationResponse
            // are framework-internal observability contracts, not serialized to the LLM.
            return output.getRaw();

        } catch (Exception e) {
            Duration elapsed = Duration.between(requestStart, Instant.now());
            DelegationResponse response = new DelegationResponse(
                    finalRequest.getTaskId(),
                    DelegationStatus.FAILURE,
                    finalTarget.getRole(),
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
            delegationContext
                    .getExecutionContext()
                    .fireDelegationFailed(new DelegationFailedEvent(
                            finalRequest.getTaskId(),
                            callerRole,
                            finalTarget.getRole(),
                            e.getMessage() != null
                                    ? e.getMessage()
                                    : e.getClass().getSimpleName(),
                            e,
                            response,
                            elapsed));
            throw e;
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
            if (priorDelegationId != null) {
                MDC.put(MDC_DELEGATION_ID, priorDelegationId);
            } else {
                MDC.remove(MDC_DELEGATION_ID);
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

    /**
     * Returns an immutable snapshot of all {@link DelegationResponse} objects produced
     * by this tool instance, in invocation order. Includes both successful delegations and
     * guard-blocked attempts ({@link DelegationStatus#FAILURE}).
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
        String normalized = role.trim();
        return delegationContext.getPeerAgents().stream()
                .filter(a -> a.getRole().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }
}
