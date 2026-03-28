# Multi-Ensemble Network Dashboard

The `/network` route in agentensemble-viz shows all ensembles in the network with
their status, capabilities, and connections in a topology graph.

---

## Getting Started

Navigate to:
```
http://localhost:5173/network?ensembles=kitchen:ws://localhost:7329/ws,maintenance:ws://localhost:7330/ws
```

The query parameter `ensembles` accepts a comma-separated list of `name:wsUrl` pairs.

---

## Features

### Network Topology Graph

Ensembles are displayed as nodes in an interactive graph powered by React Flow.
Each node shows:
- Ensemble name
- Lifecycle state (green = READY, yellow = STARTING, red = STOPPED)
- Active task count and queue depth
- Task progress bar

Connections between ensembles (shared tasks/tools) are displayed as animated edges.

### Ensemble Detail Sidebar

Click an ensemble node to open the sidebar panel showing:
- Lifecycle state and connection status
- Active tasks, queue depth, completed tasks metrics
- Shared capabilities (tasks and tools)
- WebSocket URL

### Drill-Down

Click "Drill Down" in the sidebar to navigate to the live execution dashboard
(`/live?server=ws://...`) for that specific ensemble. This reuses the existing
live dashboard infrastructure.

### Add Ensemble

Click "Add Ensemble" in the header to connect to a new ensemble by entering its
name and WebSocket URL.

---

## Architecture

The network dashboard opens independent WebSocket connections to each ensemble.
No aggregating portal is needed. The existing `HelloMessage` with `snapshotTrace`
provides late-join support for each connection.

Status polling (every 5s) fetches `/api/status` from each ensemble's HTTP endpoint
for queue depth and lifecycle state information.
