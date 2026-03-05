package net.agentensemble.trace;

import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Record of a single agent-to-agent delegation captured during task execution.
 *
 * <p>Included in {@link TaskTrace} for the delegating agent's task trace.
 * The worker agent's complete execution trace is available in the {@code workerTrace}
 * field when peer delegation ({@code AgentDelegationTool}) is used. For hierarchical
 * workflow delegations, the worker traces are accessible separately via
 * {@link ExecutionTrace}.
 */
@Value
@Builder(toBuilder = true)
public class DelegationTrace {

    /** The role of the agent that initiated the delegation. */
    @NonNull
    String delegatorRole;

    /** The role of the agent that received the delegated task. */
    @NonNull
    String workerRole;

    /** The task description that was delegated. */
    @NonNull
    String taskDescription;

    /** Wall-clock instant when the delegation began. */
    @NonNull
    Instant startedAt;

    /** Wall-clock instant when the worker agent completed its task. */
    @NonNull
    Instant completedAt;

    /** Total elapsed time for the delegation ({@code completedAt - startedAt}). */
    @NonNull
    Duration duration;

    /** Depth of this delegation in the delegation chain (0 = first delegation). */
    int depth;

    /**
     * The worker agent's text output, or an error message if the delegation failed.
     * This is the value that was returned to the delegating agent's ReAct loop.
     */
    String result;

    /** Whether the delegation completed successfully without an exception. */
    boolean succeeded;

    /**
     * The worker agent's complete execution trace.
     * Available for peer delegations ({@code AgentDelegationTool}).
     * {@code null} for hierarchical delegations (worker traces are in
     * {@link ExecutionTrace} {@code taskTraces}).
     */
    TaskTrace workerTrace;
}
