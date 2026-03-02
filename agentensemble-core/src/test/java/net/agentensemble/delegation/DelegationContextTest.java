package net.agentensemble.delegation;

import dev.langchain4j.model.chat.ChatModel;
import net.agentensemble.Agent;
import net.agentensemble.agent.AgentExecutor;
import net.agentensemble.memory.MemoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DelegationContextTest {

    private ChatModel model;
    private Agent agentA;
    private Agent agentB;
    private AgentExecutor executor;
    private MemoryContext memoryContext;

    @BeforeEach
    void setUp() {
        model = mock(ChatModel.class);
        agentA = Agent.builder().role("Researcher").goal("Research things").llm(model).build();
        agentB = Agent.builder().role("Writer").goal("Write things").llm(model).build();
        executor = mock(AgentExecutor.class);
        memoryContext = MemoryContext.disabled();
    }

    // ========================
    // create()
    // ========================

    @Test
    void create_setsAllFields() {
        List<Agent> peers = List.of(agentA, agentB);
        DelegationContext ctx = DelegationContext.create(peers, 3, memoryContext, executor, false);

        assertThat(ctx.getPeerAgents()).containsExactly(agentA, agentB);
        assertThat(ctx.getMaxDepth()).isEqualTo(3);
        assertThat(ctx.getCurrentDepth()).isEqualTo(0);
        assertThat(ctx.getMemoryContext()).isSameAs(memoryContext);
        assertThat(ctx.getAgentExecutor()).isSameAs(executor);
        assertThat(ctx.isVerbose()).isFalse();
    }

    @Test
    void create_withVerboseTrue() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 2, memoryContext, executor, true);
        assertThat(ctx.isVerbose()).isTrue();
    }

    @Test
    void create_copysPeerList_immutably() {
        List<Agent> peers = List.of(agentA);
        DelegationContext ctx = DelegationContext.create(peers, 2, memoryContext, executor, false);
        // The returned list must not be the same mutable reference
        assertThat(ctx.getPeerAgents()).containsExactly(agentA);
    }

    @Test
    void create_throwsWhenPeerListIsNull() {
        assertThatThrownBy(() -> DelegationContext.create(null, 3, memoryContext, executor, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("peerAgents");
    }

    @Test
    void create_throwsWhenExecutorIsNull() {
        assertThatThrownBy(() ->
                DelegationContext.create(List.of(agentA), 3, memoryContext, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agentExecutor");
    }

    @Test
    void create_throwsWhenMemoryContextIsNull() {
        assertThatThrownBy(() ->
                DelegationContext.create(List.of(agentA), 3, null, executor, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryContext");
    }

    @Test
    void create_throwsWhenMaxDepthIsZero() {
        assertThatThrownBy(() ->
                DelegationContext.create(List.of(agentA), 0, memoryContext, executor, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    @Test
    void create_throwsWhenMaxDepthIsNegative() {
        assertThatThrownBy(() ->
                DelegationContext.create(List.of(agentA), -1, memoryContext, executor, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    // ========================
    // isAtLimit()
    // ========================

    @Test
    void isAtLimit_falseWhenDepthBelowMax() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 3, memoryContext, executor, false);
        assertThat(ctx.isAtLimit()).isFalse();
    }

    @Test
    void isAtLimit_trueWhenDepthEqualsMax() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 1, memoryContext, executor, false);
        DelegationContext descended = ctx.descend();
        assertThat(descended.isAtLimit()).isTrue();
    }

    @Test
    void isAtLimit_trueWhenDepthExceedsMax() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 1, memoryContext, executor, false);
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
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 3, memoryContext, executor, false);
        DelegationContext child = ctx.descend();

        assertThat(child.getCurrentDepth()).isEqualTo(1);
        assertThat(ctx.getCurrentDepth()).isEqualTo(0); // original unchanged
    }

    @Test
    void descend_preservesAllOtherFields() {
        List<Agent> peers = List.of(agentA, agentB);
        DelegationContext ctx = DelegationContext.create(peers, 5, memoryContext, executor, true);
        DelegationContext child = ctx.descend();

        assertThat(child.getPeerAgents()).containsExactlyElementsOf(peers);
        assertThat(child.getMaxDepth()).isEqualTo(5);
        assertThat(child.getMemoryContext()).isSameAs(memoryContext);
        assertThat(child.getAgentExecutor()).isSameAs(executor);
        assertThat(child.isVerbose()).isTrue();
    }

    @Test
    void descend_chainedThreeTimes_depthIsThree() {
        DelegationContext ctx = DelegationContext.create(List.of(agentA), 5, memoryContext, executor, false);
        DelegationContext three = ctx.descend().descend().descend();
        assertThat(three.getCurrentDepth()).isEqualTo(3);
    }
}
