package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import net.agentensemble.Agent;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.ratelimit.RateLimitTimeoutException;
import net.agentensemble.ratelimit.RateLimitedChatModel;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Integration tests verifying that rate limiting is correctly applied at the
 * Task, Agent, and Ensemble levels during ensemble execution.
 *
 * These tests use stub ChatModels to avoid network calls, and measure
 * elapsed time to verify that the rate-limit token bucket delays requests.
 */
class RateLimitIntegrationTest {

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }

    private ChatModel stubModel(String response) {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse(response));
        return mockLlm;
    }

    // ========================
    // Ensemble-level rate limit -- sequential workflow
    // ========================

    @Test
    void testEnsembleRateLimit_sequential_delaysRequests() {
        // 2 requests per second: each token refills every 500ms.
        // 3 sequential tasks -> 2 waits of ~500ms -> total >= ~1 second.
        var model = stubModel("done");

        var task1 = Task.of("Summarise topic A", "Summary A");
        var task2 = Task.of("Summarise topic B", "Summary B");
        var task3 = Task.of("Summarise topic C", "Summary C");

        long start = System.nanoTime();
        var output = Ensemble.builder()
                .chatLanguageModel(model)
                .rateLimit(RateLimit.perSecond(2))
                .task(task1)
                .task(task2)
                .task(task3)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(output.getTaskOutputs()).hasSize(3);
        // 3 requests at 2 req/sec: request 1 is immediate, requests 2 and 3 each wait ~500ms
        // Lower bound: 700ms (generous to avoid flakiness on slow CI)
        assertThat(elapsedMs).isGreaterThan(700L);
    }

    @Test
    void testEnsembleRateLimit_allTasksComplete_outputCorrect() {
        var model = stubModel("task done");

        var output = Ensemble.builder()
                .chatLanguageModel(model)
                .rateLimit(RateLimit.perSecond(10)) // high limit: no meaningful delay
                .task(Task.of("Task 1", "Output 1"))
                .task(Task.of("Task 2", "Output 2"))
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();

        assertThat(output.getTaskOutputs()).hasSize(2);
        assertThat(output.getRaw()).isEqualTo("task done");
    }

    // ========================
    // Ensemble-level rate limit -- parallel workflow (shared bucket)
    // ========================

    @Test
    void testEnsembleRateLimit_parallel_sharedBucketEnforcesLimit() {
        // 2 parallel tasks sharing a single bucket of 1 req/sec.
        // Both tasks start concurrently but only one can proceed immediately;
        // the other must wait ~1 second for the next token.
        var model = stubModel("parallel done");

        var task1 = Task.of("Parallel task A", "Output A");
        var task2 = Task.of("Parallel task B", "Output B");

        long start = System.nanoTime();
        var output = Ensemble.builder()
                .chatLanguageModel(model)
                .rateLimit(RateLimit.of(1, Duration.ofSeconds(1)))
                .task(task1)
                .task(task2)
                .workflow(Workflow.PARALLEL)
                .build()
                .run();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(output.getTaskOutputs()).hasSize(2);
        // One task had to wait ~1 second for the next token
        // Lower bound: 700ms to avoid flakiness
        assertThat(elapsedMs).isGreaterThan(700L);
    }

    @Test
    void testEnsembleRateLimit_parallel_withoutRateLimit_fastCompletion() {
        // Without rate limit, both parallel tasks should complete quickly (< 2 seconds)
        // This verifies the rate limit IS the cause of slowness in the above test.
        var model = stubModel("fast");

        long start = System.nanoTime();
        var output = Ensemble.builder()
                .chatLanguageModel(model)
                .task(Task.of("Task A", "Output A"))
                .task(Task.of("Task B", "Output B"))
                .workflow(Workflow.PARALLEL)
                .build()
                .run();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(output.getTaskOutputs()).hasSize(2);
        // Without rate limiting, both tasks should complete well under 700ms
        assertThat(elapsedMs).isLessThan(700L);
    }

    // ========================
    // Task-level rate limit with chatLanguageModel
    // ========================

    @Test
    void testTaskRateLimit_withChatLanguageModel_wrapsModelAndEnforcesLimit() {
        // Task has its own chatLanguageModel + rateLimit.
        // The model is wrapped at build time; 3 requests through this task's agent
        // (synthesized from description) will be rate-limited.
        // We use a counting model to verify the rate limiter intercepted the calls.
        var callCount = new AtomicInteger(0);
        var countingModel = new CountingChatModel(callCount, "counted result");

        // One task with its own model and rate limit
        var task = Task.builder()
                .description("Analyse the market data")
                .expectedOutput("Market analysis report")
                .chatLanguageModel(countingModel)
                .rateLimit(RateLimit.perSecond(100)) // high limit: functional correctness, not timing
                .build();

        var output = Ensemble.builder().task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("counted result");
        assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
        // The chatLanguageModel on the built task is wrapped
        assertThat(task.getChatLanguageModel()).isInstanceOf(RateLimitedChatModel.class);
        assertThat(((RateLimitedChatModel) task.getChatLanguageModel()).getDelegate())
                .isSameAs(countingModel);
    }

    @Test
    void testTaskRateLimit_withoutChatLanguageModel_usesEnsembleModel() {
        // Task has rateLimit but no chatLanguageModel.
        // Ensemble-level model is used but wrapped per this task's rateLimit.
        var model = stubModel("inherited model result");

        var task = Task.builder()
                .description("Summarise the findings")
                .expectedOutput("A summary")
                .rateLimit(RateLimit.perSecond(100)) // high limit for functional correctness
                .build();

        var output =
                Ensemble.builder().chatLanguageModel(model).task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("inherited model result");
        // rateLimit is preserved on the task (not consumed since no chatLanguageModel was set)
        assertThat(task.getRateLimit()).isEqualTo(RateLimit.perSecond(100));
    }

    // ========================
    // Agent-level rate limit
    // ========================

    @Test
    void testAgentRateLimit_wrapsLlmAndRunsSuccessfully() {
        var model = stubModel("agent rate-limited result");

        var agent = Agent.builder()
                .role("Analyst")
                .goal("Analyse data")
                .llm(model)
                .rateLimit(RateLimit.perSecond(100)) // high limit for functional correctness
                .build();

        var task = Task.builder()
                .description("Analyse the data")
                .expectedOutput("Analysis report")
                .agent(agent)
                .build();

        var output = Ensemble.builder().task(task).build().run();

        assertThat(output.getRaw()).isEqualTo("agent rate-limited result");
        assertThat(agent.getLlm()).isInstanceOf(RateLimitedChatModel.class);
        assertThat(((RateLimitedChatModel) agent.getLlm()).getDelegate()).isSameAs(model);
    }

    // ========================
    // Explicit RateLimitedChatModel decorator (shared limiter)
    // ========================

    @Test
    void testExplicitRateLimitedModel_sharedAcrossMultipleAgents() {
        // Create a shared rate-limited model and use it for multiple explicit agents.
        // Both agents share the same token bucket.
        var underlying = stubModel("shared result");
        var sharedModel = RateLimitedChatModel.of(underlying, RateLimit.perSecond(2));

        var agent1 = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(sharedModel)
                .build();
        var agent2 =
                Agent.builder().role("Writer").goal("Write").llm(sharedModel).build();

        var task1 = Task.builder()
                .description("Research AI")
                .expectedOutput("Research")
                .agent(agent1)
                .build();
        var task2 = Task.builder()
                .description("Write article")
                .expectedOutput("Article")
                .agent(agent2)
                .build();

        long start = System.nanoTime();
        var output = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(Workflow.SEQUENTIAL)
                .build()
                .run();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(output.getTaskOutputs()).hasSize(2);
        // 2 requests sharing a 2 req/sec bucket: second request waits ~500ms
        assertThat(elapsedMs).isGreaterThan(300L);
    }

    // ========================
    // RateLimitTimeoutException integration
    // ========================

    @Test
    void testEnsembleRateLimit_timeoutExceeded_throwsRateLimitTimeoutException() {
        // Very tight rate limit: 1 request per 10 seconds with 50ms wait timeout.
        // The second request will timeout before a token is available.
        var model = stubModel("first response");

        // Wrap manually to set a tiny wait timeout
        var rateLimitedModel =
                RateLimitedChatModel.of(model, RateLimit.of(1, Duration.ofSeconds(10)), Duration.ofMillis(50));

        var agent1 = Agent.builder()
                .role("Researcher")
                .goal("Research")
                .llm(rateLimitedModel)
                .build();
        var agent2 = Agent.builder()
                .role("Writer")
                .goal("Write")
                .llm(rateLimitedModel)
                .build();

        var task1 = Task.builder()
                .description("Research")
                .expectedOutput("Report")
                .agent(agent1)
                .build();
        var task2 = Task.builder()
                .description("Write")
                .expectedOutput("Article")
                .agent(agent2)
                .build();

        var ensemble = Ensemble.builder()
                .task(task1)
                .task(task2)
                .workflow(Workflow.SEQUENTIAL)
                .build();

        // Task 1 consumes the token; task 2 should throw RateLimitTimeoutException
        // which propagates as a TaskExecutionException
        org.assertj.core.api.Assertions.assertThatThrownBy(ensemble::run)
                .hasRootCauseInstanceOf(RateLimitTimeoutException.class);
    }

    // ========================
    // Helpers
    // ========================

    /**
     * A stub ChatModel that records the number of calls and returns a fixed response.
     */
    static class CountingChatModel implements ChatModel {

        private final AtomicInteger counter;
        private final String response;

        CountingChatModel(AtomicInteger counter, String response) {
            this.counter = counter;
            this.response = response;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            counter.incrementAndGet();
            return ChatResponse.builder().aiMessage(new AiMessage(response)).build();
        }
    }
}
