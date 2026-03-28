package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobToolTest {

    @TempDir
    Path tempDir;

    private GlobTool tool;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test file structure:
        // src/main/java/Foo.java
        // src/main/java/Bar.java
        // src/test/java/FooTest.java
        // docs/readme.md
        // build.gradle
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(tempDir.resolve("src/main/java/Foo.java"), "class Foo {}");
        Files.writeString(tempDir.resolve("src/main/java/Bar.java"), "class Bar {}");
        Files.writeString(tempDir.resolve("src/test/java/FooTest.java"), "class FooTest {}");
        Files.writeString(tempDir.resolve("docs/readme.md"), "# README");
        Files.writeString(tempDir.resolve("build.gradle"), "apply plugin: 'java'");

        tool = GlobTool.of(tempDir);
    }

    // --- metadata ---

    @Test
    void name_returnsGlob() {
        assertThat(tool.name()).isEqualTo("glob");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful searches ---

    @Test
    void execute_findJavaFiles_returnsMatchingPaths() {
        var result = tool.execute("{\"pattern\": \"**/*.java\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("src/main/java/Foo.java");
        assertThat(result.getOutput()).contains("src/main/java/Bar.java");
        assertThat(result.getOutput()).contains("src/test/java/FooTest.java");
    }

    @Test
    void execute_findMarkdownFiles_returnsOnlyMdFiles() {
        var result = tool.execute("{\"pattern\": \"**/*.md\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("docs/readme.md");
        assertThat(result.getOutput()).doesNotContain(".java");
    }

    @Test
    void execute_resultsAreSortedAlphabetically() {
        var result = tool.execute("{\"pattern\": \"**/*.java\"}");

        assertThat(result.isSuccess()).isTrue();
        String[] lines = result.getOutput().split("\n");
        assertThat(lines).isSortedAccordingTo(String::compareTo);
    }

    // --- subdirectory path filter ---

    @Test
    void execute_withPath_searchesOnlySubdirectory() {
        var result = tool.execute("{\"pattern\": \"**/*.java\", \"path\": \"src/main\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("src/main/java/Foo.java");
        assertThat(result.getOutput()).contains("src/main/java/Bar.java");
        assertThat(result.getOutput()).doesNotContain("FooTest.java");
    }

    @Test
    void execute_withPath_nonExistentDirectory_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"**/*.java\", \"path\": \"nonexistent\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not a directory");
    }

    // --- cap at MAX_RESULTS ---

    @Test
    void execute_capsResultsAtMaxLimit() throws IOException {
        // Create 250 files
        Path manyDir = Files.createDirectory(tempDir.resolve("many"));
        for (int i = 0; i < 250; i++) {
            Files.writeString(manyDir.resolve("file" + String.format("%03d", i) + ".txt"), "content");
        }

        var result = tool.execute("{\"pattern\": \"**/*.txt\"}");

        assertThat(result.isSuccess()).isTrue();
        String[] lines = result.getOutput().split("\n");
        assertThat(lines).hasSizeLessThanOrEqualTo(GlobTool.MAX_RESULTS);
    }

    // --- empty results ---

    @Test
    void execute_noMatch_returnsSuccessWithMessage() {
        var result = tool.execute("{\"pattern\": \"**/*.xyz\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsIgnoringCase("no files found");
    }

    // --- path traversal rejection ---

    @Test
    void execute_pathTraversal_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"**/*.java\", \"path\": \"../outside\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    @Test
    void execute_absolutePathOutsideSandbox_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"**/*.java\", \"path\": \"/tmp\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    // --- invalid input ---

    @Test
    void execute_blankPattern_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"   \"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("blank");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute("not valid json");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_missingPatternField_returnsFailure() {
        var result = tool.execute("{\"path\": \"src\"}");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_nullInput_returnsFailure() {
        var result = tool.execute((String) null);

        assertThat(result.isSuccess()).isFalse();
    }

    // --- invalid glob pattern ---

    @Test
    void execute_invalidGlobPattern_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"[invalid\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("invalid");
    }

    // --- inputType ---

    @Test
    void inputType_returnsGlobInputClass() {
        assertThat(tool.inputType()).isEqualTo(GlobInput.class);
    }

    // --- factory method validation ---

    @Test
    void of_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> GlobTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> GlobTool.of(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
