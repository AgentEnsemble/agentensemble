package net.agentensemble.workspace;

import java.nio.file.Path;

/**
 * An isolated working directory for a coding agent.
 *
 * <p>Implementations may be backed by a git worktree ({@link GitWorktreeWorkspace}) or a
 * plain temporary directory ({@link DirectoryWorkspace}). In both cases, changes made inside
 * the workspace do not affect the original project directory until the user explicitly merges
 * or copies them.
 *
 * <p>{@link #close()} cleans up the workspace (removes worktree, deletes temp directory, etc.)
 * and is idempotent -- calling it more than once has no additional effect. Cleanup failures are
 * logged but never thrown, so {@code close()} is safe to call in finally blocks and
 * try-with-resources.
 */
public interface Workspace extends AutoCloseable {

    /**
     * Absolute path to the isolated working directory.
     *
     * @return the workspace root path
     */
    Path path();

    /**
     * Human-readable identifier for this workspace.
     *
     * <p>For git worktrees this is the branch name; for directory workspaces it is the
     * directory name.
     *
     * @return the workspace identifier
     */
    String id();

    /**
     * Whether this workspace is still active (not yet cleaned up).
     *
     * @return {@code true} if the workspace has not been closed
     */
    boolean isActive();

    /**
     * Clean up this workspace.
     *
     * <p>For git worktrees this removes the worktree and deletes the temporary branch. For
     * directory workspaces this recursively deletes the temporary directory. This method is
     * idempotent and never throws.
     */
    @Override
    void close();
}
