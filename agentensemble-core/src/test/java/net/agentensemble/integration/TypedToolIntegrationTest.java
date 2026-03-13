package net.agentensemble.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import net.agentensemble.tool.AbstractTypedAgentTool;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.LangChain4jToolAdapter;
import net.agentensemble.tool.ToolInput;
import net.agentensemble.tool.ToolParam;
import net.agentensemble.tool.ToolResult;
import net.agentensemble.tool.TypedAgentTool;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that verify the full typed-tool pipeline from schema generation
 * through deserialization and execution dispatch via {@link LangChain4jToolAdapter}.
 *
 * <p>These tests exercise the collaboration of {@link TypedAgentTool},
 * {@link net.agentensemble.tool.ToolSchemaGenerator},
 * {@link net.agentensemble.tool.ToolInputDeserializer}, and
 * {@link LangChain4jToolAdapter} end-to-end using only the public API.
 */
class TypedToolIntegrationTest {

    // ========================
    // Test fixtures
    // ========================

    @ToolInput(description = "Parameters for the greeting tool")
    record GreetInput(
            @ToolParam(description = "Name to greet") String name,
            @ToolParam(description = "Language code", required = false) String language) {}

    static final class GreetingTool extends AbstractTypedAgentTool<GreetInput> {
        @Override
        public String name() {
            return "greet";
        }

        @Override
        public String description() {
            return "Greets a person by name.";
        }

        @Override
        public Class<GreetInput> inputType() {
            return GreetInput.class;
        }

        @Override
        public ToolResult execute(GreetInput input) {
            String lang = (input.language() != null) ? input.language() : "en";
            String greeting = "en".equals(lang) ? "Hello" : "Hola";
            return ToolResult.success(greeting + ", " + input.name() + "!");
        }
    }

    @ToolInput(description = "Counter parameters")
    record CountInput(
            @ToolParam(description = "Starting number") int start,
            @ToolParam(description = "Steps to count") int steps) {}

    static final class CounterTool extends AbstractTypedAgentTool<CountInput> {
        @Override
        public String name() {
            return "counter";
        }

        @Override
        public String description() {
            return "Counts from a starting number.";
        }

        @Override
        public Class<CountInput> inputType() {
            return CountInput.class;
        }

        @Override
        public ToolResult execute(CountInput input) {
            StringBuilder sb = new StringBuilder();
            for (int i = input.start(); i < input.start() + input.steps(); i++) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(i);
            }
            return ToolResult.success(sb.toString());
        }
    }

    // ========================
    // Schema generation
    // ========================

    @Test
    void toSpecification_typedTool_generatesMultiParamSchema() {
        ToolSpecification spec = LangChain4jToolAdapter.toSpecification(new GreetingTool());

        assertThat(spec.name()).isEqualTo("greet");
        assertThat(spec.description()).isEqualTo("Greets a person by name.");
        // Typed schema: individual named params, NOT single "input"
        assertThat(spec.parameters().properties()).containsKey("name");
        assertThat(spec.parameters().properties()).containsKey("language");
        assertThat(spec.parameters().properties()).doesNotContainKey("input");
    }

    @Test
    void toSpecification_typedTool_requiredFieldsCorrect() {
        ToolSpecification spec = LangChain4jToolAdapter.toSpecification(new GreetingTool());

        assertThat(spec.parameters().required()).contains("name");
        assertThat(spec.parameters().required()).doesNotContain("language");
    }

    @Test
    void toSpecification_integerFields_generatedAsIntegerSchema() {
        ToolSpecification spec = LangChain4jToolAdapter.toSpecification(new CounterTool());

        assertThat(spec.parameters().properties().get("start")).isInstanceOf(JsonIntegerSchema.class);
        assertThat(spec.parameters().properties().get("steps")).isInstanceOf(JsonIntegerSchema.class);
    }

    @Test
    void toSpecification_legacyTool_hasSingleInputSchema() {
        AgentTool legacy = mock(AgentTool.class);
        when(legacy.name()).thenReturn("legacy");
        when(legacy.description()).thenReturn("A legacy tool");

        ToolSpecification spec = LangChain4jToolAdapter.toSpecification(legacy);

        assertThat(spec.parameters().properties()).containsKey("input");
        assertThat(spec.parameters().properties()).doesNotContainKey("name");
        assertThat(spec.parameters().required()).contains("input");
    }

    // ========================
    // Execution routing
    // ========================

    @Test
    void executeForResult_typedTool_allFields_succeeds() {
        ToolResult result = LangChain4jToolAdapter.executeForResult(
                new GreetingTool(), "{\"name\": \"Alice\", \"language\": \"es\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Hola, Alice!");
    }

    @Test
    void executeForResult_typedTool_onlyRequiredFields_succeeds() {
        ToolResult result = LangChain4jToolAdapter.executeForResult(new GreetingTool(), "{\"name\": \"Bob\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Hello, Bob!");
    }

    @Test
    void executeForResult_typedTool_missingRequiredField_returnsFailure() {
        ToolResult result = LangChain4jToolAdapter.executeForResult(new GreetingTool(), "{\"language\": \"en\"}");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).containsIgnoringCase("name");
    }

    @Test
    void executeForResult_typedTool_integerFields_parsedCorrectly() {
        ToolResult result = LangChain4jToolAdapter.executeForResult(new CounterTool(), "{\"start\": 3, \"steps\": 4}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("3, 4, 5, 6");
    }

    @Test
    void executeForResult_typedTool_extraFieldsIgnored() {
        ToolResult result = LangChain4jToolAdapter.executeForResult(
                new GreetingTool(), "{\"name\": \"Charlie\", \"unknown\": \"ignored\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Hello, Charlie!");
    }

    @Test
    void executeForResult_typedTool_invalidJson_returnsFailure() {
        ToolResult result = LangChain4jToolAdapter.executeForResult(new GreetingTool(), "not valid json");

        assertThat(result.isSuccess()).isFalse();
    }

    // ========================
    // Legacy path backward compatibility
    // ========================

    @Test
    void executeForResult_legacyTool_extractsInputKeyFromJson() {
        AgentTool legacy = mock(AgentTool.class);
        when(legacy.name()).thenReturn("legacy");
        when(legacy.description()).thenReturn("Legacy");
        when(legacy.execute("hello world")).thenReturn(ToolResult.success("done"));

        ToolResult result = LangChain4jToolAdapter.executeForResult(legacy, "{\"input\": \"hello world\"}");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("done");
    }

    @Test
    void executeForResult_legacyTool_malformedJson_passesRawString() {
        AgentTool legacy = mock(AgentTool.class);
        when(legacy.name()).thenReturn("legacy");
        when(legacy.description()).thenReturn("Legacy");
        when(legacy.execute("raw")).thenReturn(ToolResult.success("raw result"));

        ToolResult result = LangChain4jToolAdapter.executeForResult(legacy, "raw");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("raw result");
    }

    // ========================
    // TypedAgentTool type identity
    // ========================

    @Test
    void greetingTool_isTypedAgentTool() {
        assertThat(new GreetingTool()).isInstanceOf(TypedAgentTool.class);
        assertThat(new GreetingTool()).isInstanceOf(AgentTool.class);
    }

    @Test
    void greetingTool_inputType_returnsCorrectClass() {
        assertThat(new GreetingTool().inputType()).isEqualTo(GreetInput.class);
    }
}
