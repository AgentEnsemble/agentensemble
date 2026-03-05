package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.agentensemble.delegation.DelegationRequest;
import net.agentensemble.delegation.policy.DelegationPolicyContext;
import net.agentensemble.delegation.policy.DelegationPolicyResult;
import net.agentensemble.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class HierarchicalConstraintEnforcerTest {

    private static final DelegationPolicyContext CTX =
            new DelegationPolicyContext("Manager", 0, 3, List.of("Analyst", "Writer", "Researcher"));

    private static DelegationRequest requestFor(String role) {
        return DelegationRequest.builder()
                .agentRole(role)
                .taskDescription("Do the task")
                .build();
    }

    // ========================
    // No constraints: always allows
    // ========================

    @Test
    void noConstraints_evaluate_alwaysAllows() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().build());

        var result = enforcer.evaluate(requestFor("Analyst"), CTX);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    // ========================
    // allowedWorkers enforcement
    // ========================

    @Test
    void allowedWorkers_targetInSet_allows() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .allowedWorker("Analyst")
                .allowedWorker("Writer")
                .build());

        var result = enforcer.evaluate(requestFor("Analyst"), CTX);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    @Test
    void allowedWorkers_targetNotInSet_rejects() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().allowedWorker("Analyst").build());

        var result = enforcer.evaluate(requestFor("Writer"), CTX);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(((DelegationPolicyResult.Reject) result).reason())
                .contains("Writer")
                .contains("allowed");
    }

    @Test
    void allowedWorkers_emptySet_allowsAll() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().build());

        assertThat(enforcer.evaluate(requestFor("Analyst"), CTX)).isInstanceOf(DelegationPolicyResult.Allow.class);
        assertThat(enforcer.evaluate(requestFor("Writer"), CTX)).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    // ========================
    // Per-worker cap enforcement
    // ========================

    @Test
    void maxCallsPerWorker_underCap_allows() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .maxCallsPerWorker("Analyst", 2)
                .build());

        var result = enforcer.evaluate(requestFor("Analyst"), CTX);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    @Test
    void maxCallsPerWorker_atCapOnSecondCall_rejectsSecond() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .maxCallsPerWorker("Analyst", 1)
                .build());

        // First call: allowed (cap not yet reached)
        var first = enforcer.evaluate(requestFor("Analyst"), CTX);
        assertThat(first).isInstanceOf(DelegationPolicyResult.Allow.class);

        // Second call: rejected (cap of 1 already consumed)
        var second = enforcer.evaluate(requestFor("Analyst"), CTX);
        assertThat(second).isInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(((DelegationPolicyResult.Reject) second).reason())
                .contains("Analyst")
                .contains("cap");
    }

    @Test
    void maxCallsPerWorker_capForOneWorker_doesNotAffectOther() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .maxCallsPerWorker("Analyst", 1)
                .build());

        enforcer.evaluate(requestFor("Analyst"), CTX); // consume Analyst cap

        // Writer has no cap -- still allowed
        var result = enforcer.evaluate(requestFor("Writer"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    @Test
    void maxCallsPerWorker_workerNotInMap_noCapApplied() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .maxCallsPerWorker("Analyst", 1)
                .build());

        // Writer not in maxCallsPerWorker map -- can be called many times
        enforcer.evaluate(requestFor("Writer"), CTX);
        enforcer.evaluate(requestFor("Writer"), CTX);
        var result = enforcer.evaluate(requestFor("Writer"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    // ========================
    // Global cap enforcement
    // ========================

    @Test
    void globalMaxDelegations_zero_noCapApplied() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().globalMaxDelegations(0).build());

        for (int i = 0; i < 10; i++) {
            assertThat(enforcer.evaluate(requestFor("Analyst"), CTX)).isInstanceOf(DelegationPolicyResult.Allow.class);
        }
    }

    @Test
    void globalMaxDelegations_atCapOnNextCall_rejects() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().globalMaxDelegations(2).build());

        // First two: allowed
        assertThat(enforcer.evaluate(requestFor("Analyst"), CTX)).isInstanceOf(DelegationPolicyResult.Allow.class);
        assertThat(enforcer.evaluate(requestFor("Writer"), CTX)).isInstanceOf(DelegationPolicyResult.Allow.class);

        // Third: rejected
        var result = enforcer.evaluate(requestFor("Researcher"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(((DelegationPolicyResult.Reject) result).reason())
                .contains("global")
                .contains("2");
    }

    @Test
    void globalMaxDelegations_acrossDifferentWorkers_counted() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().globalMaxDelegations(3).build());

        enforcer.evaluate(requestFor("Analyst"), CTX);
        enforcer.evaluate(requestFor("Writer"), CTX);
        enforcer.evaluate(requestFor("Researcher"), CTX);

        var result = enforcer.evaluate(requestFor("Analyst"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Reject.class);
    }

    // ========================
    // Stage ordering enforcement
    // ========================

    @Test
    void requiredStages_firstStageWorker_alwaysAllowed() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher"))
                .requiredStage(List.of("Analyst"))
                .build());

        var result = enforcer.evaluate(requestFor("Researcher"), CTX);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    @Test
    void requiredStages_laterStageWorker_rejectedBeforePriorStageCompletes() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher"))
                .requiredStage(List.of("Analyst"))
                .build());

        // Stage 0 (Researcher) not yet completed
        var result = enforcer.evaluate(requestFor("Analyst"), CTX);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(((DelegationPolicyResult.Reject) result).reason())
                .contains("Analyst")
                .contains("stage");
    }

    @Test
    void requiredStages_laterStageWorker_allowedAfterPriorStageCompletes() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher"))
                .requiredStage(List.of("Analyst"))
                .build());

        // Complete stage 0
        enforcer.evaluate(requestFor("Researcher"), CTX);
        enforcer.recordDelegation("Researcher");

        // Stage 1 worker now allowed
        var result = enforcer.evaluate(requestFor("Analyst"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    @Test
    void requiredStages_multipleMembersInPriorStage_allMustCompleteBeforeNext() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher", "Writer"))
                .requiredStage(List.of("Analyst"))
                .build());

        // Only Researcher completed, Writer not yet
        enforcer.evaluate(requestFor("Researcher"), CTX);
        enforcer.recordDelegation("Researcher");

        // Analyst (stage 1) still rejected -- Writer (stage 0) not yet complete
        var result = enforcer.evaluate(requestFor("Analyst"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(((DelegationPolicyResult.Reject) result).reason()).contains("Writer");
    }

    @Test
    void requiredStages_multipleMembersInPriorStage_allowedWhenAllComplete() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher", "Writer"))
                .requiredStage(List.of("Analyst"))
                .build());

        // Both stage 0 members complete
        enforcer.evaluate(requestFor("Researcher"), CTX);
        enforcer.recordDelegation("Researcher");
        enforcer.evaluate(requestFor("Writer"), CTX);
        enforcer.recordDelegation("Writer");

        // Analyst (stage 1) now allowed
        var result = enforcer.evaluate(requestFor("Analyst"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    @Test
    void requiredStages_workerNotInAnyStage_unconstrainedByOrdering() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredStage(List.of("Researcher"))
                .requiredStage(List.of("Analyst"))
                .build());

        // "Writer" is not in any stage -- not constrained by ordering
        var result = enforcer.evaluate(requestFor("Writer"), CTX);
        assertThat(result).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    // ========================
    // recordDelegation and validatePostExecution
    // ========================

    @Test
    void validatePostExecution_noRequiredWorkers_passesAlways() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().build());

        assertThatNoException().isThrownBy(enforcer::validatePostExecution);
    }

    @Test
    void validatePostExecution_allRequiredWorkersCalled_passes() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredWorker("Analyst")
                .requiredWorker("Writer")
                .build());

        enforcer.recordDelegation("Analyst");
        enforcer.recordDelegation("Writer");

        assertThatNoException().isThrownBy(enforcer::validatePostExecution);
    }

    @Test
    void validatePostExecution_oneRequiredWorkerMissing_throwsWithViolation() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredWorker("Analyst")
                .requiredWorker("Writer")
                .build());

        enforcer.recordDelegation("Analyst"); // Writer never called

        assertThatThrownBy(enforcer::validatePostExecution)
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> {
                    var violations = ((ConstraintViolationException) e).getViolations();
                    assertThat(violations).hasSize(1);
                    assertThat(violations.get(0)).contains("Writer");
                });
    }

    @Test
    void validatePostExecution_multipleRequiredWorkersMissing_allListedInViolations() {
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .requiredWorker("Analyst")
                .requiredWorker("Writer")
                .requiredWorker("Researcher")
                .build());

        // None called

        assertThatThrownBy(enforcer::validatePostExecution)
                .isInstanceOf(ConstraintViolationException.class)
                .satisfies(e -> {
                    var violations = ((ConstraintViolationException) e).getViolations();
                    assertThat(violations).hasSize(3);
                });
    }

    @Test
    void recordDelegation_calledMultipleTimes_noDoubleCount() {
        var enforcer = new HierarchicalConstraintEnforcer(
                HierarchicalConstraints.builder().requiredWorker("Analyst").build());

        enforcer.recordDelegation("Analyst");
        enforcer.recordDelegation("Analyst"); // called twice -- still just satisfies the requirement

        assertThatNoException().isThrownBy(enforcer::validatePostExecution);
    }

    // ========================
    // Combined constraint checks
    // ========================

    @Test
    void allowedWorkers_checkedBeforeOtherConstraints() {
        // Worker not in allowedWorkers, but also not in maxCallsPerWorker
        var enforcer = new HierarchicalConstraintEnforcer(HierarchicalConstraints.builder()
                .allowedWorker("Analyst")
                .globalMaxDelegations(10)
                .build());

        var result = enforcer.evaluate(requestFor("Writer"), CTX);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(((DelegationPolicyResult.Reject) result).reason()).contains("allowed");
    }
}
