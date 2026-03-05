package net.agentensemble.delegation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DelegationPriorityTest {

    @Test
    void testEnumValues_containsAllExpectedValues() {
        DelegationPriority[] values = DelegationPriority.values();
        assertThat(values)
                .containsExactlyInAnyOrder(
                        DelegationPriority.LOW,
                        DelegationPriority.NORMAL,
                        DelegationPriority.HIGH,
                        DelegationPriority.CRITICAL);
    }

    @Test
    void testValueOf_normal() {
        assertThat(DelegationPriority.valueOf("NORMAL")).isEqualTo(DelegationPriority.NORMAL);
    }

    @Test
    void testValueOf_low() {
        assertThat(DelegationPriority.valueOf("LOW")).isEqualTo(DelegationPriority.LOW);
    }

    @Test
    void testValueOf_high() {
        assertThat(DelegationPriority.valueOf("HIGH")).isEqualTo(DelegationPriority.HIGH);
    }

    @Test
    void testValueOf_critical() {
        assertThat(DelegationPriority.valueOf("CRITICAL")).isEqualTo(DelegationPriority.CRITICAL);
    }

    @Test
    void testOrdinal_lowIsLessThanNormal() {
        assertThat(DelegationPriority.LOW.ordinal()).isLessThan(DelegationPriority.NORMAL.ordinal());
    }

    @Test
    void testOrdinal_normalIsLessThanHigh() {
        assertThat(DelegationPriority.NORMAL.ordinal()).isLessThan(DelegationPriority.HIGH.ordinal());
    }

    @Test
    void testOrdinal_highIsLessThanCritical() {
        assertThat(DelegationPriority.HIGH.ordinal()).isLessThan(DelegationPriority.CRITICAL.ordinal());
    }
}
