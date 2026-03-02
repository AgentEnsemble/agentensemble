# Active Context

## Current Work Focus

v0.1.0 released and published to GitHub Packages. Development continues on main at 0.2.0-SNAPSHOT.

## Recent Changes

- All Phase 1 implementation complete (Issues #1-#14, PRs #21-#36), 126 tests passing
- Release pipeline: `maven-publish`, GitHub Packages, tag-triggered release workflow, enhanced CI
- v0.1.0 released: tag pushed, GitHub Actions release workflow triggered (builds, publishes, creates GH Release)
- Version bumped to 0.2.0-SNAPSHOT on main

## Next Steps

1. Begin Phase 2: Hierarchical workflow (Issue #15)
2. Memory system (Issue #16)
3. Agent delegation (Issue #17)

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
