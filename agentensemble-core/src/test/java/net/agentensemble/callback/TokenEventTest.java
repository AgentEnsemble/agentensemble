package net.agentensemble.callback;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Contract tests for the {@link TokenEvent} record.
 */
class TokenEventTest {

    @Test
    void constructorAndAccessors() {
        TokenEvent event = new TokenEvent("Hello", "Research Agent");
        assertThat(event.token()).isEqualTo("Hello");
        assertThat(event.agentRole()).isEqualTo("Research Agent");
    }

    @Test
    void emptyTokenIsAllowed() {
        TokenEvent event = new TokenEvent("", "Agent");
        assertThat(event.token()).isEqualTo("");
    }

    @Test
    void equalityBasedOnFields() {
        TokenEvent a = new TokenEvent("tok", "role");
        TokenEvent b = new TokenEvent("tok", "role");
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void unequalWhenTokensDiffer() {
        TokenEvent a = new TokenEvent("foo", "role");
        TokenEvent b = new TokenEvent("bar", "role");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void unequalWhenRolesDiffer() {
        TokenEvent a = new TokenEvent("tok", "Agent A");
        TokenEvent b = new TokenEvent("tok", "Agent B");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringContainsFieldValues() {
        TokenEvent event = new TokenEvent("world", "Writer");
        String str = event.toString();
        assertThat(str).contains("world").contains("Writer");
    }
}
