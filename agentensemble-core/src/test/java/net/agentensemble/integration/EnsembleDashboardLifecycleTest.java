package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.agentensemble.Ensemble;
import net.agentensemble.Task;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.dashboard.EnsembleDashboard;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.trace.export.ExecutionTraceExporter;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Verifies the lifecycle contract for {@link EnsembleDashboard} when integrated with
 * {@link Ensemble}.
 *
 * <p>The key invariant: when a dashboard is registered via
 * {@code Ensemble.builder().webDashboard(dashboard)}, the ensemble owns the dashboard's
 * lifecycle for each run -- it starts the dashboard at the beginning and stops it in
 * the {@code finally} block, whether the run succeeds or throws. This prevents
 * non-daemon server threads (e.g. Javalin/Jetty) from blocking JVM exit.
 *
 * <p>When the dashboard is wired manually (via {@code .listener()} and
 * {@code .reviewHandler()} directly), the ensemble does NOT own the lifecycle and must
 * NOT call {@code stop()} -- the caller is responsible for cleanup.
 */
class EnsembleDashboardLifecycleTest {

    // ========================
    // Auto-start and auto-stop on a successful run
    // ========================

    @Test
    void webDashboard_startsAndStopsForEachRun() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard)
                .task(Task.of("Research AI trends"))
                .build();

        ensemble.run();

        // For the first run: builder calls start(), then runWithInputs() calls start()
        // (idempotent no-op), then finally calls stop().
        InOrder inOrder = inOrder(dashboard);
        inOrder.verify(dashboard).start(); // builder auto-start
        inOrder.verify(dashboard).stop(); // finally auto-stop
    }

    // ========================
    // Auto-stop even when run throws
    // ========================

    @Test
    void webDashboard_stopsEvenWhenRunThrows() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("LLM failure"));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        assertThatThrownBy(() -> Ensemble.builder()
                        .chatLanguageModel(mockLlm)
                        .webDashboard(dashboard)
                        .task(Task.of("Research AI trends"))
                        .build()
                        .run())
                .isInstanceOf(Exception.class);

        verify(dashboard).stop();
    }

    // ========================
    // Multiple sequential run() calls -- each restarts the dashboard
    // ========================

    @Test
    void webDashboard_restartsForEachSequentialRun() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        Ensemble ensemble = Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard)
                .task(Task.of("Research AI trends"))
                .build();

        ensemble.run();
        ensemble.run();

        // start() called once by builder + once per run() call in runWithInputs() = 3 total
        // stop() called once per run() = 2 total
        verify(dashboard, times(3)).start();
        verify(dashboard, times(2)).stop();
    }

    // ========================
    // stop() throws -- exception is swallowed, run result is unaffected
    // ========================

    @Test
    void webDashboard_stopThrows_runCompletesNormally() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));
        doThrow(new RuntimeException("Server shutdown error")).when(dashboard).stop();

        // stop() throws but the exception should be swallowed; run() must still return normally
        EnsembleOutput output = Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard)
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        assertThat(output).isNotNull();
        verify(dashboard).stop();
    }

    // ========================
    // start() throws -- exception is swallowed, run proceeds (and stop is still called)
    // ========================

    @Test
    void webDashboard_startThrowsInRunWithInputs_runProceedsAndStopCalled() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));
        // First start() call (from EnsembleBuilder.webDashboard()) succeeds.
        // Second start() call (from runWithInputs() at the top of the try block) throws --
        // this exercises the catch branch that logs and swallows the exception.
        doNothing()
                .doThrow(new RuntimeException("Port already in use"))
                .when(dashboard)
                .start();

        // The run should still complete (start failure in runWithInputs is logged, not propagated)
        EnsembleOutput output = Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard)
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        assertThat(output).isNotNull();
        // stop() is still called in the finally block
        verify(dashboard).stop();
    }

    // ========================
    // Manually-wired dashboard is NOT auto-stopped
    // ========================

    @Test
    void manuallyWiredDashboard_isNotAutoStopped() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        // Wire the dashboard's listener directly via listener() -- NOT via webDashboard().
        // The ensemble has no reference to the dashboard (dashboard field is null),
        // so it must NOT call stop() even though we used the dashboard's listener.
        Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .listener(dashboard.streamingListener())
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        // The ensemble must NOT call stop() -- the user manages the lifecycle
        verify(dashboard, never()).stop();
    }

    // ========================
    // Externally-started dashboard: caller retains lifecycle ownership
    // ========================

    @Test
    void webDashboard_externallyStarted_doesNotStartOrStopServer() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        // Dashboard is already running (caller started it externally before webDashboard())
        when(dashboard.isRunning()).thenReturn(true);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard)
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        // The ensemble must not call start() or stop() -- the caller owns the server lifecycle
        verify(dashboard, never()).start();
        verify(dashboard, never()).stop();
    }

    @Test
    void webDashboard_externallyStarted_stillReceivesEnsembleLifecycleHooks() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        // Dashboard is already running (caller started it externally)
        when(dashboard.isRunning()).thenReturn(true);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard)
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        // Even when lifecycle is caller-owned, the ensemble must still fire the
        // ensemble_started and ensemble_completed dashboard hooks so late-joining clients
        // (e.g. Playwright connecting after run() completes) receive full event history.
        verify(dashboard).onEnsembleStarted(any(), any(), anyInt(), any());
        verify(dashboard).onEnsembleCompleted(any(), any(), anyLong(), any(), anyLong(), anyInt());
    }

    // ========================
    // traceExporter auto-wiring via webDashboard
    // ========================

    @Test
    void webDashboard_autoWiresDashboardTraceExporterWhenNoExplicitExporterConfigured() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        // Dashboard provides a trace exporter (e.g. configured via traceExportDir)
        ExecutionTraceExporter dashboardExporter = mock(ExecutionTraceExporter.class);
        when(dashboard.traceExporter()).thenReturn(dashboardExporter);

        Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard) // auto-wires dashboardExporter since no explicit exporter set
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        // The dashboard's exporter must be used since no explicit traceExporter was set
        verify(dashboardExporter).export(any());
    }

    @Test
    void webDashboard_doesNotOverrideExplicitlyConfiguredTraceExporter() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        ExecutionTraceExporter dashboardExporter = mock(ExecutionTraceExporter.class);
        when(dashboard.traceExporter()).thenReturn(dashboardExporter);

        // An explicit exporter is set BEFORE webDashboard() -- it must not be overridden
        ExecutionTraceExporter explicitExporter = mock(ExecutionTraceExporter.class);

        Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .traceExporter(explicitExporter) // set before webDashboard
                .webDashboard(dashboard) // must not override explicit exporter
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        // The explicit exporter must be used; the dashboard's exporter must NOT be invoked
        verify(explicitExporter).export(any());
        verify(dashboardExporter, never()).export(any());
    }

    // ========================
    // Helper
    // ========================

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }
}
