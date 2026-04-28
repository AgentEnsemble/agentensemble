package net.agentensemble.workflow.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

class LoopBuilderTest {

    private static Task task(String description) {
        return Task.of(description);
    }

    private static Task namedTask(String name, String description) {
        return Task.builder()
                .name(name)
                .description(description)
                .expectedOutput("ok")
                .build();
    }

    // ========================
    // Defaults
    // ========================

    @Test
    void defaults_areAppliedWhenNotSet() {
        Loop loop = Loop.builder()
                .name("reflect")
                .task(task("write"))
                .until(ctx -> false)
                .build();

        assertThat(loop.getMaxIterations()).isEqualTo(Loop.DEFAULT_MAX_ITERATIONS);
        assertThat(loop.getOnMaxIterations()).isEqualTo(MaxIterationsAction.RETURN_LAST);
        assertThat(loop.getOutputMode()).isEqualTo(LoopOutputMode.LAST_ITERATION);
        assertThat(loop.getMemoryMode()).isEqualTo(LoopMemoryMode.ACCUMULATE);
        assertThat(loop.isInjectFeedback()).isTrue();
        assertThat(loop.getContext()).isEmpty();
    }

    @Test
    void body_preservesDeclaredOrder() {
        Task a = task("first");
        Task b = task("second");
        Task c = task("third");

        Loop loop = Loop.builder()
                .name("ordered")
                .task(a)
                .task(b)
                .task(c)
                .maxIterations(1)
                .build();

        assertThat(loop.getBody()).containsExactly(a, b, c);
    }

    @Test
    void body_isImmutable() {
        Loop loop =
                Loop.builder().name("immut").task(task("a")).maxIterations(1).build();

        assertThatThrownBy(() -> loop.getBody().add(task("b"))).isInstanceOf(UnsupportedOperationException.class);
    }

    // ========================
    // Validation: name
    // ========================

    @Test
    void name_mustNotBeNull() {
        assertThatThrownBy(() -> Loop.builder().task(task("a")).maxIterations(1).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must be non-blank");
    }

    @Test
    void name_mustNotBeBlank() {
        assertThatThrownBy(() -> Loop.builder()
                        .name("   ")
                        .task(task("a"))
                        .maxIterations(1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must be non-blank");
    }

    // ========================
    // Validation: body
    // ========================

    @Test
    void body_mustNotBeEmpty() {
        assertThatThrownBy(() -> Loop.builder().name("empty").maxIterations(1).build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("body must contain at least one task");
    }

    @Test
    void body_rejectsDuplicateNames() {
        Task a = namedTask("worker", "first");
        Task b = namedTask("worker", "second");

        assertThatThrownBy(() -> Loop.builder()
                        .name("dup")
                        .task(a)
                        .task(b)
                        .maxIterations(1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate task name");
    }

    @Test
    void body_rejectsDuplicateDescriptionsWhenUnnamed() {
        Task a = task("same description");
        Task b = task("same description");

        assertThatThrownBy(() -> Loop.builder()
                        .name("dup")
                        .task(a)
                        .task(b)
                        .maxIterations(1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("duplicate task name");
    }

    // ========================
    // Validation: stop conditions
    // ========================

    @Test
    void stopCondition_predicateAloneIsValid() {
        Loop loop = Loop.builder()
                .name("p")
                .task(task("a"))
                .until(ctx -> true)
                .maxIterations(1)
                .build();

        assertThat(loop.getUntil()).isNotNull();
    }

    @Test
    void stopCondition_maxIterationsAloneIsValid() {
        Loop loop = Loop.builder().name("m").task(task("a")).maxIterations(3).build();

        assertThat(loop.getMaxIterations()).isEqualTo(3);
        assertThat(loop.getUntil()).isNull();
    }

    @Test
    void maxIterations_mustBePositive() {
        assertThatThrownBy(() -> Loop.builder()
                        .name("zero")
                        .task(task("a"))
                        .maxIterations(0)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxIterations must be >= 1");
    }

    @Test
    void maxIterations_negativeIsRejected() {
        assertThatThrownBy(() -> Loop.builder()
                        .name("neg")
                        .task(task("a"))
                        .maxIterations(-5)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxIterations must be >= 1");
    }

    // ========================
    // Validation: body context references
    // ========================

    @Test
    void bodyContext_mayReferenceOtherBodyTasks() {
        Task first = namedTask("first", "describe first");
        Task second = Task.builder()
                .name("second")
                .description("describe second")
                .expectedOutput("ok")
                .context(List.of(first))
                .build();

        Loop loop = Loop.builder()
                .name("intra")
                .task(first)
                .task(second)
                .maxIterations(1)
                .build();

        assertThat(loop.getBody()).hasSize(2);
    }

    @Test
    void bodyContext_rejectsReferenceOutsideBody() {
        Task outside = namedTask("outside", "describe outside");
        Task inside = Task.builder()
                .name("inside")
                .description("describe inside")
                .expectedOutput("ok")
                .context(List.of(outside))
                .build();

        assertThatThrownBy(() -> Loop.builder()
                        .name("leaky")
                        .task(inside)
                        .maxIterations(1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("outside the loop body");
    }

    // ========================
    // Outer-DAG context
    // ========================

    // ========================
    // Memory window validation
    // ========================

    @Test
    void memoryWindow_withWindowMode_acceptedWhenSizeIsPositive() {
        Loop loop = Loop.builder()
                .name("win")
                .task(task("a"))
                .maxIterations(2)
                .memoryMode(LoopMemoryMode.WINDOW)
                .memoryWindowSize(3)
                .build();

        assertThat(loop.getMemoryMode()).isEqualTo(LoopMemoryMode.WINDOW);
        assertThat(loop.getMemoryWindowSize()).isEqualTo(3);
    }

    @Test
    void memoryWindow_windowModeWithoutSize_rejected() {
        assertThatThrownBy(() -> Loop.builder()
                        .name("win")
                        .task(task("a"))
                        .maxIterations(2)
                        .memoryMode(LoopMemoryMode.WINDOW)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("memoryWindowSize");
    }

    @Test
    void memoryWindow_sizeWithoutWindowMode_rejected() {
        assertThatThrownBy(() -> Loop.builder()
                        .name("win")
                        .task(task("a"))
                        .maxIterations(2)
                        .memoryWindowSize(3)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only meaningful when");
    }

    @Test
    void outerContext_isPreserved() {
        Task upstream = namedTask("up", "upstream");
        Loop loop = Loop.builder()
                .name("with-deps")
                .task(task("body"))
                .context(upstream)
                .maxIterations(1)
                .build();

        assertThat(loop.getContext()).containsExactly(upstream);
    }
}
