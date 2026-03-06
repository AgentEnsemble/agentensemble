package net.agentensemble.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import net.agentensemble.tool.AbstractAgentTool;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.LangChain4jToolAdapter;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolContext;
import net.agentensemble.tool.ToolContextInjector;
import net.agentensemble.tool.ToolMetrics;
import net.agentensemble.tool.ToolResult;

/**
 * Resolves a mixed list of tools (AgentTool instances and @Tool-annotated objects)
 * into a unified structure that can generate LangChain4j ToolSpecifications and
 * dispatch tool execution requests.
 *
 * <p>During resolution, {@link AbstractAgentTool} instances receive an injected
 * {@link ToolContext} containing the configured {@link ToolMetrics}, {@link Executor},
 * and optional ReviewHandler (for tool-level approval gates). This happens once per
 * execution before any tool calls begin.
 *
 * <p>This class is package-private -- it is an implementation detail of AgentExecutor.
 */
final class ToolResolver {

    private ToolResolver() {
        // Utility class -- not instantiable
    }

    /**
     * Resolve a mixed list of tools into AgentTool and @Tool-annotated object maps,
     * plus the complete list of LangChain4j ToolSpecifications.
     *
     * <p>{@link AbstractAgentTool} instances in the list are automatically injected with
     * a {@link ToolContext} built from the supplied {@code metrics}, {@code executor},
     * and {@code reviewHandler}.
     *
     * @param tools         list of AgentTool instances and/or @Tool-annotated objects
     * @param metrics       the ToolMetrics backend to inject into AbstractAgentTool instances
     * @param executor      the Executor to inject into AbstractAgentTool instances
     * @param reviewHandler the ReviewHandler for tool-level approval gates (stored as Object
     *                      to avoid runtime class loading); may be null
     * @return a ResolvedTools value encapsulating all resolution results
     */
    static ResolvedTools resolve(List<Object> tools, ToolMetrics metrics, Executor executor, Object reviewHandler) {
        Map<String, AgentTool> agentToolMap = new HashMap<>();
        Map<String, Object> annotatedObjectMap = new HashMap<>();
        List<ToolSpecification> allSpecs = new ArrayList<>();

        for (Object tool : tools) {
            if (tool instanceof AgentTool agentTool) {
                agentToolMap.put(agentTool.name(), agentTool);
                allSpecs.add(LangChain4jToolAdapter.toSpecification(agentTool));

                // Inject ToolContext into AbstractAgentTool instances
                if (agentTool instanceof AbstractAgentTool abstractTool) {
                    ToolContext ctx = ToolContext.of(agentTool.name(), metrics, executor, reviewHandler);
                    ToolContextInjector.injectContext(abstractTool, ctx);
                }
            } else {
                // @Tool-annotated object
                List<ToolSpecification> specs = ToolSpecifications.toolSpecificationsFrom(tool);
                for (ToolSpecification spec : specs) {
                    annotatedObjectMap.put(spec.name(), tool);
                }
                allSpecs.addAll(specs);
            }
        }

        return new ResolvedTools(agentToolMap, annotatedObjectMap, allSpecs);
    }

    /**
     * Resolve a mixed list of tools using the supplied metrics and executor, with no
     * ReviewHandler. Convenience overload for call sites where review is not configured.
     *
     * @param tools    list of AgentTool instances and/or @Tool-annotated objects
     * @param metrics  the ToolMetrics backend to inject into AbstractAgentTool instances
     * @param executor the Executor to inject into AbstractAgentTool instances
     * @return a ResolvedTools value
     */
    static ResolvedTools resolve(List<Object> tools, ToolMetrics metrics, Executor executor) {
        return resolve(tools, metrics, executor, null);
    }

    /**
     * Resolve using the default no-op metrics and virtual-thread executor, with no
     * ReviewHandler. Convenience overload for call sites where metrics, executor, and
     * review are not configured.
     *
     * @param tools list of AgentTool instances and/or @Tool-annotated objects
     * @return a ResolvedTools value
     */
    static ResolvedTools resolve(List<Object> tools) {
        return resolve(
                tools,
                NoOpToolMetrics.INSTANCE,
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                null);
    }

    /**
     * Encapsulates the result of resolving a tool list.
     *
     * <p>Provides fast lookup by tool name and dispatches execution requests to the
     * correct backing implementation (AgentTool or @Tool-annotated method).
     */
    record ResolvedTools(
            Map<String, AgentTool> agentToolMap,
            Map<String, Object> annotatedObjectMap,
            List<ToolSpecification> allSpecifications) {

        /**
         * @return true if at least one tool was resolved
         */
        boolean hasTools() {
            return !agentToolMap.isEmpty() || !annotatedObjectMap.isEmpty();
        }

        /**
         * Dispatch a tool execution request to the correct tool implementation.
         *
         * <p>AgentTool instances take precedence over @Tool-annotated objects when
         * both share the same name.
         *
         * <p>Before executing an {@link AbstractAgentTool}, the supplied {@code agentRole}
         * is set on the current thread's agent-role thread-local so that metrics are
         * tagged correctly. The thread-local is cleared after execution.
         *
         * @param request   the tool execution request from the LLM
         * @param agentRole the role of the agent invoking this tool; used for metrics tagging
         * @return the full ToolResult, or a failure ToolResult on unknown tool
         */
        ToolResult execute(ToolExecutionRequest request, String agentRole) {
            String toolName = request.name();

            // AgentTool takes precedence
            AgentTool agentTool = agentToolMap.get(toolName);
            if (agentTool != null) {
                ToolContextInjector.setCurrentAgentRole(agentRole);
                try {
                    return LangChain4jToolAdapter.executeForResult(agentTool, request.arguments());
                } finally {
                    ToolContextInjector.clearCurrentAgentRole();
                }
            }

            // @Tool-annotated object
            Object annotatedObj = annotatedObjectMap.get(toolName);
            if (annotatedObj != null) {
                String result =
                        LangChain4jToolAdapter.executeAnnotatedTool(annotatedObj, toolName, request.arguments());
                // Annotated tools return plain strings; wrap as success or failure
                if (result != null && result.startsWith("Error:")) {
                    return ToolResult.failure(
                            result.substring("Error:".length()).trim());
                }
                return ToolResult.success(result != null ? result : "");
            }

            return ToolResult.failure("Unknown tool '" + toolName + "'");
        }
    }
}
