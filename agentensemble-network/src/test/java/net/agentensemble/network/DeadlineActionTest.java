package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DeadlineAction} enum.
 */
class DeadlineActionTest {

    @Test
    void allThreeValuesExist() {
        assertThat(DeadlineAction.values())
                .containsExactly(
                        DeadlineAction.RETURN_TIMEOUT_ERROR,
                        DeadlineAction.RETURN_PARTIAL,
                        DeadlineAction.CONTINUE_IN_BACKGROUND);
    }

    @Test
    void valueOf_returnTimeoutError() {
        assertThat(DeadlineAction.valueOf("RETURN_TIMEOUT_ERROR")).isEqualTo(DeadlineAction.RETURN_TIMEOUT_ERROR);
    }

    @Test
    void valueOf_returnPartial() {
        assertThat(DeadlineAction.valueOf("RETURN_PARTIAL")).isEqualTo(DeadlineAction.RETURN_PARTIAL);
    }

    @Test
    void valueOf_continueInBackground() {
        assertThat(DeadlineAction.valueOf("CONTINUE_IN_BACKGROUND")).isEqualTo(DeadlineAction.CONTINUE_IN_BACKGROUND);
    }
}
