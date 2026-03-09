package net.agentensemble.ratelimit;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A {@link ChatModel} decorator that enforces a request rate limit using a token-bucket algorithm.
 *
 * <p>Wraps any {@link ChatModel} and blocks calling threads when the rate limit is exceeded,
 * allowing at most {@code rateLimit.requests} requests per {@code rateLimit.period}. When a
 * thread waits longer than {@code waitTimeout}, a {@link RateLimitTimeoutException} is thrown.
 * If a waiting thread is interrupted, a {@link RateLimitInterruptedException} is thrown instead
 * (the interrupt flag is preserved on the thread).
 *
 * <p>This class is thread-safe. Multiple threads calling {@link #chat(ChatRequest)} concurrently
 * share the same token bucket, making it suitable for use with parallel workflows.
 *
 * <h2>Token-bucket behaviour</h2>
 *
 * <p>The bucket starts with one pre-loaded token so the first request is always immediate.
 * It refills at a rate of one token per {@code period / requests} nanoseconds, up to a
 * maximum capacity of {@code requests} tokens. Tokens accumulate while the model is idle,
 * allowing a burst of up to {@code requests} consecutive requests after a sufficiently long
 * quiet period.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 * // Wrap any ChatModel
 * var rateLimitedModel = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(60));
 *
 * // Use as a standard ChatModel
 * var researcher = Agent.builder()
 *     .role("Researcher")
 *     .llm(rateLimitedModel)
 *     .build();
 * </pre>
 *
 * <h2>Shared buckets for agents on the same API key</h2>
 *
 * <pre>
 * var sharedModel = RateLimitedChatModel.of(openAiModel, RateLimit.perMinute(60));
 *
 * // Both agents share the same token bucket
 * var researcher = Agent.builder().llm(sharedModel).build();
 * var writer = Agent.builder().llm(sharedModel).build();
 * </pre>
 *
 * <h2>Builder conveniences</h2>
 *
 * <p>For single-agent or task-first use, the builder convenience methods auto-wrap the LLM:
 * <pre>
 * // Agent level
 * Agent.builder().llm(openAiModel).rateLimit(RateLimit.perMinute(60)).build();
 *
 * // Task level
 * Task.builder().chatLanguageModel(openAiModel).rateLimit(RateLimit.perMinute(60)).build();
 *
 * // Ensemble level (all synthesized agents share one bucket)
 * Ensemble.builder().chatLanguageModel(openAiModel).rateLimit(RateLimit.perMinute(60)).build();
 * </pre>
 */
public final class RateLimitedChatModel implements ChatModel {

    /** Default wait timeout when none is specified. */
    public static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(30);

    private final ChatModel delegate;
    private final RateLimit rateLimit;
    private final Duration waitTimeout;
    private final long nanosPerToken;

