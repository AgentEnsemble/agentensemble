# Ensemble Network: Building Distributed AI Systems That Never Sleep

## Table of Contents

### Part I: The Vision

- **[Chapter 1: The Hotel That Never Sleeps](01-the-hotel-that-never-sleeps.md)**
  Why single-process AI agent frameworks cannot model the real world. The hotel analogy
  as an architectural blueprint. What it means for AI systems to be always-on,
  decentralized, and human-augmented.

- **[Chapter 2: Ensembles as Services](02-ensembles-as-services.md)**
  The paradigm shift from "scripts that run and exit" to "services that run continuously."
  Long-running ensembles, shared capabilities, and the ensemble network. How existing
  frameworks compare and where they fall short.

### Part II: The Architecture

- **[Chapter 3: Cross-Ensemble Delegation](03-cross-ensemble-delegation.md)**
  The core differentiator: delegating complex, multi-step tasks across service boundaries.
  The difference between borrowing a tool and hiring a department. NetworkTask, NetworkTool,
  and how they integrate with the existing agent ReAct loop.

- **[Chapter 4: The WorkRequest Protocol](04-the-workrequest-protocol.md)**
  The standardized envelope for cross-ensemble communication. Request identity, priority,
  deadlines, delivery methods, trace context, and caching. Ingress and egress patterns.
  "Call me back at this number when you have a hotel room available."

- **[Chapter 5: Capacity Management](05-capacity-management.md)**
  The "bend, don't break" principle. Why LLM systems should accept and queue, not reject.
  Caller-side SLAs, priority queuing with aging, operational profiles, and elastic scaling.
  The ticket seller on the street.

- **[Chapter 6: Durable Transport](06-durable-transport.md)**
  Why WebSocket is not enough. The split between real-time events and reliable work
  delivery. Durable queues, result stores, asymmetric routing, and the pluggable transport
  SPI. Simple mode for development, durable mode for production.

### Part III: Humans in the Loop (and Out of It)

- **[Chapter 7: Human Participation](07-human-participation.md)**
  Humans as participants, not controllers. The interaction spectrum from fully autonomous
  to fully gated. Role-based reviews, directives, the manager who goes home at night.
  How the dashboard catches humans up with late-join snapshots.

- **[Chapter 8: Observability](08-observability.md)**
  Distributed tracing with OpenTelemetry. W3C trace context propagation across ensemble
  boundaries. The adaptive audit trail: leveled logging with dynamic rules. Scaling
  metrics for Kubernetes HPA. Token cost tracking and model tier switching.

### Part IV: Resilience and Testing

- **[Chapter 9: Error Handling and Resilience](09-error-handling-and-resilience.md)**
  What happens when things go wrong in a distributed AI system. Timeouts, retry policies,
  circuit breakers, fallback strategies. The distinction between transient and business
  errors. Framework handles semantics, infrastructure handles transport.

- **[Chapter 10: Testing, Simulation, and Chaos](10-testing-simulation-chaos.md)**
  You cannot set fire to the hotel. The three-tier testing pyramid: component tests (test
  the alarms), simulation (computer-model the evacuation), chaos engineering (run the drill
  with real people). Built-in chaos, not bolted on.

### Part V: Advanced Patterns

- **[Chapter 11: Shared State and Consistency](11-shared-state-and-consistency.md)**
  When multiple ensembles share state, what consistency model applies? Advisory memory
  versus authoritative state. Per-scope consistency: eventual, locked, optimistic, external.
  Natural language as the compatibility layer.

- **[Chapter 12: Federation](12-federation.md)**
  Scaling beyond a single cluster. Realms, cross-namespace discovery, elastic capacity
  sharing. The hotel chain: when Hotel A is at capacity, Hotel B's kitchen serves the
  overflow. Capacity advertisement and load-based routing.

- **[Chapter 13: Discovery and Evolution](13-discovery-and-evolution.md)**
  Capability-based discovery: "who on the network can check inventory?" Dynamic tool
  catalogs that resolve at execution time. Why natural language contracts eliminate
  schema versioning. The LLM as a compatibility layer.

### Part VI: Deployment and Operations

- **[Chapter 14: Infrastructure-Native Deployment](14-infrastructure-native-deployment.md)**
  Kubernetes as the substrate. Each ensemble is a Deployment fronted by a Service. DNS for
  discovery, HPA for scaling, namespaces for compartmentalization. Health probes, drain
  lifecycle, graceful shutdown. Example manifests.

- **[Chapter 15: The Road Ahead](15-the-road-ahead.md)**
  Semantic capability matching, autonomous ensemble spawning, cross-organization federation,
  formal verification of delegation chains, cost-optimal routing. Where this architecture
  goes next.

---

### Appendices

- **[Appendix A: Wire Protocol Reference](appendix-a-wire-protocol.md)**
  Complete message type catalog for the ensemble network wire protocol.

- **[Appendix B: SPI Reference](appendix-b-spi-reference.md)**
  All pluggable interfaces: Transport, DeliveryHandler, Ingress, ResultCache, LockProvider,
  AuditSink, ReviewNotifier.

- **[Appendix C: Comparison Matrix](appendix-c-comparison-matrix.md)**
  Feature-by-feature comparison with CrewAI, AutoGen, LangGraph, MCP, and traditional
  microservice orchestration.
