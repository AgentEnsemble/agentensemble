package net.agentensemble.tool;

import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

/**
 * An {@link AgentTool} that provides its own pre-built JSON Schema for tool parameters,
 * bypassing the standard schema generation from {@link ToolSchemaGenerator}.
 *
 * <p>Use this interface when the tool's parameter schema is defined externally
 * (for example, from an MCP server) and cannot be represented as a Java record.
 * The framework uses {@link #parameterSchema()} directly when building the
 * {@link dev.langchain4j.agent.tool.ToolSpecification} for the LLM.
 *
 * <p>Like {@link TypedAgentTool}, the framework passes the full JSON arguments string
 * to {@link #execute(String)} rather than extracting a single {@code "input"} key.
 *
 * @see TypedAgentTool
 * @see LangChain4jToolAdapter#toSpecification(AgentTool)
 */
public interface CustomSchemaAgentTool extends AgentTool {

    /**
     * Returns the pre-built JSON Schema describing this tool's parameters.
     *
     * <p>The returned schema is used as-is in the {@link dev.langchain4j.agent.tool.ToolSpecification}
     * sent to the LLM. It must accurately describe the parameters that
     * {@link #execute(String)} expects to receive as a JSON string.
     *
     * @return the parameter schema; never null
     */
    JsonObjectSchema parameterSchema();
}
