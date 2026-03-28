package net.agentensemble.telemetry.otel;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import net.agentensemble.callback.DelegationCompletedEvent;
import net.agentensemble.callback.DelegationFailedEvent;
import net.agentensemble.callback.DelegationStartedEvent;
import net.agentensemble.callback.TaskCompleteEvent;
import net.agentensemble.callback.TaskFailedEvent;
import net.agentensemble.callback.TaskStartEvent;
import net.agentensemble.callback.ToolCallEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OTelTracingListenerTest {

    private InMemorySpanExporter spanExporter;
    private OTelTracingListener listener;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();
        OpenTelemetry otel =
                OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        listener = OTelTracingListener.create(otel);
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void ensembleStartedAndCompleted_createsRootSpan() {
        listener.onEnsembleStarted("ens-1", "SEQUENTIAL", 3);
        listener.onEnsembleCompleted("ens-1", Duration.ofSeconds(5), "COMPLETED");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData rootSpan = spans.get(0);
        assertThat(rootSpan.getName()).isEqualTo("ensemble.run");
        assertThat(rootSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(rootSpan.getAttributes().get(OTelAttributes.ENSEMBLE_NAME)).isEqualTo("ens-1");
    }

    @Test
    void getTraceId_returnsNonNullAfterEnsembleStarted() {
        assertThat(listener.getTraceId()).isNull();

        listener.onEnsembleStarted("ens-1", "SEQUENTIAL", 1);

        assertThat(listener.getTraceId()).isNotNull();
        assertThat(listener.getTraceId()).hasSize(32); // W3C trace ID is 32 hex chars
    }

    @Test
    void taskStartAndComplete_createsTaskSpan() {
        listener.onTaskStart(new TaskStartEvent("Summarize report", "analyst", 1, 3));
        listener.onTaskComplete(
                new TaskCompleteEvent("Summarize report", "analyst", null, Duration.ofMillis(1200), 1, 3));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData taskSpan = spans.get(0);
        assertThat(taskSpan.getName()).isEqualTo("task.execute");
        assertThat(taskSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(taskSpan.getAttributes().get(OTelAttributes.TASK_DESCRIPTION))
                .isEqualTo("Summarize report");
        assertThat(taskSpan.getAttributes().get(OTelAttributes.AGENT_ROLE)).isEqualTo("analyst");
        assertThat(taskSpan.getAttributes().get(OTelAttributes.TASK_INDEX)).isEqualTo(1L);
        assertThat(taskSpan.getAttributes().get(OTelAttributes.DURATION_MS)).isEqualTo(1200L);
    }

    @Test
    void taskFailed_setsErrorStatus() {
        listener.onTaskStart(new TaskStartEvent("Process data", "worker", 2, 5));
        listener.onTaskFailed(new TaskFailedEvent(
                "Process data", "worker", new RuntimeException("timeout"), Duration.ofMillis(500), 2, 5));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData failedSpan = spans.get(0);
        assertThat(failedSpan.getName()).isEqualTo("task.execute");
        assertThat(failedSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(failedSpan.getStatus().getDescription()).isEqualTo("timeout");
    }

    @Test
    void toolCall_createsAndEndsSpanImmediately() {
        listener.onToolCall(
                new ToolCallEvent("calculator", "{\"a\":1}", "2", null, "math-agent", Duration.ofMillis(50)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData toolSpan = spans.get(0);
        assertThat(toolSpan.getName()).isEqualTo("tool.execute");
        assertThat(toolSpan.getAttributes().get(OTelAttributes.TOOL_NAME)).isEqualTo("calculator");
        assertThat(toolSpan.getAttributes().get(OTelAttributes.AGENT_ROLE)).isEqualTo("math-agent");
        assertThat(toolSpan.getAttributes().get(OTelAttributes.DURATION_MS)).isEqualTo(50L);
    }

    @Test
    void delegationStartedAndCompleted_createsClientSpan() {
        listener.onDelegationStarted(new DelegationStartedEvent("del-1", "manager", "worker", "Sub-task A", 1, null));
        listener.onDelegationCompleted(
                new DelegationCompletedEvent("del-1", "manager", "worker", null, Duration.ofMillis(3000)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData delegationSpan = spans.get(0);
        assertThat(delegationSpan.getName()).isEqualTo("network.delegate");
        assertThat(delegationSpan.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(delegationSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
        assertThat(delegationSpan.getAttributes().get(OTelAttributes.DELEGATION_TARGET))
                .isEqualTo("worker");
        assertThat(delegationSpan.getAttributes().get(OTelAttributes.DURATION_MS))
                .isEqualTo(3000L);
    }

    @Test
    void delegationFailed_setsErrorStatus() {
        listener.onDelegationStarted(new DelegationStartedEvent("del-2", "manager", "worker", "Sub-task B", 1, null));
        listener.onDelegationFailed(new DelegationFailedEvent(
                "del-2", "manager", "worker", "agent unavailable", null, null, Duration.ofMillis(100)));

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);

        SpanData failedSpan = spans.get(0);
        assertThat(failedSpan.getName()).isEqualTo("network.delegate");
        assertThat(failedSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(failedSpan.getStatus().getDescription()).isEqualTo("agent unavailable");
    }

    @Test
    void ensembleCompleted_withDurationMs_attribute() {
        listener.onEnsembleStarted("ens-dur", "PARALLEL", 2);
        listener.onEnsembleCompleted("ens-dur", Duration.ofMillis(7500), "COMPLETED");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getAttributes().get(OTelAttributes.DURATION_MS)).isEqualTo(7500L);
    }

    @Test
    void multipleSpanTypes_createdCorrectly() {
        // Simulate a full run with ensemble, task, tool, and delegation
        listener.onEnsembleStarted("ens-full", "SEQUENTIAL", 2);
        listener.onTaskStart(new TaskStartEvent("Task 1", "agent-a", 1, 2));
        listener.onToolCall(new ToolCallEvent("search", "{}", "found", null, "agent-a", Duration.ofMillis(100)));
        listener.onTaskComplete(new TaskCompleteEvent("Task 1", "agent-a", null, Duration.ofMillis(500), 1, 2));
        listener.onDelegationStarted(new DelegationStartedEvent("del-x", "agent-a", "agent-b", "Task 2", 1, null));
        listener.onDelegationCompleted(
                new DelegationCompletedEvent("del-x", "agent-a", "agent-b", null, Duration.ofMillis(800)));
        listener.onEnsembleCompleted("ens-full", Duration.ofMillis(1500), "COMPLETED");

        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(4); // tool, task, delegation, ensemble

        assertThat(spans)
                .extracting(SpanData::getName)
                .containsExactly("tool.execute", "task.execute", "network.delegate", "ensemble.run");
    }
}
