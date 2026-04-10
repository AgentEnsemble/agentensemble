# External Workflow Integration (Temporal, Step Functions, etc.)

The `agentensemble-executor` module lets you call AgentEnsemble **directly in-process** from any
external workflow engine -- no HTTP server, no network hop, no Temporal SDK dependency required
inside this library.

Two execution modes are available:

| Class | Granularity | Best for |
|---|---|---|
| `TaskExecutor` | One task = one external activity | Temporal workflows where each AgentEnsemble task is a separate activity with its own retry policy, timeout, and heartbeat |
| `EnsembleExecutor` | One ensemble = one external activity | Simpler pipelines where AgentEnsemble's internal orchestration handles the full run inside a single activity |

Two test doubles are also provided so your Temporal activities can be tested without a real LLM:

| Class | Extends |
|---|---|
| `FakeTaskExecutor` | `TaskExecutor` |
| `FakeEnsembleExecutor` | `EnsembleExecutor` |

---

## Heartbeats

**Yes -- heartbeats still work.** The `HeartbeatEnsembleListener` bridges `EnsembleListener`
lifecycle events to any `Consumer<Object>`. You pass Temporal's heartbeat method as the consumer:

```java
executor.execute(request, Activity.getExecutionContext()::heartbeat);
```

The consumer receives a `HeartbeatDetail` record for each event:

| `eventType` | When fired |
|---|---|
| `task_started` | Agent begins executing a task |
| `task_completed` | Agent finishes a task successfully |
| `task_failed` | A task fails with an exception |
| `tool_call` | Agent invokes a tool within the ReAct loop |
| `iteration_started` | New ReAct iteration begins (LLM call pending) |
| `iteration_completed` | ReAct iteration finishes (LLM response received) |

`HeartbeatDetail` is a plain Java record serializable by Temporal's default Jackson
`DataConverter`. Temporal stores the latest heartbeat detail in the activity's history, visible
in the Temporal UI and accessible from the workflow via `Activity.getLastHeartbeatDetails()`.

---

## Adding the Dependency

```kotlin
// build.gradle.kts (in your Temporal worker project)
dependencies {
    implementation("net.agentensemble:agentensemble-executor:$agentEnsembleVersion")
    // Optional: whichever tool modules the agents need
    implementation("net.agentensemble:agentensemble-tools-datetime:$agentEnsembleVersion")
}
```

---

## Full Temporal Integration: Task-per-Activity

This is the recommended Temporal pattern. Each `TaskRequest` maps to one Temporal activity.
The Temporal workflow sequences activities and passes upstream outputs as context entries.

### 1. Define the Activity interface (in your Temporal project)

```java
@ActivityInterface
public interface ResearchPipelineActivity {

    @ActivityMethod
    TaskResult research(TaskRequest request);

    @ActivityMethod
    TaskResult write(TaskRequest request);
}
```

### 2. Implement the activities

Accept `TaskExecutor` by concrete type -- `FakeTaskExecutor` (a subtype) can then be injected
in tests without any additional interface.

```java
public class ResearchPipelineActivityImpl implements ResearchPipelineActivity {

    private final TaskExecutor executor;

    /** Production constructor. */
    public ResearchPipelineActivityImpl() {
        this(new TaskExecutor(
            SimpleModelProvider.of(
                OpenAiChatModel.builder()
                    .apiKey(System.getenv("OPENAI_API_KEY"))
                    .modelName("gpt-4o-mini")
                    .build()),
            SimpleToolProvider.builder()
                .tool("web-search", new WebSearchTool(System.getenv("SEARCH_API_KEY")))
                .tool("datetime", new DateTimeTool())
                .build()));
    }

    /** Package-private constructor for testing -- accepts FakeTaskExecutor. */
    ResearchPipelineActivityImpl(TaskExecutor executor) {
        this.executor = executor;
    }

    @Override
    public TaskResult research(TaskRequest request) {
        // heartbeat() keeps the activity alive during long LLM / tool-call chains
        return executor.execute(request, Activity.getExecutionContext()::heartbeat);
    }

    @Override
    public TaskResult write(TaskRequest request) {
        return executor.execute(request, Activity.getExecutionContext()::heartbeat);
    }
}
```

### 3. Write the Temporal workflow

The workflow orchestrates activities, passes upstream outputs as `context` entries, and handles
all retry and timeout semantics via Temporal's standard policies.

