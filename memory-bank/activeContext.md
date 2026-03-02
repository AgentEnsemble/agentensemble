# Active Context

## Current Work Focus

Building AgentEnsemble Phase 1 from scratch. Currently in the documentation and project setup phase.

## Recent Changes

- Created comprehensive design specification (13 documents in docs/design/)
- Established naming conventions (Ensemble, Workflow, etc. -- avoiding competing framework names)
- Set up memory bank

## Next Steps

1. Create GitHub issues for the full development roadmap (#1-#20)
2. Set up Gradle project structure (Issue #1)
3. Implement exception hierarchy (Issue #3)
4. Implement domain model (Agent, Task, Ensemble) with TDD (Issues #4-#6)
5. Implement engine components (TemplateResolver, tools, prompts, executor, workflow) (Issues #7-#11)
6. Wire Ensemble.run() (Issue #12)
7. Example application and README (Issues #13-#14)

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
