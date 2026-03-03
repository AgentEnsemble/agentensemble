# Active Context

## Current Work Focus

All doc examples are now runnable from the command line. 5 named Gradle tasks added to
`agentensemble-examples`. All doc example pages have run instructions. README links to
docs site throughout. 440 tests passing on main.

## Recent Changes

- Issue #19 (Structured Output) merged via PR #48 (squash commit 1d69c5c):

  **New classes:**
  - `net.agentensemble.output.ParseResult<T>`: success/failure result container for
    parse attempts; `success(T)` / `failure(String)` factory methods; public (accessed
    from `AgentExecutor` across packages).
  - `net.agentensemble.output.JsonSchemaGenerator`: reflection-based schema generator;
    `generate(Class<?>)` produces human-readable JSON-like schema for prompt injection;
    supports records, POJOs, String, numeric types, boolean, List<T>, Map<K,V>, enums,
    nested objects; max nesting depth 5; rejects primitives, void, top-level arrays;
    `topLevelScalarOrCollectionSchema()` handles scalars before field introspection.
  - `net.agentensemble.output.StructuredOutputParser`: JSON extraction and Jackson
    deserialization; `parse(String, Class<T>)` returns `ParseResult<T>`;
    `extractJson(String)` tries markdown fences first (non-greedy regex), then trimmed
    full response, then regex scan for first embedded JSON block; scalar fallback in
    `parse()` attempts direct Jackson parse when no object/array found.
  - `net.agentensemble.exception.OutputParsingException`: extends `AgentEnsembleException`;
    fields: `rawOutput` (last bad response), `outputType`, `parseErrors` (immutable list),
    `attemptCount`. Thrown after all retries exhausted.

  **Changes to existing classes:**
  - `Task`: added `Class<?> outputType` (default null) and `int maxOutputRetries` (default 3);
    builder validation: outputType cannot be primitive/void/array; maxOutputRetries >= 0.
  - `TaskOutput`: added `Object parsedOutput` (nullable) and `Class<?> outputType` (nullable);
    added `getParsedOutput(Class<T>)` typed accessor (throws IllegalStateException when null
    or type mismatch).
  - `AgentPromptBuilder`: injects `## Output Format` section with JSON schema and JSON-only
    instructions ("ONLY valid JSON matching this schema (object, array, or scalar as appropriate)")
    when `task.getOutputType() != null`.
  - `AgentExecutor`: after main execution (tool loop or direct), calls `parseStructuredOutput()`
    when `task.getOutputType() != null`; retry loop sends correction prompt to LLM with error
    message and schema; on exhaustion throws `OutputParsingException` with `currentResponse`
    (last bad response, not initial); correction prompt updated to say "valid JSON" not "JSON object".

  **Copilot review fixes (commit 71bf58c, squashed into 1d69c5c):**
  - `OutputParsingException.rawOutput`: uses `currentResponse` (last bad response)
  - `JSON_BLOCK_PATTERN`: non-greedy (`.*?`) to find first block not oversized span
  - `StructuredOutputParser.parse()`: scalar fallback for Boolean/Integer/String
  - `JsonSchemaGenerator.generate()`: `topLevelScalarOrCollectionSchema()` short-circuits
    before `generateObject()` for String.class, Boolean.class, etc.
  - Prompt wording: "valid JSON value" not "JSON object" throughout
  - Docs: tasks.md and task-configuration.md accurately describe scalar support

  **Tests:** 358 -> 440 (+82 new)
  - `JsonSchemaGeneratorTest`: 23 (records, POJOs, enums, nested, Maps, Lists,
    scalar top-level types, validation)
  - `StructuredOutputParserTest`: 20 (extractJson strategies, parse success/failure,
    scalar types, non-greedy multi-block)
  - `ExceptionHierarchyTest`: +5 (OutputParsingException hierarchy and fields)
  - `TaskTest`: +12 (outputType/maxOutputRetries defaults, validation, toBuilder)
  - `TaskOutputTest`: +7 (parsedOutput, outputType fields, getParsedOutput typed access)
  - `AgentPromptBuilderTest`: +4 (output format section present/absent, schema content)
  - `StructuredOutputIntegrationTest`: 11 (happy path, markdown fence, retry, zero retries,
    all-retries-exhausted, backward compat, mixed tasks, parallel workflow)

  **Documentation:**
  - `docs/guides/tasks.md`: Structured Output section with typed/Markdown examples and retry docs
  - `docs/reference/task-configuration.md`: outputType/maxOutputRetries fields + validation
  - `docs/getting-started/concepts.md`: outputType and maxOutputRetries in Task concept
  - `docs/examples/structured-output.md`: new; two full examples (typed JSON + Markdown output)
  - `docs/design/03-domain-model.md`: Task and TaskOutput specs updated
  - `docs/design/13-future-roadmap.md`: Phase 6 marked COMPLETE with implementation notes
  - `README.md`: Structured Output section, updated Task Configuration table, roadmap updated

## Next Steps

1. Release v0.6.0 (structured output milestone, via release-please)
2. Issue #42: Execution metrics -- ExecutionMetrics on EnsembleOutput
3. Issue #20 (v1.0.0): Advanced features (callbacks, streaming, guardrails, built-in tools)
4. Issue #44 (backlog): Execution graph visualization (depends on #18, #42)

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

- **ParseResult visibility**: public (accessed from AgentExecutor in different package)
- **Schema generation**: prompt-based JSON schema (not LangChain4j ResponseFormat.JSON)
  to work universally across all LLM providers without capability detection
- **JSON extraction priority**: markdown fences first (non-greedy, most LLMs wrap in
  ```json```), then full trimmed response, then regex scan for first embedded JSON block
- **Scalar fallback**: `parse()` attempts direct Jackson parse when extractJson returns null
- **rawOutput in OutputParsingException**: carries currentResponse (last bad response)
  not initialResponse (first response), to aid debugging of retry failures
- **Top-level array rejection**: JsonSchemaGenerator.generate() and Task.outputType
  both reject array types; workaround: wrap in record (e.g., record Results(List<T> items))
- **Parallel execution**: `Executors.newVirtualThreadPerTaskExecutor()` (stable Java 21)
  NOT `StructuredTaskScope` (preview API, unstable across versions)
- **Error strategy default**: FAIL_FAST (mirrors SEQUENTIAL behavior)
- **Memory architecture**: MemoryContext is created fresh per run() call; LTM and
  entity memory are shared across runs (user controls their lifecycle)

## Important Patterns and Preferences

- TDD: Write tests first, then implementation
- Feature branches per GitHub issue
- No git commit --amend (linear history)
- No emoji/unicode in code or developer docs
- Production-grade quality, not prototype
- Lombok @Builder + custom builder class: use field initializers, NOT @Builder.Default
