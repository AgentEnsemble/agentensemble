# 26 - Coding Agents: Code-Aware Tools, Workspace Isolation, and MCP Bridge

This document specifies the design for coding agent support in AgentEnsemble: a set of
code-aware tools, git worktree workspace isolation, an MCP protocol bridge, and a
high-level `CodingAgent` factory that ties them together.

This builds on the v2.x tool system (`AgentTool`, `AbstractTypedAgentTool<T>`,
`AbstractAgentTool`) and the v3.0.0 long-running ensemble mode. It introduces four new
modules through pure composition -- no changes to `Agent`, `Task`, `Ensemble`, or
`AgentExecutor`.

---

## 1. Motivation

### The gap

AgentEnsemble can orchestrate agents that research, analyze, and produce reports. But it
cannot orchestrate agents that **write code**. A coding agent needs to:

1. **Read and search code** -- not just read a file, but grep for patterns, find files by
   glob, understand project structure
2. **Edit code surgically** -- not just overwrite a file, but replace a line range, find
   and replace, apply a diff
3. **Run builds and tests** -- invoke `gradle test` or `npm test`, parse the results into
   structured pass/fail data, and iterate on failures
4. **Operate git** -- check status, stage changes, commit, create branches
5. **Work in isolation** -- avoid polluting the main working tree with experimental changes

The existing tool set (`FileReadTool`, `FileWriteTool`, `ProcessAgentTool`) provides raw
building blocks but lacks the code-awareness, structured output parsing, and safety
guardrails that coding tasks require.

### Two ecosystems, one bridge

The **Model Context Protocol (MCP)** ecosystem provides well-tested reference servers for
filesystem operations and git -- written in TypeScript, running as subprocesses,
communicating over stdio. LangChain4j (already an AgentEnsemble dependency) has a built-in
MCP client (`McpClient`, `McpToolProvider`, `StdioMcpTransport`).

Rather than choosing between MCP and custom Java tools, we build **both**:

| Approach | When to use |
|---|---|
| **MCP backend** | Environments with Node.js. Mature, well-tested MCP servers for filesystem + git. |
| **Java backend** | Pure-JVM deployments. No Node.js dependency. Full control over tool behavior. |
| **Auto** (default) | `CodingAgent` detects whether MCP servers are available and chooses automatically. |

This gives users maximum flexibility without forcing a runtime dependency.

---

## 2. Module Structure

Four new modules, layered for independent use:

```
agentensemble-mcp/                -- MCP protocol bridge (AgentTool ← MCP)
agentensemble-tools/coding/       -- Custom Java coding tools (pure-JVM)
agentensemble-workspace/          -- Git worktree workspace isolation
agentensemble-coding/             -- CodingAgent factory (ties it all together)
```

### Dependency graph

```
agentensemble-coding
  ├── agentensemble-mcp              (compileOnly -- optional MCP backend)
  ├── agentensemble-tools:coding     (compileOnly -- optional Java backend)
  ├── agentensemble-workspace        (api -- always needed for isolation)
  └── agentensemble-tools:file-read  (implementation -- always needed)
```

### Pick what you need

| User goal | Depend on |
|---|---|
| Just coding tools (Java) | `agentensemble-tools-coding` |
| Just MCP bridge | `agentensemble-mcp` |
| Tools + workspace isolation | `agentensemble-tools-coding` + `agentensemble-workspace` |
| Full coding agent experience | `agentensemble-coding` (brings everything transitively) |

---

## 3. MCP Bridge (`agentensemble-mcp`)

### Problem

LangChain4j's MCP support exposes tools through `McpToolProvider`, which works with
LangChain4j `AiServices`. AgentEnsemble uses its own `AgentTool` interface. The bridge
adapts one to the other.

### McpAgentTool

Wraps a single MCP tool as an `AgentTool`:

```java
public final class McpAgentTool implements AgentTool {
    private final McpClient client;
    private final String toolName;
    private final String toolDescription;
    private final JsonObjectSchema parameters;

    @Override
    public String name() { return toolName; }

    @Override
    public String description() { return toolDescription; }

    @Override
    public ToolResult execute(String input) {
        // Parse input JSON, call client.executeTool(toolName, args), wrap result
    }
}
```

