package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Priority}.
 */
class PriorityTest {

    @Test
    void allValuesExist() {
        assertThat(Priority.values()).containsExactly(Priority.CRITICAL, Priority.HIGH, Priority.NORMAL, Priority.LOW);
    }

    @Test
    void ordinalOrdering() {
        assertThat(Priority.CRITICAL.ordinal()).isLessThan(Priority.HIGH.ordinal());
        assertThat(Priority.HIGH.ordinal()).isLessThan(Priority.NORMAL.ordinal());
        assertThat(Priority.NORMAL.ordinal()).isLessThan(Priority.LOW.ordinal());
    }

    @Test
    void valueOfRoundTrip() {
        for (Priority p : Priority.values()) {
            assertThat(Priority.valueOf(p.name())).isEqualTo(p);
        }
    }
}
