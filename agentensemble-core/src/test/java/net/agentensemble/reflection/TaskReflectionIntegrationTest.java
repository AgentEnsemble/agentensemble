package net.agentensemble.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskReflectedEvent;
import net.agentensemble.execution.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the task reflection lifecycle.
 *
 * <p>Tests that:
 * <ul>
 *   <li>Reflections are stored in the {@link ReflectionStore} after execution</li>
 *   <li>Reflections accumulate across multiple runs (run count increments)</li>
 *   <li>The {@code onTaskReflected} callback fires with correct event data</li>
 *   <li>No reflection occurs for tasks without {@code .reflect(true)}</li>
 *   <li>Custom {@link ReflectionStrategy} is invoked correctly</li>
 * </ul>
 *
 * <p>Uses a stub {@link ReflectionStrategy} to avoid LLM calls.
 */
class TaskReflectionIntegrationTest {

    private static final String TASK_DESCRIPTION = "Write a quarterly business report";
    private static final String TASK_OUTPUT = "Q4 2025 Business Report: Revenue up 15%...";

    private InMemoryReflectionStore store;
    private AtomicReference<TaskReflectedEvent> capturedEvent;
    private AtomicInteger eventCount;
    private ExecutionContext contextWithListener;

    @BeforeEach
    void setUp() {
        store = new InMemoryReflectionStore();
        capturedEvent = new AtomicReference<>();
        eventCount = new AtomicInteger(0);

        contextWithListener = ExecutionContext.of(
                net.agentensemble.memory.MemoryContext.disabled(),
                false,
                List.of(new EnsembleListener() {
                    @Override
                    public void onTaskReflected(TaskReflectedEvent event) {
                        capturedEvent.set(event);
                        eventCount.incrementAndGet();
                    }
                }),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                net.agentensemble.tool.NoOpToolMetrics.INSTANCE,
                null,
                net.agentensemble.trace.CaptureMode.OFF,
                null,
                null,
                null,
                null,
                store);
    }

    @Test
    void taskReflector_withCustomStrategy_storesReflection() {
        Task task = buildReflectingTask();
        ReflectionStrategy stubStrategy = input -> TaskReflection.ofFirstRun(
                "Improved: " + input.task().getDescription(),
                "Improved output spec",
                List.of("Good structure"),
                List.of("Add more data"));

        Task taskWithCustomStrategy = Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput("A structured report")
                .reflect(ReflectionConfig.builder().strategy(stubStrategy).build())
                .build();

        TaskReflector.reflect(taskWithCustomStrategy, TASK_OUTPUT, null, contextWithListener);

        String identity = TaskIdentity.of(taskWithCustomStrategy);
        Optional<TaskReflection> stored = store.retrieve(identity);
        assertThat(stored).isPresent();
        assertThat(stored.get().refinedDescription()).startsWith("Improved: ");
        assertThat(stored.get().runCount()).isEqualTo(1);
    }

    @Test
    void taskReflector_firesTaskReflectedEvent() {
        Task task = buildTaskWithStubStrategy("Refined description", "Refined output");

        TaskReflector.reflect(task, TASK_OUTPUT, null, contextWithListener);

        assertThat(eventCount.get()).isEqualTo(1);
        assertThat(capturedEvent.get()).isNotNull();
        assertThat(capturedEvent.get().taskDescription()).isEqualTo(TASK_DESCRIPTION);
        assertThat(capturedEvent.get().isFirstReflection()).isTrue();
    }

    @Test
    void taskReflector_secondRun_isFirstReflectionFalse() {
        Task task = buildTaskWithStubStrategy("Refined description", "Refined output");

        // Run 1
        TaskReflector.reflect(task, TASK_OUTPUT, null, contextWithListener);
        // Run 2
        TaskReflector.reflect(task, TASK_OUTPUT + " updated", null, contextWithListener);

        assertThat(capturedEvent.get().isFirstReflection()).isFalse();
        assertThat(capturedEvent.get().reflection().runCount()).isEqualTo(2);
    }