Because MCP tools already have typed parameter schemas, `McpAgentTool` implements
`TypedAgentTool<T>` where the schema is passed through directly to LangChain4j's
`ToolSpecification` -- no intermediate record needed.

### McpToolFactory

Creates `AgentTool` instances from an MCP server:

```java
public final class McpToolFactory {

    /** Connect to any MCP server and return all its tools as AgentTools. */
    public static List<AgentTool> fromServer(McpTransport transport) { ... }

    /** Connect to an MCP server, filter to specific tool names. */
    public static List<AgentTool> fromServer(McpTransport transport, String... toolNames) { ... }

    /** Convenience: start the MCP filesystem reference server. */
    public static McpServerLifecycle filesystem(Path allowedDir) { ... }

    /** Convenience: start the MCP git reference server. */
    public static McpServerLifecycle git(Path repoPath) { ... }
}
```

The `filesystem()` and `git()` methods use `StdioMcpTransport` to spawn the reference
servers as Node.js subprocesses:

```
npx @modelcontextprotocol/server-filesystem <allowedDir>
npx @modelcontextprotocol/server-git --repository <repoPath>
```

### McpServerLifecycle

Manages the lifecycle of an MCP server subprocess:

```java
public final class McpServerLifecycle implements AutoCloseable {
    private final McpClient client;
    private final McpTransport transport;

    public void start() { ... }      // Initialize transport and client
    public void close() { ... }      // Shutdown client, kill subprocess
    public boolean isAlive() { ... }
    public List<AgentTool> tools() { ... }
}
```

`McpServerLifecycle` implements `AutoCloseable` for try-with-resources usage. It also
integrates with the ensemble's `EnsembleListener` for automatic shutdown when the ensemble
stops.

### MCP reference server tools

The MCP **filesystem** server provides:

| Tool | Description |
|---|---|
| `read_text_file` | Read file contents with optional head/tail |
| `write_file` | Create or overwrite a file |
| `edit_file` | Multi-line content matching with dry-run mode |
| `search_files` | Recursive glob-based file search |
| `list_directory` | Directory listing |
| `directory_tree` | Recursive JSON tree of directory structure |
| `get_file_info` | File metadata (size, timestamps, permissions) |

The MCP **git** server provides:

| Tool | Description |
|---|---|
| `git_status` | Working tree status |
| `git_diff_unstaged` / `git_diff_staged` / `git_diff` | Diff views |
| `git_commit` | Commit staged changes |
| `git_add` | Stage files |
| `git_log` | Commit history with date filtering |
| `git_branch` / `git_create_branch` / `git_checkout` | Branch operations |
| `git_show` | Show commit contents |
| `git_reset` | Unstage changes |

### Build configuration

```kotlin
// agentensemble-mcp/build.gradle.kts
plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

dependencies {
    api(project(":agentensemble-core"))
    api(libs.langchain4j.mcp.client)
}
```

---

## 4. Custom Java Coding Tools (`agentensemble-tools/coding`)

Seven tools, all extending `AbstractTypedAgentTool<T>`, following the `FileWriteTool`
pattern: sandbox validation, approval gates, typed input records.

### 4.1 GlobTool

Find files by name pattern using `java.nio.file.PathMatcher` + `Files.walkFileTree`.
Sandboxed to `baseDir`.

```java
@ToolInput(description = "Find files matching a glob pattern")
public record GlobInput(
    @ToolParam(description = "Glob pattern, e.g. '**/*.java'") String pattern,
    @ToolParam(description = "Subdirectory to search within", required = false) String path
) {}
```

Returns matching paths sorted by name, capped at 200 results. Created via
`GlobTool.of(Path baseDir)`.

### 4.2 CodeSearchTool

Content search with regex patterns. Uses `ProcessBuilder` to invoke `grep -rn` (or `rg`
if detected at construction time) for performance on large codebases.

