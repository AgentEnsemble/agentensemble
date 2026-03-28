package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RequestMode} enum.
 */
class RequestModeTest {

    @Test
    void allThreeValuesExist() {
        assertThat(RequestMode.values())
                .containsExactly(RequestMode.AWAIT, RequestMode.ASYNC, RequestMode.AWAIT_WITH_DEADLINE);
    }

    @Test
    void valueOf_await() {
        assertThat(RequestMode.valueOf("AWAIT")).isEqualTo(RequestMode.AWAIT);
    }

    @Test
    void valueOf_async() {
        assertThat(RequestMode.valueOf("ASYNC")).isEqualTo(RequestMode.ASYNC);
    }

    @Test
    void valueOf_awaitWithDeadline() {
        assertThat(RequestMode.valueOf("AWAIT_WITH_DEADLINE")).isEqualTo(RequestMode.AWAIT_WITH_DEADLINE);
    }
}
