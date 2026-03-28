package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedCapability} and {@link SharedCapabilityType}.
 */
class SharedCapabilityTest {

    @Test
    void constructionWithValidArguments() {
        SharedCapability cap =
                new SharedCapability("prepare-meal", "Prepare a meal as specified", SharedCapabilityType.TASK);
        assertThat(cap.name()).isEqualTo("prepare-meal");
        assertThat(cap.description()).isEqualTo("Prepare a meal as specified");
        assertThat(cap.type()).isEqualTo(SharedCapabilityType.TASK);
        assertThat(cap.tags()).isEmpty();
    }

    @Test
    void constructionWithTags() {
        SharedCapability cap = new SharedCapability(
                "prepare-meal", "Prepare a meal", SharedCapabilityType.TASK, List.of("food", "kitchen"));
        assertThat(cap.tags()).containsExactly("food", "kitchen");
    }

    @Test
    void nullTagsDefaultsToEmptyList() {
        SharedCapability cap = new SharedCapability("prepare-meal", "Prepare a meal", SharedCapabilityType.TASK, null);
        assertThat(cap.tags()).isEmpty();
    }

    @Test
    void threeArgConstructorDefaultsTagsToEmpty() {
        SharedCapability cap = new SharedCapability("prepare-meal", "Prepare a meal", SharedCapabilityType.TASK);
        assertThat(cap.tags()).isEmpty();
    }

    @Test
    void toolCapabilityType() {
        SharedCapability cap =
                new SharedCapability("check-inventory", "Check ingredient availability", SharedCapabilityType.TOOL);
        assertThat(cap.type()).isEqualTo(SharedCapabilityType.TOOL);
    }

    @Test
    void nullNameThrows() {
        assertThatThrownBy(() -> new SharedCapability(null, "desc", SharedCapabilityType.TASK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @Test
    void blankNameThrows() {
        assertThatThrownBy(() -> new SharedCapability("  ", "desc", SharedCapabilityType.TASK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void nullDescriptionThrows() {
        assertThatThrownBy(() -> new SharedCapability("name", null, SharedCapabilityType.TASK))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("description");
    }

    @Test
    void nullTypeThrows() {
        assertThatThrownBy(() -> new SharedCapability("name", "desc", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("type");
    }

    @Test
    void equalityBasedOnAllFields() {
        SharedCapability a = new SharedCapability("name", "desc", SharedCapabilityType.TASK);
        SharedCapability b = new SharedCapability("name", "desc", SharedCapabilityType.TASK);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void equalityIncludesTags() {
        SharedCapability a = new SharedCapability("name", "desc", SharedCapabilityType.TASK, List.of("tag1"));
        SharedCapability b = new SharedCapability("name", "desc", SharedCapabilityType.TASK, List.of("tag1"));
        SharedCapability c = new SharedCapability("name", "desc", SharedCapabilityType.TASK, List.of("tag2"));
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void inequalityWhenTypesDiffer() {
        SharedCapability task = new SharedCapability("name", "desc", SharedCapabilityType.TASK);
        SharedCapability tool = new SharedCapability("name", "desc", SharedCapabilityType.TOOL);
        assertThat(task).isNotEqualTo(tool);
    }

    @Test
    void sharedCapabilityTypeValues() {
        assertThat(SharedCapabilityType.values()).containsExactly(SharedCapabilityType.TASK, SharedCapabilityType.TOOL);
    }
}
