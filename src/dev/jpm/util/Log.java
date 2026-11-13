package dev.jpm.util;

/** Console output. Status lines go to stderr so stdout stays clean for program output. */
public final class Log {
    private Log() {}

    public static boolean verbose = System.getenv("JPM_VERBOSE") != null;
    private static final boolean COLOR = System.console() != null && System.getenv("NO_COLOR") == null;

    private static String c(String code, String s) {
        return COLOR ? "\u001b[" + code + "m" + s + "\u001b[0m" : s;
    }

    public static void info(String msg) {
        System.err.println(msg);
    }

    public static void step(String verb, String msg) {
        System.err.println(c("1;32", String.format("%12s", verb)) + " " + msg);
    }

    public static void warn(String msg) {
        System.err.println(c("1;33", "warning") + ": " + msg);
    }

    public static void error(String msg) {
        System.err.println(c("1;31", "error") + ": " + msg);
    }

    public static void debug(String msg) {
        if (verbose) System.err.println(c("2", "  " + msg));
    }

    public static String bold(String s) {
        return c("1", s);
    }

    public static String dim(String s) {
        return c("2", s);
    }
}
