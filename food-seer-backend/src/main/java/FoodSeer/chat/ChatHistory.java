package FoodSeer.chat;

import java.util.ArrayList;
import java.util.List;

import FoodSeer.dto.ChatTurnDto;

/**
 * Picks which earlier messages to send to the model.
 *
 * The chat page sends its whole conversation; this keeps only the most recent
 * turns, cuts very long ones, and drops anything that is not a plain user or
 * assistant message. In particular a client cannot slip in its own "system"
 * message, because only FoodSeer writes those.
 */
public final class ChatHistory {

    /** Default number of earlier messages to send (3 questions and 3 answers). */
    public static final int DEFAULT_MAX_TURNS = 6;

    /** Longest earlier message sent, in characters; the rest is cut off. */
    public static final int MAX_CHARS_PER_TURN = 1000;

    private ChatHistory() {
        // utility class
    }

    /**
     * Keeps the most recent usable turns.
     *
     * @param history
     *            the conversation as the page sent it, oldest first; may be null
     * @param maxTurns
     *            how many turns to keep; 0 or less keeps none
     * @return at most {@code maxTurns} user and assistant turns, oldest first
     */
    public static List<ChatTurn> recent(final List<ChatTurnDto> history, final int maxTurns) {
        if (history == null || maxTurns <= 0) {
            return List.of();
        }
        final List<ChatTurn> usable = new ArrayList<>();
        for (final ChatTurnDto turn : history) {
            if (turn == null || turn.getRole() == null || turn.getContent() == null) {
                continue;
            }
            final String role = turn.getRole().trim().toLowerCase(java.util.Locale.ROOT);
            if (!ChatTurn.USER.equals(role) && !ChatTurn.ASSISTANT.equals(role)) {
                continue;
            }
            String content = turn.getContent().trim();
            if (content.isEmpty()) {
                continue;
            }
            if (content.length() > MAX_CHARS_PER_TURN) {
                content = content.substring(0, MAX_CHARS_PER_TURN);
            }
            usable.add(new ChatTurn(role, content));
        }
        final int from = Math.max(0, usable.size() - maxTurns);
        return List.copyOf(usable.subList(from, usable.size()));
    }
}
