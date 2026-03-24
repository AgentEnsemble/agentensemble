# Chapter 7: Human Participation

## The Manager Who Goes Home

In every human-in-the-loop AI system built today, the human is a gatekeeper. The system
runs a task, produces output, pauses, and presents the output to a human for approval. The
human reviews it, clicks "Approve" or "Edit," and the system continues. If no human is
present, the system waits. If the wait exceeds a timeout, the system either auto-approves
(risky) or fails (wasteful).

This model treats the human as a **controller** -- a required component in the execution
pipeline. Without the controller, the pipeline stalls.

A hotel does not work this way. The general manager is not a controller. She is a
**participant**. She joins the operation when she chooses, observes the current state, gives
direction, handles things that need her authority, and leaves when she chooses. The hotel
does not stall when she is absent. It runs autonomously. Her presence makes it better, but
her absence does not make it stop.

The Ensemble Network implements this model. Humans are participants in the network, not
controllers of it.

## The Interaction Spectrum

Human interaction with the ensemble network exists on a spectrum from fully autonomous to
fully gated. The level is configurable per task, per ensemble, or at the network level.

### Autonomous

No human involvement at all. The ensemble handles the task entirely on its own.

Housekeeping cleans rooms after checkout. No approval needed. No notification. The process
runs and the result is recorded in the audit trail.

This is the default. If no review gate is configured and no directives are active, the
ensemble operates autonomously.

### Advisory

A human provides guidance that influences future task executions. The guidance is
non-blocking: the ensemble does not pause to wait for it. The human sends it when they
think of it.

"Guest in 801 is a VIP, prioritize all their requests." This is a **directive**. It is
injected into the ensemble's context and influences how agents reason about subsequent
tasks. The agents see the directive as additional context in their prompts.

```json
{
  "type": "directive",
  "to": "room-service",
  "from": "manager:human",
  "content": "Guest in 801 is VIP, prioritize all their requests"
}
```

Directives are stored by the ensemble and injected as context into task executions until
they expire (optional TTL) or are superseded by a newer directive.

### Notifiable

The ensemble alerts a human about something noteworthy but does not wait for a response.
The ensemble proceeds with its best-effort handling.

"Water leak detected in room 305, maintenance dispatched." The maintenance ensemble handles
the emergency. It also sends a notification to the manager. The manager sees it on the
dashboard (if connected) or in the notification log (when she connects later). She does
not need to do anything -- the ensemble handled it. But she is informed.

### Approvable

The ensemble produces output and presents it for human review. If a human is available,
they approve, edit, or reject. If no human is available within the timeout, the system
applies a default action (usually auto-approve).

This is the existing v2.1.0 review gate model. A task pauses after producing output. The
browser dashboard shows the review panel with Approve, Edit, and Exit Early buttons. A
countdown timer shows the remaining timeout. When the timer expires, the configured
`onTimeout` action (CONTINUE or EXIT_EARLY) is applied automatically.

```java
Task.builder()
    .description("Draft marketing email")
    .review(Review.builder()
        .prompt("Review the draft before sending")
        .timeout(Duration.ofMinutes(5))
        .onTimeout(OnTimeoutAction.CONTINUE)
        .build())
    .build();
```

Approvable tasks degrade gracefully: if nobody is watching, the work still gets done.

### Gated

The task cannot proceed without specific human authorization. There is no timeout. The
task waits indefinitely for a qualified human to connect and make a decision.

Opening the hotel safe requires the manager. Not the receptionist, not the bell staff.
The manager specifically. If the manager is not present, the safe stays closed. When the
manager arrives, she sees the pending authorization request, approves it, and the task
proceeds.

```java
Task.builder()
    .description("Open the hotel safe for cash reconciliation")
    .review(Review.builder()
        .prompt("Manager authorization required to open the safe")
        .requiredRole("manager")
        .timeout(Duration.ZERO)  // no timeout -- wait indefinitely
        .build())
    .build();
```

The `requiredRole` field is the key addition. It restricts who can make the decision.
A human connected to the dashboard with the "receptionist" role sees the pending review
but cannot act on it -- it is grayed out. When a human with the "manager" role connects,
the review lights up and becomes actionable.

### Out-of-Band Notification

For gated tasks, the ensemble should not silently wait. It should actively seek a qualified
human:

```java
Review.builder()
    .prompt("Manager authorization required")
    .requiredRole("manager")
    .timeout(Duration.ZERO)
    .notifyVia(ReviewNotifier.slack("#ops-channel"))
    .build()
```

