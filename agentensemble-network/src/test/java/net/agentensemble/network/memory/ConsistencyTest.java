package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Consistency}.
 */
class ConsistencyTest {

    @Test
    void allExpectedValues_exist() {
        assertThat(Consistency.values())
                .containsExactly(
                        Consistency.EVENTUAL, Consistency.LOCKED, Consistency.OPTIMISTIC, Consistency.EXTERNAL);
    }

    @Test
    void valueOf_roundTrip() {
        for (Consistency c : Consistency.values()) {
            assertThat(Consistency.valueOf(c.name())).isEqualTo(c);
        }
    }
}
