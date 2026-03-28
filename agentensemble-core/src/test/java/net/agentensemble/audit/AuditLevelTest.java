package net.agentensemble.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditLevelTest {

    @Test
    void isAtLeast_sameLevel_returnsTrue() {
        for (AuditLevel level : AuditLevel.values()) {
            assertThat(level.isAtLeast(level))
                    .as("%s.isAtLeast(%s)", level, level)
                    .isTrue();
        }
    }

    @Test
    void isAtLeast_higherLevel_returnsTrue() {
        assertThat(AuditLevel.FULL.isAtLeast(AuditLevel.OFF)).isTrue();
        assertThat(AuditLevel.FULL.isAtLeast(AuditLevel.MINIMAL)).isTrue();
        assertThat(AuditLevel.FULL.isAtLeast(AuditLevel.STANDARD)).isTrue();
        assertThat(AuditLevel.STANDARD.isAtLeast(AuditLevel.OFF)).isTrue();
        assertThat(AuditLevel.STANDARD.isAtLeast(AuditLevel.MINIMAL)).isTrue();
        assertThat(AuditLevel.MINIMAL.isAtLeast(AuditLevel.OFF)).isTrue();
    }

    @Test
    void isAtLeast_lowerLevel_returnsFalse() {
        assertThat(AuditLevel.OFF.isAtLeast(AuditLevel.MINIMAL)).isFalse();
        assertThat(AuditLevel.OFF.isAtLeast(AuditLevel.STANDARD)).isFalse();
        assertThat(AuditLevel.OFF.isAtLeast(AuditLevel.FULL)).isFalse();
        assertThat(AuditLevel.MINIMAL.isAtLeast(AuditLevel.STANDARD)).isFalse();
        assertThat(AuditLevel.MINIMAL.isAtLeast(AuditLevel.FULL)).isFalse();
        assertThat(AuditLevel.STANDARD.isAtLeast(AuditLevel.FULL)).isFalse();
    }

    @Test
    void ordinal_order_isCorrect() {
        assertThat(AuditLevel.OFF.ordinal()).isLessThan(AuditLevel.MINIMAL.ordinal());
        assertThat(AuditLevel.MINIMAL.ordinal()).isLessThan(AuditLevel.STANDARD.ordinal());
        assertThat(AuditLevel.STANDARD.ordinal()).isLessThan(AuditLevel.FULL.ordinal());
    }
}