    /** Guards availableTokens, lastRefillNanos, and the tokenAvailable condition. */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Used for time-based waiting only. Wakeups are purely time-driven via
     * {@link Condition#awaitNanos(long)}; no explicit signals are sent.
     * The condition is used solely so that waiting threads release the lock
     * during the wait and can be woken by a timed expiry.
     */
    private final Condition tokenAvailable = lock.newCondition();

    /** Token bucket capacity: the maximum number of tokens that can accumulate while idle. */
    private final double capacity;

    /**
     * Monotonic timestamp (System.nanoTime) of the last token refill.
     * Used to compute how many tokens have accumulated since the previous request.
     */
    private long lastRefillNanos;

    /**
     * Available tokens in the bucket. Starts at 1.0 so the very first request is always
     * immediate. Accumulates up to {@link #capacity} during idle periods, enabling burst
     * traffic up to the configured request limit.
     */
    private double availableTokens;

    private RateLimitedChatModel(ChatModel delegate, RateLimit rateLimit, Duration waitTimeout) {
        this.delegate = delegate;
        this.rateLimit = rateLimit;
        this.waitTimeout = waitTimeout;
        this.nanosPerToken = rateLimit.nanosPerToken();
        this.capacity = rateLimit.getRequests();
        this.lastRefillNanos = System.nanoTime();
        this.availableTokens = 1.0;
    }

    /**
     * Create a {@code RateLimitedChatModel} with the default wait timeout of 30 seconds.
     *
     * @param delegate  the underlying {@link ChatModel}; must not be null
     * @param rateLimit the rate limit to enforce; must not be null
     * @return a new {@code RateLimitedChatModel}
     * @throws IllegalArgumentException if {@code delegate} or {@code rateLimit} is null
     */
    public static RateLimitedChatModel of(ChatModel delegate, RateLimit rateLimit) {
        return of(delegate, rateLimit, DEFAULT_WAIT_TIMEOUT);
    }

    /**
     * Create a {@code RateLimitedChatModel} with a custom wait timeout.
     *
     * @param delegate    the underlying {@link ChatModel}; must not be null
     * @param rateLimit   the rate limit to enforce; must not be null
     * @param waitTimeout how long to wait for a token before throwing
     *                    {@link RateLimitTimeoutException}; must not be null
     * @return a new {@code RateLimitedChatModel}
     * @throws IllegalArgumentException if any argument is null
     */
    public static RateLimitedChatModel of(ChatModel delegate, RateLimit rateLimit, Duration waitTimeout) {
        if (delegate == null) {
            throw new IllegalArgumentException("RateLimitedChatModel delegate must not be null");
        }
        if (rateLimit == null) {
            throw new IllegalArgumentException("RateLimitedChatModel rateLimit must not be null");
        }
        if (waitTimeout == null) {
            throw new IllegalArgumentException("RateLimitedChatModel waitTimeout must not be null");
        }
        return new RateLimitedChatModel(delegate, rateLimit, waitTimeout);
    }

    /**
     * Acquires a rate-limit token (blocking if necessary) then delegates to the underlying model.
     *
     * <p>If the wait exceeds {@link #getWaitTimeout()}, throws {@link RateLimitTimeoutException}
     * without making any LLM call. If the waiting thread is interrupted, throws
     * {@link RateLimitInterruptedException} (the interrupt flag is preserved).
     *
     * @param request the chat request
     * @return the chat response from the delegate
     * @throws RateLimitTimeoutException     if a token could not be acquired within the wait timeout
     * @throws RateLimitInterruptedException if the waiting thread was interrupted
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        acquireToken();
        return delegate.chat(request);
    }

    /**
     * Acquires a single token from the bucket using the token-bucket algorithm.
     * Blocks until a token is available or the deadline/interrupt condition is met.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Refill tokens based on elapsed time since the last refill, up to capacity.</li>
     *   <li>If {@code availableTokens >= 1}, consume one and return immediately.</li>
     *   <li>Otherwise wait (releasing the lock) until the next token is expected, then retry.</li>
     * </ol>
     */
    private void acquireToken() {
        lock.lock();
        try {
            long deadlineNanos = System.nanoTime() + waitTimeout.toNanos();

            while (true) {
                long now = System.nanoTime();

                // Refill tokens based on elapsed time since the last refill
                long elapsed = now - lastRefillNanos;
                if (elapsed > 0) {
                    availableTokens = Math.min(capacity, availableTokens + (double) elapsed / nanosPerToken);
                    lastRefillNanos = now;
                }

                if (availableTokens >= 1.0) {
                    // Token available: consume it and proceed
                    availableTokens -= 1.0;
                    return;
                }

                // Token not yet available: check if we have time left to wait
                long remainingNanos = deadlineNanos - now;
                if (remainingNanos <= 0) {
                    throw new RateLimitTimeoutException(rateLimit, waitTimeout);
                }

                // Wait for the lesser of: time until the next token, or remaining timeout
                long nanosUntilToken = (long) Math.ceil((1.0 - availableTokens) * nanosPerToken);
                long waitNanos = Math.min(nanosUntilToken, remainingNanos);
                try {
                    //noinspection ResultOfMethodCallIgnored
                    tokenAvailable.awaitNanos(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RateLimitInterruptedException(rateLimit, e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * The underlying {@link ChatModel} that this decorator wraps.
     */
    public ChatModel getDelegate() {
        return delegate;
    }

    /**
     * The rate limit being enforced.
     */
    public RateLimit getRateLimit() {
        return rateLimit;
    }

    /**
     * The configured wait timeout. When a thread waits longer than this for a token,
     * a {@link RateLimitTimeoutException} is thrown.
     */
    public Duration getWaitTimeout() {
        return waitTimeout;
    }
}
