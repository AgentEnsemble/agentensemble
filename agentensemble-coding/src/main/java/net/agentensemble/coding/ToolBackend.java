package net.agentensemble.coding;

/**
 * Determines which tool set is assembled for a {@link CodingAgent}.
 *
 * <ul>
 *   <li>{@link #AUTO} -- detect at runtime: prefer MCP, then Java tools, then MINIMAL</li>
 *   <li>{@link #JAVA} -- use Java coding tools ({@code agentensemble-tools-coding} must be on classpath)</li>
 *   <li>{@link #MCP} -- use MCP reference servers ({@code agentensemble-mcp} + Node.js required)</li>
 *   <li>{@link #MINIMAL} -- {@code FileReadTool} only (always available)</li>
 * </ul>
 */
public enum ToolBackend {

    /**
     * Auto-detect: prefer MCP if available, then Java tools, then MINIMAL.
     */
    AUTO,

    /**
     * Use Java coding tools (GlobTool, CodeSearchTool, CodeEditTool, ShellTool, etc.).
     * Requires {@code agentensemble-tools-coding} on the classpath.
     */
    JAVA,

    /**
     * Use MCP reference servers for filesystem and git operations.
     * Requires {@code agentensemble-mcp} on the classpath and Node.js installed.
     */
    MCP,

    /**
     * Minimal tool set: {@code FileReadTool} only. Always available.
     */
    MINIMAL
}
