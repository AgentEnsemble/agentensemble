package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import net.agentensemble.exception.AgentEnsembleException;
import org.junit.jupiter.api.Test;

class WorkspaceExceptionTest {

    @Test
    void messageOnly() {
        WorkspaceException ex = new WorkspaceException("something failed");
        assertThat(ex).hasMessage("something failed");
        assertThat(ex).hasNoCause();
        assertThat(ex).isInstanceOf(AgentEnsembleException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void messageAndCause() {
        Throwable cause = new RuntimeException("root cause");
        WorkspaceException ex = new WorkspaceException("wrapper", cause);
        assertThat(ex).hasMessage("wrapper");
        assertThat(ex).hasCause(cause);
    }
}
