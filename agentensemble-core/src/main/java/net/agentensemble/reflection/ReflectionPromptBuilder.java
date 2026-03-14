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
        StringBuilder sb = new StringBuilder(1024);

        sb.append("You are a task prompt optimization specialist. Your role is to analyze how a "
                + "task definition performed and propose improvements to its instructions "
                + "for future executions.\n\n"
                + "## Original Task Definition\n\n"
                + "### Description\n");
        sb.append(task.getDescription()).append("\n\n### Expected Output Specification\n");
        sb.append(task.getExpectedOutput()).append("\n\n## What Was Produced\n");
        sb.append(input.taskOutput()).append("\n\n");

        if (input.priorReflection().isPresent()) {
            TaskReflection prior = input.priorReflection().get();
            sb.append("## Prior Improvement Notes (run ")
                    .append(prior.runCount())
                    .append(")\n\n### Previously Refined Instructions\n");
            sb.append(prior.refinedDescription()).append("\n\n### Previously Refined Output Specification\n");
            sb.append(prior.refinedExpectedOutput()).append("\n\n");
            if (!prior.observations().isEmpty()) {
                sb.append("### Prior Observations\n");
                for (String obs : prior.observations()) {
                    sb.append("- ").append(obs).append('\n');
                }
                sb.append('\n');
            }
        } else {
            sb.append("## Prior Improvement Notes\n" + "None — this is the first reflection for this task.\n\n");
        }

        sb.append("## Analysis Instructions\n\n"
                + "Using the task definition and execution output above:\n\n"
                + "1. Evaluate whether the task instructions were clear, concise, and effective.\n"
                + "2. Identify where the instructions helped or hindered the agent's execution flow.\n"
                + "3. Propose targeted improvements focused on:\n"
                + "   - Improving clarity and conciseness\n"
                + "   - Consolidating overlapping or redundant guidance\n"
                + "   - Identifying outdated or low-impact instructions that add noise\n"
                + "   - Tightening the expected output format if output deviated from intent\n\n");
        sb.append("Respond using EXACTLY the following structured format ")
                .append("(do not include any text outside these sections):\n\n"
                        + "REFINED_DESCRIPTION:\n"
                        + "[An improved version of the task description]\n\n"
                        + "REFINED_EXPECTED_OUTPUT:\n"
                        + "[An improved version of the expected output specification]\n\n"
                        + "OBSERVATIONS:\n"
                        + "- [Key observation about what worked or did not work]\n\n"
                        + "SUGGESTIONS:\n"
                        + "- [Specific actionable improvement for future runs]\n");

        return sb.toString();
    }
}
