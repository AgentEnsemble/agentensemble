package net.agentensemble.executor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A map-backed {@link ToolProvider} that resolves tool instances by name.
 *
 * <p>Build an instance via the fluent {@link Builder}:
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
 *
 * <p>For agents that need no tools:
 *
 * <pre>
 * ToolProvider noTools = SimpleToolProvider.empty();
 * TaskExecutor executor = new TaskExecutor(modelProvider, noTools);
 * // Or, equivalently:
 * TaskExecutor executor = new TaskExecutor(modelProvider);
 * </pre>
 *
 * <p>Accepted tool types mirror AgentEnsemble's {@code ToolResolver} contract:
 * {@code AgentTool} implementations, {@code @Tool}-annotated objects, and
 * {@code DynamicToolProvider} implementations are all valid entries.
 */
public final class SimpleToolProvider implements ToolProvider {

    private static final SimpleToolProvider EMPTY = new SimpleToolProvider(new HashMap<>());

    private final Map<String, Object> tools;

    private SimpleToolProvider(Map<String, Object> tools) {
        this.tools = Collections.unmodifiableMap(new HashMap<>(tools));
    }

    /**
     * Returns a shared provider with no registered tools. Useful for agents that rely
     * solely on LLM reasoning without external tool access.
     *
     * @return the empty provider instance
     */
    public static SimpleToolProvider empty() {
        return EMPTY;
    }

    /**
     * Creates a provider with a single named tool.
     *
     * @param name the tool name as referenced in {@code AgentSpec.getToolNames()}
     * @param tool the tool instance
     * @return a provider with the single tool registered
     * @throws NullPointerException if name or tool is null
     */
    public static SimpleToolProvider of(String name, Object tool) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(tool, "tool must not be null");
        Map<String, Object> map = new HashMap<>();
        map.put(name, tool);
        return new SimpleToolProvider(map);
    }

    /**
     * Returns a new builder for constructing a {@code SimpleToolProvider}.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public List<Object> get(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<Object> result = new ArrayList<>(names.size());
        for (String name : names) {
            Object tool = tools.get(name);
            if (tool == null) {
                throw new IllegalArgumentException(
                        "No tool registered with name '" + name + "'. Registered names: " + tools.keySet());
            }
            result.add(tool);
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<Object> getAll() {
        return List.copyOf(tools.values());
    }

    /**
     * Fluent builder for {@link SimpleToolProvider}.
     */
    public static final class Builder {

        private final Map<String, Object> tools = new HashMap<>();

        private Builder() {}

        /**
         * Registers a tool instance under the given name.
         *
         * <p>The name is referenced in {@code AgentSpec.getToolNames()} to equip an
         * agent with this tool at execution time.
         *
         * @param name the tool identifier; must not be null
         * @param tool the tool instance (AgentTool, {@code @Tool}-annotated object, or
         *             DynamicToolProvider); must not be null
         * @return this builder
         * @throws NullPointerException if name or tool is null
         */
        public Builder tool(String name, Object tool) {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(tool, "tool must not be null");
            tools.put(name, tool);
            return this;
        }

        /**
         * Builds the provider.
         *
         * @return the configured {@link SimpleToolProvider}
         */
        public SimpleToolProvider build() {
            return new SimpleToolProvider(tools);
        }
    }
}
