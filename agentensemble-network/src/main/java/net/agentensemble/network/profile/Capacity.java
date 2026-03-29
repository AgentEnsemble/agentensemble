package net.agentensemble.network.profile;

/**
 * Capacity configuration for an ensemble within a {@link NetworkProfile}.
 *
 * <p>Uses a fluent builder pattern matching the design spec:
 * <pre>
 * Capacity.replicas(4).maxConcurrent(50)
 * </pre>
 *
 * @param replicas      target replica count (0 for scale-to-zero)
 * @param maxConcurrent maximum concurrent tasks per replica
 * @param dormant       whether the ensemble should be dormant
 */
public record Capacity(int replicas, int maxConcurrent, boolean dormant) {

    public Capacity {
        if (replicas < 0) {
            throw new IllegalArgumentException("replicas must be non-negative");
        }
        if (maxConcurrent < 0) {
            throw new IllegalArgumentException("maxConcurrent must be non-negative");
        }
    }

    /**
     * Start building a capacity specification with the given replica count.
     */
    public static CapacityBuilder replicas(int replicas) {
        return new CapacityBuilder(replicas);
    }

    /**
     * Fluent builder for {@link Capacity}.
     */
    public static final class CapacityBuilder {
        private final int replicas;
        private int maxConcurrent = 10;
        private boolean dormant = false;

        private CapacityBuilder(int replicas) {
            this.replicas = replicas;
        }

        public Capacity maxConcurrent(int maxConcurrent) {
            return new Capacity(replicas, maxConcurrent, dormant);
        }

        public CapacityBuilder dormant(boolean dormant) {
            this.dormant = dormant;
            return this;
        }

        public Capacity build() {
            return new Capacity(replicas, maxConcurrent, dormant);
        }
    }
}
