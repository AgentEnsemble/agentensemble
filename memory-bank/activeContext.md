# Active Context

## Current Work Focus

v0.3.0 released and published to GitHub Packages. Development continues on main at 0.4.0-SNAPSHOT.
README updated to document hierarchical workflow and memory system.

## Recent Changes

- Issue #16 merged (PR #40): memory system fully implemented
  - `MemoryEntry`: immutable record of a task output captured for memory
  - `ShortTermMemory`: per-run list accumulator; unmodifiable view via getEntries()
  - `LongTermMemory`: interface for cross-run persistence
  - `EmbeddingStoreLongTermMemory`: LangChain4j EmbeddingStore + EmbeddingModel;
    embed() returns Response<Embedding> (still uses Response wrapper in 1.11.0)
  - `EntityMemory`: interface for named entity key-value fact store
  - `InMemoryEntityMemory`: ConcurrentHashMap-backed; thread-safe
  - `EnsembleMemory`: config object with field-initializer builder (no @Builder.Default)
  - `MemoryContext`: runtime state per run; disabled() is a shared no-op singleton;
    from(config) creates new STM per call (fresh context per run)
  - `AgentPromptBuilder`: 3-arg buildUserPrompt(Task, List<TaskOutput>, MemoryContext);
    STM section replaces explicit context when STM active; LTM + entity sections appended
  - `AgentExecutor`: memory-aware 4-arg execute() overload; backward-compat 3-arg overload
  - `WorkflowExecutor`: interface updated to execute(List, boolean, MemoryContext)
  - `SequentialWorkflowExecutor` / `HierarchicalWorkflowExecutor` / `DelegateTaskTool`:
    all updated to accept and thread MemoryContext through
  - `Ensemble`: added `memory` field (EnsembleMemory, nullable); creates MemoryContext
    at start of run(); passes through to WorkflowExecutor
  - 251 tests passing (was 174, +77 new)
- v0.3.0 released: tag pushed, GitHub Packages, GitHub Release triggered by CI

## Next Steps

1. Issue #17: Agent delegation (allowDelegation flag, delegation depth limit)
2. Issue #18: Parallel workflow (concurrent independent tasks, Java 21 virtual threads)
3. Issue #19: Structured output (outputType on Task, JSON parsing, retry loop)

## Important Notes

- LangChain4j 1.11.0: EmbeddingModel.embed(String) returns Response<Embedding>
  (NOT dropped the Response wrapper unlike ChatModel.chat())
- EmbeddingStore.add(Embedding, TextSegment) -- store method with explicit embedding
- Metadata.from(key, value) -- static factory on dev.langchain4j.data.document.Metadata
- EmbeddingSearchRequest.builder().queryEmbedding(e).maxResults(n).minScore(0.0).build()
- EmbeddingSearchResult.matches() -> List<EmbeddingMatch<TextSegment>>
- EmbeddingMatch.embedded() -> TextSegment
- Lombok @Builder.Default + custom build() causes static context errors -- always
  use field initializers in the inner builder class instead

## Active Decisions

- **Memory architecture**: MemoryContext is created fresh per run() call; LTM and
  entity memory are shared across runs (user controls their lifecycle)
- **STM replaces explicit context**: when shortTerm=true, the "Short-Term Memory"
  section in the user prompt replaces (not duplicates) the explicit context section
- **Manager excluded from STM**: in hierarchical workflow, the manager agent itself
  uses MemoryContext.disabled() -- only worker delegations participate in memory
- **EntityMemory is user-seeded**: no automatic entity extraction; users populate
  facts manually before running the ensemble

## Important Patterns and Preferences

- TDD: Write tests first, then implementation
- Feature branches per GitHub issue
- No git commit --amend (linear history)
- No emoji/unicode in code or developer docs
- Production-grade quality, not prototype
- Lombok @Builder + custom builder class: use field initializers, NOT @Builder.Default
