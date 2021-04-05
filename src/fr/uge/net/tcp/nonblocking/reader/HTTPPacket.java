package fr.uge.net.tcp.nonblocking.reader;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.reader.HTTPPacket.HTTPPacketType.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * {@link #type} : type (not null) of the packet that can be either:
 * <ul>
 *  <li> {@link HTTPPacketType#REQUEST} -> in this case every fields are null except {@link #resource} who is the request.
 *  <li> {@link HTTPPacketType#BAD_RESPONSE} -> in this case every fields are null.
 *  <li> {@link HTTPPacketType#GOOD_RESPONSE} -> in this case {@link #contentType} contains the type of the content and
 *  {@link #content} contains the content of the HTTP response (the buffer is in read-mode).
 *  The {@link #resource} can be null if not wanted.
 */
public record HTTPPacket(HTTPPacketType type, String contentType, ByteBuffer content, String resource) {
    public static final String OTHER_CONTENT = "application/octet-stream";
    public static final String TEXT_CONTENT = "text/plain";
    public HTTPPacket {
        requireNonNull(type);
        switch (type) {
            case REQUEST -> requireNonNull(resource);
            case GOOD_RESPONSE -> {
                requireNonNull(contentType);
                requireNonNull(content);
            }
        }
    }
    public enum HTTPPacketType {REQUEST, GOOD_RESPONSE, BAD_RESPONSE}

    /**
     * ------------------------------------------------
     *                 FACTORY METHODS
     * ------------------------------------------------
     */

    public static HTTPPacket createBadResponse() {
        return new HTTPPacket(BAD_RESPONSE, null, null, null);
    }
    public static HTTPPacket createGoodResponse(String type, ByteBuffer content, String resource) {
        return new HTTPPacket(GOOD_RESPONSE, type, content, resource);
    }
    public static HTTPPacket createGoodResponse(String type, ByteBuffer content) {
        return new HTTPPacket(GOOD_RESPONSE, type, content, null);
    }
    public static HTTPPacket createRequest(String resource) {
        return new HTTPPacket(REQUEST, null, null, resource);
    }

    /**
     * @return this object as a buffer in read-mode.
     */
    public ByteBuffer toBuffer()  {
        return switch (type) {
            case REQUEST -> fromRequest();
            case GOOD_RESPONSE -> fromGoodResponse();
            case BAD_RESPONSE -> fromBadResponse();
        };
    }
    private ByteBuffer fromRequest() {
        return US_ASCII.encode("GET " + resource + "\r\n");
    }
    private ByteBuffer fromBadResponse() {
        return US_ASCII.encode("HTTP/1.1 404 NOT FOUND\r\n");
    }
    private ByteBuffer fromGoodResponse() {
        var header = US_ASCII.encode(
                "HTTP/1.1 200 OK\r\n"+
                "Content-Length: " + content.limit() + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                (resource == null ? "" : "Resource: " + resource + "\r\n") +
                "\r\n");
        return ByteBuffer.allocate(header.capacity() + content.limit())
                .put(header)
                .put(content)
                .flip();
    }
}