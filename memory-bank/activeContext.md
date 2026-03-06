# Active Context

## Current Work Focus

Issue #126 (Tool-level approval gates via ReviewHandler) is complete on branch
`feat/126-tool-level-approval-gates`. Implementation threads `ReviewHandler` through
`ToolContext` so all `AbstractAgentTool` subclasses can call `requestApproval()` before
executing dangerous or irreversible actions.

## Recent Changes

### Issue #126: Tool-Level Approval Gates

**Infrastructure (agentensemble-core):**
- `ToolContext`: added `Object reviewHandler` field and 4-arg `of()` factory. Stored as `Object` to avoid class loading issues when `agentensemble-review` is absent from runtime classpath.
- `ToolResolver.resolve()`: extended to accept a `reviewHandler` parameter (4-arg overload); threads it into `ToolContext.of()` for each resolved `AbstractAgentTool`.
- `AgentExecutor`: passes `executionContext.reviewHandler()` to `ToolResolver.resolve()`.
- `AbstractAgentTool`: added `protected ReviewDecision requestApproval(String)`, `protected ReviewDecision requestApproval(String, Duration, OnTimeoutAction)`, `protected Object rawReviewHandler()`, `IllegalStateException` re-throw in `execute()`, and `static final ReentrantLock CONSOLE_APPROVAL_LOCK` to serialize concurrent console reviews.
- `LangChain4jToolAdapter.executeForResult()`: added `IllegalStateException` re-throw (configuration errors must not be silently converted to `ToolResult.failure()`).

**Built-in tool updates (agentensemble-tools):**
- `ProcessAgentTool`: added `requireApproval(boolean)` builder option. Requests approval before `ProcessBuilder.start()`. Edit replaces stdin input; ExitEarly returns failure without starting process.
- `FileWriteTool`: added `Builder` pattern with `requireApproval(boolean)`. Requests approval before `Files.writeString()`. Edit replaces file content; ExitEarly returns failure without writing.
- `HttpAgentTool`: added `requireApproval(boolean)` builder option. Requests approval before `httpClient.send()`. Edit replaces request body; ExitEarly returns failure without sending.
- All three tool modules: added `compileOnly(":agentensemble-review")` and `testImplementation(":agentensemble-review")` to build files.

**Tests:**
- `ToolContextTest` (new, 10 tests): factory with/without reviewHandler, accessor, null validation
- `AbstractAgentToolApprovalTest` (new, 18 tests): Continue/Edit/ExitEarly decisions, null handler auto-approves, custom timeout, ISE propagation, CONSOLE_APPROVAL_LOCK
- `ToolResolverTest` (extended, +5 tests): reviewHandler threaded from resolve() into ToolContext
- `ProcessAgentToolTest` (extended, +7 tests): approval enabled Continue/Edit/ExitEarly/disabled/no-handler matrix
- `FileWriteToolTest` (extended, +10 tests): same matrix + builder factory tests + content truncation
- `HttpAgentToolTest` (extended, +8 tests): same matrix + verify no HTTP send on ExitEarly
- `ToolApprovalIntegrationTest` (new, 6 tests): end-to-end with mock LLM + programmatic ReviewHandler; parallel approval

**Documentation:**
- `docs/guides/review.md`: new "Tool-Level Approval Gates" section
- `docs/guides/built-in-tools.md`: "Approval Gate" subsections for ProcessAgentTool, FileWriteTool, HttpAgentTool
- `docs/examples/human-in-the-loop.md`: "Tool-Level Approval" example section
- `docs/design/06-tool-system.md`: new "Tool-Level Approval Gates" architecture section

## Recent Changes

### Issue #113: MapReduceEnsemble task-first refactor

**MapReduceEnsemble.Builder -- new task-first API:**
- `mapTask(Function<T, Task>)` -- task-first map factory (no agent required); overloads existing `mapTask(BiFunction<T, Agent, Task>)`
- `reduceTask(Function<List<Task>, Task>)` -- task-first reduce factory; overloads existing `reduceTask(BiFunction<Agent, List<Task>, Task>)`
- `directTask(Function<List<T>, Task>)` -- task-first short-circuit factory; overloads existing `directTask(BiFunction<Agent, List<T>, Task>)`
- `chatLanguageModel(ChatModel)` -- default LLM for synthesised agents
- `mapAgent` and `reduceAgent` -- retained as optional power-user fields (backward compatible)

**Zero-ceremony factory:**
- `MapReduceEnsemble.of(model, items, mapDescription, reduceDescription)` -- builds and runs in one call

**Validation -- mutual exclusivity:**
- Each phase (map/reduce/direct) must use either task-first OR agent-first, never both
- Agent-first: both agent factory AND task factory must be set
- Task-first: task factory alone is sufficient

