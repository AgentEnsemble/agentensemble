# 02 - Architecture

## Module Structure

```
agentensemble/                          # Root project
  agentensemble-core/                   # Core library (the framework)
  agentensemble-examples/               # Example applications
```

## Dependency Graph

```
agentensemble-core
  +-- langchain4j-core          (LLM abstractions, tool specs, message types)
  +-- lombok                    (compile-only: builders, value objects)
  +-- jackson-databind          (JSON serialization for tool I/O)
  +-- slf4j-api                 (logging facade)

agentensemble-examples
  +-- agentensemble-core
  +-- langchain4j-open-ai       (OpenAI model provider -- example only)
  +-- logback-classic            (SLF4J implementation for examples)
```

Users add their own LangChain4j model provider as a runtime dependency (e.g., `langchain4j-open-ai`, `langchain4j-ollama`, `langchain4j-anthropic`).

## Package Structure

```
net.agentensemble
  Agent.java                            # Top-level user-facing domain objects
  Task.java
  Ensemble.java

  net.agentensemble.agent
    AgentExecutor.java                  # Runs one agent on one task
    AgentPromptBuilder.java             # Constructs system/user prompts

  net.agentensemble.task
    TaskOutput.java                     # Task result value object

  net.agentensemble.ensemble
    EnsembleOutput.java                 # Ensemble result value object

  net.agentensemble.workflow
    Workflow.java                       # Enum: SEQUENTIAL, HIERARCHICAL
    WorkflowExecutor.java              # Strategy interface
    SequentialWorkflowExecutor.java    # Runs tasks one-by-one
    HierarchicalConstraints.java        # Constraints for hierarchical workflow
    HierarchicalConstraintEnforcer.java # Enforces hierarchical constraints (package-private)

  net.agentensemble.tool
    AgentTool.java                      # Framework tool interface
    ToolResult.java                     # Tool execution result
    LangChain4jToolAdapter.java        # Adapts AgentTool to LC4j

  net.agentensemble.config
    TemplateResolver.java               # {variable} substitution

  net.agentensemble.exception
    AgentEnsembleException.java         # Base exception
    ValidationException.java            # Invalid configuration
    TaskExecutionException.java         # Task-level failure
    AgentExecutionException.java        # Agent-level failure
    ToolExecutionException.java         # Tool execution failure
    MaxIterationsExceededException.java # Agent hit iteration limit
    PromptTemplateException.java        # Template variable error
    ConstraintViolationException.java   # Hierarchical constraint violated

  net.agentensemble.metrics             # Execution metrics (issue #42)
    TaskMetrics.java                    # Per-task token counts, timing, costs
    ExecutionMetrics.java               # Aggregated per-run metrics
    CostConfiguration.java              # Per-token cost rates for estimation
    CostEstimate.java                   # Monetary cost estimate value object
    MemoryOperationCounts.java          # STM/LTM/entity operation counters

  net.agentensemble.trace               # Execution trace (issues #42, #89)
    ExecutionTrace.java                 # Top-level immutable run trace
    TaskTrace.java                      # Per-task trace
    LlmInteraction.java                 # Single LLM chat() call record
    ToolCallTrace.java                  # Single tool invocation record
    DelegationTrace.java                # Peer delegation record
    TaskPrompts.java                    # System/user prompt snapshot
    AgentSummary.java                   # Agent configuration snapshot
    ErrorTrace.java                     # Error record
    LlmResponseType.java                # FINAL_ANSWER / TOOL_CALLS enum
    ToolCallOutcome.java                # SUCCESS / FAILURE / ERROR / SKIPPED enum
    CaptureMode.java                    # OFF / STANDARD / FULL capture depth (issue #89)
    CapturedMessage.java                # Serializable LLM message snapshot (issue #89)

  net.agentensemble.trace.export        # Trace export (issue #42)
    ExecutionTraceExporter.java         # Strategy interface for exporting traces
    JsonTraceExporter.java              # File-based JSON exporter

  net.agentensemble.trace.internal      # Internal accumulator (package-private API)
    TaskTraceAccumulator.java           # Mutable collector, frozen at task end

  net.agentensemble.memory              # Memory subsystem
    MemoryContext.java                  # Runtime memory state for one run
    MemoryOperationListener.java        # Callback interface for memory events (issue #89)
    EnsembleMemory.java                 # Memory configuration
    ShortTermMemory.java                # In-run task output accumulator
    LongTermMemory.java                 # Pluggable persistent store interface
    EntityMemory.java                   # Key-value fact store interface
    MemoryEntry.java                    # Memory entry value object
```

## Design Principles

1. **Immutability**: `Agent`, `Task`, `TaskOutput`, `EnsembleOutput` are immutable value objects (`@Value`). Once built, they cannot be modified.

2. **Builder pattern**: All domain objects use Lombok `@Builder` for construction with validation in custom builder methods.

3. **Strategy pattern**: `WorkflowExecutor` is a strategy interface with `SequentialWorkflowExecutor`, `HierarchicalWorkflowExecutor`, and `ParallelWorkflowExecutor` implementations. `ManagerPromptStrategy` is a secondary strategy interface that controls how the Manager agent's system and user prompts are generated in hierarchical workflow -- `DefaultManagerPromptStrategy` provides the built-in behaviour while callers can substitute a custom implementation via `Ensemble.Builder.managerPromptStrategy()`.

4. **Composition over inheritance**: `AgentExecutor` composes LangChain4j services rather than extending them. No deep inheritance hierarchies.

5. **Fail-fast**: Validation happens at construction time (`build()`) and at `run()` time, not deep in execution. Users get clear error messages early.

6. **No singletons or statics**: All components are instantiated, making testing straightforward. `TemplateResolver` uses static methods but is stateless and pure.

7. **Separation of concerns**: Prompt construction (AgentPromptBuilder), execution (AgentExecutor), orchestration (WorkflowExecutor), and domain model are separate classes with single responsibilities.

## Build System

- **Gradle** with **Kotlin DSL** (`.gradle.kts`)
- **Version catalog** (`gradle/libs.versions.toml`) for centralized dependency management
- `java-library` plugin for `agentensemble-core` (proper `api` vs `implementation` separation)
- `application` plugin for `agentensemble-examples`
- Java 21 target
- Lombok annotation processor configured via Gradle
