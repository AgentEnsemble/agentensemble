# Chapter 5: Capacity Management -- Bend, Don't Break

## The Instinct to Reject

When a web server is overwhelmed, the standard response is to reject requests. Return HTTP
503 Service Unavailable. Drop the connection. Shed load. The reasoning is sound for
real-time systems: if you cannot serve the request within the expected latency window,
returning an error is better than making the client wait indefinitely.

This instinct is wrong for AI agent systems.

An LLM task is not a web request. A web request expects a response in milliseconds. An LLM
task might take 30 seconds, 5 minutes, or an hour. The caller already expects latency. When
maintenance delegates parts ordering to procurement, it does not expect an instant response.
It expects procurement to do its job and deliver the result when it is ready.

Rejecting a work request because the ensemble is busy is like a hotel telling a guest "We
cannot take your room service order because the kitchen is currently preparing other meals."
No hotel does this. The kitchen takes the order, puts it in the queue, and prepares it
when capacity is available. The guest is told: "Your order will be ready in approximately
30 minutes." The guest decides whether to wait.

This is the "bend, don't break" principle: **accept and queue by default, reject only at
hard limits.**

## Accept, Queue, Estimate, Process, Deliver

When a work request arrives at an ensemble, the processing pipeline is:

```
Request arrives
  -> Is the ensemble alive and in READY state?
     No  -> Return UNAVAILABLE (the only legitimate rejection)
     Yes -> Accept into priority queue
            -> Return task_accepted with queue position and ETA
            -> Process when capacity is available
            -> Deliver result via caller-specified delivery method
```

The ensemble always accepts the work (as long as it is alive). It always reports its current
queue position and estimated completion time. The caller receives this information
immediately and makes its own decision.

This is fundamentally different from the traditional approach where the server decides to
accept or reject. In the Ensemble Network, the server always accepts. The client decides
whether the wait is acceptable.

## Caller-Side SLAs

Every WorkRequest carries a `deadline` field: the caller's SLA. "I need this within 30
minutes." This is not an instruction to the provider -- it is information for the caller's
own routing logic.

```json
{
  "requestId": "maint-7721",
  "task": "purchase-parts",
  "deadline": "PT30M",
  "priority": "HIGH"
}
```

The provider responds with its honest estimate:

```json
{
  "type": "task_accepted",
  "requestId": "maint-7721",
  "queuePosition": 7,
  "estimatedCompletion": "PT45M"
}
```

The estimated completion time (45 minutes) exceeds the caller's deadline (30 minutes). The
caller's `NetworkTask` implementation now has three options:

1. **Wait anyway**: The deadline was advisory. The caller accepts the longer wait.
2. **Cancel and reroute**: Try a different provider. In a federated network, another
   realm's procurement ensemble might have shorter queues.
3. **Continue without**: The deadline was a best-effort target. The caller proceeds with
   partial information and incorporates the result later if it arrives.

The choice depends on the request mode configured by the caller (AWAIT, ASYNC, or
AWAIT_WITH_DEADLINE) and the application logic. The framework provides the information;
the application makes the decision.

## Priority Queuing

Not all work is equal. When the hotel has 20 room service orders queued and the general
manager requests a meal for a VIP guest in the penthouse, that order goes to the front.

The ensemble's internal queue orders work by priority:

```
CRITICAL > HIGH > NORMAL > LOW
```

Within the same priority level, work is processed in FIFO order (first in, first out).

### Priority Aging

A LOW priority request submitted at 9am should not still be waiting at 5pm because higher
priority requests keep arriving. Priority aging ensures that old low-priority work
eventually gets processed.

The aging algorithm is configurable. The simplest approach: every N minutes, unprocessed
work gets promoted one level. A LOW request submitted at 9am becomes NORMAL at 9:30am,
HIGH at 10am, and CRITICAL at 10:30am. The exact intervals are configurable per ensemble.

```java
// Create a priority queue with 30-minute aging intervals
RequestQueue queue = RequestQueue.priority(AgingPolicy.every(Duration.ofMinutes(30)));

// Or disable aging entirely
RequestQueue noAgingQueue = RequestQueue.priority(AgingPolicy.none());
```

Aging is computed lazily at dequeue time -- no background threads are involved. The
`PriorityWorkQueue` stores the enqueue timestamp with each entry and calculates the
effective priority when selecting the next item to process.

### Human Re-Prioritization

A human connected to the dashboard can see the queue and manually re-prioritize individual
work items. "Move request #4821 to the front" -- the request gets promoted to CRITICAL and
jumps ahead of everything else.

