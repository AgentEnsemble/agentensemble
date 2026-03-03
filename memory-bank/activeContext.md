# Active Context

## Current Work Focus

PR #43 merged to main (squash commit ecd7625). Maven Central publishing pipeline configured.
Development continues at 0.5.0-SNAPSHOT.

## Recent Changes

- Maven Central publishing fully operational -- v0.4.2 successfully released:
  - `com.vanniktech.maven.publish` 0.29.0 with `SonatypeHost.CENTRAL_PORTAL` + `signAllPublications()`
  - `publishAndReleaseToMavenCentral` handles full upload + auto-promotion lifecycle
  - `publishAllPublicationsToGitHubPackagesRepository` also gets signing env vars
  - Build step runs `:agentensemble-core:mavenPlainJavadocJar` to generate javadoc JAR
    (vanniktech generates `maven-javadoc-{v}-javadoc.jar`; upload step copies it to
    `agentensemble-core-{v}-javadoc.jar` before attaching to GitHub Release)
  - SNAPSHOT bump is version-aware: only bumps gradle.properties if the next patch
    version is higher than the current development SNAPSHOT
  - `release-please-action@v4` (simple type) requires `ORG_RELEASE_PLEASE_TOKEN` PAT
    secret to create/approve PRs (org-level GITHUB_TOKEN PR restriction is in effect)
  - `.release-please-manifest.json` is at `0.4.2` (last release)
  - Required secrets: `ORG_GPG_SIGNING_KEY`, `ORG_GPG_SIGNING_PASSWORD`,
    `ORG_MAVEN_CENTRAL_USERNAME`, `ORG_MAVEN_CENTRAL_PASSWORD`
  - Pending: `ORG_RELEASE_PLEASE_TOKEN` PAT (repo scope) for release-please PR creation

- PR #43 second commit (3064533): addressed 4 Copilot inline comments on PR #43
  - Ensemble.java: validateContextOrdering() now uses identity-based sets
    (IdentityHashMap-backed executedSoFar + ensureTaskSet) to be consistent with
    resolveTasks() and validateAgentMembership(); prevents value-equal but
    identity-distinct context tasks from passing validation but failing remapping
  - ToolResult.java: updated failure() Javadoc: "null is normalized to a default
    message" instead of "must not be null"
  - MemoryContextTest.java: replaced vacuous testRecord_withoutLongTerm_doesNotCallStore
    (unwired mock verify was always true) with assertion-based test verifying
    hasLongTerm() is false, STM is recorded, queryLongTerm returns empty
  - TaskOutput.java: added @NonNull to raw, taskDescription, agentRole, completedAt,
    duration fields to match the design spec in docs/design/03-domain-model.md
  - TaskOutputTest.java: updated three null-field tests to expect NullPointerException
    (enforced by Lombok @NonNull at build time)

- PR #43 first commit (44c482b): addressed all actionable Copilot review feedback
  from 16 closed PRs #21-#41
  - Bug: Ensemble.resolveTasks two-pass approach; IdentityHashMap for agent membership
  - Null safety: 13 defensive fixes across AgentDelegationTool, DelegateTaskTool,
    EmbeddingStoreLongTermMemory, AgentExecutor, AgentPromptBuilder, Task, Agent,
    ToolResult, LangChain4jToolAdapter, EnsembleMemory
  - Correctness: MDC save/restore for nested delegation; hierarchical error wrapping;
    HIERARCHICAL role validation; toolCallCounter fix; MemoryContext.isActive semantics
  - Code quality: effective tool count logging; WARN for tool errors; UUID sentinel in
    TemplateResolver; constructor delegation; prompt stripTrailing
  - Documentation: logback version sync, template-variables hyphens, workflows timing
  - Tests: +10 tests (287 -> 297); fixed dead test; renamed misleading test
  - CI: skipped-guard on automerge; dependabot groups for github-actions
  - 297 tests passing

## Next Steps

1. Issue #18: Parallel workflow (concurrent independent tasks, Java 21 virtual threads)
2. Issue #19: Structured output (outputType on Task, JSON parsing, retry loop)
3. Issue #20: Advanced features (callbacks, streaming, guardrails, built-in tools)

## Important Notes

- LangChain4j 1.11.0: EmbeddingModel.embed(String) returns Response<Embedding>
  (NOT dropped the Response wrapper unlike ChatModel.chat())
- EmbeddingStore.add(Embedding, TextSegment) -- store method with explicit embedding
- Metadata.from(key, value) -- static factory on dev.langchain4j.data.document.Metadata
- EmbeddingSearchRequest.builder().queryEmbedding(e).maxResults(n).minScore(0.0).build()
- EmbeddingSearchResult.matches() -> List<EmbeddingMatch<TextSegment>>
- EmbeddingMatch.embedded() -> TextSegment
- Lombok @Builder.Default + custom build() causes static context errors -- always
  use field initializers in the inner builder class instead

## Active Decisions

- **Memory architecture**: MemoryContext is created fresh per run() call; LTM and
  entity memory are shared across runs (user controls their lifecycle)
- **STM replaces explicit context**: when shortTerm=true, the "Short-Term Memory"
  section in the user prompt replaces (not duplicates) the explicit context section
- **Manager excluded from STM**: in hierarchical workflow, the manager agent itself
  uses MemoryContext.disabled() -- only worker delegations participate in memory
- **EntityMemory is user-seeded**: no automatic entity extraction; users populate
  facts manually before running the ensemble

## Important Patterns and Preferences

- TDD: Write tests first, then implementation
- Feature branches per GitHub issue
- No git commit --amend (linear history)
- No emoji/unicode in code or developer docs
- Production-grade quality, not prototype
- Lombok @Builder + custom builder class: use field initializers, NOT @Builder.Default
