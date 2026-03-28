package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Round-trip serialization tests for all directive-related protocol messages.
 *
 * <p>Verifies that the {@code type} discriminator is correct, all fields survive
 * the round-trip, and null fields are handled correctly.
 */
class DirectiveProtocolTest {

    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
    }

    // ========================
    // DirectiveMessage (client -> server)
    // ========================

    @Test
    void directiveMessageRoundTrip() throws Exception {
        DirectiveMessage msg = new DirectiveMessage(
                "ensemble-alpha", "operator-1", "Focus on quality over speed", null, null, "PT10M");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("directive");
        assertThat(json).contains("\"to\":\"ensemble-alpha\"");
        assertThat(json).contains("\"from\":\"operator-1\"");
        assertThat(json).contains("Focus on quality over speed");
        assertThat(json).contains("\"ttl\":\"PT10M\"");

        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        assertThat(deserialized).isInstanceOf(DirectiveMessage.class);
        DirectiveMessage rt = (DirectiveMessage) deserialized;
        assertThat(rt.to()).isEqualTo("ensemble-alpha");
        assertThat(rt.from()).isEqualTo("operator-1");
        assertThat(rt.content()).isEqualTo("Focus on quality over speed");
        assertThat(rt.action()).isNull();
        assertThat(rt.value()).isNull();
        assertThat(rt.ttl()).isEqualTo("PT10M");
    }

    @Test
    void directiveMessage_controlPlane_roundTrip() throws Exception {
        DirectiveMessage msg = new DirectiveMessage(null, "admin", null, "SET_MODEL_TIER", "fallback", null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("directive");
        assertThat(json).contains("\"action\":\"SET_MODEL_TIER\"");
        assertThat(json).contains("\"value\":\"fallback\"");
        // Null fields should be omitted
        assertThat(json).doesNotContain("\"to\"");
        assertThat(json).doesNotContain("\"content\"");
        assertThat(json).doesNotContain("\"ttl\"");

        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        DirectiveMessage rt = (DirectiveMessage) deserialized;
        assertThat(rt.to()).isNull();
        assertThat(rt.action()).isEqualTo("SET_MODEL_TIER");
        assertThat(rt.value()).isEqualTo("fallback");
    }

    // ========================
    // DirectiveAckMessage (server -> client)
    // ========================

    @Test
    void directiveAckMessageRoundTrip() throws Exception {
        DirectiveAckMessage msg = new DirectiveAckMessage("dir-001", "accepted");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("directive_ack");
        assertThat(json).contains("\"directiveId\":\"dir-001\"");
        assertThat(json).contains("\"status\":\"accepted\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(DirectiveAckMessage.class);
        DirectiveAckMessage rt = (DirectiveAckMessage) deserialized;
        assertThat(rt.directiveId()).isEqualTo("dir-001");
        assertThat(rt.status()).isEqualTo("accepted");
    }

    // ========================
    // DirectiveActiveMessage (server -> client)
    // ========================

    @Test
    void directiveActiveMessageRoundTrip() throws Exception {
        DirectiveActiveMessage msg = new DirectiveActiveMessage(
                "dir-002", "operator-1", "Prioritize VIP guests", null, null, "2026-03-28T15:00:00Z");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("directive_active");
        assertThat(json).contains("\"directiveId\":\"dir-002\"");
        assertThat(json).contains("\"from\":\"operator-1\"");
        assertThat(json).contains("Prioritize VIP guests");
        assertThat(json).contains("\"expiresAt\":\"2026-03-28T15:00:00Z\"");
        // Null action/value should be omitted
        assertThat(json).doesNotContain("\"action\"");
        assertThat(json).doesNotContain("\"value\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(DirectiveActiveMessage.class);
        DirectiveActiveMessage rt = (DirectiveActiveMessage) deserialized;
        assertThat(rt.directiveId()).isEqualTo("dir-002");
        assertThat(rt.from()).isEqualTo("operator-1");
        assertThat(rt.content()).isEqualTo("Prioritize VIP guests");
        assertThat(rt.action()).isNull();
        assertThat(rt.value()).isNull();
        assertThat(rt.expiresAt()).isEqualTo("2026-03-28T15:00:00Z");
    }

    @Test
    void directiveActiveMessage_controlPlane_roundTrip() throws Exception {
        DirectiveActiveMessage msg =
                new DirectiveActiveMessage("dir-003", "admin", null, "SET_MODEL_TIER", "fallback", null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("directive_active");
        assertThat(json).contains("\"action\":\"SET_MODEL_TIER\"");
        assertThat(json).doesNotContain("\"content\"");
        assertThat(json).doesNotContain("\"expiresAt\"");

        DirectiveActiveMessage rt = (DirectiveActiveMessage) serializer.fromJson(json, ServerMessage.class);
        assertThat(rt.action()).isEqualTo("SET_MODEL_TIER");
        assertThat(rt.value()).isEqualTo("fallback");
        assertThat(rt.content()).isNull();
        assertThat(rt.expiresAt()).isNull();
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
