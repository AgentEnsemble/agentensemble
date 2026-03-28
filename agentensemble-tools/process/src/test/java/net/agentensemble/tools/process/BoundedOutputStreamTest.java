package net.agentensemble.tools.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BoundedOutputStream}.
 */
class BoundedOutputStreamTest {

    @Test
    void writesWithinLimit_capturedFully() throws IOException {
        BoundedOutputStream out = new BoundedOutputStream(100);
        out.write("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(out.isTruncated()).isFalse();
    }

    @Test
    void writesExceedingLimit_truncated() throws IOException {
        BoundedOutputStream out = new BoundedOutputStream(5);
        out.write("hello world".getBytes(StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hello\n[output truncated at 5 bytes]");
        assertThat(out.isTruncated()).isTrue();
    }

    @Test
    void writesAtExactLimit_notTruncated() throws IOException {
        BoundedOutputStream out = new BoundedOutputStream(5);
        out.write("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(out.isTruncated()).isFalse();
    }

    @Test
    void singleByteWrites_exceedLimit_truncated() throws IOException {
        BoundedOutputStream out = new BoundedOutputStream(3);
        for (byte b : "abcdef".getBytes(StandardCharsets.UTF_8)) {
            out.write(b);
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("abc\n[output truncated at 3 bytes]");
        assertThat(out.isTruncated()).isTrue();
        assertThat(out.totalBytesWritten()).isEqualTo(6);
    }

    @Test
    void multipleChunks_exceedLimit_truncated() throws IOException {
        BoundedOutputStream out = new BoundedOutputStream(10);
        out.write("12345".getBytes(StandardCharsets.UTF_8));
        out.write("678901234".getBytes(StandardCharsets.UTF_8));
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("1234567890\n[output truncated at 10 bytes]");
        assertThat(out.isTruncated()).isTrue();
    }

    @Test
    void constructor_nonPositive_throws() {
        assertThatThrownBy(() -> new BoundedOutputStream(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedOutputStream(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
