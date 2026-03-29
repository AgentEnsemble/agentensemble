package net.agentensemble.network.federation;

import java.util.Objects;

/**
 * Snapshot of an ensemble's current capacity and load for federation routing.
 *
 * @param ensemble      the ensemble name
 * @param realm         the realm this ensemble belongs to
 * @param status        availability status ({@code "available"}, {@code "busy"}, {@code "draining"})
 * @param currentLoad   current load as a fraction from 0.0 to 1.0
 * @param maxConcurrent maximum concurrent tasks
 * @param shareable     whether spare capacity is available to other realms
 */
public record CapacityStatus(
        String ensemble, String realm, String status, double currentLoad, int maxConcurrent, boolean shareable) {

    public CapacityStatus {
        Objects.requireNonNull(ensemble, "ensemble must not be null");
        Objects.requireNonNull(realm, "realm must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
