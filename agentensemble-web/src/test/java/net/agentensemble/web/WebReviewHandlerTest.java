package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTimeoutException;
import net.agentensemble.review.ReviewTiming;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebReviewHandler}.
 *
 * <p>Uses {@link AutoResolvingConnectionManager} to immediately complete review futures so
 * that {@code review()} does not block the test thread. Timeout scenarios use a real
 * {@link ConnectionManager} with a very short timeout (10 ms) so the future expires quickly.
 */
class WebReviewHandlerTest {

    private MessageSerializer serializer;
    private ReviewRequest request;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
        // A standard after-execution review request used by most tests.
        request = ReviewRequest.of(
                "Draft a press release for the product launch",
                "FOR IMMEDIATE RELEASE: Company announces major product launch.",
                ReviewTiming.AFTER_EXECUTION,
                Duration.ofMinutes(5));
    }

    // ========================
    // Helper -- ConnectionManager that resolves any registered future immediately
    // ========================

    /**
     * Subclass of {@link ConnectionManager} that completes every registered pending-review
     * future immediately with a pre-configured value. This prevents {@code review()} from
     * blocking and allows synchronous test execution.
     */
    static class AutoResolvingConnectionManager extends ConnectionManager {

        private final String resolveWith;

        AutoResolvingConnectionManager(MessageSerializer serializer, String resolveWith) {
            super(serializer);
            this.resolveWith = resolveWith;
        }

        @Override
        void registerPendingReview(String reviewId, CompletableFuture<String> future) {
            super.registerPendingReview(reviewId, future);
            // Complete the future synchronously before review() reaches future.get()
            resolveReview(reviewId, resolveWith);
        }
    }

    /**
     * Subclass that completes the future exceptionally to exercise the ExecutionException path.
     */
    static class ExceptionallyResolvingConnectionManager extends ConnectionManager {

        private final Throwable cause;

        ExceptionallyResolvingConnectionManager(MessageSerializer serializer, Throwable cause) {
            super(serializer);
            this.cause = cause;
        }

        @Override
        void registerPendingReview(String reviewId, CompletableFuture<String> future) {
            super.registerPendingReview(reviewId, future);
            future.completeExceptionally(cause);
        }
    }

    private WebReviewHandler handlerWith(String resolveWith, OnTimeoutAction onTimeout) {
        ConnectionManager cm = new AutoResolvingConnectionManager(serializer, resolveWith);
        return new WebReviewHandler(cm, serializer, Duration.ofMinutes(5), onTimeout);
    }

    private String decisionJson(String decision, String revisedOutput) {
        return serializer.toJson(new ReviewDecisionMessage("r", decision, revisedOutput));
    }

    // ========================
    // Decision mapping
    // ========================

    @Test
    void review_approveDecision_returnsContinue() {
        ReviewDecision result = handlerWith(decisionJson("approve", null), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.continueExecution());
    }

    @Test
    void review_continueDecision_returnsContinue() {
        ReviewDecision result = handlerWith(decisionJson("continue", null), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.continueExecution());
    }

    @Test
    void review_editDecision_returnsEditWithRevisedOutput() {
        ReviewDecision result = handlerWith(
                        decisionJson("edit", "Revised press release text"), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.edit("Revised press release text"));
    }

    @Test
    void review_editDecision_nullRevisedOutput_returnsEditWithEmptyString() {
        // decision.revisedOutput() is null -- the handler substitutes an empty string
        ReviewDecision result = handlerWith(decisionJson("edit", null), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.edit(""));
    }

    @Test
    void review_exitEarlyDecision_returnsExitEarly() {
        ReviewDecision result = handlerWith(decisionJson("exit_early", null), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.exitEarly());
    }

    @Test
    void review_unknownDecision_treatedAsExitEarly() {
        ReviewDecision result = handlerWith(decisionJson("unknown_value", null), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.exitEarly());
    }

    @Test
    void review_uppercaseApprove_isCaseInsensitiveMatch() {
        ReviewDecision result = handlerWith(decisionJson("APPROVE", null), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.continueExecution());
    }

    @Test
    void review_mixedCaseEdit_isCaseInsensitiveMatch() {
        ReviewDecision result = handlerWith(decisionJson("Edit", "revised"), OnTimeoutAction.CONTINUE)
                .review(request);
        assertThat(result).isEqualTo(ReviewDecision.edit("revised"));
    }

    // ========================
    // Disconnect (empty-string resolution)
    // ========================

    @Test
    void review_emptyStringResolution_onTimeout_continue_returnsContinue() {
        ReviewDecision result = handlerWith("", OnTimeoutAction.CONTINUE).review(request);
        assertThat(result).isEqualTo(ReviewDecision.continueExecution());
    }

    @Test
    void review_emptyStringResolution_onTimeout_exitEarly_returnsExitEarlyTimeout() {
        ReviewDecision result = handlerWith("", OnTimeoutAction.EXIT_EARLY).review(request);
        assertThat(result).isEqualTo(ReviewDecision.exitEarlyTimeout());
    }

    @Test
    void review_emptyStringResolution_onTimeout_fail_throwsReviewTimeoutException() {
        assertThatThrownBy(() -> handlerWith("", OnTimeoutAction.FAIL).review(request))
                .isInstanceOf(ReviewTimeoutException.class);
    }

    // ========================
    // Actual timeout (TimeoutException path)
    // ========================

    @Test
    void review_timeout_onTimeout_continue_returnsContinue() {
        // ConnectionManager never resolves the future → TimeoutException fires after 10 ms
        ConnectionManager cm = new ConnectionManager(serializer);
        WebReviewHandler handler =
                new WebReviewHandler(cm, serializer, Duration.ofMillis(10), OnTimeoutAction.CONTINUE);
        ReviewDecision result = handler.review(request);
        assertThat(result).isEqualTo(ReviewDecision.continueExecution());
    }

    @Test
    void review_timeout_onTimeout_exitEarly_returnsExitEarlyTimeout() {
        ConnectionManager cm = new ConnectionManager(serializer);
        WebReviewHandler handler =
                new WebReviewHandler(cm, serializer, Duration.ofMillis(10), OnTimeoutAction.EXIT_EARLY);
        ReviewDecision result = handler.review(request);
        assertThat(result).isEqualTo(ReviewDecision.exitEarlyTimeout());
    }

    @Test
    void review_timeout_onTimeout_fail_throwsReviewTimeoutException() {
        ConnectionManager cm = new ConnectionManager(serializer);
        WebReviewHandler handler = new WebReviewHandler(cm, serializer, Duration.ofMillis(10), OnTimeoutAction.FAIL);
        assertThatThrownBy(() -> handler.review(request))
                .isInstanceOf(ReviewTimeoutException.class)
                .hasMessageContaining("Draft a press release");
    }

    @Test
    void review_timeout_longDescription_isTruncatedInExceptionMessage() {
        String longDescription = "A".repeat(200);
        ReviewRequest longRequest =
                ReviewRequest.of(longDescription, "output", ReviewTiming.AFTER_EXECUTION, Duration.ofMinutes(5));
        ConnectionManager cm = new ConnectionManager(serializer);
        WebReviewHandler handler = new WebReviewHandler(cm, serializer, Duration.ofMillis(10), OnTimeoutAction.FAIL);
        assertThatThrownBy(() -> handler.review(longRequest))
                .isInstanceOf(ReviewTimeoutException.class)
                .hasMessageContaining("...");
    }

    // ========================
    // Interrupted
    // ========================

    @Test
    void review_interrupted_returnsExitEarly() throws Exception {
        // ConnectionManager never resolves -- handler blocks for up to 30 s
        ConnectionManager cm = new ConnectionManager(serializer);
        WebReviewHandler handler =
                new WebReviewHandler(cm, serializer, Duration.ofSeconds(30), OnTimeoutAction.CONTINUE);

        CompletableFuture<ReviewDecision> resultFuture = new CompletableFuture<>();
        Thread reviewThread = Thread.ofVirtual().start(() -> resultFuture.complete(handler.review(request)));

        // Allow the virtual thread to start blocking, then interrupt it
        Thread.sleep(50);
        reviewThread.interrupt();

        ReviewDecision result = resultFuture.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo(ReviewDecision.exitEarly());
    }

    // ========================
    // ExecutionException
    // ========================

    @Test
    void review_executionException_appliesConfiguredTimeoutAction() {
        ConnectionManager cm =
                new ExceptionallyResolvingConnectionManager(serializer, new RuntimeException("simulated failure"));
        WebReviewHandler handler =
                new WebReviewHandler(cm, serializer, Duration.ofMinutes(5), OnTimeoutAction.CONTINUE);
        ReviewDecision result = handler.review(request);
        assertThat(result).isEqualTo(ReviewDecision.continueExecution());
    }

    @Test
    void review_executionException_onTimeout_exitEarly_returnsExitEarlyTimeout() {
        ConnectionManager cm = new ExceptionallyResolvingConnectionManager(serializer, new RuntimeException("boom"));
        WebReviewHandler handler =
                new WebReviewHandler(cm, serializer, Duration.ofMinutes(5), OnTimeoutAction.EXIT_EARLY);
        ReviewDecision result = handler.review(request);
        assertThat(result).isEqualTo(ReviewDecision.exitEarlyTimeout());
    }

    // ========================
    // Broadcast connects to session
    // ========================

    @Test
    void review_broadcastsReviewRequestedToConnectedSessions() {
        ConnectionManagerTest.MockWsSession session = new ConnectionManagerTest.MockWsSession("s1");
        ConnectionManager cm = new ConnectionManager(serializer);
        cm.onConnect(session);
        session.clearMessages();

        WebReviewHandler handler = new WebReviewHandler(cm, serializer, Duration.ofMillis(5), OnTimeoutAction.CONTINUE);
        handler.review(request);

        // At least one message should have been broadcast (review_requested or review_timed_out)
        assertThat(session.sentMessages()).isNotEmpty();
        boolean hasReviewRequested = session.sentMessages().stream().anyMatch(m -> m.contains("review_requested"));
        assertThat(hasReviewRequested).isTrue();
    }

    @Test
    void review_timedOut_broadcastsReviewTimedOutMessage() {
        ConnectionManagerTest.MockWsSession session = new ConnectionManagerTest.MockWsSession("s1");
        ConnectionManager cm = new ConnectionManager(serializer);
        cm.onConnect(session);
        session.clearMessages();

        WebReviewHandler handler = new WebReviewHandler(cm, serializer, Duration.ofMillis(5), OnTimeoutAction.CONTINUE);
        handler.review(request);

        boolean hasTimedOut = session.sentMessages().stream().anyMatch(m -> m.contains("review_timed_out"));
        assertThat(hasTimedOut).isTrue();
    }

    // ========================
    // Concurrent reviews
    // ========================

    /**
     * Subclass of {@link ConnectionManager} that captures each registered future for manual
     * resolution, and decrements a latch so the test can wait for all concurrent reviews to
     * be registered before resolving them in an arbitrary order.
     *
     * <p>Review IDs are stored in {@code orderedIds} ({@link CopyOnWriteArrayList}) to preserve
     * insertion order, because {@link ConcurrentHashMap#keySet()} does not guarantee ordering.
     */
    static class CapturingConnectionManager extends ConnectionManager {

        private final ConcurrentHashMap<String, CompletableFuture<String>> captured = new ConcurrentHashMap<>();
        private final CopyOnWriteArrayList<String> orderedIds = new CopyOnWriteArrayList<>();
        private final CountDownLatch registeredLatch;

        CapturingConnectionManager(MessageSerializer serializer, int expectedCount) {
            super(serializer);
            this.registeredLatch = new CountDownLatch(expectedCount);
        }

        @Override
        void registerPendingReview(String reviewId, CompletableFuture<String> future) {
            super.registerPendingReview(reviewId, future);
            captured.put(reviewId, future);
            orderedIds.add(reviewId); // insertion-ordered; used for deterministic index access in tests
            registeredLatch.countDown();
        }

        boolean awaitAllRegistered(long timeoutSeconds) throws InterruptedException {
            return registeredLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        /** Returns review IDs in registration order. */
        List<String> capturedReviewIds() {
            return new ArrayList<>(orderedIds);
        }
    }

    @Test
    void review_concurrentReviews_eachFutureResolvesIndependently() throws Exception {
        CapturingConnectionManager cm = new CapturingConnectionManager(serializer, 3);
        WebReviewHandler handler =
                new WebReviewHandler(cm, serializer, Duration.ofSeconds(10), OnTimeoutAction.CONTINUE);

        // Start 3 concurrent review() calls on virtual threads.
        // Virtual threads are used deliberately: review() blocks on future.get(), and submitting
        // blocking tasks to the ForkJoinPool common pool (via supplyAsync) can exhaust the pool
        // on low-parallelism CI agents and cause the test to hang.
        CompletableFuture<ReviewDecision> f1 = new CompletableFuture<>();
        CompletableFuture<ReviewDecision> f2 = new CompletableFuture<>();
        CompletableFuture<ReviewDecision> f3 = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(() -> f1.complete(handler.review(
                        ReviewRequest.of("Task 1", "o1", ReviewTiming.AFTER_EXECUTION, Duration.ofMinutes(5)))));
        Thread.ofVirtual()
                .start(() -> f2.complete(handler.review(
                        ReviewRequest.of("Task 2", "o2", ReviewTiming.AFTER_EXECUTION, Duration.ofMinutes(5)))));
        Thread.ofVirtual()
                .start(() -> f3.complete(handler.review(
                        ReviewRequest.of("Task 3", "o3", ReviewTiming.AFTER_EXECUTION, Duration.ofMinutes(5)))));

        // Wait for all 3 to register their futures before resolving.
        // capturedReviewIds() preserves insertion order (CopyOnWriteArrayList) so index access is
        // deterministic even though review() calls start concurrently.
        assertThat(cm.awaitAllRegistered(5)).isTrue();

        // Resolve in reverse insertion order to verify each future is resolved independently
        List<String> reviewIds = cm.capturedReviewIds();
        cm.resolveReview(reviewIds.get(2), decisionJson("continue", null));
        cm.resolveReview(reviewIds.get(0), decisionJson("continue", null));
        cm.resolveReview(reviewIds.get(1), decisionJson("continue", null));

        // All 3 should complete with the correct decision regardless of resolution order
        assertThat(f1.get(5, TimeUnit.SECONDS)).isEqualTo(ReviewDecision.continueExecution());
        assertThat(f2.get(5, TimeUnit.SECONDS)).isEqualTo(ReviewDecision.continueExecution());
        assertThat(f3.get(5, TimeUnit.SECONDS)).isEqualTo(ReviewDecision.continueExecution());
    }

    // ========================
    // Unknown reviewId (late decision after timeout)
    // ========================

    @Test
    void resolveReview_unknownReviewId_afterTimeout_isIgnoredWithoutException() {
        // Capture the reviewId so we can call resolveReview on it after it has already timed out
        AtomicReference<String> capturedId = new AtomicReference<>();
        ConnectionManager cm = new ConnectionManager(serializer) {
            @Override
            void registerPendingReview(String reviewId, CompletableFuture<String> future) {
                super.registerPendingReview(reviewId, future);
                capturedId.set(reviewId);
            }
        };

        WebReviewHandler handler =
                new WebReviewHandler(cm, serializer, Duration.ofMillis(10), OnTimeoutAction.CONTINUE);
        handler.review(request); // times out, removes reviewId from pendingReviews

        // Simulates a late browser decision arriving after the timeout has already cleared
        // the reviewId -- must be silently ignored with no exception
        assertThatCode(() -> cm.resolveReview(capturedId.get(), decisionJson("continue", null)))
                .doesNotThrowAnyException();
    }
}
