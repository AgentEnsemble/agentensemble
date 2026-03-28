# Coding Tools Example

This example shows how to assemble a coding agent with all 7 coding tools from
the `agentensemble-tools-coding` module.

## Wiring All Coding Tools

```java
import net.agentensemble.tools.coding.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

Path workspace = Path.of("/workspace/my-project");

var agent = Agent.builder()
    .role("Senior Software Engineer")
    .goal("Fix bugs and implement features in the codebase")
    .tools(List.of(
        GlobTool.of(workspace),
        CodeSearchTool.of(workspace),
        CodeEditTool.builder(workspace).requireApproval(true).build(),
        ShellTool.builder(workspace).requireApproval(true).timeout(Duration.ofSeconds(60)).build(),
        GitTool.builder(workspace).requireApproval(true).build(),
        BuildRunnerTool.of(workspace),
        TestRunnerTool.of(workspace)
    ))
    .llm(chatModel)
    .maxIterations(75)
    .build();
```

## Search-Only Agent

For agents that only read and search code. Note: `GlobTool`, `CodeSearchTool`,
and `GitTool` with `of()` do not have approval gates. If you need to prevent
the agent from running destructive git commands (push, reset, etc.), use
`builder().requireApproval(true)` instead.

```java
var reviewer = Agent.builder()
    .role("Code Reviewer")
    .goal("Review code and identify issues")
    .tools(List.of(
        GlobTool.of(workspace),
        CodeSearchTool.of(workspace),
        GitTool.of(workspace)   // of() -- no approval gate; safe AND destructive commands allowed
    ))
    .llm(chatModel)
    .build();
```

## Build and Test Agent

An agent focused on running builds and tests:

```java
var tester = Agent.builder()
    .role("Test Engineer")
    .goal("Run tests and report results")
    .tools(List.of(
        BuildRunnerTool.builder(workspace).timeout(Duration.ofSeconds(300)).build(),
        TestRunnerTool.builder(workspace).timeout(Duration.ofSeconds(600)).build(),
        GlobTool.of(workspace),
        CodeSearchTool.of(workspace)
    ))
    .llm(chatModel)
    .maxIterations(30)
    .build();
```

## Using Structured Test Results

The `TestRunnerTool` provides structured output that can be consumed programmatically:

```java
var ensemble = Ensemble.builder()
    .addTask(Task.builder()
        .description("Run all tests and report results")
        .build())
    .addAgent(tester)
    .listener(new EnsembleListener() {
        @Override
        public void onToolCall(ToolCallEvent event) {
            if ("test_runner".equals(event.toolName()) && event.structuredResult() != null) {
                JsonNode result = (JsonNode) event.structuredResult();
                int passed = result.get("passed").asInt();
                int failed = result.get("failed").asInt();
                System.out.printf("Tests: %d passed, %d failed%n", passed, failed);
            }
        }
    })
    .build();
```

## Workspace Scoping

All coding tools accept a `Path baseDir` at construction time. When combined
with the `agentensemble-workspace` module (see [Workspace Isolation](../guides/workspace-isolation.md)),
all tools are automatically scoped to the isolated working directory:

```java
Path isolatedDir = workspace; // or a git worktree path

// All tools operate within the same sandboxed directory
var tools = List.of(
    GlobTool.of(isolatedDir),
    CodeSearchTool.of(isolatedDir),
    CodeEditTool.of(isolatedDir),
    ShellTool.builder(isolatedDir).requireApproval(false).build(),
    GitTool.of(isolatedDir),
    BuildRunnerTool.of(isolatedDir),
    TestRunnerTool.of(isolatedDir)
);
```
