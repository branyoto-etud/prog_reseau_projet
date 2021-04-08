package fr.uge.net.tcp.nonblocking.display;

import fr.uge.net.tcp.nonblocking.packet.Packet;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import static fr.uge.net.tcp.nonblocking.display.AnsiColors.*;
import static fr.uge.net.tcp.nonblocking.packet.Packet.ErrorCode.ERROR_RECOVER;

public class ServerMessageDisplay {
    public static void onPacketReceived(Packet p, String pseudo) {
        switch (p.type()) {
            case ERR -> {
                switch (p.code()) {
                    case AUTH_ERROR, DEST_ERROR -> onBadError(p.code());
                    case WRONG_CODE, INVALID_LENGTH -> onServerInternalError(pseudo);
                    case ERROR_RECOVER -> onBadRecoverError(pseudo);
                    case REJECTED -> onRejectError(p.pseudo(), pseudo);
                }
            }
            case AUTH -> onBadAuthPacket(p.pseudo());
            case GMSG -> onGMSGPacket(p.message(), pseudo);
            case DMSG -> onDMSGPacket(p.message(), p.pseudo(), pseudo);
            case PC -> onPCPacket(p.pseudo(), pseudo);
            case TOKEN -> onBadTokenPacket(pseudo);
        }
    }

    private static String me(String pseudo) {
        return color(pseudo, CYAN);
    }
    private static String other(String other) {
        return color(other, PURPLE);
    }
    private static String message(String msg) {
        return color(msg, YELLOW1);
    }
    private static String sc(SocketChannel sc) {
        try {
            return color(sc.getRemoteAddress().toString(), YELLOW);
        } catch (IOException e){
            return color("???", YELLOW);
        }
    }

    public static void onAuthPacket(SocketChannel sc, String other) {
        System.out.println(sc(sc) + " trying to authenticate with the pseudo " + other(other));
    }
    public static void onBadTokenPacket(String pseudo) {
        System.out.println(color("Received a TOKEN packet from " + me(pseudo), RED1) +
                           color(" who doesn't represent private connection", RED1));
    }
    public static void onTokenPacket(SocketChannel sc, int token) {
        System.out.println("Received a TOKEN packet from " + sc(sc) + " with content " +
                message("" + token) + " authentication success.");
    }
    public static void onPCPacket(String other, String pseudo) {
        System.out.println("A connection between " + me(pseudo) + " and " + other(other) + " has been received from " + me(pseudo));
    }
    public static void onDMSGPacket(String msg, String other, String pseudo) {
        System.out.println(me(pseudo) + " sending a direct message containing " + message(msg) + " to " + other(other));
    }
    public static void onGMSGPacket(String msg, String pseudo) {
        System.out.println(me(pseudo) + " sent " + message(msg) + " to everyone");
    }
    public static void onBadAuthPacket(String pseudo) {
        System.out.println(color("Received an AUTH packet from ", RED1) + me(pseudo) +
                color(" but already identified with the pseudo: ", RED1) + other(pseudo));
    }
    public static void onRejectError(String other, String pseudo) {
        System.out.println(me(pseudo) + color(" reject private connection from ", RED1) + other(other));
    }
    public static void onBadRecoverError(String pseudo) {
        System.out.println(color("Received a RECOVER packet from ", RED1) + me(pseudo) +
                color(" while not being rejecting!", RED1));
    }
    public static void onServerInternalError(String pseudo) {
        System.out.println(color("Error : Server sent an invalid packet to ", RED1) + me(pseudo));
    }
    public static void onBadError(Packet.ErrorCode code) {
        System.out.println(color("Receive unwanted error packet: ", RED1) +
                color(code.toString(), MAGENTA));
    }
    public static void onErrorProcessed(String pseudo) {
        System.out.println(color("Stops accepting data from ",RED) + me(pseudo) +
                color(" until reception of ERROR_RECOVER!", RED));
    }
    public static void onRecover(String pseudo) {
        System.out.println(me(pseudo) + color(" recovers from previous error!",GREEN));
    }
}
