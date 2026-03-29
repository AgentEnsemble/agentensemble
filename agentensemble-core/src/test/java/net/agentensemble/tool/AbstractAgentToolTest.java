package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import net.agentensemble.callback.EnsembleListener;
import net.agentensemble.callback.FileChangedEvent;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for AbstractAgentTool: template method, auto-metrics, exception safety,
 * ToolContext injection, and agentRole thread-local.
 */
class AbstractAgentToolTest {

    // ========================
    // Concrete test implementation
    // ========================

    static class EchoTool extends AbstractAgentTool {
        final List<String> executedInputs = new ArrayList<>();
        private ToolResult fixedResult = null;
        private boolean shouldThrow = false;

        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echoes input";
        }

        @Override
        protected ToolResult doExecute(String input) {
            executedInputs.add(input);
            if (shouldThrow) {
                throw new RuntimeException("doExecute exploded");
            }
            return fixedResult != null ? fixedResult : ToolResult.success("echo: " + input);
        }

        void setResult(ToolResult result) {
            this.fixedResult = result;
        }

        void setThrows(boolean throwOnExecute) {
            this.shouldThrow = throwOnExecute;
        }
    }

    // ========================
    // Template method: execute() delegates to doExecute()
    // ========================

    @Test
    void execute_delegatesToDoExecute() {
        var tool = new EchoTool();

        ToolResult result = tool.execute("hello");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("echo: hello");
        assertThat(tool.executedInputs).containsExactly("hello");
    }

    @Test
    void execute_withNullInput_passesNullToDoExecute() {
        var tool = new EchoTool();

        // Should not throw at the execute() level; doExecute handles or not
        ToolResult result = tool.execute(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(tool.executedInputs).containsExactly((String) null);
    }

    @Test
    void execute_isFinal_cannotBeOverridden_byDesign() throws Exception {
        // Verify via reflection that execute() is declared final
        var method = AbstractAgentTool.class.getDeclaredMethod("execute", String.class);
        assertThat(java.lang.reflect.Modifier.isFinal(method.getModifiers())).isTrue();
    }

    // ========================
    // Exception safety: doExecute throws -> ToolResult.failure
    // ========================

    @Test
    void execute_doExecuteThrows_returnsFailureResult() {
        var tool = new EchoTool();
        tool.setThrows(true);

        ToolResult result = tool.execute("input");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("doExecute exploded");
    }

    @Test
    void execute_doExecuteThrows_doesNotPropagateException() {
        var tool = new EchoTool();
        tool.setThrows(true);

        // execute() must never throw; exceptions are absorbed
        assertThat(tool.execute("any")).satisfies(r -> assertThat(r.isSuccess()).isFalse());
    }

    // ========================
    // Null return from doExecute treated as empty success
    // ========================

    @Test
    void execute_doExecuteReturnsNull_treatedAsEmptySuccess() {
        var tool = new EchoTool();
        tool.setResult(null); // doExecute now returns null
        // Override to force null return
        var nullTool = new AbstractAgentTool() {
            @Override
            public String name() {
                return "null_returner";
            }

            @Override
            public String description() {
                return "Returns null";
            }

            @Override
            protected ToolResult doExecute(String input) {
                return null;
            }
        };

        ToolResult result = nullTool.execute("test");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    // ========================
    // ToolContext injection
    // ========================

    @Test
    void setContext_null_throws() {
        var tool = new EchoTool();

        assertThatThrownBy(() -> ToolContextInjector.injectContext(tool, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolContext");
    }

    @Test
    void log_beforeContextInjection_usesClassLogger() {
        var tool = new EchoTool();

        // log() is accessible -- should not throw even before injection
        assertThat(tool.log()).isNotNull();
    }

    @Test
    void metrics_beforeContextInjection_usesNoOpMetrics() {
        var tool = new EchoTool();

        assertThat(tool.metrics()).isSameAs(NoOpToolMetrics.INSTANCE);
    }

    @Test
    void executor_beforeContextInjection_usesVirtualThreadExecutor() {
        var tool = new EchoTool();

        assertThat(tool.executor()).isNotNull();
    }

    @Test
    void afterContextInjection_log_usesInjectedLogger() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var ctx = ToolContext.of("echo", recorder, executor);
        ToolContextInjector.injectContext(tool, ctx);

        assertThat(tool.log()).isNotNull();
        assertThat(tool.log().getName()).isEqualTo("net.agentensemble.tool.echo");
    }

    @Test
    void afterContextInjection_metrics_usesInjectedMetrics() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var ctx = ToolContext.of("echo", recorder, executor);
        ToolContextInjector.injectContext(tool, ctx);

        assertThat(tool.metrics()).isSameAs(recorder);
    }

    // ========================
    // Auto-metrics recording
    // ========================

    @Test
    void execute_success_recordsSuccessMetricAndDuration() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor());
        ToolContextInjector.injectContext(tool, ctx);

        tool.execute("test");

        assertThat(recorder.successCalls).isEqualTo(1);
        assertThat(recorder.failureCalls).isEqualTo(0);
        assertThat(recorder.errorCalls).isEqualTo(0);
        assertThat(recorder.durations).hasSize(1);
        assertThat(recorder.durations.get(0)).isGreaterThanOrEqualTo(Duration.ZERO);
    }

    @Test
    void execute_failure_recordsFailureMetricAndDuration() {
        var tool = new EchoTool();
        tool.setResult(ToolResult.failure("bad input"));
        var recorder = new RecordingToolMetrics();
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor());
        ToolContextInjector.injectContext(tool, ctx);

        tool.execute("test");

        assertThat(recorder.successCalls).isEqualTo(0);
        assertThat(recorder.failureCalls).isEqualTo(1);
        assertThat(recorder.errorCalls).isEqualTo(0);
        assertThat(recorder.durations).hasSize(1);
    }

    @Test
    void execute_exceptionThrown_recordsErrorMetricAndDuration() {
        var tool = new EchoTool();
        tool.setThrows(true);
        var recorder = new RecordingToolMetrics();
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor());
        ToolContextInjector.injectContext(tool, ctx);

        tool.execute("test");

        assertThat(recorder.successCalls).isEqualTo(0);
        assertThat(recorder.failureCalls).isEqualTo(0);
        assertThat(recorder.errorCalls).isEqualTo(1);
        assertThat(recorder.durations).hasSize(1);
    }

    // ========================
    // Thread-local agentRole
    // ========================

    @Test
    void setCurrentAgentRole_null_clearsThreadLocal() {
        AbstractAgentTool.setCurrentAgentRole("Writer");
        AbstractAgentTool.setCurrentAgentRole(null);
        assertThat(AbstractAgentTool.CURRENT_AGENT_ROLE.get()).isNull();
    }

    @Test
    void clearCurrentAgentRole_removesValue() {
        AbstractAgentTool.setCurrentAgentRole("Researcher");
        AbstractAgentTool.clearCurrentAgentRole();
        assertThat(AbstractAgentTool.CURRENT_AGENT_ROLE.get()).isNull();
    }

    @Test
    void execute_withAgentRoleSet_recordsMetricsWithRole() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor());
        ToolContextInjector.injectContext(tool, ctx);

        AbstractAgentTool.setCurrentAgentRole("Researcher");
        try {
            tool.execute("input");
        } finally {
            AbstractAgentTool.clearCurrentAgentRole();
        }

        assertThat(recorder.lastAgentRole).isEqualTo("Researcher");
    }

    @Test
    void execute_withoutAgentRoleSet_usesUnknownRole() {
        AbstractAgentTool.clearCurrentAgentRole(); // ensure clean state
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor());
        ToolContextInjector.injectContext(tool, ctx);

        tool.execute("input");

        assertThat(recorder.lastAgentRole).isEqualTo(AbstractAgentTool.UNKNOWN_AGENT);
    }

    // ========================
    // fireFileChanged
    // ========================

    @Test
    void fireFileChanged_invokesListenerViaReflection() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        RecordingFileChangeListener listener = new RecordingFileChangeListener();
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor(), null, listener);
        ToolContextInjector.injectContext(tool, ctx);

        AbstractAgentTool.setCurrentAgentRole("Coder");
        try {
            tool.fireFileChanged("src/Main.java", "MODIFIED", 10, 3);
        } finally {
            AbstractAgentTool.clearCurrentAgentRole();
        }

        assertThat(listener.events).hasSize(1);
        FileChangedEvent event = listener.events.get(0);
        assertThat(event.agentRole()).isEqualTo("Coder");
        assertThat(event.filePath()).isEqualTo("src/Main.java");
        assertThat(event.changeType()).isEqualTo("MODIFIED");
        assertThat(event.linesAdded()).isEqualTo(10);
        assertThat(event.linesRemoved()).isEqualTo(3);
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void fireFileChanged_withNoAgentRole_usesUnknown() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        RecordingFileChangeListener listener = new RecordingFileChangeListener();
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor(), null, listener);
        ToolContextInjector.injectContext(tool, ctx);

        AbstractAgentTool.clearCurrentAgentRole(); // ensure no role set
        tool.fireFileChanged("file.txt", "CREATED", 1, 0);

        assertThat(listener.events).hasSize(1);
        assertThat(listener.events.get(0).agentRole()).isEqualTo(AbstractAgentTool.UNKNOWN_AGENT);
    }

    @Test
    void fireFileChanged_noListener_doesNotThrow() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        // No file change listener (null)
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor(), null, null);
        ToolContextInjector.injectContext(tool, ctx);

        // Should not throw
        tool.fireFileChanged("file.txt", "DELETED", 0, 5);
    }

    @Test
    void fireFileChanged_noContext_doesNotThrow() {
        var tool = new EchoTool();
        // No context injected at all
        tool.fireFileChanged("file.txt", "MODIFIED", 1, 1);
        // Should not throw
    }

    @Test
    void fireFileChanged_listenerThrows_doesNotPropagate() {
        var tool = new EchoTool();
        var recorder = new RecordingToolMetrics();
        EnsembleListener throwingListener = new EnsembleListener() {
            @Override
            public void onFileChanged(FileChangedEvent event) {
                throw new RuntimeException("listener boom");
            }
        };
        var ctx = ToolContext.of("echo", recorder, Executors.newVirtualThreadPerTaskExecutor(), null, throwingListener);
        ToolContextInjector.injectContext(tool, ctx);

        // Should not throw even when listener throws
        tool.fireFileChanged("file.txt", "MODIFIED", 1, 0);
    }

    /** Recording listener that implements EnsembleListener to capture FileChangedEvents. */
    static class RecordingFileChangeListener implements EnsembleListener {
        final List<FileChangedEvent> events = new ArrayList<>();

        @Override
        public void onFileChanged(FileChangedEvent event) {
            events.add(event);
        }
    }

    // ========================
    // Helper: recording ToolMetrics
    // ========================

    static class RecordingToolMetrics implements ToolMetrics {
        int successCalls = 0;
        int failureCalls = 0;
        int errorCalls = 0;
        final List<Duration> durations = new ArrayList<>();
        String lastAgentRole;

        @Override
        public void incrementSuccess(String toolName, String agentRole) {
            successCalls++;
            lastAgentRole = agentRole;
        }

        @Override
        public void incrementFailure(String toolName, String agentRole) {
            failureCalls++;
            lastAgentRole = agentRole;
        }

        @Override
        public void incrementError(String toolName, String agentRole) {
            errorCalls++;
            lastAgentRole = agentRole;
        }

        @Override
        public void recordDuration(String toolName, String agentRole, Duration duration) {
            durations.add(duration);
        }

        @Override
        public void incrementCounter(String metricName, String toolName, Map<String, String> tags) {}

        @Override
        public void recordValue(String metricName, String toolName, double value, Map<String, String> tags) {}
    }
}
