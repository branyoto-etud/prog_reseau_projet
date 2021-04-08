package fr.uge.net.tcp.nonblocking.display;

import fr.uge.net.tcp.nonblocking.packet.Packet;

import static fr.uge.net.tcp.nonblocking.display.AnsiColors.*;
import static java.util.Objects.requireNonNull;

public class ClientMessageDisplay {
    public static void onConnectSuccess() {
        System.out.println(color("Connected to the server with success.", LIME));
        pseudoAsk();
    }
    public static void onConnectFail() {
        System.out.println(color("Connection to the server rejected!", RED2));
    }
    public static void onAuthSuccess(String pseudo) {
        requireNonNull(pseudo);
        System.out.print("Successfully authenticate to the server with the pseudo: ");
        System.out.println(color(pseudo, YELLOW) + ".");
    }
    private static void onAuthFail(String pseudo) {
        requireNonNull(pseudo);
        System.out.println(color("The pseudo : ", RED2) + color(pseudo, YELLOW) + color(" is already taken!", RED2));
        pseudoAsk();
    }
    private static void pseudoAsk() {
        System.out.print("Enter your pseudo to authentication : ");
        System.out.flush();
    }
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
    public static void onErrorReceived(Packet.ErrorCode code, String pseudo) {
        requireNonNull(pseudo);
        requireNonNull(code);
        System.out.print(RED2);
        switch (code) {
            case AUTH_ERROR -> onAuthFail(pseudo);
            case DEST_ERROR -> System.out.println("Error : Requested user is not connected to the server!");
            case REJECTED -> System.out.println("Error : The connection with " + color(pseudo, MAGENTA) + RED2 +
                    " has been rejected!");
            case WRONG_CODE -> System.out.println("Error : The server received a packet with an invalid code!");
            case INVALID_LENGTH -> System.out.println("Error : The server received a packet with an invalid length!");
            case ERROR_RECOVER -> System.out.println("Recover on previous error!");
        }
        System.out.print(RESET);
    }
    public static void onGeneralMessageReceived(String pseudo, String message) {
        requireNonNull(message);
        if (pseudo == null) {
            System.out.println(color("<me> ", GREEN1) + message);
        } else {
            System.out.println(color("<" + pseudo + "> ", GREEN1) + message);
        }
    }
    public static void onDirectMessageReceived(String pseudo, String message) {
        requireNonNull(pseudo);
        requireNonNull(message);
        System.out.println(color("[" + pseudo + "] ", CYAN) + message);
    }
    public static void onPrivateConnectionReceived(String pseudo) {
        requireNonNull(pseudo);
        System.out.print(color(pseudo + " request a private connection. ", GOLD));
        System.out.println("Accept ? (" + color("y", GREEN1) + "/" + color("n", RED2) + ")");
    }
    public static void onTokenReceived(String token) {
        System.out.println("The token is : " + color(token, MAGENTA));
    }
}
