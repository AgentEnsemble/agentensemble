package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewRequest;
import net.agentensemble.review.ReviewTiming;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HumanInputTool}.
 *
 * <p>Tests verify the tool's behaviour for each possible {@link ReviewDecision}: Continue,
 * Edit, and ExitEarly. Also covers the no-handler-configured fallback path.
 */
class HumanInputToolTest {

    // ========================
    // Factory methods
    // ========================

    @Test
    void of_returnsNewInstance() {
        HumanInputTool tool = HumanInputTool.of();
        assertThat(tool).isNotNull();
        assertThat(tool.name()).isEqualTo(HumanInputTool.TOOL_NAME);
        assertThat(tool.description()).isNotBlank();
    }

    @Test
    void of_withCustomTimeoutAndAction_createsInstance() {
        HumanInputTool tool =
                HumanInputTool.of(java.time.Duration.ofSeconds(30), net.agentensemble.review.OnTimeoutAction.CONTINUE);
        assertThat(tool).isNotNull();
        assertThat(tool.name()).isEqualTo("human_input");
        // With CONTINUE on timeout: tool should handle a handler that returns continue
        tool.injectReviewHandler(request -> ReviewDecision.continueExecution());
        ToolResult result = tool.execute("Test question?");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void name_isHumanInput() {
        assertThat(HumanInputTool.of().name()).isEqualTo("human_input");
    }

    @Test
    void description_isNotBlank() {
        assertThat(HumanInputTool.of().description()).isNotBlank();
    }

    // ========================
    // No ReviewHandler injected
    // ========================

    @Test
    void doExecute_noReviewHandler_returnsSafeDefault() {
        HumanInputTool tool = HumanInputTool.of();
        // No handler injected; tool should return a safe default and not throw
        ToolResult result = tool.execute("Is the research direction correct?");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isNotBlank();
    }

    // ========================
    // ReviewHandler returns Continue
    // ========================

    @Test
    void doExecute_handlerReturnsContinue_returnsSuccessAcknowledgement() {
        HumanInputTool tool = HumanInputTool.of();
        tool.injectReviewHandler(request -> ReviewDecision.continueExecution());

        ToolResult result = tool.execute("Should I proceed with topic X?");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isNotBlank();
    }

    @Test
    void doExecute_handlerReturnsContinue_returnsExactAcknowledgementText() {
        // Locks down the exact return value documented in the class Javadoc
        HumanInputTool tool = HumanInputTool.of();
        tool.injectReviewHandler(request -> ReviewDecision.continueExecution());

        ToolResult result = tool.execute("Should I proceed?");
        assertThat(result.getOutput()).isEqualTo("Understood. Please continue.");
    }

    @Test
    void doExecute_noReviewHandler_returnsDefaultFallbackMessage() {
        // Verifies the fallback message is non-empty (not an "empty" response)
        HumanInputTool tool = HumanInputTool.of();

        ToolResult result = tool.execute("Is the research direction correct?");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("No reviewer is available. Please proceed with your best judgment.");
    }

    @Test
    void doExecute_handlerReturnsContinue_requestHasDuringExecutionTiming() {
        HumanInputTool tool = HumanInputTool.of();

        ReviewRequest[] capturedRequest = new ReviewRequest[1];
        tool.injectReviewHandler(request -> {
            capturedRequest[0] = request;
            return ReviewDecision.continueExecution();
        });

        tool.execute("Agent question here");
        assertThat(capturedRequest[0]).isNotNull();
        assertThat(capturedRequest[0].timing()).isEqualTo(ReviewTiming.DURING_EXECUTION);
        assertThat(capturedRequest[0].taskDescription()).isEqualTo("Agent question here");
    }

    // ========================
    // ReviewHandler returns Edit
    // ========================

    @Test
    void doExecute_handlerReturnsEdit_returnsRevisedText() {
        HumanInputTool tool = HumanInputTool.of();
        tool.injectReviewHandler(request -> ReviewDecision.edit("The answer is: focus on NLP."));

        ToolResult result = tool.execute("What should I focus on?");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("The answer is: focus on NLP.");
    }

    @Test
    void doExecute_handlerReturnsEdit_emptyRevised_returnsEmptySuccess() {
        HumanInputTool tool = HumanInputTool.of();
        tool.injectReviewHandler(request -> ReviewDecision.edit(""));

        ToolResult result = tool.execute("Question?");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("");
    }

    // ========================
    // ReviewHandler returns ExitEarly
    // ========================

    @Test
    void doExecute_handlerReturnsExitEarly_throwsExitEarlyException() {
        HumanInputTool tool = HumanInputTool.of();
        tool.injectReviewHandler(request -> ReviewDecision.exitEarly());

        // The execute() method in AbstractAgentTool is final and re-throws ExitEarlyException
        assertThatThrownBy(() -> tool.execute("Any question?")).isInstanceOf(ExitEarlyException.class);
    }

    // ========================
    // Handler injection
    // ========================

    @Test
    void injectReviewHandler_nullHandler_noHandlerSet() {
        HumanInputTool tool = HumanInputTool.of();
        // Inject a real handler first, then null out
        tool.injectReviewHandler(request -> ReviewDecision.continueExecution());
        tool.injectReviewHandler(null);

        // With null handler, should fall back to safe default
        ToolResult result = tool.execute("Question?");
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void injectReviewHandler_multipleInjections_lastInjectionWins() {
        HumanInputTool tool = HumanInputTool.of();

        ReviewHandler first = request -> ReviewDecision.exitEarly();
        ReviewHandler second = request -> ReviewDecision.continueExecution();

        tool.injectReviewHandler(first);
        tool.injectReviewHandler(second); // overwrites first

        // Should NOT throw (second handler wins)
        ToolResult result = tool.execute("Question?");
        assertThat(result.isSuccess()).isTrue();
    }

    // ========================
    // AgentTool interface compliance
    // ========================

    @Test
    void implementsAgentTool() {
        HumanInputTool tool = HumanInputTool.of();
        assertThat(tool).isInstanceOf(AgentTool.class);
        assertThat(tool).isInstanceOf(AbstractAgentTool.class);
    }
}
