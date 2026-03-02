# Active Context

## Current Work Focus

Phase 1 is complete. Build/release pipeline is in place and ready to cut the first release.

## Recent Changes

- All Phase 1 implementation complete (Issues #1-#14, PRs #21-#36), 126 tests passing
- Example application: ResearchWriterExample (two-agent research + writing)
- Comprehensive README, 13 design docs, brand assets in assets/brand/
- Release pipeline added (commit 86c8e13):
  - `maven-publish` plugin on `agentensemble-core` with full POM metadata + sources/Javadoc JARs
  - GitHub Packages as Maven repository target
  - Release workflow (`.github/workflows/release.yml`): tag-triggered (`v*.*.*`), builds+tests, publishes, creates GitHub Release with auto-generated notes
  - Enhanced CI: `--continue` flag, test result reporting, dependency-submission job

## Next Steps

1. Update `gradle.properties` version to `0.1.0`, commit, tag `v0.1.0`, push -- triggers first release
2. Begin Phase 2: Hierarchical workflow (Issue #15)
3. Memory system (Issue #16)
4. Agent delegation (Issue #17)

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
