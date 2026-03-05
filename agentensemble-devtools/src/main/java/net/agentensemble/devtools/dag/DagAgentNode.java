package net.agentensemble.devtools.dag;

import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import lombok.Value;

/**
 * Snapshot of an agent's configuration included in a {@link DagModel}.
 *
 * <p>Provides enough information to render agent nodes in a visualization tool
 * without requiring access to the original {@link net.agentensemble.Agent} instance.
 */
@Value
@Builder
public class DagAgentNode {

    /** The agent's role, used as the primary identifier. */
    @NonNull
    String role;

    /** The agent's primary objective. */
    @NonNull
    String goal;

    /**
     * Optional background/persona context for the agent.
     * {@code null} when not configured.
     */
    String background;

    /**
     * Names of tools available to this agent.
     * Empty when the agent has no tools configured.
     */
    @Singular
    List<String> toolNames;

    /** Whether this agent is allowed to delegate tasks to peer agents. */
    boolean allowDelegation;
}
