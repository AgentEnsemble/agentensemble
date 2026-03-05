package net.agentensemble.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for CaptureMode: enum values, isAtLeast comparisons, and
 * resolve() logic for the programmatic / system property / default chain.
 *
 * Environment variable resolution is not tested here because setting real
 * environment variables from tests is not portable; that path is covered
 * by the integration tests.
 */
class CaptureModeTest {

    @AfterEach
    void clearSystemProperty() {
        System.clearProperty(CaptureMode.SYSTEM_PROPERTY);
    }

    // ========================
    // Enum ordering
    // ========================

    @Test
    void values_orderedCorrectly() {
        assertThat(CaptureMode.OFF.compareTo(CaptureMode.STANDARD)).isLessThan(0);
        assertThat(CaptureMode.STANDARD.compareTo(CaptureMode.FULL)).isLessThan(0);
        assertThat(CaptureMode.OFF.compareTo(CaptureMode.FULL)).isLessThan(0);
    }

    // ========================
    // isAtLeast
    // ========================

    @Test
    void isAtLeast_off_isAtLeastOff() {
        assertThat(CaptureMode.OFF.isAtLeast(CaptureMode.OFF)).isTrue();
    }

    @Test
    void isAtLeast_off_isNotAtLeastStandard() {
        assertThat(CaptureMode.OFF.isAtLeast(CaptureMode.STANDARD)).isFalse();
    }

    @Test
    void isAtLeast_off_isNotAtLeastFull() {
        assertThat(CaptureMode.OFF.isAtLeast(CaptureMode.FULL)).isFalse();
    }

    @Test
    void isAtLeast_standard_isAtLeastStandard() {
        assertThat(CaptureMode.STANDARD.isAtLeast(CaptureMode.STANDARD)).isTrue();
    }

    @Test
    void isAtLeast_standard_isAtLeastOff() {
        assertThat(CaptureMode.STANDARD.isAtLeast(CaptureMode.OFF)).isTrue();
    }

    @Test
    void isAtLeast_standard_isNotAtLeastFull() {
        assertThat(CaptureMode.STANDARD.isAtLeast(CaptureMode.FULL)).isFalse();
    }

    @Test
    void isAtLeast_full_isAtLeastAll() {
        assertThat(CaptureMode.FULL.isAtLeast(CaptureMode.OFF)).isTrue();
        assertThat(CaptureMode.FULL.isAtLeast(CaptureMode.STANDARD)).isTrue();
        assertThat(CaptureMode.FULL.isAtLeast(CaptureMode.FULL)).isTrue();
    }

    // ========================
    // resolve -- programmatic wins
    // ========================

    @Test
    void resolve_withStandardProgrammatic_returnsStandardRegardlessOfSystemProperty() {
        System.setProperty(CaptureMode.SYSTEM_PROPERTY, "FULL");
        assertThat(CaptureMode.resolve(CaptureMode.STANDARD)).isEqualTo(CaptureMode.STANDARD);
    }

    @Test
    void resolve_withFullProgrammatic_returnsFull() {
        assertThat(CaptureMode.resolve(CaptureMode.FULL)).isEqualTo(CaptureMode.FULL);
    }

    // ========================
    // resolve -- system property fallback
    // ========================

    @Test
    void resolve_withOffProgrammatic_andNoSystemProperty_returnsOff() {
        assertThat(CaptureMode.resolve(CaptureMode.OFF)).isEqualTo(CaptureMode.OFF);
    }

    @Test
    void resolve_withOffProgrammatic_andSystemPropertyFull_returnsFull() {
        System.setProperty(CaptureMode.SYSTEM_PROPERTY, "FULL");
        assertThat(CaptureMode.resolve(CaptureMode.OFF)).isEqualTo(CaptureMode.FULL);
    }

    @Test
    void resolve_withOffProgrammatic_andSystemPropertyStandard_returnsStandard() {
        System.setProperty(CaptureMode.SYSTEM_PROPERTY, "STANDARD");
        assertThat(CaptureMode.resolve(CaptureMode.OFF)).isEqualTo(CaptureMode.STANDARD);
    }

    @Test
    void resolve_systemPropertyIsCaseInsensitive() {
        System.setProperty(CaptureMode.SYSTEM_PROPERTY, "full");
        assertThat(CaptureMode.resolve(CaptureMode.OFF)).isEqualTo(CaptureMode.FULL);

        System.setProperty(CaptureMode.SYSTEM_PROPERTY, "Standard");
        assertThat(CaptureMode.resolve(CaptureMode.OFF)).isEqualTo(CaptureMode.STANDARD);
    }

    @Test
    void resolve_systemPropertyBlank_fallsBackToOff() {
        System.setProperty(CaptureMode.SYSTEM_PROPERTY, "  ");
        assertThat(CaptureMode.resolve(CaptureMode.OFF)).isEqualTo(CaptureMode.OFF);
    }

    @Test
    void resolve_systemPropertyInvalid_logsWarnAndFallsBackToOff() {
        System.setProperty(CaptureMode.SYSTEM_PROPERTY, "VERBOSE");
        // Should not throw; should fall through to OFF
        assertThat(CaptureMode.resolve(CaptureMode.OFF)).isEqualTo(CaptureMode.OFF);
    }

    @Test
    void resolve_withNullProgrammatic_returnsOff() {
        assertThat(CaptureMode.resolve(null)).isEqualTo(CaptureMode.OFF);
    }

    // ========================
    // String constants
    // ========================

    @Test
    void constants_haveExpectedValues() {
        assertThat(CaptureMode.SYSTEM_PROPERTY).isEqualTo("agentensemble.captureMode");
        assertThat(CaptureMode.ENV_VAR).isEqualTo("AGENTENSEMBLE_CAPTURE_MODE");
    }
}
