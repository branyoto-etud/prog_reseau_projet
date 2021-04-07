package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.ChatOSUtils.copyBuffer;
import static fr.uge.net.tcp.nonblocking.ChatOSUtils.moveData;
import static fr.uge.net.tcp.nonblocking.Config.CONTENT_MAX_SIZE;
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
     *       <li> If this is a response but the size is not in the header (i.e. there's no field "Content-Length"). </li>
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
            var status = readFirstLine(bb);
            if (status != REFILL) return status;
        }
        if (!contentReading) {
            var status = readHeader(bb);
            if (status != DONE) return status;
        }
        return readContent(bb);
    }

    /**
     * Reads the first line from the buffer.
     * And tries to create a packet from it. If successful return DONE otherwise
     * return REFILL or ERROR depending of the return of {@link #processFirstLine(String)}.
     *
     * @param bb buffer in write-mode.
     * @return the current state of the reader.
     */
    private ProcessStatus readFirstLine(ByteBuffer bb) {
        var status = reader.process(bb);
        if (status != DONE) return status;
        status = processFirstLine(reader.get());
        reader.reset();
        typeFound = true;
        return status;
    }

    /**
     * Processes the line and create a packet if the line contains enough data.
     * The returned value can be:
     * <ul>
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} :
     *     if the line starts with "HTTP/1.1" and with a code different of 200
     *     (i.e. a bad response) or if the line starts with "GET" (i.e. a request).
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL} :
     *     if the line starts with "HTTP/1.1" and the code 200.
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR} :
     *     if the line doesn't starts with "HTTP/1.1" nor "GET".
     * </ul>
     *
     * @param line the line to process.
     * @return the current state of the reader.
     */
    private ProcessStatus processFirstLine(String line) {
        if (line.startsWith("HTTP/1.1")) {
            var words = line.split(" ", 3);
            if (words[1].equals("200")) return REFILL;
            packet = createBadResponse();
            return DONE;
        }
        if (line.startsWith("GET")) {
            packet = createRequest(line.substring(3).trim());
            return DONE;
        }
        return ERROR;
    }

    /**
     * Reads lines from the buffer until blank line is read.
     * Return REFILL if the buffer is empty and no empty line has been read.<br>
     * Return ERROR if a line contains a field "Content-Length" with an incorrect value
     * (i.e. negative or above {@link fr.uge.net.tcp.nonblocking.Config#CONTENT_MAX_SIZE}).
     *
     * @param bb buffer in write-mode.
     * @return the current state of the reader.
     */
    private ProcessStatus readHeader(ByteBuffer bb) {
        ProcessStatus status;
        while ((status = reader.process(bb)) == DONE) {
            status = processHeaderLine(reader.get());
            reader.reset();
            if (status != DONE) continue;
            contentReading = true;
            return DONE;
        }
        return status;
    }

    /**
     * Processes the line extracted from a header and depending on the start of the string do an action.
     * <ul>
     *     <li> If the line starts with "Content-Type:" fill {@link #contentType}.
     *     <li> If the line starts with "Resource:" fill {@link #resource}.
     *     <li> If the line starts with "Content-Length:" assign {@link #buff}
     *     with a new buffer with the found length.
     * </ul>
     * The returned value can be:
     * <ul>
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} :
     *     if the line is blank.
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR} :
     *     if the line starts with "Content-Length:" but the content is incorrect
     *     (i.e. negative or above {@link fr.uge.net.tcp.nonblocking.Config#CONTENT_MAX_SIZE}).
     *     <li> {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#REFILL} :
     *     In any other case (even when the processing of the line has done something).
     * </ul>
     *
     * @param line the line to process.
     * @return the current state of the reader.
     */
    private ProcessStatus processHeaderLine(String line) {
        if (line.isBlank()) return DONE;
        if (line.startsWith("Content-Type:")) {
            contentType = line.substring(13).trim();
        } else if (line.startsWith("Resource:")) {
            resource = line.substring(9).trim();
        } else if (line.startsWith("Content-Length:")) {
            var size = parseInt(line.substring(15).trim());
            if (size < 0 || size > CONTENT_MAX_SIZE) return ERROR;
            buff = ByteBuffer.allocate(size);
        }
        return REFILL;
    }

    /**
     * Reads until {@link #buff} is full. If so, create a http good response packet.<br>
     * Can return {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#ERROR}
     * if {@link #buff} has not been assigned. This is caused by the missing of a field "Content-Length" in the header.
     *
     * @param bb buffer in write-mode.
     * @return the current state of the reader.
     */
    private ProcessStatus readContent(ByteBuffer bb) {
        if (buff == null) return ERROR;
        moveData(bb.flip(), buff);
        bb.compact();
        if (buff.hasRemaining()) return REFILL;
        packet = createGoodResponse(contentType, buff, resource);
        return DONE;
    }

    /**
     * @return the packet if the {@link #process(ByteBuffer)} method has successfully read a packet.
     * The returned packet has a new buffer for each call of this method.
     * @throws IllegalStateException if the process method hasn't finished to read a packet.
     */
    @Override
    public HTTPPacket get() {
        if (status != DONE) throw new IllegalStateException("Not DONE!");
        if (packet.type() != GOOD_RESPONSE) return packet;
        return createGoodResponse(contentType, copyBuffer(buff), resource);
    }

    /**
     * Resets the reader to its initial state.<br>
     * Note that the packet will not be accessible with {@link #get()} until {@link #process(ByteBuffer)}
     * have returned {@link fr.uge.net.tcp.nonblocking.reader.Reader.ProcessStatus#DONE} once again.
     */
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
