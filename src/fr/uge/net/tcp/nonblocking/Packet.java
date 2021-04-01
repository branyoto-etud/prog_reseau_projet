package fr.uge.net.tcp.nonblocking;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;
import static java.nio.charset.StandardCharsets.UTF_8;

public final record Packet(PacketType type, ErrorCode code, String message, String pseudo) {
    public static class PacketBuilder {
        /**
         * Create an error packet with the given error code.
         * @param code the error code.
         * @return a new error packet if {@code code} is different to {@link ErrorCode#REJECTED} and
         * between 0 and {@code ErrorCode.values().length} and null otherwise.
         */
        public static Packet makeErrorPacket(byte code) {
            if (code < 0 || code >= ErrorCode.values().length || code == ErrorCode.REJECTED.ordinal())
                return null;
            return new Packet(PacketType.ERR, ErrorCode.values()[code], null, null);
        }
        /**
         * Create an error packet with {@link ErrorCode#REJECTED} and the {@code pseudo}.
         * @param pseudo the name of the client that reject the private connection.
         * @return a new error packet with {@link ErrorCode#REJECTED} and the {@code pseudo}.
         */
        public static Packet makeRejectedPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.ERR, ErrorCode.REJECTED, null, pseudo);
        }
        /**
         * Create an error packet with the given error code.
         * @param code the error code.
         * @return a new error packet with the given code.
         */
        public static Packet makeErrorPacket(ErrorCode code) {
            return new Packet(PacketType.ERR, code, null, null);
        }
        /**
         * Create an authentication packet with the given pseudo.
         * @param pseudo the pseudo of the client.
         * @return a new authentication packet or null if {@code pseudo} is null.
         */
        public static Packet makeAuthenticationPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.AUTH, null, null, pseudo);
        }
        /**
         * Create a general message packet with the given message from {@code pseudo}.
         * Only send by the server.
         * @param message the message to send.
         * @param pseudo the pseudo of the sending client.
         * @return a new general message packet or null if {@code message} or {@code pseudo} is null.
         */
        public static Packet makeGeneralMessagePacket(String message, String pseudo) {
            if (message == null || pseudo == null) return null;
            return new Packet(PacketType.GMSG, null, message, pseudo);
        }
        /**
         * Create a direct message packet with the given message from/to {@code pseudo}.
         * The meaning of the pseudo depend on the receiver.
         * @param message the message to send.
         * @param pseudo the pseudo of the sending/receiving client.
         * @return a new general message packet or null if {@code message} or {@code pseudo} is null.
         */
        public static Packet makeDirectMessagePacket(String message, String pseudo) {
            if (message == null || pseudo == null) return null;
            return new Packet(PacketType.DMSG, null, message, pseudo);
        }
        /**
         * Create a private connection packet form/to {@code pseudo}.
         * The meaning of the pseudo depend on the receiver.
         * @param pseudo the pseudo of the sending/receiving client.
         * @return a new private connection packet or null if {@code pseudo} is null.
         */
        public static Packet makePrivateConnectionPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.CP, null, null, pseudo);
        }
        /**
         * Create a token packet containing an identification key and the pseudo
         * of the other client.
         * @param token the identification key.
         * @param pseudo the pseudo of the other client.
         * @return a new token packet or null if {@code pseudo} is null.
         */
        public static Packet makeTokenPacket(int token, String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.TOKEN, null, "" + token, pseudo);
        }
    }
    /**
     * Type of the different packets.
     */
    public enum PacketType {ERR, AUTH, GMSG, DMSG, CP, TOKEN}
    /**
     * Possible error codes.
     */
    public enum ErrorCode {AUTH_ERROR, DEST_ERROR, REJECTED, WRONG_CODE, INVALID_LENGTH, ERROR_RECOVER, OTHER}
    /**
     * Check if {@code length} is not bigger than {@link Config#TEXT_SIZE}.
     * @param length the length to check.
     * @return the same length.
     * @throws IllegalStateException if the length is bigger than {@link Config#TEXT_SIZE}.
     */
    private static int checkLength(int length) {
        if (length > Config.TEXT_SIZE) {
            throw new IllegalStateException("The message cannot be converted because the encoding of a string is bigger than " + Config.TEXT_SIZE);
        }
        return length;
    }
    /**
     * Create a buffer representing the packet that is sent according to the protocol CHATOS.
     *
     * @return a buffer in write-mode containing the message to be send.
     * The returned buffer is a new one for each call.
     * @throws IllegalStateException if any string encoded in UTF-8 is bigger than {@link Config#TEXT_SIZE}.
     */
    public ByteBuffer toBuffer() {
        return switch (type) {
            case ERR -> errToBuffer();
            case AUTH -> authToBuffer();
            case GMSG -> generalToBuffer();
            case DMSG -> directToBuffer();
            case CP -> privateToBuffer();
            case TOKEN -> tokenToBuffer();
        };
    }
    /**
     * Create a buffer containing the packet for an error.
     * <pre>
     *    byte      byte
     * ---------------------
     * |   0   | errorCode |
     * --------------------- </pre>
     *
     * @see ErrorCode
     */
    private ByteBuffer errToBuffer() {
        if (code != ErrorCode.REJECTED) return ByteBuffer.allocate(2).put((byte) type.ordinal()).put((byte) code.ordinal());

        var pseudoBuff = UTF_8.encode(pseudo);
        var length = checkLength(pseudoBuff.remaining());
        return ByteBuffer.allocate(Byte.BYTES * 2 + Integer.BYTES + length)
                .put((byte) type.ordinal())
                .put((byte) code.ordinal())
                .putInt(length)
                .put(pseudoBuff);
    }
    /**
     * Create a buffer containing the packet for an authentication.
     * <pre>
     *   byte  integer   string (utf-8)
     * ----------------------------------
     * |  1  | length |      pseudo     |
     * ---------------------------------- </pre>
     */
    private ByteBuffer authToBuffer() {
        var pseudoBuff = UTF_8.encode(pseudo);
        var length = checkLength(pseudoBuff.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + length)
                .put((byte) type.ordinal())
                .putInt(length)
                .put(pseudoBuff);
    }
    /**
     * Create a buffer containing the packet representing a message destined to everyone.
     * Can have two representation :
     * <ul>
     * <li>Client to Server
     * <pre>
     *   byte  integer   string (utf-8)
     * ----------------------------------
     * |  2  | length |     message     |
     * ---------------------------------- </pre>
     * <li>Server to Client
     * <pre>
     *   byte  integer   string (utf-8)   integer    string (utf-8)
     * -------------------------------------------------------------
     * |  2  | length |     message     | length |     pseudo      |
     * ------------------------------------------------------------- </pre>
     */
    private ByteBuffer generalToBuffer() {
        if (pseudo != null) return directToBuffer(); // When the server send the GMSG

        var messageBuffer = UTF_8.encode(message);
        var length = checkLength(messageBuffer.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + length)
                .put((byte) type.ordinal())
                .putInt(length)
                .put(messageBuffer);
    }
    /**
     * Create a buffer containing the packet representing a message destined to only one client.
     * In the case of a packet send by the server, the pseudo represent the source
     * and if the packet has been sent by the client, the pseudo represent the destination.
     *
     * <pre>
     *   byte  integer   string (utf-8)    integer   string (utf-8)
     * -------------------------------------------------------------
     * |  3  | length |     message     | length |     pseudo      |
     * ------------------------------------------------------------- </pre>
     */
    private ByteBuffer directToBuffer() {
        var messageBuffer = UTF_8.encode(message);
        var messageLength = checkLength(messageBuffer.remaining());
        var destBuffer = UTF_8.encode(pseudo);
        var destLength = checkLength(destBuffer.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES * 2 + messageLength + destLength)
                .put((byte) type.ordinal())
                .putInt(messageLength)
                .put(messageBuffer)
                .putInt(destLength)
                .put(destBuffer);
    }
    /**
     * Create a buffer containing the packet for a private connection.
     * In the case of a packet send by the server, the pseudo represent the source
     * and if the packet has been sent by the client, the pseudo represent the destination.
     * <pre>
     *   byte  integer   string (utf-8)
     * ----------------------------------
     * |  4  | length |      pseudo     |
     * ---------------------------------- </pre>
     */
    private ByteBuffer privateToBuffer() {
        var destBuffer = UTF_8.encode(pseudo);
        var length = checkLength(destBuffer.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + length)
                .put((byte) type.ordinal())
                .putInt(length)
                .put(destBuffer);
    }
    /**
     * Create a buffer containing the token for a private connection.
     * Only the server can send this type of packet.
     * <pre>
     *   byte  integer
     * -----------------
     * |  5  |  token  |
     * ----------------- </pre>
     */
    private ByteBuffer tokenToBuffer() {
        var pseudoBuffer = UTF_8.encode(pseudo);
        var length = checkLength(pseudoBuffer.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES * 2 + length)
                .put((byte) PacketType.TOKEN.ordinal())
                .putInt(Integer.parseInt(message))
                .putInt(length)
                .put(pseudoBuffer);
    }
    /**
     * Display the packet in the standard output.
     */
    public void display(String userName) {
        switch (type) {
            case ERR -> onErrorReceived(code, pseudo == null ? userName : pseudo);
            case AUTH -> onAuthSuccess(pseudo);
            case GMSG -> onGeneralMessageReceived(pseudo, message);
            case DMSG -> onDirectMessageReceived(pseudo, message);
            case CP -> onPrivateConnectionReceived(pseudo);
            case TOKEN -> System.out.println("The token is : " + message);
        }
    }
}
