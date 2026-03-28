# Coding Agent Example

Demonstrates the high-level `CodingEnsemble.run()` API for running a coding agent
directly in a project directory.

---

## What It Does

1. Accepts a project directory path as a command-line argument
2. Auto-detects the project type (Java/Gradle, npm, Python, etc.)
3. Assembles appropriate coding tools
4. Runs a bug-fix task using the configured LLM

## Code

```java
ChatModel model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o")
    .build();

EnsembleOutput output = CodingEnsemble.run(
    model,
    Path.of("/path/to/project"),
    CodingTask.fix("Find and fix any compilation errors"));

System.out.println(output.getRaw());
```

## Running

```bash
export OPENAI_API_KEY=sk-...
./gradlew :agentensemble-examples:runCodingAgent --args="/path/to/your/project"
```

If no path argument is provided, the current directory is used.

## Key Concepts

- **Project detection**: `ProjectDetector` scans the directory for build-file markers
  and returns a `ProjectContext` with language, build system, and source roots.
- **Tool assembly**: `CodingAgent.builder()` assembles the right tool set based on the
  detected `ToolBackend` (AUTO by default).
- **System prompt**: `CodingSystemPrompt` generates a coding-specific agent background
  with workflow instructions and build/test commands.

## See Also

- [Coding Agents Guide](../guides/coding-agents.md)
- [Isolated Coding Example](isolated-coding.md)
