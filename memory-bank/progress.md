# Progress

## What Works

- Design specification is complete (13 documents covering all aspects of Phase 1)
- Memory bank is established
- Repository is initialized with MIT license and Java .gitignore

## What's Left to Build

### Milestone 1: Foundation
- [x] Project scaffolding (Gradle build, settings, version catalog) -- PR #21 merged
- [x] CI workflow and Dependabot -- PR #22 merged
- [x] Exception hierarchy (7 exception classes) -- PR #29 merged

### Milestone 2: Domain Model
- [x] Agent domain object with builder and validation -- PR #30 merged
- [x] Task and TaskOutput with builders and validation -- PR #31 merged
- [x] Ensemble and EnsembleOutput (structure, run() stubbed) -- PR #32 merged

### Milestone 3: Engine
- [x] TemplateResolver (variable substitution) -- PR #33 merged
- [x] Tool system (AgentTool interface, ToolResult, LangChain4jToolAdapter) -- PR #34 merged
- [x] AgentPromptBuilder (system/user prompt construction) -- PR #34 merged
- [x] AgentExecutor (core agent execution with LangChain4j, tool loop) -- PR #35 merged
- [x] SequentialWorkflowExecutor (sequential task orchestration) -- PR #36 merged

### Milestone 4: Integration
- [x] Ensemble.run() implementation (wire everything together) -- PR #36 merged
- [x] Example application (ResearchWriterExample) -- committed
- [x] README and documentation -- committed

### GitHub Issues
- [x] Issues #1-#20 created (Issues #1-14 Phase 1, #15-20 Phase 2+)

## Release Pipeline

- [x] `maven-publish` on `agentensemble-core` with POM metadata, sources JAR, Javadoc JAR
- [x] GitHub Packages as Maven repository target
- [x] Maven Central publishing via `com.vanniktech.maven.publish` 0.29.0 (Central Portal)
- [x] GPG artifact signing configured (in-memory key via env vars)
- [x] release-please-action@v4 (simple type): auto-opens Release PRs from Conventional Commits, manages CHANGELOG.md
- [x] Release workflow: tag-triggered (`v*.*.*`), publishes to Maven Central + GitHub Packages, uploads JARs, auto-bumps SNAPSHOT
- [x] Enhanced CI: `--continue` flag, test result reporting, dependency-submission job

### Phase 2: Hierarchical Workflow
- [x] Issue #15: Hierarchical workflow (PR #38 merged) -- 174 tests

### Phase 3: Memory System
- [x] Issue #16: Memory system -- short-term, long-term, entity (PR #40 merged) -- 251 tests

### Phase 4: Agent Delegation
- [x] Issue #17: Agent delegation (PR #41 merged) -- 287 tests
  - DelegationContext, AgentDelegationTool, AgentExecutor 5-arg execute()
  - Ensemble.maxDelegationDepth, SequentialWorkflowExecutor 2-arg constructor
  - HierarchicalWorkflowExecutor 4-arg constructor, DelegateTaskTool 5-arg constructor
  - 36 new tests: DelegationContextTest (16), AgentDelegationToolTest (14),
    DelegationEnsembleIntegrationTest (10); updated 2 existing test classes

### Documentation
- [x] Comprehensive user docs (21 files in docs/getting-started/, guides/, reference/, examples/)

### Copilot Review Feedback
- [x] PR #43 (fix/copilot-review-feedback): address all Copilot feedback from PRs #21-#41 -- MERGED

### Phase 5: Parallel Workflow
- [x] Issue #18: Parallel workflow (PR #45 merged, squash commit 7535576) -- 358 tests (+61)
  - New: TaskDependencyGraph, ParallelWorkflowExecutor, ParallelErrorStrategy, ParallelExecutionException
  - Workflow.PARALLEL enum value; Ensemble.parallelErrorStrategy field (default FAIL_FAST)
  - ShortTermMemory thread-safe (CopyOnWriteArrayList + snapshot semantics)
  - Bug fix: resolveTasks() pass-2 updates originalToResolved (fixes diamond-pattern deps)
  - Bug fix: shouldSkip() tracks skippedTasks Set for transitive CONTINUE_ON_ERROR propagation
  - Documentation: workflows.md, ensemble-configuration.md, concurrency.md, roadmap, examples
  - Copilot review: 3 comments addressed (transitive skip bug, new test, FAIL_FAST Javadoc)

