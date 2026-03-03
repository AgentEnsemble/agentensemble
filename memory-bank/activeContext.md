# Active Context

## Current Work Focus

PR #66 open on `fix/javadoc-link-error-add-to-ci`: fixes the broken javadoc `{@link}`
references that caused the v0.7.0 release workflow to fail, and adds `:agentensemble-core:javadoc`
to the CI workflow so the same class of error is caught pre-merge.

Full javadoc cleanup (92 remaining warnings) is tracked in issue #65.
Next after #66 merges: Issue #42 (Execution Metrics) or Issue #58 (Guardrails).

## Recent Changes

- **Class-size refactoring** (6 commits on main):
  - Extracted `EnsembleValidator` (package-private) from `Ensemble`: all 10 validate*()
    methods + detectCycle() + warnUnusedAgents() moved out; `Ensemble` delegates via
    `new EnsembleValidator(this).validate()`. Ensemble.java: 523 -> 342 lines.
  - Extracted `StructuredOutputHandler` (package-private) from `AgentExecutor`:
    `parseStructuredOutput()` + `buildStructuredOutputCorrectionPrompt()` moved out;
    AgentExecutor delegates via `StructuredOutputHandler.parse(agent, task, response, prompt)`.
    AgentExecutor.java: 411 -> 321 lines.
  - Extracted `ParallelTaskCoordinator` (package-private) from `ParallelWorkflowExecutor`:
    `submitTask()`, `resolveDependent()`, `shouldSkip()` moved into coordinator class that
    holds all per-execution shared state as fields (eliminates 16-parameter method signatures).
    ParallelWorkflowExecutor.java: 510 -> 243 lines.
  - Split test files by concern (all tests preserved, zero new assertions):
    - `EnsembleTest` (386) -> `EnsembleTest` (141 builder/listeners) + `EnsembleValidationTest` (265)
    - `TaskTest` (438) -> `TaskTest` (264 builder/defaults) + `TaskValidationTest` (199)
    - `ParallelWorkflowExecutorTest` (505) -> 3 files + shared base class
    - `ParallelEnsembleIntegrationTest` (558) -> basic (376) + error strategy (212)
    - `HierarchicalEnsembleIntegrationTest` (385) -> execution (289) + validation+callbacks (95)
    - `DelegationEnsembleIntegrationTest` (376) -> core scenarios (224) + config/memory (209)
  - No file in the project now exceeds 380 lines.

- Issue #57 (Callbacks + ExecutionContext refactor) implemented on feature branch:

  **New packages and classes:**
  - `net.agentensemble.execution.ExecutionContext`: immutable value bundling `MemoryContext`,
    `verbose` flag, and `List<EnsembleListener>`. Factory methods: `of(mc, verbose, listeners)`,
    `of(mc, verbose)`, `disabled()`. Fire methods (`fireTaskStart`, `fireTaskComplete`,
    `fireTaskFailed`, `fireToolCall`) catch per-listener exceptions and log at WARN without
    aborting execution or blocking subsequent listeners.
  - `net.agentensemble.callback.EnsembleListener`: interface with 4 default no-op methods:
    `onTaskStart`, `onTaskComplete`, `onTaskFailed`, `onToolCall`.
  - `net.agentensemble.callback.TaskStartEvent`: record(taskDescription, agentRole, taskIndex, totalTasks)
  - `net.agentensemble.callback.TaskCompleteEvent`: record(taskDescription, agentRole, taskOutput, duration, taskIndex, totalTasks)
  - `net.agentensemble.callback.TaskFailedEvent`: record(taskDescription, agentRole, cause, duration, taskIndex, totalTasks)
  - `net.agentensemble.callback.ToolCallEvent`: record(toolName, toolArguments, toolResult, agentRole, duration)
  - `net.agentensemble.agent.ToolResolver`: package-private class extracted from AgentExecutor;
    `resolve(List<Object>)` -> `ResolvedTools` nested record with `hasTools()` and `execute(request)`.

  **Changes to existing classes:**
  - `WorkflowExecutor.execute()`: signature changed from `(List<Task>, boolean, MemoryContext)` to
    `(List<Task>, ExecutionContext)`.
  - `AgentExecutor`: 3 overloads collapsed to 2: `execute(task, ctx, ExecutionContext)` and
    `execute(task, ctx, ExecutionContext, DelegationContext)`. Fires `ToolCallEvent` after each
    tool execution in the ReAct loop. `ToolResolver` replaces inline `resolveTools()` and
    `ResolvedTools` inner record.
  - `DelegationContext`: replaced separate `memoryContext` + `verbose` fields with single
    `ExecutionContext executionContext`. `create()` signature: `(peers, maxDepth, executionContext,
    executor)`. `getExecutionContext()` replaces `getMemoryContext()` + `isVerbose()`.
  - `DelegateTaskTool`: constructor changed from `(agents, executor, verbose, memoryContext, dc)` to
    `(agents, executor, executionContext, dc)`.
  - `AgentDelegationTool.delegate()`: uses `delegationContext.getExecutionContext()` for execute call.
  - `SequentialWorkflowExecutor.execute()`: fires `TaskStartEvent` before each task, `TaskCompleteEvent`
    after success, `TaskFailedEvent` in catch block before re-throwing.
  - `ParallelWorkflowExecutor.execute()`: same event firing pattern; parallel taskIndex=0 (unordered).
  - `HierarchicalWorkflowExecutor.execute()`: same event firing for manager meta-task; manager runs
    with `ExecutionContext.of(MemoryContext.disabled(), verbose, listeners)` (disabled memory, same listeners).
  - `Ensemble`: added `@Singular List<EnsembleListener> listeners` field; custom `EnsembleBuilder`
    inner class with convenience methods `onTaskStart(Consumer)`, `onTaskComplete(Consumer)`,
    `onTaskFailed(Consumer)`, `onToolCall(Consumer)` -- each wraps the Consumer in an anonymous
    `EnsembleListener` and delegates to Lombok-generated `listener(EnsembleListener)`.
    `run()` builds `ExecutionContext.of(memoryContext, verbose, listeners)`.

  **Updated test files:**
  - `AgentExecutorTest`: 8 call sites -> `ExecutionContext.disabled()`
  - `DelegationContextTest`: rewritten with `ExecutionContext` (removed memoryContext/verbose params)
  - `AgentDelegationToolTest`: `execute()` mock updated, `DelegationContext.create()` updated
  - `DelegateTaskToolTest`: constructor and create() updated
  - `ParallelWorkflowExecutorTest`: `execute()` calls + memory test updated; imports updated
  - `HierarchicalWorkflowExecutorTest`: `execute()` calls updated; imports updated
  - `EnsembleTest`: 7 listener builder tests added

  **New test files:** 499 total (up from 440 baseline)
  - `ExecutionContextTest`: 20 tests (factory methods, immutability, fire-method exception safety)
  - `EnsembleListenerTest`: 10 tests (default no-ops, event record fields, override patterns)
  - `ToolResolverTest`: 10 tests (AgentTool resolution, @Tool resolution, dispatch, unknown tool)
  - `CallbackIntegrationTest`: 14 tests (full lifecycle via mocked LLMs)

  **Documentation:**
  - `docs/guides/callbacks.md`: new guide covering quick start, event types, full interface impl,
    multiple listeners, exception safety, thread safety, practical examples
  - `docs/design/13-future-roadmap.md`: Phase 7 marked COMPLETE with implementation notes;
    old Phase 7 advanced features section renamed Phase 8
  - `mkdocs.yml`: Callbacks guide added to Guides nav section

