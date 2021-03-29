package fr.uge.net.tcp.nonblocking.client;

import static java.util.Objects.requireNonNull;

public class ClientMessageDisplay {
    private static final String ANSI_BRIGHT_RED = "\u001B[91m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_RED = "\u001B[31m";

    public static void onConnectSuccess() {
        System.out.println("Connected to the server with success.");
        pseudoAsk();
    }
    public static void onConnectFail() {
        System.out.println("Connected to the server rejected!");
    }
    public static void onAuthSuccess(String pseudo) {
        requireNonNull(pseudo);
        System.out.print("Successfully authenticate to the server with the pseudo: ");
        System.out.println(ANSI_YELLOW + pseudo + ANSI_RESET + ".");
    }
    private static void onAuthFail(String pseudo) {
        requireNonNull(pseudo);
        System.out.println("The pseudo : " + ANSI_YELLOW + pseudo + ANSI_BRIGHT_RED + " is already taken!");
        pseudoAsk();
    }
    private static void pseudoAsk() {
        System.out.println("Enter your pseudo to authentication : ");
    }
    public static void onGeneralMessageReceived(String pseudo, String message) {
        requireNonNull(message);
        if (pseudo == null) {
            System.out.println(ANSI_GREEN + "<me>" + ANSI_RESET + message);
        } else {
            System.out.println(ANSI_GREEN + "<" + pseudo + ">" + ANSI_RESET + message);
        }
    }
    public static void onDirectMessageReceived(String pseudo, String message) {
        requireNonNull(pseudo);
        requireNonNull(message);
        System.out.println(ANSI_CYAN + "[" + pseudo + "]" + ANSI_RESET + message);
    }
    public static void onPrivateConnectionReceived(String pseudo) {
        requireNonNull(pseudo);
        System.out.print(ANSI_RED + pseudo + " request a private connection." + ANSI_RESET);
        System.out.println("Accept ? (" + ANSI_GREEN + "o" + ANSI_RESET + "/" + ANSI_BRIGHT_RED + "n" + ANSI_RESET + ")");
    }
}