```java
@ToolInput(description = "Search code content using regex patterns")
public record CodeSearchInput(
    @ToolParam(description = "Regex pattern to search for") String pattern,
    @ToolParam(description = "Glob to filter files, e.g. '*.java'", required = false) String glob,
    @ToolParam(description = "Context lines before/after matches", required = false) Integer contextLines,
    @ToolParam(description = "Case insensitive search", required = false) Boolean ignoreCase,
    @ToolParam(description = "Subdirectory to search within", required = false) String path
) {}
```

Returns formatted `file:line:content` results, capped at 100 matches.

### 4.3 CodeEditTool

Surgical code edits with three modes:

| Mode | Parameters | Behavior |
|---|---|---|
| `replace_lines` | `startLine`, `endLine`, `content` | Replace a line range (1-based, inclusive) |
| `find_replace` | `find`, `content`, `regex` | Find text/regex and replace |
| `write` | `content` | Full file write (same as FileWriteTool) |

```java
@ToolInput(description = "Edit code files with surgical precision")
public record CodeEditInput(
    @ToolParam(description = "File path relative to workspace") String path,
    @ToolParam(description = "Edit mode: replace_lines, find_replace, or write") String command,
    @ToolParam(description = "Start line (1-based) for replace_lines", required = false) Integer startLine,
    @ToolParam(description = "End line (1-based, inclusive) for replace_lines", required = false) Integer endLine,
    @ToolParam(description = "New content or replacement text") String content,
    @ToolParam(description = "Text/regex to find for find_replace", required = false) String find,
    @ToolParam(description = "Use regex matching for find_replace", required = false) Boolean regex
) {}
```

Returns the modified file snippet showing a few lines of surrounding context. Supports
optional approval gates via `CodeEditTool.builder(Path).requireApproval(true).build()`.

### 4.4 ShellTool

General shell command execution. Always uses `baseDir` as working directory.

```java
@ToolInput(description = "Execute a shell command in the workspace")
public record ShellInput(
    @ToolParam(description = "Shell command to execute") String command,
    @ToolParam(description = "Working directory relative to workspace", required = false) String workingDir,
    @ToolParam(description = "Timeout in seconds", required = false) Integer timeoutSeconds
) {}
```

Long output is truncated at a configurable limit (default 10,000 characters) with a
message indicating truncation. Created via
`ShellTool.builder(Path).requireApproval(true).timeout(Duration.ofSeconds(60)).build()`.

### 4.5 GitTool

Git operations via subprocess. Destructive operations require approval.

```java
@ToolInput(description = "Execute git operations in the workspace repository")
public record GitInput(
    @ToolParam(description = "Git command: status, diff, log, commit, add, branch, stash, checkout")
    String command,
    @ToolParam(description = "Arguments (file paths, branch names)", required = false) String args,
    @ToolParam(description = "Commit message (for commit command)", required = false) String message
) {}
```

Dangerous operations (`push`, `reset --hard`, `force-push`, `rebase`) trigger
`requestApproval()` from `AbstractAgentTool`. Created via
`GitTool.builder(Path).requireApproval(true).build()`.

### 4.6 BuildRunnerTool

Build command execution with structured result parsing.

```java
@ToolInput(description = "Run a build command and parse the results")
public record BuildRunnerInput(
    @ToolParam(description = "Build command, e.g. 'gradle build'") String command,
    @ToolParam(description = "Working directory relative to workspace", required = false) String workingDir
) {}
```

Internally delegates to `ProcessBuilder`. Output is parsed heuristically into a structured
result attached via `ToolResult.success(text, structuredJsonNode)`:

```json
{ "success": true, "errors": [], "warnings": ["deprecation in Foo.java:42"] }
```

### 4.7 TestRunnerTool

Test execution with structured results.

```java
@ToolInput(description = "Run tests and parse the results")
public record TestRunnerInput(
    @ToolParam(description = "Test command, e.g. 'gradle test'") String command,
    @ToolParam(description = "Test class or file filter", required = false) String testFilter,
    @ToolParam(description = "Working directory relative to workspace", required = false) String workingDir
) {}

public record TestResult(
    boolean success,
    int passed,
    int failed,
    int skipped,
    List<TestFailure> failures
) {}

public record TestFailure(String testName, String message, String stackTrace) {}
```

Recognizes JUnit, Gradle, Maven, and npm test output patterns. Returns both human-readable
text and a `TestResult` as structured output.

