# MCP Coding Example

Demonstrates a coding agent that uses MCP (Model Context Protocol) reference servers
for filesystem and git operations instead of Java-native tools.

---

## What It Does

1. Accepts a git-tracked project directory as a command-line argument
2. Starts MCP filesystem and git reference servers scoped to the project
3. Combines all MCP tools into a single coding agent
4. Runs a bug-fix task using the MCP tools for file reading, editing, and git operations
5. Shuts down the MCP servers automatically when complete

## Code

```java
// Use the first CLI argument as the project directory, or default to "."
Path projectDir = args.length > 0 ? Path.of(args[0]) : Path.of(".");

ChatModel model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .build();

// Start MCP filesystem and git servers (AutoCloseable)
try (McpServerLifecycle fs = McpToolFactory.filesystem(projectDir);
        McpServerLifecycle git = McpToolFactory.git(projectDir)) {

    fs.start();
    git.start();

    // Combine filesystem and git tools
    List<Object> mcpTools = new ArrayList<>();
    mcpTools.addAll(fs.tools());
    mcpTools.addAll(git.tools());

    // Build a coding agent with MCP tools
    Agent agent = Agent.builder()
        .role("Senior Software Engineer")
        .goal("Implement, debug, and refactor code with precision")
        .tools(mcpTools)
        .llm(model)
        .maxIterations(75)
        .build();

    Task task = CodingTask.fix("Find and fix any compilation errors")
        .toBuilder()
        .agent(agent)
        .build();

    EnsembleOutput output = Ensemble.run(model, task);
    System.out.println(output.getRaw());
}
```

## Running

```bash
export OPENAI_API_KEY=sk-...
./gradlew :agentensemble-examples:runMcpCoding --args="/path/to/git/project"
```

If no path argument is provided, the current directory is used.

**Prerequisites:** Node.js must be installed (`npx` available on the system PATH).
The MCP reference servers are installed automatically via `npx --yes` on first run.

## Key Concepts

- **MCP servers**: `McpToolFactory.filesystem()` and `McpToolFactory.git()` start
  the official MCP reference server subprocesses. Each exposes a standard set of tools
  (file read/write/search, git status/diff/commit, etc.).
- **Lifecycle management**: `McpServerLifecycle` implements `AutoCloseable`. Use
  try-with-resources to ensure servers are shut down cleanly.
- **Tool composition**: MCP tools produce standard `AgentTool` instances that can be
  freely mixed with Java-native tools in any agent's tool list.
- **CodingTask**: The `CodingTask.fix()` convenience method provides a pre-configured
  task description and expected output for bug-fix workflows.

## MCP Tools Available

The exact tool names are defined by the MCP reference servers. You can discover them
at runtime via `fs.tools()` and `git.tools()`. Typical tools include:

### Filesystem server tools
`read_file`, `write_file`, `edit_file`, `search_files`, `list_directory`,
`directory_tree`, `get_file_info`

### Git server tools
`git_status`, `git_diff_unstaged`, `git_diff_staged`, `git_diff`, `git_commit`,
`git_add`, `git_log`, `git_branch`, `git_create_branch`, `git_checkout`, `git_show`,
`git_reset`

## See Also

- [MCP Bridge Guide](../guides/mcp.md) -- Full MCP integration documentation
- [Coding Agents Guide](../guides/coding-agents.md) -- Coding agent overview
- [Coding Agent Example](coding-agent.md) -- High-level CodingEnsemble API
- [Isolated Coding Example](isolated-coding.md) -- Git worktree isolation
