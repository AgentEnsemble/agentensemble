package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.junit.jupiter.api.Test;

/**
 * Verifies the lifecycle contract for {@link EnsembleDashboard} when integrated with
 * {@link Ensemble}.
 *
 * <p>The key invariant: when a dashboard is registered via
 * {@code Ensemble.builder().webDashboard(dashboard)}, the ensemble owns the dashboard's
 * lifecycle and must call {@link EnsembleDashboard#stop()} after the run completes --
 * whether successfully or with an exception. This prevents non-daemon server threads
 * (e.g. Javalin/Jetty) from blocking JVM exit after the ensemble task is done.
 *
 * <p>When the dashboard is wired manually (via {@code .listener()} and
 * {@code .reviewHandler()} directly), the ensemble does NOT own the lifecycle and must
 * NOT call {@code stop()} -- the caller is responsible for cleanup.
 */
class EnsembleDashboardLifecycleTest {

    // ========================
    // Auto-stop after successful run
    // ========================

    @Test
    void webDashboard_stopsAfterSuccessfulRun() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        when(dashboard.isRunning()).thenReturn(false);
        when(dashboard.streamingListener()).thenReturn(mock(EnsembleListener.class));

        Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .webDashboard(dashboard)
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        verify(dashboard).stop();
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
    // Manually-wired dashboard is NOT auto-stopped
    // ========================

    @Test
    void manuallyWiredDashboard_isNotAutoStopped() {
        var mockLlm = mock(ChatModel.class);
        when(mockLlm.chat(any(ChatRequest.class))).thenReturn(textResponse("Done."));

        EnsembleDashboard dashboard = mock(EnsembleDashboard.class);
        EnsembleListener noOpListener = mock(EnsembleListener.class);
        when(dashboard.streamingListener()).thenReturn(noOpListener);

        // Wire the listener directly -- not via webDashboard(), so ensemble.dashboard is null
        Ensemble.builder()
                .chatLanguageModel(mockLlm)
                .listener(noOpListener)
                .task(Task.of("Research AI trends"))
                .build()
                .run();

        // The ensemble must NOT call stop() -- the user manages the lifecycle
        verify(dashboard, never()).stop();
    }

    // ========================
    // Helper
    // ========================

    private ChatResponse textResponse(String text) {
        return ChatResponse.builder().aiMessage(new AiMessage(text)).build();
    }
}
