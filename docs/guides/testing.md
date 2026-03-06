# Testing

This guide covers patterns for writing deterministic, no-network unit and integration tests
for AgentEnsemble workflows.

---

## Stub ChatModel

All AgentEnsemble tests that exercise the execution path need a `ChatModel` that returns
canned responses without making real API calls. In LangChain4j 1.x, the `ChatModel`
interface defines two override points:

| Method | Description |
|---|---|
| `doChat(ChatRequest)` | Primary override point for LangChain4j 1.x; default throws `RuntimeException("Not implemented")` |
| `chat(ChatRequest)` | Default implementation calls `doChat()`; override this directly as an alternative |

**Override `doChat(ChatRequest)` (recommended for LangChain4j 1.x):**

```java
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import java.util.concurrent.atomic.AtomicInteger;

class StubChatModel implements ChatModel {

    private final String[] responses;
    private final AtomicInteger index = new AtomicInteger(0);

    StubChatModel(String... responses) {
        this.responses = responses.clone();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        int i = index.getAndIncrement();
        String text = responses[Math.min(i, responses.length - 1)];
        return ChatResponse.builder()
                .aiMessage(new AiMessage(text))
                .build();
    }
}
```

!!! warning "Common mistake: overriding the wrong method"
    A `ChatModel` that overrides only a convenience method (such as `chat(String)` from
    the v1.x API) but not `chat(ChatRequest)` or `doChat(ChatRequest)` will hit the
    default `doChat()` stub and throw `RuntimeException: Not implemented` at runtime.
    Always override one of the two methods in the table above.

---

## Agent Synthesis and Stub Models

When building a task-first ensemble (no explicit `.agent()` on tasks), the framework
synthesizes agents during `Ensemble.resolveAgents()` -- **before the first task executes**.
The behaviour at synthesis time depends on which `AgentSynthesizer` is configured:

| Synthesizer | LLM call at synthesis time? | Safe with stub model? |
|---|---|---|
| `AgentSynthesizer.template()` (default) | No | Yes |
| `AgentSynthesizer.llmBased()` | Yes, one call per agentless task | Only if the stub returns valid JSON |

### Default behaviour (`template()`)

The default synthesizer makes no LLM call. It derives the agent role from the task
description using a verb-to-role lookup table. A stub model only needs to respond to
actual task execution prompts:

```java
StubChatModel model = new StubChatModel("Research complete: AI trends summary...");

// No agentSynthesizer() call -> uses AgentSynthesizer.template() by default
EnsembleOutput result = Ensemble.builder()
        .chatLanguageModel(model)
        .task(Task.of("Research the latest AI trends"))
        .build()
        .run();
```

This is the **recommended pattern for tests**. The stub only needs as many responses as
there are task iterations.

### Using `llmBased()` in tests

If the test specifically covers LLM-based synthesis, the stub must respond to the
synthesis prompt (which comes before task execution prompts) and return valid JSON:

```java
// Response 0: synthesis prompt -> JSON persona
// Response 1: task execution prompt -> final answer
StubChatModel model = new StubChatModel(
        "{\"role\": \"Researcher\", \"goal\": \"Research AI trends\", \"backstory\": \"Expert researcher.\"}",
        "Research complete: AI adoption grew 42% in 2025.");

EnsembleOutput result = Ensemble.builder()
        .chatLanguageModel(model)
        .agentSynthesizer(AgentSynthesizer.llmBased())
        .task(Task.of("Research the latest AI trends"))
        .build()
        .run();
```

The JSON response must include `role` and `goal` fields (both non-blank). `backstory` is
optional. If the synthesis response is not valid JSON, `LlmBasedAgentSynthesizer` falls
back to template synthesis automatically.

!!! note "Synthesis call ordering"
    `resolveAgents()` iterates tasks in declaration order. Each agentless task consumes
    one stub response for synthesis before the execution responses are consumed. Plan
    the stub response array accordingly.

---

## Full Integration Test Example

A complete example for a two-task sequential workflow:

