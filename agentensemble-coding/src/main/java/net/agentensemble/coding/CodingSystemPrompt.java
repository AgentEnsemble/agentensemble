package net.agentensemble.coding;

import java.util.Objects;

/**
 * Builds the agent {@code background} string with coding-specific workflow instructions.
 *
 * <p>The generated prompt includes the detected project language, build/test commands,
 * source structure, and a step-by-step workflow that guides the agent through reading,
 * planning, editing, testing, and iterating.
 */
public final class CodingSystemPrompt {

    private CodingSystemPrompt() {}

    /**
     * Build a coding-agent system prompt from the given project context.
     *
     * @param project the detected project context
     * @return the system prompt string, never {@code null}
     * @throws NullPointerException if {@code project} is null
     */
    public static String build(ProjectContext project) {
        Objects.requireNonNull(project, "project must not be null");
        StringBuilder sb = new StringBuilder();

        if ("unknown".equals(project.language())) {
            sb.append("You are an expert software engineer.\n");
        } else {
            sb.append("You are an expert software engineer working on a ")
                    .append(project.language())
                    .append(" project");
            if (!"unknown".equals(project.buildSystem())) {
                sb.append(" using ").append(project.buildSystem());
            }
            sb.append(".\n");
        }

        sb.append("\n## Workflow\n");
        sb.append("1. Read relevant code to understand the task\n");
        sb.append("2. Plan your approach before making changes\n");
        sb.append("3. Make focused, minimal changes\n");
        if (!project.testCommand().isEmpty()) {
            sb.append("4. Run tests: ").append(project.testCommand()).append('\n');
            sb.append("5. If tests fail, analyze the output and fix the issues\n");
            sb.append("6. Repeat until all tests pass\n");
        } else {
            sb.append("4. Verify your changes work correctly\n");
        }

        if (!project.buildCommand().isEmpty() || !project.testCommand().isEmpty()) {
            sb.append("\n## Build system\n");
            if (!project.buildCommand().isEmpty()) {
                sb.append("Build: ").append(project.buildCommand()).append('\n');
            }
            if (!project.testCommand().isEmpty()) {
                sb.append("Test: ").append(project.testCommand()).append('\n');
            }
        }

        if (!project.sourceRoots().isEmpty()) {
            sb.append("\n## Project structure\n");
            sb.append("Source roots:\n");
            for (String root : project.sourceRoots()) {
                sb.append("- ").append(root).append('\n');
            }
        }

        return sb.toString();
    }
}
