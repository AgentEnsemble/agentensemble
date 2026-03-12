# 19. Phase-Level Workflow Grouping and Parallel Phase Execution

**Target release:** v0.x (Issue #186)

Phases allow tasks to be grouped into named, independently-executable workstreams. Phase
dependencies form a DAG: independent phases run in parallel, and a phase only starts once
all of its declared predecessors have completed. Within each phase, tasks execute according
to that phase's (or the ensemble's) workflow strategy.

---

## 1. Problem Statement

The existing flat task list with context-chaining handles sequential pipelines well, but
cannot express parallel workstreams where each workstream has its own internal task sequence.

**Example:** Three diners order different main courses. Each main course has its own
sequential preparation steps (prep, cook, plate), but all three are prepared simultaneously
and served together once all are ready. The flat task list has no way to express "these
three groups run concurrently."

The context-chaining mechanism (`Task.context(otherTask)`) creates task-level dependencies
but does not provide a named grouping or a barrier concept. A developer wanting parallel
workstreams today must structure every task with explicit cross-task dependencies and rely
on the parallel workflow executor's graph inference -- which works, but loses the semantic
grouping and makes intent less clear.

---

## 2. Goals

1. Introduce a `Phase` value object that groups named tasks into a logical workstream.
2. Allow phases to declare dependencies on other phases, forming a DAG.
3. Execute independent phases concurrently; block dependent phases until predecessors complete.
4. Keep the existing flat `.task()` API fully backward compatible.
5. Allow each phase to override the ensemble-level workflow strategy.
6. Surface phase grouping in `EnsembleOutput`, `ExecutionTrace`, and the live dashboard.

---

## 3. Non-Goals

- Nested phases (phases within phases) -- the DAG is one level deep.
- Phase-level guardrails, rate limits, or listeners (post-v1, use task-level equivalents).
- Modifying `Task`, `Agent`, or `WorkflowExecutor` -- all changes are additive.
- Hierarchical workflow per-phase -- excluded from v1 due to Manager agent complexity.

---

## 4. API Design

### 4.1 Phase Value Object

```java
// Minimal
Phase research = Phase.of("research", gatherTask, analyzeTask);

// Full builder
Phase research = Phase.builder()
    .name("research")
    .task(gatherSourcesTask)
    .task(analyzeDataTask)
    .workflow(Workflow.PARALLEL)   // optional: overrides ensemble-level workflow
    .build();

// Phase with dependency -- will not start until "research" completes
Phase writing = Phase.builder()
    .name("writing")
    .after(research)               // one or more predecessor phases
    .task(outlineTask)
    .task(draftTask)
    .build();
```

### 4.2 Ensemble Builder

```java
Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(research)
    .phase(writing)
    .build();

// Convenience varargs overload
Ensemble.builder()
    .chatLanguageModel(llm)
    .phase("research", gatherTask, analyzeTask)
    .phase("writing", outlineTask, draftTask)
    .build();
```

### 4.3 Backward Compatibility

Flat `.task()` ensembles continue to work exactly as before. The two styles cannot be
mixed on the same ensemble builder -- validation will reject it.

```java
// Still works -- no phases, no change
Ensemble.builder()
    .chatLanguageModel(llm)
    .task(task1)
    .task(task2)
    .build();
```

---

## 5. Phase Domain Model

```
Phase
  name       : String          (required, unique within ensemble)
  tasks      : List<Task>      (required, non-empty)
  workflow   : Workflow         (optional, null = inherit from ensemble)
  after      : List<Phase>     (optional, predecessors in dependency graph)
```

`Phase` is an immutable Lombok `@Value @Builder`. It lives in
`net.agentensemble.workflow.Phase`.

### 5.1 Static Factory

```java
public static Phase of(String name, Task... tasks) { ... }
public static Phase of(String name, List<Task> tasks) { ... }
```

### 5.2 Builder Notes

- `name` must not be null or blank.
- `tasks` must contain at least one task.
- `after` stores phase references by identity, not by name. Cycle detection happens in
  `EnsembleValidator` at build time.
- `workflow` may not be `HIERARCHICAL` (validation rejects it -- Manager agent cannot be
  scoped to a phase in v1).

---

## 6. Execution Model

### 6.1 Phase DAG

A phase with no `after()` declarations is a **root phase**. Root phases start immediately
and run in parallel. A non-root phase starts as soon as all of its predecessors have
completed successfully.

```
Example: Kitchen scenario
  [steak]  ----\
  [salmon] -----+--> [serve]
  [pasta]  ----/

steak, salmon, pasta: root phases (start immediately, run in parallel)
serve: non-root phase (starts when steak + salmon + pasta all complete)
```

