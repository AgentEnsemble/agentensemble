# Active Context

## Current Work

Branch `feature/186-phases` -- Phase-level workflow grouping and parallel phase execution
(Issue #186). Implementation complete, full CI build passing. Ready for PR review.

## Completed This Session (Phases -- complete implementation)

**7 commits on `feature/186-phases`:**

### Docs pass (commits 1-3):
- GitHub issue #186 created with full acceptance criteria
- Design doc `docs/design/19-phases.md`
- Guide `docs/guides/phases.md`
- Example page `docs/examples/phases.md`
- Reference update `docs/reference/ensemble-configuration.md`
- `PhasesExample.java` + `runPhases` Gradle task
- `mkdocs.yml` navigation updated

### Implementation pass (commits 4-7):
- **`Phase`** domain object (`net.agentensemble.workflow.Phase`) -- `@Builder @Getter`
  with `name`, `tasks` (`@Singular`), `workflow` (optional), `after` (predecessors, managed
  manually to avoid Lombok conflict). `Phase.of(String, Task...)` and
  `Phase.of(String, List<Task>)` static factories. `PhaseBuilder.build()` validates name,
  tasks, and rejects HIERARCHICAL workflow.
- **`Ensemble.phase(Phase)`** and **`Ensemble.phase(String, Task...)`** builder methods.
  `phases` field managed by `EnsembleBuilder` (not `@Singular` to avoid Lombok naming
  conflict with the varargs convenience method).
- **`EnsembleValidator`** phase rules: rejects mixed task+phase, unique names,
  acyclic DAG (DFS), tasks-have-LLM per phase.
- **`PhaseDagExecutor`** (`public class`, `net.agentensemble.workflow`): BiFunction-based
  phaseRunner API; ConcurrentHashMap + CountDownLatch + virtual threads; prior-outputs
  snapshot passed to each phase runner for cross-phase context() resolution; failure
  cascades transitively to dependents; global task output map updated after each phase.
- **`Ensemble.executePhases()`** per-phase runner: resolves template vars
  (`resolveTasksFromList` with identity-preservation when no templates change),
  synthesizes agents, selects workflow executor, calls `executeSeeded()`.
- **`SequentialWorkflowExecutor.executeSeeded()`**: public method that pre-seeds
  `completedOutputs` from prior phase outputs; returns only current-phase task outputs
  (not seed outputs) to prevent duplicates in the aggregated list.
- **`EnsembleOutput.phaseOutputs`**: `Map<String, List<TaskOutput>>` field populated
  by `executePhases`; `getPhaseOutputs()` returns empty map for flat-task ensembles.
- **`PhaseTest`**: 24 unit tests for Phase builder validation and static factories.
- **`PhaseIntegrationTest`**: 12 integration tests (all pass with deterministic handlers,
  no LLM needed): sequential phases, parallel phases, kitchen convergent scenario,
  cross-phase context, failure propagation, per-phase workflow override, callbacks,
  validation errors.

**Key bugs found and fixed during testing:**
- `SequentialWorkflowExecutor.executeSeeded()` incorrectly included seed outputs in
  the returned task list, causing duplicates in PhaseDagExecutor's aggregated list.
  Fixed: only tasks in `resolvedTasks` are included in the returned output.
- `resolveTasksFromList()` was creating new Task objects even when no template vars
  were substituted, breaking identity-based cross-phase context lookup. Fixed: preserve
  original task identity when description/expectedOutput are unchanged.

## Previously Completed (Heartbeat Scheduler Leak Fix)

Branch `fix/web-dashboard-heartbeat-scheduler-leak` (PR #184) -- all merged to main.

## Key Design Decisions (Phases, Issue #186)

- **Phase** is a named group of tasks; it does NOT modify Task
- **Phase DAG**: phases declare dependencies via `.after(otherPhase)`. Independent
  phases run in parallel; dependents wait for all predecessors to complete
- **Backward compatible**: flat `.task()` ensembles are unchanged; cannot mix tasks and phases
- **Per-phase workflow**: each phase can override the ensemble-level workflow strategy
  (HIERARCHICAL not permitted per-phase in v1)
- **Cross-phase context**: tasks in later phases may reference tasks in predecessor phases
  via existing `Task.context()` mechanism; resolved by seeding completedOutputs map
- **Identity preservation**: `resolveTasksFromList()` preserves original Task identity when
  no templates change -- critical for identity-based cross-phase context lookup
- **PhaseDagExecutor**: reuses existing workflow executors per phase via BiFunction API;
  same virtual-thread pattern as ParallelWorkflowExecutor

## Next Steps

- Open PR for `feature/186-phases` branch
- After merge, update `docs/reference/ensemble-configuration.md` with `getPhaseOutputs()`
  in the EnsembleOutput accessor table

## Important Patterns and Preferences

### Phases (Issue #186)
- `Phase.of("name", task1, task2)` -- static factory
- `Phase.builder().name(...).task(...).after(otherPhase).workflow(Workflow.PARALLEL).build()`
- `Ensemble.builder().phase(p1).phase(p2).build()` -- registers phases, cannot mix with `.task()`
- `EnsembleOutput.getPhaseOutputs()` returns `Map<String, List<TaskOutput>>` -- empty for flat tasks
- `SequentialWorkflowExecutor.executeSeeded(tasks, ctx, priorOutputs)` -- cross-phase seeding
- Package: `net.agentensemble.workflow` for Phase, PhaseDagExecutor; `net.agentensemble.ensemble` for EnsembleOutput
