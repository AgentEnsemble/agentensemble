package net.agentensemble.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryWorkspaceTest {

    @Test
    void createTemp_emptyWorkspace() {
        try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp()) {
            assertThat(ws.isActive()).isTrue();
            assertThat(ws.path()).isDirectory();
            assertThat(ws.id()).isNotBlank();
        }
    }

    @Test
    void createTemp_withSource_copiesFiles(@TempDir Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("file.txt"), "content");
        Files.createDirectories(sourceDir.resolve("sub"));
        Files.writeString(sourceDir.resolve("sub/nested.txt"), "nested");

        try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp(sourceDir)) {
            assertThat(ws.path().resolve("file.txt")).exists();
            assertThat(Files.readString(ws.path().resolve("file.txt"))).isEqualTo("content");
            assertThat(ws.path().resolve("sub/nested.txt")).exists();
            assertThat(Files.readString(ws.path().resolve("sub/nested.txt"))).isEqualTo("nested");
        }
    }

    @Test
    void createTemp_withSource_skipsGitDirectory(@TempDir Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("file.txt"), "content");
        Files.createDirectories(sourceDir.resolve(".git/objects"));
        Files.writeString(sourceDir.resolve(".git/HEAD"), "ref: refs/heads/main");

        try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp(sourceDir)) {
            assertThat(ws.path().resolve("file.txt")).exists();
            assertThat(ws.path().resolve(".git")).doesNotExist();
        }
    }

    @Test
    void close_deletesDirectory() {
        DirectoryWorkspace ws = DirectoryWorkspace.createTemp();
        Path wsPath = ws.path();
        assertThat(wsPath).isDirectory();

        ws.close();

        assertThat(ws.isActive()).isFalse();
        assertThat(wsPath).doesNotExist();
    }

    @Test
    void close_deletesNestedFiles(@TempDir Path sourceDir) throws Exception {
        Files.writeString(sourceDir.resolve("a.txt"), "a");
        Files.createDirectories(sourceDir.resolve("deep/nested/dir"));
        Files.writeString(sourceDir.resolve("deep/nested/dir/b.txt"), "b");

        DirectoryWorkspace ws = DirectoryWorkspace.createTemp(sourceDir);
        Path wsPath = ws.path();

        ws.close();

        assertThat(wsPath).doesNotExist();
    }

    @Test
    void close_isIdempotent() {
        DirectoryWorkspace ws = DirectoryWorkspace.createTemp();

        ws.close();
        ws.close(); // Should not throw

        assertThat(ws.isActive()).isFalse();
    }

    @Test
    void close_autoCleanupDisabled_doesNotDelete() {
        DirectoryWorkspace ws = DirectoryWorkspace.createTemp(null, false);
        Path wsPath = ws.path();

        ws.close();

        assertThat(ws.isActive()).isFalse();
        assertThat(wsPath).isDirectory();

        // Manual cleanup for the test
        wsPath.toFile().delete();
    }

    @Test
    void id_returnsDirectoryName() {
        try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp()) {
            assertThat(ws.id()).isEqualTo(ws.path().getFileName().toString());
        }
    }

    @Test
    void createTemp_withNullSource_createsEmptyWorkspace() {
        try (DirectoryWorkspace ws = DirectoryWorkspace.createTemp(null, true)) {
            assertThat(ws.isActive()).isTrue();
            assertThat(ws.path()).isDirectory();
        }
    }
}