**Build logic:**
- `buildStatic()` uses `createMapTask(item)` / `createReduceTask(chunkTasks)` helpers that dispatch to the correct factory style
- Inner `Ensemble.builder().chatLanguageModel(chatLanguageModel)` set so `resolveAgents()` synthesises agents at run time
- `MapReduceAdaptiveExecutor` updated with same factory dispatch logic + `chatLanguageModel` propagated to each inner Ensemble

**Tests:**
- `MapReduceEnsembleTaskFirstTest` (17 unit tests): DAG structure, no explicit agents, context wiring, factory counts, chatLanguageModel passthrough, tools on task
- `MapReduceEnsembleTaskFirstValidationTest` (15 validation tests): mutual exclusivity, zero-ceremony factory validation
- `MapReduceEnsembleTaskFirstIntegrationTest` (12 integration tests): static/adaptive end-to-end, tools, per-task LLM, zero-ceremony, agent-first regression

**Documentation:**
- `docs/guides/map-reduce.md`: new "Task-first API (v2.0.0)" section, updated builder reference with task-first / agent-first tables
- `docs/examples/map-reduce.md`: new "Task-first examples (v2.0.0)" section with zero-ceremony and builder examples; existing agent-first examples moved under own heading
- `agentensemble-examples/.../MapReduceTaskFirstKitchenExample.java`: runnable example with zero-ceremony and task-first builder approaches
- `agentensemble-examples/build.gradle.kts`: `runMapReduceTaskFirstKitchen` Gradle task registered

### Issue #111: EnsembleOutput partial results and graceful exit-early

**ExitReason (extended):**
- TIMEOUT -- review gate timeout expired with onTimeout(EXIT_EARLY)
- ERROR -- unrecoverable exception terminated the pipeline
- (existing: COMPLETED, USER_EXIT_EARLY)

**ReviewDecision.ExitEarly (breaking change):**
- Changed from no-arg record to `ExitEarly(boolean timedOut)` record
- Factory `exitEarly()` returns `new ExitEarly(false)` (unchanged behavior)
- New factory `exitEarlyTimeout()` returns `new ExitEarly(true)`
- `ConsoleReviewHandler.handleTimeout()` with EXIT_EARLY now returns `exitEarlyTimeout()`

**ExitEarlyException (extended):**
- Added `boolean timedOut` field with `isTimedOut()` accessor
- 2-arg constructor `(message, timedOut)` + 1-arg defaults to false
- HumanInputTool propagates `exitEarly.timedOut()` when creating exception

**EnsembleOutput (new convenience API):**
- `isComplete()` -- true only when exitReason == COMPLETED
- `completedTasks()` -- alias for getTaskOutputs(); always safe to call
- `lastCompletedOutput()` -- Optional<TaskOutput> last element
- `getOutput(Task task)` -- identity-based Optional<TaskOutput> lookup
- `taskOutputIndex` internal field (Map<Task,TaskOutput>, excluded from equals/hashCode)
- All workflow executors now populate taskOutputIndex

**Ensemble.runWithInputs() fix:**
- After execution, remaps executor's agentResolved-keyed taskOutputIndex back to
  original task instances using positional correspondence:
  `tasks.get(i)` -> `agentResolvedTasks.get(i)` -> lookup in executor index

**Executor changes:**
- SequentialWorkflowExecutor: TIMEOUT vs USER_EXIT_EARLY distinction in all 3 gate paths
- ParallelWorkflowExecutor: full exit-early support via AtomicReference<ExitReason>
- ParallelTaskCoordinator: after-execution review gate, ExitEarlyException handling,
  HumanInputTool injection, shouldSkip() respects exit-early signal

### Issue #112: Workflow inference from task context declarations

**Ensemble.workflow (changed):**
- Field is now nullable (removed @Builder.Default); default is null
- When null, `resolveWorkflow(List<Task>)` infers PARALLEL if any task has context dep
  on another ensemble task, else SEQUENTIAL
- `selectExecutor(Workflow, List<Agent>)` takes explicit workflow
- Logs "Workflow inferred: X" when inference fires

**EnsembleValidator (updated):**
- Added `resolveWorkflow()` with same inference logic
- All workflow-specific validations take effective workflow as parameter
- `validateContextOrdering(effective)` skips for both inferred and explicit PARALLEL

**Tests updated:**
- EnsembleTest: `testDefaultWorkflow_isNullWhenNotSet`
- EnsembleValidationTest: forward-reference and ordering tests use explicit SEQUENTIAL

## Key Design Decisions (Issues #111/#112)

