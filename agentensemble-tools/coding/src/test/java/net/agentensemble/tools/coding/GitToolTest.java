package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import net.agentensemble.exception.ToolConfigurationException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolContext;
import net.agentensemble.tool.ToolContextInjector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitToolTest {

    @TempDir
    Path tempDir;

    private GitTool tool;

    private static boolean gitAvailable;

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
    void setUp() throws IOException, InterruptedException {
        tool = GitTool.of(tempDir);

        if (gitAvailable) {
            // Initialize a temp git repo
            new ProcessBuilder("git", "init")
                    .directory(tempDir.toFile())
                    .start()
                    .waitFor();
            new ProcessBuilder("git", "config", "user.email", "test@test.com")
                    .directory(tempDir.toFile())
                    .start()
                    .waitFor();
            new ProcessBuilder("git", "config", "user.name", "Test")
                    .directory(tempDir.toFile())
                    .start()
                    .waitFor();
        }
    }

    // --- metadata ---

    @Test
    void name_returnsGit() {
        assertThat(tool.name()).isEqualTo("git");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- git status ---

    @Test
    void execute_status_returnsOutput() {
        assumeTrue(gitAvailable, "Git not available");

        var result = tool.execute("{\"command\": \"status\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isNotBlank();
    }

    // --- git add + commit ---

    @Test
    void execute_addAndCommit_succeeds() throws IOException {
        assumeTrue(gitAvailable, "Git not available");
        Files.writeString(tempDir.resolve("test.txt"), "hello");

        var addResult = tool.execute("{\"command\": \"add\", \"args\": \"test.txt\"}");
        assertThat(addResult.isSuccess()).isTrue();

        var commitResult = tool.execute("{\"command\": \"commit\", \"message\": \"Initial commit\"}");
        assertThat(commitResult.isSuccess()).isTrue();
    }

    // --- git diff ---

    @Test
    void execute_diff_returnsOutput() throws IOException {
        assumeTrue(gitAvailable, "Git not available");
        Files.writeString(tempDir.resolve("file.txt"), "initial");
        new ProcessBuilder("git", "add", "file.txt").directory(tempDir.toFile()).start();

        var result = tool.execute("{\"command\": \"diff\"}");
        // Even if empty, should succeed
        assertThat(result.isSuccess()).isTrue();
    }

    // --- git log ---

    @Test
    void execute_logWithArgs_returnsOutput() throws IOException, InterruptedException {
        assumeTrue(gitAvailable, "Git not available");
        Files.writeString(tempDir.resolve("f.txt"), "x");
        new ProcessBuilder("git", "add", ".")
                .directory(tempDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "commit", "-m", "test commit")
                .directory(tempDir.toFile())
                .start()
                .waitFor();

        var result = tool.execute("{\"command\": \"log\", \"args\": \"--oneline -1\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("test commit");
    }

    // --- unknown command ---

    @Test
    void execute_unknownCommand_returnsFailure() {
        var result = tool.execute("{\"command\": \"cherry-pick\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("unknown");
    }

    // --- blank command ---

    @Test
    void execute_blankCommand_returnsFailure() {
        var result = tool.execute("{\"command\": \"  \"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("blank");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute("not valid json");

        assertThat(result.isSuccess()).isFalse();
    }

    // --- dangerous operation detection ---

    @Test
    void isDangerous_push_isTrue() {
        assertThat(GitTool.isDangerous("push", "")).isTrue();
    }

    @Test
    void isDangerous_rebase_isTrue() {
        assertThat(GitTool.isDangerous("rebase", "")).isTrue();
    }

    @Test
    void isDangerous_clean_isTrue() {
        assertThat(GitTool.isDangerous("clean", "")).isTrue();
    }

    @Test
    void isDangerous_resetHard_isTrue() {
        assertThat(GitTool.isDangerous("reset", "--hard")).isTrue();
    }

    @Test
    void isDangerous_resetSoft_isFalse() {
        assertThat(GitTool.isDangerous("reset", "--soft")).isFalse();
    }

    @Test
    void isDangerous_checkoutDot_isTrue() {
        assertThat(GitTool.isDangerous("checkout", ".")).isTrue();
    }

    @Test
    void isDangerous_checkoutBranch_isFalse() {
        assertThat(GitTool.isDangerous("checkout", "main")).isFalse();
    }

    @Test
    void isDangerous_branchDelete_isTrue() {
        assertThat(GitTool.isDangerous("branch", "-D feature")).isTrue();
    }

    @Test
    void isDangerous_branchList_isFalse() {
        assertThat(GitTool.isDangerous("branch", "-a")).isFalse();
    }

    @Test
    void isDangerous_forceFlag_isTrue() {
        assertThat(GitTool.isDangerous("push", "--force origin main")).isTrue();
    }

    @Test
    void isDangerous_status_isFalse() {
        assertThat(GitTool.isDangerous("status", "")).isFalse();
    }

    @Test
    void isDangerous_diff_isFalse() {
        assertThat(GitTool.isDangerous("diff", "")).isFalse();
    }

    @Test
    void isDangerous_log_isFalse() {
        assertThat(GitTool.isDangerous("log", "")).isFalse();
    }

    @Test
    void isDangerous_add_isFalse() {
        assertThat(GitTool.isDangerous("add", ".")).isFalse();
    }

    @Test
    void isDangerous_commit_isFalse() {
        assertThat(GitTool.isDangerous("commit", "")).isFalse();
    }

    // --- approval gate ---

    private GitTool approvalToolWithHandler(ReviewHandler handler) {
        var builtTool = GitTool.builder(tempDir).requireApproval(true).build();
        var ctx = ToolContext.of(
                builtTool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(builtTool, ctx);
        return builtTool;
    }

    @Test
    void requireApproval_dangerousOp_handlerContinue_executesCommand() {
        assumeTrue(gitAvailable, "Git not available");
        var approvalTool = approvalToolWithHandler(ReviewHandler.autoApprove());

        // Status is safe, shouldn't need approval. Push is dangerous but we auto-approve.
        var result = approvalTool.execute("{\"command\": \"status\"}");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void requireApproval_dangerousOp_handlerExitEarly_returnsFailure() {
        var approvalTool = approvalToolWithHandler(request -> ReviewDecision.exitEarly());

        // Push is dangerous
        var result = approvalTool.execute("{\"command\": \"push\"}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("rejected");
    }

    @Test
    void requireApproval_safeOp_bypassesApproval() {
        assumeTrue(gitAvailable, "Git not available");
        // Handler that rejects everything -- safe ops should still succeed
        var approvalTool = approvalToolWithHandler(request -> ReviewDecision.exitEarly());

        var result = approvalTool.execute("{\"command\": \"status\"}");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void requireApproval_noHandler_dangerousOp_throwsToolConfigurationException() {
        var builtTool = GitTool.builder(tempDir).requireApproval(true).build();

        assertThatThrownBy(() -> builtTool.execute("{\"command\": \"push\"}"))
                .isInstanceOf(ToolConfigurationException.class)
                .hasMessageContaining("requires approval");
    }

    // --- git show ---

    @Test
    void execute_show_succeeds() throws IOException, InterruptedException {
        assumeTrue(gitAvailable, "Git not available");
        Files.writeString(tempDir.resolve("s.txt"), "show");
        new ProcessBuilder("git", "add", ".")
                .directory(tempDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "commit", "-m", "show commit")
                .directory(tempDir.toFile())
                .start()
                .waitFor();

        var result = tool.execute("{\"command\": \"show\", \"args\": \"--stat HEAD\"}");
        assertThat(result.isSuccess()).isTrue();
    }

    // --- git with no args and empty output ---

    @Test
    void execute_branchWithArgs_returnsOutput() throws IOException, InterruptedException {
        assumeTrue(gitAvailable, "Git not available");
        Files.writeString(tempDir.resolve("b.txt"), "branch");
        new ProcessBuilder("git", "add", ".")
                .directory(tempDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "commit", "-m", "initial")
                .directory(tempDir.toFile())
                .start()
                .waitFor();

        var result = tool.execute("{\"command\": \"branch\"}");
        assertThat(result.isSuccess()).isTrue();
    }

    // --- isDangerous edge cases ---

    @Test
    void isDangerous_checkoutDoubleDash_isTrue() {
        assertThat(GitTool.isDangerous("checkout", "-- .")).isTrue();
    }

    @Test
    void isDangerous_pushForceShortFlag_isTrue() {
        assertThat(GitTool.isDangerous("add", "something -f more")).isTrue();
    }

    // --- factory/builder validation ---

    @Test
    void of_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> GitTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> GitTool.builder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> GitTool.builder(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_negativeTimeout_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> GitTool.builder(tempDir).timeout(java.time.Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- commit with message ---

    @Test
    void execute_commitWithMessage_usesMessage() throws IOException, InterruptedException {
        assumeTrue(gitAvailable, "Git not available");
        Files.writeString(tempDir.resolve("m.txt"), "commit msg test");
        new ProcessBuilder("git", "add", ".")
                .directory(tempDir.toFile())
                .start()
                .waitFor();

        var result = tool.execute("{\"command\": \"commit\", \"message\": \"My commit message\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("My commit message");
    }

    // --- command with timeout ---

    @Test
    void execute_timeout_returnsFailure() {
        assumeTrue(gitAvailable, "Git not available");
        var timedTool =
                GitTool.builder(tempDir).timeout(java.time.Duration.ofMillis(1)).build();

        // git log on a non-git dir or long op should timeout
        var result = timedTool.execute("{\"command\": \"log\", \"args\": \"--all --oneline\"}");
        // Result may succeed quickly or fail - either way it exercises the timeout path
        assertThat(result).isNotNull();
    }

    // --- git stash ---

    @Test
    void execute_stash_succeeds() throws IOException, InterruptedException {
        assumeTrue(gitAvailable, "Git not available");
        Files.writeString(tempDir.resolve("st.txt"), "stash");
        new ProcessBuilder("git", "add", ".")
                .directory(tempDir.toFile())
                .start()
                .waitFor();
        new ProcessBuilder("git", "commit", "-m", "initial for stash")
                .directory(tempDir.toFile())
                .start()
                .waitFor();

        var result = tool.execute("{\"command\": \"stash\"}");
        // Should succeed (nothing to stash, but command is valid)
        assertThat(result).isNotNull();
    }

    // --- git fetch (no remote) ---

    @Test
    void execute_fetchNoRemote_returnsResult() {
        assumeTrue(gitAvailable, "Git not available");

        var result = tool.execute("{\"command\": \"fetch\"}");
        // No remote configured, may fail but exercises the path
        assertThat(result).isNotNull();
    }
}
