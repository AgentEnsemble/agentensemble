package net.agentensemble.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.agentensemble.tool.AgentTool;

/**
 * Factory methods to create {@link AgentTool} instances from MCP servers.
 *
 * <p>Use {@link #fromServer(McpTransport)} or {@link #fromServer(McpTransport, String...)}
 * to connect to any MCP server and obtain its tools as AgentTool instances.
 *
 * <p>Use {@link #filesystem(Path)} or {@link #git(Path)} to start the well-known MCP
 * reference servers for filesystem and git operations.
 */
public final class McpToolFactory {

    private McpToolFactory() {
        // Utility class -- not instantiable
    }

    /**
     * Connect to an MCP server and return all its tools as {@link AgentTool} instances.
     *
     * <p>This method creates a {@link DefaultMcpClient} from the transport, lists all
     * available tools, and wraps each one as an {@link McpAgentTool}.
     *
     * <p><strong>Resource management:</strong> The returned tools capture the MCP client
     * internally. The caller must close the transport when done to avoid leaking the
     * subprocess. For managed lifecycle, prefer {@link McpServerLifecycle} via
     * {@link #filesystem(Path)} or {@link #git(Path)}.
     *
     * @param transport the MCP transport (e.g., {@link dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport})
     * @return a list of AgentTool instances wrapping the server's tools
     * @throws IllegalArgumentException if transport is null
     */
    public static List<AgentTool> fromServer(McpTransport transport) {
        return fromClient(buildClient(transport));
    }

    /**
     * Connect to an MCP server and return only the named tools as {@link AgentTool} instances.
     *
     * <p><strong>Resource management:</strong> The returned tools capture the MCP client
     * internally. The caller must close the transport when done to avoid leaking the
     * subprocess. For managed lifecycle, prefer {@link McpServerLifecycle}.
     *
     * @param transport the MCP transport
     * @param toolNames the names of tools to include (must match exactly); must not be null
     * @return a filtered list of AgentTool instances
     * @throws IllegalArgumentException if transport is null, toolNames is null, or any
     *     requested tool name is not found on the server
     */
    public static List<AgentTool> fromServer(McpTransport transport, String... toolNames) {
        return fromClient(buildClient(transport), toolNames);
    }

    /**
     * Create a {@link McpServerLifecycle} for the MCP filesystem reference server.
     *
     * <p>The filesystem server provides tools for reading, writing, searching,
     * and listing files within the specified directory.
     *
     * <p>Requires {@code npx} (Node.js) to be available on the system PATH.
     *
     * @param allowedDir the directory to expose to the MCP filesystem server
     * @return a lifecycle manager; call {@link McpServerLifecycle#start()} to begin
     */
    public static McpServerLifecycle filesystem(Path allowedDir) {
        if (allowedDir == null) {
            throw new IllegalArgumentException("allowedDir must not be null");
        }
        List<String> command = List.of(
                "npx",
                "--yes",
                "@modelcontextprotocol/server-filesystem",
                allowedDir.toAbsolutePath().toString());
        return new McpServerLifecycle(command);
    }

    /**
     * Create a {@link McpServerLifecycle} for the MCP git reference server.
     *
     * <p>The git server provides tools for status, diff, log, commit, branch,
     * and other git operations on the specified repository.
     *
     * <p>Requires {@code npx} (Node.js) to be available on the system PATH.
     *
     * @param repoPath the git repository root directory
     * @return a lifecycle manager; call {@link McpServerLifecycle#start()} to begin
     */
    public static McpServerLifecycle git(Path repoPath) {
        if (repoPath == null) {
            throw new IllegalArgumentException("repoPath must not be null");
        }
        List<String> command = List.of(
                "npx",
                "--yes",
                "@modelcontextprotocol/server-git",
                "--repository",
                repoPath.toAbsolutePath().toString());
        return new McpServerLifecycle(command);
    }

    // ========================
    // Package-private for testability
    // ========================

    /**
     * Convert all tools from an already-connected {@link McpClient} to AgentTool instances.
     */
    static List<AgentTool> fromClient(McpClient client) {
        List<ToolSpecification> specs = client.listTools();
        List<AgentTool> tools = new ArrayList<>(specs.size());
        for (ToolSpecification spec : specs) {
            tools.add(new McpAgentTool(client, spec.name(), spec.description(), spec.parameters()));
        }
        return tools;
    }

    /**
     * Convert named tools from an already-connected {@link McpClient} to AgentTool instances.
     */
    static List<AgentTool> fromClient(McpClient client, String... toolNames) {
        if (toolNames == null) {
            throw new IllegalArgumentException("toolNames must not be null");
        }
        List<AgentTool> allTools = fromClient(client);

        Set<String> requested = Arrays.stream(toolNames).collect(Collectors.toSet());
        List<AgentTool> filtered =
                allTools.stream().filter(t -> requested.contains(t.name())).collect(Collectors.toList());

        // Verify all requested names were found
        Set<String> found = filtered.stream().map(AgentTool::name).collect(Collectors.toSet());
        Set<String> missing =
                requested.stream().filter(name -> !found.contains(name)).collect(Collectors.toSet());

        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("MCP server does not provide tool(s): " + missing);
        }

        return filtered;
    }

    private static McpClient buildClient(McpTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        return new DefaultMcpClient.Builder().transport(transport).build();
    }
}
