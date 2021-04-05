package fr.uge.net.tcp.nonblocking;

public class Config {
    public static final int TEXT_SIZE = 1_024;
    public static final int BUFFER_MAX_SIZE = 32_768; // 2^15
    public static final int HTTP_HEADER_MAX_SIZE = 1000; // 100 (mandatory) + 900 (enough space to avoid errors)
    public static final int CONTENT_MAX_SIZE = BUFFER_MAX_SIZE - HTTP_HEADER_MAX_SIZE;

    public static final boolean DEBUG_BUFFER = false;
    public static final boolean DEBUG_METHOD = false;
    public static final boolean DEBUG_CONNECTION = false;
}
