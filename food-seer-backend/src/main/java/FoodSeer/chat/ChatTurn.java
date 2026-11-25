package FoodSeer.chat;

/**
 * One message in a conversation with the chat model.
 *
 * @param role
 *            "system", "user" or "assistant"
 * @param content
 *            the message text
 */
public record ChatTurn(String role, String content) {

    /** Role of the instructions FoodSeer gives the model. */
    public static final String SYSTEM = "system";

    /** Role of a message the customer typed. */
    public static final String USER = "user";

    /** Role of a message the model wrote. */
    public static final String ASSISTANT = "assistant";

    /**
     * Creates a system message.
     *
     * @param content
     *            the instructions
     * @return the message
     */
    public static ChatTurn system(final String content) {
        return new ChatTurn(SYSTEM, content);
    }

    /**
     * Creates a customer message.
     *
     * @param content
     *            what the customer typed
     * @return the message
     */
    public static ChatTurn user(final String content) {
        return new ChatTurn(USER, content);
    }

    /**
     * Creates a model message.
     *
     * @param content
     *            what the model said
     * @return the message
     */
    public static ChatTurn assistant(final String content) {
        return new ChatTurn(ASSISTANT, content);
    }
}
