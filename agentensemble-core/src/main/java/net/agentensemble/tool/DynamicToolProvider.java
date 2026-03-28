package net.agentensemble.tool;

import java.util.List;

/**
 * A tool provider that resolves to zero or more {@link AgentTool}s at execution time.
 *
 * <p>Place instances into {@code Task.builder().tools(...)} alongside regular tools.
 * The framework's {@code ToolResolver} expands providers at resolution time, which
 * happens per-execution rather than at build time. This enables dynamic tool discovery
 * where the set of available tools can change between executions.
 *
 * <p>Primary implementation: {@code NetworkToolCatalog} in the network module.
 *
 * @see AgentTool
 */
public interface DynamicToolProvider {

    /**
     * Resolve the current set of tools provided by this provider.
     *
     * <p>Called at task execution time. Implementations should return a fresh snapshot
     * of available tools -- the result may differ between invocations as the network
     * topology changes.
     *
     * @return the currently available tools; never null, may be empty
     */
    List<AgentTool> resolve();
}
