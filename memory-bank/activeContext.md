# Active Context

## Current Work Focus

v2.0.0 architecture design documented on branch `v2-architecture-design`.
GitHub issues being created to track implementation work.

Issue #98 (Static MapReduceEnsemble with chunkSize) is complete on branch
`feat/issue-98-static-map-reduce-ensemble`. MapReduce (#98, #99, #100) ships
in the v1.x API before v2.0.0 work begins.

## Recent Changes

### v2.0.0 Architecture Design (branch: v2-architecture-design)

- `docs/design/15-v2-architecture.md`: full design document covering all v2.0.0
  architectural decisions
- `mkdocs.yml`: v2.0.0 Architecture added to Design nav section

**Core paradigm shift**: task-first, agent-invisible. Users define tasks; agent
composition is handled by `AgentSynthesizer` behind the scenes. `Agent` remains
available as an optional power-user escape hatch via `Task.agent()`.

**Four pillars of v2.0.0:**
1. Task-First API -- `Ensemble.run(model, Task.of(...), Task.of(...))` zero-ceremony path
2. Task-scoped cross-execution memory -- named scopes, `MemoryStore` SPI, persists across runs
3. Human-in-the-loop review gates -- `ReviewHandler` SPI, before/during/after timing,
   timeout + continue/edit/exit-early; `ConsoleReviewHandler` for CLI
4. Partial results -- `EnsembleOutput` redesigned with `isComplete()`, `exitReason()`,
   `completedTasks()`; exit-early guarantees memory persistence

**Breaking changes** (clean break, no compat shim):
- `Ensemble.builder().agents()` removed
- `Task.agent()` optional (auto-synthesized when absent)
- Tools, LLM, maxIterations move to Task
- `Ensemble.memory(EnsembleMemory)` replaced by `Task.memory(scope)` + `Ensemble.memoryStore()`
- Module split: agentensemble-core -> core + agentensemble-memory + agentensemble-review

**SPI contracts defined** in design doc section 7:
- `AgentSynthesizer` (core)
- `MemoryStore` (agentensemble-memory)
- `ReviewHandler` (agentensemble-review)

**Parallel workstreams** (Groups A-F, see design doc section 9):
- Groups A (core refactor), B (memory), C (review) can run in parallel
- Group D (output/workflow) depends on Group A
- Group E (MapReduce refactor) depends on Group A + #98-100
- Group F (BOM + migration) depends on A+B+C

### Issue #98 -- Static MapReduceEnsemble with chunkSize (v2.0.0)

Implementation complete on `feat/issue-98-static-map-reduce-ensemble`.
(See previous activeContext entry for full details.)

## Next Steps

- Create GitHub epic + issues for v2.0.0 workstreams
- Open PR for v2-architecture-design branch
- Continue MapReduce work: issue #99 (Adaptive), then #100 (short-circuit)
- After #98-100 land, begin v2.0.0 Group A (core refactor) work
