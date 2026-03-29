package net.agentensemble.network.federation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.network.CapabilityRegistry;
import net.agentensemble.web.protocol.CapacityUpdateMessage;

/**
 * Federation-aware capability and capacity registry.
 *
 * <p>Extends {@link CapabilityRegistry} with realm awareness and load-based routing.
 * The routing hierarchy is: local ensemble -> same realm -> cross-realm (shareable only).
 */
public class FederationRegistry {

    private final CapabilityRegistry capabilityRegistry;
    private final ConcurrentHashMap<String, String> realmByEnsemble = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CapacityStatus> capacityByEnsemble = new ConcurrentHashMap<>();

    public FederationRegistry(CapabilityRegistry capabilityRegistry) {
        this.capabilityRegistry = Objects.requireNonNull(capabilityRegistry);
    }

    /** Update capacity info from a capacity_update message. */
    public void updateCapacity(CapacityUpdateMessage update) {
        Objects.requireNonNull(update);
        realmByEnsemble.put(update.ensemble(), update.realm());
        capacityByEnsemble.put(
                update.ensemble(),
                new CapacityStatus(
                        update.ensemble(),
                        update.realm(),
                        update.status(),
                        update.currentLoad(),
                        update.maxConcurrent(),
                        update.shareable()));
    }

    /**
     * Find the best provider using routing hierarchy: local -> same realm -> cross-realm.
     */
    public Optional<String> findProvider(String toolName, String localRealm) {
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(localRealm);
        List<String> allProviders = capabilityRegistry.findAllProviders(toolName);
        if (allProviders.isEmpty()) return Optional.empty();

        // 1. Prefer local realm providers
        Optional<String> localProvider = allProviders.stream()
                .filter(e -> localRealm.equals(realmByEnsemble.get(e)))
                .min(Comparator.comparingDouble(this::loadOf));
        if (localProvider.isPresent()) return localProvider;

        // 2. Try providers in same realm (no realm info = assume local)
        Optional<String> sameRealm = allProviders.stream()
                .filter(e -> !realmByEnsemble.containsKey(e))
                .min(Comparator.comparingDouble(this::loadOf));
        if (sameRealm.isPresent()) return sameRealm;

        // 3. Cross-realm: only shareable providers
        return allProviders.stream().filter(this::isShareable).min(Comparator.comparingDouble(this::loadOf));
    }

    /**
     * Find the least-loaded provider across all realms, respecting shareability.
     */
    public Optional<String> findLeastLoadedProvider(String toolName, String localRealm) {
        Objects.requireNonNull(toolName);
        Objects.requireNonNull(localRealm);
        List<String> allProviders = capabilityRegistry.findAllProviders(toolName);
        return allProviders.stream()
                .filter(e ->
                        localRealm.equals(realmByEnsemble.get(e)) || !realmByEnsemble.containsKey(e) || isShareable(e))
                .min(Comparator.comparingDouble(this::loadOf));
    }

    /** Get capacity status for an ensemble. */
    public Optional<CapacityStatus> getCapacity(String ensemble) {
        return Optional.ofNullable(capacityByEnsemble.get(ensemble));
    }

    /** Get the realm of an ensemble. */
    public Optional<String> getRealm(String ensemble) {
        return Optional.ofNullable(realmByEnsemble.get(ensemble));
    }

    private double loadOf(String ensemble) {
        CapacityStatus status = capacityByEnsemble.get(ensemble);
        return status != null ? status.currentLoad() : 0.5; // default mid-load if unknown
    }

    private boolean isShareable(String ensemble) {
        CapacityStatus status = capacityByEnsemble.get(ensemble);
        return status != null && status.shareable();
    }
}
