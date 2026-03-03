package net.agentensemble.exception;

import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionHierarchyTest {

    // ========================
    // AgentEnsembleException
    // ========================

    @Test
    void agentEnsembleException_extendsRuntimeException() {
        var ex = new AgentEnsembleException("test message");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void agentEnsembleException_messageConstructor() {
        var ex = new AgentEnsembleException("test message");
        assertThat(ex.getMessage()).isEqualTo("test message");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void agentEnsembleException_messageAndCauseConstructor() {
        var cause = new RuntimeException("root cause");
        var ex = new AgentEnsembleException("test message", cause);
        assertThat(ex.getMessage()).isEqualTo("test message");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void agentEnsembleException_causeConstructor() {
        var cause = new RuntimeException("root cause");
        var ex = new AgentEnsembleException(cause);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ========================
    // ValidationException
    // ========================

    @Test
    void validationException_extendsAgentEnsembleException() {
        var ex = new ValidationException("invalid config");
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void validationException_messageConstructor() {
        var ex = new ValidationException("Agent role must not be blank");
        assertThat(ex.getMessage()).isEqualTo("Agent role must not be blank");
    }

    // ========================
    // TaskExecutionException
    // ========================

    @Test
    void taskExecutionException_extendsAgentEnsembleException() {
        var ex = new TaskExecutionException("failed", "Research task", "Researcher", List.of());
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void taskExecutionException_fieldsAccessible_noCompletedOutputs() {
        var ex = new TaskExecutionException("Task failed", "Research task", "Researcher", List.of());
        assertThat(ex.getMessage()).isEqualTo("Task failed");
        assertThat(ex.getTaskDescription()).isEqualTo("Research task");
        assertThat(ex.getAgentRole()).isEqualTo("Researcher");
        assertThat(ex.getCompletedTaskOutputs()).isEmpty();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void taskExecutionException_withCause() {
        var cause = new RuntimeException("LLM timeout");
        var ex = new TaskExecutionException("Task failed", "Write task", "Writer",
                List.of(), cause);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getTaskDescription()).isEqualTo("Write task");
        assertThat(ex.getAgentRole()).isEqualTo("Writer");
    }

    @Test
    void taskExecutionException_completedOutputsIsImmutable() {
        var ex = new TaskExecutionException("failed", "task", "agent", List.of());
        assertThat(ex.getCompletedTaskOutputs()).isUnmodifiable();
    }

    // ========================
    // AgentExecutionException
    // ========================

    @Test
    void agentExecutionException_extendsAgentEnsembleException() {
        var cause = new RuntimeException("LLM error");
        var ex = new AgentExecutionException("Agent failed", "Researcher", "Research task", cause);
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void agentExecutionException_fieldsAccessible() {
        var cause = new RuntimeException("connection timeout");
        var ex = new AgentExecutionException("Agent failed to execute", "Senior Analyst",
                "Research AI trends", cause);
        assertThat(ex.getMessage()).isEqualTo("Agent failed to execute");
        assertThat(ex.getAgentRole()).isEqualTo("Senior Analyst");
        assertThat(ex.getTaskDescription()).isEqualTo("Research AI trends");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ========================
    // ToolExecutionException
    // ========================

    @Test
    void toolExecutionException_extendsAgentEnsembleException() {
        var cause = new RuntimeException("network error");
        var ex = new ToolExecutionException("Tool failed", "web_search", "AI trends", cause);
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void toolExecutionException_fieldsAccessible() {
        var cause = new RuntimeException("connection refused");
        var ex = new ToolExecutionException("Tool web_search failed", "web_search",
                "query: AI trends 2026", cause);
        assertThat(ex.getMessage()).isEqualTo("Tool web_search failed");
        assertThat(ex.getToolName()).isEqualTo("web_search");
        assertThat(ex.getToolInput()).isEqualTo("query: AI trends 2026");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    // ========================
    // MaxIterationsExceededException
    // ========================

    @Test
    void maxIterationsExceededException_extendsAgentEnsembleException() {
        var ex = new MaxIterationsExceededException("Researcher", "Research task", 25, 28);
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void maxIterationsExceededException_fieldsAccessible() {
        var ex = new MaxIterationsExceededException("Researcher", "Research AI trends", 25, 28);
        assertThat(ex.getAgentRole()).isEqualTo("Researcher");
        assertThat(ex.getTaskDescription()).isEqualTo("Research AI trends");
        assertThat(ex.getMaxIterations()).isEqualTo(25);
        assertThat(ex.getToolCallsMade()).isEqualTo(28);
    }

    @Test
    void maxIterationsExceededException_messageContainsContext() {
        var ex = new MaxIterationsExceededException("Researcher", "Research task", 10, 13);
        assertThat(ex.getMessage())
                .contains("Researcher")
                .contains("10");
    }

    // ========================
    // PromptTemplateException
    // ========================

    @Test
    void promptTemplateException_extendsAgentEnsembleException() {
        var ex = new PromptTemplateException("Missing variables: [topic]",
                List.of("topic"), "Research {topic}");
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void promptTemplateException_fieldsAccessible() {
        var missing = List.of("topic", "year");
        var template = "Research {topic} in {year}";
        var ex = new PromptTemplateException("Missing template variables: [topic, year]",
                missing, template);
        assertThat(ex.getMessage()).isEqualTo("Missing template variables: [topic, year]");
        assertThat(ex.getMissingVariables()).containsExactly("topic", "year");
        assertThat(ex.getTemplate()).isEqualTo(template);
    }

    @Test
    void promptTemplateException_missingVariablesIsImmutable() {
        var ex = new PromptTemplateException("error", List.of("x"), "{x}");
        assertThat(ex.getMissingVariables()).isUnmodifiable();
    }

    // ========================
    // ParallelExecutionException
    // ========================

    private TaskOutput taskOutput(String raw, String role, String description) {
        return TaskOutput.builder()
                .raw(raw)
                .agentRole(role)
                .taskDescription(description)
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(1))
                .toolCallCount(0)
                .build();
    }

    @Test
    void parallelExecutionException_extendsAgentEnsembleException() {
        var ex = new ParallelExecutionException("partial failure", List.of(), Map.of("task", new RuntimeException()));
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void parallelExecutionException_fieldsAccessible() {
        var output = taskOutput("result", "Researcher", "Research task");
        var cause = new RuntimeException("LLM error");
        var ex = new ParallelExecutionException(
                "1 of 2 tasks failed",
                List.of(output),
                Map.of("Write task", cause));

        assertThat(ex.getMessage()).isEqualTo("1 of 2 tasks failed");
        assertThat(ex.getCompletedTaskOutputs()).containsExactly(output);
        assertThat(ex.getFailedTaskCauses()).containsKey("Write task");
        assertThat(ex.getFailedTaskCauses().get("Write task")).isSameAs(cause);
        assertThat(ex.getCompletedCount()).isEqualTo(1);
        assertThat(ex.getFailedCount()).isEqualTo(1);
    }

    @Test
    void parallelExecutionException_completedOutputsIsImmutable() {
        var ex = new ParallelExecutionException("error", List.of(), Map.of("t", new RuntimeException()));
        assertThat(ex.getCompletedTaskOutputs()).isUnmodifiable();
    }

    @Test
    void parallelExecutionException_failedTaskCausesIsImmutable() {
        var ex = new ParallelExecutionException("error", List.of(), Map.of("t", new RuntimeException()));
        assertThat(ex.getFailedTaskCauses()).isUnmodifiable();
    }

    @Test
    void parallelExecutionException_multipleFailures_countsCorrect() {
        var o1 = taskOutput("res1", "Agent1", "Task 1");
        var o2 = taskOutput("res2", "Agent2", "Task 2");
        var ex = new ParallelExecutionException(
                "2 of 4 tasks failed",
                List.of(o1, o2),
                Map.of("Task 3", new RuntimeException("err3"), "Task 4", new RuntimeException("err4")));

        assertThat(ex.getCompletedCount()).isEqualTo(2);
        assertThat(ex.getFailedCount()).isEqualTo(2);
    }
}
