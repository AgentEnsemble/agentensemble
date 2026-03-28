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
 * Unit tests for {@link RecordingNetworkTool}.
 */
class RecordingNetworkToolTest {

    @Test
    void name_returnsEnsembleDotTool() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        assertThat(recorder.name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void description_mentionsEnsembleAndTool() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        assertThat(recorder.description()).contains("ensemble").contains("kitchen");
        assertThat(recorder.description()).contains("tool").contains("check-inventory");
    }

    @Test
    void execute_recordsTheInput() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        recorder.execute("wagyu stock level");

        assertThat(recorder.lastRequest()).isEqualTo("wagyu stock level");
    }

    @Test
    void execute_returnsDefaultResult() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "custom result");

        ToolResult result = recorder.execute("anything");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("custom result");
    }

    @Test
    void callCount_startsAtZero() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        assertThat(recorder.callCount()).isZero();
    }

    @Test
    void callCount_incrementsOnEachCall() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        recorder.execute("first");
        assertThat(recorder.callCount()).isEqualTo(1);

        recorder.execute("second");
        assertThat(recorder.callCount()).isEqualTo(2);

        recorder.execute("third");
        assertThat(recorder.callCount()).isEqualTo(3);
    }

    @Test
    void lastRequest_returnsMostRecentInput() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        recorder.execute("first");
        recorder.execute("second");
        recorder.execute("third");

        assertThat(recorder.lastRequest()).isEqualTo("third");
    }

    @Test
    void lastRequest_throwsNoSuchElementExceptionWhenNoRequests() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        assertThatThrownBy(recorder::lastRequest).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void requests_returnsImmutableCopyOfAllInputs() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

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
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        recorder.execute("first");
        List<String> snapshot = recorder.requests();

        recorder.execute("second");

        assertThat(snapshot).containsExactly("first");
        assertThat(recorder.requests()).containsExactly("first", "second");
    }

    @Test
    void ensembleName_returnsConfiguredValue() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        assertThat(recorder.ensembleName()).isEqualTo("kitchen");
    }

    @Test
    void toolName_returnsConfiguredValue() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        assertThat(recorder.toolName()).isEqualTo("check-inventory");
    }

    @Test
    void threadSafety_multipleConcurrentCallsAllRecorded() throws InterruptedException {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");
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
    void networkToolRecordingFactory_createsCorrectInstance() {
        RecordingNetworkTool recorder = NetworkTool.recording("kitchen", "check-inventory");

        assertThat(recorder.ensembleName()).isEqualTo("kitchen");
        assertThat(recorder.toolName()).isEqualTo("check-inventory");
        assertThat(recorder.name()).isEqualTo("kitchen.check-inventory");
        assertThat(recorder.callCount()).isZero();

        ToolResult result = recorder.execute("test input");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("recorded");
        assertThat(recorder.lastRequest()).isEqualTo("test input");
    }

    @Test
    void networkToolRecordingFactory_withCustomDefaultResult() {
        RecordingNetworkTool recorder = NetworkTool.recording("kitchen", "check-inventory", "custom default");

        ToolResult result = recorder.execute("test input");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("custom default");
    }

    @Test
    void execute_handlesNullInputAsEmptyString() {
        RecordingNetworkTool recorder = new RecordingNetworkTool("kitchen", "check-inventory", "recorded");

        recorder.execute(null);

        assertThat(recorder.callCount()).isEqualTo(1);
        assertThat(recorder.lastRequest()).isEmpty();
    }
}
