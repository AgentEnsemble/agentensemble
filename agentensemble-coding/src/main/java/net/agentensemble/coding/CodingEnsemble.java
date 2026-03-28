package net.agentensemble.coding;

import dev.langchain4j.model.chat.ChatModel;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.workspace.GitWorktreeProvider;
import net.agentensemble.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Convenience runners for coding agent ensembles.
 *
 * <p>{@link #run(ChatModel, Path, Task...)} executes tasks directly in the given working
 * directory. {@link #runIsolated(ChatModel, Path, Task...)} creates a git worktree first,
 * runs the tasks there, and preserves the worktree on success so the user can review and
 * merge the changes.
 *
 * <p>Usage:
 * <pre>
 * // Direct execution
 * EnsembleOutput result = CodingEnsemble.run(model, workingDir,
 *     CodingTask.fix("Fix the login timeout bug"));
 *
 * // Isolated execution in a git worktree
 * EnsembleOutput result = CodingEnsemble.runIsolated(model, repoRoot,
 *     CodingTask.implement("Add user profile endpoint"));
 * </pre>
 *
 * @see CodingAgent
 * @see CodingTask
 */
public final class CodingEnsemble {

    private static final Logger LOG = LoggerFactory.getLogger(CodingEnsemble.class);

    private CodingEnsemble() {}

    /**
     * Run coding tasks directly in the given working directory.
     *
     * <p>Changes are made in-place. Use {@link #runIsolated(ChatModel, Path, Task...)} for
     * branch-isolated execution.
     *
     * @param model      the LLM to use
     * @param workingDir the project root directory
     * @param tasks      one or more tasks to execute
     * @return the ensemble output
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if no tasks are provided
     */
    public static EnsembleOutput run(ChatModel model, Path workingDir, Task... tasks) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(workingDir, "workingDir must not be null");
        validateTasks(tasks);

        Agent agent =
                CodingAgent.builder().llm(model).workingDirectory(workingDir).build();

        Task[] agentTasks = attachAgent(agent, tasks);
        return Ensemble.run(model, agentTasks);
    }

    /**
     * Run coding tasks in an isolated git worktree.
     *
     * <p>Creates a worktree from the current HEAD, scopes all tools to it, and runs the
     * ensemble. On success the worktree is preserved so the user can review and merge. On
     * failure the worktree is cleaned up.
     *
     * @param model    the LLM to use
     * @param repoRoot the git repository root
     * @param tasks    one or more tasks to execute
     * @return the ensemble output
     * @throws NullPointerException     if any argument is null
     * @throws IllegalArgumentException if no tasks are provided
     */
    public static EnsembleOutput runIsolated(ChatModel model, Path repoRoot, Task... tasks) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(repoRoot, "repoRoot must not be null");
        validateTasks(tasks);

        GitWorktreeProvider provider = GitWorktreeProvider.of(repoRoot);
        Workspace workspace = provider.create();
        LOG.info("Created isolated workspace: {} at {}", workspace.id(), workspace.path());

        try {
            Agent agent = CodingAgent.builder().llm(model).workspace(workspace).build();

            Task[] agentTasks = attachAgent(agent, tasks);
            EnsembleOutput output = Ensemble.run(model, agentTasks);

            LOG.info("Coding ensemble completed successfully. Workspace preserved at: {}", workspace.path());
            return output;
        } catch (Throwable t) {
            LOG.warn("Coding ensemble failed. Cleaning up workspace: {}", workspace.id(), t);
            workspace.close();
            throw t;
        }
    }

    private static void validateTasks(Task... tasks) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        if (tasks.length == 0) {
            throw new IllegalArgumentException("At least one task is required");
        }
        for (int i = 0; i < tasks.length; i++) {
            if (tasks[i] == null) {
                throw new IllegalArgumentException("tasks[" + i + "] must not be null");
            }
        }
    }

    private static Task[] attachAgent(Agent agent, Task[] tasks) {
        return Arrays.stream(tasks).map(t -> t.toBuilder().agent(agent).build()).toArray(Task[]::new);
    }
}
