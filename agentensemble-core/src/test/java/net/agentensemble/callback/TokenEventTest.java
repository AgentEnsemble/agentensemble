package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link TokenEvent} record.
 */
class TokenEventTest {

    @Test
    void constructorAndAccessors() {
        TokenEvent event = new TokenEvent("Hello", "Research Agent", "Research AI trends");
        assertThat(event.token()).isEqualTo("Hello");
        assertThat(event.agentRole()).isEqualTo("Research Agent");
        assertThat(event.taskDescription()).isEqualTo("Research AI trends");
    }

    @Test
    void emptyTokenIsAllowed() {
        TokenEvent event = new TokenEvent("", "Agent", "Some task");
        assertThat(event.token()).isEqualTo("");
    }

    @Test
    void equalityBasedOnFields() {
        TokenEvent a = new TokenEvent("tok", "role", "task");
        TokenEvent b = new TokenEvent("tok", "role", "task");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void unequalWhenTokensDiffer() {
        TokenEvent a = new TokenEvent("foo", "role", "task");
        TokenEvent b = new TokenEvent("bar", "role", "task");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void unequalWhenRolesDiffer() {
        TokenEvent a = new TokenEvent("tok", "Agent A", "task");
        TokenEvent b = new TokenEvent("tok", "Agent B", "task");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void unequalWhenTaskDescriptionsDiffer() {
        TokenEvent a = new TokenEvent("tok", "role", "Task A");
        TokenEvent b = new TokenEvent("tok", "role", "Task B");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringContainsFieldValues() {
        TokenEvent event = new TokenEvent("world", "Writer", "Write something");
        String str = event.toString();
        assertThat(str).contains("world").contains("Writer");
    }
}
