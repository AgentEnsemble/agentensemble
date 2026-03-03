package net.agentensemble.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.LangChain4jToolAdapter;

/**
 * Resolves a mixed list of tools (AgentTool instances and @Tool-annotated objects)
 * into a unified structure that can generate LangChain4j ToolSpecifications and
 * dispatch tool execution requests.
 *
 * This class is package-private -- it is an implementation detail of AgentExecutor.
 */
final class ToolResolver {

    private ToolResolver() {
        // Utility class -- not instantiable
    }

    /**
     * Resolve a mixed list of tools into AgentTool and @Tool-annotated object maps,
     * plus the complete list of LangChain4j ToolSpecifications.
     *
     * @param tools list of AgentTool instances and/or @Tool-annotated objects
     * @return a ResolvedTools value encapsulating all resolution results
     */
    static ResolvedTools resolve(List<Object> tools) {
        Map<String, AgentTool> agentToolMap = new HashMap<>();
        Map<String, Object> annotatedObjectMap = new HashMap<>();
        List<ToolSpecification> allSpecs = new ArrayList<>();

        for (Object tool : tools) {
            if (tool instanceof AgentTool agentTool) {
                agentToolMap.put(agentTool.name(), agentTool);
                allSpecs.add(LangChain4jToolAdapter.toSpecification(agentTool));
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
     * Encapsulates the result of resolving a tool list.
     *
     * Provides fast lookup by tool name and dispatches execution requests to the
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
         * AgentTool instances take precedence over @Tool-annotated objects when
         * both share the same name.
         *
         * @param request the tool execution request from the LLM
         * @return the tool's result string, or "Error: ..." on failure or unknown tool
         */
        String execute(ToolExecutionRequest request) {
            String toolName = request.name();

            // AgentTool takes precedence
            AgentTool agentTool = agentToolMap.get(toolName);
            if (agentTool != null) {
                return LangChain4jToolAdapter.execute(agentTool, request.arguments());
            }

            // @Tool-annotated object
            Object annotatedObj = annotatedObjectMap.get(toolName);
            if (annotatedObj != null) {
                return LangChain4jToolAdapter.executeAnnotatedTool(annotatedObj, toolName, request.arguments());
            }

            return "Error: Unknown tool '" + toolName + "'";
        }
    }
}
