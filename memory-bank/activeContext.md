# Active Context

## Current Work Focus

Issues #104 and #105 (v2.0.0 Task-First Architecture, Group A) are complete.

## Recent Changes

### Issue #104: Task-First Core -- Task absorbs Agent responsibilities

**Summary:** `Ensemble.builder().agent()` removed. `Task.agent` made optional. New task-level
fields: `tools`, `chatLanguageModel`, `maxIterations`. Static factory methods `Task.of()`.
Static `Ensemble.run(ChatModel, Task...)` convenience method.

**Modified classes in `agentensemble-core`:**
- `Task` -- `agent` is now optional (nullable). New fields: `chatLanguageModel`, `tools` (`List<Object>`),
  `maxIterations` (`Integer`). Static factories `Task.of(String)` and `Task.of(String, String)`.
  Validation updated: agent no longer required; `maxIterations` validated when set; `tools` validated.
- `Ensemble` -- `agents` field removed from builder. New fields: `chatLanguageModel`, `agentSynthesizer`.
  Static `Ensemble.run(ChatModel, Task...)` method. `runWithInputs()` now has an agent resolution step
  after template resolution. `getAgents()` derives from tasks (identity dedup). `selectExecutor()` takes
  derived agents list. `resolveManagerLlm()` falls back to `chatLanguageModel` then derived agents.
- `EnsembleValidator` -- `validateAgentsNotEmpty()` and `validateAgentMembership()` removed. New
  `validateTasksHaveLlm()`: every task must have agent, task-level LLM, or ensemble-level LLM.
  Hierarchical validation derives roles from tasks with explicit agents.
- `MapReduceEnsemble` -- removed `ensembleBuilder.agent(agent)` calls (agents on tasks only).
- `MapReduceAdaptiveExecutor` -- removed all `builder.agent()` calls (agents on tasks only).

**New classes in `agentensemble-core`:**
- `net.agentensemble.synthesis.SynthesisContext` -- record(ChatModel model, Locale locale)
- `net.agentensemble.synthesis.AgentSynthesizer` -- SPI interface with static factories
- `net.agentensemble.synthesis.TemplateAgentSynthesizer` -- verb-to-role lookup, deterministic
- `net.agentensemble.synthesis.LlmBasedAgentSynthesizer` -- LLM JSON call with template fallback

**Also fixed in other modules:**
- `agentensemble-examples` -- removed `.agent()` from Ensemble builder calls
- `agentensemble-devtools` -- removed `.agent()` from Ensemble builder calls in tests

**Tests added:**
- Unit: `TemplateAgentSynthesizerTest` (25), `LlmBasedAgentSynthesizerTest` (10), `SynthesisContextTest` (3)
- Unit: Added Task.of(), task-level fields to `TaskTest`
- Unit: Added maxIterations and tools validation to `TaskValidationTest`
- Validation: `EnsembleValidationTest` updated (testRun_withEmptyAgents -> testRun_taskWithNoLlm),
  `HierarchicalEnsembleValidationIntegrationTest` updated
- All integration tests updated to remove `.agent()` from Ensemble.builder()

### Issue #105: AgentSynthesizer SPI

Delivered as part of Issue #104 above. The AgentSynthesizer SPI is fully integrated.

**Key integration details:**
- `Ensemble.resolveAgents()` called after template resolution, before workflow execution
- Task-level `chatLanguageModel` takes precedence over ensemble-level for synthesis
- Task-level `tools` and `maxIterations` override synthesis defaults
- Synthesized agents are ephemeral (not cached, not returned by `getAgents()`)

## Next Steps

- Begin other v2.0.0 workstreams (Groups B-F per design doc)

## Important Patterns and Preferences

### v2 Task-First API
- `Task.of(description)` -- zero-ceremony, default expectedOutput, no agent required
- `Task.of(description, expectedOutput)` -- zero-ceremony with custom output
- `Ensemble.run(model, tasks...)` -- static factory, single-line ensemble execution
- `Ensemble.builder().chatLanguageModel(model)` -- ensemble-level LLM for synthesis
- `Ensemble.builder().agentSynthesizer(...)` -- synthesis strategy (default: template)
- `Task.builder().agent(Agent)` -- explicit agent (power-user escape hatch)
- `Task.builder().chatLanguageModel(ChatModel)` -- per-task LLM override
- `Task.builder().tools(List)` -- per-task tools for synthesized agent
- `Task.builder().maxIterations(int)` -- per-task iteration cap

### Agent Resolution Order (in Ensemble.resolveAgents())
1. If task.getAgent() != null: use as-is
2. Else: synthesize using agentSynthesizer with (task-level LLM or ensemble LLM)
3. Apply task-level maxIterations and tools to synthesized agent
4. Set resolved agent on task via toBuilder()

### LLM Resolution for Synthesis
1. task.getChatLanguageModel() != null -> use it
2. ensemble.getChatLanguageModel() != null -> use it
3. Neither -> ValidationException ("No LLM available for task")

### Manager LLM Resolution (hierarchical)
1. ensemble.managerLlm != null -> use it
2. ensemble.chatLanguageModel != null -> use it
3. derivedAgents not empty -> use derivedAgents.get(0).getLlm()
4. Otherwise -> ValidationException

### getAgents() vs tasks
- `Ensemble.getAgents()` derives from tasks via identity-based dedup
- Only tasks with explicit agents contribute
- Synthesized agents are NOT included (ephemeral)
- MapReduceEnsembleTest assertions still work (each map task has its own agent)

### Synthesis approach
- TemplateAgentSynthesizer: first-word verb-to-role lookup (25 verbs mapped), no LLM call
- LlmBasedAgentSynthesizer: prompt -> JSON parse -> fallback to template on any error
- Custom: implement AgentSynthesizer interface directly

### Pre-existing patterns (unchanged)
- Static MapReduce: single `Ensemble.run()` with pre-built DAG, `toEnsemble()` works
- Adaptive MapReduce: multiple `Ensemble.run()` calls, one per level
- Carrier tasks use `__carry__:` role prefix for filtering
- Short-circuit: `directAgent`/`directTask` in adaptive mode
