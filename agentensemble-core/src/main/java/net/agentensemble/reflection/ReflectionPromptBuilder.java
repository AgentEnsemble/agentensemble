package net.agentensemble.reflection;

import net.agentensemble.Task;

/**
 * Builds the user prompt for the reflection LLM call.
 *
 * <p>The reflection prompt presents the original task definition, the output produced,
 * and any prior reflection notes, then asks the model to identify improvements to the
 * task's instructions for future runs.
 *
 * <p>The response is expected in a structured format with clearly labeled sections
 * that {@link LlmReflectionStrategy} parses to construct a {@link TaskReflection}.
 */
final class ReflectionPromptBuilder {

    private ReflectionPromptBuilder() {}

    /**
     * Builds the reflection prompt from the provided input.
     *
     * @param input the reflection input bundle; must not be null
     * @return the formatted prompt text to send to the reflection LLM
     */
    static String buildPrompt(ReflectionInput input) {
        Task task = input.task();
        StringBuilder sb = new StringBuilder();

        sb.append("You are a task prompt optimization specialist. Your role is to analyze how a ")
                .append("task definition performed and propose improvements to its instructions ")
                .append("for future executions.\n\n");

        sb.append("## Original Task Definition\n\n");
        sb.append("### Description\n");
        sb.append(task.getDescription()).append("\n\n");
        sb.append("### Expected Output Specification\n");
        sb.append(task.getExpectedOutput()).append("\n\n");

        sb.append("## What Was Produced\n");
        sb.append(input.taskOutput()).append("\n\n");

        if (input.priorReflection().isPresent()) {
            TaskReflection prior = input.priorReflection().get();
            sb.append("## Prior Improvement Notes (run ")
                    .append(prior.runCount())
                    .append(")\n\n");
            sb.append("### Previously Refined Instructions\n");
            sb.append(prior.refinedDescription()).append("\n\n");
            sb.append("### Previously Refined Output Specification\n");
            sb.append(prior.refinedExpectedOutput()).append("\n\n");
            if (!prior.observations().isEmpty()) {
                sb.append("### Prior Observations\n");
                for (String obs : prior.observations()) {
                    sb.append("- ").append(obs).append("\n");
                }
                sb.append("\n");
            }
        } else {
            sb.append("## Prior Improvement Notes\n");
            sb.append("None — this is the first reflection for this task.\n\n");
        }

        sb.append("## Analysis Instructions\n\n");
        sb.append("Using the task definition and execution output above:\n\n");
        sb.append("1. Evaluate whether the task instructions were clear, concise, and effective.\n");
        sb.append("2. Identify where the instructions helped or hindered the agent's execution flow.\n");
        sb.append("3. Propose targeted improvements focused on:\n");
        sb.append("   - Improving clarity and conciseness\n");
        sb.append("   - Consolidating overlapping or redundant guidance\n");
        sb.append("   - Identifying outdated or low-impact instructions that add noise\n");
        sb.append("   - Tightening the expected output format if output deviated from intent\n\n");
        sb.append("Respond using EXACTLY the following structured format ")
                .append("(do not include any text outside these sections):\n\n");
        sb.append("REFINED_DESCRIPTION:\n");
        sb.append("[An improved version of the task description]\n\n");
        sb.append("REFINED_EXPECTED_OUTPUT:\n");
        sb.append("[An improved version of the expected output specification]\n\n");
        sb.append("OBSERVATIONS:\n");
        sb.append("- [Key observation about what worked or did not work]\n\n");
        sb.append("SUGGESTIONS:\n");
        sb.append("- [Specific actionable improvement for future runs]\n");

        return sb.toString();
    }
}
