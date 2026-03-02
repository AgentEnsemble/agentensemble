package net.agentensemble.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTest {

    @Test
    void testSuccess_withOutput() {
        var result = ToolResult.success("search results here");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo("search results here");
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    void testSuccess_withNullOutput_treatedAsEmpty() {
        var result = ToolResult.success(null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    void testSuccess_withEmptyOutput() {
        var result = ToolResult.success("");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEmpty();
    }

    @Test
    void testFailure_withMessage() {
        var result = ToolResult.failure("Connection refused");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getOutput()).isEmpty();
        assertThat(result.getErrorMessage()).isEqualTo("Connection refused");
    }
}
