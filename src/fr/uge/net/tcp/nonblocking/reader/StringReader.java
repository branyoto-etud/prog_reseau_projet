package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static fr.uge.net.tcp.nonblocking.config.Config.BUFFER_SIZE;

/**
 * This reader process a buffer to extract an integer followed by
 * a string encoded in UTF8 with the length represented by the integer.
 * Like so:
 * <pre>
 *   integer   string (utf8)
 * --------------------------
 * | length |     message   |
 * --------------------------</pre>
 */
public class StringReader implements Reader<String> {
    private final ByteBuffer buff = ByteBuffer.allocate(BUFFER_SIZE).limit(Integer.BYTES);
    private ProcessStatus state = ProcessStatus.REFILL;
    private String message = null;
    private int size = -1;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == ProcessStatus.DONE || state == ProcessStatus.ERROR) {
            throw new IllegalStateException();
        }
        bb.flip();
        state = subProcess(bb);
        bb.compact();
        return state;
    }
    private ProcessStatus subProcess(ByteBuffer bb) {
        if (size == -1) {
            fillInnerBuffer(bb);
            if (buff.hasRemaining()) return ProcessStatus.REFILL;
            size = buff.flip().getInt();                                // Get an Integer
            if (size <= 0 || size > 1_024) return ProcessStatus.ERROR;  // Check if positive
            buff.clear().limit(size);                                   // Set the inner buffer limit
        }
        fillInnerBuffer(bb);
        if (buff.hasRemaining()) return ProcessStatus.REFILL;

        message = UTF_8.decode(buff.flip()).toString();
        return ProcessStatus.DONE;
    }

    private void fillInnerBuffer(ByteBuffer bb) {
        if (bb.remaining()<=buff.remaining()){          // If the inner buffer capacity is greater than given buffer
            buff.put(bb);
        } else {                                        // Unless
            int oldLimit = bb.limit();                  // Remember the old limit
            bb.limit(bb.position() + buff.remaining()); // Set the new limit to the correct value
            buff.put(bb);                               // Read data
            bb.limit(oldLimit);                         // Reset to the old limit
        }
    }
    @Override
    public String get() {
        if (state != ProcessStatus.DONE){
            throw new IllegalStateException();
        }
        return message;
    }

    @Override
    public void reset() {
        state = ProcessStatus.REFILL;
        buff.clear().limit(Integer.BYTES);
        size = -1;
        message = null; // Not necessary
    }
}
