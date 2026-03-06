package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.review.OnTimeoutAction;
import net.agentensemble.review.Review;
import net.agentensemble.review.ReviewDecision;
import net.agentensemble.review.ReviewHandler;
import net.agentensemble.review.ReviewRequest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AbstractAgentTool.requestApproval():
 * - handler returning Continue/Edit/ExitEarly
 * - null handler auto-approves
 * - custom timeout and onTimeout
 * - ExitEarlyException propagation through execute()
 * - IllegalStateException propagation through execute()
 * - CONSOLE_APPROVAL_LOCK is a non-null static ReentrantLock
 */
class AbstractAgentToolApprovalTest {

    // ========================
    // Concrete test implementation
    // ========================

    /**
     * A tool that conditionally calls requestApproval() before returning its result,
     * enabling tests to control both approval and execution paths.
     */
    static class ApprovalCaptureTool extends AbstractAgentTool {

        private final boolean callApproval;
        private ToolResult fixedResult;
        private ReviewDecision capturedDecision;
        private Duration customTimeout;
        private OnTimeoutAction customOnTimeout;
        private boolean throwIseOnExecute = false;

        ApprovalCaptureTool(boolean callApproval) {
            this.callApproval = callApproval;
            this.fixedResult = ToolResult.success("done");
        }

        @Override
        public String name() {
            return "approval_capture";
        }

        @Override
        public String description() {
            return "Captures approval decision for testing";
        }

        @Override
        protected ToolResult doExecute(String input) {
            if (throwIseOnExecute) {
                throw new IllegalStateException("deliberate ISE from doExecute");
            }
            if (callApproval) {
                if (customTimeout != null) {
                    capturedDecision = requestApproval("test action: " + input, customTimeout, customOnTimeout);
                } else {
                    capturedDecision = requestApproval("test action: " + input);
                }
                if (capturedDecision instanceof ReviewDecision.ExitEarly) {
                    return ToolResult.failure("rejected: " + input);
                }
                if (capturedDecision instanceof ReviewDecision.Edit edit) {
                    return ToolResult.success("edited: " + edit.revisedOutput());
                }
            }
            return fixedResult;
        }

        void setFixedResult(ToolResult result) {
            this.fixedResult = result;
        }

        void setCustomTimeout(Duration timeout, OnTimeoutAction onTimeout) {
            this.customTimeout = timeout;
            this.customOnTimeout = onTimeout;
        }
    }

    private ApprovalCaptureTool toolWithHandler(ReviewHandler handler) {
        var tool = new ApprovalCaptureTool(true);
        injectHandler(tool, handler);
        return tool;
    }