```java
@WorkflowInterface
public interface ResearchWorkflow {
    @WorkflowMethod
    String run(String topic);
}

public class ResearchWorkflowImpl implements ResearchWorkflow {

    private static final ActivityOptions ACTIVITY_OPTIONS = ActivityOptions.newBuilder()
            // Allow up to 30 minutes per activity (LLM chains can be slow)
            .setScheduleToCloseTimeout(Duration.ofMinutes(30))
            // Temporal marks the activity as failed if no heartbeat arrives within 2 minutes.
            // HeartbeatEnsembleListener fires on every LLM iteration and tool call, so
            // the 2-minute window is generous for typical agent workloads.
            .setHeartbeatTimeout(Duration.ofMinutes(2))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build())
            .build();

    private final ResearchPipelineActivity activity =
            Workflow.newActivityStub(ResearchPipelineActivity.class, ACTIVITY_OPTIONS);

    @Override
    public String run(String topic) {

        // Activity 1: Research
        TaskResult research = activity.research(
                TaskRequest.builder()
                        .description("Research the latest developments in {topic}")
                        .expectedOutput("A comprehensive, accurate research summary")
                        .agent(AgentSpec.builder()
                                .role("Research Analyst")
                                .goal("Find accurate, up-to-date information on any topic")
                                .toolNames(List.of("web-search", "datetime"))
                                .build())
                        .inputs(Map.of("topic", topic))
                        .build());

        // Activity 2: Write -- injects research output as a template variable {research}
        TaskResult article = activity.write(
                TaskRequest.builder()
                        .description("Write a blog post about {topic} using this research: {research}")
                        .expectedOutput("A well-structured, engaging 500-word blog post")
                        .agent(AgentSpec.of("Technical Writer", "Write clear, compelling content"))
                        .context(Map.of("research", research.output()))   // <-- upstream output
                        .inputs(Map.of("topic", topic))
                        .build());

        return article.output();
    }
}
```

### 4. Register the Temporal worker

```java
WorkflowServiceStubs service = WorkflowServiceStubs.newLocalServiceStubs();
WorkflowClient client = WorkflowClient.newInstance(service);
WorkerFactory factory = WorkerFactory.newInstance(client);

Worker worker = factory.newWorker("research-task-queue");
worker.registerWorkflowImplementationTypes(ResearchWorkflowImpl.class);
worker.registerActivitiesImplementations(new ResearchPipelineActivityImpl());

factory.start();
```

### 5. Start a workflow from a client

```java
ResearchWorkflow workflow = client.newWorkflowStub(
        ResearchWorkflow.class,
        WorkflowOptions.newBuilder()
                .setTaskQueue("research-task-queue")
                .setWorkflowId("research-" + UUID.randomUUID())
                .build());

String result = workflow.run("Artificial Intelligence");
System.out.println(result);
```

---

## Testing Temporal Activities and Workflows

Use `FakeTaskExecutor` to test both activity implementations and the full workflow
(via `TestWorkflowEnvironment`) without any LLM calls.

### Unit-testing a single activity

```java
import net.agentensemble.executor.FakeTaskExecutor;
import net.agentensemble.executor.TaskRequest;
import net.agentensemble.executor.TaskResult;
import static org.assertj.core.api.Assertions.assertThat;

class ResearchActivityTest {

    @Test
    void research_callsExecutorWithCorrectRequest_returnsResult() {
        // Arrange
        FakeTaskExecutor fake = FakeTaskExecutor.builder()
                .whenDescriptionContains("Research", "AI is advancing rapidly in 2026.")
                .defaultOutput("Unexpected request")
                .build();

        var activity = new ResearchPipelineActivityImpl(fake);

        var request = TaskRequest.builder()
                .description("Research the latest developments in {topic}")
                .expectedOutput("A research summary")
                .inputs(Map.of("topic", "AI"))
                .build();

        // Act
        TaskResult result = activity.research(request);

        // Assert
        assertThat(result.output()).isEqualTo("AI is advancing rapidly in 2026.");
        assertThat(result.isComplete()).isTrue();
    }
}
```

### Integration-testing the full Temporal workflow

Use Temporal's `TestWorkflowEnvironment` to run the real workflow code with fake activity
implementations -- no LLM, no network, fast deterministic tests.

```java
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.client.WorkflowOptions;
import net.agentensemble.executor.FakeTaskExecutor;

class ResearchWorkflowTest {

    private TestWorkflowEnvironment testEnv;
    private static final String TASK_QUEUE = "test-queue";

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();

        // Configure a fake executor for both research and write activities
        FakeTaskExecutor fake = FakeTaskExecutor.builder()
                .whenDescriptionContains("Research", "Research done: AI grows 40% YoY.")
                .whenDescriptionContains("Write",    "Article: AI is reshaping every industry.")
                .build();

        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(ResearchWorkflowImpl.class);
        worker.registerActivitiesImplementations(new ResearchPipelineActivityImpl(fake));
        testEnv.start();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void run_sequencesResearchThenWrite_returnsArticleOutput() {
        ResearchWorkflow workflow = testEnv.newWorkflowStub(
                ResearchWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());

        String result = workflow.run("Artificial Intelligence");

        assertThat(result).isEqualTo("Article: AI is reshaping every industry.");
    }

    @Test
    void run_passesResearchContextToWriteActivity() {
        // Use a custom fake that validates context passing
        List<TaskRequest> capturedRequests = new ArrayList<>();
        FakeTaskExecutor capturingFake = FakeTaskExecutor.builder()
                .whenDescriptionContains("Research", "Research output.")
                .whenDescriptionContains("Write",    "Article output.")
                .build();

        // You can also use Mockito to capture and verify the TaskRequest:
        // TaskExecutor mockExecutor = mock(TaskExecutor.class);
        // when(mockExecutor.execute(any(), any())).thenReturn(new TaskResult("output", 1, 0, "COMPLETED"));
        // ArgumentCaptor<TaskRequest> captor = ArgumentCaptor.forClass(TaskRequest.class);
        // verify(mockExecutor, times(2)).execute(captor.capture(), any());
        // assertThat(captor.getAllValues().get(1).getContext()).containsKey("research");
    }
}
```