```
Example: Research pipeline
  [research] --> [analysis] --\
                               +--> [report] --> [review]
  [data-gathering] -----------/

research, data-gathering: root phases (parallel)
analysis: after(research)
report: after(analysis, data-gathering)
review: after(report)
```

### 6.2 Within-Phase Execution

Each phase delegates to a standard `WorkflowExecutor` instance for its internal tasks:

- `SEQUENTIAL` (default): tasks execute one after another in declaration order.
- `PARALLEL`: tasks execute concurrently, respecting their `context()` dependency graph.

The workflow used is: `phase.getWorkflow() != null ? phase.getWorkflow() : ensemble.getWorkflow()`.

`HIERARCHICAL` is explicitly disallowed per-phase (see Non-Goals).

### 6.3 Cross-Phase Context

Tasks in a later phase may reference tasks from earlier phases via the existing
`Task.context(otherTask)` mechanism. Because the phase DAG guarantees ordering, a task
in "report" can safely call `context(summarizeTask)` -- "research" is guaranteed complete
before "report" starts. The executor resolves context outputs from the accumulated shared
output map that grows as phases complete.

### 6.4 Error Handling

If a task in phase N fails:

- `SEQUENTIAL` within the phase: phase N fails immediately (existing behavior).
- `PARALLEL` within the phase: existing `ParallelErrorStrategy` applies.
- When a phase fails, all phases that (transitively) depend on it are skipped.
- Phases that do not depend on the failed phase continue running.
- `EnsembleOutput` records the failed phase; `ExecutionTrace` records skipped phases.

### 6.5 Exit-Early (Review Gate / HumanInputTool)

If any task raises `ExitEarlyException`, all running phases are interrupted and the ensemble
exits. This is the same behavior as today's parallel workflow executor.

---

## 7. PhaseDagExecutor

A new `PhaseDagExecutor` class handles phase-level orchestration. It is a `public` class
within `net.agentensemble.workflow`.

### 7.1 Algorithm

```
1. Build predecessor-count map and successor-adjacency map from Phase.after() declarations.
2. Submit all root phases (predecessorCount == 0) to a virtual-thread executor.
3. When a phase completes:
   a. Add its TaskOutput list to the shared completedOutputs map.
   b. For each successor: decrement predecessor count. If count reaches 0, submit it.
4. When a phase fails:
   a. Record the failure.
   b. Recursively mark all transitive successors as SKIPPED.
   c. Decrement the remaining-phases latch.
5. Block on a CountDownLatch until all phases have completed, failed, or been skipped.
6. If any phase failed: throw EnsembleExecutionException wrapping the first failure.
7. Build and return EnsembleOutput.
```

### 7.2 Per-Phase Execution

For each phase, `PhaseDagExecutor` selects the appropriate `WorkflowExecutor` and calls:

```java
WorkflowExecutor executor = selectExecutor(phase, ensemble);
List<Task> resolved = resolveTemplateVariables(phase.getTasks(), inputVariables);
EnsembleOutput phaseOutput = executor.execute(resolved, executionContext);
```

The `executionContext` passed to each phase includes the accumulated outputs from all
previously-completed phases so that cross-phase `context()` references resolve correctly.

### 7.3 Thread Safety

- Completed outputs are stored in a `ConcurrentHashMap<Phase, List<TaskOutput>>`.
- Predecessor counts are stored in a `ConcurrentHashMap<Phase, AtomicInteger>`.
- A single `CountDownLatch(phases.size())` tracks overall completion.
- A `ReentrantLock` serializes review-gate prompts (same pattern as `ParallelTaskCoordinator`).

---

## 8. Ensemble Changes

### 8.1 New Fields

```
Ensemble
  phases : List<Phase>   -- added alongside existing tasks field
```

### 8.2 Builder

Two new builder methods are added to `Ensemble.EnsembleBuilder`:

```java
public EnsembleBuilder phase(Phase phase)
public EnsembleBuilder phase(String name, Task... tasks)
```

Calling both `.task()` and `.phase()` on the same builder is rejected at build time.

### 8.3 run() Dispatch

```java
if (!phases.isEmpty()) {
    // Phase path
    new PhaseDagExecutor(this).execute(phases, executionContext);
} else {
    // Existing flat-task path (unchanged)
    selectWorkflowExecutor().execute(resolvedTasks, executionContext);
}
```

---

## 9. EnsembleOutput Changes

New field (additive, backward compatible):

