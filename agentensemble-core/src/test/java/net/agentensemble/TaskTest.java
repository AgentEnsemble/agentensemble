package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.guardrail.GuardrailResult;
import net.agentensemble.guardrail.InputGuardrail;
import net.agentensemble.guardrail.OutputGuardrail;
import net.agentensemble.memory.MemoryScope;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.ratelimit.RateLimitedChatModel;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Tests for Task builder, defaults, immutability, toBuilder, and outputType happy paths.
 *
 * Validation error cases are covered by TaskValidationTest.
 */
class TaskTest {

    private final Agent testAgent = Agent.builder()
            .role("Researcher")
            .goal("Find information")
            .llm(mock(ChatModel.class))
            .build();

    record SimpleReport(String title, String summary) {}

    // ========================
    // Build success cases
    // ========================

    @Test
    void testBuild_withMinimalFields_succeeds() {
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .agent(testAgent)
                .build();

        assertThat(task.getDescription()).isEqualTo("Research AI trends");
        assertThat(task.getExpectedOutput()).isEqualTo("A detailed report");
        assertThat(task.getAgent()).isSameAs(testAgent);
        assertThat(task.getContext()).isEmpty();
    }

    @Test
    void testBuild_withAllFields_succeeds() {
        var contextTask = Task.builder()
                .description("Gather data")
                .expectedOutput("Raw data")
                .agent(testAgent)
                .build();

        var task = Task.builder()
                .description("Analyze AI trends")
                .expectedOutput("An analysis report")
                .agent(testAgent)
                .context(List.of(contextTask))
                .build();

        assertThat(task.getDescription()).isEqualTo("Analyze AI trends");
        assertThat(task.getExpectedOutput()).isEqualTo("An analysis report");
        assertThat(task.getContext()).hasSize(1);
        assertThat(task.getContext().get(0)).isSameAs(contextTask);
    }

    @Test
    void testBuild_withMultipleContextTasks_succeeds() {
        var ctx1 = Task.builder()
                .description("Task 1")
                .expectedOutput("Out 1")
                .agent(testAgent)
                .build();
        var ctx2 = Task.builder()
                .description("Task 2")
                .expectedOutput("Out 2")
                .agent(testAgent)
                .build();

        var task = Task.builder()
                .description("Main task")
                .expectedOutput("Final output")
                .agent(testAgent)
                .context(List.of(ctx1, ctx2))
                .build();

        assertThat(task.getContext()).hasSize(2);
    }

    @Test
    void testBuild_withTemplateVariables_succeeds() {
        // Template variables in description/expectedOutput are valid -- resolved at ensemble.run() time
        var task = Task.builder()
                .description("Research {topic} developments in {year}")
                .expectedOutput("A report on {topic}")
                .agent(testAgent)
                .build();

        assertThat(task.getDescription()).contains("{topic}");
        assertThat(task.getExpectedOutput()).contains("{topic}");
    }

    // ========================
    // Default values
    // ========================

    @Test
    void testDefaultValues_contextIsEmpty() {
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("A report")
                .agent(testAgent)
                .build();

        assertThat(task.getContext()).isNotNull().isEmpty();
    }

    @Test
    void testBuild_withoutOutputType_defaultsToNull() {
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A report")
                .agent(testAgent)
                .build();

        assertThat(task.getOutputType()).isNull();
    }

    @Test
    void testBuild_withoutMaxOutputRetries_defaultsToThree() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .build();

