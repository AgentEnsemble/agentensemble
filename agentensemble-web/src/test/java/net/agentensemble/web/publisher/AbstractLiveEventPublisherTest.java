package net.agentensemble.web.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.LlmIterationCompletedMessage;
import net.agentensemble.web.protocol.LlmIterationStartedMessage;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import net.agentensemble.web.protocol.TaskStartedMessage;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link AbstractLiveEventPublisher}'s shared scaffolding: envelope construction +
 * sequence numbering on {@code accept}, the no-op snapshot/iteration methods (hub owns that
 * state), and the malformed-payload drop path.
 */
class AbstractLiveEventPublisherTest {

    private static final class Capturing extends AbstractLiveEventPublisher {
        final List<LiveEventEnvelope> envelopes = new ArrayList<>();

        Capturing() {
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
        public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> subscriber) {}

        @Override
        protected void publishEnvelope(LiveEventEnvelope envelope) {
            envelopes.add(envelope);
        }
    }

    @Test
    void constructor_rejectsNullInfoOrSerializer() {
        assertThatThrownBy(() -> new AbstractLiveEventPublisher(null, new MessageSerializer()) {
                    @Override
                    public void start() {}

                    @Override
                    public void stop() {}

                    @Override
                    public boolean isConnected() {
                        return false;
                    }

                    @Override
                    public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> s) {}

                    @Override
                    protected void publishEnvelope(LiveEventEnvelope envelope) {}
                })
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AbstractLiveEventPublisher(ProducerInfo.of("p", "s"), null) {
                    @Override
                    public void start() {}

                    @Override
                    public void stop() {}

                    @Override
                    public boolean isConnected() {
                        return false;
                    }

                    @Override
                    public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> s) {}

                    @Override
                    protected void publishEnvelope(LiveEventEnvelope envelope) {}
                })
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void accept_stampsMonotonicSequenceAndForwardsEnvelope() {
        Capturing pub = new Capturing();
        MessageSerializer s = new MessageSerializer();
        pub.accept(s.toJson(new TaskStartedMessage(1, 1, "a", "A", Instant.now())));
        pub.accept(s.toJson(new TaskStartedMessage(2, 2, "b", "B", Instant.now())));
        assertThat(pub.envelopes).hasSize(2);
        assertThat(pub.envelopes.get(0).sequence()).isEqualTo(1L);
        assertThat(pub.envelopes.get(1).sequence()).isEqualTo(2L);
        assertThat(pub.envelopes.get(0).producer().producerId()).isEqualTo("p1");
    }

    @Test
    void accept_dropsUnparseablePayload_silently() {
        Capturing pub = new Capturing();
        pub.accept("not json at all");
        // The malformed payload is logged at WARN and dropped; no envelope is published.
        assertThat(pub.envelopes).isEmpty();
    }

    @Test
    void snapshotAndIterationMethods_areNoOps_andDoNotThrow() {
        // The hub owns the snapshot store and iteration ring buffer; the publisher-side
        // sink methods are no-ops. We exercise them to ensure no NPEs and to claim line
        // coverage on the AbstractLiveEventPublisher implementations.
        Capturing pub = new Capturing();
        pub.appendToSnapshot("{}");
        pub.noteEnsembleStarted("run-1", Instant.now());
        pub.clearIterationSnapshots();
        pub.recordIterationStarted("k", new LlmIterationStartedMessage("a", "t", 1, List.of(), 0));
        pub.recordIterationCompleted(
                "k", new LlmIterationCompletedMessage("a", "t", 1, "FINAL", "r", List.of(), 1L, 1L, 1L));
        // No assertion needed; reaching this line means none threw.
        assertThat(pub.info().producerId()).isEqualTo("p1");
    }

    @Test
    void supportsReviewFanIn_defaultsTrue() {
        assertThat(new Capturing().supportsReviewFanIn()).isTrue();
    }
}
