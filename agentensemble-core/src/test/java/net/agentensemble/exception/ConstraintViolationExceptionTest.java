package net.agentensemble.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;
import net.agentensemble.task.TaskOutput;
import org.junit.jupiter.api.Test;

class ConstraintViolationExceptionTest {

    // ========================
    // Message formatting
    // ========================

    @Test
    void singleViolation_messageContainsViolation() {
        var ex = new ConstraintViolationException(List.of("Required worker 'Analyst' was not called"));

        assertThat(ex.getMessage()).contains("Analyst").contains("violated");
    }

    @Test
    void multipleViolations_messageContainsCount() {
        var ex = new ConstraintViolationException(List.of("v1", "v2", "v3"));

        assertThat(ex.getMessage()).contains("3");
    }

    @Test
    void multipleViolations_messageContainsAllViolations() {
        var ex = new ConstraintViolationException(List.of("v1", "v2"));

        assertThat(ex.getMessage()).contains("v1").contains("v2");
    }

    // ========================
    // violations() accessor
    // ========================

    @Test
    void getViolations_returnsAllViolations() {
        var ex = new ConstraintViolationException(List.of("a", "b", "c"));

        assertThat(ex.getViolations()).containsExactly("a", "b", "c");
    }

    @Test
    void getViolations_returnedListIsImmutable() {
        var ex = new ConstraintViolationException(List.of("v1"));

        assertThatThrownBy(() -> ex.getViolations().add("extra")).isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // completedTaskOutputs() accessor
    // ========================

    @Test
    void noArgsConstructor_completedTaskOutputsIsEmpty() {
        var ex = new ConstraintViolationException(List.of("v1"));

        assertThat(ex.getCompletedTaskOutputs()).isEmpty();
    }

    @Test
    void constructorWithOutputs_completedTaskOutputsReturned() {
        var output = mock(TaskOutput.class);
        var ex = new ConstraintViolationException(List.of("v1"), List.of(output));

        assertThat(ex.getCompletedTaskOutputs()).containsExactly(output);
    }

    @Test
    void getCompletedTaskOutputs_returnedListIsImmutable() {
        var output = mock(TaskOutput.class);
        var ex = new ConstraintViolationException(List.of("v1"), List.of(output));

        assertThatThrownBy(() -> ex.getCompletedTaskOutputs().add(mock(TaskOutput.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Cause constructor
    // ========================

    @Test
    void constructorWithCause_preservesCause() {
        var cause = new RuntimeException("root cause");
        var ex = new ConstraintViolationException(List.of("v1"), cause);

        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getViolations()).containsExactly("v1");
    }

    @Test
    void constructorWithCause_completedTaskOutputsIsEmpty() {
        var cause = new RuntimeException("root cause");
        var ex = new ConstraintViolationException(List.of("v1"), cause);

        assertThat(ex.getCompletedTaskOutputs()).isEmpty();
    }

    // ========================
    // Type hierarchy
    // ========================

    @Test
    void extendsAgentEnsembleException() {
        var ex = new ConstraintViolationException(List.of("v1"));

        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
    }

    @Test
    void isRuntimeException() {
        var ex = new ConstraintViolationException(List.of("v1"));

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
