package net.agentensemble.network;

import static org.assertj.core.api.Assertions.assertThat;

import net.agentensemble.tool.ToolResult;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StubNetworkTask}.
 */
class StubNetworkTaskTest {

    @Test
    void name_returnsEnsembleDotTask() {
        StubNetworkTask stub = new StubNetworkTask("kitchen", "prepare-meal", "done");

        assertThat(stub.name()).isEqualTo("kitchen.prepare-meal");
    }

    @Test
    void description_mentionsEnsembleAndTask() {
        StubNetworkTask stub = new StubNetworkTask("kitchen", "prepare-meal", "done");

        assertThat(stub.description()).contains("ensemble").contains("kitchen");
        assertThat(stub.description()).contains("task").contains("prepare-meal");
    }

    @Test
    void execute_returnsCannedResponseAsSuccess() {
        StubNetworkTask stub = new StubNetworkTask("kitchen", "prepare-meal", "Meal ready in 25 min");

        ToolResult result = stub.execute("order wagyu steak");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Meal ready in 25 min");
    }

    @Test
    void execute_ignoresInput() {
        StubNetworkTask stub = new StubNetworkTask("kitchen", "prepare-meal", "canned");

        ToolResult first = stub.execute("input one");
        ToolResult second = stub.execute("input two");

        assertThat(first.getOutput()).isEqualTo("canned");
        assertThat(second.getOutput()).isEqualTo("canned");
    }

    @Test
    void ensembleName_returnsConfiguredValue() {
        StubNetworkTask stub = new StubNetworkTask("kitchen", "prepare-meal", "done");

        assertThat(stub.ensembleName()).isEqualTo("kitchen");
    }

    @Test
    void taskName_returnsConfiguredValue() {
        StubNetworkTask stub = new StubNetworkTask("kitchen", "prepare-meal", "done");

        assertThat(stub.taskName()).isEqualTo("prepare-meal");
    }

    @Test
    void cannedResponse_returnsConfiguredValue() {
        StubNetworkTask stub = new StubNetworkTask("kitchen", "prepare-meal", "Meal ready in 25 min");

        assertThat(stub.cannedResponse()).isEqualTo("Meal ready in 25 min");
    }

    @Test
    void networkTaskStubFactory_createsCorrectInstance() {
        StubNetworkTask stub = NetworkTask.stub("kitchen", "prepare-meal", "Meal ready in 25 min");

        assertThat(stub.ensembleName()).isEqualTo("kitchen");
        assertThat(stub.taskName()).isEqualTo("prepare-meal");
        assertThat(stub.cannedResponse()).isEqualTo("Meal ready in 25 min");
        assertThat(stub.name()).isEqualTo("kitchen.prepare-meal");

        ToolResult result = stub.execute("anything");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("Meal ready in 25 min");
    }
}
