# Chapter 9: Error Handling and Resilience

## Five Ways Things Go Wrong

In a single-process ensemble, errors are straightforward: a task fails, an exception is
thrown, the workflow executor handles it. In a distributed network of ensembles,
failures are more varied and more subtle.

### Failure Mode A: Unreachable

The target ensemble does not exist. The pod is not running. DNS does not resolve. The
Kubernetes deployment has zero replicas.

This is a **connection failure**. The `NetworkTask` cannot establish a WebSocket connection
or the queue consumer finds no target queue. The failure is immediate and unambiguous.

### Failure Mode B: Busy

The target ensemble is reachable but under heavy load. It accepts the request, queues it,
and reports an estimated completion time that exceeds the caller's deadline.

This is a **capacity issue**. The request is accepted but the SLA cannot be met. Per the
"bend, don't break" principle (Chapter 5), the provider does not reject -- it reports
honestly and lets the caller decide.

### Failure Mode C: Crash Mid-Execution

The target ensemble accepted the request and started working on it. Five minutes into a
15-minute procurement process, the pod is killed (OOM, deployment rollout, node failure).

This is the most dangerous failure mode. Work has started but not completed. The calling
ensemble is waiting for a result that may never arrive. With durable transport (Chapter 6),
the request re-enters the queue and another pod picks it up. With WebSocket-only transport,
the request is lost.

### Failure Mode D: Business Error

The target ensemble processed the request and failed for a domain reason: "No vendors found
for model X-420," "Budget exceeded for this cost center," "Allergen detected in the
requested dish."

This is a **business error**. The system worked correctly; the domain constraint prevented
completion. The caller needs to handle this differently from a transient failure -- retrying
will produce the same result.

### Failure Mode E: Network Blip

The WebSocket connection drops for 5 seconds due to a transient network issue, then
recovers. In-flight request state is unclear. Did the request reach the target? Was it
processed? Was the response sent but not received?

This is a **transient failure**. With idempotency keys, the caller can safely retry. With
durable transport, the request was in the queue all along and unaffected.

## The Resilience Toolbox

The framework provides four mechanisms for handling failures. Each is configured per
`NetworkTask` or `NetworkTool` call.

### Timeouts

Every cross-ensemble call has a timeout. There is no such thing as "wait forever" in a
distributed system (except for gated human reviews, which are a deliberate design choice).

```java
NetworkTask.from("procurement", "purchase-parts")
    .timeout(Duration.ofMinutes(30))
    .connectTimeout(Duration.ofSeconds(10))
```

Two timeouts:
- **Connect timeout**: How long to wait to establish the connection or submit to the queue.
  Short (seconds). Detects failure mode A (unreachable).
- **Execution timeout**: How long to wait for the complete result after the request is
  accepted. Longer (minutes to hours). Detects failure mode C (crash mid-execution).

When a timeout fires, the `NetworkTask` throws a `NetworkTimeoutException`. The calling
agent's tool call fails. The agent may retry, use a fallback, or report the failure.

### Retry Policies

Retries address transient failures (modes A, C, E). They must not retry business errors
(mode D) because retrying "no vendors found" will find no vendors again.

```java
NetworkTask.from("procurement", "purchase-parts")
    .retryPolicy(RetryPolicy.builder()
        .maxAttempts(3)
        .backoff(Duration.ofSeconds(5), Duration.ofMinutes(1))
        .retryOn(ConnectionFailure.class, TimeoutException.class)
        .noRetryOn(TaskFailureResponse.class)
        .build())
```

The `.retryOn()` and `.noRetryOn()` clauses distinguish transient from business errors.
`ConnectionFailure` and `TimeoutException` are transient -- retry with exponential backoff.
`TaskFailureResponse` is a business error -- propagate immediately.

The idempotency key in the WorkRequest ensures that retries are safe. If the original
request was partially processed before the failure, the receiver recognizes the duplicate
`requestId` and returns the cached result (or continues from where it left off if the
result is not yet available).

### Circuit Breakers

If procurement has failed 5 times in the last minute, continuing to send requests wastes
resources and adds latency. A circuit breaker stops trying until the target recovers.