### Phase 6: Structured Output
- [x] Issue #19: Structured output (PR #48 merged, squash commit 1d69c5c) -- 440 tests (+82)

  **New classes:**
  - `net.agentensemble.output.ParseResult<T>`
  - `net.agentensemble.output.JsonSchemaGenerator`
  - `net.agentensemble.output.StructuredOutputParser`
  - `net.agentensemble.exception.OutputParsingException`

  **Modified classes:**
  - `Task`: `outputType`, `maxOutputRetries` fields with validation
  - `TaskOutput`: `parsedOutput`, `outputType` fields; `getParsedOutput(Class<T>)` typed accessor
  - `AgentPromptBuilder`: `## Output Format` section injection
  - `AgentExecutor`: `parseStructuredOutput()` retry loop

  **Tests:** +71 (JsonSchemaGeneratorTest, StructuredOutputParserTest, ExceptionHierarchyTest +5,
  TaskTest +12, TaskOutputTest +7, AgentPromptBuilderTest +4, StructuredOutputIntegrationTest 11)

  **Documentation:** tasks.md, task-configuration.md, concepts.md, structured-output.md (new),
  03-domain-model.md, 13-future-roadmap.md (Phase 6 marked COMPLETE), README.md

### Runnable Examples + Docs Site Links

- [x] `agentensemble-examples` module: 5 named `JavaExec` Gradle tasks under `"examples"` group:
  - `runResearchWriter` (default `run` task still points here too)
  - `runHierarchicalTeam` (HierarchicalTeamExample.java)
  - `runParallelWorkflow` (ParallelCompetitiveIntelligenceExample.java)
  - `runMemoryAcrossRuns` (MemoryAcrossRunsExample.java -- requires `langchain4j` artifact added
    to examples deps for InMemoryEmbeddingStore)
  - `runStructuredOutput` (StructuredOutputExample.java -- both typed JSON and Markdown parts)
- [x] `gradle/libs.versions.toml`: added `langchain4j` (main artifact) alongside `langchain4j-core`
- [x] All 5 doc example pages (`research-writer`, `hierarchical-team`, `parallel-workflow`,
  `memory-across-runs`, `structured-output`) have "Running the Example" sections with exact
  gradle commands and custom args
- [x] `mkdocs.yml`: `Structured Output` added to Examples nav (was missing from the merged branch)
- [x] `README.md`: every major section has a `**Full documentation:**` link to `docs.agentensemble.net`;
  "Running the Examples" section updated with all 5 named tasks; "Documentation" table links to
  hosted docs site

### Phase 7: Advanced Features (Issue #20 -- decomposed into sub-issues)

- [ ] Issue #57 (v0.7.0): Callbacks/Event Listeners + ExecutionContext refactor
  - ExecutionContext bundles MemoryContext + verbose + listeners
  - WorkflowExecutor.execute() refactored to accept ExecutionContext
  - EnsembleListener interface: onTaskStart, onTaskComplete, onToolCall
  - Event records: TaskStartEvent, TaskCompleteEvent, ToolCallEvent
- [ ] Issue #58 (v0.8.0): Guardrails: Pre/post execution validation
  - InputGuardrail / OutputGuardrail functional interfaces
  - GuardrailResult, GuardrailViolationException
  - Task.inputGuardrails / Task.outputGuardrails builder fields
  - AgentExecutor invokes guardrails before/after execution
- [ ] Issue #59 (v0.8.0): Rate Limiting: Per-agent/per-LLM
  - RateLimitedChatModel decorator (token-bucket, thread-safe)
  - RateLimit value object with factory methods
  - Agent.rateLimit() optional builder convenience
- [ ] Issue #60 (v0.9.0): Built-in Tool Library (agentensemble-tools module)
  - New Gradle module: agentensemble-tools
  - 7 tools: CalculatorTool, DateTimeTool, FileReadTool, FileWriteTool,
    WebSearchTool, WebScraperTool, JsonParserTool
  - Code execution deferred (security complexity)
- [ ] Issue #61 (v1.0.0): Streaming Output
  - Agent.streamingLlm optional field (StreamingChatLanguageModel)
  - TokenEvent added to EnsembleListener
  - Final response streamed; tool-loop remains non-streaming

## Current Status

**Phase**: Phase 6 complete -- Issue #19 done, PR #48 merged; examples now runnable; Issue #20 planned
**Total tests**: 440 passing on main
**Current version**: 0.6.0-SNAPSHOT (main)
**Last release**: v0.4.2 -- Maven Central + GitHub Packages (v0.6.0 pending release-please)
**Next action**: Release v0.6.0, then Issue #42 (Execution Metrics), then Issue #57 (v0.7.0)

## Known Issues

None yet (project has not started implementation).

## Evolution of Project Decisions

1. Initially considered Maven, switched to Gradle with Kotlin DSL per user preference
2. Chose "Ensemble" as the core orchestration concept for clarity and distinct identity
3. Chose "background" as the agent persona context field
4. Chose "run()" as the ensemble execution method
5. Chose "Workflow" as the execution strategy enum
