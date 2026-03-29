package net.agentensemble.network.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.agentensemble.memory.MemoryEntry;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VersionedResult}.
 */
class VersionedResultTest {

    @Test
    void validConstruction() {
        MemoryEntry entry =
                MemoryEntry.builder().content("test").storedAt(Instant.now()).build();

        VersionedResult vr = new VersionedResult(List.of(entry), 3);

        assertThat(vr.entries()).hasSize(1);
        assertThat(vr.version()).isEqualTo(3);
    }

    @Test
    void nullEntries_throwsNPE() {
        assertThatThrownBy(() -> new VersionedResult(null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entries");
    }

    @Test
    void negativeVersion_throwsIllegalArgument() {
        assertThatThrownBy(() -> new VersionedResult(List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void entries_areDefensivelyCopied() {
        MemoryEntry entry =
                MemoryEntry.builder().content("test").storedAt(Instant.now()).build();

        List<MemoryEntry> mutableList = new ArrayList<>();
        mutableList.add(entry);

        VersionedResult vr = new VersionedResult(mutableList, 0);
        mutableList.add(entry); // modify original

        assertThat(vr.entries()).hasSize(1); // not affected
    }

    @Test
    void entries_areImmutable() {
        VersionedResult vr = new VersionedResult(List.of(), 0);

        assertThat(vr.entries()).isUnmodifiable();
    }
}