        assertThat(task.getMaxOutputRetries()).isEqualTo(3);
    }

    // ========================
    // Immutability
    // ========================

    @Test
    void testContextList_isImmutable() {
        var ctx = Task.builder()
                .description("Ctx")
                .expectedOutput("Out")
                .agent(testAgent)
                .build();
        var task = Task.builder()
                .description("Main")
                .expectedOutput("Result")
                .agent(testAgent)
                .context(List.of(ctx))
                .build();

        assertThat(task.getContext()).isUnmodifiable();
    }

    @Test
    void testContextList_defaultIsImmutable() {
        var task = Task.builder()
                .description("Main")
                .expectedOutput("Result")
                .agent(testAgent)
                .build();

        assertThat(task.getContext()).isUnmodifiable();
    }

    // ========================
    // toBuilder
    // ========================

    @Test
    void testToBuilder_createsModifiedCopy() {
        var original = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .agent(testAgent)
                .build();

        var ctx = Task.builder()
                .description("Ctx")
                .expectedOutput("Out")
                .agent(testAgent)
                .build();
        var modified = original.toBuilder().context(List.of(ctx)).build();

        assertThat(modified.getDescription()).isEqualTo("Research AI trends");
        assertThat(modified.getContext()).hasSize(1);
        assertThat(original.getContext()).isEmpty();
    }

    @Test
    void testToBuilder_preservesOutputType() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .outputType(SimpleReport.class)
                .maxOutputRetries(2)
                .build();

        var copy = task.toBuilder().description("Updated task").build();

        assertThat(copy.getOutputType()).isEqualTo(SimpleReport.class);
        assertThat(copy.getMaxOutputRetries()).isEqualTo(2);
    }

    // ========================
    // outputType happy paths
    // ========================

    @Test
    void testBuild_withOutputType_record_succeeds() {
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A structured report")
                .agent(testAgent)
                .outputType(SimpleReport.class)
                .build();

        assertThat(task.getOutputType()).isEqualTo(SimpleReport.class);
    }

    @Test
    void testBuild_withOutputType_string_succeeds() {
        var task = Task.builder()
                .description("Summarize this")
                .expectedOutput("A summary")
                .agent(testAgent)
                .outputType(String.class)
                .build();

        assertThat(task.getOutputType()).isEqualTo(String.class);
    }

    // ========================
    // maxOutputRetries happy paths
    // ========================

    @Test
    void testBuild_withMaxOutputRetries_zero_succeeds() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .maxOutputRetries(0)
                .build();

        assertThat(task.getMaxOutputRetries()).isZero();
    }

    @Test
    void testBuild_withMaxOutputRetries_positive_succeeds() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .maxOutputRetries(5)
                .build();

        assertThat(task.getMaxOutputRetries()).isEqualTo(5);
    }

    // ========================
    // inputGuardrails happy paths
    // ========================

    @Test
    void testBuild_defaultInputGuardrails_isEmpty() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .build();

        assertThat(task.getInputGuardrails()).isEmpty();
    }

    @Test
    void testBuild_withInputGuardrails_stored() {
        InputGuardrail guardrail = input -> GuardrailResult.success();

        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .inputGuardrails(List.of(guardrail))
                .build();

        assertThat(task.getInputGuardrails()).containsExactly(guardrail);
    }

    @Test
    void testBuild_inputGuardrailsList_isImmutable() {
        InputGuardrail guardrail = input -> GuardrailResult.success();

        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .inputGuardrails(List.of(guardrail))
                .build();

        assertThat(task.getInputGuardrails()).isUnmodifiable();
    }

    // ========================
    // outputGuardrails happy paths
    // ========================

    @Test
    void testBuild_defaultOutputGuardrails_isEmpty() {
        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .build();

        assertThat(task.getOutputGuardrails()).isEmpty();
    }

    @Test
    void testBuild_withOutputGuardrails_stored() {
        OutputGuardrail guardrail = output -> GuardrailResult.success();

        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .outputGuardrails(List.of(guardrail))
                .build();

        assertThat(task.getOutputGuardrails()).containsExactly(guardrail);
    }

    @Test
    void testBuild_withBothGuardrails_stored() {
        InputGuardrail inGuard = input -> GuardrailResult.success();
        OutputGuardrail outGuard = output -> GuardrailResult.success();

        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .agent(testAgent)
                .inputGuardrails(List.of(inGuard))
                .outputGuardrails(List.of(outGuard))
                .build();

        assertThat(task.getInputGuardrails()).containsExactly(inGuard);
        assertThat(task.getOutputGuardrails()).containsExactly(outGuard);
    }

    // ========================
    // Task.of() convenience factories (v2)
    // ========================

    @Test
    void taskOf_descriptionOnly_setsDefaultExpectedOutput() {
        var task = Task.of("Research the latest AI developments");

        assertThat(task.getDescription()).isEqualTo("Research the latest AI developments");
        assertThat(task.getExpectedOutput()).isEqualTo(Task.DEFAULT_EXPECTED_OUTPUT);
        assertThat(task.getAgent()).isNull();
        assertThat(task.getContext()).isEmpty();
        assertThat(task.getTools()).isEmpty();
        assertThat(task.getChatLanguageModel()).isNull();
        assertThat(task.getMaxIterations()).isNull();
    }

    @Test
    void taskOf_descriptionAndExpectedOutput_setsCustomOutput() {
        var task = Task.of("Research AI trends", "A detailed market analysis");

        assertThat(task.getDescription()).isEqualTo("Research AI trends");
        assertThat(task.getExpectedOutput()).isEqualTo("A detailed market analysis");
        assertThat(task.getAgent()).isNull();
    }

    @Test
    void taskOf_descriptionOnly_isImmutableAndRepeatable() {
        var task1 = Task.of("Research AI");
        var task2 = Task.of("Research AI");

        assertThat(task1.getDescription()).isEqualTo(task2.getDescription());
        assertThat(task1.getExpectedOutput()).isEqualTo(task2.getExpectedOutput());
    }

    // ========================
    // New v2 fields: tools, chatLanguageModel, maxIterations
    // ========================

    @Test
    void testBuild_withTools_storedAndImmutable() {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "calculator";
            }

            @Override
            public String description() {
                return "Does math";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("42");
            }
        };

        var task = Task.builder()
                .description("Calculate something")
                .expectedOutput("A number")
                .tools(List.of(tool))
                .build();

        assertThat(task.getTools()).containsExactly(tool);
        assertThat(task.getTools()).isUnmodifiable();
    }

    @Test
    void testBuild_defaultTools_isEmpty() {
        var task = Task.of("Do something");
        assertThat(task.getTools()).isEmpty();
        assertThat(task.getTools()).isUnmodifiable();
    }

    @Test
    void testBuild_withChatLanguageModel_stored() {
        ChatModel model = mock(ChatModel.class);

        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .chatLanguageModel(model)
                .build();

        assertThat(task.getChatLanguageModel()).isSameAs(model);
        assertThat(task.getAgent()).isNull(); // no explicit agent
    }

    @Test
    void testBuild_defaultChatLanguageModel_isNull() {
        var task = Task.of("Research task");
        assertThat(task.getChatLanguageModel()).isNull();
    }

    @Test
    void testBuild_withMaxIterations_stored() {
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .maxIterations(10)
                .build();

        assertThat(task.getMaxIterations()).isEqualTo(10);
    }

    @Test
    void testBuild_defaultMaxIterations_isNull() {
        var task = Task.of("Research task");
        assertThat(task.getMaxIterations()).isNull();
    }

    @Test
    void testBuild_withAllNewV2Fields_succeed() {
        ChatModel model = mock(ChatModel.class);

        var task = Task.builder()
                .description("Research and analyse AI trends")
                .expectedOutput("A comprehensive report")
                .chatLanguageModel(model)
                .maxIterations(15)
                .build();

        assertThat(task.getAgent()).isNull();
        assertThat(task.getChatLanguageModel()).isSameAs(model);
        assertThat(task.getMaxIterations()).isEqualTo(15);
        assertThat(task.getTools()).isEmpty();
    }

    @Test
    void testBuild_agentAndNewFields_agentTakesPriority() {
        // When explicit agent is set alongside new fields, agent is stored as-is.
        // The new fields are used during synthesis (when no agent is set).
        ChatModel taskModel = mock(ChatModel.class);

        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .agent(testAgent)
                .chatLanguageModel(taskModel)
                .maxIterations(20)
                .build();

        assertThat(task.getAgent()).isSameAs(testAgent);
        assertThat(task.getChatLanguageModel()).isSameAs(taskModel);
        assertThat(task.getMaxIterations()).isEqualTo(20);
    }

    // ========================
    // v2 memory scope builder methods
    // ========================

    @Test
    void testBuild_memorySingleScope_stored() {
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .agent(testAgent)
                .memory("research")
                .build();

        assertThat(task.getMemoryScopes()).hasSize(1);
        assertThat(task.getMemoryScopes().get(0).getName()).isEqualTo("research");
    }

    @Test
    void testBuild_memoryVarargScopes_allStored() {
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .agent(testAgent)
                .memory("scope-a", "scope-b", "scope-c")
                .build();

        assertThat(task.getMemoryScopes()).hasSize(3);
        assertThat(task.getMemoryScopes().get(0).getName()).isEqualTo("scope-a");
        assertThat(task.getMemoryScopes().get(1).getName()).isEqualTo("scope-b");
        assertThat(task.getMemoryScopes().get(2).getName()).isEqualTo("scope-c");
    }

    @Test
    void testBuild_memoryScopeObject_stored() {
        var scope = MemoryScope.of("weekly-intel");
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .agent(testAgent)
                .memory(scope)
                .build();

        assertThat(task.getMemoryScopes()).hasSize(1);
        assertThat(task.getMemoryScopes().get(0).getName()).isEqualTo("weekly-intel");
    }

    @Test
    void testBuild_defaultMemoryScopes_isEmpty() {
        var task = Task.of("Do something");
        assertThat(task.getMemoryScopes()).isEmpty();
    }

    // ========================
    // rateLimit builder convenience
    // ========================

    @Test
    void testBuild_withRateLimit_andChatLanguageModel_wrapsChatModel() {
        ChatModel model = mock(ChatModel.class);

        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .chatLanguageModel(model)
                .rateLimit(RateLimit.perMinute(60))
                .build();

        // chatLanguageModel is wrapped with RateLimitedChatModel
        assertThat(task.getChatLanguageModel()).isInstanceOf(RateLimitedChatModel.class);
        assertThat(((RateLimitedChatModel) task.getChatLanguageModel()).getDelegate())
                .isSameAs(model);
        // rateLimit is consumed at build time; not stored on the task
        assertThat(task.getRateLimit()).isNull();
    }

    @Test
    void testBuild_withRateLimit_noChatLanguageModel_storedOnTask() {
        // When no task-level chatLanguageModel is set, rateLimit is stored for Ensemble to apply
        var task = Task.builder()
                .description("Research task")
                .expectedOutput("Output")
                .rateLimit(RateLimit.perMinute(30))
                .build();

        assertThat(task.getChatLanguageModel()).isNull();
        assertThat(task.getRateLimit()).isEqualTo(RateLimit.perMinute(30));
    }

    @Test
    void testBuild_defaultRateLimit_isNull() {
        var task = Task.of("Do something");
        assertThat(task.getRateLimit()).isNull();
    }

    @Test
    void testBuild_withRateLimit_andChatLanguageModel_rateLimitNullOnTask() {
        // Verify rateLimit is NOT carried to the resulting Task when model is also set
        ChatModel model = mock(ChatModel.class);

        var task = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .chatLanguageModel(model)
                .rateLimit(RateLimit.perSecond(2))
                .build();

        assertThat(task.getRateLimit()).isNull();
    }

    @Test
    void testToBuilder_preservesRateLimit_whenNoChatModel() {
        // toBuilder on a task with rateLimit (no chatModel) preserves it
        var original = Task.builder()
                .description("Task")
                .expectedOutput("Output")
                .rateLimit(RateLimit.perMinute(10))
                .build();

        var copy = original.toBuilder().description("Updated task").build();
        assertThat(copy.getRateLimit()).isEqualTo(RateLimit.perMinute(10));
    }

    // ========================
    // handler field (deterministic tasks)
    // ========================

    @Test
    void testBuild_withLambdaHandler_stored() {
        var task = Task.builder()
                .description("Fetch data")
                .expectedOutput("JSON")
                .handler(ctx -> net.agentensemble.tool.ToolResult.success("data"))
                .build();

        assertThat(task.getHandler()).isNotNull();
        assertThat(task.getAgent()).isNull();
        assertThat(task.getChatLanguageModel()).isNull();
    }

    @Test
    void testBuild_withAgentToolHandler_wrapsToolAsHandler() {
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echoes input";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success(input);
            }
        };

        var task = Task.builder()
                .description("Echo task")
                .expectedOutput("Echoed output")
                .handler(tool)
                .build();

        assertThat(task.getHandler()).isNotNull();
        assertThat(task.getAgent()).isNull();
    }

    @Test
    void testBuild_agentToolHandler_usesDescriptionAsInput_whenNoContext() {
        String[] capturedInput = new String[1];
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "cap";
            }

            @Override
            public String description() {
                return "Captures input";
            }

            @Override
            public ToolResult execute(String input) {
                capturedInput[0] = input;
                return ToolResult.success(input);
            }
        };

        var task = Task.builder()
                .description("my-url://example.com")
                .expectedOutput("Response body")
                .handler(tool)
                .build();

        // Execute with no context outputs -- input should be the task description
        var ctx = new net.agentensemble.task.TaskHandlerContext(
                task.getDescription(), task.getExpectedOutput(), List.of());
        task.getHandler().execute(ctx);

        assertThat(capturedInput[0]).isEqualTo("my-url://example.com");
    }

    @Test
    void testBuild_agentToolHandler_usesLastContextOutput_whenContextPresent() {
        String[] capturedInput = new String[1];
        AgentTool tool = new AgentTool() {
            @Override
            public String name() {
                return "cap";
            }

            @Override
            public String description() {
                return "Captures input";
            }

            @Override
            public ToolResult execute(String input) {
                capturedInput[0] = input;
                return ToolResult.success(input);
            }
        };

        var priorOutput = net.agentensemble.task.TaskOutput.builder()
                .raw("prior-raw-text")
                .taskDescription("Prior")
                .agentRole("(deterministic)")
                .completedAt(java.time.Instant.now())
                .duration(java.time.Duration.ofMillis(1))
                .toolCallCount(0)
                .build();

        var task = Task.builder()
                .description("Next step")
                .expectedOutput("Processed result")
                .handler(tool)
                .build();

        var ctx = new net.agentensemble.task.TaskHandlerContext(
                task.getDescription(), task.getExpectedOutput(), List.of(priorOutput));
        task.getHandler().execute(ctx);

        assertThat(capturedInput[0]).isEqualTo("prior-raw-text");
    }

    @Test
    void testBuild_defaultHandler_isNull() {
        var task = Task.of("Regular AI task");
        assertThat(task.getHandler()).isNull();
    }

    @Test
    void testToBuilder_onHandlerTask_preservesHandler() {
        var original = Task.builder()
                .description("Fetch prices")
                .expectedOutput("JSON prices")
                .handler(ctx -> net.agentensemble.tool.ToolResult.success("prices"))
                .build();

        var copy = original.toBuilder().expectedOutput("Updated output").build();

        assertThat(copy.getHandler()).isSameAs(original.getHandler());
        assertThat(copy.getExpectedOutput()).isEqualTo("Updated output");
    }

    @Test
    void testToBuilder_onAiTask_handlerRemainsNull() {
        var original = Task.of("Regular AI task");
        var copy = original.toBuilder().description("Updated AI task").build();

        assertThat(copy.getHandler()).isNull();
        assertThat(copy.getDescription()).isEqualTo("Updated AI task");
    }

    @Test
    void testBuild_handlerWithContext_succeeds() {
        // Handler tasks may have context (prior task outputs)
        var contextTask = Task.builder()
                .description("Context task")
                .expectedOutput("Context output")
                .handler(ctx -> net.agentensemble.tool.ToolResult.success("context"))
                .build();

        var dependent = Task.builder()
                .description("Dependent task")
                .expectedOutput("Dependent output")
                .context(List.of(contextTask))
                .handler(ctx -> net.agentensemble.tool.ToolResult.success("result"))
                .build();

        assertThat(dependent.getHandler()).isNotNull();
        assertThat(dependent.getContext()).containsExactly(contextTask);
    }

    @Test
    void testBuild_handlerWithGuardrails_succeeds() {
        // Handler tasks may have guardrails
        var task = Task.builder()
                .description("Guarded task")
                .expectedOutput("Output")
                .inputGuardrails(List.of(input -> net.agentensemble.guardrail.GuardrailResult.success()))
                .outputGuardrails(List.of(out -> net.agentensemble.guardrail.GuardrailResult.success()))
                .handler(ctx -> net.agentensemble.tool.ToolResult.success("result"))
                .build();

        assertThat(task.getHandler()).isNotNull();
        assertThat(task.getInputGuardrails()).hasSize(1);
        assertThat(task.getOutputGuardrails()).hasSize(1);
    }

    // ========================
    // Task.name field (Phase 2 -- Ensemble Control API)
    // ========================

    @Test
    void name_isNullByDefault() {
        var task = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .build();

        assertThat(task.getName()).isNull();
    }

    @Test
    void name_whenSetViaBuilder_isReturned() {
        var task = Task.builder()
                .name("researcher")
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .build();

        assertThat(task.getName()).isEqualTo("researcher");
    }

    @Test
    void name_blank_throwsValidationException() {
        assertThatThrownBy(() -> Task.builder()
                        .name("  ")
                        .description("Research AI trends")
                        .expectedOutput("A detailed report")
                        .build())
                .isInstanceOf(net.agentensemble.exception.ValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void name_empty_throwsValidationException() {
        assertThatThrownBy(() -> Task.builder()
                        .name("")
                        .description("Research AI trends")
                        .expectedOutput("A detailed report")
                        .build())
                .isInstanceOf(net.agentensemble.exception.ValidationException.class)
                .hasMessageContaining("name");
    }

    @Test
    void toBuilder_preservesName() {
        var original = Task.builder()
                .name("researcher")
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .build();

        var copy = original.toBuilder().build();

        assertThat(copy.getName()).isEqualTo("researcher");
    }

    @Test
    void toBuilder_allowsNameChange() {
        var original = Task.builder()
                .name("researcher")
                .description("Research AI trends")
                .expectedOutput("A detailed report")
                .build();

        var copy = original.toBuilder().name("senior-researcher").build();

        assertThat(copy.getName()).isEqualTo("senior-researcher");
        assertThat(original.getName()).isEqualTo("researcher");
    }

    @Test
    void taskOf_descriptionOnly_hasNullName() {
        var task = Task.of("Research AI trends");

        assertThat(task.getName()).isNull();
    }

    @Test
    void taskOf_descriptionAndExpectedOutput_hasNullName() {
        var task = Task.of("Research AI trends", "A detailed report");

        assertThat(task.getName()).isNull();
    }
}
