package net.agentensemble.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class MemoryScopeTest {

    // ========================
    // MemoryScope.of()
    // ========================

    @Test
    void testOf_validName_createsScope() {
        MemoryScope scope = MemoryScope.of("research");

        assertThat(scope.getName()).isEqualTo("research");
        assertThat(scope.getEvictionPolicy()).isNull();
    }

    @Test
    void testOf_nullName_throwsException() {
        assertThatThrownBy(() -> MemoryScope.of(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOf_blankName_throwsException() {
        assertThatThrownBy(() -> MemoryScope.of("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testOf_emptyName_throwsException() {
        assertThatThrownBy(() -> MemoryScope.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    // ========================
    // MemoryScope.builder()
    // ========================

    @Test
    void testBuilder_nameOnly_noEvictionPolicy() {
        MemoryScope scope = MemoryScope.builder().name("research").build();

        assertThat(scope.getName()).isEqualTo("research");
        assertThat(scope.getEvictionPolicy()).isNull();
    }

    @Test
    void testBuilder_withKeepLastEntries_setsEvictionPolicy() {
        MemoryScope scope =
                MemoryScope.builder().name("research").keepLastEntries(5).build();

        assertThat(scope.getName()).isEqualTo("research");
        assertThat(scope.getEvictionPolicy()).isNotNull();
    }

    @Test
    void testBuilder_withKeepEntriesWithin_setsEvictionPolicy() {
        MemoryScope scope = MemoryScope.builder()
                .name("research")
                .keepEntriesWithin(Duration.ofDays(7))
                .build();

        assertThat(scope.getName()).isEqualTo("research");
        assertThat(scope.getEvictionPolicy()).isNotNull();
    }

    @Test
    void testBuilder_nullName_throwsException() {
        assertThatThrownBy(() -> MemoryScope.builder().name(null).build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testBuilder_noName_throwsException() {
        assertThatThrownBy(() -> MemoryScope.builder().build()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testBuilder_lastConfigWins_keepLastAfterKeepWithin() {
        // Setting keepLastEntries after keepEntriesWithin: keepLast should win
        MemoryScope scope = MemoryScope.builder()
                .name("scope")
                .keepEntriesWithin(Duration.ofDays(1))
                .keepLastEntries(3)
                .build();

        assertThat(scope.getEvictionPolicy()).isNotNull();
        // Verify it behaves as keepLastEntries(3) -- 3 of 4 entries retained
        var entries = java.util.List.of(
                MemoryEntry.builder()
                        .content("a")
                        .storedAt(java.time.Instant.now())
                        .build(),
                MemoryEntry.builder()
                        .content("b")
                        .storedAt(java.time.Instant.now())
                        .build(),
                MemoryEntry.builder()
                        .content("c")
                        .storedAt(java.time.Instant.now())
                        .build(),
                MemoryEntry.builder()
                        .content("d")
                        .storedAt(java.time.Instant.now())
                        .build());
        assertThat(scope.getEvictionPolicy().apply(entries)).hasSize(3);
    }

    @Test
    void testToString_containsName() {
        MemoryScope scope = MemoryScope.of("research");
        assertThat(scope.toString()).contains("research");
    }
}
