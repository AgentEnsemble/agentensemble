package net.agentensemble.ratelimit;

import net.agentensemble.exception.AgentEnsembleException;

/**
 * Thrown when a thread waiting for a rate-limit token is interrupted.
 *
 * <p>Distinct from {@link RateLimitTimeoutException}: this exception indicates the
 * thread was externally interrupted (e.g., by thread pool shutdown or task cancellation),
 * not that the configured wait timeout was exceeded.
 *
 * <p>The calling thread's interrupt flag is preserved (re-set) before this exception is
 * thrown, so callers can check {@link Thread#isInterrupted()} or catch this exception and
 * re-interrupt if needed.
 *
 * @see RateLimitTimeoutException
 * @see RateLimitedChatModel
 */
public class RateLimitInterruptedException extends AgentEnsembleException {

    private static final long serialVersionUID = 1L;

    private final RateLimit rateLimit;

    /**
     * Constructs a new {@code RateLimitInterruptedException}.
     *
     * @param rateLimit the rate limit that was being enforced when the interruption occurred
     * @param cause     the {@link InterruptedException} that triggered this exception
     */
    public RateLimitInterruptedException(RateLimit rateLimit, InterruptedException cause) {
        super(
                "Rate limit wait was interrupted for limit: "
                        + rateLimit.getRequests()
                        + " requests per "
                        + rateLimit.getPeriod(),
                cause);
        this.rateLimit = rateLimit;
    }

    /**
     * The rate limit that was being enforced when this exception was thrown.
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }
}
