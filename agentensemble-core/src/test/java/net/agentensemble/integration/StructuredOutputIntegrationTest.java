package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.exception.OutputParsingException;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for structured output (outputType) execution.
 *
 * Uses mocked LLMs to simulate JSON, invalid JSON, and retry scenarios
 * without real network calls.
 */
class StructuredOutputIntegrationTest {

    record ResearchReport(String title, String summary) {}

    record FindingsReport(String topic, List<String> findings) {}

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    // ========================
    // Happy path: first attempt succeeds
    // ========================

    @Test
    void testStructuredOutput_happyPath_parsedOutputAvailable() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("{\"title\": \"AI Trends\", \"summary\": \"AI is growing fast\"}"));

        var agent = Agent.builder()
                .role("Researcher")
                .goal("Research AI trends")
                .llm(mockLlm)
                .build();

        var task = Task.builder()
                .description("Research AI trends in 2026")
                .expectedOutput("A structured research report")
                .agent(agent)
                .outputType(ResearchReport.class)
                .build();

        var output = Ensemble.builder().task(task).build().run();

        // Raw output is preserved
        assertThat(output.getRaw()).contains("AI Trends");

        // Structured output is available
        var taskOutput = output.getTaskOutputs().get(0);
        assertThat(taskOutput.getOutputType()).isEqualTo(ResearchReport.class);

        ResearchReport report = taskOutput.getParsedOutput(ResearchReport.class);
        assertThat(report.title()).isEqualTo("AI Trends");
        assertThat(report.summary()).isEqualTo("AI is growing fast");
    }

    @Test
    void testStructuredOutput_jsonInMarkdownFence_parsedCorrectly() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                .thenReturn(textResponse(
                        "Here is the output:\n```json\n{\"title\": \"Report\", \"summary\": \"Details\"}\n```"));

        var agent =
                Agent.builder().role("Researcher").goal("Research").llm(mockLlm).build();

        var task = Task.builder()
                .description("Research task")
                .expectedOutput("A structured report")
                .agent(agent)
                .outputType(ResearchReport.class)
                .build();

        var output = Ensemble.builder().task(task).build().run();

        ResearchReport report = output.getTaskOutputs().get(0).getParsedOutput(ResearchReport.class);
        assertThat(report.title()).isEqualTo("Report");
        assertThat(report.summary()).isEqualTo("Details");
    }

    // ========================
    // Retry path: first attempt fails, second succeeds
    // ========================

    @Test
    void testStructuredOutput_retryOnParseFailure_succeedsOnSecondAttempt() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class)))
                // First call: invalid JSON (main execution)
                .thenReturn(textResponse("I apologize, let me provide the structured output..."))
                // Second call: valid JSON (retry)
                .thenReturn(textResponse("{\"title\": \"AI Report\", \"summary\": \"AI trends\"}"));

        var agent =
                Agent.builder().role("Researcher").goal("Research").llm(mockLlm).build();

        var task = Task.builder()
                .description("Research AI")
                .expectedOutput("A report")
                .agent(agent)
                .outputType(ResearchReport.class)
                .maxOutputRetries(3)
                .build();

        var output = Ensemble.builder().task(task).build().run();

        // LLM was called twice: once for main execution, once for the retry
        verify(mockLlm, times(2)).chat(any(ChatRequest.class));

        ResearchReport report = output.getTaskOutputs().get(0).getParsedOutput(ResearchReport.class);
        assertThat(report.title()).isEqualTo("AI Report");
    }

    // ========================
    // All retries exhausted: throws OutputParsingException
    // ========================

    @Test
    void testStructuredOutput_allRetriesExhausted_throwsOutputParsingException() {
        var mockLlm = mock(ChatModel.class);
        // Always returns invalid JSON
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("This is not JSON"));

        var agent =
                Agent.builder().role("Researcher").goal("Research").llm(mockLlm).build();

        int maxRetries = 2;
        var task = Task.builder()
                .description("Research AI")
                .expectedOutput("A report")
                .agent(agent)
                .outputType(ResearchReport.class)
                .maxOutputRetries(maxRetries)
                .build();

        var ensemble = Ensemble.builder().task(task).build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(OutputParsingException.class)
                .satisfies(ex -> {
                    var ope = (OutputParsingException) ex;
                    assertThat(ope.getOutputType()).isEqualTo(ResearchReport.class);
                    assertThat(ope.getAttemptCount()).isEqualTo(maxRetries + 1);
                    assertThat(ope.getParseErrors()).hasSize(maxRetries + 1);
                    assertThat(ope.getRawOutput()).isEqualTo("This is not JSON");
                });

        // Called once for main execution + maxRetries for retries
        verify(mockLlm, times(maxRetries + 1)).chat(any(ChatRequest.class));
    }

    @Test
    void testStructuredOutput_zeroRetries_throwsOnFirstFailure() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Not JSON"));

        var agent =
                Agent.builder().role("Researcher").goal("Research").llm(mockLlm).build();

        var task = Task.builder()
                .description("Research AI")
                .expectedOutput("A report")
                .agent(agent)
                .outputType(ResearchReport.class)
                .maxOutputRetries(0)
                .build();

        var ensemble = Ensemble.builder().task(task).build();

        assertThatThrownBy(ensemble::run)
                .isInstanceOf(OutputParsingException.class)
                .satisfies(ex -> {
                    var ope = (OutputParsingException) ex;
                    assertThat(ope.getAttemptCount()).isEqualTo(1);
                    assertThat(ope.getParseErrors()).hasSize(1);
                });

        // Called exactly once (no retries)
        verify(mockLlm, times(1)).chat(any(ChatRequest.class));
    }

    // ========================
    // Backward compatibility: task without outputType
    // ========================

    @Test
    void testNoOutputType_rawOutputOnly_noStructuredParsing() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Plain text response"));

        var agent =
                Agent.builder().role("Researcher").goal("Research").llm(mockLlm).build();

        var task = Task.builder()
                .description("Research AI")
                .expectedOutput("A plain text report")
                .agent(agent)
                // no outputType
                .build();

        var output = Ensemble.builder().task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("Plain text response");

        var taskOutput = output.getTaskOutputs().get(0);
        assertThat(taskOutput.getOutputType()).isNull();
        assertThat(taskOutput.getParsedOutput()).isNull();
    }

    // ========================
    // Mixed tasks: some with outputType, some without
    // ========================

    @Test
    void testMixedTasks_someStructured_someRaw() {
        var mockLlm1 = mock(ChatModel.class);
        when(mockLlm1.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("{\"title\": \"Research\", \"summary\": \"Summary\"}"));

        var mockLlm2 = mock(ChatModel.class);
        when(mockLlm2.chat(any(ChatRequest.class))).thenReturn(textResponse("A nicely written blog post."));

        var researcher = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(mockLlm1)
                .build();

        var writer = Agent.builder().role("Writer").goal("Write").llm(mockLlm2).build();

        var researchTask = Task.builder()
                .description("Research AI trends")
                .expectedOutput("A structured report")
                .agent(researcher)
                .outputType(ResearchReport.class)
                .build();

        var writeTask = Task.builder()
                .description("Write a blog post")
                .expectedOutput("A blog post")
                .agent(writer)
                .context(List.of(researchTask))
                // no outputType -- plain text
                .build();

        var output =
                Ensemble.builder().task(researchTask).task(writeTask).build().run();

        // Task 1: structured output available
        var researchOutput = output.getTaskOutputs().get(0);
        assertThat(researchOutput.getOutputType()).isEqualTo(ResearchReport.class);
        ResearchReport report = researchOutput.getParsedOutput(ResearchReport.class);
        assertThat(report.title()).isEqualTo("Research");

        // Task 2: plain text only
        var writeOutput = output.getTaskOutputs().get(1);
        assertThat(writeOutput.getOutputType()).isNull();
        assertThat(writeOutput.getRaw()).isEqualTo("A nicely written blog post.");
    }

    // ========================
    // Works with PARALLEL workflow
    // ========================

    @Test
    void testStructuredOutput_parallelWorkflow_bothTasksParsed() {
        var mockLlm1 = mock(ChatModel.class);
        when(mockLlm1.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("{\"title\": \"Task1\", \"summary\": \"Summary1\"}"));

        var mockLlm2 = mock(ChatModel.class);
        when(mockLlm2.chat(any(ChatRequest.class)))
                .thenReturn(textResponse("{\"title\": \"Task2\", \"summary\": \"Summary2\"}"));

        var agent1 = Agent.builder().role("Agent1").goal("Work").llm(mockLlm1).build();

        var agent2 = Agent.builder().role("Agent2").goal("Work").llm(mockLlm2).build();

        var task1 = Task.builder()
                .description("Task 1")
                .expectedOutput("Structured output 1")
                .agent(agent1)
                .outputType(ResearchReport.class)
                .build();

        var task2 = Task.builder()
                .description("Task 2")
                .expectedOutput("Structured output 2")
                .agent(agent2)
                .outputType(ResearchReport.class)
                .build();

        var output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(2);

        // Both tasks should have parsed output
        var outputs = output.getTaskOutputs();
        long parsedCount =
                outputs.stream().filter(t -> t.getParsedOutput() != null).count();
        assertThat(parsedCount).isEqualTo(2);
    }
}
