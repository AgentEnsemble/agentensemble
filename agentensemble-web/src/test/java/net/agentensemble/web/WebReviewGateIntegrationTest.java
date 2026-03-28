package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTiming;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.ReviewDecisionMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link WebReviewHandler}: end-to-end with a real embedded Javalin
 * server and a programmatic Java WebSocket test client.
 *
 * <p>Each test starts a {@link WebDashboard} on an ephemeral port, connects a Java
 * {@link HttpClient} WebSocket client, starts a {@code review()} call on a virtual thread
 * (which blocks waiting for a browser decision), then has the test client send a
 * {@code review_decision} message and asserts on the returned {@link ReviewDecision}.
 *
 * <p>The timeout test verifies that when no decision arrives, the configured
 * {@link OnTimeoutAction} is applied and a {@code review_timed_out} message is broadcast.
 *
 * <p>All dashboards are bound to {@code localhost} to exercise the default origin policy.
 * The test clients supply an {@code Origin: http://localhost} header because Java's
 * {@link HttpClient} does not send an Origin header by default, which the server's
 * loopback-origin validation would otherwise reject.
 */
class WebReviewGateIntegrationTest {

    private WebDashboard dashboard;
    private WebSocket ws;
    private MessageSerializer serializer;

    @BeforeEach
    void setUp() throws Exception {
        serializer = new MessageSerializer();
        // localhost binding exercises the default origin policy; the test client supplies
        // an Origin header so the server's loopback-origin check accepts the connection.
        dashboard = WebDashboard.builder()
                .port(0)
                .host("localhost")
                .reviewTimeout(Duration.ofSeconds(5))
                .build();
        dashboard.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort close
            }
        }
        if (dashboard != null) {
            dashboard.stop();
        }
    }

    // ========================
    // Helpers
    // ========================

    private ReviewRequest standardRequest() {
        return ReviewRequest.of(
                "Draft a press release for product launch",
                "FOR IMMEDIATE RELEASE: Company announces major product.",
                ReviewTiming.AFTER_EXECUTION,
                Duration.ofSeconds(5));
    }

    /**
     * Connects a WebSocket client to the dashboard, waits for the initial {@code hello}
     * message (confirming the server has registered the session), and returns the client
     * together with a {@link CompletableFuture} that completes with the {@code reviewId}
     * string when a {@code review_requested} message arrives.
     */
    private record ConnectedClient(WebSocket webSocket, CompletableFuture<String> reviewId) {}

    private ConnectedClient connectAndCapture() throws Exception {
        CountDownLatch helloLatch = new CountDownLatch(1);
        CompletableFuture<String> reviewIdFuture = new CompletableFuture<>();

        HttpClient client = HttpClient.newHttpClient();
        WebSocket webSocket = client.newWebSocketBuilder()
                // Origin header required: the server enforces loopback-origin validation
                // when bound to localhost, and Java's HttpClient does not send Origin by default.
                .header("Origin", "http://localhost")
                .buildAsync(URI.create("ws://localhost:" + dashboard.actualPort() + "/ws"), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(20);
                    }

                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        if (last) {
                            String msg = data.toString();
                            if (msg.contains("\"type\":\"hello\"")) {
                                helloLatch.countDown();
                            } else if (msg.contains("\"type\":\"review_requested\"")) {
                                reviewIdFuture.complete(extractReviewId(msg));
                            }
                        }
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        assertThat(helloLatch.await(5, TimeUnit.SECONDS))
                .as("WebSocket client did not receive hello within timeout")
                .isTrue();
        return new ConnectedClient(webSocket, reviewIdFuture);
    }

    /**
     * Serializes a {@link ReviewDecisionMessage} to JSON, including the {@code "type"}
     * discriminator field required by the server's polymorphic {@link net.agentensemble.web.protocol.ClientMessage}
     * deserialization.
     */
    private String decisionJson(String reviewId, String decision, String revisedOutput) {
        return serializer.toJson(new ReviewDecisionMessage(reviewId, decision, revisedOutput));
    }

    /**
     * Extracts the {@code reviewId} value from a {@code review_requested} JSON message
     * using simple string parsing. Avoids a compile-time dependency on the polymorphic
     * deserializer configuration.
     */
    private static String extractReviewId(String json) {
        String key = "\"reviewId\":\"";
        int start = json.indexOf(key) + key.length();
        int end = json.indexOf("\"", start);
        return json.substring(start, end);
    }

    // ========================
    // Approve / continue flow
    // ========================

    @Test
    void reviewGate_continueDecision_returnsReviewDecisionContinue() throws Exception {
        ConnectedClient cc = connectAndCapture();
        ws = cc.webSocket();

        // Start review() on a virtual thread -- it blocks until the browser sends a decision
        CompletableFuture<ReviewDecision> decisionFuture = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(() -> decisionFuture.complete(dashboard.reviewHandler().review(standardRequest())));

        // Wait for the server to broadcast review_requested, then send a CONTINUE decision
        String reviewId = cc.reviewId().get(5, TimeUnit.SECONDS);
        ws.sendText(decisionJson(reviewId, "CONTINUE", null), true).get(5, TimeUnit.SECONDS);

        ReviewDecision result = decisionFuture.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo(ReviewDecision.continueExecution());
    }

    // ========================
    // Edit flow
    // ========================

    @Test
    void reviewGate_editDecision_returnsEditWithRevisedOutput() throws Exception {
        ConnectedClient cc = connectAndCapture();
        ws = cc.webSocket();

        CompletableFuture<ReviewDecision> decisionFuture = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(() -> decisionFuture.complete(dashboard.reviewHandler().review(standardRequest())));

        String reviewId = cc.reviewId().get(5, TimeUnit.SECONDS);
        ws.sendText(decisionJson(reviewId, "EDIT", "Revised press release with corrections."), true)
                .get(5, TimeUnit.SECONDS);

        ReviewDecision result = decisionFuture.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo(ReviewDecision.edit("Revised press release with corrections."));
    }

    // ========================
    // Exit-early flow
    // ========================

    @Test
    void reviewGate_exitEarlyDecision_returnsExitEarly() throws Exception {
        ConnectedClient cc = connectAndCapture();
        ws = cc.webSocket();

        CompletableFuture<ReviewDecision> decisionFuture = new CompletableFuture<>();
        Thread.ofVirtual()
                .start(() -> decisionFuture.complete(dashboard.reviewHandler().review(standardRequest())));

        String reviewId = cc.reviewId().get(5, TimeUnit.SECONDS);
        ws.sendText(decisionJson(reviewId, "EXIT_EARLY", null), true).get(5, TimeUnit.SECONDS);

        ReviewDecision result = decisionFuture.get(5, TimeUnit.SECONDS);
        assertThat(result).isEqualTo(ReviewDecision.exitEarly());
    }

    // ========================
    // Timeout flow
    // ========================

    @Test
    void reviewGate_timeout_continueAction_returnsContinueAndBroadcastsReviewTimedOut() throws Exception {
        // Create a separate dashboard with a very short timeout to trigger the timeout path quickly
        WebDashboard shortTimeoutDashboard = WebDashboard.builder()
                .port(0)
                .host("localhost")
                .reviewTimeout(Duration.ofMillis(50))
                .onTimeout(OnTimeoutAction.CONTINUE)
                .build();
        shortTimeoutDashboard.start();

        CountDownLatch helloLatch = new CountDownLatch(1);
        CountDownLatch timedOutLatch = new CountDownLatch(1);

        HttpClient client = HttpClient.newHttpClient();
        WebSocket shortWs = client.newWebSocketBuilder()
                .header("Origin", "http://localhost")
                .buildAsync(
                        URI.create("ws://localhost:" + shortTimeoutDashboard.actualPort() + "/ws"),
                        new WebSocket.Listener() {
                            @Override
                            public void onOpen(WebSocket webSocket) {
                                webSocket.request(10);
                            }

                            @Override
                            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                if (last) {
                                    String msg = data.toString();
                                    if (msg.contains("\"type\":\"hello\"")) {
                                        helloLatch.countDown();
                                    } else if (msg.contains("\"type\":\"review_timed_out\"")) {
                                        timedOutLatch.countDown();
                                    }
                                }
                                webSocket.request(1);
                                return null;
                            }
                        })
                .get(10, TimeUnit.SECONDS);

        try {
            assertThat(helloLatch.await(5, TimeUnit.SECONDS))
                    .as("WebSocket client did not receive hello within timeout")
                    .isTrue();

            // Start review() on a virtual thread -- no decision is sent, so it will time out.
            // Use a short-timeout request so the request-level timeout fires quickly.
            ReviewRequest shortTimeoutRequest = ReviewRequest.of(
                    "Draft a press release for product launch",
                    "FOR IMMEDIATE RELEASE: Company announces major product.",
                    ReviewTiming.AFTER_EXECUTION,
                    Duration.ofMillis(50));
            CompletableFuture<ReviewDecision> decisionFuture = new CompletableFuture<>();
            Thread.ofVirtual()
                    .start(() -> decisionFuture.complete(
                            shortTimeoutDashboard.reviewHandler().review(shortTimeoutRequest)));

            // review() must return CONTINUE after the 50 ms timeout expires
            ReviewDecision result = decisionFuture.get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo(ReviewDecision.continueExecution());

            // The server must have broadcast review_timed_out to connected clients
            assertThat(timedOutLatch.await(2, TimeUnit.SECONDS))
                    .as("review_timed_out message was not broadcast within timeout")
                    .isTrue();
        } finally {
            try {
                shortWs.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best-effort close
            }
            shortTimeoutDashboard.stop();
        }
    }
}