### Build configuration

```kotlin
// agentensemble-tools/coding/build.gradle.kts
plugins {
    id("agentensemble.tool-conventions")
}

dependencies {
    compileOnly(project(":agentensemble-review"))
    testImplementation(project(":agentensemble-review"))
}

mavenPublishing {
    pom {
        name = "AgentEnsemble Tools: Coding"
        description = "Coding tools (search, edit, git, build, test) for AgentEnsemble"
    }
}
```

---

## 5. Workspace Isolation (`agentensemble-workspace`)

### Motivation

Coding agents make experimental changes. Without isolation, those changes land directly in
the user's working tree -- potentially breaking their build, conflicting with uncommitted
work, or leaving half-finished code behind if the agent fails.

Git worktrees provide zero-copy, branch-isolated working directories from the same
repository. Creating and cleaning up a worktree is cheap (no clone, no disk copy of the
full repo).

### Workspace interface

```java
public interface Workspace extends AutoCloseable {

    /** Absolute path to the isolated working directory. */
    Path path();

    /** Human-readable identifier (branch name for git worktrees). */
    String id();

    /** Whether this workspace is still active (not yet cleaned up). */
    boolean isActive();

    /** Clean up: remove worktree, delete temp branch, etc. */
    @Override
    void close();
}
```

### WorkspaceConfig

```java
@Builder @Value
public class WorkspaceConfig {
    /** Prefix for generated branch/directory names. */
    String namePrefix;

    /** Git ref to create the worktree from. Default: HEAD. */
    @Builder.Default String baseRef = "HEAD";

    /** Whether to auto-cleanup on close. Default: true. */
    @Builder.Default boolean autoCleanup = true;

    /** Base directory for workspaces. Default: <repoRoot>/.agentensemble/workspaces/ */
    Path workspacesDir;
}
```

### WorkspaceProvider

```java
public interface WorkspaceProvider {
    Workspace create(WorkspaceConfig config);
    Workspace create();  // default config
}
```

### GitWorktreeProvider

```java
public final class GitWorktreeProvider implements WorkspaceProvider {
    private final Path repoRoot;

    public static GitWorktreeProvider of(Path repoRoot) { ... }

    @Override
    public Workspace create(WorkspaceConfig config) {
        // 1. Generate branch name: <prefix>-<shortUUID>
        // 2. Resolve dir: <config.workspacesDir>/<branch>
        // 3. Execute: git worktree add -b <branch> <dir> <baseRef>
        // 4. Return GitWorktreeWorkspace
    }
}
```

`GitWorktreeWorkspace.close()` executes:
1. `git worktree remove <path>` (or `--force` if dirty and `autoCleanup` is true)
2. `git branch -D <branch>` to clean up the temporary branch

### DirectoryWorkspace

A simple fallback for non-git projects. Creates a temporary directory, optionally copies
files from a source directory. `close()` deletes the temp directory.

### WorkspaceLifecycleListener

An `EnsembleListener` that manages workspace lifecycle automatically:

```java
public final class WorkspaceLifecycleListener implements EnsembleListener {
    private final WorkspaceProvider provider;
    private final WorkspaceConfig config;
    private final Map<String, Workspace> active = new ConcurrentHashMap<>();

    @Override
    public void onTaskStart(TaskStartEvent event) {
        Workspace ws = provider.create(config);
        active.put(event.taskDescription(), ws);
    }

    @Override
    public void onTaskComplete(TaskCompleteEvent event) {
        Workspace ws = active.remove(event.taskDescription());
        if (ws != null) ws.close();
    }

    @Override
    public void onTaskFailed(TaskFailedEvent event) {
        Workspace ws = active.remove(event.taskDescription());
        if (ws != null) ws.close();
    }
}
```

This listener is opt-in. `CodingEnsemble` registers it automatically when using
`runIsolated()`.

---

## 6. CodingAgent Factory (`agentensemble-coding`)

### ProjectDetector

Auto-detects project type from filesystem markers:

