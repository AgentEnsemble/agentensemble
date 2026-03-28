package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecordingNetworkTask}.
 */
class RecordingNetworkTaskTest {

    @Test
    void name_returnsEnsembleDotTask() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        assertThat(recorder.name()).isEqualTo("kitchen.prepare-meal");
    }

    @Test
    void description_mentionsEnsembleAndTask() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        assertThat(recorder.description()).contains("ensemble").contains("kitchen");
        assertThat(recorder.description()).contains("task").contains("prepare-meal");
    }

    @Test
    void execute_recordsTheInput() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        recorder.execute("order wagyu steak");

        assertThat(recorder.lastRequest()).isEqualTo("order wagyu steak");
    }

    @Test
    void execute_returnsDefaultResponse() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "custom response");

        ToolResult result = recorder.execute("anything");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("custom response");
    }

    @Test
    void callCount_startsAtZero() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        assertThat(recorder.callCount()).isZero();
    }

    @Test
    void callCount_incrementsOnEachCall() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        recorder.execute("first");
        assertThat(recorder.callCount()).isEqualTo(1);

        recorder.execute("second");
        assertThat(recorder.callCount()).isEqualTo(2);

        recorder.execute("third");
        assertThat(recorder.callCount()).isEqualTo(3);
    }

    @Test
    void lastRequest_returnsMostRecentInput() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        recorder.execute("first");
        recorder.execute("second");
        recorder.execute("third");

        assertThat(recorder.lastRequest()).isEqualTo("third");
    }

    @Test
    void lastRequest_throwsNoSuchElementExceptionWhenNoRequests() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        assertThatThrownBy(recorder::lastRequest).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void requests_returnsImmutableCopyOfAllInputs() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        recorder.execute("first");
        recorder.execute("second");
        recorder.execute("third");

        List<String> requests = recorder.requests();
        assertThat(requests).containsExactly("first", "second", "third");

        // Verify immutability
        assertThatThrownBy(() -> requests.add("fourth")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requests_returnsSnapshotNotLiveView() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        recorder.execute("first");
        List<String> snapshot = recorder.requests();

        recorder.execute("second");

        assertThat(snapshot).containsExactly("first");
        assertThat(recorder.requests()).containsExactly("first", "second");
    }

    @Test
    void ensembleName_returnsConfiguredValue() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        assertThat(recorder.ensembleName()).isEqualTo("kitchen");
    }

    @Test
    void taskName_returnsConfiguredValue() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        assertThat(recorder.taskName()).isEqualTo("prepare-meal");
    }

    @Test
    void threadSafety_multipleConcurrentCallsAllRecorded() throws InterruptedException {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");
        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        recorder.execute("request-" + index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(recorder.callCount()).isEqualTo(threadCount);
            assertThat(recorder.requests()).hasSize(threadCount);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void networkTaskRecordingFactory_createsCorrectInstance() {
        RecordingNetworkTask recorder = NetworkTask.recording("kitchen", "prepare-meal");

        assertThat(recorder.ensembleName()).isEqualTo("kitchen");
        assertThat(recorder.taskName()).isEqualTo("prepare-meal");
        assertThat(recorder.name()).isEqualTo("kitchen.prepare-meal");
        assertThat(recorder.callCount()).isZero();

        ToolResult result = recorder.execute("test input");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("recorded");
        assertThat(recorder.lastRequest()).isEqualTo("test input");
    }

    @Test
    void networkTaskRecordingFactory_withCustomDefaultResponse() {
        RecordingNetworkTask recorder = NetworkTask.recording("kitchen", "prepare-meal", "custom default");

        ToolResult result = recorder.execute("test input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("custom default");
    }

    @Test
    void execute_handlesNullInputAsEmptyString() {
        RecordingNetworkTask recorder = new RecordingNetworkTask("kitchen", "prepare-meal", "recorded");

        recorder.execute(null);

        assertThat(recorder.callCount()).isEqualTo(1);
        assertThat(recorder.lastRequest()).isEmpty();
    }
}