### `FakeTaskExecutor` API reference

| Method | Description |
|---|---|
| `FakeTaskExecutor.alwaysReturns(String)` | Returns the same output for every `execute()` call |
| `builder().whenDescriptionContains(substring, output)` | Returns `output` when the request's description contains `substring`; first match wins |
| `builder().whenDescription(Predicate<String>, output)` | Returns `output` when the description predicate is true |
| `builder().whenAgentRole(role, output)` | Returns `output` when `request.getAgent().getRole()` matches |
| `builder().defaultOutput(String)` | Output returned when no rule matches (default: `"Fake task output."`) |

`FakeEnsembleExecutor` has the same API. For multi-task requests, each task is matched
independently; the final task's output becomes `EnsembleResult.finalOutput()`.

---

## Context Passing Between Tasks

Upstream task outputs become template variables in downstream tasks:

```java
// Activity 1: Research returns "AI is growing fast."
TaskResult research = activity.research(
    TaskRequest.builder()
        .description("Research {topic}")
        .expectedOutput("A research summary")
        .inputs(Map.of("topic", topic))
        .build());

// Activity 2: Write -- {research} resolves to the upstream output
TaskResult article = activity.write(
    TaskRequest.builder()
        .description("Write about {topic} using: {research}")
        .expectedOutput("A blog post")
        .context(Map.of("research", research.output()))  // key = template variable name
        .inputs(Map.of("topic", topic))
        .build());
```

Context entries and explicit inputs are merged. Explicit `inputs()` take precedence over
`context()` when both share a key.

---

## Ensemble-per-Activity (Simpler Pipelines)

Use `EnsembleExecutor` when you want AgentEnsemble to handle a full pipeline inside a single
Temporal activity:

```java
public class PipelineActivityImpl implements PipelineActivity {

    private final EnsembleExecutor executor;

    public PipelineActivityImpl() {
        this(new EnsembleExecutor(SimpleModelProvider.of(buildModel())));
    }

    PipelineActivityImpl(EnsembleExecutor executor) {
        this.executor = executor;
    }

    @Override
    public EnsembleResult run(EnsembleRequest request) {
        return executor.execute(request, Activity.getExecutionContext()::heartbeat);
    }
}
```

In tests, inject `FakeEnsembleExecutor`:

```java
FakeEnsembleExecutor fake = FakeEnsembleExecutor.builder()
        .whenDescriptionContains("Research", "Research output.")
        .whenDescriptionContains("Write",    "Article output.")
        .build();

PipelineActivityImpl activity = new PipelineActivityImpl(fake);
```

---

## Model and Tool Provider Configuration

Models and tools are configured on the **worker side** and are never serialized into workflow
history. Use `modelName` in a `TaskRequest` to select a specific model at request time:

```java
ModelProvider models = SimpleModelProvider.builder()
        .model("gpt-4o-mini", cheapModel)
        .model("gpt-4o", premiumModel)
        .defaultModel(cheapModel)
        .build();

// Workflow code -- selecting a named model for a specific task:
TaskRequest.builder()
        .description("Synthesize the final executive summary")
        .expectedOutput("A crisp one-page summary")
        .modelName("gpt-4o")        // resolved by the worker's ModelProvider at run time
        .agent(AgentSpec.of("Executive Synthesizer", "Produce board-level summaries"))
        .build();
```

---

## Works with Any External Orchestrator

The `agentensemble-executor` module has **no Temporal SDK dependency**. The heartbeat consumer
is a plain `Consumer<Object>`. The same executors work with:

- **Temporal** -- `Activity.getExecutionContext()::heartbeat`
- **AWS Step Functions** -- pass a heartbeat callback to a state machine activity poller
- **Kafka Streams** -- call `execute()` inside a `Processor`
- **Spring Batch** -- wrap in a `Tasklet`
- **Plain threads** -- pass `null` for no heartbeating
