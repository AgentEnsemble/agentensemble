# Coding Agents

AgentEnsemble can orchestrate agents that write, debug, and refactor code. The
`agentensemble-coding` module provides a high-level factory that auto-detects project type,
assembles the right tools, and generates coding-specific agent instructions.

---

## Quick Start

```java
EnsembleOutput result = CodingEnsemble.run(model, Path.of("/my/project"),
    CodingTask.fix("NullPointerException in UserService.getById()"));
```

That single call:
1. Detects the project type (Java/Gradle, npm, Python, etc.)
2. Assembles coding tools (file read, plus optional search/edit/git/test tools)
3. Generates a system prompt with build/test commands and source roots
4. Runs the agent with a higher iteration limit (75 vs the default 25)

---

## Project Detection

`ProjectDetector.analyze(Path)` scans the project root for build-file markers:

| Marker file | Language | Build system | Build command | Test command |
|---|---|---|---|---|
| `build.gradle.kts` / `build.gradle` | Java | Gradle | `./gradlew build` | `./gradlew test` |
| `pom.xml` | Java | Maven | `mvn compile` | `mvn test` |
| `package.json` + `tsconfig.json` | TypeScript | npm | `npm run build` | `npm test` |
| `package.json` | JavaScript | npm | `npm run build` | `npm test` |
| `pyproject.toml` / `requirements.txt` | Python | pip | `python -m build` | `python -m pytest` |
| `go.mod` | Go | go | `go build ./...` | `go test ./...` |
| `Cargo.toml` | Rust | Cargo | `cargo build` | `cargo test` |

Source roots are detected automatically (e.g., `src/main/java`, `src/test/java` for
Java/Gradle projects).

---

## CodingAgent Builder

For full control over agent construction:

```java
Agent agent = CodingAgent.builder()
    .llm(model)
    .workingDirectory(Path.of("/my/project"))
    .toolBackend(ToolBackend.AUTO)       // or JAVA, MCP, MINIMAL
    .requireApproval(true)               // for destructive operations
    .maxIterations(75)                   // higher default for coding
    .additionalTools(myCustomTool)       // extra tools
    .build();
```

The builder returns a standard `Agent` -- no subclassing. You can use it with
`Task`, `Ensemble`, or any other framework feature.

### Working Directory vs Workspace

Either `workingDirectory(Path)` or `workspace(Workspace)` is required (not both):

- **workingDirectory**: Tools operate directly in this directory. Changes are in-place.
- **workspace**: Tools operate in the workspace path (typically a git worktree). Use
  `CodingEnsemble.runIsolated()` to create the workspace automatically.

---

## Tool Backends

| Backend | Description | Requirements |
|---|---|---|
| `AUTO` (default) | Detect best available backend | None |
| `JAVA` | Java coding tools (glob, search, edit, shell, git, build, test) | `agentensemble-tools-coding` on classpath |
| `MCP` | MCP reference servers for filesystem + git | `agentensemble-mcp` on classpath + Node.js |
| `MINIMAL` | `FileReadTool` only | Always available |

`AUTO` resolves in order: MCP > JAVA > MINIMAL. If neither optional module is on the
classpath, the agent works with file-read only.

### Using MCP Tools Directly

You can also start MCP servers manually and pass their tools to any agent via
`additionalTools()`. This gives you full control over server lifecycle:

```java
try (McpServerLifecycle fs = McpToolFactory.filesystem(projectDir);
        McpServerLifecycle git = McpToolFactory.git(projectDir)) {
    fs.start();
    git.start();

    List<Object> mcpTools = new ArrayList<>();
    mcpTools.addAll(fs.tools());
    mcpTools.addAll(git.tools());

    Agent agent = Agent.builder()
        .role("Senior Software Engineer")
        .goal("Implement, debug, and refactor code with precision")
        .tools(mcpTools)
        .llm(model)
        .maxIterations(75)
        .build();

    Task task = CodingTask.fix("Fix the login timeout bug")
        .toBuilder().agent(agent).build();

    EnsembleOutput output = Ensemble.run(model, task);
}
```

See the [MCP Coding Example](../examples/mcp-coding.md) for a complete walkthrough.

---

## CodingTask Convenience Methods

Pre-configured tasks for common coding workflows:

```java
// Bug fix
Task task = CodingTask.fix("NullPointerException in handler");

// Feature implementation
Task task = CodingTask.implement("Add pagination to /api/users");

// Refactoring
Task task = CodingTask.refactor("Extract UserRepository interface");
```

Each returns a standard `Task` that can be further customized:

```java
Task task = CodingTask.fix("Some bug")
    .toBuilder()
    .expectedOutput("Custom expected output")
    .build();
```

---

## CodingEnsemble Runners

### Direct Execution

Changes are made directly in the working directory:

```java
EnsembleOutput result = CodingEnsemble.run(model, workingDir,
    CodingTask.fix("Fix the login timeout bug"));
```

### Isolated Execution

Changes are made in a git worktree. The worktree is preserved on success (for review)
and cleaned up on failure:

```java
EnsembleOutput result = CodingEnsemble.runIsolated(model, repoRoot,
    CodingTask.implement("Add user profile endpoint"));
```

After a successful isolated run, you can review changes in the worktree directory and
merge them manually (e.g., `git merge` from the worktree branch).

---

## Dependencies

```kotlin
// Full coding agent experience
implementation("net.agentensemble:agentensemble-coding:$version")

// Optional: Java coding tools (GlobTool, CodeEditTool, ShellTool, etc.)
// implementation("net.agentensemble:agentensemble-tools-coding:$version")

// Optional: MCP bridge (filesystem + git via MCP servers)
// implementation("net.agentensemble:agentensemble-mcp:$version")
```

The workspace module (`agentensemble-workspace`) is included transitively.

---

## See Also

- [Workspace Isolation](workspace-isolation.md) -- Git worktree management
- [MCP Bridge](mcp.md) -- MCP protocol integration
- [Tools](tools.md) -- Tool system overview
- [Coding Agent Example](../examples/coding-agent.md) -- Walkthrough
- [Isolated Coding Example](../examples/isolated-coding.md) -- Worktree walkthrough
- [MCP Coding Example](../examples/mcp-coding.md) -- MCP backend walkthrough
- [Coding Tools Example](../examples/coding-tools.md) -- Java coding tools
