package net.agentensemble.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * AuditSink that writes records to SLF4J structured logging.
 */
final class LogAuditSink implements AuditSink {
    private static final Logger log = LoggerFactory.getLogger("agentensemble.audit");

    @Override
    public void write(AuditRecord record) {
        try {
            MDC.put("audit.level", record.level().name());
            MDC.put("audit.category", record.category());
            if (record.ensembleId() != null) MDC.put("audit.ensemble", record.ensembleId());
            if (record.traceId() != null) MDC.put("audit.traceId", record.traceId());

            log.info("[AUDIT] [{}] {} | {}", record.level(), record.category(), record.summary());
        } finally {
            MDC.remove("audit.level");
            MDC.remove("audit.category");
            MDC.remove("audit.ensemble");
            MDC.remove("audit.traceId");
        }
    }
}
