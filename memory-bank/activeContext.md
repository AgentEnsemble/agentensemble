# Active Context

## Current Work Focus

PR #45 open on branch feature/parallel-workflow: Issue #18 Parallel Workflow implementation.
358 tests passing (was 297, +61). Copilot review addressed. Awaiting merge.

## Recent Changes

- Maven Central publishing fully operational -- v0.4.2 successfully released:
  - `com.vanniktech.maven.publish` 0.29.0 with `SonatypeHost.CENTRAL_PORTAL` + `signAllPublications()`
  - `publishAndReleaseToMavenCentral` handles full upload + auto-promotion lifecycle
  - `publishAllPublicationsToGitHubPackagesRepository` also gets signing env vars
  - Build step runs `:agentensemble-core:mavenPlainJavadocJar` to generate javadoc JAR
    (vanniktech generates `maven-javadoc-{v}-javadoc.jar`; upload step copies it to
    `agentensemble-core-{v}-javadoc.jar` before attaching to GitHub Release)
  - SNAPSHOT bump is version-aware: only bumps gradle.properties if the next patch
    version is higher than the current development SNAPSHOT
  - `release-please-action@v4` (simple type) requires `ORG_RELEASE_PLEASE_TOKEN` PAT
    secret to create/approve PRs (org-level GITHUB_TOKEN PR restriction is in effect)
  - `.release-please-manifest.json` is at `0.4.2` (last release)
  - Required secrets: `ORG_GPG_SIGNING_KEY`, `ORG_GPG_SIGNING_PASSWORD`,
    `ORG_MAVEN_CENTRAL_USERNAME`, `ORG_MAVEN_CENTRAL_PASSWORD`
  - Pending: `ORG_RELEASE_PLEASE_TOKEN` PAT (repo scope) for release-please PR creation

- Issue #18 (Parallel Workflow) implementation on feature/parallel-workflow:

  **New classes:**
  - `TaskDependencyGraph`: identity-based DAG from task context declarations;
    `getRoots()`, `getReadyTasks(completed)`, `getDependents(task)`, `isInGraph(task)`
  - `ParallelWorkflowExecutor`: `Executors.newVirtualThreadPerTaskExecutor()` (stable
    Java 21 API, no preview flags); `CountDownLatch(totalTasks)` for synchronization;
    MDC captured from caller thread and propagated into each virtual thread; task
    completion triggers `resolveDependent()` to evaluate and submit ready dependents;
    `skippedTasks` set tracks skipped tasks to correctly propagate transitive skips
    in CONTINUE_ON_ERROR chains
  - `ParallelErrorStrategy`: `FAIL_FAST` (default) or `CONTINUE_ON_ERROR`
  - `ParallelExecutionException`: thrown by CONTINUE_ON_ERROR; carries
    `completedTaskOutputs` + `failedTaskCauses` map

  **Changes to existing classes:**
  - `Workflow`: add `PARALLEL` enum value
  - `Ensemble`: `parallelErrorStrategy` field (default FAIL_FAST);
    `validateParallelErrorStrategy()`; `validateContextOrdering()` skips PARALLEL;
    `selectExecutor()` PARALLEL branch; `resolveTasks()` pass-2 now updates
    `originalToResolved` as final tasks are created (fixes diamond-pattern bug)
  - `ShortTermMemory`: `CopyOnWriteArrayList` for thread-safe concurrent writes;
    `getEntries()` returns immutable snapshot (`List.copyOf`)
  - `MemoryContext`: Javadoc updated to reflect thread-safe status

  **Bug fixes:**
  - `Ensemble.resolveTasks()` pass-2 was not updating `originalToResolved` after
    creating finalized context-rewritten tasks. Diamond-pattern dependencies
    (A -> B -> D, A -> C -> D) produced stale object references. Fixed by adding
    `originalToResolved.put(original, finalTask)` in pass-2.
  - `ParallelWorkflowExecutor.shouldSkip()` CONTINUE_ON_ERROR only checked
    `failedTaskCauses`; skipped tasks in a chain were not propagated. Fixed by
    adding `skippedTasks` Set and checking both sets in `shouldSkip()`.
  - `FAIL_FAST` Javadoc corrected: running tasks are allowed to finish; they are
    not cancelled/interrupted (only new tasks are not scheduled).

  **Tests:** 297 -> 358 (+61 new)
  - TaskDependencyGraphTest: 21 (roots, ready tasks, dependents, diamond, identity)
  - ParallelWorkflowExecutorTest: 17 (includes transitive skip test)
  - ParallelEnsembleIntegrationTest: 16 (E2E)
  - ShortTermMemoryTest: +3 (concurrent safety, snapshot semantics)
  - ExceptionHierarchyTest: +5 (ParallelExecutionException)

  **Documentation:**
  - docs/guides/workflows.md: PARALLEL section, error strategies, thread safety
  - docs/reference/ensemble-configuration.md: parallelErrorStrategy field
  - docs/design/10-concurrency.md: full implementation details
  - docs/design/13-future-roadmap.md: Phase 5 marked complete
  - docs/examples/parallel-workflow.md: new competitive intelligence example
  - README.md: Parallel Workflow section, updated tables and roadmap

- Issue #44 (backlog): Interactive execution graph visualization -- created
  - Depends on Issue #18 (TaskDependencyGraph) and Issue #42 (ExecutionMetrics)

## Next Steps

1. Merge PR #45 (parallel workflow), release v0.5.0
2. Issue #19: Structured output (outputType on Task, JSON parsing, retry loop)
3. Issue #42: Execution metrics (ExecutionMetrics on EnsembleOutput)
4. Issue #20 (v1.0.0): Advanced features (callbacks, streaming, guardrails, built-in tools)
5. Issue #44 (backlog): Execution graph visualization (depends on #18, #42)

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

- **Parallel execution**: `Executors.newVirtualThreadPerTaskExecutor()` (stable Java 21)
  NOT `StructuredTaskScope` (preview API, unstable across versions)
- **Error strategy default**: FAIL_FAST (mirrors SEQUENTIAL behavior)
- **ParallelExecutionException**: separate class from TaskExecutionException (clean
  separation of "one failure halted run" vs "some succeeded, some failed")
- **ShortTermMemory thread safety**: CopyOnWriteArrayList + snapshot semantics
  (getEntries() returns List.copyOf, not live view)
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
