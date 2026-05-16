package net.agentensemble.ensemble;

/**
 * A long-lived external resource (subprocess, connection, server) whose lifecycle can be
 * tied to an {@link net.agentensemble.Ensemble}.
 *
 * <p>Register via {@code Ensemble.builder().managedResource(...)}. The ensemble starts the
 * resource (if not already running) before the first run or before transitioning to
 * {@code READY}, and closes any resource it owns when {@link net.agentensemble.Ensemble#stop()}
 * fires. A resource that was already running when registered is treated as caller-owned and
 * is never closed by the ensemble.
 *
 * <p>Implementations must make {@link #close()} idempotent and {@link #isRunning()} cheap
 * and side-effect-free.
 *
 * <p>The canonical implementation is
 * {@code net.agentensemble.mcp.McpServerLifecycle}, which manages an MCP server subprocess.
 */
public interface ManagedResource extends AutoCloseable {

    /**
     * Start the resource. Implementations should be tolerant of being called when the
     * resource is already running (no-op or idempotent), and should support restarting
     * after a prior {@link #close()} so callers can recover from a stopped state.
     */
    void start();

    /**
     * @return true when the resource is started and not yet closed
     */
    boolean isRunning();

    /**
     * Release the resource. Must be idempotent; subsequent calls after the first must
     * be safe no-ops. Implementations should not throw checked exceptions.
     */
    @Override
    void close();
}