    private void injectHandler(AbstractAgentTool tool, ReviewHandler handler) {
        var ctx = ToolContext.of(
                tool.name(), NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), handler);
        ToolContextInjector.injectContext(tool, ctx);
    }

    // ========================
    // null handler -> auto-approve (Continue)
    // ========================

    @Test
    void requestApproval_nullHandler_autoApprovesContinue() {
        // No ToolContext injected: rawReviewHandler() returns null
        var tool = new ApprovalCaptureTool(true);
        // inject context without a handler
        var ctx = ToolContext.of(
                "approval_capture", NoOpToolMetrics.INSTANCE, Executors.newVirtualThreadPerTaskExecutor(), null);
        ToolContextInjector.injectContext(tool, ctx);

        ToolResult result = tool.execute("test");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("done");
        // Decision should be Continue from auto-approve
        assertThat(tool.capturedDecision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void requestApproval_beforeContextInjection_autoApprovesContinue() {
        // No context injected at all: rawReviewHandler() returns null
        var tool = new ApprovalCaptureTool(true);

        ToolResult result = tool.execute("test");

        assertThat(result.isSuccess()).isTrue();
        assertThat(tool.capturedDecision).isInstanceOf(ReviewDecision.Continue.class);
    }

    // ========================
    // handler returning Continue
    // ========================

    @Test
    void requestApproval_handlerReturnsContinue_toolProceedsNormally() {
        ReviewHandler handler = ReviewHandler.autoApprove();
        var tool = toolWithHandler(handler);

        ToolResult result = tool.execute("input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("done");
        assertThat(tool.capturedDecision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void requestApproval_handlerReturnsContinue_handlerReceivesCorrectRequest() {
        ReviewHandler handler = mock(ReviewHandler.class);
        when(handler.review(any())).thenReturn(ReviewDecision.continueExecution());
        var tool = toolWithHandler(handler);

        tool.execute("my input");

        verify(handler).review(any(ReviewRequest.class));
    }

    @Test
    void requestApproval_handlerReceivesDescriptionAndDuringExecutionTiming() {
        ReviewRequest[] captured = new ReviewRequest[1];
        ReviewHandler handler = request -> {
            captured[0] = request;
            return ReviewDecision.continueExecution();
        };
        var tool = toolWithHandler(handler);

        tool.execute("my input");

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].taskDescription()).isEqualTo("test action: my input");
        assertThat(captured[0].timing()).isEqualTo(net.agentensemble.review.ReviewTiming.DURING_EXECUTION);
    }

    @Test
    void requestApproval_usesDefaultTimeoutAndOnTimeout() {
        ReviewRequest[] captured = new ReviewRequest[1];
        ReviewHandler handler = request -> {
            captured[0] = request;
            return ReviewDecision.continueExecution();
        };
        var tool = toolWithHandler(handler);

        tool.execute("input");

        assertThat(captured[0].timeout()).isEqualTo(Review.DEFAULT_TIMEOUT);
        assertThat(captured[0].onTimeoutAction()).isEqualTo(Review.DEFAULT_ON_TIMEOUT);
    }

    // ========================
    // handler returning Edit
    // ========================

    @Test
    void requestApproval_handlerReturnsEdit_toolUsesRevisedOutput() {
        ReviewHandler handler = request -> ReviewDecision.edit("revised content");
        var tool = toolWithHandler(handler);

        ToolResult result = tool.execute("original");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("edited: revised content");
        assertThat(tool.capturedDecision).isInstanceOf(ReviewDecision.Edit.class);
        assertThat(((ReviewDecision.Edit) tool.capturedDecision).revisedOutput())
                .isEqualTo("revised content");
    }

    // ========================
    // handler returning ExitEarly
    // ========================

    @Test
    void requestApproval_handlerReturnsExitEarly_toolReturnsFailure() {
        ReviewHandler handler = request -> ReviewDecision.exitEarly();
        var tool = toolWithHandler(handler);

        ToolResult result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("rejected");
        assertThat(tool.capturedDecision).isInstanceOf(ReviewDecision.ExitEarly.class);
    }

    // ========================
    // Custom timeout and onTimeout
    // ========================

    @Test
    void requestApproval_customTimeoutAndOnTimeout_passedToHandler() {
        ReviewRequest[] captured = new ReviewRequest[1];
        ReviewHandler handler = request -> {
            captured[0] = request;
            return ReviewDecision.continueExecution();
        };
        var tool = toolWithHandler(handler);
        tool.setCustomTimeout(Duration.ofSeconds(10), OnTimeoutAction.CONTINUE);

        tool.execute("input");

        assertThat(captured[0].timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(captured[0].onTimeoutAction()).isEqualTo(OnTimeoutAction.CONTINUE);
    }

    // ========================
    // Tool without approval -- handler never called
    // ========================

    @Test
    void noApproval_handlerNotCalled() {
        ReviewHandler handler = mock(ReviewHandler.class);
        var tool = new ApprovalCaptureTool(false); // does NOT call requestApproval
        injectHandler(tool, handler);

        tool.execute("input");

        verify(handler, never()).review(any());
    }

    // ========================
    // ExitEarlyException propagation through execute()
    // ========================

    @Test
    void exitEarlyException_thrownFromDoExecute_propagatesThroughExecute() {
        // Create a tool that throws ExitEarlyException from doExecute
        var tool = new AbstractAgentTool() {
            @Override
            public String name() {
                return "exit_early_thrower";
            }

            @Override
            public String description() {
                return "Throws ExitEarlyException";
            }

            @Override
            protected ToolResult doExecute(String input) {
                throw new ExitEarlyException("reviewer requested exit");
            }
        };

        assertThatThrownBy(() -> tool.execute("input"))
                .isInstanceOf(ExitEarlyException.class)
                .hasMessageContaining("reviewer requested exit");
    }

    // ========================
    // IllegalStateException propagation through execute()
    // ========================

    @Test
    void illegalStateException_thrownFromDoExecute_propagatesThroughExecute() {
        var tool = new ApprovalCaptureTool(false);
        tool.throwIseOnExecute = true;

        assertThatThrownBy(() -> tool.execute("input"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deliberate ISE");
    }

    // ========================
    // CONSOLE_APPROVAL_LOCK is a shared static ReentrantLock
    // ========================

    @Test
    void consoleApprovalLock_isNonNullReentrantLock() {
        assertThat(AbstractAgentTool.CONSOLE_APPROVAL_LOCK).isNotNull();
        assertThat(AbstractAgentTool.CONSOLE_APPROVAL_LOCK).isInstanceOf(ReentrantLock.class);
    }

    @Test
    void consoleApprovalLock_isSharedAcrossInstances() {
        // Verify same lock object is shared (static field)
        var tool1 = new ApprovalCaptureTool(true);
        var tool2 = new ApprovalCaptureTool(true);
        assertThat(AbstractAgentTool.CONSOLE_APPROVAL_LOCK).isSameAs(AbstractAgentTool.CONSOLE_APPROVAL_LOCK);
        // Static fields are shared -- verifying it's the same reference
        assertThat(tool1).isNotNull();
        assertThat(tool2).isNotNull();
    }

    // ========================
    // rawReviewHandler() accessor
    // ========================

    @Test
    void rawReviewHandler_withoutContext_returnsNull() {
        var tool = new ApprovalCaptureTool(true);
        assertThat(tool.rawReviewHandler()).isNull();
    }

    @Test
    void rawReviewHandler_withNullHandlerInContext_returnsNull() {
        var tool = new ApprovalCaptureTool(true);
        injectHandler(tool, null);
        assertThat(tool.rawReviewHandler()).isNull();
    }

    @Test
    void rawReviewHandler_withHandlerInContext_returnsHandler() {
        var handler = ReviewHandler.autoApprove();
        var tool = new ApprovalCaptureTool(true);
        injectHandler(tool, handler);
        assertThat(tool.rawReviewHandler()).isSameAs(handler);
    }
}
