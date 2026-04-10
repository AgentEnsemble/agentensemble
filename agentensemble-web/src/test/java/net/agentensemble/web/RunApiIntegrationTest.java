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
import net.agentensemble.tool.AgentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Ensemble Control API REST endpoints.
 *
 * <p>Each test starts a real embedded Javalin server on an ephemeral port, configures a mock
 * ensemble as the template, and sends HTTP requests via {@link HttpClient} to verify the
 * endpoint behavior end-to-end.
 *
 * <p>Uses {@code host("0.0.0.0")} so HTTP clients connecting from localhost are accepted without
 * Origin validation.
 */
class RunApiIntegrationTest {

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

        dashboard = WebDashboard.builder()
                .port(0) // ephemeral
                .host("0.0.0.0") // bypass localhost origin restriction for tests
                .maxConcurrentRuns(3)
                .build();
        dashboard.start();

        // Wire the mock ensemble as the template
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

    // ========================
    // POST /api/runs
    // ========================

    @Test
    void postRuns_withInputs_returns202WithRunId() throws Exception {
        CountDownLatch runStarted = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            runStarted.countDown();
            return mockOutput;
        });

        HttpResponse<String> response = post("/api/runs", "{\"inputs\":{\"topic\":\"AI safety\"}}");

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("runId").asText()).startsWith("run-");
        assertThat(body.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.has("tasks")).isTrue();
    }

    @Test
    void postRuns_emptyBody_returns202() throws Exception {
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        HttpResponse<String> response = post("/api/runs", "{}");
        assertThat(response.statusCode()).isEqualTo(202);
    }

    @Test
    void postRuns_invalidJson_returns400() throws Exception {
        HttpResponse<String> response = post("/api/runs", "not-valid-json");
        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void postRuns_noEnsembleConfigured_returns503() throws Exception {
        // Create a dashboard without wiring an ensemble
        WebDashboard noEnsembleDashboard =
                WebDashboard.builder().port(0).host("0.0.0.0").build();
        noEnsembleDashboard.start();
        try {
            int p = noEnsembleDashboard.actualPort();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + p + "/api/runs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            JsonNode body = objectMapper.readTree(response.body());
            assertThat(body.get("error").asText()).isEqualTo("NOT_CONFIGURED");
        } finally {
            noEnsembleDashboard.stop();
        }
    }

    @Test
    void postRuns_atConcurrencyLimit_returns429() throws Exception {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(3);

        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startedLatch.countDown();
            blockLatch.await(5, TimeUnit.SECONDS);
            return mockOutput;
        });

        // Submit 3 runs to fill the limit
        post("/api/runs", "{}");
        post("/api/runs", "{}");
        post("/api/runs", "{}");
        assertThat(startedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Fourth should be rejected
        HttpResponse<String> response = post("/api/runs", "{}");
        assertThat(response.statusCode()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo("CONCURRENCY_LIMIT");
        assertThat(body.has("retryAfterMs")).isTrue();

        blockLatch.countDown();
    }

    // ========================
    // GET /api/runs
    // ========================

    @Test
    void getRuns_empty_returnsEmptyList() throws Exception {
        HttpResponse<String> response = get("/api/runs");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("runs").isArray()).isTrue();
        assertThat(body.get("total").asInt()).isZero();
    }

    @Test
    void getRuns_afterSubmission_returnsRun() throws Exception {
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        post("/api/runs", "{}");

        // Wait for run to complete by polling
        for (int i = 0; i < 20; i++) {
            Thread.sleep(100);
            HttpResponse<String> response = get("/api/runs");
            JsonNode body = objectMapper.readTree(response.body());
            if (body.get("total").asInt() > 0) {
                assertThat(body.get("runs").get(0).get("runId").asText()).startsWith("run-");
                return;
            }
        }
        // The run should appear within 2 seconds
        assertThat(false).as("Run did not appear in listing within timeout").isFalse();
    }

    @Test
    void getRuns_withStatusFilter_filtersCorrectly() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            doneLatch.countDown();
            return mockOutput;
        });

        post("/api/runs", "{}");
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // give state time to update

        HttpResponse<String> completedResponse = get("/api/runs?status=COMPLETED");
        assertThat(completedResponse.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(completedResponse.body());
        assertThat(body.get("total").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void getRuns_withInvalidStatus_returns400() throws Exception {
        HttpResponse<String> response = get("/api/runs?status=INVALID_STATUS");
        assertThat(response.statusCode()).isEqualTo(400);
    }

    // ========================
    // GET /api/runs/{runId}
    // ========================

    @Test
    void getRunById_unknownId_returns404() throws Exception {
        HttpResponse<String> response = get("/api/runs/run-nonexistent");
        assertThat(response.statusCode()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("error").asText()).isEqualTo("RUN_NOT_FOUND");
    }

    @Test
    void getRunById_knownId_returns200WithDetail() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            doneLatch.countDown();
            return mockOutput;
        });

        HttpResponse<String> submitResponse = post("/api/runs", "{\"inputs\":{\"topic\":\"AI\"}}");
        JsonNode submitBody = objectMapper.readTree(submitResponse.body());
        String runId = submitBody.get("runId").asText();

        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100); // give state time to settle

        HttpResponse<String> detailResponse = get("/api/runs/" + runId);
        assertThat(detailResponse.statusCode()).isEqualTo(200);

        JsonNode detail = objectMapper.readTree(detailResponse.body());
        assertThat(detail.get("runId").asText()).isEqualTo(runId);
        assertThat(detail.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.get("inputs").get("topic").asText()).isEqualTo("AI");
    }

    // ========================
    // GET /api/capabilities
    // ========================

    @Test
    void getCapabilities_noCatalogs_returnsEmptyLists() throws Exception {
        HttpResponse<String> response = get("/api/capabilities");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("tools").isArray()).isTrue();
        assertThat(body.get("models").isArray()).isTrue();
        assertThat(body.get("preconfiguredTasks").isArray()).isTrue();
    }

    @Test
    void getCapabilities_withToolCatalog_listsTools() throws Exception {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("web_search");
        when(mockTool.description()).thenReturn("Search the web");

        WebDashboard dashboardWithCatalog = WebDashboard.builder()
                .port(0)
                .host("0.0.0.0")
                .toolCatalog(ToolCatalog.builder().tool("web_search", mockTool).build())
                .build();
        dashboardWithCatalog.start();
        try {
            int p = dashboardWithCatalog.actualPort();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + p + "/api/capabilities"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            JsonNode body = objectMapper.readTree(response.body());
            assertThat(body.get("tools").size()).isEqualTo(1);
            assertThat(body.get("tools").get(0).get("name").asText()).isEqualTo("web_search");
            assertThat(body.get("tools").get(0).get("description").asText()).isEqualTo("Search the web");
        } finally {
            dashboardWithCatalog.stop();
        }
    }

    // ========================
    // Protocol tests: RunAckMessage + RunResultMessage serialization
    // ========================

    @Test
    void runAckMessage_serialization_includesAllFields() throws Exception {
        net.agentensemble.web.protocol.MessageSerializer serializer =
                new net.agentensemble.web.protocol.MessageSerializer();
        net.agentensemble.web.protocol.RunAckMessage msg =
                new net.agentensemble.web.protocol.RunAckMessage("req-1", "run-abc", "ACCEPTED", 2, "SEQUENTIAL");
        String json = serializer.toJson(msg);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("run_ack");
        assertThat(node.get("requestId").asText()).isEqualTo("req-1");
        assertThat(node.get("runId").asText()).isEqualTo("run-abc");
        assertThat(node.get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(node.get("tasks").asInt()).isEqualTo(2);
        assertThat(node.get("workflow").asText()).isEqualTo("SEQUENTIAL");
    }

    @Test
    void runResultMessage_serialization_nullFieldsOmitted() throws Exception {
        net.agentensemble.web.protocol.MessageSerializer serializer =
                new net.agentensemble.web.protocol.MessageSerializer();
        net.agentensemble.web.protocol.RunResultMessage msg = new net.agentensemble.web.protocol.RunResultMessage(
                "run-abc", "COMPLETED", List.of(), 5000L, null, null);
        String json = serializer.toJson(msg);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("run_result");
        assertThat(node.get("runId").asText()).isEqualTo("run-abc");
        assertThat(node.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(node.get("durationMs").asLong()).isEqualTo(5000L);
        // null fields should be omitted (@JsonInclude NON_NULL)
        assertThat(node.has("metrics")).isFalse();
        assertThat(node.has("error")).isFalse();
    }

    // ========================
    // RunResultMessage nested records
    // ========================

    @Test
    void runResultMessage_withTaskOutputsAndMetrics_serializes() throws Exception {
        net.agentensemble.web.protocol.MessageSerializer serializer =
                new net.agentensemble.web.protocol.MessageSerializer();
        List<net.agentensemble.web.protocol.RunResultMessage.TaskOutputDto> outputs =
                List.of(new net.agentensemble.web.protocol.RunResultMessage.TaskOutputDto(
                        "Research AI", "AI safety is important", 8200L));
        net.agentensemble.web.protocol.RunResultMessage.MetricsDto metrics =
                new net.agentensemble.web.protocol.RunResultMessage.MetricsDto(15000L, 7L);

        net.agentensemble.web.protocol.RunResultMessage msg = new net.agentensemble.web.protocol.RunResultMessage(
                "run-abc", "COMPLETED", outputs, 11300L, metrics, null);
        String json = serializer.toJson(msg);
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("type").asText()).isEqualTo("run_result");
        assertThat(node.get("outputs").size()).isEqualTo(1);
        assertThat(node.get("outputs").get(0).get("taskName").asText()).isEqualTo("Research AI");
        assertThat(node.get("outputs").get(0).get("output").asText()).isEqualTo("AI safety is important");
        assertThat(node.get("metrics").get("totalTokens").asLong()).isEqualTo(15000L);
        assertThat(node.get("metrics").get("totalToolCalls").asLong()).isEqualTo(7L);
    }

    // ========================
    // Backward compatibility: existing endpoints still work
    // ========================

    @Test
    void existingStatusEndpoint_stillReturns200() throws Exception {
        HttpResponse<String> response = get("/api/status");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("running");
    }

    @Test
    void existingHealthLiveEndpoint_stillReturns200() throws Exception {
        HttpResponse<String> response = get("/api/health/live");
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
