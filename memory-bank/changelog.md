# Changelog

## [Unreleased]

### Changed
- Replaced manual `maven-publish` + `publishing {}` block in `agentensemble-core/build.gradle.kts`
  with `com.vanniktech.maven.publish` 0.29.0 (vanniktech plugin)
- `mavenPublishing {}` block targets `SonatypeHost.CENTRAL_PORTAL` with `signAllPublications()`
- Sources JAR and Javadoc JAR now generated automatically by the plugin (removed explicit `java { withSourcesJar(); withJavadocJar() }`)
- GitHub Packages repository retained as an additional publish target alongside Maven Central
- Release workflow updated: `publishAndReleaseToMavenCentral` task (auto-upload + auto-release)
  and `publishAllPublicationsToGitHubPackagesRepository` are now separate steps
- Release notes body simplified: no longer requires a custom Maven repository block since
  Maven Central is the default for both Gradle and Maven consumers
- Added `[plugins]` section to `gradle/libs.versions.toml` with `vanniktech-publish` entry

- Added `release-please-action@v4` (simple release type) in `.github/workflows/release-please.yml`
  - Watches main for Conventional Commits; opens/updates Release PRs with CHANGELOG entries
  - Merging a Release PR creates tag + GitHub Release (no manual `git tag` needed)
- Added `release-please-config.json` (simple type, root package)
- Added `.release-please-manifest.json` bootstrapped at `0.4.0`
- `release.yml` updated: removed `softprops/action-gh-release` (release-please owns the release);
  added `gh release upload` to attach JARs to the existing release; added post-release SNAPSHOT bump
  (patch-increments version in `gradle.properties`, commits `[skip ci]`, pushes to main)

### Required Secrets (add to GitHub repo settings before first Maven Central release)
- `ORG_GPG_SIGNING_KEY`: ASCII-armored GPG private key (`gpg --armor --export-secret-keys KEY_ID`)
- `ORG_GPG_SIGNING_PASSWORD`: GPG key passphrase
- `ORG_MAVEN_CENTRAL_USERNAME`: Sonatype Central Portal user token username
- `ORG_MAVEN_CENTRAL_PASSWORD`: Sonatype Central Portal user token password

---

## [0.5.0] - 2026-03-02 (Issue #18, PR #45)

### Added
- `Workflow.PARALLEL`: DAG-based concurrent task execution using Java 21 virtual threads
  (`Executors.newVirtualThreadPerTaskExecutor()` -- stable API, no preview flags)
- `TaskDependencyGraph`: identity-based DAG from task context declarations;
  `getRoots()`, `getReadyTasks(completed)`, `getDependents(task)`, `isInGraph(task)`,
  `getAllTasks()`, `size()`; immutable (all state built in constructor)
- `ParallelWorkflowExecutor`: event-driven scheduler; `CountDownLatch(totalTasks)` for
  synchronization; MDC propagated from calling thread to each virtual thread;
  `skippedTasks` Set tracks transitively-skipped tasks for CONTINUE_ON_ERROR correctness
- `ParallelErrorStrategy` enum: `FAIL_FAST` (default) and `CONTINUE_ON_ERROR`
- `ParallelExecutionException`: thrown by CONTINUE_ON_ERROR for partial failures;
  carries `completedTaskOutputs` (List) + `failedTaskCauses` (Map<String,Throwable>)
- `Ensemble.parallelErrorStrategy` field (default `FAIL_FAST`); validated at run()
- `Ensemble.validateParallelErrorStrategy()`: fails if null and workflow is PARALLEL
- `Ensemble.validateContextOrdering()`: skips for PARALLEL (DAG drives order)
- Task list order is irrelevant for PARALLEL (unlike SEQUENTIAL)
- 61 new tests (297->358): TaskDependencyGraphTest (21), ParallelWorkflowExecutorTest (+17),
  ParallelEnsembleIntegrationTest (16), ShortTermMemoryTest (+3), ExceptionHierarchyTest (+5)

