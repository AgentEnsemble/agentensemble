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

class FileReadToolTest {

    @TempDir
    Path tempDir;

    private FileReadTool tool;

    @BeforeEach
    void setUp() {
        tool = FileReadTool.of(tempDir);
    }

    // --- metadata ---

    @Test
    void name_returnsFileRead() {
        assertThat(tool.name()).isEqualTo("file_read");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful reads ---

    @Test
    void execute_readsFileContent() throws IOException {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "Hello, World!");

        var result = tool.execute("hello.txt");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Hello, World!");
    }

    @Test
    void execute_readsFileInSubdirectory() throws IOException {
        Path subDir = tempDir.resolve("subdir");
        Files.createDirectory(subDir);
        Files.writeString(subDir.resolve("data.txt"), "nested content");

        var result = tool.execute("subdir/data.txt");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("nested content");
    }

    @Test
    void execute_readsMultilineFile() throws IOException {
        String content = "line one\nline two\nline three";
        Files.writeString(tempDir.resolve("multi.txt"), content);

        var result = tool.execute("multi.txt");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(content);
    }

    @Test
    void execute_readsEmptyFile() throws IOException {
        Files.writeString(tempDir.resolve("empty.txt"), "");

        var result = tool.execute("empty.txt");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    // --- path traversal rejection ---

    @Test
    void execute_pathTraversal_returnsFailure() {
        var result = tool.execute("../secret.txt");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    @Test
    void execute_absolutePathOutsideSandbox_returnsFailure() {
        var result = tool.execute("/etc/passwd");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    @Test
    void execute_nestedTraversal_returnsFailure() {
        var result = tool.execute("subdir/../../etc/passwd");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    // --- file not found ---

    @Test
    void execute_fileNotFound_returnsFailure() {
        var result = tool.execute("nonexistent.txt");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    // --- directory instead of file ---

    @Test
    void execute_directory_returnsFailure() throws IOException {
        Files.createDirectory(tempDir.resolve("adir"));
        var result = tool.execute("adir");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not a regular file");
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
        assertThatThrownBy(() -> FileReadTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> FileReadTool.of(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- invalid path (triggers exception in resolveAndValidate) ---

    @Test
    void execute_pathWithNullCharacter_returnsFailure() {
        // A path containing a null byte triggers InvalidPathException on most systems,
        // which is caught by the exception handler in resolveAndValidate() -> access denied.
        var result = tool.execute("file\u0000.txt");
        assertThat(result.isSuccess()).isFalse();
    }

    // --- unreadable file (IOException from Files.readString) ---

    @Test
    void execute_unreadableFile_returnsFailure() throws IOException {
        Path file = tempDir.resolve("secret.txt");
        Files.writeString(file, "contents");
        boolean changed = file.toFile().setReadable(false);
        // Skip on systems where permission changes are not supported (e.g., root user)
        assumeTrue(changed && !file.toFile().canRead(), "Cannot restrict file read permissions");
        try {
            var result = tool.execute("secret.txt");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).containsIgnoringCase("failed to read");
        } finally {
            file.toFile().setReadable(true);
        }
    }

    // --- symlink sandbox escape ---

    @Test
    void execute_symlinkPointingOutsideSandbox_returnsAccessDenied() throws IOException {
        // Create a real file outside the sandbox
        Path outsideDir = Files.createTempDirectory("agentensemble-outside");
        Path outsideFile = outsideDir.resolve("secret.txt");
        Files.writeString(outsideFile, "secret content");

        // Create a symlink inside the sandbox pointing to the outside file
        Path symlinkPath = tempDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(symlinkPath, outsideFile);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links not supported on this system: " + e.getMessage());
            return;
        }

        try {
            var result = tool.execute("link.txt");
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
        } finally {
            Files.deleteIfExists(symlinkPath);
            Files.deleteIfExists(outsideFile);
            Files.deleteIfExists(outsideDir);
        }
    }
}
