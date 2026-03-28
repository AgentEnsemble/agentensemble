package net.agentensemble.tools.coding;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link BuildRunnerTool}.
 *
 * <p>Example LLM tool call:
 * <pre>
 * {
 *   "command": "gradle build",
 *   "workingDir": "subproject"
 * }
 * </pre>
 */
@ToolInput(description = "Run a build command and parse the results")
public record BuildRunnerInput(
        @ToolParam(description = "Build command to execute, e.g. 'gradle build' or 'mvn compile'") String command,
        @ToolParam(description = "Working directory relative to workspace root", required = false) String workingDir) {}
