package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorkspaceConfigTest {

    @Test
    void builder_defaults() {
        WorkspaceConfig config = WorkspaceConfig.builder().build();

        assertThat(config.getNamePrefix()).isNull();
        assertThat(config.getBaseRef()).isEqualTo("HEAD");
        assertThat(config.isAutoCleanup()).isTrue();
        assertThat(config.getWorkspacesDir()).isNull();
    }

    @Test
    void builder_customValues() {
        Path customDir = Path.of("/tmp/workspaces");
        WorkspaceConfig config = WorkspaceConfig.builder()
                .namePrefix("fix")
                .baseRef("main")
                .autoCleanup(false)
                .workspacesDir(customDir)
                .build();

        assertThat(config.getNamePrefix()).isEqualTo("fix");
        assertThat(config.getBaseRef()).isEqualTo("main");
        assertThat(config.isAutoCleanup()).isFalse();
        assertThat(config.getWorkspacesDir()).isEqualTo(customDir);
    }

    @Test
    void equals_and_hashCode() {
        WorkspaceConfig a = WorkspaceConfig.builder().namePrefix("a").build();
        WorkspaceConfig b = WorkspaceConfig.builder().namePrefix("a").build();
        WorkspaceConfig c = WorkspaceConfig.builder().namePrefix("c").build();

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toString_containsFieldValues() {
        WorkspaceConfig config =
                WorkspaceConfig.builder().namePrefix("test").baseRef("develop").build();

        String str = config.toString();
        assertThat(str).contains("test");
        assertThat(str).contains("develop");
    }
}
