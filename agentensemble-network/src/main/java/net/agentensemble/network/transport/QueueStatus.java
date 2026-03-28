package net.agentensemble.network.transport;

import java.time.Duration;
import java.util.Objects;

/**
 * Queue position and estimated completion time for a work request.
 *
 * <p>Returned by {@link PriorityWorkQueue#queueStatus(String, String)} to provide the
 * information needed for a {@code task_accepted} response. The fields map directly to
 * {@link net.agentensemble.web.protocol.TaskAcceptedMessage}:
 *
 * <ul>
 *   <li>{@code queuePosition} -- 0 means executing now, 1 means next, etc.</li>
 *   <li>{@code estimatedCompletion} -- estimated time until the request is processed</li>
 * </ul>
 *
 * @param queuePosition       position in the queue (0-based); must not be negative
 * @param estimatedCompletion estimated time until processing; must not be null
 * @see PriorityWorkQueue
 */
public record QueueStatus(int queuePosition, Duration estimatedCompletion) {

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if {@code queuePosition} is negative
     * @throws NullPointerException     if {@code estimatedCompletion} is null
     */
    public QueueStatus {
        if (queuePosition < 0) {
            throw new IllegalArgumentException("queuePosition must not be negative");
        }
        Objects.requireNonNull(estimatedCompletion, "estimatedCompletion must not be null");
    }
}
