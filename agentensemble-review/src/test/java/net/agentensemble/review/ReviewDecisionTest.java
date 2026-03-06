package net.agentensemble.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReviewDecision} -- sealed interface hierarchy and static factories.
 */
class ReviewDecisionTest {

    @Test
    void continueExecution_returnsSingleton() {
        ReviewDecision a = ReviewDecision.continueExecution();
        ReviewDecision b = ReviewDecision.continueExecution();
        assertThat(a).isInstanceOf(ReviewDecision.Continue.class);
        assertThat(a).isSameAs(b);
    }

    @Test
    void exitEarly_returnsSingleton() {
        ReviewDecision a = ReviewDecision.exitEarly();
        ReviewDecision b = ReviewDecision.exitEarly();
        assertThat(a).isInstanceOf(ReviewDecision.ExitEarly.class);
        assertThat(a).isSameAs(b);
    }

    @Test
    void edit_createsNewInstanceWithRevisedOutput() {
        ReviewDecision.Edit edit = ReviewDecision.edit("revised text");
        assertThat(edit).isInstanceOf(ReviewDecision.Edit.class);
        assertThat(edit.revisedOutput()).isEqualTo("revised text");
    }

    @Test
    void edit_allowsEmptyString() {
        ReviewDecision.Edit edit = ReviewDecision.edit("");
        assertThat(edit.revisedOutput()).isEqualTo("");
    }

    @Test
    void edit_throwsWhenRevisedOutputIsNull() {
        assertThatThrownBy(() -> ReviewDecision.edit(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void patternMatchingWorksForContinue() {
        ReviewDecision decision = ReviewDecision.continueExecution();
        String result =
                switch (decision) {
                    case ReviewDecision.Continue c -> "continue";
                    case ReviewDecision.Edit e -> "edit";
                    case ReviewDecision.ExitEarly x -> "exit";
                };
        assertThat(result).isEqualTo("continue");
    }

    @Test
    void patternMatchingWorksForEdit() {
        ReviewDecision decision = ReviewDecision.edit("new output");
        String result =
                switch (decision) {
                    case ReviewDecision.Continue c -> "continue";
                    case ReviewDecision.Edit e -> "edit:" + e.revisedOutput();
                    case ReviewDecision.ExitEarly x -> "exit";
                };
        assertThat(result).isEqualTo("edit:new output");
    }

    @Test
    void patternMatchingWorksForExitEarly() {
        ReviewDecision decision = ReviewDecision.exitEarly();
        String result =
                switch (decision) {
                    case ReviewDecision.Continue c -> "continue";
                    case ReviewDecision.Edit e -> "edit";
                    case ReviewDecision.ExitEarly x -> "exit";
                };
        assertThat(result).isEqualTo("exit");
    }
}
