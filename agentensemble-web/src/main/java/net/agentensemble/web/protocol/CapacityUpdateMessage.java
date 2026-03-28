package net.agentensemble.web.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * Capacity advertisement broadcast by an ensemble to the network.
 *
 * <p>Ensembles periodically send this message to advertise their current load and
 * availability. When {@code shareable} is {@code true}, the ensemble's spare capacity
 * is available to other realms in the federation.
 *
 * @param ensemble      the ensemble name
 * @param realm         the realm (K8s namespace) this ensemble belongs to
 * @param status        availability status: {@code "available"}, {@code "busy"}, or {@code "draining"}
 * @param currentLoad   current load as a fraction from 0.0 to 1.0
 * @param maxConcurrent maximum concurrent tasks this ensemble can handle
 * @param shareable     whether spare capacity is available to other realms
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CapacityUpdateMessage(
        String ensemble, String realm, String status, double currentLoad, int maxConcurrent, boolean shareable)
        implements ServerMessage {

    public CapacityUpdateMessage {
        Objects.requireNonNull(ensemble, "ensemble must not be null");
        Objects.requireNonNull(realm, "realm must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
