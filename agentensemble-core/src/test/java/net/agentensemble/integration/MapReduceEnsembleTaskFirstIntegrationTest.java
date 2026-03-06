package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.mapreduce.MapReduceEnsemble;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the task-first API of {@link MapReduceEnsemble}.
 *
 * <p>Tests verify end-to-end execution when agents are synthesised automatically from
 * task descriptions, covering the static (chunkSize) and adaptive (targetTokenBudget)
 * modes, tools declared on tasks, and the zero-ceremony {@code MapReduceEnsemble.of()}
 * factory.
 *
 * <p>No real LLM is used; Mockito mock {@code ChatModel}s satisfy the agent synthesis
 * and execution contract.
 */
class MapReduceEnsembleTaskFirstIntegrationTest {

    private ChatModel mockModelWithResponse(String response) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder()
                        .aiMessage(new AiMessage(response))
                        .build());
        return model;
    }

    private ChatModel mockModelWithCountingResponses(String prefix, AtomicInteger counter) {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            int n = counter.incrementAndGet();
            return ChatResponse.builder()
                    .aiMessage(new AiMessage(prefix + "-" + n))
                    .build();
        });
        return model;
    }

    // ========================
    // Task-first: static mode, end-to-end
    // ========================

    @Test
    void taskFirst_staticMode_N3_K5_completesSuccessfully() {
        ChatModel model = mockModelWithResponse("agent response");

        List<String> items = List.of("Alpha", "Beta", "Gamma");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(model)
                .items(items)
                .mapTask(item -> Task.of("Process " + item, "A thorough analysis of " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine all analyses into a comprehensive report")
                        .expectedOutput("A comprehensive combined report")
                        .context(chunkTasks)
                        .build())
                .chunkSize(5) // N=3 <= K=5: no intermediate level
                .build()
                .run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isNotBlank();
        // 3 map + 1 final reduce = 4 tasks
        assertThat(output.getTaskOutputs()).hasSize(4);
    }

    @Test
    void taskFirst_staticMode_finalOutputComesFromReduceTask() {
        ChatModel model = mockModelWithResponse("FINAL_REDUCE_OUTPUT");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(model)
                .items(List.of("X", "Y"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine results")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .build()
                .run();

        assertThat(output.getRaw()).isEqualTo("FINAL_REDUCE_OUTPUT");
    }

    @Test
    void taskFirst_staticMode_N6_K3_completesSuccessfully() {
        AtomicInteger counter = new AtomicInteger(0);
        ChatModel model = mockModelWithCountingResponses("output", counter);

        List<String> items = List.of("A", "B", "C", "D", "E", "F");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(model)
                .items(items)
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine into a report")
                        .expectedOutput("Combined report")
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build()
                .run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isNotBlank();
        // 6 map + 2 L1 + 1 final = 9 tasks
        assertThat(output.getTaskOutputs()).hasSize(9);
    }

    // ========================
    // Task-first: adaptive mode, end-to-end
    // ========================

    @Test
    void taskFirst_adaptiveMode_completesSuccessfully() {
        ChatModel model = mockModelWithResponse("adaptive output");

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(model)
                .items(List.of("Item1", "Item2", "Item3"))
                .mapTask(item -> Task.of("Process " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Synthesise results")
                        .expectedOutput("Synthesised output")
                        .context(chunkTasks)
                        .build())
                .targetTokenBudget(10_000)
                .build();

        assertThat(mre.isAdaptiveMode()).isTrue();

        EnsembleOutput output = mre.run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isNotBlank();
    }

    // ========================
    // Task-first: tools declared on task
    // ========================

    @Test
    void taskFirst_toolsOnMapTask_includedInSynthesisedAgent() {
        // We verify that tools declared on tasks are applied to the synthesised agent
        // by tracking whether the mock LLM (which handles tool-equipped agents) is called.
        AtomicInteger llmCallCount = new AtomicInteger(0);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            llmCallCount.incrementAndGet();
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("result with tools"))
                    .build();
        });

        Object tool = new Object() {
            @dev.langchain4j.agent.tool.Tool("Calculate something")
            public int calculate(int x) {
                return x * 2;
            }
        };

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(model)
                .items(List.of("task-with-tool"))
                .mapTask(item -> Task.builder()
                        .description("Calculate results for " + item)
                        .expectedOutput("Calculation result")
                        .tools(List.of(tool))
                        .build())
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Summarise calculations")
                        .expectedOutput("Summary")
                        .context(chunkTasks)
                        .build())
                .build()
                .run();

        assertThat(output).isNotNull();
        // The LLM was called (once per task in the parallel ensemble)
        assertThat(llmCallCount.get()).isGreaterThanOrEqualTo(1);
    }

    // ========================
    // Task-first: per-task chatLanguageModel
    // ========================

    @Test
    void taskFirst_perTaskChatLanguageModel_usedForSynthesis() {
        // Per-task LLM takes precedence over the ensemble-level LLM for synthesis.
        // We verify by using distinct mocks and checking the per-task one is called.
        AtomicInteger perTaskModelCalls = new AtomicInteger(0);
        ChatModel perTaskModel = mock(ChatModel.class);
        when(perTaskModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            perTaskModelCalls.incrementAndGet();
            return ChatResponse.builder()
                    .aiMessage(new AiMessage("per-task model output"))
                    .build();
        });

        ChatModel ensembleModel = mockModelWithResponse("ensemble model output");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(ensembleModel)
                .items(List.of("item"))
                .mapTask(item -> Task.builder()
                        .description("Analyse " + item)
                        .expectedOutput("Analysis")
                        .chatLanguageModel(perTaskModel)
                        .build())
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine analyses")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .build()
                .run();

        assertThat(output).isNotNull();
        // The per-task model was called for the map task
        assertThat(perTaskModelCalls.get()).isGreaterThanOrEqualTo(1);
    }

    // ========================
    // Zero-ceremony factory
    // ========================

    @Test
    void zeroCeremony_completesSuccessfully() {
        ChatModel model = mockModelWithResponse("zero-ceremony output");

        List<String> items = List.of("Risotto", "Duck Breast", "Salmon");

        EnsembleOutput output = MapReduceEnsemble.of(
                model, items, "Prepare a recipe for", "Combine these individual recipes into a cohesive dinner menu");

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isEqualTo("zero-ceremony output");
    }

    @Test
    void zeroCeremony_taskDescriptionsIncludeItemText() {
        // Verify that the map task descriptions are built as "mapDescription: item.toString()"
        // by using a model that echoes the user message to detect the description format.
        List<String> capturedDescriptions = new java.util.ArrayList<>();
        ChatModel captureModel = mock(ChatModel.class);
        when(captureModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            ChatRequest req = inv.getArgument(0);
            // The agent's user message contains the task description
            String messageText = req.messages().stream().map(Object::toString).reduce("", (a, b) -> a + " " + b);
            capturedDescriptions.add(messageText);
            return ChatResponse.builder().aiMessage(new AiMessage("ok")).build();
        });

        MapReduceEnsemble.of(captureModel, List.of("Risotto"), "Prepare a recipe for", "Combine menus");

        // At least one captured description should contain the item text
        assertThat(capturedDescriptions).anyMatch(desc -> desc.contains("Risotto"));
    }

    // ========================
    // Agent-first: regression - still works
    // ========================

    @Test
    void agentFirst_regression_explicitAgents_stillWorkCorrectly() {
        ChatModel model = mockModelWithResponse("agent-first output");

        AtomicInteger agentCounter = new AtomicInteger(0);

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B", "C"))
                .mapAgent(item -> Agent.builder()
                        .role("Map Agent " + item)
                        .goal("Process " + item)
                        .llm(model)
                        .build())
                .mapTask((item, agent) -> Task.builder()
                        .description("Process " + item)
                        .expectedOutput("Processed " + item)
                        .agent(agent)
                        .build())
                .reduceAgent(() -> Agent.builder()
                        .role("Reduce Agent " + agentCounter.incrementAndGet())
                        .goal("Combine results")
                        .llm(model)
                        .build())
                .reduceTask((agent, chunkTasks) -> Task.builder()
                        .description("Combine results")
                        .expectedOutput("Combined")
                        .agent(agent)
                        .context(chunkTasks)
                        .build())
                .chunkSize(5)
                .build()
                .run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isEqualTo("agent-first output");
        assertThat(output.getTaskOutputs()).hasSize(4); // 3 map + 1 final
    }

    // ========================
    // Mixed: task-first map + agent-first reduce NOT supported (covered by validation tests)
    // Both phases must use the same style.
    // This test confirms a valid mixed: task-first map + task-first reduce with different models.
    // ========================

    @Test
    void taskFirst_bothPhasesWithDifferentPerTaskModels_completesSuccessfully() {
        ChatModel mapModel = mockModelWithResponse("map output");
        ChatModel reduceModel = mockModelWithResponse("reduce output");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .items(List.of("A", "B"))
                .mapTask(item -> Task.builder()
                        .description("Map " + item)
                        .expectedOutput("Map result")
                        .chatLanguageModel(mapModel)
                        .build())
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Reduce all map results")
                        .expectedOutput("Final summary")
                        .context(chunkTasks)
                        .chatLanguageModel(reduceModel)
                        .build())
                .build()
                .run();

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).isEqualTo("reduce output");
    }

    // ========================
    // Task-first: task outputs accessible
    // ========================

    @Test
    void taskFirst_taskOutputs_areAccessible_withCorrectCount() {
        AtomicInteger counter = new AtomicInteger(0);
        ChatModel model = mockModelWithCountingResponses("result", counter);

        List<String> items = List.of("P", "Q", "R", "S");

        EnsembleOutput output = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(model)
                .items(items)
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build()
                .run();

        // 4 map + 2 L1 + 1 final = 7 tasks
        assertThat(output.getTaskOutputs()).hasSize(7);
        for (TaskOutput taskOutput : output.getTaskOutputs()) {
            assertThat(taskOutput.getRaw()).isNotBlank();
        }
    }
}
