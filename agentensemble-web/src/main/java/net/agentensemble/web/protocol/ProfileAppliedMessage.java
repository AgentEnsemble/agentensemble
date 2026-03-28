package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.Objects;

/**
 * Broadcast when an operational profile is applied to the network.
 *
 * <p>Notifies all connected ensemble dashboards of the profile change and the
 * new capacity targets for each ensemble.
 *
 * @param profileName the name of the applied profile
 * @param capacities  per-ensemble capacity targets
 * @param appliedAt   ISO-8601 timestamp of when the profile was applied
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProfileAppliedMessage(String profileName, Map<String, CapacitySpec> capacities, String appliedAt)
        implements ServerMessage {

    public ProfileAppliedMessage {
        Objects.requireNonNull(profileName, "profileName must not be null");
        capacities = capacities != null ? Map.copyOf(capacities) : Map.of();
    }
}
