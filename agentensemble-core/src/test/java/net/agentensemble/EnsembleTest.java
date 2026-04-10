package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.ArrayList;
import java.util.List;
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

    // ========================
    // withTasks()
    // ========================

    @Test
    void withTasks_returnsNewEnsembleWithReplacedTasks() {
        Task original = Task.of("Original task");
        Task replacement = Task.of("Replacement task");
        Ensemble ensemble = Ensemble.builder().task(original).build();

        Ensemble copy = ensemble.withTasks(List.of(replacement));

        assertThat(copy.getTasks()).containsExactly(replacement);
        // original ensemble's task list must be unchanged (immutability)
        assertThat(ensemble.getTasks()).containsExactly(original);
    }

    @Test
    void withTasks_preservesListeners() {
        EnsembleListener listener = new EnsembleListener() {};
        Task task = Task.of("Task");
        Ensemble ensemble = Ensemble.builder().task(task).listener(listener).build();

        Ensemble copy = ensemble.withTasks(List.of(Task.of("New task")));

        assertThat(copy.getListeners()).containsExactly(listener);
    }

    @Test
    void withTasks_preservesChatLanguageModel() {
        ChatModel model = mock(ChatModel.class);
        Task task = Task.of("Task");
        Ensemble ensemble =
                Ensemble.builder().chatLanguageModel(model).task(task).build();

        Ensemble copy = ensemble.withTasks(List.of(Task.of("New task")));

        assertThat(copy.getChatLanguageModel()).isSameAs(model);
    }

    @Test
    void withTasks_preservesWorkflow() {
        Task task = Task.of("Task");
        Ensemble ensemble =
                Ensemble.builder().task(task).workflow(Workflow.SEQUENTIAL).build();

        Ensemble copy = ensemble.withTasks(List.of(Task.of("New task")));

        assertThat(copy.getWorkflow()).isEqualTo(Workflow.SEQUENTIAL);
    }

    @Test
    void withTasks_ownsDashboardLifecycleFalse() {
        Task task = Task.of("Task");
        Ensemble ensemble = Ensemble.builder().task(task).build();

        Ensemble copy = ensemble.withTasks(List.of(Task.of("New task")));

        // Copies must never own the dashboard lifecycle -- the original (or caller) owns it
        assertThat(copy.isOwnsDashboardLifecycle()).isFalse();
    }

    @Test
    void withTasks_preservesInputs() {
        Task task = Task.of("Task");
        Ensemble ensemble = Ensemble.builder().task(task).input("topic", "AI").build();

        Ensemble copy = ensemble.withTasks(List.of(Task.of("New task")));

        assertThat(copy.getInputs()).containsEntry("topic", "AI");
    }

    @Test
    void withTasks_multipleTasks_allPresent() {
        Task t1 = Task.of("Task one");
        Task t2 = Task.of("Task two");
        Task t3 = Task.of("Task three");
        Ensemble ensemble = Ensemble.builder().task(Task.of("Original")).build();

        Ensemble copy = ensemble.withTasks(List.of(t1, t2, t3));

        assertThat(copy.getTasks()).containsExactly(t1, t2, t3);
    }

    @Test
    void withTasks_nullList_throwsNullPointerException() {
        Ensemble ensemble = Ensemble.builder().task(Task.of("Task")).build();

        assertThatThrownBy(() -> ensemble.withTasks(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("newTasks must not be null");
    }

    @Test
    void withTasks_emptyList_throwsIllegalArgumentException() {
        Ensemble ensemble = Ensemble.builder().task(Task.of("Task")).build();

        assertThatThrownBy(() -> ensemble.withTasks(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("newTasks must not be empty");
    }

    @Test
    void withTasks_listWithNullElement_throwsNullPointerException() {
        Ensemble ensemble = Ensemble.builder().task(Task.of("Task")).build();
        List<Task> tasksWithNull = new ArrayList<>();
        tasksWithNull.add(null);

        assertThatThrownBy(() -> ensemble.withTasks(tasksWithNull))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("null elements");
    }
}
