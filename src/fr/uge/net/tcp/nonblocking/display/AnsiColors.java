package fr.uge.net.tcp.nonblocking.display;

/**
 * Defines some ANSI colors ready to be used in the project.
 * Also defines methods to color text and to generate colors.
 */
public final class AnsiColors {
    /** Red */
    public static final String RED = fromRGB(200, 64, 64);
    /** Green */
    public static final String GREEN = fromRGB(32, 255, 32);
    /** Magenta */
    public static final String MAGENTA = fromRGB(230, 75, 240);
    /** Cyan */
    public static final String CYAN = fromRGB(0, 200, 200);
    /** Yellow */
    public static final String YELLOW = fromRGB(255, 255, 0);
    /** No color */
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
