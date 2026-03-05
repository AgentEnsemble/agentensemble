# Active Context

## Current Work Focus

Issue #81 (HierarchicalConstraints) has been implemented on
`feature/81-hierarchical-constraints` (2 commits):
- `41c8222` feat(#81): add HierarchicalConstraints with enforcer, validation, and tests
- `927dc89` docs(#81): update README, guides, examples, reference, and design docs for
  HierarchicalConstraints

The feature branch is ready for PR. All tests pass; full `./gradlew build
:agentensemble-core:javadoc --continue` is green.

## Recent Changes

### Issue #81 -- Constrained hierarchical mode

**New types:**

- `HierarchicalConstraints` (`@Value @Builder` in `net.agentensemble.workflow`):
  - `requiredWorkers` (`@Singular Set<String>`) -- roles that MUST be called at least once
  - `allowedWorkers` (`@Singular("allowedWorker") Set<String>`) -- allowlist; empty = all
    allowed
  - `maxCallsPerWorker` (`@Singular("maxCallsPerWorker") Map<String,Integer>`) -- per-worker
    cap (counts approved attempts)
  - `globalMaxDelegations` (`@Builder.Default int = 0`) -- total cap; 0 = unlimited
  - `requiredStages` (`@Singular("requiredStage") List<List<String>>`) -- ordered stage
    groups; all workers in stage N must complete before stage N+1 can be delegated to

- `ConstraintViolationException` (in `net.agentensemble.exception`):
  - Extends `AgentEnsembleException`
  - Fields: `List<String> violations`, `List<TaskOutput> completedTaskOutputs`
  - Constructors: (violations), (violations, completedOutputs), (violations, cause)
  - Thrown post-execution when required workers were not called

- `HierarchicalConstraintEnforcer` (package-private, `net.agentensemble.workflow`):
  - Implements `DelegationPolicy` for pre-delegation enforcement
  - `evaluate()` synchronized: checks allowedWorkers, global cap, per-worker cap, stage
    ordering; increments approved-attempt counters atomically on ALLOW
  - `recordDelegation(workerRole)` synchronized: adds to completedWorkers set
  - `validatePostExecution(completedTaskOutputs)`: checks requiredWorkers against
    completedWorkers; throws ConstraintViolationException with partial outputs if any missing

**Modified types:**

- `HierarchicalWorkflowExecutor`:
  - New 7-arg constructor adds `HierarchicalConstraints constraints` (nullable)
  - In `execute()`: when constraints != null, creates enforcer, prepends it to policy chain,
    adds internal EnsembleListener that calls `enforcer.recordDelegation(event.workerRole())`
    on DelegationCompletedEvent, calls `enforcer.validatePostExecution()` after manager
    finishes
  - All existing constructors delegate to the 7-arg with `null` constraints (backward
    compatible)

- `Ensemble`:
  - New field: `private final HierarchicalConstraints hierarchicalConstraints` (nullable)
  - `selectExecutor()` passes constraints to `HierarchicalWorkflowExecutor` 7-arg constructor

- `EnsembleValidator`:
  - New `validateHierarchicalConstraints()` method called from `validate()`
  - Only runs when `workflow == HIERARCHICAL` and constraints != null
  - Validates: requiredWorkers/allowedWorkers/maxCallsPerWorker/stage roles exist in
    registered agents; requiredWorkers subset of allowedWorkers (when allowedWorkers
    non-empty); maxCallsPerWorker values > 0; globalMaxDelegations >= 0

**Test files added:**
- `ConstraintViolationExceptionTest` -- message formatting, violations list, completedOutputs,
  cause constructor, type hierarchy
- `HierarchicalConstraintsTest` -- empty defaults, all builder methods, immutability
- `HierarchicalConstraintEnforcerTest` -- allowedWorkers, per-worker cap, global cap, stage
  ordering, recordDelegation, validatePostExecution
- `HierarchicalWorkflowExecutorConstraintTest` -- backward compat, required worker enforced,
  exception carries partial outputs, allowed workers filter, per-worker cap, global cap,
  constraints + user policies coexist
- `EnsembleConstraintValidationTest` -- valid constraints, invalid roles, invalid values
- `HierarchicalConstraintsIntegrationTest` -- full Ensemble.run() integration for each
  constraint type

## Next Steps

- Open PR for `feature/81-hierarchical-constraints` targeting `main`
- Issue #74 (Tool Pipeline/Chaining) remains open for future development

## CI Parity Rule (Lesson Learned -- PRs #84, #81)

**Problem**: Local `./gradlew :agentensemble-core:check` does NOT include `javadoc`.
CI runs `./gradlew build :agentensemble-core:javadoc --continue` which also generates
Javadoc, causing CI failures for broken `{@link}` references that were invisible locally.

**Root causes:**
1. `{@link SomeClass#someMethod()}` on Lombok-generated methods (getters from `@Value`)
   -- Lombok runs after Javadoc, so the methods are invisible during Javadoc generation.
2. `{@link PackagePrivateClass}` from a public class's Javadoc -- Javadoc cannot link to
   package-private types.

**Prevention rules:**
1. Before pushing any PR, always run:
   `./gradlew build :agentensemble-core:javadoc --continue`
2. Never use `{@link SomeClass#someMethod()}` for Lombok-generated methods (`@Value`
   getters, `@Builder` fluent methods, etc.). Use `{@code}` instead.
3. Never use `{@link PackagePrivateClass}` in public class Javadoc. Use `{@code}` instead.

---

## Active Decisions and Considerations

- **Constraint semantics for caps**: The per-worker cap (`maxCallsPerWorker`) and global cap
  (`globalMaxDelegations`) count delegation attempts that passed all other checks (at
  evaluate() time, before the worker executes). A failed worker execution still consumes a
  slot. This is by design -- caps limit the Manager's delegation attempts.

- **Stage ordering uses completedWorkers**: Stage prerequisites check `completedWorkers`
  (populated by `recordDelegation()` on DelegationCompletedEvent), meaning workers must have
  COMPLETED successfully before the next stage can proceed. A failed worker does not advance
  the stage.

- **Pre-delegation vs post-execution violations**: Pre-delegation violations (allowedWorkers,
  caps, stages) are returned as DelegationPolicyResult.reject() messages to the Manager LLM
  -- they are not exceptions. Only post-execution required-worker violations throw
  ConstraintViolationException.

- **Thread safety**: HierarchicalConstraintEnforcer uses `synchronized` methods for atomicity
  of check-and-increment operations when the Manager issues concurrent tool calls.

- **Enforcer is first policy**: The constraint enforcer is prepended to the policy chain so
  hard constraint checks always run before user-registered DelegationPolicy instances.

- **Lombok @Singular on Map**: Lombok cannot auto-singularize `maxCallsPerWorker` (it
  doesn't recognize "perWorker" as a plural suffix). The explicit annotation
  `@Singular("maxCallsPerWorker")` generates `maxCallsPerWorker(String key, Integer value)`
  as the singular method and `maxCallsPerWorkers(Map)` as the bulk method.
