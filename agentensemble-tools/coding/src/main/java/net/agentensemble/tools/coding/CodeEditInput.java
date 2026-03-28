package net.agentensemble.tools.coding;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link CodeEditTool}.
 *
 * <p>Supports three edit modes:
 * <ul>
 *   <li>{@code replace_lines} -- replace a line range (1-based, inclusive)</li>
 *   <li>{@code find_replace} -- find text or regex and replace first occurrence</li>
 *   <li>{@code write} -- full file overwrite</li>
 * </ul>
 *
 * <p>Example LLM tool call (replace_lines mode):
 * <pre>
 * {
 *   "path": "src/main/java/Foo.java",
 *   "command": "replace_lines",
 *   "startLine": 5,
 *   "endLine": 7,
 *   "content": "    // replaced content"
 * }
 * </pre>
 */
@ToolInput(description = "Edit code files with surgical precision")
public record CodeEditInput(
        @ToolParam(description = "File path relative to workspace") String path,
        @ToolParam(description = "Edit mode: replace_lines, find_replace, or write") String command,
        @ToolParam(description = "Start line (1-based) for replace_lines mode", required = false) Integer startLine,
        @ToolParam(description = "End line (1-based, inclusive) for replace_lines mode", required = false)
                Integer endLine,
        @ToolParam(description = "New content or replacement text") String content,
        @ToolParam(description = "Text or regex to find for find_replace mode", required = false) String find,
        @ToolParam(description = "Use regex matching for find_replace mode", required = false) Boolean regex) {}
