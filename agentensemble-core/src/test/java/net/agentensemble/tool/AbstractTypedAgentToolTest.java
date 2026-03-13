package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.agentensemble.exception.ExitEarlyException;
import net.agentensemble.exception.ToolConfigurationException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link AbstractTypedAgentTool}: the deserialization bridge,
 * exception handling, and type identity.
 */
class AbstractTypedAgentToolTest {

    @ToolInput(description = "Test input")
    record TestInput(@ToolParam(description = "The value") String value) {}

    private static final class EchoTool extends AbstractTypedAgentTool<TestInput> {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "Echoes the value";
        }

        @Override
        public Class<TestInput> inputType() {
            return TestInput.class;
        }

        @Override
        public ToolResult execute(TestInput input) {
            return ToolResult.success("ECHO: " + input.value());
        }
    }

    private static final class ThrowingTool extends AbstractTypedAgentTool<TestInput> {
        @Override
        public String name() {
            return "thrower";
        }

        @Override
        public String description() {
            return "Throws a runtime exception";
        }

        @Override
        public Class<TestInput> inputType() {
            return TestInput.class;
        }

        @Override
        public ToolResult execute(TestInput input) {
            throw new RuntimeException("deliberate tool error");
        }
    }

    private static final class ExitEarlyTool extends AbstractTypedAgentTool<TestInput> {
        @Override
        public String name() {
            return "exit_early";
        }

        @Override
        public String description() {
            return "Throws ExitEarlyException";
        }

        @Override
        public Class<TestInput> inputType() {
            return TestInput.class;
        }

        @Override
        public ToolResult execute(TestInput input) {
            throw new ExitEarlyException("exit signal");
        }
    }

    private static final class ConfigErrorTool extends AbstractTypedAgentTool<TestInput> {
        @Override
        public String name() {
            return "config_error";
        }

        @Override
        public String description() {
            return "Throws ToolConfigurationException";
        }

        @Override
        public Class<TestInput> inputType() {
            return TestInput.class;
        }

        @Override
        public ToolResult execute(TestInput input) {
            throw new ToolConfigurationException("bad config");
        }
    }

    // ========================
    // Happy path through String
    // ========================

    @Test
    void execute_validJson_deserializesAndCallsTypedExecute() {
        var tool = new EchoTool();
        var result = tool.execute("{\"value\": \"hello\"}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("ECHO: hello");
    }

    // ========================
    // Happy path through typed execute
    // ========================

    @Test
    void execute_typedInput_callsTypedExecuteDirectly() {
        var tool = new EchoTool();
        var result = tool.execute(new TestInput("direct"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("ECHO: direct");
    }

    // ========================
    // Deserialization errors -> ToolResult.failure
    // ========================

    @Test
    void execute_missingRequiredField_returnsFailure() {
        var tool = new EchoTool();
        var result = tool.execute("{}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("value");
    }

    @Test
    void execute_invalidJson_returnsFailure() {
        var tool = new EchoTool();
        var result = tool.execute("not valid json");
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_nullStringInput_returnsFailure() {
        var tool = new EchoTool();
        var result = tool.execute((String) null);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_emptyJsonObject_missingRequiredField_returnsFailure() {
        var tool = new EchoTool();
        var result = tool.execute("{}");
        assertThat(result.isSuccess()).isFalse();
    }

    // ========================
    // Exception handling
    // ========================

    @Test
    void execute_toolThrowsRuntimeException_returnsFailure() {
        var tool = new ThrowingTool();
        var result = tool.execute("{\"value\": \"x\"}");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("deliberate tool error");
    }

    @Test
    void execute_toolThrowsExitEarlyException_propagatesUnchanged() {
        var tool = new ExitEarlyTool();
        assertThatThrownBy(() -> tool.execute("{\"value\": \"x\"}"))
                .isInstanceOf(ExitEarlyException.class)
                .hasMessageContaining("exit signal");
    }

    @Test
    void execute_toolThrowsToolConfigurationException_propagatesUnchanged() {
        var tool = new ConfigErrorTool();
        assertThatThrownBy(() -> tool.execute("{\"value\": \"x\"}"))
                .isInstanceOf(ToolConfigurationException.class)
                .hasMessageContaining("bad config");
    }

    // ========================
    // Type identity
    // ========================

    @Test
    void echoTool_isInstanceOfTypedAgentTool() {
        var tool = new EchoTool();
        assertThat(tool).isInstanceOf(TypedAgentTool.class);
    }

    @Test
    void echoTool_isInstanceOfAgentTool() {
        var tool = new EchoTool();
        assertThat(tool).isInstanceOf(AgentTool.class);
    }

    @Test
    void echoTool_isInstanceOfAbstractAgentTool() {
        var tool = new EchoTool();
        assertThat(tool).isInstanceOf(AbstractAgentTool.class);
    }

    @Test
    void inputType_returnsCorrectClass() {
        var tool = new EchoTool();
        assertThat(tool.inputType()).isEqualTo(TestInput.class);
    }
}
