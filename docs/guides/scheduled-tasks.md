# Scheduled Tasks

Long-running ensembles can execute tasks proactively on a schedule. Scheduled tasks
fire at fixed intervals or cron expressions and optionally broadcast results to a
named topic.

## Schedule Types

The `Schedule` sealed interface has two implementations:

### Interval

Fires at a fixed rate after the ensemble reaches `READY` state.

```java
Schedule hourly = Schedule.every(Duration.ofHours(1));
Schedule fiveMinutes = Schedule.every(Duration.ofMinutes(5));
```

### Cron (not yet implemented)

The `Schedule.cron(String)` factory is defined but cron scheduling is not yet
supported at runtime. Calling `Ensemble.start()` with a cron-based scheduled task
will throw `UnsupportedOperationException`. Cron support will be added in a future
release.

```java
// Defined but will throw at runtime until cron support is implemented:
Schedule nightly = Schedule.cron("0 2 * * *");        // 2:00 AM daily
Schedule weekdays = Schedule.cron("0 9 * * 1-5");     // 9:00 AM Mon-Fri
```

## ScheduledTask

A `ScheduledTask` pairs a `Task` with a `Schedule` and an optional broadcast topic.

```java
ScheduledTask report = ScheduledTask.builder()
    .name("inventory-report")
    .task(Task.of("Check current inventory levels and generate report"))
    .schedule(Schedule.every(Duration.ofHours(1)))
    .broadcastTo("hotel.inventory")
    .build();
```

| Field | Required | Description |
|-------|----------|-------------|
| `name` | yes | Human-readable identifier for logging and metrics |
| `task` | yes | The `Task` to execute on each firing |
| `schedule` | yes | When to fire (`IntervalSchedule` or `CronSchedule`) |
| `broadcastTo` | no | Topic name to broadcast results to |

## Ensemble Integration

Register scheduled tasks on the ensemble builder. Multiple tasks can be added.

```java
Ensemble kitchen = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Manage kitchen operations"))
    .scheduledTask(ScheduledTask.builder()
        .name("inventory-report")
        .task(Task.of("Check current inventory levels and generate report"))
        .schedule(Schedule.every(Duration.ofHours(1)))
        .broadcastTo("hotel.inventory")
        .build())
    .scheduledTask(ScheduledTask.builder()
        .name("equipment-check")
        .task(Task.of("Verify all kitchen equipment is operational"))
        .schedule(Schedule.every(Duration.ofHours(12)))
        .build())
    .broadcastHandler((topic, result) -> {
        log.info("Broadcast to {}: {}", topic, result);
    })
    .webDashboard(WebDashboard.builder().port(7329).build())
    .build();

kitchen.start(7329);
```

## BroadcastHandler

The `BroadcastHandler` functional interface receives results from scheduled tasks
that have a `broadcastTo` topic configured.

```java
@FunctionalInterface
public interface BroadcastHandler {
    void broadcast(String topic, String result);
}
```

In simple mode, log or store the result. In production, publish to Kafka or another
message broker:

```java
.broadcastHandler((topic, result) -> {
    kafkaProducer.send(new ProducerRecord<>(topic, result));
})
```

## Lifecycle

The `EnsembleScheduler` is lifecycle-aware:

- Tasks only fire when the ensemble is in `READY` state.
- If the ensemble is `STARTING` or `DRAINING`, firings are silently skipped.
- When the ensemble transitions to `DRAINING`, the scheduler cancels all pending
  firings and shuts down. In-flight executions complete but no new firings start.
- The scheduler uses a daemon thread, so it does not prevent JVM shutdown.

## Related

- [Long-Running Ensembles](long-running-ensembles.md)
- [Cross-Ensemble Delegation](cross-ensemble-delegation.md)
- [Durable Transport](durable-transport.md)
