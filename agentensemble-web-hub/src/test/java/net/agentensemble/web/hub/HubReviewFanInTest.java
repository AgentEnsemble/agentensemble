package net.agentensemble.web.hub;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTiming;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ProducerInfo;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import net.agentensemble.web.publisher.RemoteReviewHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the hub-orchestrated review fan-in: a publisher-side {@link RemoteReviewHandler}
 * blocks on a review gate, the hub broadcasts the review request to browsers, a browser submits
 * a {@code review_decision}, and the hub forwards the decision back to the originating
 * publisher so its blocked future completes with the expected {@link ReviewDecision}.
 */
class HubReviewFanInTest {

    private LiveEventHub hub;
    private WebSocket browser;
    private final MessageSerializer serializer = new MessageSerializer();

    @BeforeEach
    void setUp() {
        hub = LiveEventHub.builder().port(0).host("0.0.0.0").build();
        hub.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (browser != null) {
            try {
                browser.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
        }
        if (hub != null) hub.stop();
    }

    @Test
    void reviewDecisionRoundTrip_completesPublisherFuture() throws Exception {
        InMemoryLiveEventPublisher publisher =
                new InMemoryLiveEventPublisher(hub, ProducerInfo.of("p1", "svc"), serializer);
        publisher.start();

        RemoteReviewHandler handler =
                new RemoteReviewHandler(publisher, serializer, Duration.ofSeconds(5), OnTimeoutAction.CONTINUE);

        // Connect a browser that listens for review_requested and replies with a decision.
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch reviewSeen = new CountDownLatch(1);
        browser = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + hub.actualPort() + "/ws"), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            String msg = buf.toString();
                            buf.setLength(0);
                            received.add(msg);
                            if (msg.contains("\"type\":\"event\"") && msg.contains("review_requested")) {
                                // Find the reviewId inside the inner message and reply.
                                int idx = msg.indexOf("\"reviewId\":\"");
                                if (idx > 0) {
                                    int start = idx + "\"reviewId\":\"".length();
                                    int end = msg.indexOf('"', start);
                                    String reviewId = msg.substring(start, end);
                                    String decisionJson =
                                            serializer.toJson(new ReviewDecisionMessage(reviewId, "continue", null));
                                    webSocket.sendText(decisionJson, true);
                                }
                                reviewSeen.countDown();
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);

        // Run the review on a worker so we can await its result.
        CompletableFuture<ReviewDecision> result = CompletableFuture.supplyAsync(() -> handler.review(
                ReviewRequest.of("Approve me", "an output", ReviewTiming.AFTER_EXECUTION, Duration.ofSeconds(5))));

        assertThat(reviewSeen.await(5, TimeUnit.SECONDS))
                .as("browser must have observed review_requested")
                .isTrue();

        ReviewDecision decision = result.get(5, TimeUnit.SECONDS);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);

        publisher.stop();
    }
}
