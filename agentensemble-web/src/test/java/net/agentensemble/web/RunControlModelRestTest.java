package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
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
 * Integration tests for {@code POST /api/runs/{runId}/model} with a configured ModelCatalog.
 *
 * <p>These tests require a model catalog to be configured at dashboard creation time, so
 * they live in a separate test class with its own setup.
 */
class RunControlModelRestTest {

    private WebDashboard dashboard;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private Ensemble mockEnsemble;
    private EnsembleOutput mockOutput;
    private ChatModel mockModel;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        mockEnsemble = mock(Ensemble.class);
        mockOutput = mock(EnsembleOutput.class);
        mockModel = mock(ChatModel.class);

        when(mockEnsemble.getTasks()).thenReturn(List.of());
        when(mockOutput.getTaskOutputs()).thenReturn(List.of());
        when(mockOutput.getMetrics()).thenReturn(null);
        when(mockEnsemble.withAdditionalListener(any())).thenReturn(mockEnsemble);

        dashboard = WebDashboard.builder()
                .port(0)
                .host("0.0.0.0")
                .maxConcurrentRuns(5)
                .modelCatalog(ModelCatalog.builder()
                        .model("haiku", mockModel)
                        .model("sonnet", mockModel)
                        .build())
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

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Submits a run and waits for it to start. Returns the runId. */
    private String submitBlockingRun() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        // Block: this run waits until dashboard stops
        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startLatch.countDown();
            Thread.currentThread().join(10_000);
            return mockOutput;
        });

        HttpResponse<String> resp = post("/api/runs", "{}");
        assertThat(resp.statusCode()).isEqualTo(202);
        String runId = objectMapper.readTree(resp.body()).get("runId").asText();
        assertThat(startLatch.await(3, TimeUnit.SECONDS)).isTrue();
        return runId;
    }

    /** Submits a run that completes immediately. Returns the runId. */
    private String submitQuickRun() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            doneLatch.countDown();
            return mockOutput;
        });

        HttpResponse<String> resp = post("/api/runs", "{}");
        assertThat(resp.statusCode()).isEqualTo(202);
        String runId = objectMapper.readTree(resp.body()).get("runId").asText();
        assertThat(doneLatch.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200); // let run state settle
        return runId;
    }

    // ========================
    // POST /api/runs/{runId}/model -- with model catalog
    // ========================

    @Test
    void switchModel_invalidAlias_returns400() throws Exception {
        String runId = submitBlockingRun();

        HttpResponse<String> resp = post("/api/runs/" + runId + "/model", "{\"model\":\"unknown-alias\"}");
        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("INVALID_MODEL");
    }

    @Test
    void switchModel_unknownRun_returns404() throws Exception {
        HttpResponse<String> resp = post("/api/runs/run-nonexistent/model", "{\"model\":\"haiku\"}");
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("RUN_NOT_FOUND");
    }

    @Test
    void switchModel_completedRun_returns409() throws Exception {
        String runId = submitQuickRun();

        HttpResponse<String> resp = post("/api/runs/" + runId + "/model", "{\"model\":\"haiku\"}");
        assertThat(resp.statusCode()).isEqualTo(409);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("RUN_COMPLETED");
    }

    @Test
    void cancelRun_completedRun_returns409() throws Exception {
        String runId = submitQuickRun();

        HttpResponse<String> resp = post("/api/runs/" + runId + "/cancel", "");
        assertThat(resp.statusCode()).isEqualTo(409);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("RUN_COMPLETED");
    }

    @Test
    void switchModel_runningRun_returns200Applied() throws Exception {
        String runId = submitBlockingRun();

        // Switching to a valid model alias for a running run should return APPLIED
        HttpResponse<String> resp = post("/api/runs/" + runId + "/model", "{\"model\":\"haiku\"}");
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("runId").asText()).isEqualTo(runId);
        assertThat(body.get("model").asText()).isEqualTo("haiku");
        assertThat(body.get("status").asText()).isEqualTo("APPLIED");
    }

    @Test
    void switchModel_missingModelField_returns400() throws Exception {
        String runId = submitBlockingRun();
        HttpResponse<String> resp = post("/api/runs/" + runId + "/model", "{}");
        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("BAD_REQUEST");
    }
}
