package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FileChangedEvent} record construction and field access.
 */
class FileChangedEventTest {

    @Test
    void constructionAndFieldAccess() {
        Instant ts = Instant.parse("2026-03-05T14:00:00Z");
        FileChangedEvent event = new FileChangedEvent("Coder", "src/Main.java", "MODIFIED", 10, 3, ts);

        assertThat(event.agentRole()).isEqualTo("Coder");
        assertThat(event.filePath()).isEqualTo("src/Main.java");
        assertThat(event.changeType()).isEqualTo("MODIFIED");
        assertThat(event.linesAdded()).isEqualTo(10);
        assertThat(event.linesRemoved()).isEqualTo(3);
        assertThat(event.timestamp()).isEqualTo(ts);
    }

    @Test
    void createdFileEvent() {
        Instant ts = Instant.now();
        FileChangedEvent event = new FileChangedEvent("Writer", "docs/README.md", "CREATED", 25, 0, ts);

        assertThat(event.changeType()).isEqualTo("CREATED");
        assertThat(event.linesAdded()).isEqualTo(25);
        assertThat(event.linesRemoved()).isEqualTo(0);
    }

    @Test
    void deletedFileEvent() {
        Instant ts = Instant.now();
        FileChangedEvent event = new FileChangedEvent("Cleaner", "tmp/old.txt", "DELETED", 0, 50, ts);

        assertThat(event.changeType()).isEqualTo("DELETED");
        assertThat(event.linesAdded()).isEqualTo(0);
        assertThat(event.linesRemoved()).isEqualTo(50);
    }

    @Test
    void nullFieldsPermitted() {
        FileChangedEvent event = new FileChangedEvent(null, null, null, 0, 0, null);

        assertThat(event.agentRole()).isNull();
        assertThat(event.filePath()).isNull();
        assertThat(event.changeType()).isNull();
        assertThat(event.timestamp()).isNull();
    }

    @Test
    void recordEquality() {
        Instant ts = Instant.parse("2026-03-05T14:00:00Z");
        FileChangedEvent event1 = new FileChangedEvent("Agent", "file.txt", "CREATED", 5, 0, ts);
        FileChangedEvent event2 = new FileChangedEvent("Agent", "file.txt", "CREATED", 5, 0, ts);

        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }

    @Test
    void recordToStringContainsFields() {
        Instant ts = Instant.now();
        FileChangedEvent event = new FileChangedEvent("Dev", "app.py", "MODIFIED", 2, 1, ts);

        assertThat(event.toString()).contains("Dev");
        assertThat(event.toString()).contains("app.py");
        assertThat(event.toString()).contains("MODIFIED");
    }
}
