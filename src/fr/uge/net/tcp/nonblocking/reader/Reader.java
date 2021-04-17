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
    /**
     * Current state of the reader.
     */
    enum ProcessStatus {
        /** Data are ready to be read */
        DONE,
        /** Need to process data again before Done */
        REFILL,
        /** If an error occurs during processing */
        ERROR
    }

    /**
     * Processes data from the buffer to create an object.
     * @param bb the buffer where to read data. Must be in write-mode.
     * @return the current state of the reader.
     */
    ProcessStatus process(ByteBuffer bb);

    /**
     * @return the processed element.
     */
    T get();

    /**
     * Reset the reader in it's initial state. Ready to read new data.
     */
    void reset();
}