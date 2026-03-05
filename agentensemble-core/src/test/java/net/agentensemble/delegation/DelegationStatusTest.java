package net.agentensemble.delegation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DelegationStatusTest {

    @Test
    void testEnumValues_containsAllExpectedValues() {
        DelegationStatus[] values = DelegationStatus.values();
        assertThat(values)
                .containsExactlyInAnyOrder(
                        DelegationStatus.SUCCESS, DelegationStatus.FAILURE, DelegationStatus.PARTIAL);
    }

    @Test
    void testValueOf_success() {
        assertThat(DelegationStatus.valueOf("SUCCESS")).isEqualTo(DelegationStatus.SUCCESS);
    }

    @Test
    void testValueOf_failure() {
        assertThat(DelegationStatus.valueOf("FAILURE")).isEqualTo(DelegationStatus.FAILURE);
    }

    @Test
    void testValueOf_partial() {
        assertThat(DelegationStatus.valueOf("PARTIAL")).isEqualTo(DelegationStatus.PARTIAL);
    }
}
