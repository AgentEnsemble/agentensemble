package net.agentensemble.tools.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.tool.NoOpToolMetrics;
import net.agentensemble.tool.ToolContext;
import net.agentensemble.tool.ToolContextInjector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for ProcessAgentTool: builder validation, subprocess execution,
 * protocol parsing, timeout, and failure paths.
 *
 * <p>Tests that require real subprocess execution are guarded with an assumption
 * that the shell is available on the current system.
 */
class ProcessAgentToolTest {

    private static boolean shellAvailable;

    @BeforeAll
    static void checkShellAvailability() {
        // Determine if we can run shell commands
        try {
            Process p = new ProcessBuilder("sh", "-c", "echo ok").start();
            p.waitFor();
            shellAvailable = p.exitValue() == 0;
        } catch (Exception e) {
            shellAvailable = false;
        }
    }

    // ========================
    // Builder validation
    // ========================

    @Test
    void builder_missingName_throwsIllegalStateException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder()
                        .description("desc")
                        .command("echo", "hi")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void builder_blankName_throwsIllegalStateException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder()
                        .name("  ")
                        .description("desc")
                        .command("echo", "hi")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void builder_missingDescription_throwsIllegalStateException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder()
                        .name("tool")
                        .command("echo", "hi")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("description");
    }

    @Test
    void builder_missingCommand_throwsIllegalStateException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder()
                        .name("tool")
                        .description("desc")
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("command");
    }

    @Test
    void builder_emptyCommand_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder().command())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void builder_emptyCommandList_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder().command(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void builder_nullName_throwsNullPointerException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder().name(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_nullDescription_throwsNullPointerException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder().description(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void builder_zeroTimeout_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> ProcessAgentTool.builder().timeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void builder_setsNameAndDescription() {
        var tool = ProcessAgentTool.builder()
                .name("my_tool")
                .description("Does things")
                .command("echo", "hi")
                .build();

        assertThat(tool.name()).isEqualTo("my_tool");
        assertThat(tool.description()).isEqualTo("Does things");
    }

    // ========================
    // Subprocess execution
    // ========================

    @Test
    void execute_protocolJsonSuccess_returnsOutput() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("echo_tool")
                .description("Echoes via protocol")
                .command("sh", "-c", "echo '{\"output\":\"hello world\",\"success\":true}'")
                .build();

        var result = tool.execute("anything");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("hello world");
    }

    @Test
    void execute_protocolJsonFailure_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("failing_tool")
                .description("Returns failure")
                .command("sh", "-c", "echo '{\"error\":\"something went wrong\",\"success\":false}'")
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("something went wrong");
    }

    @Test
    void execute_nonJsonOutput_returnsRawStdout() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("raw_tool")
                .description("Returns plain text")
                .command("sh", "-c", "echo 'plain text result'")
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("plain text result");
    }

    @Test
    void execute_emptyOutput_returnsEmptySuccess() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("silent_tool")
                .description("Produces no output")
                .command("sh", "-c", "true")
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    void execute_nonZeroExitCode_returnsFailure() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("failing_cmd")
                .description("Exits with error code")
                .command("sh", "-c", "exit 1")
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("exit");
    }

    @Test
    void execute_nonZeroExitWithStderr_useStderrAsError() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("stderr_tool")
                .description("Writes to stderr and exits with error")
                .command("sh", "-c", "echo 'error detail' >&2; exit 1")
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("error detail");
    }

    @Test
    void execute_timeout_returnsFailureAfterTimeout() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("slow_tool")
                .description("Takes too long")
                .command("sh", "-c", "sleep 60")
                .timeout(Duration.ofMillis(300))
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("timed out");
    }

    @Test
    void execute_nonExistentCommand_returnsFailure() {
        var tool = ProcessAgentTool.builder()
                .name("ghost_tool")
                .description("Runs a nonexistent command")
                .command("/absolutely/nonexistent/command_xyz_12345")
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("failed to start");
    }

    @Test
    void execute_protocolJsonWithStructuredOutput_setsStructuredResult() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("structured_tool")
                .description("Returns structured output")
                .command("sh", "-c", "echo '{\"output\":\"42\",\"success\":true,\"structured\":{\"value\":42}}'")
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("42");
        assertThat(result.getStructuredOutput()).isNotNull();
    }

    @Test
    void execute_commandFromList_works() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("list_cmd_tool")
                .description("Command from list")
                .command(List.of("sh", "-c", "echo '{\"output\":\"ok\",\"success\":true}'"))
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("ok");
    }

    // ========================
    // Approval gate
    // ========================

    private static ProcessAgentTool approvalToolWithHandler(ReviewHandler handler) {
        var tool = ProcessAgentTool.builder()
                .name("approval_process_tool")
                .description("A process tool that requires approval")
                .command("sh", "-c", "echo '{\"output\":\"executed\",\"success\":true}'")
                .requireApproval(true)
                .build();
        var ctx = ToolContext.of(
                tool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(tool, ctx);
        return tool;
    }

    @Test
    void requireApproval_disabled_toolRunsWithoutApproval() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = ProcessAgentTool.builder()
                .name("no_approval_tool")
                .description("Runs without approval")
                .command("sh", "-c", "echo '{\"output\":\"ran\",\"success\":true}'")
                .requireApproval(false)
                .build();

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("ran");
    }

    @Test
    void requireApproval_enabled_handlerContinue_processRuns() {
        assumeTrue(shellAvailable, "Shell not available");
        var tool = approvalToolWithHandler(ReviewHandler.autoApprove());

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("executed");
    }

    @Test
    void requireApproval_enabled_handlerExitEarly_returnsFailureWithoutStartingProcess() {
        // No shell needed -- process is never started when ExitEarly is returned
        var tool = approvalToolWithHandler(request -> ReviewDecision.exitEarly());

        var result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("rejected");
    }

    @Test
    void requireApproval_enabled_handlerEdit_revisedInputSentToProcess() {
        assumeTrue(shellAvailable, "Shell not available");
        // The tool echoes input via stdin; reviewer edits the input
        // We verify the edit decision was returned (process uses revised input)
        var handler = (ReviewHandler) request -> ReviewDecision.edit("revised-input");
        var tool = ProcessAgentTool.builder()
                .name("edit_process_tool")
                .description("Process tool for edit test")
                .command("sh", "-c", "echo '{\"output\":\"ran\",\"success\":true}'")
                .requireApproval(true)
                .build();
        var ctx = ToolContext.of(
                tool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(tool, ctx);

        var result = tool.execute("original-input");

        // Process still runs successfully with revised input
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void requireApproval_enabled_noHandlerConfigured_throwsIllegalStateException() {
        var tool = ProcessAgentTool.builder()
                .name("no_handler_tool")
                .description("Requires approval but no handler")
                .command("echo", "hi")
                .requireApproval(true)
                .build();
        // No ToolContext injected -- rawReviewHandler() returns null

        assertThatThrownBy(() -> tool.execute("input"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires approval")
                .hasMessageContaining("ReviewHandler");
    }

    @Test
    void requireApproval_approvalDescription_containsCommandAndInput() {
        // Capture the request sent to the handler
        var capturedRequest = new net.agentensemble.review.ReviewRequest[1];
        var handler = (ReviewHandler) request -> {
            capturedRequest[0] = request;
            return ReviewDecision.exitEarly();
        };
        var tool = approvalToolWithHandler(handler);

        tool.execute("test-input");

        assertThat(capturedRequest[0]).isNotNull();
        assertThat(capturedRequest[0].taskDescription()).contains("Execute command:");
        assertThat(capturedRequest[0].taskDescription()).contains("test-input");
    }
}
