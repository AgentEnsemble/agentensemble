package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProfileAppliedMessage} and {@link CapacitySpec}.
 */
class ProfileAppliedMessageTest {

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
        Map<String, CapacitySpec> capacities = Map.of(
                "kitchen", new CapacitySpec(4, 50, false),
                "front-desk", new CapacitySpec(2, 20, false));

        ProfileAppliedMessage msg =
                new ProfileAppliedMessage("sporting-event-weekend", capacities, "2026-03-28T10:00:00Z");

        assertThat(msg.profileName()).isEqualTo("sporting-event-weekend");
        assertThat(msg.capacities()).hasSize(2);
        assertThat(msg.capacities().get("kitchen").replicas()).isEqualTo(4);
        assertThat(msg.capacities().get("kitchen").maxConcurrent()).isEqualTo(50);
        assertThat(msg.capacities().get("kitchen").dormant()).isFalse();
        assertThat(msg.appliedAt()).isEqualTo("2026-03-28T10:00:00Z");
    }

    @Test
    void constructionWithDormantCapacity() {
        Map<String, CapacitySpec> capacities = Map.of("maintenance", new CapacitySpec(0, 0, true));

        ProfileAppliedMessage msg = new ProfileAppliedMessage("quiet-night", capacities, "2026-03-28T02:00:00Z");

        assertThat(msg.capacities().get("maintenance").dormant()).isTrue();
        assertThat(msg.capacities().get("maintenance").replicas()).isZero();
    }

    // ========================
    // Validation
    // ========================

    @Test
    void nullProfileNameThrows() {
        assertThatThrownBy(() -> new ProfileAppliedMessage(null, Map.of(), "now"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("profileName");
    }

    @Test
    void nullCapacitiesDefaultsToEmptyMap() {
        ProfileAppliedMessage msg = new ProfileAppliedMessage("empty", null, "now");
        assertThat(msg.capacities()).isEmpty();
    }

    @Test
    void capacitiesAreImmutable() {
        ProfileAppliedMessage msg =
                new ProfileAppliedMessage("test", Map.of("a", new CapacitySpec(1, 10, false)), "now");
        assertThatThrownBy(() -> msg.capacities().put("b", new CapacitySpec(2, 20, false)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // CapacitySpec validation
    // ========================

    @Test
    void capacitySpecNegativeReplicasThrows() {
        assertThatThrownBy(() -> new CapacitySpec(-1, 10, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("replicas");
    }

    @Test
    void capacitySpecNegativeMaxConcurrentThrows() {
        assertThatThrownBy(() -> new CapacitySpec(1, -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrent");
    }

    // ========================
    // JSON serialization round-trip
    // ========================

    @Test
    void jsonRoundTrip() throws Exception {
        Map<String, CapacitySpec> capacities = Map.of("kitchen", new CapacitySpec(4, 50, false));

        ProfileAppliedMessage msg =
                new ProfileAppliedMessage("sporting-event-weekend", capacities, "2026-03-28T10:00:00Z");
        String json = serializer.toJson(msg);

        assertThat(typeOf(json)).isEqualTo("profile_applied");
        assertThat(json).contains("\"profileName\":\"sporting-event-weekend\"");
        assertThat(json).contains("\"appliedAt\":\"2026-03-28T10:00:00Z\"");
        assertThat(json).contains("\"replicas\":4");
        assertThat(json).contains("\"maxConcurrent\":50");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ProfileAppliedMessage.class);
        ProfileAppliedMessage rt = (ProfileAppliedMessage) deserialized;
        assertThat(rt.profileName()).isEqualTo("sporting-event-weekend");
        assertThat(rt.capacities()).containsKey("kitchen");
        assertThat(rt.capacities().get("kitchen").replicas()).isEqualTo(4);
        assertThat(rt.capacities().get("kitchen").maxConcurrent()).isEqualTo(50);
        assertThat(rt.capacities().get("kitchen").dormant()).isFalse();
        assertThat(rt.appliedAt()).isEqualTo("2026-03-28T10:00:00Z");
    }

    @Test
    void jsonRoundTripDormantCapacity() throws Exception {
        Map<String, CapacitySpec> capacities = Map.of("maintenance", new CapacitySpec(0, 0, true));

        ProfileAppliedMessage msg = new ProfileAppliedMessage("quiet-night", capacities, "2026-03-28T02:00:00Z");
        String json = serializer.toJson(msg);

        assertThat(json).contains("\"dormant\":true");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ProfileAppliedMessage.class);
        ProfileAppliedMessage rt = (ProfileAppliedMessage) deserialized;
        assertThat(rt.capacities().get("maintenance").dormant()).isTrue();
    }

    @Test
    void jsonRoundTripEmptyCapacities() throws Exception {
        ProfileAppliedMessage msg = new ProfileAppliedMessage("minimal", Map.of(), "2026-03-28T00:00:00Z");
        String json = serializer.toJson(msg);

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ProfileAppliedMessage.class);
        ProfileAppliedMessage rt = (ProfileAppliedMessage) deserialized;
        assertThat(rt.capacities()).isEmpty();
    }

    @Test
    void jsonRoundTripNullAppliedAtOmitted() throws Exception {
        ProfileAppliedMessage msg = new ProfileAppliedMessage("no-timestamp", Map.of(), null);
        String json = serializer.toJson(msg);

        // appliedAt is null so @JsonInclude(NON_NULL) should omit it
        assertThat(json).doesNotContain("\"appliedAt\"");

        ServerMessage deserialized = serializer.fromJson(json, ServerMessage.class);
        assertThat(deserialized).isInstanceOf(ProfileAppliedMessage.class);
        ProfileAppliedMessage rt = (ProfileAppliedMessage) deserialized;
        assertThat(rt.appliedAt()).isNull();
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
