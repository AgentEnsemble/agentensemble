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

    @Test
    void add_freshZeroInstance_returnsSelfViaValueEquality() {
        // Regression test: previously used == (reference equality) so only the ZERO
        // singleton would short-circuit. After the fix, any instance that equals ZERO
        // (i.e., all-zero counts) must also short-circuit.
        MemoryOperationCounts a =
                MemoryOperationCounts.builder().shortTermEntriesWritten(3).build();

        MemoryOperationCounts freshZero = MemoryOperationCounts.builder().build(); // all-zero, but not ZERO singleton
        assertThat(freshZero).isNotSameAs(MemoryOperationCounts.ZERO); // pre-condition: different reference
        assertThat(freshZero).isEqualTo(MemoryOperationCounts.ZERO); // pre-condition: equal by value

        assertThat(a.add(freshZero)).isSameAs(a); // must return 'a' unchanged
    }
}