- `ReviewDecision.ExitEarly(boolean timedOut)` breaking change to record signature;
  code using `exitEarly()` factory is unaffected; direct `new ExitEarly()` needs the arg
- Identity-based task index: remapped in runWithInputs() using positional correspondence
  because resolveTasks() always creates new Task instances even for tasks without templates
- Workflow null default (not SEQUENTIAL) makes inference explicit; existing code with
  `.workflow(Workflow.SEQUENTIAL)` is unaffected
- Context ordering validation skipped for inferred PARALLEL (same as explicit PARALLEL):
  context deps with out-of-order declaration are valid in DAG-based execution

## Next Steps

- Open PR for feat/111-112-partial-results-workflow-inference -> main
- Continue with remaining v2.0.0 issues in epic #103

## Important Patterns and Preferences

### v2.0.0 EnsembleOutput API (Issue #111)
- `output.isComplete()` -- true only when all tasks ran to completion
- `output.getExitReason()` -- COMPLETED, USER_EXIT_EARLY, TIMEOUT, ERROR
- `output.completedTasks()` -- same as getTaskOutputs(); always safe
- `output.lastCompletedOutput()` -- Optional<TaskOutput> last completed
- `output.getOutput(researchTask)` -- identity-based lookup; pass the same Task instance
- taskOutputIndex is excluded from equals/hashCode/toString (implementation detail)

### v2.0.0 Workflow Inference (Issue #112)
- No `.workflow(...)` call -> framework infers at run time
- Tasks with no context deps -> SEQUENTIAL inferred
- Any task with context dep on another ensemble task -> PARALLEL inferred
- Explicit `.workflow(Workflow.X)` always wins over inference
- Context ordering validation only fires for explicit or inferred SEQUENTIAL
- `EnsembleValidator.resolveWorkflow()` and `Ensemble.resolveWorkflow()` share same logic

### v2.0.0 Review API (Issues #108/#109/#110)
- ReviewHandler + ReviewPolicy is the v2.0.0 review API
- Ensemble.builder().reviewHandler(ReviewHandler) + .reviewPolicy(ReviewPolicy)
- Task.builder().review(Review) / .beforeReview(Review)
- Review.required() / Review.skip() / Review.builder()
- HumanInputTool.of() for mid-task clarification (DURING_EXECUTION gate)
- EnsembleOutput.getExitReason() to check if pipeline stopped early
- agentensemble-review is optional; add to classpath only when review gates needed
- reviewPolicy field is NOT @Builder.Default (avoids ReviewPolicy class loading when
  review module is absent; null treated as NEVER by ExecutionContext)

### v2.0.0 Memory API (Issues #106/#107)
- MemoryStore + task scopes is the v2.0.0 primary memory API (replaces EnsembleMemory)
- Tasks declare scopes with .memory("name") -- framework auto-reads before and auto-writes after
- InMemoryStore: insertion order, most-recent retrieval, eviction supported
- EmbeddingMemoryStore: semantic similarity via LangChain4j, eviction is no-op
- MemoryEntry.metadata uses string map -- standard keys "agentRole" and "taskDescription"

### v2 Task-First API (Issues #104/#105)
- Task.of(description) -- zero-ceremony, default expectedOutput, no agent required
- Task.of(description, expectedOutput) -- zero-ceremony with custom output
- Ensemble.run(model, tasks...) -- static factory, single-line ensemble execution
- Ensemble.builder().chatLanguageModel(model) -- ensemble-level LLM for synthesis
- Ensemble.builder().agentSynthesizer(...) -- synthesis strategy (default: template)
- Task.builder().agent(Agent) -- explicit agent (power-user escape hatch)
- Task.builder().chatLanguageModel(ChatModel) -- per-task LLM override
- Task.builder().tools(List) -- per-task tools for synthesized agent
- Task.builder().maxIterations(int) -- per-task iteration cap

### Agent Resolution Order (in Ensemble.resolveAgents())
1. If task.getAgent() != null: use as-is
2. Else: synthesize using agentSynthesizer with (task-level LLM or ensemble LLM)
3. Apply task-level maxIterations and tools to synthesized agent
4. Set resolved agent on task via toBuilder()

## Previous Issues (complete)

### Issues #111, #112: Partial results and workflow inference (complete, branch committed)
### Issues #108, #109, #110: Human-in-the-loop review system (complete, PR #125)
### Issues #106, #107: agentensemble-memory module + task-scoped memory (complete, main)
### Issues #104, #105: Task-First Core + AgentSynthesizer SPI (complete, main)
### Issue #100: MapReduceEnsemble Short-Circuit Optimization (complete)
### Issue #99: Adaptive MapReduceEnsemble (complete)
