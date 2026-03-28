package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;

import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StubNetworkTool}.
 */
class StubNetworkToolTest {

    @Test
    void name_returnsEnsembleDotTool() {
        StubNetworkTool stub = new StubNetworkTool("kitchen", "check-inventory", "3 portions");

        assertThat(stub.name()).isEqualTo("kitchen.check-inventory");
    }

    @Test
    void description_mentionsEnsembleAndTool() {
        StubNetworkTool stub = new StubNetworkTool("kitchen", "check-inventory", "3 portions");

        assertThat(stub.description()).contains("ensemble").contains("kitchen");
        assertThat(stub.description()).contains("tool").contains("check-inventory");
    }

    @Test
    void execute_returnsCannedResultAsSuccess() {
        StubNetworkTool stub = new StubNetworkTool("kitchen", "check-inventory", "3 portions available");

        ToolResult result = stub.execute("wagyu");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("3 portions available");
    }

    @Test
    void execute_ignoresInput() {
        StubNetworkTool stub = new StubNetworkTool("kitchen", "check-inventory", "canned");

        ToolResult first = stub.execute("input one");
        ToolResult second = stub.execute("input two");

        assertThat(first.getOutput()).isEqualTo("canned");
        assertThat(second.getOutput()).isEqualTo("canned");
    }

    @Test
    void ensembleName_returnsConfiguredValue() {
        StubNetworkTool stub = new StubNetworkTool("kitchen", "check-inventory", "3 portions");

        assertThat(stub.ensembleName()).isEqualTo("kitchen");
    }

    @Test
    void toolName_returnsConfiguredValue() {
        StubNetworkTool stub = new StubNetworkTool("kitchen", "check-inventory", "3 portions");

        assertThat(stub.toolName()).isEqualTo("check-inventory");
    }

    @Test
    void cannedResult_returnsConfiguredValue() {
        StubNetworkTool stub = new StubNetworkTool("kitchen", "check-inventory", "3 portions available");

        assertThat(stub.cannedResult()).isEqualTo("3 portions available");
    }

    @Test
    void networkToolStubFactory_createsCorrectInstance() {
        StubNetworkTool stub = NetworkTool.stub("kitchen", "check-inventory", "3 portions available");

        assertThat(stub.ensembleName()).isEqualTo("kitchen");
        assertThat(stub.toolName()).isEqualTo("check-inventory");
        assertThat(stub.cannedResult()).isEqualTo("3 portions available");
        assertThat(stub.name()).isEqualTo("kitchen.check-inventory");

        ToolResult result = stub.execute("anything");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("3 portions available");
    }
}
