package net.agentensemble.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link DynamicToolProvider} interface contract.
 *
 * <p>Integration tests verifying that {@code ToolResolver} correctly expands
 * providers are in {@code ToolResolverTest}.
 */
class DynamicToolProviderTest {

    private static AgentTool mockAgentTool(String name) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.description()).thenReturn("A tool called " + name);
        return tool;
    }

    @Test
    void resolveReturnsSingleTool() {
        AgentTool tool = mockAgentTool("alpha");
        DynamicToolProvider provider = () -> List.of(tool);

        List<AgentTool> resolved = provider.resolve();

        assertThat(resolved).hasSize(1);
        assertThat(resolved.get(0).name()).isEqualTo("alpha");
    }

    @Test
    void resolveReturnsMultipleTools() {
        AgentTool toolA = mockAgentTool("alpha");
        AgentTool toolB = mockAgentTool("beta");
        DynamicToolProvider provider = () -> List.of(toolA, toolB);

        List<AgentTool> resolved = provider.resolve();

        assertThat(resolved).hasSize(2);
        assertThat(resolved).extracting(AgentTool::name).containsExactly("alpha", "beta");
    }

    @Test
    void emptyProviderReturnsNoTools() {
        DynamicToolProvider provider = List::of;

        List<AgentTool> resolved = provider.resolve();

        assertThat(resolved).isEmpty();
    }

    @Test
    void resolveCanReturnDifferentResultsPerInvocation() {
        AgentTool tool1 = mockAgentTool("first-call-tool");
        AgentTool tool2 = mockAgentTool("second-call-tool");

        // Simulate a provider whose available tools change between invocations
        var callCount = new int[] {0};
        DynamicToolProvider provider = () -> {
            callCount[0]++;
            return callCount[0] == 1 ? List.of(tool1) : List.of(tool2);
        };

        List<AgentTool> firstResolve = provider.resolve();
        assertThat(firstResolve).extracting(AgentTool::name).containsExactly("first-call-tool");

        List<AgentTool> secondResolve = provider.resolve();
        assertThat(secondResolve).extracting(AgentTool::name).containsExactly("second-call-tool");
    }

    @Test
    void providerIsNotAnAgentTool() {
        // DynamicToolProvider should not be treated as an AgentTool itself
        DynamicToolProvider provider = List::of;
        assertThat(provider).isNotInstanceOf(AgentTool.class);
    }
}
