package net.agentensemble.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, timestamped audit record. All records are append-only and correlatable via traceId.
 */
public record AuditRecord(
        Instant timestamp,
        AuditLevel level,
        String ensembleId,
        String category,
        String summary,
        String traceId,
        Map<String, Object> details) {

    public AuditRecord {
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(category, "category must not be null");
        details = details != null ? Map.copyOf(details) : Map.of();
    }
}
