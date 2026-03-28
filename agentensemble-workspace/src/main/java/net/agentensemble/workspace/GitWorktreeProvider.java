package net.agentensemble.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link WorkspaceProvider} that creates isolated workspaces using git worktrees.
 *
 * <p>Each workspace gets its own branch and working directory, created via
 * {@code git worktree add -b <branch> <dir> <baseRef>}. This provides zero-copy,
 * branch-isolated working directories from the same repository.
 *
 * <p>Usage:
 * <pre>
 * GitWorktreeProvider provider = GitWorktreeProvider.of(repoRoot);
 *
 * // Default configuration (branch from HEAD, auto-cleanup on close)
 * try (Workspace ws = provider.create()) {
 *     // work in ws.path()
 * }
 *
 * // Custom configuration
 * WorkspaceConfig config = WorkspaceConfig.builder()
 *     .namePrefix("fix-bug")
 *     .baseRef("main")
 *     .build();
 * try (Workspace ws = provider.create(config)) {
 *     // work in ws.path()
 * }
 * </pre>
 */
public final class GitWorktreeProvider implements WorkspaceProvider {

    private static final Logger LOG = LoggerFactory.getLogger(GitWorktreeProvider.class);
    private static final String DEFAULT_PREFIX = "agent";
    private static final int UUID_SHORT_LENGTH = 8;

    private final Path repoRoot;

    private GitWorktreeProvider(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    /**
     * Create a provider for the given git repository root.
     *
     * @param repoRoot the root directory of the git repository
     * @return a new provider
     * @throws WorkspaceException if the path is not a git repository
     */
    public static GitWorktreeProvider of(Path repoRoot) {
        Path normalized = repoRoot.toAbsolutePath().normalize();
        // .git can be a directory (normal repo) or a file (worktree pointing to main repo)
        if (!Files.exists(normalized.resolve(".git"))) {
            throw new WorkspaceException("Not a git repository: " + normalized);
        }
        return new GitWorktreeProvider(normalized);
    }

    @Override
    public Workspace create(WorkspaceConfig config) {
        String prefix = config.getNamePrefix() != null ? config.getNamePrefix() : DEFAULT_PREFIX;
        String branchName = prefix + "-" + UUID.randomUUID().toString().substring(0, UUID_SHORT_LENGTH);

        Path workspacesDir = config.getWorkspacesDir() != null
                ? config.getWorkspacesDir()
                : repoRoot.resolve(".agentensemble").resolve("workspaces");

        Path worktreePath = workspacesDir.resolve(branchName);

        try {
            Files.createDirectories(workspacesDir);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to create workspaces directory: " + workspacesDir, e);
        }

        String baseRef = config.getBaseRef();
        GitProcess.Result result =
                GitProcess.run(repoRoot, "worktree", "add", "-b", branchName, worktreePath.toString(), baseRef);

        if (!result.isSuccess()) {
            throw new WorkspaceException(
                    "Failed to create worktree (exit " + result.exitCode() + "): " + result.stderr());
        }

        LOG.info("Created workspace {} at {} (branch from {})", branchName, worktreePath, baseRef);
        return new GitWorktreeWorkspace(worktreePath, branchName, repoRoot, config.isAutoCleanup());
    }
}
