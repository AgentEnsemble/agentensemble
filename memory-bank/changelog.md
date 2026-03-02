# Changelog

## [0.1.0-SNAPSHOT] - 2026-03-02

### Added
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

## [Unreleased]

### Added
- Comprehensive design specification (13 documents in docs/design/)
  - 01-overview.md: Project overview, goals, non-goals
  - 02-architecture.md: Module structure, dependencies, design principles
  - 03-domain-model.md: Full API contracts for Agent, Task, Ensemble, outputs
  - 04-execution-engine.md: Execution flow, SequentialWorkflowExecutor, AgentExecutor
  - 05-prompt-templates.md: Exact system/user prompt templates
  - 06-tool-system.md: AgentTool interface, LangChain4j adapter, tool resolution
  - 07-template-resolver.md: Variable substitution system
  - 08-error-handling.md: Exception hierarchy and recovery strategies
  - 09-logging.md: SLF4J logging strategy, MDC, verbose mode
  - 10-concurrency.md: Thread safety model, Phase 2 considerations
  - 11-configuration.md: All configurable settings and extension points
  - 12-testing-strategy.md: Test plan, test cases, mocking approach
  - 13-future-roadmap.md: Phase 2-7 feature plans
- Memory bank documentation (projectbrief, productContext, systemPatterns, techContext, activeContext, progress, changelog)

### Decisions
- Project name: AgentEnsemble
- Build system: Gradle with Kotlin DSL
- Java 21 target
- MIT license
- Naming conventions established: Ensemble, Workflow, background, run() -- chosen for clarity and distinct identity
