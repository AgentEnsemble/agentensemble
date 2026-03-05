package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Agent;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.exception.TaskExecutionException;
import net.agentensemble.execution.ExecutionContext;
import net.agentensemble.memory.MemoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HierarchicalWorkflowExecutorTest {

    private ChatModel managerModel;
    private ChatModel workerModel;
    private Agent worker;
    private HierarchicalWorkflowExecutor executor;

    @BeforeEach
    void setUp() {
        managerModel = mock(ChatModel.class);
        workerModel = mock(ChatModel.class);
        worker = Agent.builder()
                .role("Researcher")
                .goal("Research topics")
                .llm(workerModel)
                .build();
        executor = new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3);
    }

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse delegateCallResponse(String agentRole, String taskDescription) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .id("delegate-1")
                .name("delegateTask")
                .arguments(
                        "{\"agentRole\": \"" + agentRole + "\", " + "\"taskDescription\": \"" + taskDescription + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(AiMessage.from(req)).build();
    }

    private Task researchTask() {
        return Task.builder()
                .description("Research AI trends")
                .expectedOutput("Summary of AI trends")
                .agent(worker)
                .build();
    }

    // ========================
    // Output structure tests
    // ========================

    @Test
    void testExecute_noDelegation_rawIsManagerOutput() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Direct manager answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Direct manager answer");
    }

    @Test
    void testExecute_noDelegation_taskOutputsContainsOnlyManagerOutput() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Manager answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTaskOutputs().get(0).getAgentRole()).isEqualTo("Manager");
    }

    @Test
    void testExecute_withDelegation_taskOutputsContainsWorkerThenManager() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Final synthesis"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getTaskOutputs().get(0).getAgentRole()).isEqualTo("Researcher");
        assertThat(output.getTaskOutputs().get(1).getAgentRole()).isEqualTo("Manager");
    }

    @Test
    void testExecute_managerOutputIsLastInTaskOutputs() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Manager final"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs().getLast().getRaw()).isEqualTo("Manager final");
    }

    @Test
    void testExecute_rawIsManagerFinalOutput() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker stuff"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Synthesized final answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Synthesized final answer");
    }

    // ========================
    // Tool call count tests
    // ========================

    @Test
    void testExecute_withDelegation_totalToolCallsIsAtLeastOne() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenReturn(textResponse("Final synthesis"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getTotalToolCalls()).isGreaterThanOrEqualTo(1);
    }

    // ========================
    // Duration tests
    // ========================

    @Test
    void testExecute_totalDurationIsPositive() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getTotalDuration()).isPositive();
    }

    // ========================
    // Multiple task tests
    // ========================

    @Test
    void testExecute_multipleTasks_allPassedToManagerPrompt() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Multi-task answer"));

        Agent writer = Agent.builder()
                .role("Writer")
                .goal("Write content")
                .llm(workerModel)
                .build();
        executor = new HierarchicalWorkflowExecutor(managerModel, List.of(worker, writer), 20, 3);

        Task task1 = researchTask();
        Task task2 = Task.builder()
                .description("Write the report")
                .expectedOutput("A report")
                .agent(writer)
                .build();

        EnsembleOutput output = executor.execute(List.of(task1, task2), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Multi-task answer");
    }

    // ========================
    // Manager agent config tests
    // ========================

    @Test
    void testExecute_managerAgentHasManagerRole() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs().getLast().getAgentRole())
                .isEqualTo(HierarchicalWorkflowExecutor.MANAGER_ROLE);
    }

    @Test
    void testExecute_taskOutputsIsImmutable() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getTaskOutputs()).isUnmodifiable();
    }

    // ========================
    // Callback event tests
    // ========================

    @Test
    void testExecute_firesTaskStartEvent_forManagerMetaTask() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        List<TaskStartEvent> events = new ArrayList<>();
        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                events.add(event);
            }
        }));

        executor.execute(List.of(researchTask()), ec);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).agentRole()).isEqualTo(HierarchicalWorkflowExecutor.MANAGER_ROLE);
        assertThat(events.get(0).taskIndex()).isEqualTo(1);
        assertThat(events.get(0).totalTasks()).isEqualTo(1);
    }

    @Test
    void testExecute_firesTaskCompleteEvent_withManagerOutput() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Final answer"));

        List<TaskCompleteEvent> events = new ArrayList<>();
        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent event) {
                events.add(event);
            }
        }));

        executor.execute(List.of(researchTask()), ec);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).agentRole()).isEqualTo(HierarchicalWorkflowExecutor.MANAGER_ROLE);
        assertThat(events.get(0).taskOutput().getRaw()).isEqualTo("Final answer");
        assertThat(events.get(0).duration()).isPositive();
    }

    @Test
    void testExecute_firesTaskFailedEvent_whenManagerLlmThrows() {
        when(managerModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM failure"));

        List<TaskFailedEvent> events = new ArrayList<>();
        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskFailed(TaskFailedEvent event) {
                events.add(event);
            }
        }));

        assertThatThrownBy(() -> executor.execute(List.of(researchTask()), ec))
                .isInstanceOf(TaskExecutionException.class);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).agentRole()).isEqualTo(HierarchicalWorkflowExecutor.MANAGER_ROLE);
        assertThat(events.get(0).cause()).isNotNull();
        assertThat(events.get(0).duration()).isNotNull();
    }

    @Test
    void testExecute_doesNotFireTaskCompleteEvent_whenManagerFails() {
        when(managerModel.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM failure"));

        List<TaskCompleteEvent> events = new ArrayList<>();
        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskComplete(TaskCompleteEvent event) {
                events.add(event);
            }
        }));

        assertThatThrownBy(() -> executor.execute(List.of(researchTask()), ec))
                .isInstanceOf(TaskExecutionException.class);

        assertThat(events).isEmpty();
    }

    @Test
    void testExecute_managerFailureAfterDelegation_exceptionContainsPartialWorkerOutputs() {
        when(workerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Worker result"));
        when(managerModel.chat(any(ChatRequest.class)))
                .thenReturn(delegateCallResponse("Researcher", "Research AI trends"))
                .thenThrow(new RuntimeException("Manager synthesis failed"));

        assertThatThrownBy(() -> executor.execute(List.of(researchTask()), ExecutionContext.disabled()))
                .isInstanceOf(TaskExecutionException.class)
                .satisfies(ex -> {
                    TaskExecutionException tee = (TaskExecutionException) ex;
                    assertThat(tee.getCompletedTaskOutputs()).hasSize(1);
                    assertThat(tee.getCompletedTaskOutputs().get(0).getRaw()).isEqualTo("Worker result");
                });
    }

    @Test
    void testExecute_listenerException_doesNotAbortExecution() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        ExecutionContext ec = ExecutionContext.of(MemoryContext.disabled(), false, List.of(new EnsembleListener() {
            @Override
            public void onTaskStart(TaskStartEvent event) {
                throw new RuntimeException("Listener boom");
            }
        }));

        EnsembleOutput output = executor.execute(List.of(researchTask()), ec);

        assertThat(output.getRaw()).isEqualTo("Answer");
    }

    // ========================
    // ManagerPromptStrategy tests
    // ========================

    @Test
    void testExecute_withDefaultStrategy_usesDefaultManagerPrompts() {
        // Verify that the default (no strategy arg) constructor produces the same output
        // as explicitly passing DefaultManagerPromptStrategy.DEFAULT.
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Default answer"));
        HierarchicalWorkflowExecutor explicitDefault = new HierarchicalWorkflowExecutor(
                managerModel, List.of(worker), 20, 3, DefaultManagerPromptStrategy.DEFAULT);

        EnsembleOutput output = explicitDefault.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Default answer");
    }

    @Test
    void testExecute_withCustomStrategy_systemPromptIsInjectedIntoManagerBackground() {
        // The custom strategy returns a fixed system prompt. We verify that the manager
        // agent is initialised with that string by capturing what gets sent to the LLM
        // (the system message will contain the background text).
        String customSystem = "CUSTOM_SYSTEM_PROMPT";
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();

        when(managerModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            capturedRequest.set(inv.getArgument(0));
            return textResponse("Custom answer");
        });

        ManagerPromptStrategy customStrategy = new ManagerPromptStrategy() {
            @Override
            public String buildSystemPrompt(ManagerPromptContext ctx) {
                return customSystem;
            }

            @Override
            public String buildUserPrompt(ManagerPromptContext ctx) {
                return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
            }
        };

        HierarchicalWorkflowExecutor customExecutor =
                new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3, customStrategy);
        customExecutor.execute(List.of(researchTask()), ExecutionContext.disabled());

        // The system message sent to the manager LLM must contain the custom system prompt
        assertThat(capturedRequest.get()).isNotNull();
        boolean systemPromptPresent = capturedRequest.get().messages().stream()
                .anyMatch(msg -> msg.toString().contains(customSystem));
        assertThat(systemPromptPresent).isTrue();
    }

    @Test
    void testExecute_withCustomStrategy_userPromptIsInjectedIntoManagerTask() {
        String customUser = "CUSTOM_USER_PROMPT";
        AtomicReference<ChatRequest> capturedRequest = new AtomicReference<>();

        when(managerModel.chat(any(ChatRequest.class))).thenAnswer(inv -> {
            capturedRequest.set(inv.getArgument(0));
            return textResponse("Custom answer");
        });

        ManagerPromptStrategy customStrategy = new ManagerPromptStrategy() {
            @Override
            public String buildSystemPrompt(ManagerPromptContext ctx) {
                return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx);
            }

            @Override
            public String buildUserPrompt(ManagerPromptContext ctx) {
                return customUser;
            }
        };

        HierarchicalWorkflowExecutor customExecutor =
                new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3, customStrategy);
        customExecutor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(capturedRequest.get()).isNotNull();
        boolean userPromptPresent = capturedRequest.get().messages().stream()
                .anyMatch(msg -> msg.toString().contains(customUser));
        assertThat(userPromptPresent).isTrue();
    }

    @Test
    void testExecute_withCustomStrategy_returningEmptyStrings_doesNotThrow() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer despite empty prompts"));

        ManagerPromptStrategy emptyStrategy = new ManagerPromptStrategy() {
            @Override
            public String buildSystemPrompt(ManagerPromptContext ctx) {
                return "";
            }

            @Override
            public String buildUserPrompt(ManagerPromptContext ctx) {
                return "";
            }
        };

        HierarchicalWorkflowExecutor emptyExecutor =
                new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3, emptyStrategy);

        // Validation of strategy output is the caller's responsibility -- no exception thrown
        EnsembleOutput output = emptyExecutor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Answer despite empty prompts");
    }

    @Test
    void testExecute_withNullStrategy_fallsBackToDefault() {
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Fallback answer"));

        // Passing null strategy should silently fall back to DefaultManagerPromptStrategy
        HierarchicalWorkflowExecutor nullStrategyExecutor =
                new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3, null);

        EnsembleOutput output = nullStrategyExecutor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(output.getRaw()).isEqualTo("Fallback answer");
    }

    @Test
    void testExecute_customStrategy_receivesCorrectAgentsInContext() {
        AtomicReference<ManagerPromptContext> capturedContext = new AtomicReference<>();
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        ManagerPromptStrategy capturingStrategy = new ManagerPromptStrategy() {
            @Override
            public String buildSystemPrompt(ManagerPromptContext ctx) {
                capturedContext.set(ctx);
                return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx);
            }

            @Override
            public String buildUserPrompt(ManagerPromptContext ctx) {
                return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
            }
        };

        HierarchicalWorkflowExecutor capturingExecutor =
                new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3, capturingStrategy);
        capturingExecutor.execute(List.of(researchTask()), ExecutionContext.disabled());

        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().agents()).hasSize(1);
        assertThat(capturedContext.get().agents().get(0).getRole()).isEqualTo("Researcher");
    }

    @Test
    void testExecute_customStrategy_receivesCorrectTasksInContext() {
        AtomicReference<ManagerPromptContext> capturedContext = new AtomicReference<>();
        when(managerModel.chat(any(ChatRequest.class))).thenReturn(textResponse("Answer"));

        ManagerPromptStrategy capturingStrategy = new ManagerPromptStrategy() {
            @Override
            public String buildSystemPrompt(ManagerPromptContext ctx) {
                return DefaultManagerPromptStrategy.DEFAULT.buildSystemPrompt(ctx);
            }

            @Override
            public String buildUserPrompt(ManagerPromptContext ctx) {
                capturedContext.set(ctx);
                return DefaultManagerPromptStrategy.DEFAULT.buildUserPrompt(ctx);
            }
        };

        Task task = researchTask();
        HierarchicalWorkflowExecutor capturingExecutor =
                new HierarchicalWorkflowExecutor(managerModel, List.of(worker), 20, 3, capturingStrategy);
        capturingExecutor.execute(List.of(task), ExecutionContext.disabled());

        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().tasks()).hasSize(1);
        assertThat(capturedContext.get().tasks().get(0).getDescription()).isEqualTo("Research AI trends");
    }
}
