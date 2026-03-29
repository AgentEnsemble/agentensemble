package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import net.agentensemble.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VersionedEntry}.
 */
class VersionedEntryTest {

    @Test
    void validConstruction() {
        MemoryEntry entry =
                MemoryEntry.builder().content("test").storedAt(Instant.now()).build();

        VersionedEntry ve = new VersionedEntry(entry, 5);

        assertThat(ve.entry()).isSameAs(entry);
        assertThat(ve.version()).isEqualTo(5);
    }

    @Test
    void nullEntry_throwsNPE() {
        assertThatThrownBy(() -> new VersionedEntry(null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entry");
    }

    @Test
    void negativeVersion_throwsIllegalArgument() {
        MemoryEntry entry =
                MemoryEntry.builder().content("test").storedAt(Instant.now()).build();

        assertThatThrownBy(() -> new VersionedEntry(entry, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void zeroVersion_isValid() {
        MemoryEntry entry =
                MemoryEntry.builder().content("test").storedAt(Instant.now()).build();

        VersionedEntry ve = new VersionedEntry(entry, 0);
        assertThat(ve.version()).isZero();
    }
}
