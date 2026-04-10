package net.agentensemble.executor;

import java.util.List;

/**
 * Provides tool instances to {@link TaskExecutor} and {@link EnsembleExecutor} at execution time.
 *
 * <p>Tools are resolved by name from {@code AgentSpec.getToolNames()}. Each name corresponds
 * to an entry registered in the provider. Returned objects may be any type accepted by
 * AgentEnsemble's {@code ToolResolver}:
 * <ul>
 *   <li>{@code AgentTool} implementations</li>
 *   <li>Objects with {@code @Tool}-annotated methods (LangChain4j tool objects)</li>
 *   <li>{@code DynamicToolProvider} implementations</li>
 * </ul>
 *
 * <p>The built-in {@link SimpleToolProvider} covers most use cases:
 *
 * <pre>
 * ToolProvider tools = SimpleToolProvider.builder()
 *     .tool("datetime", new DateTimeTool())
 *     .tool("calculator", new CalculatorTool())
 *     .tool("web-search", new WebSearchTool(apiKey))
 *     .build();
 *
 * TaskExecutor executor = new TaskExecutor(modelProvider, tools);
 * </pre>
 */
public interface ToolProvider {

    /**
     * Returns the tool instances registered under the given names, in the same order
     * as the input list.
     *
     * @param names the list of tool names from {@code AgentSpec.getToolNames()}
     * @return the resolved tool instances; never null
     * @throws IllegalArgumentException if any name has no registered tool
     */
    List<Object> get(List<String> names);

    /**
     * Returns all registered tool instances.
     *
     * @return all tools; never null, may be empty
     */
    List<Object> getAll();
}
