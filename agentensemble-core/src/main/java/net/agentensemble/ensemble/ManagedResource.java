package net.agentensemble.ensemble;

/**
 * A long-lived external resource (subprocess, connection, server) whose lifecycle can be
 * tied to an {@link net.agentensemble.Ensemble}.
 *
 * <p>Register via {@code Ensemble.builder().managedResource(...)}. <strong>The builder
 * starts the resource immediately on registration</strong> (if it is not already
 * running), so callers can chain {@code .managedResource(fs).agent(... fs.tools() ...)}
 * in a single fluent call. This is a side effect of {@code managedResource(...)} itself,
 * not of {@code build()}, {@code run()}, or {@code start(int)} -- registration can
 * launch a subprocess. Subsequent calls to {@link net.agentensemble.Ensemble#run()} and
 * {@link net.agentensemble.Ensemble#start(int)} call {@link #start()} again on every
 * owned resource that is not currently running, which is how the lifecycle is revived if
 * something has closed the resource in the meantime.
 *
 * <p>Resources the ensemble owns are closed when
 * {@link net.agentensemble.Ensemble#stop()} fires. A resource that was already running
 * when registered is treated as caller-owned and is never started or closed by the
 * ensemble.
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
