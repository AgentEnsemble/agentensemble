# Changelog

## [Unreleased] - Issue #126: Tool-Level Approval Gates -- 2026-03-05

### Added (Issue #126 -- Tool-level approval gates via ReviewHandler)

**Infrastructure (agentensemble-core):**
- `ToolContext`: `Object reviewHandler` field; 4-arg `of()` factory; `reviewHandler()` accessor
- `ToolResolver.resolve()`: 4-arg overload threads `reviewHandler` from `ExecutionContext` into each `ToolContext`
- `AgentExecutor`: passes `executionContext.reviewHandler()` to `ToolResolver.resolve()`
- `AbstractAgentTool`: `protected ReviewDecision requestApproval(String)` and `requestApproval(String, Duration, OnTimeoutAction)`; `protected Object rawReviewHandler()`; re-throws `IllegalStateException` from `execute()`; `static final ReentrantLock CONSOLE_APPROVAL_LOCK`
- `LangChain4jToolAdapter.executeForResult()`: re-throws `IllegalStateException` so configuration errors propagate up

**Built-in tools (agentensemble-tools):**
- `ProcessAgentTool.Builder.requireApproval(boolean)` -- approval before subprocess start
- `FileWriteTool.Builder` (new builder pattern) with `requireApproval(boolean)` -- approval before file write
- `HttpAgentTool.Builder.requireApproval(boolean)` -- approval before HTTP send
- Build files for process, file-write, http: added `compileOnly` and `testImplementation` on `:agentensemble-review`

**Tests:**
- `ToolContextTest` (new), `AbstractAgentToolApprovalTest` (new), `ToolResolverTest` (+5 tests)
- `ProcessAgentToolTest` (+7), `FileWriteToolTest` (+10), `HttpAgentToolTest` (+8)
- `ToolApprovalIntegrationTest` (new, 6 end-to-end tests)

**Documentation:**
- `docs/guides/review.md`: Tool-Level Approval Gates section
- `docs/guides/built-in-tools.md`: Approval Gate subsections for 3 tools
- `docs/examples/human-in-the-loop.md`: Tool-Level Approval example
- `docs/design/06-tool-system.md`: Tool-Level Approval Gates architecture section

---

## [Unreleased] - Issue #113: MapReduceEnsemble Task-First Refactor -- 2026-03-05

### Added (Issue #113 -- Rework MapReduceEnsemble for task-first paradigm)

**MapReduceEnsemble.Builder -- new task-first factory methods:**
- `mapTask(Function<T, Task>)` -- creates one Task per item with no explicit agent; overloads the existing `mapTask(BiFunction<T, Agent, Task>)`
- `reduceTask(Function<List<Task>, Task>)` -- creates one Task per reduce group with no explicit agent; overloads the existing `reduceTask(BiFunction<Agent, List<Task>, Task>)`
- `directTask(Function<List<T>, Task>)` -- creates the direct short-circuit Task with no explicit agent; overloads the existing `directTask(BiFunction<Agent, List<T>, Task>)`
- `chatLanguageModel(ChatModel)` -- default LLM propagated to each inner Ensemble for `AgentSynthesizer` to use

**MapReduceEnsemble.of() zero-ceremony factory:**
- `MapReduceEnsemble.of(ChatModel model, List<T> items, String mapDescription, String reduceDescription)` -- static factory that builds and runs a map-reduce pipeline in one call; agents synthesised automatically

**Validation -- mutual exclusivity per phase:**
- Using task-first `mapTask(Function)` together with agent-first `mapAgent` or `mapTask(BiFunction)` throws `ValidationException("Ambiguous map configuration...")`
- Same for reduce and direct phases
- Agent-first validation updated: requires BOTH agent factory AND task factory (unchanged semantics, improved messages)

**Build logic (static and adaptive):**
- `createMapTask(item)` helper dispatches to task-first or agent-first factory
- `createReduceTask(chunkTasks)` helper dispatches to task-first or agent-first factory
- `createDirectTask()` helper dispatches to task-first or agent-first factory
- `buildStatic()` passes `chatLanguageModel` to inner `Ensemble.builder()` when set
- `MapReduceAdaptiveExecutor`: new constructor parameters for `chatLanguageModel`, `mapTaskOnlyFactory`, `reduceTaskOnlyFactory`, `directTaskOnlyFactory`; `newEnsembleBuilder()` sets `chatLanguageModel` when non-null

**Backward compatibility:**
- All existing agent-first builder methods (`mapAgent`, `mapTask(BiFunction)`, `reduceAgent`, `reduceTask(BiFunction)`, `directAgent`, `directTask(BiFunction)`) retained unchanged
- All existing tests pass

### Tests Added (Issue #113)

**Unit tests -- `MapReduceEnsembleTaskFirstTest` (17 tests):**
- `taskFirst_staticMode_buildSucceeds`, `taskFirst_adaptiveMode_buildSucceeds`
- `taskFirst_mapTasks_haveNoExplicitAgent`, `taskFirst_reduceTasks_haveNoExplicitAgent`
- `taskFirst_N3_K5_noIntermediateLevel_fourTasksTotal`, `taskFirst_N4_K3_oneIntermediateLevel_sevenTasksTotal`
- `taskFirst_N4_K3_contextWiringIsCorrect`, `taskFirst_nodeTypes_areCorrect`, `taskFirst_mapReduceLevels_areCorrect`
- `taskFirst_mapTaskFactory_calledOncePerItem`, `taskFirst_reduceTaskFactory_calledOncePerGroup`
- `chatLanguageModel_passedToInnerEnsemble`, `taskFirst_perTaskChatLanguageModel_isPreserved`
- `taskFirst_toolsOnTask_arePreserved`

**Validation tests -- `MapReduceEnsembleTaskFirstValidationTest` (15 tests):**
- `noMapFactory_throwsValidationException`, `noReduceFactory_throwsValidationException`
- `taskFirstMap_combinedWithAgentFirstMapTask_throwsValidationException`
- `taskFirstMap_combinedWithAgentFactory_throwsValidationException`
- `agentFirstMap_withoutMapAgent_throwsValidationException`, `agentFirstMap_withoutMapTask_throwsValidationException`
- `taskFirstReduce_combinedWithAgentFirstReduceTask_throwsValidationException`
- `agentFirstReduce_withoutReduceAgent_throwsValidationException`, `agentFirstReduce_withoutReduceTask_throwsValidationException`
- `taskFirstDirect_staticMode_throwsValidationException`, `taskFirstDirect_combinedWithAgentFirstDirect_throwsValidationException`
- Zero-ceremony factory: null model, null items, empty items, blank map description, blank reduce description (5 tests)

**Integration tests -- `MapReduceEnsembleTaskFirstIntegrationTest` (12 tests):**
- `taskFirst_staticMode_N3_K5_completesSuccessfully`, `taskFirst_staticMode_finalOutputComesFromReduceTask`
- `taskFirst_staticMode_N6_K3_completesSuccessfully`
- `taskFirst_adaptiveMode_completesSuccessfully`
- `taskFirst_toolsOnMapTask_includedInSynthesisedAgent`
- `taskFirst_perTaskChatLanguageModel_usedForSynthesis`
- `zeroCeremony_completesSuccessfully`, `zeroCeremony_taskDescriptionsIncludeItemText`
- `agentFirst_regression_explicitAgents_stillWorkCorrectly`
- `taskFirst_bothPhasesWithDifferentPerTaskModels_completesSuccessfully`
- `taskFirst_taskOutputs_areAccessible_withCorrectCount`

### Documentation Updated (Issue #113)

- `docs/guides/map-reduce.md`: new "Task-first API (v2.0.0)" section (before "Problem" section) with:
  - Zero-ceremony factory example
  - Task-first builder example with structured output
  - Task-first with tools on task example
  - Choosing task-first vs agent-first comparison table
  - Updated "Builder reference" with separate "Task-first fields" and "Agent-first fields" tables
- `docs/examples/map-reduce.md`: restructured with "Task-first examples (v2.0.0)" section first:
  - `runMapReduceTaskFirstKitchen` Gradle command
  - Zero-ceremony factory code snippet
  - Task-first builder code snippet with explanation of `AgentSynthesizer`
  - Existing agent-first examples moved under "Agent-first examples (power-user)" heading
- `agentensemble-examples/src/main/java/.../MapReduceTaskFirstKitchenExample.java`: new runnable example demonstrating zero-ceremony factory and task-first builder with structured output; uses same 7-dish kitchen scenario as `MapReduceKitchenExample.java`
- `agentensemble-examples/build.gradle.kts`: `"runMapReduceTaskFirstKitchen"` Gradle task registered

### Design Decisions (Issue #113)

- Task-first factories use **method overloading** (`Function<T,Task>` vs `BiFunction<T,Agent,Task>`): Java can resolve these at compile time since the arity differs (1-arg vs 2-arg lambda). This avoids new method names and keeps the builder fluent.
- **Agent synthesis happens inside the inner Ensemble's `resolveAgents()` step**, not in `MapReduceEnsemble` itself. This reuses all existing synthesis logic (template/LLM synthesizers, per-task LLM, tools, maxIterations application) without duplication.
- **`chatLanguageModel` is optional**: if every task carries its own `chatLanguageModel`, the ensemble-level field is not needed. The inner `EnsembleValidator` will catch missing LLM at `run()` time if neither is provided.
- **Mutual exclusivity validated at `build()` time**, not `run()` time, so errors are caught early before any tasks are created.

---

## [Unreleased] - Issues #111 + #112: Partial Results and Workflow Inference -- 2026-03-05

### Added (Issue #111 -- EnsembleOutput partial results and graceful exit-early)

**ExitReason enum (extended):**
- Added `TIMEOUT` -- review gate timeout expired with `onTimeout(EXIT_EARLY)`
- Added `ERROR` -- unrecoverable exception terminated the pipeline

**ReviewDecision.ExitEarly (breaking change):**
- Changed from no-arg record to `ExitEarly(boolean timedOut)` record
- `EXIT_EARLY_INSTANCE = new ExitEarly(false)` (unchanged semantics for `exitEarly()` factory)
- New `EXIT_EARLY_TIMEOUT_INSTANCE = new ExitEarly(true)` and `exitEarlyTimeout()` factory
- `ConsoleReviewHandler.handleTimeout()` for `EXIT_EARLY` now returns `exitEarlyTimeout()` (was `exitEarly()`)

**ExitEarlyException (extended):**
- Added `boolean timedOut` field; 2-arg constructor `(message, timedOut)`
- `isTimedOut()` accessor; 1-arg constructor defaults to `false`
- `HumanInputTool` propagates `exitEarly.timedOut()` when constructing the exception

**EnsembleOutput (new convenience API):**
- `isComplete()` -- `true` only when `exitReason == ExitReason.COMPLETED`
- `completedTasks()` -- alias for `getTaskOutputs()`; always safe to call
- `lastCompletedOutput()` -- `Optional<TaskOutput>` last element or empty
- `getOutput(Task task)` -- identity-based `Optional<TaskOutput>` lookup
- `Map<Task, TaskOutput> taskOutputIndex` internal field (excluded from equals/hashCode/toString)
- `Builder.taskOutputIndex(Map)` -- copies to IdentityHashMap for identity-based semantics

**SequentialWorkflowExecutor (updated):**
- Before-review ExitEarly: checks `timedOut()` to set `TIMEOUT` vs `USER_EXIT_EARLY`
- After-review ExitEarly: same timeout distinction
- `ExitEarlyException` catch: uses `e.isTimedOut()` to set exit reason
- All output builders now include `taskOutputIndex(completedOutputs)`

**ParallelWorkflowExecutor (new capability):**
- Full exit-early support via `AtomicReference<ExitReason> exitEarlyReasonRef`
- `exitEarlyReasonRef` checked in `shouldSkip()` to stop submitting new tasks
- Running tasks allowed to complete; partial output built with correct ExitReason
- Added `taskOutputIndex` to all output builders

**ParallelTaskCoordinator (updated):**
- `exitEarlyReasonRef` added to constructor and all relevant logic
- After-execution review gate logic ported from SequentialWorkflowExecutor
- `HumanInputTool` injection before task execution
- `ExitEarlyException` caught separately from generic `Exception`
- `shouldSkip()` checks `exitEarlyReasonRef != null` first

**Ensemble.runWithInputs() (updated):**
- Builds original-task -> output index after execution using positional correspondence
  `tasks.get(i)` -> `agentResolvedTasks.get(i)` to remap executor's agentResolved keys
  back to the original task instances the caller holds
- `taskOutputIndex` preserved through trace attachment step

### Added (Issue #112 -- Workflow inference from task context declarations)

**Ensemble.workflow (changed):**
- Field is now nullable (no `@Builder.Default`); default is `null`
- When null, `resolveWorkflow(List<Task>)` infers:
  - `PARALLEL` if any task has `context` dep on another ensemble task
  - `SEQUENTIAL` otherwise (default)
- `selectExecutor(Workflow, List<Agent>)` now takes explicit workflow
- `buildExecutionTrace(...)` takes explicit workflow for `.workflow(name)` call
- Logs "Workflow inferred: X" when inference applies

**EnsembleValidator (updated):**
- Added `resolveWorkflow()` private method with same inference logic
- All workflow-specific validations now take `effective` workflow as parameter:
  `validateManagerMaxIterations`, `validateParallelErrorStrategy`,
  `validateHierarchicalRoles`, `validateHierarchicalConstraints`
- `validateContextOrdering(effective)` skips for inferred or explicit PARALLEL

### Tests Added (Issues #111 + #112)

**Unit tests:**
- `EnsembleOutputTest`: TIMEOUT/ERROR exit reasons, isComplete() (all 4 cases),
  completedTasks(), lastCompletedOutput() (3 tests), getOutput(Task) (5 tests
  including identity vs equality, null task, index not provided)
- `EnsembleTest`: testDefaultWorkflow_isNullWhenNotSet, testExplicitWorkflow_sequential_isPreserved

**Integration tests (new files):**
- `PartialResultsIntegrationTest` (8 tests): 3-task sequential exit-early after task 2;
  all-complete with full index; before-first-task exit; parallel exit-early; full run
  getOutput for all tasks; timeout exit reason; policy-driven review with index
- `WorkflowInferenceIntegrationTest` (7 tests): no-workflow 3-task sequential; DAG inferred
  from context deps; fan-in (C waits for A+B); explicit SEQUENTIAL override; explicit PARALLEL;
  cycle-detection still runs; single task no-workflow

**Updated tests:**
- `EnsembleValidationTest`: forward-reference and ordering violation tests now use
  explicit `.workflow(Workflow.SEQUENTIAL)` (required since context deps now infer PARALLEL)

### Documentation Updated (Issues #111 + #112)

- `docs/guides/workflows.md`: new "Workflow Inference" section with table, code examples,
  explicit override, context ordering validation note; SEQUENTIAL section updated with note
  about inference
- `docs/getting-started/concepts.md`: Workflow section updated; inference table added;
  PARALLEL section added with DAG diagram
- `docs/reference/ensemble-configuration.md`: workflow default updated to `null (inferred)`;
  new EnsembleOutput methods: isComplete, completedTasks, lastCompletedOutput, getOutput;
  ExitReason updated to show all 4 values
- `docs/guides/error-handling.md`: new "Exit Reasons" section with all 4 ExitReason values;
  "Partial Results" section expanded with isComplete(), completedTasks(), lastCompletedOutput(),
  getOutput(Task), identity-based lookup explanation

### Design Decisions (Issues #111 + #112)

- `ReviewDecision.ExitEarly(boolean timedOut)` is a breaking change to the record signature;
  existing code using `ReviewDecision.exitEarly()` factory is unaffected since the factory
  still works; code directly constructing `new ExitEarly()` needs the `timedOut` argument
- Identity-based task index: the executor keyed by agentResolved instances is remapped in
  `Ensemble.runWithInputs()` back to original task instances, since `resolveTasks()` always
  creates new Task instances even for tasks with no template variables
