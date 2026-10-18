package io.github.immichuploader;

/**
 * A user-facing failure. The CLI prints the message without a stack trace.
 */
public class UserError extends RuntimeException {
    public UserError(String message) {
        super(message);
    }

    public UserError(String message, Throwable cause) {
        super(message, cause);
    }
}
