package fr.uge.net.tcp.nonblocking.display;

import fr.uge.net.tcp.nonblocking.packet.Packet;

import static fr.uge.net.tcp.nonblocking.display.AnsiColors.*;
import static java.util.Objects.requireNonNull;


/**
 * Display class (only used by the client).
 */
public final class ClientMessageDisplay {
    /**
     * Displays that the connection to the server is successful.
     */
    public static void onConnectSuccess() {
        System.out.println(color("Connected to the server with success.", fromRGB(50, 250, 0)));
        pseudoAsk();
    }
    /**
     * Displays that the connection to the server has failed.
     */
    public static void onConnectFail() {
        System.out.println(color("Connection to the server rejected!", RED));
    }
    private static void onAuthSuccess(String pseudo) {
        requireNonNull(pseudo);
        System.out.print("Successfully authenticate to the server with the pseudo: ");
        System.out.println(color(pseudo, YELLOW) + ".");
    }
    private static void onAuthFail(String pseudo) {
        requireNonNull(pseudo);
        System.out.println(color("The pseudo : ", RED) + color(pseudo, YELLOW) + color(" is already taken!", RED));
        pseudoAsk();
    }
    private static void pseudoAsk() {
        System.out.print("Enter your pseudo to authentication : ");
        System.out.flush();
    }

    /**
     * Displays a packet received from the server.
     * @param packet the packet to display. Cannot be null.
     * @param userName the pseudo of the client. Cannot be null.
     */
    public static void onMessageReceived(Packet packet, String userName) {
        requireNonNull(packet);
        requireNonNull(userName);
        switch (packet.type()) {
            case ERR -> onErrorReceived(packet.code(), packet.pseudo() == null ? userName : packet.pseudo());
            case AUTH -> onAuthSuccess(packet.pseudo());
            case GMSG -> onGeneralMessageReceived(packet.pseudo(), packet.message());
            case DMSG -> onDirectMessageReceived(packet.pseudo(), packet.message());
            case PC -> onPrivateConnectionReceived(packet.pseudo());
            case TOKEN -> onTokenReceived(packet.message());
        }
    }
    private static void onErrorReceived(Packet.ErrorCode code, String pseudo) {
        requireNonNull(pseudo);
        requireNonNull(code);
        System.out.print(RED);
        switch (code) {
            case AUTH_ERROR -> onAuthFail(pseudo);
            case DEST_ERROR -> System.out.println("Error : Requested user is not connected to the server!");
            case REJECTED -> System.out.println("Error : The connection with " + color(pseudo, MAGENTA) + RED +
                    " has been rejected!");
            case WRONG_CODE -> System.out.println("Error : The server received a packet with an invalid code!");
            case INVALID_LENGTH -> System.out.println("Error : The server received a packet with an invalid length!");
            case ERROR_RECOVER -> System.out.println("Recover on previous error!");
        }
        System.out.print(RESET);
    }
    private static void onGeneralMessageReceived(String pseudo, String message) {
        requireNonNull(message);
        if (pseudo == null) {
            System.out.println(color("<me> ", GREEN) + message);
        } else {
            System.out.println(color("<" + pseudo + "> ", GREEN) + message);
        }
    }
    private static void onDirectMessageReceived(String pseudo, String message) {
        requireNonNull(pseudo);
        requireNonNull(message);
        System.out.println(color("[" + pseudo + "] ", CYAN) + message);
    }
    private static void onPrivateConnectionReceived(String pseudo) {
        requireNonNull(pseudo);
        System.out.print(color(pseudo + " request a private connection. ", fromRGB(255, 150, 30)));
        System.out.println("Accept ? (" + color("y", GREEN) + "/" + color("n", RED) + ")");
    }
    private static void onTokenReceived(String token) {
        System.out.println("The token is : " + color(token, MAGENTA));
    }
}
