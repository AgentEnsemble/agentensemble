# Active Context

## Current Work

Branch: `feature/189-deterministic-only-orchestration` (Issue #189)

Deterministic-only orchestration as a first-class pattern: AgentEnsemble can be used
without any AI/LLM to orchestrate purely deterministic (non-AI) task pipelines with the
same DAG execution, parallel phases, callbacks, guardrails, and metrics.

## Completed This Session

### Implementation (commit b83bed7)

**Core change: `Ensemble.run(Task...)` static factory (no-model overload)**
- New zero-ceremony API for handler-only pipelines: `Ensemble.run(fetchTask, parseTask, storeTask)`
- Validates all tasks have handlers; throws `IllegalArgumentException` with clear message pointing
  to the offending task and suggesting `Ensemble.run(ChatModel, Task...)` for AI tasks
- Resolves overload ambiguity in existing test: `Ensemble.run((ChatModel) null, task)`

**Bug fix: `phaseOutputs` not propagated in `outputWithTrace`**
- In `Ensemble.runWithInputs()`, the final `outputWithTrace` EnsembleOutput build was missing
  `.phaseOutputs(output.getPhaseOutputs())` -- the per-phase results map was silently dropped
- This was a pre-existing bug exposed by the new phase tests that assert on `getPhaseOutputs()`

### Tests: `DeterministicOnlyEnsembleIntegrationTest` (15 new integration tests)
- Sequential pipeline: all handler tasks, no model, runs successfully
- Data passing: output of task A flows into task B via `context()` and `contextOutputs()`
- Three-step chained pipeline: each step reads prior step output
- Parallel workflow with handler tasks (no model)
- Parallel fan-out with context-dependency inference (PARALLEL inferred automatically)
- Phase DAG with deterministic tasks only (no LLM)
- Cross-phase context passing between deterministic tasks
- `Ensemble.run(Task...)` factory: happy paths and error paths (null, empty, no-handler task)
- Callbacks fire for handler tasks
- Handler failure propagates as `TaskExecutionException`
- Mixed handler + non-handler without model fails validation with clear error

### Documentation
- `docs/design/20-deterministic-only.md` -- new design doc
- `docs/guides/deterministic-orchestration.md` -- new guide
- `docs/design/18-deterministic-tasks.md` -- updated with `Ensemble.run(Task...)` factory
- `docs/examples/deterministic-tasks.md` -- added deterministic-only pipeline section
- `README.md` -- broadened Task concept, added non-AI-exclusive callout
- `mkdocs.yml` -- added new guide and design doc to nav

### Example
- `DeterministicOnlyPipelineExample.java` -- three patterns (sequential ETL, parallel fan-out,
  phase-based pipeline)
- `agentensemble-examples/build.gradle.kts` -- `runDeterministicOnlyPipeline` task

## Status
- Full CI build: PASSING (`./gradlew build`)
- All 15 new integration tests: PASSING
- Branch: `feature/189-deterministic-only-orchestration`
- Ready for PR

## Key Design Decisions

### No new framework code required for validation
The existing `EnsembleValidator` already correctly skips handler tasks in `validateTasksHaveLlm()`
and `validatePhaseTasksHaveLlm()`. All-handler ensembles pass validation without a `chatLanguageModel`.

### Factory API design
`Ensemble.run(Task...)` is a separate overload (not replacing `Ensemble.run(ChatModel, Task...)`).
Java resolves `Ensemble.run((ChatModel)null, task)` unambiguously to the model overload.

### phaseOutputs bug fix scope
The missing `.phaseOutputs()` propagation affected all phase-based ensembles (AI and deterministic),
not just the new deterministic case. The existing `PhaseIntegrationTest` did not assert on
`getPhaseOutputs()` so the bug was previously silent.

## Next Steps
- Open PR for feature/189-deterministic-only-orchestration
- Consider: Ensemble.run(Phase...) zero-ceremony factory for phase-based pipelines
