package io.agentensemble.exception;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