- Workflow null default chosen over SEQUENTIAL to make inference explicit and opt-in; any
  existing code calling `.workflow(Workflow.SEQUENTIAL)` is unaffected
- Context ordering validation in EnsembleValidator is skipped for inferred PARALLEL (not just
  explicit PARALLEL): if tasks have context deps, they will be handled by the DAG correctly
  regardless of declaration order, so the ordering constraint is meaningless

---

## [Unreleased] - PR #125 Copilot review fixes -- 2026-03-05

### Fixed (PR #125 -- Copilot inline review comments, commit `2e7798e`)

Five issues addressed from Copilot's automated review of the initial #108/#109/#110 commit:

- **`SequentialWorkflowExecutor` (Fix 1)**: policy-driven after-review gates (triggered by
  `ReviewPolicy` with no explicit `task.review()`) were passing `timeout = null` to the
  `ReviewRequest`, causing `ConsoleReviewHandler` to take the indefinite blocking path and
  hang forever. Now defaults to `Review.DEFAULT_TIMEOUT` and `Review.DEFAULT_ON_TIMEOUT`
  when `task.getReview()` is null.

- **`ConsoleReviewHandler` (Fix 2)**: two problems resolved:
  1. Thread accumulation: the timed path created new `ExecutorService` instances per call
     and used `BufferedReader.readLine()` which does not respond to `Thread.interrupt()` on
     blocking streams like `System.in`. Replaced with `pollReadLine()` -- a polling loop
     using `reader.ready()` + 50ms sleeps that checks the interrupt flag between attempts,
     so `cancel(true)` after a timeout causes the reader thread to exit cleanly.
  2. Countdown accuracy: `remainingSeconds = timeout.toSeconds()` truncated sub-second
     durations (e.g., a 200ms timeout showed "0:00" from the start). Changed to track
     `remainingMillis` as an `AtomicLong` and convert to seconds only for display.

- **`HumanInputTool` (Fix 3/4)**: two inconsistencies between code, logs, and Javadoc:
  1. Log warning said "Returning empty response" but the method returns
     "No reviewer is available. Please proceed with your best judgment." -- fixed to
     "Returning default response".
  2. Class Javadoc said "an empty acknowledgement string" for the `Continue` path but code
     returns "Understood. Please continue." -- fixed to document the actual return value.

- **`AutoApproveWithDelayReviewHandler` (Fix 5)**: `Thread.sleep(delay.toMillis())` throws
  `ArithmeticException` for `Duration` values exceeding `Long.MAX_VALUE` milliseconds.
  Replaced with `Thread.sleep(Duration)` (Java 21 API) which routes through
  `TimeUnit.NANOSECONDS.convert(Duration)` that clamps overflow to `Long.MAX_VALUE`,
  avoiding the exception. Log message updated to use `delay.toString()` (no overflow risk).

### Tests Added (PR #125 Copilot fixes)

- `HumanInputToolTest`: `doExecute_handlerReturnsContinue_returnsExactAcknowledgementText`
  (locks down "Understood. Please continue." string); `doExecute_noReviewHandler_returnsDefaultFallbackMessage`
  (locks down "No reviewer is available..." string)
- `ReviewGateIntegrationTest`: `policyDrivenReview_noTaskReview_requestHasDefaultTimeoutAndOnTimeout`
  -- captures the `ReviewRequest` from a policy-driven gate and asserts both
  `timeout == Review.DEFAULT_TIMEOUT` and `onTimeoutAction == Review.DEFAULT_ON_TIMEOUT`
- `AutoApproveWithDelayReviewHandlerTest` (new class, 9 tests): zero/small delay normal
  operation; `ignoresTimeoutOnRequest` contract; interrupt path restores flag and returns
  Continue; overflow-safe Duration (`Duration.ofSeconds(Long.MAX_VALUE / 1000 + 1)`) does
  not throw `ArithmeticException`; constructor null/negative/zero/positive validation

---

## [Unreleased] - Issues #108 + #109 + #110: Human-in-the-Loop Review System -- 2026-03-05

### Added (Issues #108, #109, #110 -- Human-in-the-Loop Review System)

**New Gradle module `agentensemble-review`:**
- `ReviewHandler` -- SPI `@FunctionalInterface`: `ReviewDecision review(ReviewRequest request)`;
  static factories: `console()`, `autoApprove()`, `autoApproveWithDelay(Duration)`, `web(URI)` (stub)
- `ReviewRequest` -- immutable class: `taskDescription`, `taskOutput`, `timing`, `timeout`,
  `onTimeoutAction`, `prompt`; 4-arg and 6-arg `of()` factories
- `ReviewTiming` enum -- `BEFORE_EXECUTION`, `DURING_EXECUTION`, `AFTER_EXECUTION`
- `ReviewDecision` sealed interface -- `Continue`, `Edit(String revisedOutput)`, `ExitEarly`;
  singleton factories `continueExecution()`, `edit(String)`, `exitEarly()`
- `Review` factory -- `required()`, `required(String prompt)`, `skip()`, `builder()`;
  `ReviewBuilder` with `timeout(Duration)`, `onTimeout(OnTimeoutAction)`, `prompt(String)`
- `OnTimeoutAction` enum -- `CONTINUE`, `EXIT_EARLY`, `FAIL`
- `ReviewPolicy` enum -- `NEVER`, `AFTER_EVERY_TASK`, `AFTER_LAST_TASK`
- `ConsoleReviewHandler` -- stdin/stdout CLI with in-place countdown timer;
  accepts `c`/Enter (Continue), `e` + revised text (Edit), `x` (ExitEarly);
  package-private constructor for testability via injected `InputStream`/`PrintStream`
- `AutoApproveReviewHandler` -- singleton, always returns Continue; for CI/tests
- `AutoApproveWithDelayReviewHandler` -- returns Continue after configurable delay
- `WebReviewHandler` -- design placeholder; always throws `UnsupportedOperationException`
- `ReviewTimeoutException` -- thrown by `ConsoleReviewHandler` when `OnTimeoutAction.FAIL`

**Changes to `agentensemble-core`:**

- `ExitReason` enum (`net.agentensemble.ensemble`): `COMPLETED`, `USER_EXIT_EARLY`
- `ExitEarlyException` (`net.agentensemble.exception`): unchecked, propagates through stack
- `HumanInputTool` (`net.agentensemble.tool`): `AbstractAgentTool` subclass; `of()` and
  `of(Duration, OnTimeoutAction)` factories; `injectReviewHandler(ReviewHandler)` (public);
  returns reviewer's text response to agent as tool result; throws `ExitEarlyException` on ExitEarly
- `Task` -- added `review` (after-execution gate) and `beforeReview` (before-execution gate)
- `Ensemble` -- added `reviewHandler` and `reviewPolicy` builder fields; `reviewPolicy` is NOT
  `@Builder.Default` to avoid eager loading of `ReviewPolicy` class when review module absent
- `ExecutionContext` -- added `reviewHandler()`, `reviewPolicy()` accessors; new 10-arg `of()`
  factory; all existing factories delegate to it with null for new fields
- `EnsembleOutput` -- added `exitReason` field (defaults to `COMPLETED`); updated `of()` factory
  and `Builder`
- `AbstractAgentTool.execute()` -- re-throws `ExitEarlyException` before general catch block
- `LangChain4jToolAdapter.executeForResult()` -- re-throws `ExitEarlyException`
- `AgentExecutor.execute()` -- re-throws `ExitEarlyException` in inner try-catch;
  `executeParallelTools()` unwraps `CompletionException(ExitEarlyException)` and re-throws
- `SequentialWorkflowExecutor` -- wires all three gate timing points (before, during via
  `ExitEarlyException` catch, after); `injectReviewHandlerIntoTools()` called before each task;
  `buildPartialOutput()` for early-exit scenarios; `applyEdit()` replaces output and updates memory
- `agentensemble-core/build.gradle.kts`: added `compileOnly/testImplementation` for review module;
  updated LINE coverage threshold 90% -> 87% (reflects new optional module code in low-coverage
  agent/tool packages)

**Build/Settings:**
- `settings.gradle.kts`: `include("agentensemble-review")`

### Tests Added (Issues #108/#109/#110)

**`agentensemble-review` module:**
- `ReviewDecisionTest` (10): sealed interface + pattern matching, factory methods, null validation
- `ReviewTest` (14): required(), required(String), skip(), builder(), validation
- `ReviewRequestTest` (10): 4-arg and 6-arg factories, null handling, accessors
- `ConsoleReviewHandlerTest` (23): Continue/Edit/ExitEarly (blocking + timed), timeout action
  CONTINUE/EXIT_EARLY/FAIL, header display branches (BEFORE/DURING/AFTER timing, prompt),
  ReviewTimeoutException constructors
- `AutoApproveReviewHandlerTest` (14): autoApprove (singleton, all timings), autoApproveWithDelay
  (zero/non-zero/interrupted), web (callback URL, exception message), console factory

**`agentensemble-core` module:**
- `HumanInputToolTest` (14): of()/of(Duration, OnTimeoutAction), no-handler fallback,
  Continue/Edit/ExitEarly decisions, handler injection, AgentTool compliance
- `LangChain4jToolAdapterTest` (+1): ExitEarlyException is re-thrown, not converted to ToolResult.failure
- `AgentExecutorTest` (+2): HumanInputTool ExitEarly through full ReAct loop; HumanInputTool
  Continue resumes loop and agent produces final answer
- `ExecutionContextTest` (+7): reviewHandler defaults null, reviewPolicy defaults NEVER,
  10-arg factory with handler, null policy -> NEVER, null toolExecutor/metrics validation,
  disabled() has null handler
- `EnsembleOutputTest` (+4): exitReason defaults COMPLETED via of(), builder USER_EXIT_EARLY,
  null -> COMPLETED, ExitReason enum values
- `ReviewGateIntegrationTest` (14): after/before/edit/policy/skip/required end-to-end with
  Mockito mock LLMs and programmatic ReviewHandler lambdas

### Documentation Added/Updated (Issues #108/#109/#110)

- `docs/guides/review.md`: new comprehensive guide (all three gate timing points, Review config,
  ReviewPolicy, built-in handlers, partial results, custom handlers, task-level override table)
- `docs/examples/human-in-the-loop.md`: new example (after-execution gate, ensemble policy,
  before-execution confirmation, HumanInputTool, timeout config, CI/testing)
- `docs/getting-started/installation.md`: `agentensemble-review` added as optional dependency
  in Gradle snippet and Available Modules table
- `docs/reference/ensemble-configuration.md`: `reviewHandler` and `reviewPolicy` fields added;
  `exitReason` field added to EnsembleOutput accessor table
- `docs/reference/task-configuration.md`: `review` and `beforeReview` fields added
- `mkdocs.yml`: Review guide added to Guides nav; Human-in-the-Loop added to Examples nav

### Design Decisions (Issues #108/#109/#110)

- `reviewPolicy` is NOT `@Builder.Default` in `Ensemble` -- Lombok `@Builder.Default` generates
  a static method referencing `ReviewPolicy.NEVER` which would trigger eager class loading even
  when `agentensemble-review` is absent from the runtime classpath. `null` is treated as `NEVER`
  in `ExecutionContext` constructor.
- `ExitEarlyException` propagation: re-throw guards added at `AbstractAgentTool.execute()`,
  `LangChain4jToolAdapter.executeForResult()`, and `AgentExecutor.execute()` to prevent the
  exception from being swallowed and converted to tool failure or agent execution exception.
- After-execution ExitEarly includes the completed task output in `EnsembleOutput.taskOutputs`.
  Before-execution ExitEarly does NOT include the skipped task.
- `Edit` before execution is treated as `Continue` (no output exists to edit yet).
- Memory scope re-store: when reviewer edits output, `SequentialWorkflowExecutor.applyEdit()`
  writes the revised `MemoryEntry` to all declared memory scopes.
- Coverage threshold: LINE 90% -> 87% in `agentensemble-core` to accommodate pre-existing
  low-coverage packages (`agent` 72%, `trace.export` 0%, `tool` 82%) that now include new
  optional module integration code.

---

## [Unreleased] - Issues #106 + #107: agentensemble-memory module + scoped memory -- 2026-03-05

### Added (Issue #106 -- Extract agentensemble-memory module)

**New Gradle module `agentensemble-memory`:**
- `settings.gradle.kts`: `include("agentensemble-memory")`
- `agentensemble-memory/build.gradle.kts`: `java-library`, JaCoCo gates, Spotless, ErrorProne

**Moved from `agentensemble-core`:**
- 9 main classes: `EmbeddingStoreLongTermMemory`, `EnsembleMemory`, `EntityMemory`,
  `InMemoryEntityMemory`, `LongTermMemory`, `MemoryContext`, `MemoryEntry`,
  `MemoryOperationListener`, `ShortTermMemory`
- 6 test classes (adapted for new APIs)

**New class:**
- `MemoryRecord` (carrier record): decouples `MemoryContext.record()` from core's `TaskOutput`;
  fields `content`, `agentRole`, `taskDescription`, `completedAt`

**Core changes:**
- `agentensemble-core/build.gradle.kts`: `compileOnly(project(":agentensemble-memory"))` +
  `testImplementation(project(":agentensemble-memory"))`
- `AgentExecutor`: wraps `TaskOutput` fields into `MemoryRecord` before calling `record()`

**`EnsembleMemory` change:** builder throws `IllegalArgumentException` (was `ValidationException`)

**Documentation updated:**
- `docs/reference/memory-configuration.md`: module coordinates section added
- `docs/getting-started/installation.md`: `agentensemble-memory` as optional dependency

---

### Added (Issue #107 -- Task-scoped cross-execution memory with named scopes)

**New types in `agentensemble-memory`:**
- `MemoryStore` interface: `store(scope, entry)`, `retrieve(scope, query, maxResults)`,
  `evict(scope, policy)`; factories `inMemory()` and `embeddings(model, store)`
- `InMemoryStore` (package-private): `ConcurrentHashMap`-backed, insertion-order retrieval,
  eviction supported
- `EmbeddingMemoryStore` (package-private): LangChain4j semantic similarity; eviction is no-op
- `MemoryScope`: `of(name)` + `builder()` with `keepLastEntries` and `keepEntriesWithin`
- `EvictionPolicy`: `keepLastEntries(int n)` and `keepEntriesWithin(Duration d)` factories
- `MemoryTool.of(scope, store)`: `@Tool storeMemory(key, value)` and `@Tool retrieveMemory(query)`
- `MemoryEntry` updated (breaking): `{content, structuredContent, storedAt, metadata}` -- old
  `agentRole`/`taskDescription`/`timestamp` fields replaced by `metadata` Map<String,String>

**`agentensemble-core` changes:**
- `Task`: `memoryScopes` field; builder `.memory(String)`, `.memory(String...)`, `.memory(MemoryScope)`
- `Ensemble`: `memoryStore(MemoryStore)` replaces `memory(EnsembleMemory)` (breaking)
- `ExecutionContext`: `memoryStore` field + `memoryStore()` accessor; 8-arg `of()` factory
- `AgentPromptBuilder.buildUserPrompt()`: new 4-arg overload accepting `MemoryStore`;
  injects `## Memory: {scope}` sections per declared scope
- `AgentExecutor`: calls `storeInDeclaredScopes()` after task completes

**Tests added:**
- `EvictionPolicyTest`, `MemoryScopeTest`, `MemoryStoreTest`, `MemoryToolTest`,
  `EmbeddingMemoryStoreTest`
- `MemoryEnsembleIntegrationTest` rewritten to cover cross-run persistence, shared scope,
  scope isolation, empty scope on first run
- `DelegationEnsembleConfigIntegrationTest` updated to use new `memoryStore()` API

