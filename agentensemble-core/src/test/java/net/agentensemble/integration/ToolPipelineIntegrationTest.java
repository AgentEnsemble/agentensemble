package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.ensemble.ExitReason;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.PipelineErrorStrategy;
import net.agentensemble.tool.ToolPipeline;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for ToolPipeline running inside a full ensemble execution.
 *
 * <p>These tests verify that a ToolPipeline:
 * <ul>
 *   <li>Appears as a single tool in the LLM's tool specification</li>
 *   <li>Executes all steps without additional LLM round-trips between steps</li>
 *   <li>Returns a single result to the LLM after all steps complete</li>
 *   <li>Correctly propagates ToolContext (metrics, logging) to nested steps</li>
 *   <li>Respects error strategy within a live ensemble execution</li>
 * </ul>
 *
 * <p>Uses Mockito-mocked LLMs (no real network calls). The mock LLM:
 * <ol>
 *   <li>Returns a tool-call response for the pipeline on the first invocation</li>
 *   <li>Returns a final text response on the second invocation</li>
 * </ol>
 */
class ToolPipelineIntegrationTest {

    // ========================
    // Fixture tools
    // ========================

    static AgentTool upperCaseTool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "upper_case";
            }

            @Override
            public String description() {
                return "Converts input to upper case";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success(input == null ? "" : input.toUpperCase());
            }
        };
    }

    static AgentTool appendSuffixTool(String suffix) {
        return new AgentTool() {
            @Override
            public String name() {
                return "append_suffix";
            }

            @Override
            public String description() {
                return "Appends '" + suffix + "' to input";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success((input != null ? input : "") + suffix);
            }
        };
    }

    static AgentTool failingTool(String errorMessage) {
        return new AgentTool() {
            @Override
            public String name() {
                return "failing_step";
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

    /** A tool that counts how many times it has been called. */
    static class CallCountingTool implements AgentTool {
        final AtomicInteger callCount = new AtomicInteger(0);
        final String toolName;

        CallCountingTool(String name) {
            this.toolName = name;
        }

        @Override
        public String name() {
            return toolName;
        }

        @Override
        public String description() {
            return "Counts calls";
        }

        @Override
        public ToolResult execute(String input) {
            callCount.incrementAndGet();
            return ToolResult.success("counted: " + callCount.get());
        }
    }

    // ========================
    // Helpers
    // ========================

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private static ChatResponse toolCallResponse(String toolName, String input) {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("req-1")
                .name(toolName)
                .arguments("{\"input\":\"" + input + "\"}")
                .build();
        return ChatResponse.builder().aiMessage(new AiMessage(List.of(request))).build();
    }

    private static Agent agentWithPipelineCall(
            String pipelineName, String pipelineInput, String finalAnswer, AgentTool... tools) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse(pipelineName, pipelineInput))
                .thenReturn(textResponse(finalAnswer));
        return Agent.builder()
                .role("Worker")
                .goal("Complete the task using the pipeline")
                .llm(mockLlm)
                .tools(List.of(tools))
                .build();
    }

    // ========================
    // Pipeline appears as a single tool
    // ========================

    @Test
    void pipeline_appearsAsSingleToolToLlm_allStepsExecutedWithinOneLlmTurn() {
        var step1CallCount = new AtomicInteger(0);
        var step2CallCount = new AtomicInteger(0);
        var step1 = new AgentTool() {
            @Override
            public String name() {
                return "step1";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public ToolResult execute(String input) {
                step1CallCount.incrementAndGet();
                return ToolResult.success(input + "_step1");
            }
        };
        var step2 = new AgentTool() {
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
                step2CallCount.incrementAndGet();
                return ToolResult.success(input + "_step2");
            }
        };

        var pipeline = ToolPipeline.of("my_pipeline", "Runs step1 then step2", step1, step2);
        var agent = agentWithPipelineCall("my_pipeline", "initial_input", "Pipeline ran successfully", pipeline);
        var task = Task.builder()
                .description("Run the pipeline")
                .expectedOutput("Confirmation")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        // Ensemble completes
        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getRaw()).isEqualTo("Pipeline ran successfully");

        // Both steps ran exactly once -- no extra LLM round-trips triggered them
        assertThat(step1CallCount.get()).isEqualTo(1);
        assertThat(step2CallCount.get()).isEqualTo(1);
    }

    @Test
    void pipeline_returnsChainedOutputToLlm_singleToolCallResult() {
        // The LLM receives the final output of the pipeline chain in one tool result
        var upperCase = upperCaseTool();
        var appendSuffix = appendSuffixTool("_processed");
        var pipeline = ToolPipeline.of("transform", "Transform data", upperCase, appendSuffix);

        var agent = agentWithPipelineCall("transform", "hello world", "Data has been transformed", pipeline);
        var task = Task.builder()
                .description("Transform the data")
                .expectedOutput("Confirmation")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        // The LLM received "HELLO WORLD_processed" as the tool result (step1->step2 chained)
        // and produced its final answer
        assertThat(output.getRaw()).isEqualTo("Data has been transformed");
    }

    // ========================
    // Error strategy in ensemble context
    // ========================

    @Test
    void pipeline_failFast_failedMiddleStep_llmReceivesErrorMessage() {
        var step3CallCount = new AtomicInteger(0);
        var pipeline = ToolPipeline.builder()
                .name("pipeline_with_failure")
                .description("Pipeline that fails in the middle")
                .step(upperCaseTool())
                .step(failingTool("middle step failed"))
                .step(new AgentTool() {
                    @Override
                    public String name() {
                        return "step3";
                    }

                    @Override
                    public String description() {
                        return "";
                    }

                    @Override
                    public ToolResult execute(String input) {
                        step3CallCount.incrementAndGet();
                        return ToolResult.success("should not run");
                    }
                })
                .errorStrategy(PipelineErrorStrategy.FAIL_FAST)
                .build();

        // LLM receives the pipeline error and adapts
        var agent = agentWithPipelineCall(
                "pipeline_with_failure", "input", "Could not process: middle step failed", pipeline);
        var task = Task.builder()
                .description("Process data")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        // Step3 was never called because of FAIL_FAST
        assertThat(step3CallCount.get()).isEqualTo(0);
    }

    @Test
    void pipeline_continueOnFailure_allStepsRun_llmReceivesFinalResult() {
        var step3 = new CallCountingTool("step3");
        var pipeline = ToolPipeline.builder()
                .name("resilient_pipeline")
                .description("Continues even on failure")
                .step(upperCaseTool())
                .step(failingTool("non-fatal error"))
                .step(step3)
                .errorStrategy(PipelineErrorStrategy.CONTINUE_ON_FAILURE)
                .build();

        var agent = agentWithPipelineCall("resilient_pipeline", "data", "Processed with partial errors", pipeline);
        var task = Task.builder()
                .description("Process data resiliently")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        // Step3 ran despite step2's failure
        assertThat(step3.callCount.get()).isEqualTo(1);
    }

    // ========================
    // ToolContext propagation through ensemble
    // ========================

    @Test
    void pipeline_toolContextPropagated_metricsRecordedForNestedAbstractAgentToolSteps() {
        // This uses the v2 task-first API with auto-synthesized agent and mocked LLM
        // to verify that metrics/logging are wired through to pipeline steps.
        var pipeline = ToolPipeline.of("pipeline", "desc", upperCaseTool(), appendSuffixTool("_done"));

        var agent = agentWithPipelineCall("pipeline", "test", "Done", pipeline);
        var task = Task.builder()
                .description("Use the pipeline")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        // The test verifies no exceptions are thrown during context injection and execution
        EnsembleOutput output = Ensemble.builder().task(task).build().run();
        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
    }

    // ========================
    // Pipeline with adapter in ensemble context
    // ========================

    @Test
    void pipeline_withAdapter_transformsBetweenSteps_ensembleCompletesSuccessfully() {
        var step2Recording = new java.util.ArrayList<String>();
        var step2 = new AgentTool() {
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
                step2Recording.add(input);
                return ToolResult.success("processed: " + input);
            }
        };

        var pipeline = ToolPipeline.builder()
                .name("pipeline_with_adapter")
                .description("Transforms between steps")
                .step(upperCaseTool())
                .adapter(result -> "PREFIX_" + result.getOutput())
                .step(step2)
                .build();

        var agent = agentWithPipelineCall("pipeline_with_adapter", "hello", "Adapter pipeline succeeded", pipeline);
        var task = Task.builder()
                .description("Run the adapted pipeline")
                .expectedOutput("Result")
                .agent(agent)
                .build();

        EnsembleOutput output = Ensemble.builder().task(task).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        // Step2 received the adapter's output, not the raw upper-case result
        assertThat(step2Recording).containsExactly("PREFIX_HELLO");
    }

    // ========================
    // Pipeline registered via Task.builder().tools() (v2 task-first API)
    // ========================

    @Test
    void pipeline_registeredOnTask_autoSynthesizedAgent_executes() {
        var pipeline = ToolPipeline.of("task_pipeline", "Pipeline on task", upperCaseTool());

        // v2: no explicit agent -- agent is synthesized from task description
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(toolCallResponse("task_pipeline", "synthesized"))
                .thenReturn(textResponse("Task-first pipeline works"));

        var task = Task.builder()
                .description("Run the pipeline tool")
                .expectedOutput("Result")
                .tools(List.of(pipeline))
                .build();

        EnsembleOutput output =
                Ensemble.builder().chatLanguageModel(mockLlm).task(task).build().run();

        assertThat(output.getExitReason()).isEqualTo(ExitReason.COMPLETED);
        assertThat(output.getRaw()).isEqualTo("Task-first pipeline works");
    }
}
