package fr.uge.net.tcp.nonblocking.display;

public class AnsiColors {
    public static final String RED2 = fromRGB(200, 64, 64);
    public static final String GREEN = fromRGB(0, 255, 0);
    public static final String GREEN1 = fromRGB(32, 255, 32);

    public static final String LIME = fromRGB(50, 250, 0);
    public static final String PURPLE = fromRGB(150, 50, 175);
    public static final String MAGENTA = fromRGB(230, 75, 240);

    public static final String CYAN = fromRGB(0, 200, 200);
    public static final String YELLOW = fromRGB(255, 255, 0);
    public static final String YELLOW1 = fromRGB(255, 255, 128);
    public static final String GOLD = fromRGB(255, 150, 30);

    public static final String RESET = "\u001B[0m";

    /**
     * Converts color in (r,g,b) to an ANSI Color.
     * @param r red value.
     * @param g green value.
     * @param b blue value..
     * @return the escape value tha represent the requested color.
     */
    public static String fromRGB(int r, int g, int b) {
        return "\u001B[38;2;" + r + ";" + g + ";" + b + "m";
    }

    /**
     * Colors the {@code message} with the given {@code ansi_color}.
     *
     * @param message the message to color.
     * @param ansi_color the text color.
     * @return the message colored.
     */
    public static String color(String message, String ansi_color) {
        return ansi_color + message + RESET;
    }
}
