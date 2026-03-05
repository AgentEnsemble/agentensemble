package net.agentensemble.trace;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

/**
 * A snapshot of an agent's configuration captured at the start of an ensemble run.
 *
 * <p>Included in {@link ExecutionTrace} to provide context for
 * post-mortem analysis without requiring access to the original {@link net.agentensemble.Agent}
 * instance.
 */
@Value
@Builder(toBuilder = true)
public class AgentSummary {

    /** The agent's role, used as the primary identifier in logs and traces. */
    @NonNull
    String role;

    /** The agent's primary objective as configured in the ensemble. */
    @NonNull
    String goal;

    /**
     * Optional persona or background context for the agent.
     * {@code null} when not configured.
     */
    String background;

    /**
     * Names of all tools available to this agent (excluding the auto-injected delegation tool).
     * Empty when the agent has no tools configured.
     */
    @Singular
    List<String> toolNames;

    /** Whether this agent is allowed to delegate tasks to peer agents. */
    boolean allowDelegation;
}
