# Chapter 10: Testing, Simulation, and Chaos

## You Cannot Set Fire to the Hotel

Testing a single ensemble is straightforward. You define tasks, provide a deterministic
chat model, run the ensemble, and assert on the output. The entire execution happens in
one process, one thread, one test method.

Testing a network of ensembles is a different problem. You have multiple services, multiple
processes, multiple queues, multiple failure modes. You cannot spin up the entire hotel --
all departments, all infrastructure, all connections -- in a unit test. And even if you
could, the most important questions are not "does it work when everything is healthy?" but
"what happens when things go wrong?"

You cannot set fire to the hotel to test the fire response. But you can test the fire
alarms, the sprinkler system, and the evacuation plan separately. And you can run a fire
drill with real people.

This maps to three tiers of testing, each answering different questions at different costs.

## Tier A: Component Tests -- Test the Alarms

Component tests verify each ensemble in isolation. Cross-ensemble dependencies are replaced
with test doubles. These tests are fast, cheap, and run in CI on every commit.

### Stubs

A stub replaces a remote ensemble with a canned response:

```java
NetworkTask procurementStub = NetworkTask.stub("procurement", "purchase-parts",
    "Ordered from SupplyCo, PO #4821, delivery Thursday");

Ensemble maintenance = Ensemble.builder()
    .chatLanguageModel(testModel)
    .task(Task.builder()
        .description("Fix boiler in building 2")
        .tools(procurementStub)
        .build())
    .build();

EnsembleOutput output = maintenance.run();
assertThat(output.getRaw()).contains("Thursday");
```

The maintenance ensemble does not know the procurement response is fake. Its agent calls
the tool, gets the canned response, and continues reasoning. The test verifies that
maintenance handles the procurement result correctly.

### Recording Doubles

A recording double captures what was sent to a remote ensemble for assertion:

```java
NetworkTask recorder = NetworkTask.recording("procurement", "purchase-parts");

// ... run the maintenance ensemble with the recorder ...

assertThat(recorder.lastRequest()).contains("valve", "X-420", "building 2");
assertThat(recorder.callCount()).isEqualTo(1);
```

This verifies that maintenance sends the right request to procurement without needing
procurement to actually exist.

### Contract Tests

Contract tests verify both sides of an inter-ensemble boundary independently. Each side
tests against the shared contract without the other side being present.

**Caller side** (maintenance): "I send a valid procurement request."

```java
@Test void maintenanceSendsValidProcurementRequest() {
    NetworkTask recorder = NetworkTask.recording("procurement", "purchase-parts");
    maintenanceEnsemble(recorder).run();
    assertThat(recorder.lastRequest()).contains("valve", "model");
}
```

**Provider side** (procurement): "Given a valid request, I produce a valid response."

```java
@Test void procurementHandlesPartRequest() {
    EnsembleOutput output = procurementEnsemble.run(Map.of(
        "request", "Order replacement valve model X-420, urgent"));
    assertThat(output.getRaw()).containsPattern("PO #\\d+");
    assertThat(output.getRaw()).contains("delivery");
}
```

Neither test requires the other ensemble. Each tests its own side of the contract. If both
pass, the integration should work. This is the same principle as Pact consumer-driven
contract testing, adapted for natural language contracts.

## Tier B: Simulation -- Computer-Model the Evacuation

Simulation models the full network with simulated components: fake LLMs (fast, cheap,
deterministic), time compression (run simulated hours in real minutes), and scenario
definitions (load profiles, failure injection, latency profiles).

Simulation answers questions that component tests cannot:
- "How many kitchen replicas do we need for the conference?"
- "What happens when procurement goes offline for 5 minutes during peak?"
- "Where is the bottleneck when we have 200 concurrent check-ins?"

```java
Simulation sim = Simulation.builder()
    .network(hotelNetwork)
    .scenario(Scenario.builder()
        .name("Conference peak load")
        .load("front-desk", LoadProfile.ramp(0, 200, Duration.ofMinutes(30)))
        .load("room-service", LoadProfile.steady(50))
        .failure("kitchen", FailureProfile.downAt(
            Duration.ofMinutes(15), Duration.ofMinutes(5)))
        .latency("procurement", LatencyProfile.multiply(3.0))
        .build())
    .chatModel(SimulationChatModel.fast())
    .timeCompression(60)  // 1 hour of simulated time = 1 minute of wall time
    .build();

SimulationResult result = sim.run();
```

### SimulationChatModel

The `SimulationChatModel` is not a real LLM. It generates realistic-shaped responses
without making API calls. It is configurable:

- **Response latency**: Simulates the time an LLM takes to respond (configurable per
  "model tier")
- **Response length**: Generates responses of realistic token count
- **Token counting**: Reports accurate token usage for cost estimation
- **Deterministic**: Same input produces same output (seeded randomness)

The simulation does not test LLM quality. It tests system behavior: queuing, routing,
scaling, failure handling, capacity management. The LLM is a latency source and a token
consumer, not an intelligence source, in simulation mode.

### Scenario Definition

A scenario defines the conditions under which the simulation runs:

**LoadProfile**: How much work arrives at each ensemble over time.
- `steady(n)`: N requests per minute, constant
- `ramp(from, to, duration)`: Linearly increasing from `from` to `to` over `duration`
- `spike(base, peak, at, duration)`: Spike from `base` to `peak` at time `at` for
  `duration`, then back to `base`

