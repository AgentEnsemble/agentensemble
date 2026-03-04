package net.agentensemble.tools.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
