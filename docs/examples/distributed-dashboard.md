# Example: Distributed Live Dashboard

Two ensemble processes stream into a single `LiveEventHub`; a browser connects once and sees both runs side by side.

## Prerequisites

- The `agentensemble-web-hub` module on the hub process's classpath.
- The `agentensemble-web` module on each publisher process's classpath.
- `OPENAI_API_KEY` set in each publisher's environment.

## Step 1: start the hub

Terminal A:

```bash
./gradlew :agentensemble-examples:runDistributedDashboardHub
```

You should see:

```
  LiveEventHub started on port 7400
    Browser : ws://localhost:7400/ws
    Ingress : ws://localhost:7400/ingress
    REST    : http://localhost:7400/api/hub/producers
```

## Step 2: open the browser

Navigate to `http://localhost:7400/hub?server=ws://localhost:7400/ws`. The producer sidebar is empty until publishers connect.

## Step 3: start the first publisher

Terminal B:

```bash
./gradlew :agentensemble-examples:runDistributedDashboardPublisher \
    --args="ws://localhost:7400/ingress svc-a instance-1 \"Research AI trends in 2026\""
```

The browser sidebar shows `svc-a` as a producer chip. Click it to inspect the run.

## Step 4: start the second publisher

Terminal C:

```bash
./gradlew :agentensemble-examples:runDistributedDashboardPublisher \
    --args="ws://localhost:7400/ingress svc-b instance-2 \"Write a marketing summary\""
```

Both producers appear in the sidebar simultaneously. Their task timelines update independently.

## Step 5: late-join check

Refresh the browser mid-run. The hub re-sends a `hub_hello` containing the merged `snapshotTrace`; the page re-renders the same per-producer state without restarting any publisher.

## Step 6: producer restart

Kill Terminal B. The browser shows a `producer_left` event. Restart B with the **same `producerId`** (the example derives it from `serviceName-instanceId`, so use the same args): the publisher re-attaches to its retained state and the browser resumes the timeline.

## Example source

- [`DistributedDashboardHubMain.java`](https://github.com/AgentEnsemble/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/DistributedDashboardHubMain.java)
- [`DistributedDashboardPublisherMain.java`](https://github.com/AgentEnsemble/agentensemble/blob/main/agentensemble-examples/src/main/java/net/agentensemble/examples/DistributedDashboardPublisherMain.java)

## See also

- [Distributed Dashboard guide](../guides/distributed-dashboard.md)
- [Live Dashboard example](live-dashboard.md) — embedded single-process example
