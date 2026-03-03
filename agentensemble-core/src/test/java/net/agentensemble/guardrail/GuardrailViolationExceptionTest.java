package net.agentensemble.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import net.agentensemble.exception.AgentEnsembleException;
import org.junit.jupiter.api.Test;

class GuardrailViolationExceptionTest {

    @Test
    void extendsAgentEnsembleException() {
        GuardrailViolationException ex = new GuardrailViolationException(
                GuardrailViolationException.GuardrailType.INPUT, "contains PII", "Summarize", "writer");
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void inputType_storesAllFields() {
        GuardrailViolationException ex = new GuardrailViolationException(
                GuardrailViolationException.GuardrailType.INPUT, "contains PII", "Summarize the news", "writer");

        assertThat(ex.getGuardrailType()).isEqualTo(GuardrailViolationException.GuardrailType.INPUT);
        assertThat(ex.getViolationMessage()).isEqualTo("contains PII");
        assertThat(ex.getTaskDescription()).isEqualTo("Summarize the news");
        assertThat(ex.getAgentRole()).isEqualTo("writer");
    }

    @Test
    void outputType_storesAllFields() {
        GuardrailViolationException ex = new GuardrailViolationException(
                GuardrailViolationException.GuardrailType.OUTPUT, "response too long", "Write a report", "analyst");

        assertThat(ex.getGuardrailType()).isEqualTo(GuardrailViolationException.GuardrailType.OUTPUT);
        assertThat(ex.getViolationMessage()).isEqualTo("response too long");
        assertThat(ex.getTaskDescription()).isEqualTo("Write a report");
        assertThat(ex.getAgentRole()).isEqualTo("analyst");
    }

    @Test
    void message_includesGuardrailTypeAndViolation() {
        GuardrailViolationException ex = new GuardrailViolationException(
                GuardrailViolationException.GuardrailType.INPUT, "contains PII", "Summarize", "writer");

        assertThat(ex.getMessage()).contains("INPUT");
        assertThat(ex.getMessage()).contains("contains PII");
    }

    @Test
    void message_includesAgentAndTask() {
        GuardrailViolationException ex = new GuardrailViolationException(
                GuardrailViolationException.GuardrailType.OUTPUT, "too long", "Write an essay", "essayist");

        assertThat(ex.getMessage()).contains("essayist");
        assertThat(ex.getMessage()).contains("Write an essay");
    }
}
