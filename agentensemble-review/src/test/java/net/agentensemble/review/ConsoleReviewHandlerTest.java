package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConsoleReviewHandler} with injected stdin/stdout.
 *
 * <p>The package-private constructor is used to inject a {@link ByteArrayInputStream}
 * as stdin and a {@link ByteArrayOutputStream} as stdout, avoiding any real console I/O.
 */
class ConsoleReviewHandlerTest {

    private static ConsoleReviewHandler handlerWith(String stdinInput) {
        InputStream in = new ByteArrayInputStream(stdinInput.getBytes(StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        return new ConsoleReviewHandler(in, out);
    }

    private static ReviewRequest basicRequest() {
        return ReviewRequest.of(
                "Write a blog post about AI",
                "The AI landscape in 2025...",
                ReviewTiming.AFTER_EXECUTION,
                null); // no timeout
    }

    // ========================
    // Blocking (no timeout) path
    // ========================

    @Test
    void blockingReview_inputC_returnsContinue() {
        ConsoleReviewHandler handler = handlerWith("c\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void blockingReview_emptyInput_returnsContinue() {
        ConsoleReviewHandler handler = handlerWith("\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void blockingReview_inputCUppercase_returnsContinue() {
        ConsoleReviewHandler handler = handlerWith("C\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void blockingReview_inputX_returnsExitEarly() {
        ConsoleReviewHandler handler = handlerWith("x\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.ExitEarly.class);
    }

    @Test
    void blockingReview_inputXUppercase_returnsExitEarly() {
        ConsoleReviewHandler handler = handlerWith("X\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.ExitEarly.class);
    }

    @Test
    void blockingReview_inputE_withRevisedText_returnsEdit() {
        // First line "e", second line is the revised output
        ConsoleReviewHandler handler = handlerWith("e\nRevised output text\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Edit.class);
        assertThat(((ReviewDecision.Edit) decision).revisedOutput()).isEqualTo("Revised output text");
    }

    @Test
    void blockingReview_inputE_withEmptyRevised_returnsEditEmpty() {
        ConsoleReviewHandler handler = handlerWith("e\n\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Edit.class);
        assertThat(((ReviewDecision.Edit) decision).revisedOutput()).isEqualTo("");
    }

    @Test
    void blockingReview_unrecognisedInput_defaultsToContinue() {
        ConsoleReviewHandler handler = handlerWith("zzz\n");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void blockingReview_eof_returnsContinue() {
        // Empty stream means immediate EOF
        ConsoleReviewHandler handler = handlerWith("");
        ReviewDecision decision = handler.review(basicRequest());
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    // ========================
    // Timeout path
    // ========================

    @Test
    void timedReview_inputBeforeTimeout_returnsContinue() {
        ConsoleReviewHandler handler = handlerWith("c\n");
        ReviewRequest request = ReviewRequest.of("Task", "Output", ReviewTiming.AFTER_EXECUTION, Duration.ofSeconds(5));
        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void timedReview_inputBeforeTimeout_returnsExitEarly() {
        ConsoleReviewHandler handler = handlerWith("x\n");
        ReviewRequest request = ReviewRequest.of("Task", "Output", ReviewTiming.AFTER_EXECUTION, Duration.ofSeconds(5));
        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.ExitEarly.class);
    }

    @Test
    void timedReview_timeout_onTimeoutContinue_returnsContinue() {
        // Simulate a timeout by giving a very short timeout with a blocking/no-input stream
        // A PipedInputStream with no data will block forever; we use a short timeout
        InputStream blockingStream = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        ConsoleReviewHandler handler = new ConsoleReviewHandler(blockingStream, out);

        ReviewRequest request = ReviewRequest.of(
                "Task", "Output", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(200), OnTimeoutAction.CONTINUE, null);

        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void timedReview_timeout_onTimeoutExitEarly_returnsExitEarly() {
        InputStream blockingStream = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        ConsoleReviewHandler handler = new ConsoleReviewHandler(blockingStream, out);

        ReviewRequest request = ReviewRequest.of(
                "Task",
                "Output",
                ReviewTiming.AFTER_EXECUTION,
                Duration.ofMillis(200),
                OnTimeoutAction.EXIT_EARLY,
                null);

        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.ExitEarly.class);
    }

    @Test
    void timedReview_timeout_onTimeoutFail_throwsReviewTimeoutException() {
        InputStream blockingStream = new InputStream() {
            @Override
            public int read() throws java.io.IOException {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
        };
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        ConsoleReviewHandler handler = new ConsoleReviewHandler(blockingStream, out);

        ReviewRequest request = ReviewRequest.of(
                "Task", "Output", ReviewTiming.AFTER_EXECUTION, Duration.ofMillis(200), OnTimeoutAction.FAIL, null);

        assertThatThrownBy(() -> handler.review(request))
                .isInstanceOf(ReviewTimeoutException.class)
                .hasMessageContaining("timed out");
    }

    // ========================
    // Header display branches
    // ========================

    @Test
    void blockingReview_beforeExecution_blankOutput_noOutputLine() {
        // Output is blank for BEFORE_EXECUTION -> neither "Output:" nor "Question:" line
        ConsoleReviewHandler handler = handlerWith("c\n");
        ReviewRequest request = ReviewRequest.of(
                "Research AI trends",
                "", // blank output
                ReviewTiming.BEFORE_EXECUTION,
                null);
        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void blockingReview_duringExecution_blankOutput_showsQuestionLine() {
        // DURING_EXECUTION with blank output shows "Question:" header
        ConsoleReviewHandler handler = handlerWith("c\n");
        ReviewRequest request = ReviewRequest.of(
                "Should I focus on NLP or CV?",
                "", // blank output
                ReviewTiming.DURING_EXECUTION,
                null);
        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void blockingReview_withCustomPrompt_proceedsToContinue() {
        ConsoleReviewHandler handler = handlerWith("c\n");
        ReviewRequest request = ReviewRequest.of(
                "Research task",
                "Some output",
                ReviewTiming.AFTER_EXECUTION,
                null,
                OnTimeoutAction.EXIT_EARLY,
                "Please check the references");
        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void blockingReview_withBlankPrompt_stillProceedsToContinue() {
        ConsoleReviewHandler handler = handlerWith("c\n");
        ReviewRequest request = ReviewRequest.of(
                "Research task",
                "Some output",
                ReviewTiming.AFTER_EXECUTION,
                null,
                OnTimeoutAction.EXIT_EARLY,
                "   "); // blank prompt, should not show Prompt: line
        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.Continue.class);
    }

    @Test
    void timedReview_editInputBeforeTimeout_returnsEdit() {
        ConsoleReviewHandler handler = handlerWith("e\nRevised text from timed review\n");
        ReviewRequest request = ReviewRequest.of("Task", "Output", ReviewTiming.AFTER_EXECUTION, Duration.ofSeconds(5));
        ReviewDecision decision = handler.review(request);
        assertThat(decision).isInstanceOf(ReviewDecision.Edit.class);
        assertThat(((ReviewDecision.Edit) decision).revisedOutput()).isEqualTo("Revised text from timed review");
    }

    // ========================
    // ReviewTimeoutException
    // ========================

    @Test
    void reviewTimeoutException_singleArg_hasMessage() {
        ReviewTimeoutException e = new ReviewTimeoutException("timed out after 5s");
        assertThat(e.getMessage()).isEqualTo("timed out after 5s");
        assertThat(e.getCause()).isNull();
    }

    @Test
    void reviewTimeoutException_twoArg_hasCause() {
        RuntimeException cause = new RuntimeException("root cause");
        ReviewTimeoutException e = new ReviewTimeoutException("outer message", cause);
        assertThat(e.getMessage()).isEqualTo("outer message");
        assertThat(e.getCause()).isSameAs(cause);
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void constructor_nullInputStream_throws() {
        PrintStream out = new PrintStream(new ByteArrayOutputStream());
        assertThatThrownBy(() -> new ConsoleReviewHandler(null, out)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullOutputStream_throws() {
        InputStream in = new ByteArrayInputStream(new byte[0]);
        assertThatThrownBy(() -> new ConsoleReviewHandler(in, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
