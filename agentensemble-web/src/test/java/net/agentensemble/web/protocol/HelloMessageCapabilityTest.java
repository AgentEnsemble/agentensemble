package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Serialization round-trip and forward compatibility tests for
 * {@link HelloMessage} with shared capabilities (EN-003).
 */
class HelloMessageCapabilityTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Test
    void serializeHelloWithCapabilities() throws Exception {
        List<SharedCapabilityInfo> caps = List.of(
                new SharedCapabilityInfo("prepare-meal", "Prepare a meal", "TASK"),
                new SharedCapabilityInfo("check-inventory", "Check inventory", "TOOL"));

        HelloMessage msg = new HelloMessage(null, caps);
        String json = mapper.writeValueAsString(msg);

        assertThat(json).contains("\"sharedCapabilities\"");
        assertThat(json).contains("prepare-meal");
        assertThat(json).contains("check-inventory");
        assertThat(json).contains("TASK");
        assertThat(json).contains("TOOL");
    }

    @Test
    void deserializeHelloWithCapabilities() throws Exception {
        String json =
                """
                {
                    "type": "hello",
                    "snapshotTrace": null,
                    "sharedCapabilities": [
                        {"name": "prepare-meal", "description": "Prepare a meal", "type": "TASK"},
                        {"name": "check-inventory", "description": "Check inventory", "type": "TOOL"}
                    ]
                }
                """;

        HelloMessage msg = mapper.readValue(json, HelloMessage.class);

        assertThat(msg.snapshotTrace()).isNull();
        assertThat(msg.sharedCapabilities()).hasSize(2);
        assertThat(msg.sharedCapabilities().get(0).name()).isEqualTo("prepare-meal");
        assertThat(msg.sharedCapabilities().get(0).type()).isEqualTo("TASK");
        assertThat(msg.sharedCapabilities().get(1).name()).isEqualTo("check-inventory");
        assertThat(msg.sharedCapabilities().get(1).type()).isEqualTo("TOOL");
    }

    @Test
    void backwardCompatibleHelloWithoutCapabilities() throws Exception {
        HelloMessage msg = new HelloMessage(null);
        String json = mapper.writeValueAsString(msg);

        HelloMessage deserialized = mapper.readValue(json, HelloMessage.class);
        assertThat(deserialized.snapshotTrace()).isNull();
        assertThat(deserialized.sharedCapabilities()).isNull();
    }

    @Test
    void emptyCapabilitiesListSerializesCorrectly() throws Exception {
        HelloMessage msg = new HelloMessage(null, List.of());
        String json = mapper.writeValueAsString(msg);

        HelloMessage deserialized = mapper.readValue(json, HelloMessage.class);
        assertThat(deserialized.sharedCapabilities()).isEmpty();
    }

    @Test
    void forwardCompatibilityIgnoresUnknownFields() throws Exception {
        // Simulate a future version that adds extra fields to HelloMessage
        String json =
                """
                {
                    "type": "hello",
                    "snapshotTrace": null,
                    "sharedCapabilities": [
                        {"name": "prepare-meal", "description": "Prepare a meal", "type": "TASK"}
                    ],
                    "futureField": "some-value",
                    "anotherFutureField": 42
                }
                """;

        HelloMessage msg = mapper.readValue(json, HelloMessage.class);

        assertThat(msg.snapshotTrace()).isNull();
        assertThat(msg.sharedCapabilities()).hasSize(1);
        assertThat(msg.sharedCapabilities().get(0).name()).isEqualTo("prepare-meal");
    }

    @Test
    void forwardCompatibilityInCapabilityInfoIgnoresUnknownFields() throws Exception {
        // Simulate a future version that adds extra fields to SharedCapabilityInfo
        String json =
                """
                {
                    "type": "hello",
                    "snapshotTrace": null,
                    "sharedCapabilities": [
                        {
                            "name": "prepare-meal",
                            "description": "Prepare a meal",
                            "type": "TASK",
                            "tags": ["food", "kitchen"],
                            "version": "2.0"
                        }
                    ]
                }
                """;

        HelloMessage msg = mapper.readValue(json, HelloMessage.class);

        assertThat(msg.sharedCapabilities()).hasSize(1);
        assertThat(msg.sharedCapabilities().get(0).name()).isEqualTo("prepare-meal");
        assertThat(msg.sharedCapabilities().get(0).type()).isEqualTo("TASK");
    }

    @Test
    void fullSerializationRoundTrip() throws Exception {
        List<SharedCapabilityInfo> caps = List.of(
                new SharedCapabilityInfo("prepare-meal", "Prepare a meal as specified", "TASK"),
                new SharedCapabilityInfo("check-inventory", "Check ingredient availability", "TOOL"),
                new SharedCapabilityInfo("dietary-check", "Verify allergen safety", "TOOL"));

        HelloMessage original = new HelloMessage(null, caps);
        String json = mapper.writeValueAsString(original);
        HelloMessage deserialized = mapper.readValue(json, HelloMessage.class);

        assertThat(deserialized.sharedCapabilities()).hasSize(3);
        assertThat(deserialized.sharedCapabilities())
                .extracting(SharedCapabilityInfo::name)
                .containsExactly("prepare-meal", "check-inventory", "dietary-check");
        assertThat(deserialized.sharedCapabilities())
                .extracting(SharedCapabilityInfo::type)
                .containsExactly("TASK", "TOOL", "TOOL");
    }
}
