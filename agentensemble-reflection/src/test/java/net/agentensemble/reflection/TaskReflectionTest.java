package net.agentensemble.reflection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskReflectionTest {

    @Test
    void ofFirstRun_setsRunCountToOne() {
        TaskReflection reflection = TaskReflection.ofFirstRun(
                "Improved description", "Improved expected output", List.of("Observation 1"), List.of("Suggestion 1"));

        assertThat(reflection.runCount()).isEqualTo(1);
    }

    @Test
    void ofFirstRun_setsReflectedAtToRecentInstant() {
        Instant before = Instant.now();
        TaskReflection reflection = TaskReflection.ofFirstRun("desc", "output", List.of(), List.of());
        Instant after = Instant.now();

        assertThat(reflection.reflectedAt()).isBetween(before, after);
    }

    @Test
    void ofFirstRun_storesAllFields() {
        List<String> observations = List.of("obs1", "obs2");
        List<String> suggestions = List.of("sug1");

        TaskReflection reflection =
                TaskReflection.ofFirstRun("Refined description", "Refined output", observations, suggestions);

        assertThat(reflection.refinedDescription()).isEqualTo("Refined description");
        assertThat(reflection.refinedExpectedOutput()).isEqualTo("Refined output");
        assertThat(reflection.observations()).containsExactlyElementsOf(observations);
        assertThat(reflection.suggestions()).containsExactlyElementsOf(suggestions);
    }

    @Test
    void fromPrior_incrementsRunCount() {
        TaskReflection prior = TaskReflection.ofFirstRun("desc", "output", List.of(), List.of());

        TaskReflection updated = TaskReflection.fromPrior("desc2", "output2", List.of(), List.of(), prior);

        assertThat(updated.runCount()).isEqualTo(2);
    }

    @Test
    void fromPrior_incrementsRunCountMultipleTimes() {
        TaskReflection r1 = TaskReflection.ofFirstRun("d", "o", List.of(), List.of());
        TaskReflection r2 = TaskReflection.fromPrior("d2", "o2", List.of(), List.of(), r1);
        TaskReflection r3 = TaskReflection.fromPrior("d3", "o3", List.of(), List.of(), r2);

        assertThat(r3.runCount()).isEqualTo(3);
    }

    @Test
    void fromPrior_refreshesTimestamp() throws InterruptedException {
        TaskReflection prior = TaskReflection.ofFirstRun("desc", "output", List.of(), List.of());
        Thread.sleep(1); // ensure clock advances at least 1ms

        TaskReflection updated = TaskReflection.fromPrior("d2", "o2", List.of(), List.of(), prior);

        assertThat(updated.reflectedAt()).isAfterOrEqualTo(prior.reflectedAt());
    }

    @Test
    void observationsAndSuggestions_areImmutable() {
        TaskReflection reflection = TaskReflection.ofFirstRun("d", "o", List.of("obs"), List.of("sug"));

        assertThatThrownBy(() -> reflection.observations().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> reflection.suggestions().add("new")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void constructor_rejectsNullRefinedDescription() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReflection(null, "output", List.of(), List.of(), Instant.now(), 1))
                .withMessageContaining("refinedDescription");
    }

    @Test
    void constructor_rejectsNullRefinedExpectedOutput() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReflection("desc", null, List.of(), List.of(), Instant.now(), 1))
                .withMessageContaining("refinedExpectedOutput");
    }

    @Test
    void constructor_rejectsNullObservations() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReflection("desc", "output", null, List.of(), Instant.now(), 1))
                .withMessageContaining("observations");
    }

    @Test
    void constructor_rejectsNullSuggestions() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReflection("desc", "output", List.of(), null, Instant.now(), 1))
                .withMessageContaining("suggestions");
    }

    @Test
    void constructor_rejectsNullReflectedAt() {
        assertThatNullPointerException()
                .isThrownBy(() -> new TaskReflection("desc", "output", List.of(), List.of(), null, 1))
                .withMessageContaining("reflectedAt");
    }

    @Test
    void constructor_rejectsRunCountZero() {
        assertThatThrownBy(() -> new TaskReflection("d", "o", List.of(), List.of(), Instant.now(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runCount");
    }

    @Test
    void constructor_rejectsNegativeRunCount() {
        assertThatThrownBy(() -> new TaskReflection("d", "o", List.of(), List.of(), Instant.now(), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runCount");
    }

    @Test
    void fromPrior_rejectsNullPrior() {
        assertThatNullPointerException()
                .isThrownBy(() -> TaskReflection.fromPrior("d", "o", List.of(), List.of(), null))
                .withMessageContaining("prior");
    }

    @Test
    void emptyObservationsAndSuggestions_areAllowed() {
        TaskReflection reflection = TaskReflection.ofFirstRun("d", "o", List.of(), List.of());

        assertThat(reflection.observations()).isEmpty();
        assertThat(reflection.suggestions()).isEmpty();
    }
}
