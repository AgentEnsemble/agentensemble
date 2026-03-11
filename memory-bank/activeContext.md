# Active Context

## Current Work

Branch `feature/186-phases` -- Phase-level workflow grouping and parallel phase execution
(Issue #186). Design, documentation, and example complete. Implementation work in progress.

## Completed This Session (Phases -- docs/design pass)

- **GitHub issue #186** created with full acceptance criteria
- **Branch** `feature/186-phases` created from `main`
- **Design doc** `docs/design/19-phases.md` -- complete spec covering Phase domain model,
  Phase DAG execution model, PhaseDagExecutor algorithm, Ensemble changes, EnsembleOutput
  changes, ExecutionTrace changes, validation rules, edge cases, package/class structure,
  and testing requirements
- **Guide** `docs/guides/phases.md` -- when to use, declaring phases, dependencies,
  cross-phase context, per-phase workflow override, error handling, comparison table
- **Example page** `docs/examples/phases.md` -- six runnable code examples covering
  sequential phases, parallel phases, kitchen scenario, per-phase workflow, diamond
  dependency, deterministic handler phases, and reading phase outputs
- **Reference update** `docs/reference/ensemble-configuration.md` -- added `phases` field
  to builder table, new Phase Configuration section with field table, validation rules,
  and static factory docs
- **Navigation** `mkdocs.yml` -- added Phases to Guides, Examples, and Design nav sections
- **Runnable example** `agentensemble-examples/src/main/java/.../PhasesExample.java` with
  three patterns (sequential deterministic, kitchen parallel, AI-backed parallel)
- **Gradle task** `runPhases` registered in `agentensemble-examples/build.gradle.kts`

## What Still Needs to Be Implemented (Phase Execution)

The following implementation work is planned on this branch:

1. `Phase` value object (`net.agentensemble.workflow.Phase`) -- `@Value @Builder` with
   `name`, `tasks`, `workflow` (optional), `after` (predecessor phases)
2. `PhaseStatus` enum (`COMPLETED`, `FAILED`, `SKIPPED`)
3. `PhaseTrace` value object
4. `Ensemble` -- add `phases` field + `phase()` builder methods; mutually-exclusive
   with `.task()`; dispatch to PhaseDagExecutor when phases present
5. `EnsembleValidator` -- phase rules: unique names, non-empty tasks, no mixed task+phase,
   acyclic DAG, no HIERARCHICAL per-phase, cross-phase context predecessor validation
6. `PhaseDagExecutor` -- phase-level DAG execution using virtual threads, CountDownLatch,
   ConcurrentHashMap; delegates to existing workflow executors per phase
7. `EnsembleOutput` -- add `phaseOutputs: Map<String, List<TaskOutput>>` field
8. `ExecutionTrace` -- add `phases: List<PhaseTrace>` field
9. Unit tests, integration tests, E2E tests per design doc section 14

## Previously Completed (Heartbeat Scheduler Leak Fix)

Branch `fix/web-dashboard-heartbeat-scheduler-leak` (PR #184) fixes a JVM hang caused
by the heartbeat scheduler not being shut down in `WebDashboard.stop()`.

- `WebDashboard.stop()` now calls `heartbeatScheduler.shutdownNow()` + `awaitTermination(2s)`
- Tests: `stop_shutsDownHeartbeatSchedulerThread`, `stop_isIdempotent_doesNotThrowOnDoubleStop`,
  `close_shutsDownHeartbeatSchedulerThread`
- All 171 tests pass; BUILD SUCCESSFUL

## Previously Completed (Deterministic Tasks)

Branch `feature/deterministic-tasks` implements deterministic (non-AI) task execution via
`Task.builder().handler(...)`. Full build + all tests pass.

## Key Design Decisions (Phases, Issue #186)

- **Phase** is a named group of tasks forming a workstream; it does NOT modify Task
- **Phase DAG**: phases declare dependencies via `.after(otherPhase)`. Independent phases
  run in parallel; dependents wait for all predecessors to complete
- **Backward compatible**: flat `.task()` ensembles are unchanged; cannot mix tasks and phases
- **Per-phase workflow**: each phase can override the ensemble-level workflow strategy
  (HIERARCHICAL not permitted per-phase in v1)
- **Cross-phase context**: tasks in later phases may reference tasks in predecessor phases
  via existing `Task.context()` mechanism; validated at build time
- **PhaseDagExecutor**: reuses existing WorkflowExecutors per phase; same virtual-thread
  pattern as ParallelWorkflowExecutor

## Important Patterns and Preferences

### Phases (Issue #186)
- `Phase.of("name", task1, task2)` -- static factory
- `Phase.builder().name(...).task(...).after(otherPhase).workflow(Workflow.PARALLEL).build()`
- `Ensemble.builder().phase(p1).phase(p2).build()` -- registers phases, cannot mix with `.task()`
- `EnsembleOutput.getPhaseOutputs()` returns `Map<String, List<TaskOutput>>`
- Package: `net.agentensemble.workflow` for Phase, PhaseStatus, PhaseTrace, PhaseDagExecutor

### Rate Limiting (Issue #59, v0.8.0)
- `RateLimit.perMinute(60)` / `perSecond(2)` / `of(N, Duration)`
- `RateLimitedChatModel.of(model, rateLimit)` -- manual decorator for shared buckets
- Builder shortcut: `.rateLimit()` on Ensemble, Task, Agent builders
- `RateLimitTimeoutException extends AgentEnsembleException`
- `RateLimitInterruptedException extends AgentEnsembleException`
- Package: `net.agentensemble.ratelimit`

### agentensemble-web module (v2.1.0)
- `WebDashboard.onPort(port)` -- zero-config; `WebDashboard.builder()` for full config
- `Ensemble.builder().webDashboard(dashboard)` -- single call wires listener + review
  handler + lifecycle hooks

### v2 Task-First API (Issues #104/#105)
- Task.of(description) -- zero-ceremony, default expectedOutput, no agent required
- Ensemble.run(model, tasks...) -- static factory, single-line ensemble execution
- Ensemble.builder().chatLanguageModel(model) -- ensemble-level LLM for synthesis

## Next Steps

1. Implement Phase domain object and Ensemble builder changes
2. Implement EnsembleValidator phase rules
3. Write unit tests (TDD) before implementing PhaseDagExecutor
4. Implement PhaseDagExecutor + Ensemble dispatch
5. Update EnsembleOutput and ExecutionTrace
6. Run full build and all tests
7. Update memory bank and merge
