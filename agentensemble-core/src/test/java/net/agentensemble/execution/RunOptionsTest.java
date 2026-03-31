package net.agentensemble.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RunOptionsTest {

    @Test
    void defaultConstantHasNullOverrides() {
        assertThat(RunOptions.DEFAULT.getMaxToolOutputLength()).isNull();
        assertThat(RunOptions.DEFAULT.getToolLogTruncateLength()).isNull();
    }

    @Test
    void builderDefaultsToNull() {
        RunOptions opts = RunOptions.builder().build();
        assertThat(opts.getMaxToolOutputLength()).isNull();
        assertThat(opts.getToolLogTruncateLength()).isNull();
    }

    @Test
    void builderSetsMaxToolOutputLength() {
        RunOptions opts = RunOptions.builder().maxToolOutputLength(500).build();
        assertThat(opts.getMaxToolOutputLength()).isEqualTo(500);
    }

    @Test
    void builderSetsToolLogTruncateLength() {
        RunOptions opts = RunOptions.builder().toolLogTruncateLength(100).build();
        assertThat(opts.getToolLogTruncateLength()).isEqualTo(100);
    }

    @Test
    void builderSetsBothFields() {
        RunOptions opts = RunOptions.builder()
                .maxToolOutputLength(-1)
                .toolLogTruncateLength(200)
                .build();
        assertThat(opts.getMaxToolOutputLength()).isEqualTo(-1);
        assertThat(opts.getToolLogTruncateLength()).isEqualTo(200);
    }

    @Test
    void builderSetsUnlimitedValues() {
        RunOptions opts = RunOptions.builder()
                .maxToolOutputLength(-1)
                .toolLogTruncateLength(-1)
                .build();
        assertThat(opts.getMaxToolOutputLength()).isEqualTo(-1);
        assertThat(opts.getToolLogTruncateLength()).isEqualTo(-1);
    }

    @Test
    void builderSetsZeroLogLength() {
        // 0 = suppress log output content entirely
        RunOptions opts = RunOptions.builder().toolLogTruncateLength(0).build();
        assertThat(opts.getToolLogTruncateLength()).isEqualTo(0);
    }
}
