package fr.uge.net.tcp.nonblocking.display;

public class AnsiColors {
    public static final String RED = fromRGB(255, 0, 0);
    public static final String RED1 = fromRGB(255, 32, 32);
    public static final String RED2 = fromRGB(255, 64, 64);
    public static final String RED3 = fromRGB(255, 96, 96);
    public static final String RED4 = fromRGB(255, 128, 128);
    public static final String GREEN = fromRGB(0, 255, 0);
    public static final String GREEN1 = fromRGB(32, 255, 32);
    public static final String GREEN2 = fromRGB(64, 255, 64);
    public static final String GREEN3 = fromRGB(96, 255, 96);
    public static final String GREEN4 = fromRGB(128, 255, 128);
    public static final String BLUE = fromRGB(0, 0, 255);
    public static final String BLUE1 = fromRGB(32, 32, 255);
    public static final String BLUE2 = fromRGB(64, 64, 255);
    public static final String BLUE3 = fromRGB(96, 96, 255);
    public static final String BLUE4 = fromRGB(128, 128, 255);

    public static final String LIME = fromRGB(50, 250, 0);
    public static final String PURPLE = fromRGB(150, 50, 255);

    public static final String CYAN = fromRGB(0, 255, 255);
    public static final String MAGENTA = fromRGB(255, 0, 255);
    public static final String YELLOW = fromRGB(255, 255, 0);
    public static final String GOLD = fromRGB(255, 150, 30);

    public static final String RESET = "\u001B[0m";

    public static String fromRGB(int r, int g, int b) {
        return "\u001B[38;2;" + r + ";" + g + ";" + b + "m";
    }
    public static String color(String msg, String ansi_color) {
        return ansi_color + msg + RESET;
    }
}
