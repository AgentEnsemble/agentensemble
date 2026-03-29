package net.agentensemble.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.agentensemble.web.protocol.SharedCapabilityInfo;

/**
 * Thread-safe registry of shared capabilities discovered across the network.
 *
 * <p>Tracks which ensembles provide which capabilities (tasks and tools), with inverted
 * indices for fast lookup by name and tag. Capabilities are registered when ensembles
 * connect and send their {@link net.agentensemble.web.protocol.HelloMessage} with
 * shared capability info.
 *
 * <p>All operations are thread-safe via {@link ConcurrentHashMap}.
 */
public class CapabilityRegistry {

    private final ConcurrentHashMap<String, List<SharedCapabilityInfo>> capabilitiesByEnsemble =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> ensemblesByCapability = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> ensemblesByTag = new ConcurrentHashMap<>();

    /**
     * Register capabilities for an ensemble. Replaces any previously registered capabilities.
     *
     * @param ensemble     the ensemble name; must not be null
     * @param capabilities the capabilities to register; must not be null
     */
    public void register(String ensemble, List<SharedCapabilityInfo> capabilities) {
        Objects.requireNonNull(ensemble, "ensemble must not be null");
        Objects.requireNonNull(capabilities, "capabilities must not be null");

        // Remove old entries first
        unregister(ensemble);

        // Store new capabilities
        capabilitiesByEnsemble.put(ensemble, List.copyOf(capabilities));

        // Build inverted indices
        for (SharedCapabilityInfo cap : capabilities) {
            ensemblesByCapability
                    .computeIfAbsent(cap.name(), k -> ConcurrentHashMap.newKeySet())
                    .add(ensemble);
            if (cap.tags() != null) {
                for (String tag : cap.tags()) {
                    ensemblesByTag
                            .computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet())
                            .add(ensemble);
                }
            }
        }
    }

    /**
     * Remove all capabilities for an ensemble.
     *
     * @param ensemble the ensemble name; must not be null
     */
    public void unregister(String ensemble) {
        Objects.requireNonNull(ensemble, "ensemble must not be null");

        List<SharedCapabilityInfo> old = capabilitiesByEnsemble.remove(ensemble);
        if (old != null) {
            for (SharedCapabilityInfo cap : old) {
                Set<String> providers = ensemblesByCapability.get(cap.name());
                if (providers != null) {
                    providers.remove(ensemble);
                    if (providers.isEmpty()) {
                        ensemblesByCapability.remove(cap.name());
                    }
                }
                if (cap.tags() != null) {
                    for (String tag : cap.tags()) {
                        Set<String> tagProviders = ensemblesByTag.get(tag);
                        if (tagProviders != null) {
                            tagProviders.remove(ensemble);
                            if (tagProviders.isEmpty()) {
                                ensemblesByTag.remove(tag);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Find the first provider of a named capability.
     *
     * @param capabilityName the capability name to search for
     * @return an {@code Optional} containing the first provider ensemble name, or empty
     */
    public Optional<String> findProvider(String capabilityName) {
        Set<String> providers = ensemblesByCapability.get(capabilityName);
        if (providers == null || providers.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(providers.iterator().next());
    }

    /**
     * Find all providers of a named capability.
     *
     * @param capabilityName the capability name to search for
     * @return an unmodifiable list of ensemble names; never null, may be empty
     */
    public List<String> findAllProviders(String capabilityName) {
        Set<String> providers = ensemblesByCapability.get(capabilityName);
        if (providers == null) {
            return List.of();
        }
        return List.copyOf(providers);
    }

    /**
     * Find all capabilities with a given tag.
     *
     * @param tag the tag to search for
     * @return an unmodifiable list of matching capabilities; never null, may be empty
     */
    public List<SharedCapabilityInfo> findByTag(String tag) {
        Set<String> ensembles = ensemblesByTag.get(tag);
        if (ensembles == null) {
            return List.of();
        }
        List<SharedCapabilityInfo> result = new ArrayList<>();
        for (String ensemble : ensembles) {
            List<SharedCapabilityInfo> caps = capabilitiesByEnsemble.get(ensemble);
            if (caps != null) {
                for (SharedCapabilityInfo cap : caps) {
                    if (cap.tags() != null && cap.tags().contains(tag)) {
                        result.add(cap);
                    }
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Return all capabilities across all ensembles.
     *
     * @return an unmodifiable list of all capabilities; never null, may be empty
     */
    public List<SharedCapabilityInfo> all() {
        List<SharedCapabilityInfo> result = new ArrayList<>();
        capabilitiesByEnsemble.values().forEach(result::addAll);
        return Collections.unmodifiableList(result);
    }

    /** Return all capabilities grouped by ensemble name. */
    public Map<String, List<SharedCapabilityInfo>> allByEnsemble() {
        return Map.copyOf(capabilitiesByEnsemble);
    }

    /**
     * Find all capabilities with a given tag, grouped by ensemble name.
     *
     * @param tag the tag to search for
     * @return an unmodifiable map of ensemble name to matching capabilities; never null, may be empty
     */
    public Map<String, List<SharedCapabilityInfo>> findByTagWithEnsemble(String tag) {
        Set<String> ensembles = ensemblesByTag.get(tag);
        if (ensembles == null) {
            return Map.of();
        }
        Map<String, List<SharedCapabilityInfo>> result = new HashMap<>();
        for (String ensemble : ensembles) {
            List<SharedCapabilityInfo> caps = capabilitiesByEnsemble.get(ensemble);
            if (caps != null) {
                List<SharedCapabilityInfo> matched = new ArrayList<>();
                for (SharedCapabilityInfo cap : caps) {
                    if (cap.tags() != null && cap.tags().contains(tag)) {
                        matched.add(cap);
                    }
                }
                if (!matched.isEmpty()) {
                    result.put(ensemble, Collections.unmodifiableList(matched));
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Total number of registered capabilities.
     *
     * @return the count of all capabilities across all ensembles
     */
    public int size() {
        return capabilitiesByEnsemble.values().stream().mapToInt(List::size).sum();
    }
}