```java
@Test
void twoTaskSequentialWorkflow_contextPassedCorrectly() {
    StubChatModel model = new StubChatModel(
            "Research complete: AI adoption grew 42% in 2025.",
            "Blog post written based on: AI adoption grew 42% in 2025.");

    Task researchTask = Task.builder()
            .description("Research AI adoption trends for 2025")
            .expectedOutput("A concise research report")
            .build();

    Task writeTask = Task.builder()
            .description("Write a blog post summarising the research findings")
            .expectedOutput("A 200-word blog post")
            .context(List.of(researchTask))
            .build();

    EnsembleOutput result = Ensemble.builder()
            .chatLanguageModel(model)
            .task(researchTask)
            .task(writeTask)
            .build()
            .run();

    assertThat(result.isComplete()).isTrue();
    assertThat(result.getTaskOutputs()).hasSize(2);
    assertThat(result.getTaskOutputs().get(0).getRaw())
            .contains("AI adoption grew 42%");
    assertThat(result.getRaw())
            .contains("Blog post written");
}
```

---

## Testing Tasks with Tools

When a task uses tools, the stub model must return a tool-call response for each
iteration before producing a final text answer. Use Mockito to express the multi-step
sequence clearly:

```java
@Test
void taskWithTool_toolCalledAndResultUsed() {
    ChatModel mockLlm = mock(ChatModel.class);

    ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
            .id("1")
            .name("calculator")
            .arguments("{\"expression\": \"42 * 2\"}")
            .build();

    when(mockLlm.doChat(any()))
            .thenReturn(toolCallResponse(toolCall))
            .thenReturn(textResponse("The answer is 84."));

    Task task = Task.builder()
            .description("Calculate 42 times 2")
            .expectedOutput("The numeric result")
            .tools(List.of(new CalculatorTool()))
            .build();

    EnsembleOutput result = Ensemble.builder()
            .chatLanguageModel(mockLlm)
            .task(task)
            .build()
            .run();

    assertThat(result.getRaw()).contains("84");
}
```

---

## Testing Context Dependencies

When a task uses `.context(List.of(upstreamTask))`, the upstream task output is injected
into the downstream task's user prompt. Verify this by capturing the request in the stub:

```java
@Test
void contextTask_outputInjectedIntoDownstreamPrompt() {
    List<ChatRequest> capturedRequests = new ArrayList<>();

    ChatModel capturingModel = new StubChatModel(
            "Upstream result: key finding.",
            "Downstream response using the context.") {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            capturedRequests.add(request);
            return super.doChat(request);
        }
    };

    Task upstreamTask = Task.builder()
            .description("Produce a key finding")
            .expectedOutput("A finding")
            .build();

    Task downstreamTask = Task.builder()
            .description("Summarise the findings")
            .expectedOutput("A summary")
            .context(List.of(upstreamTask))
            .build();

    Ensemble.builder()
            .chatLanguageModel(capturingModel)
            .task(upstreamTask)
            .task(downstreamTask)
            .build()
            .run();

    // The second call's messages should contain the upstream output
    String downstreamUserMessage = capturedRequests.get(1)
            .messages().stream()
            .filter(m -> m instanceof UserMessage)
            .map(m -> ((UserMessage) m).singleText())
            .findFirst()
            .orElse("");
    assertThat(downstreamUserMessage).contains("Upstream result: key finding.");
}
```

---

## Per-task and Ensemble-level Models

For workflows that mix per-task models and ensemble-level models, build separate stubs and
wire each to the appropriate scope:

```java
StubChatModel cheapModel = new StubChatModel("Short summary.");
StubChatModel powerfulModel = new StubChatModel("Detailed analysis with full evidence.");

EnsembleOutput result = Ensemble.builder()
        .chatLanguageModel(cheapModel)                 // ensemble default
        .task(Task.builder()
                .description("Summarise this paragraph")
                .expectedOutput("Two sentences")
                .build())                              // uses cheapModel (ensemble default)
        .task(Task.builder()
                .description("Analyse this financial model in depth")
                .expectedOutput("Full risk analysis")
                .chatLanguageModel(powerfulModel)      // overrides for this task
                .build())
        .build()
        .run();
```
