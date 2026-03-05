# Active Context

## Current Work Focus

Issues #78 (Delegation Policy Hooks) and #79 (Delegation Lifecycle Events) were implemented
on `feature/delegation-policy-hooks-and-lifecycle-events` (2 commits):
- `f5d9b67` feat(#79,#78): add delegation lifecycle events, correlation IDs, and policy hooks
- `20b3185` docs(#78,#79): update README, guides, examples, and design docs

The feature branch is ready to be merged. All tests pass; full `./gradlew check` is green.

## Recent Changes

### Issue #79 -- Delegation lifecycle events and correlation IDs

**New event records in `net.agentensemble.callback`:**
- `DelegationStartedEvent`: delegationId, delegatingAgentRole, workerRole, taskDescription,
  delegationDepth, request -- fired before worker invocation (only when all guards and policies pass)
- `DelegationCompletedEvent`: delegationId, delegatingAgentRole, workerRole, response, duration
  -- fired after successful worker execution
- `DelegationFailedEvent`: delegationId, delegatingAgentRole, workerRole, failureReason,
  cause (Throwable or null), response, duration -- fired for guard failures, policy rejections,
  and worker exceptions

**EnsembleListener changes:**
- Added `onDelegationStarted(DelegationStartedEvent)` default no-op
- Added `onDelegationCompleted(DelegationCompletedEvent)` default no-op
- Added `onDelegationFailed(DelegationFailedEvent)` default no-op

**ExecutionContext changes:**
- Added `fireDelegationStarted`, `fireDelegationCompleted`, `fireDelegationFailed` fire methods
  with same exception-isolation semantics as existing fire methods

**AgentDelegationTool and DelegateTaskTool changes:**
- Fire `DelegationStartedEvent` after all guards and policies pass, before worker invocation
- Fire `DelegationCompletedEvent` on successful worker execution
- Fire `DelegationFailedEvent` on guard failure (cause=null), policy rejection (cause=null),
  or worker exception (cause=exception); guard/policy failures have no corresponding start event

**Ensemble.Builder changes:**
- Lambda convenience methods: `onDelegationStarted`, `onDelegationCompleted`, `onDelegationFailed`

### Issue #78 -- Delegation policy hooks: DelegationPolicy

**New types in `net.agentensemble.delegation.policy`:**
- `DelegationPolicy` (@FunctionalInterface): `evaluate(DelegationRequest, DelegationPolicyContext)`
  returning `DelegationPolicyResult`
- `DelegationPolicyResult` (sealed interface): `Allow` (singleton), `Reject` (record w/ reason),
  `Modify` (record w/ modifiedRequest); factory methods `allow()`, `reject(String)`,
  `modify(DelegationRequest)`
- `DelegationPolicyContext` (immutable record): `delegatingAgentRole`, `currentDepth`,
  `maxDepth`, `availableWorkerRoles`

**DelegationContext changes:**
- Added `List<DelegationPolicy> policies` field; immutable, propagated through `descend()`
- New 5-arg `create()` factory with policies; original 4-arg delegates to it with `List.of()`

**Policy evaluation in AgentDelegationTool and DelegateTaskTool:**
- Runs after all built-in guards, before worker invocation
- Evaluation order: registered order; first REJECT short-circuits (fires DelegationFailedEvent,
  returns error message, records FAILURE response, worker never invoked)
- MODIFY replaces working request for subsequent policies and worker invocation
- ALLOW continues to next policy; all-ALLOW proceeds to worker

**Ensemble.Builder changes:**
- `@Singular("delegationPolicy") List<DelegationPolicy> delegationPolicies` field
- `selectExecutor()` passes policies to all three workflow executors
- All executors (Sequential, Hierarchical, Parallel) updated with policy-aware constructors
  and `DelegationContext.create()` calls

**Tests added:**
- `DelegationPolicyContextTest`: record fields, equality, toString
- `DelegationPolicyResultTest`: allow singleton, reject/modify factories, factory validation,
  pattern matching exhaustiveness
- `AgentDelegationToolPolicyTest`: ALLOW/REJECT/MODIFY, multiple policies in order,
  first-REJECT short-circuits, MODIFY+REJECT chaining, propagation through descend(),
  policy context fields (callerRole, depth, availableRoles)
- `DelegationEventsTest`: all three event record fields and equality
- `AgentDelegationToolEventsTest`: start+completed on success, correlationId matching,
  guard failures fire failed-only (no start), policy rejection fires failed-only,
  worker exception fires start+failed, listener exceptions don't abort delegation
- `DelegateTaskToolPolicyAndEventsTest`: same coverage as above for hierarchical path

## Next Steps

- Open PR for `feature/delegation-policy-hooks-and-lifecycle-events` targeting `main`
- Issue #81 (HierarchicalConstraints) remains open and depends on #78 being merged
- Issue #74 (Tool Pipeline/Chaining) remains open for future development

## Active Decisions and Considerations

- **Guard failures vs policy rejections in events**: Guard failures (depth limit, self-delegation,
  unknown agent) fire `DelegationFailedEvent` with `cause=null`. Policy rejections also fire
  `DelegationFailedEvent` with `cause=null`. Worker exceptions fire both `DelegationStartedEvent`
  and `DelegationFailedEvent` with the thrown exception as `cause`.
- **DelegationStartedEvent only fires when all pass**: The event is only fired when the worker
  is about to be invoked -- not for guard or policy failures. This means listeners don't see a
  start event for delegations that were blocked before execution.
- **MODIFY propagation**: When a policy returns MODIFY, the modified request replaces the
  working request for all subsequent policy evaluations AND for the final worker invocation.
  The `DelegationStartedEvent.request()` carries the final working request (possibly modified).
- **Lombok @Singular conflict**: The varargs `delegationPolicies(DelegationPolicy... policies)`
  method was not added to EnsembleBuilder because Lombok's @Singular annotation generates
  `delegationPolicies(Collection)` which would conflict. Users can call
  `.delegationPolicy(p)` multiple times or `.delegationPolicies(list)` (Lombok-generated).
