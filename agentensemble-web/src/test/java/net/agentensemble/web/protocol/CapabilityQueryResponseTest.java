package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CapabilityQueryMessage} and {@link CapabilityResponseMessage}.
 */
class CapabilityQueryResponseTest {

    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
    }

    // ========================
    // CapabilityQueryMessage
    // ========================

    @Test
    void queryConstruction() {
        CapabilityQueryMessage msg =
                new CapabilityQueryMessage("req-1", "ensemble-alpha", "prepare-meal", "food", "TASK");
        assertThat(msg.requestId()).isEqualTo("req-1");
        assertThat(msg.from()).isEqualTo("ensemble-alpha");
        assertThat(msg.toolName()).isEqualTo("prepare-meal");
        assertThat(msg.tag()).isEqualTo("food");
        assertThat(msg.capabilityType()).isEqualTo("TASK");
    }

    @Test
    void queryConstructionWithNullOptionalFields() {
        CapabilityQueryMessage msg = new CapabilityQueryMessage("req-2", "ensemble-beta", null, null, null);
        assertThat(msg.requestId()).isEqualTo("req-2");
        assertThat(msg.from()).isEqualTo("ensemble-beta");
        assertThat(msg.toolName()).isNull();
        assertThat(msg.tag()).isNull();
        assertThat(msg.capabilityType()).isNull();
    }

    @Test
    void queryNullRequestIdThrows() {
        assertThatThrownBy(() -> new CapabilityQueryMessage(null, "from", null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void queryNullFromThrows() {
        assertThatThrownBy(() -> new CapabilityQueryMessage("req-1", null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("from");
    }

    @Test
    void queryRoundTrip() throws Exception {
        // Use null capabilityType filter — the common discovery case.
        CapabilityQueryMessage msg =
                new CapabilityQueryMessage("req-3", "ensemble-alpha", "prepare-meal", "food", null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("capability_query");
        assertThat(json).contains("\"requestId\":\"req-3\"");
        assertThat(json).contains("\"from\":\"ensemble-alpha\"");
        assertThat(json).contains("\"toolName\":\"prepare-meal\"");
        assertThat(json).contains("\"tag\":\"food\"");

        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        assertThat(deserialized).isInstanceOf(CapabilityQueryMessage.class);
        CapabilityQueryMessage rt = (CapabilityQueryMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-3");
        assertThat(rt.from()).isEqualTo("ensemble-alpha");
        assertThat(rt.toolName()).isEqualTo("prepare-meal");
        assertThat(rt.tag()).isEqualTo("food");
    }

    @Test
    void queryRoundTripWithCapabilityTypeFilter() throws Exception {
        // The capabilityType field no longer collides with Jackson's @JsonTypeInfo discriminator
        // (which uses "type"), so we can round-trip through ClientMessage.class.
        CapabilityQueryMessage msg = new CapabilityQueryMessage("req-7", "ensemble-alpha", null, null, "TASK");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("capability_query");
        assertThat(json).contains("\"capabilityType\":\"TASK\"");

        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        assertThat(deserialized).isInstanceOf(CapabilityQueryMessage.class);
        CapabilityQueryMessage rt = (CapabilityQueryMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-7");
        assertThat(rt.from()).isEqualTo("ensemble-alpha");
        assertThat(rt.capabilityType()).isEqualTo("TASK");
    }

    @Test
    void queryRoundTripNullFieldsOmitted() throws Exception {
        CapabilityQueryMessage msg = new CapabilityQueryMessage("req-4", "ensemble-beta", null, null, null);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("capability_query");
        assertThat(json).doesNotContain("\"toolName\"");
        assertThat(json).doesNotContain("\"tag\"");
        assertThat(json).doesNotContain("\"capabilityType\"");

        ClientMessage deserialized = serializer.fromJson(json, ClientMessage.class);
        assertThat(deserialized).isInstanceOf(CapabilityQueryMessage.class);
        CapabilityQueryMessage rt = (CapabilityQueryMessage) deserialized;
        assertThat(rt.toolName()).isNull();
        assertThat(rt.tag()).isNull();
    }

    // ========================
    // CapabilityResponseMessage
    // ========================

    @Test
    void responseConstruction() {
        List<SharedCapabilityInfo> caps = List.of(
                new SharedCapabilityInfo("prepare-meal", "Prepare a meal", "TASK", List.of("food")),
                new SharedCapabilityInfo("check-inventory", "Check inventory", "TOOL"));
        CapabilityResponseMessage msg = new CapabilityResponseMessage("req-1", "kitchen-ensemble", caps);

        assertThat(msg.requestId()).isEqualTo("req-1");
        assertThat(msg.ensemble()).isEqualTo("kitchen-ensemble");
        assertThat(msg.capabilities()).hasSize(2);
        assertThat(msg.capabilities().get(0).name()).isEqualTo("prepare-meal");
        assertThat(msg.capabilities().get(0).tags()).containsExactly("food");
    }

    @Test
    void responseNullCapabilitiesDefaultsToEmpty() {
        CapabilityResponseMessage msg = new CapabilityResponseMessage("req-2", "ensemble-x", null);
        assertThat(msg.capabilities()).isEmpty();
    }

    @Test
    void responseNullRequestIdThrows() {
        assertThatThrownBy(() -> new CapabilityResponseMessage(null, "ensemble", List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("requestId");
    }

    @Test
    void responseNullEnsembleThrows() {
        assertThatThrownBy(() -> new CapabilityResponseMessage("req-1", null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ensemble");
    }

    @Test
    void responseRoundTrip() throws Exception {
        List<SharedCapabilityInfo> caps = List.of(
                new SharedCapabilityInfo("prepare-meal", "Prepare a meal", "TASK", List.of("food", "kitchen")),
                new SharedCapabilityInfo("check-inventory", "Check inventory", "TOOL"));
        CapabilityResponseMessage msg = new CapabilityResponseMessage("req-5", "kitchen-ensemble", caps);
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("capability_response");
        assertThat(json).contains("\"requestId\":\"req-5\"");
        assertThat(json).contains("\"ensemble\":\"kitchen-ensemble\"");
        assertThat(json).contains("prepare-meal");
        assertThat(json).contains("check-inventory");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(CapabilityResponseMessage.class);
        CapabilityResponseMessage rt = (CapabilityResponseMessage) deserialized;
        assertThat(rt.requestId()).isEqualTo("req-5");
        assertThat(rt.ensemble()).isEqualTo("kitchen-ensemble");
        assertThat(rt.capabilities()).hasSize(2);
        assertThat(rt.capabilities().get(0).name()).isEqualTo("prepare-meal");
        assertThat(rt.capabilities().get(0).tags()).containsExactly("food", "kitchen");
        assertThat(rt.capabilities().get(1).name()).isEqualTo("check-inventory");
        assertThat(rt.capabilities().get(1).tags()).isEmpty();
    }

    @Test
    void responseRoundTripEmptyCapabilities() throws Exception {
        CapabilityResponseMessage msg = new CapabilityResponseMessage("req-6", "empty-ensemble", List.of());
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("capability_response");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(CapabilityResponseMessage.class);
        CapabilityResponseMessage rt = (CapabilityResponseMessage) deserialized;
        assertThat(rt.capabilities()).isEmpty();
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
