package net.agentensemble.web.publisher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.agentensemble.web.protocol.LiveEventEnvelope;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionForwardMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-way {@link LiveEventPublisher} that POSTs each envelope to a hub's HTTP ingress endpoint
 * ({@code POST /api/hub/ingress}). Fire-and-forget: no review fan-in, no reverse channel.
 *
 * <p>{@link #subscribeToReviewDecisions(Consumer)} throws {@link UnsupportedOperationException};
 * pair this publisher only with no-op review handlers (e.g. ensembles without review gates).
 *
 * <p>Used for short-lived or stateless processes where maintaining a persistent WebSocket
 * connection is not justified — at the cost of losing review-gate support.
 */
public final class HttpLiveEventPublisher extends AbstractLiveEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(HttpLiveEventPublisher.class);

    private final URI ingressUri;
    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public HttpLiveEventPublisher(URI ingressUri, ProducerInfo info, MessageSerializer serializer) {
        super(info, serializer);
        this.ingressUri = Objects.requireNonNull(ingressUri, "ingressUri must not be null");
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public void start() {
        running.set(true);
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                httpClient.close();
            } catch (Exception e) {
                log.debug("HTTP publisher {} httpClient.close() failed: {}", info().producerId(), e.getMessage());
            }
        }
    }

    @Override
    public boolean isConnected() {
        return running.get();
    }

    @Override
    public boolean supportsReviewFanIn() {
        return false;
    }

    @Override
    public void subscribeToReviewDecisions(Consumer<ReviewDecisionForwardMessage> subscriber) {
        throw new UnsupportedOperationException(
                "HttpLiveEventPublisher does not support review fan-in (no reverse channel). "
                        + "Use WebSocketLiveEventPublisher for ensembles that have review gates.");
    }

    @Override
    protected void publishEnvelope(LiveEventEnvelope envelope) {
        if (!running.get()) return;
        try {
            String body = serializer().toJson(envelope);
            HttpRequest request = HttpRequest.newBuilder(ingressUri)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(t -> {
                        log.debug("HTTP publisher {} send failed: {}", info().producerId(), t.getMessage());
                        return null;
                    });
        } catch (RuntimeException e) {
            log.debug("HTTP publisher {} send failed: {}", info().producerId(), e.getMessage());
        }
    }
}
