package fr.uge.net.tcp.nonblocking.reader;

import fr.uge.net.tcp.nonblocking.Config;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.ChatOSUtils.moveData;
import static fr.uge.net.tcp.nonblocking.Config.TEXT_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

/**
 * The reader can process a buffer to extract an integer (the {@code length} of the message)
 * followed by a string encoded in UTF-8 with the given {@code length}.
 * Like so:
 * <pre>
 *   integer   string (utf-8)
 * ----------------------------
 * | length |     message     |
 * ----------------------------</pre>
 * <br>
 * The {@code length} of the message cannot be greater than
 * {@link Config#TEXT_SIZE}.
 *
 * The method {@link #process(ByteBuffer)} can be used to read a message until
 * it returns {@link ProcessStatus#DONE}.
 * After that, a call to {@link #get()} will return the processed message.
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
    // content is always in write-mode
    private final ByteBuffer buff = ByteBuffer.allocate(TEXT_SIZE).limit(Integer.BYTES);
    private ProcessStatus state = ProcessStatus.REFILL;
    private String message = null;
    private int length = -1;

    /**
     * Processes the buffer and extracts the length (integer)
     * and the message (encoded in UTF-8).
     * This method remembers what has been processed beforehand.
     * The returned value can be :
     * <ul><li>   {@link ProcessStatus#REFILL} :
     *     if the reader has not finished.
     * <li>   {@link ProcessStatus#ERROR} :
     *     if the length of the message is not between 1 and {@link Config#TEXT_SIZE}.
     * <li>   {@link ProcessStatus#DONE} :
     *     if the message is ready to be get.
     *
     * @param bb the buffer where to read the data.
     *           Must be in write-mode before being called and will be kept in write-mode after.
     * @return the current state of the reader.
     * @throws IllegalStateException if the buffer's state is not {@link ProcessStatus#REFILL}.
     * @throws NullPointerException if {@code bb} is null.
     */
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        requireNonNull(bb);
        if (state == ProcessStatus.DONE || state == ProcessStatus.ERROR) {
            throw new IllegalStateException();
        }
        state = subProcess(bb.flip());
        bb.compact();
        return state;
    }

    /**
     * Same thing as {@link #process(ByteBuffer)} but without the invalid argument and state.
     * This method does not update the state of the reader, it only returns it.
     *
     * @param bb the buffer where to read the data. The buffer is in read-mode before and after.
     * @return the current state of the reader.
     */
    private ProcessStatus subProcess(ByteBuffer bb) {
        if (length == -1) {                                                 // If the size has been read yet
            if (moveData(bb, buff)) return ProcessStatus.REFILL;            // Tries to fill content and asks for refill if not full
            length = buff.flip().getInt();                                  // Gets the length of the message
            if (length <= 0 || length > 1_024) return ProcessStatus.ERROR;  // Checks if positive
            buff.clear().limit(length);                                     // Sets the inner buffer limit
        }
        if (moveData(bb, buff)) return ProcessStatus.REFILL;                // Tries to fill content and asks for refill if not full

        message = UTF_8.decode(buff.flip()).toString();                     // Reads the found string
        buff.clear();                                                       // Resets buffer
        return ProcessStatus.DONE;                                          // Validates the process
    }

    /**
     * @return the message if the process method has successfully read a message.
     * @throws IllegalStateException if the process method hasn't finished to read a message.
     */
    @Override
    public String get() {
        if (state == ProcessStatus.DONE) return message;
        throw new IllegalStateException();
    }

    /**
     * Resets the reader to its initial state.
     * (This is equivalent to create a new {@link StringReader} without the allocation of a {@link ByteBuffer})<br>
     * Note that the message will not be accessible with {@link #get()} until {@link #process(ByteBuffer)}
     * have returned {@link ProcessStatus#DONE} once again.
     */
    @Override
    public void reset() {
        state = ProcessStatus.REFILL;
        buff.clear().limit(Integer.BYTES);
        length = -1;
    }
}
