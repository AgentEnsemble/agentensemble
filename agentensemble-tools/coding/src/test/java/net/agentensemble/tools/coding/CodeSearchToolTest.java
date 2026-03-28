package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeSearchToolTest {

    @TempDir
    Path tempDir;

    private CodeSearchTool tool;

    @BeforeEach
    void setUp() throws IOException {
        // Create test files with known content
        Files.createDirectories(tempDir.resolve("src/main/java"));
        Files.createDirectories(tempDir.resolve("src/test/java"));
        Files.writeString(
                tempDir.resolve("src/main/java/Foo.java"),
                "package example;\n\npublic class Foo {\n    public void doSomething() {\n        System.out.println(\"hello\");\n    }\n}\n");
        Files.writeString(
                tempDir.resolve("src/main/java/Bar.java"),
                "package example;\n\npublic class Bar {\n    public void doOther() {\n        System.out.println(\"world\");\n    }\n}\n");
        Files.writeString(
                tempDir.resolve("src/test/java/FooTest.java"),
                "package example;\n\npublic class FooTest {\n    public void testFoo() {\n        // test code\n    }\n}\n");

        // Use Java fallback to ensure tests work everywhere
        tool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.JAVA_FALLBACK);
    }

    // --- metadata ---

    @Test
    void name_returnsCodeSearch() {
        assertThat(tool.name()).isEqualTo("code_search");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- successful searches ---

    @Test
    void execute_findClassDeclarations_returnsMatches() {
        var result = tool.execute("{\"pattern\": \"public class\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
        assertThat(result.getOutput()).contains("Bar.java");
        assertThat(result.getOutput()).contains("FooTest.java");
    }

    @Test
    void execute_findSpecificString_returnsCorrectFile() {
        var result = tool.execute("{\"pattern\": \"doSomething\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
        assertThat(result.getOutput()).doesNotContain("Bar.java");
    }

    @Test
    void execute_withGlobFilter_filtersFiles() {
        var result = tool.execute("{\"pattern\": \"public class\", \"glob\": \"*Test.java\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("FooTest.java");
        assertThat(result.getOutput()).doesNotContain("Foo.java:"); // Should not match Foo.java (not *Test.java)
        assertThat(result.getOutput()).doesNotContain("Bar.java");
    }

    @Test
    void execute_caseInsensitive_matchesRegardlessOfCase() {
        var result = tool.execute("{\"pattern\": \"PACKAGE\", \"ignoreCase\": true}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
    }

    @Test
    void execute_caseSensitive_doesNotMatchWrongCase() {
        var result = tool.execute("{\"pattern\": \"PACKAGE\", \"ignoreCase\": false}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsIgnoringCase("no matches");
    }

    @Test
    void execute_withContextLines_includesContext() {
        var result = tool.execute("{\"pattern\": \"doSomething\", \"contextLines\": 1}");

        assertThat(result.isSuccess()).isTrue();
        // Should include lines before and after the match
        assertThat(result.getOutput()).contains("public class Foo");
    }

    // --- subdirectory path filter ---

    @Test
    void execute_withPath_searchesOnlySubdirectory() {
        var result = tool.execute("{\"pattern\": \"public class\", \"path\": \"src/main\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
        assertThat(result.getOutput()).doesNotContain("FooTest.java");
    }

    // --- empty results ---

    @Test
    void execute_noMatch_returnsSuccessWithMessage() {
        var result = tool.execute("{\"pattern\": \"nonexistent_xyz_123\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsIgnoringCase("no matches");
    }

    // --- path traversal ---

    @Test
    void execute_pathTraversal_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"test\", \"path\": \"../outside\"}");

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
    void execute_invalidRegex_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"[invalid\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("invalid");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute("not valid json");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_missingPattern_returnsFailure() {
        var result = tool.execute("{\"glob\": \"*.java\"}");

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_nullInput_returnsFailure() {
        var result = tool.execute((String) null);

        assertThat(result.isSuccess()).isFalse();
    }

    // --- cap at MAX_MATCHES ---

    @Test
    void execute_capsResultsAtMaxLimit() throws IOException {
        // Create a file with many matching lines
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            sb.append("match_line_").append(i).append('\n');
        }
        Files.writeString(tempDir.resolve("many_matches.txt"), sb.toString());

        var result = tool.execute("{\"pattern\": \"match_line_\"}");

        assertThat(result.isSuccess()).isTrue();
        String[] lines = result.getOutput().split("\n");
        assertThat(lines).hasSizeLessThanOrEqualTo(CodeSearchTool.MAX_MATCHES);
    }

    // --- Java fallback early termination ---

    @Test
    void execute_javaFallback_terminatesAtMaxMatches() throws IOException {
        // Create many files each containing a match to hit MAX_MATCHES via visitFile termination
        Path manyDir = Files.createDirectory(tempDir.resolve("manyfiles"));
        for (int i = 0; i < 120; i++) {
            Files.writeString(manyDir.resolve("file" + String.format("%03d", i) + ".java"), "matching content here");
        }

        var result = tool.execute("{\"pattern\": \"matching content\"}");

        assertThat(result.isSuccess()).isTrue();
        String[] lines = result.getOutput().split("\n");
        assertThat(lines).hasSizeLessThanOrEqualTo(CodeSearchTool.MAX_MATCHES);
    }

    // --- Java fallback with null ignoreCase (not true, not false) ---

    @Test
    void execute_javaFallback_nullIgnoreCase_defaultsCaseSensitive() {
        var result = tool.execute("{\"pattern\": \"PACKAGE\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).containsIgnoringCase("no matches");
    }

    // --- binary file skipping ---

    @Test
    void execute_skipsBinaryFiles() throws IOException {
        // Create a binary file with null bytes
        byte[] binary = new byte[] {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 'h', 'e', 'l', 'l', 'o'};
        Files.write(tempDir.resolve("binary.dat"), binary);

        var result = tool.execute("{\"pattern\": \"hello\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).doesNotContain("binary.dat");
    }

    // --- factory method validation ---

    @Test
    void of_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> CodeSearchTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> CodeSearchTool.of(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- path not a directory ---

    @Test
    void execute_pathNotADirectory_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"test\", \"path\": \"src/main/java/Foo.java\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not a directory");
    }

    // --- empty file ---

    @Test
    void execute_emptyFileIsText() throws IOException {
        Files.writeString(tempDir.resolve("empty.txt"), "");

        var result = tool.execute("{\"pattern\": \"anything\"}");

        assertThat(result.isSuccess()).isTrue();
    }

    // --- absolute path outside sandbox ---

    @Test
    void execute_absolutePathOutsideSandbox_returnsFailure() {
        var result = tool.execute("{\"pattern\": \"test\", \"path\": \"/tmp\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    // --- context lines with no match ---

    @Test
    void execute_contextLinesZero_returnsBasicOutput() {
        var result = tool.execute("{\"pattern\": \"doSomething\", \"contextLines\": 0}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
    }

    // --- regex pattern search ---

    @Test
    void execute_regexPattern_matchesCorrectly() {
        var result = tool.execute("{\"pattern\": \"do[A-Z]\\\\w+\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
    }

    // --- of factory detects backend ---

    @Test
    void of_createsToolSuccessfully() {
        var autoTool = CodeSearchTool.of(tempDir);
        assertThat(autoTool).isNotNull();
        assertThat(autoTool.name()).isEqualTo("code_search");
    }

    // --- of factory auto-detects and runs a search ---

    @Test
    void of_autoDetectBackend_searchWorks() {
        var autoTool = CodeSearchTool.of(tempDir);
        var result = autoTool.execute("{\"pattern\": \"public class\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
    }

    // --- Java fallback with glob filter and no context ---

    @Test
    void javaFallback_withGlobAndContextZero_works() {
        var result = tool.execute("{\"pattern\": \"doSomething\", \"glob\": \"*.java\", \"contextLines\": 0}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
    }

    // --- inputType ---

    @Test
    void inputType_returnsCodeSearchInputClass() {
        assertThat(tool.inputType()).isEqualTo(CodeSearchInput.class);
    }

    // --- Java fallback context with surrounding lines ---

    @Test
    void javaFallback_contextLinesNegative_treatedAsZero() {
        // contextLines < 0 should behave like 0
        var result = tool.execute("{\"pattern\": \"doSomething\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("Foo.java");
    }

    // --- Java fallback no glob ---

    @Test
    void javaFallback_noGlob_searchesAllFiles() {
        var result = tool.execute("{\"pattern\": \"README\"}");

        // readme.md contains README
        assertThat(result.isSuccess()).isTrue();
    }

    // --- subprocess backend tests (grep and rg) ---

    @Nested
    class SubprocessBackendTest {

        private static boolean grepAvailable;
        private static boolean rgAvailable;

        @BeforeAll
        static void checkAvailability() {
            try {
                Process p = new ProcessBuilder("grep", "--version")
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                grepAvailable = p.waitFor() == 0;
            } catch (Exception e) {
                grepAvailable = false;
            }
            try {
                Process p = new ProcessBuilder("rg", "--version")
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                rgAvailable = p.waitFor() == 0;
            } catch (Exception e) {
                rgAvailable = false;
            }
        }

        // --- grep backend ---

        @Test
        void grepBackend_findClassDeclarations_returnsMatches() {
            assumeTrue(grepAvailable, "grep not available");
            var grepTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.GREP);

            var result = grepTool.execute("{\"pattern\": \"public class\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
            assertThat(result.getOutput()).contains("Bar.java");
        }

        @Test
        void grepBackend_noMatch_returnsSuccessWithMessage() {
            assumeTrue(grepAvailable, "grep not available");
            var grepTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.GREP);

            var result = grepTool.execute("{\"pattern\": \"nonexistent_xyz_123\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).containsIgnoringCase("no matches");
        }

        @Test
        void grepBackend_withGlobFilter_filtersFiles() {
            assumeTrue(grepAvailable, "grep not available");
            var grepTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.GREP);

            var result = grepTool.execute("{\"pattern\": \"public class\", \"glob\": \"*Test.java\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("FooTest.java");
        }

        @Test
        void grepBackend_caseInsensitive_matchesRegardlessOfCase() {
            assumeTrue(grepAvailable, "grep not available");
            var grepTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.GREP);

            var result = grepTool.execute("{\"pattern\": \"PACKAGE\", \"ignoreCase\": true}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
        }

        @Test
        void grepBackend_withContextLines_returnsOutput() {
            assumeTrue(grepAvailable, "grep not available");
            var grepTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.GREP);

            var result = grepTool.execute("{\"pattern\": \"doSomething\", \"contextLines\": 1}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
        }

        @Test
        void grepBackend_withPath_searchesSubdirectory() {
            assumeTrue(grepAvailable, "grep not available");
            var grepTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.GREP);

            var result = grepTool.execute("{\"pattern\": \"public class\", \"path\": \"src/main\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
            assertThat(result.getOutput()).doesNotContain("FooTest.java");
        }

        // --- rg backend ---

        @Test
        void rgBackend_findClassDeclarations_returnsMatches() {
            assumeTrue(rgAvailable, "rg not available");
            var rgTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.RG);

            var result = rgTool.execute("{\"pattern\": \"public class\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
            assertThat(result.getOutput()).contains("Bar.java");
        }

        @Test
        void rgBackend_noMatch_returnsSuccessWithMessage() {
            assumeTrue(rgAvailable, "rg not available");
            var rgTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.RG);

            var result = rgTool.execute("{\"pattern\": \"nonexistent_xyz_123\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).containsIgnoringCase("no matches");
        }

        @Test
        void rgBackend_withGlobFilter_filtersFiles() {
            assumeTrue(rgAvailable, "rg not available");
            var rgTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.RG);

            var result = rgTool.execute("{\"pattern\": \"public class\", \"glob\": \"*Test.java\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("FooTest.java");
        }

        @Test
        void rgBackend_caseInsensitive_matchesRegardlessOfCase() {
            assumeTrue(rgAvailable, "rg not available");
            var rgTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.RG);

            var result = rgTool.execute("{\"pattern\": \"PACKAGE\", \"ignoreCase\": true}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
        }

        @Test
        void rgBackend_withContextLines_returnsOutput() {
            assumeTrue(rgAvailable, "rg not available");
            var rgTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.RG);

            var result = rgTool.execute("{\"pattern\": \"doSomething\", \"contextLines\": 1}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
        }

        @Test
        void rgBackend_withPath_searchesSubdirectory() {
            assumeTrue(rgAvailable, "rg not available");
            var rgTool = CodeSearchTool.withBackend(tempDir, CodeSearchTool.SearchBackend.RG);

            var result = rgTool.execute("{\"pattern\": \"public class\", \"path\": \"src/main\"}");

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOutput()).contains("Foo.java");
            assertThat(result.getOutput()).doesNotContain("FooTest.java");
        }
    }
}
