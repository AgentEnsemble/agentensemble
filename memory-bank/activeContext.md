# Active Context

## Current Work Focus

PR #43 merged to main (squash commit ecd7625). Branch fix/copilot-review-feedback deleted.
All Copilot review feedback from PRs #21-#41 and PR #43 itself is now on main.
Development continues at 0.5.0-SNAPSHOT.

## Recent Changes

- PR #43 second commit (3064533): addressed 4 Copilot inline comments on PR #43
  - Ensemble.java: validateContextOrdering() now uses identity-based sets
    (IdentityHashMap-backed executedSoFar + ensureTaskSet) to be consistent with
    resolveTasks() and validateAgentMembership(); prevents value-equal but
    identity-distinct context tasks from passing validation but failing remapping
  - ToolResult.java: updated failure() Javadoc: "null is normalized to a default
    message" instead of "must not be null"
  - MemoryContextTest.java: replaced vacuous testRecord_withoutLongTerm_doesNotCallStore
    (unwired mock verify was always true) with assertion-based test verifying
    hasLongTerm() is false, STM is recorded, queryLongTerm returns empty
  - TaskOutput.java: added @NonNull to raw, taskDescription, agentRole, completedAt,
    duration fields to match the design spec in docs/design/03-domain-model.md
  - TaskOutputTest.java: updated three null-field tests to expect NullPointerException
    (enforced by Lombok @NonNull at build time)

- PR #43 first commit (44c482b): addressed all actionable Copilot review feedback
  from 16 closed PRs #21-#41
  - Bug: Ensemble.resolveTasks two-pass approach; IdentityHashMap for agent membership
  - Null safety: 13 defensive fixes across AgentDelegationTool, DelegateTaskTool,
    EmbeddingStoreLongTermMemory, AgentExecutor, AgentPromptBuilder, Task, Agent,
    ToolResult, LangChain4jToolAdapter, EnsembleMemory
  - Correctness: MDC save/restore for nested delegation; hierarchical error wrapping;
    HIERARCHICAL role validation; toolCallCounter fix; MemoryContext.isActive semantics
  - Code quality: effective tool count logging; WARN for tool errors; UUID sentinel in
    TemplateResolver; constructor delegation; prompt stripTrailing
  - Documentation: logback version sync, template-variables hyphens, workflows timing
  - Tests: +10 tests (287 -> 297); fixed dead test; renamed misleading test
  - CI: skipped-guard on automerge; dependabot groups for github-actions
  - 297 tests passing

- Issue #17 merged (PR #41): agent delegation fully implemented
  - `DelegationContext`: immutable runtime state; create() factory; descend() creates
    child with depth+1; isAtLimit() when currentDepth >= maxDepth
  - `AgentDelegationTool`: @Tool-annotated; auto-injected by AgentExecutor when
    allowDelegation=true and delegationContext != null; guards: depth limit, self-
    delegation, unknown role; accumulates delegatedOutputs
  - `AgentExecutor`: 5-arg execute(Task, List, boolean, MemoryContext, DelegationContext);
    buildEffectiveTools() prepends delegation tool when applicable; 4-arg backward-compat
    delegates to 5-arg with null DelegationContext
  - `Ensemble`: maxDelegationDepth field (default 3, validated > 0); passes to
    SequentialWorkflowExecutor(agents, maxDelegationDepth) and
    HierarchicalWorkflowExecutor(managerLlm, agents, managerMaxIterations, maxDelegationDepth)
  - `SequentialWorkflowExecutor`: 2-arg constructor; creates DelegationContext per run;
    passes to agentExecutor.execute(task, contextOutputs, verbose, memoryContext, ctx)
  - `HierarchicalWorkflowExecutor`: 4-arg constructor; creates workerDelegationContext;
    passes to DelegateTaskTool(agents, executor, verbose, memoryContext, ctx)
  - `DelegateTaskTool`: 5-arg constructor adds delegationContext; threads through to
    agentExecutor.execute() for worker executions
  - MDC keys: delegation.depth, delegation.parent (set during delegated executions)
  - 287 tests passing (was 251, +36 new)
- v0.4.0 released: tag pushed, GitHub Packages, GitHub Release triggered by CI
- Comprehensive user documentation added: 21 files in docs/
  (getting-started/, guides/, reference/, examples/) covering all features through v0.4.0
- README updated: Agent Delegation section, updated config tables, docs index

## Next Steps

1. Merge PR #43 -- DONE (ecd7625 on main)
2. Issue #18: Parallel workflow (concurrent independent tasks, Java 21 virtual threads)
3. Issue #19: Structured output (outputType on Task, JSON parsing, retry loop)
4. Issue #20: Advanced features (callbacks, streaming, guardrails, built-in tools)

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
