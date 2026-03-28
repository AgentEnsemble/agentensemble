package net.agentensemble.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a {@link ToolBackend} request into a concrete backend by checking classpath
 * availability of optional modules.
 *
 * <p>Uses {@code Class.forName()} to detect whether the MCP bridge or Java coding tools
 * modules are on the classpath, following the same pattern as
 * {@code net.agentensemble.format.ContextFormatters.isToonAvailable()}.
 */
final class ToolBackendDetector {

    private static final Logger LOG = LoggerFactory.getLogger(ToolBackendDetector.class);

    private static final String MCP_MARKER_CLASS = "net.agentensemble.mcp.McpToolFactory";
    private static final String JAVA_TOOLS_MARKER_CLASS = "net.agentensemble.tools.coding.GlobTool";

    private ToolBackendDetector() {}

    /**
     * Resolve the requested backend to a concrete backend.
     *
     * <p>{@link ToolBackend#AUTO} is resolved by checking for MCP first, then Java tools,
     * then falling back to {@link ToolBackend#MINIMAL}. Explicit backends are validated --
     * if the required module is not on the classpath, an {@link IllegalStateException} is thrown.
     *
     * @param requested the requested backend
     * @return the resolved concrete backend (never {@link ToolBackend#AUTO})
     * @throws IllegalStateException if an explicit backend is requested but its module is not available
     */
    static ToolBackend resolve(ToolBackend requested) {
        return switch (requested) {
            case AUTO -> resolveAuto();
            case JAVA -> {
                if (!isJavaToolsAvailable()) {
                    throw new IllegalStateException(
                            "JAVA tool backend selected but agentensemble-tools-coding is not on the classpath. "
                                    + "Add a dependency on net.agentensemble:agentensemble-tools-coding.");
                }
                yield ToolBackend.JAVA;
            }
            case MCP -> {
                if (!isMcpAvailable()) {
                    throw new IllegalStateException(
                            "MCP tool backend selected but agentensemble-mcp is not on the classpath. "
                                    + "Add a dependency on net.agentensemble:agentensemble-mcp.");
                }
                yield ToolBackend.MCP;
            }
            case MINIMAL -> ToolBackend.MINIMAL;
        };
    }

    private static ToolBackend resolveAuto() {
        if (isMcpAvailable()) {
            LOG.debug("AUTO backend resolved to MCP (agentensemble-mcp detected on classpath)");
            return ToolBackend.MCP;
        }
        if (isJavaToolsAvailable()) {
            LOG.debug("AUTO backend resolved to JAVA (agentensemble-tools-coding detected on classpath)");
            return ToolBackend.JAVA;
        }
        LOG.debug("AUTO backend resolved to MINIMAL (no optional tool modules on classpath)");
        return ToolBackend.MINIMAL;
    }

    static boolean isMcpAvailable() {
        try {
            Class.forName(MCP_MARKER_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static boolean isJavaToolsAvailable() {
        try {
            Class.forName(JAVA_TOOLS_MARKER_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
