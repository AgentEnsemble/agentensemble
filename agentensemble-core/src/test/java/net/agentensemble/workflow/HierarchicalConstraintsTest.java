package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HierarchicalConstraintsTest {

    // ========================
    // Default / empty builder
    // ========================

    @Test
    void emptyBuilder_requiredWorkersIsEmpty() {
        var constraints = HierarchicalConstraints.builder().build();

        assertThat(constraints.getRequiredWorkers()).isEmpty();
    }

    @Test
    void emptyBuilder_allowedWorkersIsEmpty() {
        var constraints = HierarchicalConstraints.builder().build();

        assertThat(constraints.getAllowedWorkers()).isEmpty();
    }

    @Test
    void emptyBuilder_maxCallsPerWorkerIsEmpty() {
        var constraints = HierarchicalConstraints.builder().build();

        assertThat(constraints.getMaxCallsPerWorker()).isEmpty();
    }

    @Test
    void emptyBuilder_globalMaxDelegationsIsZero() {
        var constraints = HierarchicalConstraints.builder().build();

        assertThat(constraints.getGlobalMaxDelegations()).isZero();
    }

    @Test
    void emptyBuilder_requiredStagesIsEmpty() {
        var constraints = HierarchicalConstraints.builder().build();

        assertThat(constraints.getRequiredStages()).isEmpty();
    }

    // ========================
    // Builder: requiredWorker(s)
    // ========================

    @Test
    void requiredWorker_singleRole_addedToSet() {
        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Analyst").build();

        assertThat(constraints.getRequiredWorkers()).containsExactlyInAnyOrder("Analyst");
    }

    @Test
    void requiredWorker_multipleRoles_allAddedToSet() {
        var constraints = HierarchicalConstraints.builder()
                .requiredWorker("Analyst")
                .requiredWorker("Writer")
                .build();

        assertThat(constraints.getRequiredWorkers()).containsExactlyInAnyOrder("Analyst", "Writer");
    }

    @Test
    void requiredWorkers_bulkAdd_allAdded() {
        var constraints = HierarchicalConstraints.builder()
                .requiredWorkers(Set.of("A", "B", "C"))
                .build();

        assertThat(constraints.getRequiredWorkers()).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void getRequiredWorkers_returnedSetIsImmutable() {
        var constraints =
                HierarchicalConstraints.builder().requiredWorker("Analyst").build();

        assertThatThrownBy(() -> constraints.getRequiredWorkers().add("Extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Builder: allowedWorker(s)
    // ========================

    @Test
    void allowedWorker_singleRole_addedToSet() {
        var constraints =
                HierarchicalConstraints.builder().allowedWorker("Analyst").build();

        assertThat(constraints.getAllowedWorkers()).containsExactlyInAnyOrder("Analyst");
    }

    @Test
    void allowedWorker_multipleRoles_allAddedToSet() {
        var constraints = HierarchicalConstraints.builder()
                .allowedWorker("Analyst")
                .allowedWorker("Writer")
                .build();

        assertThat(constraints.getAllowedWorkers()).containsExactlyInAnyOrder("Analyst", "Writer");
    }

    @Test
    void getAllowedWorkers_returnedSetIsImmutable() {
        var constraints =
                HierarchicalConstraints.builder().allowedWorker("Analyst").build();

        assertThatThrownBy(() -> constraints.getAllowedWorkers().add("Extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Builder: maxCallsPerWorker
    // ========================

    @Test
    void maxCallsPerWorker_singleEntry_addedToMap() {
        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("Analyst", 3)
                .build();

        assertThat(constraints.getMaxCallsPerWorker()).containsEntry("Analyst", 3);
    }

    @Test
    void maxCallsPerWorker_multipleEntries_allAddedToMap() {
        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("Analyst", 2)
                .maxCallsPerWorker("Writer", 5)
                .build();

        assertThat(constraints.getMaxCallsPerWorker())
                .containsEntry("Analyst", 2)
                .containsEntry("Writer", 5);
    }

    @Test
    void getMaxCallsPerWorker_returnedMapIsImmutable() {
        var constraints = HierarchicalConstraints.builder()
                .maxCallsPerWorker("Analyst", 3)
                .build();

        assertThatThrownBy(() -> constraints.getMaxCallsPerWorker().put("Extra", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Builder: globalMaxDelegations
    // ========================

    @Test
    void globalMaxDelegations_setExplicitly_returnsSetValue() {
        var constraints =
                HierarchicalConstraints.builder().globalMaxDelegations(10).build();

        assertThat(constraints.getGlobalMaxDelegations()).isEqualTo(10);
    }

    @Test
    void globalMaxDelegations_zero_isUnlimited() {
        var constraints =
                HierarchicalConstraints.builder().globalMaxDelegations(0).build();

        assertThat(constraints.getGlobalMaxDelegations()).isZero();
    }

    // ========================
    // Builder: requiredStages
    // ========================

    @Test
    void requiredStage_singleStage_addedToList() {
        var constraints = HierarchicalConstraints.builder()
                .requiredStage(List.of("Analyst"))
                .build();

        assertThat(constraints.getRequiredStages()).containsExactly(List.of("Analyst"));
    }

    @Test
    void requiredStage_multipleStages_preservesOrder() {
        var stage0 = List.of("Researcher");
        var stage1 = List.of("Analyst", "Writer");

        var constraints = HierarchicalConstraints.builder()
                .requiredStage(stage0)
                .requiredStage(stage1)
                .build();

        assertThat(constraints.getRequiredStages()).containsExactly(stage0, stage1);
    }

    @Test
    void getRequiredStages_returnedListIsImmutable() {
        var constraints = HierarchicalConstraints.builder()
                .requiredStage(List.of("Analyst"))
                .build();

        assertThatThrownBy(() -> constraints.getRequiredStages().add(List.of("Extra")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Combined builder usage
    // ========================

    @Test
    void fullBuilder_allFieldsSet_accessorsReturnCorrectValues() {
        var constraints = HierarchicalConstraints.builder()
                .requiredWorker("Researcher")
                .allowedWorker("Researcher")
                .allowedWorker("Analyst")
                .maxCallsPerWorker("Analyst", 2)
                .globalMaxDelegations(5)
                .requiredStage(List.of("Researcher"))
                .requiredStage(List.of("Analyst"))
                .build();

        assertThat(constraints.getRequiredWorkers()).containsExactlyInAnyOrder("Researcher");
        assertThat(constraints.getAllowedWorkers()).containsExactlyInAnyOrder("Researcher", "Analyst");
        assertThat(constraints.getMaxCallsPerWorker()).containsEntry("Analyst", 2);
        assertThat(constraints.getGlobalMaxDelegations()).isEqualTo(5);
        assertThat(constraints.getRequiredStages()).containsExactly(List.of("Researcher"), List.of("Analyst"));
    }
}
