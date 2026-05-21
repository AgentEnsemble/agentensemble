package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.function.Consumer;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTiming;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import net.agentensemble.web.publisher.AbstractLiveEventPublisher;
import net.agentensemble.web.publisher.LiveEventPublisher;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the publisher capability gating: when a {@link LiveEventPublisher}
 * reports {@code supportsReviewFanIn() == false} (e.g. HTTP transport), wiring it into a
 * {@link WebDashboard} must succeed and only fail clearly when a review gate actually fires.
 *
 * <p>Pre-fix, the publisher's {@code subscribeToReviewDecisions} threw
 * {@link UnsupportedOperationException} at dashboard construction even for ensembles that
 * never used review gates.
 */
class WebDashboardPublisherUnsupportedReviewTest {

    @Test
    void httpStylePublisher_buildsDashboardAndFailsOnlyOnActualReview() {
        MessageSerializer serializer = new MessageSerializer();
        LiveEventPublisher publisher = new AbstractLiveEventPublisher(ProducerInfo.of("p1", "svc"), serializer) {
            @Override
            public void start() {}

            @Override
            public void stop() {}

            @Override
            public boolean isConnected() {
                return true;
            }

            @Override
            public boolean supportsReviewFanIn() {
                return false;
            }

            @Override
            public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> subscriber) {
                throw new UnsupportedOperationException("one-way transport");
            }

            @Override
            protected void publishEnvelope(LiveEventEnvelope envelope) {
                // Drop on the floor for the test.
            }
        };

        WebDashboard dashboard = WebDashboard.builder()
                .port(0)
                .publisher(publisher)
                .reviewTimeout(Duration.ofMillis(100))
                .onTimeout(OnTimeoutAction.CONTINUE)
                .build();

        assertThat(dashboard.reviewHandler()).isNotNull();

        assertThatThrownBy(() -> dashboard
                        .reviewHandler()
                        .review(ReviewRequest.of("t", "o", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(50))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support review fan-in");
    }
}