    @Test
    void taskReflector_accumulatesRunCount() {
        Task task = buildTaskWithStubStrategy("Refined description", "Refined output");

        for (int i = 0; i < 3; i++) {
            TaskReflector.reflect(task, TASK_OUTPUT, null, contextWithListener);
        }

        String identity = TaskIdentity.of(task);
        assertThat(store.retrieve(identity)).isPresent().hasValueSatisfying(r -> assertThat(r.runCount())
                .isEqualTo(3));
    }

    @Test
    void taskReflector_withoutReflectionConfig_doesNothing() {
        Task task = Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput("A structured report")
                .build(); // no .reflect()

        TaskReflector.reflect(task, TASK_OUTPUT, null, contextWithListener);

        assertThat(store.size()).isZero();
        assertThat(eventCount.get()).isZero();
    }

    @Test
    void taskReflector_withNoStore_createsEphemeralFallback() {
        // Context without a reflection store
        ExecutionContext noStoreContext = ExecutionContext.of(
                net.agentensemble.memory.MemoryContext.disabled(),
                false,
                List.of(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(),
                net.agentensemble.tool.NoOpToolMetrics.INSTANCE,
                null,
                net.agentensemble.trace.CaptureMode.OFF,
                null,
                null,
                null,
                null,
                null); // no reflection store

        Task task = buildTaskWithStubStrategy("Refined description", "Refined output");

        // Should not throw -- creates an ephemeral store and continues
        TaskReflector.reflect(task, TASK_OUTPUT, null, noStoreContext);
        // No assertion needed: just verify no exception is thrown
    }

    @Test
    void taskIdentity_stableAcrossInstances() {
        Task task1 = Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput("Output A")
                .build();

        Task task2 = Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput("Output B") // different expected output
                .build();

        // Identity is based on description only -- same description = same identity
        assertThat(TaskIdentity.of(task1)).isEqualTo(TaskIdentity.of(task2));
    }

    @Test
    void taskIdentity_differentDescriptions_differentIdentities() {
        Task task1 = Task.builder()
                .description("Task A description")
                .expectedOutput("Output")
                .build();

        Task task2 = Task.builder()
                .description("Task B description")
                .expectedOutput("Output")
                .build();

        assertThat(TaskIdentity.of(task1)).isNotEqualTo(TaskIdentity.of(task2));
    }

    @Test
    void taskReflector_withLlmStrategy_onLlmFailure_storesFallback() {
        ChatModel failingModel = mock(ChatModel.class);
        when(failingModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM failure"));

        Task task = Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput("A structured report")
                .reflect(ReflectionConfig.builder().model(failingModel).build())
                .build();

        // Should not throw -- LLM failure is handled gracefully
        TaskReflector.reflect(task, TASK_OUTPUT, failingModel, contextWithListener);

        // Fallback reflection should be stored with error observation
        String identity = TaskIdentity.of(task);
        Optional<TaskReflection> stored = store.retrieve(identity);
        assertThat(stored).isPresent();
        assertThat(stored.get().observations()).anyMatch(obs -> obs.contains("Reflection could not be completed"));
    }

    // ========================
    // Helpers
    // ========================

    private Task buildReflectingTask() {
        return Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput("A structured report")
                .reflect(true)
                .build();
    }

    private Task buildTaskWithStubStrategy(String refinedDesc, String refinedOutput) {
        ReflectionStrategy stub = input -> {
            if (input.priorReflection().isPresent()) {
                return TaskReflection.fromPrior(
                        refinedDesc,
                        refinedOutput,
                        List.of("observation"),
                        List.of("suggestion"),
                        input.priorReflection().get());
            }
            return TaskReflection.ofFirstRun(refinedDesc, refinedOutput, List.of("observation"), List.of("suggestion"));
        };
        return Task.builder()
                .description(TASK_DESCRIPTION)
                .expectedOutput("A structured report")
                .reflect(ReflectionConfig.builder().strategy(stub).build())
                .build();
    }
}
