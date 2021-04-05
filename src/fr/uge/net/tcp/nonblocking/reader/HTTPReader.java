package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.reader.HTTPPacket.HTTPPacketType.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.moveData;
import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

public class HTTPReader implements Reader<HTTPPacket> {
    private final HTTPLineReader reader = new HTTPLineReader();
    private String contentType = "application/octet-stream";
    private boolean contentReading = false;
    private ProcessStatus status = REFILL;
    private boolean typeFound = false;
    private HTTPPacket packet = null;
    private ByteBuffer buff = null;

    /**
     * Processes the buffer and read every lines until an HTTP component can be created.
     * (i.e. if it's an HTTP response read until empty line and if it's an HTTP request read the first line only)
     * This method remembers what has been processed beforehand.
     *
     * The returned value can be :
     * <ul>
     *   <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL} :
     *     if the reader has not finished.</li>
     *   <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} :
     *     if the message is ready to be get.</li>
     *   <li>   {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR} :
     *     <ul>
     *       <li> If the first line doesn't starts with 'HTTP' nor 'GET'. </li>
     *       <li> If the first line starts with 'GET' but doesn't ends with 'HTTP/1.1'. </li>
     *       <li> If this is a response but the size is negative or above {@link fr.uge.net.tcp.nonblocking.Config#BUFFER_MAX_SIZE}. </li>
     *       <li> If this is a response but the size is not in the header (in the field "Content-Length"). </li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * @param bb the buffer where to read the data.
     *           Must be in write-mode before being called and will be kept in write-mode after.
     * @return the current state of this reader.
     * @throws NullPointerException if {@code bb} is null.
     * @throws IllegalStateException if the reader has already finished reading data.
     * (i.e. already returned {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE})
     */
    @Override
    public ProcessStatus process(ByteBuffer bb) {
        requireNonNull(bb);
        if (status != REFILL) throw new IllegalStateException("Not in REFILL mode!");
        status = subProcess(bb);

        return status;
    }
    private ProcessStatus subProcess(ByteBuffer bb) {
        if (!typeFound) {
            var status = readType(bb);
            if (status != DONE) return status;
            if (packet != null) return DONE;
        }
        if (!contentReading) {
            var status = readHeader(bb);
            if (status != DONE) return status;
        }
        return readContent(bb);
    }

    private ProcessStatus readType(ByteBuffer bb) {
        var status = reader.process(bb);
        if (status != DONE) return status;
        var msg = reader.get();
        if (msg.startsWith("HTTP")) {
            var tokens = msg.split(" ", 3);
            if (!tokens[1].equals("200")) packet = new HTTPPacket(BAD_RESPONSE, null, null, null);
        } else if (msg.startsWith("GET") && msg.endsWith("HTTP/1.1")) {
            packet = new HTTPPacket(REQUEST, null, null, msg.substring(3, msg.length() - 8).trim());
        } else {
            return ERROR;
        }
        reader.reset();
        typeFound = true;
        return DONE;
    }
    private ProcessStatus readHeader(ByteBuffer bb) {
        ProcessStatus status;
        while ((status = reader.process(bb)) == DONE) {
            var msg = reader.get();
            reader.reset();
            if (msg.isBlank()) {
                contentReading = true;
                break;
            }
            if (msg.startsWith("Content-Type:"))
                contentType = msg.substring(13).trim();
            if (msg.startsWith("Content-Length:")) {
                var size = parseInt(msg.substring(15).trim());
                if (size < 0 || size > BUFFER_MAX_SIZE) return ERROR;
                buff = ByteBuffer.allocate(size);
            }
        }
        return status;
    }
    private ProcessStatus readContent(ByteBuffer bb) {
        if (buff == null) return ERROR;
        moveData(bb.flip(), buff);
        bb.compact();
        if (buff.hasRemaining()) return REFILL;
        packet = new HTTPPacket(GOOD_RESPONSE, contentType, buff, null);
        return DONE;
    }
    @Override
    public HTTPPacket get() {
        if (status != DONE) throw new IllegalStateException("Not DONE!");
        if (packet.type() != GOOD_RESPONSE) return packet;
        return new HTTPPacket(GOOD_RESPONSE, contentType, copyBuffer(buff).flip(), null);
    }

    /**
     * Copy every data between 0 and {@code buff.limit()} inside a new buffer.
     * @param buff the buffer to copy. Will be the same before and after the call of this method.
     * @return the new buffer in write-mode. The size of this buffer is {@code buff.limit()}.
     */
    // TODO : move elsewhere
    public static ByteBuffer copyBuffer(ByteBuffer buff) {
        var bb = ByteBuffer.allocate(buff.limit());
        for (var i = 0; i < buff.limit(); i++) {
            bb.put(buff.get(i));
        }
        return bb;
    }

    @Override
    public void reset() {
        contentType = "application/octet-stream";
        contentReading = false;
        typeFound = false;
        status = REFILL;
        reader.reset();
        packet = null;
        buff = null;
    }
}
