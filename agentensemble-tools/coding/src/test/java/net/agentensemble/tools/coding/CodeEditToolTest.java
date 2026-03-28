package net.agentensemble.tools.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeEditToolTest {

    @TempDir
    Path tempDir;

    private CodeEditTool tool;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(
                tempDir.resolve("example.java"),
                "line one\nline two\nline three\nline four\nline five\nline six\nline seven\n");

        tool = CodeEditTool.of(tempDir);
    }

    // --- metadata ---

    @Test
    void name_returnsCodeEdit() {
        assertThat(tool.name()).isEqualTo("code_edit");
    }

    @Test
    void description_isNonBlank() {
        assertThat(tool.description()).isNotBlank();
    }

    // --- replace_lines mode ---

    @Test
    void replaceLines_replacesCorrectLines() throws IOException {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"startLine\": 2, \"endLine\": 3, \"content\": \"replaced line\"}");

        assertThat(result.isSuccess()).isTrue();
        String content = Files.readString(tempDir.resolve("example.java"));
        assertThat(content).contains("replaced line");
        assertThat(content).doesNotContain("line two");
        assertThat(content).doesNotContain("line three");
        assertThat(content).contains("line one");
        assertThat(content).contains("line four");
    }

    @Test
    void replaceLines_singleLine_replacesOneLine() throws IOException {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"startLine\": 1, \"endLine\": 1, \"content\": \"new first line\"}");

        assertThat(result.isSuccess()).isTrue();
        String content = Files.readString(tempDir.resolve("example.java"));
        assertThat(content).startsWith("new first line");
        assertThat(content).doesNotContain("line one");
    }

    @Test
    void replaceLines_startLineExceedsFileLength_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"startLine\": 100, \"endLine\": 101, \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("exceeds file length");
    }

    @Test
    void replaceLines_endLineExceedsFileLength_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"startLine\": 1, \"endLine\": 100, \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("exceeds file length");
    }

    @Test
    void replaceLines_startGreaterThanEnd_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"startLine\": 5, \"endLine\": 2, \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("startLine must be <= endLine");
    }

    @Test
    void replaceLines_zeroLineNumber_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"startLine\": 0, \"endLine\": 1, \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase(">= 1");
    }

    @Test
    void replaceLines_missingStartLine_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"endLine\": 3, \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("startLine");
    }

    @Test
    void replaceLines_fileNotFound_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"nonexistent.java\", \"command\": \"replace_lines\", \"startLine\": 1, \"endLine\": 1, \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void replaceLines_returnsContextSnippet() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"replace_lines\", \"startLine\": 4, \"endLine\": 4, \"content\": \"new line four\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).contains("new line four");
    }

    // --- find_replace mode ---

    @Test
    void findReplace_literalMatch_replacesFirstOccurrence() throws IOException {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"find_replace\", \"find\": \"line two\", \"content\": \"second line\"}");

        assertThat(result.isSuccess()).isTrue();
        String content = Files.readString(tempDir.resolve("example.java"));
        assertThat(content).contains("second line");
        assertThat(content).doesNotContain("line two");
    }

    @Test
    void findReplace_regexMatch_replacesFirstOccurrence() throws IOException {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"find_replace\", \"find\": \"line \\\\w+\", \"content\": \"LINE REPLACED\", \"regex\": true}");

        assertThat(result.isSuccess()).isTrue();
        String content = Files.readString(tempDir.resolve("example.java"));
        assertThat(content).contains("LINE REPLACED");
    }

    @Test
    void findReplace_noMatch_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"find_replace\", \"find\": \"nonexistent text\", \"content\": \"replacement\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void findReplace_invalidRegex_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"find_replace\", \"find\": \"[invalid\", \"content\": \"x\", \"regex\": true}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("invalid");
    }

    @Test
    void findReplace_missingFind_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"find_replace\", \"content\": \"replacement\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("find");
    }

    @Test
    void findReplace_fileNotFound_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"nonexistent.java\", \"command\": \"find_replace\", \"find\": \"x\", \"content\": \"y\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("not found");
    }

    // --- write mode ---

    @Test
    void write_writesNewFile() throws IOException {
        var result = tool.execute("{\"path\": \"new_file.txt\", \"command\": \"write\", \"content\": \"new content\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("new_file.txt"))).isEqualTo("new content");
    }

    @Test
    void write_createsParentDirectories() throws IOException {
        var result =
                tool.execute("{\"path\": \"subdir/nested/file.txt\", \"command\": \"write\", \"content\": \"deep\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("subdir/nested/file.txt"))).isEqualTo("deep");
    }

    @Test
    void write_overwritesExistingFile() throws IOException {
        var result =
                tool.execute("{\"path\": \"example.java\", \"command\": \"write\", \"content\": \"completely new\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("example.java"))).isEqualTo("completely new");
    }

    // --- unknown command ---

    @Test
    void execute_unknownCommand_returnsFailure() {
        var result = tool.execute("{\"path\": \"example.java\", \"command\": \"delete\", \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("unknown command");
    }

    // --- path traversal ---

    @Test
    void execute_pathTraversal_returnsFailure() {
        var result = tool.execute("{\"path\": \"../outside.txt\", \"command\": \"write\", \"content\": \"evil\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    @Test
    void execute_absolutePath_returnsFailure() {
        var result = tool.execute("{\"path\": \"/tmp/evil.txt\", \"command\": \"write\", \"content\": \"evil\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("access denied");
    }

    // --- blank/null input ---

    @Test
    void execute_blankPath_returnsFailure() {
        var result = tool.execute("{\"path\": \"   \", \"command\": \"write\", \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("blank");
    }

    @Test
    void execute_blankCommand_returnsFailure() {
        var result = tool.execute("{\"path\": \"file.txt\", \"command\": \"  \", \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("blank");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var result = tool.execute("not valid json");

        assertThat(result.isSuccess()).isFalse();
    }

    // --- approval gate ---

    private CodeEditTool approvalToolWithHandler(ReviewHandler handler) {
        var builtTool = CodeEditTool.builder(tempDir).requireApproval(true).build();
        var ctx = ToolContext.of(
                builtTool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(builtTool, ctx);
        return builtTool;
    }

    @Test
    void requireApproval_handlerContinue_editsFile() throws IOException {
        var approvalTool = approvalToolWithHandler(ReviewHandler.autoApprove());

        var result = approvalTool.execute(
                "{\"path\": \"example.java\", \"command\": \"write\", \"content\": \"approved content\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(tempDir.resolve("example.java"))).isEqualTo("approved content");
    }

    @Test
    void requireApproval_handlerExitEarly_returnsFailure() {
        var approvalTool = approvalToolWithHandler(request -> ReviewDecision.exitEarly());

        var result = approvalTool.execute(
                "{\"path\": \"example.java\", \"command\": \"write\", \"content\": \"should not be written\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("rejected");
    }

    @Test
    void requireApproval_noHandler_throwsToolConfigurationException() {
        var builtTool = CodeEditTool.builder(tempDir).requireApproval(true).build();

        assertThatThrownBy(() ->
                        builtTool.execute("{\"path\": \"example.java\", \"command\": \"write\", \"content\": \"x\"}"))
                .isInstanceOf(ToolConfigurationException.class)
                .hasMessageContaining("requires approval");
    }

    // --- inputType ---

    @Test
    void inputType_returnsCodeEditInputClass() {
        assertThat(tool.inputType()).isEqualTo(CodeEditInput.class);
    }

    // --- null content ---

    @Test
    void execute_nullContent_returnsFailure() {
        // Content field is required by ToolParam, but null can reach execute if deserialized oddly
        var input = new CodeEditInput("example.java", "write", null, null, null, null, null);
        var result = tool.execute(input);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("null");
    }

    // --- find_replace with empty find string ---

    @Test
    void findReplace_emptyFind_returnsFailure() {
        var result = tool.execute(
                "{\"path\": \"example.java\", \"command\": \"find_replace\", \"find\": \"\", \"content\": \"x\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("find");
    }

    // --- factory/builder validation ---

    @Test
    void of_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> CodeEditTool.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullBaseDir_throwsNullPointerException() {
        assertThatThrownBy(() -> CodeEditTool.builder(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nonExistentDirectory_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> CodeEditTool.builder(tempDir.resolve("does-not-exist")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builder_requireApprovalFalse_buildSucceeds() {
        var builtTool = CodeEditTool.builder(tempDir).requireApproval(false).build();
        assertThat(builtTool).isNotNull();
    }
}
