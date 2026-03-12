# Phases

Phases let you group tasks into named workstreams and declare dependencies between them.
Independent phases run in parallel; a phase only starts when all its declared predecessors
have completed.

---

## Simple Sequential Phases

The simplest use of phases is to give logical names to sequential stages of work.

```java
Phase research = Phase.builder()
    .name("research")
    .task(Task.builder()
        .description("Search for recent papers on retrieval-augmented generation")
        .expectedOutput("List of 10 relevant papers with abstracts")
        .build())
    .task(Task.builder()
        .description("Summarize the key findings from the papers")
        .expectedOutput("Bullet-point summary of themes and findings")
        .build())
    .build();

Phase writing = Phase.builder()
    .name("writing")
    .after(research)   // will not start until research completes
    .task(Task.builder()
        .description("Write an outline for a blog post on RAG")
        .expectedOutput("Structured outline with sections and key points")
        .build())
    .task(Task.builder()
        .description("Write the full blog post from the outline")
        .expectedOutput("2000-word blog post in Markdown")
        .build())
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(research)
    .phase(writing)
    .build()
    .run();

System.out.println(output.getFinalOutput());
```

---

## Parallel Independent Phases

When phases do not depend on each other they run concurrently -- each in its own virtual
thread.

```java
Phase marketResearch = Phase.builder()
    .name("market-research")
    .task(Task.of("Research competitor pricing", "Competitor pricing table"))
    .task(Task.of("Research target demographics", "Demographics summary"))
    .build();

Phase technicalResearch = Phase.builder()
    .name("technical-research")
    .task(Task.of("Assess implementation complexity", "Complexity score and rationale"))
    .task(Task.of("Identify required integrations", "Integration checklist"))
    .build();

Phase report = Phase.builder()
    .name("report")
    .after(marketResearch, technicalResearch)   // waits for both
    .task(Task.builder()
        .description("Write a product feasibility report combining market and technical findings")
        .expectedOutput("Feasibility report with go/no-go recommendation")
        .context(marketResearch.getTasks().get(0))   // cross-phase context
        .context(technicalResearch.getTasks().get(0))
        .build())
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(marketResearch)
    .phase(technicalResearch)
    .phase(report)
    .build()
    .run();
```

`market-research` and `technical-research` start at the same time. `report` starts as
soon as both of them have finished.

---

## Kitchen Scenario: Parallel Convergent Phases

Three independent workstreams (one per dish) all converge into a final serving phase.

```java
// Each dish is prepared independently and in parallel
Phase steak = Phase.builder()
    .name("steak")
    .task(Task.of("Prepare steak", "Seasoned and at room temperature"))
    .task(Task.of("Sear steak", "Medium-rare, rested for 5 minutes"))
    .task(Task.of("Plate steak", "Plated with garnish and sauce"))
    .build();

Phase salmon = Phase.builder()
    .name("salmon")
    .task(Task.of("Prepare salmon", "Skin removed, seasoned"))
    .task(Task.of("Cook salmon", "Crispy skin, fully cooked"))
    .task(Task.of("Plate salmon", "Plated with lemon and herbs"))
    .build();

Phase pasta = Phase.builder()
    .name("pasta")
    .task(Task.of("Boil pasta", "Al dente"))
    .task(Task.of("Make sauce", "Reduced tomato sauce, seasoned"))
    .task(Task.of("Plate pasta", "Pasta and sauce combined, topped with basil"))
    .build();

// Serving only happens once all dishes are ready
Phase serve = Phase.builder()
    .name("serve")
    .after(steak, salmon, pasta)
    .task(Task.of("Deliver all plates", "All three dishes delivered simultaneously"))
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(steak)
    .phase(salmon)
    .phase(pasta)
    .phase(serve)
    .build()
    .run();
```

Execution timeline:

```
t=0  [steak starts]  [salmon starts]  [pasta starts]
t=?                                                   [serve starts when last finishes]
```

---

## Per-Phase Workflow Override

Each phase can use a different workflow strategy. For example, gather data in parallel
then write sequentially.

```java
Phase dataGathering = Phase.builder()
    .name("data-gathering")
    .workflow(Workflow.PARALLEL)   // all data tasks run concurrently
    .task(Task.of("Fetch sales data", "Sales CSV"))
    .task(Task.of("Fetch inventory data", "Inventory CSV"))
    .task(Task.of("Fetch customer data", "Customer CSV"))
    .build();

Phase analysis = Phase.builder()
    .name("analysis")
    .workflow(Workflow.SEQUENTIAL) // analysis tasks depend on each other in order
    .after(dataGathering)
    .task(Task.of("Merge datasets", "Combined dataset"))
    .task(Task.of("Compute metrics", "KPI summary"))
    .task(Task.of("Generate charts description", "Chart descriptions for report"))
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(dataGathering)
    .phase(analysis)
    .build()
    .run();
```

---

## Diamond Dependency Pattern

A classic DAG: two parallel phases both feed into a single converging phase.

```java
Phase A = Phase.of("A", taskA);
Phase B = Phase.of("B", taskB);
Phase C = Phase.of("C", taskC);
Phase D = Phase.builder().name("D").after(B, C).task(taskD).build();

//   [A]   (independent, runs in parallel with B and C but has no successors)
//   [B] --\
//          +--> [D]
//   [C] --/

Ensemble.builder()
    .chatLanguageModel(llm)
    .phase(A)
    .phase(B)
    .phase(C)
    .phase(D)
    .build()
    .run();
```

---

## Phases with Deterministic Tasks

Phases work with deterministic `handler` tasks. No LLM is needed for phases composed
entirely of handler tasks.

```java
Phase fetch = Phase.builder()
    .name("fetch")
    .task(Task.builder()
        .description("Fetch live prices")
        .expectedOutput("JSON price map")
        .handler(ctx -> ToolResult.success(priceApi.fetchAll()))
        .build())
    .build();

Phase analyse = Phase.builder()
    .name("analyse")
    .after(fetch)
    .task(Task.builder()
        .description("Identify the top 3 performing assets")
        .expectedOutput("Ranked list of top 3 assets with rationale")
        .context(fetch.getTasks().get(0))
        .build())
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(llm)   // only needed for the analyse phase
    .phase(fetch)
    .phase(analyse)
    .build()
    .run();
```

---

## Reading Phase Outputs

`EnsembleOutput` provides both a flat list of all task outputs and a phase-keyed map.

```java
EnsembleOutput output = ensemble.run();

// Flat list -- same as before, backward compatible
List<TaskOutput> all = output.getTaskOutputs();

// Phase-keyed map -- new
Map<String, List<TaskOutput>> byPhase = output.getPhaseOutputs();

List<TaskOutput> researchOutputs = byPhase.get("research");
List<TaskOutput> writingOutputs  = byPhase.get("writing");

// Final output is always the last task of the last completed phase
String finalText = output.getFinalOutput();
```
