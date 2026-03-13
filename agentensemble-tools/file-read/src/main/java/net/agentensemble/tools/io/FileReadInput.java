package net.agentensemble.tools.io;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link FileReadTool}.
 *
 * <p>Example LLM tool call:
 * <pre>
 * { "path": "reports/summary.txt" }
 * </pre>
 */
@ToolInput(description = "Parameters for reading a file within the sandbox directory")
public record FileReadInput(
        @ToolParam(
                        description = "Relative file path within the sandbox directory, "
                                + "e.g. 'report.txt' or 'data/notes.txt'. "
                                + "Path traversal (../) is not permitted.")
                String path) {}
