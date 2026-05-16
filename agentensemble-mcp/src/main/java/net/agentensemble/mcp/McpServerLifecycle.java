package net.agentensemble.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import net.agentensemble.ensemble.ManagedResource;
import net.agentensemble.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of an MCP server subprocess and its associated tools.
 *
 * <p>Implements {@link ManagedResource} so it can be wired into an
 * {@link net.agentensemble.Ensemble} via {@code Ensemble.builder().managedResource(...)},
 * and {@link AutoCloseable} for use with try-with-resources:
 *
 * <pre>
 * try (McpServerLifecycle server = McpToolFactory.filesystem(baseDir)) {
 *     server.start();
 *     List&lt;AgentTool&gt; tools = server.tools();
 *     // ... use tools with an agent
 * }
 * </pre>
 *
 * <p>The lifecycle is revivable: calling {@link #start()} after {@link #close()} spawns a
 * fresh subprocess and rebuilds the underlying MCP client. Tool instances returned by
 * {@link #tools()} reference the lifecycle indirectly through a supplier, so they survive
 * a close/restart cycle without needing to be rebuilt.
 *
 * <p>Instances are created by {@link McpToolFactory#filesystem(java.nio.file.Path)}
 * and {@link McpToolFactory#git(java.nio.file.Path)}.
 */
public final class McpServerLifecycle implements ManagedResource {

    private static final Logger log = LoggerFactory.getLogger(McpServerLifecycle.class);

    private final List<String> command;

    private volatile McpClient client;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile List<AgentTool> cachedTools;

    /**
     * Whether {@link #client} was injected via the test constructor and therefore must not
     * be replaced when the lifecycle is restarted after a close. Test-only code paths keep
     * their original mock; production code paths build a new client on each revive.
     */
    private final boolean clientPreInjected;

    McpServerLifecycle(List<String> command) {
        this.command = command != null ? List.copyOf(command) : List.of();
        this.clientPreInjected = false;
    }

    /**
     * Package-private constructor for testing with a pre-built client.
     */
    McpServerLifecycle(McpClient client, List<String> command) {
        this.command = command != null ? List.copyOf(command) : List.of();
        this.client = client;
        this.clientPreInjected = client != null;
    }

    /**
     * Start the MCP server subprocess and initialize the connection.
     *
     * <p>This creates the stdio transport, builds the MCP client, and validates the
     * connection with a health check. After this method returns, {@link #tools()} can be
     * called.
     *
     * <p>Behavior by current state:
     * <ul>
     *   <li><b>not started</b>: builds the client (if needed) and runs the health check.</li>
     *   <li><b>already started</b>: no-op (idempotent). Useful so callers can defensively
     *       call {@code start()} on every iteration of a long-running loop.</li>
     *   <li><b>previously closed</b>: revives the lifecycle. The state flips back to
     *       started, a fresh transport+client is built (unless the client was pre-injected
     *       in tests), the tool cache is cleared, and the health check runs against the
     *       new connection.</li>
     * </ul>
     *
     * <p>If initialization or health check fails, any partially created resources are
     * cleaned up before the exception propagates and the lifecycle is left in the
     * pre-call state.
     */
    @Override
    public synchronized void start() {
        if (started && !closed) {
            // Already running -- no-op so callers can call start() defensively each iteration.
            return;
        }
        boolean reviving = closed;
        log.info(reviving ? "Restarting MCP server: {}" : "Starting MCP server: {}", String.join(" ", command));
        try {
            if (reviving && !clientPreInjected) {
                // Old client (if any) was already closed by close(); drop the reference so we
                // build a fresh transport and client below.
                client = null;
            }
            if (client == null) {
                StdioMcpTransport transport =
                        new StdioMcpTransport.Builder().command(command).build();
                client = new DefaultMcpClient.Builder().transport(transport).build();
            }
            client.checkHealth();
            closed = false;
            started = true;
            log.info(reviving ? "MCP server restarted successfully" : "MCP server started successfully");
        } catch (Exception e) {
            // Clean up partially initialized resources on failure -- always close the
            // client (whether pre-injected for tests or freshly built) so callers see a
            // deterministic shutdown. Only null the reference when not pre-injected so
            // tests that supply a mock can still inspect it after the failure.
            if (client != null) {
                try {
                    client.close();
                } catch (Exception closeEx) {
                    log.warn("Error closing MCP client after failed start: {}", closeEx.getMessage(), closeEx);
                }
                if (!clientPreInjected) {
                    client = null;
                }
            }
            // Leave state as it was before this start() attempt: if reviving from closed, stay closed;
            // if starting fresh, stay un-started.
            throw e;
        }
    }

    /**
     * Shut down the MCP client and close the transport.
     *
     * <p>This method is idempotent; calling it multiple times has no additional effect.
     * After {@code close()}, the lifecycle can be restarted via {@link #start()} -- a fresh
     * subprocess and client will be created, and tool instances returned by {@link #tools()}
     * (or any prior call to it) automatically pick up the new client through the supplier
     * indirection in {@link McpAgentTool}. {@code close()} also drops the internal tools
     * cache; the captured tool instances stay valid, but the next {@link #tools()} after a
     * revive will relist against the new session.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        started = false;
        // Drop the tools cache so a future start() forces a relist against the new
        // session. Tool *instances* the caller has already taken from tools() stay
        // valid -- McpAgentTool resolves the live client through a supplier.
        cachedTools = null;
        log.info("Closing MCP server lifecycle");
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * Returns whether this lifecycle is active (started and not yet closed).
     *
     * @return true if the server is running
     */
    public boolean isAlive() {
        return started && !closed;
    }

    /**
     * Alias for {@link #isAlive()}, satisfying the {@link ManagedResource} contract.
     */
    @Override
    public boolean isRunning() {
        return isAlive();
    }

    /**
     * List all tools from the MCP server as {@link AgentTool} instances.
     *
     * <p>The tool list is cached within a session; subsequent calls return the same list
     * without re-querying the server. The cache is dropped inside {@link #close()} so
     * that a {@code close()} -> {@code start()} -> {@code tools()} sequence relists from
     * the fresh connection. Tool instances themselves are stable across restart cycles --
     * they resolve the active client through a supplier each time they execute.
     *
     * @return an unmodifiable list of AgentTool instances
     * @throws IllegalStateException if not yet started or currently closed
     */
    public synchronized List<AgentTool> tools() {
        // Synchronized as a whole so the closed/started check is atomic with a
        // concurrent start()/close() (which also sync on this). Without this, a thread
        // could read closed=false, then close() races in, then we'd cache tools backed
        // by a dead client. Cache fill is cheap; this is not a hot path.
        if (closed) {
            throw new IllegalStateException("McpServerLifecycle is closed");
        }
        if (!started) {
            throw new IllegalStateException("McpServerLifecycle has not been started");
        }
        if (cachedTools == null) {
            cachedTools = Collections.unmodifiableList(McpToolFactory.fromClientSupplier(this::currentClient));
        }
        return cachedTools;
    }

    /**
     * Returns the current underlying {@link McpClient} for use by tool instances. Throws
     * if the lifecycle is not currently running, so {@link McpAgentTool#execute} surfaces
     * a clear failure instead of silently passing the call to a dead client.
     */
    McpClient currentClient() {
        if (!started || closed) {
            throw new IllegalStateException("MCP server is not running");
        }
        return client;
    }

    /**
     * Returns a supplier that always resolves to the lifecycle's current
     * {@link McpClient}. Used so tool instances captured before a restart pick up the new
     * client transparently after revive.
     */
    Supplier<McpClient> clientSupplier() {
        return this::currentClient;
    }

    /**
     * Returns the command used to start the MCP server subprocess.
     *
     * @return an unmodifiable list of command tokens
     */
    List<String> command() {
        return command;
    }
}
