package fr.uge.net.tcp.nonblocking;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Represents a packet circulating between the server and the clients.
 *
 * A packet is always starting with a {@link PacketType} that define it's meaning.
 * The other fields can be null if not useful with this type. The following list explain
 * what are the different types and what fields they use.
 * <ul>
 * <li> {@link PacketType#ERR} (code and pseudo) : This packet is to notify an incorrect comportment.
 * <li> {@link PacketType#AUTH} (pseudo) : This packet is used by the client to register to the server. The pseudo
 * is the unique identifier for this client.
 * <li> {@link PacketType#GMSG} (message and pseudo) : This packet is a General Message. A message from a client to everyone.
 * <li> {@link PacketType#DMSG} (message and pseudo) : This packet is a Direct Message. A message from a client to another.
 * <li> {@link PacketType#PC} (pseudo) : This packet is Private Connection. This is used to establish a connection between
 * two clients.
 * <li> {@link PacketType#TOKEN} (message, pseudo) : This packet is a special packet used to register to the server as a private
 * connection. The token is an unique identifier sent to the both end of a private connection.
 * </ul>
 * For more explanations see Protocol.txt.
 */
public final record Packet(PacketType type, ErrorCode code, String message, String pseudo) {
    /**
     * Utility class to create more easily {@link Packet}.
     * If one of the parameter is null, the returned packet will be null.
     */
    public static class PacketBuilder { // Todo : Maybe return Optional
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
         * Create an error packet with the given error code.
         * @param code the error code.
         * @return a new error packet with the given code. Or null if {@code code} is null.
         */
        public static Packet makeErrorPacket(ErrorCode code) {
            if (code == null) return null;
            return new Packet(PacketType.ERR, code, null, null);
        }
        /**
         * Create an error packet with {@link ErrorCode#REJECTED} and the {@code pseudo}.
         * @param pseudo the name of the client that reject the private connection.
         * @return a new error packet with {@link ErrorCode#REJECTED} and the {@code pseudo}.
         * Or null if {@code pseudo} is null.
         */
        public static Packet makeRejectedPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.ERR, ErrorCode.REJECTED, null, pseudo);
        }
        /**
         * Create an authentication packet with the given pseudo.
         * @param pseudo the pseudo of the client.
         * @return a new authentication packet. Or null if {@code pseudo} is null.
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
         * @return a new general message packet. Or null if {@code message} or {@code pseudo} is null.
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
         * @return a new general message packet. Or null if {@code message} or {@code pseudo} is null.
         */
        public static Packet makeDirectMessagePacket(String message, String pseudo) {
            if (message == null || pseudo == null) return null;
            return new Packet(PacketType.DMSG, null, message, pseudo);
        }
        /**
         * Create a private connection packet form/to {@code pseudo}.
         * The meaning of the pseudo depend on the receiver.
         * @param pseudo the pseudo of the sending/receiving client.
         * @return a new private connection packet. Or null if {@code pseudo} is null.
         */
        public static Packet makePrivateConnectionPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.PC, null, null, pseudo);
        }
        /**
         * Create a token packet containing an identification key and the pseudo
         * of the other client.
         * @param token the identification key.
         * @param pseudo the pseudo of the other client.
         * @return a new token packet. Or null if {@code pseudo} is null.
         */
        public static Packet makeTokenPacket(int token, String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.TOKEN, null, "" + token, pseudo);
        }
    }
    /**
     * Type of the different packets.
     * <ul>
     *     <li> {@link PacketType#ERR} : Error code
     *     <li> {@link PacketType#AUTH} : Authentication
     *     <li> {@link PacketType#GMSG} : General Message
     *     <li> {@link PacketType#DMSG} : Direct Message
     *     <li> {@link PacketType#PC} : Private Connection
     *     <li> {@link PacketType#TOKEN} : Token
     */
    public enum PacketType {ERR, AUTH, GMSG, DMSG, PC, TOKEN}
    /**
     * Possible errors code.
     * <ul>
     *     <li>{@link ErrorCode#AUTH_ERROR} : Authentication error. If the requested pseudo is already taken.
     *     <li>{@link ErrorCode#DEST_ERROR} : Destination error. If the destination is not connected to the server.
     *     <li>{@link ErrorCode#REJECTED} : Private Connection Rejected. If the private connection is rejected by the other client.
     *     <li>{@link ErrorCode#WRONG_CODE} : Wrong Code. If the code ({@link PacketType} or {@link ErrorCode} is invalid.
     *     <li>{@link ErrorCode#INVALID_LENGTH} : Invalid text length. If the length of a text is negative, 0 or bigger than {@link Config#TEXT_SIZE}.
     *     <li>{@link ErrorCode#ERROR_RECOVER} : Recovering of an error. If the client tries to recover from an error he made.
     */
    public enum ErrorCode {AUTH_ERROR, DEST_ERROR, REJECTED, WRONG_CODE, INVALID_LENGTH, ERROR_RECOVER}

    /**
     * Check if {@code length} is not bigger than {@link Config#TEXT_SIZE}.
     * @param length the length to check.
     * @return the same length if correct.
     * @throws IllegalStateException if the length is bigger than {@link Config#TEXT_SIZE}.
     */
    private static int checkLength(int length) {
        if (length > Config.TEXT_SIZE) {
            throw new IllegalStateException("The message cannot be converted because the encoding of a string is bigger than " + Config.TEXT_SIZE);
        }
        return length;
    }
    /**
     * Display the packet in the standard output.
     * @param userName the name of the current user (only used for {@link ErrorCode#AUTH_ERROR})
     */
    public void display(String userName) { // Todo : remove from here (not common between Server and client)
        switch (type) {
            case ERR -> onErrorReceived(code, pseudo == null ? userName : pseudo);
            case AUTH -> onAuthSuccess(pseudo);
            case GMSG -> onGeneralMessageReceived(pseudo, message);
            case DMSG -> onDirectMessageReceived(pseudo, message);
            case PC -> onPrivateConnectionReceived(pseudo);
            case TOKEN -> onTokenReceived(message);
        }
    }

    /**
     * Create a buffer representing the packet that is sent according to the protocol CHATOS.
     *
     * @return a buffer in write-mode containing the message to be send.
     * The returned buffer is a new one for each call.
     * @throws IllegalStateException if any string encoded in {@link java.nio.charset.StandardCharsets#UTF_8} is bigger than {@link Config#TEXT_SIZE}.
     */
    public ByteBuffer toBuffer() {
        return switch (type) {
            case ERR -> errToBuffer();
            case AUTH -> authToBuffer();
            case GMSG -> generalToBuffer();
            case DMSG -> directToBuffer();
            case PC -> privateToBuffer();
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
     * Or the following one if the error is {@link ErrorCode#REJECTED}.
     * <pre>
     *    byte      byte     integer string (utf-8)
     * ---------------------------------------------
     * |   0   | REJECTED  | length |    pseudo    |
     * --------------------------------------------- </pre>
     * @see ErrorCode
     */
    private ByteBuffer errToBuffer() {
        if (code == ErrorCode.REJECTED) {
            return rejectedToBuffer();
        } else {
            return ByteBuffer.allocate(2).put((byte) type.ordinal()).put((byte) code.ordinal());
        }
    }
    /**
     * Create a buffer containing the packet for the error {@link ErrorCode#REJECTED}.
     * <pre>
     *    byte      byte     integer string (utf-8)
     * ---------------------------------------------
     * |   0   | REJECTED  | length |    pseudo    |
     * --------------------------------------------- </pre>
     * @see ErrorCode
     */
    private ByteBuffer rejectedToBuffer() {
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
     * <pre>
     *   byte  integer   string (utf-8)   integer    string (utf-8)
     * -------------------------------------------------------------
     * |  2  | length |     message     | length |     pseudo      |
     * ------------------------------------------------------------- </pre>
     */
    private ByteBuffer generalToBuffer() {
        return directToBuffer(); // Todo : Replace because it's ugly :sick_face:
    }
    /**
     * Create a buffer containing the packet representing a message destined to only one client.
     * In the case of a packet sent by the server, the pseudo represent the source
     * and if the packet has been sent by the client, the pseudo represent the destination.
     *
     * <pre>
     *   byte  integer   string (utf-8)   integer   string (utf-8)
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
     * In the case of a packet sent by the server, the pseudo represent the source
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
     * <pre>
     *    byte   integer   integer string (utf-8)
     * -------------------------------------------
     * |   5   |  token  | length |    pseudo    |
     * ------------------------------------------- </pre>
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
}
