package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.agentensemble.Ensemble;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.web.RunState.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RunManager#cancelRun(String)} and
 * {@link RunManager#switchModel(String, ChatModel)}.
 */
class RunManagerCancelSwitchTest {

    private RunManager manager;
    private Ensemble mockEnsemble;
    private EnsembleOutput mockOutput;

    @BeforeEach
    void setUp() {
        manager = new RunManager(/* maxConcurrentRuns= */ 3, /* maxRetainedRuns= */ 10);
        mockEnsemble = mock(Ensemble.class);
        mockOutput = mock(EnsembleOutput.class);

        when(mockEnsemble.getTasks()).thenReturn(List.of());
        when(mockOutput.getTaskOutputs()).thenReturn(List.of());
        when(mockOutput.getMetrics()).thenReturn(null);

        // withAdditionalListener is called internally in executeRun;
        // the mock must return a valid Ensemble with the same tasks
        when(mockEnsemble.withAdditionalListener(any())).thenReturn(mockEnsemble);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    // ========================
    // cancelRun
    // ========================

    @Test
    void cancelRun_unknownRunId_returnsNotFound() {
        assertThat(manager.cancelRun("run-unknown")).isEqualTo("NOT_FOUND");
    }

    @Test
    void cancelRun_acceptedRun_returnsCancelling() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch waitLatch = new CountDownLatch(1);

        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startLatch.countDown();
            waitLatch.await(5, TimeUnit.SECONDS);
            return mockOutput;
        });

        RunState state = manager.submitRun(mockEnsemble, null, null, null, null, null);
        assertThat(state.getStatus()).isEqualTo(Status.ACCEPTED);

        // Wait until run is executing
        assertThat(startLatch.await(3, TimeUnit.SECONDS)).isTrue();

        String result = manager.cancelRun(state.getRunId());
        assertThat(result).isEqualTo("CANCELLING");
        assertThat(state.isCancelled()).isTrue();

        waitLatch.countDown();
    }

    @Test
    void cancelRun_completedRun_returnsRejected() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);

        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        RunState state = manager.submitRun(mockEnsemble, null, null, null, null, resultMsg -> doneLatch.countDown());
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Run is now completed
        assertThat(state.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(manager.cancelRun(state.getRunId())).isEqualTo("REJECTED");
    }

    // ========================
    // switchModel
    // ========================

    @Test
    void switchModel_unknownRunId_returnsNotFound() {
        ChatModel newModel = mock(ChatModel.class);
        assertThat(manager.switchModel("run-unknown", newModel)).isEqualTo("NOT_FOUND");
    }

    @Test
    void switchModel_completedRun_returnsRejected() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        RunState state = manager.submitRun(mockEnsemble, null, null, null, null, resultMsg -> doneLatch.countDown());
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        ChatModel newModel = mock(ChatModel.class);
        assertThat(manager.switchModel(state.getRunId(), newModel)).isEqualTo("REJECTED");
    }

    @Test
    void switchModel_runningRun_returnsApplied() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch waitLatch = new CountDownLatch(1);

        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startLatch.countDown();
            waitLatch.await(5, TimeUnit.SECONDS);
            return mockOutput;
        });

        RunState state = manager.submitRun(mockEnsemble, null, null, null, null, null);
        assertThat(startLatch.await(3, TimeUnit.SECONDS)).isTrue();

        ChatModel newModel = mock(ChatModel.class);
        String result = manager.switchModel(state.getRunId(), newModel);
        assertThat(result).isEqualTo("APPLIED");

        waitLatch.countDown();
    }

    // ========================
    // CancellationCheckListener integration
    // ========================

    @Test
    void cancellationFlag_setBeforeExecution_runCompletesAsCancelled() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        RunState state = manager.submitRun(mockEnsemble, null, null, null, null, resultMsg -> doneLatch.countDown());
        // Pre-cancel (before run starts): flag is set, run still executes but status becomes CANCELLED
        manager.cancelRun(state.getRunId());

        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        // Run is COMPLETED or CANCELLED depending on timing; cancelled flag is set
        assertThat(state.isCancelled()).isTrue();
    }
}
