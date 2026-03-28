package net.agentensemble.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.Task;
import org.junit.jupiter.api.Test;

class CodingTaskTest {

    @Test
    void fix_producesTaskWithBugDescription() {
        Task task = CodingTask.fix("NullPointerException in UserService.getById()");

        assertThat(task.getDescription()).contains("Fix the following bug");
        assertThat(task.getDescription()).contains("NullPointerException in UserService.getById()");
        assertThat(task.getExpectedOutput()).contains("bug is fixed");
        assertThat(task.getExpectedOutput()).contains("tests pass");
    }

    @Test
    void implement_producesTaskWithFeatureDescription() {
        Task task = CodingTask.implement("Add pagination to /api/users");

        assertThat(task.getDescription()).contains("Implement the following feature");
        assertThat(task.getDescription()).contains("Add pagination to /api/users");
        assertThat(task.getExpectedOutput()).contains("implemented with tests");
    }

    @Test
    void refactor_producesTaskWithRefactoringDescription() {
        Task task = CodingTask.refactor("Extract UserRepository interface");

        assertThat(task.getDescription()).contains("Refactor the following");
        assertThat(task.getDescription()).contains("Extract UserRepository interface");
        assertThat(task.getExpectedOutput()).contains("refactored");
        assertThat(task.getExpectedOutput()).contains("tests still passing");
    }

    @Test
    void fix_taskIsCustomizable() {
        Task task = CodingTask.fix("Some bug").toBuilder()
                .expectedOutput("Custom output")
                .build();

        assertThat(task.getDescription()).contains("Some bug");
        assertThat(task.getExpectedOutput()).isEqualTo("Custom output");
    }

    @Test
    void fix_null_throwsNpe() {
        assertThatThrownBy(() -> CodingTask.fix(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void implement_null_throwsNpe() {
        assertThatThrownBy(() -> CodingTask.implement(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void refactor_null_throwsNpe() {
        assertThatThrownBy(() -> CodingTask.refactor(null)).isInstanceOf(NullPointerException.class);
    }
}
