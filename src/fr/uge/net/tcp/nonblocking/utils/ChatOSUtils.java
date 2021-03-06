package fr.uge.net.tcp.nonblocking.utils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;

import static java.util.Objects.requireNonNull;

/**
 * Utility class that define constants and methods used in the whole project.
 */
public class ChatOSUtils {
    /**
     * The maximum size of any text encoded in UTF-8
     * (i.e. pseudos, messages, resources ...)
     */
    public static final int TEXT_SIZE = 1_024;
    /**
     * The maximum size for all buffers in this project (= 2 ^ 15).
     */
    public static final int BUFFER_MAX_SIZE = 32_768;
    /**
     * The maximum size of the header in an HTTP Response.
     * (= 100 for the actual header + 900 to leave enough space for extensions)
     */
    public static final int HTTP_HEADER_MAX_SIZE = 1000;
    /**
     * Actual maximum size of the content in an HTTP Response.
     * Any content with a greater length than needed will be slitted in 2 HTTP Responses.
     */
    public static final int CONTENT_MAX_SIZE = BUFFER_MAX_SIZE - HTTP_HEADER_MAX_SIZE;
    /**
     * Copies every data between 0 and {@code buff.limit()} inside a new buffer.
     * @param buff the buffer to copy. Won't be modified during the call of this method.
     * @return the new buffer in read-mode. The size of this buffer is {@code buff.limit()}.
     */
    public static ByteBuffer copyBuffer(ByteBuffer buff) {
        var bb = ByteBuffer.allocate(buff.limit());
        for (var i = 0; i < buff.limit(); i++) {
            bb.put(buff.get(i));
        }
        return bb.flip();
    }
    /**
     * Moves data from {@code src} to {@code dest}.
     * The quantity of data taken from {@code src} depends on the remaining of each buffer.
     *
     * @param src the input buffer. Must be in read-mode. Cannot be null.
     * @param dest the output buffer. Must be in write-mode. Cannot be null.
     * @return {@code true} the output is not full.
     * @throws NullPointerException if at least one of the buffer is null.
     */
    public static boolean moveData(ByteBuffer src, ByteBuffer dest) {
        requireNonNull(src);
        requireNonNull(dest);
        // Reads everything if enough space
        if (src.remaining() <= dest.remaining()){
            dest.put(src);
        } else { // Reads only what can be read
            int oldLimit = src.limit();
            src.limit(src.position() + dest.remaining());
            dest.put(src);
            src.limit(oldLimit);
        }
        return dest.hasRemaining();
    }
    /**
     * Closes the channel if not already closed.
     * @param channel the channel to close.
     */
    public static void silentlyClose(Channel channel) {
        try {
            channel.close();
        } catch (IOException ignore) {
            // Ignore
        }
    }
}
