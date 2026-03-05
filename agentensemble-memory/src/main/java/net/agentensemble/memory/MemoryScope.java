package net.agentensemble.memory;

import java.time.Duration;

/**
 * A named memory scope that a task can declare to read from and write to.
 *
 * <p>Multiple tasks can share the same scope name, allowing them to read each other's
 * outputs across separate {@code Ensemble.run()} invocations.
 *
 * <p>Scopes can optionally declare an eviction policy that controls how many entries are
 * retained when the scope is written to. When no policy is set, entries accumulate
 * indefinitely.
 *
 * <p>Use the static factory for simple cases:
 * <pre>
 * Task.builder()
 *     .description("Research AI trends")
 *     .memory("research")
 *     .build();
 * </pre>
 *
 * <p>Use the builder for scopes with eviction:
 * <pre>
 * MemoryScope scope = MemoryScope.builder()
 *     .name("research")
 *     .keepLastEntries(5)
 *     .build();
 *
 * Task.builder()
 *     .description("Research AI trends")
 *     .memory(scope)
 *     .build();
 * </pre>
 */
public class MemoryScope {

    private final String name;
    private final EvictionPolicy evictionPolicy;

    private MemoryScope(String name, EvictionPolicy evictionPolicy) {
        this.name = name;
        this.evictionPolicy = evictionPolicy;
    }

    /**
     * Create a scope with no eviction policy.
     *
     * @param name the scope name; must not be null or blank
     * @return a new MemoryScope
     * @throws IllegalArgumentException if {@code name} is null or blank
     */
    public static MemoryScope of(String name) {
        validateName(name);
        return new MemoryScope(name, null);
    }

    /**
     * Return a builder for a configured scope.
     *
     * @return a new MemoryScopeBuilder
     */
    public static MemoryScopeBuilder builder() {
        return new MemoryScopeBuilder();
    }

    /**
     * Return the scope name.
     *
     * @return the name; never null
     */
    public String getName() {
        return name;
    }

    /**
     * Return the eviction policy for this scope, or {@code null} when no policy is set.
     *
     * @return the eviction policy, or {@code null}
     */
    public EvictionPolicy getEvictionPolicy() {
        return evictionPolicy;
    }

    @Override
    public String toString() {
        return "MemoryScope{name='" + name + "'}";
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MemoryScope name must not be null or blank");
        }
    }

    /**
     * Builder for {@link MemoryScope} with optional eviction configuration.
     *
     * <p>Either {@link #keepLastEntries(int)} or {@link #keepEntriesWithin(java.time.Duration)}
     * may be set, but not both. If both are called, the last one configured takes precedence.
     */
    public static class MemoryScopeBuilder {

        private String name;
        private int keepLastEntriesValue = 0;
        private java.time.Duration keepEntriesWithinValue = null;

        /**
         * Set the scope name.
         *
         * @param name the scope name; must not be null or blank
         * @return this builder
         */
        public MemoryScopeBuilder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Retain only the {@code n} most recent entries in this scope.
         *
         * @param n the maximum number of entries; must be positive
         * @return this builder
         */
        public MemoryScopeBuilder keepLastEntries(int n) {
            this.keepLastEntriesValue = n;
            this.keepEntriesWithinValue = null;
            return this;
        }

        /**
         * Retain only entries stored within the given duration from the current time.
         *
         * @param duration the retention window; must be positive
         * @return this builder
         */
        public MemoryScopeBuilder keepEntriesWithin(Duration duration) {
            this.keepEntriesWithinValue = duration;
            this.keepLastEntriesValue = 0;
            return this;
        }

        /**
         * Build the {@link MemoryScope}.
         *
         * @return a new MemoryScope
         * @throws IllegalArgumentException if the name is not set or is blank
         */
        public MemoryScope build() {
            validateName(name);
            EvictionPolicy policy = null;
            if (keepLastEntriesValue > 0) {
                policy = EvictionPolicy.keepLastEntries(keepLastEntriesValue);
            } else if (keepEntriesWithinValue != null) {
                policy = EvictionPolicy.keepEntriesWithin(keepEntriesWithinValue);
            }
            return new MemoryScope(name, policy);
        }
    }
}
