package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.Ensemble;
import net.agentensemble.ensemble.EnsembleOutput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for Ensemble Control API Phase 3/5 REST endpoints:
 * - {@code POST /api/runs/{runId}/cancel}
 * - {@code POST /api/runs/{runId}/model} (no catalog configured)
 * - {@code POST /api/reviews/{reviewId}}
 * - {@code GET /api/reviews}
 * - {@code POST /api/runs/{runId}/inject}
 * - {@code POST /api/tools/{name}/invoke}
 *
 * <p>Tests requiring a model catalog are in {@link RunControlModelRestTest}.
 */
class RunControlRestTest {

    private WebDashboard dashboard;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private Ensemble mockEnsemble;
    private EnsembleOutput mockOutput;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        mockEnsemble = mock(Ensemble.class);
        mockOutput = mock(EnsembleOutput.class);

        when(mockEnsemble.getTasks()).thenReturn(List.of());
        when(mockOutput.getTaskOutputs()).thenReturn(List.of());
        when(mockOutput.getMetrics()).thenReturn(null);
        when(mockEnsemble.withAdditionalListener(any())).thenReturn(mockEnsemble);

        dashboard = WebDashboard.builder()
                .port(0)
                .host("0.0.0.0")
                .maxConcurrentRuns(5)
                .build();
        dashboard.start();
        dashboard.setEnsemble(mockEnsemble);

