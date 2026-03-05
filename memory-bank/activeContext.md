# Active Context

## Current Work Focus

Issue #89 (CaptureMode: transparent debug/capture mode for complete execution recording) has
been implemented on `feature/89-capture-mode`.

## Recent Changes

### Issue #89 -- CaptureMode

**New types:**

**`net.agentensemble.trace.CaptureMode`** (enum) -- Three capture levels:
- `OFF` (default): base trace behavior, zero overhead
- `STANDARD`: adds full LLM message history per ReAct iteration (`LlmInteraction.messages`)
  and wires memory operation counts via `MemoryOperationListener`
- `FULL`: adds auto-export to `./traces/` and enriched tool I/O (`ToolCallTrace.parsedInput`)

Resolution order: builder field > JVM system property `agentensemble.captureMode` > env var
`AGENTENSEMBLE_CAPTURE_MODE` > OFF. Zero code change required to activate from CLI.

**`net.agentensemble.trace.CapturedMessage`** (`@Value @Builder`) -- Serializable snapshot
of one LangChain4j `ChatMessage` (system/user/assistant/tool). Static factory `from(ChatMessage)`
and `fromAll(List<ChatMessage>)`.

**`net.agentensemble.memory.MemoryOperationListener`** (interface) -- Callback interface with
default no-op methods: `onStmWrite()`, `onLtmStore()`, `onLtmRetrieval(Duration)`,
`onEntityLookup(Duration)`. Wired into `MemoryContext` via `setOperationListener()`.

**Modified types:**

- `LlmInteraction`: added `@Singular List<CapturedMessage> messages` (empty at OFF)
- `ToolCallTrace`: added `Map<String, Object> parsedInput` (null at OFF/STANDARD, populated at FULL)
- `ExecutionTrace`: added `@NonNull @Builder.Default CaptureMode captureMode = OFF`;
  schema version bumped to `1.1`
- `ExecutionContext`: added `CaptureMode captureMode` field; new 7-param `of()` overload
- `TaskTraceAccumulator`: new 5-param constructor accepting `CaptureMode`; new
  `setCurrentMessages(List<CapturedMessage>)` method; `finalizeIteration()` includes
  message snapshot when STANDARD+
- `MemoryContext`: added `setOperationListener()` / `clearOperationListener()`; `record()`,
  `queryLongTerm()`, `getEntityFacts()` fire listener callbacks with timing
- `AgentExecutor`: passes captureMode to accumulator; snapshots messages at STANDARD+;
  parses tool arguments at FULL; wires memory listener at STANDARD+; clears listener
  in `finally` block
- `Ensemble`: added `@Builder.Default CaptureMode captureMode = OFF`; resolves effective
  mode via `CaptureMode.resolve()`; auto-registers `JsonTraceExporter(./traces/)` at FULL
  when no explicit exporter set; passes captureMode to `ExecutionContext` and `ExecutionTrace`

**Documentation:**

- `docs/guides/capture-mode.md` -- new guide
- `docs/examples/capture-mode.md` -- new example (Markdown)
- `agentensemble-examples/src/main/java/.../CaptureModeExample.java` -- runnable Java example
  (`./gradlew :agentensemble-examples:runCaptureMode`)
- Updated: `docs/design/02-architecture.md` (added trace/metrics/memory packages)
- Updated: `docs/design/04-execution-engine.md` (added trace accumulation + CaptureMode sections)
- Updated: `docs/design/09-logging.md` (added JsonTraceExporter + CaptureMode log entries)
- Updated: `docs/design/11-configuration.md` (added all missing Ensemble builder fields from #42/#89)
- Updated: `docs/design/13-future-roadmap.md` (added completed #42 and #89 sections)
- Updated: `docs/reference/ensemble-configuration.md` (added captureMode row)
- Updated: `README.md` (added CaptureMode section after Metrics)
- Updated: `mkdocs.yml` (added guide and example pages)

**Tests:**

- `CaptureModeTest` -- 15 unit tests covering enum, isAtLeast, resolve() chain
- `CapturedMessageTest` -- 10 unit tests covering all ChatMessage type conversions
- `TaskTraceAccumulatorCaptureModeTest` -- 8 unit tests for OFF/STANDARD/FULL behavior
- `AgentExecutorCaptureModeTest` -- 8 integration tests using mocked LLMs
- `ExecutionTraceTest` -- updated for schema version 1.1

All tests pass. Full build (`./gradlew build :agentensemble-core:javadoc --continue`) green.

## Next Steps

- PR review for `feature/89-capture-mode`
- Issue #44 (interactive execution graph visualization) can now use `CaptureMode.STANDARD`
  or `FULL` data for replay and visualization
