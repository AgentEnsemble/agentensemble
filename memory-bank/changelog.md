# Changelog

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
