
# Workspace Isolation Examples

These examples show how to create isolated workspaces for coding agents using git worktrees
and temporary directories.

## Git Worktree -- Basic Usage

Create an isolated worktree from the current HEAD, do work in it, and clean up automatically:

```java
import net.agentensemble.workspace.GitWorktreeProvider;
import net.agentensemble.workspace.Workspace;

import java.nio.file.Path;

public class BasicWorktreeExample {

    public static void main(String[] args) {
        GitWorktreeProvider provider = GitWorktreeProvider.of(Path.of("."));

        try (Workspace ws = provider.create()) {
            System.out.println("Working in: " + ws.path());
            System.out.println("Branch: " + ws.id());
            // Agent tools operate on ws.path() instead of the main working tree
        }
        // Worktree and branch are removed automatically
    }
}
```

## Git Worktree -- Custom Configuration

Branch from a specific ref, with a descriptive prefix:

```java
import net.agentensemble.workspace.GitWorktreeProvider;
import net.agentensemble.workspace.Workspace;
import net.agentensemble.workspace.WorkspaceConfig;

import java.nio.file.Path;

public class CustomWorktreeExample {

    public static void main(String[] args) {
        GitWorktreeProvider provider = GitWorktreeProvider.of(Path.of("."));

        WorkspaceConfig config = WorkspaceConfig.builder()
                .namePrefix("fix-login")
                .baseRef("main")
                .autoCleanup(false)  // keep the worktree for manual inspection
                .build();

        Workspace ws = provider.create(config);
        System.out.println("Workspace: " + ws.path());
        // Branch name: fix-login-<uuid>

        // ... agent does its work ...

        ws.close();
        // autoCleanup=false: worktree and branch still exist
        // User can inspect results and merge manually
    }
}
```

## Directory Workspace -- Non-Git Projects

For projects without git, use a temporary directory:

```java
import net.agentensemble.workspace.DirectoryWorkspace;

import java.nio.file.Files;
import java.nio.file.Path;

public class DirectoryWorkspaceExample {

    public static void main(String[] args) throws Exception {
        // Empty workspace
        try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp()) {
            Files.writeString(ws.path().resolve("hello.py"), "print('hello')");
            System.out.println("Created: " + ws.path());
        }

        // Copy from existing source (.git directory is skipped)
        try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp(Path.of("/my/project"))) {
            System.out.println("Copied project to: " + ws.path());
        }
    }
}
```

## Lifecycle Listener -- Automatic Workspace Management

Let the ensemble manage workspace creation and cleanup automatically:

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.workspace.GitWorktreeProvider;
import net.agentensemble.workspace.WorkspaceLifecycleListener;

import java.nio.file.Path;

public class LifecycleListenerExample {

    public static void main(String[] args) {
        // Configure your LLM provider -- for example:
        // ChatModel model = OpenAiChatModel.builder().apiKey(...).build();

        GitWorktreeProvider provider = GitWorktreeProvider.of(Path.of("."));
        WorkspaceLifecycleListener listener = WorkspaceLifecycleListener.of(provider);

        Ensemble.builder()
                .chatLanguageModel(model)
                .listener(listener)
                .task(Task.of("Fix the failing test in AuthService"))
                .build()
                .run();

        // Workspace was created before the task started,
        // and cleaned up after the task completed (or failed).
    }
}
```

## Parallel Tasks with Independent Workspaces

Each task in a parallel workflow gets its own isolated workspace:

```java
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.workspace.GitWorktreeProvider;
import net.agentensemble.workspace.WorkspaceConfig;
import net.agentensemble.workspace.WorkspaceLifecycleListener;

import java.nio.file.Path;

public class ParallelWorkspacesExample {

    public static void main(String[] args) {
        // Configure your LLM provider -- for example:
        // ChatModel model = OpenAiChatModel.builder().apiKey(...).build();

        GitWorktreeProvider provider = GitWorktreeProvider.of(Path.of("."));
        WorkspaceConfig config = WorkspaceConfig.builder()
                .namePrefix("parallel")
                .build();
        WorkspaceLifecycleListener listener = WorkspaceLifecycleListener.of(provider, config);

        Task fixAuth = Task.of("Fix the authentication bug");
        Task addLogging = Task.of("Add structured logging to the API layer");

        Ensemble.builder()
                .chatLanguageModel(model)
                .listener(listener)
                .tasks(fixAuth, addLogging)
                .build()
                .run();

        // Each task ran in its own worktree:
        // parallel-<uuid1> for fixAuth, parallel-<uuid2> for addLogging
    }
}
```

## See Also

- [Workspace Isolation Guide](../guides/workspace-isolation.md)
- [Callbacks and Event Listeners](../guides/callbacks.md)
