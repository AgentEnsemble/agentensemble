package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AutoApproveWithDelayReviewHandler}.
 *
 * <p>Covers normal operation (zero and non-zero delay), interrupt handling, constructor
 * validation, and overflow-safety when given a {@link Duration} that would exceed
 * {@link Long#MAX_VALUE} milliseconds.
 */
class AutoApproveWithDelayReviewHandlerTest {

    private static ReviewRequest basicRequest() {
        return ReviewRequest.of("Task description", "Task output", ReviewTiming.AFTER_EXECUTION, null);
    }

    // ========================
    // Normal operation
    // ========================

    @Test
    void review_withZeroDelay_returnsContinueImmediately() {
        AutoApproveWithDelayReviewHandler handler = new AutoApproveWithDelayReviewHandler(Duration.ZERO);
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void review_withSmallDelay_returnsContinue() {
        AutoApproveWithDelayReviewHandler handler = new AutoApproveWithDelayReviewHandler(Duration.ofMillis(50));
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void review_ignoresTimeoutOnRequest_alwaysReturnsContinue() {
        // The handler does not respect the ReviewRequest timeout; it always approves
        AutoApproveWithDelayReviewHandler handler = new AutoApproveWithDelayReviewHandler(Duration.ofMillis(10));
        ReviewRequest requestWithShortTimeout = ReviewRequest.of(
                "Task", "Output", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(1), OnTimeoutAction.EXIT_EARLY, null);
        ReviewDecision decision = handler.review(requestWithShortTimeout);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    // ========================
    // Interrupt handling
    // ========================

    @Test
    void review_interrupted_restoresInterruptFlagAndReturnsContinue() {
        AutoApproveWithDelayReviewHandler handler = new AutoApproveWithDelayReviewHandler(Duration.ofSeconds(60));

        // Pre-interrupt so sleep exits immediately
        Thread.currentThread().interrupt();

        ReviewDecision decision = handler.review(basicRequest());

        // Handler restores the interrupt flag; consume it for test cleanliness
        assertThat(Thread.interrupted()).isTrue();
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    // ========================
    // Overflow safety (Fix 5: Thread.sleep(Duration) vs Thread.sleep(delay.toMillis()))
    // ========================

    @Test
    void review_withDurationThatWouldOverflowToMillis_doesNotThrowArithmeticException() {
        // Duration.ofSeconds(Long.MAX_VALUE / 1000 + 1) causes toMillis() to throw
        // ArithmeticException on overflow. Thread.sleep(Duration) avoids this by routing
        // through TimeUnit.NANOSECONDS.convert(Duration) which clamps on overflow.
        Duration overflowDuration = Duration.ofSeconds(Long.MAX_VALUE / 1000 + 1);
        AutoApproveWithDelayReviewHandler handler = new AutoApproveWithDelayReviewHandler(overflowDuration);

        // Pre-interrupt so the (very long) sleep exits immediately
        Thread.currentThread().interrupt();

        ReviewDecision decision = handler.review(basicRequest());

        // Handler restores the interrupt flag; consume it for test cleanliness
        assertThat(Thread.interrupted()).isTrue();
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void constructor_nullDelay_throws() {
        assertThatThrownBy(() -> new AutoApproveWithDelayReviewHandler(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delay must not be null");
    }

    @Test
    void constructor_negativeDelay_throws() {
        assertThatThrownBy(() -> new AutoApproveWithDelayReviewHandler(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("delay must not be negative");
    }

    @Test
    void constructor_zeroDelay_succeeds() {
        AutoApproveWithDelayReviewHandler handler = new AutoApproveWithDelayReviewHandler(Duration.ZERO);
        assertThat(handler).isNotNull();
    }

    @Test
    void constructor_positiveDelay_succeeds() {
        AutoApproveWithDelayReviewHandler handler = new AutoApproveWithDelayReviewHandler(Duration.ofSeconds(5));
        assertThat(handler).isNotNull();
    }
}
