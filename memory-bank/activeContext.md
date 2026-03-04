# Active Context

## Current Work Focus

Feature branch `feature/60-built-in-tool-library` implemented Issue #60 (Built-in Tool Library:
agentensemble-tools module) for v1.0.0. PR to be opened against main. All tests pass.

## Recent Changes

### Issue #60 -- agentensemble-tools module (v1.0.0)

New Gradle module `agentensemble-tools` added with 7 built-in `AgentTool` implementations:

**New module:** `net.agentensemble:agentensemble-tools`
- `agentensemble-tools/build.gradle.kts`: `java-library` + `vanniktech-publish`; depends on
  `:agentensemble-core` via `api()`; adds Jsoup for WebScraperTool, Jackson for JsonParserTool/
  providers; JaCoCo coverage thresholds (LINE >= 90%, BRANCH >= 75%)
- `settings.gradle.kts`: added `include("agentensemble-tools")`
- `gradle/libs.versions.toml`: added `jsoup = "1.18.3"` version + `jsoup` library entry

**New classes in `net.agentensemble.tools`:**

| Class | Public API | Description |
|-------|-----------|-------------|
| `CalculatorTool` | `new CalculatorTool()` | Recursive-descent parser; +,-,*,/,%,^, parens, unary minus |
| `DateTimeTool` | `new DateTimeTool()` | now, today, date arithmetic, timezone conversion; package-private `Clock` constructor for testing |
| `FileReadTool` | `FileReadTool.of(Path)` | Sandboxed file reads; path traversal rejected via normalize()+startsWith() |
| `FileWriteTool` | `FileWriteTool.of(Path)` | Sandboxed file writes; JSON input {"path":...,"content":...}; creates parent dirs |
| `WebSearchProvider` | `@FunctionalInterface` | Provider interface for WebSearchTool; `search(String) throws IOException, InterruptedException` |
| `WebSearchTool` | `of(WebSearchProvider)`, `ofTavily(key)`, `ofSerpApi(key)` | Delegates to WebSearchProvider |
| `TavilySearchProvider` | package-private | Tavily API; injectable HttpClient constructor for testing |
| `SerpApiSearchProvider` | package-private | SerpAPI/Google; injectable HttpClient constructor for testing |
| `WebScraperTool` | `new WebScraperTool()`, `withMaxContentLength(int)` | HTTP GET + Jsoup text extraction; UrlFetcher injectable for testing |
| `UrlFetcher` | package-private `@FunctionalInterface` | HTTP fetching abstraction for WebScraperTool testing |
| `HttpUrlFetcher` | package-private | Real HttpClient implementation; injectable HttpClient constructor for testing |
| `JsonParserTool` | `new JsonParserTool()` | Dot-notation + array-index path extraction; input: "path\nJSON" format |

**Test files (`agentensemble-tools/src/test/java/net/agentensemble/tools/`):**
- `CalculatorToolTest` (31 tests): arithmetic, precedence, parens, unary minus, decimals, errors
- `DateTimeToolTest` (22 tests): now, today, timezone, date arithmetic, convert, failures
- `FileReadToolTest` (16 tests): reads, subdirs, traversal rejection, not-found, factory validation
- `FileWriteToolTest` (20 tests): writes, auto-create dirs, overwrite, traversal rejection, JSON parsing
- `WebSearchToolTest` (14 tests): provider delegation, trims, IO exception, null/blank, factories
- `TavilySearchProviderTest` (9 tests): parseResults(), search() with mock HttpClient
- `SerpApiSearchProviderTest` (9 tests): parseResults(), search() with mock HttpClient
- `WebScraperToolTest` (14 tests): HTML text extraction, truncation, mock UrlFetcher, failures
- `HttpUrlFetcherTest` (5 tests): 200 success, 404/500 errors, IO exception, default constructor
- `JsonParserToolTest` (19 tests): top-level, nested, array access, object/array serialization, errors
- `BuiltInToolsIntegrationTest` (6 tests): all tools implement AgentTool, multi-tool scenarios

**Documentation updated:**
- `docs/guides/built-in-tools.md`: new guide (dependency, one section per tool, combined usage)
- `docs/design/13-future-roadmap.md`: Phase 9 Built-in Tools marked COMPLETE
- `docs/getting-started/installation.md`: updated to v1.0.0 + agentensemble-tools optional dep
- `mkdocs.yml`: Built-in Tools added to Guides nav
- `README.md`: agentensemble-tools dep in quickstart, v1.0.0 roadmap entry struck through

## Next Steps

1. Open PR for `feature/60-built-in-tool-library` -> main, get review + merge
2. Release v1.0.0 (via release-please after PR merge)
3. Issue #59: Rate Limiting (v1.x) -- deferred from original v0.8.0 target
4. Issue #61: Streaming Output (future)

## Important Notes

- LangChain4j 1.11.0: EmbeddingModel.embed(String) returns Response<Embedding>
- EmbeddingStore.add(Embedding, TextSegment) -- store method with explicit embedding
- Metadata.from(key, value) -- static factory
- EmbeddingSearchRequest.builder().queryEmbedding(e).maxResults(n).minScore(0.0).build()
- Lombok @Builder.Default + custom build() causes static context errors -- always
  use field initializers in the inner builder class instead
- Custom builder methods: declare inner `public static class EnsembleBuilder` and add methods
  that delegate to Lombok-generated @Singular methods. Lombok fills in the rest.

## Active Decisions

- **agentensemble-tools is optional**: separate artifact, not bundled in core; users add it only
  if they want the built-in tools
- **WebSearchProvider is public API**: functional interface; users can create custom providers
- **UrlFetcher is package-private**: not part of public API; injectable only for testing
- **Provider HttpClient injection**: package-private constructors accept HttpClient for testing
  (avoids real HTTP in unit tests); public constructors use default HttpClient
- **FileRead/WriteToolSandboxing**: normalize() + startsWith(baseDir) -- catches all traversal
- **JsonParserTool input format**: first line = path, remaining lines = JSON (LLM-friendly)
- **DateTimeTool Clock injection**: package-private Clock constructor for deterministic tests
- **Coverage thresholds on tools module**: same as core (LINE >= 90%, BRANCH >= 75%)
- **ExecutionContext threading**: created once per Ensemble.run(), threaded through entire stack
- **ParseResult visibility**: public (accessed from AgentExecutor in different package)

## Important Patterns and Preferences

- TDD: Write tests first, then implementation
- Feature branches per GitHub issue
- No git commit --amend (linear history)
- No emoji/unicode in code or developer docs
- Production-grade quality, not prototype
- Lombok @Builder + custom builder class: use field initializers, NOT @Builder.Default
- ExecutionContext.disabled() is the backward-compat factory for tests and internal uses
- Package-private constructors for testability (Clock, HttpClient, UrlFetcher injection)
