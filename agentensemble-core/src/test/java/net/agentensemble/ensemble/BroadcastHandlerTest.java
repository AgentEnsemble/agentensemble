package net.agentensemble.ensemble;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BroadcastHandler}.
 */
class BroadcastHandlerTest {

    @Test
    void broadcast_invokesHandler() {
        AtomicReference<String> capturedTopic = new AtomicReference<>();
        AtomicReference<String> capturedResult = new AtomicReference<>();

        BroadcastHandler handler = (topic, result) -> {
            capturedTopic.set(topic);
            capturedResult.set(result);
        };

        handler.broadcast("my-topic", "task output");

        assertThat(capturedTopic.get()).isEqualTo("my-topic");
        assertThat(capturedResult.get()).isEqualTo("task output");
    }
}
