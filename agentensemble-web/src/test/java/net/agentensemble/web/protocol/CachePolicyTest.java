package net.agentensemble.web.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CachePolicy}.
 */
class CachePolicyTest {

    @Test
    void allValuesExist() {
        assertThat(CachePolicy.values()).containsExactly(CachePolicy.USE_CACHED, CachePolicy.FORCE_FRESH);
    }

    @Test
    void valueOfRoundTrip() {
        for (CachePolicy cp : CachePolicy.values()) {
            assertThat(CachePolicy.valueOf(cp.name())).isEqualTo(cp);
        }
    }
}
