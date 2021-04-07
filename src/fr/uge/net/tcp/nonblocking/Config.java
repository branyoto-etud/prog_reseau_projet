package fr.uge.net.tcp.nonblocking;

public class Config {
    /**
     * The maximum size of any text encoded in UTF-8
     * (i.e. pseudos, messages, resources ...)
     */
    public static final int TEXT_SIZE = 1_024;
    /**
     * The maximum size for all buffers in this project (= 2 ^ 15).
     */
    public static final int BUFFER_MAX_SIZE = 32_768;
    /**
     * The maximum size of the header in an HTTP Response.
     * (= 100 for the actual header + 900 to leave enough space for extensions)
     */
    public static final int HTTP_HEADER_MAX_SIZE = 1000;
    /**
     * Actual maximum size of the content in an HTTP Response.
     * Any content with a length bigger than that need to be slitted in 2 HTTP Responses.
     */
    public static final int CONTENT_MAX_SIZE = BUFFER_MAX_SIZE - HTTP_HEADER_MAX_SIZE;
}
