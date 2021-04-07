package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.ChatOSUtils.copyBuffer;
import static fr.uge.net.tcp.nonblocking.ChatOSUtils.moveData;
import static fr.uge.net.tcp.nonblocking.Config.BUFFER_MAX_SIZE;
import static fr.uge.net.tcp.nonblocking.reader.HTTPPacket.HTTPPacketType.GOOD_RESPONSE;
import static fr.uge.net.tcp.nonblocking.reader.HTTPPacket.*;
import static fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus.*;
import static java.lang.Integer.parseInt;
import static java.util.Objects.requireNonNull;

public class HTTPReader implements Reader<HTTPPacket> {
    private final HTTPLineReader reader = new HTTPLineReader();
    private String contentType = OTHER_CONTENT;
    private boolean contentReading = false;
    private ProcessStatus status = REFILL;
    private boolean typeFound = false;
    private HTTPPacket packet = null;
    private ByteBuffer buff = null;
    private String resource = null;

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
        if (msg.startsWith("HTTP/1.1")) {
            var tokens = msg.split(" ", 3);
            if (!tokens[1].equals("200")) packet = createBadResponse();
        } else if (msg.startsWith("GET")) {
            packet = createRequest(msg.substring(3).trim());
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
            var line = reader.get();
            reader.reset();
            if (line.isBlank()) {
                contentReading = true;
                break;
            }
            if (line.startsWith("Content-Type:")) {
                contentType = line.substring(13).trim();
            } else if (line.startsWith("Resource:")) {
                resource = line.substring(9).trim();
            } else if (line.startsWith("Content-Length:")) {
                var size = parseInt(line.substring(15).trim());
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
        packet = createGoodResponse(contentType, buff, resource);
        return DONE;
    }
    @Override
    public HTTPPacket get() {
        if (status != DONE) throw new IllegalStateException("Not DONE!");
        if (packet.type() != GOOD_RESPONSE) return packet;
        return createGoodResponse(contentType, copyBuffer(buff), resource);
    }
    @Override
    public void reset() {
        contentType = OTHER_CONTENT;
        contentReading = false;
        typeFound = false;
        resource = null;
        status = REFILL;
        reader.reset();
        packet = null;
        buff = null;
    }
}
