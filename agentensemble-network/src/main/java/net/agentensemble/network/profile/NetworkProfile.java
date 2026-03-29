package net.agentensemble.network.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An operational profile defining capacity targets and pre-load directives for
 * anticipated load changes.
 *
 * <pre>
 * NetworkProfile profile = NetworkProfile.builder()
 *     .name("sporting-event-weekend")
 *     .ensemble("front-desk", Capacity.replicas(4).maxConcurrent(50))
 *     .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
 *     .preload("kitchen", "inventory", "Extra beer and ice stocked")
 *     .build();
 * </pre>
 */
public class NetworkProfile {

    private final String name;
    private final Map<String, Capacity> ensembleCapacities;
    private final List<PreloadDirective> preloadDirectives;

    private NetworkProfile(
            String name, Map<String, Capacity> ensembleCapacities, List<PreloadDirective> preloadDirectives) {
        this.name = name;
        this.ensembleCapacities = Map.copyOf(ensembleCapacities);
        this.preloadDirectives = List.copyOf(preloadDirectives);
    }

    public String name() {
        return name;
    }

    public Map<String, Capacity> ensembleCapacities() {
        return ensembleCapacities;
    }

    public List<PreloadDirective> preloadDirectives() {
        return preloadDirectives;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String name;
        private final Map<String, Capacity> ensembleCapacities = new HashMap<>();
        private final List<PreloadDirective> preloadDirectives = new ArrayList<>();

        private Builder() {}

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            return this;
        }

        public Builder ensemble(String name, Capacity capacity) {
            Objects.requireNonNull(name, "ensemble name must not be null");
            Objects.requireNonNull(capacity, "capacity must not be null");
            ensembleCapacities.put(name, capacity);
            return this;
        }

        public Builder preload(String ensembleName, String scope, String content) {
            preloadDirectives.add(new PreloadDirective(ensembleName, scope, content));
            return this;
        }

        public NetworkProfile build() {
            Objects.requireNonNull(name, "name is required");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            return new NetworkProfile(name, ensembleCapacities, preloadDirectives);
        }
    }
}