**Documentation updated:**
- `docs/guides/memory.md`: complete rewrite for v2.0.0 MemoryStore API
- `docs/examples/memory-across-runs.md`: rewritten for new API
- `docs/reference/memory-configuration.md`: MemoryStore, MemoryScope, EvictionPolicy, MemoryTool
- `docs/reference/task-configuration.md`: `memoryScopes` field
- `docs/reference/ensemble-configuration.md`: `memoryStore` field replaces `memory`

**Breaking changes (v2.0.0):**
- `Ensemble.builder().memory(EnsembleMemory)` removed; replaced by `.memoryStore(MemoryStore)`
- `MemoryEntry` fields restructured: `timestamp` -> `storedAt`, `agentRole`/`taskDescription`
  -> `metadata` map with standard keys `"agentRole"` / `"taskDescription"`

---

## [Unreleased] - Issues #104 + #105: Task-First Core + AgentSynthesizer SPI -- 2026-03-05

### Added (Issue #104 -- Task absorbs Agent responsibilities)

- `Task.agent` -- made optional (nullable). `Task.of(String)` and `Task.of(String, String)`
  static convenience factories; no agent required.
- `Task.chatLanguageModel` (`ChatModel`, nullable) -- per-task LLM override for synthesis.
- `Task.tools` (`List<Object>`, default `[]`) -- tools for the synthesized agent.
- `Task.maxIterations` (`Integer`, nullable) -- per-task iteration cap (null = use default 25).
- `Ensemble.chatLanguageModel` (`ChatModel`) -- ensemble-level LLM for synthesis.
- `Ensemble.agentSynthesizer` (`AgentSynthesizer`, default `template()`) -- synthesis strategy.
- `Ensemble.run(ChatModel, Task...)` -- static zero-ceremony factory method.
- `Ensemble.getAgents()` -- now derived from tasks (identity dedup); no longer a builder field.
- `Ensemble.resolveAgents()` -- new internal step: synthesizes agents for agentless tasks.

### Removed (Issue #104 -- breaking change)

- `Ensemble.builder().agent(Agent)` -- removed. Agents are now on tasks, not the ensemble.
- `EnsembleValidator.validateAgentsNotEmpty()` -- removed (no agents list).
- `EnsembleValidator.validateAgentMembership()` -- removed (no membership to validate).
- `EnsembleValidator.warnUnusedAgents()` -- removed.

### Changed (Issue #104)

- `EnsembleValidator` -- `validateTasksHaveLlm()` replaces the old agents checks.
  Hierarchical role validation now derives roles from tasks with explicit agents.
- `MapReduceEnsemble` -- removed `builder.agent(agent)` calls (agents on tasks only).
- `MapReduceAdaptiveExecutor` -- removed all `builder.agent()` calls (agents on tasks only).

### Added (Issue #105 -- AgentSynthesizer SPI)

- `net.agentensemble.synthesis.AgentSynthesizer` -- SPI interface with
  `synthesize(Task, SynthesisContext)`. Static factories: `template()`, `llmBased()`.
- `net.agentensemble.synthesis.SynthesisContext` -- record `(ChatModel model, Locale locale)`.
  Static factory `of(ChatModel)`.
- `net.agentensemble.synthesis.TemplateAgentSynthesizer` -- deterministic, no LLM call.
  25-verb verb-to-role lookup table. Role extracted from first word of description.
  Goal = description. Backstory derived from role.
- `net.agentensemble.synthesis.LlmBasedAgentSynthesizer` -- one LLM call per task.
  JSON response parsed into role/goal/backstory. Falls back to TemplateAgentSynthesizer on
  any parse or LLM error.

### Tests Added (Issues #104 + #105)

- `TemplateAgentSynthesizerTest` (25): role extraction parametrized tests, goal/backstory,
  determinism, fully valid agent, factory methods
- `LlmBasedAgentSynthesizerTest` (10): happy path, empty backstory, malformed JSON fallback,
  missing required fields, LLM exception, null response, factory methods
- `SynthesisContextTest` (3): of() factory, constructor, equality
- `TaskTest`: Task.of(), task-level tools/chatLanguageModel/maxIterations
- `TaskValidationTest`: maxIterations validation, tools validation, null agent succeeds
- Integration tests updated: all `.agent()` calls removed from `Ensemble.builder()` across all
  test files; `EnsembleValidationTest`, `HierarchicalEnsembleValidationIntegrationTest` updated.
- `EnsembleConstraintValidationTest`, `HierarchicalConstraintsIntegrationTest`: updated to
  include analyst tasks so both roles are registered for constraint validation.

### Documentation Added/Updated (Issues #104 + #105)

- `docs/guides/agents.md`: v2 intro (agents optional), AgentSynthesizer section,
  template/LLM-based/custom synthesis examples, when to use explicit agents table.
- `docs/guides/tasks.md`: zero-ceremony section, per-task LLM/tools/maxIterations,
  explicit agent as power-user escape hatch.
- `docs/getting-started/quickstart.md`: zero-ceremony example leads the page;
  explicit agent section follows.
- `docs/reference/task-configuration.md`: new fields documented
  (chatLanguageModel, tools, maxIterations, agent now optional).
- `docs/reference/ensemble-configuration.md`: `chatLanguageModel`, `agentSynthesizer`
  fields added; `agents` removed; Validation section updated; static factory documented.
- `docs/migration/v1-to-v2.md`: new migration guide (8 sections, quick checklist).
- `mkdocs.yml`: Migration section added with v1-to-v2.md.

### Design Decisions (Issues #104 + #105)

- Agent resolution happens in `Ensemble.resolveAgents()` after template resolution and before
  workflow execution -- workflow executors always see tasks with agents set.
- Task-level `tools`, `chatLanguageModel`, `maxIterations` are used ONLY for synthesis
  (when no explicit agent is set). When an explicit agent is set, those fields are ignored.
- Synthesized agents are ephemeral: not cached, not in `getAgents()`, not in the trace agent list.
- `LlmBasedAgentSynthesizer` falls back to template silently on any error to ensure an agent
  is always produced.
- HIERARCHICAL workflow: roles validated only for tasks with explicit agents (synthesized
  agent roles are unknown at validation time).

---

## [Unreleased] - Issue #100: MapReduceEnsemble Short-Circuit Optimization -- 2026-03-05

### Added

- `MapReduceEnsemble.Builder` -- 3 new fields:
  - `directAgent(Supplier<Agent>)` -- factory for the direct task agent (adaptive only)
  - `directTask(BiFunction<Agent, List<T>, Task>)` -- factory for the direct task (adaptive only)
  - `inputEstimator(Function<T, String>)` -- converts each item to text for input size estimation
