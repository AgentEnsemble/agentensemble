# Logging

AgentEnsemble uses SLF4J for all logging. Add any SLF4J-compatible implementation to your project (Logback, Log4j2, JUL, etc.).

---

## Adding a Logging Implementation

**Logback (recommended):**
```kotlin
implementation("ch.qos.logback:logback-classic:1.5.32")
```

**Log4j2:**
```kotlin
implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.23.1")
```

---

## Logger Name

All AgentEnsemble classes log under the `net.agentensemble` namespace. Set the log level for this package in your configuration:

```xml
<logger name="net.agentensemble" level="INFO"/>
```

---

## Log Levels

| Level | What is logged |
|---|---|
| ERROR | Ensemble run failures, task failures |
| WARN | Agent exceeded max iterations (stop messages sent), unused agents, delegation guards triggered |
| INFO | Ensemble start/complete, task start/complete, tool calls, delegation events, memory state |
| DEBUG | Prompt lengths, context counts, task output previews |
| TRACE | Full agent responses |

---

## MDC Keys

AgentEnsemble populates MDC (Mapped Diagnostic Context) values during execution. These can be included in your log format to add context to each log line.

| Key | Value | When set |
|---|---|---|
| `ensemble.id` | UUID | For the duration of each `run()` call |
| `task.index` | `"2/5"` (current/total) | During each task execution |
| `agent.role` | Agent role string | During each task/agent execution |
| `delegation.depth` | `"1"`, `"2"`, etc. | During delegated agent executions |
| `delegation.parent` | Parent agent role | During delegated agent executions |

---

## Logback Configuration

### Basic Pattern with MDC

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss} %-5level [%X{ensemble.id:-}] [%X{task.index:-}] [%X{agent.role:-}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="net.agentensemble" level="INFO"/>
    <root level="WARN"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

### Sample Output

```
14:32:01 INFO  [a3f4-...] [] [] net.agentensemble.Ensemble - Ensemble run started | Workflow: SEQUENTIAL | Tasks: 2 | Agents: 2
14:32:01 INFO  [a3f4-...] [1/2] [Senior Research Analyst] net.agentensemble.agent.AgentExecutor - Agent 'Senior Research Analyst' executing task | Tools: 1 | AllowDelegation: false
14:32:01 INFO  [a3f4-...] [1/2] [Senior Research Analyst] net.agentensemble.agent.AgentExecutor - Tool call: web_search(AI agents 2026) -> Found 5 results... [342ms]
14:32:03 INFO  [a3f4-...] [1/2] [Senior Research Analyst] net.agentensemble.workflow.SequentialWorkflowExecutor - Task 1/2 completed | Duration: PT2.341S | Tool calls: 1
14:32:03 INFO  [a3f4-...] [2/2] [Content Writer] net.agentensemble.agent.AgentExecutor - Agent 'Content Writer' executing task | Tools: 0 | AllowDelegation: false
14:32:05 INFO  [a3f4-...] [] [] net.agentensemble.Ensemble - Ensemble run completed | Duration: PT4.102S | Tasks: 2 | Tool calls: 1
```

---

## Verbose Mode

Set `verbose = true` on an agent or ensemble to log the full system prompt, user prompt, and LLM response at INFO level. This is useful during development:

```java
// Agent-level verbose
Agent researcher = Agent.builder()
    .role("Researcher")
    .goal("Research topics")
    .llm(model)
    .verbose(true)
    .build();

// Ensemble-level verbose (applies to all agents)
Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .tasks(...)
    .verbose(true)
    .build();
```

When verbose, each agent logs:
- Full system prompt
- Full user prompt (including memory sections if enabled)
- Full LLM response

---

## Delegation Logging

When delegation occurs, the log context switches to show the delegation chain:

```
14:32:05 INFO  [a3f4-...] [1/1] [Lead Researcher] AgentExecutor - Agent 'Lead Researcher' delegating subtask to 'Content Writer' (depth 1/3)
14:32:05 INFO  [a3f4-...] [1/1] [Content Writer] AgentExecutor - Agent 'Content Writer' executing task | Tools: 0 | AllowDelegation: false
```

The `delegation.depth` and `delegation.parent` MDC keys are available during delegated executions for structured log patterns that show the full delegation tree.

---

## Structured JSON Logging

For production environments, consider structured JSON logging with Logback's `logstash-logback-encoder`:

```kotlin
implementation("net.logstash.logback:logstash-logback-encoder:8.0")
```

```xml
<appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
        <includeMdc>true</includeMdc>
    </encoder>
</appender>
```

All MDC keys (`ensemble.id`, `task.index`, `agent.role`, `delegation.depth`, `delegation.parent`) are automatically included in the JSON output.

---

## Tool Output Truncation

By default, tool results are truncated to **200 characters** in log statements to keep log files readable. The full output always passes through to the LLM and is stored in the execution trace.

Two independent knobs let you tune this behaviour:

### `toolLogTruncateLength` — log visibility

Controls what appears in INFO/WARN log lines for tool calls:

```java
Ensemble.builder()
    .toolLogTruncateLength(500)   // log first 500 chars (more context in logs)
    .toolLogTruncateLength(-1)    // log full output (useful when debugging)
    .toolLogTruncateLength(0)     // suppress output content from logs entirely
    .build();
```

### `maxToolOutputLength` — LLM context window

Controls how many characters the LLM sees in its message history. Default is `-1` (unlimited). Set a positive value to cap very large tool outputs and save tokens:

```java
Ensemble.builder()
    .maxToolOutputLength(5_000)   // all tool results capped at 5 000 chars for the LLM
    .build();
```

When truncation occurs, a note (`"... [truncated, full length: N chars]"`) is appended so the model knows the output was cut. The full output is always stored in the trace regardless of this setting.

### Per-run overrides with `RunOptions`

Both settings can be overridden on a single `run()` call without changing the ensemble-level defaults:

```java
// Full log output for this debugging run only
ensemble.run(RunOptions.builder()
    .toolLogTruncateLength(-1)
    .build());

// Specific run gets a bigger LLM window
ensemble.run(Map.of("topic", "AI"), RunOptions.builder()
    .maxToolOutputLength(-1)
    .build());
```
