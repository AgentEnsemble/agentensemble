package net.agentensemble.ratelimit;

import java.time.Duration;
import java.util.Objects;
import net.agentensemble.exception.ValidationException;

/**
 * Immutable value object describing a request rate limit.
 *
 * <p>Specifies the maximum number of LLM API requests allowed within a given time window.
 * Use the factory methods to create instances:
 *
 * <pre>
 * RateLimit.of(60, Duration.ofMinutes(1))  // 60 requests per minute
 * RateLimit.perMinute(60)                  // convenience alias
 * RateLimit.perSecond(2)                   // 2 requests per second
 * </pre>
 *
 * <p>Rate limits are enforced by {@link RateLimitedChatModel} using a token-bucket algorithm.
 * Pass a {@code RateLimit} to {@link RateLimitedChatModel#of(dev.langchain4j.model.chat.ChatModel,
 * RateLimit)} to wrap any {@link dev.langchain4j.model.chat.ChatModel} with rate limiting.
 */
public final class RateLimit {

    private final int requests;
    private final Duration period;

    private RateLimit(int requests, Duration period) {
        this.requests = requests;
        this.period = period;
    }

    /**
     * Create a rate limit of {@code requests} per {@code period}.
     *
     * @param requests the maximum number of requests allowed in {@code period}; must be &gt; 0
     * @param period   the time window; must be positive and non-null
     * @return a new {@code RateLimit}
     * @throws ValidationException if {@code requests} &lt;= 0 or {@code period} is null/non-positive
     */
    public static RateLimit of(int requests, Duration period) {
        if (requests <= 0) {
            throw new ValidationException("RateLimit requests must be > 0, got: " + requests);
        }
        if (period == null) {
            throw new ValidationException("RateLimit period must not be null");
        }
        if (period.isZero() || period.isNegative()) {
            throw new ValidationException("RateLimit period must be positive, got: " + period);
        }
        return new RateLimit(requests, period);
    }

    /**
     * Convenience factory: {@code requests} per minute.
     *
     * @param requests the maximum number of requests per minute; must be &gt; 0
     * @return a new {@code RateLimit}
     */
    public static RateLimit perMinute(int requests) {
        return of(requests, Duration.ofMinutes(1));
    }

    /**
     * Convenience factory: {@code requests} per second.
     *
     * @param requests the maximum number of requests per second; must be &gt; 0
     * @return a new {@code RateLimit}
     */
    public static RateLimit perSecond(int requests) {
        return of(requests, Duration.ofSeconds(1));
    }

    /**
     * The maximum number of requests allowed within {@link #getPeriod()}.
     */
    public int getRequests() {
        return requests;
    }

    /**
     * The time window over which {@link #getRequests()} are allowed.
     */
    public Duration getPeriod() {
        return period;
    }

    /**
     * The nanosecond interval between consecutive tokens in the token bucket.
     *
     * <p>Used internally by {@link RateLimitedChatModel}. Equal to
     * {@code period.toNanos() / requests}, clamped to a minimum of 1 nanosecond so that
     * extremely high request rates (where {@code requests > period.toNanos()}) do not
     * silently disable rate limiting by returning zero.
     */
    long nanosPerToken() {
        return Math.max(1L, period.toNanos() / requests);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RateLimit other)) return false;
        return requests == other.requests && Objects.equals(period, other.period);
    }

    @Override
    public int hashCode() {
        return Objects.hash(requests, period);
    }

    @Override
    public String toString() {
        return "RateLimit{requests=" + requests + ", period=" + period + "}";
    }
}
