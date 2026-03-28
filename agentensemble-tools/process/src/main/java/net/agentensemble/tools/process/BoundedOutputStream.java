package net.agentensemble.tools.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * An {@link OutputStream} that captures up to a configured number of bytes.
 *
 * <p>Bytes written beyond the limit are silently discarded. When the output is
 * converted to a string via {@link #toString(Charset)}, a truncation marker is
 * appended if any bytes were discarded.
 *
 * <p>Not thread-safe — callers must synchronize externally if shared across threads.
 */
final class BoundedOutputStream extends OutputStream {

    private final ByteArrayOutputStream delegate;
    private final int maxBytes;
    private int count;
    private boolean truncated;

    /**
     * @param maxBytes the maximum number of bytes to retain; must be positive
     */
    BoundedOutputStream(int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.delegate = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        this.maxBytes = maxBytes;
    }

    @Override
    public void write(int b) throws IOException {
        if (count < maxBytes) {
            delegate.write(b);
        } else {
            truncated = true;
        }
        count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (count >= maxBytes) {
            truncated = true;
            count += len;
            return;
        }
        int remaining = maxBytes - count;
        if (len <= remaining) {
            delegate.write(b, off, len);
        } else {
            delegate.write(b, off, remaining);
            truncated = true;
        }
        count += len;
    }

    /**
     * Returns the captured output as a string, with a truncation marker appended
     * if the output exceeded the configured limit.
     */
    String toString(Charset charset) {
        String content = delegate.toString(charset);
        if (truncated) {
            return content + "\n[output truncated at " + maxBytes + " bytes]";
        }
        return content;
    }

    /** Returns {@code true} if any bytes were discarded. */
    boolean isTruncated() {
        return truncated;
    }

    /** Returns the total number of bytes written (including discarded bytes). */
    int totalBytesWritten() {
        return count;
    }
}
