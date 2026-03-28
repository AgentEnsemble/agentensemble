package net.agentensemble.tools.coding;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link GlobTool}.
 *
 * <p>The {@code pattern} field is required and accepts standard glob syntax
 * (e.g. {@code **&#47;*.java}, {@code src/**&#47;*.ts}). The optional {@code path}
 * field restricts the search to a subdirectory within the workspace.
 *
 * <p>Example LLM tool call:
 * <pre>
 * {
 *   "pattern": "**&#47;*.java",
 *   "path": "src/main"
 * }
 * </pre>
 */
@ToolInput(description = "Find files matching a glob pattern within the workspace")
public record GlobInput(
        @ToolParam(description = "Glob pattern, e.g. '**/*.java' or 'src/**/*.ts'") String pattern,
        @ToolParam(description = "Subdirectory to search within (relative to workspace root)", required = false)
                String path) {}
