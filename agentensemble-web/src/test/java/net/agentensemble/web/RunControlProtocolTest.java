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
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.web.protocol.MessageSerializer;
import net.agentensemble.web.protocol.RunControlAckMessage;
import net.agentensemble.web.protocol.RunControlMessage;
import net.agentensemble.web.protocol.SubscribeAckMessage;
import net.agentensemble.web.protocol.SubscribeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the new protocol messages (Phase 3/4) and tool invocation (Phase 5).
 *
 * <p>Covers:
 * - {@link RunControlMessage} serialization/deserialization
 * - {@link RunControlAckMessage} serialization
 * - {@link SubscribeMessage} serialization/deserialization
 * - {@link SubscribeAckMessage} serialization
 * - {@code POST /api/tools/{name}/invoke} with tool catalog (success path)
 */
class RunControlProtocolTest {

    private MessageSerializer serializer;
    private ObjectMapper objectMapper;

    // For REST tests
    private WebDashboard dashboard;
    private HttpClient httpClient;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        serializer = new MessageSerializer();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (dashboard != null) dashboard.stop();
    }

    // ========================
    // RunControlMessage serialization
    // ========================

    @Test
    void runControlMessage_cancel_serializesCorrectly() {
        RunControlMessage msg = new RunControlMessage("run-abc", "cancel", null);
        String json = serializer.toJson(msg);
        JsonNode node = objectMapper.valueToTree(objectMapper.convertValue(json, Object.class));
        // Verify JSON round-trip via fromJson
        RunControlMessage parsed = serializer.fromJson(json, RunControlMessage.class);
        assertThat(parsed.runId()).isEqualTo("run-abc");
        assertThat(parsed.action()).isEqualTo("cancel");
        assertThat(parsed.model()).isNull();
    }

    @Test
    void runControlMessage_switchModel_serializesCorrectly() {
        RunControlMessage msg = new RunControlMessage("run-abc", "switch_model", "haiku");
        String json = serializer.toJson(msg);
        RunControlMessage parsed = serializer.fromJson(json, RunControlMessage.class);
        assertThat(parsed.runId()).isEqualTo("run-abc");
        assertThat(parsed.action()).isEqualTo("switch_model");
        assertThat(parsed.model()).isEqualTo("haiku");
    }

    @Test
    void runControlMessage_typeFieldPresent_inJson() {
        RunControlMessage msg = new RunControlMessage("run-1", "cancel", null);
        String json = serializer.toJson(msg);
        assertThat(json).contains("\"type\":\"run_control\"");
        assertThat(json).contains("\"runId\":\"run-1\"");
    }

    // ========================
    // RunControlAckMessage serialization
    // ========================

    @Test
    void runControlAckMessage_cancelling_serializesCorrectly() {
        RunControlAckMessage ack = new RunControlAckMessage("run-abc", "cancel", "CANCELLING", null, null);
        String json = serializer.toJson(ack);
        assertThat(json).contains("\"type\":\"run_control_ack\"");
        assertThat(json).contains("\"runId\":\"run-abc\"");
        assertThat(json).contains("\"action\":\"cancel\"");
        assertThat(json).contains("\"status\":\"CANCELLING\"");
        // null fields omitted by @JsonInclude(NON_NULL)
        assertThat(json).doesNotContain("\"model\"");
    }

    @Test
    void runControlAckMessage_applied_serializesWithModel() {
        RunControlAckMessage ack = new RunControlAckMessage("run-abc", "switch_model", "APPLIED", "haiku", "sonnet");
        String json = serializer.toJson(ack);
        assertThat(json).contains("\"model\":\"haiku\"");
        assertThat(json).contains("\"previousModel\":\"sonnet\"");
    }

    // ========================
    // SubscribeMessage serialization
    // ========================

    @Test
    void subscribeMessage_withEvents_serializesCorrectly() {
        SubscribeMessage msg = new SubscribeMessage(List.of("task_started", "run_result"), null);
        String json = serializer.toJson(msg);
        assertThat(json).contains("\"type\":\"subscribe\"");
        assertThat(json).contains("task_started");
        assertThat(json).contains("run_result");
        assertThat(json).doesNotContain("\"runId\"");
    }

    @Test
    void subscribeMessage_withRunId_serializesCorrectly() {
        SubscribeMessage msg = new SubscribeMessage(List.of("run_result"), "run-abc");
        String json = serializer.toJson(msg);
        assertThat(json).contains("\"runId\":\"run-abc\"");
    }

    @Test
    void subscribeMessage_deserialization_roundTrips() {
        SubscribeMessage msg = new SubscribeMessage(List.of("task_started", "*"), "run-xyz");
        String json = serializer.toJson(msg);
        SubscribeMessage parsed = serializer.fromJson(json, SubscribeMessage.class);
        assertThat(parsed.events()).containsExactlyInAnyOrder("task_started", "*");
        assertThat(parsed.runId()).isEqualTo("run-xyz");
    }

    // ========================
    // SubscribeAckMessage serialization
    // ========================

    @Test
    void subscribeAckMessage_serializesCorrectly() {
        SubscribeAckMessage ack = new SubscribeAckMessage(List.of("task_started", "run_result"), null);
        String json = serializer.toJson(ack);
        assertThat(json).contains("\"type\":\"subscribe_ack\"");
        assertThat(json).contains("task_started");
        assertThat(json).doesNotContain("\"runId\"");
    }

    @Test
    void subscribeAckMessage_withRunId_serializesCorrectly() {
        SubscribeAckMessage ack = new SubscribeAckMessage(List.of("*"), "run-abc");
        String json = serializer.toJson(ack);
        assertThat(json).contains("\"runId\":\"run-abc\"");
        assertThat(json).contains("\"*\"");
    }

    // ========================
    // POST /api/tools/{name}/invoke -- with tool catalog (success path)
    // ========================

    @Test
    void invokeTool_withToolCatalog_returnsSuccess() throws Exception {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("calculator");
        when(mockTool.description()).thenReturn("Calculate math expressions");
        when(mockTool.execute(any())).thenReturn(ToolResult.success("42"));

        dashboard = WebDashboard.builder()
                .port(0)
                .host("0.0.0.0")
                .toolCatalog(ToolCatalog.builder().tool("calculator", mockTool).build())
                .build();
        dashboard.start();
        port = dashboard.actualPort();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/tools/calculator/invoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"6*7\"}"))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("tool").asText()).isEqualTo("calculator");
        assertThat(body.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(body.get("output").asText()).isEqualTo("42");
        assertThat(body.has("durationMs")).isTrue();
    }

    @Test
    void invokeTool_unknownTool_returns404() throws Exception {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("calculator");
        when(mockTool.description()).thenReturn("Calculate");

        dashboard = WebDashboard.builder()
                .port(0)
                .host("0.0.0.0")
                .toolCatalog(ToolCatalog.builder().tool("calculator", mockTool).build())
                .build();
        dashboard.start();
        port = dashboard.actualPort();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/tools/unknown_tool/invoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"test\"}"))
                .timeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("TOOL_NOT_FOUND");
    }

    @Test
    void invokeTool_toolThrows_returns500() throws Exception {
        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.name()).thenReturn("buggy");
        when(mockTool.description()).thenReturn("Buggy tool");
        when(mockTool.execute(any())).thenThrow(new RuntimeException("Tool error"));

        dashboard = WebDashboard.builder()
                .port(0)
                .host("0.0.0.0")
                .toolCatalog(ToolCatalog.builder().tool("buggy", mockTool).build())
                .build();
        dashboard.start();
        port = dashboard.actualPort();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/tools/buggy/invoke"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":\"test\"}"))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(500);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").asText()).isEqualTo("TOOL_EXECUTION_FAILED");
    }
}