```java
public record ProjectContext(
    String language,       // "java", "typescript", "python", "go", "rust", "unknown"
    String buildSystem,    // "gradle", "maven", "npm", "pip", "cargo", "go", "unknown"
    String buildCommand,   // "gradle build", "mvn compile", "npm run build"
    String testCommand,    // "gradle test", "mvn test", "npm test"
    List<String> sourceRoots  // ["src/main/java", "src/test/java"]
) {}

public final class ProjectDetector {
    public static ProjectContext analyze(Path directory) {
        // build.gradle.kts / build.gradle → Java/Gradle
        // pom.xml → Java/Maven
        // package.json → TypeScript or JavaScript / npm
        // pyproject.toml / requirements.txt → Python
        // go.mod → Go
        // Cargo.toml → Rust
        // Fallback: unknown
    }
}
```

### CodingSystemPrompt

Builds the agent's `background` string with coding-specific instructions:

```java
public final class CodingSystemPrompt {
    public static String build(String language, String buildCommand,
                               String testCommand, ProjectContext project) {
        // Produces a structured prompt like:
        //
        // You are an expert software engineer working on a Java project.
        //
        // ## Workflow
        // 1. Read relevant code to understand the task
        // 2. Plan your approach before making changes
        // 3. Make focused, minimal changes
        // 4. Run tests: gradle test
        // 5. If tests fail, analyze and fix
        // 6. Repeat until all tests pass
        //
        // ## Build system
        // Build: gradle build
        // Test: gradle test
        //
        // ## Project structure
        // - src/main/java
        // - src/test/java
    }
}
```

### CodingAgent

A factory that produces a standard `Agent` (no subclassing):

```java
Agent agent = CodingAgent.builder()
    .llm(model)
    .workingDirectory(Path.of("/path/to/project"))  // OR .workspace(workspace)
    .toolBackend(ToolBackend.JAVA)                   // or MCP, or AUTO (default)
    .requireApproval(true)                            // for destructive ops
    .maxIterations(75)                                // higher default for coding
    .additionalTools(customTool1, customTool2)        // user-provided extras
    .build();
```

`ToolBackend.AUTO` detection:
1. Check if `npx` is available on PATH
2. Check if MCP server packages are installed or installable
3. If yes → use MCP tools for filesystem + git, Java tools for TestRunner + BuildRunner
4. If no → use all Java tools

The factory:
1. Runs `ProjectDetector.analyze(baseDir)` to discover the project type
2. Assembles the appropriate tool set based on `ToolBackend`
3. Builds the system prompt via `CodingSystemPrompt`
4. Returns `Agent.builder().role("Senior Software Engineer").goal(...).background(...).tools(...).llm(llm).maxIterations(75).build()`

### CodingTask

Convenience factories for common coding tasks:

```java
// Fix a bug
Task task = CodingTask.fix("NullPointerException in UserService.getById() when user not found");

// Implement a feature
Task task = CodingTask.implement("Add pagination to the /api/users endpoint with limit and offset parameters");

// Refactor code
Task task = CodingTask.refactor("Extract UserRepository interface from UserService");

// Full control
Task task = CodingTask.builder()
    .description("Migrate from JUnit 4 to JUnit 5")
    .expectedOutput("All tests migrated and passing")
    .review(Review.required("Verify migration is complete"))
    .build();
```

### CodingEnsemble

Convenience factory for the full experience:

```java
// Direct execution (changes land in the working directory)
EnsembleOutput result = CodingEnsemble.run(model, workingDir,
    CodingTask.fix("Fix the login timeout bug"));

// Isolated execution (changes in a git worktree)
EnsembleOutput result = CodingEnsemble.runIsolated(model, repoRoot,
    CodingTask.implement("Add user profile endpoint"));
```

`runIsolated()` creates a `GitWorktreeWorkspace`, scopes all tools to it, runs the
ensemble, and returns the result. The worktree persists after completion so the user can
review and merge the changes.

---

## 7. Design Decisions

### No new WorkflowExecutor

The iterative test-fix cycle (write code → run tests → see failures → fix → repeat) is
handled by the existing ReAct loop in `AgentExecutor` with a higher `maxIterations`
default (75 vs 25). The coding-specific system prompt instructs the agent to follow the
plan-edit-test-iterate pattern.

