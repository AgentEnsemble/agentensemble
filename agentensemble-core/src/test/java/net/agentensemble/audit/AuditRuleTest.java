package net.agentensemble.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuditRuleTest {

    @Test
    void builder_whenCondition_buildsEventRule() {
        AuditRule rule =
                AuditRule.when("task_failed").escalateTo(AuditLevel.FULL).build();

        assertThat(rule.condition()).isEqualTo("task_failed");
        assertThat(rule.ruleType()).isEqualTo(AuditRule.AuditRuleType.EVENT);
        assertThat(rule.targetLevel()).isEqualTo(AuditLevel.FULL);
        assertThat(rule.targetEnsembles()).containsExactly("*");
        assertThat(rule.duration()).isNull();
    }

    @Test
    void builder_schedule_buildsScheduleRule() {
        AuditRule rule = AuditRule.schedule("0 */5 * * *")
                .escalateTo(AuditLevel.STANDARD)
                .duration(Duration.ofMinutes(10))
                .build();

        assertThat(rule.condition()).isEqualTo("0 */5 * * *");
        assertThat(rule.ruleType()).isEqualTo(AuditRule.AuditRuleType.SCHEDULE);
        assertThat(rule.targetLevel()).isEqualTo(AuditLevel.STANDARD);
        assertThat(rule.duration()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void builder_withoutEscalateTo_throwsIllegalState() {
        assertThatThrownBy(() -> AuditRule.when("task_failed").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("targetLevel");
    }

    @Test
    void appliesTo_wildcard_matchesAnyEnsemble() {
        AuditRule rule =
                AuditRule.when("task_failed").escalateTo(AuditLevel.FULL).build();

        assertThat(rule.appliesTo("any-ensemble-id")).isTrue();
        assertThat(rule.appliesTo("another-one")).isTrue();
    }

    @Test
    void appliesTo_specificEnsembles_matchesOnly() {
        AuditRule rule = AuditRule.when("task_failed")
                .escalateTo(AuditLevel.FULL)
                .on("ens-1", "ens-2")
                .build();

        assertThat(rule.appliesTo("ens-1")).isTrue();
        assertThat(rule.appliesTo("ens-2")).isTrue();
        assertThat(rule.appliesTo("ens-3")).isFalse();
    }

    @Test
    void matches_exactCondition_returnsTrue() {
        AuditRule rule =
                AuditRule.when("task_failed").escalateTo(AuditLevel.FULL).build();

        assertThat(rule.matches("task_failed")).isTrue();
        assertThat(rule.matches("task_completed")).isFalse();
        assertThat(rule.matches(null)).isFalse();
    }

    @Test
    void builder_withDurationAndEnsembles_setsAllFields() {
        AuditRule rule = AuditRule.when("error")
                .escalateTo(AuditLevel.STANDARD)
                .on("prod-ens")
                .duration(Duration.ofMinutes(5))
                .build();

        assertThat(rule.targetEnsembles()).containsExactly("prod-ens");
        assertThat(rule.duration()).isEqualTo(Duration.ofMinutes(5));
    }
}
