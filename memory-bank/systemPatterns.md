# System Patterns

## Architecture

Two-module Gradle project:
- `agentensemble-core`: The framework library
- `agentensemble-examples`: Example applications

Package: `net.agentensemble`

## Key Technical Decisions

1. **Lombok @Value + @Builder**: All domain objects are immutable value objects with builders. Custom builder methods handle validation.
2. **Strategy pattern for workflows**: `WorkflowExecutor` interface with `SequentialWorkflowExecutor` implementation. New strategies (hierarchical, parallel) plug in without changing existing code.
3. **Composition with LangChain4j**: We use LangChain4j's `ChatLanguageModel.generate()` directly for the tool loop rather than AiServices. This gives us control over the iteration limit and error handling.
4. **Two tool paths**: Framework's `AgentTool` interface (adapted to LC4j) and native LC4j `@Tool` annotations (passed through). Both can be mixed.
5. **Fail-fast validation**: Configuration validation happens at `build()` time and at `run()` entry, never deep in execution.
6. **Unchecked exceptions**: All framework exceptions extend `RuntimeException` to avoid forcing catch-or-declare on users.
7. **MDC-based observability**: Ensemble ID, task index, and agent role are set in SLF4J MDC for structured logging.
8. **Template resolver**: Simple `{variable}` substitution with escaped `{{variable}}` support. Resolved before execution starts.

## Design Patterns in Use

| Pattern | Where | Purpose |
|---|---|---|
| Builder | Agent, Task, Ensemble, outputs | Clean construction with validation |
| Strategy | WorkflowExecutor | Pluggable execution strategies |
| Adapter | LangChain4jToolAdapter | Adapts AgentTool to LC4j tool spec |
| Value Object | Agent, Task, TaskOutput, EnsembleOutput, ToolResult | Immutable, thread-safe domain objects |
| Template Method | AgentPromptBuilder | Standardized prompt construction |

## Component Relationships

```
Ensemble.run(inputs)
  -> validates configuration
  -> TemplateResolver resolves {variables}
  -> WorkflowExecutor.execute(resolvedTasks)
    -> for each task:
      -> AgentPromptBuilder builds system + user prompts
      -> AgentExecutor runs agent on task
        -> if tools: LLM generate loop with tool calls
        -> if no tools: single LLM generate call
      -> TaskOutput captured
  -> EnsembleOutput assembled
```

## Critical Implementation Paths

1. **Tool loop in AgentExecutor**: The ReAct-style loop where the LLM calls tools, gets results, and iterates until producing a final answer. Max iterations guard prevents infinite loops.
2. **Context passing in SequentialWorkflowExecutor**: Each task's output is stored and passed as context to subsequent tasks that declare dependencies.
3. **Validation in Ensemble.run()**: DAG cycle detection, context ordering check, agent membership check.