This avoids modifying the `Workflow` enum (a core change) and adding a new
`WorkflowExecutor`. The LLM is better positioned to decide when to run tests and when to
iterate than a rigid outer loop.

If structured multi-phase workflows are needed later (e.g., planning agent → coding agent
→ review agent), the existing `Workflow.SEQUENTIAL` or `PhaseDagExecutor` can compose
them.

### Tools injected at construction, not workspace-aware

All coding tools accept a `Path baseDir` at construction time (same pattern as
`FileReadTool.of(Path)` and `FileWriteTool.of(Path)`). They are not "workspace-aware" --
they just operate on whatever directory they were given.

`CodingAgent.builder()` scopes all tools to `workspace.path()` when a workspace is
provided. This keeps tools simple, composable, and independently testable.

### MCP and Java tools coexist

Both tool backends produce standard `AgentTool` instances. They can be mixed freely in a
single agent's tool list. The `CodingAgent` factory can use MCP filesystem/git tools
alongside Java TestRunnerTool and BuildRunnerTool in the same agent.

### Approval gates on dangerous operations

All tools that modify state (file writes, git commits, shell commands) support
`requireApproval(boolean)` via `AbstractAgentTool.requestApproval()`. The `CodingAgent`
factory defaults `requireApproval(true)` for destructive operations (git push, shell
execution) and `requireApproval(false)` for routine operations (file reads, code search).

MCP tools do not have native approval gates. The `McpAgentTool` wrapper can optionally
add an approval gate before forwarding the call to the MCP server.

---

## 8. Implementation Phases

| Phase | Module | Deliverable | Dependencies |
|---|---|---|---|
| 1 | `agentensemble-mcp` | McpAgentTool, McpToolFactory, McpServerLifecycle | agentensemble-core, langchain4j-mcp |
| 2 | `agentensemble-tools/coding` | GlobTool, CodeSearchTool, CodeEditTool, ShellTool, GitTool, BuildRunnerTool, TestRunnerTool | agentensemble-core |
| 3 | `agentensemble-workspace` | Workspace, GitWorktreeProvider, DirectoryWorkspace, WorkspaceLifecycleListener | agentensemble-core |
| 4 | `agentensemble-coding` | ProjectDetector, CodingSystemPrompt, CodingAgent, CodingTask, CodingEnsemble | Phases 1-3 |
| 5 | `agentensemble-examples` | CodingAgentExample, IsolatedCodingExample | Phase 4 |

Phases 1, 2, and 3 are independent and can be worked in parallel. Phase 4 depends on all
three. Phase 5 depends on Phase 4.

---

## 9. Configuration Changes

### settings.gradle.kts

```kotlin
include("agentensemble-mcp")
include("agentensemble-tools:coding")
include("agentensemble-workspace")
include("agentensemble-coding")
```

### agentensemble-tools/bom/build.gradle.kts

```kotlin
constraints {
    // ... existing entries ...
    api(project(":agentensemble-tools:coding"))
}
```

### agentensemble-bom/build.gradle.kts

```kotlin
constraints {
    // ... existing entries ...
    api(project(":agentensemble-mcp"))
    api(project(":agentensemble-workspace"))
    api(project(":agentensemble-coding"))
}
```

---

## 10. Testing Strategy

| Module | Approach |
|---|---|
| `agentensemble-mcp` | Mock `McpClient`. Verify `McpAgentTool` adapts calls and results correctly. Verify `McpToolFactory` creates tools from mock server. |
| `agentensemble-tools/coding` | `@TempDir` filesystem isolation. Sandbox escape prevention. Approval gate mocking. Success and failure paths. 90% line / 75% branch coverage. |
| `agentensemble-workspace` | Temp git repo (`git init`). Verify worktree create/cleanup. Verify tools work within worktree. Verify dirty-workspace handling. |
| `agentensemble-coding` | Mock `ChatModel` with scripted tool calls. Verify tool list assembly per `ToolBackend`. Verify `ProjectDetector` recognizes project types. |
| E2E | `CodingEnsemble.runIsolated()` with a real LLM on a small test project containing a known bug. Verify the agent finds, fixes, and tests it. |
