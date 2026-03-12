package net.agentensemble.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Phase} builder validation, static factories, and immutability.
 */
class PhaseTest {

    private static Task task(String description) {
        return Task.of(description);
    }

    // ========================
    // Builder -- valid construction
    // ========================

    @Test
    void builder_validPhase_setsAllFields() {
        Task t1 = task("Gather sources");
        Task t2 = task("Summarize findings");

        Phase phase = Phase.builder()
                .name("research")
                .task(t1)
                .task(t2)
                .workflow(Workflow.PARALLEL)
                .build();

        assertThat(phase.getName()).isEqualTo("research");
        assertThat(phase.getTasks()).containsExactly(t1, t2);
        assertThat(phase.getWorkflow()).isEqualTo(Workflow.PARALLEL);
        assertThat(phase.getAfter()).isEmpty();
    }

    @Test
    void builder_noWorkflowOverride_workflowIsNull() {
        Phase phase = Phase.builder().name("p").task(task("t")).build();
        assertThat(phase.getWorkflow()).isNull();
    }

    @Test
    void builder_withAfterDependency_storesPredecessor() {
        Phase a = Phase.builder().name("a").task(task("a-task")).build();
        Phase b = Phase.builder().name("b").task(task("b-task")).after(a).build();

        assertThat(b.getAfter()).containsExactly(a);
    }

    @Test
    void builder_withVarargsAfter_storesAllPredecessors() {
        Phase a = Phase.builder().name("a").task(task("a")).build();
        Phase b = Phase.builder().name("b").task(task("b")).build();
        Phase c = Phase.builder().name("c").task(task("c")).build();

        Phase d = Phase.builder().name("d").task(task("d")).after(a, b, c).build();

        assertThat(d.getAfter()).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    void builder_nullElementsInVarargsAfter_areIgnored() {
        Phase a = Phase.builder().name("a").task(task("a")).build();

        Phase b = Phase.builder().name("b").task(task("b")).after(a, null).build();

        assertThat(b.getAfter()).containsExactly(a);
    }

    @Test
    void builder_nullVarargsAfter_isNoOp() {
        Phase phase =
                Phase.builder().name("p").task(task("t")).after((Phase[]) null).build();
        assertThat(phase.getAfter()).isEmpty();
    }

    @Test
    void builder_tasksListIsImmutable() {
        Phase phase = Phase.builder().name("p").task(task("t")).build();
        assertThat(phase.getTasks()).isUnmodifiable();
    }

    @Test
    void builder_afterListIsImmutable() {
        Phase a = Phase.builder().name("a").task(task("a")).build();
        Phase b = Phase.builder().name("b").task(task("b")).after(a).build();
        assertThat(b.getAfter()).isUnmodifiable();
    }

    @Test
    void builder_sequentialWorkflow_isAccepted() {
        Phase phase = Phase.builder()
                .name("p")
                .task(task("t"))
                .workflow(Workflow.SEQUENTIAL)
                .build();
        assertThat(phase.getWorkflow()).isEqualTo(Workflow.SEQUENTIAL);
    }

    @Test
    void builder_parallelWorkflow_isAccepted() {
        Phase phase = Phase.builder()
                .name("p")
                .task(task("t"))
                .workflow(Workflow.PARALLEL)
                .build();
        assertThat(phase.getWorkflow()).isEqualTo(Workflow.PARALLEL);
    }

    // ========================
    // Builder -- validation failures
    // ========================

    @Test
    void builder_nullName_throwsValidationException() {
        assertThatThrownBy(() -> Phase.builder().name(null).task(task("t")).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void builder_blankName_throwsValidationException() {
        assertThatThrownBy(() -> Phase.builder().name("   ").task(task("t")).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void builder_emptyName_throwsValidationException() {
        assertThatThrownBy(() -> Phase.builder().name("").task(task("t")).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void builder_noTasks_throwsValidationException() {
        assertThatThrownBy(() -> Phase.builder().name("research").build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one task");
    }

    @Test
    void builder_hierarchicalWorkflow_throwsValidationException() {
        assertThatThrownBy(() -> Phase.builder()
                        .name("p")
                        .task(task("t"))
                        .workflow(Workflow.HIERARCHICAL)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("HIERARCHICAL is not supported at the phase level");
    }

    // ========================
    // Static factory -- of(String, Task...)
    // ========================

    @Test
    void of_varargs_createsPhaseWithTasks() {
        Task t1 = task("T1");
        Task t2 = task("T2");

        Phase phase = Phase.of("research", t1, t2);

        assertThat(phase.getName()).isEqualTo("research");
        assertThat(phase.getTasks()).containsExactly(t1, t2);
        assertThat(phase.getWorkflow()).isNull();
        assertThat(phase.getAfter()).isEmpty();
    }

    @Test
    void of_varargs_singleTask_isAccepted() {
        Phase phase = Phase.of("solo", task("only task"));
        assertThat(phase.getTasks()).hasSize(1);
    }

    @Test
    void of_varargs_nullName_throwsValidationException() {
        assertThatThrownBy(() -> Phase.of(null, task("t")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void of_varargs_blankName_throwsValidationException() {
        assertThatThrownBy(() -> Phase.of("  ", task("t")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void of_varargs_noTasks_throwsValidationException() {
        assertThatThrownBy(() -> Phase.of("research"))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one task");
    }

    @Test
    void of_varargs_nullTasks_throwsValidationException() {
        assertThatThrownBy(() -> Phase.of("research", (Task[]) null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one task");
    }

    // ========================
    // Static factory -- of(String, List<Task>)
    // ========================

    @Test
    void of_list_createsPhaseWithTasks() {
        Task t1 = task("T1");
        Task t2 = task("T2");

        Phase phase = Phase.of("research", List.of(t1, t2));

        assertThat(phase.getName()).isEqualTo("research");
        assertThat(phase.getTasks()).containsExactly(t1, t2);
    }

    @Test
    void of_list_nullName_throwsValidationException() {
        assertThatThrownBy(() -> Phase.of(null, List.of(task("t"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must not be null or blank");
    }

    @Test
    void of_list_emptyList_throwsValidationException() {
        assertThatThrownBy(() -> Phase.of("research", List.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one task");
    }

    @Test
    void of_list_nullList_throwsValidationException() {
        assertThatThrownBy(() -> Phase.of("research", (List<Task>) null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("at least one task");
    }
}
