package net.agentensemble.workspace;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link Workspace} backed by a git worktree.
 *
 * <p>The worktree is created by {@link GitWorktreeProvider} and lives in an isolated directory
 * with its own branch. {@link #close()} removes the worktree and deletes the temporary branch.
 *
 * <p>This class is thread-safe. {@link #close()} is idempotent and never throws.
 */
final class GitWorktreeWorkspace implements Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(GitWorktreeWorkspace.class);

    private final Path worktreePath;
    private final String branchName;
    private final Path repoRoot;
    private final boolean autoCleanup;
    private final AtomicBoolean active = new AtomicBoolean(true);

    GitWorktreeWorkspace(Path worktreePath, String branchName, Path repoRoot, boolean autoCleanup) {
        this.worktreePath = worktreePath;
        this.branchName = branchName;
        this.repoRoot = repoRoot;
        this.autoCleanup = autoCleanup;
    }

    @Override
    public Path path() {
        return worktreePath;
    }

    @Override
    public String id() {
        return branchName;
    }

    @Override
    public boolean isActive() {
        return active.get();
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }

        if (!autoCleanup) {
            LOG.debug("Workspace {} marked inactive (autoCleanup disabled, skipping removal)", branchName);
            return;
        }

        removeWorktree();
        deleteBranch();
    }

    private void removeWorktree() {
        try {
            GitProcess.Result result = GitProcess.run(repoRoot, "worktree", "remove", worktreePath.toString());
            if (result.isSuccess()) {
                LOG.debug("Removed worktree {}", worktreePath);
                return;
            }

            // Worktree may be dirty -- retry with --force.
            LOG.debug("Worktree remove failed ({}), retrying with --force: {}", result.exitCode(), result.stderr());
            GitProcess.Result forceResult =
                    GitProcess.run(repoRoot, "worktree", "remove", "--force", worktreePath.toString());
            if (forceResult.isSuccess()) {
                LOG.debug("Force-removed worktree {}", worktreePath);
            } else {
                LOG.warn(
                        "Failed to remove worktree {} (exit {}): {}",
                        worktreePath,
                        forceResult.exitCode(),
                        forceResult.stderr());
            }
        } catch (WorkspaceException e) {
            LOG.warn("Exception removing worktree {}: {}", worktreePath, e.getMessage());
        }
    }

    private void deleteBranch() {
        try {
            GitProcess.Result result = GitProcess.run(repoRoot, "branch", "-D", branchName);
            if (result.isSuccess()) {
                LOG.debug("Deleted branch {}", branchName);
            } else {
                LOG.warn("Failed to delete branch {} (exit {}): {}", branchName, result.exitCode(), result.stderr());
            }
        } catch (WorkspaceException e) {
            LOG.warn("Exception deleting branch {}: {}", branchName, e.getMessage());
        }
    }
}
