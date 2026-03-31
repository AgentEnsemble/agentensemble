# 11 - Configuration Reference

This document specifies all configurable settings, their defaults, hardcoded constants, and extension points.

## User-Facing Configuration

All configuration is done through builder methods on domain objects. There are no configuration files, environment variables, or property files.

### Agent Configuration

| Setting | Type | Default | Required | Description |
|---|---|---|---|---|
| `role` | `String` | -- | Yes | The agent's role/title. Used in prompts and logging. |
| `goal` | `String` | -- | Yes | The agent's primary objective. Included in system prompt. |
| `background` | `String` | `null` | No | Background context for the agent persona. Included in system prompt if present. |
| `tools` | `List<Object>` | `List.of()` | No | Tools available to this agent. Each must be `AgentTool` or `@Tool`-annotated object. |
| `llm` | `ChatLanguageModel` | -- | Yes | The LangChain4j model to use. |
| `allowDelegation` | `boolean` | `false` | No | Reserved for Phase 2. Whether the agent can delegate to other agents. |
| `verbose` | `boolean` | `false` | No | When true, elevates prompt/response logging to INFO level. |
| `maxIterations` | `int` | `25` | No | Maximum tool call iterations before forcing a final answer. Must be > 0. |
| `responseFormat` | `String` | `""` | No | Extra formatting instructions appended to system prompt. |

### Task Configuration

| Setting | Type | Default | Required | Description |
|---|---|---|---|---|
| `description` | `String` | -- | Yes | What the agent should do. Supports `{variable}` templates. |
| `expectedOutput` | `String` | -- | Yes | What the output should look like. Supports templates. |
| `agent` | `Agent` | -- | Yes | The agent assigned to this task. |
| `context` | `List<Task>` | `List.of()` | No | Tasks whose outputs feed into this task as context. |

### Ensemble Configuration

| Setting | Type | Default | Required | Description |
|---|---|---|---|---|
| `agents` | `List<Agent>` | -- | Yes | All agents participating. Must not be empty. |
| `tasks` | `List<Task>` | -- | Yes | All tasks to execute, in order. Must not be empty. |
| `workflow` | `Workflow` | `SEQUENTIAL` | No | How tasks are executed. |
| `verbose` | `boolean` | `false` | No | When true, elevates logging for all tasks/agents to INFO. |
| `memory` | `EnsembleMemory` | `null` | No | Memory configuration (short-term, long-term, entity). |
| `maxDelegationDepth` | `int` | `3` | No | Maximum peer-delegation depth. Must be > 0. |
| `toolExecutor` | `Executor` | virtual threads | No | Executor for parallel tool calls within a single LLM turn. |
| `toolMetrics` | `ToolMetrics` | `NoOpToolMetrics` | No | Metrics backend for tool execution timings. |
| `listeners` | `List<EnsembleListener>` | `List.of()` | No | Event listeners for task/tool/delegation lifecycle events. |
| `inputs` | `Map<String, String>` | `{}` | No | Template variable values applied to task descriptions. |
| `hierarchicalConstraints` | `HierarchicalConstraints` | `null` | No | Constraints for hierarchical workflow (required workers, caps). |
| `delegationPolicies` | `List<DelegationPolicy>` | `List.of()` | No | Custom policies evaluated before each delegation. |
| `costConfiguration` | `CostConfiguration` | `null` | No | Per-token cost rates for monetary cost estimation. |
| `traceExporter` | `ExecutionTraceExporter` | `null` | No | Called after each run with the complete execution trace. |
| `captureMode` | `CaptureMode` | `OFF` | No | Depth of data collection: OFF, STANDARD, or FULL. Can also be set via the `agentensemble.captureMode` system property or `AGENTENSEMBLE_CAPTURE_MODE` environment variable. |

### Workflow Options

| Value | Description | Status |
|---|---|---|
| `SEQUENTIAL` | Tasks execute one after another. Output from earlier tasks can feed as context to later tasks. | Implemented |
| `HIERARCHICAL` | A manager agent delegates tasks to worker agents. | Phase 2 |

## Hardcoded Constants

These values are internal framework constants, not configurable by users. They are defined as `private static final` fields in the relevant classes.

| Constant | Value | Class | Rationale |
|---|---|---|---|
| `MAX_STOP_MESSAGES` | `3` | `AgentExecutor` | After 3 "please stop" messages, the agent is considered stuck and `MaxIterationsExceededException` is thrown. |
| `CONTEXT_LENGTH_WARN_THRESHOLD` | `10000` | `AgentPromptBuilder` | Log a WARN when context from a single task exceeds this character count. |
| `LOG_TRUNCATE_LENGTH` | _removed_ | `AgentExecutor` | Replaced by `toolLogTruncateLength` on `Ensemble.builder()` (default `200`). Now configurable. |
| `MDC_DESCRIPTION_MAX_LENGTH` | `80` | `SequentialWorkflowExecutor` | Task description is truncated in MDC to keep diagnostic context concise. |
| `ERROR_TEMPLATE_MAX_LENGTH` | `100` | `TemplateResolver` | Template string is truncated in error messages. |
| `CHAT_MEMORY_MAX_MESSAGES` | `20` | `AgentExecutor` | Maximum messages retained in the agent's chat memory window during tool-use loops. |

### Rationale for Hardcoding

These constants represent sensible defaults that rarely need changing. Making them configurable would add API surface without proportional value. If users need different values, they can:
1. Open a GitHub issue requesting the constant be made configurable
2. Fork the framework and change the constant
3. In Phase 2+, we may expose some of these as configuration options if demand exists

## Extension Points

### Custom Tools

The primary extension point. Users create tools by:
1. Implementing the `AgentTool` interface
2. Creating classes with `@dev.langchain4j.agent.tool.Tool` annotated methods

See [06-tool-system.md](06-tool-system.md) for details.

### Custom LLM Providers

Users provide any `ChatLanguageModel` implementation from LangChain4j:
- `OpenAiChatModel` (OpenAI / Azure OpenAI)
- `AnthropicChatModel` (Anthropic Claude)
- `OllamaChatModel` (Ollama / local models)
- `VertexAiGeminiChatModel` (Google Vertex AI)
- `BedrockChatModel` (Amazon Bedrock)
- Custom implementations of the `ChatLanguageModel` interface

### Custom Workflow Executors (Phase 2)

The `WorkflowExecutor` interface allows custom execution strategies:

```java
public interface WorkflowExecutor {
    EnsembleOutput execute(List<Task> resolvedTasks, boolean verbose);
}
```

In Phase 1, only `SequentialWorkflowExecutor` is provided. In Phase 2, `HierarchicalWorkflowExecutor` and `ParallelWorkflowExecutor` will be added. Users could also implement custom strategies.

## What Is NOT Configurable

| Feature | Why Not |
|---|---|
| Prompt templates | Fixed in `AgentPromptBuilder`. Custom prompt strategies planned for Phase 2. |
| Tool execution timeout | Users should implement timeouts within their `AgentTool.execute()` method. |
| Retry behavior for LLM calls | LangChain4j model instances handle retries. Configure at the model level. |
| Logging format | Configured via the user's SLF4J implementation (Logback, Log4j2, etc.). |
| Memory / persistence | Phase 2 feature. |
