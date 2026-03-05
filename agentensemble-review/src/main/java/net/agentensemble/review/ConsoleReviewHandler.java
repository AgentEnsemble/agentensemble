package net.agentensemble.review;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ReviewHandler} that blocks on stdin and presents a CLI prompt with a countdown
 * timer.
 *
 * <h2>Interaction</h2>
 *
 * <p>When a review gate fires, the handler prints task context and a prompt:
 * <pre>
 * == Review Required =============================================
 * Task:   Research AI trends in 2025
 * Output: The AI landscape in 2025 has seen rapid progress...
 * ---
 * [c] Continue  [e] Edit  [x] Exit early  (auto-x in 4:59)
 * > _
 * </pre>
 *
 * <p>The countdown line updates in-place without scrolling the terminal. Accepted inputs:
 * <ul>
 *   <li>{@code c} or Enter -- {@link ReviewDecision.Continue} (pass output forward unchanged)</li>
 *   <li>{@code e} -- prompt for replacement text; returns {@link ReviewDecision.Edit}</li>
 *   <li>{@code x} -- {@link ReviewDecision.ExitEarly} (stop the pipeline)</li>
 * </ul>
 *
 * <h2>Timeout</h2>
 *
 * <p>When the {@link ReviewRequest#timeout()} expires, the configured
 * {@link ReviewRequest#onTimeoutAction()} is executed:
 * <ul>
 *   <li>{@link OnTimeoutAction#CONTINUE} -- returns Continue</li>
 *   <li>{@link OnTimeoutAction#EXIT_EARLY} -- returns ExitEarly</li>
 *   <li>{@link OnTimeoutAction#FAIL} -- throws {@link ReviewTimeoutException}</li>
 * </ul>
 *
 * <h2>Testability</h2>
 *
 * <p>The package-private constructor accepts injected {@link InputStream} and
 * {@link PrintStream} for unit testing without real stdin/stdout.
 */
public final class ConsoleReviewHandler implements ReviewHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsoleReviewHandler.class);

    /** Maximum characters of task description to show in the review header. */
    private static final int MAX_DESCRIPTION_LENGTH = 200;

    /** Maximum characters of task output to show in the review header. */
    private static final int MAX_OUTPUT_LENGTH = 500;

    private final InputStream inputStream;
    private final PrintStream outputStream;

    /**
     * Create a ConsoleReviewHandler using {@code System.in} and {@code System.out}.
     */
    public ConsoleReviewHandler() {
        this(System.in, System.out);
    }

    /**
     * Create a ConsoleReviewHandler with injected I/O streams.
     *
     * <p>Package-private: intended for unit testing.
     *
     * @param inputStream  the stream to read user input from; must not be null
     * @param outputStream the stream to write prompts to; must not be null
     */
    ConsoleReviewHandler(InputStream inputStream, PrintStream outputStream) {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }
        if (outputStream == null) {
            throw new IllegalArgumentException("outputStream must not be null");
        }
        this.inputStream = inputStream;
        this.outputStream = outputStream;
    }

    @Override
    public ReviewDecision review(ReviewRequest request) {
        printHeader(request);

        Duration timeout = request.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return blockingReview(request);
        } else {
            return timedReview(request, timeout);
        }
    }

    // ========================
    // Private helpers
    // ========================

    private void printHeader(ReviewRequest request) {
        outputStream.println();
        outputStream.println("== Review Required =============================================");
        outputStream.println("Task:   " + truncate(request.taskDescription(), MAX_DESCRIPTION_LENGTH));

        String output = request.taskOutput();
        if (output != null && !output.isBlank()) {
            outputStream.println("Output: " + truncate(output, MAX_OUTPUT_LENGTH));
        } else if (request.timing() == ReviewTiming.DURING_EXECUTION) {
            outputStream.println("Question: " + truncate(request.taskDescription(), MAX_DESCRIPTION_LENGTH));
        }

        String prompt = request.prompt();
        if (prompt != null && !prompt.isBlank()) {
            outputStream.println("Prompt: " + prompt);
        }

        outputStream.println("---");
    }

    /** Block on stdin indefinitely with no timeout. */
    private ReviewDecision blockingReview(ReviewRequest request) {
        outputStream.print("[c] Continue  [e] Edit  [x] Exit early  > ");
        outputStream.flush();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String firstLine = reader.readLine();
            return processInput(firstLine, reader, request);
        } catch (IOException e) {
            log.warn("ConsoleReviewHandler I/O error: {}", e.getMessage());
            return ReviewDecision.continueExecution();
        }
    }

    /** Block on stdin with a countdown timer and timeout. */
    private ReviewDecision timedReview(ReviewRequest request, Duration timeout) {
        AtomicLong remainingSeconds = new AtomicLong(timeout.toSeconds());

        // Print initial prompt line
        printCountdownPrompt(remainingSeconds.get());

        // Schedule countdown updates every second
        ScheduledExecutorService countdown = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "review-countdown");
            t.setDaemon(true);
            return t;
        });

        // Submit the entire user interaction (possibly multi-line for edit) to a reader thread
        ExecutorService readerService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "review-input-reader");
            t.setDaemon(true);
            return t;
        });

        Future<ReviewDecision> decisionFuture = readerService.submit(() -> {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String firstLine = reader.readLine();
            return processInput(firstLine, reader, request);
        });

        @SuppressWarnings("FutureReturnValueIgnored")
        var unused = countdown.scheduleAtFixedRate(
                () -> {
                    long remaining = remainingSeconds.decrementAndGet();
                    if (remaining >= 0 && !decisionFuture.isDone()) {
                        printCountdownPrompt(remaining);
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);

        try {
            ReviewDecision decision = decisionFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            // Print newline after in-place countdown to avoid overwriting output
            outputStream.println();
            return decision;
        } catch (TimeoutException e) {
            decisionFuture.cancel(true);
            outputStream.println();
            log.info("Review gate timed out after {}s", timeout.toSeconds());
            return handleTimeout(request.onTimeoutAction(), timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ReviewDecision.continueExecution();
        } catch (ExecutionException e) {
            log.warn(
                    "ConsoleReviewHandler I/O error in reader thread: {}",
                    e.getCause().getMessage());
            return ReviewDecision.continueExecution();
        } finally {
            countdown.shutdownNow();
            readerService.shutdownNow();
        }
    }

    private void printCountdownPrompt(long remainingSeconds) {
        long mins = remainingSeconds / 60;
        long secs = remainingSeconds % 60;
        // \r returns to the beginning of the line without scrolling
        outputStream.printf("\r[c] Continue  [e] Edit  [x] Exit early  (auto-x in %d:%02d) > ", mins, secs);
        outputStream.flush();
    }

    /**
     * Process the first line of user input. May read a second line when the user chooses
     * the edit path.
     *
     * @param firstLine the first line read from stdin (may be null on EOF)
     * @param reader    the reader for follow-up input on the edit path
     * @param request   the original review request
     * @return the decided ReviewDecision
     * @throws IOException if I/O fails on the edit path
     */
    private ReviewDecision processInput(String firstLine, BufferedReader reader, ReviewRequest request)
            throws IOException {
        if (firstLine == null) {
            // EOF -- treat as Continue
            return ReviewDecision.continueExecution();
        }

        String input = firstLine.trim().toLowerCase(Locale.ROOT);

        if (input.isEmpty() || "c".equals(input)) {
            return ReviewDecision.continueExecution();
        } else if ("e".equals(input)) {
            outputStream.println("Enter revised output (press Enter when done):");
            outputStream.flush();
            String revised = reader.readLine();
            return ReviewDecision.edit(revised != null ? revised : "");
        } else if ("x".equals(input)) {
            return ReviewDecision.exitEarly();
        } else {
            // Unrecognised input defaults to Continue
            log.debug("ConsoleReviewHandler: unrecognised input '{}', defaulting to Continue", input);
            return ReviewDecision.continueExecution();
        }
    }

    private ReviewDecision handleTimeout(OnTimeoutAction action, Duration timeout) {
        return switch (action) {
            case CONTINUE -> {
                log.info("Review timed out: continuing (OnTimeoutAction.CONTINUE)");
                yield ReviewDecision.continueExecution();
            }
            case EXIT_EARLY -> {
                log.info("Review timed out: exiting early (OnTimeoutAction.EXIT_EARLY)");
                yield ReviewDecision.exitEarly();
            }
            case FAIL -> throw new ReviewTimeoutException(
                    "Review gate timed out after " + timeout.toSeconds() + "s (OnTimeoutAction.FAIL)");
        };
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
