# Workspace Isolation

Coding agents make experimental changes. Without isolation, those changes land directly in the
user's working tree -- potentially breaking their build, conflicting with uncommitted work, or
leaving half-finished code behind if the agent fails.

The `agentensemble-workspace` module provides workspace isolation using **git worktrees** for
git-based projects and **temporary directories** for non-git projects.

## Git Worktrees vs. Temporary Directories

| Backend | When to use | How it works |
|---|---|---|
| **Git worktree** (`GitWorktreeProvider`) | Git repositories (the common case) | Creates a zero-copy, branch-isolated working directory from the same repo |
| **Temporary directory** (`DirectoryWorkspace`) | Non-git projects or quick experiments | Creates a temp directory, optionally copies source files |

Git worktrees are preferred because they share the object store with the main repository,
so creation is fast and disk-efficient.

## Core Interfaces

### Workspace

Every isolated working directory implements the `Workspace` interface:

| Method | Returns | Description |
|--------|---------|-------------|
| `path()` | `Path` | Absolute path to the isolated working directory |
| `id()` | `String` | Human-readable identifier (branch name for worktrees, directory name otherwise) |
| `isActive()` | `boolean` | Whether the workspace is still active (not yet cleaned up) |
| `close()` | `void` | Clean up: remove worktree + branch, or delete temp directory |

`Workspace` extends `AutoCloseable`, so you can use try-with-resources:

```java
try (Workspace ws = provider.create()) {
    // All changes happen inside ws.path()
}
// Workspace is automatically cleaned up here
```

### WorkspaceConfig

Controls how workspaces are created:

| Field | Default | Description |
|-------|---------|-------------|
| `namePrefix` | `"agent"` | Prefix for generated branch/directory names |
| `baseRef` | `"HEAD"` | Git ref to branch from (ignored by `DirectoryWorkspace`) |
| `autoCleanup` | `true` | Whether `close()` removes the worktree/directory |
| `workspacesDir` | `<repoRoot>/.agentensemble/workspaces/` | Where to create workspaces |

```java
WorkspaceConfig config = WorkspaceConfig.builder()
    .namePrefix("fix-login-bug")
    .baseRef("main")
    .autoCleanup(true)
    .build();
```

### WorkspaceProvider

Factory interface for creating workspaces:

```java
public interface WorkspaceProvider {
    Workspace create(WorkspaceConfig config);
    Workspace create();  // uses default config
}
```

## Using Git Worktrees

### Setup

```java
GitWorktreeProvider provider = GitWorktreeProvider.of(Path.of("/path/to/repo"));
```

The `of()` method validates that the path is a git repository (has a `.git` directory or file).

### Creating a Workspace

```java
// Default: branch from HEAD, auto-cleanup on close
try (Workspace ws = provider.create()) {
    Path workDir = ws.path();
    // workDir is a fully functional git worktree on its own branch
    // e.g., /path/to/repo/.agentensemble/workspaces/agent-a1b2c3d4
}
```

### Custom Configuration

```java
WorkspaceConfig config = WorkspaceConfig.builder()
    .namePrefix("refactor")
    .baseRef("feature/auth")
    .workspacesDir(Path.of("/tmp/workspaces"))
    .build();

try (Workspace ws = provider.create(config)) {
    // Branch: refactor-<uuid>, based on feature/auth
    // Located in /tmp/workspaces/refactor-<uuid>
}
```

### What Happens on Close

When `close()` is called on a git worktree workspace:

1. `git worktree remove <path>` -- removes the worktree
2. If the worktree is dirty (uncommitted changes), retries with `--force`
3. `git branch -D <branch>` -- deletes the temporary branch
4. If any step fails, it logs a warning but does not throw

### Keeping a Workspace

Set `autoCleanup(false)` to keep the worktree after close:

```java
WorkspaceConfig config = WorkspaceConfig.builder()
    .autoCleanup(false)
    .build();

Workspace ws = provider.create(config);
// ... agent does its work ...
ws.close();
// Worktree and branch still exist -- user can inspect and merge manually
```

## Using Directory Workspaces

For non-git projects, use `DirectoryWorkspace` directly:

```java
// Empty temp directory
try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp()) {
    Files.writeString(ws.path().resolve("main.py"), "print('hello')");
}

// Copy from an existing directory (.git is automatically skipped)
try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp(Path.of("/project/src"))) {
    // ws.path() contains a copy of /project/src
}
```

## Automatic Lifecycle Management

`WorkspaceLifecycleListener` is an `EnsembleListener` that creates a workspace when a task
starts and cleans it up when the task completes or fails. Register it on your ensemble:

```java
GitWorktreeProvider provider = GitWorktreeProvider.of(repoRoot);
WorkspaceLifecycleListener listener = WorkspaceLifecycleListener.of(provider);

EnsembleOutput result = Ensemble.builder()
    .chatLanguageModel(model)
    .listener(listener)
    .task(Task.of("Refactor the authentication module"))
    .build()
    .run();
```

### Looking Up the Active Workspace

During task execution, tools can look up their workspace:

```java
Optional<Workspace> ws = listener.getWorkspace(taskDescription);
ws.ifPresent(workspace -> {
    Path workDir = workspace.path();
    // Use workDir for file operations, builds, etc.
});
```

### Custom Configuration

```java
WorkspaceConfig config = WorkspaceConfig.builder()
    .namePrefix("coding")
    .baseRef("develop")
    .build();

WorkspaceLifecycleListener listener = WorkspaceLifecycleListener.of(provider, config);
```

### Monitoring

```java
Map<String, Workspace> active = listener.activeWorkspaces();
active.forEach((task, ws) ->
    System.out.println(task + " -> " + ws.path()));
```

## Error Handling

- `GitWorktreeProvider.of()` throws `WorkspaceException` if the path is not a git repository
- `WorkspaceProvider.create()` throws `WorkspaceException` if worktree creation fails
- `Workspace.close()` never throws -- cleanup failures are logged at WARN level
- `WorkspaceLifecycleListener` catches all exceptions internally to avoid disrupting task execution

## Dependency

```kotlin
// Gradle
implementation("net.agentensemble:agentensemble-workspace:$agentensembleVersion")

// Or via the BOM
implementation(platform("net.agentensemble:agentensemble-bom:$agentensembleVersion"))
implementation("net.agentensemble:agentensemble-workspace")
```

## Related

- [Callbacks and Event Listeners](callbacks.md)
- [Design Doc: Coding Agents](../design/26-coding-agents.md)
