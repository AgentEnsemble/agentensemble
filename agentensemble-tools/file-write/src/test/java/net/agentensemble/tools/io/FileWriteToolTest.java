package net.agentensemble.tools.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolContext;
import net.agentensemble.tool.ToolContextInjector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWriteToolTest {

    @TempDir
    Path tempDir;

    private FileWriteTool tool;

    @BeforeEach
    void setUp() {
        tool = FileWriteTool.of(tempDir);
    }

    // --- metadata ---

    @Test
    void name_returnsFileWrite() {
        assertThat(tool.name()).isEqualTo("file_write");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful writes ---

    @Test
    void execute_writesFileContent() throws IOException {
        var result = tool.execute("{\"path\": \"output.txt\", \"content\": \"Hello, World!\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("output.txt"))).isEqualTo("Hello, World!");
    }

    @Test
    void execute_writesFileInSubdirectory() throws IOException {
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);

        var result = tool.execute("{\"path\": \"subdir/notes.txt\", \"content\": \"nested\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(subDir.resolve("notes.txt"))).isEqualTo("nested");
    }

    @Test
    void execute_createsParentDirectoriesIfNeeded() throws IOException {
        var result = tool.execute("{\"path\": \"new/nested/dir/file.txt\", \"content\": \"deep\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("new/nested/dir/file.txt"))).isEqualTo("deep");
    }

    @Test
    void execute_overwritesExistingFile() throws IOException {
        Files.writeString(tempDir.resolve("existing.txt"), "old content");

        var result = tool.execute("{\"path\": \"existing.txt\", \"content\": \"new content\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("existing.txt"))).isEqualTo("new content");
    }

    @Test
    void execute_writesMultilineContent() throws IOException {
        String content = "line one\nline two\nline three";
        var result = tool.execute("{\"path\": \"multi.txt\", \"content\": \"" + content.replace("\n", "\\n") + "\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("multi.txt"))).isEqualTo(content);
    }

    @Test
    void execute_writesEmptyContent() throws IOException {
        var result = tool.execute("{\"path\": \"empty.txt\", \"content\": \"\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("empty.txt"))).isEmpty();
    }

    @Test
    void execute_successReportIncludesPath() {
        var result = tool.execute("{\"path\": \"result.txt\", \"content\": \"data\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("result.txt");
    }

    // --- path traversal rejection ---

    @Test
    void execute_pathTraversal_returnsFailure() {
        var result = tool.execute("{\"path\": \"../outside.txt\", \"content\": \"x\"}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    @Test
    void execute_absolutePathOutsideSandbox_returnsFailure() {
        var result = tool.execute("{\"path\": \"/tmp/evil.txt\", \"content\": \"x\"}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    @Test
    void execute_nestedTraversal_returnsFailure() {
        var result = tool.execute("{\"path\": \"a/../../etc/passwd\", \"content\": \"x\"}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    // --- invalid JSON ---

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute("not valid json");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("invalid");
    }

    @Test
    void execute_missingPathField_returnsFailure() {
        var result = tool.execute("{\"content\": \"hello\"}");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_missingContentField_returnsFailure() {
        var result = tool.execute("{\"path\": \"file.txt\"}");
        assertThat(result.isSuccess()).isFalse();
    }

    // --- null/blank ---

    @Test
    void execute_nullInput_returnsFailure() {
        var result = tool.execute(null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_blankInput_returnsFailure() {
        var result = tool.execute("   ");
        assertThat(result.isSuccess()).isFalse();
    }

    // --- factory method validation ---

    @Test
    void of_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> FileWriteTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> FileWriteTool.of(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- invalid path (triggers exception in resolveAndValidate) ---

    @Test
    void execute_pathWithNullCharacter_returnsFailure() {
        // A path containing a null byte triggers InvalidPathException on most systems.
        var result = tool.execute("{\"path\": \"file\u0000.txt\", \"content\": \"data\"}");
        assertThat(result.isSuccess()).isFalse();
    }

    // --- unwritable directory (IOException from Files.writeString) ---

    @Test
    void execute_writeToUnwritableDirectory_returnsFailure() throws IOException {
        Path subDir = Files.createDirectory(tempDir.resolve("readonly"));
        boolean changed = subDir.toFile().setWritable(false);
        assumeTrue(changed && !subDir.toFile().canWrite(), "Cannot restrict directory write permissions");
        try {
            var result = tool.execute("{\"path\": \"readonly/secret.txt\", \"content\": \"data\"}");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).containsIgnoringCase("failed to write");
        } finally {
            subDir.toFile().setWritable(true);
        }
    }

    // --- symlink sandbox escape ---

    @Test
    void execute_symlinkDirectoryPointingOutsideSandbox_returnsAccessDenied() throws IOException {
        // Create a real directory outside the sandbox
        Path outsideDir = Files.createTempDirectory("agentensemble-outside");

        // Create a symlink inside the sandbox pointing to the outside directory
        Path symlinkPath = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(symlinkPath, outsideDir);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links not supported on this system: " + e.getMessage());
            return;
        }

        try {
            // Attempt to write through the symlink into the outside directory
            var result = tool.execute("{\"path\": \"link/secret.txt\", \"content\": \"evil\"}");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
        } finally {
            Files.deleteIfExists(symlinkPath);
            Files.deleteIfExists(outsideDir);
        }
    }

    // --- builder factory ---

    @Test
    void builder_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> FileWriteTool.builder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> FileWriteTool.builder(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_requireApprovalFalse_buildSucceeds() {
        var builtTool = FileWriteTool.builder(tempDir).requireApproval(false).build();
        assertThat(builtTool).isNotNull();
        assertThat(builtTool.name()).isEqualTo("file_write");
    }

    @Test
    void builder_requireApprovalTrue_buildSucceeds() {
        var builtTool = FileWriteTool.builder(tempDir).requireApproval(true).build();
        assertThat(builtTool).isNotNull();
    }

    // --- approval gate ---

    private FileWriteTool approvalToolWithHandler(ReviewHandler handler) {
        var builtTool = FileWriteTool.builder(tempDir).requireApproval(true).build();
        var ctx = ToolContext.of(
                builtTool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(builtTool, ctx);
        return builtTool;
    }

    @Test
    void requireApproval_disabled_writesFileWithoutApproval() throws IOException {
        var builtTool = FileWriteTool.builder(tempDir).requireApproval(false).build();

        var result = builtTool.execute("{\"path\": \"no-approval.txt\", \"content\": \"written\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("no-approval.txt"))).isEqualTo("written");
    }

    @Test
    void requireApproval_enabled_handlerContinue_writesOriginalContent() throws IOException {
        var approvalTool = approvalToolWithHandler(ReviewHandler.autoApprove());

        var result = approvalTool.execute("{\"path\": \"approved.txt\", \"content\": \"original content\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("approved.txt"))).isEqualTo("original content");
    }

    @Test
    void requireApproval_enabled_handlerExitEarly_returnsFailureWithoutWriting() {
        var approvalTool = approvalToolWithHandler(request -> ReviewDecision.exitEarly());

        var result = approvalTool.execute("{\"path\": \"never-written.txt\", \"content\": \"should not appear\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("rejected");
        assertThat(Files.exists(tempDir.resolve("never-written.txt"))).isFalse();
    }

    @Test
    void requireApproval_enabled_handlerEdit_writesRevisedContent() throws IOException {
        var approvalTool = approvalToolWithHandler(request -> ReviewDecision.edit("reviewer-revised content"));

        var result = approvalTool.execute("{\"path\": \"edited.txt\", \"content\": \"original\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("edited.txt"))).isEqualTo("reviewer-revised content");
    }

    @Test
    void requireApproval_enabled_noHandlerConfigured_throwsIllegalStateException() {
        var builtTool = FileWriteTool.builder(tempDir).requireApproval(true).build();
        // No ToolContext injected -- rawReviewHandler() returns null

        assertThatThrownBy(() -> builtTool.execute("{\"path\": \"file.txt\", \"content\": \"x\"}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires approval")
                .hasMessageContaining("ReviewHandler");
    }

    @Test
    void requireApproval_approvalDescription_containsPathAndContentPreview() {
        var capturedRequest = new net.agentensemble.review.ReviewRequest[1];
        var approvalTool = approvalToolWithHandler(request -> {
            capturedRequest[0] = request;
            return ReviewDecision.exitEarly();
        });

        approvalTool.execute("{\"path\": \"test-file.txt\", \"content\": \"the content\"}");

        assertThat(capturedRequest[0]).isNotNull();
        assertThat(capturedRequest[0].taskDescription()).contains("Write to file:");
        assertThat(capturedRequest[0].taskDescription()).contains("test-file.txt");
        assertThat(capturedRequest[0].taskDescription()).contains("the content");
    }

    @Test
    void requireApproval_longContent_previewTruncatedTo200Chars() {
        var capturedRequest = new net.agentensemble.review.ReviewRequest[1];
        var approvalTool = approvalToolWithHandler(request -> {
            capturedRequest[0] = request;
            return ReviewDecision.exitEarly();
        });
        String longContent = "x".repeat(400);

        approvalTool.execute("{\"path\": \"big.txt\", \"content\": \"" + longContent + "\"}");

        assertThat(capturedRequest[0]).isNotNull();
        // Description should be truncated, not containing the full 400 chars of content
        assertThat(capturedRequest[0].taskDescription()).contains("...");
    }
}
