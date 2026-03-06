package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReviewHandler} static factories:
 * autoApprove, autoApproveWithDelay, and console.
 */
class AutoApproveReviewHandlerTest {

    private static ReviewRequest anyRequest() {
        return ReviewRequest.of("Task description", "Task output", ReviewTiming.AFTER_EXECUTION, null);
    }

    // ========================
    // autoApprove()
    // ========================

    @Test
    void autoApprove_alwaysReturnsContinue() {
        ReviewHandler handler = ReviewHandler.autoApprove();
        ReviewDecision decision = handler.review(anyRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void autoApprove_returnsContinueForBeforeExecution() {
        ReviewHandler handler = ReviewHandler.autoApprove();
        ReviewRequest request = ReviewRequest.of("Task", "", ReviewTiming.BEFORE_EXECUTION, Duration.ofMinutes(5));
        assertThat(handler.review(request)).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void autoApprove_returnsContinueForDuringExecution() {
        ReviewHandler handler = ReviewHandler.autoApprove();
        ReviewRequest request = ReviewRequest.of("Question?", "", ReviewTiming.DURING_EXECUTION, null);
        assertThat(handler.review(request)).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void autoApprove_returnsSameInstanceEveryTime() {
        ReviewHandler h1 = ReviewHandler.autoApprove();
        ReviewHandler h2 = ReviewHandler.autoApprove();
        assertThat(h1).isSameAs(h2);
    }

    // ========================
    // autoApproveWithDelay()
    // ========================

    @Test
    void autoApproveWithDelay_zeroDelay_returnsContinueImmediately() {
        ReviewHandler handler = ReviewHandler.autoApproveWithDelay(Duration.ZERO);
        ReviewDecision decision = handler.review(anyRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void autoApproveWithDelay_withSmallDelay_returnsContinue() {
        ReviewHandler handler = ReviewHandler.autoApproveWithDelay(Duration.ofMillis(50));
        long start = System.currentTimeMillis();
        ReviewDecision decision = handler.review(anyRequest());
        long elapsed = System.currentTimeMillis() - start;
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
        // Should have waited at least the delay (with some tolerance)
        assertThat(elapsed).isGreaterThanOrEqualTo(40L);
    }

    @Test
    void autoApproveWithDelay_nullDelay_throwsIllegalArgument() {
        assertThatThrownBy(() -> ReviewHandler.autoApproveWithDelay(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void autoApproveWithDelay_negativeDelay_throwsIllegalArgument() {
        assertThatThrownBy(() -> ReviewHandler.autoApproveWithDelay(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // autoApproveWithDelay -- interrupted path
    // ========================

    @Test
    void autoApproveWithDelay_interruptedDuringSleep_returnsContinueAndRestoresFlag() {
        ReviewHandler handler = ReviewHandler.autoApproveWithDelay(Duration.ofSeconds(60));
        // Interrupt the thread before calling review() so Thread.sleep() throws immediately
        Thread.currentThread().interrupt();
        try {
            ReviewDecision decision = handler.review(anyRequest());
            assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clean up interrupt flag
        }
    }

    // ========================
    // console()
    // ========================

    @Test
    void console_returnsConsoleReviewHandlerInstance() {
        ReviewHandler handler = ReviewHandler.console();
        assertThat(handler).isInstanceOf(ConsoleReviewHandler.class);
    }
}