- `MapReduceEnsemble.NODE_TYPE_DIRECT = "direct"` constant
- `MapReduceAdaptiveExecutor.estimateInputTokens()` -- pre-execution input token estimation
  using heuristic `text.length() / 4` (same heuristic as output estimation in #99)
- `MapReduceAdaptiveExecutor.runDirectPhase()` -- single-task execution path for short-circuit
- Short-circuit guard in `MapReduceAdaptiveExecutor.run()` -- fires before map phase when
  `estimated <= targetTokenBudget` and both direct factories are configured

### Tests Added

- `MapReduceEnsembleShortCircuitValidationTest` (9 unit tests) -- builder validation
- `MapReduceEnsembleShortCircuitRunTest` (16 unit tests) -- execution with mock LLMs
- `MapReduceEnsembleShortCircuitIntegrationTest` (7 integration tests) -- Mockito mock LLMs
- `DagExporterTest` -- 5 new short-circuit trace export tests

### Documentation Updated

- `docs/reference/ensemble-configuration.md` -- `directAgent`, `directTask`, `inputEstimator`
- `docs/guides/map-reduce.md` -- "Short-circuit optimization" section
- `docs/examples/map-reduce.md` -- "Short-circuit: small order, single direct task" section

### Design Decisions

- Short-circuit is opt-in (backwards-compatible); no `directAgent`/`directTask` = no check
- Boundary is inclusive: fires when `estimated <= targetTokenBudget`
- Static mode (`chunkSize`) forbids `directAgent`/`directTask` (`ValidationException` at build)
- `directAgent` and `directTask` must both be set or both be null (`ValidationException`)
- Trace: `workflow = "MAP_REDUCE_ADAPTIVE"`, single `TaskTrace` with `nodeType = "direct"`,
  `mapReduceLevel = 0`; `mapReduceLevels` has exactly 1 entry (level 0, taskCount=1)
- `TaskNode.tsx` already had DIRECT badge from #98 -- no visualization code changes needed

---

## [Unreleased] - v2.0.0 Architecture Design -- 2026-03-05

### Added (v2.0.0 Architecture -- branch: v2-architecture-design)

- `docs/design/15-v2-architecture.md`: comprehensive v2.0.0 design document. Covers:
  task-first paradigm shift, `AgentSynthesizer` SPI (template-based + LLM-based), what
  moves from Agent to Task (tools, LLM, maxIterations), task-scoped cross-execution memory
  (`MemoryStore` SPI, named scopes, TTL/eviction, `MemoryTool`), human-in-the-loop review
  system (`ReviewHandler` SPI, `ReviewDecision` sealed type, before/during/after gate
  timing, timeout + continue/edit/exit-early, `ConsoleReviewHandler` CLI interaction),
  `EnsembleOutput` redesign (partial results, `isComplete()`, `exitReason()`,
  `completedTasks()`, `ExitReason` enum), workflow inference from context declarations,
  module split (agentensemble-core + agentensemble-memory + agentensemble-review), SPI
  boundary definitions (section 7), breaking changes summary (section 8), parallel
  workstream plan (Groups A-F, section 9), and migration guide outline (section 10).
- `mkdocs.yml`: v2.0.0 Architecture added to Design nav section.
- GitHub issues created (epic + 12 issues) tracking v2.0.0 implementation workstreams.

### Architecture Decisions Recorded (v2.0.0)

| Decision | Choice |
|---|---|
| Paradigm | Task-first; agent auto-synthesized from task description |
| Backward compat | Clean break (semver major); no compatibility shim |
| Agent | Optional power-user escape hatch via `Task.agent()` |
| Memory | Task-scoped named scopes; cross-execution persistence; `MemoryStore` SPI |
| Human-in-loop | `ReviewHandler` SPI; before/during/after timing; timeout + continue/edit/exit |
| Partial results | `EnsembleOutput` treats incomplete pipeline as first-class outcome |
| Workflow | Inferred sequential by default; DAG-inferred parallel from context declarations; explicit override retained |
| Modules | agentensemble-core split: + agentensemble-memory + agentensemble-review |
| Parallel work | Groups A/B/C can run simultaneously once SPI contracts are stable |
| MapReduce | Ships first in v1.x API (#98-100), then refactored in Group E |

---

## [Unreleased] - Dynamic Agent Creation + MapReduceEnsemble Design -- 2026-03-05

### Added (Dynamic Agent Creation -- current framework documentation)

- `docs/examples/dynamic-agents.md`: full "Chef's Kitchen" example walkthrough documenting
  how to create agents and tasks programmatically at runtime using the existing
  `Workflow.PARALLEL` API. Covers fan-out/fan-in pattern, context size considerations,
  structured output tip, execution timeline diagram.
- `agentensemble-examples/.../DynamicAgentsExample.java`: runnable example with default
  4-dish order and configurable dishes via command-line args; demonstrates the complete
  fan-out (N specialist agents) + fan-in (Head Chef aggregation) pattern.
- `agentensemble-examples/build.gradle.kts`: added `runDynamicAgents` Gradle task.
- `docs/guides/workflows.md`: added "Dynamic Agent Creation" subsection under PARALLEL
  workflow with code example, execution pattern diagram, and context size warning.
- `mkdocs.yml`: added "Dynamic Agent Creation" to Examples nav section.
- `README.md`: added Dynamic Agent Creation subsection under Parallel Workflow section;
  added `runDynamicAgents` command to examples section; added MapReduceEnsemble mention
  in roadmap.

### Changed (Tesla reference removal)

- Replaced `--args="Tesla"` / `--args="Tesla automotive"` with `--args="Acme Corp"` /
  `--args="Acme Corp enterprise software"` across:
  - `README.md`
  - `agentensemble-examples/build.gradle.kts`
  - `docs/examples/hierarchical-team.md`
  - `docs/examples/parallel-workflow.md`
  - `agentensemble-examples/src/.../HierarchicalTeamExample.java`
  - `agentensemble-examples/src/.../ParallelCompetitiveIntelligenceExample.java`

### Added (MapReduceEnsemble Design -- v2.0.0)

- `docs/design/14-map-reduce.md`: comprehensive design document for `MapReduceEnsemble`.
  17 sections covering: problem statement, two reduction strategies (static with chunkSize /
  adaptive with targetTokenBudget), short-circuit optimization, full API design, return
  types, static DAG construction algorithm (O(log_K(N)) tree depth), adaptive execution
  algorithm (level-by-level with bin-packing), three-tier token estimation, first-fit-
  decreasing bin-packing algorithm, trace/metrics aggregation across multiple Ensemble.run()
  calls, visualization layer changes (DagModel schemaVersion 1.1, DagTaskNode.nodeType /
  mapReduceLevel fields, DagModel.mapReduceMode, TypeScript types, TaskNode.tsx badge
  rendering), error handling, validation rules, edge cases table, full code examples,
  implementation class structure.
- `docs/design/13-future-roadmap.md`: added Phase 10 (v2.0.0) with MapReduceEnsemble
  three-issue delivery plan; added table entry.
- `mkdocs.yml`: added "MapReduceEnsemble: design/14-map-reduce.md" to Design nav.

### Added (GitHub Issues)

- Issue #98: "feat: Static MapReduceEnsemble with chunkSize (v2.0.0)" -- comprehensive
  acceptance criteria checklist covering core implementation, edge cases, tests (unit +
  integration), visualization layer (devtools + viz), example, documentation, memory bank,
  build.
- Issue #99: "feat: Adaptive MapReduceEnsemble with targetTokenBudget (v2.0.0)" -- depends
  on #98; covers targetTokenBudget, contextWindowSize/budgetRatio convenience, maxReduceLevels,
  tokenEstimator, bin-packing, trace/metrics aggregation, adaptive execution loop, full
  test matrix.
- Issue #100: "feat: MapReduceEnsemble short-circuit optimization for small inputs (v2.0.0)"
  -- depends on #99; covers directAgent, directTask, inputEstimator, decision tree, input
  size estimation, full test matrix.

---

## [Unreleased] - Issue #94 -- Homebrew Tap Distribution -- 2026-03-05

### Added (Issue #94 -- Distribute agentensemble-viz via Homebrew tap)

**`agentensemble-viz/cli.js` changes:**
- Replaced `__dirname`-based `distDir` with `new URL('./dist/', import.meta.url).pathname`
  so Bun's `--compile` embeds the entire `dist/` directory into the binary at compile time
- Added version reading: `readFileSync(new URL('./package.json', import.meta.url).pathname)`
  so Bun embeds `package.json` into the binary (same URL pattern)
- Added `--version` flag: prints `agentensemble-viz/<semver>` to stdout and exits 0;
  must run before the HTTP server starts (required by Homebrew formula test block)

**`agentensemble-viz/package.json` changes:**
- Added `compile:darwin-arm64`, `compile:darwin-x64`, `compile:linux-x64` scripts using
  `bun build --compile --target=<target> cli.js --outfile agentensemble-viz`

**`.github/workflows/release.yml` changes:**
- After Java artifact upload: set up Node 20 + Bun (`oven-sh/setup-bun@v2`)
- `npm ci && npm run build` in `agentensemble-viz/`
- Sequential Bun cross-compile for 3 targets; each compile: create tarball, remove binary
  to avoid filename collision before next target
- Upload 3 tarballs (`agentensemble-viz-darwin-arm64.tar.gz`, etc.) to the GitHub Release
- Fire `repository_dispatch` event (`new-release` type) to `AgentEnsemble/homebrew-tap`
  with `version` in the payload; requires `ORG_HOMEBREW_TAP_TOKEN` secret (PAT, `repo` scope)

**`AgentEnsemble/homebrew-tap` repo (new files, already pushed to main):**
- `Formula/agentensemble-viz.rb`: platform-aware Homebrew formula with `on_macos`/`on_linux`
  DSL blocks; SHA256 lines use `# DARWIN_ARM64_SHA256`, `# DARWIN_X64_SHA256`,
  `# LINUX_X64_SHA256` comment anchors for reliable sed-based updates; test block validates
  `--version` output against `version.to_s`
- `.github/workflows/update-formula.yml`: triggered by `repository_dispatch` event;
  downloads all 3 tarballs from the GitHub Release, computes SHA256 with `sha256sum`,
  updates version field + download URLs + SHA256 values in the formula using `sed`,
  commits and pushes; zero manual interaction

**Tests:**
- `agentensemble-viz/src/__tests__/cli.test.ts`: new test file (4 tests);
  `// @vitest-environment node` pragma; strips `NODE_OPTIONS` from child process env to
  prevent vitest's internal loader flags from interfering with the spawned Node.js process;
  covers: exit code 0 for `--version`, semver output format, no server startup, clean stderr

**Documentation:**
- `docs/guides/visualization.md`: Homebrew listed as Option 1 (recommended for regular use),
  npx as Option 2, global npm as Option 3
- `docs/examples/visualization.md`: Homebrew added to "Running the Viewer" section
- `docs/getting-started/installation.md`: new "Execution Graph Visualizer" section with all
  three install options and link to visualization guide
- `README.md`: new "Execution Graph Visualization" section with Homebrew + npx install
  commands, Flow View / Timeline View description, links to guide and example; docs
  navigation table updated to include visualization in Guides and Examples

### Notes
- The `ORG_HOMEBREW_TAP_TOKEN` secret must be added to the main repo before the first
  release to enable the `repository_dispatch` step to succeed
- Bun cross-compilation downloads target runtimes from the Bun CDN at compile time (adds
  ~1-2 min to release CI per target); produced binaries are ~30 MB each
- The npm distribution (`npx @agentensemble/viz`) remains the primary path; Homebrew is
  the recommended alternative for users who prefer native package management

---

## [Unreleased] - Issue #44 -- Execution Graph Visualization -- 2026-03-05

### Added (Issue #44 -- Interactive execution graph visualization)

**New Gradle module: `agentensemble-devtools`**

Separate optional Java module (`testImplementation` or `runtimeOnly` scope) providing
developer tooling for visualization and trace export.

New types (`net.agentensemble.devtools`):
- `DagAgentNode` (`@Value @Builder`): agent snapshot (role, goal, background, toolNames, allowDelegation)
- `DagTaskNode` (`@Value @Builder`): task node (id, description, expectedOutput, agentRole,
  dependsOn, parallelGroup, onCriticalPath)
- `DagModel` (`@Value @Builder`): pre-execution DAG snapshot; `schemaVersion="1.0"`, `type="dag"`,
  workflow, generatedAt, agents, tasks, parallelGroups, criticalPath; `toJson()` / `toJson(Path)`
- `DagExporter`: static `build(Ensemble)` -- constructs `TaskDependencyGraph`, computes topological
  levels (memoized recursion), computes parallel groups, computes critical path (endpoint backtracking)
- `EnsembleDevTools`: static facade -- `buildDag(Ensemble)`, `exportDag(Ensemble, Path)`,
  `exportTrace(EnsembleOutput, Path)`, `export(Ensemble, EnsembleOutput, Path)` returning `ExportResult`

Tests:
- `DagExporterTest` (17): null validation, single task, linear chain, fan-out, diamond,
  independent roots, workflow name, agent nodes, JSON content round-trip
- `EnsembleDevToolsTest` (12): all facade methods, null handling, directory creation, file content

**New npm package: `@agentensemble/viz`**

Located at `agentensemble-viz/` in the project root. Standalone TypeScript/React app.
Distributed via npm (`npx @agentensemble/viz ./traces/`).

Key files:
- `cli.js`: Node.js HTTP server; serves built app + `/api/files` (list) + `/api/file` (serve)
- `src/types/trace.ts`: TypeScript types for ExecutionTrace JSON (schema 1.1)
- `src/types/dag.ts`: TypeScript types for DagModel JSON (schema 1.0)
- `src/utils/parser.ts`: file detection, parsing, ISO-8601 duration parsing, formatting utilities
- `src/utils/colors.ts`: agent color palette (10 colors), tool outcome colors, opacity utilities
- `src/utils/graphLayout.ts`: dagre-based DAG layout returning ReactFlow nodes/edges
- `src/App.tsx`: state management (useReducer), view routing, dark/light mode, CLI auto-load
- `src/pages/LoadTrace.tsx`: landing page; CLI server file list + drag-and-drop
- `src/pages/FlowView.tsx`: ReactFlow DAG with dagre layout, agent legend, minimap, detail panel
- `src/pages/TimelineView.tsx`: SVG Gantt timeline; agent swimlanes; LLM sub-bars; tool markers;
  click to open task/LLM/tool detail panels with full message history (at captureMode >= STANDARD)
- `src/components/graph/TaskNode.tsx`: custom ReactFlow node (agent color, critical path, metrics)
- `src/components/shared/DetailPanel.tsx`: flow view task detail (description, metrics, trace)
- `src/components/shared/MetricsBadge.tsx`: token, latency, cost, call-count badges

Tests (41/41 pass):
- `src/__tests__/parser.test.ts` (28): detectFileType, parseJsonFile, parseDurationMs, formatDuration, formatTokenCount
- `src/__tests__/colors.test.ts` (13): getAgentColor, seedAgentColors, getToolOutcomeColor, withOpacity

Build: TypeScript (tsc + vite build) produces clean production bundle with no errors.

**Documentation:**
- `docs/guides/visualization.md`: new guide covering installation, quick start, API reference,
  capture modes, Flow View, Timeline View, file formats
- `docs/examples/visualization.md`: parallel workflow example with all three export patterns
- `mkdocs.yml`: visualization pages added to Guides and Examples nav sections

**Related GitHub issue:**
- #94 created: "Distribute agentensemble-viz via Homebrew tap" (follow-up)

**File schemas produced:**
- `*.dag.json` (`type:"dag"`, schema 1.0): pre-execution graph from `DagExporter`
- `*.trace.json` (schema 1.1): post-execution trace from `EnsembleDevTools.exportTrace()` /
  `JsonTraceExporter`; enriched at captureMode STANDARD/FULL

---

## [Unreleased] - feature/89-capture-mode
### Added
- `CaptureMode` enum (OFF/STANDARD/FULL) with `isAtLeast()` and `CaptureMode.resolve()`
  supporting JVM system property `agentensemble.captureMode` and env var
  `AGENTENSEMBLE_CAPTURE_MODE` for zero-code activation
- `CapturedMessage` value object: serializable snapshot of one LangChain4j `ChatMessage`
- `MemoryOperationListener` interface + wired into `MemoryContext` via
  `setOperationListener()` / `clearOperationListener()`
- `LlmInteraction.messages` field: full per-iteration message history at STANDARD+
- `ToolCallTrace.parsedInput` field: structured tool arguments map at FULL
- `ExecutionTrace.captureMode` field; schema version bumped to `1.1`
- `ExecutionContext.captureMode()` accessor + 7-param `of()` factory method
- `Ensemble.captureMode` builder field with effective-mode resolution and FULL auto-export
- `CaptureModeExample.java` + `runCaptureMode` Gradle task in `agentensemble-examples`
- New docs: `docs/guides/capture-mode.md`, `docs/examples/capture-mode.md`
### Changed
- `AgentExecutor`: snapshots messages at STANDARD+, enriches tool traces at FULL,
  wires/clears memory listener in try/finally per-task
- `TaskTraceAccumulator`: new CaptureMode constructor + `setCurrentMessages()`
- Design docs updated: 02-architecture, 04-execution-engine, 09-logging, 11-configuration,
  13-future-roadmap
- `docs/reference/ensemble-configuration.md`: added captureMode row
- `README.md`: added CaptureMode section
- `mkdocs.yml`: added CaptureMode guide + example pages


## [Implemented / branch ready] Issue #42 -- 2026-03-05

Feature branch: `feature/42-execution-metrics`
Commit: `d01ea3e`

### Added (Issue #42 -- Execution metrics, token tracking, cost estimation, and execution trace)

**New types (net.agentensemble.metrics):**
- `TaskMetrics` (`@Value @Builder`): per-task token counts (input/output/total; -1 = unknown),
  LLM latency, tool execution time, prompt build time, memory retrieval time, LLM call count,
  tool call count, delegation count, `MemoryOperationCounts`, optional `CostEstimate`;
  `EMPTY` constant
- `ExecutionMetrics` (`@Value @Builder`): aggregated run-level totals; `from(List<TaskOutput>)`
  factory with -1-propagation rule; `EMPTY` constant
- `MemoryOperationCounts` (`@Value @Builder`): STM writes, LTM stores, LTM retrievals,
  entity lookups; `add()` for aggregation; `ZERO` constant
- `CostConfiguration` (`@Value @Builder`): `inputTokenRate`, `outputTokenRate`, `currency`;
  `estimate(long inputTokens, long outputTokens)` returns `null` when either is -1
- `CostEstimate` (`@Value @Builder`): `inputCost`, `outputCost`, `totalCost`; `add()` method

**New types (net.agentensemble.trace):**
- `ExecutionTrace` (`@Value @Builder(toBuilder=true)`): top-level trace for one run;
  `schemaVersion="1.0"`, `ensembleId`, `workflow`, timestamps/duration, `inputs`, `agents`,
  `taskTraces`, `metrics`, `totalCostEstimate`, `errors`, `metadata`;
  `toJson()` / `toJson(Path)` via Jackson+JavaTimeModule (`WRITE_DURATIONS_AS_TIMESTAMPS=false`);
  `export(ExecutionTraceExporter)`
- `TaskTrace`: per-task trace with `prompts`, `llmInteractions`, `delegations`, `finalOutput`,
  `parsedOutput`, `metrics`, `metadata`
- `LlmInteraction`: one ReAct iteration; `iterationIndex`, `startedAt`/`completedAt`/`latency`,
  `inputTokens`/`outputTokens` (-1 if unknown), `responseType`, `responseText`, `toolCalls`
- `ToolCallTrace`: one tool invocation; `toolName`, `arguments`, `result`, `structuredOutput`,
  timing, `outcome` (SUCCESS/FAILURE/ERROR/SKIPPED_MAX_ITERATIONS), `metadata`
- `DelegationTrace`: delegation record; timing, `depth`, `result`, `succeeded`,
  `workerTrace` (nested TaskTrace for peer delegation)
- `AgentSummary`, `TaskPrompts`, `ErrorTrace`, `LlmResponseType` (enum), `ToolCallOutcome` (enum)

**New types (net.agentensemble.trace.export):**
- `ExecutionTraceExporter` (`@FunctionalInterface`): `void export(ExecutionTrace trace)`
- `JsonTraceExporter`: directory mode (auto-names `{ensembleId}.json`) or file mode

**New internal type (net.agentensemble.trace.internal):**
- `TaskTraceAccumulator`: mutable per-task collector; `beginLlmCall()`/`endLlmCall()` for LLM
  timing + token capture; `addToolCallToCurrentIteration()`; `finalizeIteration()` seals
  `LlmInteraction`; `addDelegation()`; memory operation counters; `buildTrace()`/`buildMetrics()`

**Modified types:**
- `TaskOutput`: `metrics` (`TaskMetrics`, default `EMPTY`) + `trace` (`TaskTrace`, nullable) added;
  `@Builder(toBuilder=true)` added for immutable copy support
- `EnsembleOutput`: replaced `@Builder @Value` with `@Value` + manual fluent builder; added
  `metrics` (auto-computed `ExecutionMetrics.from(taskOutputs)`) + `trace` (`ExecutionTrace`,
  nullable); `of(raw, outputs, duration, toolCalls)` convenience factory
- `AgentExecutor`: creates `TaskTraceAccumulator` per `execute()`; times prompt building;
  wraps each `LLM.chat()` with `beginLlmCall()`/`endLlmCall()`; builds `ToolCallTrace` per
  tool call; wires `accumulator::addDelegation` into `AgentDelegationTool`
- `AgentDelegationTool`: new 3-arg constructor accepts `Consumer<DelegationTrace>`; builds
  `DelegationTrace` with nested worker `TaskTrace` after successful peer delegation
- `ExecutionContext`: `costConfiguration` field added (nullable, passed to `TaskTraceAccumulator`);
  new 6-arg `of()` overload; backward-compat 5-arg overload delegates with `null`
- `Ensemble`: `costConfiguration` + `traceExporter` builder fields; `runWithInputs()` passes
  costConfig to `ExecutionContext`, assembles `ExecutionTrace`, calls exporter

**Dependencies:**
- `jackson-datatype-jsr310` added to `agentensemble-core/build.gradle.kts`
- Version catalog entry `jackson-datatype-jsr310` added to `gradle/libs.versions.toml`

**Tests added (all pass, line coverage >= 90%):**
- `TaskMetricsTest`, `ExecutionMetricsTest`, `MemoryOperationCountsTest`,
  `CostConfigurationTest`, `TaskTraceAccumulatorTest`, `AgentExecutorMetricsTest`,
  `ExecutionTraceTest`, `EnsembleOutputTest`

**Docs updated:**
- `docs/guides/metrics.md`: major rewrite covering execution metrics, token counts,
  cost estimation, execution trace, JSON export, trace structure, tool call inspection
- `docs/examples/metrics.md`: new end-to-end example
- `docs/reference/ensemble-configuration.md`: `costConfiguration`/`traceExporter` fields;
  `getMetrics()`/`getTrace()` on both `EnsembleOutput` and `TaskOutput`
- `docs/design/01-overview.md`: Metrics and Execution Trace in core concepts table
- `README.md`: Metrics and Observability section
- `mkdocs.yml`: Metrics and Traces example page added

---

## [Unreleased] -- Remove GitHub Packages publishing -- 2026-03-04

### Removed
- GitHub Packages publish step (`publishAllPublicationsToGitHubPackagesRepository`) removed
  from `.github/workflows/release.yml`
- `packages: write` permission removed from `.github/workflows/release.yml`
  (only `contents: write` is still needed)
- `publishing { repositories { maven { name = "GitHubPackages" ... } } }` block removed from:
  - `agentensemble-core/build.gradle.kts`
  - `agentensemble-metrics-micrometer/build.gradle.kts`
  - `agentensemble-tools/bom/build.gradle.kts`
  - `buildSrc/src/main/kotlin/agentensemble.tool-conventions.gradle.kts`

Maven Central remains the sole publication target for all modules.

---

## [Implemented / branch ready] Issue #81 -- 2026-03-04

Feature branch: `feature/81-hierarchical-constraints`
Commits: `41c8222`, `927dc89`

### Added (Issue #81 -- Constrained hierarchical mode)

**New types:**
- `HierarchicalConstraints` (`@Value @Builder` in `net.agentensemble.workflow`):
  `requiredWorkers` (Set), `allowedWorkers` (Set), `maxCallsPerWorker` (Map),
  `globalMaxDelegations` (int, default 0), `requiredStages` (List<List<String>>)
- `ConstraintViolationException` (in `net.agentensemble.exception`): extends
  `AgentEnsembleException`; fields: `violations`, `completedTaskOutputs`; thrown
  post-execution when required workers were not called
- `HierarchicalConstraintEnforcer` (package-private in `net.agentensemble.workflow`):
  implements `DelegationPolicy` for pre-delegation enforcement; synchronized
  `evaluate()` checks allowedWorkers, global cap, per-worker cap, stage ordering;
  `recordDelegation()` tracks completed workers; `validatePostExecution()` checks
  required workers

**Modified types:**
- `HierarchicalWorkflowExecutor`: new 7-arg constructor with nullable `HierarchicalConstraints`;
  `execute()` creates enforcer when constraints != null, prepends enforcer to policy chain,
  registers internal EnsembleListener to call `recordDelegation()` on DelegationCompletedEvent,
  calls `validatePostExecution()` after manager finishes; all existing constructors backward
  compatible
- `Ensemble`: new `hierarchicalConstraints` field; passed to `HierarchicalWorkflowExecutor`
  in `selectExecutor()`
- `EnsembleValidator`: new `validateHierarchicalConstraints()` validates constraint roles
  against registered agents, values > 0, requiredWorkers subset of allowedWorkers

**Tests added (6 new test files, 851 total):**
- `ConstraintViolationExceptionTest`, `HierarchicalConstraintsTest`,
  `HierarchicalConstraintEnforcerTest`, `HierarchicalWorkflowExecutorConstraintTest`,
  `EnsembleConstraintValidationTest`, `HierarchicalConstraintsIntegrationTest`

**Docs updated (14 files):**
- README.md, docs/guides/delegation.md, docs/guides/workflows.md,
  docs/guides/error-handling.md, docs/examples/hierarchical-team.md,
  docs/reference/ensemble-configuration.md, docs/design/02-architecture.md,
  docs/design/03-domain-model.md, docs/design/04-execution-engine.md,
  docs/design/08-error-handling.md, docs/design/13-future-roadmap.md,
  docs/getting-started/concepts.md

**CI parity rule addition:** `{@link PackagePrivateClass}` from public class Javadoc also fails;
must use `{@code}` (extends rule from issue #84 about Lombok-generated methods).

---

## [Implemented / branch ready] Issues #78 + #79 -- 2026-03-04

Feature branch: `feature/delegation-policy-hooks-and-lifecycle-events`
Commits: `f5d9b67`, `20b3185`

### Added (Issue #79 -- Delegation lifecycle events and correlation IDs)
- `DelegationStartedEvent` record: delegationId, delegatingAgentRole, workerRole,
  taskDescription, delegationDepth, request -- in `net.agentensemble.callback`
- `DelegationCompletedEvent` record: delegationId, delegatingAgentRole, workerRole,
  response, duration -- in `net.agentensemble.callback`
- `DelegationFailedEvent` record: delegationId, delegatingAgentRole, workerRole,
  failureReason, cause, response, duration -- in `net.agentensemble.callback`
- `EnsembleListener.onDelegationStarted(DelegationStartedEvent)` default no-op
- `EnsembleListener.onDelegationCompleted(DelegationCompletedEvent)` default no-op
- `EnsembleListener.onDelegationFailed(DelegationFailedEvent)` default no-op
- `ExecutionContext.fireDelegationStarted/Completed/Failed()` fire methods with same
  exception-isolation semantics as existing task fire methods
- `Ensemble.Builder` lambda convenience methods: `onDelegationStarted`, `onDelegationCompleted`,
  `onDelegationFailed`
- Event firing in `AgentDelegationTool` (peer delegation) and `DelegateTaskTool`
  (hierarchical delegation): started before worker, completed on success, failed on all
  failure paths; guard/policy failures fire failed only (no start event)

### Added (Issue #78 -- Delegation policy hooks)
- `DelegationPolicy` (@FunctionalInterface): `evaluate(DelegationRequest, DelegationPolicyContext)`
  in `net.agentensemble.delegation.policy`
- `DelegationPolicyResult` (sealed interface): `Allow` (singleton), `Reject` (record w/ reason),
  `Modify` (record w/ modifiedRequest); factory methods `allow()`, `reject(String)`,
  `modify(DelegationRequest)` in `net.agentensemble.delegation.policy`
- `DelegationPolicyContext` (immutable record): delegatingAgentRole, currentDepth, maxDepth,
  availableWorkerRoles in `net.agentensemble.delegation.policy`
- `DelegationContext.policies` field (immutable `List<DelegationPolicy>`); propagated through
  `descend()`; 5-arg `create()` factory; original 4-arg delegates with `List.of()`
- `Ensemble.delegationPolicies` field (`@Singular("delegationPolicy") List<DelegationPolicy>`);
  wired through `selectExecutor()` to all three workflow executors
- Policy evaluation in `AgentDelegationTool` and `DelegateTaskTool`: runs after guards,
  before worker invocation; REJECT short-circuits; MODIFY replaces working request
- `SequentialWorkflowExecutor`, `HierarchicalWorkflowExecutor`, `ParallelWorkflowExecutor`:
  updated with policy-aware constructors and `DelegationContext.create()` calls

### Tests (Issues #78 + #79)
- `DelegationPolicyContextTest`: record fields, equality
- `DelegationPolicyResultTest`: allow singleton, reject/modify factories, validation,
  pattern matching exhaustiveness
- `AgentDelegationToolPolicyTest`: ALLOW/REJECT/MODIFY, chaining, first-REJECT short-circuits,
  MODIFY+REJECT uses modified request, propagation through descend(), context field verification
- `DelegationEventsTest`: all three event record fields and equality
- `AgentDelegationToolEventsTest`: start+completed on success, correlationId matching, guard
  failures/policy rejections fire failed-only, worker exception fires start+failed,
  listener exception isolation
- `DelegateTaskToolPolicyAndEventsTest`: full policy and event coverage for hierarchical path

### Documentation (Issues #78 + #79)
- `docs/guides/delegation.md`: Delegation Policy Hooks + Delegation Lifecycle Events sections
- `docs/guides/callbacks.md`: delegation event type tables + updated quick start
- `docs/reference/ensemble-configuration.md`: delegationPolicies row added
- `docs/design/13-future-roadmap.md`: Phase 7+ section for #78/#79 marked complete
- `docs/examples/callbacks.md`: Delegation Events section added
- `docs/examples/hierarchical-team.md`: Delegation Policy Hooks section added
- `README.md`: delegation policy hooks + lifecycle events in delegation section;
  delegation events in callbacks section; configuration table updated

---

## [Implemented / branch ready] Issues #77 + #80 -- 2026-03-04

Feature branch: `feature/delegation-contracts-and-manager-prompt-strategy`
Commits: `eee052c`, `5771d47`, `acf513b`

### Added (Issue #80 -- Manager prompt extension hook)
- `ManagerPromptStrategy` (public interface in `net.agentensemble.workflow`): two methods
  `buildSystemPrompt(ManagerPromptContext)` and `buildUserPrompt(ManagerPromptContext)`
- `ManagerPromptContext` (public immutable record): `agents`, `tasks`, `previousOutputs`, `workflowDescription`
- `DefaultManagerPromptStrategy` (public class): contains the existing `ManagerPromptBuilder` logic;
  exposes `DEFAULT` singleton; used as the default when no strategy is registered
- `Ensemble.Builder.managerPromptStrategy(ManagerPromptStrategy)` -- defaults to `DefaultManagerPromptStrategy.DEFAULT`
- `HierarchicalWorkflowExecutor` 5-arg constructor accepting `ManagerPromptStrategy`; builds
  `ManagerPromptContext` and delegates prompt production to strategy; blank user prompt falls
  back to built-in coordinator string
- Tests: `ManagerPromptContextTest`, `DefaultManagerPromptStrategyTest`, 8 new tests in
  `HierarchicalWorkflowExecutorTest`

### Deprecated (Issue #80)
- `ManagerPromptBuilder.buildBackground()` and `buildTaskDescription()` marked
  `@Deprecated(forRemoval = true)`; both delegate to `DefaultManagerPromptStrategy.DEFAULT`

### Added (Issue #77 -- Structured delegation contracts)
- `DelegationPriority` enum: `LOW`, `NORMAL`, `HIGH`, `CRITICAL`
- `DelegationStatus` enum: `SUCCESS`, `FAILURE`, `PARTIAL`
- `DelegationRequest` (immutable `@Value @Builder`): `taskId` (auto-UUID), `agentRole`,
  `taskDescription`, `scope`, `priority`, `expectedOutputSchema`, `maxOutputRetries`, `metadata`
- `DelegationResponse` (immutable Java record): `taskId`, `status`, `workerRole`, `rawOutput`,
  `parsedOutput`, `artifacts`, `errors`, `metadata`, `duration`
- `AgentDelegationTool.getDelegationResponses()`: returns all responses in invocation order
- `DelegateTaskTool.getDelegationResponses()`: same contract; guard failures produce FAILURE responses
- Tests: `DelegationPriorityTest`, `DelegationStatusTest`, `DelegationRequestTest`,
  `DelegationResponseTest`; 12 new tests in `AgentDelegationToolTest`; 11 new tests in `DelegateTaskToolTest`

### Documentation (both issues)
- `README.md`: custom manager prompts note, structured delegation contracts note, updated
  Ensemble Configuration table with `managerPromptStrategy`
- `docs/guides/workflows.md`: "Customizing the Manager Prompt" subsection
- `docs/reference/ensemble-configuration.md`: `managerPromptStrategy` row
- `docs/guides/delegation.md`: "Structured Delegation Contracts" section
- `docs/examples/hierarchical-team.md`: custom strategy example appended
- `HierarchicalTeamExample.java`: demonstrates `investmentStrategy`
- `docs/design/02-architecture.md`: strategy pattern note updated
- `docs/design/03-domain-model.md`: all four new delegation types added

---

## [Planned] Issues #78, #79, #81 -- Structured Delegation API (remaining) -- 2026-03-04

Still open; depend on issue #77 being merged:

---

## [Planned] Issues #77-#81 -- Structured Delegation API -- Created 2026-03-04

### Architecture Decisions Recorded

Five GitHub issues created to enhance the delegation infrastructure. Dependency order:
#77 foundational -> #78/#79 depend on #77 -> #81 depends on #77/#78/#79; #80 independent.

| Issue | Feature | New Types |
|-------|---------|-----------|
| #77 | Structured delegation contract (DelegationRequest / DelegationResponse) | `DelegationRequest`, `DelegationResponse`, `DelegationPriority`, `DelegationStatus` in `net.agentensemble.delegation` |
| #78 | Delegation policy hooks | `DelegationPolicy`, `DelegationPolicyResult`, `DelegationPolicyContext` in `net.agentensemble.delegation.policy` |
| #79 | Delegation lifecycle events + correlation IDs | `DelegationStartedEvent`, `DelegationCompletedEvent`, `DelegationFailedEvent` in `net.agentensemble.callback` |
| #80 | Manager prompt extension hook | `ManagerPromptStrategy`, `ManagerPromptContext`, `DefaultManagerPromptStrategy` in `net.agentensemble.workflow` |
| #81 | Constrained hierarchical mode | `HierarchicalConstraints`, `ConstraintViolationException` in `net.agentensemble.workflow` |

Key design decisions:
- **Option C (hybrid)**: LLM-facing `@Tool` method keeps 2-param signature `(agentRole, taskDescription)`;
  `DelegationRequest` constructed internally; `DelegationResponse` serialized as Jackson JSON returned to LLM
- **Jackson**: used for all DelegationRequest/Response serialization (already on classpath via LangChain4j)
- **DelegationPolicy as sealed type**: ALLOW / REJECT(reason) / MODIFY(newRequest) -- first-failure-wins for REJECT; MODIFY chains
- **Constraint enforcement via built-in policies**: `HierarchicalConstraints` registers policies before user policies
- **ManagerPromptBuilder deprecated**: existing logic extracted to public `DefaultManagerPromptStrategy`; `ManagerPromptBuilder` kept one cycle

---

## [Unreleased / merged to main] -- Issues #60 + #73, PR #72 (squash `706305e`) -- MERGED 2026-03-04

### Fixed (PR #72 post-review -- commits 5e81c70, 1134b52)
- `ExecutionContext.java`: two `{@link}` references to Lombok-generated builder methods
  (`toolExecutor(Executor)`, `toolMetrics(ToolMetrics)`) replaced with `{@code}` examples;
  Javadoc tool cannot resolve methods generated by Lombok annotation processing, causing
  "error: reference not found" and a CI failure on `:agentensemble-core:javadoc`
- `FileReadTool`: added `toRealPath()` re-validation after the initial `normalize()+startsWith()`
  check; the initial check blocks `../` path traversal but not symlinks inside `baseDir` that
  point outside the sandbox; re-validating against `baseDir.toRealPath()` blocks symlink escapes
- `FileWriteTool`: same symlink fix applied to the parent directory check after `createDirectories`
- `ProcessAgentTool`: replaced sequential wait-then-drain with concurrent virtual-thread drain;
  the previous approach could deadlock when a child process filled the OS pipe buffer (~64 KB)
  before `waitFor()` started reading; two `Thread.ofVirtual()` threads now drain stdout/stderr
  concurrently, with `join()` called in both the normal and timeout paths
- `agentensemble-tools/bom/build.gradle.kts`: added explicit Maven coordinates
  (`net.agentensemble:agentensemble-tools-bom`) so the BOM publishes under a stable artifact
  ID rather than the Gradle default of `net.agentensemble:bom`
- `README.md`: updated the built-in tools dependency snippet from the obsolete
  `agentensemble-tools:1.0.0` aggregate artifact to the correct BOM + per-tool module pattern
- `docs/guides/built-in-tools.md`: added SSRF security note for `WebScraperTool` describing
  the risk when URLs come from untrusted inputs (LLM prompt injection / end users) and listing
  mitigations (URL allowlisting, blocking private ranges, hardened fetcher/proxy)
- New tests: `FileReadToolTest.execute_symlinkPointingOutsideSandbox_returnsAccessDenied()`,
  `FileWriteToolTest.execute_symlinkDirectoryPointingOutsideSandbox_returnsAccessDenied()`
  (both use `assumeTrue` to skip on systems without symlink support)
- Issue #74 created: Tool Pipeline/Chaining (Unix-pipe-style `search -> filter -> format`)
  for future development

## [Unreleased / merged via PR #72] -- feature/60-built-in-tool-library (Issues #60 + #73)

### Added
- `AbstractAgentTool` base class: template method (`doExecute`), automatic timing/success/failure/
  error counters, structured logging via `log()`, custom metrics via `metrics()`, executor
  access via `executor()`, exception safety (uncaught exceptions converted to ToolResult.failure)
- `ToolContext`: immutable record (Logger, ToolMetrics, Executor) injected by framework into
  AbstractAgentTool instances before first execution
- `ToolMetrics` interface + `NoOpToolMetrics` singleton default (zero overhead)
- `ToolContextInjector`: friend-bridge enabling cross-package ToolContext injection
- `ProcessAgentTool`: subprocess execution with formal AgentEnsemble subprocess protocol spec
  (JSON-over-stdio; success/failure/structured output; timeout + process kill; handles non-reading stdin)
- `HttpAgentTool`: REST endpoint wrapping (GET with query param, POST with body; custom headers;
  auto Content-Type for JSON input; injectable HttpClient)
- `agentensemble-metrics-micrometer` module: `MicrometerToolMetrics` bridging `ToolMetrics` to
  Micrometer; `agentensemble.tool.executions` counter + `agentensemble.tool.duration` timer;
  tagged by `(tool_name, agent_role, outcome)`
- buildSrc `agentensemble.tool-conventions` precompiled Gradle convention plugin
- `agentensemble-tools/bom` BOM platform module for version alignment
- `pluginManagement` in `settings.gradle.kts` resolving plugin version conflicts with buildSrc
- All 9 built-in tools as independent sub-modules under `agentensemble-tools/`
- `RemoteToolExample.java` and `MetricsExample.java` examples
- New guides: `remote-tools.md`, `metrics.md`

### Changed
- `ToolResult` enhanced with optional `structuredOutput` field and typed accessor
- `ToolCallEvent` enhanced with `structuredResult` field; metrics tagged by `(toolName, agentRole)`
- `ToolResolver` injects ToolContext, sets/clears agentRole thread-local, returns `ToolResult`
- `AgentExecutor` parallelizes multi-tool turns via CompletableFuture + virtual threads
- `ExecutionContext` gains `toolExecutor` and `toolMetrics` fields
- `Ensemble.builder()` gains `toolExecutor(Executor)` and `toolMetrics(ToolMetrics)` options
- All 7 original tools converted to extend `AbstractAgentTool` (`doExecute` instead of `execute`)
- All 7 original tools moved to per-module sub-packages; old flat `agentensemble-tools/src/` removed
- `guides/tools.md`, `guides/built-in-tools.md`, `getting-started/installation.md` updated
- `mkdocs.yml` navigation updated with Remote Tools and Metrics guides
- `agentensemble-examples/build.gradle.kts` updated with all tool module dependencies


## [1.0.0] - 2026-03-03 (Issue #60, feature/60-built-in-tool-library)

### Added
- New Gradle module `agentensemble-tools` published as separate artifact
  `net.agentensemble:agentensemble-tools`; optional -- users add it only when they want built-in tools
- `gradle/libs.versions.toml`: `jsoup = "1.18.3"` version + `jsoup` library entry
- `settings.gradle.kts`: `include("agentensemble-tools")`
- `net.agentensemble.tools` package with 7 built-in `AgentTool` implementations:
  - `CalculatorTool`: arithmetic expression evaluation via recursive-descent parser;
    supports +, -, *, /, % (modulo), ^ (power), parentheses, unary minus, decimal numbers;
    integer results formatted without decimal point
  - `DateTimeTool`: current date/time (now, today), timezone conversion, date/datetime arithmetic;
    uses `java.time`; package-private `Clock` constructor for deterministic testing;
    `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")` for consistent output
  - `FileReadTool`: sandboxed file reads via `FileReadTool.of(Path baseDir)` factory;
    path traversal rejected via `normalize() + startsWith(baseDir)` check
  - `FileWriteTool`: sandboxed file writes; JSON input format `{"path": ..., "content": ...}`;
    parent directories created automatically; same traversal protection as FileReadTool
  - `WebSearchProvider`: public `@FunctionalInterface`; `search(String) throws IOException, InterruptedException`
  - `WebSearchTool`: delegates to WebSearchProvider; factory methods `of(WebSearchProvider)`,
    `ofTavily(String apiKey)`, `ofSerpApi(String apiKey)`
  - `WebScraperTool`: HTTP GET + Jsoup HTML-to-text extraction; strips scripts/nav/footer/header;
    truncates at configurable character limit; `new WebScraperTool()` and
    `WebScraperTool.withMaxContentLength(int)` factory
  - `JsonParserTool`: dot-notation + array-index path extraction from JSON;
    input format: first line = path expression, remaining lines = JSON
- Package-private classes (testability infrastructure):
  - `TavilySearchProvider`: Tavily API implementation; injectable `HttpClient` constructor
  - `SerpApiSearchProvider`: SerpAPI/Google implementation; injectable `HttpClient` constructor
  - `UrlFetcher`: `@FunctionalInterface` for URL fetching (injectable in WebScraperTool tests)
  - `HttpUrlFetcher`: real `java.net.http.HttpClient` implementation; injectable `HttpClient` constructor
- 165 new tests across 11 test classes in `agentensemble-tools`:
  - `CalculatorToolTest` (31), `DateTimeToolTest` (22), `FileReadToolTest` (16),
    `FileWriteToolTest` (20), `WebSearchToolTest` (14), `TavilySearchProviderTest` (9),
    `SerpApiSearchProviderTest` (9), `WebScraperToolTest` (14), `HttpUrlFetcherTest` (5),
    `JsonParserToolTest` (19), `BuiltInToolsIntegrationTest` (6)
- `docs/guides/built-in-tools.md`: new comprehensive guide with one section per tool
- `docs/design/13-future-roadmap.md`: Phase 9 Built-in Tools section marked COMPLETE with
  implementation table
- `docs/getting-started/installation.md`: updated to v1.0.0; added `agentensemble-tools`
  optional dependency; switched from GitHub Packages to Maven Central in code examples
- `mkdocs.yml`: Built-in Tools added to Guides nav (between Tools and Memory)
- `README.md`: quickstart dependency updated to 1.0.0 with `agentensemble-tools` shown as
  optional; roadmap entry v1.0.0 struck through

### Technical Notes
- `WebSearchProvider` is public API -- users create custom search backends via lambda or class
- `UrlFetcher`, `TavilySearchProvider`, `SerpApiSearchProvider`, `HttpUrlFetcher` are all
  package-private (implementation details not exposed to users)
- HttpClient injection (package-private constructors) avoids real HTTP in unit tests without
  requiring WireMock or other server infrastructure
- `DateTimeTool` uses `DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")` everywhere to
  ensure consistent second-level precision (Java's `LocalDateTime.toString()` drops trailing
  `:00` seconds by default)
- FileRead/WriteTool sandboxing: `baseDir.resolve(input).normalize()` then `startsWith(baseDir)`
  catches all traversal including `../`, absolute paths, and nested `a/../../b` patterns
- `JsonParserTool` path parser handles dot-notation, bracket array indices, and mixed
  combinations like `users[1].address.city`
- `BuiltInToolsIntegrationTest` verifies tool interoperability: all 7 tools implement `AgentTool`,
  FileWrite+FileRead round-trip, JsonParser+FileWrite pipeline, WebSearch+Calculator scenario

---

## [2.0.0] - 2026-03-05 (branch: feat/issue-98-static-map-reduce-ensemble)

### Added
- `MapReduceEnsemble<T>` in `net.agentensemble.mapreduce`: static tree-reduction DAG builder
  that automates fan-out/tree-reduce for parallel agent orchestration. Builder API with
  `mapAgent`, `mapTask`, `reduceAgent`, `reduceTask` (required) and `chunkSize` (default 5,
  min 2) plus all Ensemble passthrough fields.
- Static DAG construction algorithm: O(log_K(N)) tree depth; N <= K produces 2 levels (map
  + final reduce); N > K produces intermediate reduce levels until level size <= K.
- `toEnsemble()` method returns the pre-built inner `Ensemble` for devtools inspection.
- `getNodeTypes()` and `getMapReduceLevels()` identity maps for devtools enrichment.
- `DagTaskNode.nodeType` and `DagTaskNode.mapReduceLevel` optional fields for map-reduce
  visualization metadata.
- `DagModel.mapReduceMode` optional field (`"STATIC"` or `"ADAPTIVE"`).
- `DagExporter.build(MapReduceEnsemble<?>)` overload that enriches task nodes with
  map-reduce metadata and sets `mapReduceMode = "STATIC"`.
- `MapReduceKitchenExample.java` and `runMapReduceKitchen` Gradle task.
- `docs/guides/map-reduce.md` and `docs/examples/map-reduce.md`.
- `MapReduceEnsemble` section in `docs/reference/ensemble-configuration.md`.

### Changed
- `DagModel.schemaVersion` bumped from `"1.0"` to `"1.1"` to reflect new optional fields.
- `agentensemble-viz types/dag.ts`: added `nodeType?`, `mapReduceLevel?` to `DagTaskNode`;
  added `mapReduceMode?` to `DagModel`.
- `agentensemble-viz TaskNode.tsx`: renders MAP, REDUCE Ln, AGGREGATE, DIRECT badges.
- `mkdocs.yml`: added MapReduceEnsemble guide and example to nav.
- `README.md`: added MapReduceEnsemble subsection, `runMapReduceKitchen` example, v2.0.0
  in roadmap, design/14 in design docs list.

### Tests Added
- `MapReduceEnsembleTest` (35 unit tests): builder validation, DAG construction N=1/3/4/9/25/26,
  context wiring, agent counts, factory guarantees, optional builder setter coverage.
- `MapReduceEnsembleIntegrationTest` (13 integration tests): end-to-end with mock LLMs,
  FAIL_FAST, CONTINUE_ON_ERROR, runtime input overrides, metadata accessor tests.
- `DagExporterTest`: 11 new tests for `build(MapReduceEnsemble<?>)` overload.

## [2.0.0-issue-99] - 2026-03-05
### Added
- `MapReduceEnsemble`: adaptive strategy (`targetTokenBudget`, `contextWindowSize`,
  `budgetRatio`, `maxReduceLevels`, `tokenEstimator`) in addition to existing static strategy
- `MapReduceTokenEstimator`: 3-tier token estimation (provider -> custom -> heuristic)
- `MapReduceBinPacker`: first-fit-decreasing bin-packing for adaptive reduce grouping
- `MapReduceAdaptiveExecutor`: level-by-level adaptive execution using PassthroughChatModel
  carrier tasks to propagate context between levels
- `PassthroughChatModel`: package-private ChatModel returning fixed text for carriers
- `MapReduceLevelSummary`: per-level timing/task-count summary in `ExecutionTrace`
- `ExecutionTrace.mapReduceLevels`: list of per-level summaries for adaptive runs
- `TaskTrace.nodeType` / `TaskTrace.mapReduceLevel`: annotation for adaptive trace tasks
- `DagExporter.build(ExecutionTrace)`: post-execution DAG export for adaptive runs
- `MapReduceEnsemble.isAdaptiveMode()`: mode query accessor
- Tests: MapReduceTokenEstimatorTest (12), MapReduceBinPackerTest (13),
  MapReduceEnsembleAdaptiveValidationTest (22), MapReduceEnsembleAdaptiveRunTest (9),
  DagExporterTest additions (8)
### Changed
- `MapReduceEnsemble.toEnsemble()` throws `UnsupportedOperationException` in adaptive mode
- `docs/guides/map-reduce.md`: adaptive mode section added
- `docs/examples/map-reduce.md`: adaptive mode kitchen example added
- `docs/reference/ensemble-configuration.md`: adaptive fields, updated methods table

## [Unreleased]

### Fixed (PR #66 -- fix/javadoc-link-error-add-to-ci)
- `Ensemble.java`: three `{@link}` references pointing to Lombok `@Singular`-generated
  builder methods (`listener(EnsembleListener)`, `listeners(Collection)`) replaced with
  `{@code}`. Javadoc runs against raw source before annotation processing; those methods
  are invisible to it, producing "error: reference not found" and failing the release workflow.
  Affected lines: field-level comment (2 occurrences) and `EnsembleBuilder` class-level comment.
- `ci.yml`: added `:agentensemble-core:javadoc` to the CI build step
  (`./gradlew build :agentensemble-core:javadoc --continue`) so javadoc errors are caught
  on every PR, not only at release time. Previously the javadoc task was only executed in
  the release workflow.
- Issue #65 created to track the 92 remaining javadoc warnings (missing `@param`, `@return`,
  field/constructor comments across exception and tool classes).


### Changed (Class-size refactoring -- 2026-03-03)
- `Ensemble.java` (523 -> 342 lines): all validation logic extracted to package-private
  `EnsembleValidator`; `Ensemble.run()` delegates via `new EnsembleValidator(this).validate()`
- `AgentExecutor.java` (411 -> 321 lines): structured output parsing and LLM retry loop
  extracted to package-private `StructuredOutputHandler`; `AgentExecutor` delegates via
  `StructuredOutputHandler.parse(agent, task, response, systemPrompt)`
- `ParallelWorkflowExecutor.java` (510 -> 243 lines): per-task submission, dependency
  resolution, skip cascading, and `shouldSkip()` extracted to package-private
  `ParallelTaskCoordinator`; coordinator holds all per-execution shared state as fields,
  eliminating 16-parameter method signatures

### Added (Class-size refactoring -- 2026-03-03)
- `net.agentensemble.EnsembleValidator`: package-private; `validate(Ensemble)` entry point;
  contains `validateTasksNotEmpty`, `validateAgentsNotEmpty`, `validateMaxDelegationDepth`,
  `validateManagerMaxIterations`, `validateParallelErrorStrategy`, `validateAgentMembership`,
  `validateHierarchicalRoles`, `validateNoCircularContextDependencies`, `validateContextOrdering`,
  `warnUnusedAgents`
- `net.agentensemble.agent.StructuredOutputHandler`: package-private; static `parse(Agent, Task,
  String, String)` method; handles retry loop with LLM correction prompts
- `net.agentensemble.workflow.ParallelTaskCoordinator`: package-private; per-execution coordinator
  holding shared state (completedOutputs, failedTaskCauses, skippedTasks, pendingDepCounts,
  firstFailureRef, latch, etc.) as final fields; `submitTask(Task)` and `resolveDependent(Task)`
  (package-private and private respectively)
- New test classes (all tests relocated, zero new assertions):
  - `EnsembleValidationTest` (validation error cases split from `EnsembleTest`)
  - `TaskValidationTest` (validation error cases split from `TaskTest`)
  - `ParallelWorkflowExecutorTestBase` (abstract base with shared helpers)
  - `ParallelWorkflowExecutorErrorTest` (FAIL_FAST + CONTINUE_ON_ERROR split from main test)
  - `ParallelWorkflowExecutorCallbackTest` (callback/taskIndex split from main test)
  - `ParallelEnsembleErrorIntegrationTest` (error strategies split from integration test)
  - `HierarchicalEnsembleValidationIntegrationTest` (validation + callbacks split)
  - `DelegationEnsembleConfigIntegrationTest` (config/memory/hierarchical split)

### Added (Code Quality Tooling)
- **Spotless** (`com.diffplug.spotless` 7.0.2): enforces consistent Java formatting using
  `palantir-java-format` 2.47.0 (4-space indent, matching existing style); `spotlessCheck`
  wired into `check` task so CI fails on violations; `spotlessApply` auto-formats
- **Error Prone** (`net.ltgt.errorprone` 4.2.0, `error_prone_core` 2.36.0): compile-time
  bug detection; surfaced 8 real issues (IdentityHashMapUsage x4, ReferenceEquality,
  UnusedVariable x2, JdkObsolete, FutureReturnValueIgnored x4) -- all fixed
- **JaCoCo**: coverage reporting (XML + HTML) and enforcement gate for `agentensemble-core`:
  LINE >= 90%, BRANCH >= 75% (current: 94.1% line, 81.4% branch); wired into `check`
- **Codecov**: coverage uploaded on every CI run via `codecov-action@v5`; `codecov.yml`
  configures auto threshold (project) and 80% target for new code (patch)
- **Pre-commit hook**: `.githooks/pre-commit` runs `spotlessApply` on staged Java/Kotlin
  files and re-stages any reformatted files so commits always contain formatted code;
  activated via `./gradlew setupGitHooks`

### Fixed (Error Prone findings)
- `Ensemble.java`: `Map<Task, Task>` -> `IdentityHashMap<Task, Task>` (IdentityHashMapUsage)
- `TaskDependencyGraph.java`: field types `Map<Task, List<Task>>` -> `IdentityHashMap<Task, List<Task>>` (IdentityHashMapUsage x2)
- `ParallelWorkflowExecutor.java`: `Map<Task, AtomicInteger>` -> `IdentityHashMap<Task, AtomicInteger>` (IdentityHashMapUsage); `LinkedList<>` -> `ArrayList<>` (JdkObsolete); `executor.submit` -> `var unused = executor.submit` (FutureReturnValueIgnored)
- `AgentExecutor.java`: removed unused `boolean verbose` parameter from `executeWithTools` method signature (UnusedVariable)
- `Task.java`: added `@SuppressWarnings("ReferenceEquality")` to `validateContext` -- identity comparison is intentional (two Agent objects with identical fields are distinct agents)
- `ShortTermMemoryTest.java`: three `executor.submit` -> `var unused = executor.submit` (FutureReturnValueIgnored)
- `JsonSchemaGeneratorTest.java`: removed unused local variables `trailingCommaPos` and `activePos` (UnusedVariable)

---


### Added (GitHub Pages documentation site)
- `mkdocs.yml`: MkDocs Material site configuration; nav mirrors `docs/index.md` structure;
  brand logo (`assets/logo.svg`), favicon (`assets/favicon.svg`), light/dark toggle,
  custom color scheme, code copy buttons, search
- `docs/stylesheets/custom.css`: brand color overrides for both light and dark schemes;
  header/tabs use the brand gradient (#2DD4FF -> #4D95FF -> #8363F9)
- `docs/assets/logo.svg`: copy of `assets/brand/agentensemble-logo-mark.svg`
- `docs/assets/favicon.svg`: copy of `assets/brand/agentensemble-favicon-32.svg`
- `docs/requirements.txt`: `mkdocs-material` dependency for CI install
- `.github/workflows/docs.yml`: GitHub Actions workflow; triggers on push to main when
  `docs/**` or `mkdocs.yml` change, plus manual dispatch; deploys to GitHub Pages via
  `actions/deploy-pages@v4` (source: GitHub Actions, not branch)
- `.gitignore`: added `site/` (MkDocs build output)
- `docs/design/01-overview.md`: fixed broken `../../LICENSE` relative link -- changed to
  absolute GitHub URL so it resolves correctly from the published site

### One-time manual step required
- Enable GitHub Pages in repo Settings > Pages > Source: "GitHub Actions"
- Site will be live at: https://agentensemble.github.io/agentensemble/


### Changed
- Replaced manual `maven-publish` + `publishing {}` block in `agentensemble-core/build.gradle.kts`
  with `com.vanniktech.maven.publish` 0.29.0 (vanniktech plugin)
- `mavenPublishing {}` block targets `SonatypeHost.CENTRAL_PORTAL` with `signAllPublications()`
- Sources JAR and Javadoc JAR now generated automatically by the plugin (removed explicit `java { withSourcesJar(); withJavadocJar() }`)
- GitHub Packages repository retained as an additional publish target alongside Maven Central
- Release workflow updated: `publishAndReleaseToMavenCentral` task (auto-upload + auto-release)
  and `publishAllPublicationsToGitHubPackagesRepository` are now separate steps
- Release notes body simplified: no longer requires a custom Maven repository block since
  Maven Central is the default for both Gradle and Maven consumers
- Added `[plugins]` section to `gradle/libs.versions.toml` with `vanniktech-publish` entry

- Added `release-please-action@v4` (simple release type) in `.github/workflows/release-please.yml`
  - Watches main for Conventional Commits; opens/updates Release PRs with CHANGELOG entries
  - Merging a Release PR creates tag + GitHub Release (no manual `git tag` needed)
- Added `release-please-config.json` (simple type, root package)
- Added `.release-please-manifest.json` bootstrapped at `0.4.0`
- `release.yml` updated: removed `softprops/action-gh-release` (release-please owns the release);
  added `gh release upload` to attach JARs to the existing release; added post-release SNAPSHOT bump
  (patch-increments version in `gradle.properties`, commits `[skip ci]`, pushes to main)

### Required Secrets (add to GitHub repo settings before first Maven Central release)
- `ORG_GPG_SIGNING_KEY`: ASCII-armored GPG private key (`gpg --armor --export-secret-keys KEY_ID`)
- `ORG_GPG_SIGNING_PASSWORD`: GPG key passphrase
- `ORG_MAVEN_CENTRAL_USERNAME`: Sonatype Central Portal user token username
- `ORG_MAVEN_CENTRAL_PASSWORD`: Sonatype Central Portal user token password

---

## [0.8.0] - 2026-03-03 (Issue #58, feature/58-guardrails)

### Added
- `net.agentensemble.guardrail` package: `InputGuardrail` (`@FunctionalInterface`),
  `OutputGuardrail` (`@FunctionalInterface`), `GuardrailResult` (success/failure factory),
  `GuardrailInput` (record: taskDescription, expectedOutput, contextOutputs, agentRole),
  `GuardrailOutput` (record: rawResponse, parsedOutput, taskDescription, agentRole),
  `GuardrailViolationException` (extends `AgentEnsembleException`; carries `GuardrailType` enum,
  violationMessage, taskDescription, agentRole)
- `Task.inputGuardrails` field: `List<InputGuardrail>`, default empty immutable list
- `Task.outputGuardrails` field: `List<OutputGuardrail>`, default empty immutable list
- 64 new tests (499 -> 563): `GuardrailResultTest` (6), `GuardrailInputTest` (3),
  `GuardrailOutputTest` (3), `GuardrailViolationExceptionTest` (5),
  `ExceptionHierarchyTest` (+4), `TaskTest` (+7), `AgentExecutorTest` (+11),
  `GuardrailIntegrationTest` (8)
- `docs/guides/guardrails.md`: new guide (quick start, input/output guardrails, multiple
  guardrails, exception handling, callbacks integration, structured output, thread safety)

### Changed
- `AgentExecutor.execute()`: runs input guardrails before prompt building (before any LLM call);
  runs output guardrails after final response and after structured output parsing;
  throws `GuardrailViolationException` on first failure with full context
- `SequentialWorkflowExecutor`: catch clause extended to include `GuardrailViolationException`
  alongside `AgentExecutionException | MaxIterationsExceededException`; fires `TaskFailedEvent`
  before wrapping in `TaskExecutionException`
- `docs/guides/tasks.md`: Guardrails section added
- `docs/reference/task-configuration.md`: `inputGuardrails` and `outputGuardrails` rows added
- `docs/reference/exceptions.md`: `GuardrailViolationException` section added
- `docs/getting-started/concepts.md`: Guardrails concept section added
- `docs/design/13-future-roadmap.md`: Phase 8 (Guardrails) marked COMPLETE; Phase 9 for remaining
- `mkdocs.yml`: Guardrails guide added to Guides nav
- `README.md`: Guardrails section, Task Configuration table updated, roadmap updated

### Technical Notes
- First-failure semantics: guardrails evaluated in order; first failure stops evaluation
- Input guardrails run before prompts are built -- no LLM calls when input guardrail fails
- Output guardrails run after structured output parsing (parsedOutput available in GuardrailOutput)
- GuardrailViolationException is propagated as cause of TaskExecutionException (consistent pattern)
- ParallelTaskCoordinator already catches all Exception so parallel workflow handles guardrails correctly

---

## [0.7.0] - 2026-03-03 (Issue #57, feature/57-callbacks-execution-context)

### Added
- `net.agentensemble.execution.ExecutionContext`: immutable value bundling `MemoryContext`,
  `verbose`, and `List<EnsembleListener>`; factory methods `of(mc, verbose, listeners)`,
  `of(mc, verbose)`, `disabled()`; fire methods catch per-listener exceptions at WARN
- `net.agentensemble.callback` package: `EnsembleListener` interface (4 default no-op methods),
  `TaskStartEvent`, `TaskCompleteEvent`, `TaskFailedEvent`, `ToolCallEvent` records
- `net.agentensemble.agent.ToolResolver`: package-private class extracted from `AgentExecutor`;
  resolves mixed `AgentTool` + `@Tool`-annotated object lists into `ResolvedTools`
- `Ensemble.listeners` field (`@Singular List<EnsembleListener>`); builder convenience
  methods `onTaskStart(Consumer)`, `onTaskComplete(Consumer)`, `onTaskFailed(Consumer)`,
  `onToolCall(Consumer)` -- each wraps lambda in anonymous `EnsembleListener`
- `docs/guides/callbacks.md`: new guide (quick start, event types, thread safety, examples)
- 59 new tests (440 -> 499): `ExecutionContextTest` (20), `EnsembleListenerTest` (10),
  `ToolResolverTest` (10), `CallbackIntegrationTest` (14), `EnsembleTest` (+7 listener builder)

### Changed
- `WorkflowExecutor.execute()`: `(List<Task>, boolean, MemoryContext)` -> `(List<Task>, ExecutionContext)`
- `AgentExecutor`: 3 overloads -> 2; fires `ToolCallEvent` after each tool execution in ReAct loop
- `DelegationContext`: replaced `memoryContext` + `verbose` with `ExecutionContext`;
  `create()`: `(peers, maxDepth, executionContext, executor)`; `getExecutionContext()` replaces old getters
- `DelegateTaskTool` constructor: `(agents, executor, executionContext, delegationContext)`
- `AgentDelegationTool.delegate()`: uses `delegationContext.getExecutionContext()` for execute call
- `SequentialWorkflowExecutor`, `ParallelWorkflowExecutor`, `HierarchicalWorkflowExecutor`:
  accept `ExecutionContext`; fire `TaskStartEvent`/`TaskCompleteEvent`/`TaskFailedEvent`
- `docs/design/13-future-roadmap.md`: Phase 7 marked COMPLETE; renamed old Phase 7 to Phase 8
- `mkdocs.yml`: Callbacks guide added to Guides nav

### Technical Notes
- `ExecutionContext.disabled()` is the backward-compat factory for tests and internal callsites
- `HierarchicalWorkflowExecutor` manager uses disabled memory + same listeners (meta-orchestrator)
- `ToolResolver` is package-private in `net.agentensemble.agent` (not part of public API)
- Parallel workflow `TaskStartEvent.taskIndex` is 0 (ordering not guaranteed in parallel)

---

## [Planned] Issue #20 Advanced Features -- Phase 7 Sub-Issues Created 2026-03-03

### Architecture Decisions Recorded

Issue #20 decomposed into 5 independently releasable sub-issues:

| Issue | Feature | Release |
|-------|---------|---------|
| #57 | Callbacks/Event Listeners + ExecutionContext refactor | v0.7.0 |
| #58 | Guardrails: Pre/post execution validation | v0.8.0 |
| #59 | Rate Limiting: Per-agent/per-LLM | v0.8.0 |
| #60 | Built-in Tool Library: agentensemble-tools module | v0.9.0 |
| #61 | Streaming Output: Token-by-token via StreamingChatLanguageModel | v1.0.0 |

Key architecture decisions:
- ExecutionContext (#57): replaces (MemoryContext, boolean verbose) params in
  WorkflowExecutor.execute() and AgentExecutor.execute() with a single context object;
  prerequisite for guardrails, streaming, and any future runtime extensibility
- Streaming (#61): decorator pattern -- Agent.streamingLlm optional field wraps a
  StreamingChatLanguageModel; tool-loop uses standard ChatModel; only final response is
  streamed via TokenEvent callbacks; preserves TaskOutput.raw contract
- Rate Limiting (#59): pure decorator -- RateLimitedChatModel wraps any ChatModel (zero
  changes to execution paths); token-bucket algorithm; thread-safe for parallel workflows;
  Agent.rateLimit() convenience auto-wraps at build time
- Built-in tools (#60): separate optional agentensemble-tools Gradle module; code
  execution (sandboxed) deferred due to security complexity
- Guardrails (#58): functional interfaces (InputGuardrail, OutputGuardrail) on Task;
  invoked in AgentExecutor before LLM call (input) and after response (output);
  throws GuardrailViolationException on failure

---

## [0.6.0] - 2026-03-03 (Issue #19, PR #48)

### Added
- `Task.outputType(Class<?>)`: optional field; when set, agent is instructed to produce
  JSON matching the schema derived from the class; output is automatically parsed after execution
- `Task.maxOutputRetries(int)`: number of retry attempts when structured output parsing fails;
  default 3; must be >= 0; 0 disables retries
- `TaskOutput.parsedOutput`: the parsed Java object (null when no outputType set)
- `TaskOutput.outputType`: the Class used for parsing (null when no outputType set)
- `TaskOutput.getParsedOutput(Class<T>)`: typed accessor; throws `IllegalStateException` when
  null or type mismatch
- `net.agentensemble.output.ParseResult<T>`: success/failure result container for parse attempts;
  public class; `success(T)`, `failure(String)`, `isSuccess()`, `getValue()`, `getErrorMessage()`
- `net.agentensemble.output.JsonSchemaGenerator`: reflection-based JSON-like schema description
  generator; `generate(Class<?>)`; supports records, POJOs, String, numeric wrappers, Boolean,
  List<T>, Map<K,V>, enums, nested objects; max nesting depth 5; scalar short-circuit via
  `topLevelScalarOrCollectionSchema()` (avoids introspecting JDK internals)
- `net.agentensemble.output.StructuredOutputParser`: JSON extraction (markdown fences first with
  non-greedy regex, then plain trimmed response, then first embedded block) and Jackson
  deserialization; scalar fallback in `parse()` for Boolean/Integer/String; `FAIL_ON_UNKNOWN_PROPERTIES=false`
- `net.agentensemble.exception.OutputParsingException`: extends `AgentEnsembleException`; thrown
  when all retries exhausted; carries `rawOutput` (last bad response), `outputType`, `parseErrors`
  (immutable list of per-attempt errors), `attemptCount`
- `AgentPromptBuilder`: `## Output Format` section injected into user prompt when outputType is set;
  prompt says "ONLY valid JSON matching this schema (object, array, or scalar as appropriate)"
- `AgentExecutor.parseStructuredOutput()`: retry loop after main execution; sends correction prompt
  to LLM on failure showing parse error and schema; throws `OutputParsingException` on exhaustion
- 82 new tests (358 -> 440): JsonSchemaGeneratorTest (23), StructuredOutputParserTest (20),
  ExceptionHierarchyTest (+5), TaskTest (+12), TaskOutputTest (+7), AgentPromptBuilderTest (+4),
  StructuredOutputIntegrationTest (11)
- `docs/examples/structured-output.md`: new two-example walkthrough (typed JSON + Markdown output)

### Changed
- `docs/guides/tasks.md`: Structured Output section (typed/Markdown examples, retry docs,
  supported types with scalar caveats)
- `docs/reference/task-configuration.md`: outputType/maxOutputRetries fields + validation table
- `docs/getting-started/concepts.md`: Task concept updated with outputType/maxOutputRetries
- `docs/design/03-domain-model.md`: Task and TaskOutput specs updated
- `docs/design/13-future-roadmap.md`: Phase 6 marked COMPLETE with implementation notes
- `README.md`: Structured Output section, Task Configuration table, roadmap updated

### Technical Notes
- Scalar support: Boolean/Integer/Long/Double respond with bare JSON values (e.g., `true`, `42`);
  String requires JSON-quoted output (e.g., `"text"`)
- JSON block extraction: non-greedy pattern finds first embedded block, not oversized span
- `OutputParsingException.rawOutput` carries the *last* bad response (currentResponse after retries),
  not the initial response -- enables effective debugging of retry chains

---

## [0.5.0] - 2026-03-02 (Issue #18, PR #45)

### Added
- `Workflow.PARALLEL`: DAG-based concurrent task execution using Java 21 virtual threads
  (`Executors.newVirtualThreadPerTaskExecutor()` -- stable API, no preview flags)
- `TaskDependencyGraph`: identity-based DAG from task context declarations;
  `getRoots()`, `getReadyTasks(completed)`, `getDependents(task)`, `isInGraph(task)`,
  `getAllTasks()`, `size()`; immutable (all state built in constructor)
- `ParallelWorkflowExecutor`: event-driven scheduler; `CountDownLatch(totalTasks)` for
  synchronization; MDC propagated from calling thread to each virtual thread;
  `skippedTasks` Set tracks transitively-skipped tasks for CONTINUE_ON_ERROR correctness
- `ParallelErrorStrategy` enum: `FAIL_FAST` (default) and `CONTINUE_ON_ERROR`
- `ParallelExecutionException`: thrown by CONTINUE_ON_ERROR for partial failures;
  carries `completedTaskOutputs` (List) + `failedTaskCauses` (Map<String,Throwable>)
- `Ensemble.parallelErrorStrategy` field (default `FAIL_FAST`); validated at run()
- `Ensemble.validateParallelErrorStrategy()`: fails if null and workflow is PARALLEL
- `Ensemble.validateContextOrdering()`: skips for PARALLEL (DAG drives order)
- Task list order is irrelevant for PARALLEL (unlike SEQUENTIAL)
- 61 new tests (297->358): TaskDependencyGraphTest (21), ParallelWorkflowExecutorTest (+17),
  ParallelEnsembleIntegrationTest (16), ShortTermMemoryTest (+3), ExceptionHierarchyTest (+5)

### Fixed
- `Ensemble.resolveTasks()`: pass-2 now updates `originalToResolved` when creating a
  context-rewritten Task so downstream tasks receive the final reference (fixes diamond
  pattern A->B->D, A->C->D producing stale object references in D's context list)
- `ParallelWorkflowExecutor.shouldSkip()` CONTINUE_ON_ERROR: added `skippedTasks` Set;
  check now includes both `failedTaskCauses` AND `skippedTasks` so transitive dependents
  in chains (A fails -> B skipped -> C was incorrectly run, now skipped) are correctly
  handled
- `ParallelErrorStrategy.FAIL_FAST` Javadoc: corrected inaccurate "cancel/interrupt
  running tasks" text; actual behavior is running tasks finish normally, only new tasks
  are not scheduled

### Changed
- `ShortTermMemory`: backing list changed from `ArrayList` to `CopyOnWriteArrayList`
  for thread-safe concurrent writes from parallel tasks
- `ShortTermMemory.getEntries()`: returns `List.copyOf(entries)` (immutable snapshot)
  instead of `Collections.unmodifiableList(entries)` (live view)
- `MemoryContext` Javadoc: updated to reflect thread-safe status

### Documentation
- `docs/guides/workflows.md`: PARALLEL section (DAG explanation, error strategies,
  thread safety, task list order, diamond pattern, choosing a workflow table updated)
- `docs/reference/ensemble-configuration.md`: added `parallelErrorStrategy` row
- `docs/design/10-concurrency.md`: full concurrent execution model; replaced
  "Phase 2+ considerations" with actual implementation details and JMM guarantees
- `docs/design/13-future-roadmap.md`: Phase 5 marked complete with implementation notes
- `docs/examples/parallel-workflow.md`: new competitive intelligence example
  (market research + financial analysis in parallel -> SWOT -> executive summary)
- `README.md`: Parallel Workflow section; updated Ensemble Configuration table;
  roadmap updated (v0.5.0 struck through)

---

## [0.5.0-SNAPSHOT] - 2026-03-02 (PR #43, fix/copilot-review-feedback)

### Fixed (Copilot PR #43 review -- commit 3064533)
- `Ensemble.validateContextOrdering`: switched to identity-based membership sets
  (IdentityHashMap-backed executedSoFar + ensureTaskSet) to be consistent with
  resolveTasks() and validateAgentMembership(); prevents value-equal but identity-distinct
  context tasks from passing validation but failing template remapping
- `ToolResult.failure` Javadoc: updated from "must not be null" to "null is normalized to
  a default message" to accurately reflect the normalization already in place
- `MemoryContextTest.testRecord_withoutLongTerm_doesNotCallStore`: replaced vacuous
  verify(mock, never()) test (unwired mock; always passed regardless of behavior) with
  assertion-based test verifying observable state (hasLongTerm() false, STM recorded,
  queryLongTerm returns empty)
- `TaskOutput`: added Lombok @NonNull to raw, taskDescription, agentRole, completedAt, and
  duration to match design spec (docs/design/03-domain-model.md); updated three
  null-permitting tests to expect NullPointerException

### Fixed (Bug)
- `Ensemble.resolveTasks`: two-pass approach remaps context list references to resolved Task
  instances (fixes spurious TaskExecutionException when using template variables with context
  dependencies -- value equality of resolved vs original tasks diverges)

### Fixed (Null Safety)
- `AgentDelegationTool.delegate`: null/blank agentRole/taskDescription validated early
- `DelegateTaskTool`: null/blank param validation; null memoryContext normalized to disabled()
- `EmbeddingStoreLongTermMemory.store`: null content rejected; null timestamp defaulted to Instant.now()
- `AgentExecutor.execute`: null memoryContext normalized to MemoryContext.disabled()
- `AgentPromptBuilder`: null-guard ctx.getRaw() in context rendering
- `LangChain4jToolAdapter.convertToType`: primitive defaults for null value (prevents Method.invoke NPE)
- `Task.build`: null context elements throw ValidationException; self-referencing context detected
- `Agent.build`: null responseFormat normalized to empty string
- `ToolResult.failure`: null errorMessage normalized to default message
- `EnsembleMemory`: longTermMaxResults > 0 validated conditionally (only when longTerm != null)
- `Ensemble.validate`: managerMaxIterations > 0 validated for HIERARCHICAL workflow

### Fixed (Correctness)
- `AgentDelegationTool`: MDC save/restore for nested delegation chains (A->B->C)
- `HierarchicalWorkflowExecutor`: manager failure wrapped in TaskExecutionException with partial outputs
- `Ensemble`: reserved "Manager" role and duplicate roles validated for HIERARCHICAL
- `Ensemble.validateAgentMembership`: IdentityHashMap for identity-based agent lookup (per design spec)
- `Ensemble.validateContextOrdering`: distinguishes missing-task from ordering-violation messages
- `AgentExecutor` toolCallCounter: increments only on executed calls (not stop-message path)
- `Ensemble.run`: ValidationException logged at WARN; runtime failures at ERROR with throwable
- `MemoryContext.isActive`: returns true only when at least one memory type is genuinely active

### Changed (Code Quality)
- `AgentExecutor`: logs effective tool count (post-delegation-injection) instead of configured count
- `AgentExecutor`: tool errors logged at WARN (result starts with "Error:"); successes at INFO
- `AgentExecutor.ResolvedTools.execute`: removed unused originalTools parameter
- `DelegationContext` Javadoc: clarified thread-safety limitation (mutable referenced components)
- `LangChain4jToolAdapter`: throwable included in WARN log for tool execution exceptions
- `TemplateResolver`: UUID-embedded sentinel prefix; restore regex precompiled as static final
- `TaskExecutionException`: no-cause constructor delegates to with-cause constructor
- `AgentPromptBuilder`: stripTrailing() on system prompt; context block separator fixed (no double ---)

### Changed (Documentation)
- quickstart.md, installation.md, logging.md: logback version 1.5.12 -> 1.5.32
- template-variables.md: variable names support letters/digits/underscores only (no hyphens)
- workflows.md: context ordering validated at run(), not at build() time
- Task.java Javadoc: build-time vs run-time validation split clarified

### Added (Tests, 287 -> 297)
- EnsembleTest: 4 new validation tests (HIERARCHICAL reserved role, duplicate roles, managerMaxIterations=0, missing context task); renamed testRun_withMutualContextDependency to testRun_withForwardContextReference
- TaskTest: self-reference and null context element validation tests
- TaskOutputTest: null field behavior documentation tests and default toolCallCount test (updated to NPE assertions after @NonNull added)
- MemoryContextTest: replaced vacuous mock test with observable-state assertions
- AgentTest, AgentDelegationToolTest: assertion updates for changed messages

### Fixed (CI)
- ci.yml: !contains(needs.*.result, 'skipped') guard added to dependabot-automerge
- ci.yml: auto-merge branch protection requirement documented in script
- dependabot.yml: groups config added for github-actions ecosystem

---

## [0.4.0] - 2026-03-02

### Added
- Agent delegation (`net.agentensemble.delegation` package):
  - `DelegationContext`: immutable runtime state per delegation chain; `create()` factory
    (peerAgents, maxDepth, memoryContext, agentExecutor, verbose); `descend()` returns
    child with depth+1; `isAtLimit()` when currentDepth >= maxDepth
  - `AgentDelegationTool`: `@Tool`-annotated; auto-injected into agent tool list at
    execution time when `allowDelegation=true`; guards: depth limit, self-delegation,
    unknown role; accumulates `delegatedOutputs`; MDC keys `delegation.depth` and
    `delegation.parent` set during delegated executions
- `AgentExecutor.execute(Task, List, boolean, MemoryContext, DelegationContext)`:
  5-arg overload; `buildEffectiveTools()` prepends `AgentDelegationTool` when applicable;
  4-arg backward-compat overload passes null delegationContext
- `Ensemble.maxDelegationDepth` field (default 3; validated > 0 at run time)
- `SequentialWorkflowExecutor(List<Agent>, int maxDelegationDepth)`: 2-arg constructor;
  creates root `DelegationContext` per run; passes to AgentExecutor
- `HierarchicalWorkflowExecutor(ChatModel, List<Agent>, int managerMaxIterations, int maxDelegationDepth)`:
  4-arg constructor; creates `workerDelegationContext` for worker peer delegation;
  passes to `DelegateTaskTool`
- `DelegateTaskTool(List<Agent>, AgentExecutor, boolean, MemoryContext, DelegationContext)`:
  5-arg constructor; threads `delegationContext` to worker `AgentExecutor.execute()` calls
- 36 new tests: `DelegationContextTest` (16), `AgentDelegationToolTest` (14),
  `DelegationEnsembleIntegrationTest` (10); updated `DelegateTaskToolTest` and
  `HierarchicalWorkflowExecutorTest` for new constructors
- 287 total tests passing
- Comprehensive user documentation: 21 new files in `docs/`:
  - `docs/index.md`
  - `docs/getting-started/`: installation, quickstart, concepts
  - `docs/guides/`: agents, tasks, workflows, tools, memory, delegation,
    error-handling, logging, template-variables
  - `docs/reference/`: agent-configuration, task-configuration, ensemble-configuration,
    memory-configuration, exceptions
  - `docs/examples/`: research-writer, hierarchical-team, memory-across-runs
- README updated: Agent Delegation section, updated Agent/Ensemble config tables with
  allowDelegation and maxDelegationDepth, docs index section, v0.4.0 marked complete

### Technical Notes
- Delegation tool method name is `delegate` (not `delegateTask`) -- disambiguated from
  the Manager's `delegateTask` tool in hierarchical workflow
- `DelegationContext` is immutable (all-final fields, no setters) -- thread-safe
- The `SequentialWorkflowExecutor` no-arg constructor is removed; all callers
  now pass `agents` and `maxDelegationDepth`

---

## [0.3.0] - 2026-03-02

### Added
- Memory system (`net.agentensemble.memory` package) with three types:
  - **Short-term**: `ShortTermMemory` accumulates all task outputs per run; injected into subsequent agent prompts automatically (replaces explicit context section when enabled)
  - **Long-term**: `LongTermMemory` interface + `EmbeddingStoreLongTermMemory` implementation; uses LangChain4j `EmbeddingStore` + `EmbeddingModel`; embeds outputs on store, retrieves by semantic similarity before each task
  - **Entity**: `EntityMemory` interface + `InMemoryEntityMemory` (ConcurrentHashMap-backed); user-seeded key-value facts injected into every agent prompt
- `EnsembleMemory`: builder-pattern config object; requires at least one memory type enabled; `longTermMaxResults` (default 5)
- `MemoryContext`: runtime state holder per `Ensemble.run()` call; `disabled()` no-op singleton; `from(EnsembleMemory)` creates fresh STM per call
- `Ensemble.memory` field: optional `EnsembleMemory`; creates `MemoryContext` at start of `run()`
- `AgentPromptBuilder.buildUserPrompt(Task, List<TaskOutput>, MemoryContext)`: injects Short-Term Memory, Long-Term Memory, Entity Knowledge sections; backward-compat 2-arg overload retained
- `AgentExecutor.execute(Task, List<TaskOutput>, boolean, MemoryContext)`: injects memories before execution; records output after; backward-compat 3-arg overload retained
- `WorkflowExecutor` interface updated to `execute(List<Task>, boolean, MemoryContext)`
- `SequentialWorkflowExecutor`, `HierarchicalWorkflowExecutor`, `DelegateTaskTool` updated to accept and thread `MemoryContext`
- 77 new tests: `MemoryEntryTest` (5), `ShortTermMemoryTest` (8), `EmbeddingStoreLongTermMemoryTest` (9), `InMemoryEntityMemoryTest` (15), `EnsembleMemoryTest` (10), `MemoryContextTest` (22), `MemoryEnsembleIntegrationTest` (8)
- 251 total tests passing

### Technical Notes
- `EmbeddingModel.embed(String)` in LangChain4j 1.11.0 returns `Response<Embedding>` (Response wrapper NOT dropped unlike ChatModel)
- Manager agent in hierarchical workflow uses `MemoryContext.disabled()` (meta-orchestrator, not a worker)
- Entity memory is user-seeded; no automatic LLM-based entity extraction in this release

---

## [0.2.0] - 2026-03-02

### Changed
- Repackaged from `io.agentensemble` to `net.agentensemble` (agentensemble.net registered; .io was unavailable)
- Maven group coordinate updated: `net.agentensemble:agentensemble-core`
- All source directories, package declarations, imports, config files, and docs updated
- `validateContextOrdering()` in `Ensemble` skips for HIERARCHICAL workflow (manager handles ordering)

### Added
- Hierarchical workflow (`Workflow.HIERARCHICAL`): Manager agent delegates tasks to workers via `delegateTask` tool, synthesizes final result
- `DelegateTaskTool`: `@Tool`-annotated tool for worker delegation, case-insensitive role matching, collects `TaskOutput`s
- `ManagerPromptBuilder`: builds manager system prompt (worker list) and user prompt (task list)
- `HierarchicalWorkflowExecutor`: implements `WorkflowExecutor`, creates virtual Manager at runtime
- `Ensemble.managerLlm`: optional field, defaults to first agent's LLM
- `Ensemble.managerMaxIterations`: configurable manager iteration limit, default 20
- `-parameters` compiler flag in root `build.gradle.kts` for `@Tool` parameter name reflection
- 49 new tests: `DelegateTaskToolTest` (16), `ManagerPromptBuilderTest` (14), `HierarchicalWorkflowExecutorTest` (10), `HierarchicalEnsembleIntegrationTest` (9)
- 174 total tests passing

---

## [0.1.0] - 2026-03-02

### Added
- Release pipeline: tag-triggered GitHub Actions workflow publishes to GitHub Packages
- `maven-publish` plugin on `agentensemble-core` with full POM metadata (name, description, URL, MIT license, developer, SCM, issue management)
- Sources JAR and Javadoc JAR generated as part of every build
- GitHub Packages repository configured (`https://maven.pkg.github.com/AgentEnsemble/agentensemble`)
- Enhanced CI: `--continue` flag collects all test results on failure, `publish-unit-test-result-action` reports inline on PRs, `dependency-submission` job feeds GitHub dependency graph
- Phase 1 complete: full sequential multi-agent orchestration framework
- `Agent`: immutable value object with role, goal, background, tools, llm, verbose, maxIterations, responseFormat
- `Task`: immutable value object with description, expectedOutput, agent, context
- `Ensemble`: orchestrator with validation, template resolution, workflow execution
- `Workflow`: SEQUENTIAL (implemented), HIERARCHICAL (Phase 2)
- `EnsembleOutput`, `TaskOutput`: immutable result value objects
- `AgentExecutor`: ReAct-style tool-calling loop via LangChain4j 1.11.0 ChatModel API
- `SequentialWorkflowExecutor`: sequential task execution with MDC logging
- `AgentPromptBuilder`: system/user prompt construction
- `TemplateResolver`: {variable} substitution with escaped {{}} support
- `AgentTool` interface + `LangChain4jToolAdapter`: dual tool paths (AgentTool + @Tool)
- `ToolResult`: success/failure factory methods
- Full exception hierarchy (7 exception classes, all unchecked)
- SLF4J logging with MDC (ensemble.id, task.index, agent.role)
- 126 unit + integration tests, all passing
- `ResearchWriterExample`: two-agent research + writing workflow
- Comprehensive README with quickstart, API docs, tool guide, logging guide
- CI workflow (GitHub Actions) with Dependabot auto-merge
- 20 GitHub issues tracking Phase 1-5 roadmap

### Technical Notes
- Built on LangChain4j 1.11.0 (ChatModel, ChatRequest, ChatResponse API)
- Java 21, Gradle 9.3.1 with Kotlin DSL
- Version catalog (gradle/libs.versions.toml) for centralized dependency management

