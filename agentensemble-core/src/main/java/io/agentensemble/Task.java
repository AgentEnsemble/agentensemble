package io.agentensemble;

import io.agentensemble.exception.ValidationException;
import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * A unit of work assigned to an agent.
 *
 * Tasks are immutable value objects. Use the builder to construct instances.
 * All validation is performed at build time.
 *
 * Task descriptions and expected outputs may contain {variable} placeholders
 * that are resolved at {@code ensemble.run(inputs)} time.
 *
 * Example:
 * <pre>
 * Task researchTask = Task.builder()
 *     .description("Research {topic} developments in {year}")
 *     .expectedOutput("A detailed report on {topic}")
 *     .agent(researcher)
 *     .build();
 * </pre>
 */
@Builder(toBuilder = true)
@Value
public class Task {

    /**
     * What the agent should do. Supports {variable} template placeholders
     * resolved at ensemble.run(inputs) time. Required.
     */
    String description;

    /**
     * What the output should look like. Included in the agent's user prompt
     * so the agent knows the expected format and content. Supports templates.
     * Required.
     */
    String expectedOutput;

    /** The agent assigned to execute this task. Required. */
    Agent agent;

    /**
     * Tasks whose outputs should be included as context when executing this task.
     * All referenced tasks must be executed before this one (validated at
     * ensemble.run() time by the workflow executor).
     * Default: empty list.
     */
    List<Task> context;

    /**
     * Custom builder that sets defaults and validates the Task configuration.
     */
    public static class TaskBuilder {

        // Default value
        private List<Task> context = List.of();

        public Task build() {
            validateDescription();
            validateExpectedOutput();
            validateAgent();
            context = List.copyOf(context != null ? context : List.of());
            return new Task(description, expectedOutput, agent, context);
        }

        private void validateDescription() {
            if (description == null || description.isBlank()) {
                throw new ValidationException("Task description must not be blank");
            }
        }

        private void validateExpectedOutput() {
            if (expectedOutput == null || expectedOutput.isBlank()) {
                throw new ValidationException("Task expectedOutput must not be blank");
            }
        }

        private void validateAgent() {
            if (agent == null) {
                throw new ValidationException("Task agent must not be null");
            }
        }
    }
}
