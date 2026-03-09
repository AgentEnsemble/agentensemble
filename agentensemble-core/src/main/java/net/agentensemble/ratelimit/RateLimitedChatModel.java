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
 *
 * <p>This class is thread-safe. Multiple threads calling {@link #chat(ChatRequest)} concurrently
 * share the same token bucket, making it suitable for use with parallel workflows.
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

    /** Guards nextAvailableNanos and the tokenAvailable condition. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Signalled whenever nextAvailableNanos advances due to time passing. */
    private final Condition tokenAvailable = lock.newCondition();

    /**
     * Timestamp (System.nanoTime) at which the next token will be available.
     * Starts at 0 so the very first request is always immediate.
     */
    private long nextAvailableNanos = 0;

    private RateLimitedChatModel(ChatModel delegate, RateLimit rateLimit, Duration waitTimeout) {
        this.delegate = delegate;
        this.rateLimit = rateLimit;
        this.waitTimeout = waitTimeout;
        this.nanosPerToken = rateLimit.nanosPerToken();
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
     * without making any LLM call.
     *
     * @param request the chat request
     * @return the chat response from the delegate
     * @throws RateLimitTimeoutException if a token could not be acquired within the wait timeout
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        acquireToken();
        return delegate.chat(request);
    }

    /**
     * Acquires a single token from the bucket. Blocks until:
     * <ul>
     *   <li>a token is available (nextAvailableNanos &lt;= now), or</li>
     *   <li>the wait timeout is exceeded (throws {@link RateLimitTimeoutException})</li>
     * </ul>
     */
    private void acquireToken() {
        lock.lock();
        try {
            long deadlineNanos = System.nanoTime() + waitTimeout.toNanos();

            while (true) {
                long now = System.nanoTime();

                if (nextAvailableNanos <= now) {
                    // Token is available: claim it by advancing the next-available timestamp
                    nextAvailableNanos = now + nanosPerToken;
                    return;
                }

                // Token not yet available: check if we have time left to wait
                long remainingNanos = deadlineNanos - now;
                if (remainingNanos <= 0) {
                    throw new RateLimitTimeoutException(rateLimit, waitTimeout);
                }

                // Wait for the lesser of: time until next token, or remaining timeout
                long waitNanos = Math.min(nextAvailableNanos - now, remainingNanos);
                try {
                    //noinspection ResultOfMethodCallIgnored
                    tokenAvailable.awaitNanos(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RateLimitTimeoutException(rateLimit, waitTimeout);
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
