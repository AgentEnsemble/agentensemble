package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LlmIterationCompletedEvent} record construction and field access.
 */
class LlmIterationCompletedEventTest {

    @Test
    void constructionAndFieldAccess() {
        LlmIterationCompletedEvent.ToolCallRequest toolReq =
                new LlmIterationCompletedEvent.ToolCallRequest("web_search", "{\"query\":\"AI\"}");

        LlmIterationCompletedEvent event = new LlmIterationCompletedEvent(
                "Researcher",
                "Find AI papers",
                0,
                "TOOL_CALLS",
                null,
                List.of(toolReq),
                500L,
                200L,
                Duration.ofMillis(1200));

        assertThat(event.agentRole()).isEqualTo("Researcher");
        assertThat(event.taskDescription()).isEqualTo("Find AI papers");
        assertThat(event.iterationIndex()).isEqualTo(0);
        assertThat(event.responseType()).isEqualTo("TOOL_CALLS");
        assertThat(event.responseText()).isNull();
        assertThat(event.toolRequests()).hasSize(1);
        assertThat(event.toolRequests().get(0).name()).isEqualTo("web_search");
        assertThat(event.toolRequests().get(0).arguments()).isEqualTo("{\"query\":\"AI\"}");
        assertThat(event.inputTokens()).isEqualTo(500L);
        assertThat(event.outputTokens()).isEqualTo(200L);
        assertThat(event.latency()).isEqualTo(Duration.ofMillis(1200));
    }

    @Test
    void finalAnswerResponse() {
        LlmIterationCompletedEvent event = new LlmIterationCompletedEvent(
                "Writer",
                "Write report",
                3,
                "FINAL_ANSWER",
                "Here is the report content...",
                Collections.emptyList(),
                1000L,
                800L,
                Duration.ofSeconds(2));

        assertThat(event.responseType()).isEqualTo("FINAL_ANSWER");
        assertThat(event.responseText()).isEqualTo("Here is the report content...");
        assertThat(event.toolRequests()).isEmpty();
    }

    @Test
    void nullFieldsPermitted() {
        LlmIterationCompletedEvent event =
                new LlmIterationCompletedEvent(null, null, 0, null, null, null, 0L, 0L, null);

        assertThat(event.agentRole()).isNull();
        assertThat(event.taskDescription()).isNull();
        assertThat(event.responseType()).isNull();
        assertThat(event.toolRequests()).isNull();
        assertThat(event.latency()).isNull();
    }

    @Test
    void toolCallRequestRecordEquality() {
        LlmIterationCompletedEvent.ToolCallRequest r1 = new LlmIterationCompletedEvent.ToolCallRequest("search", "{}");
        LlmIterationCompletedEvent.ToolCallRequest r2 = new LlmIterationCompletedEvent.ToolCallRequest("search", "{}");

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    @Test
    void recordEquality() {
        LlmIterationCompletedEvent event1 = new LlmIterationCompletedEvent(
                "Agent", "Task", 1, "FINAL_ANSWER", "result", List.of(), 100L, 50L, Duration.ofMillis(500));
        LlmIterationCompletedEvent event2 = new LlmIterationCompletedEvent(
                "Agent", "Task", 1, "FINAL_ANSWER", "result", List.of(), 100L, 50L, Duration.ofMillis(500));

        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }

    @Test
    void multipleToolRequests() {
        List<LlmIterationCompletedEvent.ToolCallRequest> requests = List.of(
                new LlmIterationCompletedEvent.ToolCallRequest("tool1", "{\"a\":1}"),
                new LlmIterationCompletedEvent.ToolCallRequest("tool2", "{\"b\":2}"),
                new LlmIterationCompletedEvent.ToolCallRequest("tool3", "{\"c\":3}"));

        LlmIterationCompletedEvent event = new LlmIterationCompletedEvent(
                "Agent", "Task", 0, "TOOL_CALLS", null, requests, 200L, 100L, Duration.ofMillis(300));

        assertThat(event.toolRequests()).hasSize(3);
        assertThat(event.toolRequests().get(0).name()).isEqualTo("tool1");
        assertThat(event.toolRequests().get(2).name()).isEqualTo("tool3");
    }

    @Test
    void recordToStringContainsFields() {
        LlmIterationCompletedEvent event = new LlmIterationCompletedEvent(
                "Analyst", "Analyze", 2, "FINAL_ANSWER", "done", List.of(), 100L, 50L, Duration.ofMillis(400));

        assertThat(event.toString()).contains("Analyst");
        assertThat(event.toString()).contains("FINAL_ANSWER");
    }
}
