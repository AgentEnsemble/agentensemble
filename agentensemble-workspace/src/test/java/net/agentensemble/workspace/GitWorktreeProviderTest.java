package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitWorktreeProviderTest {

    private static boolean gitAvailable;

    @TempDir
    Path tempDir;

    Path repoDir;

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
    }

    @Test
    void of_validRepo_succeeds() {
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);
        assertThat(provider).isNotNull();
    }

    @Test
    void of_nonGitDirectory_throwsWorkspaceException() {
        Path nonGitDir = tempDir.resolve("not-a-repo");
        assertThatThrownBy(() -> GitWorktreeProvider.of(nonGitDir))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("Not a git repository");
    }

    @Test
    void create_defaultConfig_producesValidWorkspace() {
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);

        try (Workspace ws = provider.create()) {
            assertThat(ws.isActive()).isTrue();
            assertThat(ws.path()).isDirectory();
            assertThat(ws.id()).startsWith("agent-");
            assertThat(ws.id()).hasSize("agent-".length() + 8);
        }
    }

    @Test
    void create_customPrefix_usesPrefix() {
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);
        WorkspaceConfig config = WorkspaceConfig.builder().namePrefix("fix-bug").build();

        try (Workspace ws = provider.create(config)) {
            assertThat(ws.id()).startsWith("fix-bug-");
        }
    }

    @Test
    void create_customWorkspacesDir() {
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);
        Path customDir = tempDir.resolve("custom-workspaces");
        WorkspaceConfig config =
                WorkspaceConfig.builder().workspacesDir(customDir).build();

        try (Workspace ws = provider.create(config)) {
            assertThat(ws.path().getParent()).isEqualTo(customDir);
        }
    }

    @Test
    void create_customBaseRef() throws Exception {
        // Create a branch to use as base
        exec(repoDir, "git", "branch", "feature-branch");
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);
        WorkspaceConfig config =
                WorkspaceConfig.builder().baseRef("feature-branch").build();

        try (Workspace ws = provider.create(config)) {
            assertThat(ws.isActive()).isTrue();
            assertThat(ws.path()).isDirectory();
        }
    }

    @Test
    void create_multipleWorkspaces_areIndependent() {
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);

        try (Workspace ws1 = provider.create();
                Workspace ws2 = provider.create()) {
            assertThat(ws1.id()).isNotEqualTo(ws2.id());
            assertThat(ws1.path()).isNotEqualTo(ws2.path());
            assertThat(ws1.path()).isDirectory();
            assertThat(ws2.path()).isDirectory();
        }
    }

    @Test
    void create_worktreeContainsRepoFiles() throws Exception {
        // Add a file to the repo
        Files.writeString(repoDir.resolve("README.md"), "hello");
        exec(repoDir, "git", "add", "README.md");
        exec(repoDir, "git", "commit", "-m", "add readme");

        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);

        try (Workspace ws = provider.create()) {
            assertThat(ws.path().resolve("README.md")).exists();
            assertThat(Files.readString(ws.path().resolve("README.md"))).isEqualTo("hello");
        }
    }

    @Test
    void create_invalidBaseRef_throwsWorkspaceException() {
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);
        WorkspaceConfig config = WorkspaceConfig.builder()
                .baseRef("nonexistent-ref-that-does-not-exist")
                .build();

        assertThatThrownBy(() -> provider.create(config))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("Failed to create worktree");
    }

    @Test
    void create_invalidWorkspacesDir_throwsWorkspaceException() {
        GitWorktreeProvider provider = GitWorktreeProvider.of(repoDir);
        // Use a path that cannot be created (file exists where dir is needed)
        Path blockingFile = repoDir.resolve("blocking-file");
        try {
            Files.writeString(blockingFile, "I block directory creation");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        WorkspaceConfig config = WorkspaceConfig.builder()
                .workspacesDir(blockingFile.resolve("cannot-create-here"))
                .build();

        assertThatThrownBy(() -> provider.create(config))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("Failed to create workspaces directory");
    }

    private static void exec(Path dir, String... command) throws Exception {
        Process p =
                new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed (exit " + exitCode + "): " + String.join(" ", command));
        }
    }
}
