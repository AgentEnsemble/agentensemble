package net.agentensemble.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import java.util.Collections;
import java.util.List;
import net.agentensemble.tool.AgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the lifecycle of an MCP server subprocess and its associated tools.
 *
 * <p>Implements {@link AutoCloseable} for use with try-with-resources:
 *
 * <pre>
 * try (McpServerLifecycle server = McpToolFactory.filesystem(baseDir)) {
 *     server.start();
 *     List&lt;AgentTool&gt; tools = server.tools();
 *     // ... use tools with an agent
 * }
 * </pre>
 *
 * <p>Instances are created by {@link McpToolFactory#filesystem(java.nio.file.Path)}
 * and {@link McpToolFactory#git(java.nio.file.Path)}.
 */
public final class McpServerLifecycle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpServerLifecycle.class);

    private final List<String> command;

    private volatile McpClient client;
    private volatile boolean started;
    private volatile boolean closed;
    private volatile List<AgentTool> cachedTools;

    McpServerLifecycle(List<String> command) {
        this.command = command != null ? List.copyOf(command) : List.of();
    }

    /**
     * Package-private constructor for testing with a pre-built client.
     */
    McpServerLifecycle(McpClient client, List<String> command) {
        this.command = command != null ? List.copyOf(command) : List.of();
        this.client = client;
    }

    /**
     * Start the MCP server subprocess and initialize the connection.
     *
     * <p>This creates the stdio transport, builds the MCP client, and validates
     * the connection with a health check. After this method returns,
     * {@link #tools()} can be called.
     *
     * @throws IllegalStateException if already started or closed
     */
    public void start() {
        if (closed) {
            throw new IllegalStateException("Cannot start a closed McpServerLifecycle");
        }
        if (started) {
            throw new IllegalStateException("McpServerLifecycle is already started");
        }
        log.info("Starting MCP server: {}", String.join(" ", command));
        if (client == null) {
            StdioMcpTransport transport =
                    new StdioMcpTransport.Builder().command(command).build();
            client = new DefaultMcpClient.Builder().transport(transport).build();
        }
        client.checkHealth();
        started = true;
        log.info("MCP server started successfully");
    }

    /**
     * Shut down the MCP client and close the transport.
     *
     * <p>This method is idempotent; calling it multiple times has no additional effect.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
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
     * List all tools from the MCP server as {@link AgentTool} instances.
     *
     * <p>The tool list is cached after the first call; subsequent calls return
     * the same list without re-querying the server.
     *
     * @return an unmodifiable list of AgentTool instances
     * @throws IllegalStateException if not yet started or already closed
     */
    public List<AgentTool> tools() {
        if (!started) {
            throw new IllegalStateException("McpServerLifecycle has not been started");
        }
        if (closed) {
            throw new IllegalStateException("McpServerLifecycle is closed");
        }
        if (cachedTools == null) {
            cachedTools = Collections.unmodifiableList(McpToolFactory.fromClient(client));
        }
        return cachedTools;
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
