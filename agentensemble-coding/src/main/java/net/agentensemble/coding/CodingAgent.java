package net.agentensemble.coding;

import dev.langchain4j.model.chat.ChatModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.agentensemble.Agent;
import net.agentensemble.tools.io.FileReadTool;
import net.agentensemble.workspace.Workspace;

/**
 * Factory that produces a standard {@link Agent} pre-configured for coding tasks.
 *
 * <p>The factory auto-detects the project type, assembles the appropriate tool set based on
 * the selected {@link ToolBackend}, and generates a coding-specific system prompt. The
 * returned {@link Agent} is a plain framework agent -- no subclassing involved.
 *
 * <p>Usage:
 * <pre>
 * Agent agent = CodingAgent.builder()
 *     .llm(model)
 *     .workingDirectory(Path.of("/path/to/project"))
 *     .toolBackend(ToolBackend.AUTO)
 *     .maxIterations(75)
 *     .build();
 * </pre>
 *
 * @see CodingTask
 * @see CodingEnsemble
 */
public final class CodingAgent {

    private static final String DEFAULT_ROLE = "Senior Software Engineer";
    private static final String DEFAULT_GOAL = "Implement, debug, and refactor code with precision. "
            + "Read the codebase to understand context, make focused changes, "
            + "and verify correctness by running tests.";
    private static final int DEFAULT_MAX_ITERATIONS = 75;

    private CodingAgent() {}

    /**
     * Create a new builder.
     *
     * @return a fresh builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for constructing a coding {@link Agent}.
     *
     * <p>Required: {@link #llm(ChatModel)} and either {@link #workingDirectory(Path)} or
     * {@link #workspace(Workspace)}. All other fields have sensible defaults.
     */
    public static final class Builder {
        private ChatModel llm;
        private Path workingDirectory;
        private Workspace workspace;
        private ToolBackend toolBackend = ToolBackend.AUTO;
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private List<Object> additionalTools = List.of();

        private Builder() {}

        /** The LLM to use for the agent. Required. */
        public Builder llm(ChatModel llm) {
            this.llm = llm;
            return this;
        }

        /** The project directory to work in. Mutually exclusive with {@link #workspace}. */
        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = workingDirectory;
            return this;
        }

        /** A workspace to scope tools to. Mutually exclusive with {@link #workingDirectory}. */
        public Builder workspace(Workspace workspace) {
            this.workspace = workspace;
            return this;
        }

        /** Tool backend selection. Default: {@link ToolBackend#AUTO}. */
        public Builder toolBackend(ToolBackend toolBackend) {
            this.toolBackend = Objects.requireNonNull(toolBackend, "toolBackend must not be null");
            return this;
        }

        /** Maximum tool-call iterations. Default: 75. */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** Additional tools to include alongside the coding tool set. */
        public Builder additionalTools(Object... tools) {
            if (tools == null) {
                throw new IllegalArgumentException("additionalTools varargs array must not be null");
            }
            for (int i = 0; i < tools.length; i++) {
                if (tools[i] == null) {
                    throw new IllegalArgumentException(
                            "additionalTools must not contain null elements (null at index " + i + ')');
                }
            }
            this.additionalTools = List.of(tools);
            return this;
        }

        /**
         * Build the coding agent.
         *
         * @return a standard {@link Agent} configured for coding tasks
         * @throws NullPointerException     if {@code llm} is null
         * @throws IllegalStateException    if neither workingDirectory nor workspace is set,
         *                                  or if both are set
         * @throws IllegalArgumentException if {@code maxIterations} is not positive
         */
        public Agent build() {
            Objects.requireNonNull(llm, "llm must not be null");
            validateDirectory();

            Path baseDir = workspace != null ? workspace.path() : workingDirectory;
            ProjectContext project = ProjectDetector.analyze(baseDir);
            ToolBackend resolved = ToolBackendDetector.resolve(toolBackend);
            List<Object> tools = assembleTools(baseDir, resolved);

            String background = CodingSystemPrompt.build(project);

            return Agent.builder()
                    .role(DEFAULT_ROLE)
                    .goal(DEFAULT_GOAL)
                    .background(background)
                    .tools(tools)
                    .llm(llm)
                    .maxIterations(maxIterations)
                    .build();
        }

        private void validateDirectory() {
            if (workingDirectory == null && workspace == null) {
                throw new IllegalStateException("Either workingDirectory or workspace must be set");
            }
            if (workingDirectory != null && workspace != null) {
                throw new IllegalStateException(
                        "workingDirectory and workspace are mutually exclusive -- set one, not both");
            }
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be positive, got: " + maxIterations);
            }
        }

        private List<Object> assembleTools(Path baseDir, ToolBackend resolved) {
            List<Object> tools = new ArrayList<>();

            switch (resolved) {
                case MINIMAL -> tools.add(FileReadTool.of(baseDir));
                case JAVA ->
                // When agentensemble-tools-coding ships, wire real Java tools here.
                // Until then, fail fast rather than silently degrading to MINIMAL.
                throw new UnsupportedOperationException("ToolBackend.JAVA is not yet implemented: "
                        + "Java-specific coding tools are not wired in this build");
                case MCP ->
                // When agentensemble-mcp ships, wire real MCP tools here.
                // Until then, fail fast rather than silently degrading to MINIMAL.
                throw new UnsupportedOperationException(
                        "ToolBackend.MCP is not yet implemented: " + "MCP tools are not wired in this build");
                default -> tools.add(FileReadTool.of(baseDir));
            }

            for (Object tool : additionalTools) {
                tools.add(tool);
            }

            return Collections.unmodifiableList(tools);
        }
    }
}
