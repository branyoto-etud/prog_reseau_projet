package fr.uge.net.tcp.nonblocking.http;

import fr.uge.net.tcp.nonblocking.reader.Reader;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.DONE;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.REFILL;
import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.BUFFER_MAX_SIZE;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * Reader used to read an HTTP line (i.e. all bytes until {@link #CR}{@link #LF} is read).
 */
public class HTTPLineReader implements Reader<String> {
    private static final byte CR = '\015';     // ASCII code for \r
    private static final byte LF = '\012';     // ASCII code for \n

    private final ByteBuffer buff = ByteBuffer.allocate(BUFFER_MAX_SIZE); // Always in write-mode
    private final StringBuilder lineBuilder = new StringBuilder();
    private ProcessStatus status = REFILL;
    private int pos = -1;

    /**
     * Processes the buffer and extracts data until the end of a line ({@link #CR}{@link #LF}).
     * This method remembers what has been processed beforehand.
     * The returned value can be :
     * <ul>
     *   <li>   {@link ProcessStatus#REFILL} : if the reader has not finished.</li>
     *   <li>   {@link ProcessStatus#ERROR} : never!</li>
     *   <li>   {@link ProcessStatus#DONE} : if the message is ready to be get.</li>
     * </ul>
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
        if (status != REFILL)
            throw new IllegalStateException("Reader not ready to process data.");
        if ((status = subProcess(bb.flip())) == DONE)
            lineBuilder.append(US_ASCII.decode(buff.flip().limit(pos)));
        bb.compact();
        return status;
    }

    /**
     * Actual loop that read each byte and check if the previous is {@link #CR} and the current {@link #LF}.
     * If the buffer has not enough space, the content of the buffer is decoded in
     * {@link java.nio.charset.StandardCharsets#US_ASCII} and put in {@link #lineBuilder}.
     *
     * @param bb buffer in read-mode.
     * @return the current status of the reader.
     */
    private ProcessStatus subProcess(ByteBuffer bb) {
        while (bb.hasRemaining()) {
            var c = bb.get();
            if (c == LF && pos != -1 && buff.get(pos) == CR) return DONE;
            buff.put(c);
            pos++;
            if (!buff.hasRemaining()) {
                lineBuilder.append(US_ASCII.decode(buff.flip().limit(pos)));
                buff.clear().put(buff.get(pos));
                pos = 0;
            }
        }
        return REFILL;
    }

    /**
     * @return the line if the process method has successfully read a line.
     * @throws IllegalStateException if the process method hasn't finished to read a line.
     */
    @Override
    public String get() {
        if (status == DONE) return lineBuilder.toString();
        throw new IllegalStateException("Reader not done!");
    }

    /**
     * Resets the reader to its initial state.<br>
     * Note that the message will not be accessible with {@link #get()} until {@link #process(ByteBuffer)}
     * have returned {@link ProcessStatus#DONE} once again.
     */
    @Override
    public void reset() {
        lineBuilder.setLength(0);
        status = REFILL;
        buff.clear();
        pos = -1;
    }
}
