# 13 - Future Roadmap

This document outlines features planned for Phase 2 and beyond. These are NOT part of the Phase 1 implementation but are designed into the architecture so they can be added without breaking changes.

## Phase 2: Hierarchical Workflow

**Goal**: Enable a manager agent that automatically delegates tasks to worker agents based on their roles and goals.

### How It Works

1. User defines agents and tasks as usual
2. User sets `workflow(Workflow.HIERARCHICAL)`
3. Optionally provides a `managerLlm` (defaults to the first agent's LLM)
4. At `run()` time:
   - A "Manager" agent is automatically created with a meta-prompt
   - The manager receives the full list of tasks and available worker agents
   - The manager decides which agent should handle each task
   - The manager can re-order tasks, combine outputs, and synthesize a final result

### Design Considerations

- `HierarchicalWorkflowExecutor` implements `WorkflowExecutor`
- Manager prompt includes agent roles/goals/backgrounds so it can make informed delegation decisions
- Manager uses tool calls to delegate: `delegate_task(agent_role, task_description)`
- Manager produces the final synthesized output
- Error handling: if a delegated task fails, the manager is informed and can reassign or skip

### API Extension

```java
var ensemble = Ensemble.builder()
    .agents(List.of(researcher, writer, editor))
    .tasks(List.of(researchTask, writeTask, editTask))
    .workflow(Workflow.HIERARCHICAL)
    .managerLlm(gpt4Model)  // New optional field
    .build();
```

---

## Phase 3: Memory System

**Goal**: Enable agents to maintain context across tasks and across ensemble runs.

### Memory Types

#### Short-Term Memory
- Conversation context within a single task execution
- Already partially supported via the chat memory window in AgentExecutor
- Enhancement: make the window size configurable per agent

#### Long-Term Memory
- Persists across ensemble runs
- Backed by a vector store (via LangChain4j's EmbeddingStore)
- Before each task, relevant memories are retrieved and injected into the prompt
- After each task, key information is extracted and stored

#### Entity Memory
- Tracks information about specific entities mentioned across tasks
- Uses a structured store (key-value or graph)
- Agents can query entity memory for known facts about a person, company, concept, etc.

### API Extension

```java
var ensemble = Ensemble.builder()
    .agents(List.of(researcher, writer))
    .tasks(List.of(researchTask, writeTask))
    .workflow(Workflow.SEQUENTIAL)
    .memory(EnsembleMemory.builder()
        .shortTerm(true)
        .longTerm(embeddingStore)
        .entityMemory(entityStore)
        .build())
    .build();
```

---

## Phase 4: Agent Delegation

**Goal**: Allow agents to delegate subtasks to other agents within the same ensemble during task execution.

### How It Works

- Agent A is executing a task and decides it needs help
- Agent A calls a `delegate` tool: `delegate(agent_role="Data Analyst", task="Analyze this dataset...")`
- The framework pauses Agent A, executes the delegated subtask with the target agent
- The subtask output is returned to Agent A as the tool result
- Agent A incorporates the result and continues

### Requirements

- Agent must have `allowDelegation = true`
- Target agent must be in the ensemble's agent list
- Delegation depth limit to prevent infinite recursion (configurable, default: 3)
- Delegated subtasks are logged separately with clear parent-child relationship

---

## Phase 5: Parallel Workflow (COMPLETE -- v0.5.0)

**Implemented**: `Workflow.PARALLEL`, `TaskDependencyGraph`, `ParallelWorkflowExecutor`, `ParallelErrorStrategy`, `ParallelExecutionException`.

### How It Works

- `TaskDependencyGraph` builds a DAG from each task's `context` list using identity-based maps.
- Tasks with no unmet dependencies start immediately on Java 21 virtual threads.
- As each task completes, its dependents are evaluated. Those whose all dependencies are now satisfied are submitted; those with failed dependencies are skipped.
- Uses `Executors.newVirtualThreadPerTaskExecutor()` (stable Java 21 API, no preview flags).
- MDC is propagated from the calling thread into each virtual thread.

### Implemented API

```java
var ensemble = Ensemble.builder()
    .agents(List.of(a1, a2, a3))
    .tasks(List.of(t1, t2, t3))  // t1 and t2 are independent, t3 depends on both
    .workflow(Workflow.PARALLEL)
    .parallelErrorStrategy(ParallelErrorStrategy.FAIL_FAST)  // or CONTINUE_ON_ERROR
    .build();
// t1 and t2 run concurrently, t3 runs after both complete
```

### Error Strategies

- `FAIL_FAST` (default): cancel unstarted tasks on first failure, throw `TaskExecutionException`.
- `CONTINUE_ON_ERROR`: independent tasks finish; failed-dep tasks skipped; throw `ParallelExecutionException` with partial results.

See [docs/design/10-concurrency.md](10-concurrency.md) for the full concurrency design.

---

## Phase 6: Structured Output (COMPLETE -- v0.6.0)

**Implemented**: `Task.outputType`, `Task.maxOutputRetries`, `TaskOutput.parsedOutput`,
`TaskOutput.getParsedOutput(Class)`, `JsonSchemaGenerator`, `StructuredOutputParser`,
`ParseResult`, `OutputParsingException`.

### How It Works

- `Task.outputType(Class<?>)` specifies the target Java class (records, POJOs, common JDK types).
- `AgentPromptBuilder` injects an `## Output Format` section into the user prompt containing the
  JSON schema derived from the class, plus explicit JSON-only instructions.
- `AgentExecutor` runs a retry loop after the main execution:
  1. `StructuredOutputParser.extractJson(raw)` -- extracts JSON from the response, handling plain
     JSON, markdown fences, and prose-embedded JSON.
  2. `StructuredOutputParser.parse(json, type)` -- deserializes via Jackson (`FAIL_ON_UNKNOWN_PROPERTIES = false`).
  3. On failure: sends a correction prompt to the LLM showing the error and schema; retries up to
     `Task.maxOutputRetries` times (default: 3).
  4. On exhaustion: throws `OutputParsingException` with raw output, parse errors, and attempt count.
- Parsed output is stored in `TaskOutput.parsedOutput`; access via `getParsedOutput(Class<T>)`.

### Implemented API

```java
record ResearchReport(String title, List<String> findings, String conclusion) {}

var task = Task.builder()
    .description("Research AI trends")
    .expectedOutput("A structured research report")
    .agent(researcher)
    .outputType(ResearchReport.class)   // required JSON schema injected into prompt
    .maxOutputRetries(3)               // default; use 0 to disable retries
    .build();

// After execution:
ResearchReport report = taskOutput.getParsedOutput(ResearchReport.class);
```

### Deferred to a Future Release

- Generic collection types as top-level output (`List<MyRecord>` -- `Class<?>` cannot carry generic info).
  Workaround: wrap in a record: `record Results(List<MyRecord> items) {}`.
- Setting `ResponseFormat.JSON` on `ChatRequest` to use native JSON mode on models that support it
  (would require detecting model capability at runtime). Prompt-based instruction works universally.

---

## Phase 7: Advanced Features

### Callbacks and Event Listeners

```java
ensemble.onTaskStart(event -> log.info("Starting: {}", event.taskDescription()));
ensemble.onTaskComplete(event -> log.info("Done: {} in {}", event.taskDescription(), event.duration()));
ensemble.onToolCall(event -> log.info("Tool: {}", event.toolName()));
```

### Streaming Output

- Stream agent responses token-by-token using LangChain4j's `StreamingChatLanguageModel`
- Useful for real-time UIs showing agent progress

### Guardrails / Output Validation

- Pre-execution guardrails: validate task inputs before sending to LLM
- Post-execution guardrails: validate outputs against custom rules
- Pluggable validation interface

### Built-In Tool Library

A separate module `agentensemble-tools` providing common tools:
- Web search (via SerpAPI, Tavily, etc.)
- Web scraping
- File read/write
- Code execution (sandboxed)
- Calculator
- Date/time utilities
- JSON/XML parsing

### Rate Limiting

- Per-agent or per-LLM rate limiting (requests per minute)
- Useful when multiple agents share the same API key
- Configurable via agent builder or ensemble builder

---

## Release Plan

| Phase | Target | Key Features |
|---|---|---|
| Phase 1 | v0.1.0 | Core framework: Agent, Task, Ensemble, sequential workflow, tools |
| Phase 2 | v0.2.0 | Hierarchical workflow |
| Phase 3 | v0.3.0 | Memory system (short-term, long-term, entity) |
| Phase 4 | v0.4.0 | Agent delegation |
| Phase 5 | v0.5.0 | Parallel workflow |
| Phase 6 | v0.6.0 | Structured output |
| Phase 7 | v1.0.0 | Callbacks, streaming, guardrails, built-in tools |

Each phase should be backward-compatible with previous phases. The API is designed with future phases in mind -- builder methods can be added without breaking existing code.
