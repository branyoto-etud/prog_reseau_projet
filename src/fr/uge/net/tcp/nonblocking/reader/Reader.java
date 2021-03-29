package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

/**
 * <p>A reader is an object that take a buffer (in write-mode) as input and
 * process it to obtain an element of type T.
 * <p>
 * The method {@link #process(ByteBuffer)} will read from the buffer until it has completed his task.
 * In this case, it will return {@link ProcessStatus#DONE}.
 * But if the buffer doesn't contain enough data, the reader will conserve what has been read so far
 * and will return {@link ProcessStatus#REFILL}.
 * If {@link #process(ByteBuffer)} encounter an error the method will return {@link ProcessStatus#ERROR}.
 * The errors need to be explicitly specified in the implementations of {@link Reader}.
 * <p>
 * The method {@link #get()} can be used to obtain the processed element <b>only</b>
 * if {@link #process(ByteBuffer)} return {@link ProcessStatus#DONE}. Otherwise the behaviour is undefined.
 * Each call of the {@link #get()} method need to return the same value if no other method are called.
 * <p>
 * The method {@link #reset()} reset the {@link Reader} in it's initial state.
 *
 * @param <T> the type of the element this reader take care of.
 */
public interface Reader<T> {
    enum ProcessStatus {DONE,REFILL,ERROR}

    ProcessStatus process(ByteBuffer bb);
    T get();
    void reset();

    /**
     * Move data from {@code src} to {@code dest}.
     * The quantity of data taken from {@code src} depends on the remaining of each buffer.
     *
     * @param src the input buffer. Must be in read-mode.
     * @param dest the output buffer. Must be in write-mode.
     * @return {@code true} the output is not full.
     */
    static boolean moveData(ByteBuffer src, ByteBuffer dest) {
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
}