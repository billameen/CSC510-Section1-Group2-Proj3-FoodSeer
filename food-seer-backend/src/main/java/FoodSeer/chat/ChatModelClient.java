package FoodSeer.chat;

import java.util.List;

import FoodSeer.exception.ChatUnavailableException;

/**
 * Sends a conversation to a chat model and returns its answer.
 *
 * The production implementation is {@link OllamaChatClient}. Tests and the
 * chat safety eval use their own implementations, so no test needs a running
 * model.
 */
public interface ChatModelClient {

    /**
     * Sends the conversation and returns the model's raw answer.
     *
     * @param conversation
     *            the messages, oldest first, starting with the system message
     * @return the text the model wrote, never null
     * @throws ChatUnavailableException
     *             if the model cannot be reached, times out, or gives no
     *             answer
     */
    String complete(List<ChatTurn> conversation);
}
