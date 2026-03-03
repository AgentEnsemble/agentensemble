package net.agentensemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.exception.ValidationException;
import net.agentensemble.tool.AgentTool;
import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

class AgentTest {

    private final ChatModel mockLlm = mock(ChatModel.class);

    // ========================
    // Build success cases
    // ========================

    @Test
    void testBuild_withMinimalFields_succeeds() {
        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find information")
                .llm(mockLlm)
                .build();

        assertThat(agent.getRole()).isEqualTo("Researcher");
        assertThat(agent.getGoal()).isEqualTo("Find information");
        assertThat(agent.getLlm()).isSameAs(mockLlm);
    }

    @Test
    void testBuild_withAllFields_succeeds() {
        var tool = stubAgentTool("search");

        var agent = Agent.builder()
                .role("Senior Research Analyst")
                .goal("Find cutting-edge developments")
                .background("You are an expert researcher with 20 years of experience.")
                .tools(List.of(tool))
                .llm(mockLlm)
                .allowDelegation(true)
                .verbose(true)
                .maxIterations(10)
                .responseFormat("Respond in bullet points.")
                .build();

        assertThat(agent.getRole()).isEqualTo("Senior Research Analyst");
        assertThat(agent.getGoal()).isEqualTo("Find cutting-edge developments");
        assertThat(agent.getBackground()).isEqualTo("You are an expert researcher with 20 years of experience.");
        assertThat(agent.getTools()).hasSize(1);
        assertThat(agent.getLlm()).isSameAs(mockLlm);
        assertThat(agent.isAllowDelegation()).isTrue();
        assertThat(agent.isVerbose()).isTrue();
        assertThat(agent.getMaxIterations()).isEqualTo(10);
        assertThat(agent.getResponseFormat()).isEqualTo("Respond in bullet points.");
    }

    @Test
    void testDefaultValues_areCorrect() {
        var agent = Agent.builder()
                .role("Analyst")
                .goal("Analyze data")
                .llm(mockLlm)
                .build();

        assertThat(agent.getBackground()).isNull();
        assertThat(agent.getTools()).isEmpty();
        assertThat(agent.isAllowDelegation()).isFalse();
        assertThat(agent.isVerbose()).isFalse();
        assertThat(agent.getMaxIterations()).isEqualTo(25);
        assertThat(agent.getResponseFormat()).isEmpty();
    }

    @Test
    void testBuild_withAnnotatedToolObject_succeeds() {
        var annotatedTools = new AnnotatedToolObject();
        var agent = Agent.builder()
                .role("Analyst")
                .goal("Analyze data")
                .tools(List.of(annotatedTools))
                .llm(mockLlm)
                .build();

        assertThat(agent.getTools()).hasSize(1);
    }

    @Test
    void testBuild_withMixedTools_succeeds() {
        var agentTool = stubAgentTool("calculator");
        var annotatedTool = new AnnotatedToolObject();

        var agent = Agent.builder()
                .role("Analyst")
                .goal("Analyze data")
                .tools(List.of(agentTool, annotatedTool))
                .llm(mockLlm)
                .build();

        assertThat(agent.getTools()).hasSize(2);
    }

    // ========================
    // Validation: role
    // ========================

    @Test
    void testBuild_withNullRole_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role(null)
                        .goal("Find information")
                        .llm(mockLlm)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("role");
    }

    @Test
    void testBuild_withBlankRole_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role("   ")
                        .goal("Find information")
                        .llm(mockLlm)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("role");
    }

    @Test
    void testBuild_withEmptyRole_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role("")
                        .goal("Find information")
                        .llm(mockLlm)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("role");
    }

    // ========================
    // Validation: goal
    // ========================

    @Test
    void testBuild_withNullGoal_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role("Researcher")
                        .goal(null)
                        .llm(mockLlm)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("goal");
    }

    @Test
    void testBuild_withBlankGoal_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role("Researcher")
                        .goal("  ")
                        .llm(mockLlm)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("goal");
    }

    // ========================
    // Validation: llm
    // ========================

    @Test
    void testBuild_withNullLlm_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role("Researcher")
                        .goal("Find information")
                        .llm(null)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("LLM");
    }

    // ========================
    // Validation: maxIterations
    // ========================

    @Test
    void testBuild_withZeroMaxIterations_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role("Researcher")
                        .goal("Find information")
                        .llm(mockLlm)
                        .maxIterations(0)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    void testBuild_withNegativeMaxIterations_throwsValidation() {
        assertThatThrownBy(() -> Agent.builder()
                        .role("Researcher")
                        .goal("Find information")
                        .llm(mockLlm)
                        .maxIterations(-1)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    void testBuild_withMaxIterationsEqualToOne_succeeds() {
        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find information")
                .llm(mockLlm)
                .maxIterations(1)
                .build();

        assertThat(agent.getMaxIterations()).isEqualTo(1);
    }

    // ========================
    // Validation: tools
    // ========================

    @Test
    void testBuild_withInvalidToolObject_throwsValidation() {
        var invalidTool = new Object(); // neither AgentTool nor @Tool-annotated

        assertThatThrownBy(() -> Agent.builder()
                        .role("Researcher")
                        .goal("Find information")
                        .tools(List.of(invalidTool))
                        .llm(mockLlm)
                        .build())
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Object");
    }

    // ========================
    // Immutability
    // ========================

    @Test
    void testToolsList_isImmutable() {
        var agent = Agent.builder()
                .role("Researcher")
                .goal("Find information")
                .llm(mockLlm)
                .build();

        assertThat(agent.getTools()).isUnmodifiable();
    }

    // ========================
    // toBuilder
    // ========================

    @Test
    void testToBuilder_createsModifiedCopy() {
        var original = Agent.builder()
                .role("Researcher")
                .goal("Find information")
                .llm(mockLlm)
                .build();

        var modified = original.toBuilder().verbose(true).build();

        assertThat(modified.isVerbose()).isTrue();
        assertThat(original.isVerbose()).isFalse();
        assertThat(modified.getRole()).isEqualTo("Researcher");
        assertThat(modified.getLlm()).isSameAs(mockLlm);
    }

    // ========================
    // Helpers
    // ========================

    private static AgentTool stubAgentTool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "A test tool";
            }

            @Override
            public ToolResult execute(String input) {
                return ToolResult.success("ok");
            }
        };
    }

    /** A plain Java class with a LangChain4j @Tool-annotated method. */
    static class AnnotatedToolObject {
        @Tool("Search the web for information")
        public String search(String query) {
            return "results for: " + query;
        }
    }
}
