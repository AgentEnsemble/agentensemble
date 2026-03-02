package io.agentensemble.ensemble;

import io.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnsembleOutputTest {

    @Test
    void testBuild_withAllFields_succeeds() {
        var taskOutput = TaskOutput.builder()
                .raw("Research result")
                .taskDescription("Research task")
                .agentRole("Researcher")
                .completedAt(Instant.now())
                .duration(Duration.ofSeconds(5))
                .toolCallCount(2)
                .build();

        var output = EnsembleOutput.builder()
                .raw("Final output")
                .taskOutputs(List.of(taskOutput))
                .totalDuration(Duration.ofSeconds(5))
                .totalToolCalls(2)
                .build();

        assertThat(output.getRaw()).isEqualTo("Final output");
        assertThat(output.getTaskOutputs()).hasSize(1);
        assertThat(output.getTotalDuration()).isEqualTo(Duration.ofSeconds(5));
        assertThat(output.getTotalToolCalls()).isEqualTo(2);
    }

    @Test
    void testTaskOutputsList_isImmutable() {
        var output = EnsembleOutput.builder()
                .raw("output")
                .taskOutputs(List.of())
                .totalDuration(Duration.ofSeconds(1))
                .totalToolCalls(0)
                .build();

        assertThat(output.getTaskOutputs()).isUnmodifiable();
    }
}
