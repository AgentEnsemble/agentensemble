package io.agentensemble.ensemble;

import io.agentensemble.task.TaskOutput;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.util.List;

/**
 * The result of a complete ensemble execution.
 *
 * Contains the final output (from the last task), all individual task outputs
 * in execution order, timing information, and a count of total tool calls.
 */
@Builder
@Value
public class EnsembleOutput {

    /** The raw text output from the final task. Convenience accessor. */
    String raw;

    /** All task outputs in execution order. */
    List<TaskOutput> taskOutputs;

    /** Total wall-clock duration of the ensemble execution. */
    Duration totalDuration;

    /** Total number of tool calls made across all tasks. */
    int totalToolCalls;

    /**
     * Custom builder that stores taskOutputs as an immutable list.
     */
    public static class EnsembleOutputBuilder {

        public EnsembleOutput build() {
            List<TaskOutput> immutableOutputs = taskOutputs != null
                    ? List.copyOf(taskOutputs)
                    : List.of();
            return new EnsembleOutput(raw, immutableOutputs, totalDuration, totalToolCalls);
        }
    }
}
