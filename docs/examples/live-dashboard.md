# Live Execution Dashboard

This example demonstrates how to attach the embedded WebSocket dashboard to an ensemble,
stream real-time execution events to a browser, and use browser-based review gates for
human approval.

---

## Dependencies

```kotlin
// build.gradle.kts
dependencies {
    implementation("net.agentensemble:agentensemble-core:2.1.0")
    implementation("net.agentensemble:agentensemble-web:2.1.0")
    implementation("net.agentensemble:agentensemble-review:2.1.0")
    implementation("dev.langchain4j:langchain4j-open-ai:1.11.0")
}
```

---

## Basic Streaming (No Review Gates)

The simplest use case: stream all task and tool events to any browser without any
human-in-the-loop gates.

```java
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.web.WebDashboard;
import dev.langchain4j.model.openai.OpenAiChatModel;

var model = OpenAiChatModel.builder()
    .apiKey(System.getenv("OPENAI_API_KEY"))
    .modelName("gpt-4o-mini")
    .build();

var researcher = Agent.builder()
    .role("Senior Research Analyst")
    .goal("Research the topic thoroughly")
    .llm(model)
    .build();

var writer = Agent.builder()
    .role("Content Writer")
    .goal("Write clear, engaging content")
    .llm(model)
    .build();

var researchTask = Task.builder()
    .description("Research the latest developments in quantum computing")
    .expectedOutput("A 300-word research summary")
    .agent(researcher)
    .build();

var writeTask = Task.builder()
    .description("Write a blog post about quantum computing based on the research")
    .expectedOutput("A 500-word blog post")
    .agent(writer)
    .build();

// Start the WebSocket server on port 7329 and attach it to the ensemble.
// Open http://localhost:7329 in a browser to see the live event stream.
EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)
    .task(researchTask)
    .task(writeTask)
    .webDashboard(WebDashboard.onPort(7329))
    .build()
    .run();

System.out.println(output.getRaw());
```

**What the browser receives:**

```json
{"type":"hello","serverId":"ae-...","serverTime":"2026-03-05T20:00:00Z"}
{"type":"task_started","taskIndex":1,"totalTasks":2,"taskDescription":"Research the latest developments in quantum computing","agentRole":"Senior Research Analyst"}
{"type":"tool_called","toolName":"web_search","input":"quantum computing 2026 breakthroughs","output":"...","durationMs":820}
{"type":"task_completed","taskIndex":1,"totalTasks":2,"taskDescription":"Research the latest developments in quantum computing","agentRole":"Senior Research Analyst","rawOutput":"Quantum computing in 2026...","toolCallCount":2,"durationMs":4200}
{"type":"task_started","taskIndex":2,"totalTasks":2,...}
{"type":"task_completed","taskIndex":2,"totalTasks":2,...,"rawOutput":"# Quantum Leap..."}
{"type":"ensemble_completed","exitReason":"COMPLETED","totalDurationMs":9100}
```

---

## With Browser-Based Review Gates

This example adds a review gate after the first task. The ensemble pauses and all connected
browsers display an approval panel.

```java
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.Review;
import net.agentensemble.web.WebDashboard;

// Configure the dashboard with a 10-minute review timeout.
// If no browser responds within 10 minutes, execution continues automatically.
var dashboard = WebDashboard.builder()
    .port(7329)
    .reviewTimeout(Duration.ofMinutes(10))
    .onTimeout(OnTimeoutAction.CONTINUE)
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(researcher)
    .agent(writer)

    .task(Task.builder()
        .description("Research the latest AI safety developments")
        .expectedOutput("A comprehensive research summary covering key events, key players, and current debates")
        .agent(researcher)
        .review(Review.required())      // pause here -- browser must approve before writing begins
        .build())

    .task(Task.builder()
        .description("Write a 600-word blog post based on the research")
        .expectedOutput("A 600-word blog post ready for publication")
        .agent(writer)
        .build())

    .webDashboard(dashboard)
    .build()
    .run();

// Check whether the reviewer stopped the pipeline early
if (output.getExitReason() == ExitReason.USER_EXIT_EARLY) {
    System.out.println("Pipeline stopped at review gate.");
    System.out.println("Completed tasks: " + output.getTaskOutputs().size());
} else {
    System.out.println("Blog post: " + output.getRaw());
}
```

**What the browser shows when the review gate fires:**

