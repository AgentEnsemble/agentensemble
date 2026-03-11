package net.agentensemble.task;

import java.util.List;
import java.util.Objects;

/**
 * Context provided to a {@link TaskHandler} when it executes a deterministic task.
 *
 * <p>Carries the resolved task description and expected output (with any
 * {@code {variable}} placeholders already substituted), plus the outputs of all
 * tasks declared in {@code Task.context()} that have already completed. The
 * context outputs are in the same order as the context list declared on the task.
 *
 * @see TaskHandler
 */
public record TaskHandlerContext(String description, String expectedOutput, List<TaskOutput> contextOutputs) {

    /**
     * Canonical constructor -- validates inputs and defensive-copies the context list.
     *
     * @param description    the resolved task description; must not be null
     * @param expectedOutput the resolved expected output; must not be null
     * @param contextOutputs outputs from prior tasks declared in {@code Task.context()};
     *                       must not be null; may be empty
     */
    public TaskHandlerContext {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(expectedOutput, "expectedOutput must not be null");
        Objects.requireNonNull(contextOutputs, "contextOutputs must not be null");
        contextOutputs = List.copyOf(contextOutputs);
    }
}
