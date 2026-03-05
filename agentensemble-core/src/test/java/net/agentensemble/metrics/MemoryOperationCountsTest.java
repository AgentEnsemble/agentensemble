package net.agentensemble.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for MemoryOperationCounts builder and add(). */
class MemoryOperationCountsTest {

    @Test
    void testZero_allCountsZero() {
        assertThat(MemoryOperationCounts.ZERO.getShortTermEntriesWritten()).isZero();
        assertThat(MemoryOperationCounts.ZERO.getLongTermStores()).isZero();
        assertThat(MemoryOperationCounts.ZERO.getLongTermRetrievals()).isZero();
        assertThat(MemoryOperationCounts.ZERO.getEntityLookups()).isZero();
    }

    @Test
    void testAdd_sumsCountsCorrectly() {
        MemoryOperationCounts a = MemoryOperationCounts.builder()
                .shortTermEntriesWritten(2)
                .longTermStores(1)
                .longTermRetrievals(3)
                .entityLookups(4)
                .build();
        MemoryOperationCounts b = MemoryOperationCounts.builder()
                .shortTermEntriesWritten(1)
                .longTermStores(2)
                .longTermRetrievals(1)
                .entityLookups(0)
                .build();

        MemoryOperationCounts result = a.add(b);

        assertThat(result.getShortTermEntriesWritten()).isEqualTo(3);
        assertThat(result.getLongTermStores()).isEqualTo(3);
        assertThat(result.getLongTermRetrievals()).isEqualTo(4);
        assertThat(result.getEntityLookups()).isEqualTo(4);
    }

    @Test
    void testAdd_nullOther_returnsSelf() {
        MemoryOperationCounts a =
                MemoryOperationCounts.builder().shortTermEntriesWritten(2).build();

        assertThat(a.add(null)).isSameAs(a);
    }

    @Test
    void testAdd_zeroOther_returnsSelf() {
        MemoryOperationCounts a =
                MemoryOperationCounts.builder().shortTermEntriesWritten(2).build();

        assertThat(a.add(MemoryOperationCounts.ZERO)).isSameAs(a);
    }

    @Test
    void testAdd_zeroToZero_returnsZero() {
        MemoryOperationCounts result = MemoryOperationCounts.ZERO.add(MemoryOperationCounts.ZERO);

        assertThat(result.getShortTermEntriesWritten()).isZero();
    }
}
