package fr.uge.net.tcp.nonblocking;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;

import static java.util.Objects.requireNonNull;

public class ChatOSUtils {
    /**
     * Copy every data between 0 and {@code buff.limit()} inside a new buffer.
     * @param buff the buffer to copy. Will be the same before and after the call of this method.
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
     * Move data from {@code src} to {@code dest}.
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
        if (src.remaining()<=dest.remaining()){             // If the output buffer capacity is greater than input buffer
            dest.put(src);                                  // Reads everything
        } else {                                            // Otherwise
            int oldLimit = src.limit();                     // Remembers the old limit
            src.limit(src.position() + dest.remaining());   // Sets the new limit to the correct value
            dest.put(src);                                  // Reads data
            src.limit(oldLimit);                            // Resets to the old limit
        }
        return dest.hasRemaining();                         // Returns true if the buffer is not full
    }
    public static void silentlyClose(Channel channel) {
        try {
            channel.close();
        } catch (IOException ignore) {
            // Ignore
        }
    }
}
