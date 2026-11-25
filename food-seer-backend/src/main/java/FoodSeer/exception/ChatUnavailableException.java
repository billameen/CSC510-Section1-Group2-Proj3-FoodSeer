package FoodSeer.exception;

/**
 * Thrown when the chat model cannot answer: it is not running, it timed out,
 * the configured model is not installed, or it sent back no answer.
 *
 * {@link GlobalExceptionHandler} turns this into 503 Service Unavailable, so
 * a client can tell "the assistant is down" apart from a real answer.
 */
public class ChatUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message
     *            a message that is safe to show to the user
     */
    public ChatUnavailableException(final String message) {
        super(message);
    }

    /**
     * Creates the exception with its cause.
     *
     * @param message
     *            a message that is safe to show to the user
     * @param cause
     *            the underlying error, kept for the logs
     */
    public ChatUnavailableException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
