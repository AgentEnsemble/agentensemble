package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.Map;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.ratelimit.RateLimit;
import net.agentensemble.workflow.Workflow;
import org.junit.jupiter.api.Test;

/**
 * Tests for Ensemble builder defaults and event listener registration.
 *
 * Validation behaviour is covered by EnsembleValidationTest.
 */
class EnsembleTest {

    private Agent agent(String role) {
        return Agent.builder()
                .role(role)
                .goal("Do the work")
                .llm(mock(ChatModel.class))
                .build();
    }

    // ========================
    // Defaults
    // ========================

    @Test
    void testDefaultWorkflow_isNullWhenNotSet() {
        // workflow is null when not explicitly set; the framework infers SEQUENTIAL or PARALLEL
        // at run time based on task context declarations (see issue #112).
        var ensemble = Ensemble.builder().build();

        assertThat(ensemble.getWorkflow()).isNull();
    }

    @Test
    void testExplicitWorkflow_sequential_isPreserved() {
        var ensemble = Ensemble.builder().workflow(Workflow.SEQUENTIAL).build();
        assertThat(ensemble.getWorkflow()).isEqualTo(Workflow.SEQUENTIAL);
    }

    @Test
    void testDefaultVerbose_isFalse() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().build();

        assertThat(ensemble.isVerbose()).isFalse();
    }

    // ========================
    // Listener builder
    // ========================

    @Test
    void testDefaultListeners_isEmpty() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().build();
        assertThat(ensemble.getListeners()).isEmpty();
    }

    @Test
    void testListener_addsToList() {
        var researcher = agent("Researcher");
        EnsembleListener listener = new EnsembleListener() {};
        var ensemble = Ensemble.builder().listener(listener).build();
        assertThat(ensemble.getListeners()).containsExactly(listener);
    }

    @Test
    void testOnTaskStart_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().onTaskStart(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testOnTaskComplete_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().onTaskComplete(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testOnTaskFailed_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().onTaskFailed(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testOnToolCall_addsListenerToList() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().onToolCall(e -> {}).build();
        assertThat(ensemble.getListeners()).hasSize(1);
    }

    @Test
    void testMultipleListeners_allAccumulate() {
        var researcher = agent("Researcher");
        EnsembleListener l1 = new EnsembleListener() {};
        EnsembleListener l2 = new EnsembleListener() {};
        var ensemble = Ensemble.builder()
                .listener(l1)
                .listener(l2)
                .onTaskStart(e -> {})
                .build();
        assertThat(ensemble.getListeners()).hasSize(3);
    }

    // ========================
    // Inputs builder
    // ========================

    @Test
    void testDefaultInputs_isEmpty() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().build();
        assertThat(ensemble.getInputs()).isEmpty();
    }

    @Test
    void testInput_addsSingleEntry() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder().input("topic", "AI agents").build();
        assertThat(ensemble.getInputs()).containsExactly(Map.entry("topic", "AI agents"));
    }

    @Test
    void testMultipleInput_allAccumulate() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder()
                .input("company", "Acme")
                .input("industry", "software")
                .build();
        assertThat(ensemble.getInputs())
                .hasSize(2)
                .containsEntry("company", "Acme")
                .containsEntry("industry", "software");
    }

    @Test
    void testInputs_bulkAddsAllEntries() {
        var researcher = agent("Researcher");
        var ensemble = Ensemble.builder()
                .inputs(Map.of("company", "Acme", "industry", "software"))
                .build();
        assertThat(ensemble.getInputs()).containsEntry("company", "Acme").containsEntry("industry", "software");
    }

    // ========================
    // Null handler guard tests
    // ========================

    @Test
    void testOnTaskStart_nullHandler_throwsNullPointerException() {
        assertThatThrownBy(() -> Ensemble.builder().onTaskStart(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("handler");
    }

    @Test
    void testOnTaskComplete_nullHandler_throwsNullPointerException() {
        assertThatThrownBy(() -> Ensemble.builder().onTaskComplete(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("handler");
    }

    @Test
    void testOnTaskFailed_nullHandler_throwsNullPointerException() {
        assertThatThrownBy(() -> Ensemble.builder().onTaskFailed(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("handler");
    }

    @Test
    void testOnToolCall_nullHandler_throwsNullPointerException() {
        assertThatThrownBy(() -> Ensemble.builder().onToolCall(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("handler");
    }

    // ========================
    // rateLimit builder field
    // ========================

    @Test
    void testDefaultRateLimit_isNull() {
        var ensemble = Ensemble.builder().build();
        assertThat(ensemble.getRateLimit()).isNull();
    }

    @Test
    void testRateLimit_isStoredOnEnsemble() {
        var limit = RateLimit.perMinute(60);
        var ensemble = Ensemble.builder().rateLimit(limit).build();
        assertThat(ensemble.getRateLimit()).isSameAs(limit);
    }

    @Test
    void testRateLimit_perSecond_isStoredOnEnsemble() {
        var limit = RateLimit.perSecond(2);
        var ensemble = Ensemble.builder().rateLimit(limit).build();
        assertThat(ensemble.getRateLimit()).isEqualTo(RateLimit.perSecond(2));
    }

    @Test
    void testRateLimit_canBeCombinedWithChatLanguageModel() {
        ChatModel model = mock(ChatModel.class);
        var ensemble = Ensemble.builder()
                .chatLanguageModel(model)
                .rateLimit(RateLimit.perMinute(60))
                .build();
        assertThat(ensemble.getChatLanguageModel()).isSameAs(model);
        assertThat(ensemble.getRateLimit()).isEqualTo(RateLimit.perMinute(60));
    }
}