```java
NetworkTask.from("procurement", "purchase-parts")
    .circuitBreaker(CircuitBreaker.builder()
        .failureThreshold(5)
        .windowDuration(Duration.ofMinutes(1))
        .halfOpenAfter(Duration.ofMinutes(5))
        .build())
```

Three states:
- **Closed** (normal): requests flow through. Failures are counted.
- **Open** (tripped): all requests fail immediately without attempting the call. No
  network traffic. The calling agent gets an immediate `CircuitOpenException`.
- **Half-open** (probing): after the cooldown period, one request is allowed through. If
  it succeeds, the circuit closes. If it fails, the circuit opens again.

Circuit breaker state is per caller-target pair. Room service's circuit breaker to kitchen
is independent of maintenance's circuit breaker to kitchen.

The circuit breaker state is exposed as a metric (`agentensemble_circuit_breaker_state`)
so operators can see which connections are healthy and which are tripped.

### Fallback Strategies

When all retries are exhausted and the circuit breaker is open, the caller needs a plan B.

```java
NetworkTask.from("procurement", "purchase-parts")
    .onFailure(Fallback.returnMessage(
        "Procurement is currently unavailable. The parts order could not be placed."))
```

Or route to an alternative provider:

```java
NetworkTask.from("procurement", "purchase-parts")
    .onFailure(Fallback.delegateTo("procurement-backup", "purchase-parts"))
```

Or degrade gracefully:

```java
NetworkTask.from("procurement", "purchase-parts")
    .onFailure(Fallback.custom(error -> {
        // Log the failure, notify the human, return partial result
        return "Parts order failed: " + error.getMessage()
            + ". Manual procurement required.";
    }))
```

The fallback result is returned to the calling agent as if it were a normal tool result.
The agent incorporates it into its reasoning: "Procurement is unavailable, I should notify
the building manager that the repair will be delayed."

## Framework Handles Semantics, Infrastructure Handles Transport

A clean separation of concerns:

| Concern | Owner | Rationale |
|---|---|---|
| Timeouts per call | Framework | The framework knows the call semantics |
| Retry with error distinction | Framework | The framework knows transient vs business |
| Circuit breaker state | Framework | The framework tracks per-target failure rates |
| Fallback strategies | Framework | The framework provides the alternative routing |
| TLS encryption | Infrastructure | K8s service mesh (Istio) handles mTLS |
| Connection pooling | Infrastructure | WebSocket/HTTP client handles pooling |
| Load balancing | Infrastructure | K8s Service provides round-robin |
| DNS resolution | Infrastructure | K8s CoreDNS handles service discovery |
| Health checks | Infrastructure | K8s probes use the framework's health endpoints |
| Auto-scaling | Infrastructure | K8s HPA uses the framework's metrics |

The framework does not re-implement TLS, load balancing, or DNS. It does not need to.
Kubernetes provides these at the infrastructure level. The framework focuses on the
**semantic** layer: understanding what failed, deciding whether to retry, tracking circuit
breaker state, and providing fallback behavior.

This separation means the same framework code works in local development (no Istio, no HPA,
direct connections) and in production (full service mesh, auto-scaling, network policies).
The framework's resilience logic is independent of the deployment environment.

## The Durable Queue as a Resilience Primitive

The durable transport layer (Chapter 6) is itself a resilience mechanism. When work requests
flow through a durable queue rather than a direct WebSocket connection:

- **Failure mode A** (unreachable): The request sits in the queue until the target starts.
  No retry needed -- the queue handles the wait.
- **Failure mode C** (crash mid-execution): The visibility timeout expires and the request
  re-enters the queue. Another replica picks it up.
- **Failure mode E** (network blip): The queue is unaffected by transient network issues
  between pods. The request is already persisted.

The durable queue does not eliminate the need for timeouts and circuit breakers (the caller
still needs to know when to give up waiting), but it absorbs many transient failures that
would otherwise require explicit retry logic.

This is why the architecture recommends durable transport for production: it converts many
failure modes from "the request is lost" to "the request is delayed." The "bend, don't
break" principle applied to the transport layer itself.
