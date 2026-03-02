# Changelog

## [Unreleased]

### Changed
- README updated to reflect hierarchical workflow (v0.2.0) and memory system (v0.3.0):
  - Added Memory to Core Concepts table
  - Updated dependency version to 0.3.0
  - Added Hierarchical Workflow section with full code example
  - Added Memory System section covering short-term, long-term, and entity memory with code examples
  - Added EnsembleMemory configuration table
  - Updated Ensemble Configuration table with managerLlm, managerMaxIterations, memory fields
  - Updated Task Configuration table noting context is for sequential workflow
  - Marked v0.2.0 and v0.3.0 as complete in roadmap

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

