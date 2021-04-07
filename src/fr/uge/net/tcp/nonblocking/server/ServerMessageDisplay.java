package fr.uge.net.tcp.nonblocking.server;

import fr.uge.net.tcp.nonblocking.Packet;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import static fr.uge.net.tcp.nonblocking.Packet.ErrorCode.ERROR_RECOVER;

public record ServerMessageDisplay(String pseudo) {
    private static final String ANSI_PALE_YELLOW = "\u001B[38:5:214m";
    private static final String ANSI_DARK_RED = "\u001B[38:5:88m";
    private static final String ANSI_PURPLE = "\u001B[38:5:90m";
    private static final String ANSI_GREEN = "\u001B[38:5:28m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_YELLOW = "\u001B[93m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";

    public void onPacketReceived(Packet p, boolean rejecting) {
        if (rejecting) {
            if (p.code() == ERROR_RECOVER) onRecover();
            return;
        }
        switch (p.type()) {
            case ERR -> {
                switch (p.code()) {
                    case AUTH_ERROR, DEST_ERROR -> onBadError(p.code());
                    case WRONG_CODE, INVALID_LENGTH -> onServerInternalError();
                    case ERROR_RECOVER -> onBadRecoverError();
                    case REJECTED -> onRejectError(p.pseudo());
                }
            }
            case AUTH -> onBadAuthPacket(p.pseudo());
            case GMSG -> onGMSGPacket(p.message());
            case DMSG -> onDMSGPacket(p.message(), p.pseudo());
            case PC -> onPCPacket(p.pseudo());
            case TOKEN -> onBadTokenPacket();
        }
    }

    private String me() {
        return ANSI_CYAN + pseudo + ANSI_RESET;
    }
    private static String other(String other) {
        return ANSI_PURPLE + other + ANSI_RESET;
    }
    private static String message(String msg) {
        return ANSI_PALE_YELLOW + msg + ANSI_RESET;
    }
    private static String sc(SocketChannel sc) {
        try {
            return ANSI_YELLOW + sc.getRemoteAddress().toString() + ANSI_RESET;
        } catch (IOException e){
            return ANSI_YELLOW + "???" + ANSI_RESET;
        }
    }

    public void onAuthPacket(SocketChannel sc, String other) {
        System.out.println(sc(sc) + " trying to authenticate with the pseudo " + other(other));
    }
    public void onBadTokenPacket() {
        System.out.println(ANSI_BRIGHT_RED + "Received a TOKEN packet from " + me() +
                ANSI_BRIGHT_RED + " who doesn't represent private connection" + ANSI_RESET);
    }
    public void onTokenPacket(SocketChannel sc, int token) {
        System.out.println("Received a TOKEN packet from " + sc(sc) + " with content " +
                message("" + token) + " authentication success.");
    }
    public void onPCPacket(String other) {
        System.out.println("A connection between " + me() + " and " + other(other) + " has been received from " + me());
    }
    public void onDMSGPacket(String msg, String other) {
        System.out.println(me() + " sending a direct message containing " + message(msg) + " to " + other(other));
    }
    public void onGMSGPacket(String msg) {
        System.out.println(me() + " sent " + message(msg) + " to everyone");
    }
    public void onBadAuthPacket(String pseudo) {
        System.out.println(ANSI_BRIGHT_RED + "Received an AUTH packet from " + me() + ANSI_BRIGHT_RED +
                " but already identified with the pseudo: " + other(pseudo));
    }
    public void onRejectError(String other) {
        System.out.println(me() + ANSI_BRIGHT_RED + " reject private connection from " + other(other));
    }
    public void onBadRecoverError() {
        System.out.println(ANSI_BRIGHT_RED + "Received a RECOVER packet from " + me() +
                ANSI_BRIGHT_RED + " while not being rejecting!" + ANSI_RESET);
    }
    public void onServerInternalError() {
        System.out.println(ANSI_DARK_RED + "Error : Server sent an invalid packet to " + me());
    }
    public void onBadError(Packet.ErrorCode code) {
        System.out.println(ANSI_BRIGHT_RED + "Receive unwanted error packet: " +
                ANSI_MAGENTA + code + ANSI_RESET);
    }
    public void onErrorProcessed() {
        System.out.println(ANSI_DARK_RED + "Stops accepting data from " + me() +
                ANSI_DARK_RED + " until reception of ERROR_RECOVER!" + ANSI_RESET);
    }
    public void onRecover() {
        System.out.println(me() + ANSI_GREEN + " recovers from previous error!" + ANSI_RESET);
    }

}
