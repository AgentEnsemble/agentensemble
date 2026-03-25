# Active Context

## Current Work

Branch: `feat/toon-context-format`
Tracking issue: GH #206, PR #207

TOON context format integration -- design doc, documentation, and scaffolding complete.
Implementation code to follow.

## Completed This Session

### TOON Context Format Documentation (GH #206, PR #207)

Created the full documentation scaffolding for integrating TOON (Token-Oriented Object
Notation) as a configurable serialization format for LLM-facing structured data:

- **Design doc**: `docs/design/25-toon-context-format.md` -- ADR specifying ContextFormat
  enum, ContextFormatter SPI, integration points (AgentPromptBuilder, AgentExecutor,
  ExecutionTrace), optional JToon dependency, and testing strategy.
- **User guide**: `docs/guides/toon-format.md` -- Setup instructions, what gets formatted,
  when to use JSON vs TOON, error handling, full example.
- **Example page**: `docs/examples/toon-format.md` -- Three-task pipeline walkthrough with
  JSON vs TOON comparison.
- **Runnable example**: `ToonFormatExample.java` with `runToonFormat` Gradle task.
- **Reference update**: `contextFormat` field added to ensemble-configuration.md builder table.
- **Version catalog**: `jtoon = 1.0.9` added to libs.versions.toml.
- **Navigation**: mkdocs.yml updated with new pages in Guides, Examples, and Design sections.

### Previous Session: P3 Performance Fixes (GH #205)

## Previous Session Work

### P3 Performance Fixes (GH #205)

Fixed all addressable P3 performance violations from the PMD and Error Prone reports.
4 commits on `fix/error-prone-pmd-p3` branch:

**Commit 1 (bc7ce41): GuardLogStatement + StringBuilder optimizations**

- GuardLogStatement (174 violations): Added `if (log.isXxxEnabled())` guards around all
  debug/trace/warn log calls across 38 production source files. Files using `log()` accessor
  method (AbstractAgentTool, ToolPipeline, ProcessAgentTool) fixed manually with
  `if (log().isXxxEnabled())` pattern. WebSocketServer inner-class inline logger fixed with
  local variable guard. AgentPromptBuilder fully rewritten.

- StringBuilder performance (InsufficientStringBufferDeclaration, ConsecutiveAppendsShouldReuse,
  ConsecutiveLiteralAppends, AppendCharacterWithChar): 145 violations across 7 files:
  - AgentPromptBuilder, ReflectionPromptBuilder, DefaultManagerPromptStrategy: initial capacity
    increased, chained appends, merged adjacent literals, char literals for single chars
  - SerpApiSearchProvider, TavilySearchProvider: initial capacity, char literals
  - MemoryTool, JsonSchemaGenerator: char literals for single-char appends

**Commit 2 (48a06e9): LooseCoupling fixes**

Changed concrete collection type declarations to interfaces across 18 production files and
6 test files:
- ConcurrentHashMap -> Map (ConnectionManager, TemplateResolver, EmbeddingMemoryStore,
  EmbeddingStoreLongTermMemory, MemoryContext, InMemoryEntityMemory, InMemoryStore,
  InMemoryReflectionStore, AgentExecutor, and others)
- CopyOnWriteArrayList -> List (ConnectionManager per-run message lists)
- IdentityHashMap -> Map (Ensemble, PhaseDagExecutor, SequentialWorkflowExecutor,
  ParallelWorkflowExecutor, TaskDependencyGraph local variables)

Instantiation sites (new ConcurrentHashMap<>(), new IdentityHashMap<>()) preserved.
Added missing java.util.Map imports where needed.

**Commit 3 (ce91798): AvoidInstantiatingObjectsInLoops + StringSplitter**

- AvoidInstantiatingObjectsInLoops (2 fixes):
  - EmbeddingMemoryStore, EmbeddingStoreLongTermMemory: hoisted HashMap for metadata
    reconstruction outside the results loop; use clear() before each iteration.
  - Note: remaining violations are inherently per-iteration (putIfAbsent new ArrayList<>(),
    new AtomicInteger per phase -- each element needs its own independent collection).

- StringSplitter (Error Prone, 3 violations):
  - LlmReflectionStrategy: Pattern.compile("\n") constant replaces section.split("\n", -1)
  - TypedToolsExample: Pattern.compile(",") constant in SortTool inner class
  - DeterministicOnlyPipelineExample: EQUALS_SIGN and SEMICOLON Pattern constants

**Commit 4 (5e7ab65): Missing imports + ProcessAgentTool correction**

Added missing java.util.Map and java.util.List imports to 9 files after LooseCoupling changes.
Fixed ConnectionManagerTest.java where script created invalid java.util.concurrent.List reference.
Reverted two extra ProcessAgentTool log guards (stdout/stderr drain lambdas) that were not in
the PMD violation list and caused branch coverage to drop below 0.75 threshold.

## Status
- Full clean build: BUILD SUCCESSFUL (273 tasks, 1m 12s)
- All tests: PASSING
- Branch: `fix/error-prone-pmd-p3`
- PR pending

## Key Design Decisions

### Log guard pattern for log() method tools
AbstractAgentTool, ToolPipeline, ProcessAgentTool use `log()` as a method call (not a field).
Guards use `if (log().isXxxEnabled()) { log().xxx(...); }` -- calls log() twice per guarded call
which is acceptable since log() is a fast field access with fallback.

### LooseCoupling: IdentityHashMap kept for identity semantics
IdentityHashMap declarations for fields/variables used as plain Map (no identity-specific API
calls) were changed to Map. But IdentityHashMap instantiation sites are preserved -- the
concrete type is correct at creation.

### AvoidInstantiatingObjectsInLoops: per-iteration collections accepted
Many violations are putIfAbsent/computeIfAbsent patterns where each absent key genuinely
needs a new independent collection. These cannot be hoisted without changing semantics.
Only the metadata HashMap pattern (populate, Map.copyOf(), discard) was optimizable.

## Next Steps
- Merge `fix/error-prone-pmd-p3` PR
- Continue with P4 style fixes (issue #205, lowest priority)
