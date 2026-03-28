package net.agentensemble.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditPolicyTest {

    @Test
    void effectiveLevel_noRules_returnsDefault() {
        AuditPolicy policy =
                AuditPolicy.builder().defaultLevel(AuditLevel.MINIMAL).build();

        assertThat(policy.effectiveLevel("any-ensemble", "any-event")).isEqualTo(AuditLevel.MINIMAL);
    }

    @Test
    void effectiveLevel_matchingRule_escalates() {
        AuditPolicy policy = AuditPolicy.builder()
                .defaultLevel(AuditLevel.MINIMAL)
                .rule(AuditRule.when("task_failed").escalateTo(AuditLevel.FULL).build())
                .build();

        assertThat(policy.effectiveLevel("ens-1", "task_failed")).isEqualTo(AuditLevel.FULL);
    }

    @Test
    void effectiveLevel_nonMatchingRule_returnsDefault() {
        AuditPolicy policy = AuditPolicy.builder()
                .defaultLevel(AuditLevel.STANDARD)
                .rule(AuditRule.when("task_failed").escalateTo(AuditLevel.FULL).build())
                .build();

        assertThat(policy.effectiveLevel("ens-1", "task_completed")).isEqualTo(AuditLevel.STANDARD);
    }

    @Test
    void effectiveLevel_wildcardEnsemble_matchesAll() {
        AuditPolicy policy = AuditPolicy.builder()
                .defaultLevel(AuditLevel.OFF)
                .rule(AuditRule.when("error").escalateTo(AuditLevel.STANDARD).build())
                .build();

        assertThat(policy.effectiveLevel("any-id", "error")).isEqualTo(AuditLevel.STANDARD);
    }

    @Test
    void effectiveLevel_specificEnsemble_onlyMatchesThat() {
        AuditPolicy policy = AuditPolicy.builder()
                .defaultLevel(AuditLevel.OFF)
                .rule(AuditRule.when("error")
                        .escalateTo(AuditLevel.FULL)
                        .on("prod-ens")
                        .build())
                .build();

        assertThat(policy.effectiveLevel("prod-ens", "error")).isEqualTo(AuditLevel.FULL);
        assertThat(policy.effectiveLevel("dev-ens", "error")).isEqualTo(AuditLevel.OFF);
    }

    @Test
    void effectiveLevel_multipleRules_highestLevelWins() {
        AuditPolicy policy = AuditPolicy.builder()
                .defaultLevel(AuditLevel.OFF)
                .rule(AuditRule.when("task_failed")
                        .escalateTo(AuditLevel.STANDARD)
                        .build())
                .rule(AuditRule.when("task_failed").escalateTo(AuditLevel.FULL).build())
                .build();

        assertThat(policy.effectiveLevel("ens-1", "task_failed")).isEqualTo(AuditLevel.FULL);
    }

    @Test
    void effectiveLevel_multipleRules_lowerDoesNotDowngrade() {
        AuditPolicy policy = AuditPolicy.builder()
                .defaultLevel(AuditLevel.STANDARD)
                .rule(AuditRule.when("some_event")
                        .escalateTo(AuditLevel.MINIMAL)
                        .build())
                .build();

        // MINIMAL < STANDARD (default), so default wins
        assertThat(policy.effectiveLevel("ens-1", "some_event")).isEqualTo(AuditLevel.STANDARD);
    }

    @Test
    void builder_defaultLevel_isOff() {
        AuditPolicy policy = AuditPolicy.builder().build();

        assertThat(policy.defaultLevel()).isEqualTo(AuditLevel.OFF);
        assertThat(policy.rules()).isEmpty();
    }
}
