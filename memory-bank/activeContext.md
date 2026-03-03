# Active Context

## Current Work Focus

Feature branch `feature/structured-output` for Issue #19 (Structured Output, v0.6.0).
429 tests passing on feature branch. PR to be opened against main.

## Recent Changes

- Issue #19 (Structured Output) implemented on `feature/structured-output`:

  **New classes:**
  - `net.agentensemble.output.ParseResult<T>`: result container for parse attempts;
    `success(T)` / `failure(String)` factory methods; `isSuccess()`, `getValue()`,
    `getErrorMessage()`. Public (accessed from `AgentExecutor` across packages).
  - `net.agentensemble.output.JsonSchemaGenerator`: reflection-based schema generator;
    `generate(Class<?>)` produces human-readable JSON-like schema for prompt injection;
    supports records, POJOs, String, numeric types, boolean, List<T>, Map<K,V>, enums,
    nested objects; max nesting depth 5; rejects primitives, void, top-level arrays.
  - `net.agentensemble.output.StructuredOutputParser`: JSON extraction and Jackson
    deserialization; `parse(String, Class<T>)` returns `ParseResult<T>`; `extractJson(String)`
    tries markdown fences first, then trimmed full response, then regex scan for
    embedded JSON; `ObjectMapper` configured with FAIL_ON_UNKNOWN_PROPERTIES=false,
    FAIL_ON_NULL_FOR_PRIMITIVES=true.
  - `net.agentensemble.exception.OutputParsingException`: extends `AgentEnsembleException`;
    fields: `rawOutput`, `outputType`, `parseErrors` (immutable list), `attemptCount`.
    Thrown after all retries exhausted.

  **Changes to existing classes:**
  - `Task`: added `Class<?> outputType` (default null) and `int maxOutputRetries` (default 3);
    builder validation: outputType cannot be primitive/void/array; maxOutputRetries >= 0.
  - `TaskOutput`: added `Object parsedOutput` (nullable) and `Class<?> outputType` (nullable);
    added `getParsedOutput(Class<T>)` typed accessor (throws IllegalStateException when null
    or type mismatch).
  - `AgentPromptBuilder`: injects `## Output Format` section with JSON schema and JSON-only
    instructions when `task.getOutputType() != null`.
  - `AgentExecutor`: after main execution (tool loop or direct), calls `parseStructuredOutput()`
    when `task.getOutputType() != null`; retry loop sends correction prompt to LLM with error
    message and schema; on exhaustion throws `OutputParsingException`; passes `parsedOutput` and
    `outputType` to `TaskOutput.builder()`.

  **Tests:** 358 -> 429 (+71 new)
  - `JsonSchemaGeneratorTest`: 17 (records, POJOs, enums, nested, Maps, Lists, validation)
  - `StructuredOutputParserTest`: 15 (extractJson strategies, parse success/failure)
  - `ExceptionHierarchyTest`: +5 (OutputParsingException hierarchy and fields)
  - `TaskTest`: +12 (outputType/maxOutputRetries defaults, validation, toBuilder)
  - `TaskOutputTest`: +7 (parsedOutput, outputType fields, getParsedOutput typed access)
  - `AgentPromptBuilderTest`: +4 (output format section present/absent, schema content)
  - `StructuredOutputIntegrationTest`: 11 (happy path, markdown fence, retry, zero retries,
    all-retries-exhausted, backward compat, mixed tasks, parallel workflow)

  **Documentation:**
  - `docs/guides/tasks.md`: Structured Output section with typed/Markdown examples and retry docs
  - `docs/reference/task-configuration.md`: outputType and maxOutputRetries fields + validation
  - `docs/getting-started/concepts.md`: outputType and maxOutputRetries in Task concept
  - `docs/examples/structured-output.md`: new; two full examples (typed JSON + Markdown output)
  - `docs/design/03-domain-model.md`: Task and TaskOutput specs updated
  - `docs/design/13-future-roadmap.md`: Phase 6 marked COMPLETE with implementation notes
  - `README.md`: Structured Output section, updated Task Configuration table, roadmap updated

## Next Steps

1. Merge PR for Issue #19 to main (v0.6.0 release via release-please)
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
- **JSON extraction priority**: markdown fences first (most LLMs wrap in ```json```),
  then full trimmed response, then regex scan for embedded JSON
- **Retry loop design**: sends system prompt + correction user prompt (fresh context),
  not the full tool conversation history -- sufficient for parse correction
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