### Fixed
- `Ensemble.resolveTasks()`: pass-2 now updates `originalToResolved` when creating a
  context-rewritten Task so downstream tasks receive the final reference (fixes diamond
  pattern A->B->D, A->C->D producing stale object references in D's context list)
- `ParallelWorkflowExecutor.shouldSkip()` CONTINUE_ON_ERROR: added `skippedTasks` Set;
  check now includes both `failedTaskCauses` AND `skippedTasks` so transitive dependents
  in chains (A fails -> B skipped -> C was incorrectly run, now skipped) are correctly
  handled
- `ParallelErrorStrategy.FAIL_FAST` Javadoc: corrected inaccurate "cancel/interrupt
  running tasks" text; actual behavior is running tasks finish normally, only new tasks
  are not scheduled

### Changed
- `ShortTermMemory`: backing list changed from `ArrayList` to `CopyOnWriteArrayList`
  for thread-safe concurrent writes from parallel tasks
- `ShortTermMemory.getEntries()`: returns `List.copyOf(entries)` (immutable snapshot)
  instead of `Collections.unmodifiableList(entries)` (live view)
- `MemoryContext` Javadoc: updated to reflect thread-safe status

### Documentation
- `docs/guides/workflows.md`: PARALLEL section (DAG explanation, error strategies,
  thread safety, task list order, diamond pattern, choosing a workflow table updated)
- `docs/reference/ensemble-configuration.md`: added `parallelErrorStrategy` row
- `docs/design/10-concurrency.md`: full concurrent execution model; replaced
  "Phase 2+ considerations" with actual implementation details and JMM guarantees
- `docs/design/13-future-roadmap.md`: Phase 5 marked complete with implementation notes
- `docs/examples/parallel-workflow.md`: new competitive intelligence example
  (market research + financial analysis in parallel -> SWOT -> executive summary)
- `README.md`: Parallel Workflow section; updated Ensemble Configuration table;
  roadmap updated (v0.5.0 struck through)

---

## [0.5.0-SNAPSHOT] - 2026-03-02 (PR #43, fix/copilot-review-feedback)

### Fixed (Copilot PR #43 review -- commit 3064533)
- `Ensemble.validateContextOrdering`: switched to identity-based membership sets
  (IdentityHashMap-backed executedSoFar + ensureTaskSet) to be consistent with
  resolveTasks() and validateAgentMembership(); prevents value-equal but identity-distinct
  context tasks from passing validation but failing template remapping
- `ToolResult.failure` Javadoc: updated from "must not be null" to "null is normalized to
  a default message" to accurately reflect the normalization already in place
- `MemoryContextTest.testRecord_withoutLongTerm_doesNotCallStore`: replaced vacuous
  verify(mock, never()) test (unwired mock; always passed regardless of behavior) with
  assertion-based test verifying observable state (hasLongTerm() false, STM recorded,
  queryLongTerm returns empty)
- `TaskOutput`: added Lombok @NonNull to raw, taskDescription, agentRole, completedAt, and
  duration to match design spec (docs/design/03-domain-model.md); updated three
  null-permitting tests to expect NullPointerException

### Fixed (Bug)
- `Ensemble.resolveTasks`: two-pass approach remaps context list references to resolved Task
  instances (fixes spurious TaskExecutionException when using template variables with context
  dependencies -- value equality of resolved vs original tasks diverges)

### Fixed (Null Safety)
- `AgentDelegationTool.delegate`: null/blank agentRole/taskDescription validated early
- `DelegateTaskTool`: null/blank param validation; null memoryContext normalized to disabled()
- `EmbeddingStoreLongTermMemory.store`: null content rejected; null timestamp defaulted to Instant.now()
- `AgentExecutor.execute`: null memoryContext normalized to MemoryContext.disabled()
- `AgentPromptBuilder`: null-guard ctx.getRaw() in context rendering
- `LangChain4jToolAdapter.convertToType`: primitive defaults for null value (prevents Method.invoke NPE)
- `Task.build`: null context elements throw ValidationException; self-referencing context detected
- `Agent.build`: null responseFormat normalized to empty string
- `ToolResult.failure`: null errorMessage normalized to default message
- `EnsembleMemory`: longTermMaxResults > 0 validated conditionally (only when longTerm != null)
- `Ensemble.validate`: managerMaxIterations > 0 validated for HIERARCHICAL workflow

### Fixed (Correctness)
- `AgentDelegationTool`: MDC save/restore for nested delegation chains (A->B->C)
- `HierarchicalWorkflowExecutor`: manager failure wrapped in TaskExecutionException with partial outputs
- `Ensemble`: reserved "Manager" role and duplicate roles validated for HIERARCHICAL
- `Ensemble.validateAgentMembership`: IdentityHashMap for identity-based agent lookup (per design spec)
- `Ensemble.validateContextOrdering`: distinguishes missing-task from ordering-violation messages
- `AgentExecutor` toolCallCounter: increments only on executed calls (not stop-message path)
- `Ensemble.run`: ValidationException logged at WARN; runtime failures at ERROR with throwable
- `MemoryContext.isActive`: returns true only when at least one memory type is genuinely active

### Changed (Code Quality)
- `AgentExecutor`: logs effective tool count (post-delegation-injection) instead of configured count
- `AgentExecutor`: tool errors logged at WARN (result starts with "Error:"); successes at INFO
- `AgentExecutor.ResolvedTools.execute`: removed unused originalTools parameter
- `DelegationContext` Javadoc: clarified thread-safety limitation (mutable referenced components)
- `LangChain4jToolAdapter`: throwable included in WARN log for tool execution exceptions
- `TemplateResolver`: UUID-embedded sentinel prefix; restore regex precompiled as static final
- `TaskExecutionException`: no-cause constructor delegates to with-cause constructor
- `AgentPromptBuilder`: stripTrailing() on system prompt; context block separator fixed (no double ---)

### Changed (Documentation)
- quickstart.md, installation.md, logging.md: logback version 1.5.12 -> 1.5.32
- template-variables.md: variable names support letters/digits/underscores only (no hyphens)
- workflows.md: context ordering validated at run(), not at build() time
- Task.java Javadoc: build-time vs run-time validation split clarified

### Added (Tests, 287 -> 297)
- EnsembleTest: 4 new validation tests (HIERARCHICAL reserved role, duplicate roles, managerMaxIterations=0, missing context task); renamed testRun_withMutualContextDependency to testRun_withForwardContextReference
- TaskTest: self-reference and null context element validation tests
- TaskOutputTest: null field behavior documentation tests and default toolCallCount test (updated to NPE assertions after @NonNull added)
- MemoryContextTest: replaced vacuous mock test with observable-state assertions
- AgentTest, AgentDelegationToolTest: assertion updates for changed messages

### Fixed (CI)
- ci.yml: !contains(needs.*.result, 'skipped') guard added to dependabot-automerge
- ci.yml: auto-merge branch protection requirement documented in script
- dependabot.yml: groups config added for github-actions ecosystem

---

## [0.4.0] - 2026-03-02

### Added
- Agent delegation (`net.agentensemble.delegation` package):
  - `DelegationContext`: immutable runtime state per delegation chain; `create()` factory
    (peerAgents, maxDepth, memoryContext, agentExecutor, verbose); `descend()` returns
    child with depth+1; `isAtLimit()` when currentDepth >= maxDepth
  - `AgentDelegationTool`: `@Tool`-annotated; auto-injected into agent tool list at
    execution time when `allowDelegation=true`; guards: depth limit, self-delegation,
    unknown role; accumulates `delegatedOutputs`; MDC keys `delegation.depth` and
    `delegation.parent` set during delegated executions
- `AgentExecutor.execute(Task, List, boolean, MemoryContext, DelegationContext)`:
  5-arg overload; `buildEffectiveTools()` prepends `AgentDelegationTool` when applicable;
  4-arg backward-compat overload passes null delegationContext
- `Ensemble.maxDelegationDepth` field (default 3; validated > 0 at run time)
- `SequentialWorkflowExecutor(List<Agent>, int maxDelegationDepth)`: 2-arg constructor;
  creates root `DelegationContext` per run; passes to AgentExecutor
- `HierarchicalWorkflowExecutor(ChatModel, List<Agent>, int managerMaxIterations, int maxDelegationDepth)`:
  4-arg constructor; creates `workerDelegationContext` for worker peer delegation;
  passes to `DelegateTaskTool`
- `DelegateTaskTool(List<Agent>, AgentExecutor, boolean, MemoryContext, DelegationContext)`:
  5-arg constructor; threads `delegationContext` to worker `AgentExecutor.execute()` calls
- 36 new tests: `DelegationContextTest` (16), `AgentDelegationToolTest` (14),
  `DelegationEnsembleIntegrationTest` (10); updated `DelegateTaskToolTest` and
  `HierarchicalWorkflowExecutorTest` for new constructors
- 287 total tests passing
- Comprehensive user documentation: 21 new files in `docs/`:
  - `docs/index.md`
  - `docs/getting-started/`: installation, quickstart, concepts
  - `docs/guides/`: agents, tasks, workflows, tools, memory, delegation,
    error-handling, logging, template-variables
  - `docs/reference/`: agent-configuration, task-configuration, ensemble-configuration,
    memory-configuration, exceptions
  - `docs/examples/`: research-writer, hierarchical-team, memory-across-runs
- README updated: Agent Delegation section, updated Agent/Ensemble config tables with
  allowDelegation and maxDelegationDepth, docs index section, v0.4.0 marked complete

### Technical Notes
- Delegation tool method name is `delegate` (not `delegateTask`) -- disambiguated from
  the Manager's `delegateTask` tool in hierarchical workflow
- `DelegationContext` is immutable (all-final fields, no setters) -- thread-safe
- The `SequentialWorkflowExecutor` no-arg constructor is removed; all callers
  now pass `agents` and `maxDelegationDepth`

---

## [0.3.0] - 2026-03-02

### Added
- Memory system (`net.agentensemble.memory` package) with three types:
  - **Short-term**: `ShortTermMemory` accumulates all task outputs per run; injected into subsequent agent prompts automatically (replaces explicit context section when enabled)
  - **Long-term**: `LongTermMemory` interface + `EmbeddingStoreLongTermMemory` implementation; uses LangChain4j `EmbeddingStore` + `EmbeddingModel`; embeds outputs on store, retrieves by semantic similarity before each task
  - **Entity**: `EntityMemory` interface + `InMemoryEntityMemory` (ConcurrentHashMap-backed); user-seeded key-value facts injected into every agent prompt
- `EnsembleMemory`: builder-pattern config object; requires at least one memory type enabled; `longTermMaxResults` (default 5)
- `MemoryContext`: runtime state holder per `Ensemble.run()` call; `disabled()` no-op singleton; `from(EnsembleMemory)` creates fresh STM per call
- `Ensemble.memory` field: optional `EnsembleMemory`; creates `MemoryContext` at start of `run()`
- `AgentPromptBuilder.buildUserPrompt(Task, List<TaskOutput>, MemoryContext)`: injects Short-Term Memory, Long-Term Memory, Entity Knowledge sections; backward-compat 2-arg overload retained
- `AgentExecutor.execute(Task, List<TaskOutput>, boolean, MemoryContext)`: injects memories before execution; records output after; backward-compat 3-arg overload retained
- `WorkflowExecutor` interface updated to `execute(List<Task>, boolean, MemoryContext)`
- `SequentialWorkflowExecutor`, `HierarchicalWorkflowExecutor`, `DelegateTaskTool` updated to accept and thread `MemoryContext`
- 77 new tests: `MemoryEntryTest` (5), `ShortTermMemoryTest` (8), `EmbeddingStoreLongTermMemoryTest` (9), `InMemoryEntityMemoryTest` (15), `EnsembleMemoryTest` (10), `MemoryContextTest` (22), `MemoryEnsembleIntegrationTest` (8)
- 251 total tests passing

### Technical Notes
- `EmbeddingModel.embed(String)` in LangChain4j 1.11.0 returns `Response<Embedding>` (Response wrapper NOT dropped unlike ChatModel)
- Manager agent in hierarchical workflow uses `MemoryContext.disabled()` (meta-orchestrator, not a worker)
- Entity memory is user-seeded; no automatic LLM-based entity extraction in this release

---

## [0.2.0] - 2026-03-02

### Changed
- Repackaged from `io.agentensemble` to `net.agentensemble` (agentensemble.net registered; .io was unavailable)
- Maven group coordinate updated: `net.agentensemble:agentensemble-core`
- All source directories, package declarations, imports, config files, and docs updated
- `validateContextOrdering()` in `Ensemble` skips for HIERARCHICAL workflow (manager handles ordering)

### Added
- Hierarchical workflow (`Workflow.HIERARCHICAL`): Manager agent delegates tasks to workers via `delegateTask` tool, synthesizes final result
- `DelegateTaskTool`: `@Tool`-annotated tool for worker delegation, case-insensitive role matching, collects `TaskOutput`s
- `ManagerPromptBuilder`: builds manager system prompt (worker list) and user prompt (task list)
- `HierarchicalWorkflowExecutor`: implements `WorkflowExecutor`, creates virtual Manager at runtime
- `Ensemble.managerLlm`: optional field, defaults to first agent's LLM
- `Ensemble.managerMaxIterations`: configurable manager iteration limit, default 20
- `-parameters` compiler flag in root `build.gradle.kts` for `@Tool` parameter name reflection
- 49 new tests: `DelegateTaskToolTest` (16), `ManagerPromptBuilderTest` (14), `HierarchicalWorkflowExecutorTest` (10), `HierarchicalEnsembleIntegrationTest` (9)
- 174 total tests passing

---

## [0.1.0] - 2026-03-02

### Added
- Release pipeline: tag-triggered GitHub Actions workflow publishes to GitHub Packages
- `maven-publish` plugin on `agentensemble-core` with full POM metadata (name, description, URL, MIT license, developer, SCM, issue management)
- Sources JAR and Javadoc JAR generated as part of every build
- GitHub Packages repository configured (`https://maven.pkg.github.com/AgentEnsemble/agentensemble`)
- Enhanced CI: `--continue` flag collects all test results on failure, `publish-unit-test-result-action` reports inline on PRs, `dependency-submission` job feeds GitHub dependency graph
- Phase 1 complete: full sequential multi-agent orchestration framework
- `Agent`: immutable value object with role, goal, background, tools, llm, verbose, maxIterations, responseFormat
- `Task`: immutable value object with description, expectedOutput, agent, context
- `Ensemble`: orchestrator with validation, template resolution, workflow execution
- `Workflow`: SEQUENTIAL (implemented), HIERARCHICAL (Phase 2)
- `EnsembleOutput`, `TaskOutput`: immutable result value objects
- `AgentExecutor`: ReAct-style tool-calling loop via LangChain4j 1.11.0 ChatModel API
- `SequentialWorkflowExecutor`: sequential task execution with MDC logging
- `AgentPromptBuilder`: system/user prompt construction
- `TemplateResolver`: {variable} substitution with escaped {{}} support
- `AgentTool` interface + `LangChain4jToolAdapter`: dual tool paths (AgentTool + @Tool)
- `ToolResult`: success/failure factory methods
- Full exception hierarchy (7 exception classes, all unchecked)
- SLF4J logging with MDC (ensemble.id, task.index, agent.role)
- 126 unit + integration tests, all passing
- `ResearchWriterExample`: two-agent research + writing workflow
- Comprehensive README with quickstart, API docs, tool guide, logging guide
- CI workflow (GitHub Actions) with Dependabot auto-merge
- 20 GitHub issues tracking Phase 1-5 roadmap

### Technical Notes
- Built on LangChain4j 1.11.0 (ChatModel, ChatRequest, ChatResponse API)
- Java 21, Gradle 9.3.1 with Kotlin DSL
- Version catalog (gradle/libs.versions.toml) for centralized dependency management

