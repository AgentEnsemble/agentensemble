package net.agentensemble.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.review.ReviewPolicy;
import net.agentensemble.web.WebDashboard;

/**
 * E2E test server for Playwright end-to-end tests.
 *
 * <p>Starts a {@link WebDashboard} and runs a pre-canned ensemble with a {@link StubChatModel}
 * so that Playwright tests can exercise the full browser + WebSocket + Java server
 * vertical slice without any real LLM API calls.
 *
 * <h2>Configuration</h2>
 * <p>Two environment variables control behaviour:
 * <ul>
 *   <li>{@code E2E_PORT} -- TCP port to listen on (default: 7329)</li>
 *   <li>{@code E2E_SCENARIO} -- test scenario: {@code sequential} (default) or
 *       {@code with-review}</li>
 * </ul>
 *
 * <h2>Scenarios</h2>
 * <dl>
 *   <dt>sequential</dt>
 *   <dd>Runs a two-task sequential ensemble with no review gates. Both tasks complete
 *       immediately using the stub model. Playwright asserts that the timeline renders
 *       two completed task bars.</dd>
 *   <dt>with-review</dt>
 *   <dd>Runs a one-task ensemble with {@link ReviewPolicy#AFTER_EVERY_TASK}. The server
 *       blocks after the task completes, waiting for a browser review decision. Playwright
 *       asserts that the review panel appears, then clicks Approve and verifies the
 *       ensemble completes.</dd>
 * </dl>
 *
 * <p>The process remains alive after the ensemble finishes (main thread blocks on a
 * {@link CountDownLatch}) so Playwright can assert on the final rendered state.
 * Playwright kills the process when all tests complete.
 */
public class E2eTestServer {

    public static void main(String[] args) throws InterruptedException {
        int port = readIntEnv("E2E_PORT", 7329);
        String scenario = System.getenv().getOrDefault("E2E_SCENARIO", "sequential");

        WebDashboard dashboard = buildDashboard(port, scenario);
        dashboard.start();

        // Print the readiness line that Playwright's webServer watches for.
        // The port being open is the actual readiness signal; this log line helps debug.
        System.out.println("[E2eTestServer] started on port " + dashboard.actualPort() + " -- scenario: " + scenario);
        System.out.flush();

        // Run the scenario on a virtual thread so the main thread can block below
        // without consuming the OS thread needed for the Javalin server.
        Thread.ofVirtual().start(() -> runScenario(dashboard, scenario));

        // Block the main thread indefinitely. Playwright kills this process when done.
        new CountDownLatch(1).await();
    }

    // ========================
    // Dashboard construction
    // ========================

    private static WebDashboard buildDashboard(int port, String scenario) {
        WebDashboard.Builder builder = WebDashboard.builder().port(port).host("0.0.0.0");

        if ("with-review".equals(scenario)) {
            // Allow 2 minutes for the Playwright test to interact with the review panel
            // before the server times out and auto-continues.
            builder.reviewTimeout(Duration.ofMinutes(2));
        }

        return builder.build();
    }

    // ========================
    // Scenario runners
    // ========================

    private static void runScenario(WebDashboard dashboard, String scenario) {
        try {
            if ("with-review".equals(scenario)) {
                runWithReviewScenario(dashboard);
            } else {
                runSequentialScenario(dashboard);
            }
        } catch (Exception e) {
            System.err.println("[E2eTestServer] Scenario '" + scenario + "' threw: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    /**
     * Sequential scenario: two tasks with no review gates.
     *
     * <p>Both tasks complete immediately with canned stub responses. The Playwright test
     * verifies that the timeline renders two completed task bars and the header shows
     * "2 / 2 tasks".
     */
    private static void runSequentialScenario(WebDashboard dashboard) {
        StubChatModel model = new StubChatModel(
                "Research complete: AI adoption grew 42% in 2025, driven by code generation and"
                        + " document processing use cases.",
                "Blog post written: The AI revolution of 2025 brought enterprise LLM adoption to"
                        + " new heights, transforming how teams write code and process documents.");

        Ensemble.builder()
                .chatLanguageModel(model)
                .webDashboard(dashboard)
                .task(Task.of("Research AI adoption trends for 2025", "A concise research report"))
                .task(Task.of("Write a blog post summarising the AI research findings", "A 200-word blog post"))
                .build()
                .run();

        System.out.println("[E2eTestServer] Sequential scenario complete.");
        System.out.flush();
    }

    /**
     * With-review scenario: one task followed by a human review gate.
     *
     * <p>Waits for at least one browser to connect via WebSocket before firing the ensemble.
     * This ensures that when {@code review_requested} is broadcast, the browser is already
     * connected and receives the message directly -- rather than relying on it being present
     * in the late-join snapshot (which it is not, by current design).
     *
     * <p>The Playwright test asserts that the review panel appears, clicks Approve, and
     * verifies the ensemble completes with "1 / 1 tasks".
     */
    private static void runWithReviewScenario(WebDashboard dashboard) {
        // Block until the browser WebSocket client has connected. This prevents a race
        // where the review fires before the browser is ready to receive it.
        waitForClientConnection(dashboard.actualPort());

        StubChatModel model =
                new StubChatModel("FOR IMMEDIATE RELEASE: AgentEnsemble announces v3.0, the fastest multi-agent"
                        + " framework available for Java developers.");

        Ensemble.builder()
                .chatLanguageModel(model)
                .webDashboard(dashboard)
                .reviewPolicy(ReviewPolicy.AFTER_EVERY_TASK)
                .task(Task.of(
                        "Draft a press release for the AgentEnsemble v3.0 product launch",
                        "A professional press release suitable for publication"))
                .build()
                .run();

        System.out.println("[E2eTestServer] With-review scenario complete.");
        System.out.flush();
    }

    /**
     * Polls the server's {@code /api/status} endpoint until the connected-client count
     * is greater than zero. This is used before firing the review scenario so the ensemble
     * starts only after a browser WebSocket is live and ready to receive events.
     *
     * <p>Polls every 100 ms with no upper bound -- Playwright's per-test timeout enforces
     * the overall time limit. The server is on localhost so network latency is negligible.
     *
     * @param port the port the WebDashboard server is listening on
     */
    private static void waitForClientConnection(int port) {
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

        String statusUrl = "http://localhost:" + port + "/api/status";
        System.out.println("[E2eTestServer] Waiting for browser client to connect...");
        System.out.flush();

        while (true) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(statusUrl))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                String body = response.body();
                // The /api/status response includes "clients":<n>. A value > 0 means
                // at least one browser WebSocket is connected.
                if (body.contains("\"clients\":0") || !body.contains("clients")) {
                    Thread.sleep(100);
                } else {
                    System.out.println("[E2eTestServer] Browser connected -- starting ensemble.");
                    System.out.flush();
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // Server not ready yet or transient error -- retry
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    // ========================
    // Helpers
    // ========================

    private static int readIntEnv(String name, int defaultValue) {
        String val = System.getenv(name);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            System.err.println("[E2eTestServer] Invalid value for env var "
                    + name
                    + ": '"
                    + val
                    + "' -- using default "
                    + defaultValue);
            return defaultValue;
        }
    }
}
