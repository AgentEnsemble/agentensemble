package net.agentensemble.web;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import net.agentensemble.tool.AgentTool;

/**
 * Registry mapping tool names to {@link AgentTool} instances for the Ensemble Control API.
 *
 * <p>Tools registered here form the server-side allowlist for API-submitted runs. Clients
 * reference tools by name in API requests; only registered tools can be used. This prevents
 * arbitrary tool invocation from external callers.
 *
 * <pre>
 * ToolCatalog catalog = ToolCatalog.builder()
 *     .tool("web_search", webSearchTool)
 *     .tool("calculator", calculatorTool)
 *     .build();
 * </pre>
 *
 * <p>Registered via {@link WebDashboard.Builder#toolCatalog(ToolCatalog)}:
 *
 * <pre>
 * WebDashboard dashboard = WebDashboard.builder()
 *     .port(7329)
 *     .toolCatalog(catalog)
 *     .build();
 * </pre>
 */
public final class ToolCatalog {

    private final Map<String, AgentTool> tools;

    private ToolCatalog(Builder builder) {
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(builder.tools));
    }

    /**
     * Returns a new builder for constructing a {@link ToolCatalog}.
     *
     * @return a new Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Resolves the tool with the given name.
     *
     * @param name the tool name; must not be null
     * @return the registered {@link AgentTool}
     * @throws NoSuchElementException if no tool is registered with the given name
     */
    public AgentTool resolve(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            List<String> available = tools.keySet().stream().sorted().toList();
            throw new NoSuchElementException("Unknown tool '" + name + "'. Available: " + available);
        }
        return tool;
    }

    /**
     * Returns the tool with the given name, or empty if not found.
     *
     * @param name the tool name
     * @return an Optional containing the tool, or empty
     */
    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Returns a list of all registered tool descriptions, in registration order.
     *
     * @return unmodifiable list of {@link ToolInfo} records
     */
    public List<ToolInfo> list() {
        return tools.entrySet().stream()
                .map(e -> new ToolInfo(e.getKey(), e.getValue().description()))
                .toList();
    }

    /**
     * Returns {@code true} if a tool with the given name is registered.
     *
     * @param name the tool name
     * @return true if registered
     */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    /**
     * Returns the number of registered tools.
     *
     * @return tool count
     */
    public int size() {
        return tools.size();
    }

    /**
     * Descriptive snapshot of a registered tool returned by {@link #list()}.
     *
     * @param name        the tool name clients use in API requests
     * @param description the tool's description as returned by {@link AgentTool#description()}
     */
    public record ToolInfo(String name, String description) {}

    /**
     * Fluent builder for {@link ToolCatalog}.
     *
     * <p>Tools are stored in insertion order. Attempting to register the same name twice
     * throws {@link IllegalArgumentException} at builder time (fail-fast).
     */
    public static final class Builder {

        private final Map<String, AgentTool> tools = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Registers a tool under the given name.
         *
         * @param name the name clients use to reference this tool; must not be null or blank
         * @param tool the tool implementation; must not be null
         * @return this builder
         * @throws IllegalArgumentException if the name is null/blank or already registered,
         *                                  or if the tool is null
         */
        public Builder tool(String name, AgentTool tool) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be null or blank");
            }
            if (tool == null) {
                throw new IllegalArgumentException("tool must not be null (name: '" + name + "')");
            }
            if (tools.containsKey(name)) {
                throw new IllegalArgumentException("duplicate tool name: '" + name + "'");
            }
            tools.put(name, tool);
            return this;
        }

        /**
         * Builds and returns the {@link ToolCatalog}.
         *
         * @return a new, immutable ToolCatalog
         */
        public ToolCatalog build() {
            return new ToolCatalog(this);
        }
    }
}
