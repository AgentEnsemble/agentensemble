# 09 - Logging Strategy

This document specifies the logging approach used throughout AgentEnsemble.

## Framework

- **SLF4J API** (`slf4j-api`) for the logging facade
- Users provide their own SLF4J implementation (Logback, Log4j2, etc.)
- The `agentensemble-examples` module includes Logback for demonstration

## Logger Naming Convention

Each class uses its own SLF4J logger:

```java
private static final Logger log = LoggerFactory.getLogger(ClassName.class);
```

This follows standard Java logging conventions and allows fine-grained log level control per class.

## Log Levels by Component

### Ensemble.run()

Logger: `io.agentensemble.Ensemble`

```
INFO  : "Ensemble run started | Workflow: {workflow} | Tasks: {taskCount} | Agents: {agentCount}"
DEBUG : "Input variables: {inputs}"
DEBUG : "Task list: [{task1Description}, {task2Description}, ...]"
DEBUG : "Agent list: [{agent1Role}, {agent2Role}, ...]"
INFO  : "Ensemble run completed | Duration: {totalDuration} | Tasks: {taskCount} | Tool calls: {totalToolCalls}"
WARN  : "Agent '{role}' is registered but not assigned to any task"
ERROR : "Ensemble run failed: {exceptionMessage}"
```

### SequentialWorkflowExecutor

Logger: `io.agentensemble.workflow.SequentialWorkflowExecutor`

```
INFO  : "Task {index}/{total} starting | Description: {truncated80} | Agent: {role}"
INFO  : "Task {index}/{total} completed | Duration: {duration} | Tool calls: {toolCallCount}"
DEBUG : "Task {index}/{total} context: {contextCount} prior outputs"
DEBUG : "Task {index}/{total} output preview: {truncated200}"
ERROR : "Task {index}/{total} failed: {exceptionMessage}"
```

### AgentExecutor

Logger: `io.agentensemble.agent.AgentExecutor`

```
INFO  : "Agent '{role}' executing task | Tools: {toolCount}"
INFO  : "Tool call: {toolName}({truncatedInput200}) -> {truncatedOutput200} [{durationMs}ms]"
DEBUG : "System prompt ({charCount} chars):\n{systemPrompt}"
DEBUG : "User prompt ({charCount} chars):\n{userPrompt}"
DEBUG : "Agent '{role}' completed | Tool calls: {count} | Duration: {duration}"
WARN  : "Tool error: {toolName}({truncatedInput200}) -> {errorMessage}"
WARN  : "Agent '{role}' returned empty response for task '{truncatedDescription}'"
WARN  : "Agent '{role}' exceeded max iterations ({max}). Stop message sent ({stopCount}/3)."
WARN  : "Context from task '{description}' is {length} characters (>10000). Consider breaking into smaller tasks."
TRACE : "Full LLM response:\n{fullResponse}"
```

### AgentPromptBuilder

Logger: `io.agentensemble.agent.AgentPromptBuilder`

```
DEBUG : "Built system prompt ({charCount} chars) for agent '{role}'"
DEBUG : "Built user prompt ({charCount} chars) for task '{truncatedDescription}'"
```

### TemplateResolver

Logger: `io.agentensemble.config.TemplateResolver`

```
DEBUG : "Resolving template ({charCount} chars) with {inputCount} input variables"
DEBUG : "Resolved {variableCount} variables in template"
```

### LangChain4jToolAdapter

Logger: `io.agentensemble.tool.LangChain4jToolAdapter`

```
DEBUG : "Adapted AgentTool '{name}' to LangChain4j ToolSpecification"
WARN  : "AgentTool '{name}' threw exception during execution: {message}"
```

## Verbose Mode

When verbose mode is active (`ensemble.verbose = true` OR `agent.verbose = true`), certain log statements are elevated from DEBUG/TRACE to INFO level:

| Normal Level | Elevated To | Content |
|---|---|---|
| DEBUG | INFO | System prompt text |
| DEBUG | INFO | User prompt text |
| TRACE | INFO | Full LLM response text |
| DEBUG | INFO | Task output preview |

**Resolution**: `effectiveVerbose = ensemble.verbose || task.agent().verbose()`

### Implementation Pattern

```java
if (effectiveVerbose) {
    log.info("System prompt:\n{}", systemPrompt);
} else {
    log.debug("System prompt ({} chars):\n{}", systemPrompt.length(), systemPrompt);
}
```

Verbose mode is intended for development and debugging. In production, users should rely on standard log level configuration.

## MDC (Mapped Diagnostic Context)

MDC values are set at the start of each task execution and cleared after completion (including on failure via try/finally).

### MDC Keys

| Key | Value | Example |
|---|---|---|
| `ensemble.id` | UUID generated per `run()` call | `"a1b2c3d4-e5f6-7890-abcd-ef1234567890"` |
| `task.index` | Current task position | `"2/5"` |
| `agent.role` | Current agent's role | `"Senior Research Analyst"` |

### Usage in Log Patterns

Users can include MDC values in their logging configuration:

**Logback example:**
```xml
<pattern>%d{HH:mm:ss.SSS} [%thread] %-5level [%X{ensemble.id:-}] [%X{task.index:-}] [%X{agent.role:-}] %logger{36} - %msg%n</pattern>
```

**Example output:**
```
14:23:01.456 [main] INFO  [a1b2c3d4] [1/3] [Researcher] i.a.w.SequentialWorkflowExecutor - Task 1/3 starting | Description: Research AI trends | Agent: Researcher
14:23:05.789 [main] INFO  [a1b2c3d4] [1/3] [Researcher] i.a.a.AgentExecutor - Tool call: web_search(AI agent frameworks 2026) -> Top results: 1. LangChain... [1234ms]
14:23:12.321 [main] INFO  [a1b2c3d4] [1/3] [Researcher] i.a.w.SequentialWorkflowExecutor - Task 1/3 completed | Duration: PT10.865S | Tool calls: 2
```

### MDC Lifecycle

```
ensemble.run():
  MDC.put("ensemble.id", UUID.randomUUID().toString())
  try:
    for each task:
      MDC.put("task.index", ...)
      MDC.put("agent.role", ...)
      try:
        execute task
      finally:
        MDC.remove("task.index")
        MDC.remove("agent.role")
  finally:
    MDC.remove("ensemble.id")
```

## Truncation Constants

To keep logs readable, certain values are truncated:

| Context | Max Length | Constant Name |
|---|---|---|
| Task description in MDC | 80 chars | `MDC_DESCRIPTION_MAX_LENGTH` |
| Tool input/output in INFO logs | 200 chars | `LOG_TRUNCATE_LENGTH` |
| Output preview in verbose logs | 200 chars | `LOG_TRUNCATE_LENGTH` |
| Template in error messages | 100 chars | `ERROR_TEMPLATE_MAX_LENGTH` |

Truncation appends `"..."` when text is cut.

Full untruncated values are always available at DEBUG or TRACE level.

## No Logging Dependencies

The `agentensemble-core` module depends only on `slf4j-api` (the facade). It does NOT include any SLF4J implementation. Users must provide their own:

- **Logback**: `ch.qos.logback:logback-classic`
- **Log4j2**: `org.apache.logging.log4j:log4j-slf4j2-impl`
- **JUL bridge**: `org.slf4j:slf4j-jdk14`
- **Simple**: `org.slf4j:slf4j-simple` (for quick testing)

If no implementation is provided, SLF4J outputs a warning and discards all log messages.
