package dev.jpm.util;

/** A user-facing failure. The message is printed as is, without a stack trace. */
public class JpmException extends RuntimeException {
    private final int exitCode;

    public JpmException(String message) {
        this(message, 1);
    }

    public JpmException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public JpmException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = 1;
    }

    public int exitCode() {
        return exitCode;
    }
}
