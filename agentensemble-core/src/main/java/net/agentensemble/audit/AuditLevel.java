package net.agentensemble.audit;

/**
 * Configurable audit verbosity levels (similar to CaptureMode).
 */
public enum AuditLevel {
    OFF,
    MINIMAL,
    STANDARD,
    FULL;

    public boolean isAtLeast(AuditLevel threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
