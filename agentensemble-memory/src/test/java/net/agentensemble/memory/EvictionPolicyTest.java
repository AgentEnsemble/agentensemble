package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvictionPolicyTest {

    private MemoryEntry entry(String content, Instant storedAt) {
        return MemoryEntry.builder()
                .content(content)
                .storedAt(storedAt)
                .metadata(Map.of())
                .build();
    }

    // ========================
    // keepLastEntries -- validation
    // ========================

    @Test
    void testKeepLastEntries_zeroN_throwsException() {
        assertThatThrownBy(() -> EvictionPolicy.keepLastEntries(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testKeepLastEntries_negativeN_throwsException() {
        assertThatThrownBy(() -> EvictionPolicy.keepLastEntries(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // keepLastEntries -- behavior
    // ========================

    @Test
    void testKeepLastEntries_fewerEntriesThanN_keepsAll() {
        EvictionPolicy policy = EvictionPolicy.keepLastEntries(5);
        List<MemoryEntry> entries =
                List.of(entry("A", Instant.now()), entry("B", Instant.now()), entry("C", Instant.now()));

        List<MemoryEntry> retained = policy.apply(entries);

        assertThat(retained).hasSize(3);
        assertThat(retained.stream().map(MemoryEntry::getContent)).containsExactly("A", "B", "C");
    }

    @Test
    void testKeepLastEntries_exactlyN_keepsAll() {
        EvictionPolicy policy = EvictionPolicy.keepLastEntries(3);
        List<MemoryEntry> entries =
                List.of(entry("A", Instant.now()), entry("B", Instant.now()), entry("C", Instant.now()));

        List<MemoryEntry> retained = policy.apply(entries);

        assertThat(retained).hasSize(3);
    }

    @Test
    void testKeepLastEntries_moreThanN_evictsOldest() {
        EvictionPolicy policy = EvictionPolicy.keepLastEntries(2);
        List<MemoryEntry> entries = List.of(
                entry("First", Instant.now()),
                entry("Second", Instant.now()),
                entry("Third", Instant.now()),
                entry("Fourth", Instant.now()));

        List<MemoryEntry> retained = policy.apply(entries);

        assertThat(retained).hasSize(2);
        assertThat(retained.stream().map(MemoryEntry::getContent)).containsExactly("Third", "Fourth");
    }

    @Test
    void testKeepLastEntries_emptyList_returnsEmpty() {
        EvictionPolicy policy = EvictionPolicy.keepLastEntries(3);

        List<MemoryEntry> retained = policy.apply(List.of());

        assertThat(retained).isEmpty();
    }

    @Test
    void testKeepLastEntries_returnedListIsUnmodifiable() {
        EvictionPolicy policy = EvictionPolicy.keepLastEntries(1);
        List<MemoryEntry> entries = List.of(entry("A", Instant.now()));

        List<MemoryEntry> retained = policy.apply(entries);

        assertThatThrownBy(() -> retained.add(entry("B", Instant.now())))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // keepEntriesWithin -- validation
    // ========================

    @Test
    void testKeepEntriesWithin_nullDuration_throwsException() {
        assertThatThrownBy(() -> EvictionPolicy.keepEntriesWithin(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testKeepEntriesWithin_zeroDuration_throwsException() {
        assertThatThrownBy(() -> EvictionPolicy.keepEntriesWithin(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testKeepEntriesWithin_negativeDuration_throwsException() {
        assertThatThrownBy(() -> EvictionPolicy.keepEntriesWithin(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // keepEntriesWithin -- behavior
    // ========================

    @Test
    void testKeepEntriesWithin_recentEntriesKept() {
        EvictionPolicy policy = EvictionPolicy.keepEntriesWithin(Duration.ofHours(24));

        Instant recent = Instant.now().minusSeconds(60);
        Instant old = Instant.now().minus(Duration.ofDays(2));

        List<MemoryEntry> entries = List.of(entry("Recent", recent), entry("Old", old));

        List<MemoryEntry> retained = policy.apply(entries);

        assertThat(retained).hasSize(1);
        assertThat(retained.get(0).getContent()).isEqualTo("Recent");
    }

    @Test
    void testKeepEntriesWithin_allEntriesOld_returnsEmpty() {
        EvictionPolicy policy = EvictionPolicy.keepEntriesWithin(Duration.ofMinutes(5));

        Instant old = Instant.now().minus(Duration.ofHours(1));

        List<MemoryEntry> entries = List.of(entry("Old A", old), entry("Old B", old));

        List<MemoryEntry> retained = policy.apply(entries);

        assertThat(retained).isEmpty();
    }

    @Test
    void testKeepEntriesWithin_nullStoredAt_entryEvicted() {
        EvictionPolicy policy = EvictionPolicy.keepEntriesWithin(Duration.ofHours(24));
        MemoryEntry entryWithNullTimestamp =
                MemoryEntry.builder().content("no timestamp").build();

        List<MemoryEntry> retained = policy.apply(List.of(entryWithNullTimestamp));

        assertThat(retained).isEmpty();
    }
}
