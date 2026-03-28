package net.agentensemble.tools.coding;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link ShellTool}.
 *
 * <p>Example LLM tool call:
 * <pre>
 * {
 *   "command": "ls -la src/main/java",
 *   "workingDir": "subproject",
 *   "timeoutSeconds": 30
 * }
 * </pre>
 */
@ToolInput(description = "Execute a shell command in the workspace")
public record ShellInput(
        @ToolParam(description = "Shell command to execute") String command,
        @ToolParam(description = "Working directory relative to workspace root", required = false) String workingDir,
        @ToolParam(description = "Timeout in seconds (default: 60)", required = false) Integer timeoutSeconds) {}
