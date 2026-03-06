package net.agentensemble.review;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ReviewHandler} that returns {@link ReviewDecision#continueExecution()} after
 * sleeping for a configured delay.
 *
 * <p>Use in tests that need to simulate realistic human review timing without blocking
 * on stdin. Obtain an instance via {@link ReviewHandler#autoApproveWithDelay(Duration)}.
 *
 * <p>The delay does not respect the {@link ReviewRequest#timeout()}. Interrupted sleep
 * is handled gracefully: the interrupt flag is restored and Continue is returned.
 *
 * <p>This implementation is thread-safe.
 */
final class AutoApproveWithDelayReviewHandler implements ReviewHandler {

    private static final Logger log = LoggerFactory.getLogger(AutoApproveWithDelayReviewHandler.class);

    private final Duration delay;

    /**
     * Construct with the given delay.
     *
     * @param delay how long to sleep before returning Continue; must not be null or negative
     */
    AutoApproveWithDelayReviewHandler(Duration delay) {
        if (delay == null) {
            throw new IllegalArgumentException("delay must not be null");
        }
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        this.delay = delay;
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        if (!delay.isZero()) {
            try {
                // Thread.sleep(Duration) is used instead of Thread.sleep(delay.toMillis()) to avoid
                // ArithmeticException on overflow for very large Duration values (e.g., values
                // exceeding Long.MAX_VALUE milliseconds, which toMillis() cannot represent).
                log.debug("AutoApproveWithDelayReviewHandler sleeping for {}", delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("AutoApproveWithDelayReviewHandler interrupted; returning Continue immediately");
            }
        }
        return ReviewDecision.continueExecution();
    }
}
