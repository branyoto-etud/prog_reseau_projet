package fr.uge.net.tcp.nonblocking.client;

import fr.uge.net.tcp.nonblocking.Packet;

import static java.util.Objects.requireNonNull;

public class ClientMessageDisplay {
    private static final String ANSI_DARK_RED = "\u001B[38:5:88m";
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_LIME = "\u001B[38:5:40m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_YELLOW = "\u001B[93m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";

    public static void onConnectSuccess() {
        System.out.println(ANSI_LIME + "Connected to the server with success." + ANSI_RESET);
        pseudoAsk();
    }
    public static void onConnectFail() {
        System.out.println(ANSI_BRIGHT_RED + "Connection to the server rejected!" + ANSI_RESET);
    }
    public static void onAuthSuccess(String pseudo) {
        requireNonNull(pseudo);
        System.out.print("Successfully authenticate to the server with the pseudo: ");
        System.out.println(ANSI_YELLOW + pseudo + ANSI_RESET + ".");
    }
    private static void onAuthFail(String pseudo) {
        requireNonNull(pseudo);
        System.out.println("The pseudo : " + ANSI_YELLOW + pseudo + ANSI_DARK_RED + " is already taken!" + ANSI_RESET);
        pseudoAsk();
    }
    private static void pseudoAsk() {
        System.out.print("Enter your pseudo to authentication : ");
        System.out.flush();
    }
    public static void onMessageReceived(Packet packet,String userName) {
        requireNonNull(packet);
        packet.display(userName);
    }
    public static void onErrorReceived(Packet.ErrorCode code, String pseudo) {
        requireNonNull(pseudo);
        requireNonNull(code);
        System.out.print(ANSI_DARK_RED);
        switch (code) {
            case AUTH_ERROR -> onAuthFail(pseudo);
            case DEST_ERROR -> System.out.println("Error : Requested user is not connected to the server!");
            case REJECTED -> System.out.println("Error : The connection with " + ANSI_MAGENTA + pseudo + ANSI_DARK_RED + " has been rejected!");
            case WRONG_CODE -> System.out.println("Error : The server received a packet with an invalid code!");
            case INVALID_LENGTH -> System.out.println("Error : The server received a packet with an invalid length!");
            case ERROR_RECOVER -> System.out.println("Recover on previous error!");
            case OTHER -> System.out.println("Unknown Error! Should not happen!");
        }
        System.out.print(ANSI_RESET);
    }
    public static void onGeneralMessageReceived(String pseudo, String message) {
        requireNonNull(message);
        if (pseudo == null) {
            System.out.println(ANSI_GREEN + "<me> " + ANSI_RESET + message);
        } else {
            System.out.println(ANSI_GREEN + "<" + pseudo + "> " + ANSI_RESET + message);
        }
    }
    public static void onDirectMessageReceived(String pseudo, String message) {
        requireNonNull(pseudo);
        requireNonNull(message);
        System.out.println(ANSI_CYAN + "[" + pseudo + "] " + ANSI_RESET + message);
    }
    public static void onPrivateConnectionReceived(String pseudo) {
        requireNonNull(pseudo);
        System.out.print(ANSI_BRIGHT_RED + pseudo + " request a private connection. " + ANSI_RESET);
        System.out.println("Accept ? (" + ANSI_GREEN + "y" + ANSI_RESET + "/" + ANSI_DARK_RED + "n" + ANSI_RESET + ")");
    }
    public static void onTokenReceived(String token) {
        System.out.println("The token is : " + ANSI_MAGENTA + token + ANSI_RESET);
    }
}
