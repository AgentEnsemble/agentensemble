package net.agentensemble.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GuardrailOutputTest {

    @Test
    void constructor_storesAllFields() {
        GuardrailOutput output = new GuardrailOutput("The response text", "parsed-value", "Summarize", "writer");

        assertThat(output.rawResponse()).isEqualTo("The response text");
        assertThat(output.parsedOutput()).isEqualTo("parsed-value");
        assertThat(output.taskDescription()).isEqualTo("Summarize");
        assertThat(output.agentRole()).isEqualTo("writer");
    }

    @Test
    void parsedOutput_canBeNull() {
        GuardrailOutput output = new GuardrailOutput("raw text", null, "Do a task", "agent");

        assertThat(output.parsedOutput()).isNull();
    }

    @Test
    void parsedOutput_withTypedObject() {
        record Report(String title) {}
        Report report = new Report("My Report");

        GuardrailOutput output = new GuardrailOutput("{\"title\":\"My Report\"}", report, "Research task", "analyst");

        assertThat(output.parsedOutput()).isEqualTo(report);
        assertThat(output.parsedOutput()).isInstanceOf(Report.class);
    }
}
