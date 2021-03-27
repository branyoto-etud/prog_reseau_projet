package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

/**
 * <p>A reader is an object that take a buffer (in write-mode) as input and
 * process it to obtain an element of type T.
 * <p>
 * The method {@link #process(ByteBuffer)} will return {@link ProcessStatus#REFILL} until
 * the reader has finished reading data.
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
}