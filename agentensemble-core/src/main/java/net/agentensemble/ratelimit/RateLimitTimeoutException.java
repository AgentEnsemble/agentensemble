package net.agentensemble.ratelimit;

import java.time.Duration;
import net.agentensemble.exception.AgentEnsembleException;

/**
 * Thrown when a request cannot acquire a rate-limit token within the configured timeout.
 *
 * <p>This exception is thrown by {@link RateLimitedChatModel} when a thread waits longer
 * than the configured wait timeout for a token to become available in the token bucket.
 *
 * <p>To handle this exception, either:
 * <ul>
 *   <li>Increase the {@code waitTimeout} on {@link RateLimitedChatModel}</li>
 *   <li>Reduce the concurrency of your parallel workflow</li>
 *   <li>Increase the {@link RateLimit} to allow more requests per period</li>
 * </ul>
 */
public class RateLimitTimeoutException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final RateLimit rateLimit;
    private final Duration waitTimeout;

    /**
     * Constructs a new {@code RateLimitTimeoutException}.
     *
     * @param rateLimit   the rate limit that was being enforced
     * @param waitTimeout the timeout duration that was exceeded
     */
    public RateLimitTimeoutException(RateLimit rateLimit, Duration waitTimeout) {
        super(buildMessage(rateLimit, waitTimeout));
        this.rateLimit = rateLimit;
        this.waitTimeout = waitTimeout;
    }

    /**
     * The rate limit that was being enforced when this exception was thrown.
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * The configured wait timeout that was exceeded.
     */
    public Duration getWaitTimeout() {
        return waitTimeout;
    }

    private static String buildMessage(RateLimit rateLimit, Duration waitTimeout) {
        return "Rate limit exceeded: waited "
                + waitTimeout.toMillis()
                + "ms but no token became available. "
                + "Limit: "
                + rateLimit.getRequests()
                + " requests per "
                + rateLimit.getPeriod()
                + ". "
                + "Increase waitTimeout or reduce concurrency.";
    }
}
