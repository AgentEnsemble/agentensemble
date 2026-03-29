package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedCapabilityInfo}.
 */
class SharedCapabilityInfoTest {

    private MessageSerializer serializer;

    @BeforeEach
    void setUp() {
        serializer = new MessageSerializer();
    }

    @Test
    void constructionWithAllFields() {
        SharedCapabilityInfo info = new SharedCapabilityInfo("prepare-meal", "Prepare a meal as specified", "TASK");
        assertThat(info.name()).isEqualTo("prepare-meal");
        assertThat(info.description()).isEqualTo("Prepare a meal as specified");
        assertThat(info.type()).isEqualTo("TASK");
        assertThat(info.tags()).isEmpty();
    }

    @Test
    void constructionWithTags() {
        SharedCapabilityInfo info =
                new SharedCapabilityInfo("prepare-meal", "Prepare a meal", "TASK", List.of("food", "kitchen"));
        assertThat(info.name()).isEqualTo("prepare-meal");
        assertThat(info.description()).isEqualTo("Prepare a meal");
        assertThat(info.type()).isEqualTo("TASK");
        assertThat(info.tags()).containsExactly("food", "kitchen");
    }

    @Test
    void nullTagsDefaultsToEmptyList() {
        SharedCapabilityInfo info = new SharedCapabilityInfo("check-inventory", "desc", "TOOL", null);
        assertThat(info.tags()).isEmpty();
    }

    @Test
    void nullDescriptionDefaultsToEmpty() {
        SharedCapabilityInfo info = new SharedCapabilityInfo("check-inventory", null, "TOOL");
        assertThat(info.description()).isEmpty();
    }

    @Test
    void nullNameThrows() {
        assertThatThrownBy(() -> new SharedCapabilityInfo(null, "desc", "TASK"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullTypeThrows() {
        assertThatThrownBy(() -> new SharedCapabilityInfo("name", "desc", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equality() {
        SharedCapabilityInfo a = new SharedCapabilityInfo("name", "desc", "TASK");
        SharedCapabilityInfo b = new SharedCapabilityInfo("name", "desc", "TASK");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void serializationRoundTripWithTags() {
        SharedCapabilityInfo original =
                new SharedCapabilityInfo("prepare-meal", "Prepare a meal", "TASK", List.of("food", "kitchen"));
        String json = serializer.toJson(original);

        assertThat(json).contains("\"tags\":[\"food\",\"kitchen\"]");

        SharedCapabilityInfo deserialized = serializer.fromJson(json, SharedCapabilityInfo.class);
        assertThat(deserialized.name()).isEqualTo("prepare-meal");
        assertThat(deserialized.tags()).containsExactly("food", "kitchen");
    }

    @Test
    void serializationRoundTripWithoutTags() {
        SharedCapabilityInfo original = new SharedCapabilityInfo("check-inventory", "Check stock", "TOOL");
        String json = serializer.toJson(original);

        SharedCapabilityInfo deserialized = serializer.fromJson(json, SharedCapabilityInfo.class);
        assertThat(deserialized.name()).isEqualTo("check-inventory");
        assertThat(deserialized.tags()).isEmpty();
    }
}
