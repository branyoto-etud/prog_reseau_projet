package fr.uge.net.tcp.nonblocking.packet;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.utils.ChatOSUtils.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Represents a packet circulating between the server and the clients.
 *
 * A packet is always starting with a {@link PacketType} that defines it's meaning.
 * The other fields can be null if not useful with this type. The following list explains
 * what are the different types and which fields they use.
 * <ul>
 * <li> {@link PacketType#ERR} (code and pseudo) : This packet notifies an incorrect behavior.</li>
 * <li> {@link PacketType#AUTH} (pseudo) : This packet is used by the client to register to the server. The pseudo
 * is the unique identifier for this client.</li>
 * <li> {@link PacketType#GMSG} (message and pseudo) : This packet is a General Message. A message from a client to everyone.</li>
 * <li> {@link PacketType#DMSG} (message and pseudo) : This packet is a Direct Message. A message from a client to another.</li>
 * <li> {@link PacketType#PC} (pseudo) : This packet is Private Connection. It is used to establish a connection between
 * two clients.</li>
 * <li> {@link PacketType#TOKEN} (message, pseudo) : This packet is a special packet used to register to the server as a private
 * connection. The token is an unique identifier sent to the both ends of a private connection.</li>
 * </ul>
 * For more explanations, see Protocol.txt.
 */
public final record Packet(PacketType type, ErrorCode code, String message, String pseudo) {
    /**
     * Utility class made to create {@link Packet} more easily.
     * If one of the parameter is null, the returned packet will be null.
     */
    public final static class PacketFactory {
        /**
         * Creates an error packet with the given error code.
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
         * Creates an error packet with the given error code.
         * @param code the error code.
         * @return a new error packet with the given code or null if {@code code} is null.
         */
        public static Packet makeErrorPacket(ErrorCode code) {
            if (code == null) return null;
            return new Packet(PacketType.ERR, code, null, null);
        }
        /**
         * Creates an error packet with {@link ErrorCode#REJECTED} and the {@code pseudo}.
         * @param pseudo the name of the client that rejects the private connection.
         * @return a new error packet with {@link ErrorCode#REJECTED} and the {@code pseudo}
         * or null if {@code pseudo} is null.
         */
        public static Packet makeRejectedPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.ERR, ErrorCode.REJECTED, null, pseudo);
        }
        /**
         * Creates an authentication packet with the given pseudo.
         * @param pseudo the pseudo of the client.
         * @return a new authentication packet or null if {@code pseudo} is null.
         */
        public static Packet makeAuthenticationPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.AUTH, null, null, pseudo);
        }
        /**
         * Creates a general message packet with the given message from {@code pseudo}.
         * @param message the message to send.
         * @param pseudo the pseudo of the sending client.
         * @return a new general message packet or null if {@code message} or {@code pseudo} is null.
         */
        public static Packet makeGeneralMessagePacket(String message, String pseudo) {
            if (message == null || pseudo == null) return null;
            return new Packet(PacketType.GMSG, null, message, pseudo);
        }
        /**
         * Creates a direct message packet with the given message from/to {@code pseudo}.
         * The meaning of the pseudo depends on the receiver.
         * @param message the message to send.
         * @param pseudo the pseudo of the sending/receiving client.
         * @return a new general message packet or null if {@code message} or {@code pseudo} is null.
         */
        public static Packet makeDirectMessagePacket(String message, String pseudo) {
            if (message == null || pseudo == null) return null;
            return new Packet(PacketType.DMSG, null, message, pseudo);
        }
        /**
         * Creates a private connection packet form/to {@code pseudo}.
         * The meaning of the pseudo depends on the receiver.
         * @param pseudo the pseudo of the sending/receiving client.
         * @return a new private connection packet or null if {@code pseudo} is null.
         */
        public static Packet makePrivateConnectionPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.PC, null, null, pseudo);
        }
        /**
         * Creates a token packet containing an identification key and the pseudo
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
     * Types of the different packets.
     */
    public enum PacketType {
        /** Error code */
        ERR,
        /** Authentication */
        AUTH,
        /** General Message */
        GMSG,
        /** Direct Message */
        DMSG,
        /** Private Connection */
        PC,
        /** Token */
        TOKEN
    }
    /**
     * Possible error codes.
     */
    public enum ErrorCode {
        /** Authentication error. If the requested pseudo is already taken. */
        AUTH_ERROR,
        /** Destination error. If the destination is not connected to the server. */
        DEST_ERROR,
        /** Private Connection Rejected. If the private connection is rejected by the other client. */
        REJECTED,
        /** Wrong Code. If the code ({@link PacketType} or {@link ErrorCode} is invalid. */
        WRONG_CODE,
        /** Invalid text length. If the length of a text is negative, 0 or greater than {@link fr.uge.net.tcp.nonblocking.utils.ChatOSUtils#TEXT_SIZE}. */
        INVALID_LENGTH,
        /** Recovering of an error. If the client tries to recover from an error he made. */
        ERROR_RECOVER
    }

    /**
     * Check if {@code length} is not greater than {@link fr.uge.net.tcp.nonblocking.utils.ChatOSUtils#TEXT_SIZE}.
     * @param length the length to check.
     * @return the same length if correct.
     * @throws IllegalStateException if the length is greater than {@link fr.uge.net.tcp.nonblocking.utils.ChatOSUtils#TEXT_SIZE}.
     */
    private static int checkLength(int length) {
        if (length > TEXT_SIZE) {
            throw new IllegalStateException("The message cannot be converted because the encoding of a string is greater than " + TEXT_SIZE);
        }
        return length;
    }

    /**
     * Creates a buffer representing the packet that is sent according to the protocol CHATOS.
     *
     * @return a buffer in read-mode containing the message to be sent.
     * The returned buffer is a new one for each call.
     * @throws IllegalStateException if any string encoded in {@link java.nio.charset.StandardCharsets#UTF_8} is greater than {@link fr.uge.net.tcp.nonblocking.utils.ChatOSUtils#TEXT_SIZE}.
     */
    public ByteBuffer toBuffer() {
        return (switch (type) {
            case ERR -> errToBuffer();
            case AUTH -> authToBuffer();
            case GMSG -> generalToBuffer();
            case DMSG -> directToBuffer();
            case PC -> privateToBuffer();
            case TOKEN -> tokenToBuffer();
        }).flip();
    }

    /**
     * Creates a buffer containing the packet for an error.
     * <pre>
     *    byte      byte
     * ---------------------
     * |   0   | errorCode |
     * ---------------------
     * </pre>
     * Or the following one if the error is {@link ErrorCode#REJECTED}.
     * <pre>
     *    byte      byte     integer string (utf-8)
     * ---------------------------------------------
     * |   0   | REJECTED  | length |    pseudo    |
     * ---------------------------------------------
     * </pre>
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
     * Creates a buffer containing the packet for the error {@link ErrorCode#REJECTED}.
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
     * Creates a buffer containing the packet for an authentication.
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
     * Creates a buffer containing the packet representing a message destined to everyone.
     * <pre>
     *   byte  integer   string (utf-8)   integer    string (utf-8)
     * -------------------------------------------------------------
     * |  2  | length |     message     | length |     pseudo      |
     * ------------------------------------------------------------- </pre>
     */
    private ByteBuffer generalToBuffer() {
        return twoStringToBuffer();
    }
    /**
     * Creates a buffer containing the packet representing a message destined to only one client.
     * In the case of a packet sent by the server, the pseudo represents the source
     * and if the packet has been sent by the client, the pseudo represents the destination.
     *
     * <pre>
     *   byte  integer   string (utf-8)   integer   string (utf-8)
     * -------------------------------------------------------------
     * |  3  | length |     message     | length |     pseudo      |
     * ------------------------------------------------------------- </pre>
     */
    private ByteBuffer directToBuffer() {
        return twoStringToBuffer();
    }

    /**
     * Creates a buffer containing the packet with a code and two strings.
     *
     * <pre>
     *   byte   integer   string (utf-8)   integer   string (utf-8)
     * -------------------------------------------------------------
     * | code | length |     message     | length |     pseudo      |
     * ------------------------------------------------------------- </pre>
     */
    private ByteBuffer twoStringToBuffer() {
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
     * Creates a buffer containing the packet for a private connection.
     * In the case of a packet sent by the server, the pseudo represents the source
     * and if the packet has been sent by the client, the pseudo represents the destination.
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
     * Creates a buffer containing the token for a private connection.
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
