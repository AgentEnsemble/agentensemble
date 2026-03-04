package net.agentensemble.tool;

/**
 * Framework-internal bridge that allows classes outside the {@code net.agentensemble.tool}
 * package to inject a {@link ToolContext} into {@link AbstractAgentTool} instances and
 * manage the per-thread agent role, without exposing those operations as public API.
 *
 * <p>This class is in the same package as {@link AbstractAgentTool}, which gives it
 * access to the package-private methods. External callers (e.g.,
 * {@link net.agentensemble.agent.ToolResolver}) use this injector rather than calling
 * {@link AbstractAgentTool} methods directly.
 *
 * <p><strong>Not intended for use by application code.</strong> This is a framework
 * implementation detail.
 */
public final class ToolContextInjector {

    private ToolContextInjector() {
        // Utility class -- not instantiable
    }

    /**
     * Inject a {@link ToolContext} into an {@link AbstractAgentTool} instance.
     *
     * @param tool    the tool to inject into; must not be null
     * @param context the context to inject; must not be null
     */
    public static void injectContext(AbstractAgentTool tool, ToolContext context) {
        tool.setContext(context);
    }

    /**
     * Set the agent role on the current thread before tool execution.
     * The role is used to tag metrics with the invoking agent's identity.
     *
     * @param agentRole the role of the agent invoking the tool; null clears the role
     */
    public static void setCurrentAgentRole(String agentRole) {
        AbstractAgentTool.setCurrentAgentRole(agentRole);
    }

    /**
     * Clear the agent role from the current thread after tool execution.
     */
    public static void clearCurrentAgentRole() {
        AbstractAgentTool.clearCurrentAgentRole();
    }
}
