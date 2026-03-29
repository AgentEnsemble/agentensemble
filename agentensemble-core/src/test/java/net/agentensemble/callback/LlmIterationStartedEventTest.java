package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import net.agentensemble.trace.CapturedMessage;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LlmIterationStartedEvent} record construction and field access.
 */
class LlmIterationStartedEventTest {

    @Test
    void constructionAndFieldAccess() {
        CapturedMessage msg = CapturedMessage.builder()
                .role("system")
                .content("You are a helpful assistant")
                .build();
        List<CapturedMessage> messages = List.of(msg);

        LlmIterationStartedEvent event = new LlmIterationStartedEvent("Researcher", "Find AI papers", 0, messages);

        assertThat(event.agentRole()).isEqualTo("Researcher");
        assertThat(event.taskDescription()).isEqualTo("Find AI papers");
        assertThat(event.iterationIndex()).isEqualTo(0);
        assertThat(event.messages()).hasSize(1);
        assertThat(event.messages().get(0).getRole()).isEqualTo("system");
    }

    @Test
    void emptyMessageList() {
        LlmIterationStartedEvent event =
                new LlmIterationStartedEvent("Writer", "Write report", 2, Collections.emptyList());

        assertThat(event.messages()).isEmpty();
        assertThat(event.iterationIndex()).isEqualTo(2);
    }

    @Test
    void nullFieldsPermitted() {
        LlmIterationStartedEvent event = new LlmIterationStartedEvent(null, null, 0, null);

        assertThat(event.agentRole()).isNull();
        assertThat(event.taskDescription()).isNull();
        assertThat(event.messages()).isNull();
    }

    @Test
    void multipleMessagesPreserveOrder() {
        CapturedMessage system =
                CapturedMessage.builder().role("system").content("sys").build();
        CapturedMessage user =
                CapturedMessage.builder().role("user").content("hello").build();
        CapturedMessage assistant =
                CapturedMessage.builder().role("assistant").content("hi").build();

        LlmIterationStartedEvent event =
                new LlmIterationStartedEvent("Agent", "Task", 1, List.of(system, user, assistant));

        assertThat(event.messages()).hasSize(3);
        assertThat(event.messages().get(0).getRole()).isEqualTo("system");
        assertThat(event.messages().get(1).getRole()).isEqualTo("user");
        assertThat(event.messages().get(2).getRole()).isEqualTo("assistant");
    }

    @Test
    void recordEquality() {
        List<CapturedMessage> messages =
                List.of(CapturedMessage.builder().role("user").content("test").build());

        LlmIterationStartedEvent event1 = new LlmIterationStartedEvent("Agent", "Task", 0, messages);
        LlmIterationStartedEvent event2 = new LlmIterationStartedEvent("Agent", "Task", 0, messages);

        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }

    @Test
    void recordToStringContainsFields() {
        LlmIterationStartedEvent event =
                new LlmIterationStartedEvent("Analyst", "Analyze data", 3, Collections.emptyList());

        assertThat(event.toString()).contains("Analyst");
        assertThat(event.toString()).contains("Analyze data");
    }
}
