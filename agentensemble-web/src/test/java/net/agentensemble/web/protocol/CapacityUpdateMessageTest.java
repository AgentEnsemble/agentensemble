package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CapacityUpdateMessage}.
 */
class CapacityUpdateMessageTest {

    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
    }

    // ========================
    // Construction
    // ========================

    @Test
    void construction() {
        CapacityUpdateMessage msg = new CapacityUpdateMessage("kitchen", "hotel-downtown", "available", 0.3, 10, true);

        assertThat(msg.ensemble()).isEqualTo("kitchen");
        assertThat(msg.realm()).isEqualTo("hotel-downtown");
        assertThat(msg.status()).isEqualTo("available");
        assertThat(msg.currentLoad()).isEqualTo(0.3);
        assertThat(msg.maxConcurrent()).isEqualTo(10);
        assertThat(msg.shareable()).isTrue();
    }

    @Test
    void constructionBusyNotShareable() {
        CapacityUpdateMessage msg = new CapacityUpdateMessage("maintenance", "hotel-airport", "busy", 1.0, 5, false);

        assertThat(msg.ensemble()).isEqualTo("maintenance");
        assertThat(msg.realm()).isEqualTo("hotel-airport");
        assertThat(msg.status()).isEqualTo("busy");
        assertThat(msg.currentLoad()).isEqualTo(1.0);
        assertThat(msg.maxConcurrent()).isEqualTo(5);
        assertThat(msg.shareable()).isFalse();
    }

    // ========================
    // Validation
    // ========================

    @Test
    void nullEnsembleThrows() {
        assertThatThrownBy(() -> new CapacityUpdateMessage(null, "realm", "available", 0.0, 1, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensemble");
    }

    @Test
    void nullRealmThrows() {
        assertThatThrownBy(() -> new CapacityUpdateMessage("kitchen", null, "available", 0.0, 1, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("realm");
    }

    @Test
    void nullStatusThrows() {
        assertThatThrownBy(() -> new CapacityUpdateMessage("kitchen", "realm", null, 0.0, 1, true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("status");
    }

    // ========================
    // JSON serialization round-trip
    // ========================

    @Test
    void jsonRoundTrip() throws Exception {
        CapacityUpdateMessage msg = new CapacityUpdateMessage("kitchen", "hotel-downtown", "available", 0.45, 8, true);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("capacity_update");
        assertThat(json).contains("\"ensemble\":\"kitchen\"");
        assertThat(json).contains("\"realm\":\"hotel-downtown\"");
        assertThat(json).contains("\"status\":\"available\"");
        assertThat(json).contains("\"currentLoad\":0.45");
        assertThat(json).contains("\"maxConcurrent\":8");
        assertThat(json).contains("\"shareable\":true");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(CapacityUpdateMessage.class);
        CapacityUpdateMessage rt = (CapacityUpdateMessage) deserialized;
        assertThat(rt.ensemble()).isEqualTo("kitchen");
        assertThat(rt.realm()).isEqualTo("hotel-downtown");
        assertThat(rt.status()).isEqualTo("available");
        assertThat(rt.currentLoad()).isEqualTo(0.45);
        assertThat(rt.maxConcurrent()).isEqualTo(8);
        assertThat(rt.shareable()).isTrue();
    }

    @Test
    void jsonRoundTripNotShareable() throws Exception {
        CapacityUpdateMessage msg = new CapacityUpdateMessage("maintenance", "hotel-airport", "busy", 1.0, 5, false);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("capacity_update");
        assertThat(json).contains("\"shareable\":false");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(CapacityUpdateMessage.class);
        CapacityUpdateMessage rt = (CapacityUpdateMessage) deserialized;
        assertThat(rt.shareable()).isFalse();
        assertThat(rt.currentLoad()).isEqualTo(1.0);
    }

    @Test
    void jsonRoundTripDraining() throws Exception {
        CapacityUpdateMessage msg = new CapacityUpdateMessage("concierge", "hotel-beach", "draining", 0.75, 3, false);
        String json = serializer.toJson(msg);

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(CapacityUpdateMessage.class);
        CapacityUpdateMessage rt = (CapacityUpdateMessage) deserialized;
        assertThat(rt.status()).isEqualTo("draining");
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
