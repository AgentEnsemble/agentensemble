# Active Context

## Current Work Focus

Phase 2 Issue #15 (Hierarchical Workflow) complete and merged to main (PR #38). Development at 0.2.0-SNAPSHOT.

## Recent Changes

- Repackaged (PR #39): `io.agentensemble` -> `net.agentensemble` (agentensemble.net registered)
  - 53 files updated: source trees, package declarations, imports, gradle.properties, docs, CI
  - Maven group: `net.agentensemble:agentensemble-core`
- Issue #15 merged (PR #38): hierarchical workflow fully implemented
  - `DelegateTaskTool`: @Tool-annotated tool the manager uses to invoke workers by role
  - `ManagerPromptBuilder`: builds manager system/user prompts from worker list + task list
  - `HierarchicalWorkflowExecutor`: manager agent runs via AgentExecutor, delegates to workers
  - `Ensemble` updated: `managerLlm`, `managerMaxIterations`, wired executor, relaxed context ordering
  - Build: added `-parameters` compiler flag for @Tool parameter name reflection
  - 174 tests passing (49 new), 0 failures
- v0.1.0 released: tag pushed, GitHub Packages, GitHub Release created

## Next Steps

1. Phase 2 Issue #16: Memory system (short-term, long-term, entity)
2. Phase 2 Issue #17: Agent delegation (allowDelegation flag, delegation depth limit)

## Important Notes

- LangChain4j 1.11.0 renamed ChatLanguageModel -> ChatModel
- LangChain4j 1.11.0 uses ChatModel.chat(ChatRequest) -> ChatResponse (not generate())
- Mockito cannot use argThat with ambiguous ChatModel.chat() overloads -- use any(ChatRequest.class)

## Active Decisions

- **Naming**: Core concepts are "Ensemble", "Workflow", "background", and "run()" -- chosen for clarity and distinct identity
- **Tool loop**: Using LangChain4j's `ChatLanguageModel.generate()` directly with manual tool dispatch rather than AiServices, for control over iteration limits
- **Gradle Kotlin DSL**: User preference over Maven
- **No SLF4J impl in core**: Users bring their own logging implementation

## Important Patterns and Preferences

- TDD: Write tests first, then implementation
- Feature branches per GitHub issue
- No git commit --amend (linear history)
- No emoji/unicode in code or developer docs
- Production-grade quality, not prototype

## Learnings and Project Insights

- The project is brand new (fresh repo with only README, LICENSE, .gitignore)
- Design docs are complete and serve as the implementation blueprint
- All 13 design documents are in docs/design/ and cover: overview, architecture, domain model, execution engine, prompt templates, tool system, template resolver, error handling, logging, concurrency, configuration, testing strategy, future roadmap
