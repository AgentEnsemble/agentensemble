package net.agentensemble.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditRecordTest {

    @Test
    void details_areCopied_andImmutable() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("key", "value");

        AuditRecord record =
                new AuditRecord(Instant.now(), AuditLevel.STANDARD, "ens-1", "test", "summary", null, mutable);

        // Modifying the original map should not affect the record
        mutable.put("key2", "value2");
        assertThat(record.details()).hasSize(1);
        assertThat(record.details()).containsEntry("key", "value");

        // The details map itself should be unmodifiable
        assertThatThrownBy(() -> record.details().put("newKey", "newValue"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullDetails_becomesEmptyMap() {
        AuditRecord record = new AuditRecord(Instant.now(), AuditLevel.MINIMAL, "ens-1", "test", "summary", null, null);

        assertThat(record.details()).isNotNull().isEmpty();
    }

    @Test
    void nullTimestamp_throwsNPE() {
        assertThatThrownBy(() -> new AuditRecord(null, AuditLevel.STANDARD, "ens-1", "test", "summary", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("timestamp");
    }

    @Test
    void nullLevel_throwsNPE() {
        assertThatThrownBy(() -> new AuditRecord(Instant.now(), null, "ens-1", "test", "summary", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("level");
    }

    @Test
    void nullCategory_throwsNPE() {
        assertThatThrownBy(
                        () -> new AuditRecord(Instant.now(), AuditLevel.STANDARD, "ens-1", null, "summary", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("category");
    }

    @Test
    void validRecord_preservesAllFields() {
        Instant ts = Instant.now();
        Map<String, Object> details = Map.of("k", "v");

        AuditRecord record =
                new AuditRecord(ts, AuditLevel.FULL, "ens-42", "delegation.start", "summary text", "trace-1", details);

        assertThat(record.timestamp()).isEqualTo(ts);
        assertThat(record.level()).isEqualTo(AuditLevel.FULL);
        assertThat(record.ensembleId()).isEqualTo("ens-42");
        assertThat(record.category()).isEqualTo("delegation.start");
        assertThat(record.summary()).isEqualTo("summary text");
        assertThat(record.traceId()).isEqualTo("trace-1");
        assertThat(record.details()).containsEntry("k", "v");
    }
}
