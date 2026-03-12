# Active Context

## Current Work

Branch: `feature/193-task-reflection`

Task reflection — self-optimizing prompt loop via persistent reflection store. After a
task executes and all reviews pass, an automated reflection step analyzes the task's
definition and output, identifies improvements to the instructions, and stores those
improvements in a pluggable `ReflectionStore`. On subsequent runs, the stored reflection
is injected into the prompt, creating a cross-run learning loop without modifying the
compile-time task definition.

## Completed This Session

### New Module: agentensemble-reflection

**SPI types:**
- `ReflectionStore` — SPI interface with `store(taskIdentity, reflection)` and
  `retrieve(taskIdentity)` methods. Implementations may use any backend.
- `TaskReflection` — immutable record with `refinedDescription`, `refinedExpectedOutput`,
  `observations`, `suggestions`, `reflectedAt`, `runCount`. Factory methods `ofFirstRun()`
  and `fromPrior()` manage run count accumulation.
- `InMemoryReflectionStore` — `ConcurrentHashMap`-backed default implementation.

**Tests (28):**
- `TaskReflectionTest` (16 tests): validation, immutability, factory methods, run count
- `InMemoryReflectionStoreTest` (12 tests): CRUD, validation, thread safety, clear

### Core additions (agentensemble-core)

**New types:**
- `ReflectionInput` — input bundle (task, taskOutput, priorReflection) for strategies.
- `ReflectionStrategy` — `@FunctionalInterface` SPI for custom analysis logic.
- `ReflectionConfig` — configuration value object: model override, custom strategy.
  `ReflectionConfig.DEFAULT` used by `.reflect(true)`.
- `LlmReflectionStrategy` — default implementation. Sends structured prompt, parses
  response into `TaskReflection`. Falls back gracefully on LLM failure or parse failure.
- `ReflectionPromptBuilder` — builds the meta-prompt: task definition + output +
  prior notes + structured analysis instructions.
- `TaskIdentity` — SHA-256 hash of task description, used as store key.
- `TaskReflector` — static utility that orchestrates the full reflection lifecycle:
  load prior → build input → resolve strategy → run reflection → store → fire event.
- `TaskReflectedEvent` — new callback event record.

**Modified types:**
- `Task` — new `reflectionConfig` field. Builder adds `.reflect(boolean)` and
  `.reflect(ReflectionConfig)` methods.
- `AgentPromptBuilder.buildUserPrompt` — new 5-arg overload with `TaskReflection priorReflection`.
  Injects `## Task Improvement Notes` section before `## Task` when reflection present.
- `AgentExecutor.execute` — loads prior reflection before prompt building; calls
  `TaskReflector.reflect()` after memory scopes stored.
- `DeterministicTaskExecutor.execute` — same reflection lifecycle.
- `EnsembleListener` — new `onTaskReflected(TaskReflectedEvent)` default method.
- `ExecutionContext` — new `reflectionStore` field + factory overload + `reflectionStore()`
  accessor + `fireTaskReflected()` dispatch method.
- `Ensemble` — new `reflectionStore` field + `Ensemble.reflectionStore(ReflectionStore)`
  builder method. Passes store to `ExecutionContext.of(...)`.

**Tests (core, 4 new test classes):**
- `AgentPromptBuilderReflectionTest` (9 tests): injection, ordering, backward compatibility
- `LlmReflectionStrategyParseTest` (10 tests): parsing, fallback, LLM round-trip
- `ReflectionConfigTest` (10 tests): builder, task builder integration
- `TaskReflectionIntegrationTest` (10 tests): store/retrieve, run count, events, fallback

### Build files
- `settings.gradle.kts` — added `include("agentensemble-reflection")`
- `agentensemble-reflection/build.gradle.kts` — new module build file
- `agentensemble-core/build.gradle.kts` — added `api(project(":agentensemble-reflection"))`
- `agentensemble-bom/build.gradle.kts` — added reflection to BOM constraints

