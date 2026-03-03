# Changelog

## [Unreleased]

### Added (Code Quality Tooling)
- **Spotless** (`com.diffplug.spotless` 7.0.2): enforces consistent Java formatting using
  `palantir-java-format` 2.47.0 (4-space indent, matching existing style); `spotlessCheck`
  wired into `check` task so CI fails on violations; `spotlessApply` auto-formats
- **Error Prone** (`net.ltgt.errorprone` 4.2.0, `error_prone_core` 2.36.0): compile-time
  bug detection; surfaced 8 real issues (IdentityHashMapUsage x4, ReferenceEquality,
  UnusedVariable x2, JdkObsolete, FutureReturnValueIgnored x4) -- all fixed
- **JaCoCo**: coverage reporting (XML + HTML) and enforcement gate for `agentensemble-core`:
  LINE >= 90%, BRANCH >= 75% (current: 94.1% line, 81.4% branch); wired into `check`
- **Codecov**: coverage uploaded on every CI run via `codecov-action@v5`; `codecov.yml`
  configures auto threshold (project) and 80% target for new code (patch)
- **Pre-commit hook**: `.githooks/pre-commit` runs `spotlessApply` on staged Java/Kotlin
  files and re-stages any reformatted files so commits always contain formatted code;
  activated via `./gradlew setupGitHooks`

### Fixed (Error Prone findings)
- `Ensemble.java`: `Map<Task, Task>` -> `IdentityHashMap<Task, Task>` (IdentityHashMapUsage)
- `TaskDependencyGraph.java`: field types `Map<Task, List<Task>>` -> `IdentityHashMap<Task, List<Task>>` (IdentityHashMapUsage x2)
- `ParallelWorkflowExecutor.java`: `Map<Task, AtomicInteger>` -> `IdentityHashMap<Task, AtomicInteger>` (IdentityHashMapUsage); `LinkedList<>` -> `ArrayList<>` (JdkObsolete); `executor.submit` -> `var unused = executor.submit` (FutureReturnValueIgnored)
- `AgentExecutor.java`: removed unused `boolean verbose` parameter from `executeWithTools` method signature (UnusedVariable)
- `Task.java`: added `@SuppressWarnings("ReferenceEquality")` to `validateContext` -- identity comparison is intentional (two Agent objects with identical fields are distinct agents)
- `ShortTermMemoryTest.java`: three `executor.submit` -> `var unused = executor.submit` (FutureReturnValueIgnored)
- `JsonSchemaGeneratorTest.java`: removed unused local variables `trailingCommaPos` and `activePos` (UnusedVariable)

---


### Added (GitHub Pages documentation site)
- `mkdocs.yml`: MkDocs Material site configuration; nav mirrors `docs/index.md` structure;
  brand logo (`assets/logo.svg`), favicon (`assets/favicon.svg`), light/dark toggle,
  custom color scheme, code copy buttons, search
