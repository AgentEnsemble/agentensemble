package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Round-trip serialization tests for all server->client and client->server protocol messages.
 *
 * <p>Each test verifies: the serialized JSON contains the correct {@code type} discriminator,
 * the message can be deserialized back to the correct type, and all fields survive the round-trip.
 */
class ProtocolSerializationTest {

    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
    }

    // ========================
    // Server -> Client messages
    // ========================

    @Test
    void helloMessageRoundTrip() throws Exception {
        HelloMessage msg = new HelloMessage("ens-123", Instant.parse("2026-03-05T14:00:00Z"), null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("hello");
        assertThat(json).contains("ens-123");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(HelloMessage.class);
        HelloMessage roundTripped = (HelloMessage) deserialized;
        assertThat(roundTripped.ensembleId()).isEqualTo("ens-123");
        assertThat(roundTripped.startedAt()).isEqualTo(Instant.parse("2026-03-05T14:00:00Z"));
        assertThat(roundTripped.snapshotTrace()).isNull();
    }

    @Test
    void helloMessageWithSnapshotRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode snapshot = mapper.readTree("{\"workflow\":\"SEQUENTIAL\"}");
        HelloMessage msg = new HelloMessage("ens-456", Instant.parse("2026-03-05T15:00:00Z"), snapshot);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("hello");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(HelloMessage.class);
        HelloMessage roundTripped = (HelloMessage) deserialized;
        assertThat(roundTripped.snapshotTrace()).isNotNull();
    }

    @Test
    void ensembleStartedMessageRoundTrip() throws Exception {
        EnsembleStartedMessage msg =
                new EnsembleStartedMessage("ens-999", Instant.parse("2026-03-05T14:00:00Z"), 4, "SEQUENTIAL");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("ensemble_started");
        assertThat(json).contains("\"totalTasks\":4");
        assertThat(json).contains("\"workflow\":\"SEQUENTIAL\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(EnsembleStartedMessage.class);
        EnsembleStartedMessage rt = (EnsembleStartedMessage) deserialized;
        assertThat(rt.ensembleId()).isEqualTo("ens-999");
        assertThat(rt.totalTasks()).isEqualTo(4);
        assertThat(rt.workflow()).isEqualTo("SEQUENTIAL");
    }

    @Test
    void taskStartedMessageRoundTrip() throws Exception {
        TaskStartedMessage msg = new TaskStartedMessage(
                1, 4, "Research AI trends", "Senior Research Analyst", Instant.parse("2026-03-05T14:00:01Z"));
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("task_started");
        assertThat(json).contains("\"taskIndex\":1");
        assertThat(json).contains("Research AI trends");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(TaskStartedMessage.class);
        TaskStartedMessage rt = (TaskStartedMessage) deserialized;
        assertThat(rt.taskIndex()).isEqualTo(1);
        assertThat(rt.totalTasks()).isEqualTo(4);
        assertThat(rt.agentRole()).isEqualTo("Senior Research Analyst");
    }

    @Test
    void taskCompletedMessageRoundTrip() throws Exception {
        TaskCompletedMessage msg = new TaskCompletedMessage(
                1,
                4,
                "Research AI trends",
                "Senior Research Analyst",
                Instant.parse("2026-03-05T14:00:45Z"),
                44000L,
                1842L,
                3);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("task_completed");
        assertThat(json).contains("\"durationMs\":44000");
        assertThat(json).contains("\"tokenCount\":1842");
        assertThat(json).contains("\"toolCallCount\":3");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(TaskCompletedMessage.class);
        TaskCompletedMessage rt = (TaskCompletedMessage) deserialized;
        assertThat(rt.durationMs()).isEqualTo(44000L);
        assertThat(rt.tokenCount()).isEqualTo(1842L);
        assertThat(rt.toolCallCount()).isEqualTo(3);
    }

    @Test
    void taskCompletedMessage_minusOneTokenCount_sentinelRoundTrip() throws Exception {
        // -1 is the sentinel for "token count unknown" (consistent with TaskMetrics.totalTokens).
        TaskCompletedMessage msg = new TaskCompletedMessage(1, 1, "Task", "Agent", Instant.now(), 5000L, -1L, 0);
        String json = serializer.toJson(msg);

        assertThat(json).contains("\"tokenCount\":-1");

        TaskCompletedMessage rt = (TaskCompletedMessage) serializer.fromJson(json, ServerMessage.class);
        assertThat(rt.tokenCount()).isEqualTo(-1L);
    }

    @Test
    void taskFailedMessageRoundTrip() throws Exception {
        TaskFailedMessage msg = new TaskFailedMessage(
                2,
                "Write report",
                "Content Writer",
                Instant.parse("2026-03-05T14:01:00Z"),
                "MaxIterationsExceededException: limit reached");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("task_failed");
        assertThat(json).contains("MaxIterationsExceededException");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(TaskFailedMessage.class);
        TaskFailedMessage rt = (TaskFailedMessage) deserialized;
        assertThat(rt.reason()).contains("MaxIterationsExceededException");
    }

    @Test
    void toolCalledMessageRoundTrip() throws Exception {
        ToolCalledMessage msg = new ToolCalledMessage(
                "Senior Research Analyst", 1, "web_search", 1200L, "SUCCESS", "{\"query\":\"AI\"}", "results", null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("tool_called");
        assertThat(json).contains("web_search");
        assertThat(json).contains("\"outcome\":\"SUCCESS\"");
        assertThat(json).contains("\"toolArguments\":\"{\\\"query\\\":\\\"AI\\\"}\"");
        assertThat(json).contains("\"toolResult\":\"results\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ToolCalledMessage.class);
        ToolCalledMessage rt = (ToolCalledMessage) deserialized;
        assertThat(rt.toolName()).isEqualTo("web_search");
        assertThat(rt.durationMs()).isEqualTo(1200L);
        assertThat(rt.toolArguments()).isEqualTo("{\"query\":\"AI\"}");
        assertThat(rt.toolResult()).isEqualTo("results");
        // structuredResult was null; after round-trip it is null (field absent or explicit null)
        assertThat(rt.structuredResult()).isNull();
    }

    @Test
    void toolCalledMessage_nullOutcome_serializesAsNull() throws Exception {
        // When the outcome is not known (e.g. ToolCallEvent has no outcome field), the
        // protocol message uses null rather than a misleading "SUCCESS".
        ToolCalledMessage msg = new ToolCalledMessage("Analyst", 0, "calculator", 500L, null, "{}", "42", null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("tool_called");
        assertThat(json).contains("\"outcome\":null");

        ToolCalledMessage rt = (ToolCalledMessage) serializer.fromJson(json, ServerMessage.class);
        assertThat(rt.outcome()).isNull();
    }

    @Test
    void delegationStartedMessageRoundTrip() throws Exception {
        DelegationStartedMessage msg =
                new DelegationStartedMessage("del-123", "Lead Researcher", "Content Writer", "Write a blog post");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("delegation_started");
        assertThat(json).contains("\"delegationId\":\"del-123\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(DelegationStartedMessage.class);
        DelegationStartedMessage rt = (DelegationStartedMessage) deserialized;
        assertThat(rt.delegationId()).isEqualTo("del-123");
        assertThat(rt.workerRole()).isEqualTo("Content Writer");
    }

    @Test
    void delegationCompletedMessageRoundTrip() throws Exception {
        DelegationCompletedMessage msg =
                new DelegationCompletedMessage("del-456", "Lead Researcher", "Content Writer", 32000L);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("delegation_completed");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(DelegationCompletedMessage.class);
        DelegationCompletedMessage rt = (DelegationCompletedMessage) deserialized;
        assertThat(rt.durationMs()).isEqualTo(32000L);
    }

    @Test
    void delegationFailedMessageRoundTrip() throws Exception {
        DelegationFailedMessage msg = new DelegationFailedMessage(
                "del-789", "Lead Researcher", "Content Writer", "Guard rejected: depth limit");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("delegation_failed");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(DelegationFailedMessage.class);
        DelegationFailedMessage rt = (DelegationFailedMessage) deserialized;
        assertThat(rt.reason()).isEqualTo("Guard rejected: depth limit");
    }

    @Test
    void reviewRequestedMessageRoundTrip() throws Exception {
        ReviewRequestedMessage msg = new ReviewRequestedMessage(
                "rev-111",
                "Research AI trends",
                "The AI landscape in 2025...",
                "AFTER_EXECUTION",
                null,
                300000L,
                "CONTINUE");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("review_requested");
        assertThat(json).contains("\"reviewId\":\"rev-111\"");
        assertThat(json).contains("\"timeoutMs\":300000");
        assertThat(json).contains("\"onTimeout\":\"CONTINUE\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ReviewRequestedMessage.class);
        ReviewRequestedMessage rt = (ReviewRequestedMessage) deserialized;
        assertThat(rt.reviewId()).isEqualTo("rev-111");
        assertThat(rt.timing()).isEqualTo("AFTER_EXECUTION");
        assertThat(rt.onTimeout()).isEqualTo("CONTINUE");
    }

    @Test
    void reviewTimedOutMessageRoundTrip() throws Exception {
        ReviewTimedOutMessage msg = new ReviewTimedOutMessage("rev-222", "CONTINUE");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("review_timed_out");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ReviewTimedOutMessage.class);
        ReviewTimedOutMessage rt = (ReviewTimedOutMessage) deserialized;
        assertThat(rt.reviewId()).isEqualTo("rev-222");
        assertThat(rt.action()).isEqualTo("CONTINUE");
    }

    @Test
    void ensembleCompletedMessageRoundTrip() throws Exception {
        EnsembleCompletedMessage msg = new EnsembleCompletedMessage(
                "ens-777", Instant.parse("2026-03-05T14:05:00Z"), 300000L, "COMPLETED", 12500L, 15);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("ensemble_completed");
        assertThat(json).contains("\"exitReason\":\"COMPLETED\"");
        assertThat(json).contains("\"totalToolCalls\":15");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(EnsembleCompletedMessage.class);
        EnsembleCompletedMessage rt = (EnsembleCompletedMessage) deserialized;
        assertThat(rt.totalTokens()).isEqualTo(12500L);
        assertThat(rt.totalToolCalls()).isEqualTo(15);
    }

    @Test
    void heartbeatMessageRoundTrip() throws Exception {
        HeartbeatMessage msg = new HeartbeatMessage(1741212300000L);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("heartbeat");
        assertThat(json).contains("1741212300000");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(HeartbeatMessage.class);
        HeartbeatMessage rt = (HeartbeatMessage) deserialized;
        assertThat(rt.serverTimeMs()).isEqualTo(1741212300000L);
    }

    @Test
    void pongMessageRoundTrip() throws Exception {
        PongMessage msg = new PongMessage();
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("pong");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(PongMessage.class);
    }

    @Test
    void tokenMessageRoundTrip() throws Exception {
        TokenMessage msg = new TokenMessage(
                "Hello ", "Senior Research Analyst", "Research AI trends", Instant.parse("2026-03-05T14:01:00Z"));
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("token");
        assertThat(json).contains("\"token\":\"Hello \"");
        assertThat(json).contains("Senior Research Analyst");
        assertThat(json).contains("Research AI trends");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(TokenMessage.class);
        TokenMessage rt = (TokenMessage) deserialized;
        assertThat(rt.token()).isEqualTo("Hello ");
        assertThat(rt.agentRole()).isEqualTo("Senior Research Analyst");
        assertThat(rt.taskDescription()).isEqualTo("Research AI trends");
        assertThat(rt.sentAt()).isEqualTo(Instant.parse("2026-03-05T14:01:00Z"));
    }

    @Test
    void tokenMessage_emptyToken_roundTrip() throws Exception {
        TokenMessage msg = new TokenMessage("", "Writer", "Write something", Instant.now());
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("token");
        TokenMessage rt = (TokenMessage) serializer.fromJson(json, ServerMessage.class);
        assertThat(rt.token()).isEqualTo("");
        assertThat(rt.taskDescription()).isEqualTo("Write something");
    }

    // ========================
    // Client -> Server messages
    // ========================

    @Test
    void pingMessageDeserialization() throws Exception {
        String json = "{\"type\":\"ping\"}";
        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        assertThat(deserialized).isInstanceOf(PingMessage.class);
    }

    @Test
    void reviewDecisionContinueDeserialization() throws Exception {
        String json = "{\"type\":\"review_decision\",\"reviewId\":\"rev-333\",\"decision\":\"CONTINUE\"}";
        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);

        assertThat(deserialized).isInstanceOf(ReviewDecisionMessage.class);
        ReviewDecisionMessage rdm = (ReviewDecisionMessage) deserialized;
        assertThat(rdm.reviewId()).isEqualTo("rev-333");
        assertThat(rdm.decision()).isEqualTo("CONTINUE");
        assertThat(rdm.revisedOutput()).isNull();
    }

    @Test
    void reviewDecisionEditDeserialization() throws Exception {
        String json =
                "{\"type\":\"review_decision\",\"reviewId\":\"rev-444\",\"decision\":\"EDIT\",\"revisedOutput\":\"Better output\"}";
        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);

        assertThat(deserialized).isInstanceOf(ReviewDecisionMessage.class);
        ReviewDecisionMessage rdm = (ReviewDecisionMessage) deserialized;
        assertThat(rdm.decision()).isEqualTo("EDIT");
        assertThat(rdm.revisedOutput()).isEqualTo("Better output");
    }

    @Test
    void reviewDecisionExitEarlyDeserialization() throws Exception {
        String json = "{\"type\":\"review_decision\",\"reviewId\":\"rev-555\",\"decision\":\"EXIT_EARLY\"}";
        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);

        assertThat(deserialized).isInstanceOf(ReviewDecisionMessage.class);
        ReviewDecisionMessage rdm = (ReviewDecisionMessage) deserialized;
        assertThat(rdm.decision()).isEqualTo("EXIT_EARLY");
    }

    @Test
    void reviewDecisionRoundTrip() throws Exception {
        ReviewDecisionMessage msg = new ReviewDecisionMessage("rev-666", "EDIT", "Revised output text");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("review_decision");
        assertThat(json).contains("\"revisedOutput\":\"Revised output text\"");
    }

    // ========================
    // Null field handling
    // ========================

    @Test
    void nullFieldsOmittedInHelloMessage() throws Exception {
        HelloMessage msg = new HelloMessage(null, null, null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("hello");
        assertThat(json).doesNotContain("ensembleId");
        assertThat(json).doesNotContain("startedAt");
        assertThat(json).doesNotContain("snapshotTrace");
    }

    // ========================
    // Error handling
    // ========================

    @Test
    void fromJson_malformedJson_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> serializer.fromJson("not-valid-json", ClientMessage.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize");
    }

    @Test
    void fromJson_unknownTypeDiscriminator_throwsIllegalArgumentException() {
        // An unknown type discriminator causes InvalidTypeIdException, which is wrapped in
        // IllegalArgumentException by the serializer's catch block.
        String json = "{\"type\":\"unknown_message_type\",\"data\":\"something\"}";
        assertThatThrownBy(() -> serializer.fromJson(json, ClientMessage.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize");
    }

    // ========================
    // EN-006: Cross-ensemble protocol messages
    // ========================

    @Test
    void taskRequestMessageRoundTrip() throws Exception {
        TaskRequestMessage msg = new TaskRequestMessage(
                "req-001",
                "ensemble-alpha",
                "prepare-meal",
                "Make a pasta dish",
                Priority.HIGH,
                "PT5M",
                new DeliverySpec(DeliveryMethod.WEBSOCKET, null),
                new TraceContext("00-traceparent-01", null),
                CachePolicy.USE_CACHED,
                "cache-key-1",
                null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("task_request");
        assertThat(json).contains("\"requestId\":\"req-001\"");
        assertThat(json).contains("\"from\":\"ensemble-alpha\"");
        assertThat(json).contains("\"task\":\"prepare-meal\"");

        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        assertThat(deserialized).isInstanceOf(TaskRequestMessage.class);
        TaskRequestMessage rt = (TaskRequestMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-001");
        assertThat(rt.from()).isEqualTo("ensemble-alpha");
        assertThat(rt.task()).isEqualTo("prepare-meal");
        assertThat(rt.context()).isEqualTo("Make a pasta dish");
        assertThat(rt.priority()).isEqualTo(Priority.HIGH);
        assertThat(rt.deadline()).isEqualTo("PT5M");
        assertThat(rt.cachePolicy()).isEqualTo(CachePolicy.USE_CACHED);
    }

    @Test
    void toolRequestMessageRoundTrip() throws Exception {
        ToolRequestMessage msg = new ToolRequestMessage(
                "req-002",
                "ensemble-beta",
                "check-inventory",
                "tomatoes",
                new TraceContext("00-traceparent-02", "vendor=abc"));
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("tool_request");
        assertThat(json).contains("\"requestId\":\"req-002\"");
        assertThat(json).contains("\"tool\":\"check-inventory\"");

        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        assertThat(deserialized).isInstanceOf(ToolRequestMessage.class);
        ToolRequestMessage rt = (ToolRequestMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-002");
        assertThat(rt.from()).isEqualTo("ensemble-beta");
        assertThat(rt.tool()).isEqualTo("check-inventory");
        assertThat(rt.input()).isEqualTo("tomatoes");
        assertThat(rt.traceContext().traceparent()).isEqualTo("00-traceparent-02");
        assertThat(rt.traceContext().tracestate()).isEqualTo("vendor=abc");
    }

    @Test
    void taskAcceptedMessageRoundTrip() throws Exception {
        TaskAcceptedMessage msg = new TaskAcceptedMessage("req-001", 0, "PT2M");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("task_accepted");
        assertThat(json).contains("\"requestId\":\"req-001\"");
        assertThat(json).contains("\"queuePosition\":0");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(TaskAcceptedMessage.class);
        TaskAcceptedMessage rt = (TaskAcceptedMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-001");
        assertThat(rt.queuePosition()).isEqualTo(0);
        assertThat(rt.estimatedCompletion()).isEqualTo("PT2M");
    }

    @Test
    void taskProgressMessageRoundTrip() throws Exception {
        TaskProgressMessage msg = new TaskProgressMessage("req-001", "RUNNING", "Gathering ingredients", 25);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("task_progress");
        assertThat(json).contains("\"requestId\":\"req-001\"");
        assertThat(json).contains("\"percentComplete\":25");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(TaskProgressMessage.class);
        TaskProgressMessage rt = (TaskProgressMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-001");
        assertThat(rt.status()).isEqualTo("RUNNING");
        assertThat(rt.message()).isEqualTo("Gathering ingredients");
        assertThat(rt.percentComplete()).isEqualTo(25);
    }

    @Test
    void taskResponseMessageRoundTrip() throws Exception {
        TaskResponseMessage msg = new TaskResponseMessage("req-001", "COMPLETED", "Pasta is ready", null, 45000L);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("task_response");
        assertThat(json).contains("\"requestId\":\"req-001\"");
        assertThat(json).contains("\"status\":\"COMPLETED\"");
        assertThat(json).contains("\"result\":\"Pasta is ready\"");
        assertThat(json).contains("\"durationMs\":45000");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(TaskResponseMessage.class);
        TaskResponseMessage rt = (TaskResponseMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-001");
        assertThat(rt.status()).isEqualTo("COMPLETED");
        assertThat(rt.result()).isEqualTo("Pasta is ready");
        assertThat(rt.error()).isNull();
        assertThat(rt.durationMs()).isEqualTo(45000L);
    }

    @Test
    void toolResponseMessageRoundTrip() throws Exception {
        ToolResponseMessage msg = new ToolResponseMessage("req-002", "COMPLETED", "In stock: 5kg", null, 1200L);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("tool_response");
        assertThat(json).contains("\"requestId\":\"req-002\"");
        assertThat(json).contains("\"status\":\"COMPLETED\"");
        assertThat(json).contains("\"result\":\"In stock: 5kg\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage rt = (ToolResponseMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-002");
        assertThat(rt.status()).isEqualTo("COMPLETED");
        assertThat(rt.result()).isEqualTo("In stock: 5kg");
        assertThat(rt.error()).isNull();
        assertThat(rt.durationMs()).isEqualTo(1200L);
    }

    // ========================
    // Helpers
    // ========================

    private String typeOf(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        return node.get("type").asText();
    }
}