### Documentation
- `docs/design/22-task-reflection.md` — architecture doc: problem, goals, lifecycle,
  prompt injection, SPI contracts, module structure, thread safety
- `docs/guides/task-reflection.md` — user guide with quick start, configuration,
  storage options, callbacks, default prompt
- `docs/examples/task-reflection.md` — code examples for 6 use cases
- `mkdocs.yml` — navigation entries added to Guides, Examples, and Design sections

## PR #194 Copilot Review Response (commit 0c281b6)

### Comment 2+3 — Reflection timing (most significant)
Reflection was running inside `AgentExecutor.execute()` and `DeterministicTaskExecutor.execute()`
BEFORE the after-execution review gate. This violated the documented contract of
"after all reviews pass on accepted output".

**Fix:** Removed `TaskReflector.reflect()` from both executors. Added to the workflow layer:
- `SequentialWorkflowExecutor.executeSeeded()` — after the after-review gate (including both
  normal Continue flow and ExitEarly path)
- `ParallelTaskCoordinator.submitTask()` — same; uses fully-qualified class names to avoid
  adding imports

### Comment 4 — Ephemeral store per reflection call
`TaskReflector.resolveStore()` was creating `new InMemoryReflectionStore()` on every
reflection call when no store was configured. This prevented prior-reflection retrieval
because each call got a fresh empty store.

**Fix:** `Ensemble.runWithInputs()` now provisions a single `InMemoryReflectionStore` at
run-start time when tasks have reflection enabled and no explicit store is configured.
`TaskReflector.resolveStore()` now returns `null` (skips reflection) when no store is in
context, rather than creating a throwaway store. Added `hasReflectionEnabled(List<Task>)` helper.

### Comments 1, 5, 6 — Doc and Javadoc corrections
- Design doc section 13: moved `ReflectionStrategy`, `ReflectionConfig`, `ReflectionInput`
  to `agentensemble-core` section (they were listed under `agentensemble-reflection`)
- `InMemoryReflectionStore` Javadoc: `.model(model)` → `.chatLanguageModel(model)`
- Design doc Ensemble integration example: same fix
- Design doc now also lists `SequentialWorkflowExecutor`, `ParallelTaskCoordinator` as
  integration points

### Comment 7 — slf4j dependency
Changed `agentensemble-reflection/build.gradle.kts` from `implementation(libs.slf4j.api)`
to `compileOnly(libs.slf4j.api)`. The SPI module sources don't use SLF4J directly; this
keeps the published artifact's dependency surface minimal.

### Test update
`TaskReflectionIntegrationTest.taskReflector_withNoStore_createsEphemeralFallback` renamed
to `taskReflector_withNoStore_skipsReflection` with assertions verifying nothing is stored
and no event is fired when no store is in context.

## Status
- Full build: PASSING (both modules, build `0c281b6`)
- All tests: PASSING
- Branch: `feature/193-task-reflection` pushed to origin
- PR #194 open; 7 Copilot comments addressed

## Key Design Decisions

### Cross-run vs intra-run
Reflection is CROSS-RUN (across separate `Ensemble.run()` calls). Phase review is
INTRA-RUN (retry within one run). Both can be used together.

### Post-acceptance only
Reflection runs AFTER all reviews pass. It is not a quality gate; it is a learning step.

### Static definition preserved
Reflection never mutates the `Task` object. The original description and expectedOutput
are always present in the prompt below the reflection notes.

### Pluggable storage
`ReflectionStore` SPI supports any backend: in-memory, RDBMS, SQLite, REST API. The
`InMemoryReflectionStore` default logs a WARN when used without explicit configuration.

### Non-fatal reflection
All reflection failures (LLM errors, parse failures) are caught and logged as WARN.
The task output is never affected.

### Task identity = SHA-256(description)
Stable across JVM restarts. Same description = shared reflection entry (intentional).

## Next Steps
- Merge PR #193
- Consider: SQLite-backed `ReflectionStore` implementation in examples or devtools module
