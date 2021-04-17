package fr.uge.net.tcp.nonblocking.display;

import fr.uge.net.tcp.nonblocking.packet.Packet;

import java.io.IOException;
import java.nio.channels.SocketChannel;

import static fr.uge.net.tcp.nonblocking.display.AnsiColors.*;
import static java.util.Objects.requireNonNull;

/**
 * Display class (only used by the server).
 */
public final class ServerMessageDisplay {
    /**
     * Displays a paquet received by the sever.
     * @param p the paquet to display. Cannot be null.
     * @param pseudo the pseudo of the sender. Cannot be null.
     */
    public static void onPacketReceived(Packet p, String pseudo) {
        requireNonNull(p);
        requireNonNull(pseudo);
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
        return color(other, fromRGB(150, 50, 175));
    }
    private static String message(String msg) {
        return color(msg, fromRGB(255, 255, 128));
    }
    private static String sc(SocketChannel sc) {
        try {
            return color(sc.getRemoteAddress().toString(), YELLOW);
        } catch (IOException e){
            return color("???", YELLOW);
        }
    }

    /**
     * Displays an attempt to authenticate with a pseudo.
     * @param sc the socket that tries to authenticate. Cannot be null.
     * @param pseudo the pseudo he wanted to have. Cannot be null.
     */
    public static void onAuthPacket(SocketChannel sc, String pseudo) {
        requireNonNull(sc);
        requireNonNull(pseudo);
        System.out.println(sc(sc) + " trying to authenticate with the pseudo " + other(pseudo));
    }
    private static void onBadTokenPacket(String pseudo) {
        System.out.println(color("Received a TOKEN packet from " + me(pseudo), RED) +
                           color(" who doesn't represent private connection", RED));
    }

    /**
     * Displays a TOKEN packet received from a client not already authenticated.
     * @param sc the socket of the sender. Cannot be null.
     * @param token the token he identifies with.
     */
    public static void onTokenPacket(SocketChannel sc, int token) {
        requireNonNull(sc);
        System.out.println("Received a TOKEN packet from " + sc(sc) + " with content " +
                message("" + token) + ", authentication success.");
    }
    private static void onPCPacket(String other, String pseudo) {
        System.out.println("A connection between " + me(pseudo) + " and " + other(other) + " has been received from " + me(pseudo));
    }
    private static void onDMSGPacket(String msg, String other, String pseudo) {
        System.out.println(me(pseudo) + " sending a direct message containing " + message(msg) + " to " + other(other));
    }
    private static void onGMSGPacket(String msg, String pseudo) {
        System.out.println(me(pseudo) + " sent " + message(msg) + " to everyone");
    }
    private static void onBadAuthPacket(String pseudo) {
        System.out.println(color("Received an AUTH packet from ", RED) + me(pseudo) +
                color(" but already identified with the pseudo: ", RED) + other(pseudo));
    }
    private static void onRejectError(String other, String pseudo) {
        System.out.println(me(pseudo) + color(" reject private connection from ", RED) + other(other));
    }
    private static void onBadRecoverError(String pseudo) {
        System.out.println(color("Received a RECOVER packet from ", RED) + me(pseudo) +
                color(" while not being rejecting!", RED));
    }
    private static void onServerInternalError(String pseudo) {
        System.out.println(color("Error : Server sent an invalid packet to ", RED) + me(pseudo));
    }
    private static void onBadError(Packet.ErrorCode code) {
        System.out.println(color("Receive unwanted error packet: ", RED) +
                color(code.toString(), MAGENTA));
    }

    /**
     * Displays the interruption of the reading for this client until reception of ERROR_RECOVER.
     * @param pseudo the client that has sent an error. Cannot be null.
     */
    public static void onErrorProcessed(String pseudo) {
        requireNonNull(pseudo);
        System.out.println(color("Stops accepting data from ", RED) + me(pseudo) +
                color(" until reception of ERROR_RECOVER!", RED));
    }

    /**
     * Displays the restart of the reading process.
     * @param pseudo the client that recovers. Cannot be null.
     */
    public static void onRecover(String pseudo) {
        requireNonNull(pseudo);
        System.out.println(me(pseudo) + color(" recovers from previous error!",fromRGB(0, 255, 0)));
    }
}