        port = dashboard.actualPort();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        dashboard.stop();
    }

    // ========================
    // Helpers
    // ========================

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String submitRun() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch waitLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startLatch.countDown();
            waitLatch.await(10, TimeUnit.SECONDS);
            return mockOutput;
        });

        HttpResponse<String> resp = post("/api/runs", "{}");
        assertThat(resp.statusCode()).isEqualTo(202);
        JsonNode body = objectMapper.readTree(resp.body());
        String runId = body.get("runId").asText();

        // Wait until run is executing so cancel/inject works
        startLatch.await(3, TimeUnit.SECONDS);
        return runId;
    }

    // ========================
    // POST /api/runs/{runId}/cancel
    // ========================

    @Test
    void cancelRun_unknownRunId_returns404() throws Exception {
        HttpResponse<String> resp = post("/api/runs/run-unknown/cancel", "");
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("RUN_NOT_FOUND");
    }

    @Test
    void cancelRun_activeRun_returns200Cancelling() throws Exception {
        String runId = submitRun();

        HttpResponse<String> resp = post("/api/runs/" + runId + "/cancel", "");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("runId").asText()).isEqualTo(runId);
        assertThat(body.get("status").asText()).isEqualTo("CANCELLING");
    }

    @Test
    void cancelRun_alreadyCancelled_returnsValidStatus() throws Exception {
        String runId = submitRun();

        // First cancel
        post("/api/runs/" + runId + "/cancel", "");
        // Second cancel -- while running: status is still RUNNING → CANCELLING again
        HttpResponse<String> resp = post("/api/runs/" + runId + "/cancel", "");
        assertThat(resp.statusCode()).isIn(200, 409);
    }

    // ========================
    // POST /api/runs/{runId}/model -- no model catalog configured
    // ========================

    @Test
    void switchModel_noModelCatalog_returns503() throws Exception {
        String runId = submitRun();
        HttpResponse<String> resp = post("/api/runs/" + runId + "/model", "{\"model\":\"haiku\"}");
        assertThat(resp.statusCode()).isEqualTo(503);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void switchModel_noModelCatalog_unknownRunId_returns503() throws Exception {
        // No catalog → 503 regardless of runId
        HttpResponse<String> resp = post("/api/runs/run-unknown/model", "{\"model\":\"haiku\"}");
        assertThat(resp.statusCode()).isEqualTo(503);
    }

    // ========================
    // POST /api/reviews/{reviewId}
    // ========================

    @Test
    void reviewDecision_unknownReviewId_returns404() throws Exception {
        HttpResponse<String> resp = post("/api/reviews/rev-unknown", "{\"decision\":\"CONTINUE\"}");
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("REVIEW_NOT_FOUND");
    }

    @Test
    void reviewDecision_missingDecisionField_returns404BecauseReviewUnknown() throws Exception {
        // hasPendingReview is checked first, so 404 even if body is missing decision
        HttpResponse<String> resp = post("/api/reviews/rev-unknown", "{}");
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    // ========================
    // GET /api/reviews
    // ========================

    @Test
    void listReviews_noPendingReviews_returnsEmptyList() throws Exception {
        HttpResponse<String> resp = get("/api/reviews");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("reviews").isArray()).isTrue();
        assertThat(body.get("reviews").size()).isEqualTo(0);
        assertThat(body.get("total").asInt()).isEqualTo(0);
    }

    @Test
    void listReviews_withRunIdFilter_returnsEmptyForUnknownRun() throws Exception {
        HttpResponse<String> resp = get("/api/reviews?runId=run-xyz");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("reviews").size()).isEqualTo(0);
    }

    // ========================
    // POST /api/runs/{runId}/inject
    // ========================

    @Test
    void injectDirective_unknownRunId_returns404() throws Exception {
        HttpResponse<String> resp = post("/api/runs/run-unknown/inject", "{\"content\":\"Focus on security\"}");
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("RUN_NOT_FOUND");
    }

    @Test
    void injectDirective_activeRun_returns200WithDirectiveId() throws Exception {
        // Need mockEnsemble.getDirectiveStore() to return a real store
        net.agentensemble.directive.DirectiveStore store = new net.agentensemble.directive.DirectiveStore();
        when(mockEnsemble.getDirectiveStore()).thenReturn(store);

        String runId = submitRun();

        HttpResponse<String> resp =
                post("/api/runs/" + runId + "/inject", "{\"content\":\"Focus on security\",\"target\":\"researcher\"}");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.has("directiveId")).isTrue();
        assertThat(body.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void injectDirective_missingContentField_returns400() throws Exception {
        String runId = submitRun();
        HttpResponse<String> resp = post("/api/runs/" + runId + "/inject", "{\"target\":\"agent\"}");
        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("BAD_REQUEST");
    }

    // ========================
    // GET /api/reviews and POST /api/reviews/{reviewId} -- with actual pending review
    // ========================

    @Test
    void listReviews_withPendingReview_returnsReviewDetails() throws Exception {
        // Open a review on a virtual thread (it blocks until resolved)
        java.util.concurrent.CountDownLatch reviewOpenedLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch reviewResolvedLatch = new java.util.concurrent.CountDownLatch(1);
        net.agentensemble.review.ReviewRequest request = net.agentensemble.review.ReviewRequest.of(
                "Research task",
                "Output text",
                net.agentensemble.review.ReviewTiming.AFTER_EXECUTION,
                java.time.Duration.ofSeconds(10));

        Thread.startVirtualThread(() -> {
            reviewOpenedLatch.countDown();
            try {
                dashboard.reviewHandler().review(request);
            } catch (Exception ignored) {
                // Review might be cancelled when dashboard stops
            }
            reviewResolvedLatch.countDown();
        });

        // Wait for review to be opened
        assertThat(reviewOpenedLatch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // let review register

        // GET /api/reviews should now return the pending review
        HttpResponse<String> listResp = get("/api/reviews");
        assertThat(listResp.statusCode()).isEqualTo(200);
        JsonNode listBody = objectMapper.readTree(listResp.body());
        assertThat(listBody.get("total").asInt()).isGreaterThanOrEqualTo(1);

        // Get the reviewId from the list
        JsonNode reviews = listBody.get("reviews");
        String reviewId = reviews.get(0).get("reviewId").asText();
        assertThat(reviewId).isNotBlank();
        assertThat(reviews.get(0).get("taskDescription").asText()).isEqualTo("Research task");

        // POST /api/reviews/{reviewId} to resolve it
        HttpResponse<String> resolveResp = post("/api/reviews/" + reviewId, "{\"decision\":\"CONTINUE\"}");
        assertThat(resolveResp.statusCode()).isEqualTo(200);
        JsonNode resolveBody = objectMapper.readTree(resolveResp.body());
        assertThat(resolveBody.get("status").asText()).isEqualTo("APPLIED");

        // Wait for review thread to complete
        assertThat(reviewResolvedLatch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    // ========================
    // POST /api/tools/{name}/invoke
    // ========================

    @Test
    void invokeToolNoToolCatalog_returns503() throws Exception {
        HttpResponse<String> resp = post("/api/tools/calculator/invoke", "{\"input\":\"2+2\"}");
        assertThat(resp.statusCode()).isEqualTo(503);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("NOT_CONFIGURED");
    }
}
