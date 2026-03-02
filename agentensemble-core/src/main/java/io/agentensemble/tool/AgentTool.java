package io.agentensemble.tool;

/**
 * A tool that an agent can use during task execution.
 *
 * Implement this interface to create custom tools. The tool name is used by
 * the LLM to select and invoke the tool. The description is shown to the LLM
 * to help it decide when to use the tool.
 *
 * Example:
 * <pre>
 * public class CalculatorTool implements AgentTool {
 *     public String name() { return "calculator"; }
 *     public String description() { return "Performs arithmetic. Input: a math expression."; }
 *     public ToolResult execute(String input) {
 *         double result = evaluate(input);
 *         return ToolResult.success(String.valueOf(result));
 *     }
 * }
 * </pre>
 *
 * Alternatively, use the {@code @dev.langchain4j.agent.tool.Tool} annotation
 * on methods in a plain Java object. Both approaches can be mixed in a single
 * agent's tool list.
 */
public interface AgentTool {

    /**
     * Unique tool name used by the LLM to select this tool.
     * Must be non-null and non-blank.
     */
    String name();

    /**
     * Description of what this tool does and when to use it.
     * Shown to the LLM in the tool specification.
     */
    String description();

    /**
     * Execute the tool with the given text input.
     *
     * @param input the input string from the LLM tool call
     * @return a ToolResult indicating success or failure
     */
    ToolResult execute(String input);
}
