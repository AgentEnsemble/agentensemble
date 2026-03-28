package net.agentensemble.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.file.Path;
import net.agentensemble.Task;
import net.agentensemble.ensemble.EnsembleOutput;
import net.agentensemble.workspace.WorkspaceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodingEnsembleTest {

    @TempDir
    Path tempDir;

    // ---- Validation: run() ----

    @Test
    void run_nullModel_throwsNpe() {
        Task task = CodingTask.fix("bug");
        assertThatThrownBy(() -> CodingEnsemble.run(null, tempDir, task))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("model");
    }

    @Test
    void run_nullWorkingDir_throwsNpe() {
        Task task = CodingTask.fix("bug");
        assertThatThrownBy(() -> CodingEnsemble.run(mock(ChatModel.class), null, task))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("workingDir");
    }

    @Test
    void run_noTasks_throwsIae() {
        assertThatThrownBy(() -> CodingEnsemble.run(mock(ChatModel.class), tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one task");
    }

    @Test
    void run_nullTask_throwsIae() {
        assertThatThrownBy(() -> CodingEnsemble.run(mock(ChatModel.class), tempDir, (Task) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tasks[0]");
    }

    @Test
    void run_withMockedModel_executesSuccessfully() {
        ChatModel model = mockChatModel("Done. Fixed the bug.");
        Task task = CodingTask.fix("NullPointerException in handler");

        EnsembleOutput output = CodingEnsemble.run(model, tempDir, task);

        assertThat(output).isNotNull();
        assertThat(output.getRaw()).contains("Fixed the bug");
    }

    @Test
    void run_multipleTasks_executesAll() {
        ChatModel model = mockChatModel("Task completed.");
        Task task1 = CodingTask.fix("bug one");
        Task task2 = CodingTask.implement("feature two");

        EnsembleOutput output = CodingEnsemble.run(model, tempDir, task1, task2);

        assertThat(output).isNotNull();
    }

    // ---- Validation: runIsolated() ----

    @Test
    void runIsolated_nullModel_throwsNpe() {
        Task task = CodingTask.fix("bug");
        assertThatThrownBy(() -> CodingEnsemble.runIsolated(null, tempDir, task))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("model");
    }

    @Test
    void runIsolated_nullRepoRoot_throwsNpe() {
        Task task = CodingTask.fix("bug");
        assertThatThrownBy(() -> CodingEnsemble.runIsolated(mock(ChatModel.class), null, task))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("repoRoot");
    }

    @Test
    void runIsolated_noTasks_throwsIae() {
        assertThatThrownBy(() -> CodingEnsemble.runIsolated(mock(ChatModel.class), tempDir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one task");
    }

    @Test
    void runIsolated_notGitRepo_throwsWorkspaceException() {
        // tempDir is not a git repo, so GitWorktreeProvider.of() should fail
        Task task = CodingTask.fix("bug");
        assertThatThrownBy(() -> CodingEnsemble.runIsolated(mock(ChatModel.class), tempDir, task))
                .isInstanceOf(WorkspaceException.class)
                .hasMessageContaining("Not a git repository");
    }

    // ---- ProjectContext constant ----

    @Test
    void projectContext_unknown_hasExpectedDefaults() {
        assertThat(ProjectContext.UNKNOWN.language()).isEqualTo("unknown");
        assertThat(ProjectContext.UNKNOWN.buildSystem()).isEqualTo("unknown");
        assertThat(ProjectContext.UNKNOWN.buildCommand()).isEmpty();
        assertThat(ProjectContext.UNKNOWN.testCommand()).isEmpty();
        assertThat(ProjectContext.UNKNOWN.sourceRoots()).isEmpty();
    }

    // ---- Helper ----

    private static ChatModel mockChatModel(String response) {
        ChatModel model = mock(ChatModel.class);
        ChatResponse chatResponse =
                ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        when(model.chat(any(ChatRequest.class))).thenReturn(chatResponse);
        return model;
    }
}
