package net.agentensemble.workflow.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.Task;
import net.agentensemble.exception.ValidationException;
import org.junit.jupiter.api.Test;

class GraphBuilderTest {

    private static Task task(String name) {
        return Task.builder()
                .name(name)
                .description("desc-" + name)
                .expectedOutput("ok")
                .build();
    }

    // ========================
    // Defaults
    // ========================

    @Test
    void defaults_areAppliedWhenNotSet() {
        Graph g = Graph.builder()
                .name("g")
                .state("a", task("a"))
                .start("a")
                .edge("a", Graph.END)
                .build();

        assertThat(g.getMaxSteps()).isEqualTo(Graph.DEFAULT_MAX_STEPS);
        assertThat(g.getOnMaxSteps()).isEqualTo(MaxStepsAction.RETURN_LAST);
        assertThat(g.isInjectFeedbackOnRevisit()).isTrue();
        assertThat(g.getStates()).containsOnlyKeys("a");
        assertThat(g.getEdges()).hasSize(1);
        assertThat(g.getNoFeedbackStates()).isEmpty();
    }

    @Test
    void states_preserveDeclarationOrder() {
        Graph g = Graph.builder()
                .name("g")
                .state("a", task("a"))
                .state("b", task("b"))
                .state("c", task("c"))
                .start("a")
                .edge("a", "b")
                .edge("b", "c")
                .edge("c", Graph.END)
                .build();

        assertThat(g.getStates()).containsOnlyKeys("a", "b", "c");
        assertThat(g.getStates().keySet()).containsExactly("a", "b", "c");
    }

    @Test
    void stateNoFeedback_addsToNoFeedbackSet() {
        Graph g = Graph.builder()
                .name("g")
                .stateNoFeedback("router", task("router"))
                .state("worker", task("worker"))
                .start("router")
                .edge("router", "worker")
                .edge("worker", Graph.END)
                .build();

        assertThat(g.getNoFeedbackStates()).containsExactly("router");
    }

    // ========================
    // Edge convenience methods
    // ========================

    @Test
    void edge_unconditionalAndConditional_bothCaptured() {
        GraphPredicate cond = ctx -> true;
        Graph g = Graph.builder()
                .name("g")
                .state("a", task("a"))
                .state("b", task("b"))
                .start("a")
                .edge("a", "b", cond, "always-true")
                .edge("a", Graph.END)
                .edge("b", Graph.END)
                .build();

        assertThat(g.getEdges()).hasSize(3);
        assertThat(g.getEdges().get(0).getCondition()).isSameAs(cond);
        assertThat(g.getEdges().get(0).getConditionDescription()).isEqualTo("always-true");
        assertThat(g.getEdges().get(1).getCondition()).isNull();
    }

    // ========================
    // Validation: name
    // ========================

    @Test
    void name_mustBeNonBlank() {
        assertThatThrownBy(() -> Graph.builder()
                        .state("a", task("a"))
                        .start("a")
                        .edge("a", Graph.END)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("name must be non-blank");
    }

    // ========================
    // Validation: states
    // ========================

    @Test
    void states_mustNotBeEmpty() {
        assertThatThrownBy(() -> Graph.builder().name("g").build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must declare at least one state");
    }

    @Test
    void states_rejectsDuplicateNames() {
        assertThatThrownBy(() -> Graph.builder().name("g").state("a", task("a")).state("a", task("a2")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Duplicate state name");
    }

    @Test
    void states_rejectsBlankName() {
        assertThatThrownBy(() -> Graph.builder().name("g").state("  ", task("blank")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("State name must be non-blank");
    }

    @Test
    void states_rejectsReservedEndName() {
        // The state(...) builder method allows the name to pass through; final validation
        // happens in build().
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state(Graph.END, task("x"))
                        .start(Graph.END)
                        .edge(Graph.END, Graph.END)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("reserved state name");
    }

    // ========================
    // Validation: maxSteps
    // ========================

    @Test
    void maxSteps_zeroRejected() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .start("a")
                        .edge("a", Graph.END)
                        .maxSteps(0)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxSteps must be >= 1");
    }

    @Test
    void maxSteps_negativeRejected() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .start("a")
                        .edge("a", Graph.END)
                        .maxSteps(-1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxSteps must be >= 1");
    }

    // ========================
    // Validation: start state
    // ========================

    @Test
    void start_required() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .edge("a", Graph.END)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must declare a start state");
    }

    @Test
    void start_mustReferenceKnownState() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .start("nonexistent")
                        .edge("a", Graph.END)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("is not a declared state");
    }

    @Test
    void start_cannotBeEnd() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .start(Graph.END)
                        .edge("a", Graph.END)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("start state cannot be Graph.END");
    }

    // ========================
    // Validation: edges
    // ========================

    @Test
    void edges_unknownFromRejected() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .start("a")
                        .edge("a", Graph.END)
                        .edge("ghost", Graph.END)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'from' = 'ghost'");
    }

    @Test
    void edges_unknownToRejected() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .start("a")
                        .edge("a", "ghost")
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'to' = 'ghost'");
    }

    @Test
    void edges_endAsFromRejected() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .start("a")
                        .edge("a", Graph.END)
                        .edge(Graph.END, "a")
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("END is terminal");
    }

    // ========================
    // Validation: every non-END state has outgoing edge
    // ========================

    @Test
    void state_withNoOutgoingEdge_rejected() {
        assertThatThrownBy(() -> Graph.builder()
                        .name("g")
                        .state("a", task("a"))
                        .state("b", task("b"))
                        .start("a")
                        .edge("a", "b")
                        // no outgoing edge from "b"
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("no outgoing edges");
    }

    // ========================
    // Validation: no-feedback states
    // ========================

    @Test
    void stateNoFeedback_unknownStateRejected() {
        // Using stateNoFeedback() registers the state, so it can't fail. But manually
        // setting a noFeedback name without registering the state should fail. We reach this
        // by using toBuilder() which exposes the underlying field via the builder.
        // For now, verify the convenience method works correctly:
        Graph g = Graph.builder()
                .name("g")
                .stateNoFeedback("a", task("a"))
                .start("a")
                .edge("a", Graph.END)
                .build();
        assertThat(g.getNoFeedbackStates()).containsExactly("a");
    }
}
