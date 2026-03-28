package net.agentensemble.tools.coding;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link CodeSearchTool}.
 *
 * <p>The {@code pattern} field is required and accepts regex syntax. Optional fields
 * control file filtering, context lines, case sensitivity, and search scope.
 *
 * <p>Example LLM tool call:
 * <pre>
 * {
 *   "pattern": "class\\s+\\w+Service",
 *   "glob": "*.java",
 *   "contextLines": 2,
 *   "ignoreCase": true,
 *   "path": "src/main"
 * }
 * </pre>
 */
@ToolInput(description = "Search code content using regex patterns")
public record CodeSearchInput(
        @ToolParam(description = "Regex pattern to search for") String pattern,
        @ToolParam(description = "Glob to filter files, e.g. '*.java'", required = false) String glob,
        @ToolParam(description = "Number of context lines before and after each match", required = false)
                Integer contextLines,
        @ToolParam(description = "Case insensitive search", required = false) Boolean ignoreCase,
        @ToolParam(description = "Subdirectory to search within (relative to workspace root)", required = false)
                String path) {}
