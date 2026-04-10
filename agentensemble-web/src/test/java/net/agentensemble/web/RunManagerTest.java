package net.agentensemble.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.agentensemble.Ensemble;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.web.RunState.Status;
import net.agentensemble.web.protocol.RunResultMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RunManager}: submission, state tracking, concurrency limiting,
 * eviction, and shutdown.
 */
class RunManagerTest {

    private RunManager manager;
    private Ensemble mockEnsemble;
    private EnsembleOutput mockOutput;

    @BeforeEach
    void setUp() {
        manager = new RunManager(/* maxConcurrentRuns= */ 2, /* maxRetainedRuns= */ 5);
        mockEnsemble = mock(Ensemble.class);
        mockOutput = mock(EnsembleOutput.class);

        when(mockEnsemble.getTasks()).thenReturn(List.of());
        when(mockOutput.getTaskOutputs()).thenReturn(List.of());
        when(mockOutput.getMetrics()).thenReturn(null);
        // RunManager.executeRun() calls withAdditionalListener to add the CancellationCheckListener.
        // Without this stub, Mockito returns null which causes a NullPointerException when run() is called.
        when(mockEnsemble.withAdditionalListener(any())).thenReturn(mockEnsemble);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    // ========================
    // Constructor validation
    // ========================

    @Test
    void constructor_zeroMaxConcurrentRuns_throws() {
        assertThatThrownBy(() -> new RunManager(0, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrentRuns must be >= 1");
    }

    @Test
    void constructor_zeroMaxRetainedRuns_throws() {
        assertThatThrownBy(() -> new RunManager(5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetainedRuns must be >= 1");
    }

    // ========================
    // submitRun -- happy path
    // ========================

    @Test
    void submitRun_accepted_returnAcceptedState() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);

        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startLatch.countDown();
            doneLatch.await(5, TimeUnit.SECONDS);
            return mockOutput;
        });

        RunState state = manager.submitRun(mockEnsemble, Map.of("topic", "AI"), null, null, null, null);

        // Run ID and task count are assigned deterministically at submission time.
        assertThat(state.getRunId()).startsWith("run-");
        assertThat(state.getTaskCount()).isZero();

        // Wait for the virtual thread to start executing; this confirms that submitRun()
        // returned before the run completed (non-blocking), and that the state has advanced
        // to RUNNING (the virtual thread acquired the semaphore and called executeRun).
        assertThat(startLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(state.getStatus()).isEqualTo(Status.RUNNING);

        // Allow run to complete
        doneLatch.countDown();
    }

    @Test
    void submitRun_completesSuccessfully_stateTransitionsToCompleted() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<RunResultMessage> result = new AtomicReference<>();

        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        RunState state = manager.submitRun(mockEnsemble, Map.of("topic", "AI"), null, null, null, resultMsg -> {
            result.set(resultMsg);
            doneLatch.countDown();
        });

        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(state.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(result.get()).isNotNull();
        assertThat(result.get().runId()).isEqualTo(state.getRunId());
        assertThat(result.get().status()).isEqualTo("COMPLETED");
    }

    @Test
    void submitRun_ensembleThrows_stateTransitionsToFailed() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<RunResultMessage> result = new AtomicReference<>();

        when(mockEnsemble.run(any(Map.class), any())).thenThrow(new RuntimeException("LLM failed"));

        RunState state = manager.submitRun(mockEnsemble, Map.of(), null, null, null, resultMsg -> {
            result.set(resultMsg);
            doneLatch.countDown();
        });

        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(state.getStatus()).isEqualTo(Status.FAILED);
        assertThat(state.getError()).contains("LLM failed");
        assertThat(result.get().status()).isEqualTo("FAILED");
        assertThat(result.get().error()).contains("LLM failed");
    }

    // ========================
    // Concurrency limiting
    // ========================

    @Test
    void submitRun_atConcurrencyLimit_returnsRejected() throws InterruptedException {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(2); // wait for 2 runs to start

        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startedLatch.countDown();
            blockLatch.await(5, TimeUnit.SECONDS);
            return mockOutput;
        });

        // Submit 2 runs to fill the limit (maxConcurrentRuns = 2)
        RunState s1 = manager.submitRun(mockEnsemble, Map.of(), null, null, null, null);
        RunState s2 = manager.submitRun(mockEnsemble, Map.of(), null, null, null, null);
        assertThat(startedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Third run should be rejected
        RunState s3 = manager.submitRun(mockEnsemble, Map.of(), null, null, null, null);
        assertThat(s3.getStatus()).isEqualTo(Status.REJECTED);

        // Rejected state not retained
        assertThat(manager.getRun(s3.getRunId())).isEmpty();

        // Allow in-flight runs to complete
        blockLatch.countDown();
    }

