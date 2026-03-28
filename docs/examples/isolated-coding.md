# Isolated Coding Example

Demonstrates `CodingEnsemble.runIsolated()` which runs a coding agent in a git worktree,
keeping changes isolated from the main working tree.

---

## What It Does

1. Accepts a git repository root as a command-line argument
2. Creates an isolated git worktree (branch from HEAD)
3. Runs a feature-implementation task inside the worktree
4. Preserves the worktree on success so you can review and merge

## Code

```java
ChatModel model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .build();

EnsembleOutput output = CodingEnsemble.runIsolated(
    model,
    Path.of("/path/to/git/repo"),
    CodingTask.implement("Add a README.md file with a project overview"));

System.out.println(output.getRaw());
```

## Running

```bash
export OPENAI_API_KEY=sk-...
./gradlew :agentensemble-examples:runIsolatedCoding --args="/path/to/git/repo"
```

The argument must be the root of a git repository.

## How Isolation Works

`runIsolated()` uses the `agentensemble-workspace` module to:

1. **Create a worktree**: `git worktree add -b agent-<uuid> .agentensemble/workspaces/agent-<uuid> HEAD`
2. **Scope tools**: All coding tools operate inside the worktree directory
3. **Run the ensemble**: The agent reads, edits, and tests code in the worktree
4. **Preserve on success**: The worktree stays so you can `cd` into it, review changes, and merge
5. **Clean up on failure**: If the agent fails, the worktree is removed automatically

After a successful run, you can merge the changes:

```bash
cd /path/to/repo
git merge agent-a1b2c3d4
```

Or cherry-pick specific commits from the worktree branch.

## See Also

- [Coding Agents Guide](../guides/coding-agents.md)
- [Workspace Isolation Guide](../guides/workspace-isolation.md)
- [Coding Agent Example](coding-agent.md)
