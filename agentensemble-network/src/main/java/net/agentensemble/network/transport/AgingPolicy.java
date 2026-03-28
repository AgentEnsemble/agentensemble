package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for priority aging in a {@link PriorityWorkQueue}.
 *
 * <p>Priority aging prevents starvation of low-priority work requests. After each
 * {@code promotionInterval} elapses, an unprocessed request is treated as one priority
 * level higher than its original priority. A {@link net.agentensemble.web.protocol.Priority#LOW LOW}
 * request submitted at 9:00 with a 30-minute interval is treated as
 * {@link net.agentensemble.web.protocol.Priority#NORMAL NORMAL} at 9:30,
 * {@link net.agentensemble.web.protocol.Priority#HIGH HIGH} at 10:00, and
 * {@link net.agentensemble.web.protocol.Priority#CRITICAL CRITICAL} at 10:30.
 *
 * <p>Use the static factories for common configurations:
 * <ul>
 *   <li>{@link #every(Duration)} -- promote one level per interval</li>
 *   <li>{@link #none()} -- aging disabled (effectively infinite interval)</li>
 * </ul>
 *
 * @param promotionInterval time between priority promotions; must be positive and non-null
 * @see PriorityWorkQueue
 */
public record AgingPolicy(Duration promotionInterval) {

    /** Sentinel interval used by {@link #none()} to effectively disable aging. */
    private static final Duration NONE_INTERVAL = Duration.ofDays(36500);

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException     if {@code promotionInterval} is null
     * @throws IllegalArgumentException if {@code promotionInterval} is zero or negative
     */
    public AgingPolicy {
        Objects.requireNonNull(promotionInterval, "promotionInterval must not be null");
        if (promotionInterval.isNegative() || promotionInterval.isZero()) {
            throw new IllegalArgumentException("promotionInterval must be positive");
        }
    }

    /**
     * Create an aging policy that promotes unprocessed requests one priority level
     * every {@code interval}.
     *
     * @param interval time between promotions; must be positive and non-null
     * @return a new aging policy
     */
    public static AgingPolicy every(Duration interval) {
        return new AgingPolicy(interval);
    }

    /**
     * Create an aging policy with aging effectively disabled.
     *
     * <p>Internally uses a very large interval (~100 years) so that no promotion
     * ever occurs in practice.
     *
     * @return an aging policy that never promotes
     */
    public static AgingPolicy none() {
        return new AgingPolicy(NONE_INTERVAL);
    }
}
