package net.agentensemble.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import net.agentensemble.tool.CustomSchemaAgentTool;
import net.agentensemble.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single MCP tool as an {@link net.agentensemble.tool.AgentTool}.
 *
 * <p>Implements {@link CustomSchemaAgentTool} so that the MCP tool's parameter schema
 * is passed through directly to the LLM via
 * {@link net.agentensemble.tool.LangChain4jToolAdapter#toSpecification} -- no
 * intermediate Java record is needed.
 *
 * <p>Instances are created by {@link McpToolFactory} or {@link McpServerLifecycle#tools()};
 * the constructor is package-private.
 */
public final class McpAgentTool implements CustomSchemaAgentTool {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTool.class);

    private final McpClient client;
    private final String toolName;
    private final String toolDescription;
    private final JsonObjectSchema parameters;

    McpAgentTool(McpClient client, String toolName, String toolDescription, JsonObjectSchema parameters) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be null or blank");
        }
        this.client = client;
        this.toolName = toolName;
        this.toolDescription = toolDescription != null ? toolDescription : "";
        this.parameters =
                parameters != null ? parameters : JsonObjectSchema.builder().build();
    }

    @Override
    public String name() {
        return toolName;
    }

    @Override
    public String description() {
        return toolDescription;
    }

    @Override
    public JsonObjectSchema parameterSchema() {
        return parameters;
    }

    /**
     * Executes the MCP tool by forwarding the JSON arguments to the MCP server.
     *
     * @param input the full JSON arguments string from the LLM
     * @return a {@link ToolResult} wrapping the MCP server's response
     */
    @Override
    public ToolResult execute(String input) {
        try {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(input != null ? input : "{}")
                    .build();

            log.debug("Executing MCP tool '{}' with arguments: {}", toolName, input);

            ToolExecutionResult mcpResult = client.executeTool(request);

            if (mcpResult.isError()) {
                String errorText = mcpResult.resultText();
                log.warn("MCP tool '{}' returned error: {}", toolName, errorText);
                return ToolResult.failure(errorText != null ? errorText : "MCP tool returned an error");
            }

            String resultText = mcpResult.resultText();
            return ToolResult.success(resultText != null ? resultText : "");
        } catch (Exception e) {
            log.warn("MCP tool '{}' threw exception: {}", toolName, e.getMessage(), e);
            return ToolResult.failure(e.getMessage() != null ? e.getMessage() : "MCP tool execution failed");
        }
    }
}