When the gated review fires and no qualified human is connected, the ensemble:
1. Queues the review
2. Sends a Slack notification: "Review pending: Manager authorization required to open the
   hotel safe. Connect to approve: https://dashboard.hotel.example/network"
3. Continues waiting
4. When the manager connects (maybe from home on their phone), they see the pending review
5. They approve, and the task resumes

The ensemble did not stall. Other tasks continued processing. Only the specific gated task
waited. The notification brought the right human to the right decision point.

## Directives: Non-Blocking Guidance

Directives are the most common form of human interaction in a running network. They are
the verbal instructions a manager gives while walking around the hotel.

### Context Directives

Context directives add information that agents incorporate into their reasoning:

"The heating system is being serviced today. Route HVAC complaints to the service team,
not our maintenance."

This is injected into the maintenance ensemble's context. When a maintenance agent
receives an HVAC complaint, the directive appears in its prompt as additional context.
The agent reasons about it and routes the complaint accordingly.

Context directives have an optional TTL. "VIP in 801 until Sunday" expires automatically.
Without a TTL, the directive persists until explicitly removed or superseded.

### Control Plane Directives

Control plane directives change ensemble behavior at runtime:

**Model tier switching**: "Switch the kitchen ensemble to the fallback (cheaper) LLM."

```json
{
  "type": "directive",
  "to": "kitchen",
  "action": "SET_MODEL_TIER",
  "value": "FALLBACK"
}
```

The ensemble's subsequent tasks use the fallback model. In-flight tasks continue with
their original model. This is for cost management: when the token spend is high, switch
non-critical ensembles to cheaper models.

**Profile application**: "Apply the sporting-event-weekend profile."

```json
{
  "type": "directive",
  "to": "*",
  "action": "APPLY_PROFILE",
  "value": "sporting-event-weekend"
}
```

This adjusts capacity for all ensembles in the network. Chapter 5 covers profiles in
detail.

### Who Can Send Directives?

Directives can come from:
- A human via the dashboard
- An automated policy rule (e.g., "when monthly token spend exceeds $500, switch to
  fallback models")
- Another ensemble (a "management" ensemble could issue directives to "operational"
  ensembles)

The framework does not distinguish between these sources. A directive is a directive. The
`from` field in the message records the source for the audit trail.

## Late-Join: Catching Humans Up

The existing v2.1.0 late-join mechanism works beautifully for the network model.

When a human connects to the dashboard, the server sends a `hello` message containing:
- The current state of all ensembles the human has access to
- The current snapshot of all events since the last ensemble restart
- Any pending review gates awaiting human decision

The human is instantly up to date. They do not need to have been watching continuously.
They connect, see the current state, and start participating.

When the human disconnects, nothing changes for the ensembles. Events continue to be
recorded. When the human reconnects later -- maybe the next morning -- they get the full
snapshot again and are caught up in seconds.

This is the hotel manager arriving at 8am. Within minutes, the dashboard shows her
everything that happened overnight. She does not need to have been present. The state is
there. The pending decisions are there. She catches up and starts her day.

## Role-Based Access

Not all humans see the same things.

The general manager sees all ensembles, all queues, all pending reviews, all metrics. She
can send directives to any ensemble and approve any review gate.

The receptionist sees the front desk ensemble and related cross-department events (guest
check-in/check-out notifications). She can send directives to the front desk and approve
front-desk review gates. She cannot see the kitchen's internal operations or approve
maintenance purchase orders.

The bell staff sees their specific task assignments. They can mark tasks complete but
cannot send directives or approve reviews.

```java
HumanRole manager = HumanRole.builder()
    .name("manager")
    .canObserve("*")
    .canDirect("*")
    .canApprove("*")
    .build();

HumanRole receptionist = HumanRole.builder()
    .name("receptionist")
    .canObserve("front-desk", "room-service")
    .canDirect("front-desk")
    .canApprove("front-desk")
    .subscribesTo("guest.checkout", "guest.checkin")
    .build();
```

Role definitions are configured on the network. The mapping from authenticated user to
role is provided by the organization's identity provider (the framework does not implement
authentication -- it delegates to the existing infrastructure, typically K8s + service
mesh + identity provider).

## The Dashboard as a Portal

The dashboard is not a control room that must be staffed 24/7. It is a **portal** that
humans step through when they want to participate.

Open the portal: see the current state, give direction, handle pending decisions. Close the
portal: the system continues. Open it again tomorrow: catch up and participate again.

This model is natural for organizations where AI systems augment human work rather than
replace it. The humans are still the decision-makers for critical matters. They are the
authority for compliance-sensitive actions. They provide strategic direction. But they do
not need to babysit the system. It runs on its own.
