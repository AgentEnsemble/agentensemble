# Metrics

AgentEnsemble provides pluggable tool metrics through the `ToolMetrics` interface.
Every tool that extends `AbstractAgentTool` is automatically instrumented with
execution counts and timing -- with zero configuration when metrics are not needed.

---

## How Tool Metrics Work

When a tool is executed, `AbstractAgentTool.execute()` (the template method) automatically records:

- **Success counter** -- incremented when `doExecute()` returns a successful `ToolResult`
- **Failure counter** -- incremented when `doExecute()` returns a failed `ToolResult`
- **Error counter** -- incremented when `doExecute()` throws an uncaught exception
- **Duration timer** -- recorded on every execution regardless of outcome

All metrics are tagged with the **tool name** and the **agent role** that invoked the tool.
This allows you to distinguish "the researcher agent's `web_search` calls" from "the writer
agent's `web_search` calls" when the same tool is shared across multiple agents.

Tools can also record custom metrics using the `metrics()` accessor:

```java
public class InventoryTool extends AbstractAgentTool {

    @Override
    protected ToolResult doExecute(String input) {
        var result = lookupInventory(input);
        if (result.fromCache()) {
            metrics().incrementCounter("inventory.cache.hits", name(), null);
        }
        return ToolResult.success(result.toString());
    }
}
```

---

## Default: No-Op Metrics

By default, all metrics are discarded with zero overhead using `NoOpToolMetrics`.
No configuration is needed if you don't want metrics.

---

## Micrometer Integration

The `agentensemble-metrics-micrometer` module bridges `ToolMetrics` to any
[Micrometer](https://micrometer.io/) `MeterRegistry` (Prometheus, Datadog, InfluxDB, etc.).

### Installation

=== "Gradle"

    ```kotlin
    implementation("net.agentensemble:agentensemble-metrics-micrometer:VERSION")
    // Also add your Micrometer registry implementation, e.g.:
    implementation("io.micrometer:micrometer-registry-prometheus:1.14.4")
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>net.agentensemble</groupId>
      <artifactId>agentensemble-metrics-micrometer</artifactId>
      <version>VERSION</version>
    </dependency>
    <!-- Also add your Micrometer registry implementation -->
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-registry-prometheus</artifactId>
      <version>1.14.4</version>
    </dependency>
    ```

### Usage

```java
MeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
// or: new SimpleMeterRegistry() for tests
// or: inject from Spring Boot's auto-configured registry

Ensemble.builder()
    .agent(analyst)
    .task(analysisTask)
    .toolMetrics(new MicrometerToolMetrics(registry))
    .build()
    .run();
```

### Metrics Recorded

#### `agentensemble.tool.executions` (Counter)

Total tool invocations tagged by outcome.

| Tag         | Values                  | Description                                |
|-------------|-------------------------|--------------------------------------------|
| `tool_name` | e.g., `web_search`      | Tool name from `AgentTool.name()`          |
| `agent_role`| e.g., `Researcher`      | Role of the agent that invoked the tool    |
| `outcome`   | `success`, `failure`, `error` | Execution result                    |

Example Prometheus query -- success rate per tool:

```promql
rate(agentensemble_tool_executions_total{outcome="success"}[5m])
/ rate(agentensemble_tool_executions_total[5m])
```

#### `agentensemble.tool.duration` (Timer)

Execution duration per tool+agent combination.

| Tag         | Description                             |
|-------------|-----------------------------------------|
| `tool_name` | Tool name from `AgentTool.name()`       |
| `agent_role`| Role of the agent that invoked the tool |

Example Prometheus query -- p99 latency per tool:

```promql
histogram_quantile(0.99,
  rate(agentensemble_tool_duration_seconds_bucket[5m]))
```

### Custom Metrics

Tools extending `AbstractAgentTool` can record additional metrics:

```java
public class SearchTool extends AbstractAgentTool {

    @Override
    protected ToolResult doExecute(String input) {
        long resultCount = search(input).size();
        // Record the number of results returned
        metrics().recordValue("search.result_count", name(), (double) resultCount, null);
        // Record with extra tags
        metrics().incrementCounter(
            "search.queries",
            name(),
            Map.of("type", isJsonQuery(input) ? "structured" : "freetext")
        );
        return ToolResult.success(formatResults(input));
    }
}
```

---

## Custom ToolMetrics Implementation

You can implement `ToolMetrics` directly to bridge to any metrics backend:

```java
public class StatsdToolMetrics implements ToolMetrics {

    private final StatsdClient statsd;

    public StatsdToolMetrics(StatsdClient statsd) {
        this.statsd = statsd;
    }

    @Override
    public void incrementSuccess(String toolName, String agentRole) {
        statsd.increment("tool.executions.success",
            "tool:" + toolName, "agent:" + agentRole);
    }

    @Override
    public void incrementFailure(String toolName, String agentRole) {
        statsd.increment("tool.executions.failure",
            "tool:" + toolName, "agent:" + agentRole);
    }

    @Override
    public void incrementError(String toolName, String agentRole) {
        statsd.increment("tool.executions.error",
            "tool:" + toolName, "agent:" + agentRole);
    }

    @Override
    public void recordDuration(String toolName, String agentRole, Duration duration) {
        statsd.time("tool.duration", duration.toMillis(),
            "tool:" + toolName, "agent:" + agentRole);
    }

    @Override
    public void incrementCounter(String metricName, String toolName, Map<String, String> tags) {
        statsd.increment(metricName, "tool:" + toolName);
    }

    @Override
    public void recordValue(String metricName, String toolName, double value,
                            Map<String, String> tags) {
        statsd.gauge(metricName, (long) value, "tool:" + toolName);
    }
}
```

---

## Configuration Reference

| Builder option    | Type          | Default        | Description                                  |
|-------------------|---------------|----------------|----------------------------------------------|
| `toolMetrics`     | `ToolMetrics` | `NoOpToolMetrics` | Metrics backend for tool execution data   |
| `toolExecutor`    | `Executor`    | Virtual threads | Executor for parallel multi-tool turns     |

```java
Ensemble.builder()
    .agent(agent)
    .task(task)
    .toolMetrics(new MicrometerToolMetrics(meterRegistry))
    .toolExecutor(Executors.newVirtualThreadPerTaskExecutor())
    .build()
    .run();
```
