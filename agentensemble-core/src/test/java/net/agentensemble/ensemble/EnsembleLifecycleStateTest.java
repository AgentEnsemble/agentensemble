package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EnsembleLifecycleState}.
 */
class EnsembleLifecycleStateTest {

    @Test
    void allFourStatesExist() {
        EnsembleLifecycleState[] values = EnsembleLifecycleState.values();
        assertThat(values).hasSize(4);
        assertThat(values)
                .containsExactly(
                        EnsembleLifecycleState.STARTING,
                        EnsembleLifecycleState.READY,
                        EnsembleLifecycleState.DRAINING,
                        EnsembleLifecycleState.STOPPED);
    }

    @Test
    void valueOfRoundTrips() {
        for (EnsembleLifecycleState state : EnsembleLifecycleState.values()) {
            assertThat(EnsembleLifecycleState.valueOf(state.name())).isEqualTo(state);
        }
    }
}
