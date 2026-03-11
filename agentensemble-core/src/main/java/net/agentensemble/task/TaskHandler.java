package net.agentensemble.task;

import net.agentensemble.tool.ToolResult;

/**
 * A handler that executes a task deterministically, without any AI or LLM call.
 *
 * <p>When a {@link net.agentensemble.Task} has a {@code handler} configured via
 * {@link net.agentensemble.Task.TaskBuilder#handler(TaskHandler)} or
 * {@link net.agentensemble.Task.TaskBuilder#handler(net.agentensemble.tool.AgentTool)},
 * the workflow executors bypass {@link net.agentensemble.agent.AgentExecutor} entirely
 * and invoke this handler directly. No agent synthesis, no LLM prompting, no tool-calling
 * loop -- just a direct Java function call.
 *
 * <p>This is useful for tasks whose output is fully deterministic:
 * <ul>
 *   <li>Calling a REST API and forwarding the response downstream</li>
 *   <li>Reading a file or database and forwarding the contents</li>
 *   <li>Transforming or aggregating outputs from prior tasks</li>
 *   <li>Running a {@link net.agentensemble.tool.ToolPipeline} without LLM round-trips</li>
 * </ul>
 *
 * <p>The handler receives a {@link TaskHandlerContext} containing the task's resolved
 * description, expected output, and prior task outputs, and must return a
 * {@link ToolResult}. Use {@link ToolResult#success(String)} for normal completion
 * and {@link ToolResult#failure(String)} to signal an error (which causes the task to fail).
 *
 * <p>When the task has {@code outputType} declared, set a structured Java value via
 * {@link ToolResult#success(String, Object)} to bypass JSON deserialization:
 * <pre>
 * return ToolResult.success(json, myTypedObject);
 * </pre>
 *
 * <p>All lifecycle features work identically to AI-backed tasks:
 * <ul>
 *   <li>Input and output guardrails are evaluated as normal.</li>
 *   <li>Review gates ({@code beforeReview} / {@code review}) fire as configured.</li>
 *   <li>Memory scopes are read and written as declared on the task.</li>
 *   <li>Callbacks ({@code TaskStartEvent}, {@code TaskCompleteEvent}) are fired.</li>
 * </ul>
 *
 * <p>Deterministic tasks appear in {@link net.agentensemble.ensemble.EnsembleOutput}
 * with {@code agentRole = "(deterministic)"} and {@code toolCallCount = 0}.
 *
 * <h2>Usage</h2>
 * <pre>
 * // Direct lambda -- full context available
 * Task fetchPrices = Task.builder()
 *     .description("Fetch current stock prices")
 *     .expectedOutput("JSON with stock prices")
 *     .handler(ctx -&gt; ToolResult.success(httpClient.get("https://api.example.com/prices")))
 *     .build();
 *
 * // Wrap an existing AgentTool -- input is the task description (or last context output)
 * Task fetch = Task.builder()
 *     .description("https://api.example.com/prices")
 *     .expectedOutput("HTTP response body")
 *     .handler(httpTool)   // AgentTool overload on Task.builder()
 *     .build();
 *
 * // ToolPipeline is an AgentTool and works the same way
 * Task fetchAndParse = Task.builder()
 *     .description("https://api.example.com/prices")
 *     .expectedOutput("Parsed stock data")
 *     .handler(ToolPipeline.of(httpTool, jsonParserTool))
 *     .build();
 *
 * // Use context from a prior task
 * Task transform = Task.builder()
 *     .description("Transform the raw price data")
 *     .expectedOutput("Formatted report")
 *     .context(List.of(fetchPrices))
 *     .handler(ctx -&gt; {
 *         String raw = ctx.contextOutputs().get(0).getRaw();
 *         return ToolResult.success(ReportFormatter.format(raw));
 *     })
 *     .build();
 * </pre>
 *
 * @see TaskHandlerContext
 * @see ToolResult
 */
@FunctionalInterface
public interface TaskHandler {

    /**
     * Execute the task deterministically and return the result.
     *
     * @param context the resolved task description, expected output, and prior task outputs
     * @return the task result; must not be null; use {@link ToolResult#failure(String)}
     *         to signal an error
     */
    ToolResult execute(TaskHandlerContext context);
}
