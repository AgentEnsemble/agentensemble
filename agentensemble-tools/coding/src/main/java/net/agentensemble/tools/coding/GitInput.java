package net.agentensemble.tools.coding;

import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;

/**
 * Typed input record for {@link GitTool}.
 *
 * <p>Example LLM tool calls:
 * <pre>
 * {"command": "status"}
 * {"command": "diff"}
 * {"command": "log", "args": "--oneline -10"}
 * {"command": "add", "args": "src/main/java/Foo.java"}
 * {"command": "commit", "message": "Fix null check in UserService"}
 * {"command": "branch", "args": "-a"}
 * </pre>
 */
@ToolInput(description = "Execute git operations in the workspace repository")
public record GitInput(
        @ToolParam(
                        description =
                                "Git command: status, diff, log, commit, add, branch, stash, checkout, show, tag, merge, fetch, pull, push, reset")
                String command,
        @ToolParam(description = "Additional arguments (file paths, branch names, flags)", required = false)
                String args,
        @ToolParam(description = "Commit message (used with the commit command)", required = false) String message) {}