**FailureProfile**: When ensembles fail and recover.
- `downAt(time, duration)`: Ensemble goes down at `time`, comes back after `duration`

**LatencyProfile**: How much slower an ensemble responds.
- `fixed(duration)`: Add fixed latency to every response
- `multiply(factor)`: Multiply normal latency by `factor`

### SimulationResult

The simulation produces a structured report:

- **Bottlenecks**: Which ensembles hit capacity limits and when
- **Failure cascades**: How one ensemble's failure affected others (circuit breaker trips,
  fallback activations, queue buildup)
- **Capacity report**: Recommended replica counts for each ensemble under the simulated load
- **Token estimate**: Projected token consumption and cost at production LLM prices
- **Timeline**: Detailed event log of every request, response, failure, and recovery

The simulation can be visualized in the same dashboard used for live operation. The
`/simulation` route in agentensemble-viz replays the simulation timeline, showing the same
flow graphs and timeline views that the live dashboard shows.

## Tier C: Chaos Engineering -- Run the Drill with Real People

Simulation predicts behavior. Chaos engineering verifies it.

In a simulation, the LLMs are fake, the connections are simulated, and the failures are
synthetic. In a chaos experiment, the ensembles are real (running in a staging environment),
the LLMs are real (or realistic test doubles), and the failures are injected into the
actual running system.

This is the fire drill. Real people (the operations team) watch the dashboard as faults are
injected. They verify that the alarms fire, the failovers work, and the system recovers.

### Built-In, Not Bolted On

The Ensemble Network's chaos engineering is built into the framework, not an external tool.
This matters because the framework operates at the **application layer**, not the
infrastructure layer.

External chaos tools (Chaos Monkey, Litmus, Gremlin) operate at the infrastructure level:
kill a pod, partition a network, corrupt a disk. They cannot inject application-level faults:
"Drop all `purchase-parts` requests to procurement but keep `check-inventory` working."
"Simulate an LLM timeout on the kitchen ensemble but not on room service." "Degrade the
kitchen's capacity to 50% without killing any pods."

The framework owns the network layer. It can inject precise, semantic faults that external
tools cannot.

### ChaosExperiment

```java
ChaosExperiment experiment = ChaosExperiment.builder()
    .name("Kitchen outage during dinner rush")
    .against(stagingNetwork)

    // Fault schedule
    .at(Duration.ofMinutes(3),
        Fault.latency("procurement", Duration.ofSeconds(30)))
    .at(Duration.ofMinutes(5),
        Fault.kill("kitchen"))
    .at(Duration.ofMinutes(10),
        Fault.restore("kitchen"))

    // Expected behavior (assertions)
    .expect(Assertion.circuitBreakerOpens(
        "room-service", "kitchen", within(Duration.ofSeconds(30))))
    .expect(Assertion.fallbackActivated(
        "room-service", within(Duration.ofMinutes(1))))
    .expect(Assertion.noDataLoss())
    .expect(Assertion.allPendingRequestsResolve(
        within(Duration.ofMinutes(15))))

    .build();

ChaosReport report = experiment.run();
assertThat(report.passed()).isTrue();
```

### Fault Types

| Fault | What it does |
|---|---|
| `kill(ensemble)` | Stops the ensemble (simulates pod death) |
| `restore(ensemble)` | Restarts the ensemble |
| `latency(ensemble, duration)` | Adds latency to all responses |
| `dropMessages(ensemble, rate)` | Drops a percentage of messages |
| `degradeCapacity(ensemble, factor)` | Reduces concurrent task limit |
| `partialFailure(ensemble, task, rate)` | Fails a percentage of specific task requests |

### Assertion Types

| Assertion | What it verifies |
|---|---|
| `circuitBreakerOpens(from, to, within)` | Circuit breaker trips within the expected time |
| `fallbackActivated(ensemble, within)` | Fallback strategy activates |
| `noDataLoss()` | All accepted requests eventually get a response |
| `allPendingRequestsResolve(within)` | No requests are stuck after recovery |
| `queueDrains(ensemble, within)` | Queue returns to normal depth |

### ChaosReport

The report includes:
- **Pass/fail** for each assertion
- **Timeline** of all faults injected and system responses
- **Metrics snapshots** at key moments (before fault, during fault, after recovery)
- **Human observations** (if operators were watching the dashboard during the drill and
  added notes)

The chaos experiment can be run as a CI test (automated, unattended) or as a game day
exercise (operators watching the dashboard, practicing their response procedures).

## The Testing Pyramid

```
          /\
         /  \  C. Chaos drills
        /    \     Real systems, real faults, real people
       /------\    Rare, expensive, high-confidence
      /        \
     /          \  B. Simulation
    /            \     Full network, simulated components
   /--------------\    Regular, moderate cost, good coverage
  /                \
 /                  \  A. Component tests
/                    \     Isolated ensembles, stubs, contracts
/--------------------\    Frequent, cheap, fast feedback
```

Each tier builds on the one below it. Component tests verify that each piece works.
Simulation predicts how the pieces interact. Chaos engineering proves it.

Together, they answer the three questions:
- Do the alarms work? (component tests)
- What does the computer model predict? (simulation)
- Does the drill go as planned? (chaos engineering)

You still cannot set fire to the hotel. But you can be confident that when the fire alarm
goes off, the sprinklers activate, the exits are clear, and the staff know what to do.
