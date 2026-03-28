package net.agentensemble.audit;

/**
 * SPI for writing audit records to a backend.
 */
public interface AuditSink {
    void write(AuditRecord record);

    static AuditSink log() {
        return new LogAuditSink();
    }
}