## Next Steps

1. Open PR for `feature/57-callbacks-execution-context` -> main, get review + merge
2. Release v0.7.0 (callbacks milestone, via release-please)
3. Issue #42: Execution metrics -- ExecutionMetrics on EnsembleOutput (v0.7.x)
4. Issue #58 + #59 (v0.8.0): Guardrails + Rate Limiting
5. Issue #60 (v0.9.0): Built-in Tool Library (agentensemble-tools module)
6. Issue #61 (v1.0.0): Streaming Output

## Important Notes

- LangChain4j 1.11.0: EmbeddingModel.embed(String) returns Response<Embedding>
- EmbeddingStore.add(Embedding, TextSegment) -- store method with explicit embedding
- Metadata.from(key, value) -- static factory
- EmbeddingSearchRequest.builder().queryEmbedding(e).maxResults(n).minScore(0.0).build()
- Lombok @Builder.Default + custom build() causes static context errors -- always
  use field initializers in the inner builder class instead
- Custom builder methods: declare inner `public static class EnsembleBuilder` and add methods
  that delegate to Lombok-generated @Singular methods. Lombok fills in the rest.

## Active Decisions

- **ExecutionContext threading**: created once per Ensemble.run(), threaded through entire
  execution stack (WorkflowExecutor -> AgentExecutor -> DelegationContext -> tools)
- **Fire method exception safety**: catch per-listener, log WARN, continue to next listener
- **DelegationContext.getMemoryContext()/isVerbose() removed**: callers use
  `delegationContext.getExecutionContext().memoryContext()` and `.isVerbose()`
- **HierarchicalWorkflowExecutor manager context**: disabled memory + same listeners
  (manager is meta-orchestrator, shouldn't record to shared memory)
- **Parallel taskIndex**: 0 (meaningless for parallel -- order not guaranteed)
- **ToolResolver visibility**: package-private (implementation detail of agent package)
- **ParseResult visibility**: public (accessed from AgentExecutor in different package)
- **Schema generation**: prompt-based JSON schema (not LangChain4j ResponseFormat.JSON)
- **Parallel execution**: `Executors.newVirtualThreadPerTaskExecutor()` (stable Java 21)

## Important Patterns and Preferences

- TDD: Write tests first, then implementation
- Feature branches per GitHub issue
- No git commit --amend (linear history)
- No emoji/unicode in code or developer docs
- Production-grade quality, not prototype
- Lombok @Builder + custom builder class: use field initializers, NOT @Builder.Default
- ExecutionContext.disabled() is the backward-compat factory for tests and internal uses
