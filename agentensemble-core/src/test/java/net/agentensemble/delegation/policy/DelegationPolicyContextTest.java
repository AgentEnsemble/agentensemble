package net.agentensemble.delegation.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DelegationPolicyContextTest {

    @Test
    void constructor_storesAllFields() {
        List<String> roles = List.of("Analyst", "Writer");
        DelegationPolicyContext ctx = new DelegationPolicyContext("Manager", 1, 3, roles);

        assertThat(ctx.delegatingAgentRole()).isEqualTo("Manager");
        assertThat(ctx.currentDepth()).isEqualTo(1);
        assertThat(ctx.maxDepth()).isEqualTo(3);
        assertThat(ctx.availableWorkerRoles()).containsExactly("Analyst", "Writer");
    }

    @Test
    void record_equalityByValue() {
        List<String> roles = List.of("Analyst");
        DelegationPolicyContext ctx1 = new DelegationPolicyContext("Manager", 0, 3, roles);
        DelegationPolicyContext ctx2 = new DelegationPolicyContext("Manager", 0, 3, roles);

        assertThat(ctx1).isEqualTo(ctx2);
        assertThat(ctx1.hashCode()).isEqualTo(ctx2.hashCode());
    }

    @Test
    void record_inequalityOnDifferentFields() {
        DelegationPolicyContext ctx1 = new DelegationPolicyContext("Manager", 0, 3, List.of());
        DelegationPolicyContext ctx2 = new DelegationPolicyContext("Manager", 1, 3, List.of());

        assertThat(ctx1).isNotEqualTo(ctx2);
    }

    @Test
    void toString_containsFieldValues() {
        DelegationPolicyContext ctx = new DelegationPolicyContext("Manager", 2, 5, List.of("Analyst"));
        String str = ctx.toString();

        assertThat(str).contains("Manager");
        assertThat(str).contains("2");
        assertThat(str).contains("5");
    }
}