```
+--------------------------------------------------------------------+
| Review Required                                                     |
+--------------------------------------------------------------------+
| Task: Research the latest AI safety developments                    |
|                                                                     |
| Agent output:                                                       |
| AI safety in 2026 has been dominated by three major themes:        |
| interpretability research from leading labs, international          |
| governance negotiations in Geneva, and the rise of alignment        |
| benchmarks...                                                       |
|                                                                     |
| [Approve]   [Edit]   [Exit Early]                                   |
|                                                                     |
| Auto-continue in 9:42 ...                                           |
+--------------------------------------------------------------------+
```

---

## Parallel Workflow with Dashboard

The dashboard also works with parallel and hierarchical workflows. Events for concurrently
executing tasks arrive in whichever order they complete.

```java
import net.agentensemble.Workflow;

var marketTask = Task.builder()
    .description("Gather market data for the EV industry")
    .expectedOutput("Key market statistics and trends")
    .agent(analyst)
    .build();

var competitorTask = Task.builder()
    .description("Research the top three EV manufacturers")
    .expectedOutput("Competitor profiles with strengths and weaknesses")
    .agent(researcher)
    .build();

var reportTask = Task.builder()
    .description("Write an investment report based on the market and competitor research")
    .expectedOutput("A 1000-word investment report")
    .agent(writer)
    .context(List.of(marketTask, competitorTask))   // depends on both parallel tasks
    .review(Review.required())                       // review the report before returning
    .build();

WebDashboard dashboard = WebDashboard.builder()
    .port(7329)
    .reviewTimeout(Duration.ofMinutes(15))
    .onTimeout(OnTimeoutAction.EXIT_EARLY)           // stop if no browser decision in 15 min
    .build();

EnsembleOutput output = Ensemble.builder()
    .agent(analyst)
    .agent(researcher)
    .agent(writer)
    .task(marketTask)
    .task(competitorTask)
    .task(reportTask)
    .workflow(Workflow.PARALLEL)
    .webDashboard(dashboard)
    .build()
    .run();

System.out.printf("Exit reason: %s%n", output.getExitReason());
System.out.printf("Final output: %s%n", output.getRaw());
```

---

## Ephemeral Port

Use `port(0)` to let the OS assign a free port. This is useful in CI or test environments
where a hardcoded port might be occupied.

```java
WebDashboard dashboard = WebDashboard.builder()
    .port(0)
    .build();

EnsembleOutput output = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Analyse the dataset"))
    .webDashboard(dashboard)
    .build()
    .run();

// actualPort() returns the OS-assigned port (only valid after the server has started)
System.out.printf("Dashboard was available at http://localhost:%d%n", dashboard.actualPort());
```

---

## Reusing a Dashboard Across Multiple Runs

Create the dashboard once and reuse it across sequential or concurrent ensemble runs. The
server starts on the first call to `.webDashboard()` and keeps running until the JVM exits.

```java
WebDashboard dashboard = WebDashboard.onPort(7329);

// Run 1: research phase
var researchOutput = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.of("Research AI trends"))
    .webDashboard(dashboard)
    .build()
    .run();

// Run 2: writing phase -- server already running; not restarted
var writeOutput = Ensemble.builder()
    .chatLanguageModel(model)
    .task(Task.builder()
        .description("Write a report based on: " + researchOutput.getRaw())
        .expectedOutput("A polished report")
        .build())
    .webDashboard(dashboard)
    .build()
    .run();
```

---

## Running the Example

```bash
export OPENAI_API_KEY=your-api-key

# Basic streaming dashboard on port 7329
./gradlew :agentensemble-examples:runLiveDashboard

# With browser-based review gates
./gradlew :agentensemble-examples:runLiveDashboardWithReview
```

Open `http://localhost:7329` in a browser, then run the Gradle task in another terminal.
Events appear in real time as the ensemble executes.

---

## Related Documentation

- [Live Dashboard Guide](../guides/live-dashboard.md) -- Full API reference, configuration, and protocol
- [Human-in-the-Loop Review](../examples/human-in-the-loop.md) -- Review timing, policies, and console vs. browser approval
- [Review Guide](../guides/review.md) -- Review configuration, `OnTimeoutAction`, and `ReviewPolicy`
- [Design: Live Execution Dashboard](../design/16-live-dashboard.md) -- Architecture and WebSocket protocol specification
