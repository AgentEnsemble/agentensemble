package net.agentensemble.dashboard;

/**
 * SPI for handling incoming cross-ensemble work requests.
 *
 * <p>Implementations dispatch incoming task and tool requests to the appropriate shared
 * task or tool registered on the ensemble. The dashboard routes deserialized wire-protocol
 * messages to this handler.
 *
 * @see EnsembleDashboard#setRequestHandler(RequestHandler)
 */
public interface RequestHandler {

    /**
     * Result of handling a task request.
     *
     * @param status     outcome status ("COMPLETED", "FAILED", "REJECTED")
     * @param result     task output on success; null on failure/rejection
     * @param error      error message on failure/rejection; null on success
     * @param durationMs execution duration in milliseconds
     */
    record TaskResult(String status, String result, String error, long durationMs) {}

    /**
     * Result of handling a tool request.
     *
     * @param status     outcome status ("COMPLETED", "FAILED")
     * @param result     tool output on success; null on failure
     * @param error      error message on failure; null on success
     * @param durationMs execution duration in milliseconds
     */
    record ToolResult(String status, String result, String error, long durationMs) {}

    /**
     * Handle an incoming task request by executing the named shared task.
     *
     * @param taskName the name of the shared task to execute
     * @param context  the natural language input/context for the task
     * @return the execution result; never null
     */
    TaskResult handleTaskRequest(String taskName, String context);

    /**
     * Handle an incoming tool request by invoking the named shared tool.
     *
     * @param toolName the name of the shared tool to invoke
     * @param input    the input string for the tool
     * @return the invocation result; never null
     */
    ToolResult handleToolRequest(String toolName, String input);
}
