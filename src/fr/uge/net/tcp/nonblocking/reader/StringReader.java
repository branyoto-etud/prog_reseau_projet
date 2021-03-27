package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static fr.uge.net.tcp.nonblocking.config.Config.BUFFER_SIZE;
import static java.util.Objects.requireNonNull;

/**
 * The reader can process a buffer to extract an integer (the {@code length} of the message)
 * followed by a string encoded in UTF8 with the given {@code length}.
 * Like so:
 * <pre>
 *   integer   string (utf8)
 * --------------------------
 * | length |     message   |
 * --------------------------</pre>
 * <br>
 * The {@code length} of the message cannot be greater than
 * {@link fr.uge.net.tcp.nonblocking.config.Config#BUFFER_SIZE}.
 *
 * The method {@link #process(ByteBuffer)} can be used to read a message until
 * it's return {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE}.
 * After that a call to {@link #get()} will return the processed message.
 * If you want to reuse this reader to process a new message you need to call {@link #reset()}.
 *
 * Use examples:
 * <blockquote><pre>
 * var buffer = ByteBuffer.allocate(9)
 *                        .putInt(5)
 *                        .put(
 *                            StandardCharsets.UTF_8.encode("hello")
 *                        );
 * var reader = new StringReader();
 * reader.process(buffer);              // Return ProcessStatus.DONE
 * var message = reader.get();          // message = "hello"
 * reader.reset();
 * buffer.clear().putInt(1);
 * reader.process(buffer);              // Return ProcessStatus.REFILL
 * buffer.clear().put((byte) 97);
 * reader.process(buffer);              // Return ProcessStatus.DONE
 * message = reader.get();              // message = "a"</pre></blockquote>
 */
public class StringReader implements Reader<String> {
    // buff is always in write-mode
    private final ByteBuffer buff = ByteBuffer.allocate(BUFFER_SIZE).limit(Integer.BYTES);
    private ProcessStatus state = ProcessStatus.REFILL;
    private String message = null;
    private int length = -1;

    /**
     * Process the buffer and extract the length (integer)
     * and the message (encoded in UTF8).
     * The returned value can be :
     * <ul><li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL} :
     *     if the reader has not finished.
     * <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR} :
     *     if the length of the message is not between 1 and {@link fr.uge.net.tcp.nonblocking.config.Config#BUFFER_SIZE}.
     * <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} :
     *     if the message if ready to be get.
     *
     * @param bb the buffer where to read the data.
     *           Must be in write-mode before and will be in write-mode after.
     * @return the current state of the reader.
     * @throws IllegalStateException if the buffer's state is not {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL}.
     * @throws NullPointerException if {@code bb} is null.
     */
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        requireNonNull(bb);
        if (state == ProcessStatus.DONE || state == ProcessStatus.ERROR) {
            throw new IllegalStateException();
        }
        bb.flip();                                      // Read-mode
        state = subProcess(bb);                         // aux method that have the correct argument
        bb.compact();                                   // Write-mode
        return state;
    }

    /**
     * Same thing has {@link #process(ByteBuffer)} but without the invalid argument and state.
     * This method does not update the state of the reader, it only return it.
     *
     * @param bb the buffer where to read the data. The buffer is in read mode before and after.
     * @return the current state of the reader.
     */
    private ProcessStatus subProcess(ByteBuffer bb) {
        if (length == -1) {                                                 // If the size has been read yet
            if (fillInnerBuffer(bb)) return ProcessStatus.REFILL;          // Try to fill buff and ask for refill if not full
            length = buff.flip().getInt();                                  // Get the length of the message
            if (length <= 0 || length > 1_024) return ProcessStatus.ERROR;  // Check if positive
            buff.clear().limit(length);                                     // Set the inner buffer limit
        }
        if (fillInnerBuffer(bb)) return ProcessStatus.REFILL;              // Try to fill buff and ask for refill if not full

        message = UTF_8.decode(buff.flip()).toString();                     // Read the found string
        buff.clear();                                                       // Reset buffer
        return ProcessStatus.DONE;                                          // Validate the process
    }

    /**
     * Fill {@link #buff} with the maximum that can be read from {@code bb}.
     *
     * @param bb the input buffer. Must be in read-mode.
     * @return {@code true} the buffer is not full.
     */
    private boolean fillInnerBuffer(ByteBuffer bb) {
        if (bb.remaining()<=buff.remaining()){          // If the inner buffer capacity is greater than given buffer
            buff.put(bb);                               // Read everything
        } else {                                        // Otherwise
            int oldLimit = bb.limit();                  // Remember the old limit
            bb.limit(bb.position() + buff.remaining()); // Set the new limit to the correct value
            buff.put(bb);                               // Read data
            bb.limit(oldLimit);                         // Reset to the old limit
        }
        return buff.hasRemaining();                     // Return true if the buffer is not full
    }

    /**
     * @return the message if the process method have successfully read a message.
     * @throws IllegalStateException if the process method haven't finished to read a message.
     */
    @Override
    public String get() {
        if (state != ProcessStatus.DONE){
            throw new IllegalStateException();
        }
        return message;
    }

    /**
     * Reset the reader to it's initial state.
     * (This is equivalent to create a new {@link StringReader} without the allocation of a {@link ByteBuffer})<br>
     * Note that the message will not be accessible with {@link #get()} until {@link #process(ByteBuffer)}
     * have returned {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE}.
     */
    @Override
    public void reset() {
        state = ProcessStatus.REFILL;
        buff.clear().limit(Integer.BYTES);
        length = -1;
    }
}
