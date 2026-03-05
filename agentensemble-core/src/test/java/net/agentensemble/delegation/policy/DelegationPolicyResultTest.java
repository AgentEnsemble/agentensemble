package net.agentensemble.delegation.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.delegation.DelegationRequest;
import org.junit.jupiter.api.Test;

class DelegationPolicyResultTest {

    // ========================
    // allow()
    // ========================

    @Test
    void allow_returnsSingletonAllow() {
        DelegationPolicyResult r1 = DelegationPolicyResult.allow();
        DelegationPolicyResult r2 = DelegationPolicyResult.allow();

        assertThat(r1).isSameAs(r2);
        assertThat(r1).isInstanceOf(DelegationPolicyResult.Allow.class);
    }

    @Test
    void allow_isNotRejectOrModify() {
        DelegationPolicyResult result = DelegationPolicyResult.allow();

        assertThat(result).isNotInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(result).isNotInstanceOf(DelegationPolicyResult.Modify.class);
    }

    // ========================
    // reject(String)
    // ========================

    @Test
    void reject_storesReason() {
        DelegationPolicyResult.Reject result = DelegationPolicyResult.reject("project_key is UNKNOWN");

        assertThat(result.reason()).isEqualTo("project_key is UNKNOWN");
    }

    @Test
    void reject_isRejectSubtype() {
        DelegationPolicyResult result = DelegationPolicyResult.reject("some reason");

        assertThat(result).isInstanceOf(DelegationPolicyResult.Reject.class);
        assertThat(result).isNotInstanceOf(DelegationPolicyResult.Allow.class);
        assertThat(result).isNotInstanceOf(DelegationPolicyResult.Modify.class);
    }

    @Test
    void reject_nullReason_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> DelegationPolicyResult.reject(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void reject_blankReason_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> DelegationPolicyResult.reject("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");
    }

    @Test
    void reject_twoInstancesWithSameReason_areEqual() {
        DelegationPolicyResult.Reject r1 = DelegationPolicyResult.reject("reason");
        DelegationPolicyResult.Reject r2 = DelegationPolicyResult.reject("reason");

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    // ========================
    // modify(DelegationRequest)
    // ========================

    @Test
    void modify_storesModifiedRequest() {
        DelegationRequest req = DelegationRequest.builder()
                .agentRole("Analyst")
                .taskDescription("Analyse data")
                .build();
        DelegationPolicyResult.Modify result = DelegationPolicyResult.modify(req);

        assertThat(result.modifiedRequest()).isSameAs(req);
    }

    @Test
    void modify_isModifySubtype() {
        DelegationRequest req = DelegationRequest.builder()
                .agentRole("Analyst")
                .taskDescription("task")
                .build();
        DelegationPolicyResult result = DelegationPolicyResult.modify(req);

        assertThat(result).isInstanceOf(DelegationPolicyResult.Modify.class);
        assertThat(result).isNotInstanceOf(DelegationPolicyResult.Allow.class);
        assertThat(result).isNotInstanceOf(DelegationPolicyResult.Reject.class);
    }

    @Test
    void modify_nullRequest_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> DelegationPolicyResult.modify(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modifiedRequest");
    }

    // ========================
    // Pattern matching (sealed interface exhaustiveness)
    // ========================

    @Test
    void allowResult_matchesCorrectBranch() {
        DelegationPolicyResult result = DelegationPolicyResult.allow();

        String outcome =
                switch (result) {
                    case DelegationPolicyResult.Allow a -> "allowed";
                    case DelegationPolicyResult.Reject r -> "rejected";
                    case DelegationPolicyResult.Modify m -> "modified";
                };

        assertThat(outcome).isEqualTo("allowed");
    }

    @Test
    void rejectResult_matchesCorrectBranch() {
        DelegationPolicyResult result = DelegationPolicyResult.reject("no access");

        String outcome =
                switch (result) {
                    case DelegationPolicyResult.Allow a -> "allowed";
                    case DelegationPolicyResult.Reject r -> r.reason();
                    case DelegationPolicyResult.Modify m -> "modified";
                };

        assertThat(outcome).isEqualTo("no access");
    }

    @Test
    void modifyResult_matchesCorrectBranch() {
        DelegationRequest req = DelegationRequest.builder()
                .agentRole("Analyst")
                .taskDescription("task")
                .build();
        DelegationPolicyResult result = DelegationPolicyResult.modify(req);

        String outcome =
                switch (result) {
                    case DelegationPolicyResult.Allow a -> "allowed";
                    case DelegationPolicyResult.Reject r -> "rejected";
                    case DelegationPolicyResult.Modify m -> "modified:"
                            + m.modifiedRequest().getAgentRole();
                };

        assertThat(outcome).isEqualTo("modified:Analyst");
    }
}
