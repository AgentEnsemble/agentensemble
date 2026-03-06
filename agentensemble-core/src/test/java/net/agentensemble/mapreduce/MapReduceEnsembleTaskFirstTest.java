package net.agentensemble.mapreduce;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MapReduceEnsemble} task-first API (v2.0.0).
 *
 * <p>Tests cover DAG construction and builder behaviour when using the task-first
 * {@code mapTask(Function<T, Task>)}, {@code reduceTask(Function<List<Task>, Task>)},
 * and {@code chatLanguageModel} builder methods.
 *
 * <p>No LLM calls are made; the tests only verify build-time DAG shape and
 * context-wiring.
 */
class MapReduceEnsembleTaskFirstTest {

    private static final ChatModel STUB = new NoOpChatModel();

    // ========================
    // Basic build
    // ========================

    @Test
    void taskFirst_staticMode_buildSucceeds() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine analyses")
                        .expectedOutput("Combined result")
                        .context(chunkTasks)
                        .build())
                .chunkSize(5)
                .build();

        assertThat(mre).isNotNull();
        assertThat(mre.isAdaptiveMode()).isFalse();
    }

    @Test
    void taskFirst_adaptiveMode_buildSucceeds() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine analyses")
                        .expectedOutput("Combined result")
                        .context(chunkTasks)
                        .build())
                .targetTokenBudget(1000)
                .build();

        assertThat(mre).isNotNull();
        assertThat(mre.isAdaptiveMode()).isTrue();
    }

    // ========================
    // Map tasks have no explicit agent
    // ========================

    @Test
    void taskFirst_mapTasks_haveNoExplicitAgent() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(5)
                .build();

        // The 3 map tasks (indices 0-2) should have no explicit agent
        List<Task> tasks = mre.toEnsemble().getTasks();
        for (int i = 0; i < 3; i++) {
            assertThat(tasks.get(i).getAgent())
                    .as("map task %d should have no explicit agent", i)
                    .isNull();
        }
    }

    @Test
    void taskFirst_reduceTasks_haveNoExplicitAgent() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(5)
                .build();

        // The final reduce task (index 3) should have no explicit agent
        List<Task> tasks = mre.toEnsemble().getTasks();
        Task finalTask = tasks.get(tasks.size() - 1);
        assertThat(finalTask.getAgent()).isNull();
    }

    // ========================
    // DAG structure
    // ========================

    @Test
    void taskFirst_N3_K5_noIntermediateLevel_fourTasksTotal() {
        // N=3, K=5 (default): 3 map + 1 final = 4 tasks
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .build(); // default chunkSize=5

        assertThat(mre.toEnsemble().getTasks()).hasSize(4);
    }

    @Test
    void taskFirst_N4_K3_oneIntermediateLevel_sevenTasksTotal() {
        // N=4, K=3: 4 map + 2 L1 + 1 final = 7 tasks
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C", "D"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build();

        assertThat(mre.toEnsemble().getTasks()).hasSize(7);
    }

    @Test
    void taskFirst_N4_K3_contextWiringIsCorrect() {
        List<String> items = List.of("A", "B", "C", "D");
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(items)
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build();

        List<Task> tasks = mre.toEnsemble().getTasks();
        // tasks: [M0, M1, M2, M3, L1-0, L1-1, Final]
        Task m0 = tasks.get(0), m1 = tasks.get(1), m2 = tasks.get(2), m3 = tasks.get(3);
        Task l1_0 = tasks.get(4), l1_1 = tasks.get(5);
        Task finalTask = tasks.get(6);

        assertThat(m0.getContext()).isEmpty();
        assertThat(m1.getContext()).isEmpty();
        assertThat(m2.getContext()).isEmpty();
        assertThat(m3.getContext()).isEmpty();

        assertThat(l1_0.getContext()).containsExactly(m0, m1, m2);
        assertThat(l1_1.getContext()).containsExactly(m3);
        assertThat(finalTask.getContext()).containsExactly(l1_0, l1_1);
    }

    @Test
    void taskFirst_nodeTypes_areCorrect() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C", "D"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build();

        List<Task> tasks = mre.toEnsemble().getTasks();

        // Map tasks (0-3) have node type "map"
        for (int i = 0; i < 4; i++) {
            assertThat(mre.getNodeTypes().get(tasks.get(i))).isEqualTo(MapReduceEnsemble.NODE_TYPE_MAP);
        }
        // L1 tasks (4-5) have node type "reduce"
        assertThat(mre.getNodeTypes().get(tasks.get(4))).isEqualTo(MapReduceEnsemble.NODE_TYPE_REDUCE);
        assertThat(mre.getNodeTypes().get(tasks.get(5))).isEqualTo(MapReduceEnsemble.NODE_TYPE_REDUCE);
        // Final task has node type "final-reduce"
        assertThat(mre.getNodeTypes().get(tasks.get(6))).isEqualTo(MapReduceEnsemble.NODE_TYPE_FINAL_REDUCE);
    }

    @Test
    void taskFirst_mapReduceLevels_areCorrect() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C", "D"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build();

        List<Task> tasks = mre.toEnsemble().getTasks();

        for (int i = 0; i < 4; i++) {
            assertThat(mre.getMapReduceLevels().get(tasks.get(i))).isEqualTo(0);
        }
        assertThat(mre.getMapReduceLevels().get(tasks.get(4))).isEqualTo(1);
        assertThat(mre.getMapReduceLevels().get(tasks.get(5))).isEqualTo(1);
        assertThat(mre.getMapReduceLevels().get(tasks.get(6))).isEqualTo(2);
    }

    // ========================
    // Map factory invocation count
    // ========================

    @Test
    void taskFirst_mapTaskFactory_calledOncePerItem() {
        AtomicInteger callCount = new AtomicInteger(0);

        MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C", "D"))
                .mapTask(item -> {
                    callCount.incrementAndGet();
                    return Task.of("Analyse " + item);
                })
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .chunkSize(3)
                .build();

        assertThat(callCount.get()).isEqualTo(4);
    }

    @Test
    void taskFirst_reduceTaskFactory_calledOncePerGroup() {
        AtomicInteger callCount = new AtomicInteger(0);

        MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A", "B", "C", "D"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> {
                    callCount.incrementAndGet();
                    return Task.builder()
                            .description("Combine")
                            .expectedOutput("Combined")
                            .context(chunkTasks)
                            .build();
                })
                .chunkSize(3)
                .build();

        // 2 L1 groups + 1 final = 3 calls
        assertThat(callCount.get()).isEqualTo(3);
    }

    // ========================
    // chatLanguageModel passthrough
    // ========================

    @Test
    void chatLanguageModel_passedToInnerEnsemble() {
        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A"))
                .mapTask(item -> Task.of("Analyse " + item))
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .build();

        assertThat(mre.toEnsemble().getChatLanguageModel()).isSameAs(STUB);
    }

    // ========================
    // Task-first with per-task chatLanguageModel
    // ========================

    @Test
    void taskFirst_perTaskChatLanguageModel_isPreserved() {
        ChatModel perTaskModel = new NoOpChatModel();

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .items(List.of("A"))
                .mapTask(item -> Task.builder()
                        .description("Analyse " + item)
                        .expectedOutput("Result")
                        .chatLanguageModel(perTaskModel)
                        .build())
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .chatLanguageModel(perTaskModel)
                        .build())
                .build();

        // Tasks should carry their per-task LLM
        List<Task> tasks = mre.toEnsemble().getTasks();
        assertThat(tasks.get(0).getChatLanguageModel()).isSameAs(perTaskModel);
        assertThat(tasks.get(1).getChatLanguageModel()).isSameAs(perTaskModel);
    }

    // ========================
    // Task-first with tools on task
    // ========================

    @Test
    void taskFirst_toolsOnTask_arePreserved() {
        Object toolInstance = new Object() {
            @dev.langchain4j.agent.tool.Tool("a tool")
            public String doThing() {
                return "result";
            }
        };

        MapReduceEnsemble<String> mre = MapReduceEnsemble.<String>builder()
                .chatLanguageModel(STUB)
                .items(List.of("A"))
                .mapTask(item -> Task.builder()
                        .description("Analyse " + item)
                        .expectedOutput("Result")
                        .tools(List.of(toolInstance))
                        .build())
                .reduceTask(chunkTasks -> Task.builder()
                        .description("Combine")
                        .expectedOutput("Combined")
                        .context(chunkTasks)
                        .build())
                .build();

        List<Task> tasks = mre.toEnsemble().getTasks();
        // The map task should carry the tool
        assertThat(tasks.get(0).getTools()).containsExactly(toolInstance);
    }

    // ========================
    // Helpers
    // ========================

    static class NoOpChatModel implements ChatModel {
        @Override
        public ChatResponse chat(ChatRequest request) {
            throw new UnsupportedOperationException("NoOpChatModel must not be called in unit tests");
        }
    }
}
