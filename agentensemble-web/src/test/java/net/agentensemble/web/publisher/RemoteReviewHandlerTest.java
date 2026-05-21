package net.agentensemble.web.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTimeoutException;
import net.agentensemble.review.ReviewTiming;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RemoteReviewHandler}. Exercises every decision branch (continue, edit,
 * exitEarly), every timeout action (CONTINUE, EXIT_EARLY, FAIL), and the per-request override
 * path so the handler honors {@code request.timeout()} / {@code request.onTimeoutAction()}.
 */
class RemoteReviewHandlerTest {

    /** Test double: captures published JSON and exposes the review subscriber for resolution. */
    private static final class CapturingPublisher extends AbstractLiveEventPublisher implements LiveEventPublisher {
        final List<String> published = new CopyOnWriteArrayList<>();
        final AtomicReference<Consumer<ReviewDecisionForwardMessage>> subscriber = new AtomicReference<>();

        CapturingPublisher() {
            super(ProducerInfo.of("p1", "svc"), new MessageSerializer());
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> s) {
            subscriber.set(s);
        }

        @Override
        protected void publishEnvelope(LiveEventEnvelope envelope) {
            published.add(serializer().toJson(envelope.message()));
        }
    }

    private RemoteReviewHandler newHandler(OnTimeoutAction onTimeout) {
        return newHandler(onTimeout, Duration.ofMillis(50));
    }

    private RemoteReviewHandler newHandler(OnTimeoutAction onTimeout, Duration timeout) {
        return new RemoteReviewHandler(new CapturingPublisher(), new MessageSerializer(), timeout, onTimeout);
    }

    @Test
    void approveDecision_maps_continueExecution() throws Exception {
        CapturingPublisher pub = new CapturingPublisher();
        RemoteReviewHandler handler =
                new RemoteReviewHandler(pub, new MessageSerializer(), Duration.ofSeconds(5), OnTimeoutAction.CONTINUE);
        CompletableFuture<ReviewDecision> result = CompletableFuture.supplyAsync(
                () -> handler.review(ReviewRequest.of("t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofSeconds(5))));
        // Wait for the request to be published, then send a matching decision back.
        for (int i = 0; i < 30 && pub.published.isEmpty(); i++) Thread.sleep(50);
        String reviewId = extractReviewId(pub.published.get(0));
        pub.subscriber
                .get()
                .accept(new ReviewDecisionForwardMessage(
                        reviewId,
                        new MessageSerializer().toJson(new ReviewDecisionMessage(reviewId, "approve", null))));
        assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void editDecision_maps_editDecisionWithRevisedOutput() throws Exception {
        CapturingPublisher pub = new CapturingPublisher();
        RemoteReviewHandler handler =
                new RemoteReviewHandler(pub, new MessageSerializer(), Duration.ofSeconds(5), OnTimeoutAction.CONTINUE);
        CompletableFuture<ReviewDecision> result = CompletableFuture.supplyAsync(
                () -> handler.review(ReviewRequest.of("t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofSeconds(5))));
        for (int i = 0; i < 30 && pub.published.isEmpty(); i++) Thread.sleep(50);
        String reviewId = extractReviewId(pub.published.get(0));
        pub.subscriber
                .get()
                .accept(new ReviewDecisionForwardMessage(
                        reviewId,
                        new MessageSerializer().toJson(new ReviewDecisionMessage(reviewId, "edit", "revised"))));
        ReviewDecision decision = result.get(2, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOfSatisfying(ReviewDecision.Edit.class, e -> assertThat(e.revisedOutput())
                .isEqualTo("revised"));
    }

    @Test
    void unknownDecision_maps_exitEarly() throws Exception {
        CapturingPublisher pub = new CapturingPublisher();
        RemoteReviewHandler handler =
                new RemoteReviewHandler(pub, new MessageSerializer(), Duration.ofSeconds(5), OnTimeoutAction.CONTINUE);
        CompletableFuture<ReviewDecision> result = CompletableFuture.supplyAsync(
                () -> handler.review(ReviewRequest.of("t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofSeconds(5))));
        for (int i = 0; i < 30 && pub.published.isEmpty(); i++) Thread.sleep(50);
        String reviewId = extractReviewId(pub.published.get(0));
        pub.subscriber
                .get()
                .accept(new ReviewDecisionForwardMessage(
                        reviewId, new MessageSerializer().toJson(new ReviewDecisionMessage(reviewId, "wat", null))));
        assertThat(result.get(2, TimeUnit.SECONDS)).isInstanceOf(ReviewDecision.ExitEarly.class);
    }

    @Test
    void timeout_onTimeoutContinue_returnsContinue() {
        RemoteReviewHandler handler = newHandler(OnTimeoutAction.CONTINUE);
        // Use the 6-arg factory so the request's onTimeoutAction does not default to
        // EXIT_EARLY -- the 4-arg ReviewRequest.of() factory defaults to EXIT_EARLY which
        // would override the handler default.
        ReviewDecision decision = handler.review(ReviewRequest.of(
                "t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(50), OnTimeoutAction.CONTINUE, null));
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void timeout_onTimeoutExitEarly_returnsExitEarlyTimedOut() {
        RemoteReviewHandler handler = newHandler(OnTimeoutAction.EXIT_EARLY);
        ReviewDecision decision = handler.review(ReviewRequest.of(
                "t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(50), OnTimeoutAction.EXIT_EARLY, null));
        assertThat(decision).isInstanceOfSatisfying(ReviewDecision.ExitEarly.class, e -> assertThat(e.timedOut())
                .isTrue());
    }

    @Test
    void timeout_onTimeoutFail_throwsReviewTimeoutException() {
        RemoteReviewHandler handler = newHandler(OnTimeoutAction.FAIL);
        assertThatThrownBy(() -> handler.review(ReviewRequest.of(
                        "t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(50), OnTimeoutAction.FAIL, null)))
                .isInstanceOf(ReviewTimeoutException.class)
                .hasMessageContaining("Remote review timed out");
    }

    @Test
    void perRequestOverride_isHonored_overHandlerDefaults() {
        // Handler is configured with FAIL + a long timeout; the request overrides to CONTINUE +
        // a short timeout. The applied decision must follow the request, not the handler.
        RemoteReviewHandler handler = newHandler(OnTimeoutAction.FAIL, Duration.ofSeconds(10));
        ReviewDecision decision = handler.review(ReviewRequest.of(
                "t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(50), OnTimeoutAction.CONTINUE, null));
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    private static String extractReviewId(String json) {
        int idx = json.indexOf("\"reviewId\":\"");
        if (idx < 0) throw new IllegalStateException("no reviewId in " + json);
        int start = idx + "\"reviewId\":\"".length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
