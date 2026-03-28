package net.agentensemble.directive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AutoDirectiveRuleTest {

    @Test
    void constructor_validArgs_createsRule() {
        Directive d = new Directive("id", "from", null, "SET_MODEL_TIER", "FALLBACK", Instant.now(), null);
        AutoDirectiveRule rule = new AutoDirectiveRule("test-rule", metrics -> true, d);
        assertThat(rule.name()).isEqualTo("test-rule");
        assertThat(rule.directiveToFire()).isSameAs(d);
    }

    @Test
    void constructor_nullName_throws() {
        Directive d = new Directive("id", "from", null, "ACT", "val", Instant.now(), null);
        assertThatThrownBy(() -> new AutoDirectiveRule(null, metrics -> true, d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_blankName_throws() {
        Directive d = new Directive("id", "from", null, "ACT", "val", Instant.now(), null);
        assertThatThrownBy(() -> new AutoDirectiveRule("  ", metrics -> true, d))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullCondition_throws() {
        Directive d = new Directive("id", "from", null, "ACT", "val", Instant.now(), null);
        assertThatThrownBy(() -> new AutoDirectiveRule("rule", null, d)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullDirective_throws() {
        assertThatThrownBy(() -> new AutoDirectiveRule("rule", metrics -> true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void condition_isEvaluated() {
        Directive d = new Directive("id", "from", null, "ACT", "val", Instant.now(), null);
        AutoDirectiveRule rule = new AutoDirectiveRule("rule", metrics -> metrics.getTotalLlmCallCount() > 100, d);
        assertThat(rule.condition()).isNotNull();
    }
}