- `docs/stylesheets/custom.css`: brand color overrides for both light and dark schemes;
  header/tabs use the brand gradient (#2DD4FF -> #4D95FF -> #8363F9)
- `docs/assets/logo.svg`: copy of `assets/brand/agentensemble-logo-mark.svg`
- `docs/assets/favicon.svg`: copy of `assets/brand/agentensemble-favicon-32.svg`
- `docs/requirements.txt`: `mkdocs-material` dependency for CI install
- `.github/workflows/docs.yml`: GitHub Actions workflow; triggers on push to main when
  `docs/**` or `mkdocs.yml` change, plus manual dispatch; deploys to GitHub Pages via
  `actions/deploy-pages@v4` (source: GitHub Actions, not branch)
- `.gitignore`: added `site/` (MkDocs build output)
- `docs/design/01-overview.md`: fixed broken `../../LICENSE` relative link -- changed to
  absolute GitHub URL so it resolves correctly from the published site

### One-time manual step required
- Enable GitHub Pages in repo Settings > Pages > Source: "GitHub Actions"
- Site will be live at: https://agentensemble.github.io/agentensemble/


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

## [0.7.0] - 2026-03-03 (Issue #57, feature/57-callbacks-execution-context)

### Added
- `net.agentensemble.execution.ExecutionContext`: immutable value bundling `MemoryContext`,
  `verbose`, and `List<EnsembleListener>`; factory methods `of(mc, verbose, listeners)`,
  `of(mc, verbose)`, `disabled()`; fire methods catch per-listener exceptions at WARN
- `net.agentensemble.callback` package: `EnsembleListener` interface (4 default no-op methods),
  `TaskStartEvent`, `TaskCompleteEvent`, `TaskFailedEvent`, `ToolCallEvent` records
- `net.agentensemble.agent.ToolResolver`: package-private class extracted from `AgentExecutor`;
  resolves mixed `AgentTool` + `@Tool`-annotated object lists into `ResolvedTools`
- `Ensemble.listeners` field (`@Singular List<EnsembleListener>`); builder convenience
  methods `onTaskStart(Consumer)`, `onTaskComplete(Consumer)`, `onTaskFailed(Consumer)`,
  `onToolCall(Consumer)` -- each wraps lambda in anonymous `EnsembleListener`
- `docs/guides/callbacks.md`: new guide (quick start, event types, thread safety, examples)
- 59 new tests (440 -> 499): `ExecutionContextTest` (20), `EnsembleListenerTest` (10),
  `ToolResolverTest` (10), `CallbackIntegrationTest` (14), `EnsembleTest` (+7 listener builder)

### Changed
- `WorkflowExecutor.execute()`: `(List<Task>, boolean, MemoryContext)` -> `(List<Task>, ExecutionContext)`
- `AgentExecutor`: 3 overloads -> 2; fires `ToolCallEvent` after each tool execution in ReAct loop
- `DelegationContext`: replaced `memoryContext` + `verbose` with `ExecutionContext`;
  `create()`: `(peers, maxDepth, executionContext, executor)`; `getExecutionContext()` replaces old getters
- `DelegateTaskTool` constructor: `(agents, executor, executionContext, delegationContext)`
- `AgentDelegationTool.delegate()`: uses `delegationContext.getExecutionContext()` for execute call
- `SequentialWorkflowExecutor`, `ParallelWorkflowExecutor`, `HierarchicalWorkflowExecutor`:
  accept `ExecutionContext`; fire `TaskStartEvent`/`TaskCompleteEvent`/`TaskFailedEvent`
- `docs/design/13-future-roadmap.md`: Phase 7 marked COMPLETE; renamed old Phase 7 to Phase 8
- `mkdocs.yml`: Callbacks guide added to Guides nav

### Technical Notes
- `ExecutionContext.disabled()` is the backward-compat factory for tests and internal callsites
- `HierarchicalWorkflowExecutor` manager uses disabled memory + same listeners (meta-orchestrator)
- `ToolResolver` is package-private in `net.agentensemble.agent` (not part of public API)
- Parallel workflow `TaskStartEvent.taskIndex` is 0 (ordering not guaranteed in parallel)

---

## [Planned] Issue #20 Advanced Features -- Phase 7 Sub-Issues Created 2026-03-03

### Architecture Decisions Recorded

Issue #20 decomposed into 5 independently releasable sub-issues:

| Issue | Feature | Release |
|-------|---------|---------|
| #57 | Callbacks/Event Listeners + ExecutionContext refactor | v0.7.0 |
| #58 | Guardrails: Pre/post execution validation | v0.8.0 |
| #59 | Rate Limiting: Per-agent/per-LLM | v0.8.0 |
| #60 | Built-in Tool Library: agentensemble-tools module | v0.9.0 |
| #61 | Streaming Output: Token-by-token via StreamingChatLanguageModel | v1.0.0 |

Key architecture decisions:
- ExecutionContext (#57): replaces (MemoryContext, boolean verbose) params in
  WorkflowExecutor.execute() and AgentExecutor.execute() with a single context object;
  prerequisite for guardrails, streaming, and any future runtime extensibility
- Streaming (#61): decorator pattern -- Agent.streamingLlm optional field wraps a
  StreamingChatLanguageModel; tool-loop uses standard ChatModel; only final response is
  streamed via TokenEvent callbacks; preserves TaskOutput.raw contract
- Rate Limiting (#59): pure decorator -- RateLimitedChatModel wraps any ChatModel (zero
  changes to execution paths); token-bucket algorithm; thread-safe for parallel workflows;
  Agent.rateLimit() convenience auto-wraps at build time
- Built-in tools (#60): separate optional agentensemble-tools Gradle module; code
  execution (sandboxed) deferred due to security complexity
- Guardrails (#58): functional interfaces (InputGuardrail, OutputGuardrail) on Task;
  invoked in AgentExecutor before LLM call (input) and after response (output);
  throws GuardrailViolationException on failure

---

## [0.6.0] - 2026-03-03 (Issue #19, PR #48)

### Added
- `Task.outputType(Class<?>)`: optional field; when set, agent is instructed to produce
  JSON matching the schema derived from the class; output is automatically parsed after execution
- `Task.maxOutputRetries(int)`: number of retry attempts when structured output parsing fails;
  default 3; must be >= 0; 0 disables retries
- `TaskOutput.parsedOutput`: the parsed Java object (null when no outputType set)
- `TaskOutput.outputType`: the Class used for parsing (null when no outputType set)
- `TaskOutput.getParsedOutput(Class<T>)`: typed accessor; throws `IllegalStateException` when
  null or type mismatch
- `net.agentensemble.output.ParseResult<T>`: success/failure result container for parse attempts;
  public class; `success(T)`, `failure(String)`, `isSuccess()`, `getValue()`, `getErrorMessage()`
- `net.agentensemble.output.JsonSchemaGenerator`: reflection-based JSON-like schema description
  generator; `generate(Class<?>)`; supports records, POJOs, String, numeric wrappers, Boolean,
  List<T>, Map<K,V>, enums, nested objects; max nesting depth 5; scalar short-circuit via
  `topLevelScalarOrCollectionSchema()` (avoids introspecting JDK internals)
- `net.agentensemble.output.StructuredOutputParser`: JSON extraction (markdown fences first with
  non-greedy regex, then plain trimmed response, then first embedded block) and Jackson
  deserialization; scalar fallback in `parse()` for Boolean/Integer/String; `FAIL_ON_UNKNOWN_PROPERTIES=false`
- `net.agentensemble.exception.OutputParsingException`: extends `AgentEnsembleException`; thrown
  when all retries exhausted; carries `rawOutput` (last bad response), `outputType`, `parseErrors`
  (immutable list of per-attempt errors), `attemptCount`
- `AgentPromptBuilder`: `## Output Format` section injected into user prompt when outputType is set;
  prompt says "ONLY valid JSON matching this schema (object, array, or scalar as appropriate)"
- `AgentExecutor.parseStructuredOutput()`: retry loop after main execution; sends correction prompt
  to LLM on failure showing parse error and schema; throws `OutputParsingException` on exhaustion
- 82 new tests (358 -> 440): JsonSchemaGeneratorTest (23), StructuredOutputParserTest (20),
  ExceptionHierarchyTest (+5), TaskTest (+12), TaskOutputTest (+7), AgentPromptBuilderTest (+4),
  StructuredOutputIntegrationTest (11)
- `docs/examples/structured-output.md`: new two-example walkthrough (typed JSON + Markdown output)

### Changed
- `docs/guides/tasks.md`: Structured Output section (typed/Markdown examples, retry docs,
  supported types with scalar caveats)
- `docs/reference/task-configuration.md`: outputType/maxOutputRetries fields + validation table
- `docs/getting-started/concepts.md`: Task concept updated with outputType/maxOutputRetries
- `docs/design/03-domain-model.md`: Task and TaskOutput specs updated
- `docs/design/13-future-roadmap.md`: Phase 6 marked COMPLETE with implementation notes
- `README.md`: Structured Output section, Task Configuration table, roadmap updated

### Technical Notes
- Scalar support: Boolean/Integer/Long/Double respond with bare JSON values (e.g., `true`, `42`);
  String requires JSON-quoted output (e.g., `"text"`)
- JSON block extraction: non-greedy pattern finds first embedded block, not oversized span
- `OutputParsingException.rawOutput` carries the *last* bad response (currentResponse after retries),
  not the initial response -- enables effective debugging of retry chains

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

