package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GitWorktreeWorkspace#close()} error handling paths.
 *
 * <p>Uses a bogus repoRoot to exercise the warn/catch paths that occur when
 * git commands fail during cleanup.
 */
class GitWorktreeWorkspaceCloseTest {

    @Test
    void close_withBogusRepoRoot_logsWarningsButDoesNotThrow(@TempDir Path tempDir) {
        // Create a workspace pointing to a non-git directory -- close will fail to run
        // git worktree remove and git branch -D, but should not throw.
        GitWorktreeWorkspace ws =
                new GitWorktreeWorkspace(tempDir.resolve("nonexistent-worktree"), "fake-branch", tempDir, true);

        ws.close(); // Should not throw

        assertThat(ws.isActive()).isFalse();
    }

    @Test
    void close_withBogusRepoRoot_forceRemoveAlsoFails(@TempDir Path tempDir) {
        // The worktree path doesn't exist, and repoRoot is not a git repo.
        // Both normal and force remove will fail, exercising the warn log paths.
        GitWorktreeWorkspace ws = new GitWorktreeWorkspace(tempDir.resolve("bogus"), "bogus-branch", tempDir, true);

        ws.close();

        assertThat(ws.isActive()).isFalse();
    }

    @Test
    void close_branchDeleteFails_doesNotThrow(@TempDir Path tempDir) {
        // Even if branch deletion fails, close should not throw
        GitWorktreeWorkspace ws = new GitWorktreeWorkspace(tempDir.resolve("ws"), "nonexistent-branch", tempDir, true);

        ws.close();

        assertThat(ws.isActive()).isFalse();
    }
}
