package net.agentensemble.delegation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.langchain4j.model.chat.ChatModel;
import java.util.List;
import net.agentensemble.Agent;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.execution.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DelegationContextTest {

    private ChatModel model;
    private Agent agentA;
    private Agent agentB;
    private AgentExecutor executor;
    private ExecutionContext executionContext;
    private ExecutionContext verboseContext;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        agentA = Agent.builder()
                .role("Researcher")
                .goal("Research things")
                .llm(model)
                .build();
        agentB = Agent.builder().role("Writer").goal("Write things").llm(model).build();
        executor = mock(AgentExecutor.class);
        executionContext = ExecutionContext.disabled();
        verboseContext = ExecutionContext.of(executionContext.memoryContext(), true);
    }

    // ========================
    // create()
    // ========================

    @Test
    void create_setsAllFields() {
        List<Agent> peers = List.of(agentA, agentB);
        DelegationContext ctx = DelegationContext.create(peers, 3, executionContext, executor);

        assertThat(ctx.getPeerAgents()).containsExactly(agentA, agentB);
        assertThat(ctx.getMaxDepth()).isEqualTo(3);
        assertThat(ctx.getCurrentDepth()).isEqualTo(0);
        assertThat(ctx.getExecutionContext()).isSameAs(executionContext);
        assertThat(ctx.getAgentExecutor()).isSameAs(executor);
    }

    @Test
    void create_withVerboseContext_executionContextIsVerbose() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 2, verboseContext, executor);
        assertThat(ctx.getExecutionContext().isVerbose()).isTrue();
    }

    @Test
    void create_copysPeerList_immutably() {
        List<Agent> peers = List.of(agentA);
        DelegationContext ctx = DelegationContext.create(peers, 2, executionContext, executor);
        assertThat(ctx.getPeerAgents()).containsExactly(agentA);
    }

    @Test
    void create_throwsWhenPeerListIsNull() {
        assertThatThrownBy(() -> DelegationContext.create(null, 3, executionContext, executor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peerAgents");
    }

    @Test
    void create_throwsWhenExecutorIsNull() {
        assertThatThrownBy(() -> DelegationContext.create(List.of(agentA), 3, executionContext, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentExecutor");
    }

    @Test
    void create_throwsWhenExecutionContextIsNull() {
        assertThatThrownBy(() -> DelegationContext.create(List.of(agentA), 3, null, executor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionContext");
    }

    @Test
    void create_throwsWhenMaxDepthIsZero() {
        assertThatThrownBy(() -> DelegationContext.create(List.of(agentA), 0, executionContext, executor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    @Test
    void create_throwsWhenMaxDepthIsNegative() {
        assertThatThrownBy(() -> DelegationContext.create(List.of(agentA), -1, executionContext, executor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    // ========================
    // isAtLimit()
    // ========================

    @Test
    void isAtLimit_falseWhenDepthBelowMax() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 3, executionContext, executor);
        assertThat(ctx.isAtLimit()).isFalse();
    }

    @Test
    void isAtLimit_trueWhenDepthEqualsMax() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 1, executionContext, executor);
        DelegationContext descended = ctx.descend();
        assertThat(descended.isAtLimit()).isTrue();
    }

    @Test
    void isAtLimit_trueWhenDepthExceedsMax() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 1, executionContext, executor);
        DelegationContext descended = ctx.descend();
        // Another descend beyond limit still reports at limit (depth = 2, max = 1)
        DelegationContext deeper = descended.descend();
        assertThat(deeper.isAtLimit()).isTrue();
    }

    // ========================
    // descend()
    // ========================

    @Test
    void descend_incrementsDepthByOne() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 3, executionContext, executor);
        DelegationContext child = ctx.descend();

        assertThat(child.getCurrentDepth()).isEqualTo(1);
        assertThat(ctx.getCurrentDepth()).isEqualTo(0); // original unchanged
    }

    @Test
    void descend_preservesAllOtherFields() {
        List<Agent> peers = List.of(agentA, agentB);
        DelegationContext ctx = DelegationContext.create(peers, 5, verboseContext, executor);
        DelegationContext child = ctx.descend();

        assertThat(child.getPeerAgents()).containsExactlyElementsOf(peers);
        assertThat(child.getMaxDepth()).isEqualTo(5);
        assertThat(child.getExecutionContext()).isSameAs(verboseContext);
        assertThat(child.getAgentExecutor()).isSameAs(executor);
        assertThat(child.getExecutionContext().isVerbose()).isTrue();
    }

    @Test
    void descend_chainedThreeTimes_depthIsThree() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 5, executionContext, executor);
        DelegationContext three = ctx.descend().descend().descend();
        assertThat(three.getCurrentDepth()).isEqualTo(3);
    }
}
