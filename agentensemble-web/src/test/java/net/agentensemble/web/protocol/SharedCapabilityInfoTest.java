package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SharedCapabilityInfo}.
 */
class SharedCapabilityInfoTest {

    @Test
    void constructionWithAllFields() {
        SharedCapabilityInfo info = new SharedCapabilityInfo("prepare-meal", "Prepare a meal as specified", "TASK");
        assertThat(info.name()).isEqualTo("prepare-meal");
        assertThat(info.description()).isEqualTo("Prepare a meal as specified");
        assertThat(info.type()).isEqualTo("TASK");
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
}
