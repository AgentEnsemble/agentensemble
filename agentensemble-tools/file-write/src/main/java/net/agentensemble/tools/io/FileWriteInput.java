package net.agentensemble.tools.io;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link FileWriteTool}.
 *
 * <p>Both {@code path} and {@code content} are required. The {@code path} must be a relative
 * path within the tool's configured sandbox directory. Parent directories are created
 * automatically if they do not exist.
 *
 * <p>Example LLM tool call:
 * <pre>
 * {
 *   "path": "reports/summary.txt",
 *   "content": "Analysis complete. Found 3 issues."
 * }
 * </pre>
 */
@ToolInput(description = "Parameters for writing a file within the sandbox directory")
public record FileWriteInput(
        @ToolParam(
                        description = "Relative file path within the sandbox directory, "
                                + "e.g. 'report.txt' or 'subdir/output.txt'. "
                                + "Path traversal (../) is not permitted.")
                String path,
        @ToolParam(description = "Text content to write to the file") String content) {}
