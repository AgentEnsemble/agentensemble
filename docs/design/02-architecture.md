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
io.agentensemble
  Agent.java                            # Top-level user-facing domain objects
  Task.java
  Ensemble.java

  io.agentensemble.agent
    AgentExecutor.java                  # Runs one agent on one task
    AgentPromptBuilder.java             # Constructs system/user prompts

  io.agentensemble.task
    TaskOutput.java                     # Task result value object

  io.agentensemble.ensemble
    EnsembleOutput.java                 # Ensemble result value object

  io.agentensemble.workflow
    Workflow.java                       # Enum: SEQUENTIAL, HIERARCHICAL
    WorkflowExecutor.java              # Strategy interface
    SequentialWorkflowExecutor.java    # Runs tasks one-by-one

  io.agentensemble.tool
    AgentTool.java                      # Framework tool interface
    ToolResult.java                     # Tool execution result
    LangChain4jToolAdapter.java        # Adapts AgentTool to LC4j

  io.agentensemble.config
    TemplateResolver.java               # {variable} substitution

  io.agentensemble.exception
    AgentEnsembleException.java         # Base exception
    ValidationException.java            # Invalid configuration
    TaskExecutionException.java         # Task-level failure
    AgentExecutionException.java        # Agent-level failure
    ToolExecutionException.java         # Tool execution failure
    MaxIterationsExceededException.java # Agent hit iteration limit
    PromptTemplateException.java        # Template variable error
```

## Design Principles

1. **Immutability**: `Agent`, `Task`, `TaskOutput`, `EnsembleOutput` are immutable value objects (`@Value`). Once built, they cannot be modified.

2. **Builder pattern**: All domain objects use Lombok `@Builder` for construction with validation in custom builder methods.

3. **Strategy pattern**: `WorkflowExecutor` is a strategy interface. `SequentialWorkflowExecutor` is the Phase 1 implementation. Hierarchical and parallel implementations follow in later phases.

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
