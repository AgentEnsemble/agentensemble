package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire-protocol DTO for capacity settings within a {@link ProfileAppliedMessage}.
 *
 * <p>Separate from the network module's {@code Capacity} to avoid the web module
 * depending on the network module.
 *
 * @param replicas      target replica count
 * @param maxConcurrent maximum concurrent tasks per replica
 * @param dormant       whether the ensemble should be dormant (scaled to zero)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CapacitySpec(int replicas, int maxConcurrent, boolean dormant) {

    public CapacitySpec {
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be non-negative");
        }
        if (maxConcurrent < 0) {
            throw new IllegalArgumentException("maxConcurrent must be non-negative");
        }
    }
}
