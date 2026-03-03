package net.agentensemble.guardrail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

class GuardrailInputTest {

    @Test
    void constructor_storesAllFields() {
        TaskOutput ctx = TaskOutput.builder()
                .raw("some output")
                .taskDescription("prior task")
                .agentRole("researcher")
                .completedAt(Instant.now())
                .duration(Duration.ofMillis(100))
                .toolCallCount(0)
                .build();

        GuardrailInput input = new GuardrailInput("Summarize the news", "A summary", List.of(ctx), "writer");

        assertThat(input.taskDescription()).isEqualTo("Summarize the news");
        assertThat(input.expectedOutput()).isEqualTo("A summary");
        assertThat(input.contextOutputs()).containsExactly(ctx);
        assertThat(input.agentRole()).isEqualTo("writer");
    }

    @Test
    void contextOutputs_emptyList() {
        GuardrailInput input = new GuardrailInput("Do something", "Some output", List.of(), "analyst");

        assertThat(input.contextOutputs()).isEmpty();
    }

    @Test
    void contextOutputs_isImmutable() {
        GuardrailInput input = new GuardrailInput("Do something", "Some output", List.of(), "analyst");

        assertThat(input.contextOutputs()).isUnmodifiable();
    }
}
