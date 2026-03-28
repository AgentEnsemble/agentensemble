package net.agentensemble.network.memory;

/**
 * Thrown when an optimistic write fails due to a version conflict.
 */
public class ConcurrentMemoryStoreException extends RuntimeException {

    private final String scope;
    private final long expectedVersion;

    public ConcurrentMemoryStoreException(String scope, long expectedVersion) {
        super("Version conflict in scope '" + scope + "': expected version " + expectedVersion);
        this.scope = scope;
        this.expectedVersion = expectedVersion;
    }

    public String scope() {
        return scope;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
