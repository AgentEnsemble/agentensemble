# Active Context

## Current Work

Branch: `fix/error-prone-pmd-p1`
Tracking issue: GH #205

P1 and P2 code quality violation fixes (Error Prone + PMD). P1 committed as `94047f9`, P2 committed as `ef725fe`.

## Completed This Session

### Issue #202: Cross-Phase Context Bug Fix (PR #203)

Fixed `TaskExecutionException: Context task not yet completed` when using cross-phase
`Task.context(...)` with agentless tasks (the standard LLM-backed task pattern).

**Root cause:** `resolveAgents()` creates a new Task instance when synthesizing an agent
(`toBuilder().agent(synthesizedAgent).build()`). `globalTaskOutputs` (IdentityHashMap) is
keyed by the new instance, but the `context()` list in later-phase tasks still holds the
original (user-created) task reference. Identity-based lookup fails.

**Fix location:** `Ensemble.executePhases()` -- maintain a cumulative synchronized
`IdentityHashMap<Task, Task>` across all phases. Before calling `executeSeeded()`, augment
`priorOutputs` with entries keyed by original task references.

**Tests:** 8 new integration tests in `PhaseIntegrationTest` covering all 8 failure scenarios.
All use Mockito mock `ChatModel` for deterministic agentless execution. All confirmed FAIL
before fix and PASS after fix. Full build: BUILD SUCCESSFUL.

**Files changed:**
- `agentensemble-core/src/main/java/net/agentensemble/Ensemble.java` (72 lines changed)
- `agentensemble-core/src/test/java/net/agentensemble/integration/PhaseIntegrationTest.java` (368 lines added)

---

### Issue #195: Typed Tool Input System

Introduces `TypedAgentTool<T>` -- an opt-in extension to `AgentTool` that lets tool authors
declare a Java record as the tool's input type. The framework generates a typed JSON Schema
for the LLM, handles JSON deserialization, and validates required fields automatically.
Fully backward compatible -- `AgentTool` and `AbstractAgentTool` are unchanged.

### New framework types (agentensemble-core)

- `@ToolInput` -- optional annotation on input record classes
- `@ToolParam` -- annotation on record components (description, required)
- `TypedAgentTool<T>` -- interface extending `AgentTool`; adds `inputType()` + `execute(T)`
- `AbstractTypedAgentTool<T>` -- extends `AbstractAgentTool`; `doExecute(String)` deserializes
  JSON and delegates to `execute(T)`
- `ToolSchemaGenerator` -- introspects record classes via `getRecordComponents()`;
  maps Java types to LangChain4j `JsonObjectSchema` elements
- `ToolInputDeserializer` -- Jackson-based JSON -> typed record; validates required fields;
  `FAIL_ON_UNKNOWN_PROPERTIES=false` (LLM extra fields are silently ignored)
- `LangChain4jToolAdapter` -- updated to detect `TypedAgentTool`, generate multi-param schemas,
  and route full JSON args for typed tools vs "input" key extraction for legacy tools

### Built-in tool migrations

Migrated to `AbstractTypedAgentTool<T>`:
- `FileReadTool` -> `FileReadInput(path)`
- `FileWriteTool` -> `FileWriteInput(path, content)`
- `JsonParserTool` -> `JsonParserInput(jsonPath, json)` (replaces newline-delimiter format)
- `WebSearchTool` -> `WebSearchInput(query)`
- `WebScraperTool` -> `WebScraperInput(url)`

Legacy string-input kept intentionally (good examples for docs):
- `CalculatorTool` -- input IS a math expression DSL string
- `DateTimeTool` -- input IS a date command DSL string
- `HttpAgentTool` -- passes payload through to configured remote endpoint
- `ProcessAgentTool` -- passes input through to configured subprocess stdin

### Tests

- `ToolSchemaGeneratorTest` -- type mapping (string, integer, number, boolean, enum, array, object),
  required/optional, unannotated fields, null/non-record rejection
- `ToolInputDeserializerTest` -- happy path (all types), missing required fields, null values,
  invalid JSON, JSON array, contract violations
- `AbstractTypedAgentToolTest` -- deserialization bridge, exception handling (runtime, ExitEarly,
  ToolConfigurationException), type identity
- `TypedToolIntegrationTest` -- schema generation and execution routing via LangChain4jToolAdapter,
  missing required fields, extra fields ignored, legacy backward compat
- `LangChain4jToolAdapterTest` -- updated with typed spec generation and execution routing cases
- All tool tests updated: FileRead, FileWrite, JsonParser, WebSearch, WebScraper

### Documentation

- `docs/design/23-typed-tool-input.md` -- architecture doc
- `docs/guides/tools.md` -- Option 1 is now AbstractTypedAgentTool<T>
- `docs/examples/typed-tools.md` -- custom typed tool examples
- `docs/migration/typed-tool-inputs.md` -- migration guide (no breaking changes; opt-in)
- `mkdocs.yml` -- all new pages added to nav

## Status
- Full build: PASSING (239 tasks, BUILD SUCCESSFUL in 51s)
- All tests: PASSING
- Branch: `feature/195-typed-tool-input` committed (commit 7a2b78e)
- 37 files changed, 2669 insertions(+), 160 deletions(-)

## Key Design Decisions

### Backward compatibility
`AgentTool` interface and `AbstractAgentTool` are completely unchanged. Every existing tool
compiles and runs identically. `TypedAgentTool<T>` is purely opt-in.

### Execution routing
`LangChain4jToolAdapter.executeForResult()` detects `TypedAgentTool` instances and passes
the full JSON arguments string instead of extracting the "input" key. Legacy tools still
receive only the "input" key value (unchanged behavior).

### Intentional legacy examples
`CalculatorTool` and `DateTimeTool` intentionally keep the string-input style with explanatory
Javadoc -- they demonstrate when a single DSL string is the right design for tool inputs.

### Jackson configuration
`ToolInputDeserializer` configures `ObjectMapper` with `FAIL_ON_UNKNOWN_PROPERTIES=false`.
LLMs may include extra fields in their tool calls; silently ignoring them prevents spurious
failures and is the correct production behavior.

## Next Steps
- Await PR #196 review and merge
