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
                1842,
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
        assertThat(rt.tokenCount()).isEqualTo(1842);
        assertThat(rt.toolCallCount()).isEqualTo(3);
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
        ToolCalledMessage msg = new ToolCalledMessage("Senior Research Analyst", 1, "web_search", 1200L, "SUCCESS");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("tool_called");
        assertThat(json).contains("web_search");
        assertThat(json).contains("\"outcome\":\"SUCCESS\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ToolCalledMessage.class);
        ToolCalledMessage rt = (ToolCalledMessage) deserialized;
        assertThat(rt.toolName()).isEqualTo("web_search");
        assertThat(rt.durationMs()).isEqualTo(1200L);
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
    // Helpers
    // ========================

    private String typeOf(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.readTree(json);
        return node.get("type").asText();
    }
}