```
EnsembleOutput
  phaseOutputs : Map<String, List<TaskOutput>>  -- phase name -> task outputs
                                                   null/empty for flat-task ensembles
```

The flat `taskOutputs` list remains and is populated for both flat and phase-based runs
(phase runs accumulate all task outputs into the flat list as well).

---

## 10. ExecutionTrace Changes

New field (additive):

```
ExecutionTrace
  phases : List<PhaseTrace>   -- null/empty for flat-task ensembles

PhaseTrace
  name        : String
  status      : PhaseStatus   (COMPLETED, FAILED, SKIPPED)
  taskTraces  : List<TaskTrace>
  startedAt   : Instant
  completedAt : Instant
  duration    : Duration
```

---

## 11. Validation Rules

`EnsembleValidator` enforces:

| Rule | Error |
|---|---|
| Cannot mix `.task()` and `.phase()` | `ValidationException` at build time |
| Phase name must not be null or blank | `ValidationException` at build time |
| Phase name must be unique within ensemble | `ValidationException` at build time |
| Each phase must contain at least one task | `ValidationException` at build time |
| Phase DAG must be acyclic | `ValidationException` at build time |
| Phase workflow must not be `HIERARCHICAL` | `ValidationException` at build time |
| Each task within a phase is valid per existing task rules | Existing validation |
| Cross-phase `context()` reference must point to a task in a predecessor phase | `ValidationException` at build time |

Cycle detection uses a depth-first traversal of the `after()` graph.

---

## 12. Edge Cases

| Scenario | Behavior |
|---|---|
| Single phase, no `after()` | Behaves like a flat-task ensemble with a named wrapper |
| Phase with a single task | Executes that task; workflow override ignored (no scheduling needed) |
| Cross-phase context to non-predecessor | Validation error at build time |
| Phase fails, unrelated phases running | Unrelated phases complete normally |
| Phase fails, dependents still pending | Dependents are skipped |
| All root phases fail | Ensemble fails; `EnsembleOutput` contains all failures |
| Deterministic handler tasks within a phase | Fully supported (no LLM needed for those tasks) |
| Phase with `PARALLEL` workflow and single task | Valid; runs the single task normally |
| Ensemble-level rate limit | Shared across all phases (existing bucket applies) |

---

## 13. Package and Class Structure

```
net.agentensemble.workflow
    Phase                   (new -- public value object)
    PhaseTrace              (new -- public value object, part of trace)
    PhaseStatus             (new -- public enum: COMPLETED, FAILED, SKIPPED)
    PhaseDagExecutor        (new -- package-private)

net.agentensemble
    Ensemble                (modified -- adds phases field + builder methods)
    EnsembleValidator       (modified -- adds phase validation rules)

net.agentensemble.ensemble
    EnsembleOutput          (modified -- adds phaseOutputs field)

net.agentensemble.trace
    ExecutionTrace          (modified -- adds phases field)
```

---

## 14. Testing Requirements

### Unit
- `Phase` builder: valid construction, null name, blank name, empty tasks, HIERARCHICAL workflow rejected
- Phase DAG cycle detection: simple cycle (A -> B -> A), transitive cycle (A -> B -> C -> A), no cycle
- `EnsembleValidator`: mixed task+phase, duplicate names, cross-phase context to non-predecessor
- `PhaseDagExecutor` unit tests with stub executors: root-only execution, diamond dependency, failure propagation, skip cascade

### Integration
- Sequential phases (phase 2 starts after phase 1 completes)
- Parallel independent phases (both start simultaneously)
- Convergent DAG (two parallel phases merge into one)
- Cross-phase context resolution
- Per-phase workflow override (parallel within one phase, sequential within another)
- Phase failure stops dependents but not independent phases
- Deterministic handler tasks within phases
- Exit-early (review gate) from within a phase

### Feature / E2E
- Kitchen scenario: steak + salmon + pasta in parallel, then serve
- Research pipeline: research + data-gathering in parallel, then analysis, then report, then review

---

## 15. Implementation Notes

- `Phase` is intentionally simple: no callbacks, no guardrails, no rate limits at the
  phase level in v1. These concerns are handled at the task level.
- `PhaseDagExecutor` follows the same virtual-thread pattern as `ParallelWorkflowExecutor`
  to ensure consistent thread management across the framework.
- The `after()` field on `Phase` stores references to other `Phase` instances (identity).
  The Ensemble builder assembles all phases before building, so forward references require
  the builder pattern.
- `PhaseTrace` is modeled after `TaskTrace` and uses the same JSON serialization approach
  for the live dashboard and `ExecutionTrace.toJson()`.
