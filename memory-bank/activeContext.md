# Active Context

## Current Work Focus

Issues #104 and #105 (v2.0.0 Task-First Architecture, Group A) and issues #106 and #107
(agentensemble-memory module and task-scoped memory, Group B) are both complete on their
respective branches:
- `feat/106-107-memory-module-and-scoped-memory` (PR #123, open)
- main already includes #104 and #105

## Recent Changes

### Issue #106: Extract agentensemble-memory module

Moved all memory classes from `agentensemble-core` into a new dedicated
`agentensemble-memory` Gradle module with a clean SPI boundary.

**Key decisions:**
- Introduced `MemoryRecord` (carrier record) so `MemoryContext.record()` accepts task
  output metadata without importing `TaskOutput` from core -- breaks circular dependency
- `EnsembleMemory` builder throws `IllegalArgumentException` (no dependency on core
  `ValidationException`)
- `agentensemble-core` declares `compileOnly(agentensemble-memory)` -- memory is optional
- All 9 main classes + 6 original test classes moved; 1 new test class
  (`MemoryOperationListenerTest`) and expanded `MemoryContextTest` for listener callbacks

### Issue #107: Task-scoped cross-execution memory with named scopes

Added `MemoryStore` SPI, `MemoryScope`, `EvictionPolicy`, and `MemoryTool` to
`agentensemble-memory`. Replaced `Ensemble.builder().memory(EnsembleMemory)` with
`Ensemble.builder().memoryStore(MemoryStore)` as the primary v2.0.0 memory API.

**New types in `agentensemble-memory`:**
- `MemoryStore` interface + `InMemoryStore` + `EmbeddingMemoryStore`
- `MemoryScope` with `MemoryScopeBuilder`
- `EvictionPolicy` with `keepLastEntries()` and `keepEntriesWithin()` factories
- `MemoryTool` with `@Tool storeMemory` and `@Tool retrieveMemory`
- `MemoryEntry` updated: `{content, structuredContent, storedAt, metadata}` (breaking)

**Core changes:**
- `Task.builder().memory(String)`, `memory(String...)`, `memory(MemoryScope)` -- declare scopes
- `Ensemble.builder().memoryStore(MemoryStore)` -- replaces `memory(EnsembleMemory)`
- `ExecutionContext.memoryStore()` -- new accessor
- `AgentPromptBuilder.buildUserPrompt()` -- injects `## Memory: {scope}` sections
- `AgentExecutor` -- stores task output in declared scopes after completion

### Issue #104: Task-First Core -- Task absorbs Agent responsibilities

**Summary:** `Ensemble.builder().agent()` removed. `Task.agent` made optional. New task-level
fields: `tools`, `chatLanguageModel`, `maxIterations`. Static factory methods `Task.of()`.
Static `Ensemble.run(ChatModel, Task...)` convenience method.

**Modified classes in `agentensemble-core`:**
- `Task` -- `agent` is now optional (nullable). New fields: `chatLanguageModel`, `tools` (`List<Object>`),
  `maxIterations` (`Integer`). Static factories `Task.of(String)` and `Task.of(String, String)`.
- `Ensemble` -- `agents` field removed from builder. New fields: `chatLanguageModel`, `agentSynthesizer`.
  Static `Ensemble.run(ChatModel, Task...)` method.
- `EnsembleValidator` -- `validateAgentsNotEmpty()` and `validateAgentMembership()` removed. New
  `validateTasksHaveLlm()`.

**New classes in `agentensemble-core`:**
- `net.agentensemble.synthesis.SynthesisContext`
- `net.agentensemble.synthesis.AgentSynthesizer`
- `net.agentensemble.synthesis.TemplateAgentSynthesizer`
- `net.agentensemble.synthesis.LlmBasedAgentSynthesizer`

### Issue #105: AgentSynthesizer SPI

Delivered as part of Issue #104 above. Fully integrated.

## Next Steps

- Issues #108+ (human-in-the-loop review gates, partial results redesign)
- Merge PR #123 (feat/106-107-memory-module-and-scoped-memory)

## Important Patterns and Preferences

### v2.0.0 Memory API (Issues #106/#107)
- MemoryStore + task scopes is the v2.0.0 primary memory API (replaces EnsembleMemory)
- Tasks declare scopes with `.memory("name")` -- framework auto-reads before and auto-writes after
- InMemoryStore: insertion order, most-recent retrieval, eviction supported
- EmbeddingMemoryStore: semantic similarity via LangChain4j, eviction is no-op
- MemoryEntry.metadata uses string map -- standard keys "agentRole" and "taskDescription"
- Scope isolation is absolute: tasks only read from scopes they explicitly declare

### v2 Task-First API (Issues #104/#105)
- `Task.of(description)` -- zero-ceremony, default expectedOutput, no agent required
- `Task.of(description, expectedOutput)` -- zero-ceremony with custom output
- `Ensemble.run(model, tasks...)` -- static factory, single-line ensemble execution
- `Ensemble.builder().chatLanguageModel(model)` -- ensemble-level LLM for synthesis
- `Ensemble.builder().agentSynthesizer(...)` -- synthesis strategy (default: template)
- `Task.builder().agent(Agent)` -- explicit agent (power-user escape hatch)
- `Task.builder().chatLanguageModel(ChatModel)` -- per-task LLM override
- `Task.builder().tools(List)` -- per-task tools for synthesized agent
- `Task.builder().maxIterations(int)` -- per-task iteration cap

### Agent Resolution Order (in Ensemble.resolveAgents())
1. If task.getAgent() != null: use as-is
2. Else: synthesize using agentSynthesizer with (task-level LLM or ensemble LLM)
3. Apply task-level maxIterations and tools to synthesized agent
4. Set resolved agent on task via toBuilder()

## Previous Issues (complete)

### Issue #100: MapReduceEnsemble Short-Circuit Optimization (complete)
### Issue #99: Adaptive MapReduceEnsemble (complete)
