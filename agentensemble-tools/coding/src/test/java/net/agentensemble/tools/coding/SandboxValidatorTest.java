package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxValidatorTest {

    @TempDir
    Path tempDir;

    private SandboxValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SandboxValidator(tempDir);
    }

    // --- construction ---

    @Test
    void constructor_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> new SandboxValidator(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new SandboxValidator(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baseDir_returnsNormalizedAbsolutePath() {
        assertThat(validator.baseDir()).isAbsolute();
        assertThat(validator.baseDir().toString()).doesNotContain("..");
    }

    // --- resolveAndValidate ---

    @Test
    void resolveAndValidate_validRelativePath_returnsResolvedPath() {
        Path result = validator.resolveAndValidate("subdir/file.txt");

        assertThat(result).isNotNull();
        assertThat(result.toString()).startsWith(validator.baseDir().toString());
    }

    @Test
    void resolveAndValidate_pathTraversal_returnsNull() {
        assertThat(validator.resolveAndValidate("../outside.txt")).isNull();
    }

    @Test
    void resolveAndValidate_absolutePathOutside_returnsNull() {
        assertThat(validator.resolveAndValidate("/tmp/evil.txt")).isNull();
    }

    @Test
    void resolveAndValidate_nestedTraversal_returnsNull() {
        assertThat(validator.resolveAndValidate("a/../../etc/passwd")).isNull();
    }

    @Test
    void resolveAndValidate_nullCharacter_returnsNull() {
        assertThat(validator.resolveAndValidate("file\u0000.txt")).isNull();
    }

    @Test
    void resolveAndValidate_simpleFilename_returnsResolvedPath() {
        Path result = validator.resolveAndValidate("file.txt");

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(validator.baseDir().resolve("file.txt"));
    }

    // --- isSymlinkEscape ---

    @Test
    void isSymlinkEscape_pathInsideSandbox_returnsFalse() throws IOException {
        Path subDir = Files.createDirectory(tempDir.resolve("sub"));
        Path file = subDir.resolve("file.txt");

        assertThat(validator.isSymlinkEscape(file)).isFalse();
    }

    @Test
    void isSymlinkEscape_symlinkOutsideSandbox_returnsTrue() throws IOException {
        Path outsideDir = Files.createTempDirectory("agentensemble-outside");
        Path symlinkPath = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(symlinkPath, outsideDir);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            assumeTrue(false, "Symbolic links not supported on this system: " + e.getMessage());
            return;
        }

        try {
            Path fileInLink = symlinkPath.resolve("file.txt");
            assertThat(validator.isSymlinkEscape(fileInLink)).isTrue();
        } finally {
            Files.deleteIfExists(symlinkPath);
            Files.deleteIfExists(outsideDir);
        }
    }
}
