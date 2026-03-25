package net.agentensemble.ensemble;

import java.util.Objects;

/**
 * Describes a task or tool that an ensemble shares with the network.
 *
 * <p>Shared capabilities are published during the capability handshake so that
 * peer ensembles can discover and invoke them. Each capability has a unique
 * name within the owning ensemble, a human-readable description, and a type
 * indicating whether it is a full {@link SharedCapabilityType#TASK TASK}
 * delegation or a lightweight {@link SharedCapabilityType#TOOL TOOL} call.
 *
 * @param name        unique name within the ensemble (never null or blank)
 * @param description human-readable description of the capability
 * @param type        whether this is a TASK or TOOL capability
 *
 * @see SharedCapabilityType
 * @see net.agentensemble.Ensemble.EnsembleBuilder#shareTask(String, net.agentensemble.Task)
 * @see net.agentensemble.Ensemble.EnsembleBuilder#shareTool(String, net.agentensemble.tool.AgentTool)
 */
public record SharedCapability(String name, String description, SharedCapabilityType type) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if name is blank
     */
    public SharedCapability {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(type, "type must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
