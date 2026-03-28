package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("PMD.CloseResource") // Tests explicitly verify close() behavior
class GitWorktreeWorkspaceTest {

    private static boolean gitAvailable;

    @TempDir
    Path tempDir;

    Path repoDir;
    GitWorktreeProvider provider;

    @BeforeAll
    static void checkGitAvailability() {
        try {
            Process p = new ProcessBuilder("git", "--version").start();
            gitAvailable = p.waitFor() == 0;
        } catch (Exception e) {
            gitAvailable = false;
        }
    }

    @BeforeEach
    void setupRepo() throws Exception {
        assumeTrue(gitAvailable, "Git not available");
        repoDir = tempDir.resolve("repo");
        Files.createDirectories(repoDir);
        exec(repoDir, "git", "init");
        exec(repoDir, "git", "config", "user.email", "test@example.com");
        exec(repoDir, "git", "config", "user.name", "Test User");
        exec(repoDir, "git", "commit", "--allow-empty", "-m", "initial");
        provider = GitWorktreeProvider.of(repoDir);
    }

    @Test
    void close_removesWorktreeAndBranch() {
        Workspace ws = provider.create();
        String branchName = ws.id();
        Path wsPath = ws.path();
        assertThat(wsPath).isDirectory();

        ws.close();

        assertThat(ws.isActive()).isFalse();
        assertThat(wsPath).doesNotExist();
        // Verify branch is also deleted
        GitProcess.Result result = GitProcess.run(repoDir, "branch", "--list", branchName);
        assertThat(result.stdout()).isEmpty();
    }

    @Test
    void close_isIdempotent() {
        Workspace ws = provider.create();

        ws.close();
        ws.close(); // Should not throw

        assertThat(ws.isActive()).isFalse();
    }

    @Test
    void close_dirtyWorkspace_forceRemoves() throws Exception {
        Workspace ws = provider.create();
        // Create an uncommitted file in the worktree
        Files.writeString(ws.path().resolve("dirty.txt"), "uncommitted changes");

        ws.close();

        assertThat(ws.isActive()).isFalse();
        assertThat(ws.path()).doesNotExist();
    }

    @Test
    void close_autoCleanupDisabled_doesNotRemove() {
        WorkspaceConfig config = WorkspaceConfig.builder().autoCleanup(false).build();
        Workspace ws = provider.create(config);
        Path wsPath = ws.path();

        ws.close();

        assertThat(ws.isActive()).isFalse();
        // Directory should still exist
        assertThat(wsPath).isDirectory();

        // Manual cleanup for the test
        GitProcess.run(repoDir, "worktree", "remove", "--force", wsPath.toString());
        GitProcess.run(repoDir, "branch", "-D", ws.id());
    }

    @Test
    void close_alreadyDeletedWorktree_doesNotThrow() throws Exception {
        Workspace ws = provider.create();
        Path wsPath = ws.path();

        // Manually delete the worktree directory to simulate external cleanup
        deleteRecursive(wsPath);
        assertThat(wsPath).doesNotExist();

        ws.close(); // Should not throw

        assertThat(ws.isActive()).isFalse();
    }

    @Test
    void isActive_trueAfterCreation() {
        try (Workspace ws = provider.create()) {
            assertThat(ws.isActive()).isTrue();
        }
    }

    @Test
    void isActive_falseAfterClose() {
        Workspace ws = provider.create();
        ws.close();
        assertThat(ws.isActive()).isFalse();
    }

    private static void exec(Path dir, String... command) throws Exception {
        Process p =
                new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode + "): " + String.join(" ", command));
        }
    }

    private static void deleteRecursive(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursive(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
