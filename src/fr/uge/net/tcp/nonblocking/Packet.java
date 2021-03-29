package fr.uge.net.tcp.nonblocking;

import java.nio.ByteBuffer;

import static fr.uge.net.tcp.nonblocking.client.ClientMessageDisplay.*;
import static java.nio.charset.StandardCharsets.UTF_8;

public final record Packet(PacketType type, ErrorCode code, String message, String pseudo) {
    public Packet {
        throw new IllegalStateException("Cannot create a Packet using constructor! Use PacketBuilder instead.");
    }
    public static class PacketBuilder {
        public static Packet makeErrorPacket(byte code) {
            if (code < 0 || code >= ErrorCode.values().length) return null;
            return new Packet(PacketType.ERR, ErrorCode.values()[code], null, null);
        }
        public static Packet makeAuthenticationPacket(String pseudo) {
            return new Packet(PacketType.AUTH, null, null, pseudo);
        }
        public static Packet makeGeneralMessagePacket(String message) {
            if (message == null) return null;
            return new Packet(PacketType.GMSG, null, message, null);
        }
        public static Packet makeGeneralMessagePacket(String message, String pseudo) {
            if (message == null || pseudo == null) return null;
            return new Packet(PacketType.GMSG, null, message, pseudo);
        }
        public static Packet makeDirectMessagePacket(String message, String pseudo) {
            if (message == null || pseudo == null) return null;
            return new Packet(PacketType.DMSG, null, message, pseudo);
        }
        public static Packet makePrivateConnectionPacket(String pseudo) {
            if (pseudo == null) return null;
            return new Packet(PacketType.CP, null, null, pseudo);
        }
        public static Packet makeTokenPacket(int token) {
            return new Packet(PacketType.TOKEN, null, "" + token, null);
        }
    }
    public enum PacketType {ERR, AUTH, GMSG, DMSG, CP, TOKEN}
    public enum ErrorCode {AUTH_ERROR, DEST_ERROR, REJECTED, WRONG_CODE, INVALID_LENGTH, ERROR_RECOVER, OTHER}

    private static int checkLength(int length) {
        if (length > Config.TEXT_SIZE) {
            throw new IllegalStateException("The message cannot be converted because the encoding of a string is bigger than " + Config.TEXT_SIZE);
        }
        return length;
    }

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
    private ByteBuffer errToBuffer() {
        return ByteBuffer.allocate(2).put((byte) type.ordinal()).put((byte) code.ordinal());
    }
    private ByteBuffer authToBuffer() {
        var pseudoBuff = UTF_8.encode(pseudo);
        var length = checkLength(pseudoBuff.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + length)
                .put((byte) type.ordinal())
                .putInt(length)
                .put(pseudoBuff);
    }
    private ByteBuffer generalToBuffer() {
        if (pseudo != null) return directToBuffer(); // When the server send the GMSG

        var messageBuffer = UTF_8.encode(message);
        var length = checkLength(messageBuffer.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + length)
                .put((byte) type.ordinal())
                .putInt(length)
                .put(messageBuffer);
    }
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
    private ByteBuffer privateToBuffer() {
        var destBuffer = UTF_8.encode(pseudo);
        var length = checkLength(destBuffer.remaining());
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + length)
                .put((byte) type.ordinal())
                .putInt(length)
                .put(destBuffer);
    }
    private ByteBuffer tokenToBuffer() {
        return ByteBuffer.allocate(Byte.BYTES + Integer.BYTES)
                .put((byte) PacketType.TOKEN.ordinal())
                .putInt(Integer.parseInt(message));
    }
    public void display() {
        switch (type) {
            case ERR -> onErrorReceived(code, pseudo);
            case AUTH -> onAuthSuccess(pseudo);
            case GMSG -> onGeneralMessageReceived(pseudo, message);
            case DMSG -> onDirectMessageReceived(pseudo, message);
            case CP -> onPrivateConnectionReceived(pseudo);
            case TOKEN -> System.out.println("The token is : " + message);
        }
    }
}
