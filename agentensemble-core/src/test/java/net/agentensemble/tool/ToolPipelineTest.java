package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for ToolPipeline: step chaining, error strategies, adapters,
 * ToolContext propagation, factory methods, and builder validation.
 */
class ToolPipelineTest {

    // ========================
    // Test tool fixtures
    // ========================

    /** Returns a tool that echoes its input prefixed with the given label. */
    static AgentTool prefixTool(String name, String prefix) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Prepends '" + prefix + "' to input";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success(prefix + input);
            }
        };
    }

    /** Returns a tool that always succeeds with the given fixed output. */
    static AgentTool fixedOutputTool(String name, String output) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Always returns: " + output;
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success(output);
            }
        };
    }

    /** Returns a tool that always fails with the given error message. */
    static AgentTool failingTool(String name, String errorMessage) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "Always fails";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.failure(errorMessage);
            }
        };
    }

    /** A tool that records every input it receives. */
    static class RecordingTool implements AgentTool {
        final String name;
        final List<String> receivedInputs = new ArrayList<>();
        private final String outputSuffix;

        RecordingTool(String name, String outputSuffix) {
            this.name = name;
            this.outputSuffix = outputSuffix;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Records inputs";
        }

        @Override
        public ToolResult execute(String input) {
            receivedInputs.add(input);
            return ToolResult.success(input + outputSuffix);
        }
    }

    /** An AbstractAgentTool subclass for testing context propagation. */
    static class ContextCapturingTool extends AbstractAgentTool {
        private final String toolName;
        boolean contextWasInjected = false;

        ContextCapturingTool(String name) {
            this.toolName = name;
        }

        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String description() {
            return "Captures context";
        }

        @Override
        protected ToolResult doExecute(String input) {
            contextWasInjected = !(metrics() instanceof NoOpToolMetrics);
            return ToolResult.success("context: " + contextWasInjected);
        }
    }

    // ========================
    // Happy path: chaining
    // ========================

    @Test
    void execute_singleStep_returnsStepOutput() {
        var pipeline = ToolPipeline.of("single", "Single step", prefixTool("step_a", "A:"));

        ToolResult result = pipeline.execute("hello");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("A:hello");
    }

    @Test
    void execute_twoSteps_outputOfFirstIsInputToSecond() {
        var stepA = new RecordingTool("step_a", "_processed");
        var stepB = new RecordingTool("step_b", "_done");
        var pipeline = ToolPipeline.of("two_step_pipeline", "Two steps", stepA, stepB);

        ToolResult result = pipeline.execute("start");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("start_processed_done");
        assertThat(stepA.receivedInputs).containsExactly("start");
        assertThat(stepB.receivedInputs).containsExactly("start_processed");
    }

    @Test
    void execute_threeSteps_chainsAllOutputsCorrectly() {
        var pipeline =
                ToolPipeline.of(prefixTool("step_a", "A:"), prefixTool("step_b", "B:"), prefixTool("step_c", "C:"));

        ToolResult result = pipeline.execute("x");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("C:B:A:x");
    }

    @Test
    void execute_emptyPipeline_returnsInputAsPassThrough() {
        var pipeline = ToolPipeline.builder()
                .name("empty")
                .description("Empty pipeline")
                .build();

        ToolResult result = pipeline.execute("pass-through");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("pass-through");
    }

    @Test
    void execute_emptyPipelineWithNullInput_returnsEmptySuccess() {
        var pipeline = ToolPipeline.builder()
                .name("empty")
                .description("Empty pipeline")
                .build();

        ToolResult result = pipeline.execute(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    void execute_nullInput_isPassedToFirstStep() {
        var recording = new RecordingTool("step_a", "_done");
        var pipeline = ToolPipeline.of("p", "Pipeline", recording);

        pipeline.execute(null);

        assertThat(recording.receivedInputs).containsExactly("");
    }

    // ========================
    // Error strategy: FAIL_FAST (default)
    // ========================

    @Test
    void execute_failFast_firstStepFails_returnsFailureImmediately() {
        var step2Called = new AtomicInteger(0);
        var failingFirst = failingTool("fail_first", "step1 error");
        var countingSecond = new AgentTool() {
            @Override
            public String name() {
                return "step_b";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ToolResult execute(String input) {
                step2Called.incrementAndGet();
                return ToolResult.success("should not be reached");
            }
        };

        var pipeline = ToolPipeline.builder()
                .name("pipeline")
                .description("desc")
                .step(failingFirst)
                .step(countingSecond)
                .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
                .build();

        ToolResult result = pipeline.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("step1 error");
        assertThat(step2Called.get()).isEqualTo(0);
    }

    @Test
    void execute_failFast_middleStepFails_skipsRemainingSteps() {
        var step3Called = new AtomicInteger(0);
        var pipeline = ToolPipeline.builder()
                .name("pipeline")
                .description("desc")
                .step(prefixTool("step_a", "A:"))
                .step(failingTool("step_b", "B failed"))
                .step(new AgentTool() {
                    @Override
                    public String name() {
                        return "step_c";
                    }

                    @Override
                    public String description() {
                        return "";
                    }

                    @Override
                    public ToolResult execute(String input) {
                        step3Called.incrementAndGet();
                        return ToolResult.success("C done");
                    }
                })
                .build(); // FAIL_FAST is default

        ToolResult result = pipeline.execute("x");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("B failed");
        assertThat(step3Called.get()).isEqualTo(0);
    }

    @Test
    void execute_failFast_lastStepFails_returnsLastFailure() {
        var pipeline = ToolPipeline.of(prefixTool("step_a", "A:"), failingTool("step_b", "final step failed"));

        ToolResult result = pipeline.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("final step failed");
    }

    @Test
    void execute_failFast_isDefaultStrategy() {
        var pipeline = ToolPipeline.builder().name("p").description("d").build();

        assertThat(pipeline.getErrorStrategy()).isEqualTo(PipelineErrorStrategy.FAIL_FAST);
    }

    // ========================
    // Error strategy: CONTINUE_ON_FAILURE
    // ========================

    @Test
    void execute_continueOnFailure_failedStepErrorMessagePassedToNextStep() {
        var recording = new RecordingTool("step_b", "_done");
        var pipeline = ToolPipeline.builder()
                .name("pipeline")
                .description("desc")
                .step(failingTool("step_a", "step_a_error"))
                .step(recording)
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        ToolResult result = pipeline.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("step_a_error_done");
        assertThat(recording.receivedInputs).containsExactly("step_a_error");
    }

    @Test
    void execute_continueOnFailure_allStepsRun() {
        var step1 = new RecordingTool("step_a", "_ok");
        var step3 = new RecordingTool("step_c", "_end");
        var pipeline = ToolPipeline.builder()
                .name("pipeline")
                .description("desc")
                .step(step1)
                .step(failingTool("step_b", "b_error"))
                .step(step3)
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        pipeline.execute("start");

        assertThat(step1.receivedInputs).containsExactly("start");
        assertThat(step3.receivedInputs).containsExactly("b_error");
    }

    @Test
    void execute_continueOnFailure_failedStepNullErrorMessage_emptyStringPassedToNext() {
        var recording = new RecordingTool("step_b", "_done");
        var nullErrorTool = new AgentTool() {
            @Override
            public String name() {
                return "null_error_tool";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.failure(null);
            }
        };
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(nullErrorTool)
                .step(recording)
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        pipeline.execute("x");

        // ToolResult.failure(null) normalizes errorMessage to a default; never truly null
        assertThat(recording.receivedInputs.get(0)).isNotNull();
    }

    @Test
    void execute_continueOnFailure_finalResultIsLastStepResult() {
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(failingTool("step_a", "error"))
                .step(failingTool("step_b", "final_error"))
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        ToolResult result = pipeline.execute("x");

        // Last step's result is returned
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("final_error");
    }

    // ========================
    // Step adapters
    // ========================

    @Test
    void execute_adapterBetweenSteps_transformsOutputBeforeNextInput() {
        var recording = new RecordingTool("step_b", "_done");
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(fixedOutputTool("step_a", "raw_output"))
                .adapter(result -> result.getOutput().toUpperCase())
                .step(recording)
                .build();

        pipeline.execute("x");

        assertThat(recording.receivedInputs).containsExactly("RAW_OUTPUT");
    }

    @Test
    void execute_adapterOnLastStep_adapterNotCalledForLastStep() {
        // Adapter is attached to last step but has no effect (no next step)
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(fixedOutputTool("step_a", "raw"))
                .adapter(result -> "THIS_SHOULD_NOT_APPEAR")
                .build();

        ToolResult result = pipeline.execute("x");

        // The adapter on the last step is never called; pipeline returns raw output
        assertThat(result.getOutput()).isEqualTo("raw");
    }

    @Test
    void execute_adapterOnlyOnFirstOfTwoSteps_secondStepReceivesAdaptedOutput() {
        var recording = new RecordingTool("step_b", "_done");
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(fixedOutputTool("step_a", "original"))
                .adapter(result -> "adapted:" + result.getOutput())
                .step(recording)
                .build();

        pipeline.execute("ignored_by_first_step");

        assertThat(recording.receivedInputs).containsExactly("adapted:original");
    }

    @Test
    void execute_adapterWithStructuredOutput_canAccessStructuredPayload() {
        record MyPayload(int value) {}
        var toolWithStructured = new AgentTool() {
            @Override
            public String name() {
                return "structured_tool";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("42", new MyPayload(42));
            }
        };
        var recording = new RecordingTool("step_b", "_received");
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(toolWithStructured)
                .adapter(result -> {
                    MyPayload payload = result.getStructuredOutput(MyPayload.class);
                    return "value=" + (payload != null ? payload.value() : "null");
                })
                .step(recording)
                .build();

        pipeline.execute("x");

        assertThat(recording.receivedInputs).containsExactly("value=42");
    }

    // ========================
    // ToolContext propagation
    // ========================

    @Test
    void setContext_propagatedToAbstractAgentToolSteps() {
        var contextCapturingStep = new ContextCapturingTool("context_capturing");
        var pipeline = ToolPipeline.of("p", "desc", contextCapturingStep);

        // Inject a non-default context via ToolContextInjector
        var metrics = new RecordingMetrics();
        var ctx = ToolContext.of("p", metrics, Executors.newVirtualThreadPerTaskExecutor());
        ToolContextInjector.injectContext(pipeline, ctx);

        pipeline.execute("test");

        assertThat(contextCapturingStep.contextWasInjected).isTrue();
    }

    @Test
    void setContext_nonAbstractAgentToolSteps_notAffected() {
        // Plain AgentTool steps (not AbstractAgentTool) should not cause errors during injection
        var plainStep = prefixTool("plain", "P:");
        var pipeline = ToolPipeline.of("p", "desc", plainStep);

        var ctx = ToolContext.of("p", NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor());

        // Should not throw
        ToolContextInjector.injectContext(pipeline, ctx);
        ToolResult result = pipeline.execute("test");
        assertThat(result.isSuccess()).isTrue();
    }

    // ========================
    // getSteps() and getErrorStrategy()
    // ========================

    @Test
    void getSteps_returnsStepsInOrder() {
        var stepA = prefixTool("step_a", "A:");
        var stepB = prefixTool("step_b", "B:");
        var stepC = prefixTool("step_c", "C:");
        var pipeline = ToolPipeline.of("p", "desc", stepA, stepB, stepC);

        assertThat(pipeline.getSteps()).containsExactly(stepA, stepB, stepC);
    }

    @Test
    void getSteps_returnedListIsUnmodifiable() {
        var pipeline = ToolPipeline.of("p", "desc", prefixTool("a", "A:"));

        assertThatThrownBy(() -> pipeline.getSteps().add(prefixTool("b", "B:")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getErrorStrategy_defaultIsFailFast() {
        var pipeline = ToolPipeline.of("p", "desc", prefixTool("a", "A:"));
        assertThat(pipeline.getErrorStrategy()).isEqualTo(PipelineErrorStrategy.FAIL_FAST);
    }

    @Test
    void getErrorStrategy_reflectsBuilderSetting() {
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(prefixTool("a", "A:"))
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        assertThat(pipeline.getErrorStrategy()).isEqualTo(PipelineErrorStrategy.CONTINUE_ON_FAILURE);
    }

    // ========================
    // Factory method: of(AgentTool first, AgentTool... rest)
    // ========================

    @Test
    void of_autoGeneratesNameFromStepNames() {
        var pipeline = ToolPipeline.of(prefixTool("step_a", "A:"), prefixTool("step_b", "B:"));

        assertThat(pipeline.name()).isEqualTo("step_a_then_step_b");
    }

    @Test
    void of_autoGeneratesDescriptionFromStepNames() {
        var pipeline = ToolPipeline.of(prefixTool("step_a", "A:"), prefixTool("step_b", "B:"));

        assertThat(pipeline.description()).isEqualTo("Pipeline: step_a -> step_b");
    }

    @Test
    void of_singleStep_nameIsJustStepName() {
        var pipeline = ToolPipeline.of(prefixTool("solo", "S:"));

        assertThat(pipeline.name()).isEqualTo("solo");
        assertThat(pipeline.description()).isEqualTo("Pipeline: solo");
    }

    @Test
    void of_nullFirstStep_throws() {
        assertThatThrownBy(() -> ToolPipeline.of((AgentTool) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("first step");
    }

    @Test
    void of_nullElementInRest_throws() {
        assertThatThrownBy(() -> ToolPipeline.of(prefixTool("a", "A:"), (AgentTool) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null elements");
    }

    // ========================
    // Factory method: of(String name, String description, ...)
    // ========================

    @Test
    void of_withExplicitNameAndDescription_usesProvidedValues() {
        var pipeline = ToolPipeline.of(
                "my_pipeline", "Does something useful", prefixTool("step_a", "A:"), prefixTool("step_b", "B:"));

        assertThat(pipeline.name()).isEqualTo("my_pipeline");
        assertThat(pipeline.description()).isEqualTo("Does something useful");
    }

    // ========================
    // Builder validation
    // ========================

    @Test
    void builder_blankName_throws() {
        assertThatThrownBy(() ->
                        ToolPipeline.builder().name(" ").description("desc").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void builder_nullName_throws() {
        assertThatThrownBy(() -> ToolPipeline.builder().description("desc").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void builder_blankDescription_throws() {
        assertThatThrownBy(
                        () -> ToolPipeline.builder().name("p").description("  ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void builder_nullStep_throws() {
        assertThatThrownBy(
                        () -> ToolPipeline.builder().name("p").description("d").step(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step tool");
    }

    @Test
    void builder_adapterBeforeAnyStep_throws() {
        assertThatThrownBy(
                        () -> ToolPipeline.builder().name("p").description("d").adapter(result -> result.getOutput()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("adapter() must be called after a step()");
    }

    @Test
    void builder_nullAdapter_throws() {
        assertThatThrownBy(() -> ToolPipeline.builder()
                        .name("p")
                        .description("d")
                        .step(prefixTool("a", "A:"))
                        .adapter(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adapter");
    }

    @Test
    void builder_nullErrorStrategy_throws() {
        assertThatThrownBy(
                        () -> ToolPipeline.builder().name("p").description("d").errorStrategy(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("errorStrategy");
    }

    // ========================
    // AgentTool interface contract
    // ========================

    @Test
    void name_returnsConfiguredName() {
        var pipeline =
                ToolPipeline.builder().name("my_pipeline").description("desc").build();

        assertThat(pipeline.name()).isEqualTo("my_pipeline");
    }

    @Test
    void description_returnsConfiguredDescription() {
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("does useful things")
                .build();

        assertThat(pipeline.description()).isEqualTo("does useful things");
    }

    @Test
    void pipeline_implementsAgentTool() {
        var pipeline = ToolPipeline.of("p", "d", prefixTool("a", "A:"));
        assertThat(pipeline).isInstanceOf(AgentTool.class);
    }

    @Test
    void pipeline_extendsAbstractAgentTool() {
        var pipeline = ToolPipeline.of("p", "d", prefixTool("a", "A:"));
        assertThat(pipeline).isInstanceOf(AbstractAgentTool.class);
    }

    // ========================
    // Opportunistic: adapter not called when previous step fails (CONTINUE_ON_FAILURE)
    // ========================

    @Test
    void adapter_notCalledWhenPrecedingStepFailedAndStrategyIsContinue() {
        var adapterCalled = new AtomicInteger(0);
        var recording = new RecordingTool("step_b", "_done");
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(failingTool("step_a", "error_from_a"))
                .adapter(result -> {
                    adapterCalled.incrementAndGet();
                    return "ADAPTED";
                })
                .step(recording)
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        pipeline.execute("x");

        // Adapter must not be called when its step failed
        assertThat(adapterCalled.get()).isEqualTo(0);
        // Error message is forwarded directly
        assertThat(recording.receivedInputs).containsExactly("error_from_a");
    }

    // ========================
    // Opportunistic: plain AgentTool exceptions caught per-step
    // ========================

    @Test
    void execute_plainAgentToolThrows_failFast_pipelineReturnFailureWithStepName() {
        var throwingTool = new AgentTool() {
            @Override
            public String name() {
                return "throwing_step";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ToolResult execute(String input) {
                throw new RuntimeException("boom from plain tool");
            }
        };
        var step2Called = new java.util.concurrent.atomic.AtomicInteger(0);
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(throwingTool)
                .step(new AgentTool() {
                    @Override
                    public String name() {
                        return "step2";
                    }

                    @Override
                    public String description() {
                        return "";
                    }

                    @Override
                    public ToolResult execute(String input) {
                        step2Called.incrementAndGet();
                        return ToolResult.success("done");
                    }
                })
                .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
                .build();

        ToolResult result = pipeline.execute("x");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("throwing_step");
        assertThat(result.getErrorMessage()).contains("boom from plain tool");
        // Step 2 was never called
        assertThat(step2Called.get()).isEqualTo(0);
    }

    @Test
    void execute_plainAgentToolThrows_continueOnFailure_nextStepReceivesErrorMessage() {
        var throwingTool = new AgentTool() {
            @Override
            public String name() {
                return "throwing_step";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ToolResult execute(String input) {
                throw new RuntimeException("boom");
            }
        };
        var recording = new RecordingTool("step2", "_done");
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(throwingTool)
                .step(recording)
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        ToolResult result = pipeline.execute("x");

        // Step 2 ran; received the error message from the exception
        assertThat(recording.receivedInputs).hasSize(1);
        assertThat(recording.receivedInputs.get(0)).contains("throwing_step");
        assertThat(recording.receivedInputs.get(0)).contains("boom");
        // Final result is step 2's result
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void execute_adapterReturnsNull_nextStepReceivesEmptyString() {
        var recording = new RecordingTool("step_b", "_done");
        var pipeline = ToolPipeline.builder()
                .name("p")
                .description("d")
                .step(fixedOutputTool("step_a", "some output"))
                .adapter(result -> null) // adapter returns null
                .step(recording)
                .build();

        pipeline.execute("x");

        // Null adapter output must be normalized to ""
        assertThat(recording.receivedInputs).containsExactly("");
    }

    // ========================
    // Helpers
    // ========================

    static class RecordingMetrics implements ToolMetrics {
        @Override
        public void incrementSuccess(String toolName, String agentRole) {}

        @Override
        public void incrementFailure(String toolName, String agentRole) {}

        @Override
        public void incrementError(String toolName, String agentRole) {}

        @Override
        public void recordDuration(String toolName, String agentRole, Duration duration) {}

        @Override
        public void incrementCounter(String metricName, String toolName, Map<String, String> tags) {}

        @Override
        public void recordValue(String metricName, String toolName, double value, Map<String, String> tags) {}
    }
}