    @Test
    void getActiveCount_reflectsRunningRuns() throws InterruptedException {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(1);

        when(mockEnsemble.run(any(Map.class), any())).thenAnswer(inv -> {
            startedLatch.countDown();
            blockLatch.await(5, TimeUnit.SECONDS);
            return mockOutput;
        });

        assertThat(manager.getActiveCount()).isZero();
        manager.submitRun(mockEnsemble, Map.of(), null, null, null, null);
        assertThat(startedLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(manager.getActiveCount()).isEqualTo(1);

        blockLatch.countDown();
    }

    // ========================
    // getRun
    // ========================

    @Test
    void getRun_existingRun_returnsPresent() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        RunState state = manager.submitRun(mockEnsemble, Map.of(), null, null, null, r -> doneLatch.countDown());
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        Optional<RunState> found = manager.getRun(state.getRunId());
        assertThat(found).isPresent();
        assertThat(found.get().getRunId()).isEqualTo(state.getRunId());
    }

    @Test
    void getRun_unknownId_returnsEmpty() {
        assertThat(manager.getRun("run-nonexistent")).isEmpty();
    }

    // ========================
    // listRuns
    // ========================

    @Test
    void listAllRuns_returnsAllRetainedRuns() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(2);
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        manager.submitRun(mockEnsemble, Map.of(), null, null, null, r -> doneLatch.countDown());
        manager.submitRun(mockEnsemble, Map.of(), null, null, null, r -> doneLatch.countDown());
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        List<RunState> runs = manager.listAllRuns();
        assertThat(runs).hasSize(2);
    }

    @Test
    void listRuns_withStatusFilter_returnsOnlyMatchingStatus() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        RunState completed = manager.submitRun(mockEnsemble, Map.of(), null, null, null, r -> doneLatch.countDown());
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        List<RunState> completedRuns = manager.listRuns(Status.COMPLETED, null, null);
        assertThat(completedRuns).extracting(RunState::getRunId).contains(completed.getRunId());

        List<RunState> failedRuns = manager.listRuns(Status.FAILED, null, null);
        assertThat(failedRuns).extracting(RunState::getRunId).doesNotContain(completed.getRunId());
    }

    @Test
    void listRuns_withTagFilter_returnsOnlyMatchingTags() throws Exception {
        CountDownLatch doneLatch = new CountDownLatch(1);
        when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

        manager.submitRun(mockEnsemble, Map.of(), Map.of("env", "staging"), null, null, r -> doneLatch.countDown());
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();

        List<RunState> stagingRuns = manager.listRuns(null, "env", "staging");
        assertThat(stagingRuns).hasSize(1);

        List<RunState> prodRuns = manager.listRuns(null, "env", "prod");
        assertThat(prodRuns).isEmpty();
    }

    // ========================
    // Eviction
    // ========================

    @Test
    void eviction_oldestCompletedRunsEvictedWhenOverLimit() throws Exception {
        RunManager smallManager = new RunManager(5, /* maxRetainedRuns= */ 2);
        try {
            when(mockEnsemble.run(any(Map.class), any())).thenReturn(mockOutput);

            CountDownLatch doneLatch1 = new CountDownLatch(1);
            CountDownLatch doneLatch2 = new CountDownLatch(1);
            CountDownLatch doneLatch3 = new CountDownLatch(1);

            RunState s1 = smallManager.submitRun(mockEnsemble, Map.of(), null, null, null, r -> doneLatch1.countDown());
            assertThat(doneLatch1.await(5, TimeUnit.SECONDS)).isTrue();

            RunState s2 = smallManager.submitRun(mockEnsemble, Map.of(), null, null, null, r -> doneLatch2.countDown());
            assertThat(doneLatch2.await(5, TimeUnit.SECONDS)).isTrue();

            RunState s3 = smallManager.submitRun(mockEnsemble, Map.of(), null, null, null, r -> doneLatch3.countDown());
            assertThat(doneLatch3.await(5, TimeUnit.SECONDS)).isTrue();

            // s1 should have been evicted (oldest completed), s2 and s3 should still be present
            assertThat(smallManager.listAllRuns()).hasSize(2);
            assertThat(smallManager.getRun(s1.getRunId())).isEmpty();
            assertThat(smallManager.getRun(s2.getRunId())).isPresent();
            assertThat(smallManager.getRun(s3.getRunId())).isPresent();
        } finally {
            smallManager.shutdown();
        }
    }

    // ========================
    // getMaxConcurrentRuns
    // ========================

    @Test
    void getMaxConcurrentRuns_returnsConfiguredValue() {
        assertThat(manager.getMaxConcurrentRuns()).isEqualTo(2);
    }
}
