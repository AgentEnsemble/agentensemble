# Active Context

## Current Work Focus

Issues #77 (Structured Delegation Contract) and #80 (Manager Prompt Extension Hook) were
implemented on `feature/delegation-contracts-and-manager-prompt-strategy` (3 commits):
- `eee052c` feat(#80): add public ManagerPromptStrategy API for hierarchical workflow
- `5771d47` feat(#77): add structured DelegationRequest and DelegationResponse contracts
- `acf513b` docs(#77,#80): update README, guides, examples, and design docs

The feature branch is ready to be merged. All tests pass; full `./gradlew check` is green.

## Recent Changes

### Issue #80 -- Manager prompt extension hook: ManagerPromptStrategy

**New types in `net.agentensemble.workflow`:**
- `ManagerPromptStrategy` (public interface): `buildSystemPrompt(ManagerPromptContext)`,
  `buildUserPrompt(ManagerPromptContext)` -- called once per hierarchical workflow execution
- `ManagerPromptContext` (public immutable record): `agents` (worker agents), `tasks` (resolved
  tasks to orchestrate), `previousOutputs`, `workflowDescription`
- `DefaultManagerPromptStrategy` (public class): implements `ManagerPromptStrategy` with the
  same logic that was in `ManagerPromptBuilder`; exposes `DEFAULT` singleton; constructor is
  package-private to allow subclassing within the framework

**Deprecations:**
- `ManagerPromptBuilder` deprecated (`@Deprecated(forRemoval = true)`); both static methods
  now delegate to `DefaultManagerPromptStrategy.DEFAULT`

**Ensemble.Builder changes:**
- Added `managerPromptStrategy(ManagerPromptStrategy)` field with
  `@Builder.Default = DefaultManagerPromptStrategy.DEFAULT`

**HierarchicalWorkflowExecutor changes:**
- Added overloaded constructor accepting `ManagerPromptStrategy` (5-arg); existing 4-arg
  constructor delegates to it with `DefaultManagerPromptStrategy.DEFAULT`
- Builds `ManagerPromptContext` from worker agents + resolved tasks, calls strategy, uses
  the resulting strings as manager background (system prompt) and task description (user prompt)
- If strategy returns blank user prompt, falls back to a built-in coordinator string to avoid
  `Task.builder()` validation error (validation of strategy output is caller's responsibility)

**Tests added:**
- `ManagerPromptContextTest`: record field accessors, equality, empty context
- `DefaultManagerPromptStrategyTest`: all prompt-content assertions + parity tests verifying
  output matches deprecated `ManagerPromptBuilder`
- `HierarchicalWorkflowExecutorTest`: 8 new tests -- custom strategy injection (system/user
  prompt), empty-string strategy, null-strategy fallback, context field verification

### Issue #77 -- Structured delegation contract: DelegationRequest and DelegationResponse

**Option C hybrid design**: LLM-facing `@Tool` method signature unchanged (2-param, returns
String); internally the framework constructs/threads `DelegationRequest`/`DelegationResponse`
through the delegation pipeline.

**New types in `net.agentensemble.delegation`:**
- `DelegationPriority` (enum): `LOW`, `NORMAL` (default), `HIGH`, `CRITICAL`
- `DelegationStatus` (enum): `SUCCESS`, `FAILURE`, `PARTIAL`
- `DelegationRequest` (immutable `@Value @Builder`): `taskId` (auto-UUID), `agentRole`,
  `taskDescription`, `scope` (Map), `priority`, `expectedOutputSchema`, `maxOutputRetries`,
  `metadata` (Map)
- `DelegationResponse` (immutable Java record): `taskId`, `status`, `workerRole`, `rawOutput`,
  `parsedOutput`, `artifacts`, `errors`, `metadata`, `duration`

**AgentDelegationTool and DelegateTaskTool changes:**
- Construct a `DelegationRequest` before each invocation (taskId auto-generated)
- Produce a `DelegationResponse` (SUCCESS or FAILURE) after each attempt
- Guard failures (depth limit, self-delegation, unknown agent) also produce FAILURE responses
- New `getDelegationResponses()` method on both tools returning immutable list of all responses
- `getDelegatedOutputs()` and the `@Tool` method signature are unchanged

**Tests added:**
- `DelegationPriorityTest`, `DelegationStatusTest`: enum completeness (4+3 values)
- `DelegationRequestTest`: builder, auto-UUID, uniqueness, defaults, all overridable fields, toBuilder
- `DelegationResponseTest`: record accessors, equality, artifact/metadata population, taskId correlation
- `AgentDelegationToolTest`: 12 new tests for getDelegationResponses() -- success/failure/guard statuses,
  multiple accumulation, immutability
- `DelegateTaskToolTest`: 11 new tests for getDelegationResponses() -- same coverage as above,
  plus unique taskId per delegation

### Documentation updates (both issues)

- `README.md`: Custom Manager Prompts note under Hierarchical Workflow; structured delegation
  contracts blurb under Agent Delegation; `managerPromptStrategy` in Ensemble Configuration table
- `docs/guides/workflows.md`: new "Customizing the Manager Prompt" subsection with context
  field table and code example
- `docs/reference/ensemble-configuration.md`: `managerPromptStrategy` row added
- `docs/guides/delegation.md`: new "Structured Delegation Contracts" section with
  DelegationRequest/Response field tables and guard-failure auditing notes
- `docs/examples/hierarchical-team.md`: Custom Manager Prompts section appended
- `HierarchicalTeamExample.java`: demonstrates `investmentStrategy` using
  `ManagerPromptStrategy` + `DefaultManagerPromptStrategy.DEFAULT` delegation
- `docs/design/02-architecture.md`: updated strategy pattern note to include
  `ManagerPromptStrategy` as a secondary extension point
- `docs/design/03-domain-model.md`: added DelegationRequest, DelegationResponse,
  DelegationStatus, DelegationPriority specifications

## Next Steps

- Open PR for `feature/delegation-contracts-and-manager-prompt-strategy` targeting `main`
- Issues #78 (DelegationPolicy hooks), #79 (delegation lifecycle events), and
  #81 (HierarchicalConstraints) remain open and depend on #77 being merged
- Issue #80 is fully implemented; no follow-up needed on that track
- Issue #74 (Tool Pipeline/Chaining) remains open for future development

## Remaining Planned Issues (Structured Delegation API)

### #78 -- Delegation policy hooks: DelegationPolicy
- `DelegationPolicy` (@FunctionalInterface): `evaluate(DelegationRequest, DelegationPolicyContext)`
- `DelegationPolicyResult` (sealed): `allow()`, `reject(String reason)`, `modify(DelegationRequest)`
- `DelegationPolicyContext` (record): delegatingAgentRole, currentDepth, maxDepth, availableWorkerRoles
- Policies registered via `Ensemble.Builder.delegationPolicy(...)`, threaded via DelegationContext
- REJECT produces FAILURE DelegationResponse without invoking the worker executor

### #79 -- Delegation lifecycle events and correlation IDs
- New event records: DelegationStartedEvent, DelegationCompletedEvent, DelegationFailedEvent
- EnsembleListener gains 3 new default no-op methods
- Ensemble.Builder gains 3 lambda convenience methods
- delegationId set as MDC key during worker execution

### #81 -- Constrained hierarchical mode: HierarchicalConstraints
- HierarchicalConstraints builder: requiredWorkers, allowedWorkers, maxCallsPerWorker, etc.
- Enforcement via built-in DelegationPolicy; post-execution validation
- ConstraintViolationException

## Active Decisions and Considerations

- **ManagerPromptStrategy blank user prompt**: When strategy returns blank, the executor falls
  back to a generic coordinator string rather than propagating a ValidationException (Task model
  requires non-blank description). This is documented in HierarchicalWorkflowExecutor.
- **DelegationRequest design**: taskId uses `@Builder.Default` with `UUID.randomUUID()` so each
  build() call generates a unique ID. This was critical for the uniqueness test.
- **DelegationResponse for guard failures**: All guard failures (depth limit, self-delegation,
  unknown agent) produce FAILURE DelegationResponse objects so every delegation attempt is
  fully auditable regardless of outcome.
- **ManagerPromptBuilder deprecation**: One release cycle only; will be removed in a future
  version. Both static methods delegate to DefaultManagerPromptStrategy.DEFAULT.