This is the hotel equivalent of the manager walking into the kitchen and saying "This order
first." The manager does not take over the kitchen. She gives a directive and walks away.
The kitchen processes it.

## Operational Profiles

Hotels anticipate load changes. A 500-person conference arriving on Friday means the
kitchen needs extra staff, room service needs more runners, and the front desk needs all
hands on deck. The hotel does not wait for the conference to arrive and then scramble. It
plans ahead.

Operational profiles are pre-configured capacity plans for anticipated scenarios:

```java
NetworkProfile sportingEvent = NetworkProfile.builder()
    .name("sporting-event-weekend")
    .ensemble("front-desk", Capacity.replicas(4).maxConcurrent(50))
    .ensemble("kitchen", Capacity.replicas(3).maxConcurrent(100))
    .ensemble("room-service", Capacity.replicas(3).maxConcurrent(80))
    .ensemble("maintenance", Capacity.replicas(1).maxConcurrent(10))
    .preload("kitchen", "inventory", "Extra beer and ice stocked")
    .build();

NetworkProfile offPeak = NetworkProfile.builder()
    .name("weekday-off-peak")
    .ensemble("front-desk", Capacity.replicas(1).maxConcurrent(10))
    .ensemble("kitchen", Capacity.replicas(1).maxConcurrent(20))
    .ensemble("maintenance", Capacity.dormant())
    .build();
```

A profile specifies:
- **Replica counts**: How many pods of each ensemble should be running
- **Concurrent task limits**: Maximum simultaneous task executions per ensemble
- **Dormant flag**: Whether an ensemble should be shut down entirely (saving resources)
- **Pre-loading**: Context to inject into shared memory before the scenario begins

Profiles can be applied:
- **Manually**: A human sends a directive via the dashboard
- **On a schedule**: "Apply `sporting-event-weekend` at Friday 4pm"
- **Via rules**: "When front-desk queue depth exceeds 20, apply `high-load`"

When a profile is applied, the framework adjusts Kubernetes HPA targets, activates dormant
ensembles, and pre-loads shared memory. Ensembles that need to scale up do so via K8s; the
framework provides the configuration.

## Elastic Scaling Within a Profile

Within a profile's configured bounds, ensembles scale dynamically based on actual load. The
framework exposes Micrometer/Prometheus metrics that Kubernetes HPA uses for scaling
decisions:

```
agentensemble_active_tasks{ensemble="kitchen"} 8
agentensemble_queued_requests{ensemble="kitchen"} 15
agentensemble_capacity_utilization{ensemble="kitchen"} 0.85
```

HPA watches these metrics and scales replicas within the profile's min/max bounds. If the
profile says kitchen should have 1-3 replicas and the queue depth exceeds the threshold,
HPA scales from 1 to 2, then to 3. If load drops, it scales back down.

The framework exposes the metrics. K8s handles the scaling. There is no custom auto-scaler
in the framework -- just honest reporting of the ensemble's state.

## Hard Limits

The hotel has 200 rooms. No amount of scaling changes this. When all rooms are booked, the
answer is "we are full."

Every ensemble can declare a hard limit: the maximum queue depth beyond which new work is
rejected. This is the only scenario where the ensemble returns a rejection rather than an
acceptance.

```json
{
  "type": "task_response",
  "requestId": "maint-7722",
  "status": "REJECTED",
  "reason": "AT_CAPACITY",
  "currentQueue": 200,
  "suggestion": "Try kitchen-west-wing in realm hotel-airport"
}
```

The rejection includes the current queue depth and, when available in a federated network,
a suggestion of an alternative provider. The caller can use this information to reroute.

Hard limits should be generous. The "bend, don't break" principle means most load spikes
are absorbed by the queue. Hard limits are a safety valve for truly exceptional situations,
not a normal operating mode.

## The Ticket Seller on the Street

There is a special case of capacity management that is not about handling incoming requests
but about proactively distributing information.

A ticket seller standing on a street corner shouting "Tickets available!" is not responding
to requests. Nobody asked. The seller is broadcasting availability. Interested parties
hear the announcement and come forward if they want a ticket.

This maps to scheduled tasks with broadcast delivery. The front desk ensemble runs a
scheduled task every 5 minutes: "Check current room availability." The result is broadcast
to the `hotel.availability` topic. The sales system subscribes and updates the booking
website. The conference coordinator subscribes and adjusts room block allocations. The
manager's dashboard subscribes and shows the current count.

Nobody requested this information. The front desk produced it proactively and made it
available to anyone who cares. This is the push model complementing the pull model. Some
information is more valuable when it is pushed than when it must be requested.
