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
 * Integration tests for the SSE event stream endpoint {@code GET /api/runs/{runId}/events}.
 *
 * <p>Tests the completed-run replay path and the unknown-run error path via HTTP.
 * The SSE response body is checked for the expected SSE event text.
 */
class SseHandlerIntegrationTest {

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

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> getSse(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Accept", "text/event-stream")
                .GET()
                .timeout(Duration.ofSeconds(8))
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

    // ========================
    // SSE: unknown run ID returns 200 with error body
    // ========================

    @Test
    void sseEvents_unknownRunId_returns200WithErrorContent() throws Exception {
        HttpResponse<String> resp = getSse("/api/runs/run-unknown/events");
        // SSE responses always return 200; the error is in the event body
        assertThat(resp.statusCode()).isEqualTo(200);
        // The response body should contain our error event data
        String body = resp.body();
        assertThat(body).isNotBlank();
        // Should contain RUN_NOT_FOUND in the SSE data
        assertThat(body).contains("RUN_NOT_FOUND");
    }

    // ========================
    // SSE: completed run streams stored events
    // ========================

    @Test
    void sseEvents_completedRun_returns200() throws Exception {
        // Submit and complete a run synchronously
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            doneLatch.countDown();
            return mockOutput;
        });

        HttpResponse<String> submitResp = post("/api/runs", "{}");
        assertThat(submitResp.statusCode()).isEqualTo(202);
        JsonNode submitBody = objectMapper.readTree(submitResp.body());
        String runId = submitBody.get("runId").asText();

        // Wait for run to complete
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300); // give state time to update

        // Connect to SSE endpoint for completed run -- it should return and close
        HttpResponse<String> sseResp = getSse("/api/runs/" + runId + "/events");
        assertThat(sseResp.statusCode()).isEqualTo(200);
        // Should contain a run_result event with the runId
        assertThat(sseResp.body()).contains(runId);
    }
}
