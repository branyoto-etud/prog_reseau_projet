package fr.uge.net.tcp.nonblocking.http;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.http.HTTPPacket.HTTPPacketType.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

/**
 * {@link #type} : type (not null) of the packet that can be either:
 * <ul>
 *  <li> {@link HTTPPacketType#REQUEST} -> in this case every fields are null except {@link #resource} which is the request.
 *  <li> {@link HTTPPacketType#BAD_RESPONSE} -> in this case every fields are null except {@link #resource} that can be not null.
 *  <li> {@link HTTPPacketType#GOOD_RESPONSE} -> in this case {@link #contentType} contains the type of the content and
 *  {@link #content} contains the content of the HTTP response (the buffer is in read-mode) and {@link #resource} contains
 *  the name of the received resource. None of the fields can be null.
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
                requireNonNull(resource);
                requireNonNull(content);
            }
        }
    }

    /**
     * Type of the HTTPPacket.
     */
    public enum HTTPPacketType {REQUEST, GOOD_RESPONSE, BAD_RESPONSE}


    // ------------------------------------------------
    //                 FACTORY METHODS
    // ------------------------------------------------

    /**
     * @param resource the name of the bad resource. Cannot be null.
     * @return a new {@link HTTPPacket} representing a bad response.
     */
    public static HTTPPacket createBadResponse(String resource) {
        return new HTTPPacket(BAD_RESPONSE, null, null, resource);
    }

    /**
     * @param type type of the content of this HTTP Response. Cannot be null.
     * @param content content of the HTTP Response. Cannot be null and must be in read-mode.
     * @param resource the name of the resource. Cannot be null.
     * @return a new {@link HTTPPacket} representing a good response with a named resource.
     */
    public static HTTPPacket createGoodResponse(String type, ByteBuffer content, String resource) {
        return new HTTPPacket(GOOD_RESPONSE, type, content, resource);
    }
    /**
     * @param resource the name of the resource. Cannot be null.
     * @return a new {@link HTTPPacket} representing a request of a resource.
     */
    public static HTTPPacket createRequest(String resource) {
        return new HTTPPacket(REQUEST, null, null, resource);
    }

    // ------------------------------------------------
    //               CONVERSION METHODS
    // ------------------------------------------------

    /**
     * @return a representation of this object as a buffer in read-mode.
     */
    public ByteBuffer toBuffer()  {
        return switch (type) {
            case REQUEST -> fromRequest();
            case GOOD_RESPONSE -> fromGoodResponse();
            case BAD_RESPONSE -> fromBadResponse();
        };
    }

    /**
     * @return a representation of this object as a buffer in read-mode. If the type is {@link HTTPPacketType#REQUEST}.
     */
    private ByteBuffer fromRequest() {
        return US_ASCII.encode("GET " + resource + "\r\n");
    }
    /**
     * @return a representation of this object as a buffer in read-mode. If the type is {@link HTTPPacketType#BAD_RESPONSE}.
     */
    private ByteBuffer fromBadResponse() {
        return US_ASCII.encode(
                "HTTP/1.1 404 NOT FOUND\r\n" +
                "Resource: " + resource + "\r\n" +
                "\r\n");
    }
    /**
     * @return a representation of this object as a buffer in read-mode. If the type is {@link HTTPPacketType#GOOD_RESPONSE}.
     */
    private ByteBuffer fromGoodResponse() {
        var header = US_ASCII.encode(
                "HTTP/1.1 200 OK\r\n"+
                "Content-Length: " + content.limit() + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Resource: " + resource + "\r\n" +
                "\r\n");
        return ByteBuffer.allocate(header.capacity() + content.limit())
                .put(header)
                .put(content)
                .flip();
    }
}