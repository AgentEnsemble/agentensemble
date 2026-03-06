# Active Context

## Current Work Focus

Issues #111 and #112 (v2.0.0 Partial Results and Workflow Inference) are complete on
branch `feat/111-112-partial-results-workflow-inference`. Two commits pushed:
- `562e09c` -- feat(111): EnsembleOutput partial results and graceful exit-early
- `500cd91` -- feat(112): Workflow inference from task context declarations

PR ready to open against main.

## Recent Changes

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
