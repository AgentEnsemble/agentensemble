package net.agentensemble.tools.coding;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link TestRunnerTool}.
 *
 * <p>Example LLM tool call:
 * <pre>
 * {
 *   "command": "gradle test",
 *   "testFilter": "--tests 'com.example.FooTest'",
 *   "workingDir": "subproject"
 * }
 * </pre>
 */
@ToolInput(description = "Run tests and parse the results")
public record TestRunnerInput(
        @ToolParam(description = "Test command to execute, e.g. 'gradle test' or 'mvn test' or 'npm test'")
                String command,
        @ToolParam(description = "Test filter appended to the command, e.g. '--tests FooTest'", required = false)
                String testFilter,
        @ToolParam(description = "Working directory relative to workspace root", required = false) String workingDir) {}
